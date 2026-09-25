package com.vishucraft.game.world

/** Rail shapes: 0 N-S, 1 E-W, 2..5 ascending towards +X, -X, -Z, +Z, 6..9 curves SE, SW, NW, NE. */
object Rails {
    fun isRail(id: Int) = id == Blocks.RAIL || id == Blocks.POWERED_RAIL

    /** The two directions (dx, dz) a rail shape connects. */
    fun exits(shape: Int): Array<IntArray> = when (shape) {
        1, 2, 3 -> arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0))
        6 -> arrayOf(intArrayOf(1, 0), intArrayOf(0, 1))
        7 -> arrayOf(intArrayOf(-1, 0), intArrayOf(0, 1))
        8 -> arrayOf(intArrayOf(-1, 0), intArrayOf(0, -1))
        9 -> arrayOf(intArrayOf(1, 0), intArrayOf(0, -1))
        else -> arrayOf(intArrayOf(0, 1), intArrayOf(0, -1))
    }

    /** Direction (dx, dz) a slope rises towards, or null for flat shapes. */
    fun rise(shape: Int): IntArray? = when (shape) {
        2 -> intArrayOf(1, 0); 3 -> intArrayOf(-1, 0); 4 -> intArrayOf(0, -1); 5 -> intArrayOf(0, 1); else -> null
    }

    private fun railAround(world: World, x: Int, y: Int, z: Int): Int = when {
        isRail(world.getBlock(x, y, z)) -> 0
        isRail(world.getBlock(x, y + 1, z)) -> 1
        isRail(world.getBlock(x, y - 1, z)) -> -1
        else -> Int.MIN_VALUE
    }

    /** Picks the shape that joins the neighbouring rails. Powered rails cannot curve. */
    fun shapeFor(world: World, x: Int, y: Int, z: Int, powered: Boolean): Int {
        val e = railAround(world, x + 1, y, z); val w = railAround(world, x - 1, y, z)
        val s = railAround(world, x, y, z + 1); val n = railAround(world, x, y, z - 1)
        val hasE = e != Int.MIN_VALUE; val hasW = w != Int.MIN_VALUE
        val hasS = s != Int.MIN_VALUE; val hasN = n != Int.MIN_VALUE
        if (!powered && (hasE || hasW) && (hasS || hasN) && !(hasE && hasW) && !(hasS && hasN)) {
            return when {
                hasE && hasS -> 6; hasW && hasS -> 7; hasW && hasN -> 8; else -> 9
            }
        }
        if (hasE || hasW) return when { e == 1 -> 2; w == 1 -> 3; else -> 1 }
        return when { n == 1 -> 4; s == 1 -> 5; else -> 0 }
    }
}
