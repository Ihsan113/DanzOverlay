package com.danzku.overlay

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "DanzOverlay - tahap 1"
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

        val rootOut = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            text = "Tekan tombol untuk tes root."
        }

        val btn = Button(this).apply {
            text = "Tes root (su -c id)"
            setOnClickListener {
                rootOut.text = "Menjalankan..."
                Thread {
                    val r = RootShell.run("id")
                    runOnUiThread { rootOut.text = r }
                }.start()
            }
        }

        root.addView(title)
        root.addView(nativeInfo)
        root.addView(btn)
        root.addView(rootOut)
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
