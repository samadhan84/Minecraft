package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.LevelData
import com.vishucraft.game.world.RenderType
import com.vishucraft.game.world.World
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Game state and simulation. Runs on the GL thread. */
class Game(val world: World, val level: LevelData, val input: GameInput, private val saveDir: File?) {
    companion object {
        const val REACH = 6f
        const val DAY_LENGTH_SECONDS = 720f
        /** Radians per dp of finger movement. */
        const val LOOK_SENSITIVITY = 0.0105f
    }

    val player = Player()
    var renderDistance = 6
    var timeOfDay = level.timeOfDay
    var target: RayHit? = null
        private set
    var breakProgress = 0f
        private set
    private var breakKey = Long.MIN_VALUE
    private var spawned = level.hasPlayer
    private val look = FloatArray(2)
    private val dir = FloatArray(3)
    private val offsets: List<IntArray>

    /** Called with the chunk coordinates of every block change so the renderer can prioritise it. */
    var onBlockChanged: ((Int, Int, Int) -> Unit)? = null

    init {
        if (level.hasPlayer) {
            player.x = level.x; player.y = level.y; player.z = level.z
            player.yaw = level.yaw; player.pitch = level.pitch
            player.flying = level.flying
        } else {
            val (sx, sy, sz) = world.findSpawn()
            player.x = sx; player.y = sy; player.z = sz
        }
        input.selectedBlock = level.hotbar[level.selectedSlot]
        // Chunk offsets sorted nearest first, so the area around the player streams in first.
        val r = 16
        offsets = buildList {
            for (dz in -r..r) for (dx in -r..r) add(intArrayOf(dx, dz))
        }.sortedBy { it[0] * it[0] + it[1] * it[1] }
    }

    val daylight: Float
        get() {
            val s = kotlin.math.sin(timeOfDay * 2 * Math.PI).toFloat()
            val t = ((s + 0.2f) / 0.5f).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

    fun update(dt: Float) {
        streamChunks()

        input.consumeLook(look)
        player.rotate(look[0] * LOOK_SENSITIVITY, -look[1] * LOOK_SENSITIVITY)

        while (true) {
            when (input.actions.poll() ?: break) {
                GameInput.Action.PLACE -> place()
                GameInput.Action.TOGGLE_FLY -> { player.flying = !player.flying; player.vy = 0f }
            }
        }

        val loaded = world.isLoaded(player.blockX(), player.blockZ())
        if (loaded) {
            if (!spawned) {
                // Drop the player onto the actual generated surface.
                var y = Chunk.HEIGHT - 2
                while (y > 1 && !Blocks.solid[world.getBlock(player.blockX(), y, player.blockZ())]) y--
                player.y = y + 1f
                spawned = true
            }
            player.update(dt, world, input.moveForward, input.moveStrafe, input.jumpHeld, input.descendHeld)
            if (player.y < -20f) { player.y = Chunk.HEIGHT.toFloat(); player.vy = 0f }
        }

        player.lookDir(dir)
        target = Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], REACH)
        updateBreaking(dt)

        timeOfDay = (timeOfDay + dt / DAY_LENGTH_SECONDS) % 1f
    }

    private fun updateBreaking(dt: Float) {
        val t = target
        if (!input.breakHeld || t == null || !Blocks[t.block].breakable) {
            breakProgress = 0f; breakKey = Long.MIN_VALUE
            return
        }
        val key = (t.x.toLong() shl 40) xor (t.y.toLong() shl 20) xor t.z.toLong()
        if (key != breakKey) { breakKey = key; breakProgress = 0f }
        val hardness = Blocks[t.block].hardness
        breakProgress += if (hardness <= 0f) 1f else dt / (hardness * 0.6f)
        if (breakProgress >= 1f) {
            setBlock(t.x, t.y, t.z, Blocks.AIR)
            // Plants resting on the broken block pop off too.
            val above = world.getBlock(t.x, t.y + 1, t.z)
            if (Blocks[above].render == RenderType.CROSS) setBlock(t.x, t.y + 1, t.z, Blocks.AIR)
            breakProgress = 0f; breakKey = Long.MIN_VALUE
        }
    }

    private fun place() {
        val t = target ?: return
        val id = input.selectedBlock
        if (id <= Blocks.AIR || id >= Blocks.COUNT) return
        // Clicking a plant replaces it instead of placing next to it.
        val replace = Blocks[t.block].render == RenderType.CROSS
        val x = if (replace) t.x else t.x + t.nx
        val y = if (replace) t.y else t.y + t.ny
        val z = if (replace) t.z else t.z + t.nz
        if (y < 0 || y >= Chunk.HEIGHT) return
        val existing = world.getBlock(x, y, z)
        if (existing != Blocks.AIR && existing != Blocks.WATER && !replace) return
        if (Blocks.solid[id] && player.intersectsBlock(x, y, z)) return
        if (Blocks[id].render == RenderType.CROSS) {
            val below = world.getBlock(x, y - 1, z)
            if (below != Blocks.GRASS && below != Blocks.DIRT && below != Blocks.SNOW_GRASS && below != Blocks.SAND) return
        }
        setBlock(x, y, z, id)
    }

    private fun setBlock(x: Int, y: Int, z: Int, id: Int) {
        if (world.setBlock(x, y, z, id)) onBlockChanged?.invoke(x shr 4, y, z shr 4)
    }

    private fun streamChunks() {
        val pcx = player.blockX() shr 4
        val pcz = player.blockZ() shr 4
        val loadR = renderDistance + 1
        var budget = 8 - world.pendingCount()
        for (o in offsets) {
            if (budget <= 0) break
            if (abs(o[0]) > loadR || abs(o[1]) > loadR) continue
            val cx = pcx + o[0]; val cz = pcz + o[1]
            if (world.getChunk(cx, cz) == null) { world.request(cx, cz); budget-- }
        }
        val unloadR = renderDistance + 3
        for (c in world.chunks.values) {
            if (max(abs(c.cx - pcx), abs(c.cz - pcz)) > unloadR) world.unload(c)
        }
    }

    fun save() {
        level.x = player.x; level.y = player.y; level.z = player.z
        level.yaw = player.yaw; level.pitch = player.pitch
        level.flying = player.flying
        level.timeOfDay = timeOfDay
        level.hasPlayer = spawned
        world.saveChunks()
        saveDir?.let { level.write(it) }
    }
}
