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
    internal val dir = FloatArray(3)
    private val offsets: List<IntArray>
    private var redstoneTimer = 0f

    /** Chunks whose mesh must be rebuilt this frame (batched so explosions stay cheap). */
    val dirtyChunks = HashSet<Long>()

    val redstone = Redstone(world) { x, y, z, id, meta -> setBlock(x, y, z, id, meta) }
    val mobs = Mobs(world)

    val drops = ItemEntities(world)
    val projectiles = Projectiles(world)
    val carts = Carts(world)
    /** Cooking pots, appliances and the seat the player sits on (see Kitchen.kt). */
    val kitchen = Kitchen()
    val boats = Boats(world)
    /** Airplanes, helicopters and the airports planes fly between (see Aircraft.kt). */
    val aircraft = Aircrafts(world)
    /** The elevator ride in progress (see Elevator.kt). */
    val lift = Lift()
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
    /** Rain, snow and thunderstorms (off unless turned on in the settings). */
    var weatherEnabled = false
    /** Waving plants and water, 3D clouds, stars and shadows (can be turned off on slow devices). */
    var fancyGraphics = true
    private var autosaveTimer = 0f
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
        internal set
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
    private val beds = (0 until Blocks.COUNT).filter { Blocks.isBed(it) }.toSet()
    private val trapdoors = (0 until Blocks.COUNT).filter { Blocks.isTrapdoor(it) }.toSet()
    private val handOpenables = (0 until Blocks.COUNT).filter { Blocks.opensByHand(it) }.toSet()

    fun setRain(v: Float) { rain = v }

    /** Items laid out in the crafting grid while the inventory / crafting table is open. */
    val craftGrid = arrayOfNulls<ItemStack>(9)

    /** Puts everything left in the crafting grid back into the inventory (or drops it). */
    /** Called when a chest / furnace screen closes. */
    fun closeContainer() { net?.closeContainer() }

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
    internal fun blockSound(id: Int, x: Int, y: Int, z: Int, gain: Float = 1f) =
        sound("mat:$id", x + 0.5f, y + 0.5f, z + 0.5f, gain)
    private var nextStep = 1.7f
    private var digSoundTimer = 0f
    private var wasInWater = false
    val playerAlive get() = health > 0f
    private var regenTimer = 0f
    private var lastVy = 0f
    private var lastJump = false
    private var lastCrouch = false
    private var wasOnGround = true

    /** Messages for the UI thread: "hurt", "died", or "toast:<text>". */
    val uiEvents = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** Active potion effects: key (see Items.POTIONS) -> seconds left. */
    val effects = HashMap<String, Float>()
    fun hasEffect(key: String) = (effects[key] ?: 0f) > 0f
    /** Looking through a spyglass. */
    var zoomed = false
    /** Flying with an Elytra. */
    var gliding = false
    private var rocketBoost = 0f
    private var potionRegen = 0f
    private var fishTimer = -1f
    private var fishSlot = -1
    internal var clockSeconds = 0f
    /** Breath left under water, 0..10 bubbles (drowning starts at 0). */
    var air = 10f
    private var drownTimer = 0f
    private var achievementTimer = 0f

    val xpLevel get() = com.vishucraft.game.world.Xp.levelOf(level.xp).first
    val xpProgress get() = com.vishucraft.game.world.Xp.levelOf(level.xp).second

    fun addXp(points: Int) {
        if (points <= 0 || !survival) return
        val before = xpLevel
        level.xp += points
        sound("pop", player.x, player.y + 1f, player.z, 0.25f)
        if (xpLevel > before) {
            sound("level_up", player.x, player.y + 1f, player.z)
            uiEvents.add("toast:Level ${xpLevel}!")
            if (xpLevel >= 10) award("level10")
        }
    }

    /** Unlocks an achievement once and tells the player. */
    fun award(key: String) {
        if (!level.achievements.add(key)) return
        uiEvents.add("achievement:" + com.vishucraft.game.world.Achievements.title(key))
        sound("level_up", player.x, player.y + 1f, player.z, 0.7f)
    }

    /** Things in your inventory that earn achievements. */
    private fun checkInventoryAchievements() {
        fun has(name: String) = inventory.count(Items.find(name)) > 0
        val logs = intArrayOf(Blocks.LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG)
        if (logs.any { inventory.count(it) > 0 }) award("wood")
        if (inventory.count(Blocks.CRAFTING_TABLE) > 0) award("bench")
        if (inventory.slots.any { s -> s != null && Items[s.id]?.tool == ToolType.PICKAXE }) award("pickaxe")
        if (inventory.count(Blocks.FURNACE) > 0) award("furnace")
        if (has("Iron Ingot")) award("iron")
        if (has("Diamond")) award("diamond")
        if (has("Bread")) award("bread")
        if (has("Leather")) award("leather")
        if (carts.riding != null) award("cart")
        if (mount != null) award("ride")
        if (gliding) award("elytra")
        if (dimension == com.vishucraft.game.world.Dimension.EMBER) award("ember")
        if (dimension == com.vishucraft.game.world.Dimension.SKY) award("sky")
    }

    /** Air under water: 15 seconds of breath, then 1 heart of damage a second. */
    private fun updateAir(dt: Float) {
        if (player.headInWater && !player.flying) {
            air = maxOf(0f, air - dt / 1.5f)
            if (air <= 0f) {
                drownTimer += dt
                if (drownTimer >= 1f) { drownTimer = 0f; hurtPlayer(2f, player.x, player.z, knockback = false, ignoreArmor = true) }
            }
        } else { air = minOf(10f, air + dt * 5f); drownTimer = 0f }
    }

    /** The horse the player is riding, if any. */
    var mount: Mob? = null
    /** Sheep that were sheared (uid -> time when their wool grows back). */
    internal val sheared = HashMap<Int, Float>()
    internal var hornCooldown = 0f

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
        redstone.onActivate = { x, y, z, id -> activated(x, y, z, id) }
        redstone.daylight = { daylight }
        redstone.occupied = { x, y, z -> occupied(x, y, z) }
        redstone.plateLoad = { x, y, z, items -> plateLoad(x, y, z, items) }
        if (net == null) loadEntities()
        mobs.onDeath = { m ->
            if (survival) for ((id, n) in mobDrops(m)) drops.spawn(ItemStack(id, n), m.x, m.y + 0.5f, m.z)
            val near = (m.x - player.x) * (m.x - player.x) + (m.z - player.z) * (m.z - player.z) < 24f * 24f
            if (near) {
                addXp(if (m.type == MobType.WARDEN) 500 else if (m.type.hostile) 5 else 1 + java.util.Random().nextInt(3))
                if (m.type.hostile) award("kill")
            }
            if (m.type == MobType.WARDEN) {
                // The boss drops its loot even in creative, and the game shows the ending.
                if (!survival) for ((id, n) in mobDrops(m)) drops.spawn(ItemStack(id, n), m.x, m.y + 0.5f, m.z)
                level.bossDefeated = true
                award("boss")
                uiEvents.add("ending")
            }
        }
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
            val action = input.actions.poll() ?: break
            when (action) {
                GameInput.Action.PLACE -> use()
                GameInput.Action.ATTACK -> attack()
                GameInput.Action.DROP, GameInput.Action.DROP_STACK -> dropHeld(action == GameInput.Action.DROP_STACK)
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
            // Jump (Space) next to a minecart gets in; jump or crouch (Shift) again gets out.
            val jumpPressed = input.jumpHeld && !lastJump
            val crouchPressed = input.descendHeld && !lastCrouch
            lastJump = input.jumpHeld; lastCrouch = input.descendHeld
            if (liftInput(jumpPressed, crouchPressed)) {}
            else if (boats.riding != null && crouchPressed) { boats.riding = null; player.y += 0.8f }
            else if (mount != null && crouchPressed) dismount()
            else if (carts.riding != null && crouchPressed) carts.leave(this)
            else if (carts.riding?.kind == Cart.ENGINE && jumpPressed) sound("train_horn", player.x, player.y + 2f, player.z)
            else if (carts.riding == null && jumpPressed && !player.flying) {
                val len = sqrt(dir[0] * dir[0] + dir[2] * dir[2]).coerceAtLeast(0.01f)
                carts.nearby(player.x, player.y, player.z, dir[0] / len, dir[2] / len)?.let { carts.enter(it, dir[0], dir[2]) }
            }
            lastVy = player.vy
            player.speedMul = (if (input.sprint && !player.flying) 1.3f else 1f) * (if (hasEffect("swiftness")) 1.4f else 1f)
            player.jumpMul = if (hasEffect("leaping")) 1.22f else 1f
            if (carts.riding == null && mount == null && boats.riding == null && aircraft.riding == null && !sitting && !lift.riding) player.update(dt, world, input.moveForward, input.moveStrafe, input.jumpHeld, input.descendHeld)
            updateFlight(dt)
            // Fall damage when landing hard (not while flying or in water).
            if (player.onGround && !wasOnGround && !player.flying && !player.inWater && lastVy < -14f && !hasEffect("slow_falling")) {
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
            mount?.let { h ->
                if (h.dead || h !in mobs.list) dismount()
                else { player.x = h.x; player.y = h.y + 1.05f; player.z = h.z; player.vx = 0f; player.vy = 0f; player.vz = 0f }
            }
            drops.update(dt, this)
            // Lava burns.
            val inLava = world.getBlock(player.blockX(), floorInt(player.y + 0.3f), player.blockZ()) == Blocks.LAVA ||
                world.getBlock(player.blockX(), floorInt(player.eyeY), player.blockZ()) == Blocks.LAVA
            if (inLava && !hasEffect("fire_resistance")) {
                lavaTimer -= dt
                if (lavaTimer <= 0f) { lavaTimer = 0.5f; hurtPlayer(4f, player.x, player.z, knockback = false) }
            } else lavaTimer = 0f
            if (survival) hunger(dt)
        }

        updateEffects(dt)
        updateCampfires(dt)
        updateKitchen(dt)
        if (survival) updateAir(dt) else air = 10f
        achievementTimer += dt
        if (achievementTimer >= 1f) { achievementTimer = 0f; checkInventoryAchievements() }
        if (!survival && health < 20f) health = 20f
        if (!isClient) {
            fluids.tick(dt)
            nature.tick(dt, player.blockX(), player.blockZ())
            if (dimension == com.vishucraft.game.world.Dimension.OVERWORLD && weatherEnabled) updateWeather(dt)
            else { rain = maxOf(0f, rain - dt * 0.5f); raining = false; thunder = false }
        }
        projectiles.update(dt, this)
        carts.update(dt, this)
        boats.update(dt, this)
        aircraft.update(dt, this)
        updateLift(dt)
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
        // Autosave every minute so a crash or a closed app never loses much.
        autosaveTimer += dt
        if (autosaveTimer >= 60f && spawned) { autosaveTimer = 0f; if (!isClient) save() }
    }

    fun hurtPlayer(amount: Float, fromX: Float, fromZ: Float, knockback: Boolean = true, ignoreArmor: Boolean = false) {
        if (!playerAlive || amount <= 0f || !survival) return
        val armor = if (ignoreArmor) 0 else inventory.armorPoints().coerceAtMost(20)
        var dmg = amount * (1f - armor * 0.04f)
        // Holding a shield blocks half of the damage from hits.
        if (knockback && heldItem()?.name == "Shield") { dmg *= 0.5f; damageHeld(1) }
        health -= dmg
        if (knockback) {
            val dx = player.x - fromX; val dz = player.z - fromZ
            val d = sqrt(dx * dx + dz * dz).coerceAtLeast(0.1f)
            player.vx += dx / d * 7f; player.vz += dz / d * 7f; player.vy = max(player.vy, 5f)
        }
        uiEvents.add("hurt")
        sound("hurt", player.x, player.y + 1f, player.z)
        if (health <= 0f) {
            // A Totem of Undying anywhere in the inventory saves you once.
            val totem = Items.find("Totem of Undying")
            if (inventory.remove(totem, 1)) {
                health = 2f
                effects["regeneration"] = 40f
                uiEvents.add("toast:Your Totem of Undying saved you!")
                award("totem")
                sound("pop", player.x, player.y + 1f, player.z)
                return
            }
            respawn()
        }
    }

    private fun respawn() {
        // Survival: everything you carried is dropped where you died.
        for (s in inventory.slots) if (s != null) drops.spawn(s, player.x, player.y + 1f, player.z)
        for (s in inventory.armor) if (s != null) drops.spawn(s, player.x, player.y + 1f, player.z)
        inventory.clear()
        food = 20f; saturation = 5f; exhaustion = 0f
        level.xp = 0; air = 10f; effects.clear()
        val bed = level.hasBedSpawn && dimension == com.vishucraft.game.world.Dimension.OVERWORLD &&
            world.isLoaded(level.bedX, level.bedZ) && Blocks.isBed(world.getBlock(level.bedX, level.bedY, level.bedZ))
        if (bed) {
            player.x = level.bedX + 0.5f; player.y = level.bedY + 0.6f; player.z = level.bedZ + 0.5f
        } else {
            if (level.hasBedSpawn && dimension == com.vishucraft.game.world.Dimension.OVERWORLD) {
                level.hasBedSpawn = false
                uiEvents.add("toast:Your bed was missing, so you woke up at the world spawn")
            }
            val (sx, sy, sz) = world.findSpawn()
            player.x = sx; player.y = sy; player.z = sz
        }
        player.vx = 0f; player.vy = 0f; player.vz = 0f
        health = 20f
        spawned = bed // drop onto the real surface once the chunk is loaded (unless waking in bed)
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
        if (food >= 20f && !item.name.contains("Golden Apple") && item.name != "Chorus Fruit") return false
        food = minOf(20f, food + item.food)
        saturation = minOf(food, saturation + item.food * 1.2f)
        if (item.name == "Golden Apple") health = minOf(20f, health + 8f)
        if (item.name == "Enchanted Golden Apple") { health = 20f; effects["regeneration"] = 20f; effects["fire_resistance"] = 300f }
        if (item.name == "Chorus Fruit") chorusTeleport()
        // A hot cup of chai keeps you going.
        if (item.name == "Masala Chai") effects["swiftness"] = maxOf(effects["swiftness"] ?: 0f, 30f)
        uiEvents.add("eat")
        sound("eat", player.x, player.y + 1.5f, player.z)
        return true
    }

    /** Uses up one of the held item (survival only). */
    internal fun consumeHeld(n: Int = 1) {
        if (!survival) return
        val s = heldStack() ?: return
        s.count -= n
        if (s.count <= 0) inventory.slots[input.selectedSlot] = null
    }

    /** Wears the held tool; it breaks when worn out. */
    internal fun damageHeld(amount: Int) {
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
            MobType.ZOMBIE -> listOf(i("Rotten Flesh") to r.nextInt(3)) + (if (r.nextInt(30) == 0) listOf(i("Iron Ingot") to 1) else emptyList()) +
                (if (r.nextInt(20) == 0) listOf(i("Poisonous Potato") to 1) else emptyList())
            MobType.BOOMLING -> listOf(i("Gunpowder") to r.nextInt(3)) // only when defeated before it blows up
            MobType.RATTLER -> listOf(i("Bone") to r.nextInt(3), i("Arrow") to r.nextInt(3), i("Rabbit's Foot") to (if (r.nextInt(10) == 0) 1 else 0))
            MobType.CRAWLER -> listOf(i("String") to r.nextInt(3), i("Slimeball") to (if (r.nextInt(4) == 0) 1 else 0),
                i("Spider Eye") to (if (r.nextInt(3) == 0) 1 else 0))
            MobType.GLIDER -> listOf(i("Feather") to 1 + r.nextInt(2), i("Phantom Membrane") to r.nextInt(2))
            MobType.CINDER -> listOf(Blocks.MAGMA to r.nextInt(2), i("Glowstone Dust") to r.nextInt(3), i("Netherite Scrap") to (if (r.nextInt(12) == 0) 1 else 0),
                i("Blaze Rod") to r.nextInt(2), i("Magma Cream") to (if (r.nextInt(3) == 0) 1 else 0), i("Ghast Tear") to (if (r.nextInt(6) == 0) 1 else 0))
            MobType.WISP -> listOf(i("Emerald") to r.nextInt(2), Blocks.PURPUR to r.nextInt(2), i("Ender Pearl") to r.nextInt(2),
                i("Amethyst Shard") to r.nextInt(2))
            MobType.VILLAGER, MobType.EXPLORER, MobType.WOLF, MobType.HORSE -> if (m.type == MobType.HORSE) listOf(i("Leather") to r.nextInt(3)) else emptyList()
            MobType.WARDEN -> listOf(i("Nether Star") to 1, i("Elytra") to 1, i("Diamond") to 6, i("Totem of Undying") to 1)
            MobType.CHICKEN -> listOf(i("Feather") to r.nextInt(3), i("Raw Chicken") to 1)
            MobType.RABBIT -> listOf(i("Rabbit Hide") to r.nextInt(2), i("Raw Rabbit") to 1, i("Rabbit's Foot") to (if (r.nextInt(10) == 0) 1 else 0))
            MobType.CAT -> listOf(i("String") to r.nextInt(3))
            MobType.SQUID -> listOf(i("Ink Sac") to 1 + r.nextInt(3))
            MobType.COD -> listOf(i("Raw Cod") to 1, i("Bone Meal") to (if (r.nextInt(20) == 0) 1 else 0))
            MobType.SKELETON -> listOf(i("Bone") to r.nextInt(3), i("Arrow") to r.nextInt(3))
            MobType.SLIME -> if (m.age < 0f) listOf(i("Slimeball") to r.nextInt(3)) else emptyList()
            MobType.WITCH -> listOf(listOf(i("Glass Bottle"), Blocks.REDSTONE_DUST, i("Sugar"), i("Gunpowder"), i("Spider Eye"))[r.nextInt(5)] to 1 + r.nextInt(2))
            MobType.ENDERMAN -> listOf(i("Ender Pearl") to r.nextInt(2))
            MobType.DROWNED -> listOf(i("Rotten Flesh") to r.nextInt(3), i("Trident") to (if (r.nextInt(15) == 0) 1 else 0),
                i("Nautilus Shell") to (if (r.nextInt(30) == 0) 1 else 0), i("Copper Ingot") to (if (r.nextInt(10) == 0) 1 else 0))
            else -> newMobDrops(m)
        }.filter { it.second > 0 }
    }

    /** Is a player, mob or dropped item standing in this block? (pressure plates) */
    private fun plateLoad(x: Int, y: Int, z: Int, items: Boolean): Int {
        fun inside(px: Float, py: Float, pz: Float) = floorInt(px) == x && floorInt(pz) == z && py >= y && py < y + 0.5f
        var n = if (inside(player.x, player.y, player.z)) 1 else 0
        n += mobs.list.count { !it.dead && inside(it.x, it.y, it.z) }
        if (items) n += drops.list.count { inside(it.x, it.y, it.z) }
        return n
    }

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
            val block = if (world.isLoaded(x, z)) world.getBlock(x, y, z) else Blocks.AIR
            if (!Blocks.isFurnace(block)) continue
            val outBefore = e.output?.count ?: 0
            val lit = e.tick(dt, when (block) { Blocks.SMOKER -> 1; Blocks.BLAST_FURNACE -> 2; else -> 0 })
            if ((e.output?.count ?: 0) > outBefore) addXp(1)
            val meta = world.getMeta(x, y, z)
            val want = if (lit) meta or 8 else meta and 7
            if (want != meta) setBlock(x, y, z, block, want)
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
        if (item?.use == ItemUse.SHEAR && (def.name.endsWith("Leaves") || def.name.endsWith("Wool") || block == Blocks.COBWEB)) {
            time /= 8f
        } else if (rightTool) {
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

    internal fun breakBlock(x: Int, y: Int, z: Int) {
        val id = world.getBlock(x, y, z)
        val meta = world.getMeta(x, y, z)
        setBlock(x, y, z, Blocks.AIR)
        blockSound(id, x, y, z)
        val be = world.blockEntities.remove(x, y, z)
        if (survival) {
            val dropped = Drops.forBlock(id, heldItem(), meta)
            for ((dropId, n) in dropped) drops.spawn(ItemStack(dropId, n), x + 0.5f, y + 0.3f, z + 0.5f)
            if (dropped.isNotEmpty()) addXp(when (id) {
                Blocks.COAL_ORE -> 1; Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE -> 3; Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE -> 5; else -> 0
            })
            when (be) {
                is ChestEntity -> be.slots.forEach { s -> if (s != null) drops.spawn(s, x + 0.5f, y + 0.5f, z + 0.5f) }
                is FurnaceEntity -> be.contents().forEach { s -> drops.spawn(s, x + 0.5f, y + 0.5f, z + 0.5f) }
                is com.vishucraft.game.world.ItemHolderEntity -> be.stack?.let { drops.spawn(it, x + 0.5f, y + 0.5f, z + 0.5f) }
                else -> {}
            }
            if (Blocks[id].hardness > 0f) damageHeld(if (heldItem()?.tool == ToolType.SWORD) 2 else 1)
            exhaust(0.005f)
        }
        // Beds are two blocks long.
        if (Blocks.isBed(id)) {
            val n = ChunkMesher.NORMALS[(meta and 7).coerceIn(2, 5)]
            val head = meta and com.vishucraft.game.world.Shapes.UPPER != 0
            val ox = if (head) x - n[0] else x + n[0]; val oz = if (head) z - n[2] else z + n[2]
            if (world.getBlock(ox, y, oz) == id) setBlock(ox, y, oz, Blocks.AIR)
        }
        // Fridges are two blocks tall.
        if (Blocks.isTall(id)) {
            val oy = if (meta and com.vishucraft.game.world.Shapes.UPPER != 0) y - 1 else y + 1
            if (world.getBlock(x, oy, z) == id) {
                setBlock(x, oy, z, Blocks.AIR)
                // The top half drops nothing itself, so breaking it drops the fridge here.
                if (survival && oy < y) for ((dropId, n) in Drops.forBlock(id, heldItem(), 0)) drops.spawn(ItemStack(dropId, n), x + 0.5f, y + 0.3f, z + 0.5f)
                if (survival) (world.blockEntities.remove(x, oy, z) as? ChestEntity)?.slots?.forEach { s -> if (s != null) drops.spawn(s, x + 0.5f, y + 0.5f, z + 0.5f) }
            }
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
        // Buttons and levers stuck to this block fall off.
        for (f in 0 until 6) {
            val ax = x + n[f][0]; val ay = y + n[f][1]; val az = z + n[f][2]
            val a = world.getBlock(ax, ay, az)
            if (!com.vishucraft.game.world.Shapes.isAttached(a)) continue
            if (com.vishucraft.game.world.Shapes.supportFace(world.getMeta(ax, ay, az)) != (f xor 1)) continue
            setBlock(ax, ay, az, Blocks.AIR)
            if (survival) drops.spawn(ItemStack(a), ax + 0.5f, ay + 0.3f, az + 0.5f)
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
    /** Hits the mob in front if it is closer than the targeted block (left click on computers). */
    private fun attack() {
        val mobHit = mobs.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f) ?: return
        val blockDist = target?.let {
            val bx = it.x + 0.5f - player.x; val by = it.y + 0.5f - player.eyeY; val bz = it.z + 0.5f - player.z
            sqrt(bx * bx + by * by + bz * bz) - 0.5f
        } ?: Float.MAX_VALUE
        if (mobHit.distance <= blockDist) hit(mobHit, heldItem())
    }

    private fun hit(mobHit: MobHit, item: ItemDef?) {
        var dmg = (item?.attack ?: 1).toFloat()
        if (hasEffect("strength")) dmg += 3f
        // A mace hits harder the further you fall onto the target.
        if (item?.name == "Mace" && player.vy < -6f) dmg += (-player.vy - 6f) * 1.5f
        val len = sqrt(dir[0] * dir[0] + dir[2] * dir[2]).coerceAtLeast(0.01f)
        if (isClient) net?.attack(mobHit.mob.uid, dmg, dir[0] / len, dir[2] / len)
        else mobs.damage(mobHit.mob, dmg, dir[0] / len, dir[2] / len)
        sound("hurt", mobHit.mob.x, mobHit.mob.y + 1f, mobHit.mob.z, 0.6f)
        damageHeld(if (item?.tool == ToolType.SWORD) 1 else 2)
        exhaust(0.1f)
        if (mobHit.mob.dead) uiEvents.add("toast:${mobHit.mob.type.displayName} defeated")
    }

    // ---------------------------------------------------------------- item pack 2

    /** Slow falling, Elytra gliding and firework boosts (after the normal player physics). */
    private fun updateFlight(dt: Float) {
        val p = player
        if (hasEffect("slow_falling") && p.vy < -2f) p.vy = -2f
        val elytra = inventory.armor[1]?.let { Items[it.id]?.name == "Elytra" } == true
        gliding = elytra && !p.onGround && !p.flying && !p.inWater && carts.riding == null && (gliding || p.vy < -4f)
        if (!gliding) { rocketBoost = 0f; return }
        val boost = rocketBoost > 0f
        if (boost) rocketBoost -= dt
        val speed = if (boost) 22f else 11f + max(0f, -dir[1]) * 10f
        p.vx = dir[0] * speed; p.vz = dir[2] * speed
        p.vy = if (boost) dir[1] * speed else max(p.vy, -2.5f + dir[1] * 6f)
    }

    private fun updateEffects(dt: Float) {
        clockSeconds += dt
        if (effects.isNotEmpty()) {
            val it = effects.entries.iterator()
            while (it.hasNext()) { val e = it.next(); e.setValue(e.value - dt); if (e.value <= 0f) it.remove() }
        }
        if (hasEffect("regeneration")) {
            potionRegen += dt
            if (potionRegen >= 2.5f) { potionRegen = 0f; health = minOf(20f, health + 1f) }
        }
        if (zoomed && heldItem()?.use != ItemUse.SPYGLASS) zoomed = false
        // Fishing: a fish bites after a few seconds if you keep holding the rod.
        if (fishTimer >= 0f) {
            if (input.selectedSlot != fishSlot || heldItem()?.use != ItemUse.FISH) { fishTimer = -1f; return }
            fishTimer -= dt
            if (fishTimer < 0f) {
                val r = java.util.Random().nextInt(100)
                val catch = Items.find(when { r < 55 -> "Raw Cod"; r < 80 -> "Raw Salmon"; r < 92 -> "Pufferfish"; r < 97 -> "Tropical Fish"; else -> "Nautilus Shell" })
                if (inventory.add(catch, 1) > 0) drops.spawn(ItemStack(catch), player.x, player.y + 1f, player.z)
                damageHeld(1)
                sound("splash", player.x, player.y, player.z, 0.7f)
                uiEvents.add("toast:You caught a ${Items.displayName(catch)}!")
                award("fish"); addXp(1 + java.util.Random().nextInt(3))
                uiEvents.add("pickup")
            }
        }
    }

    /** Puts a container (bowl, bottle, bucket) back after using up the held item. */
    internal fun giveBack(name: String) {
        if (!survival) return
        val id = Items.find(name)
        val s = heldStack()
        if (s == null) inventory.slots[input.selectedSlot] = ItemStack(id, 1)
        else if (inventory.add(id, 1) > 0) drops.spawn(ItemStack(id), player.x, player.y + 1f, player.z)
    }

    private fun drink(item: ItemDef) {
        when {
            item.name == "Milk Bucket" -> { effects.clear(); uiEvents.add("toast:All effects cleared") }
            item.name.startsWith("Potion of ") -> {
                award("potion")
                val key = Items.POTIONS.first { "Potion of ${it.first}" == item.name }.second
                if (key == "healing") health = minOf(20f, health + 8f)
                else {
                    val seconds = if (key == "regeneration") 45f else 180f
                    effects[key] = seconds
                    uiEvents.add("toast:${item.name.removePrefix("Potion of ")} for ${(seconds / 60).toInt()}:${"%02d".format((seconds % 60).toInt())}")
                }
            }
        }
        sound("eat", player.x, player.y + 1.5f, player.z)
        consumeHeld()
        giveBack(if (item.name == "Milk Bucket") "Bucket" else "Glass Bottle")
        uiEvents.add("eat")
    }

    /** Uses that don't need a block under the crosshair. Returns true when the item was used. */
    private fun useItem(item: ItemDef): Boolean {
        when (item.use) {
            ItemUse.DRINK -> { drink(item); return true }
            ItemUse.THROW -> {
                val speed = 24f
                val kind = when (item.name) { "Ender Pearl" -> Projectile.PEARL; "Egg" -> Projectile.EGG; else -> Projectile.SNOWBALL }
                projectiles.shoot(player.x, player.eyeY - 0.1f, player.z, dir[0] * speed, dir[1] * speed + 1f, dir[2] * speed, true, 0f, kind)
                sound("bow", player.x, player.eyeY, player.z, 0.6f)
                consumeHeld()
                return true
            }
            ItemUse.FISH -> {
                if (fishTimer >= 0f) { fishTimer = -1f; uiEvents.add("toast:You reeled in"); return true }
                val t = Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 16f, hitWater = true)
                if (t == null || t.block != Blocks.WATER) { uiEvents.add("toast:Look at water to fish"); return true }
                fishTimer = 3f + java.util.Random().nextFloat() * 6f
                fishSlot = input.selectedSlot
                sound("splash", t.x + 0.5f, t.y + 1f, t.z + 0.5f, 0.4f)
                uiEvents.add("toast:Fishing… keep holding the rod")
                return true
            }
            ItemUse.COMPASS -> {
                val (sx, _, sz) = if (level.hasBedSpawn) Triple(level.bedX.toFloat(), 0f, level.bedZ.toFloat()) else world.findSpawn()
                val dx = sx - player.x; val dz = sz - player.z
                val dist = sqrt(dx * dx + dz * dz).toInt()
                val ns = if (dz < -0.4f * kotlin.math.abs(dx)) "north" else if (dz > 0.4f * kotlin.math.abs(dx)) "south" else ""
                val ew = if (dx > 0.4f * kotlin.math.abs(dz)) "east" else if (dx < -0.4f * kotlin.math.abs(dz)) "west" else ""
                val way = listOf(ns, ew).filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "here" }
                uiEvents.add("toast:" + (if (dist < 3) "You are at your spawn point" else "Spawn is $dist blocks $way"))
                return true
            }
            ItemUse.CLOCK -> {
                val h = ((timeOfDay * 24 + 6) % 24).toInt(); val m = (((timeOfDay * 24 + 6) % 1) * 60).toInt()
                uiEvents.add("toast:%02d:%02d · %s".format(h, m, if (daylight > 0.5f) "day" else "night"))
                return true
            }
            ItemUse.SPYGLASS -> { zoomed = !zoomed; return true }
            ItemUse.ROCKET -> {
                if (gliding) { rocketBoost = 1.6f; consumeHeld(); sound("fuse", player.x, player.y, player.z, 0.6f) }
                else uiEvents.add("toast:Use rockets while flying with an Elytra")
                return true
            }
            else -> return false
        }
    }

    fun dismount() {
        val h = mount ?: return
        mount = null
        player.y = h.y + 1.2f; player.x += 0.8f
    }

    // ---------------------------------------------------------------- block pack 2

    /** Anvil: repairs the held tool or armor with iron ingots (a quarter per ingot) for one level. */
    private fun repairHeld() {
        val s = heldStack(); val def = s?.let { Items[it.id] }
        if (s == null || def == null || def.durability <= 0 || s.damage <= 0) { uiEvents.add("toast:Hold a damaged tool, weapon or armor and tap the anvil"); return }
        if (!survival) { s.damage = 0; uiEvents.add("toast:Repaired!"); sound("click", player.x, player.y, player.z); return }
        if (xpLevel < 1) { uiEvents.add("toast:Repairing costs 1 level"); return }
        val iron = Items.find("Iron Ingot")
        val quarter = (def.durability + 3) / 4
        val need = ((s.damage + quarter - 1) / quarter).coerceAtLeast(1)
        val use = minOf(need, inventory.count(iron))
        if (use == 0) { uiEvents.add("toast:You need iron ingots to repair it"); return }
        inventory.remove(iron, use)
        s.damage = maxOf(0, s.damage - use * quarter)
        // One level less.
        var total = 0; for (l in 0 until xpLevel - 1) total += com.vishucraft.game.world.Xp.needed(l)
        level.xp = total
        sound("click", player.x, player.y, player.z)
        uiEvents.add("toast:" + if (s.damage == 0) "Repaired!" else "Partly repaired (more iron needed)")
    }

    /** Composter: plant scraps fill it up; when full it gives bone meal. */
    private fun compost(x: Int, y: Int, z: Int, item: ItemDef?) {
        val m = world.getMeta(x, y, z)
        if (m >= 7) {
            drops.spawn(ItemStack(Items.find("Bone Meal")), x + 0.5f, y + 1.1f, z + 0.5f)
            setBlock(x, y, z, Blocks.COMPOSTER, 0)
            return
        }
        val held = heldId()
        val plant = item?.name in setOf("Wheat Seeds", "Wheat", "Carrot", "Potato", "Apple", "Melon Slice", "Beetroot", "Sweet Berries", "Glow Berries", "Cookie", "Bread", "Pumpkin Pie") ||
            (!Items.isItem(held) && held != 0 && (Blocks[held].category == com.vishucraft.game.world.Category.NATURE && Blocks[held].tool != ToolType.PICKAXE))
        if (!plant) { uiEvents.add("toast:Put plants, seeds or food in the composter"); return }
        consumeHeld()
        if (java.util.Random().nextInt(100) < 60) setBlock(x, y, z, Blocks.COMPOSTER, m + 1)
        blockSound(Blocks.GRASS, x, y, z, 0.6f)
        if (m + 1 >= 7) uiEvents.add("toast:The composter is full: tap it for bone meal")
    }

    /** Brewing stand: a glass bottle in hand plus an ingredient in the inventory makes a potion. */
    private fun brew() {
        val bottle = Items.find("Glass Bottle")
        if (heldId() != bottle) { uiEvents.add("toast:Hold a glass bottle. Ingredients: golden carrot, ghast tear, sugar, rabbit's foot, magma cream, blaze powder or phantom membrane"); return }
        val recipes = listOf("Golden Carrot" to "Healing", "Ghast Tear" to "Regeneration", "Sugar" to "Swiftness", "Rabbit's Foot" to "Leaping",
            "Magma Cream" to "Fire Resistance", "Blaze Powder" to "Strength", "Phantom Membrane" to "Slow Falling")
        val (ing, potion) = recipes.firstOrNull { inventory.count(Items.find(it.first)) > 0 } ?: run { uiEvents.add("toast:You need an ingredient in your inventory"); return }
        inventory.remove(Items.find(ing), 1)
        consumeHeld()
        val p = Items.find("Potion of $potion")
        if (inventory.add(p, 1) > 0) drops.spawn(ItemStack(p), player.x, player.y + 1f, player.z)
        sound("splash", player.x, player.y + 1f, player.z, 0.5f)
        uiEvents.add("toast:Brewed a Potion of $potion")
    }

    /** Cake: seven slices, each fills 2 hunger points. */
    private fun eatCake(x: Int, y: Int, z: Int) {
        if (survival && food >= 20f) { uiEvents.add("toast:You're not hungry"); return }
        food = minOf(20f, food + 2f); saturation = minOf(food, saturation + 0.4f)
        val m = world.getMeta(x, y, z) + 1
        if (m >= 7) setBlock(x, y, z, Blocks.AIR) else setBlock(x, y, z, Blocks.CAKE, m)
        sound("eat", x + 0.5f, y + 0.5f, z + 0.5f)
    }

    /** Writes text on a sign (from the sign screen). */
    fun setSignText(x: Int, y: Int, z: Int, text: String) {
        if (world.getBlock(x, y, z) != Blocks.SIGN) return
        world.blockEntities.sign(x, y, z)?.text = text.take(60)
    }

    fun signText(x: Int, y: Int, z: Int): String = (world.blockEntities.get(x, y, z) as? com.vishucraft.game.world.SignEntity)?.text ?: ""

    /** Item frame: put the held item in, or take the item out with an empty hand. */
    private fun useFrame(x: Int, y: Int, z: Int) {
        val h = world.blockEntities.holder(x, y, z) ?: return
        val held = heldStack()
        val inside = h.stack
        if (inside != null && held == null) {
            if (inventory.add(inside.id, inside.count, inside.damage) > 0) drops.spawn(inside, x + 0.5f, y + 0.5f, z + 0.5f)
            h.stack = null
        } else if (inside == null && held != null) {
            h.stack = ItemStack(held.id, 1, held.damage)
            if (survival) consumeHeld() else Unit
        } else if (inside != null) uiEvents.add("toast:Item frame: ${Items.displayName(inside.id)}")
        dirtyChunks.add(Chunk.key(x shr 4, z shr 4))
        sound("click", x + 0.5f, y + 0.5f, z + 0.5f, 0.5f)
    }

    /** Jukebox: insert a music disc to play it; tap again to take it out. */
    private fun useJukebox(x: Int, y: Int, z: Int) {
        val h = world.blockEntities.holder(x, y, z) ?: return
        val inside = h.stack
        if (inside != null) {
            if (inventory.add(inside.id, 1) > 0) drops.spawn(inside, x + 0.5f, y + 1f, z + 0.5f)
            h.stack = null
            uiEvents.add("toast:Took out ${Items.displayName(inside.id)}")
            return
        }
        val name = heldItem()?.name
        if (name == null || !name.startsWith("Music Disc")) { uiEvents.add("toast:Put a music disc in the jukebox"); return }
        h.stack = ItemStack(heldId(), 1)
        consumeHeld()
        val tune = name.substringAfter("(").removeSuffix(")").lowercase()
        sound("disc_$tune", x + 0.5f, y + 1f, z + 0.5f, 1.2f)
        uiEvents.add("toast:Now playing: ${name.substringAfter("(").removeSuffix(")")}")
    }

    /** Raw food cooks on a campfire in a few seconds. */
    private fun campfireCooks(name: String?): String? = when (name) {
        "Raw Beef" -> "Steak"; "Raw Porkchop" -> "Cooked Porkchop"; "Raw Mutton" -> "Cooked Mutton"; "Raw Chicken" -> "Cooked Chicken"
        "Raw Cod" -> "Cooked Cod"; "Raw Salmon" -> "Cooked Salmon"; "Raw Rabbit" -> "Cooked Rabbit"; "Potato" -> "Baked Potato"
        "Dried Kelp" -> null; else -> null
    }

    private class Cooking(val x: Int, val y: Int, val z: Int, val result: Int, var left: Float)
    private val cooking = ArrayList<Cooking>()

    private fun cookOnCampfire(x: Int, y: Int, z: Int, item: ItemDef) {
        if (cooking.count { it.x == x && it.y == y && it.z == z } >= 4) { uiEvents.add("toast:The campfire is full"); return }
        cooking.add(Cooking(x, y, z, Items.find(campfireCooks(item.name)!!), 6f))
        consumeHeld()
        sound("fuse", x + 0.5f, y + 0.5f, z + 0.5f, 0.4f)
    }

    private fun updateCampfires(dt: Float) {
        val it = cooking.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (world.getBlock(c.x, c.y, c.z) != Blocks.CAMPFIRE) { it.remove(); continue }
            c.left -= dt
            if (c.left <= 0f) { drops.spawn(ItemStack(c.result), c.x + 0.5f, c.y + 0.8f, c.z + 0.5f); it.remove() }
        }
        // Standing in a campfire burns.
        if (survival && !hasEffect("fire_resistance") && world.getBlock(player.blockX(), floorInt(player.y + 0.1f), player.blockZ()) == Blocks.CAMPFIRE) {
            campfireBurn += dt
            if (campfireBurn >= 1f) { campfireBurn = 0f; hurtPlayer(1f, player.x, player.z, knockback = false) }
        } else campfireBurn = 0f
    }
    private var campfireBurn = 0f

    /** For the HUD: sign text or item frame contents under the crosshair. */
    fun lookedAtBlockLabel(): String? {
        val t = target ?: return null
        return when (t.block) {
            Blocks.SIGN -> signText(t.x, t.y, t.z).ifEmpty { null }?.let { "“$it”" }
            Blocks.ITEM_FRAME -> (world.blockEntities.get(t.x, t.y, t.z) as? com.vishucraft.game.world.ItemHolderEntity)?.stack?.let { Items.displayName(it.id) }
            Blocks.JUKEBOX -> (world.blockEntities.get(t.x, t.y, t.z) as? com.vishucraft.game.world.ItemHolderEntity)?.stack?.let { "Playing: " + Items.displayName(it.id) }
            Blocks.COMPOSTER -> "Composter ${world.getMeta(t.x, t.y, t.z).coerceAtMost(7)}/7"
            else -> null
        }
    }

    /** Villager trade [index] with the villager [uid]. Returns a message for the screen. */
    fun trade(uid: Int, index: Int): String {
        val v = mobs.list.firstOrNull { it.uid == uid && it.type == MobType.VILLAGER } ?: return "The villager walked away"
        val t = com.vishucraft.game.world.Trades.jobFor(v.uid).offers.getOrNull(index) ?: return ""
        if (survival && inventory.count(t.give) < t.giveCount) return "You need ${t.giveCount} ${Items.displayName(t.give)}"
        if (survival) inventory.remove(t.give, t.giveCount)
        val left = inventory.add(t.get, t.getCount)
        if (left > 0) drops.spawn(ItemStack(t.get, left), player.x, player.y + 1f, player.z)
        sound("pop", v.x, v.y + 1.5f, v.z)
        addXp(2)
        award("trade")
        return "Got ${t.getCount} ${Items.displayName(t.get)}"
    }

    /** Gives a mob a name (from a name tag). */
    fun nameMob(uid: Int, name: String) {
        val m = mobs.list.firstOrNull { it.uid == uid } ?: return
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        if (survival && !inventory.remove(Items.find("Name Tag"), 1)) return
        m.customName = clean
        uiEvents.add("toast:This ${m.type.displayName.lowercase()} is now called $clean")
    }

    /** The boss bar: name and health 0..1 while the Sky Warden is around. */
    fun bossBar(): Pair<String, Float>? = mobs.list.firstOrNull { it.type == MobType.WARDEN && !it.dead }?.let {
        it.type.displayName to (it.health / it.type.maxHealth).coerceIn(0f, 1f)
    }

    /** What the crosshair is on: a named or tamed mob's label for the HUD. */
    fun lookedAtLabel(): String? {
        val hit = mobs.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 8f) ?: return lookedAtBlockLabel()
        val m = hit.mob
        val name = m.customName
        return when {
            name != null && m.tamed -> "$name (your ${m.type.displayName.lowercase()}${if (m.sitting) ", sitting" else ""})"
            name != null -> name
            m.tamed -> "Your ${m.type.displayName.lowercase()}${if (m.sitting) " (sitting)" else ""}"
            m.type == MobType.HORSE && m.saddled -> "Saddled horse"
            else -> null
        }
    }

    /** An egg broke: sometimes a chick hatches. */
    fun eggLanded(x: Float, y: Float, z: Float) {
        if (isClient || java.util.Random().nextInt(8) != 0) return
        mobs.list.add(Mob(MobType.CHICKEN, x, y, z).also { it.age = -600f })
    }

    /** Pets, horses, name tags and leads. Returns true when the tap was used. */
    private fun petActions(item: ItemDef?, held: Int, mob: Mob): Boolean {
        val rnd = java.util.Random()
        val name = item?.name
        val weapon = item?.tool == ToolType.SWORD || item?.tool == ToolType.AXE
        if (name == "Name Tag") { uiEvents.add("name:${mob.uid}"); return true }
        if (mob.type == MobType.VILLAGER && !weapon) { uiEvents.add("trade:${mob.uid}"); return true }
        if (name == "Lead" && !mob.type.hostile) {
            mob.leashed = !mob.leashed
            uiEvents.add("toast:" + if (mob.leashed) "The ${mob.type.displayName.lowercase()} follows you on the lead" else "Let go of the lead")
            return true
        }
        if (mob.type == MobType.HORSE) {
            if (name == "Saddle" && !mob.saddled) { mob.saddled = true; mob.tamed = true; consumeHeld(); uiEvents.add("toast:Saddled! Tap the horse to ride it"); return true }
            if (mob.saddled && !weapon && !mobs.wantsFood(mob, held)) {
                mount = mob; carts.riding = null
                uiEvents.add("toast:Riding. Crouch (Shift / ▼) to get off")
                return true
            }
        }
        val tameFood = when (mob.type) { MobType.WOLF -> name == "Bone"; MobType.CAT -> name == "Raw Cod" || name == "Raw Salmon"; else -> false }
        if (tameFood && !mob.tamed) {
            consumeHeld()
            if (rnd.nextInt(3) == 0) {
                mob.tamed = true; mob.sitting = false
                award("tame")
                uiEvents.add("toast:You tamed the ${mob.type.displayName.lowercase()}! Tap it to make it sit or follow")
                sound(mobs.voice(mob.type), mob.x, mob.y + 0.5f, mob.z)
            } else uiEvents.add("toast:The ${mob.type.displayName.lowercase()} isn't sure yet… try again")
            return true
        }
        if (mob.tamed && (mob.type == MobType.WOLF || mob.type == MobType.CAT) && !mobs.wantsFood(mob, held)) {
            mob.sitting = !mob.sitting
            uiEvents.add("toast:" + if (mob.sitting) "Sit!" else "Come on!")
            return true
        }
        return false
    }

    /** Shears on a sheep and a bucket on a cow. Returns true when used. */
    private fun useOnMob(item: ItemDef, mob: Mob): Boolean {
        if (item.use == ItemUse.SHEAR && mob.type == MobType.SHEEP) {
            if ((sheared[mob.uid] ?: 0f) > clockSeconds) { uiEvents.add("toast:Its wool hasn't grown back yet"); return true }
            sheared[mob.uid] = clockSeconds + 120f
            drops.spawn(ItemStack(Blocks.WOOL_WHITE, 1 + java.util.Random().nextInt(3)), mob.x, mob.y + 1f, mob.z)
            sound("hit_wool", mob.x, mob.y + 1f, mob.z)
            damageHeld(1)
            return true
        }
        if (item.name == "Bucket" && mob.type == MobType.COW) {
            consumeHeld()
            giveBack("Milk Bucket")
            sound("splash", mob.x, mob.y + 1f, mob.z, 0.4f)
            return true
        }
        return false
    }

    /** Chorus fruit: a random hop to a safe spot nearby. */
    private fun chorusTeleport() {
        val r = java.util.Random()
        repeat(16) {
            val x = floorInt(player.x) + r.nextInt(17) - 8; val z = floorInt(player.z) + r.nextInt(17) - 8
            if (!world.isLoaded(x, z)) return@repeat
            for (y in minOf(Chunk.HEIGHT - 3, floorInt(player.y) + 8) downTo maxOf(1, floorInt(player.y) - 8)) {
                if (Blocks.solid[world.getBlock(x, y - 1, z)] && !Blocks.solid[world.getBlock(x, y, z)] && !Blocks.solid[world.getBlock(x, y + 1, z)]) {
                    player.x = x + 0.5f; player.y = y.toFloat(); player.z = z + 0.5f; player.vy = 0f
                    sound("pop", player.x, player.y, player.z)
                    return
                }
            }
        }
    }

    /** Throws the held item (or the whole stack) forward. */
    private fun dropHeld(all: Boolean) {
        val s = heldStack() ?: return
        val n = if (all) s.count else 1
        drops.toss(ItemStack(s.id, n, s.damage), player.x, player.eyeY - 0.3f, player.z, dir[0], dir[1], dir[2])
        s.count -= n
        if (s.count <= 0) inventory.slots[input.selectedSlot] = null
        uiEvents.add("place")
    }

    /** Flint and steel wears out; a fire charge is used up. */
    private fun ignited(item: ItemDef) { if (item.name == "Fire Charge") consumeHeld() else damageHeld(1) }

    /** An ender pearl landed: teleport there (it stings a little). */
    fun pearlLanded(x: Float, y: Float, z: Float) {
        player.x = x; player.y = y; player.z = z
        player.vx = 0f; player.vy = 0f; player.vz = 0f
        hurtPlayer(2f, x, z, knockback = false, ignoreArmor = true)
        sound("pop", x, y, z)
    }

    private fun use() {
        val sel = heldId()
        val item = Items[sel]
        // Seeds, carrots and potatoes are planted on farmland.
        val crop = Farming.cropFor(item?.name)
        val tgt = target
        if (farmingUse(tgt, item)) return
        if (crop > 0 && tgt != null && tgt.block == Blocks.FARMLAND && tgt.ny == 1 && world.getBlock(tgt.x, tgt.y + 1, tgt.z) == Blocks.AIR) {
            setBlock(tgt.x, tgt.y + 1, tgt.z, crop, 0)
            consumeHeld()
            blockSound(Blocks.GRASS, tgt.x, tgt.y + 1, tgt.z, 0.7f)
            return
        }
        if (pack3Early(tgt, item, sel)) return
        if (mount != null) { dismount(); return }
        if (aircraftUse(item, tgt)) return
        if (boats.riding != null) { boats.riding = null; player.y += 0.8f; return }
        // Boats: tap to get in; a sword or axe breaks one back into an item.
        boats.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f)?.let { b ->
            if (item?.tool == ToolType.SWORD || item?.tool == ToolType.AXE) {
                boats.list.remove(b)
                if (survival) drops.spawn(ItemStack(Items.find("Oak Boat")), b.x, b.y + 0.5f, b.z)
            } else { boats.riding = b; carts.riding = null }
            return
        }
        if (item?.use == ItemUse.BOAT) {
            val w = Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], REACH, hitWater = true)
            if (w != null) {
                val onWater = w.block == Blocks.WATER
                val y = if (onWater) w.y + 0.5f else w.y + 1f
                boats.list.add(Boat(w.x + 0.5f, y, w.z + 0.5f).also { it.yaw = player.yaw })
                consumeHeld()
                return
            }
        }
        // Kitchen and home blocks come first: an egg goes on the tawa instead of being thrown, milk into the fridge.
        if (kitchen.seat != null) { standUp(); return }
        target?.let { c -> if (kitchenBlock(c, item, sel)) return }
        if (item != null && useItem(item)) return
        mobs.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f)?.let { m ->
            if (!isClient && pack3Mob(item, m.mob)) return
            if (item != null && useOnMob(item, m.mob)) return
            if (!isClient && petActions(item, sel, m.mob)) return
        }
        // Raw food on a campfire cooks instead of being eaten.
        target?.let { c -> if (c.block == Blocks.CAMPFIRE && item != null && campfireCooks(item.name) != null) { cookOnCampfire(c.x, c.y, c.z, item); return } }
        // Eating works without looking at anything.
        if (item != null && item.use == ItemUse.EAT && mobs.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f)?.let { mobs.wantsFood(it.mob, sel) } != true) {
            if (eat(item)) {
                consumeHeld()
                when {
                    item.name.endsWith("Stew") || item.name.endsWith("Soup") -> giveBack("Bowl")
                    item.name == "Honey Bottle" -> giveBack("Glass Bottle")
                }
            }
            return
        }
        // Minecarts: tap to get in or out; hitting an empty one picks it up.
        if (carts.riding != null) { carts.leave(this); return }
        carts.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 4f)?.let { c ->
            if (item?.tool == ToolType.SWORD || item?.tool == ToolType.AXE) {
                carts.list.remove(c)
                if (survival) drops.spawn(ItemStack(Items.find(Cart.itemName(c.kind))), c.x, c.y + 0.5f, c.z)
            } else {
                carts.enter(c, dir[0], dir[2])
                if (c.isTrain) uiEvents.add(when (carts.power(c)?.kind) {
                    Cart.METRO, Cart.ENGINE -> "toast:All aboard! It stops for 10 seconds at every Station Stop Rail and turns back at the end of the line"
                    else -> "toast:A coach on its own: press forward to push it, or hook it behind an engine or metro"
                })
            }
            return
        }
        // Bows shoot where you look (arrows are used up in survival).
        if (item?.use == ItemUse.BOW) {
            if (bowCooldown > 0f) return
            val arrow = Items.find("Arrow")
            if (survival && !inventory.remove(arrow, 1)) { uiEvents.add("toast:You need arrows"); return }
            val crossbow = item.name == "Crossbow"
            bowCooldown = if (crossbow) 1.1f else 0.6f
            val speed = if (crossbow) 40f else 32f
            projectiles.shoot(player.x, player.eyeY - 0.1f, player.z, dir[0] * speed, dir[1] * speed + 0.6f, dir[2] * speed, true,
                if (crossbow || item.name.contains("Enchanted")) 9f else 6f)
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
            if (mobHit.distance <= blockDist) { hit(mobHit, item); return }
        }
        val t = if (item?.use == ItemUse.BUCKET) {
            Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], REACH, hitWater = true)
        } else target
        t ?: return
        if (liftTap(t, sel)) return
        if (pack3Block(t, item, sel)) return

        when (t.block) {
            Blocks.LEVER -> { redstone.toggleLever(t.x, t.y, t.z); return }
            in Blocks.WOOD_BUTTON_FIRST until Blocks.WOOD_BUTTON_FIRST + 6, Blocks.STONE_BUTTON -> { redstone.pressButton(t.x, t.y, t.z); return }
            Blocks.CRAFTING_TABLE -> { uiEvents.add("open:craft"); return }
            in handOpenables -> { toggleOpen(t.x, t.y, t.z); return }
            in beds -> { useBed(t.x, t.y, t.z); return }
            Blocks.REPEATER -> {
                val m = world.getMeta(t.x, t.y, t.z)
                val delay = ((m shr 3) and 3) + 1 and 3
                setBlock(t.x, t.y, t.z, Blocks.REPEATER, (m and (7 or com.vishucraft.game.world.Shapes.POWERED)) or (delay shl 3))
                sound("click", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f)
                uiEvents.add("toast:Repeater delay: ${delay + 1}")
                return
            }
            Blocks.HOPPER -> { world.blockEntities.hopper(t.x, t.y, t.z); net?.openContainer(t.x, t.y, t.z, 2); uiEvents.add("open:hopper:${t.x},${t.y},${t.z}"); return }
            Blocks.FURNACE, Blocks.SMOKER, Blocks.BLAST_FURNACE -> { world.blockEntities.furnace(t.x, t.y, t.z); net?.openContainer(t.x, t.y, t.z, 1); uiEvents.add("open:furnace:${t.x},${t.y},${t.z}"); return }
            Blocks.CHEST, Blocks.BARREL -> { world.blockEntities.chest(t.x, t.y, t.z); net?.openContainer(t.x, t.y, t.z, 0); uiEvents.add("open:chest:${t.x},${t.y},${t.z}"); return }
            Blocks.ENDER_CHEST -> { uiEvents.add("open:ender"); sound("door", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f, 0.5f); return }
            Blocks.ANVIL -> { repairHeld(); return }
            Blocks.COMPOSTER -> { compost(t.x, t.y, t.z, item); return }
            Blocks.BELL -> { sound("bell", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f); return }
            Blocks.BREWING_STAND -> { brew(); return }
            Blocks.CAKE -> { eatCake(t.x, t.y, t.z); return }
            Blocks.SIGN -> { uiEvents.add("signedit:${t.x},${t.y},${t.z}"); return }
            Blocks.ITEM_FRAME -> { useFrame(t.x, t.y, t.z); return }
            Blocks.JUKEBOX -> { useJukebox(t.x, t.y, t.z); return }
            Blocks.CAMPFIRE -> if (item != null && campfireCooks(item.name) != null) { cookOnCampfire(t.x, t.y, t.z, item); return }
            Blocks.NOTE_BLOCK -> if (item == null) return
        }

        if (item != null) {
            when (item.use) {
                ItemUse.TILL -> if ((t.block == Blocks.GRASS || t.block == Blocks.DIRT || t.block == Blocks.DIRT_PATH) &&
                    world.getBlock(t.x, t.y + 1, t.z) == Blocks.AIR) { setBlock(t.x, t.y, t.z, Blocks.FARMLAND); damageHeld(1) }
                ItemUse.PATH -> if (t.block == Blocks.GRASS && world.getBlock(t.x, t.y + 1, t.z) == Blocks.AIR) {
                    setBlock(t.x, t.y, t.z, Blocks.DIRT_PATH); damageHeld(1)
                }
                ItemUse.IGNITE -> when {
                    t.block == Blocks.TNT -> { redstone.prime(t.x, t.y, t.z); ignited(item) }
                    t.block == Blocks.OBSIDIAN || t.block == Blocks.QUARTZ_BLOCK -> {
                        val portal = if (t.block == Blocks.OBSIDIAN) Blocks.EMBER_PORTAL else Blocks.SKY_PORTAL
                        if (lightPortal(t.x + t.nx, t.y + t.ny, t.z + t.nz, t.block, portal)) {
                            ignited(item); uiEvents.add("toast:The portal opens!")
                        } else uiEvents.add("toast:Build a frame (at least 4 wide, 5 tall) to make a portal")
                    }
                }
                ItemUse.BLUEPRINT -> blueprintOf(item.name)?.let { if (placeBuilding(t, it)) consumeHeld() }
                ItemUse.CART -> if (com.vishucraft.game.world.Rails.isRail(t.block)) {
                    // The cart faces away from you, so it rolls off the way you are looking.
                    val cart = Cart(t.x + 0.5f, t.y.toFloat(), t.z + 0.5f, Cart.kindOf(item.name))
                    carts.list.add(cart)
                    carts.aim(cart, dir[0], dir[2])
                    // A coach placed just behind a train is hooked on to it.
                    if (cart.kind == Cart.COACH) {
                        carts.couple(cart)
                        if (cart.leader != null) uiEvents.add("toast:Coach coupled to the train")
                    }
                    consumeHeld()
                    sound("hit_stone", cart.x, cart.y, cart.z, 0.6f)
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
        if (sel == Blocks.PUMPKIN || sel == Blocks.JACK_O_LANTERN) {
            val replace = Blocks[t.block].render == RenderType.CROSS
            if (replace) checkGolem(t.x, t.y, t.z) else checkGolem(t.x + t.nx, t.y + t.ny, t.z + t.nz)
        }
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

    /**
     * Tapping a bed sets your respawn point there; at night (or in a thunderstorm) you sleep until morning.
     * Beds do not work in the other worlds: they explode.
     */
    fun useBed(x: Int, y: Int, z: Int) {
        if (dimension != com.vishucraft.game.world.Dimension.OVERWORLD) {
            setBlock(x, y, z, Blocks.AIR)
            explode(x + 0.5f, y + 0.5f, z + 0.5f, 3.5f)
            uiEvents.add("toast:Beds don't work in this world!")
            return
        }
        // Remember the foot of the bed as the respawn point.
        val meta = world.getMeta(x, y, z)
        val n = ChunkMesher.NORMALS[(meta and 7).coerceIn(2, 5)]
        val (fx, fz) = if (meta and com.vishucraft.game.world.Shapes.UPPER != 0) (x - n[0]) to (z - n[2]) else x to z
        level.hasBedSpawn = true; level.bedX = fx; level.bedY = y; level.bedZ = fz
        val night = daylight < 0.3f || thunder
        when {
            isClient -> uiEvents.add("toast:Respawn point set (the host controls the time)")
            !night -> uiEvents.add("toast:Respawn point set. You can only sleep at night")
            mobs.list.any { it.type.hostile && !it.dead && (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) + (it.y - y) * (it.y - y) < 64f } ->
                uiEvents.add("toast:You may not rest now, there are monsters nearby")
            else -> {
                uiEvents.add("sleep")
                award("sleep")
                timeOfDay = 0.02f // sunrise
                setWeather(false, false); rain = 0f
                if (health < 20f && survival) health = minOf(20f, health + 4f)
                uiEvents.add("toast:Good morning! Respawn point set")
            }
        }
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
        // Powered and stop rails stay straight.
        val shape = com.vishucraft.game.world.Rails.shapeFor(world, x, y, z, id == Blocks.POWERED_RAIL || id == Blocks.STOP_RAIL)
        // Powered rails keep their "on" flag (bit 3) when re-shaped.
        val mask = if (id == Blocks.POWERED_RAIL) 7 else 15
        if (shape != com.vishucraft.game.world.Rails.shape(id, m)) setBlock(x, y, z, id, (m and mask.inv()) or shape)
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
            in beds -> {
                val headDir = meta xor 1 // placementMeta faces the player; the head points away from them
                val n = ChunkMesher.NORMALS[headDir]
                val hx = x + n[0]; val hz = z + n[2]
                if (world.getBlock(hx, y, hz) != Blocks.AIR || !Blocks.solid[world.getBlock(hx, y - 1, hz)] || !Blocks.solid[below]) return
                meta = headDir
                setBlock(hx, y, hz, id, headDir or S.UPPER)
            }
            in doors -> {
                if (y + 1 >= Chunk.HEIGHT || world.getBlock(x, y + 1, z) != Blocks.AIR || !Blocks.solid[below]) return
                setBlock(x, y + 1, z, id, meta or S.UPPER)
            }
            Blocks.OAK_STAIRS, Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.BRICK_STAIRS,
            Blocks.SANDSTONE_STAIRS, in trapdoors -> if (t.ny == -1) meta = meta or S.UPPER
            Blocks.FRIDGE, Blocks.WARDROBE -> if (y + 1 >= Chunk.HEIGHT || !placeTallTop(id, x, y, z, meta)) return
            Blocks.LADDER, Blocks.PAINTING, Blocks.ITEM_FRAME, Blocks.AIR_CONDITIONER, Blocks.SHOWER, Blocks.MIRROR,
            Blocks.WALL_CLOCK, Blocks.CURTAIN -> {
                // Curtains also hang in front of windows.
                if (t.ny != 0 || !(Blocks.opaque[t.block] || (id == Blocks.CURTAIN && Blocks.solid[t.block]))) return
                meta = if (t.nz == 1) 2 else if (t.nz == -1) 3 else if (t.nx == 1) 4 else 5
                if (id == Blocks.PAINTING) meta = meta or (java.util.Random().nextInt(4) shl 3)
            }
            Blocks.REPEATER, Blocks.OBSERVER -> meta = meta xor 1
            Blocks.LEVER, in Blocks.WOOD_BUTTON_FIRST until Blocks.WOOD_BUTTON_FIRST + 6, Blocks.STONE_BUTTON -> {
                // Stick it to the face that was tapped: floor, ceiling or a wall (a replaced plant: the floor).
                val support = if (replace) 1 else when {
                    t.ny == 1 -> 1; t.ny == -1 -> 0; t.nz == 1 -> 3; t.nz == -1 -> 2; t.nx == 1 -> 5; else -> 4
                }
                val n = ChunkMesher.NORMALS[support]
                if (!Blocks.opaque[world.getBlock(x + n[0], y + n[1], z + n[2])]) return
                meta = S.attachMeta(support)
            }
        }
        setBlock(x, y, z, id, meta)
        if (id == Blocks.SIGN) uiEvents.add("signedit:$x,$y,$z")
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
        saveEntities()
        saveDir?.let { level.write(it) }
    }

    /** Animals, pets and minecarts are kept in entities.dat next to the chunks of each dimension. */
    private fun saveEntities() {
        val dir = world.dataDir ?: return
        try {
            dir.mkdirs()
            val tmp = File(dir, "entities.dat.tmp")
            java.io.DataOutputStream(tmp.outputStream().buffered()).use { d -> mobs.write(d); carts.write(d); boats.write(d); carts.writeExtra(d); aircraft.write(d) }
            tmp.renameTo(File(dir, "entities.dat"))
        } catch (_: Exception) {}
    }

    private fun loadEntities() {
        val f = File(world.dataDir ?: return, "entities.dat")
        if (!f.exists()) return
        try { java.io.DataInputStream(f.inputStream().buffered()).use { d -> mobs.read(d); carts.read(d); boats.read(d); carts.readExtra(d); aircraft.read(d) } } catch (_: Exception) {}
    }
}
