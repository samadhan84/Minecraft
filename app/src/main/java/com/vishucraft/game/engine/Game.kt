package com.vishucraft.game.engine

import com.vishucraft.game.render.ChunkMesher
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.Facing
import com.vishucraft.game.world.ItemUse
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.LevelData
import com.vishucraft.game.world.Redstone
import com.vishucraft.game.world.RenderType
import com.vishucraft.game.world.ToolType
import com.vishucraft.game.world.World
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

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
    /** Seconds of camera shake left after an explosion. */
    var shake = 0f
        private set
    private var breakKey = Long.MIN_VALUE
    private var spawned = level.hasPlayer
    private val look = FloatArray(2)
    private val dir = FloatArray(3)
    private val offsets: List<IntArray>
    private var redstoneTimer = 0f

    /** Chunks whose mesh must be rebuilt this frame (batched so explosions stay cheap). */
    val dirtyChunks = HashSet<Long>()

    val redstone = Redstone(world) { x, y, z, id, meta -> setBlock(x, y, z, id, meta) }

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

        redstone.onExplosion = { ex, ey, ez ->
            val dx = player.x - ex; val dy = player.eyeY - ey; val dz = player.z - ez
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 10f) {
                val push = (10f - d) * 1.6f / max(d, 0.5f)
                player.vx += dx * push; player.vy += (dy * push).coerceAtLeast(4f); player.vz += dz * push
            }
            if (d < 40f) shake = 0.6f
        }
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
                GameInput.Action.PLACE -> use()
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

        redstoneTimer += dt
        while (redstoneTimer >= Redstone.TICK_SECONDS) {
            redstoneTimer -= Redstone.TICK_SECONDS
            redstone.tick()
        }
        if (shake > 0f) shake = max(0f, shake - dt)

        timeOfDay = (timeOfDay + dt / DAY_LENGTH_SECONDS) % 1f
    }

    /** Seconds needed to mine [block] with the currently held slot. */
    fun mineTime(block: Int): Float {
        val def = Blocks[block]
        if (def.hardness <= 0f) return 0f
        val item = Items[input.selectedBlock]
        var time = def.hardness * 1.2f
        val rightTool = item != null && item.tool != ToolType.NONE &&
            (item.tool == def.tool || (item.tool == ToolType.SWORD && (block == Blocks.COBWEB || def.tool == ToolType.HOE)))
        if (rightTool) {
            time /= if (item!!.tool == ToolType.SWORD) (if (block == Blocks.COBWEB) 15f else 1.5f) else item.speed
        } else if (def.tool == ToolType.PICKAXE) {
            time *= 1.6f
        }
        return max(time, 0.05f)
    }

    private fun updateBreaking(dt: Float) {
        val t = target
        if (!input.breakHeld || t == null || !Blocks[t.block].breakable) {
            breakProgress = 0f; breakKey = Long.MIN_VALUE
            return
        }
        val key = (t.x.toLong() shl 40) xor (t.y.toLong() shl 20) xor t.z.toLong()
        if (key != breakKey) { breakKey = key; breakProgress = 0f }
        val time = mineTime(t.block)
        breakProgress += if (time <= 0f) 1f else dt / time
        if (breakProgress >= 1f) {
            breakBlock(t.x, t.y, t.z)
            breakProgress = 0f; breakKey = Long.MIN_VALUE
        }
    }

    private fun breakBlock(x: Int, y: Int, z: Int) {
        val id = world.getBlock(x, y, z)
        val meta = world.getMeta(x, y, z)
        setBlock(x, y, z, Blocks.AIR)
        // Keep pistons consistent when one half is mined.
        val n = ChunkMesher.NORMALS
        if (id == Blocks.PISTON_HEAD) {
            val f = (meta and 7).coerceIn(0, 5)
            val bx = x - n[f][0]; val by = y - n[f][1]; val bz = z - n[f][2]
            val base = world.getBlock(bx, by, bz)
            if (base == Blocks.PISTON || base == Blocks.STICKY_PISTON) setBlock(bx, by, bz, base, f)
        } else if ((id == Blocks.PISTON || id == Blocks.STICKY_PISTON) && meta and Redstone.EXTENDED != 0) {
            val f = (meta and 7).coerceIn(0, 5)
            if (world.getBlock(x + n[f][0], y + n[f][1], z + n[f][2]) == Blocks.PISTON_HEAD) {
                setBlock(x + n[f][0], y + n[f][1], z + n[f][2], Blocks.AIR)
            }
        }
        // Plants, torches, dust and stacked sugar cane / cactus above pop off too.
        var above = y + 1
        while (above < Chunk.HEIGHT) {
            val a = world.getBlock(x, above, z)
            if (!Blocks[a].needsSupport && a != Blocks.CACTUS) break
            setBlock(x, above, z, Blocks.AIR)
            above++
        }
    }

    /** Tap: interact with levers/buttons, use the held item, or place the held block. */
    private fun use() {
        val sel = input.selectedBlock
        val item = Items[sel]
        val t = if (item?.use == ItemUse.BUCKET) {
            Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], REACH, hitWater = true)
        } else target
        t ?: return

        when (t.block) {
            Blocks.LEVER -> { redstone.toggleLever(t.x, t.y, t.z); return }
            Blocks.STONE_BUTTON -> { redstone.pressButton(t.x, t.y, t.z); return }
            Blocks.NOTE_BLOCK, Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.CHEST, Blocks.JUKEBOX -> if (item == null) return
        }

        if (item != null) {
            when (item.use) {
                ItemUse.TILL -> if ((t.block == Blocks.GRASS || t.block == Blocks.DIRT || t.block == Blocks.DIRT_PATH) &&
                    world.getBlock(t.x, t.y + 1, t.z) == Blocks.AIR) setBlock(t.x, t.y, t.z, Blocks.FARMLAND)
                ItemUse.PATH -> if (t.block == Blocks.GRASS && world.getBlock(t.x, t.y + 1, t.z) == Blocks.AIR)
                    setBlock(t.x, t.y, t.z, Blocks.DIRT_PATH)
                ItemUse.IGNITE -> if (t.block == Blocks.TNT) redstone.prime(t.x, t.y, t.z)
                ItemUse.BUCKET -> if (t.block == Blocks.WATER) setBlock(t.x, t.y, t.z, Blocks.AIR)
                ItemUse.WATER_BUCKET -> {
                    val x = t.x + t.nx; val y = t.y + t.ny; val z = t.z + t.nz
                    if (world.getBlock(x, y, z) == Blocks.AIR) setBlock(x, y, z, Blocks.WATER)
                }
                ItemUse.NONE -> {}
            }
            return
        }
        place(t, sel)
    }

    private fun place(t: RayHit, id: Int) {
        if (id <= Blocks.AIR || id >= Blocks.COUNT) return
        val def = Blocks[id]
        // Clicking a plant replaces it instead of placing next to it.
        val replace = Blocks[t.block].render == RenderType.CROSS && Blocks[t.block].id != Blocks.LEVER
        val x = if (replace) t.x else t.x + t.nx
        val y = if (replace) t.y else t.y + t.ny
        val z = if (replace) t.z else t.z + t.nz
        if (y < 0 || y >= Chunk.HEIGHT) return
        val existing = world.getBlock(x, y, z)
        if (existing != Blocks.AIR && existing != Blocks.WATER && !replace) return
        if (Blocks.solid[id] && player.intersectsBlock(x, y, z)) return
        val below = world.getBlock(x, y - 1, z)
        if (def.render == RenderType.CROSS && id != Blocks.TORCH && id != Blocks.REDSTONE_TORCH && id != Blocks.LEVER &&
            id != Blocks.COBWEB) {
            val soil = below == Blocks.GRASS || below == Blocks.DIRT || below == Blocks.SNOW_GRASS ||
                below == Blocks.SAND || below == Blocks.FARMLAND || below == Blocks.PODZOL || below == Blocks.MYCELIUM ||
                below == Blocks.COARSE_DIRT || below == Blocks.RED_SAND || (id == Blocks.SUGAR_CANE && below == Blocks.SUGAR_CANE)
            if (!soil) return
        }
        if (def.needsSupport && !Blocks.solid[below] && id != Blocks.SUGAR_CANE) return
        setBlock(x, y, z, id, placementMeta(def.facing))
    }

    /** Facing blocks look back at the player (pistons can also face up or down). */
    private fun placementMeta(facing: Facing): Int {
        if (facing == Facing.NONE) return 0
        val lx = -dir[0]; val ly = -dir[1]; val lz = -dir[2]
        if (facing == Facing.ALL && abs(ly) > 0.7f) return if (ly > 0) 0 else 1
        return if (abs(lx) > abs(lz)) (if (lx > 0) 4 else 5) else (if (lz > 0) 2 else 3)
    }

    fun setBlock(x: Int, y: Int, z: Int, id: Int, meta: Int = 0) {
        if (world.setBlock(x, y, z, id, meta)) {
            val cx = x shr 4; val cz = z shr 4
            dirtyChunks.add(Chunk.key(cx, cz))
            // Border edits change the neighbour's faces and lighting too.
            val lx = x and 15; val lz = z and 15
            if (lx == 0) dirtyChunks.add(Chunk.key(cx - 1, cz))
            if (lx == 15) dirtyChunks.add(Chunk.key(cx + 1, cz))
            if (lz == 0) dirtyChunks.add(Chunk.key(cx, cz - 1))
            if (lz == 15) dirtyChunks.add(Chunk.key(cx, cz + 1))
        }
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
