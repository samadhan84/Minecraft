package com.vishucraft.desktop

import com.vishucraft.game.render.TextureAtlas
import java.io.File
import javax.imageio.ImageIO

/** Loads the player's own pictures from the "texturepack" folder (next to the worlds folder): one PNG per tile, named like the tile. */
object TexturePackLoader {
    fun load(folder: File): Int {
        folder.mkdirs()
        // A list of every tile name, so you know what to call your pictures.
        try { File(folder, "tile-names.txt").writeText("Put PNG pictures here, named like these tiles (for example oak_planks.png), then restart the game.\n\n" + TextureAtlas.tileNames().joinToString("\n")) } catch (e: Exception) {}
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) } ?: return 0
        var used = 0
        for (f in files) {
            try {
                val img = ImageIO.read(f) ?: continue
                val px = IntArray(img.width * img.height)
                img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                if (TextureAtlas.setTile(f.nameWithoutExtension, img.width, img.height, px)) used++
            } catch (e: Exception) { /* a picture that cannot be read is skipped */ }
        }
        return used
    }
}
