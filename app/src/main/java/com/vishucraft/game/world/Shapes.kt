package com.vishucraft.game.world

/**
 * Boxes (x0, y0, z0, x1, y1, z1 inside the unit cell) for blocks that are not full cubes, used both for
 * drawing and for collision. Horizontal facing uses face indices 2 (+Z), 3 (-Z), 4 (+X), 5 (-X).
 */
object Shapes {
    const val FACING = 7
    const val OPEN = 8
    /** Door top half / trapdoor in the upper half / upside-down stairs. */
    const val UPPER = 16
    const val POWERED = 32

    private fun b(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) = floatArrayOf(x0, y0, z0, x1, y1, z1)
    private const val P = 1f / 16f

    /** A thin panel against side [f] of the cell, [t] thick. */
    private fun panel(f: Int, t: Float, y0: Float = 0f, y1: Float = 1f) = when (f) {
        2 -> b(0f, y0, 1 - t, 1f, y1, 1f)
        3 -> b(0f, y0, 0f, 1f, y1, t)
        4 -> b(1 - t, y0, 0f, 1f, y1, 1f)
        else -> b(0f, y0, 0f, t, y1, 1f)
    }

    private fun facing(meta: Int) = (meta and FACING).let { if (it < 2 || it > 5) 2 else it }

    /** Which side a door panel moves to when opened (a quarter turn). */
    private fun openSide(f: Int) = when (f) { 2 -> 5; 3 -> 4; 4 -> 2; else -> 3 }

    private val FENCES = (0 until Blocks.COUNT).filter { Blocks.isFence(it) }.toSet()

    fun connects(id: Int, neighbour: Int): Boolean = when (id) {
        in FENCES -> Blocks.isFence(neighbour) || Blocks.isGate(neighbour) || Blocks.opaque[neighbour]
        Blocks.GLASS_PANE, Blocks.IRON_BARS -> neighbour == Blocks.GLASS_PANE || neighbour == Blocks.IRON_BARS ||
            Blocks.opaque[neighbour] || neighbour == Blocks.GLASS
        else -> false
    }

