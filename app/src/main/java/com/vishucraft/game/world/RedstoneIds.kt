package com.vishucraft.game.world

/** Helpers for identifying redstone components and packing block positions into a Long. */
object RedstoneIds {
    private const val OFF = 1L shl 26 // keeps packed values positive (bit 63 clear)

    fun pack(x: Int, y: Int, z: Int): Long = ((x + OFF) shl 36) or ((z + OFF) shl 8) or (y.toLong() and 0xFF)
    fun x(p: Long): Int = ((p ushr 36) - OFF).toInt()
    fun z(p: Long): Int = (((p ushr 8) and 0xFFFFFFFL) - OFF).toInt()
    fun y(p: Long): Int = (p and 0xFF).toInt()

    private val component = BooleanArray(Blocks.COUNT).also {
        for (id in intArrayOf(
            Blocks.REDSTONE_DUST, Blocks.REDSTONE_TORCH, Blocks.LEVER, Blocks.STONE_BUTTON, Blocks.REDSTONE_LAMP,
            Blocks.REDSTONE_LAMP_ON, Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.REDSTONE_BLOCK, Blocks.TNT,
        )) it[id] = true
    }

    fun isComponent(id: Int) = component[id]
}
