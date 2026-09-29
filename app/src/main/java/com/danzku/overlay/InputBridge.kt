package com.danzku.overlay

import java.io.OutputStream

/**
 * Sisi app dari helper input root. Menjalankan InputHelper lewat `su` + app_process,
 * lalu mengirim event sentuhan multi-touch per baris. Kalau helper gagal (ready=false),
 * pemanggil kembali ke `input tap/swipe`.
 */
class InputBridge(
    private val apkPath: String,
    private val displayId: Int,
    private val onMsg: (String) -> Unit
) {
    @Volatile
    var ready: Boolean = false
        private set

    private var proc: Process? = null
    private var out: OutputStream? = null

    fun start() {
        try {
            val cmd = "CLASSPATH='$apkPath' app_process /system/bin com.danzku.overlay.InputHelper $displayId"
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            proc = p
            out = p.outputStream
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (line.startsWith("READY")) ready = true
                        onMsg(line)
                    }
                } catch (_: Throwable) {
                }
                ready = false
            }.start()
        } catch (t: Throwable) {
            ready = false
            onMsg("ERR helper: ${t.message}")
        }
    }

    fun send(line: String) {
        try {
            val o = out ?: return
            o.write((line + "\n").toByteArray())
            o.flush()
        } catch (_: Throwable) {
            ready = false
        }
    }

    fun stop() {
        runCatching { send("Q") }
        runCatching { proc?.destroy() }
        ready = false
    }
}
