package com.danzku.overlay

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Viewer display virtual. Membuat display virtual milik app, meluncurkan satu app/game di sana lewat root,
 * dan menampilkannya di layar penuh. Karena game tidak ada di layar fisik, hasil olahan tidak
 * tertangkap lagi (tidak ada feedback loop).
 *
 * mode 0 = langsung ke SurfaceView (tanpa GL, untuk diagnosis)
 * mode 1 = GL polos, mode 2 = GL + sharpen + upscale Catmull-Rom (lihat VdRenderer)
 */
class VdActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var info: TextView

    private var vd: VirtualDisplay? = null
    private var renderer: VdRenderer? = null
    private var bridge: InputBridge? = null

    private var vdId = -1
    private var vdW = 0
    private var vdH = 0
    private var vdDpi = 0
    private var mode = 2
    private var pkg = "com.android.settings"
    private var started = false
    private var baseInfo = ""

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val infoTick = object : Runnable {
        override fun run() {
            renderInfo()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pkg = intent.getStringExtra(EXTRA_PKG) ?: pkg
        mode = intent.getIntExtra(EXTRA_MODE, 2)
        val scale = intent.getFloatExtra(EXTRA_SCALE, 1f)
        val portrait = intent.getBooleanExtra(EXTRA_PORTRAIT, false)
        requestedOrientation = if (portrait) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        // Ukuran display virtual = ukuran layar fisik (sesuai orientasi) x skala
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        val longSide = max(metrics.widthPixels, metrics.heightPixels)
        val shortSide = min(metrics.widthPixels, metrics.heightPixels)
        val fullW = if (portrait) shortSide else longSide
        val fullH = if (portrait) longSide else shortSide
        vdW = even((fullW * scale).roundToInt())
        vdH = even((fullH * scale).roundToInt())
        vdDpi = max(120, (metrics.densityDpi * scale).roundToInt())

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        // mode langsung: buffer permukaan = ukuran display virtual. Mode GL: buffer = ukuran layar.
        if (mode == 0) surfaceView.holder.setFixedSize(vdW, vdH)
        surfaceView.setOnTouchListener(OnTouchListener { v, ev -> onViewerTouch(v, ev) })

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setOnClickListener { visibility = View.GONE }
        }
        baseInfo = "VD ${vdW}x$vdH (skala ${(scale * 100).roundToInt()}%) - menyiapkan..."
        renderInfo()

        val root = FrameLayout(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            info,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(infoTick)
    }

    override fun onPause() {
        ui.removeCallbacks(infoTick)
        super.onPause()
    }

    private fun even(v: Int): Int = if (v % 2 == 0) v else v + 1

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (started) return
        started = true
        if (mode == 0) {
            startVd(holder.surface)
        } else {
            val r = VdRenderer(
                holder.surface, vdW, vdH, mode,
                onReady = { inputSurface -> startVd(inputSurface) },
                onError = { msg -> setInfo("Renderer gagal: $msg") }
            )
            renderer = r
            r.start()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        teardown()
    }

    private fun teardown() {
        bridge?.stop()
        bridge = null
        runCatching { vd?.release() }
        vd = null
        vdId = -1
        renderer?.stop()
        renderer = null
        started = false
    }

    override fun onDestroy() {
        teardown()
        io.shutdown()
        super.onDestroy()
    }

    // --- display virtual ---

    private fun startVd(surface: Surface) {
        if (!Regex("[A-Za-z0-9._]+").matches(pkg)) {
            setInfo("Nama paket tidak valid: $pkg")
            return
        }
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val d = dm.createVirtualDisplay("DanzVD", vdW, vdH, vdDpi, surface, flags)
            if (d == null) {
                setInfo("createVirtualDisplay mengembalikan null")
                return
            }
            vd = d
            val id = d.display.displayId
            vdId = id
            setInfo("VD id=$id ${vdW}x$vdH dpi=$vdDpi\nMeluncurkan $pkg...")

            val b = InputBridge(applicationInfo.sourceDir, id) { line ->
                if (line.startsWith("ERR")) ui.post { setInfo("$baseInfo\n$line") }
            }
            bridge = b
            b.start()

            launchOnVd(id)
        } catch (t: Throwable) {
            setInfo("Gagal buat VD: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Cari activity launcher paket di semua user (termasuk Dual Apps/Second Space),
     * lalu jalankan di display virtual. Kalau tidak ketemu, tampilkan paket yang mirip namanya.
     */
    private fun launchOnVd(id: Int) {
        io.execute {
            val head = "VD id=$id ${vdW}x$vdH dpi=$vdDpi"
            val users = Regex("UserInfo\\{(\\d+):").findAll(RootShell.run("pm list users", 8))
                .map { it.groupValues[1].toInt() }.toList().ifEmpty { listOf(0) }

            var comp: String? = null
            var user = 0
            for (u in users) {
                val r = RootShell.run(
                    "cmd package resolve-activity --brief --user $u " +
                        "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg",
                    8
                )
                val c = r.lines().map { it.trim() }
                    .firstOrNull { Regex("[A-Za-z0-9._]+/[A-Za-z0-9._$]+").matches(it) }
                if (c != null) {
                    comp = c
                    user = u
                    break
                }
            }

            if (comp == null) {
                val kw = pkg.substringAfterLast('.')
                val similar = if (kw.isNotEmpty()) {
                    RootShell.run("pm list packages --user 0 | grep -i '$kw'", 8).lines()
                        .filter { it.startsWith("package:") }.take(10).joinToString("\n")
                } else {
                    ""
                }
                ui.post {
                    setInfo(
                        "$head\nTidak ada activity launcher untuk $pkg (user: $users).\n" +
                            (if (similar.isNotEmpty()) "Paket mirip:\n$similar" else "Tidak ada paket mirip.") +
                            "\nCoba tombol 'Pilih app' di menu utama."
                    )
                }
                return@execute
            }

            val out = RootShell.run("am start -W --user $user --display $id -n '$comp'", 25)
            ui.post { setInfo("$head\n$comp (user $user)\n" + out.take(500)) }
        }
    }

    private fun renderInfo() {
        val head = if (mode == 0) {
            "Mode langsung (tanpa GL)"
        } else {
            "GL mode $mode | game ${"%.0f".format(RenderStats.fps)} fps | layar ${"%.0f".format(RenderStats.outFps)} fps"
        }
        val touch = if (bridge?.ready == true) "sentuhan: helper root" else "sentuhan: input tap/swipe"
        info.text = "$head | $touch\n$baseInfo"
    }

    private fun setInfo(text: String) {
        ui.post {
            baseInfo = text
            info.visibility = View.VISIBLE
            renderInfo()
        }
    }

    // --- sentuhan ---

    private fun onViewerTouch(v: View, ev: MotionEvent): Boolean {
        val id = vdId
        if (id < 0 || v.width == 0 || v.height == 0) return false
        val sx = vdW / v.width.toFloat()
        val sy = vdH / v.height.toFloat()

        val b = bridge
        if (b != null && b.ready) {
            // multi-touch penuh lewat helper root
            val sb = StringBuilder("E ")
                .append(ev.actionMasked).append(' ')
                .append(ev.actionIndex).append(' ')
                .append(ev.pointerCount)
            for (i in 0 until ev.pointerCount) {
                sb.append(' ').append(ev.getPointerId(i))
                    .append(' ').append((ev.getX(i) * sx).toInt())
                    .append(' ').append((ev.getY(i) * sy).toInt())
            }
            val line = sb.toString()
            io.execute { b.send(line) }
            return true
        }

        // cadangan: tap/swipe lewat `input -d`
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downT = ev.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val x1 = (downX * sx).toInt()
                val y1 = (downY * sy).toInt()
                val x2 = (ev.x * sx).toInt()
                val y2 = (ev.y * sy).toInt()
                val moved = hypot(ev.x - downX, ev.y - downY)
                val threshold = 24 * resources.displayMetrics.density
                val dur = (ev.eventTime - downT).coerceIn(50L, 2000L)
                val cmd = if (moved < threshold) {
                    "input -d $id tap $x2 $y2"
                } else {
                    "input -d $id swipe $x1 $y1 $x2 $y2 $dur"
                }
                io.execute { RootShell.run(cmd, 5) }
            }
        }
        return true
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_PORTRAIT = "portrait"
        const val EXTRA_MODE = "mode"
    }
}
