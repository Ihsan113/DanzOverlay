package com.danzku.overlay

import java.util.concurrent.TimeUnit

/** Menjalankan satu perintah lewat `su -c`. Dipakai untuk tes root tahap 1. */
object RootShell {
    fun run(cmd: String, timeoutSec: Long = 10): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            "timeout setelah ${timeoutSec}s"
        } else {
            "exit=${p.exitValue()}\n$out"
        }
    } catch (t: Throwable) {
        "error: ${t.message}"
    }
}
