package android.opengl;

import android.graphics.Bitmap;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/** Desktop stand-in: uploads an ARGB [Bitmap] as an RGBA texture. */
public final class GLUtils {
    private GLUtils() {}

    public static void texImage2D(int target, int level, Bitmap bmp, int border) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = bmp.pixels();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int c : px) {
            buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
        }
        buf.flip();
        GL11.glTexImage2D(target, level, GL11.GL_RGBA, w, h, border, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
    }
}
