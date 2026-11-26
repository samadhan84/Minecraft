package com.vishucraft.game.render

import com.vishucraft.game.world.Appearance
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Tiles
import kotlin.math.cos
import kotlin.math.sin

/**
 * A picture of the character in 3D for menus and the inventory: the same body the game draws in third person (every
 * coloured layer of the chosen look, and the armor that is really worn), turned by [yaw] and drawn with a tiny software
 * ray caster. The result is a grid of colours that a screen can paint as little squares.
 */
object DollRenderer {
    /** Pixels of the picture: 0 = nothing there, otherwise 0xFFRRGGBB. */
    private var modelKey = Int.MIN_VALUE
    private var parts: List<MobModels.Part> = emptyList()
    private var colors: HashMap<Int, Int>? = null

    private fun colorOf(tile: Int): Int {
        val map = colors ?: HashMap<Int, Int>().also { m ->
            for (n in 0 until 32) { val c = Appearance.palette[n]; m[Tiles.id("p4_c_$n")] = pack(c[0], c[1], c[2]) }
            for (n in 0 until 7) { val c = Appearance.armorRgb[n]; m[Tiles.id("p4_armor_$n")] = pack(c[0], c[1], c[2]) }
            m[Tiles.id("p4_shield")] = pack(130, 90, 54)
            colors = m
        }
        return map[tile] ?: pack(150, 150, 150)
    }

    private fun pack(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun shade(c: Int, k: Float): Int =
        pack(((c shr 16 and 255) * k).toInt(), ((c shr 8 and 255) * k).toInt(), ((c and 255) * k).toInt())

    /** The armor colour numbers (helmet, chestplate, leggings, boots; -1 for none) of what is worn. */
    fun armorColors(armor: Array<ItemStack?>?): IntArray =
        IntArray(4) { i -> armor?.getOrNull(i)?.let { Appearance.armorColor(Items[it.id]?.name ?: "") } ?: -1 }

    /** Draws [look] wearing [armor] ([armorColors]) turned by [yaw] radians into a [w] x [h] grid (row by row). */
    fun render(look: IntArray, armor: IntArray, shield: Boolean, yaw: Float, w: Int, h: Int): IntArray {
        var key = 17
        for (v in look) key = key * 31 + v
        for (m in armor) key = key * 11 + (m + 1)
        key = key * 2 + (if (shield) 1 else 0)
        if (key != modelKey) { modelKey = key; parts = MobModels.playerModel(look, armor, shield) }
        val out = IntArray(w * h)
        val s = h / 2.45f
        val cy = cos(yaw); val sy = sin(yaw)
        val dx = sy; val dz = cy
        for (py in 0 until h) {
            val y = (h - py - 0.5f) / s - 0.05f
            for (px in 0 until w) {
                val u = (px + 0.5f - w / 2f) / s
                val ox = u * cy - 5f * sy; val oz = -u * sy - 5f * cy
                var best = Float.MAX_VALUE; var face = -1; var tiles: IntArray? = null; var bb: FloatArray? = null
                for (p in parts) {
                    val b = p.b
                    if (y < b[1] || y > b[4]) continue
                    var tNear = -1e9f; var tFar = 1e9f; var f = -1
                    // X slab.
                    if (dx > -1e-6f && dx < 1e-6f) { if (ox < b[0] || ox > b[3]) continue }
                    else {
                        val t1 = (b[0] - ox) / dx; val t2 = (b[3] - ox) / dx
                        val enter = if (t1 < t2) t1 else t2; val exit = if (t1 < t2) t2 else t1
                        if (enter > tNear) { tNear = enter; f = if (dx > 0f) 5 else 4 }
                        if (exit < tFar) tFar = exit
                    }
                    // Z slab.
                    if (dz > -1e-6f && dz < 1e-6f) { if (oz < b[2] || oz > b[5]) continue }
                    else {
                        val t1 = (b[2] - oz) / dz; val t2 = (b[5] - oz) / dz
                        val enter = if (t1 < t2) t1 else t2; val exit = if (t1 < t2) t2 else t1
                        if (enter > tNear) { tNear = enter; f = if (dz > 0f) 3 else 2 }
                        if (exit < tFar) tFar = exit
                    }
                    if (tNear <= tFar && tFar > 0f && tNear < best && f >= 0) { best = tNear; face = f; tiles = p.tiles; bb = b }
                }
                if (tiles != null) {
                    var k = when (face) { 0 -> 1f; 1 -> 0.55f; 2, 3 -> 0.88f; else -> 0.68f }
                    // A darker line along the edges of every little box makes the shapes easy to see.
                    val b = bb!!
                    val hx = ox + dx * best; val hz = oz + dz * best
                    val edge = if (face >= 4) minOf(y - b[1], b[4] - y, hz - b[2], b[5] - hz) else minOf(y - b[1], b[4] - y, hx - b[0], b[3] - hx)
                    if (edge < 0.012f) k *= 0.72f
                    out[py * w + px] = shade(colorOf(tiles[face]), k)
                }
            }
        }
        return out
    }

    /** Calls [emit] (x, y, length, colour) for each horizontal run of one colour, so a picture can be drawn with few rectangles. */
    inline fun runs(pix: IntArray, w: Int, h: Int, emit: (Int, Int, Int, Int) -> Unit) {
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                val c = pix[y * w + x]
                if (c == 0) { x++; continue }
                var e = x + 1
                while (e < w && pix[y * w + e] == c) e++
                emit(x, y, e - x, c)
                x = e
            }
        }
    }
}
