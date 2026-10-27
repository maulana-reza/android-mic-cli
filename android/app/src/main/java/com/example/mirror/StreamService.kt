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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * 1 = H.264 key frame with SPS/PPS, 2 = mic PCM. PC to phone: JSON for ControlService, or {"t":"sync"}.
 */
class StreamService : Service() {
    private lateinit var projection: MediaProjection
    private var display: VirtualDisplay? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var pc: DataOutputStream? = null
    @Volatile private var displayChanged = false
    private val main = Handler(Looper.getMainLooper())
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { displayChanged = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running || intent == null) return START_NOT_STICKY
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        goForeground(mic) // Android 14+: must happen before getMediaProjection
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")!!
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra("code", 0), data)!!
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, main)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        running = true
        val address = intent.getStringExtra("pc")!!
        worker("encoder", ::encode)
        worker("link") { link(address) }
        // The mic is optional: if it fails, the screen keeps going.
        if (mic) thread(name = "mic") { runCatching { record() }.onFailure { Log.e(TAG, "mic", it) } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        status = "mati"
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        runCatching { socket?.close() }
    }

    private fun goForeground(mic: Boolean) {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(TAG, "Mirror", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, TAG)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Mirror aktif")
            .setContentText("Layar dan mic dikirim ke PC")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            if (mic && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, type) else startForeground(1, notification)
    }

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
    private fun link(address: String) {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toInt()
        while (running) {
            status = "menyambung ke $address"
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
                    status = "tersambung ke $address"
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
            }
        } catch (e: IOException) {
            runCatching { socket?.close() } // link() reconnects
        }
    }

    private fun requestKeyFrame() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    @SuppressLint("MissingPermission") // only started with RECORD_AUDIO granted
    private fun record() {
        val chunk = SAMPLE_RATE / 50 * 2 // 20 ms of 16-bit mono
        // Half a second of buffer rides out a key frame holding the connection.
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE)
        )
        try {
            recorder.startRecording()
            val buffer = ByteArray(chunk)
            while (running) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n > 0) send(2, buffer.copyOf(n))
            }
        } finally {
            recorder.release()
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
                    // Re-encode the last frame on a static screen, so requested key frames still go out.
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
            val i = c.dequeueOutputBuffer(info, 100_000)
            if (i < 0) continue
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
        @Volatile var running = false
        @Volatile var status = "mati"
    }
}

@Suppress("DEPRECATION")
fun Context.screenSize() = Point().also {
    getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it)
}
