package com.danzku.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Pill kecil mengambang berisi FPS (dari CaptureService) dan suhu baterai.
 * Bisa digeser dengan jari; posisi disimpan. FPS hanya terisi saat capture berjalan.
 */
class PillService : Service() {

    private var pill: TextView? = null
    private var lastText = ""
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            update()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (pill == null) showPill()
        ui.removeCallbacks(tick)
        ui.post(tick)
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        val v = pill
        if (v != null) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(v) }
        }
        pill = null
        running = false
        super.onDestroy()
    }

    private fun showPill() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val prefs = getSharedPreferences("pill", Context.MODE_PRIVATE)

        val tv = TextView(this).apply {
            text = "FPS --"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f * d
                setColor(Color.argb(170, 0, 0, 0))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", (16 * d).toInt())
            y = prefs.getInt("y", (48 * d).toInt())
            title = "DanzPill"
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        tv.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - downX).toInt()
                    params.y = startY + (ev.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    true
                }
                else -> false
            }
        }

        wm.addView(tv, params)
        pill = tv
        lastText = ""
    }

    private fun update() {
        val fpsText = if (CaptureStats.running) {
            val f = CaptureStats.fps
            // di bawah 2 dianggap 0: pill sendiri ikut menggambar ulang saat teks berubah
            (if (f < 2f) 0 else Math.round(f)).toString() + " FPS"
        } else {
            "FPS --"
        }
        val temp = batteryTemp()
        val text = if (temp != null) "$fpsText · " + "%.1f".format(temp) + "°C" else fpsText
        if (text != lastText) {
            pill?.text = text
            lastText = text
        }
    }

    private fun batteryTemp(): Float? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10f
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pill FPS", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pill FPS aktif")
            .setContentText("Geser pill untuk memindahkan")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "danz_pill"
        private const val NOTIF_ID = 3

        @Volatile
        var running: Boolean = false
    }
}
