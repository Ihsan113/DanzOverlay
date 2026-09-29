package com.danzku.overlay

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
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
 * Tahap 4a (uji kelayakan jalur display virtual):
 * membuat display virtual milik app, meluncurkan satu app di sana lewat root,
 * dan menampilkan hasilnya di SurfaceView layar penuh. Belum ada shader.
 * Karena app berjalan di display virtual, overlay/viewer tidak ikut tertangkap (tidak ada feedback loop).
 * Sentuhan diteruskan lewat `input -d` (tap/swipe saja); kontrol game kontinu menyusul lewat helper root.
 */
class VdActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var info: TextView

    private var vd: VirtualDisplay? = null
    private var vdW = 0
    private var vdH = 0
    private var vdDpi = 0
    private var pkg = "com.android.settings"

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pkg = intent.getStringExtra(EXTRA_PKG) ?: pkg
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
        surfaceView.holder.setFixedSize(vdW, vdH)
        surfaceView.setOnTouchListener(OnTouchListener { v, ev -> onViewerTouch(v, ev) })

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            textSize = 11f
            setPadding(16, 8, 16, 8)
            text = "VD ${vdW}x$vdH (skala ${(scale * 100).roundToInt()}%) - menyiapkan..."
            setOnClickListener { visibility = View.GONE }
        }

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
        if (vd == null) startVd(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runCatching { vd?.release() }
        vd = null
    }

    override fun onDestroy() {
        runCatching { vd?.release() }
        vd = null
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
            setInfo("VD id=$id ${vdW}x$vdH dpi=$vdDpi\nMeluncurkan $pkg lewat root...")
            io.execute {
                val out = RootShell.run(
                    "am start -W --display $id -a android.intent.action.MAIN " +
                        "-c android.intent.category.LAUNCHER -p $pkg",
                    25
                )
                runOnUiThread { setInfo("VD id=$id ${vdW}x$vdH dpi=$vdDpi\n" + out.take(500)) }
            }
        } catch (t: Throwable) {
            setInfo("Gagal buat VD: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun setInfo(text: String) {
        runOnUiThread {
            info.text = text
            info.visibility = View.VISIBLE
        }
    }

    // --- sentuhan: tap / swipe diteruskan ke display virtual ---

    private fun onViewerTouch(v: View, ev: MotionEvent): Boolean {
        val id = vd?.display?.displayId ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downT = ev.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val sx = vdW / v.width.toFloat()
                val sy = vdH / v.height.toFloat()
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
    }
}
