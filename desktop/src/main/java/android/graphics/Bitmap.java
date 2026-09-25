package android.graphics;

/** Desktop stand-in for the tiny part of Android's Bitmap the renderer uses: an ARGB pixel array. */
public final class Bitmap {
    public enum Config { ARGB_8888 }

    private final int[] pixels;
    private final int width;
    private final int height;

    private Bitmap(int[] pixels, int width, int height) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
        return new Bitmap(colors.clone(), width, height);
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int[] pixels() { return pixels; }
    public void recycle() {}
}
