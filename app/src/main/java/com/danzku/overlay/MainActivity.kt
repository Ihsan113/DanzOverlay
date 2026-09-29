package com.danzku.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var tint: CheckBox

    private lateinit var pkgInput: EditText

    private val ui = Handler(Looper.getMainLooper())
    private var testing = false
    private val scales = floatArrayOf(1f, 0.75f, 0.5f)
    private var scaleIdx = 0

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
            text = "DanzOverlay - tahap 4a"
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

        val btnPillOn = Button(this).apply {
            text = "Tampilkan pill FPS"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    log.text = "Izin overlay belum aktif. Tekan 'Beri izin via root' dulu."
                } else {
                    startForegroundService(Intent(this@MainActivity, PillService::class.java))
                    log.text = if (CaptureStats.running) {
                        "Pill FPS ditampilkan."
                    } else {
                        "Pill ditampilkan. Mulai capture agar FPS terisi."
                    }
                }
            }
        }

        val btnPillOff = Button(this).apply {
            text = "Sembunyikan pill FPS"
            setOnClickListener {
                stopService(Intent(this@MainActivity, PillService::class.java))
                log.text = "Pill FPS disembunyikan."
            }
        }

        pkgInput = EditText(this).apply {
            setSingleLine()
            hint = "nama paket app/game"
            setText("com.android.settings")
        }

        val btnScale = Button(this).apply {
            text = "Skala render VD: 100%"
            setOnClickListener {
                scaleIdx = (scaleIdx + 1) % scales.size
                text = "Skala render VD: ${(scales[scaleIdx] * 100).toInt()}%"
            }
        }

        val btnVdLand = Button(this).apply {
            text = "Tahap 4a: viewer display virtual (landscape)"
            setOnClickListener { openViewer(false) }
        }

        val btnVdPort = Button(this).apply {
            text = "Tahap 4a: viewer display virtual (portrait)"
            setOnClickListener { openViewer(true) }
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
        root.addView(btnPillOn)
        root.addView(btnPillOff)
        root.addView(pkgInput)
        root.addView(btnScale)
        root.addView(btnVdLand)
        root.addView(btnVdPort)
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

    private fun openViewer(portrait: Boolean) {
        val pkg = pkgInput.text.toString().trim()
        if (!Regex("[A-Za-z0-9._]+").matches(pkg)) {
            log.text = "Nama paket tidak valid (hanya huruf, angka, titik, garis bawah)."
            return
        }
        startActivity(
            Intent(this, VdActivity::class.java)
                .putExtra(VdActivity.EXTRA_PKG, pkg)
                .putExtra(VdActivity.EXTRA_SCALE, scales[scaleIdx])
                .putExtra(VdActivity.EXTRA_PORTRAIT, portrait)
        )
    }

    private fun startOverlay(tintOn: Boolean, mark: Boolean = false): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            log.text = "Izin overlay belum aktif. Tekan 'Beri izin via root' dulu."
            return false
        }
        startForegroundService(
            Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_TINT, tintOn)
                .putExtra(OverlayService.EXTRA_MARK, mark)
        )
        return true
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    /**
     * Uji anti-salah: tampilkan blok magenta solid lewat overlay, lalu hitung piksel magenta di frame capture.
     * Tidak terpengaruh ukuran atau letterbox capture. Kalau ada, overlay ikut tertangkap
     * dan renderer tahap 4 butuh strategi anti-feedback.
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
        CaptureStats.markPixels = 0
        CaptureStats.scanMark = true
        ui.postDelayed({
            val baseMark = CaptureStats.markPixels
            val f0 = CaptureStats.frames
            log.text = "Uji 2/2: blok magenta dinyalakan..."
            startOverlay(tintOn = false, mark = true)
            ui.postDelayed({
                val mark = CaptureStats.markPixels
                val newFrames = CaptureStats.frames - f0
                CaptureStats.scanMark = false
                stopOverlay()
                testing = false

                log.text = if (newFrames <= 0) {
                    "Uji tidak valid: tidak ada frame baru setelah overlay menyala."
                } else {
                    val captured = mark - baseMark >= 20
                    (if (captured) "OVERLAY IKUT TERTANGKAP (feedback loop nyata)" else "Overlay TIDAK tertangkap") +
                        "\npiksel magenta: tanpa overlay=$baseMark, dengan overlay=$mark" +
                        "\nukuran capture ${CaptureStats.width}x${CaptureStats.height}, frame baru=$newFrames"
                }
            }, 2500)
        }, 1500)
    }

    private fun refreshStatus() {
        val canDraw = Settings.canDrawOverlays(this)
        val sb = StringBuilder()
        sb.append("Perangkat: ").append(Build.MODEL).append(", Android ")
            .append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Izin overlay: ").append(if (canDraw) "AKTIF" else "belum")
        sb.append("\nOverlay: ").append(if (OverlayService.running) "BERJALAN" else "mati")
        sb.append("\nPill FPS: ").append(if (PillService.running) "TAMPIL" else "mati")
        if (CaptureStats.running) {
            val ago = SystemClock.elapsedRealtime() - CaptureStats.lastFrameAt
            sb.append("\nCapture: BERJALAN ")
                .append(CaptureStats.width).append("x").append(CaptureStats.height)
                .append(", ").append("%.1f".format(CaptureStats.fps)).append(" fps")
                .append(", resize ").append(CaptureStats.resizeCount).append("x")
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