    /**
     * @param neighbour returns the block id next to this one on horizontal face 2..5.
     * @param collision true for physics (fences are 1.5 high, open gates and doors let you through).
     */
    fun boxes(id: Int, meta: Int, collision: Boolean, neighbour: (Int) -> Int): List<FloatArray> {
        val f = facing(meta)
        val open = meta and OPEN != 0
        val upper = meta and UPPER != 0
        val kind = when {
            Blocks.isDoor(id) -> Blocks.OAK_DOOR
            Blocks.isTrapdoor(id) -> Blocks.OAK_TRAPDOOR
            Blocks.isFence(id) -> Blocks.OAK_FENCE
            Blocks.isGate(id) -> Blocks.OAK_FENCE_GATE
            Blocks.isBed(id) -> Blocks.BED_FIRST
            Blocks.isPlate(id) -> Blocks.PRESSURE_PLATE
            else -> id
        }
        return when (kind) {
            Blocks.OAK_DOOR -> listOf(panel(if (open) openSide(f) else f, 3 * P))
            Blocks.OAK_TRAPDOOR -> listOf(if (open) panel(f, 3 * P) else if (upper) b(0f, 13 * P, 0f, 1f, 1f, 1f) else b(0f, 0f, 0f, 1f, 3 * P, 1f))
            Blocks.OAK_STAIRS, Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.BRICK_STAIRS, Blocks.SANDSTONE_STAIRS -> {
                // The low step faces the player who placed it; the tall half is on the far side.
                val slab = if (upper) b(0f, 0.5f, 0f, 1f, 1f, 1f) else b(0f, 0f, 0f, 1f, 0.5f, 1f)
                val y0 = if (upper) 0f else 0.5f; val y1 = if (upper) 0.5f else 1f
                val step = when (f) {
                    2 -> b(0f, y0, 0f, 1f, y1, 0.5f)
                    3 -> b(0f, y0, 0.5f, 1f, y1, 1f)
                    4 -> b(0f, y0, 0f, 0.5f, y1, 1f)
                    else -> b(0.5f, y0, 0f, 1f, y1, 1f)
                }
                listOf(slab, step)
            }
            Blocks.OAK_FENCE -> {
                val h = if (collision) 1.5f else 1f
                val out = arrayListOf(b(6 * P, 0f, 6 * P, 10 * P, h, 10 * P))
                for (side in 2..5) {
                    if (!connects(id, neighbour(side))) continue
                    if (collision) out.add(arm(side, 6 * P, 1.5f, 6 * P, 10 * P))
                    else { out.add(arm(side, 6 * P, 9 * P, 7 * P, 9 * P)); out.add(arm(side, 12 * P, 15 * P, 7 * P, 9 * P)) }
                }
                out
            }
            Blocks.OAK_FENCE_GATE -> {
                val alongX = f == 2 || f == 3
                if (open) {
                    if (collision) emptyList()
                    else if (alongX) listOf(b(0f, 5 * P, 7 * P, 2 * P, 1f, 9 * P), b(14 * P, 5 * P, 7 * P, 1f, 1f, 9 * P))
                    else listOf(b(7 * P, 5 * P, 0f, 9 * P, 1f, 2 * P), b(7 * P, 5 * P, 14 * P, 9 * P, 1f, 1f))
                } else {
                    val top = if (collision) 1.5f else 1f
                    if (alongX) listOf(b(0f, 5 * P, 7 * P, 1f, top, 9 * P)) else listOf(b(7 * P, 5 * P, 0f, 9 * P, top, 1f))
                }
            }
            Blocks.LADDER -> listOf(panel(f xor 1, 2 * P))
            Blocks.GLASS_PANE, Blocks.IRON_BARS -> {
                val out = arrayListOf(b(7 * P, 0f, 7 * P, 9 * P, 1f, 9 * P))
                var any = false
                for (side in 2..5) if (connects(id, neighbour(side))) { any = true; out.add(arm(side, 0f, 1f, 7 * P, 9 * P)) }
                if (!any) for (side in 2..5) out.add(arm(side, 0f, 1f, 7 * P, 9 * P))
                out
            }
            Blocks.BED_FIRST -> if (collision) listOf(b(0f, 0f, 0f, 1f, 9 * P, 1f)) else {
                // Mattress on four short legs; the legs sit at the outer end of each half.
                val out = arrayListOf(b(0f, 3 * P, 0f, 1f, 9 * P, 1f))
                val end = if (upper) f else f xor 1
                for (side in listOf(0f, 13 * P)) when (end) {
                    2 -> out.add(b(side, 0f, 13 * P, side + 3 * P, 3 * P, 1f))
                    3 -> out.add(b(side, 0f, 0f, side + 3 * P, 3 * P, 3 * P))
                    4 -> out.add(b(13 * P, 0f, side, 1f, 3 * P, side + 3 * P))
                    else -> out.add(b(0f, 0f, side, 3 * P, 3 * P, side + 3 * P))
                }
                out
            }
            Blocks.REPEATER -> listOf(b(0f, 0f, 0f, 1f, 2 * P, 1f))
            Blocks.DAYLIGHT_SENSOR -> listOf(b(0f, 0f, 0f, 1f, 6 * P, 1f))
            Blocks.PRESSURE_PLATE -> listOf(b(P, 0f, P, 15 * P, if (meta != 0) 0.5f * P else P, 15 * P))
            Blocks.HOPPER -> if (collision) listOf(b(0f, 0f, 0f, 1f, 1f, 1f))
                else listOf(b(0f, 10 * P, 0f, 1f, 1f, 1f), b(4 * P, 4 * P, 4 * P, 12 * P, 10 * P, 12 * P), b(6 * P, 0f, 6 * P, 10 * P, 4 * P, 10 * P))
            else -> listOf(b(0f, 0f, 0f, 1f, 1f, 1f))
        }
    }

