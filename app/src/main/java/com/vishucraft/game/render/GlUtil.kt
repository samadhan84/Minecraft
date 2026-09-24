package com.vishucraft.game.render

import android.opengl.GLES20.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

class Shader(vertexSrc: String, fragmentSrc: String) {
    val program: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GL_FRAGMENT_SHADER, fragmentSrc)
        program = glCreateProgram()
        glAttachShader(program, vs)
        glAttachShader(program, fs)
        // Fixed attribute slots (OpenGL ES 2.0 has no layout qualifiers).
        glBindAttribLocation(program, 0, "aPos")
        glBindAttribLocation(program, 1, "aUv")
        glBindAttribLocation(program, 2, "aLight")
        glLinkProgram(program)
        val status = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Link failed: " + glGetProgramInfoLog(program))
        glDeleteShader(vs)
        glDeleteShader(fs)
    }

    private fun compile(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val status = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader compile failed: " + glGetShaderInfoLog(s))
        return s
    }

    fun use() = glUseProgram(program)
    fun u(name: String): Int = uniforms.getOrPut(name) { glGetUniformLocation(program, name) }
}

/** Reusable direct buffers so uploads don't allocate every frame. */
object Buffers {
    private var floatBuf: FloatBuffer = alloc(1 shl 16)

    private fun alloc(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun floats(data: FloatArray, count: Int = data.size): FloatBuffer {
        if (floatBuf.capacity() < count) floatBuf = alloc(Integer.highestOneBit(count) shl 1)
        floatBuf.clear()
        floatBuf.put(data, 0, count)
        floatBuf.flip()
        return floatBuf
    }

    fun shorts(data: ShortArray): ShortBuffer {
        val b = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        b.put(data).flip()
        return b
    }
}

/**
 * A shared 16-bit index buffer describing quads as two triangles (0,1,2)(0,2,3).
 * OpenGL ES 2.0 only guarantees 16-bit indices, so meshes are drawn in batches of [BATCH] quads.
 */
object QuadIndices {
    const val BATCH = 16384 // 65536 vertices
    var ibo = 0
        private set

    fun reset() { ibo = 0 }

    fun ensure() {
        if (ibo != 0) return
        val idx = ShortArray(BATCH * 6)
        for (q in 0 until BATCH) {
            val v = q * 4; val i = q * 6
            idx[i] = v.toShort(); idx[i + 1] = (v + 1).toShort(); idx[i + 2] = (v + 2).toShort()
            idx[i + 3] = v.toShort(); idx[i + 4] = (v + 2).toShort(); idx[i + 5] = (v + 3).toShort()
        }
        val ids = IntArray(1); glGenBuffers(1, ids, 0); ibo = ids[0]
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, Buffers.shorts(idx), GL_STATIC_DRAW)
    }
}

/** A VBO holding quads in the chunk vertex layout (x, y, z, u, v, light). */
class GpuMesh(private val usage: Int = GL_STATIC_DRAW) {
    private var vbo = 0
    var quads = 0
        private set

    fun upload(data: FloatArray, floatCount: Int = data.size) {
        quads = floatCount / (FLOATS_PER_VERTEX * 4)
        if (quads == 0) return
        if (vbo == 0) {
            val ids = IntArray(1)
            glGenBuffers(1, ids, 0); vbo = ids[0]
        }
        QuadIndices.ensure()
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, floatCount * 4, Buffers.floats(data, floatCount), usage)
    }

    private fun pointers(baseBytes: Int) {
        val stride = FLOATS_PER_VERTEX * 4
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, baseBytes)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, baseBytes + 12)
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, baseBytes + 20)
    }

    private fun bind() {
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0)
        glEnableVertexAttribArray(1)
        glEnableVertexAttribArray(2)
    }

    fun draw(mode: Int = GL_TRIANGLES) {
        if (quads == 0 || vbo == 0) return
        bind()
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, QuadIndices.ibo)
        var q = 0
        while (q < quads) {
            val n = minOf(QuadIndices.BATCH, quads - q)
            pointers(q * 4 * FLOATS_PER_VERTEX * 4)
            glDrawElements(mode, n * 6, GL_UNSIGNED_SHORT, 0)
            q += n
        }
    }

    /** Draws raw vertices (used for line lists). */
    fun drawArrays(mode: Int, vertices: Int) {
        if (vbo == 0) return
        bind()
        pointers(0)
        glDrawArrays(mode, 0, vertices)
    }

    fun delete() {
        if (vbo != 0) glDeleteBuffers(1, intArrayOf(vbo), 0)
        vbo = 0; quads = 0
    }

    /** Forget GL names after a context loss without deleting them. */
    fun invalidate() { vbo = 0; quads = 0 }
}

object Shaders {
    private const val FRAG_PRECISION = """#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    const val BLOCK_VS = """attribute vec3 aPos;
attribute vec2 aUv;
attribute float aLight;
uniform mat4 uViewProj;
uniform vec3 uOffset;
uniform vec3 uCamPos;
varying vec2 vUv;
varying float vLight;
varying float vDist;
void main() {
    vec3 wp = aPos + uOffset;
    gl_Position = uViewProj * vec4(wp, 1.0);
    vUv = aUv;
    vLight = aLight;
    vDist = length(wp.xz - uCamPos.xz);
}
"""

    const val BLOCK_FS = FRAG_PRECISION + """uniform sampler2D uTex;
uniform float uDaylight;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform float uCutout;
uniform vec3 uTint;
varying vec2 vUv;
varying float vLight;
varying float vDist;
void main() {
    vec4 c = texture2D(uTex, vUv);
    if (c.a < uCutout) discard;
    float l = vLight > 1.5 ? 1.0 : vLight * mix(0.16, 1.0, uDaylight);
    vec3 col = c.rgb * l * uTint;
    float f = clamp((vDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);
    gl_FragColor = vec4(mix(col, uFogColor, f), c.a);
}
"""

    const val SIMPLE_VS = """attribute vec3 aPos;
attribute vec2 aUv;
attribute float aLight;
uniform mat4 uViewProj;
uniform vec3 uCamPos;
varying vec2 vUv;
varying float vDist;
void main() {
    gl_Position = uViewProj * vec4(aPos, 1.0);
    vUv = aUv;
    vDist = length(aPos.xz - uCamPos.xz) + aLight * 0.0;
}
"""

    const val SIMPLE_FS = FRAG_PRECISION + """uniform sampler2D uTex;
uniform vec4 uColor;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
varying vec2 vUv;
varying float vDist;
void main() {
    vec4 c = texture2D(uTex, vUv) * uColor;
    if (c.a < 0.01) discard;
    float f = uFogEnd > 0.0 ? clamp((vDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0) : 0.0;
    gl_FragColor = vec4(c.rgb, c.a * (1.0 - f));
}
"""
}
