package com.vishucraft.game.world

/**
 * A 16 x 512 x 16 column of blocks. Only the part up to the highest block placed so far is kept in memory (at least
 * the 128 blocks of ordinary terrain): it grows 16 blocks at a time when something is built higher up, so tall
 * towers cost memory only where they stand.
 */
class Chunk(val cx: Int, val cz: Int) {
    /** Serialised form: 2 bytes per block id followed by 1 byte of state per block, up to [height]. */
    fun toBytes(): ByteArray {
        val blocks = blocks; val meta = meta
        val n = minOf(blocks.size, meta.size)
        val out = ByteArray(n * 3)
        for (i in 0 until n) { val v = blocks[i].toInt(); out[i * 2] = (v shr 8).toByte(); out[i * 2 + 1] = v.toByte() }
        System.arraycopy(meta, 0, out, n * 2, n)
        return out
    }

    /** Reads [toBytes] data of any height, or the older 1-byte-per-block formats (with or without the state section). */
    fun fromBytes(data: ByteArray) {
        val old = LAYER * BASE
        when {
            data.size == old * 2 -> {
                alloc(BASE)
                for (i in 0 until old) blocks[i] = (data[i].toInt() and 255).toShort()
                System.arraycopy(data, old, meta, 0, old)
            }
            data.size == old -> { alloc(BASE); for (i in 0 until old) blocks[i] = (data[i].toInt() and 255).toShort() }
            data.size % (LAYER * 3 * 16) == 0 && data.size / (LAYER * 3) in 16..HEIGHT -> {
                val n = data.size / 3
                alloc(n / LAYER)
                for (i in 0 until n) blocks[i] = (((data[i * 2].toInt() and 255) shl 8) or (data[i * 2 + 1].toInt() and 255)).toShort()
                System.arraycopy(data, n * 2, meta, 0, n)
            }
            else -> throw java.io.IOException("bad chunk data (${data.size} bytes)")
        }
    }

    companion object {
        const val SIZE = 16
        /** How tall the world is. */
        const val HEIGHT = 512
        /** How tall the world used to be: terrain is generated within this, and every chunk keeps at least this much. */
        const val BASE = 128
        private const val LAYER = SIZE * SIZE

        @JvmStatic
        fun index(x: Int, y: Int, z: Int) = (y * SIZE + z) * SIZE + x

        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
    }

    /**
     * Block ids (16-bit, so there is room for more than 256 kinds of block), indexed by [index], for the blocks below
     * [height]; everything above is air. The array is replaced when the chunk grows, so other threads should read
     * this once and use its size.
     */
    @Volatile var blocks = ShortArray(LAYER * BASE); private set
    /** Per-block state: facing, redstone power, lever on/off, piston extension... (same layout as [blocks]). */
    @Volatile var meta = ByteArray(LAYER * BASE); private set

    /** How many layers are stored: blocks at this height and above are all air. */
    val height get() = blocks.size / LAYER

    private fun alloc(h: Int) { blocks = ShortArray(LAYER * h); meta = ByteArray(LAYER * h) }

    /** Makes room for a block at height [y]. */
    private fun grow(y: Int) {
        val h = minOf(HEIGHT, (y / 16 + 1) * 16)
        val nm = meta.copyOf(LAYER * h)
        blocks = blocks.copyOf(LAYER * h)
        meta = nm
    }

    /** Bumped on every change that affects this chunk's mesh. */
    @Volatile var version = 0
    /** Set when the player changed something that must be saved. */
    @Volatile var modified = false

    // Owned by the render thread.
    var requestedVersion = -1
    var uploadedVersion = -1
    var meshing = false

    fun get(x: Int, y: Int, z: Int): Int {
        val b = blocks; val i = index(x, y, z)
        return if (i < b.size) b[i].toInt() and 0xFFFF else 0
    }

    fun getMeta(x: Int, y: Int, z: Int): Int {
        val m = meta; val i = index(x, y, z)
        return if (i < m.size) m[i].toInt() and 0xFF else 0
    }

    fun set(x: Int, y: Int, z: Int, id: Int, m: Int = 0) {
        val i = index(x, y, z)
        if (i >= blocks.size) { if (id == 0 && m == 0) return; grow(y) }
        blocks[i] = id.toShort()
        meta[i] = m.toByte()
    }
}
