package com.danzku.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Tahap 2: overlay fullscreen kosong yang tembus sentuhan.
 * Belum ada capture atau shader. Mode tint hanya untuk membuktikan overlay tampil.
 */
class OverlayService : Service() {

    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val tint = intent?.getBooleanExtra(EXTRA_TINT, false) ?: false
        val mark = intent?.getBooleanExtra(EXTRA_MARK, false) ?: false
        showOverlay(tint, mark)
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        running = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DanzOverlay aktif")
            .setContentText("Overlay berjalan")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun showOverlay(tint: Boolean, mark: Boolean) {
        removeOverlay()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val view: View = if (mark) {
            MarkView(this)
        } else {
            View(this).apply {
                setBackgroundColor(if (tint) Color.argb(40, 0, 160, 255) else Color.TRANSPARENT)
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "DanzOverlay"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        wm.addView(view, lp)
        overlayView = view
    }

    private fun removeOverlay() {
        val v = overlayView ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        overlayView = null
    }

    companion object {
        const val EXTRA_TINT = "tint"
        const val EXTRA_MARK = "mark"
        private const val CHANNEL_ID = "danz_overlay"
        private const val NOTIF_ID = 1

        @Volatile
        var running: Boolean = false
    }
}

/** Blok magenta solid untuk uji apakah overlay ikut tertangkap capture. */
private class MarkView(ctx: Context) : View(ctx) {
    private val paint = Paint().apply { color = Color.rgb(255, 0, 255) }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(width * 0.1f, height * 0.2f, width * 0.6f, height * 0.5f, paint)
    }
}
