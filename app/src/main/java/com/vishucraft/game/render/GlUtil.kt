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
        glBindAttribLocation(program, 3, "aBlock")
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
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, baseBytes + 24)
    }

    private fun bind() {
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnableVertexAttribArray(0)
        glEnableVertexAttribArray(1)
        glEnableVertexAttribArray(2)
        glEnableVertexAttribArray(3)
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
attribute float aBlock;
uniform mat4 uViewProj;
uniform vec3 uOffset;
uniform vec3 uCamPos;
uniform float uTime;
uniform float uWave;
varying vec2 vUv;
varying float vLight;
varying float vBlock;
varying float vDist;
varying float vFlag;
void main() {
    // The block light carries a sway flag in multiples of 4: 1 plant, 2 leaves, 3 water surface.
    float k = floor(aBlock * 0.25 + 0.01);
    vec3 wp = aPos + uOffset;
    if (uWave > 0.5 && k > 0.5) {
        float ph = uTime * 1.7 + wp.x * 0.7 + wp.z * 0.45;
        if (k < 1.5) {
            wp.x += sin(ph) * 0.07; wp.z += cos(ph * 1.3) * 0.05;
        } else if (k < 2.5) {
            wp.x += sin(ph * 0.8) * 0.035; wp.y += cos(ph * 1.1) * 0.02; wp.z += sin(ph * 0.6 + 1.0) * 0.035;
        } else if (fract(wp.y) > 0.05) {
            wp.y += sin(uTime * 1.8 + wp.x * 0.9) * 0.035 + cos(uTime * 1.3 + wp.z * 1.1) * 0.035 - 0.06;
        }
    }
    gl_Position = uViewProj * vec4(wp, 1.0);
    vUv = aUv;
    vLight = aLight;
    vBlock = aBlock - k * 4.0;
    // uWave is only read here: a uniform used by both shaders must have the same precision in each, which
    // devices without high-precision fragment shaders (many TVs) can't give, and the program fails to link.
    vFlag = uWave > 0.5 ? k : 0.0;
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
uniform vec3 uSunTint;
varying vec2 vUv;
varying float vLight;
varying float vBlock;
varying float vDist;
varying float vFlag;
void main() {
    vec4 c = texture2D(uTex, vUv);
    if (c.a < uCutout) discard;
    float sky = vLight * mix(0.16, 1.0, uDaylight);
    // Torch light is slightly warm where it outshines the sky; sunlight turns golden at sunrise and sunset.
    vec3 warm = mix(vec3(1.0), vec3(1.08, 0.93, 0.74), clamp((vBlock - sky) * 3.0, 0.0, 1.0));
    vec3 light = vLight > 1.5 ? vec3(1.0) : max(vec3(sky) * uSunTint, vec3(vBlock) * warm);
    vec3 col = c.rgb * light * uTint;
    float a = c.a;
    if (vFlag > 2.5) {
        // Water reflects the sky more when seen from further away.
        float r = clamp(vDist / 40.0, 0.15, 0.7);
        col = mix(col, uFogColor * max(sky, 0.15) * 1.1, r * 0.55);
        a = mix(a, 0.92, r * 0.6);
    }
    float f = clamp((vDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);
    gl_FragColor = vec4(mix(col, uFogColor, f), a);
}
"""

    /** Plain block shaders, used if the ones above don't work on a device (no swaying, no golden light). */
    const val BLOCK_VS_BASIC = """attribute vec3 aPos;
attribute vec2 aUv;
attribute float aLight;
attribute float aBlock;
uniform mat4 uViewProj;
uniform vec3 uOffset;
uniform vec3 uCamPos;
varying vec2 vUv;
varying float vLight;
varying float vBlock;
varying float vDist;
void main() {
    vec3 wp = aPos + uOffset;
    gl_Position = uViewProj * vec4(wp, 1.0);
    vUv = aUv;
    vLight = aLight;
    vBlock = aBlock - floor(aBlock * 0.25 + 0.01) * 4.0;
    vDist = length(wp.xz - uCamPos.xz);
}
"""

    const val BLOCK_FS_BASIC = FRAG_PRECISION + """uniform sampler2D uTex;
uniform float uDaylight;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform float uCutout;
uniform vec3 uTint;
varying vec2 vUv;
varying float vLight;
varying float vBlock;
varying float vDist;
void main() {
    vec4 c = texture2D(uTex, vUv);
    if (c.a < uCutout) discard;
    float sky = vLight * mix(0.16, 1.0, uDaylight);
    float l = vLight > 1.5 ? 1.0 : max(sky, vBlock);
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
varying float vShade;
void main() {
    gl_Position = uViewProj * vec4(aPos, 1.0);
    vUv = aUv;
    vDist = length(aPos.xz - uCamPos.xz);
    vShade = aLight;
}
"""

    /** aLight: 0..1 darkens the colour (cloud sides); 2..3 instead fades the alpha (sky dome, stars). */
    const val SIMPLE_FS = FRAG_PRECISION + """uniform sampler2D uTex;
uniform vec4 uColor;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
varying vec2 vUv;
varying float vDist;
varying float vShade;
void main() {
    vec4 c = texture2D(uTex, vUv) * uColor;
    if (vShade > 1.5) c.a *= vShade - 2.0; else c.rgb *= vShade;
    if (c.a < 0.01) discard;
    float f = uFogEnd > 0.0 ? clamp((vDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0) : 0.0;
    gl_FragColor = vec4(c.rgb, c.a * (1.0 - f));
}
"""
}
