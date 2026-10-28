package com.example.mirror

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private val ADDRESS = Regex("""[\w.-]+:\d{1,5}""")

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: EditText
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
        status = TextView(this).apply { textSize = 18f }
        address = EditText(this).apply {
            hint = "IP:port laptop, contoh 192.168.1.8:5555"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("pc", ""))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(48, insets.systemWindowInsetTop + 48, 48, 48)
                insets
            }
        }
        root.addView(status)
        root.addView(address)
        root.addView(button("Scan QR di laptop") { scan() })
        root.addView(button("Sambung") { connect() })
        root.addView(button("Stop") { stopService(Intent(this, StreamService::class.java)) })
        if (control) root.addView(button("Izin kontrol (Accessibility)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        setContentView(root)
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

    override fun onResume() {
        super.onResume()
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        status.removeCallbacks(tick)
    }

    private fun connect() {
        val pc = address.text.toString().trim()
        if (!ADDRESS.matches(pc)) {
            address.error = "Format IP:port, contoh 192.168.1.8:5555"
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

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        status.text = buildString {
            appendLine("Layar + mic: ${StreamService.status}")
            appendLine(if (mic) "● Mic diizinkan" else "○ Mic belum diizinkan, hanya layar yang dikirim")
            if (control) appendLine(if (ControlService.instance != null) "● Kontrol aktif" else "○ Kontrol mati: aktifkan \"Mirror control\" di Accessibility")
            appendLine()
            appendLine("Scan kode QR di jendela Mirror di laptop, atau ketik IP:port dari judul jendelanya:")
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}
