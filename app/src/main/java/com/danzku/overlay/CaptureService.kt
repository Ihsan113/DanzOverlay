package com.danzku.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display

/**
 * Tahap 3 (uji kelayakan): capture layar lewat MediaProjection ke ImageReader.
 * Belum ada shader. Tujuannya mengukur FPS capture dan membuktikan apakah
 * overlay kita ikut tertangkap (feedback loop) sebelum renderer tahap 4 dibangun.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    private var lastFrames = 0L
    private var lastTick = 0L

    /** Jalan tiap 500 ms di thread capture: cek rotasi layar dan hitung FPS (turun ke 0 saat layar diam). */
    private val ticker = object : Runnable {
        override fun run() {
            resizeIfNeeded()
            val now = SystemClock.elapsedRealtime()
            val span = now - lastTick
            if (span >= 1000) {
                val f = CaptureStats.frames
                CaptureStats.fps = (f - lastFrames) * 1000f / span
                lastFrames = f
                lastTick = now
                updateNotification()
            }
            handler?.postDelayed(this, 500)
        }
    }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) resizeIfNeeded()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wajib dipanggil di setiap start agar tidak crash (batas 5 detik foreground).
        startForeground(
            NOTIF_ID,
            buildNotification("Menyiapkan capture..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        if (projection != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = readData(intent)
        if (code != Activity.RESULT_OK || data == null) {
            fail("data izin capture tidak ada")
            return START_NOT_STICKY
        }
        try {
            startCapture(code, data)
        } catch (t: Throwable) {
            fail("${t.javaClass.simpleName}: ${t.message}")
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent?): Intent? = intent?.getParcelableExtra(EXTRA_DATA)

    private fun startCapture(code: Int, data: Intent) {
        CaptureStats.reset()
        CaptureStats.lastError = null

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj

        val t = HandlerThread("danz-capture").also { it.start() }
        worker = t
        val h = Handler(t.looper)
        handler = h

        // Android 14+ mewajibkan callback terdaftar sebelum createVirtualDisplay.
        proj.registerCallback(callback, h)

        val s = realSize()
        val r = newReader(s[0], s[1])
        reader = r
        vDisplay = proj.createVirtualDisplay(
            "DanzCapture", s[0], s[1], s[2],
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, h
        )

        CaptureStats.width = s[0]
        CaptureStats.height = s[1]
        lastFrames = 0L
        lastTick = SystemClock.elapsedRealtime()
        CaptureStats.running = true
        h.postDelayed(ticker, 500)

        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, h)
    }

    @Suppress("DEPRECATION")
    private fun realSize(): IntArray {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        return r
    }

    /** Layar diputar: ubah ukuran virtual display dan ganti reader. Jalan di thread capture. */
    private fun resizeIfNeeded() {
        val vd = vDisplay ?: return
        val s = realSize()
        if (s[0] == CaptureStats.width && s[1] == CaptureStats.height) return
        val nr = newReader(s[0], s[1])
        vd.resize(s[0], s[1], s[2])
        vd.setSurface(nr.surface)
        val old = reader
        reader = nr
        runCatching { old?.close() }
        CaptureStats.width = s[0]
        CaptureStats.height = s[1]
        CaptureStats.resizeCount = CaptureStats.resizeCount + 1
    }

    private fun onFrame(rd: ImageReader) {
        val img = try {
            rd.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            CaptureStats.frames = CaptureStats.frames + 1
            CaptureStats.lastFrameAt = now
            sampleColor(img)
            if (CaptureStats.scanMark) scanMark(img)
        } finally {
            img.close()
        }
    }

    /** Rata-rata warna dari grid sampel, untuk mendeteksi apakah overlay ikut tertangkap. */
    private fun sampleColor(img: Image) {
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val px = plane.pixelStride
            val w = img.width
            val h = img.height
            val stepX = maxOf(1, w / 48)
            val stepY = maxOf(1, h / 64)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            var y = stepY / 2
            while (y < h) {
                var x = stepX / 2
                while (x < w) {
                    val i = y * rowStride + x * px
                    r += buf.get(i).toInt() and 0xFF
                    g += buf.get(i + 1).toInt() and 0xFF
                    b += buf.get(i + 2).toInt() and 0xFF
                    n++
                    x += stepX
                }
                y += stepY
            }
            if (n > 0) {
                CaptureStats.avgR = (r / n).toInt()
                CaptureStats.avgG = (g / n).toInt()
                CaptureStats.avgB = (b / n).toInt()
            }
        } catch (t: Throwable) {
            CaptureStats.lastError = "sampel warna: ${t.message}"
        }
    }

    /** Hitung piksel magenta (blok uji dari OverlayService) di seluruh frame, langkah 6 piksel. */
    private fun scanMark(img: Image) {
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val px = plane.pixelStride
            val w = img.width
            val h = img.height
            var n = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val i = y * rowStride + x * px
                    if ((buf.get(i).toInt() and 0xFF) > 200) {
                        val g = buf.get(i + 1).toInt() and 0xFF
                        val b = buf.get(i + 2).toInt() and 0xFF
                        if (g < 60 && b > 200) n++
                    }
                    x += 6
                }
                y += 6
            }
            CaptureStats.markPixels = n
        } catch (t: Throwable) {
            CaptureStats.lastError = "scan magenta: ${t.message}"
        }
    }

    private fun fail(msg: String) {
        CaptureStats.lastError = msg
        stopSelf()
    }

    override fun onDestroy() {
        handler?.removeCallbacks(ticker)
        runCatching {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        }
        runCatching { vDisplay?.release() }
        runCatching { reader?.close() }
        runCatching {
            projection?.unregisterCallback(callback)
            projection?.stop()
        }
        worker?.quitSafely()
        vDisplay = null
        reader = null
        projection = null
        CaptureStats.running = false
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DanzOverlay capture")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "${CaptureStats.width}x${CaptureStats.height} @ " +
            "%.1f".format(CaptureStats.fps) + " fps"
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "danz_capture"
        private const val NOTIF_ID = 2
    }
}
