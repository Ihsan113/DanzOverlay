package com.danzku.overlay

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Statistik renderer, dibaca UI dan pill FPS. */
object RenderStats {
    @Volatile var running: Boolean = false

    /** FPS game: jumlah frame yang dihasilkan game di display virtual per detik. */
    @Volatile var fps: Float = 0f

    /** FPS tampilan: jumlah frame yang digambar renderer ke layar per detik. */
    @Volatile var outFps: Float = 0f
    @Volatile var frames: Long = 0L
    @Volatile var error: String? = null
}

/**
 * Tahap 4: renderer GLES 3.0 (Kotlin).
 * Display virtual menggambar ke SurfaceTexture (input). Tiap frame baru diproses dua pass:
 *  A. OES -> FBO di resolusi display virtual, dengan penajaman CAS (opsional)
 *  B. FBO -> layar, dengan upscale Catmull-Rom (opsional, kalau layar lebih besar dari display virtual)
 * mode 1 = polos (bilinear), mode 2 = sharpen + upscale Catmull-Rom.
 */
class VdRenderer(
    private val window: Surface,
    private val vdW: Int,
    private val vdH: Int,
    private val mode: Int,
    private val onReady: (Surface) -> Unit,
    private val onError: (String) -> Unit
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE

    private var st: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var oesTex = 0
    private var fboTex = 0
    private var fbo = 0
    private var vbo = 0
    private var progA = 0
    private var progB = 0
    private var aTex = 0
    private var aMat = 0
    private var aPx = 0
    private var aSharp = 0
    private var bTex = 0
    private var bSize = 0
    private var bMode = 0

    private val texMatrix = FloatArray(16)
    private val sizeOut = IntArray(1)
    @Volatile private var ready = false

    private var gameFrames = 0L
    private var shownFrames = 0L
    private var lastGame = 0L
    private var lastShown = 0L
    private var lastTick = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val span = (now - lastTick).coerceAtLeast(1L)
            RenderStats.fps = (gameFrames - lastGame) * 1000f / span
            RenderStats.outFps = (shownFrames - lastShown) * 1000f / span
            RenderStats.frames = gameFrames
            lastGame = gameFrames
            lastShown = shownFrames
            lastTick = now
            handler?.postDelayed(this, 1000)
        }
    }

    fun start() {
        val t = HandlerThread("danz-render")
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post { init() }
    }

    /** Blokir sampai sumber daya EGL dilepas (dipanggil dari surfaceDestroyed). */
    fun stop() {
        val h = handler ?: return
        val latch = CountDownLatch(1)
        h.post {
            release()
            latch.countDown()
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun init() {
        try {
            initEgl()
            initGl()
            val s = SurfaceTexture(oesTex)
            s.setDefaultBufferSize(vdW, vdH)
            s.setOnFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener { onFrame() }, handler)
            st = s
            val ins = Surface(s)
            inputSurface = ins
            ready = true
            RenderStats.error = null
            RenderStats.running = true
            lastTick = SystemClock.elapsedRealtime()
            handler?.postDelayed(ticker, 1000)
            main.post { onReady(ins) }
        } catch (t: Throwable) {
            fail("init: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun fail(msg: String) {
        RenderStats.error = msg
        RenderStats.running = false
        main.post { onError(msg) }
    }

    private fun onFrame() {
        if (!ready) return
        gameFrames++
        try {
            draw()
        } catch (t: Throwable) {
            ready = false
            fail("draw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------- EGL ----------

    private fun initEgl() {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(d != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay gagal" }
        val ver = IntArray(2)
        require(EGL14.eglInitialize(d, ver, 0, ver, 1)) { "eglInitialize gagal" }
        dpy = d

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        require(EGL14.eglChooseConfig(d, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig (ES3) gagal"
        }
        val cfg = configs[0]!!

        val c = EGL14.eglCreateContext(
            d, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        require(c != EGL14.EGL_NO_CONTEXT) { "eglCreateContext gagal" }
        ctx = c

        val s = EGL14.eglCreateWindowSurface(d, cfg, window, intArrayOf(EGL14.EGL_NONE), 0)
        require(s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface gagal" }
        surf = s

        require(EGL14.eglMakeCurrent(d, s, s, c)) { "eglMakeCurrent gagal" }
        EGL14.eglSwapInterval(d, 1)
    }

    // ---------- GL ----------

    private fun initGl() {
        val ids = IntArray(1)

        // texture OES (input dari display virtual)
        GLES20.glGenTextures(1, ids, 0)
        oesTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // FBO di resolusi display virtual
        GLES20.glGenTextures(1, ids, 0)
        fboTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, vdW, vdH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        require(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO tidak lengkap: 0x${status.toString(16)}" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // quad layar penuh: x, y, u, v
        val verts = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        val fb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(verts).position(0)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, fb, GLES20.GL_STATIC_DRAW)

        progA = program(VERT_A, FRAG_A)
        aTex = GLES20.glGetUniformLocation(progA, "uTex")
        aMat = GLES20.glGetUniformLocation(progA, "uTexMatrix")
        aPx = GLES20.glGetUniformLocation(progA, "uPx")
        aSharp = GLES20.glGetUniformLocation(progA, "uSharp")

        progB = program(VERT_B, FRAG_B)
        bTex = GLES20.glGetUniformLocation(progB, "uTex")
        bSize = GLES20.glGetUniformLocation(progB, "uTexSize")
        bMode = GLES20.glGetUniformLocation(progB, "uMode")
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("shader gagal dikompilasi: $log")
        }
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("program gagal di-link: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun drawQuad() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun draw() {
        val s = st ?: return
        s.updateTexImage()
        s.getTransformMatrix(texMatrix)

        // Pass A: OES -> FBO (+ CAS)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, vdW, vdH)
        GLES20.glUseProgram(progA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(aTex, 0)
        GLES20.glUniformMatrix4fv(aMat, 1, false, texMatrix, 0)
        GLES20.glUniform2f(aPx, 1f / vdW, 1f / vdH)
        GLES20.glUniform1f(aSharp, if (mode >= 2) SHARPNESS else 0f)
        drawQuad()

        // Pass B: FBO -> layar (+ Catmull-Rom kalau layar lebih besar dari display virtual)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_WIDTH, sizeOut, 0)
        val ow = sizeOut[0]
        EGL14.eglQuerySurface(dpy, surf, EGL14.EGL_HEIGHT, sizeOut, 0)
        val oh = sizeOut[0]
        GLES20.glViewport(0, 0, ow, oh)
        GLES20.glUseProgram(progB)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glUniform1i(bTex, 0)
        GLES20.glUniform2f(bSize, vdW.toFloat(), vdH.toFloat())
        val upscale = mode >= 2 && (ow != vdW || oh != vdH)
        GLES20.glUniform1i(bMode, if (upscale) 1 else 0)
        drawQuad()

        EGL14.eglSwapBuffers(dpy, surf)
        shownFrames++
    }

    private fun release() {
        ready = false
        handler?.removeCallbacks(ticker)
        runCatching { st?.setOnFrameAvailableListener(null) }
        runCatching { inputSurface?.release() }
        runCatching { st?.release() }
        inputSurface = null
        st = null
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                val one = IntArray(1)
                if (progA != 0) GLES20.glDeleteProgram(progA)
                if (progB != 0) GLES20.glDeleteProgram(progB)
                one[0] = oesTex; GLES20.glDeleteTextures(1, one, 0)
                one[0] = fboTex; GLES20.glDeleteTextures(1, one, 0)
                one[0] = fbo; GLES20.glDeleteFramebuffers(1, one, 0)
                one[0] = vbo; GLES20.glDeleteBuffers(1, one, 0)
            }
            runCatching { EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            runCatching { EGL14.eglDestroySurface(dpy, surf) }
            runCatching { EGL14.eglDestroyContext(dpy, ctx) }
            runCatching { EGL14.eglTerminate(dpy) }
        }
        dpy = EGL14.EGL_NO_DISPLAY
        ctx = EGL14.EGL_NO_CONTEXT
        surf = EGL14.EGL_NO_SURFACE
        RenderStats.running = false
        RenderStats.fps = 0f
        RenderStats.outFps = 0f
    }

    companion object {
        private const val SHARPNESS = 0.6f

        private val VERT_A = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uTexMatrix;
            out vec2 vUv;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        // Penajaman ala AMD CAS: kekuatan berkurang di area kontras tinggi
        private val FRAG_A = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uTex;
            uniform vec2 uPx;
            uniform float uSharp;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                vec3 e = texture(uTex, vUv).rgb;
                if (uSharp <= 0.0) {
                    outColor = vec4(e, 1.0);
                    return;
                }
                vec3 b = texture(uTex, vUv + vec2(0.0, -uPx.y)).rgb;
                vec3 d = texture(uTex, vUv + vec2(-uPx.x, 0.0)).rgb;
                vec3 f = texture(uTex, vUv + vec2(uPx.x, 0.0)).rgb;
                vec3 h = texture(uTex, vUv + vec2(0.0, uPx.y)).rgb;
                vec3 mn = min(min(min(d, e), min(f, b)), h);
                vec3 mx = max(max(max(d, e), max(f, b)), h);
                vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0);
                amp = sqrt(amp);
                float peak = -1.0 / mix(8.0, 5.0, uSharp);
                vec3 w = amp * peak;
                vec3 rcpW = 1.0 / (1.0 + 4.0 * w);
                vec3 col = clamp(((b + d + f + h) * w + e) * rcpW, 0.0, 1.0);
                outColor = vec4(col, 1.0);
            }
        """.trimIndent()

        private val VERT_B = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;
            out vec2 vUv;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUv = aUv;
            }
        """.trimIndent()

        // Catmull-Rom 9 tap (bilinear-optimized), dipakai saat upscale
        private val FRAG_B = """
            #version 300 es
            precision highp float;
            precision highp sampler2D;
            uniform sampler2D uTex;
            uniform vec2 uTexSize;
            uniform int uMode;
            in vec2 vUv;
            out vec4 outColor;
            vec4 catmullRom(vec2 uv) {
                vec2 samplePos = uv * uTexSize;
                vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
                vec2 f = samplePos - texPos1;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 offset12 = w2 / w12;
                vec2 p0 = (texPos1 - 1.0) / uTexSize;
                vec2 p3 = (texPos1 + 2.0) / uTexSize;
                vec2 p12 = (texPos1 + offset12) / uTexSize;
                vec4 r = vec4(0.0);
                r += texture(uTex, vec2(p0.x, p0.y)) * w0.x * w0.y;
                r += texture(uTex, vec2(p12.x, p0.y)) * w12.x * w0.y;
                r += texture(uTex, vec2(p3.x, p0.y)) * w3.x * w0.y;
                r += texture(uTex, vec2(p0.x, p12.y)) * w0.x * w12.y;
                r += texture(uTex, vec2(p12.x, p12.y)) * w12.x * w12.y;
                r += texture(uTex, vec2(p3.x, p12.y)) * w3.x * w12.y;
                r += texture(uTex, vec2(p0.x, p3.y)) * w0.x * w3.y;
                r += texture(uTex, vec2(p12.x, p3.y)) * w12.x * w3.y;
                r += texture(uTex, vec2(p3.x, p3.y)) * w3.x * w3.y;
                return r;
            }
            void main() {
                if (uMode == 0) {
                    outColor = vec4(texture(uTex, vUv).rgb, 1.0);
                } else {
                    outColor = vec4(clamp(catmullRom(vUv).rgb, 0.0, 1.0), 1.0);
                }
            }
        """.trimIndent()
    }
}
