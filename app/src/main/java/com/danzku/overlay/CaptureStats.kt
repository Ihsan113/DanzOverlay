package com.danzku.overlay

/** Statistik capture yang dibaca UI. Ditulis oleh CaptureService (thread capture). */
object CaptureStats {
    @Volatile var running: Boolean = false
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var fps: Float = 0f
    @Volatile var frames: Long = 0L
    @Volatile var lastFrameAt: Long = 0L

    /** Rata-rata warna layar (sampel grid), -1 kalau belum ada frame. */
    @Volatile var avgR: Int = -1
    @Volatile var avgG: Int = -1
    @Volatile var avgB: Int = -1

    @Volatile var lastError: String? = null

    fun reset() {
        running = false
        width = 0
        height = 0
        fps = 0f
        frames = 0L
        lastFrameAt = 0L
        avgR = -1
        avgG = -1
        avgB = -1
    }
}
