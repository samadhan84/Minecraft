package com.vishucraft.game.world

/** A 16 x 128 x 16 column of blocks. */
class Chunk(val cx: Int, val cz: Int) {
    /** Serialised form: 2 bytes per block id followed by 1 byte of state per block. */
    fun toBytes(): ByteArray {
        val out = ByteArray(blocks.size * 3)
        for (i in blocks.indices) { val v = blocks[i].toInt(); out[i * 2] = (v shr 8).toByte(); out[i * 2 + 1] = v.toByte() }
        System.arraycopy(meta, 0, out, blocks.size * 2, meta.size)
        return out
    }

    /** Reads [toBytes] data, or the older 1-byte-per-block formats (with or without the state section). */
    fun fromBytes(data: ByteArray) {
        val n = blocks.size
        when (data.size) {
            n * 3 -> {
                for (i in 0 until n) blocks[i] = (((data[i * 2].toInt() and 255) shl 8) or (data[i * 2 + 1].toInt() and 255)).toShort()
                System.arraycopy(data, n * 2, meta, 0, n)
            }
            n * 2 -> { for (i in 0 until n) blocks[i] = (data[i].toInt() and 255).toShort(); System.arraycopy(data, n, meta, 0, n) }
            n -> { for (i in 0 until n) blocks[i] = (data[i].toInt() and 255).toShort(); meta.fill(0) }
            else -> throw java.io.IOException("bad chunk data (${data.size} bytes)")
        }
    }

    companion object {
        const val SIZE = 16
        const val HEIGHT = 128

        @JvmStatic
        fun index(x: Int, y: Int, z: Int) = (y * SIZE + z) * SIZE + x

        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
    }

    /** Block ids (16-bit, so there is room for more than 256 kinds of block). */
    val blocks = ShortArray(SIZE * SIZE * HEIGHT)
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

    fun get(x: Int, y: Int, z: Int): Int = blocks[index(x, y, z)].toInt() and 0xFFFF
    fun getMeta(x: Int, y: Int, z: Int): Int = meta[index(x, y, z)].toInt() and 0xFF

    fun set(x: Int, y: Int, z: Int, id: Int, m: Int = 0) {
        val i = index(x, y, z)
        blocks[i] = id.toShort()
        meta[i] = m.toByte()
    }
}
