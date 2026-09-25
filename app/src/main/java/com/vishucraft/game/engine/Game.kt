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
import com.vishucraft.game.world.floorInt
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
    val projectiles = Projectiles(world)
    val carts = Carts(world)
    val dimension get() = world.dimension
    private var portalTime = 0f
    private var bowCooldown = 0f
    val fluids = com.vishucraft.game.world.Fluids(world) { x, y, z, id, meta -> setBlock(x, y, z, id, meta) }
    val nature = Nature(world) { x, y, z, id, meta -> setBlock(x, y, z, id, meta) }

    // Weather: rain strength fades in and out; thunderstorms add lightning.
    var rain = 0f
        private set
    var thunder = false
        private set
    /** Brief sky flash after a lightning strike. */
    var flash = 0f
        private set
    private var raining = false
    private var weatherTimer = 240f + java.util.Random().nextFloat() * 400f
    private var thunderDelay = -1f
    private var lavaTimer = 0f
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

    // ---------------------------------------------------------------- Wi-Fi play

    /** Set while hosting or after joining a Wi-Fi game. */
    var net: com.vishucraft.game.net.Session? = null
    val isClient get() = net?.isClient == true
    private var applyingRemote = false

    /** The nearest player to a mob: this player or someone who joined over Wi-Fi. */
    class Target(val x: Float, val y: Float, val z: Float, val remoteId: Int) { val eyeY get() = y + Player.EYE }

    fun nearestTarget(x: Float, z: Float): Target {
        var best = Target(player.x, player.y, player.z, -1)
        var bestD = (player.x - x) * (player.x - x) + (player.z - z) * (player.z - z)
        if (!playerAlive) bestD = Float.MAX_VALUE
        net?.players?.values?.forEach { r ->
            val d = (r.x - x) * (r.x - x) + (r.z - z) * (r.z - z)
            if (d < bestD) { bestD = d; best = Target(r.x, r.y, r.z, r.id) }
        }
        return best
    }

    fun hurtTarget(t: Target, amount: Float, fromX: Float, fromZ: Float) {
        if (t.remoteId < 0) hurtPlayer(amount, fromX, fromZ) else net?.hurtRemote(t.remoteId, amount, fromX, fromZ)
    }

    /** A block change that came from the host (not sent back). */
    fun applyRemoteBlock(x: Int, y: Int, z: Int, id: Int, meta: Int) {
        if (id !in 0 until Blocks.COUNT) return
        applyingRemote = true
        try { setBlock(x, y, z, id, meta) } finally { applyingRemote = false }
    }

    /** A block change asked for by a client (host side): applied here, then broadcast to everyone. */
    fun remoteEdit(x: Int, y: Int, z: Int, id: Int, meta: Int) {
        if (id !in 0 until Blocks.COUNT) return
        if (!world.isLoaded(x, z)) { world.request(x shr 4, z shr 4); pendingEdits.add(intArrayOf(x, y, z, id, meta)); return }
        setBlock(x, y, z, id, meta)
    }
    private val pendingEdits = ArrayList<IntArray>()
    private val doors = (0 until Blocks.COUNT).filter { Blocks.isDoor(it) }.toSet()
    private val trapdoors = (0 until Blocks.COUNT).filter { Blocks.isTrapdoor(it) }.toSet()
    private val handOpenables = (0 until Blocks.COUNT).filter { Blocks.opensByHand(it) }.toSet()

    fun setRain(v: Float) { rain = v }

    /** Items laid out in the crafting grid while the inventory / crafting table is open. */
    val craftGrid = arrayOfNulls<ItemStack>(9)

    /** Puts everything left in the crafting grid back into the inventory (or drops it). */
    fun returnCraftGrid() {
        for (i in craftGrid.indices) {
            val s = craftGrid[i] ?: continue
            val left = inventory.add(s.id, s.count, s.damage)
            if (left > 0) drops.spawn(ItemStack(s.id, left, s.damage), player.x, player.y + 1f, player.z)
            craftGrid[i] = null
        }
    }

    fun heldStack(): ItemStack? = inventory.slots[input.selectedSlot.coerceIn(0, 8)]
    fun heldId(): Int = heldStack()?.id ?: 0
    fun heldItem(): ItemDef? = Items[heldId()]
    fun onPickup() { uiEvents.add("pickup"); sound("pop", player.x, player.y, player.z, 0.4f) }

    /** Sound hook (set by the Android layer): name, position, gain. */
    var soundSink: ((String, Float, Float, Float, Float) -> Unit)? = null
    /** Listener position hook for positional audio. */
    var listener: ((Float, Float, Float, Float) -> Unit)? = null
    fun sound(name: String, x: Float, y: Float, z: Float, gain: Float = 1f) { soundSink?.invoke(name, x, y, z, gain) }
    private fun blockSound(id: Int, x: Int, y: Int, z: Int, gain: Float = 1f) =
        sound("mat:$id", x + 0.5f, y + 0.5f, z + 0.5f, gain)
    private var nextStep = 1.7f
    private var digSoundTimer = 0f
    private var wasInWater = false
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
        redstone.onPrime = { x, y, z -> sound("fuse", x + 0.5f, y + 0.5f, z + 0.5f) }
        redstone.onClick = { x, y, z -> sound("click", x + 0.5f, y + 0.5f, z + 0.5f) }
        redstone.onDoor = { x, y, z -> sound("door", x + 0.5f, y + 0.5f, z + 0.5f) }
        redstone.daylight = { daylight }
        redstone.occupied = { x, y, z -> occupied(x, y, z) }
        mobs.onDeath = { m -> if (survival) for ((id, n) in mobDrops(m)) drops.spawn(ItemStack(id, n), m.x, m.y + 0.5f, m.z) }
        // Chunk offsets sorted nearest first, so the area around the player streams in first.
        val r = 16
        offsets = buildList {
            for (dz in -r..r) for (dx in -r..r) add(intArrayOf(dx, dz))
        }.sortedBy { it[0] * it[0] + it[1] * it[1] }

        redstone.onExplosion = { ex, ey, ez, r0 -> val r = r0
            val blast = r * 2.2f
            val dx = player.x - ex; val dy = player.eyeY - ey; val dz = player.z - ez
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < blast) {
                val push = (blast - d) * 1.4f / max(d, 0.5f)
                player.vx += dx * push; player.vy += (dy * push).coerceAtLeast(4f); player.vz += dz * push
                hurtPlayer((1f - d / blast) * r * 5.5f, ex, ez, knockback = false)
            }
            if (d < 40f) shake = 0.6f
            sound("explode", ex, ey, ez, 1.4f)
            net?.players?.values?.forEach { r ->
                val rd = sqrt((r.x - ex) * (r.x - ex) + (r.y + 1f - ey) * (r.y + 1f - ey) + (r.z - ez) * (r.z - ez))
                if (rd < blast) net?.hurtRemote(r.id, (1f - rd / blast) * r0 * 5.5f, ex, ez)
            }
            for (m in mobs.list) {
                val mx = m.x - ex; val mz = m.z - ez; val my = m.y + m.height / 2 - ey
                val md = sqrt(mx * mx + my * my + mz * mz)
                if (md < blast) mobs.damage(m, (1f - md / blast) * r * 6f, mx / max(md, 0.5f), mz / max(md, 0.5f))
            }
        }
    }

    /** Sun brightness 0..1, dimmed by rain and brightened by lightning. */
    val daylight: Float
        get() {
            if (dimension == com.vishucraft.game.world.Dimension.EMBER) return 0.12f
            if (dimension == com.vishucraft.game.world.Dimension.SKY) return 0.55f
            val s = kotlin.math.sin(timeOfDay * 2 * Math.PI).toFloat()
            val t = ((s + 0.2f) / 0.5f).coerceIn(0f, 1f)
            val sun = t * t * (3 - 2 * t) * (1f - 0.45f * rain)
            return maxOf(sun, flash)
        }

    private fun updateWeather(dt: Float) {
        weatherTimer -= dt
        val r = java.util.Random()
        if (weatherTimer <= 0f) {
            raining = !raining
            thunder = raining && r.nextInt(10) < 3
            weatherTimer = if (raining) 120f + r.nextFloat() * 200f else 300f + r.nextFloat() * 500f
        }
        rain = if (raining) minOf(1f, rain + dt * 0.12f) else maxOf(0f, rain - dt * 0.12f)
        if (flash > 0f) flash = maxOf(0f, flash - dt * 3f)
        if (thunder && rain > 0.8f && r.nextFloat() < dt / 14f) {
            flash = 0.9f
            thunderDelay = 0.3f + r.nextFloat() * 1.8f
        }
        if (thunderDelay > 0f) {
            thunderDelay -= dt
            if (thunderDelay <= 0f) { thunderDelay = -1f; sound("thunder", Float.NaN, 0f, 0f, 1f) }
        }
    }

    fun setWeather(rainOn: Boolean, storm: Boolean) {
        raining = rainOn; thunder = rainOn && storm; weatherTimer = if (rainOn) 240f else 600f
    }

    fun update(dt: Float) {
        net?.poll(this, dt)
        if (pendingEdits.isNotEmpty()) {
            val ready = pendingEdits.filter { world.isLoaded(it[0], it[2]) }
            for (e in ready) setBlock(e[0], e[1], e[2], e[3], e[4])
            pendingEdits.removeAll(ready.toSet())
        }
        streamChunks()

        input.consumeLook(look)
        player.rotate(look[0] * LOOK_SENSITIVITY * lookScale, -look[1] * LOOK_SENSITIVITY * lookScale)
        // Sticks and remote keys turn at up to 2.4 rad/s.
        player.rotate(input.lookStickX * 2.4f * dt, input.lookStickY * 1.8f * dt)
        // Aim with this frame's view before handling taps.
        player.lookDir(dir)
        target = Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], REACH)

        while (true) {
            when (input.actions.poll() ?: break) {
                GameInput.Action.PLACE -> use()
                GameInput.Action.TOGGLE_FLY -> if (!survival) { player.flying = !player.flying; player.vy = 0f }
            }
        }

        val loaded = world.isLoaded(player.blockX(), player.blockZ())
        if (loaded) {
            if (!spawned || level.arriving) {
                if (level.arriving) arrive()
                else {
                    // Drop the player onto the actual generated surface.
                    var y = Chunk.HEIGHT - 2
                    while (y > 1 && !Blocks.solid[world.getBlock(player.blockX(), y, player.blockZ())]) y--
                    player.y = y + 1f
                }
                spawned = true
            }
            lastVy = player.vy
            if (carts.riding == null) player.update(dt, world, input.moveForward, input.moveStrafe, input.jumpHeld, input.descendHeld)
            // Fall damage when landing hard (not while flying or in water).
            if (player.onGround && !wasOnGround && !player.flying && !player.inWater && lastVy < -14f) {
                hurtPlayer((-lastVy - 13f) * 0.9f, player.x, player.z, knockback = false)
            }
            wasOnGround = player.onGround
            // Footsteps and splashes.
            if (player.walkDist >= nextStep) {
                nextStep = player.walkDist + 1.7f
                val under = world.getBlock(player.blockX(), floorInt(player.y - 0.2f), player.blockZ())
                if (under != Blocks.AIR) blockSound(under, player.blockX(), floorInt(player.y - 0.2f), player.blockZ(), 0.35f)
            }
            if (player.inWater && !wasInWater && lastVy < -4f) sound("splash", player.x, player.y, player.z)
            wasInWater = player.inWater
            if (player.y < -20f) hurtPlayer(100f, player.x, player.z, knockback = false)
            if (!isClient) mobs.update(dt, this)
            drops.update(dt, this)
            // Lava burns.
            val inLava = world.getBlock(player.blockX(), floorInt(player.y + 0.3f), player.blockZ()) == Blocks.LAVA ||
                world.getBlock(player.blockX(), floorInt(player.eyeY), player.blockZ()) == Blocks.LAVA
            if (inLava) {
                lavaTimer -= dt
                if (lavaTimer <= 0f) { lavaTimer = 0.5f; hurtPlayer(4f, player.x, player.z, knockback = false) }
            } else lavaTimer = 0f
            if (survival) hunger(dt)
        }

        if (!survival && health < 20f) health = 20f
        if (!isClient) {
            fluids.tick(dt)
            nature.tick(dt, player.blockX(), player.blockZ())
            if (dimension == com.vishucraft.game.world.Dimension.OVERWORLD) updateWeather(dt) else rain = 0f
        }
        projectiles.update(dt, this)
        carts.update(dt, this)
        if (bowCooldown > 0f) bowCooldown -= dt
        // Standing in a portal for two seconds travels to the other world.
        val here = world.getBlock(player.blockX(), floorInt(player.y + 0.5f), player.blockZ())
        if (here == Blocks.EMBER_PORTAL || here == Blocks.SKY_PORTAL) {
            portalTime += dt
            if (portalTime >= 2f && net != null) {
                portalTime = -30f
                uiEvents.add("toast:Portals are closed during Wi-Fi games")
            } else if (portalTime >= 2f) {
                portalTime = -5f
                val target = when {
                    dimension != com.vishucraft.game.world.Dimension.OVERWORLD -> com.vishucraft.game.world.Dimension.OVERWORLD
                    here == Blocks.EMBER_PORTAL -> com.vishucraft.game.world.Dimension.EMBER
                    else -> com.vishucraft.game.world.Dimension.SKY
                }
                uiEvents.add("dimension:${target.name}")
            }
        } else if (portalTime > 0f) portalTime = 0f else if (portalTime < 0f) portalTime = minOf(0f, portalTime + dt)
        furnaceTimer += dt
        if (furnaceTimer >= 0.25f) { if (!isClient) { tickFurnaces(furnaceTimer); tickHoppers(furnaceTimer) }; furnaceTimer = 0f }

        player.lookDir(dir)
        target = Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], REACH)
        updateBreaking(dt)

        redstoneTimer += dt
        if (isClient) redstoneTimer = 0f // the host runs redstone and sends us the results
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
        sound("hurt", player.x, player.y + 1f, player.z)
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
        sound("eat", player.x, player.y + 1.5f, player.z)
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
            sound("break_tool", player.x, player.y + 1f, player.z)
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
            MobType.RATTLER -> listOf(i("Bone") to r.nextInt(3), i("Arrow") to r.nextInt(3))
            MobType.CRAWLER -> listOf(i("String") to r.nextInt(3), i("Slimeball") to (if (r.nextInt(4) == 0) 1 else 0))
            MobType.GLIDER -> listOf(i("Feather") to 1 + r.nextInt(2))
            MobType.CINDER -> listOf(Blocks.MAGMA to r.nextInt(2), i("Glowstone Dust") to r.nextInt(3), i("Netherite Scrap") to (if (r.nextInt(12) == 0) 1 else 0))
            MobType.WISP -> listOf(i("Emerald") to r.nextInt(2), Blocks.PURPUR to r.nextInt(2))
            MobType.VILLAGER, MobType.EXPLORER -> emptyList()
        }.filter { it.second > 0 }
    }

    /** Is a player, mob or dropped item standing in this block? (pressure plates) */
    private fun occupied(x: Int, y: Int, z: Int): Boolean {
        fun inside(px: Float, py: Float, pz: Float) = floorInt(px) == x && floorInt(pz) == z && py >= y && py < y + 0.5f
        if (inside(player.x, player.y, player.z)) return true
        if (mobs.list.any { !it.dead && inside(it.x, it.y, it.z) }) return true
        return drops.list.any { inside(it.x, it.y, it.z) }
    }

    /** Hoppers pull from the container above (and items lying on top) and push into the one below. */
    private fun tickHoppers(dt: Float) {
        for ((pos, e) in world.blockEntities.map) {
            if (e !is com.vishucraft.game.world.HopperEntity) continue
            val x = RedstoneIds.x(pos); val y = RedstoneIds.y(pos); val z = RedstoneIds.z(pos)
            if (!world.isLoaded(x, z) || world.getBlock(x, y, z) != Blocks.HOPPER) continue
            e.cooldown -= dt
            if (e.cooldown > 0f) continue
            e.cooldown = 0.4f
            // Push one item down.
            when (val below = world.blockEntities.get(x, y - 1, z)) {
                is ChestEntity -> e.slots.firstOrNull { it != null }?.let { s -> if (below.insert(s.id, 1, s.damage) == 0) e.takeOne() }
                is FurnaceEntity -> e.slots.firstOrNull { it != null }?.let { s ->
                    val smelt = com.vishucraft.game.world.Recipes.smelting.containsKey(s.id)
                    val target = if (smelt) below.input else below.fuel
                    val ok = (smelt || Items.fuel(s.id) > 0f) && (target == null || (target.id == s.id && target.count < target.maxStack))
                    if (ok) {
                        e.takeOne()
                        if (target == null) { if (smelt) below.input = ItemStack(s.id, 1) else below.fuel = ItemStack(s.id, 1) } else target.count++
                    }
                }
                else -> {}
            }
            // Pull one item from above.
            when (val above = world.blockEntities.get(x, y + 1, z)) {
                is FurnaceEntity -> above.output?.let { o -> if (e.insert(o.id, 1) == 0) { o.count--; if (o.count <= 0) above.output = null } }
                is ChestEntity -> above.slots.firstOrNull { it != null }?.let { s -> if (e.insert(s.id, 1, s.damage) == 0) above.takeOne() }
                else -> {}
            }
            // Swallow items lying on top.
            for (d in drops.list) {
                if (floorInt(d.x) == x && floorInt(d.z) == z && d.y >= y + 0.5f && d.y < y + 1.6f && d.stack.count > 0) {
                    d.stack.count = e.insert(d.stack.id, d.stack.count, d.stack.damage)
                }
            }
        }
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
        digSoundTimer -= dt
        if (digSoundTimer <= 0f) { digSoundTimer = 0.24f; blockSound(t.block, t.x, t.y, t.z, 0.45f) }
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
        blockSound(id, x, y, z)
        val be = world.blockEntities.remove(x, y, z)
        if (survival) {
            for ((dropId, n) in Drops.forBlock(id, heldItem(), meta)) drops.spawn(ItemStack(dropId, n), x + 0.5f, y + 0.3f, z + 0.5f)
            when (be) {
                is ChestEntity -> be.slots.forEach { s -> if (s != null) drops.spawn(s, x + 0.5f, y + 0.5f, z + 0.5f) }
                is FurnaceEntity -> be.contents().forEach { s -> drops.spawn(s, x + 0.5f, y + 0.5f, z + 0.5f) }
                else -> {}
            }
            if (Blocks[id].hardness > 0f) damageHeld(if (heldItem()?.tool == ToolType.SWORD) 2 else 1)
            exhaust(0.005f)
        }
        // Doors come in two halves.
        if (Blocks.isDoor(id)) {
            val oy = if (meta and com.vishucraft.game.world.Shapes.UPPER != 0) y - 1 else y + 1
            if (world.getBlock(x, oy, z) == id) setBlock(x, oy, z, Blocks.AIR)
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
        // Seeds, carrots and potatoes are planted on farmland.
        val crop = when (item?.name) { "Wheat Seeds" -> Blocks.WHEAT_CROP; "Carrot" -> Blocks.CARROTS; "Potato" -> Blocks.POTATOES; else -> -1 }
        val tgt = target
        if (crop > 0 && tgt != null && tgt.block == Blocks.FARMLAND && tgt.ny == 1 && world.getBlock(tgt.x, tgt.y + 1, tgt.z) == Blocks.AIR) {
            setBlock(tgt.x, tgt.y + 1, tgt.z, crop, 0)
            consumeHeld()
            blockSound(Blocks.GRASS, tgt.x, tgt.y + 1, tgt.z, 0.7f)
            return
        }
        // Eating works without looking at anything.
        if (item != null && item.use == ItemUse.EAT && mobs.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f)?.let { mobs.wantsFood(it.mob, sel) } != true) {
            if (eat(item)) consumeHeld()
            return
        }
        // Minecarts: tap to get in or out; hitting an empty one picks it up.
        if (carts.riding != null) { carts.riding = null; player.y += 0.6f; return }
        carts.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f)?.let { c ->
            if (item?.tool == ToolType.SWORD || item?.tool == ToolType.AXE) {
                carts.list.remove(c)
                if (survival) drops.spawn(ItemStack(Items.find("Minecart")), c.x, c.y + 0.5f, c.z)
            } else carts.riding = c
            return
        }
        // Bows shoot where you look (arrows are used up in survival).
        if (item?.use == ItemUse.BOW) {
            if (bowCooldown > 0f) return
            val arrow = Items.find("Arrow")
            if (survival && !inventory.remove(arrow, 1)) { uiEvents.add("toast:You need arrows"); return }
            bowCooldown = 0.6f
            val speed = 32f
            projectiles.shoot(player.x, player.eyeY - 0.1f, player.z, dir[0] * speed, dir[1] * speed + 0.6f, dir[2] * speed, true,
                if (item.name.contains("Enchanted")) 9f else 6f)
            damageHeld(1)
            sound("bow", player.x, player.eyeY, player.z)
            return
        }
        // Tapping a mob attacks it if it is closer than the targeted block.
        val mobHit = mobs.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f)
        if (mobHit != null) {
            val blockDist = target?.let {
                val bx = it.x + 0.5f - player.x; val by = it.y + 0.5f - player.eyeY; val bz = it.z + 0.5f - player.z
                sqrt(bx * bx + by * by + bz * bz) - 0.5f
            } ?: Float.MAX_VALUE
            if (mobHit.distance <= blockDist && mobs.feed(mobHit.mob, sel)) {
                consumeHeld()
                sound(mobs.voice(mobHit.mob.type), mobHit.mob.x, mobHit.mob.y + 1f, mobHit.mob.z, 0.8f)
                return
            }
            if (mobHit.distance <= blockDist) {
                val dmg = (item?.attack ?: 1).toFloat()
                val len = sqrt(dir[0] * dir[0] + dir[2] * dir[2]).coerceAtLeast(0.01f)
                if (isClient) net?.attack(mobHit.mob.uid, dmg, dir[0] / len, dir[2] / len)
                else mobs.damage(mobHit.mob, dmg, dir[0] / len, dir[2] / len)
                sound("hurt", mobHit.mob.x, mobHit.mob.y + 1f, mobHit.mob.z, 0.6f)
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
            in handOpenables -> { toggleOpen(t.x, t.y, t.z); return }
            Blocks.REPEATER -> {
                val m = world.getMeta(t.x, t.y, t.z)
                val delay = ((m shr 3) and 3) + 1 and 3
                setBlock(t.x, t.y, t.z, Blocks.REPEATER, (m and (7 or com.vishucraft.game.world.Shapes.POWERED)) or (delay shl 3))
                sound("click", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f)
                uiEvents.add("toast:Repeater delay: ${delay + 1}")
                return
            }
            Blocks.HOPPER -> { world.blockEntities.hopper(t.x, t.y, t.z); uiEvents.add("open:hopper:${t.x},${t.y},${t.z}"); return }
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
                ItemUse.IGNITE -> when {
                    t.block == Blocks.TNT -> { redstone.prime(t.x, t.y, t.z); damageHeld(1) }
                    t.block == Blocks.OBSIDIAN || t.block == Blocks.QUARTZ_BLOCK -> {
                        val portal = if (t.block == Blocks.OBSIDIAN) Blocks.EMBER_PORTAL else Blocks.SKY_PORTAL
                        if (lightPortal(t.x + t.nx, t.y + t.ny, t.z + t.nz, t.block, portal)) {
                            damageHeld(1); uiEvents.add("toast:The portal opens!")
                        } else uiEvents.add("toast:Build a frame (at least 4 wide, 5 tall) to make a portal")
                    }
                }
                ItemUse.CART -> if (com.vishucraft.game.world.Rails.isRail(t.block)) {
                    carts.list.add(Cart(t.x + 0.5f, t.y.toFloat(), t.z + 0.5f))
                    consumeHeld()
                }
                ItemUse.BUCKET -> if (Blocks.isLiquid(t.block) && world.getMeta(t.x, t.y, t.z) == 0) {
                    val full = Items.find(if (t.block == Blocks.LAVA) "Lava Bucket" else "Water Bucket")
                    setBlock(t.x, t.y, t.z, Blocks.AIR)
                    sound("splash", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f, 0.6f)
                    if (survival) { consumeHeld(); inventory.add(full, 1).let { left -> if (left > 0) drops.spawn(ItemStack(full), player.x, player.y, player.z) } }
                }
                ItemUse.LAVA_BUCKET -> {
                    val x = t.x + t.nx; val y = t.y + t.ny; val z = t.z + t.nz
                    if (world.getBlock(x, y, z) == Blocks.AIR) {
                        setBlock(x, y, z, Blocks.LAVA)
                        if (survival) inventory.slots[input.selectedSlot] = ItemStack(Items.find("Bucket"), 1)
                    }
                }
                ItemUse.GROW -> if (nature.boneMeal(t.x, t.y, t.z)) { consumeHeld(); uiEvents.add("place") }
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

    /**
     * Fills a vertical rectangular frame of [frame] blocks around (x, y, z) with portal blocks.
     * The inside may be 2..21 wide and 3..21 tall.
     */
    private fun lightPortal(x: Int, y: Int, z: Int, frame: Int, portal: Int): Boolean {
        if (world.getBlock(x, y, z) != Blocks.AIR) return false
        for (axis in 0..1) {
            val ax = if (axis == 0) 1 else 0; val az = 1 - ax
            // Find the frame's bottom-left inner corner.
            var by = y
            while (by > y - 22 && world.getBlock(x, by - 1, z) == Blocks.AIR) by--
            if (world.getBlock(x, by - 1, z) != frame) continue
            var left = 0
            while (left < 22 && world.getBlock(x - ax * (left + 1), by, z - az * (left + 1)) == Blocks.AIR) left++
            val lx = x - ax * left; val lz = z - az * left
            if (world.getBlock(lx - ax, by, lz - az) != frame) continue
            var w = 0
            while (w < 22 && world.getBlock(lx + ax * w, by, lz + az * w) == Blocks.AIR) w++
            var h = 0
            while (h < 22 && world.getBlock(lx, by + h, lz) == Blocks.AIR) h++
            if (w !in 2..21 || h !in 3..21) continue
            var ok = true
            for (i in 0 until w) for (j in 0 until h) if (world.getBlock(lx + ax * i, by + j, lz + az * i) != Blocks.AIR) ok = false
            for (i in 0 until w) { if (world.getBlock(lx + ax * i, by - 1, lz + az * i) != frame || world.getBlock(lx + ax * i, by + h, lz + az * i) != frame) ok = false }
            for (j in 0 until h) { if (world.getBlock(lx - ax, by + j, lz - az) != frame || world.getBlock(lx + ax * w, by + j, lz + az * w) != frame) ok = false }
            if (!ok) continue
            for (i in 0 until w) for (j in 0 until h) setBlock(lx + ax * i, by + j, lz + az * i, portal, 0)
            sound("fuse", x + 0.5f, y + 0.5f, z + 0.5f)
            return true
        }
        return false
    }

    /** After travelling: stand somewhere safe and build a portal home right here. */
    private fun arrive() {
        level.arriving = false
        val x = player.blockX(); val z = player.blockZ()
        val y = world.generator.surfaceHeight(x, z).coerceIn(8, Chunk.HEIGHT - 12) + 1
        val (frame, portal) = when (dimension) {
            com.vishucraft.game.world.Dimension.SKY -> Blocks.QUARTZ_BLOCK to Blocks.SKY_PORTAL
            com.vishucraft.game.world.Dimension.EMBER -> Blocks.OBSIDIAN to Blocks.EMBER_PORTAL
            com.vishucraft.game.world.Dimension.OVERWORLD -> Blocks.OBSIDIAN to Blocks.EMBER_PORTAL
        }
        // A small platform and a 4x5 frame beside the player.
        for (dx in -2..3) for (dz in -2..2) {
            setBlock(x + dx, y - 1, z + dz, if (dimension == com.vishucraft.game.world.Dimension.SKY) Blocks.END_STONE else frame, 0)
            for (dy in 0..4) setBlock(x + dx, y + dy, z + dz, Blocks.AIR, 0)
        }
        val fz = z + 2
        for (i in -1..2) for (j in -1..3) {
            val edge = i == -1 || i == 2 || j == -1 || j == 3
            setBlock(x + i, y + j, fz, if (edge) frame else portal, 0)
        }
        player.x = x + 0.5f; player.y = y.toFloat(); player.z = z + 0.5f
        player.vx = 0f; player.vy = 0f; player.vz = 0f
        portalTime = -5f
    }

    /** Opens or closes a door (both halves), trapdoor or fence gate. */
    fun toggleOpen(x: Int, y: Int, z: Int) {
        val id = world.getBlock(x, y, z)
        val m = world.getMeta(x, y, z) xor com.vishucraft.game.world.Shapes.OPEN
        setBlock(x, y, z, id, m)
        if (Blocks.isDoor(id)) {
            val oy = if (m and com.vishucraft.game.world.Shapes.UPPER != 0) y - 1 else y + 1
            if (world.getBlock(x, oy, z) == id) setBlock(x, oy, z, id, world.getMeta(x, oy, z) xor com.vishucraft.game.world.Shapes.OPEN)
        }
        sound("door", x + 0.5f, y + 0.5f, z + 0.5f)
    }

    private fun updateRail(x: Int, y: Int, z: Int) {
        val id = world.getBlock(x, y, z)
        if (!com.vishucraft.game.world.Rails.isRail(id)) return
        val m = world.getMeta(x, y, z)
        val shape = com.vishucraft.game.world.Rails.shapeFor(world, x, y, z, id == Blocks.POWERED_RAIL)
        if (shape != (m and 15)) setBlock(x, y, z, id, (m and 15.inv()) or shape)
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
        if (existing != Blocks.AIR && !Blocks.isLiquid(existing) && !replace) return
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
        val S = com.vishucraft.game.world.Shapes
        var meta = placementMeta(def.facing)
        when (id) {
            in doors -> {
                if (y + 1 >= Chunk.HEIGHT || world.getBlock(x, y + 1, z) != Blocks.AIR || !Blocks.solid[below]) return
                setBlock(x, y + 1, z, id, meta or S.UPPER)
            }
            Blocks.OAK_STAIRS, Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.BRICK_STAIRS,
            Blocks.SANDSTONE_STAIRS, in trapdoors -> if (t.ny == -1) meta = meta or S.UPPER
            Blocks.LADDER -> {
                if (t.ny != 0 || !Blocks.opaque[t.block]) return
                meta = if (t.nz == 1) 2 else if (t.nz == -1) 3 else if (t.nx == 1) 4 else 5
            }
            Blocks.REPEATER, Blocks.OBSERVER -> meta = meta xor 1
        }
        setBlock(x, y, z, id, meta)
        if (com.vishucraft.game.world.Rails.isRail(id)) {
            updateRail(x, y, z)
            for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) for (dy in -1..1) updateRail(x + dx, y + dy, z + dz)
        }
        consumeHeld()
        blockSound(id, x, y, z, 0.8f)
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
        val oldLight = Blocks.lightLevel(world.getBlock(x, y, z), world.getMeta(x, y, z))
        if (world.setBlock(x, y, z, id, meta)) {
            if (!applyingRemote) net?.blockChanged(x, y, z, id, meta)
            fluids.onChange(x, y, z)
            // Light spreads up to 14 blocks, so neighbouring chunks re-mesh (asynchronously) when a light changes.
            if (oldLight != Blocks.lightLevel(id, meta)) {
                for (dz in -1..1) for (dx in -1..1) if (dx != 0 || dz != 0) world.getChunk((x shr 4) + dx, (z shr 4) + dz)?.let { it.version++ }
            }
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
        val others = net?.players?.values?.map { (floorInt(it.x) shr 4) to (floorInt(it.z) shr 4) }.orEmpty()
        for (c in world.chunks.values) {
            if (max(abs(c.cx - pcx), abs(c.cz - pcz)) <= unloadR) continue
            if (others.any { (ox, oz) -> max(abs(c.cx - ox), abs(c.cz - oz)) <= unloadR }) continue
            world.unload(c)
        }
    }

    fun save() {
        if (isClient) return
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
