package android.opengl;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/**
 * Browser stand-in for Android's GLES20 class: the shared renderer calls these and they forward to WebGL 1
 * (the same API as OpenGL ES 2.0). WebGL objects live in a table on the JavaScript side (VC.o, see gl.js) and the
 * game refers to them by their index there, as it would with OpenGL names.
 */
public final class GLES20 {
    private GLES20() {}

    public static final int GL_ARRAY_BUFFER = 0x8892;
    public static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    public static final int GL_STATIC_DRAW = 0x88E4;
    public static final int GL_DYNAMIC_DRAW = 0x88E8;
    public static final int GL_STREAM_DRAW = 0x88E0;
    public static final int GL_BACK = 0x405;
    public static final int GL_BLEND = 0xBE2;
    public static final int GL_CCW = 0x901;
    public static final int GL_CLAMP_TO_EDGE = 0x812F;
    public static final int GL_COLOR_BUFFER_BIT = 0x4000;
    public static final int GL_DEPTH_BUFFER_BIT = 0x100;
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_CULL_FACE = 0xB44;
    public static final int GL_DEPTH_TEST = 0xB71;
    public static final int GL_FLOAT = 0x1406;
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_VERTEX_SHADER = 0x8B31;
    public static final int GL_LEQUAL = 0x203;
    public static final int GL_LINES = 0x1;
    public static final int GL_TRIANGLES = 0x4;
    public static final int GL_NEAREST = 0x2600;
    public static final int GL_LINEAR = 0x2601;
    public static final int GL_SRC_ALPHA = 0x302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x303;
    public static final int GL_POLYGON_OFFSET_FILL = 0x8037;
    public static final int GL_TEXTURE0 = 0x84C0;
    public static final int GL_TEXTURE_2D = 0xDE1;
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    public static final int GL_TEXTURE_WRAP_S = 0x2802;
    public static final int GL_TEXTURE_WRAP_T = 0x2803;
    public static final int GL_UNSIGNED_SHORT = 0x1403;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_RGBA = 0x1908;

    @JSBody(params = {"t"}, script = "VC.gl.activeTexture(t);") public static native void glActiveTexture(int t);
    @JSBody(params = {"p", "s"}, script = "VC.gl.attachShader(VC.o[p], VC.o[s]);") public static native void glAttachShader(int p, int s);
    @JSBody(params = {"p", "i", "n"}, script = "VC.gl.bindAttribLocation(VC.o[p], i, n);") public static native void glBindAttribLocation(int p, int i, String n);
    @JSBody(params = {"t", "b"}, script = "VC.gl.bindBuffer(t, b ? VC.o[b] : null);") public static native void glBindBuffer(int t, int b);
    @JSBody(params = {"t", "b"}, script = "VC.gl.bindTexture(t, b ? VC.o[b] : null);") public static native void glBindTexture(int t, int tex);
    @JSBody(params = {"s", "d"}, script = "VC.gl.blendFunc(s, d);") public static native void glBlendFunc(int s, int d);

    @JSBody(params = {"t", "d", "n", "u"}, script = "VC.gl.bufferData(t, d.subarray(0, n), u);")
    private static native void bufferF(int t, @JSByRef float[] d, int n, int u);
    @JSBody(params = {"t", "d", "n", "u"}, script = "VC.gl.bufferData(t, d.subarray(0, n), u);")
    private static native void bufferS(int t, @JSByRef short[] d, int n, int u);
    @JSBody(params = {"t", "size", "u"}, script = "VC.gl.bufferData(t, size, u);")
    private static native void bufferEmpty(int t, int size, int u);

    /** Buffers come from Buffers (wrapArrays mode), so they are backed by arrays starting at 0. */
    public static void glBufferData(int target, int size, Buffer data, int usage) {
        if (data instanceof FloatBuffer) {
            FloatBuffer f = (FloatBuffer) data;
            bufferF(target, f.array(), f.limit(), usage);
        } else if (data instanceof ShortBuffer) {
            ShortBuffer s = (ShortBuffer) data;
            bufferS(target, s.array(), s.limit(), usage);
        } else bufferEmpty(target, size, usage);
    }

