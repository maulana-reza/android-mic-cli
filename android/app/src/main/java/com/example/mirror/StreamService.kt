package com.example.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.widget.Toast
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "mirror"
private const val MAX_SIZE = 1600 // longest video side; lower it if the encoder fails or Wi-Fi lags
private const val BIT_RATE = 8_000_000
private const val SAMPLE_RATE = 44100 // mic as mono 16-bit PCM, the rate android-mic-cli opens its output with

// Same length as AndroidMic's handshake, so android-mic-cli tells the two apps apart on one port.
private val HELLO = "MirrorHello".toByteArray()
private val READY = "MirrorReady".toByteArray()

/**
 * Sends screen and mic to android-mic-cli on the PC over one TCP connection, and replays the input it sends back.
 * Frames both ways: 4-byte big-endian length + payload. Phone to PC: [type][data], type 0 = H.264 delta,
 * 1 = H.264 key frame with SPS/PPS, 2 = mic PCM, or no samples to stay connected while nothing else goes out.
 * PC to phone: JSON for ControlService, or {"t":"sync"}.
 */
class StreamService : Service() {
    private lateinit var projection: MediaProjection
    private var display: VirtualDisplay? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var pc: DataOutputStream? = null
    @Volatile private var displayChanged = false
    @Volatile private var restart = false // a key frame was asked for
    @Volatile private var sentAt = 0L // the last frame to the PC, uptime ms
    private var mic = false // RECORD_AUDIO granted when the stream started
    private val main = Handler(Looper.getMainLooper())
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { displayChanged = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopSelf()
            MIC -> if (running) setMic(intent.getBooleanExtra("on", micOn)) else stopSelf()
        }
        if (running || intent?.getStringExtra("pc") == null) return START_NOT_STICKY
        address = intent.getStringExtra("pc")!!
        mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        connected = false
        since = System.currentTimeMillis()
        goForeground() // Android 14+: must happen before getMediaProjection
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")!!
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra("code", 0), data)!!
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, main)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        running = true
        worker("encoder", ::encode)
        worker("link", ::link)
        if (mic) startMic()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        connected = false
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        runCatching { socket?.close() }
    }

    /** The app or the notification turned the mic on or off. */
    private fun setMic(on: Boolean) {
        micOn = on
        // Allowed only after the stream started: the mic joins now, while the app is in front for the permission.
        if (on && !mic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            mic = true
            runCatching { goForeground() }.onFailure { Log.w(TAG, "microphone service type", it) }
            startMic()
        }
        showState()
    }

    // The mic is optional: if it fails, the screen keeps going.
    private fun startMic() = thread(name = "mic") { runCatching { record() }.onFailure { Log.e(TAG, "mic", it) } }

    private fun setConnected(now: Boolean) {
        if (connected == now) return
        connected = now
        since = System.currentTimeMillis()
        if (running) showState()
    }

    private fun showState() = getSystemService(NotificationManager::class.java).notify(1, notification())

    private fun goForeground() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(TAG, "Mirror", NotificationManager.IMPORTANCE_LOW))
        // With the mic type even while the mic is off, so turning it on later is still allowed in the background.
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            if (mic && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification(), type) else startForeground(1, notification())
    }

    /** Where the stream goes and what it sends, with Mic and Stop for while another app is in front. */
    private fun notification(): Notification {
        val sending = when {
            !mic -> "Layar dikirim, mic tidak diizinkan"
            micOn -> "Layar dan mic dikirim ke laptop"
            else -> "Layar dikirim, mic mati"
        }
        return Notification.Builder(this, TAG)
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle(if (connected) "Tersambung ke ${address.substringBeforeLast(':')}" else "Menyambung ke $address")
            .setContentText(sending)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (mic) addAction(action(1, if (micOn) "Matikan mic" else "Nyalakan mic",
                    Intent(this@StreamService, StreamService::class.java).setAction(MIC).putExtra("on", !micOn)))
            }
            .addAction(action(2, "Stop", Intent(this, StreamService::class.java).setAction(STOP)))
            .build()
    }

    private fun action(code: Int, label: String, intent: Intent) = Notification.Action.Builder(
        Icon.createWithResource(this, R.drawable.ic_mirror), label,
        PendingIntent.getService(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    ).build()

    private fun worker(name: String, body: () -> Unit) = thread(name = name) {
        try {
            body()
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, name, e)
                main.post { Toast.makeText(this, "$name: ${e.message}", Toast.LENGTH_LONG).show() }
                stopSelf()
            }
        }
    }

    /** Keeps one connection to android-mic-cli, reconnecting until the service stops. */
    private fun link() {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toInt()
        while (running) {
            try {
                Socket().use { s ->
                    socket = s
                    s.connect(InetSocketAddress(host, port), 3000)
                    s.tcpNoDelay = true
                    val input = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream().buffered(1 shl 16))
                    out.write(HELLO)
                    out.flush()
                    if (!ByteArray(READY.size).also { input.readFully(it) }.contentEquals(READY)) {
                        throw IOException("$address is not android-mic-cli")
                    }
                    pc = out
                    setConnected(true)
                    requestKeyFrame()
                    while (true) {
                        val n = input.readInt()
                        if (n !in 0..1_000_000) throw IOException("bad frame length $n")
                        val msg = ByteArray(n).also { input.readFully(it) }.decodeToString()
                        runCatching {
                            val json = JSONObject(msg)
                            if (json.optString("t") == "sync") requestKeyFrame() else ControlService.instance?.handle(json)
                        }.onFailure { Log.w(TAG, "bad input: $msg", it) }
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "link", e)
            } finally {
                pc = null
                setConnected(false)
            }
            if (running) Thread.sleep(1000)
        }
    }

    /** One frame to the PC, dropped while not connected. */
    private fun send(type: Int, vararg parts: ByteArray) {
        val out = pc ?: return
        try {
            synchronized(out) { // encoder and mic share the connection
                out.writeInt(1 + parts.sumOf { it.size })
                out.write(type)
                parts.forEach { out.write(it) }
                out.flush()
                sentAt = SystemClock.uptimeMillis()
            }
        } catch (e: IOException) {
            runCatching { socket?.close() } // link() reconnects
        }
    }

    // The encoder only gets frames while the screen changes, so a key frame requested on a still screen never came.
    // A new encoder starts with one.
    private fun requestKeyFrame() {
        restart = true
    }

    @SuppressLint("MissingPermission") // only started with RECORD_AUDIO granted
    private fun record() {
        val buffer = ByteArray(SAMPLE_RATE / 50 * 2) // 20 ms of 16-bit mono
        while (running) {
            if (!micOn) { // turned off in the app: the mic is let go, so Android's mic indicator goes out too
                Thread.sleep(100)
                continue
            }
            // Half a second of buffer rides out a key frame holding the connection.
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE)
            )
            try {
                recorder.startRecording()
                while (running && micOn) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) send(2, buffer.copyOf(n))
                }
            } finally {
                recorder.release()
            }
        }
    }

    private fun encode() {
        val dpi = resources.displayMetrics.densityDpi
        try {
            while (running) {
                val size = videoSize()
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.x, size.y).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                    // Once the screen stops changing the last frame is encoded a few more times, so it sharpens on the PC.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                }
                val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = c.createInputSurface()
                val vd = display
                if (vd == null) {
                    display = projection.createVirtualDisplay(
                        "mirror", size.x, size.y, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
                    )
                } else {
                    // Android 14+ allows one VirtualDisplay per projection, so resize it on rotation.
                    vd.resize(size.x, size.y, dpi)
                    vd.surface = surface
                }
                c.start()
                codec = c
                drain(c, size)
                codec = null
                display?.surface = null
                c.stop()
                c.release()
                surface.release()
            }
        } finally {
            display?.release()
            projection.stop()
        }
    }

    /** Sends encoded frames until the service stops or the screen size changes. */
    private fun drain(c: MediaCodec, size: Point) {
        val info = MediaCodec.BufferInfo()
        var config = ByteArray(0)
        while (running) {
            if (displayChanged) {
                displayChanged = false
                if (videoSize() != size) return
            }
            if (restart) {
                restart = false
                return
            }
            val i = c.dequeueOutputBuffer(info, 100_000)
            if (i < 0) {
                // A still screen with the mic off sends nothing, and the PC gives up on a phone silent for 5 s.
                if (SystemClock.uptimeMillis() - sentAt >= 1000) send(2)
                continue
            }
            val data = ByteArray(info.size).also { c.getOutputBuffer(i)!!.get(it) }
            c.releaseOutputBuffer(i, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                config = data
                continue
            }
            // Key frames carry SPS/PPS so the page can start decoding at any of them.
            if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) send(1, config, data) else send(0, data)
        }
    }

    private fun videoSize(): Point {
        val screen = screenSize()
        val scale = minOf(1f, MAX_SIZE.toFloat() / maxOf(screen.x, screen.y))
        // Encoders want dimensions in multiples of 8.
        return Point((screen.x * scale).toInt() / 8 * 8, (screen.y * scale).toInt() / 8 * 8)
    }

    companion object {
        const val MIC = "mic" // intent action, extra "on": the app or the notification turned the mic on or off
        const val STOP = "stop" // intent action: Stop in the notification
        @Volatile var running = false
        @Volatile var connected = false
        @Volatile var since = 0L // when the current connecting or connected stretch began
        @Volatile var address = "" // the laptop, IP:port
        @Volatile var micOn = true // off: only the screen goes to the PC
    }
}

@Suppress("DEPRECATION")
fun Context.screenSize() = Point().also {
    getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it)
}
