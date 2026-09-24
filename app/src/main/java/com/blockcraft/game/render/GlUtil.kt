package com.blockcraft.game.render

import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

class Shader(vertexSrc: String, fragmentSrc: String) {
    val program: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vs = compile(GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GL_FRAGMENT_SHADER, fragmentSrc)
        program = glCreateProgram()
        glAttachShader(program, vs)
        glAttachShader(program, fs)
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

    fun ints(data: IntArray): IntBuffer {
        val b = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        b.put(data).flip()
        return b
    }
}

/** A shared index buffer describing quads as two triangles (0,1,2)(0,2,3). */
object QuadIndices {
    var ibo = 0
        private set
    private var capacity = 0

    fun reset() { ibo = 0; capacity = 0 }

    fun ensure(quads: Int) {
        if (quads <= capacity && ibo != 0) return
        var cap = maxOf(capacity, 16384)
        while (cap < quads) cap *= 2
        val idx = IntArray(cap * 6)
        for (q in 0 until cap) {
            val v = q * 4; val i = q * 6
            idx[i] = v; idx[i + 1] = v + 1; idx[i + 2] = v + 2
            idx[i + 3] = v; idx[i + 4] = v + 2; idx[i + 5] = v + 3
        }
        if (ibo == 0) {
            val ids = IntArray(1); glGenBuffers(1, ids, 0); ibo = ids[0]
        }
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, Buffers.ints(idx), GL_STATIC_DRAW)
        capacity = cap
    }
}

/** A VAO + VBO holding quads in the chunk vertex layout (x, y, z, u, v, light). */
class GpuMesh(private val usage: Int = GL_STATIC_DRAW) {
    private var vao = 0
    private var vbo = 0
    var quads = 0
        private set

    fun upload(data: FloatArray, floatCount: Int = data.size) {
        quads = floatCount / (FLOATS_PER_VERTEX * 4)
        if (quads == 0) return
        if (vao == 0) {
            val ids = IntArray(1)
            glGenVertexArrays(1, ids, 0); vao = ids[0]
            glGenBuffers(1, ids, 0); vbo = ids[0]
            glBindVertexArray(vao)
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            val stride = FLOATS_PER_VERTEX * 4
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 12)
            glEnableVertexAttribArray(2)
            glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 20)
            glBindVertexArray(0)
        }
        QuadIndices.ensure(quads)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, floatCount * 4, Buffers.floats(data, floatCount), usage)
    }

    fun draw(mode: Int = GL_TRIANGLES) {
        if (quads == 0 || vao == 0) return
        glBindVertexArray(vao)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, QuadIndices.ibo)
        glDrawElements(mode, quads * 6, GL_UNSIGNED_INT, 0)
        glBindVertexArray(0)
    }

    /** Draws raw vertices (used for line lists). */
    fun drawArrays(mode: Int, vertices: Int) {
        if (vao == 0) return
        glBindVertexArray(vao)
        glDrawArrays(mode, 0, vertices)
        glBindVertexArray(0)
    }

    fun delete() {
        if (vao != 0) {
            glDeleteVertexArrays(1, intArrayOf(vao), 0)
            glDeleteBuffers(1, intArrayOf(vbo), 0)
        }
        vao = 0; vbo = 0; quads = 0
    }

    /** Forget GL names after a context loss without deleting them. */
    fun invalidate() { vao = 0; vbo = 0; quads = 0 }
}

object Shaders {
    const val BLOCK_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in float aLight;
uniform mat4 uViewProj;
uniform vec3 uOffset;
uniform vec3 uCamPos;
out vec2 vUv;
out float vLight;
out float vDist;
void main() {
    vec3 wp = aPos + uOffset;
    gl_Position = uViewProj * vec4(wp, 1.0);
    vUv = aUv;
    vLight = aLight;
    vDist = length(wp.xz - uCamPos.xz);
}
"""

    const val BLOCK_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uDaylight;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform float uCutout;
uniform vec3 uTint;
in vec2 vUv;
in float vLight;
in float vDist;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv);
    if (c.a < uCutout) discard;
    float l = vLight > 1.5 ? 1.0 : vLight * mix(0.16, 1.0, uDaylight);
    vec3 col = c.rgb * l * uTint;
    float f = clamp((vDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);
    fragColor = vec4(mix(col, uFogColor, f), c.a);
}
"""

    const val SIMPLE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uViewProj;
uniform vec3 uCamPos;
out vec2 vUv;
out float vDist;
void main() {
    gl_Position = uViewProj * vec4(aPos, 1.0);
    vUv = aUv;
    vDist = length(aPos.xz - uCamPos.xz);
}
"""

    const val SIMPLE_FS = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec4 uColor;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
in vec2 vUv;
in float vDist;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv) * uColor;
    if (c.a < 0.01) discard;
    float f = uFogEnd > 0.0 ? clamp((vDist - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0) : 0.0;
    fragColor = vec4(c.rgb, c.a * (1.0 - f));
}
"""
}
