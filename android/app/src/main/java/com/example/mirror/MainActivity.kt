package com.example.mirror

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private val ADDRESS = Regex("""[\w.-]+:\d{1,5}""")

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var start: Button // Scan QR while stopped, Stop while streaming
    private lateinit var typed: LinearLayout // the address field and Sambung, hidden while streaming
    private lateinit var address: EditText
    private lateinit var allowed: TextView
    private lateinit var allowControl: Button
    private val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val prefs by lazy { getSharedPreferences("mirror", MODE_PRIVATE) }

    // Only the control flavor declares the accessibility service, see src/control/AndroidManifest.xml.
    @Suppress("DEPRECATION")
    private val control by lazy {
        runCatching { packageManager.getServiceInfo(ComponentName(this, ControlService::class.java), 0) }.isSuccess
    }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            status.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dot.setSize(dp(10), dp(10))
        status = text(18f).apply {
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x1A808080) } // grey on light and dark
            setPadding(dp(16), dp(14), dp(16), dp(14))
            compoundDrawablePadding = dp(12)
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        }
        start = button("", android.R.style.Widget_Material_Button_Colored) {
            if (StreamService.running) stopService(Intent(this, StreamService::class.java)) else scan()
        }
        address = EditText(this).apply {
            hint = "atau ketik IP:port laptop"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("pc", ""))
        }
        typed = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(address, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Sambung") { connect() })
        }
        val mic = Switch(this).apply {
            text = "Kirim suara mic"
            textSize = 16f
            isChecked = StreamService.micOn
            setOnCheckedChangeListener { _, on -> setMic(on) }
        }
        allowed = text(14f)
        allowControl = button("Aktifkan kontrol dari laptop") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(28f, "Mirror").apply { typeface = Typeface.DEFAULT_BOLD })
            addView(text(14f, "Layar dan mic HP ke laptop").apply { alpha = 0.7f })
            addView(status, spaced(24))
            addView(start, spaced(16))
            addView(typed, spaced(4))
            addView(mic, spaced(24))
            addView(allowed, spaced(16))
            addView(allowControl, spaced(8))
            addView(text(13f, "Di laptop, buka Mirror. Jendelanya menampilkan kode QR, dan judulnya berisi alamat untuk diketik.")
                .apply { alpha = 0.7f }, spaced(32))
        }
        setContentView(ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(dp(24), insets.systemWindowInsetTop + dp(24), dp(24), insets.systemWindowInsetBottom + dp(24))
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
        status.removeCallbacks(tick)
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
        if (StreamService.running) {
            Toast.makeText(this, "Sudah jalan. Stop dulu untuk ganti alamat.", Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString("pc", pc).apply()
        // Without the mic permission only the screen is sent.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestCapture()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        requestCapture()
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

    private fun setMic(on: Boolean) {
        StreamService.micOn = on
        // A running stream says in its notification whether the mic goes along.
        if (StreamService.running) startService(Intent(this, StreamService::class.java).setAction(StreamService.MIC))
    }

    private fun refresh() {
        val running = StreamService.running
        status.text = if (running) StreamService.status.replaceFirstChar { it.uppercase() } else "Belum tersambung"
        dot.setColor(Color.parseColor(if (!running) "#9AA0A6" else if (StreamService.status.startsWith("tersambung")) "#1E8E3E" else "#F29900"))
        start.text = if (running) "Stop" else "Scan QR di laptop"
        typed.visibility = if (running) View.GONE else View.VISIBLE
        val micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val controlOn = ControlService.instance != null
        allowed.text = buildString {
            append(if (micAllowed) "● Izin mic sudah diberikan" else "○ Izin mic diminta saat menyambung")
            if (control) append(if (controlOn) "\n● Kontrol dari laptop aktif" else "\n○ Kontrol dari laptop belum aktif")
        }
        allowControl.visibility = if (control && !controlOn) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun spaced(top: Int) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun text(size: Float, value: String = "") = TextView(this).apply {
        textSize = size
        text = value
    }

    // style 0: the theme's own button. A style with defStyleAttr 0, since the theme's buttonStyle would win otherwise.
    private fun button(label: String, style: Int = 0, onClick: () -> Unit) =
        (if (style == 0) Button(this) else Button(this, null, 0, style)).apply {
            text = label
            setOnClickListener { onClick() }
        }
}
