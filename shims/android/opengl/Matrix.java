package android.opengl;

/** Desktop stand-in for the three android.opengl.Matrix helpers the renderer uses (column-major 4x4). */
public final class Matrix {
    private Matrix() {}

    public static void perspectiveM(float[] m, int o, float fovy, float aspect, float near, float far) {
        float f = 1f / (float) Math.tan(Math.toRadians(fovy) / 2.0);
        float range = 1f / (near - far);
        for (int i = 0; i < 16; i++) m[o + i] = 0f;
        m[o] = f / aspect;
        m[o + 5] = f;
        m[o + 10] = (far + near) * range;
        m[o + 11] = -1f;
        m[o + 14] = 2f * far * near * range;
    }

    public static void setLookAtM(float[] rm, int o, float ex, float ey, float ez, float cx, float cy, float cz,
                                  float ux, float uy, float uz) {
        float fx = cx - ex, fy = cy - ey, fz = cz - ez;
        float rlf = 1f / (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx *= rlf; fy *= rlf; fz *= rlf;
        // s = f x up
        float sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux;
        float rls = 1f / (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx *= rls; sy *= rls; sz *= rls;
        // u = s x f
        float vx = sy * fz - sz * fy, vy = sz * fx - sx * fz, vz = sx * fy - sy * fx;
        rm[o] = sx; rm[o + 1] = vx; rm[o + 2] = -fx; rm[o + 3] = 0f;
        rm[o + 4] = sy; rm[o + 5] = vy; rm[o + 6] = -fy; rm[o + 7] = 0f;
        rm[o + 8] = sz; rm[o + 9] = vz; rm[o + 10] = -fz; rm[o + 11] = 0f;
        rm[o + 12] = -(sx * ex + sy * ey + sz * ez);
        rm[o + 13] = -(vx * ex + vy * ey + vz * ez);
        rm[o + 14] = fx * ex + fy * ey + fz * ez;
        rm[o + 15] = 1f;
    }

    public static void multiplyMM(float[] r, int ro, float[] a, int ao, float[] b, int bo) {
        float[] t = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) s += a[ao + k * 4 + row] * b[bo + c * 4 + k];
                t[c * 4 + row] = s;
            }
        }
        System.arraycopy(t, 0, r, ro, 16);
    }
}
