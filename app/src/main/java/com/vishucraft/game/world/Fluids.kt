package com.vishucraft.game.world

import com.vishucraft.game.render.ChunkMesher

/**
 * Flowing water and lava. Meta 0 is a source block; 1..7 is the distance from a source.
 * Water spreads 7 blocks and updates 4x a second; lava spreads 3 blocks and updates once a second.
 * Two water sources side by side create a new source; water meeting lava makes obsidian or cobblestone.
 */
class Fluids(private val world: World, private val set: (Int, Int, Int, Int, Int) -> Unit) {
    private val pendingWater = LinkedHashSet<Long>()
    private val pendingLava = LinkedHashSet<Long>()
    private var waterTimer = 0f
    private var lavaTimer = 0f
    private val N = ChunkMesher.NORMALS

    private fun maxLevel(id: Int) = if (id == Blocks.LAVA) 3 else 7

    /** Call whenever a block changes so nearby liquids re-evaluate. */
    fun onChange(x: Int, y: Int, z: Int) {
        schedule(x, y, z)
        for (n in N) schedule(x + n[0], y + n[1], z + n[2])
    }

    private fun schedule(x: Int, y: Int, z: Int) {
        if (y < 0 || y >= Chunk.HEIGHT) return
        val id = world.getBlock(x, y, z)
        val p = RedstoneIds.pack(x, y, z)
        when (id) {
            Blocks.WATER -> pendingWater.add(p)
            Blocks.LAVA -> pendingLava.add(p)
        }
    }

    fun tick(dt: Float) {
        waterTimer += dt; lavaTimer += dt
        if (waterTimer >= 0.25f) { waterTimer = 0f; process(pendingWater) }
        if (lavaTimer >= 1f) { lavaTimer = 0f; process(pendingLava) }
    }

    private fun process(queue: LinkedHashSet<Long>) {
        if (queue.isEmpty()) return
        val batch = ArrayList<Long>(minOf(queue.size, 400))
        val it = queue.iterator()
        while (it.hasNext() && batch.size < 400) { batch.add(it.next()); it.remove() }
        for (p in batch) update(RedstoneIds.x(p), RedstoneIds.y(p), RedstoneIds.z(p))
    }

    private fun canFlowInto(id: Int) = id == Blocks.AIR || Blocks[id].render == RenderType.CROSS ||
        Blocks[id].render == RenderType.FLAT

    private fun update(x: Int, y: Int, z: Int) {
        if (!world.isLoaded(x, z)) return
        val id = world.getBlock(x, y, z)
        if (!Blocks.isLiquid(id)) return
        val meta = world.getMeta(x, y, z)

        // Water and lava meeting.
        val other = if (id == Blocks.WATER) Blocks.LAVA else Blocks.WATER
        for (n in N) {
            val nx = x + n[0]; val ny = y + n[1]; val nz = z + n[2]
            if (world.getBlock(nx, ny, nz) != other) continue
            if (id == Blocks.LAVA) { set(x, y, z, if (meta == 0) Blocks.OBSIDIAN else Blocks.COBBLESTONE, 0); return }
            val lavaMeta = world.getMeta(nx, ny, nz)
            set(nx, ny, nz, if (lavaMeta == 0) Blocks.OBSIDIAN else Blocks.COBBLESTONE, 0)
        }

        var level = meta
        if (meta != 0) {
            // A flowing block is fed from above or by a neighbour with a lower level.
            var feed = Int.MAX_VALUE
            var sources = 0
            if (world.getBlock(x, y + 1, z) == id) feed = 0
            for (f in 2 until 6) {
                val n = N[f]
                if (world.getBlock(x + n[0], y, z + n[2]) != id) continue
                val m = world.getMeta(x + n[0], y, z + n[2])
                if (m == 0) sources++
                feed = minOf(feed, m)
            }
            val below = world.getBlock(x, y - 1, z)
            level = when {
                id == Blocks.WATER && sources >= 2 && (Blocks.solid[below] || (below == id && world.getMeta(x, y - 1, z) == 0)) -> 0
                feed == Int.MAX_VALUE -> 99
                else -> feed + 1
            }
            if (level > maxLevel(id)) { set(x, y, z, Blocks.AIR, 0); return }
            if (level != meta) set(x, y, z, id, level)
        }

        // Spread: straight down first, otherwise sideways.
        val below = world.getBlock(x, y - 1, z)
        if (y > 0 && canFlowInto(below)) {
            set(x, y - 1, z, id, 1)
            return
        }
        if (below == id && level != 0) return
        if (level >= maxLevel(id)) return
        for (f in 2 until 6) {
            val n = N[f]
            val nx = x + n[0]; val nz = z + n[2]
            val b = world.getBlock(nx, y, nz)
            if (canFlowInto(b)) set(nx, y, nz, id, level + 1)
            else if (b == id && world.getMeta(nx, y, nz) > level + 1) set(nx, y, nz, id, level + 1)
        }
    }
}
