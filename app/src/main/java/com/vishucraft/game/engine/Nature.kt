package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.World
import java.util.Random
import kotlin.math.abs

/** Random ticks: crops grow, saplings become trees, grass spreads onto dirt. */
class Nature(private val world: World, private val set: (Int, Int, Int, Int, Int) -> Unit) {
    private val rnd = Random()
    private var timer = 0f

    fun tick(dt: Float, px: Int, pz: Int) {
        timer += dt
        if (timer < 0.1f) return
        timer = 0f
        val pcx = px shr 4; val pcz = pz shr 4
        for (cz in pcz - 4..pcz + 4) for (cx in pcx - 4..pcx + 4) {
            val c = world.getChunk(cx, cz) ?: continue
            repeat(64) {
                val lx = rnd.nextInt(16); val lz = rnd.nextInt(16); val y = 1 + rnd.nextInt(Chunk.HEIGHT - 2)
                val id = c.get(lx, y, lz)
                if (id == Blocks.AIR || id == Blocks.STONE) return@repeat
                randomTick(cx * 16 + lx, y, cz * 16 + lz, id, c.getMeta(lx, y, lz))
            }
        }
    }

    private fun randomTick(x: Int, y: Int, z: Int, id: Int, meta: Int) {
        when {
            Blocks.isCrop(id) -> if (meta < 7 && rnd.nextFloat() < 0.6f) set(x, y, z, id, meta + 1)
            Blocks.isSapling(id) -> if (rnd.nextFloat() < 0.15f) growTree(x, y, z, id - Blocks.SAPLING_FIRST)
            id == Blocks.DIRT -> {
                if (Blocks.opaque[world.getBlock(x, y + 1, z)]) return
                for (d in 0 until 4) {
                    val nx = x + rnd.nextInt(3) - 1; val ny = y + rnd.nextInt(3) - 1; val nz = z + rnd.nextInt(3) - 1
                    if (world.getBlock(nx, ny, nz) == Blocks.GRASS) { set(x, y, z, Blocks.GRASS, 0); return }
                }
            }
        }
    }

    /** Bone meal: speeds up a crop or sapling, or sprinkles flowers on grass. */
    fun boneMeal(x: Int, y: Int, z: Int): Boolean {
        val id = world.getBlock(x, y, z)
        val meta = world.getMeta(x, y, z)
        when {
            Blocks.isCrop(id) -> { if (meta >= 7) return false; set(x, y, z, id, minOf(7, meta + 2 + rnd.nextInt(4))) }
            Blocks.isSapling(id) -> if (rnd.nextFloat() < 0.45f) growTree(x, y, z, id - Blocks.SAPLING_FIRST)
            id == Blocks.GRASS -> repeat(12) {
                val fx = x + rnd.nextInt(7) - 3; val fz = z + rnd.nextInt(7) - 3
                if (world.getBlock(fx, y, fz) == Blocks.GRASS && world.getBlock(fx, y + 1, fz) == Blocks.AIR) {
                    set(fx, y + 1, fz, when (rnd.nextInt(6)) { 0 -> Blocks.FLOWER_RED; 1 -> Blocks.FLOWER_YELLOW; else -> Blocks.TALL_GRASS }, 0)
                }
            }
            else -> return false
        }
        return true
    }

    /** Grows a tree of the sapling's wood type if there is room. */
    fun growTree(x: Int, y: Int, z: Int, type: Int): Boolean {
        val (log, leaves) = when (type) {
            1 -> Blocks.SPRUCE_LOG to Blocks.SPRUCE_LEAVES
            2 -> Blocks.BIRCH_LOG to Blocks.BIRCH_LEAVES
            3 -> Blocks.JUNGLE_LOG to Blocks.JUNGLE_LEAVES
            4 -> Blocks.ACACIA_LOG to Blocks.ACACIA_LEAVES
            5 -> Blocks.DARK_OAK_LOG to Blocks.DARK_OAK_LEAVES
            else -> Blocks.LOG to Blocks.LEAVES
        }
        val trunk = when (type) { 1 -> 6 + rnd.nextInt(3); 3 -> 7 + rnd.nextInt(5); else -> 4 + rnd.nextInt(3) }
        if (y + trunk + 3 >= Chunk.HEIGHT) return false
        for (i in 1..trunk + 1) if (world.getBlock(x, y + i, z) != Blocks.AIR) return false
        fun leaf(lx: Int, ly: Int, lz: Int) {
            val b = world.getBlock(lx, ly, lz)
            if (b == Blocks.AIR || Blocks[b].render == com.vishucraft.game.world.RenderType.CROSS) set(lx, ly, lz, leaves, 0)
        }
        val top = y + trunk
        if (type == 1) {
            var radius = 0
            for (ly in top downTo y + 2) {
                for (dz in -radius..radius) for (dx in -radius..radius) if (abs(dx) + abs(dz) <= radius + 1) leaf(x + dx, ly, z + dz)
                radius = if (radius >= 2) 1 else radius + 1
            }
            leaf(x, top + 1, z)
        } else {
            for (ly in top - 3..top) {
                val r = if (ly >= top - 1) 1 else 2
                for (dz in -r..r) for (dx in -r..r) {
                    if (abs(dx) == r && abs(dz) == r && (ly == top || rnd.nextBoolean())) continue
                    leaf(x + dx, ly, z + dz)
                }
            }
            leaf(x, top + 1, z)
        }
        for (i in 0 until trunk) set(x, y + i, z, log, 0)
        return true
    }
}
