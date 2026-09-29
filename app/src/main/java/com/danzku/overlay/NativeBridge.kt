package com.danzku.overlay

/** Jembatan JNI ke renderer native (libdanzrenderer.so). */
object NativeBridge {
    init {
        System.loadLibrary("danzrenderer")
    }

    external fun version(): String
}
