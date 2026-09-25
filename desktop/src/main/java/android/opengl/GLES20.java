package android.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

/**
 * Desktop stand-in for Android's GLES20 class: the shared renderer calls these, and they forward to desktop
 * OpenGL 2.1 through LWJGL. Only the functions and constants the game uses are provided.
 */
public final class GLES20 {
    private GLES20() {}

    public static final int GL_ARRAY_BUFFER = GL15.GL_ARRAY_BUFFER;
    public static final int GL_ELEMENT_ARRAY_BUFFER = GL15.GL_ELEMENT_ARRAY_BUFFER;
    public static final int GL_STATIC_DRAW = GL15.GL_STATIC_DRAW;
    public static final int GL_DYNAMIC_DRAW = GL15.GL_DYNAMIC_DRAW;
    public static final int GL_BACK = GL11.GL_BACK;
    public static final int GL_BLEND = GL11.GL_BLEND;
    public static final int GL_CCW = GL11.GL_CCW;
    public static final int GL_CLAMP_TO_EDGE = 0x812F;
    public static final int GL_COLOR_BUFFER_BIT = GL11.GL_COLOR_BUFFER_BIT;
    public static final int GL_DEPTH_BUFFER_BIT = GL11.GL_DEPTH_BUFFER_BIT;
    public static final int GL_COMPILE_STATUS = GL20.GL_COMPILE_STATUS;
    public static final int GL_LINK_STATUS = GL20.GL_LINK_STATUS;
    public static final int GL_CULL_FACE = GL11.GL_CULL_FACE;
    public static final int GL_DEPTH_TEST = GL11.GL_DEPTH_TEST;
    public static final int GL_FLOAT = GL11.GL_FLOAT;
    public static final int GL_FRAGMENT_SHADER = GL20.GL_FRAGMENT_SHADER;
    public static final int GL_VERTEX_SHADER = GL20.GL_VERTEX_SHADER;
    public static final int GL_LEQUAL = GL11.GL_LEQUAL;
    public static final int GL_LINES = GL11.GL_LINES;
    public static final int GL_TRIANGLES = GL11.GL_TRIANGLES;
    public static final int GL_NEAREST = GL11.GL_NEAREST;
    public static final int GL_SRC_ALPHA = GL11.GL_SRC_ALPHA;
    public static final int GL_ONE_MINUS_SRC_ALPHA = GL11.GL_ONE_MINUS_SRC_ALPHA;
    public static final int GL_POLYGON_OFFSET_FILL = GL11.GL_POLYGON_OFFSET_FILL;
    public static final int GL_TEXTURE0 = GL13.GL_TEXTURE0;
    public static final int GL_TEXTURE_2D = GL11.GL_TEXTURE_2D;
    public static final int GL_TEXTURE_MAG_FILTER = GL11.GL_TEXTURE_MAG_FILTER;
    public static final int GL_TEXTURE_MIN_FILTER = GL11.GL_TEXTURE_MIN_FILTER;
    public static final int GL_TEXTURE_WRAP_S = GL11.GL_TEXTURE_WRAP_S;
    public static final int GL_TEXTURE_WRAP_T = GL11.GL_TEXTURE_WRAP_T;
    public static final int GL_UNSIGNED_SHORT = GL11.GL_UNSIGNED_SHORT;
    public static final int GL_UNSIGNED_BYTE = GL11.GL_UNSIGNED_BYTE;
    public static final int GL_RGBA = GL11.GL_RGBA;

    public static void glActiveTexture(int t) { GL13.glActiveTexture(t); }
    public static void glAttachShader(int p, int s) { GL20.glAttachShader(p, s); }
    public static void glBindAttribLocation(int p, int i, String n) { GL20.glBindAttribLocation(p, i, n); }
    public static void glBindBuffer(int t, int b) { GL15.glBindBuffer(t, b); }
    public static void glBindTexture(int t, int tex) { GL11.glBindTexture(t, tex); }
    public static void glBlendFunc(int s, int d) { GL11.glBlendFunc(s, d); }

    public static void glBufferData(int target, int size, Buffer data, int usage) {
        if (data instanceof FloatBuffer) GL15.glBufferData(target, (FloatBuffer) data, usage);
        else if (data instanceof ShortBuffer) GL15.glBufferData(target, (ShortBuffer) data, usage);
        else if (data instanceof IntBuffer) GL15.glBufferData(target, (IntBuffer) data, usage);
        else if (data instanceof ByteBuffer) GL15.glBufferData(target, (ByteBuffer) data, usage);
        else GL15.glBufferData(target, (long) size, usage);
    }

