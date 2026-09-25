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
enum class Dimension { OVERWORLD, EMBER, SKY }

class World(val seed: Long, private val saveDir: File?, val dimension: Dimension = Dimension.OVERWORLD) {
    /** Villagers placed by structure generation, waiting to be spawned on the game thread. */
    val pendingVillagers = java.util.concurrent.ConcurrentLinkedQueue<Triple<Int, Int, Int>>()

    val chunks = ConcurrentHashMap<Long, Chunk>()
    /** Positions (see [RedstoneIds.pack]) of every redstone component in loaded chunks. */
    val components: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val generator: WorldGenerator = when (dimension) {
        Dimension.OVERWORLD -> TerrainGenerator(seed, this)
        Dimension.EMBER -> EmberGenerator(seed)
        Dimension.SKY -> SkyGenerator(seed)
    }

    private val pending: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    val workers: ExecutorService = Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3)
    ) { r -> Thread(r, "world-worker").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    private val chunkDir: File? = saveDir?.let { File(it, "chunks").apply { mkdirs() } }
    val blockEntities = BlockEntities().also { be -> saveDir?.let { be.read(File(it, "blockentities.dat")) } }

    fun getChunk(cx: Int, cz: Int): Chunk? = chunks[Chunk.key(cx, cz)]

    fun getBlock(x: Int, y: Int, z: Int): Int {
        if (y < 0) return Blocks.BEDROCK
        if (y >= Chunk.HEIGHT) return Blocks.AIR
        val c = getChunk(x shr 4, z shr 4) ?: return Blocks.AIR
        return c.get(x and 15, y, z and 15)
    }

    fun getMeta(x: Int, y: Int, z: Int): Int {
        if (y < 0 || y >= Chunk.HEIGHT) return 0
        val c = getChunk(x shr 4, z shr 4) ?: return 0
        return c.getMeta(x and 15, y, z and 15)
    }

    fun isLoaded(x: Int, z: Int) = getChunk(x shr 4, z shr 4) != null

    fun setBlock(x: Int, y: Int, z: Int, id: Int, meta: Int = 0): Boolean {
        if (y < 0 || y >= Chunk.HEIGHT) return false
        val cx = x shr 4; val cz = z shr 4
        val c = getChunk(cx, cz) ?: return false
        val lx = x and 15; val lz = z and 15
        c.set(lx, y, lz, id, meta)
        if (RedstoneIds.isComponent(id)) components.add(RedstoneIds.pack(x, y, z))
        else components.remove(RedstoneIds.pack(x, y, z))
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
                val loaded = load(cx, cz)
                val chunk = loaded ?: Chunk(cx, cz).also { generator.generate(it) }
                if (loaded != null) registerComponents(chunk)
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
        components.removeIf { RedstoneIds.x(it) shr 4 == chunk.cx && RedstoneIds.z(it) shr 4 == chunk.cz }
        if (chunk.modified) {
            val copy = chunk.blocks.copyOf()
            val meta = chunk.meta.copyOf()
            workers.execute { writeChunk(chunk.cx, chunk.cz, copy, meta) }
        }
    }

    private fun registerComponents(c: Chunk) {
        for (i in c.blocks.indices) {
            val id = c.blocks[i].toInt() and 0xFF
            if (!RedstoneIds.isComponent(id)) continue
            val x = i and 15; val z = (i shr 4) and 15; val y = i shr 8
            components.add(RedstoneIds.pack(c.cx * 16 + x, y, c.cz * 16 + z))
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun chunkFile(cx: Int, cz: Int) = chunkDir?.let { File(it, "c.$cx.$cz.bin") }

    private fun load(cx: Int, cz: Int): Chunk? {
        val f = chunkFile(cx, cz) ?: return null
        if (!f.exists()) return null
        return try {
            val c = Chunk(cx, cz)
            DataInputStream(GZIPInputStream(f.inputStream().buffered())).use {
                it.readFully(c.blocks)
                // Older saves have no block state section.
                try { it.readFully(c.meta) } catch (_: java.io.EOFException) { c.meta.fill(0) }
            }
            c
        } catch (e: Exception) {
            null
        }
    }

    private fun writeChunk(cx: Int, cz: Int, data: ByteArray, meta: ByteArray) {
        val f = chunkFile(cx, cz) ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(data); it.write(meta) }
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    /** Synchronously writes all modified chunks and the chest / furnace contents. */
    fun saveChunks() {
        saveDir?.let { try { blockEntities.write(File(it, "blockentities.dat")) } catch (_: Exception) {} }
        for (c in chunks.values) {
            if (c.modified) {
                c.modified = false
                writeChunk(c.cx, c.cz, c.blocks.copyOf(), c.meta.copyOf())
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

enum class GameMode { CREATIVE, SURVIVAL }

/** Player and world metadata stored in level.dat. */
class LevelData(
    var seed: Long,
    var x: Float = 0f, var y: Float = 0f, var z: Float = 0f,
    var yaw: Float = 0f, var pitch: Float = 0f,
    var flying: Boolean = false,
    var timeOfDay: Float = 0.3f,
    var selectedSlot: Int = 0,
    var hasPlayer: Boolean = false,
    var mode: GameMode = GameMode.CREATIVE,
    var name: String = "My World",
    var health: Float = 20f,
    var food: Float = 20f,
    var saturation: Float = 5f,
    val inventory: Inventory = Inventory(),
    var dimension: Dimension = Dimension.OVERWORLD,
    /** True right after travelling through a portal: find a safe spot and build a return portal. */
    var arriving: Boolean = false,
) {
    companion object {
        private const val VERSION = 3

        /** Creative worlds start with a useful hotbar; survival starts empty-handed. */
        fun create(seed: Long, name: String, mode: GameMode): LevelData {
            val l = LevelData(seed, mode = mode, name = name)
            if (mode == GameMode.CREATIVE) {
                val start = intArrayOf(
                    Items.find("Enchanted Diamond Pickaxe"), Items.find("Diamond Sword"), Blocks.GRASS, Blocks.STONE,
                    Blocks.PLANKS, Blocks.GLASS, Blocks.TORCH, Blocks.REDSTONE_DUST, Blocks.PISTON,
                )
                for ((i, id) in start.withIndex()) l.inventory.slots[i] = ItemStack(id, 1)
            }
            return l
        }

        fun read(dir: File): LevelData? {
            val f = File(dir, "level.dat")
            if (!f.exists()) return null
            return try {
                DataInputStream(f.inputStream().buffered()).use { d ->
                    val version = d.readInt()
                    if (version !in 1..VERSION) return null
                    val l = LevelData(d.readLong())
                    l.x = d.readFloat(); l.y = d.readFloat(); l.z = d.readFloat()
                    l.yaw = d.readFloat(); l.pitch = d.readFloat()
                    l.flying = d.readBoolean()
                    l.timeOfDay = d.readFloat()
                    if (version == 1) {
                        // Old creative-only saves: a 9-slot hotbar of ids.
                        for (i in 0 until 9) d.readInt().let { v -> if (Items.isValidSlot(v)) l.inventory.slots[i] = ItemStack(v, 1) }
                        l.selectedSlot = d.readInt().coerceIn(0, 8)
                    } else {
                        l.selectedSlot = d.readInt().coerceIn(0, 8)
                        l.mode = GameMode.values()[d.readInt().coerceIn(0, 1)]
                        l.name = d.readUTF()
                        l.health = d.readFloat(); l.food = d.readFloat(); l.saturation = d.readFloat()
                        l.inventory.read(d)
                        if (version >= 3) {
                            l.dimension = Dimension.values()[d.readInt().coerceIn(0, 2)]
                            l.arriving = d.readBoolean()
                        }
                    }
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
            d.writeInt(selectedSlot)
            d.writeInt(mode.ordinal)
            d.writeUTF(name)
            d.writeFloat(health); d.writeFloat(food); d.writeFloat(saturation)
            inventory.write(d)
            d.writeInt(dimension.ordinal)
            d.writeBoolean(arriving)
        }
        tmp.renameTo(f)
    }
}

fun floorInt(v: Float) = floor(v).toInt()