    /** Uploads [n] floats straight from an array (the UI's vertex batches). */
    public static void bufferFloats(int target, float[] data, int n, int usage) { bufferF(target, data, n, usage); }

    @JSBody(params = {"m"}, script = "VC.gl.clear(m);") public static native void glClear(int m);
    @JSBody(params = {"r", "g", "b", "a"}, script = "VC.gl.clearColor(r, g, b, a);") public static native void glClearColor(float r, float g, float b, float a);
    @JSBody(params = {"s"}, script = "VC.gl.compileShader(VC.o[s]);") public static native void glCompileShader(int s);
    @JSBody(script = "return VC.add(VC.gl.createProgram());") public static native int glCreateProgram();
    @JSBody(params = {"t"}, script = "return VC.add(VC.gl.createShader(t));") public static native int glCreateShader(int t);
    @JSBody(params = {"m"}, script = "VC.gl.cullFace(m);") public static native void glCullFace(int m);

    @JSBody(params = {"b"}, script = "VC.gl.deleteBuffer(VC.o[b]); VC.free(b);") private static native void deleteBuffer(int b);
    @JSBody(params = {"b"}, script = "VC.gl.deleteTexture(VC.o[b]); VC.free(b);") private static native void deleteTexture(int b);
    public static void glDeleteBuffers(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) if (ids[offset + i] != 0) deleteBuffer(ids[offset + i]); }
    public static void glDeleteTextures(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) if (ids[offset + i] != 0) deleteTexture(ids[offset + i]); }

    @JSBody(params = {"s"}, script = "VC.gl.deleteShader(VC.o[s]); VC.free(s);") public static native void glDeleteShader(int s);
    @JSBody(params = {"f"}, script = "VC.gl.depthFunc(f);") public static native void glDepthFunc(int f);
    @JSBody(params = {"b"}, script = "VC.gl.depthMask(b);") public static native void glDepthMask(boolean b);
    @JSBody(params = {"c"}, script = "VC.gl.disable(c);") public static native void glDisable(int c);
    @JSBody(params = {"i"}, script = "VC.gl.disableVertexAttribArray(i);") public static native void glDisableVertexAttribArray(int i);
    @JSBody(params = {"m", "f", "c"}, script = "VC.gl.drawArrays(m, f, c);") public static native void glDrawArrays(int m, int first, int count);
    @JSBody(params = {"m", "c", "t", "o"}, script = "VC.gl.drawElements(m, c, t, o);") public static native void glDrawElements(int m, int count, int type, int offset);
    @JSBody(params = {"c"}, script = "VC.gl.enable(c);") public static native void glEnable(int c);
    @JSBody(params = {"i"}, script = "VC.gl.enableVertexAttribArray(i);") public static native void glEnableVertexAttribArray(int i);
    @JSBody(params = {"m"}, script = "VC.gl.frontFace(m);") public static native void glFrontFace(int m);

    @JSBody(script = "return VC.add(VC.gl.createBuffer());") private static native int createBuffer();
    @JSBody(script = "return VC.add(VC.gl.createTexture());") private static native int createTexture();
    public static void glGenBuffers(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) ids[offset + i] = createBuffer(); }
    public static void glGenTextures(int n, int[] ids, int offset) { for (int i = 0; i < n; i++) ids[offset + i] = createTexture(); }

    @JSBody(params = {"p"}, script = "return VC.gl.getProgramInfoLog(VC.o[p]) || '';") public static native String glGetProgramInfoLog(int p);
    @JSBody(params = {"p", "n"}, script = "var v = VC.gl.getProgramParameter(VC.o[p], n); return v === true ? 1 : v === false ? 0 : (v|0);")
    private static native int programParam(int p, int n);
    public static void glGetProgramiv(int p, int name, int[] out, int offset) { out[offset] = programParam(p, name); }
    @JSBody(params = {"s"}, script = "return VC.gl.getShaderInfoLog(VC.o[s]) || '';") public static native String glGetShaderInfoLog(int s);
    @JSBody(params = {"s", "n"}, script = "var v = VC.gl.getShaderParameter(VC.o[s], n); return v === true ? 1 : v === false ? 0 : (v|0);")
    private static native int shaderParam(int s, int n);
    public static void glGetShaderiv(int s, int name, int[] out, int offset) { out[offset] = shaderParam(s, name); }
    /** Uniform locations are objects in WebGL: kept in the table once per program and name. */
    @JSBody(params = {"p", "n"}, script = "return VC.uniform(p, n);") public static native int glGetUniformLocation(int p, String n);
    @JSBody(params = {"w"}, script = "VC.gl.lineWidth(w);") public static native void glLineWidth(float w);
    @JSBody(params = {"p"}, script = "VC.gl.linkProgram(VC.o[p]);") public static native void glLinkProgram(int p);
    @JSBody(params = {"f", "u"}, script = "VC.gl.polygonOffset(f, u);") public static native void glPolygonOffset(float f, float u);
    @JSBody(params = {"s", "src"}, script = "VC.gl.shaderSource(VC.o[s], src);") public static native void glShaderSource(int shader, String src);
    @JSBody(params = {"t", "n", "v"}, script = "VC.gl.texParameteri(t, n, v);") public static native void glTexParameteri(int t, int n, int v);
    @JSBody(params = {"l", "v"}, script = "if (l) VC.gl.uniform1f(VC.o[l], v);") public static native void glUniform1f(int l, float v);
    @JSBody(params = {"l", "v"}, script = "if (l) VC.gl.uniform1i(VC.o[l], v);") public static native void glUniform1i(int l, int v);
    @JSBody(params = {"l", "a", "b"}, script = "if (l) VC.gl.uniform2f(VC.o[l], a, b);") public static native void glUniform2f(int l, float a, float b);
    @JSBody(params = {"l", "a", "b", "c"}, script = "if (l) VC.gl.uniform3f(VC.o[l], a, b, c);") public static native void glUniform3f(int l, float a, float b, float c);
    @JSBody(params = {"l", "a", "b", "c", "d"}, script = "if (l) VC.gl.uniform4f(VC.o[l], a, b, c, d);") public static native void glUniform4f(int l, float a, float b, float c, float d);
    @JSBody(params = {"l", "v", "o", "n"}, script = "if (l) VC.gl.uniformMatrix4fv(VC.o[l], false, v.subarray(o, o + n));")
    private static native void uniformMatrix(int l, @JSByRef float[] v, int o, int n);
    public static void glUniformMatrix4fv(int l, int count, boolean transpose, float[] v, int offset) { uniformMatrix(l, v, offset, 16 * count); }
    @JSBody(params = {"p"}, script = "VC.gl.useProgram(p ? VC.o[p] : null);") public static native void glUseProgram(int p);
    @JSBody(params = {"i", "s", "t", "n", "st", "o"}, script = "VC.gl.vertexAttribPointer(i, s, t, n, st, o);")
    public static native void glVertexAttribPointer(int i, int size, int type, boolean norm, int stride, int offset);
    @JSBody(params = {"x", "y", "w", "h"}, script = "VC.gl.viewport(x, y, w, h);") public static native void glViewport(int x, int y, int w, int h);

    /** Uploads ARGB pixels (as Android bitmaps hold them) as an RGBA texture. */
    @JSBody(params = {"w", "h", "px"}, script = "VC.texImage(w, h, px);")
    public static native void texImageArgb(int w, int h, @JSByRef int[] px);
}
