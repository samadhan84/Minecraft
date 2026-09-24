package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.World
import kotlin.math.abs
import kotlin.math.floor

class RayHit(val x: Int, val y: Int, val z: Int, val nx: Int, val ny: Int, val nz: Int, val block: Int)

/** Amanatides & Woo voxel traversal. Water and air are passed through. */
object Raycast {
    fun cast(world: World, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): RayHit? {
        var x = floor(ox).toInt(); var y = floor(oy).toInt(); var z = floor(oz).toInt()
        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1
        val tDeltaX = if (dx != 0f) abs(1f / dx) else Float.MAX_VALUE
        val tDeltaY = if (dy != 0f) abs(1f / dy) else Float.MAX_VALUE
        val tDeltaZ = if (dz != 0f) abs(1f / dz) else Float.MAX_VALUE
        var tMaxX = if (dx != 0f) ((if (dx > 0) x + 1 - ox else ox - x) * tDeltaX) else Float.MAX_VALUE
        var tMaxY = if (dy != 0f) ((if (dy > 0) y + 1 - oy else oy - y) * tDeltaY) else Float.MAX_VALUE
        var tMaxZ = if (dz != 0f) ((if (dz > 0) z + 1 - oz else oz - z) * tDeltaZ) else Float.MAX_VALUE
        var nx = 0; var ny = 0; var nz = 0
        var t = 0f
        while (t <= maxDist) {
            val b = world.getBlock(x, y, z)
            if (b != Blocks.AIR && b != Blocks.WATER) return RayHit(x, y, z, nx, ny, nz, b)
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX; t = tMaxX; tMaxX += tDeltaX; nx = -stepX; ny = 0; nz = 0
            } else if (tMaxY < tMaxZ) {
                y += stepY; t = tMaxY; tMaxY += tDeltaY; nx = 0; ny = -stepY; nz = 0
            } else {
                z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ; nx = 0; ny = 0; nz = -stepZ
            }
        }
        return null
    }
}