    /** An arm from the centre towards side [side]. */
    private fun arm(side: Int, y0: Float, y1: Float, w0: Float, w1: Float) = when (side) {
        2 -> b(w0, y0, 0.5f, w1, y1, 1f)
        3 -> b(w0, y0, 0f, w1, y1, 0.5f)
        4 -> b(0.5f, y0, w0, 1f, y1, w1)
        else -> b(0f, y0, w0, 0.5f, y1, w1)
    }

    private val N = arrayOf(intArrayOf(0, 0, 1), intArrayOf(0, 0, -1), intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0))

    /** Collision boxes for the block at a world position (null means a full cube). */
    fun collision(world: World, x: Int, y: Int, z: Int, id: Int): List<FloatArray>? {
        val d = Blocks[id]
        return when (d.render) {
            RenderType.SHAPE -> boxes(id, world.getMeta(x, y, z), true) { side ->
                val n = N[side - 2]; world.getBlock(x + n[0], y, z + n[2])
            }
            RenderType.BOX -> listOf(d.box!!)
            else -> null
        }
    }
}

/** Axis-by-axis movement of a box through the world, shared by the player and mobs. */
object Collision {
    private const val EPS = 1e-4f

    /**
     * Moves [pos] (feet centre x, y, z) along [axis] by [delta] for a box of half width [hw] and height [h].
     * Returns false if something blocked the movement.
     */
    fun sweep(world: World, pos: FloatArray, hw: Float, h: Float, axis: Int, delta: Float): Boolean {
        if (delta == 0f) return true
        var remaining = delta
        var free = true
        while (remaining != 0f) {
            val step = remaining.coerceIn(-0.45f, 0.45f)
            remaining -= step
            pos[axis] += step
            val bx0 = floorInt(pos[0] - hw); val bx1 = floorInt(pos[0] + hw - EPS)
            val by0 = floorInt(pos[1]) - 1; val by1 = floorInt(pos[1] + h - EPS)
            val bz0 = floorInt(pos[2] - hw); val bz1 = floorInt(pos[2] + hw - EPS)
            for (bx in bx0..bx1) for (by in by0..by1) for (bz in bz0..bz1) {
                val id = world.getBlock(bx, by, bz)
                if (!Blocks.solid[id]) continue
                val boxes = Shapes.collision(world, bx, by, bz, id)
                if (boxes == null) { if (resolve(pos, hw, h, axis, step, bx, by, bz, FULL)) free = false }
                else for (b in boxes) if (resolve(pos, hw, h, axis, step, bx, by, bz, b)) free = false
            }
            if (!free) return false
        }
        return true
    }

    private val FULL = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)

    private fun resolve(pos: FloatArray, hw: Float, h: Float, axis: Int, step: Float, bx: Int, by: Int, bz: Int, b: FloatArray): Boolean {
        val x0 = bx + b[0]; val y0 = by + b[1]; val z0 = bz + b[2]
        val x1 = bx + b[3]; val y1 = by + b[4]; val z1 = bz + b[5]
        if (pos[0] + hw <= x0 + EPS || pos[0] - hw >= x1 - EPS) return false
        if (pos[1] + h <= y0 + EPS || pos[1] >= y1 - EPS) return false
        if (pos[2] + hw <= z0 + EPS || pos[2] - hw >= z1 - EPS) return false
        when (axis) {
            0 -> pos[0] = if (step > 0) x0 - hw - 1e-3f else x1 + hw + 1e-3f
            1 -> pos[1] = if (step > 0) y0 - h - 1e-3f else y1
            else -> pos[2] = if (step > 0) z0 - hw - 1e-3f else z1 + hw + 1e-3f
        }
        return true
    }
}
