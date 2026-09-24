package com.vishucraft.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.vishucraft.game.render.TextureAtlas
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.RenderType

/** Renders small isometric block icons from the procedural atlas for the HUD. */
object BlockIcons {
    private const val S = 64
    private val cache = HashMap<Int, Bitmap>()
    private val atlas: Bitmap by lazy {
        Bitmap.createBitmap(TextureAtlas.pixels, TextureAtlas.SIZE, TextureAtlas.SIZE, Bitmap.Config.ARGB_8888)
    }

    fun tile(index: Int): Bitmap {
        val t = TextureAtlas.TILE
        return Bitmap.createBitmap(atlas, (index % 16) * t, (index / 16) * t, t, t)
    }

    fun get(id: Int): Bitmap = cache.getOrPut(id) { render(id) }

    private fun render(id: Int): Bitmap {
        val def = Blocks[id]
        val out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        val t = TextureAtlas.TILE.toFloat()
        if (def.render == RenderType.CROSS) {
            val m = Matrix().apply { setScale(S / t, S / t) }
            c.drawBitmap(tile(def.top), m, paint)
            return out
        }
        val src = floatArrayOf(0f, 0f, t, 0f, 0f, t)
        fun face(tileIndex: Int, dst: FloatArray, shade: Int) {
            val m = Matrix()
            m.setPolyToPoly(src, 0, dst, 0, 3)
            paint.colorFilter = LightingColorFilter(shade, 0)
            c.drawBitmap(tile(tileIndex), m, paint)
        }
        val s = S.toFloat()
        // Top rhombus, then left and right sides with darker shading.
        face(def.top, floatArrayOf(0f, s * 0.25f, s * 0.5f, 0f, s * 0.5f, s * 0.5f), 0xFFFFFFFF.toInt())
        face(def.side, floatArrayOf(0f, s * 0.25f, s * 0.5f, s * 0.5f, 0f, s * 0.75f), 0xFFCCCCCC.toInt())
        face(def.side, floatArrayOf(s * 0.5f, s * 0.5f, s, s * 0.25f, s * 0.5f, s), 0xFF999999.toInt())
        return out
    }
}
