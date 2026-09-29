package com.danzku.overlay

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var tint: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "DanzOverlay - tahap 2"
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
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    log.text = "Izin overlay belum aktif. Tekan 'Beri izin via root' dulu."
                } else {
                    startForegroundService(
                        Intent(this@MainActivity, OverlayService::class.java)
                            .putExtra(OverlayService.EXTRA_TINT, tint.isChecked)
                    )
                    log.text = "Overlay dimulai."
                }
                root.postDelayed({ refreshStatus() }, 500)
            }
        }

        val btnStop = Button(this).apply {
            text = "Hentikan overlay"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                log.text = "Overlay dihentikan."
                root.postDelayed({ refreshStatus() }, 500)
            }
        }

        root.addView(title)
        root.addView(nativeInfo)
        root.addView(status)
        root.addView(btnRoot)
        root.addView(btnGrant)
        root.addView(tint)
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val canDraw = Settings.canDrawOverlays(this)
        status.text = "Izin overlay: " + (if (canDraw) "AKTIF" else "belum") +
            "\nOverlay: " + (if (OverlayService.running) "BERJALAN" else "mati")
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
}
