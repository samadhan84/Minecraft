package com.vishucraft.game.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.vishucraft.game.render.TextureAtlas
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Facing
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.RenderType

/** Renders HUD icons: isometric blocks, flat sprites and item art (with a glint for enchanted items). */
object BlockIcons {
    private const val S = 64
    private val cache = HashMap<Int, Bitmap>()
    private val atlas: Bitmap by lazy {
        Bitmap.createBitmap(TextureAtlas.pixels, TextureAtlas.SIZE, TextureAtlas.SIZE, Bitmap.Config.ARGB_8888)
    }

    fun tile(index: Int): Bitmap {
        val t = TextureAtlas.TILE
        val row = TextureAtlas.TILES_PER_ROW
        return Bitmap.createBitmap(atlas, (index % row) * t, (index / row) * t, t, t)
    }

    /** Icon for a hotbar slot value: a block id or an item id. */
    fun get(slot: Int): Bitmap = cache.getOrPut(slot) {
        val item = Items[slot]
        if (item != null) sprite(item.icon, item.enchanted) else render(slot)
    }

    private fun sprite(tileIndex: Int, glint: Boolean): Bitmap {
        val out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val t = TextureAtlas.TILE.toFloat()
        c.drawBitmap(tile(tileIndex), Matrix().apply { setScale(S / t, S / t) }, Paint().apply { isFilterBitmap = false })
        if (glint) {
            // Purple shimmer bands, only where the sprite has pixels.
            val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
            for (i in -S until S * 2 step 22) {
                p.color = Color.argb(120, 190, 110, 255)
                val path = android.graphics.Path().apply {
                    moveTo(i.toFloat(), 0f); lineTo(i + 10f, 0f); lineTo(i + 10f - S, S.toFloat()); lineTo(i - S.toFloat(), S.toFloat()); close()
                }
                c.drawPath(path, p)
            }
        }
        return out
    }

    private fun render(id: Int): Bitmap {
        val def = Blocks[id]
        if (def.render == RenderType.CROSS || def.render == RenderType.FLAT) return sprite(def.top, false)
        val out = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        val t = TextureAtlas.TILE.toFloat()
        val h = when {
            def.render == RenderType.BOX -> (def.box!![4]).coerceAtLeast(0.25f)
            com.vishucraft.game.world.Blocks.isPlate(id) -> 0.2f
            else -> 1f
        }
        val s = S.toFloat()
        val drop = (1f - h) * s * 0.5f
        fun face(tileIndex: Int, src: FloatArray, dst: FloatArray, shade: Int) {
            val m = Matrix()
            m.setPolyToPoly(src, 0, dst, 0, 3)
            paint.colorFilter = LightingColorFilter(shade, 0)
            c.drawBitmap(tile(tileIndex), m, paint)
        }
        val full = floatArrayOf(0f, 0f, t, 0f, 0f, t)
        val sideSrc = floatArrayOf(0f, t * (1 - h), t, t * (1 - h), 0f, t)
        val top = if (def.facing == Facing.ALL) def.front else def.top
        val left = if (def.facing == Facing.HORIZONTAL) def.front else def.side
        // Top rhombus, then left and right sides with darker shading.
        face(top, full, floatArrayOf(0f, s * 0.25f + drop, s * 0.5f, drop, s * 0.5f, s * 0.5f + drop), 0xFFFFFFFF.toInt())
        face(left, sideSrc, floatArrayOf(0f, s * 0.25f + drop, s * 0.5f, s * 0.5f + drop, 0f, s * 0.75f), 0xFFCCCCCC.toInt())
        face(def.side, sideSrc, floatArrayOf(s * 0.5f, s * 0.5f + drop, s, s * 0.25f + drop, s * 0.5f, s), 0xFF999999.toInt())
        return out
    }
}