    public static void glClear(int m) { GL11.glClear(m); }
    public static void glClearColor(float r, float g, float b, float a) { GL11.glClearColor(r, g, b, a); }
    public static void glCompileShader(int s) { GL20.glCompileShader(s); }
    public static int glCreateProgram() { return GL20.glCreateProgram(); }
    public static int glCreateShader(int t) { return GL20.glCreateShader(t); }
    public static void glCullFace(int m) { GL11.glCullFace(m); }

    public static void glDeleteBuffers(int n, int[] ids, int offset) {
        for (int i = 0; i < n; i++) GL15.glDeleteBuffers(ids[offset + i]);
    }

    public static void glDeleteShader(int s) { GL20.glDeleteShader(s); }
    public static void glDepthFunc(int f) { GL11.glDepthFunc(f); }
    public static void glDepthMask(boolean b) { GL11.glDepthMask(b); }
    public static void glDisable(int c) { GL11.glDisable(c); }
    public static void glDrawArrays(int m, int first, int count) { GL11.glDrawArrays(m, first, count); }
    public static void glDrawElements(int m, int count, int type, int offset) { GL11.glDrawElements(m, count, type, (long) offset); }
    public static void glEnable(int c) { GL11.glEnable(c); }
    public static void glEnableVertexAttribArray(int i) { GL20.glEnableVertexAttribArray(i); }
    public static void glFrontFace(int m) { GL11.glFrontFace(m); }

    public static void glGenBuffers(int n, int[] ids, int offset) {
        for (int i = 0; i < n; i++) ids[offset + i] = GL15.glGenBuffers();
    }

    public static void glGenTextures(int n, int[] ids, int offset) {
        for (int i = 0; i < n; i++) ids[offset + i] = GL11.glGenTextures();
    }

    public static String glGetProgramInfoLog(int p) { return GL20.glGetProgramInfoLog(p); }
    public static void glGetProgramiv(int p, int name, int[] out, int offset) { out[offset] = GL20.glGetProgrami(p, name); }
    public static String glGetShaderInfoLog(int s) { return GL20.glGetShaderInfoLog(s); }
    public static void glGetShaderiv(int s, int name, int[] out, int offset) { out[offset] = GL20.glGetShaderi(s, name); }
    public static int glGetUniformLocation(int p, String n) { return GL20.glGetUniformLocation(p, n); }
    public static void glLineWidth(float w) { GL11.glLineWidth(w); }
    public static void glLinkProgram(int p) { GL20.glLinkProgram(p); }
    public static void glPolygonOffset(float f, float u) { GL11.glPolygonOffset(f, u); }

    /**
     * The shaders are written for OpenGL ES 2.0. Desktop GLSL 1.20 is almost identical, except that it has no
     * precision statements, so those lines are dropped and a version line is added.
     */
    public static void glShaderSource(int shader, String src) {
        StringBuilder out = new StringBuilder("#version 120\n");
        for (String line : src.split("\n")) {
            if (line.trim().startsWith("precision ")) continue;
            out.append(line).append('\n');
        }
        GL20.glShaderSource(shader, out);
    }

    public static void glTexParameteri(int t, int n, int v) { GL11.glTexParameteri(t, n, v); }
    public static void glUniform1f(int l, float v) { GL20.glUniform1f(l, v); }
    public static void glUniform1i(int l, int v) { GL20.glUniform1i(l, v); }
    public static void glUniform3f(int l, float a, float b, float c) { GL20.glUniform3f(l, a, b, c); }
    public static void glUniform4f(int l, float a, float b, float c, float d) { GL20.glUniform4f(l, a, b, c, d); }

    public static void glUniformMatrix4fv(int l, int count, boolean transpose, float[] v, int offset) {
        float[] m = v;
        if (offset != 0 || v.length != 16 * count) {
            m = new float[16 * count];
            System.arraycopy(v, offset, m, 0, m.length);
        }
        GL20.glUniformMatrix4fv(l, transpose, m);
    }

    public static void glUseProgram(int p) { GL20.glUseProgram(p); }
    public static void glVertexAttribPointer(int i, int size, int type, boolean norm, int stride, int offset) {
        GL20.glVertexAttribPointer(i, size, type, norm, stride, (long) offset);
    }
    public static void glViewport(int x, int y, int w, int h) { GL11.glViewport(x, y, w, h); }
}
