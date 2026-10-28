package com.example.mirror

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.format.DateFormat
import android.text.style.BulletSpan
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.Date

private val ADDRESS = Regex("""[\w.-]+:\d{1,5}""")
private const val MATCH = LayoutParams.MATCH_PARENT
private const val WRAP = LayoutParams.WRAP_CONTENT
private const val CONNECT_PERMISSIONS = 2
private const val MIC_PERMISSION = 3
private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

// Clickable layouts, announced as buttons by TalkBack.
private object AsButton : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = Button::class.java.name
    }
}

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("mirror", MODE_PRIVATE) }

    // Only the control flavor declares the accessibility service, see src/control/AndroidManifest.xml.
    @Suppress("DEPRECATION")
    private val control by lazy {
        runCatching { packageManager.getServiceInfo(ComponentName(this, ControlService::class.java), 0) }.isSuccess
    }

    private lateinit var link: LinkView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var steps: View
    private lateinit var help: View
    private lateinit var chips: LinearLayout
    private lateinit var chipRow: View
    private lateinit var controlCard: View
    private lateinit var address: EditText
    private lateinit var cancel: View
    private lateinit var controls: View
    private lateinit var mic: RoundButton
    private lateinit var micSwitch: Switch
    private lateinit var tip: View
    private lateinit var whileStopped: List<View>
    private lateinit var unlessLive: List<View>
    private val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var shownChips = ""
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            headline.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            addView(FrameLayout(context).apply {
                background = box(R.color.accent_container, 10)
                addView(icon(R.drawable.ic_mirror, R.color.on_accent_container), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, LayoutParams(dp(32), dp(32)))
            addView(text(20f, R.color.text, "Mirror", medium = true), LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        }

        // The status card: what's connected, and what to do next
        dot.setSize(dp(10), dp(10))
        link = LinkView(this)
        headline = text(24f, R.color.text, medium = true).apply {
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE // read out when it changes
        }
        detail = text(15f, R.color.muted).apply { setPaddingRelative(dp(20), dp(4), 0, 0) }
        steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf("Buka Mirror di laptop", "Scan kode QR di jendelanya", "Izinkan rekam layar di HP").forEachIndexed { i, step ->
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(13f, R.color.text, "${i + 1}", medium = true).apply {
                        gravity = Gravity.CENTER
                        background = box(R.color.surface_high, 13)
                    }, LayoutParams(dp(26), dp(26)))
                    addView(text(15f, R.color.text, step), LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
                }, lp(top = if (i == 0) 0 else 10))
            }
        }
        help = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(R.color.surface_high, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(14f, R.color.text, "Belum tersambung juga? Cek:", medium = true))
            for (check in listOf("Mirror di laptop sudah terbuka", "HP dan laptop di Wi-Fi atau hotspot yang sama",
                "Jaringan itu disetel Private di Windows, bukan Public", "Alamatnya sama dengan judul jendela Mirror")) {
                val bullet = SpannableString(check).apply { setSpan(BulletSpan(dp(10), getColor(R.color.muted)), 0, length, 0) }
                addView(text(14f, R.color.muted, bullet), lp(top = 6))
            }
        }
        chips = LinearLayout(this)
        chipRow = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(R.color.surface, 24)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(link, lp(height = dp(62)))
            addView(headline, lp(top = 18))
            addView(detail)
            addView(steps, lp(top = 18))
            addView(help, lp(top = 18))
            addView(chipRow, lp(top = 18))
        }

        controlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(R.color.surface, 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(LinearLayout(context).apply {
                addView(FrameLayout(context).apply {
                    background = oval(R.color.wait_container, 40)
                    addView(icon(R.drawable.ic_cursor, R.color.wait), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                }, LayoutParams(dp(40), dp(40)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(16f, R.color.text, "Kontrol dari laptop belum aktif", medium = true))
                    addView(text(14f, R.color.muted, "Aktifkan Mirror control di Aksesibilitas, supaya klik dan ketikan di laptop sampai ke HP."), lp(top = 4))
                }, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            })
            addView(pill("Aktifkan", R.color.accent, R.color.on_accent, height = 40) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END; topMargin = dp(12) })
        }

        // Not streaming: scan, or type the address
        val scan = pill("Scan QR di laptop", R.color.accent, R.color.on_accent, icon = R.drawable.ic_qr) { scan() }
        address = EditText(this).apply {
            id = View.generateViewId()
            setText(prefs.getString("pc", ""))
            hint = "192.168.1.8:54345"
            textSize = 16f
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.muted))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> connect(); true }
            background = box(R.color.ground, 14, stroke = R.color.outline)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val manual = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(13f, R.color.muted, "Atau ketik alamat dari judul jendela Mirror").apply { labelFor = address.id })
            addView(LinearLayout(context).apply {
                addView(address, LayoutParams(0, dp(48), 1f))
                addView(pill("Sambung", R.color.surface_high, R.color.text, height = 48, radius = 14) { connect() },
                    LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            }, lp(top = 8))
        }
        cancel = pill("Batal", 0, R.color.text, outline = R.color.outline) { stop() }

        // Streaming: the mic and Stop, big
        mic = RoundButton { micTapped() }
        val stop = RoundButton { stop() }
        stop.show(R.color.stop, android.R.color.white, R.drawable.ic_stop, "Stop", "Stop mengirim layar dan mic")
        controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(mic.view)
            addView(stop.view, LayoutParams(WRAP, WRAP).apply { marginStart = dp(56) })
        }
        micSwitch = Switch(this).apply {
            thumbDrawable = switchThumb()
            trackDrawable = switchTrack()
            thumbTintList = null
            trackTintList = null
            switchMinWidth = dp(52)
            minHeight = dp(48)
            showText = false
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Kirim suara mic"
            isChecked = StreamService.micOn
            setOnCheckedChangeListener { _, on -> if (on != StreamService.micOn) setMic(on) }
        }
        val micRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(16f, R.color.text, "Kirim suara mic"))
                addView(text(13f, R.color.muted, "Bisa diubah kapan saja, juga dari notifikasi"), lp(top = 2))
            }, LayoutParams(0, WRAP, 1f))
            addView(micSwitch, LayoutParams(WRAP, WRAP).apply { marginStart = dp(16) })
        }
        val divider = View(this).apply { setBackgroundColor(getColor(R.color.surface_high)) }
        val spacer = View(this)
        tip = text(13f, R.color.muted, "Boleh tinggalkan app ini. Mic dan Stop juga ada di notifikasi.").apply {
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelative(tinted(R.drawable.ic_info, R.color.muted, 18), null, null, null)
        }
        whileStopped = listOf(scan, manual)
        unlessLive = listOf(spacer, divider, micRow)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
            addView(header)
            addView(hero, lp(top = 20))
            addView(controlCard, lp(top = 20))
            addView(scan, lp(top = 20))
            addView(manual, lp(top = 20))
            addView(cancel, lp(top = 20))
            addView(controls, lp(top = 20, weight = 1f)) // centred in what's left of the screen
            addView(spacer, lp(weight = 1f)) // keeps the mic switch at the bottom
            addView(divider, lp(top = 20, height = dp(1)))
            addView(micRow)
            addView(tip, lp(top = 20))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(column)
            // Android 15+ draws apps under the status and navigation bars; older ones lay them out below.
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        headline.removeCallbacks(tick)
    }

    // The laptop's Mirror window shows mirror://IP:port as a QR code until a phone connects.
    private fun scan() {
        GmsBarcodeScanning.getClient(this).startScan()
            .addOnSuccessListener {
                val pc = it.rawValue.orEmpty().removePrefix("mirror://")
                if (ADDRESS.matches(pc)) {
                    address.setText(pc)
                    connect()
                } else Toast.makeText(this, "Itu bukan kode QR Mirror", Toast.LENGTH_SHORT).show()
            }
            // The first scan can fail while Google Play services is still downloading the scanner.
            .addOnFailureListener { Toast.makeText(this, "Scanner belum siap, coba lagi sebentar: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun connect() {
        val pc = address.text.toString().trim()
        if (!ADDRESS.matches(pc)) {
            address.error = "Format IP:port, contoh 192.168.1.8:54345"
            return
        }
        if (StreamService.running) return
        prefs.edit().putString("pc", pc).apply()
        // The mic, and on Android 13+ the notification with Mic and Stop. Without them only the screen goes.
        val missing = listOfNotNull(Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null).filterNot(::granted)
        if (missing.isEmpty()) requestCapture() else requestPermissions(missing.toTypedArray(), CONNECT_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == CONNECT_PERMISSIONS) return requestCapture() // granted or not: without them only the screen goes
        if (granted(Manifest.permission.RECORD_AUDIO)) setMic(true)
        else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) { // "don't ask again": only Settings can
            Toast.makeText(this, "Izinkan Mikrofon di Izin aplikasi", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Whole screen only: a single-app capture would break touch mapping.
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        startActivityForResult(intent, 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) return
        startForegroundService(
            Intent(this, StreamService::class.java)
                .putExtra("code", resultCode).putExtra("data", data).putExtra("pc", prefs.getString("pc", ""))
        )
    }

    private fun stop() {
        stopService(Intent(this, StreamService::class.java))
    }

    private fun micTapped() {
        if (granted(Manifest.permission.RECORD_AUDIO)) setMic(!StreamService.micOn)
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
    }

    private fun setMic(on: Boolean) {
        StreamService.micOn = on
        if (StreamService.running) startService(Intent(this, StreamService::class.java).setAction(StreamService.MIC).putExtra("on", on))
        refresh()
    }

    private fun refresh() {
        val running = StreamService.running
        val live = running && StreamService.connected
        val connecting = running && !live
        val micAllowed = granted(Manifest.permission.RECORD_AUDIO)
        val controlOn = ControlService.instance != null

        // Only what changed: redrawing the same screen every tick would keep the encoder sending it, and TalkBack reading it.
        link.state = if (live) LinkView.LIVE else if (connecting) LinkView.CONNECTING else LinkView.IDLE
        val color = getColor(if (live) R.color.ok else if (connecting) R.color.wait else R.color.idle)
        if (dot.color?.defaultColor != color) dot.setColor(color)
        headline.update(if (live) "Tersambung" else if (connecting) "Menyambung…" else "Belum tersambung")
        detail.update(if (live) "ke laptop ${StreamService.address.substringBeforeLast(':')} · sejak " +
            DateFormat.getTimeFormat(this).format(Date(StreamService.since)) else "ke ${StreamService.address}")
        show(detail, running)
        show(steps, !running)
        show(help, connecting && System.currentTimeMillis() - StreamService.since > 5000)
        show(chipRow, live)
        show(controlCard, control && !controlOn && !connecting)
        whileStopped.forEach { show(it, !running) }
        show(cancel, connecting)
        show(controls, live)
        unlessLive.forEach { show(it, !live) }
        show(tip, live && getSystemService(NotificationManager::class.java).areNotificationsEnabled())

        val now = "$micAllowed ${StreamService.micOn} ${control && controlOn}"
        if (now != shownChips) {
            shownChips = now
            chips.removeAllViews()
            chips.addView(chip("Layar", true))
            chips.addView(chip("Mic", micAllowed && StreamService.micOn), LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            if (control && controlOn) chips.addView(chip("Kontrol", true), LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        when {
            !micAllowed -> mic.show(R.color.surface_high, R.color.muted, R.drawable.ic_mic_off, "Izinkan mic", "Mic tidak diizinkan. Ketuk untuk mengizinkan")
            StreamService.micOn -> mic.show(R.color.accent_container, R.color.on_accent_container, R.drawable.ic_mic, "Mic nyala", "Mic nyala. Ketuk untuk mematikan")
            else -> mic.show(R.color.surface_high, R.color.muted, R.drawable.ic_mic_off, "Mic mati", "Mic mati. Ketuk untuk menyalakan")
        }
        if (micSwitch.isChecked != StreamService.micOn) micSwitch.isChecked = StreamService.micOn
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun show(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun TextView.update(value: String) {
        if (text.toString() != value) text = value
    }

    /** A big round button with its label underneath, like mute and hang up on a phone call. */
    private inner class RoundButton(onClick: () -> Unit) {
        private val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val icon = ImageView(this@MainActivity)
        private val label = text(14f, R.color.text, medium = true)
        private var shown = ""
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            accessibilityDelegate = AsButton
            setOnClickListener { onClick() }
            addView(FrameLayout(context).apply {
                background = pressable(circle, oval(R.color.text, 76))
                isDuplicateParentStateEnabled = true // a press anywhere on the button ripples the circle
                addView(icon, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            }, LayoutParams(dp(76), dp(76)))
            addView(label, LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
        }

        fun show(fill: Int, ink: Int, iconRes: Int, text: String, description: String) {
            if (text == shown) return
            shown = text
            circle.setColor(getColor(fill))
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(getColor(ink))
            label.text = text
            view.contentDescription = description
        }
    }

    /** A button: its label, and an icon if any, centred together. */
    private fun pill(label: String, fill: Int, ink: Int, icon: Int = 0, height: Int = 56, radius: Int = height / 2, outline: Int = 0,
                     onClick: () -> Unit) = FrameLayout(this).apply {
        background = pressable(box(fill, radius, outline), box(R.color.text, radius))
        minimumHeight = dp(height)
        setPadding(dp(20), 0, dp(20), 0)
        accessibilityDelegate = AsButton
        setOnClickListener { onClick() }
        addView(text(if (height >= 56) 16f else 14f, ink, label, medium = true).apply {
            if (icon != 0) {
                compoundDrawablePadding = dp(10)
                setCompoundDrawablesRelative(tinted(icon, ink, 22), null, null, null)
            }
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    private fun chip(label: String, on: Boolean) = text(13f, if (on) R.color.text else R.color.muted, label, medium = true).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(32)
        setPaddingRelative(dp(10), 0, dp(12), 0)
        compoundDrawablePadding = dp(6)
        background = if (on) box(R.color.surface_high, 16) else box(0, 16, stroke = R.color.line)
        val mark = if (on) R.drawable.ic_check else R.drawable.ic_mic_off
        setCompoundDrawablesRelative(tinted(mark, if (on) R.color.ok else R.color.muted, 16), null, null, null)
        contentDescription = if (on) "$label aktif" else "$label mati"
    }

    // Material 3's switch from plain drawables: a 52 x 32 dp track, the dot grows from 16 to 24 dp when on.
    // Switch slides a thumb half the track wide; 2 dp padding each side lets a 30 dp drawable put each dot where Material has it.
    private fun switchThumb() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), thumb(R.color.on_accent, 24, left = 0))
        addState(intArrayOf(), thumb(R.color.idle, 16, left = 10))
    }

    private fun thumb(color: Int, size: Int, left: Int) = LayerDrawable(arrayOf(oval(color, size))).apply {
        setLayerInset(0, dp(left), dp((32 - size) / 2), dp(30 - left - size), dp((32 - size) / 2))
        setPadding(dp(2), 0, dp(2), 0)
    }

    private fun switchTrack() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_checked), box(R.color.accent, 16).apply { setSize(dp(52), dp(32)) })
        addState(intArrayOf(), box(R.color.surface_high, 16).apply { setStroke(dp(2), getColor(R.color.idle)); setSize(dp(52), dp(32)) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0, height: Int = WRAP, weight: Float = 0f) = LayoutParams(MATCH, height, weight).apply { topMargin = dp(top) }

    private fun text(size: Float, color: Int, value: CharSequence = "", medium: Boolean = false) = TextView(this).apply {
        textSize = size
        setTextColor(getColor(color))
        text = value
        if (medium) typeface = MEDIUM
    }

    private fun icon(res: Int, color: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun tinted(res: Int, color: Int, size: Int): Drawable = getDrawable(res)!!.mutate().apply {
        setTint(getColor(color))
        setBounds(0, 0, dp(size), dp(size))
    }

    // Colours are resource ids; 0 = none.
    private fun box(fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(if (fill == 0) Color.TRANSPARENT else getColor(fill))
        if (stroke != 0) setStroke(dp(1), getColor(stroke))
    }

    private fun oval(fill: Int, size: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(getColor(fill))
        setSize(dp(size), dp(size))
    }

    // The mask keeps the ripple inside the shape, also for an outline-only one.
    private fun pressable(content: Drawable, mask: Drawable) = RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), content, mask)
}
