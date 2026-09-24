package com.vishucraft.game.world

/** A 16 x 128 x 16 column of blocks. */
class Chunk(val cx: Int, val cz: Int) {
    companion object {
        const val SIZE = 16
        const val HEIGHT = 128

        @JvmStatic
        fun index(x: Int, y: Int, z: Int) = (y * SIZE + z) * SIZE + x

        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
    }

    val blocks = ByteArray(SIZE * SIZE * HEIGHT)
    /** Per-block state: facing, redstone power, lever on/off, piston extension... */
    val meta = ByteArray(SIZE * SIZE * HEIGHT)

    /** Bumped on every change that affects this chunk's mesh. */
    @Volatile var version = 0
    /** Set when the player changed something that must be saved. */
    @Volatile var modified = false

    // Owned by the render thread.
    var requestedVersion = -1
    var uploadedVersion = -1
    var meshing = false

    fun get(x: Int, y: Int, z: Int): Int = blocks[index(x, y, z)].toInt() and 0xFF
    fun getMeta(x: Int, y: Int, z: Int): Int = meta[index(x, y, z)].toInt() and 0xFF

    fun set(x: Int, y: Int, z: Int, id: Int, m: Int = 0) {
        val i = index(x, y, z)
        blocks[i] = id.toByte()
        meta[i] = m.toByte()
    }
}
