package com.danzku.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var tint: CheckBox

    private val ui = Handler(Looper.getMainLooper())
    private var testing = false

    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val heading = TextView(this).apply {
            text = "DanzOverlay - tahap 3"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }

        val nativeInfo = TextView(this).apply {
            text = "Native: " + try {
                NativeBridge.version()
            } catch (t: Throwable) {
                "GAGAL (${t.message})"
            }
        }

        status = TextView(this)
        log = TextView(this).apply { typeface = Typeface.MONOSPACE }
        tint = CheckBox(this).apply { text = "Mode tint (uji, biru tipis)" }

        val btnRoot = Button(this).apply {
            text = "Tes root (su -c id)"
            setOnClickListener { runRoot("id") }
        }

        val btnGrant = Button(this).apply {
            text = "Beri izin via root"
            setOnClickListener {
                runRoot(
                    "appops set $packageName SYSTEM_ALERT_WINDOW allow; " +
                        "pm grant $packageName android.permission.POST_NOTIFICATIONS"
                )
            }
        }

        val btnStart = Button(this).apply {
            text = "Mulai overlay"
            setOnClickListener {
                if (startOverlay(tint.isChecked)) log.text = "Overlay dimulai."
            }
        }

        val btnStop = Button(this).apply {
            text = "Hentikan overlay"
            setOnClickListener {
                stopOverlay()
                log.text = "Overlay dihentikan."
            }
        }

        val btnCapStart = Button(this).apply {
            text = "Mulai capture (tahap 3)"
            setOnClickListener {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
            }
        }

        val btnCapStop = Button(this).apply {
            text = "Hentikan capture"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                log.text = "Capture dihentikan."
            }
        }

        val btnTest = Button(this).apply {
            text = "Uji: overlay ikut tertangkap?"
            setOnClickListener { runFeedbackTest() }
        }

        root.addView(heading)
        root.addView(nativeInfo)
        root.addView(status)
        root.addView(btnRoot)
        root.addView(btnGrant)
        root.addView(tint)
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(btnCapStart)
        root.addView(btnCapStop)
        root.addView(btnTest)
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
            )
            log.text = "Capture dimulai."
        } else {
            log.text = "Izin capture ditolak."
        }
    }

    private fun startOverlay(tintOn: Boolean): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            log.text = "Izin overlay belum aktif. Tekan 'Beri izin via root' dulu."
            return false
        }
        startForegroundService(
            Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_TINT, tintOn)
        )
        return true
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    /**
     * Nyalakan overlay tint saat capture jalan, lalu bandingkan rata-rata warna layar.
     * Kalau berubah, overlay ikut tertangkap dan renderer tahap 4 butuh strategi anti-feedback.
     */
    private fun runFeedbackTest() {
        if (testing) return
        if (!CaptureStats.running) {
            log.text = "Capture belum jalan. Tekan 'Mulai capture' dulu."
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            log.text = "Izin overlay belum aktif. Tekan 'Beri izin via root' dulu."
            return
        }
        testing = true
        log.text = "Uji 1/2: baseline (overlay mati)..."
        stopOverlay()
        ui.postDelayed({
            val base = intArrayOf(CaptureStats.avgR, CaptureStats.avgG, CaptureStats.avgB)
            val f0 = CaptureStats.frames
            log.text = "Uji 2/2: overlay tint dinyalakan..."
            startOverlay(true)
            ui.postDelayed({
                val after = intArrayOf(CaptureStats.avgR, CaptureStats.avgG, CaptureStats.avgB)
                val newFrames = CaptureStats.frames - f0
                stopOverlay()
                testing = false

                log.text = when {
                    base[0] < 0 || after[0] < 0 || newFrames <= 0 ->
                        "Uji tidak valid: tidak ada frame baru setelah overlay menyala."
                    else -> {
                        val diff = abs(base[0] - after[0]) + abs(base[1] - after[1]) +
                            abs(base[2] - after[2])
                        val verdict = if (diff >= 15) {
                            "OVERLAY IKUT TERTANGKAP (feedback loop nyata)"
                        } else {
                            "Overlay TIDAK tertangkap"
                        }
                        "$verdict\nRGB tanpa overlay: ${base.joinToString()}\n" +
                            "RGB dengan overlay: ${after.joinToString()}\nselisih=$diff, frame baru=$newFrames"
                    }
                }
            }, 2000)
        }, 1500)
    }

    private fun refreshStatus() {
        val canDraw = Settings.canDrawOverlays(this)
        val sb = StringBuilder()
        sb.append("Izin overlay: ").append(if (canDraw) "AKTIF" else "belum")
        sb.append("\nOverlay: ").append(if (OverlayService.running) "BERJALAN" else "mati")
        if (CaptureStats.running) {
            val ago = SystemClock.elapsedRealtime() - CaptureStats.lastFrameAt
            sb.append("\nCapture: BERJALAN ")
                .append(CaptureStats.width).append("x").append(CaptureStats.height)
                .append(", ").append("%.1f".format(CaptureStats.fps)).append(" fps")
                .append("\nFrame: ").append(CaptureStats.frames)
                .append(", terakhir ").append(if (CaptureStats.lastFrameAt == 0L) "-" else "${ago} ms lalu")
                .append("\nRata-rata RGB: ")
                .append(CaptureStats.avgR).append(", ")
                .append(CaptureStats.avgG).append(", ")
                .append(CaptureStats.avgB)
        } else {
            sb.append("\nCapture: mati")
        }
        CaptureStats.lastError?.let { sb.append("\nError capture: ").append(it) }
        status.text = sb.toString()
    }

    private fun runRoot(cmd: String) {
        log.text = "Menjalankan..."
        Thread {
            val r = RootShell.run(cmd)
            runOnUiThread {
                log.text = r
                refreshStatus()
            }
        }.start()
    }

    companion object {
        private const val REQ_CAPTURE = 1001
    }
}
