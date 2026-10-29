package android.opengl;

import android.graphics.Bitmap;

/** Browser stand-in: uploads an ARGB [Bitmap] as an RGBA texture. */
public final class GLUtils {
    private GLUtils() {}

    public static void texImage2D(int target, int level, Bitmap bmp, int border) {
        GLES20.texImageArgb(bmp.getWidth(), bmp.getHeight(), bmp.pixels());
    }
}
