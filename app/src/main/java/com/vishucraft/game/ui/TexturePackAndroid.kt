package com.vishucraft.game.ui

import android.content.Context
import android.graphics.BitmapFactory
import com.vishucraft.game.render.TextureAtlas
import java.io.File

/** Loads the player's own pictures (a texture pack) from the game's "texturepack" folder, once when the game starts. */
object TexturePackAndroid {
    private var loaded = -1

    fun folder(ctx: Context): File? = ctx.getExternalFilesDir("texturepack")?.apply { mkdirs() }

    /** Returns how many pictures were used. */
    @Synchronized fun load(ctx: Context): Int {
        if (loaded >= 0) return loaded
        var used = 0
        val dir = folder(ctx)
        if (dir != null) {
            try { File(dir, "tile-names.txt").writeText("Put PNG pictures here, named like these tiles (for example oak_planks.png), then restart the game.\n\n" + TextureAtlas.tileNames().joinToString("\n")) } catch (e: Exception) {}
            for (f in dir.listFiles { x -> x.isFile && x.name.endsWith(".png", ignoreCase = true) } ?: emptyArray()) {
                try {
                    val bmp = BitmapFactory.decodeFile(f.path) ?: continue
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    if (TextureAtlas.setTile(f.nameWithoutExtension, bmp.width, bmp.height, px)) used++
                } catch (e: Exception) { /* a picture that cannot be read is skipped */ }
            }
        }
        loaded = used
        return used
    }
}
