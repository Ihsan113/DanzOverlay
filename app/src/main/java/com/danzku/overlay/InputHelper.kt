package com.danzku.overlay

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Dijalankan sebagai root lewat `app_process` (bukan sebagai bagian dari UI app).
 * Membaca baris "E <action> <index> <n> <id x y>..." dari stdin (koordinat dalam piksel
 * display virtual) dan menyuntikkan MotionEvent ke display tujuan lewat InputManager (API hidden, via refleksi).
 */
object InputHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        val displayId = args.firstOrNull()?.toIntOrNull()
        if (displayId == null) {
            println("ERR display id tidak valid")
            return
        }

        val im: Any?
        val injectM: java.lang.reflect.Method
        val setDisplayM: java.lang.reflect.Method
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            im = imClass.getDeclaredMethod("getInstance").invoke(null)
            injectM = imClass.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            setDisplayM = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            println("ERR refleksi InputManager: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        println("READY display=$displayId")
        val reader = BufferedReader(InputStreamReader(System.`in`))
        var downTime = 0L
        var errors = 0

        while (true) {
            val line = reader.readLine() ?: break
            if (line == "Q") break
            if (!line.startsWith("E ")) continue
            try {
                val p = line.split(' ')
                val action = p[1].toInt()
                val index = p[2].toInt()
                val n = p[3].toInt()
                val props = Array(n) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = p[4 + i * 3].toInt()
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val coords = Array(n) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = p[5 + i * 3].toFloat()
                        y = p[6 + i * 3].toFloat()
                        pressure = 1f
                        size = 1f
                    }
                }
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = now
                val act = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                    action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                } else {
                    action
                }
                val ev = MotionEvent.obtain(
                    downTime, now, act, n, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
                setDisplayM.invoke(ev, displayId)
                injectM.invoke(im, ev, 0)
                ev.recycle()
            } catch (t: Throwable) {
                if (errors++ < 3) println("ERR inject: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
