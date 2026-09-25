package com.vishucraft.game.engine

import com.vishucraft.game.render.ChunkMesher
import com.vishucraft.game.world.BlockEntities
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.ChestEntity
import com.vishucraft.game.world.Drops
import com.vishucraft.game.world.FurnaceEntity
import com.vishucraft.game.world.GameMode
import com.vishucraft.game.world.ItemDef
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.RedstoneIds
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
    /** Multiplies touch look speed (settings). */
    var lookScale = 1f
    var fov = 72f
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
    val mobs = Mobs(world)

    val drops = ItemEntities(world)
    val inventory = level.inventory
    val mode get() = level.mode
    val survival get() = level.mode == GameMode.SURVIVAL

    /** Player health in half-hearts (20 = ten hearts). */
    var health = level.health.coerceIn(1f, 20f)
        private set
    /** Hunger (20 = full) and hidden saturation, as in the usual survival rules. */
    var food = level.food.coerceIn(0f, 20f)
        private set
    private var saturation = level.saturation
    private var exhaustion = 0f
    private var starveTimer = 0f
    private var lastWalk = 0f
    private var furnaceTimer = 0f

    fun heldStack(): ItemStack? = inventory.slots[input.selectedSlot.coerceIn(0, 8)]
    fun heldId(): Int = heldStack()?.id ?: 0
    fun heldItem(): ItemDef? = Items[heldId()]
    fun onPickup() { uiEvents.add("pickup") }
    val playerAlive get() = health > 0f
    private var regenTimer = 0f
    private var lastVy = 0f
    private var wasOnGround = true

    /** Messages for the UI thread: "hurt", "died", or "toast:<text>". */
    val uiEvents = java.util.concurrent.ConcurrentLinkedQueue<String>()

    init {
        if (level.hasPlayer) {
            player.x = level.x; player.y = level.y; player.z = level.z
            player.yaw = level.yaw; player.pitch = level.pitch
            player.flying = level.flying
        } else {
            val (sx, sy, sz) = world.findSpawn()
            player.x = sx; player.y = sy; player.z = sz
        }
        input.selectedSlot = level.selectedSlot
        if (survival) player.flying = false
        mobs.onDeath = { m -> if (survival) for ((id, n) in mobDrops(m)) drops.spawn(ItemStack(id, n), m.x, m.y + 0.5f, m.z) }
        // Chunk offsets sorted nearest first, so the area around the player streams in first.
        val r = 16
        offsets = buildList {
            for (dz in -r..r) for (dx in -r..r) add(intArrayOf(dx, dz))
        }.sortedBy { it[0] * it[0] + it[1] * it[1] }

        redstone.onExplosion = { ex, ey, ez, r ->
            val blast = r * 2.2f
            val dx = player.x - ex; val dy = player.eyeY - ey; val dz = player.z - ez
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < blast) {
                val push = (blast - d) * 1.4f / max(d, 0.5f)
                player.vx += dx * push; player.vy += (dy * push).coerceAtLeast(4f); player.vz += dz * push
                hurtPlayer((1f - d / blast) * r * 5.5f, ex, ez, knockback = false)
            }
            if (d < 40f) shake = 0.6f
            for (m in mobs.list) {
                val mx = m.x - ex; val mz = m.z - ez; val my = m.y + m.type.height / 2 - ey
                val md = sqrt(mx * mx + my * my + mz * mz)
                if (md < blast) mobs.damage(m, (1f - md / blast) * r * 6f, mx / max(md, 0.5f), mz / max(md, 0.5f))
            }
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
        player.rotate(look[0] * LOOK_SENSITIVITY * lookScale, -look[1] * LOOK_SENSITIVITY * lookScale)
        // Sticks and remote keys turn at up to 2.4 rad/s.
        player.rotate(input.lookStickX * 2.4f * dt, input.lookStickY * 1.8f * dt)

        while (true) {
            when (input.actions.poll() ?: break) {
                GameInput.Action.PLACE -> use()
                GameInput.Action.TOGGLE_FLY -> if (!survival) { player.flying = !player.flying; player.vy = 0f }
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
            lastVy = player.vy
            player.update(dt, world, input.moveForward, input.moveStrafe, input.jumpHeld, input.descendHeld)
            // Fall damage when landing hard (not while flying or in water).
            if (player.onGround && !wasOnGround && !player.flying && !player.inWater && lastVy < -14f) {
                hurtPlayer((-lastVy - 13f) * 0.9f, player.x, player.z, knockback = false)
            }
            wasOnGround = player.onGround
            if (player.y < -20f) hurtPlayer(100f, player.x, player.z, knockback = false)
            mobs.update(dt, this)
            drops.update(dt, this)
            if (survival) hunger(dt)
        }

        if (!survival && health < 20f) health = 20f
        furnaceTimer += dt
        if (furnaceTimer >= 0.25f) { tickFurnaces(furnaceTimer); furnaceTimer = 0f }

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

    fun hurtPlayer(amount: Float, fromX: Float, fromZ: Float, knockback: Boolean = true, ignoreArmor: Boolean = false) {
        if (!playerAlive || amount <= 0f || !survival) return
        val armor = if (ignoreArmor) 0 else inventory.armorPoints().coerceAtMost(20)
        health -= amount * (1f - armor * 0.04f)
        if (knockback) {
            val dx = player.x - fromX; val dz = player.z - fromZ
            val d = sqrt(dx * dx + dz * dz).coerceAtLeast(0.1f)
            player.vx += dx / d * 7f; player.vz += dz / d * 7f; player.vy = max(player.vy, 5f)
        }
        uiEvents.add("hurt")
        if (health <= 0f) respawn()
    }

    private fun respawn() {
        // Survival: everything you carried is dropped where you died.
        for (s in inventory.slots) if (s != null) drops.spawn(s, player.x, player.y + 1f, player.z)
        for (s in inventory.armor) if (s != null) drops.spawn(s, player.x, player.y + 1f, player.z)
        inventory.clear()
        food = 20f; saturation = 5f; exhaustion = 0f
        val (sx, sy, sz) = world.findSpawn()
        player.x = sx; player.y = sy; player.z = sz
        player.vx = 0f; player.vy = 0f; player.vz = 0f
        health = 20f
        spawned = false // drop onto the real surface once the chunk is loaded
        uiEvents.add("died")
    }

    fun explode(x: Float, y: Float, z: Float, radius: Float) = redstone.explodeAt(x, y, z, radius)

    // ---------------------------------------------------------------- survival rules

    private fun exhaust(amount: Float) { if (survival) exhaustion += amount }

    private fun hunger(dt: Float) {
        exhaust((player.walkDist - lastWalk) * 0.03f + dt * 0.01f)
        lastWalk = player.walkDist
        if (player.onGround.not() && wasOnGround && player.vy > 5f) exhaust(0.1f)
        while (exhaustion >= 4f) {
            exhaustion -= 4f
            if (saturation > 0f) saturation = max(0f, saturation - 1f) else food = max(0f, food - 1f)
        }
        regenTimer += dt
        if (food >= 18f && health < 20f && regenTimer >= 4f) {
            regenTimer = 0f; health = minOf(20f, health + 1f); exhaust(3f)
        }
        if (food <= 0f) {
            starveTimer += dt
            if (starveTimer >= 4f) { starveTimer = 0f; if (health > 1f) hurtPlayer(1f, player.x, player.z, false, ignoreArmor = true) }
        } else starveTimer = 0f
    }

    private fun eat(item: ItemDef): Boolean {
        if (!survival) return false
        if (food >= 20f && item.name != "Golden Apple") return false
        food = minOf(20f, food + item.food)
        saturation = minOf(food, saturation + item.food * 1.2f)
        if (item.name == "Golden Apple") health = minOf(20f, health + 8f)
        uiEvents.add("eat")
        return true
    }

    /** Uses up one of the held item (survival only). */
    private fun consumeHeld(n: Int = 1) {
        if (!survival) return
        val s = heldStack() ?: return
        s.count -= n
        if (s.count <= 0) inventory.slots[input.selectedSlot] = null
    }

    /** Wears the held tool; it breaks when worn out. */
    private fun damageHeld(amount: Int) {
        if (!survival) return
        val s = heldStack() ?: return
        val def = Items[s.id] ?: return
        if (def.durability <= 0) return
        s.damage += amount
        if (s.damage >= def.durability) {
            inventory.slots[input.selectedSlot] = null
            uiEvents.add("toast:Your ${def.name} broke!")
            uiEvents.add("break_tool")
        }
    }

    private fun mobDrops(m: Mob): List<Pair<Int, Int>> {
        val r = java.util.Random()
        fun i(n: String) = Items.find(n)
        return when (m.type) {
            MobType.COW -> listOf(i("Leather") to r.nextInt(3), i("Raw Beef") to 1 + r.nextInt(3))
            MobType.PIG -> listOf(i("Raw Porkchop") to 1 + r.nextInt(3))
            MobType.SHEEP -> listOf(Blocks.WOOL_WHITE to 1, i("Raw Mutton") to 1 + r.nextInt(2))
            MobType.ZOMBIE -> listOf(i("Rotten Flesh") to r.nextInt(3)) + (if (r.nextInt(30) == 0) listOf(i("Iron Ingot") to 1) else emptyList())
            MobType.BOOMLING -> emptyList() // it blew itself up
        }.filter { it.second > 0 }
    }

    private fun tickFurnaces(dt: Float) {
        for ((pos, e) in world.blockEntities.map) {
            if (e !is FurnaceEntity) continue
            val x = RedstoneIds.x(pos); val y = RedstoneIds.y(pos); val z = RedstoneIds.z(pos)
            if (!world.isLoaded(x, z) || world.getBlock(x, y, z) != Blocks.FURNACE) continue
            val lit = e.tick(dt)
            val meta = world.getMeta(x, y, z)
            val want = if (lit) meta or 8 else meta and 7
            if (want != meta) setBlock(x, y, z, Blocks.FURNACE, want)
        }
    }

    /** Seconds needed to mine [block] with the currently held slot. */
    fun mineTime(block: Int): Float {
        val def = Blocks[block]
        if (def.hardness <= 0f) return 0f
        val item = heldItem()
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
        val be = world.blockEntities.remove(x, y, z)
        if (survival) {
            for ((dropId, n) in Drops.forBlock(id, heldItem())) drops.spawn(ItemStack(dropId, n), x + 0.5f, y + 0.3f, z + 0.5f)
            when (be) {
                is ChestEntity -> be.slots.forEach { s -> if (s != null) drops.spawn(s, x + 0.5f, y + 0.5f, z + 0.5f) }
                is FurnaceEntity -> be.contents().forEach { s -> drops.spawn(s, x + 0.5f, y + 0.5f, z + 0.5f) }
                null -> {}
            }
            if (Blocks[id].hardness > 0f) damageHeld(if (heldItem()?.tool == ToolType.SWORD) 2 else 1)
            exhaust(0.005f)
        }
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
        val sel = heldId()
        val item = Items[sel]
        // Eating works without looking at anything.
        if (item != null && item.use == ItemUse.EAT) {
            if (eat(item)) consumeHeld()
            return
        }
        // Tapping a mob attacks it if it is closer than the targeted block.
        val mobHit = mobs.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f)
        if (mobHit != null) {
            val blockDist = target?.let {
                val bx = it.x + 0.5f - player.x; val by = it.y + 0.5f - player.eyeY; val bz = it.z + 0.5f - player.z
                sqrt(bx * bx + by * by + bz * bz) - 0.5f
            } ?: Float.MAX_VALUE
            if (mobHit.distance <= blockDist) {
                val dmg = (item?.attack ?: 1).toFloat()
                val len = sqrt(dir[0] * dir[0] + dir[2] * dir[2]).coerceAtLeast(0.01f)
                mobs.damage(mobHit.mob, dmg, dir[0] / len, dir[2] / len)
                damageHeld(if (item?.tool == ToolType.SWORD) 1 else 2)
                exhaust(0.1f)
                if (mobHit.mob.dead) uiEvents.add("toast:${mobHit.mob.type.displayName} defeated")
                return
            }
        }
        val t = if (item?.use == ItemUse.BUCKET) {
            Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], REACH, hitWater = true)
        } else target
        t ?: return

        when (t.block) {
            Blocks.LEVER -> { redstone.toggleLever(t.x, t.y, t.z); return }
            Blocks.STONE_BUTTON -> { redstone.pressButton(t.x, t.y, t.z); return }
            Blocks.CRAFTING_TABLE -> { uiEvents.add("open:craft"); return }
            Blocks.FURNACE -> { world.blockEntities.furnace(t.x, t.y, t.z); uiEvents.add("open:furnace:${t.x},${t.y},${t.z}"); return }
            Blocks.CHEST -> { world.blockEntities.chest(t.x, t.y, t.z); uiEvents.add("open:chest:${t.x},${t.y},${t.z}"); return }
            Blocks.NOTE_BLOCK, Blocks.JUKEBOX -> if (item == null) return
        }

        if (item != null) {
            when (item.use) {
                ItemUse.TILL -> if ((t.block == Blocks.GRASS || t.block == Blocks.DIRT || t.block == Blocks.DIRT_PATH) &&
                    world.getBlock(t.x, t.y + 1, t.z) == Blocks.AIR) { setBlock(t.x, t.y, t.z, Blocks.FARMLAND); damageHeld(1) }
                ItemUse.PATH -> if (t.block == Blocks.GRASS && world.getBlock(t.x, t.y + 1, t.z) == Blocks.AIR) {
                    setBlock(t.x, t.y, t.z, Blocks.DIRT_PATH); damageHeld(1)
                }
                ItemUse.IGNITE -> if (t.block == Blocks.TNT) { redstone.prime(t.x, t.y, t.z); damageHeld(1) }
                ItemUse.BUCKET -> if (t.block == Blocks.WATER) {
                    setBlock(t.x, t.y, t.z, Blocks.AIR)
                    if (survival) { consumeHeld(); inventory.add(Items.find("Water Bucket"), 1).let { left -> if (left > 0) drops.spawn(ItemStack(Items.find("Water Bucket")), player.x, player.y, player.z) } }
                }
                ItemUse.WATER_BUCKET -> {
                    val x = t.x + t.nx; val y = t.y + t.ny; val z = t.z + t.nz
                    if (world.getBlock(x, y, z) == Blocks.AIR) {
                        setBlock(x, y, z, Blocks.WATER)
                        if (survival) inventory.slots[input.selectedSlot] = ItemStack(Items.find("Bucket"), 1)
                    }
                }
                else -> {}
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
        consumeHeld()
        uiEvents.add("place")
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
        level.health = health; level.food = food; level.saturation = saturation
        level.selectedSlot = input.selectedSlot
        world.saveChunks()
        saveDir?.let { level.write(it) }
    }
}
