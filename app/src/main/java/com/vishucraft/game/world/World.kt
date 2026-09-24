package com.vishucraft.game.world

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor

/**
 * Chunk storage, background generation and persistence.
 * Block edits happen on the game (GL) thread; generation runs on worker threads.
 */
class World(val seed: Long, private val saveDir: File?) {
    val chunks = ConcurrentHashMap<Long, Chunk>()
    val generator = TerrainGenerator(seed)

    private val pending: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val workers: ExecutorService = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3)
    ) { r -> Thread(r, "world-worker").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    private val chunkDir: File? = saveDir?.let { File(it, "chunks").apply { mkdirs() } }

    fun getChunk(cx: Int, cz: Int): Chunk? = chunks[Chunk.key(cx, cz)]

    fun getBlock(x: Int, y: Int, z: Int): Int {
        if (y < 0) return Blocks.BEDROCK
        if (y >= Chunk.HEIGHT) return Blocks.AIR
        val c = getChunk(x shr 4, z shr 4) ?: return Blocks.AIR
        return c.get(x and 15, y, z and 15)
    }

    fun isLoaded(x: Int, z: Int) = getChunk(x shr 4, z shr 4) != null

    fun setBlock(x: Int, y: Int, z: Int, id: Int): Boolean {
        if (y < 0 || y >= Chunk.HEIGHT) return false
        val cx = x shr 4; val cz = z shr 4
        val c = getChunk(cx, cz) ?: return false
        val lx = x and 15; val lz = z and 15
        c.set(lx, y, lz, id)
        c.modified = true
        c.version++
        // Neighbouring meshes sample across borders (faces, AO, light), so refresh them too.
        for (dz in -1..1) for (dx in -1..1) {
            if (dx == 0 && dz == 0) continue
            val touchesX = (dx == -1 && lx == 0) || (dx == 1 && lx == 15) || dx == 0
            val touchesZ = (dz == -1 && lz == 0) || (dz == 1 && lz == 15) || dz == 0
            if (touchesX && touchesZ) getChunk(cx + dx, cz + dz)?.let { it.version++ }
        }
        return true
    }

    /** Asynchronously load or generate a chunk. */
    fun request(cx: Int, cz: Int) {
        val key = Chunk.key(cx, cz)
        if (chunks.containsKey(key) || !pending.add(key)) return
        workers.execute {
            try {
                val chunk = load(cx, cz) ?: Chunk(cx, cz).also { generator.generate(it) }
                chunk.version = 1
                chunks[key] = chunk
            } finally {
                pending.remove(key)
            }
        }
    }

    fun pendingCount() = pending.size

    fun unload(chunk: Chunk) {
        chunks.remove(Chunk.key(chunk.cx, chunk.cz))
        if (chunk.modified) {
            val copy = chunk.blocks.copyOf()
            workers.execute { writeChunk(chunk.cx, chunk.cz, copy) }
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun chunkFile(cx: Int, cz: Int) = chunkDir?.let { File(it, "c.$cx.$cz.bin") }

    private fun load(cx: Int, cz: Int): Chunk? {
        val f = chunkFile(cx, cz) ?: return null
        if (!f.exists()) return null
        return try {
            val c = Chunk(cx, cz)
            DataInputStream(GZIPInputStream(f.inputStream().buffered())).use { it.readFully(c.blocks) }
            c
        } catch (e: Exception) {
            null
        }
    }

    private fun writeChunk(cx: Int, cz: Int, data: ByteArray) {
        val f = chunkFile(cx, cz) ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(data) }
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    /** Synchronously writes all modified chunks. */
    fun saveChunks() {
        for (c in chunks.values) {
            if (c.modified) {
                c.modified = false
                writeChunk(c.cx, c.cz, c.blocks.copyOf())
            }
        }
    }

    fun shutdown() {
        workers.shutdown()
    }

    /** Finds a dry spawn point near the origin. */
    fun findSpawn(): Triple<Float, Float, Float> {
        for (r in 0..400 step 8) {
            for (i in 0 until 16) {
                val a = i * Math.PI * 2 / 16
                val x = (Math.cos(a) * r).toInt()
                val z = (Math.sin(a) * r).toInt()
                val h = generator.surfaceHeight(x, z)
                if (h > TerrainGenerator.SEA_LEVEL + 1 && h < 88) return Triple(x + 0.5f, h + 1f, z + 0.5f)
            }
        }
        return Triple(0.5f, 100f, 0.5f)
    }
}

/** Player and world metadata stored in level.dat. */
class LevelData(
    var seed: Long,
    var x: Float = 0f, var y: Float = 0f, var z: Float = 0f,
    var yaw: Float = 0f, var pitch: Float = 0f,
    var flying: Boolean = false,
    var timeOfDay: Float = 0.3f,
    var hotbar: IntArray = intArrayOf(
        Blocks.GRASS, Blocks.DIRT, Blocks.STONE, Blocks.COBBLESTONE, Blocks.PLANKS,
        Blocks.LOG, Blocks.GLASS, Blocks.BRICKS, Blocks.GLOWSTONE
    ),
    var selectedSlot: Int = 0,
    var hasPlayer: Boolean = false,
) {
    companion object {
        private const val VERSION = 1

        fun read(dir: File): LevelData? {
            val f = File(dir, "level.dat")
            if (!f.exists()) return null
            return try {
                DataInputStream(f.inputStream().buffered()).use { d ->
                    if (d.readInt() != VERSION) return null
                    val l = LevelData(d.readLong())
                    l.x = d.readFloat(); l.y = d.readFloat(); l.z = d.readFloat()
                    l.yaw = d.readFloat(); l.pitch = d.readFloat()
                    l.flying = d.readBoolean()
                    l.timeOfDay = d.readFloat()
                    l.hotbar = IntArray(9) { d.readInt().coerceIn(1, Blocks.COUNT - 1) }
                    l.selectedSlot = d.readInt().coerceIn(0, 8)
                    l.hasPlayer = true
                    l
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun write(dir: File) {
        dir.mkdirs()
        val f = File(dir, "level.dat")
        val tmp = File(dir, "level.dat.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { d ->
            d.writeInt(VERSION)
            d.writeLong(seed)
            d.writeFloat(x); d.writeFloat(y); d.writeFloat(z)
            d.writeFloat(yaw); d.writeFloat(pitch)
            d.writeBoolean(flying)
            d.writeFloat(timeOfDay)
            for (i in 0 until 9) d.writeInt(hotbar[i])
            d.writeInt(selectedSlot)
        }
        tmp.renameTo(f)
    }
}

fun floorInt(v: Float) = floor(v).toInt()
