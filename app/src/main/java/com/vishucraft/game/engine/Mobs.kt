package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.World
import com.vishucraft.game.world.floorInt
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class MobType(
    val displayName: String,
    val halfWidth: Float,
    val height: Float,
    val maxHealth: Int,
    val hostile: Boolean,
    val speed: Float,
) {
    COW("Cow", 0.45f, 1.4f, 10, false, 1.3f),
    PIG("Pig", 0.45f, 0.9f, 10, false, 1.3f),
    SHEEP("Sheep", 0.45f, 1.3f, 8, false, 1.3f),
    ZOMBIE("Zombie", 0.3f, 1.95f, 20, true, 2.4f),
    /** Original exploding mob: a squat stone creature with glowing cracks. */
    BOOMLING("Boomling", 0.4f, 1.3f, 20, true, 2.2f),
    /** Hooded thornwood archer that keeps its distance and shoots thorns. */
    RATTLER("Rattler", 0.3f, 1.9f, 20, true, 2.2f),
    /** Six-legged rock beetle that climbs walls; only hunts at night unless provoked. */
    CRAWLER("Crawler", 0.6f, 0.8f, 16, true, 3.0f),
    /** Moth-winged flyer that circles high up and swoops at night. */
    GLIDER("Night Glider", 0.45f, 0.4f, 12, true, 5.0f),
    /** Magma golem of the Ember Realm. */
    CINDER("Cinder Brute", 0.4f, 2.0f, 30, true, 2.0f),
    /** Floating ringed orb of the Sky Isles; blinks away when hit. */
    WISP("Void Wisp", 0.35f, 0.8f, 15, true, 3.0f),
    /** Peaceful village folk. */
    VILLAGER("Villager", 0.3f, 1.9f, 20, false, 1.0f),
    /** Other players in a Wi-Fi game (never spawned naturally). */
    EXPLORER("Explorer", 0.3f, 1.8f, 20, false, 0f),
    // ---- Added in the creature pack (appended so saves and Wi-Fi stay compatible)
    CHICKEN("Chicken", 0.25f, 0.8f, 4, false, 1.2f),
    RABBIT("Rabbit", 0.2f, 0.5f, 3, false, 2.6f),
    HORSE("Horse", 0.6f, 1.6f, 22, false, 2.0f),
    WOLF("Wolf", 0.3f, 0.85f, 8, false, 2.8f),
    CAT("Cat", 0.25f, 0.7f, 10, false, 2.4f),
    SQUID("Squid", 0.4f, 0.8f, 10, false, 1.5f),
    COD("Cod", 0.2f, 0.3f, 3, false, 2.2f),
    SKELETON("Skeleton", 0.3f, 1.95f, 20, true, 2.4f),
    SLIME("Slime", 0.5f, 1.0f, 16, true, 2.4f),
    WITCH("Witch", 0.3f, 1.95f, 26, true, 2.0f),
    /** Tall and quiet: leaves you alone until you hit it, then teleports around and hits hard. */
    ENDERMAN("Enderman", 0.3f, 2.9f, 40, true, 3.4f),
    DROWNED("Drowned", 0.3f, 1.95f, 20, true, 2.2f),
    /** The boss of the Sky Isles: a huge winged guardian. */
    WARDEN("Sky Warden", 1.2f, 1.6f, 200, true, 7f),
    // ---- Creature pack 3 (appended)
    /** Guards villages and players: attacks monsters. */
    IRON_GOLEM("Iron Golem", 0.6f, 2.7f, 100, false, 1.6f),
    /** Throws snowballs at monsters; melts in the desert and the rain. */
    SNOW_GOLEM("Snow Golem", 0.35f, 1.9f, 4, false, 1.4f),
    BAT("Bat", 0.25f, 0.45f, 6, false, 3.5f),
    FOX("Fox", 0.3f, 0.7f, 10, false, 3.0f),
    TURTLE("Turtle", 0.55f, 0.4f, 30, false, 0.9f),
    BEE("Bee", 0.2f, 0.5f, 10, false, 2.5f),
    GOAT("Goat", 0.35f, 1.3f, 10, false, 2.0f),
    FROG("Frog", 0.25f, 0.5f, 10, false, 1.8f),
    AXOLOTL("Axolotl", 0.3f, 0.4f, 14, false, 2.2f),
    PARROT("Parrot", 0.2f, 0.9f, 6, false, 3.0f),
    PANDA("Panda", 0.6f, 1.25f, 20, false, 1.1f),
    POLAR_BEAR("Polar Bear", 0.65f, 1.4f, 30, false, 2.3f),
    LLAMA("Llama", 0.45f, 1.87f, 22, false, 1.8f),
    MOOSHROOM("Mooshroom", 0.45f, 1.4f, 10, false, 1.3f);

    val swims get() = this == SQUID || this == COD || this == AXOLOTL
    /** Fly without gravity. */
    val flies get() = this == BAT || this == BEE || this == PARROT
    /** Peaceful until hit, then fight back. */
    val neutral get() = this == POLAR_BEAR || this == BEE || this == LLAMA || this == GOAT || this == IRON_GOLEM || this == PANDA

    companion object {
        /** Spawn eggs exist for the first this-many types (item ids are fixed, so later types add eggs separately). */
        const val WITH_EGGS = 39
    }
}

class Mob(val type: MobType, var x: Float, var y: Float, var z: Float, val uid: Int = nextUid++) {
    companion object { private var nextUid = 1 }
    var vx = 0f; var vy = 0f; var vz = 0f
    var yaw = 0f
    var onGround = false
    var health = type.maxHealth.toFloat()
    /** Seconds of red flash after being hit. */
    var hurtTime = 0f
    /** Counts up after death; the mob is removed once it tips over. */
    var deathTime = -1f
    /** Boomling fuse in seconds (-1 when not lit). */
    var fuse = -1f
    var walkPhase = 0f
    var moving = false
    var burning = false
    /** Negative while a baby (seconds until grown up). */
    var age = 0f
    var loveTime = 0f
    internal var breedCooldown = 0f
    val scale get() = if (age < 0f) 0.55f else 1f
    val halfWidth get() = type.halfWidth * scale
    val height get() = type.height * scale
    internal var aiTimer = 0f
    internal var wanderYaw = 0f
    internal var wandering = false
    internal var attackCooldown = 0f
    internal var burnTimer = 0f
    internal var fleeTime = 0f
    internal var shootTimer = 0f
    /** Crawlers become aggressive when hit. */
    internal var angry = false
    internal var swoopTime = 0f
    /** Given with a name tag; named mobs never despawn. */
    var customName: String? = null
    /** Tamed by the player (wolves, cats, horses). */
    var tamed = false
    var saddled = false
    /** Tamed pets sit and stay when told to. */
    var sitting = false
    /** Chickens: seconds until the next egg. Sheep: seconds until the wool grows back. */
    var timer = 0f
    /** On a lead: follows the player who holds it. */
    var leashed = false

    val dead get() = deathTime >= 0f
}

/** Result of a ray test against mobs. */
class MobHit(val mob: Mob, val distance: Float)

/** Spawning, AI and physics for all mobs. Runs on the game thread. */
class Mobs(private val world: World) {
    val list = ArrayList<Mob>()
    /** Animals and pets that are far away or in unloaded chunks: kept (and saved) but not simulated. */
    val parked = ArrayList<Mob>()
    var hostileEnabled = true
    private var unparkTimer = 0f

    /** Animals, named mobs and pets stay in the world; monsters despawn when far away. */
    fun persistent(m: Mob) = !m.type.hostile || m.customName != null || m.tamed

    fun write(d: java.io.DataOutputStream) {
        val all = (list + parked).filter { !it.dead && persistent(it) && it.type != MobType.EXPLORER }
        d.writeInt(1)
        d.writeInt(all.size)
        for (m in all) {
            d.writeUTF(m.type.name)
            d.writeFloat(m.x); d.writeFloat(m.y); d.writeFloat(m.z); d.writeFloat(m.yaw)
            d.writeFloat(m.health); d.writeFloat(m.age); d.writeFloat(m.timer)
            d.writeUTF(m.customName ?: "")
            d.writeByte((if (m.tamed) 1 else 0) or (if (m.saddled) 2 else 0) or (if (m.sitting) 4 else 0))
        }
    }

    fun read(d: java.io.DataInputStream) {
        if (d.readInt() != 1) return
        repeat(d.readInt()) {
            val name = d.readUTF()
            val x = d.readFloat(); val y = d.readFloat(); val z = d.readFloat(); val yaw = d.readFloat()
            val health = d.readFloat(); val age = d.readFloat(); val timer = d.readFloat()
            val custom = d.readUTF(); val flags = d.readByte().toInt()
            val type = MobType.values().firstOrNull { it.name == name } ?: return@repeat
            parked.add(Mob(type, x, y, z).also { m ->
                m.yaw = yaw; m.health = health; m.age = age; m.timer = timer
                m.customName = custom.ifEmpty { null }
                m.tamed = flags and 1 != 0; m.saddled = flags and 2 != 0; m.sitting = flags and 4 != 0
            })
        }
    }
    /** Called once when a mob dies (for drops). */
    var onDeath: ((Mob) -> Unit)? = null
    private val rnd = Random()
    private var spawnTimer = 0f

    fun update(dt: Float, game: Game) {
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawnTimer = 1f
            trySpawn(game)
        }
        breed(dt)
        val p = game.player
        // Bring kept animals back once their chunk is loaded and the player is near.
        unparkTimer -= dt
        if (unparkTimer <= 0f && parked.isNotEmpty()) {
            unparkTimer = 1f
            val back = parked.filter { m ->
                val dx = m.x - p.x; val dz = m.z - p.z
                dx * dx + dz * dz < 96f * 96f && world.isLoaded(floorInt(m.x), floorInt(m.z)) &&
                    world.isLoaded(floorInt(m.x) + 16, floorInt(m.z)) && world.isLoaded(floorInt(m.x) - 16, floorInt(m.z))
            }
            parked.removeAll(back.toSet()); list.addAll(back)
        }
        val it = list.iterator()
        while (it.hasNext()) {
            val m = it.next()
            val dx = m.x - p.x; val dz = m.z - p.z
            val far = dx * dx + dz * dz > 110f * 110f
            if (m.dead && m.deathTime > 0.8f) { it.remove(); continue }
            if (far || !world.isLoaded(floorInt(m.x), floorInt(m.z)) || (!hostileEnabled && m.type.hostile && !m.tamed)) {
                if (persistent(m) && !m.dead && (far || !world.isLoaded(floorInt(m.x), floorInt(m.z)))) parked.add(m)
                it.remove(); continue
            }
            tick(m, dt, game)
        }
    }

    /** Breeding foods: wheat for cows and sheep, carrots or potatoes for pigs. */
    fun wantsFood(m: Mob, slot: Int): Boolean {
        val name = com.vishucraft.game.world.Items[slot]?.name ?: return false
        return when (m.type) {
            MobType.COW, MobType.SHEEP -> name == "Wheat"
            MobType.PIG -> name == "Carrot" || name == "Potato"
            MobType.CHICKEN -> name == "Wheat Seeds"
            MobType.RABBIT -> name == "Carrot" || name == "Golden Carrot"
            MobType.HORSE -> name == "Golden Carrot" || name == "Golden Apple"
            MobType.WOLF -> m.tamed && (name == "Steak" || name == "Raw Beef" || name == "Cooked Porkchop" || name == "Cooked Chicken")
            MobType.CAT -> m.tamed && (name == "Raw Cod" || name == "Raw Salmon")
            MobType.GOAT, MobType.MOOSHROOM -> name == "Wheat"
            MobType.LLAMA -> slot == Blocks.HAY_BALE
            MobType.PANDA -> slot == Blocks.BAMBOO
            MobType.TURTLE -> slot == Blocks.SEAGRASS
            MobType.BEE -> slot in Blocks.FLOWER_FIRST until Blocks.FLOWER_FIRST + 7 || slot == Blocks.FLOWER_RED || slot == Blocks.FLOWER_YELLOW
            MobType.FOX -> m.tamed && name == "Sweet Berries"
            MobType.FROG -> name == "Slimeball"
            MobType.AXOLOTL -> name == "Raw Cod"
            else -> false
        }
    }

    /** Feeds an animal so it looks for a partner. Returns true if the food was used. */
    fun feed(m: Mob, slot: Int): Boolean {
        if (m.dead || !wantsFood(m, slot) || m.age < 0f || m.breedCooldown > 0f || m.loveTime > 0f) return false
        m.loveTime = 30f
        return true
    }

    private fun breed(dt: Float) {
        for (m in list) {
            if (m.age < 0f) m.age += dt
            if (m.breedCooldown > 0f) m.breedCooldown -= dt
            if (m.loveTime <= 0f) continue
            m.loveTime -= dt
            val mate = list.firstOrNull { o ->
                o !== m && o.type == m.type && o.loveTime > 0f && !o.dead &&
                    (o.x - m.x) * (o.x - m.x) + (o.z - m.z) * (o.z - m.z) < 64f
            } ?: continue
            // Walk towards each other; when close enough, a baby appears.
            val dx = mate.x - m.x; val dz = mate.z - m.z
            val d = sqrt(dx * dx + dz * dz)
            if (d > 1.2f) { m.yaw = atan2(dx, -dz); m.vx += dx / d * 4f * dt; m.vz += dz / d * 4f * dt; continue }
            m.loveTime = 0f; mate.loveTime = 0f
            m.breedCooldown = 120f; mate.breedCooldown = 120f
            list.add(Mob(m.type, (m.x + mate.x) / 2, m.y + 0.2f, (m.z + mate.z) / 2).also { it.age = -600f })
            return
        }
    }

    private fun tick(m: Mob, dt: Float, game: Game) {
        if (world.getBlock(floorInt(m.x), floorInt(m.y + 0.3f), floorInt(m.z)) == Blocks.LAVA && !m.dead &&
            rnd.nextFloat() < dt * 2f) damage(m, 2f, 0f, 0f)
        if (m.hurtTime > 0f) m.hurtTime -= dt
        if (!m.dead && rnd.nextFloat() < dt / 10f) game.sound(voice(m.type), m.x, m.y + 1f, m.z, 0.8f)
        if (m.dead) {
            m.deathTime += dt
            m.vx *= 0.8f; m.vz *= 0.8f
            physics(m, dt)
            return
        }
        if (m.type.swims) { swim(m, dt, game); return }
        if (game.mount === m) { ridden(m, dt, game); return }
        val p = game.nearestTarget(m.x, m.z)
        var dx = p.x - m.x; var dz = p.z - m.z
        var dist = sqrt(dx * dx + dz * dz)
        var wantX = 0f; var wantZ = 0f
        var speed = m.type.speed

        // Pets and animals on a lead follow the player.
        if (m.type.flies) { flutter(m, dt, game); return }
        val follows = (m.leashed || (m.tamed && !m.sitting && (m.type == MobType.WOLF || m.type == MobType.CAT || m.type == MobType.FOX)))
        if (follows) {
            val pl = game.player
            val fx = pl.x - m.x; val fz = pl.z - m.z
            val fd = sqrt(fx * fx + fz * fz)
            if (fd > 20f && !m.leashed) { m.x = pl.x; m.y = pl.y; m.z = pl.z; m.vx = 0f; m.vz = 0f }
            else if (m.leashed && fd > 10f) m.leashed = false // the lead snaps
            // Tamed wolves go for the monster nearest to their owner.
            val prey = if (m.type == MobType.WOLF && m.tamed) list.filter { it.type.hostile && !it.dead && !it.tamed &&
                (it.x - pl.x) * (it.x - pl.x) + (it.z - pl.z) * (it.z - pl.z) < 100f }.minByOrNull { (it.x - m.x) * (it.x - m.x) + (it.z - m.z) * (it.z - m.z) } else null
            if (prey != null) {
                val px = prey.x - m.x; val pz = prey.z - m.z; val pd = sqrt(px * px + pz * pz).coerceAtLeast(0.01f)
                m.yaw = atan2(px, -pz); wantX = px / pd; wantZ = pz / pd; speed *= 1.3f
                m.attackCooldown -= dt
                if (pd < 1.3f && m.attackCooldown <= 0f) { m.attackCooldown = 0.8f; damage(prey, 4f, px / pd, pz / pd); game.sound("hurt", prey.x, prey.y + 1f, prey.z, 0.6f) }
            } else if (fd > 3f) { m.yaw = atan2(fx, -fz); wantX = fx / fd; wantZ = fz / fd; speed *= if (fd > 6f) 1.5f else 1f }
            finishMove(m, dt, wantX, wantZ, speed)
            return
        }
        if (m.tamed && m.sitting) { finishMove(m, dt, 0f, 0f, 0f); return }

        when (m.type) {
            MobType.ENDERMAN -> {
                if (m.angry && dist < 40f && game.playerAlive) {
                    m.yaw = atan2(dx, -dz)
                    if (dist > 1f) { wantX = dx / dist; wantZ = dz / dist }
                    m.attackCooldown -= dt
                    if (dist < 1.6f && abs(p.y - m.y) < 2.5f && m.attackCooldown <= 0f) {
                        m.attackCooldown = 1.1f
                        game.hurtTarget(p, 6f, m.x, m.z)
                    }
                    // Now and then it blinks right next to you.
                    if (dist > 8f && rnd.nextFloat() < dt / 3f) teleportNear(m, p.x, p.z)
                } else wander(m, dt).let { wantX = it.first; wantZ = it.second; speed *= 0.4f }
                // Water hurts endermen.
                if (world.getBlock(floorInt(m.x), floorInt(m.y + 1f), floorInt(m.z)) == Blocks.WATER || (game.rain > 0.5f && skyExposed(floorInt(m.x), floorInt(m.y + 3f), floorInt(m.z)))) {
                    m.burnTimer += dt; if (m.burnTimer > 1f) { m.burnTimer = 0f; damage(m, 1f, 0f, 0f); teleportNear(m, m.x, m.z) }
                }
            }
            MobType.SLIME -> {
                m.attackCooldown -= dt
                if (dist < 16f && game.playerAlive) {
                    m.yaw = atan2(dx, -dz)
                    // Slimes move in hops.
                    if (m.onGround && m.attackCooldown <= 0f) { m.attackCooldown = 1f + rnd.nextFloat(); m.vy = 7f; m.vx = dx / dist * 4f; m.vz = dz / dist * 4f }
                    if (dist < 1f + m.halfWidth && abs(p.y - m.y) < 1.2f && m.timer <= 0f) { m.timer = 1f; game.hurtTarget(p, if (m.scale < 1f) 1f else 3f, m.x, m.z) }
                } else if (m.onGround && m.attackCooldown <= 0f) { m.attackCooldown = 2f + rnd.nextFloat() * 2f; m.vy = 6f; m.yaw = rnd.nextFloat() * 6.28f; m.vx = sin(m.yaw) * 2f; m.vz = -cos(m.yaw) * 2f }
                if (m.timer > 0f) m.timer -= dt
                m.moving = !m.onGround
                physics(m, dt)
                return
            }
            MobType.WITCH -> {
                if (dist < 18f && game.playerAlive) {
                    m.yaw = atan2(dx, -dz)
                    if (dist < 5f) { wantX = -dx / dist; wantZ = -dz / dist } else if (dist > 10f) { wantX = dx / dist; wantZ = dz / dist }
                    m.shootTimer -= dt
                    if (m.shootTimer <= 0f && dist < 14f) {
                        // Throws a harmful splash potion.
                        m.shootTimer = 3f + rnd.nextFloat()
                        val sx = m.x; val sy = m.y + 1.6f; val sz = m.z
                        val tx = p.x - sx; val ty = p.eyeY - 0.5f - sy; val tz = p.z - sz
                        val len = sqrt(tx * tx + ty * ty + tz * tz)
                        game.projectiles.shoot(sx, sy, sz, tx / len * 14f, ty / len * 14f + len * 0.45f, tz / len * 14f, false, 4f, Projectile.POTION)
                    }
                    // Drinks a healing potion when hurt.
                    m.timer -= dt
                    if (m.health < 10f && m.timer <= 0f) { m.timer = 12f; m.health = minOf(m.type.maxHealth.toFloat(), m.health + 8f); game.sound("eat", m.x, m.y + 1.5f, m.z, 0.6f) }
                } else wander(m, dt).let { wantX = it.first; wantZ = it.second; speed *= 0.5f }
            }
            MobType.CHICKEN -> {
                // Lays an egg every few minutes.
                if (m.timer == 0f) m.timer = 300f + rnd.nextFloat() * 300f
                m.timer -= dt
                if (m.timer <= 0f) {
                    if (m.age >= 0f) game.drops.spawn(com.vishucraft.game.world.ItemStack(com.vishucraft.game.world.Items.find("Egg")), m.x, m.y + 0.3f, m.z)
                    m.timer = 300f + rnd.nextFloat() * 300f
                }
                if (m.fleeTime > 0f) { m.fleeTime -= dt; speed *= 1.8f; if (dist > 0.01f) { wantX = -dx / dist; wantZ = -dz / dist; m.yaw = atan2(wantX, -wantZ) } }
                else wander(m, dt).let { wantX = it.first; wantZ = it.second }
                if (m.vy < -2.5f) m.vy = -2.5f // flutters down
            }
            MobType.ZOMBIE, MobType.DROWNED -> {
                if (dist < 28f && game.playerAlive) {
                    m.yaw = atan2(dx, -dz)
                    if (dist > 0.9f) { wantX = dx / dist; wantZ = dz / dist }
                    m.attackCooldown -= dt
                    if (dist < 1.4f && abs(p.y - m.y) < 1.6f && m.attackCooldown <= 0f) {
                        m.attackCooldown = 1f
                        game.hurtTarget(p, 3f, m.x, m.z)
                        game.sound("zombie", m.x, m.y + 1.5f, m.z, 0.6f)
                    }
                } else wander(m, dt).let { wantX = it.first; wantZ = it.second; speed *= 0.5f }
                // Zombies burn in direct sunlight.
                val exposed = game.daylight > 0.6f && skyExposed(floorInt(m.x), floorInt(m.y + 1.7f), floorInt(m.z))
                m.burning = exposed && world.getBlock(floorInt(m.x), floorInt(m.y + 0.2f), floorInt(m.z)) != Blocks.WATER
                if (m.burning) {
                    m.burnTimer += dt
                    if (m.burnTimer >= 1f) { m.burnTimer = 0f; damage(m, 1f, 0f, 0f) }
                }
            }
            MobType.BOOMLING -> {
                if (dist < 18f && game.playerAlive) {
                    m.yaw = atan2(dx, -dz)
                    if (m.fuse >= 0f) {
                        // Stand still and hiss; give up if the player runs away.
                        if (dist > 6f) m.fuse = -1f
                        else {
                            m.fuse += dt
                            if (m.fuse >= 1.5f) {
                                m.health = 0f; m.deathTime = 1f
                                game.explode(m.x, m.y + 0.6f, m.z, 3.2f)
                                return
                            }
                        }
                    } else {
                        if (dist < 2.6f && abs(p.y - m.y) < 2f) { m.fuse = 0f; game.sound("fuse", m.x, m.y + 1f, m.z, 1f) }
                        else { wantX = dx / dist; wantZ = dz / dist }
                    }
                } else { m.fuse = -1f; wander(m, dt).let { wantX = it.first; wantZ = it.second; speed *= 0.5f } }
            }
            MobType.RATTLER, MobType.SKELETON -> {
                val burning = game.daylight > 0.6f && skyExposed(floorInt(m.x), floorInt(m.y + 1.7f), floorInt(m.z)) &&
                    world.getBlock(floorInt(m.x), floorInt(m.y + 0.2f), floorInt(m.z)) != Blocks.WATER
                m.burning = burning
                if (burning) { m.burnTimer += dt; if (m.burnTimer >= 1f) { m.burnTimer = 0f; damage(m, 1f, 0f, 0f) } }
                if (dist < 18f && game.playerAlive) {
                    m.yaw = atan2(dx, -dz)
                    // Keep 6..11 blocks away, then shoot.
                    if (dist < 6f) { wantX = -dx / dist; wantZ = -dz / dist }
                    else if (dist > 11f) { wantX = dx / dist; wantZ = dz / dist }
                    m.shootTimer -= dt
                    if (m.shootTimer <= 0f && dist < 16f) {
                        m.shootTimer = 2f + rnd.nextFloat()
                        val sx = m.x; val sy = m.y + 1.5f; val sz = m.z
                        val tx = p.x - sx; val ty = p.eyeY - 0.3f - sy; val tz = p.z - sz
                        val len = sqrt(tx * tx + ty * ty + tz * tz)
                        val speed = 20f
                        game.projectiles.shoot(sx, sy, sz, tx / len * speed, ty / len * speed + len * 0.28f, tz / len * speed, false, 3f,
                            if (m.type == MobType.SKELETON) Projectile.ARROW else Projectile.THORN)
                        game.sound("bow", sx, sy, sz, 0.7f)
                    }
                } else wander(m, dt).let { wantX = it.first; wantZ = it.second; speed *= 0.5f }
            }
            MobType.CRAWLER, MobType.CINDER -> {
                val hunts = m.type == MobType.CINDER || m.angry || game.daylight < 0.4f
                if (hunts && dist < 20f && game.playerAlive) {
                    m.yaw = atan2(dx, -dz)
                    if (dist > 0.9f) { wantX = dx / dist; wantZ = dz / dist }
                    m.attackCooldown -= dt
                    if (dist < 1.3f + m.halfWidth && abs(p.y - m.y) < 1.6f && m.attackCooldown <= 0f) {
                        m.attackCooldown = 1f
                        game.hurtTarget(p, if (m.type == MobType.CINDER) 5f else 2.5f, m.x, m.z)
                    }
                } else wander(m, dt).let { wantX = it.first; wantZ = it.second; speed *= 0.5f }
            }
            MobType.GLIDER, MobType.WISP -> {
                flyer(m, dt, game, dist, dx, dz)
                return
            }
            MobType.WARDEN -> { boss(m, dt, game); return }
            MobType.IRON_GOLEM, MobType.SNOW_GOLEM -> {
                golem(m, dt, game).let { wantX = it.first; wantZ = it.second }
                if (m.type == MobType.IRON_GOLEM) speed *= if (wantX != 0f || wantZ != 0f) 1.4f else 0.5f
            }
            else -> if (m.type.neutral && m.angry && dist < 24f && game.playerAlive && !(m.tamed && m.type == MobType.IRON_GOLEM)) {
                // Neutral animals fight back once hit.
                m.yaw = atan2(dx, -dz)
                if (dist > 1f) { wantX = dx / dist; wantZ = dz / dist }
                speed *= 1.4f
                m.attackCooldown -= dt
                if (dist < 1.4f + m.halfWidth && abs(p.y - m.y) < 1.8f && m.attackCooldown <= 0f) {
                    m.attackCooldown = 1.2f
                    game.hurtTarget(p, when (m.type) { MobType.POLAR_BEAR -> 6f; MobType.GOAT -> 3f; MobType.PANDA -> 4f; else -> 2f }, m.x, m.z)
                }
                if (dist > 30f) m.angry = false
            } else if (m.type == MobType.FOX && game.daylight < 0.4f && hunt(m, dt, game, setOf(MobType.CHICKEN, MobType.RABBIT))) {
                return
            } else if (m.type == MobType.FROG && hunt(m, dt, game, setOf(MobType.SLIME), smallOnly = true)) {
                return
            } else {
                if (m.fleeTime > 0f) {
                    m.fleeTime -= dt
                    speed *= 1.8f
                    if (dist > 0.01f) { wantX = -dx / dist; wantZ = -dz / dist; m.yaw = atan2(wantX, -wantZ) }
                } else wander(m, dt).let { wantX = it.first; wantZ = it.second }
            }
        }

        finishMove(m, dt, wantX, wantZ, speed)
    }

    private fun finishMove(m: Mob, dt: Float, wantX: Float, wantZ: Float, speed: Float) {
        val accel = if (m.onGround) 20f else 5f
        m.vx = approach(m.vx, wantX * speed, accel * dt)
        m.vz = approach(m.vz, wantZ * speed, accel * dt)
        m.moving = abs(wantX) + abs(wantZ) > 0.01f
        val hit = physics(m, dt)
        // Hop up single blocks; crawlers simply climb; rabbits hop everywhere.
        if (hit && m.type == MobType.CRAWLER && m.moving) m.vy = 3.5f
        else if (hit && m.onGround && m.moving) m.vy = 8.4f
        else if ((m.type == MobType.RABBIT || m.type == MobType.FROG) && m.onGround && m.moving) m.vy = if (m.type == MobType.FROG) 6f else 5f
        else if (m.type == MobType.GOAT && m.onGround && m.moving && rnd.nextFloat() < dt * 0.3f) m.vy = 10f
        if (m.moving && m.onGround) m.walkPhase += sqrt(m.vx * m.vx + m.vz * m.vz) * dt * 3.2f
    }

    /** A horse with a saddle, steered by the player on its back. */
    private fun ridden(m: Mob, dt: Float, game: Game) {
        val pl = game.player
        m.yaw = pl.yaw
        val f = game.input.moveForward; val s = game.input.moveStrafe
        val wx = sin(pl.yaw) * f + cos(pl.yaw) * s; val wz = -cos(pl.yaw) * f + sin(pl.yaw) * s
        val len = sqrt(wx * wx + wz * wz).coerceAtLeast(1f)
        if (game.input.jumpHeld && m.onGround) m.vy = 10f
        finishMove(m, dt, wx / len, wz / len, 8.5f)
    }

    /** Squid and fish: drift around under water; out of water they flop and slowly suffocate. */
    private fun swim(m: Mob, dt: Float, game: Game) {
        val inWater = world.getBlock(floorInt(m.x), floorInt(m.y + 0.2f), floorInt(m.z)) == Blocks.WATER
        m.moving = true
        m.walkPhase += dt * 6f
        if (!inWater) {
            m.vy -= 25f * dt
            m.timer += dt
            if (m.timer > 2f) { m.timer = 0f; damage(m, 1f, 0f, 0f) }
            physics(m, dt)
            return
        }
        m.timer = 0f
        m.aiTimer -= dt
        if (m.aiTimer <= 0f) {
            m.aiTimer = 2f + rnd.nextFloat() * 3f
            m.wanderYaw = rnd.nextFloat() * 6.28f
            m.vy = (rnd.nextFloat() - 0.5f) * 2f
        }
        if (m.fleeTime > 0f) m.fleeTime -= dt
        val sp = m.type.speed * (if (m.fleeTime > 0f) 2f else 1f)
        m.yaw = turn(m.yaw, m.wanderYaw, dt * 2f)
        m.vx = approach(m.vx, sin(m.yaw) * sp, 6f * dt); m.vz = approach(m.vz, -cos(m.yaw) * sp, 6f * dt)
        // Stay in the water.
        if (world.getBlock(floorInt(m.x), floorInt(m.y + m.height + 0.2f), floorInt(m.z)) != Blocks.WATER && m.vy > 0f) m.vy = -0.5f
        if (!move(m, 0, m.vx * dt)) m.wanderYaw += 3.14f
        if (!move(m, 2, m.vz * dt)) m.wanderYaw += 3.14f
        move(m, 1, m.vy * dt)
    }

    /** Endermen: vanish and reappear a few blocks away. */
    private fun teleportNear(m: Mob, cx: Float, cz: Float) {
        repeat(10) {
            val x = floorInt(cx) + rnd.nextInt(13) - 6; val z = floorInt(cz) + rnd.nextInt(13) - 6
            val y = surfaceY(x, z)
            if (y > 0 && roomAt(x, y + 1, z, 3) && world.getBlock(x, y, z) != Blocks.WATER) { m.x = x + 0.5f; m.y = y + 1f; m.z = z + 0.5f; return }
        }
    }

    /** The Sky Warden: circles high above, throws fireballs and dives at the player. */
    private fun boss(m: Mob, dt: Float, game: Game) {
        val p = game.nearestTarget(m.x, m.z)
        m.moving = true
        m.walkPhase += dt * 3f
        val dx = p.x - m.x; val dz = p.z - m.z
        val dist = sqrt(dx * dx + dz * dz)
        val angry = m.health < m.type.maxHealth / 2f // second phase: faster and meaner
        var tx: Float; var ty: Float; var tz: Float
        if (m.swoopTime > 0f) {
            m.swoopTime -= dt
            tx = p.x; ty = p.y + 1f; tz = p.z
        } else {
            val a = m.walkPhase * 0.15f
            tx = p.x + cos(a) * 14f; ty = p.y + 11f; tz = p.z + sin(a) * 14f
            if (rnd.nextFloat() < dt / (if (angry) 5f else 8f)) m.swoopTime = 2.5f
        }
        // Fireballs.
        m.shootTimer -= dt
        if (m.shootTimer <= 0f && dist < 40f && game.playerAlive) {
            m.shootTimer = if (angry) 1.6f else 3f
            val sx = m.x; val sy = m.y + 0.8f; val sz = m.z
            val vx = p.x - sx; val vy = p.eyeY - sy; val vz = p.z - sz
            val len = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(0.1f)
            game.projectiles.shoot(sx, sy, sz, vx / len * 18f, vy / len * 18f + len * 0.3f, vz / len * 18f, false, if (angry) 6f else 4f, Projectile.FIREBALL)
            game.sound("bow", sx, sy, sz, 1f)
        }
        val ex = tx - m.x; val ey = ty - m.y; val ez = tz - m.z
        val len = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(0.1f)
        val sp = m.type.speed * (if (angry) 1.35f else 1f)
        m.vx = approach(m.vx, ex / len * sp, 8f * dt); m.vy = approach(m.vy, ey / len * sp, 8f * dt); m.vz = approach(m.vz, ez / len * sp, 8f * dt)
        m.yaw = atan2(m.vx, -m.vz)
        m.x += m.vx * dt; m.y += m.vy * dt; m.z += m.vz * dt
        m.attackCooldown -= dt
        val py = p.y + 1f - m.y
        if (sqrt(dx * dx + dz * dz + py * py) < 2.4f && m.attackCooldown <= 0f && game.playerAlive) {
            m.attackCooldown = 1.5f
            game.hurtTarget(p, if (angry) 9f else 6f, m.x, m.z)
            m.swoopTime = 0f
        }
    }

    /** Flying mobs: hover around, then dive at the player. No gravity. */
    private fun flyer(m: Mob, dt: Float, game: Game, dist: Float, dx: Float, dz: Float) {
        val p = game.nearestTarget(m.x, m.z)
        m.moving = true
        m.walkPhase += dt * 8f
        var tx: Float; var ty: Float; var tz: Float
        if (m.swoopTime > 0f) {
            m.swoopTime -= dt
            tx = p.x; ty = p.y + 1f; tz = p.z
        } else {
            // Circle above the player.
            val a = m.walkPhase * 0.1f
            tx = p.x + cos(a) * 7f; ty = p.y + (if (m.type == MobType.GLIDER) 9f else 3f); tz = p.z + sin(a) * 7f
            if (dist < 24f && rnd.nextFloat() < dt / 5f) m.swoopTime = 2.5f
        }
        val ex = tx - m.x; val ey = ty - m.y; val ez = tz - m.z
        val len = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(0.1f)
        val sp = m.type.speed
        m.vx = approach(m.vx, ex / len * sp, 10f * dt)
        m.vy = approach(m.vy, ey / len * sp, 10f * dt)
        m.vz = approach(m.vz, ez / len * sp, 10f * dt)
        m.yaw = atan2(m.vx, -m.vz)
        move(m, 0, m.vx * dt); move(m, 1, m.vy * dt); move(m, 2, m.vz * dt)
        m.attackCooldown -= dt
        val py = p.y + 1f - m.y
        if (sqrt(dx * dx + dz * dz + py * py) < 1.4f && m.attackCooldown <= 0f && game.playerAlive) {
            m.attackCooldown = 1.2f
            game.hurtTarget(p, if (m.type == MobType.WISP) 3f else 2f, m.x, m.z)
            m.swoopTime = 0f
        }
        if (m.type == MobType.GLIDER && game.daylight > 0.7f) m.deathTime = 0.9f // fades away at sunrise
    }

    /** Golems: the iron golem punches monsters, the snow golem throws snowballs at them. */
    private fun golem(m: Mob, dt: Float, game: Game): Pair<Float, Float> {
        val iron = m.type == MobType.IRON_GOLEM
        val range = if (iron) 16f else 12f
        val prey = list.filter { it.type.hostile && !it.dead && !it.tamed && it.type != MobType.WARDEN }
            .minByOrNull { (it.x - m.x) * (it.x - m.x) + (it.z - m.z) * (it.z - m.z) }
            ?.takeIf { (it.x - m.x) * (it.x - m.x) + (it.z - m.z) * (it.z - m.z) < range * range }
        m.attackCooldown -= dt
        if (!iron) {
            // Snow golems melt in the rain and in hot places, and leave a trail of snow.
            val hot = world.generator.biomeAt(floorInt(m.x), floorInt(m.z)).let { it == com.vishucraft.game.world.Biome.DESERT || it == com.vishucraft.game.world.Biome.BADLANDS }
            if (hot || (game.rain > 0.5f && skyExposed(floorInt(m.x), floorInt(m.y + 2f), floorInt(m.z)))) {
                m.burnTimer += dt; if (m.burnTimer > 1f) { m.burnTimer = 0f; damage(m, 1f, 0f, 0f) }
            } else if (m.onGround && rnd.nextFloat() < dt * 2f) {
                val bx = floorInt(m.x); val by = floorInt(m.y + 0.1f); val bz = floorInt(m.z)
                if (world.getBlock(bx, by, bz) == Blocks.AIR && Blocks.opaque[world.getBlock(bx, by - 1, bz)]) game.setBlock(bx, by, bz, Blocks.SNOW_LAYER, 0)
            }
        }
        if (prey == null) return wander(m, dt).let { (it.first * 0.6f) to (it.second * 0.6f) }
        val dx = prey.x - m.x; val dz = prey.z - m.z
        val d = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
        m.yaw = atan2(dx, -dz)
        if (iron) {
            if (d < 1.8f + prey.halfWidth && m.attackCooldown <= 0f) {
                m.attackCooldown = 1.4f
                damage(prey, 10f, dx / d, dz / d); prey.vy = 9f
                game.sound("hit_stone", prey.x, prey.y + 1f, prey.z)
            }
            return if (d > 1.5f) (dx / d) to (dz / d) else 0f to 0f
        }
        if (m.attackCooldown <= 0f) {
            m.attackCooldown = 1.2f
            val sx = m.x + dx / d; val sy = m.y + 1.5f; val sz = m.z + dz / d
            val ty = prey.y + prey.height * 0.6f - sy
            game.projectiles.shoot(sx, sy, sz, dx / d * 16f, ty / d * 16f + d * 0.4f, dz / d * 16f, true, 1f, Projectile.SNOWBALL)
        }
        return if (d > 8f) (dx / d) to (dz / d) else if (d < 4f) (-dx / d) to (-dz / d) else 0f to 0f
    }

    /** Chases the nearest mob of the given kinds. Returns true while hunting. */
    private fun hunt(m: Mob, dt: Float, game: Game, kinds: Set<MobType>, smallOnly: Boolean = false): Boolean {
        val prey = list.filter { it.type in kinds && !it.dead && !it.tamed && (!smallOnly || it.scale < 1f) }
            .minByOrNull { (it.x - m.x) * (it.x - m.x) + (it.z - m.z) * (it.z - m.z) } ?: return false
        val dx = prey.x - m.x; val dz = prey.z - m.z
        val d = sqrt(dx * dx + dz * dz)
        if (d > 10f) return false
        m.yaw = atan2(dx, -dz)
        m.attackCooldown -= dt
        if (d < 1.2f + prey.halfWidth && m.attackCooldown <= 0f) { m.attackCooldown = 1f; damage(prey, 3f, dx / d.coerceAtLeast(0.01f), dz / d.coerceAtLeast(0.01f)) }
        finishMove(m, dt, dx / d.coerceAtLeast(0.01f), dz / d.coerceAtLeast(0.01f), m.type.speed * 1.3f)
        return true
    }

    /** Bats, bees and parrots flutter about; bees sting when angry, tamed parrots follow you. */
    private fun flutter(m: Mob, dt: Float, game: Game) {
        m.moving = true
        m.walkPhase += dt * 14f
        m.aiTimer -= dt
        val pl = game.player
        var tx = Float.NaN; var ty = 0f; var tz = 0f
        if (m.type == MobType.PARROT && m.tamed && !m.sitting) { tx = pl.x + sin(m.walkPhase * 0.05f) * 1.5f; ty = pl.y + 2.2f; tz = pl.z + cos(m.walkPhase * 0.05f) * 1.5f }
        if (m.type == MobType.BEE && m.angry && game.playerAlive) {
            val p = game.nearestTarget(m.x, m.z)
            tx = p.x; ty = p.y + 1f; tz = p.z
            val dx = p.x - m.x; val dy = p.y + 1f - m.y; val dz = p.z - m.z
            m.attackCooldown -= dt
            if (dx * dx + dy * dy + dz * dz < 1.2f && m.attackCooldown <= 0f) {
                // A bee stings once and then it is done for.
                game.hurtTarget(p, 2f, m.x, m.z)
                m.attackCooldown = 99f; m.angry = false; m.timer = 20f
            }
        }
        if (m.type == MobType.BEE && m.timer > 0f) { m.timer -= dt; if (m.timer <= 0f) damage(m, 100f, 0f, 0f) }
        if (m.sitting && m.tamed) { m.vx = 0f; m.vz = 0f; m.vy -= 20f * dt; if (!move(m, 1, m.vy * dt)) m.vy = 0f; m.moving = false; return }
        if (tx.isNaN()) {
            if (m.aiTimer <= 0f) {
                m.aiTimer = 0.6f + rnd.nextFloat() * 1.5f
                m.wanderYaw = rnd.nextFloat() * 6.2832f
                // Bees and parrots stay a few blocks above the ground; bats roam the caves.
                val ground = floorInt(m.y)
                var below = 0
                while (below < 8 && !Blocks.solid[world.getBlock(floorInt(m.x), ground - below - 1, floorInt(m.z))]) below++
                m.vy = when { below > 5 -> -1.5f; below < 2 -> 1.5f; else -> (rnd.nextFloat() - 0.5f) * 2f }
            }
            val sp = m.type.speed * (if (m.type == MobType.BAT) 1f else 0.6f)
            m.vx = approach(m.vx, sin(m.wanderYaw) * sp, 6f * dt); m.vz = approach(m.vz, -cos(m.wanderYaw) * sp, 6f * dt)
        } else {
            val ex = tx - m.x; val ey = ty - m.y; val ez = tz - m.z
            val len = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(0.1f)
            val sp = m.type.speed * (if (len > 8f) 2f else 1f)
            if (len > 24f && m.type == MobType.PARROT) { m.x = pl.x; m.y = pl.y + 2f; m.z = pl.z }
            m.vx = approach(m.vx, ex / len * sp, 8f * dt); m.vy = approach(m.vy, ey / len * sp, 8f * dt); m.vz = approach(m.vz, ez / len * sp, 8f * dt)
        }
        m.yaw = atan2(m.vx, -m.vz)
        if (!move(m, 0, m.vx * dt)) m.wanderYaw += 2f
        if (!move(m, 2, m.vz * dt)) m.wanderYaw += 2f
        if (!move(m, 1, m.vy * dt)) m.vy = -m.vy * 0.5f
    }

    private fun wander(m: Mob, dt: Float): Pair<Float, Float> {
        m.aiTimer -= dt
        if (m.aiTimer <= 0f) {
            m.aiTimer = 2f + rnd.nextFloat() * 4f
            m.wandering = rnd.nextFloat() < 0.55f
            m.wanderYaw = rnd.nextFloat() * 6.2832f
        }
        if (!m.wandering) return 0f to 0f
        m.yaw = turn(m.yaw, m.wanderYaw, dt * 3f)
        return sin(m.yaw) to -cos(m.yaw)
    }

    private fun turn(a: Float, b: Float, step: Float): Float {
        var d = (b - a) % 6.2832f
        if (d > 3.1416f) d -= 6.2832f
        if (d < -3.1416f) d += 6.2832f
        return a + d.coerceIn(-step, step)
    }

    private fun approach(v: Float, t: Float, s: Float) = if (v < t) minOf(v + s, t) else maxOf(v - s, t)

    /** Gravity + collision. Returns true when blocked horizontally. */
    private fun physics(m: Mob, dt: Float): Boolean {
        val inWater = world.getBlock(floorInt(m.x), floorInt(m.y + 0.3f), floorInt(m.z)) == Blocks.WATER
        if (inWater) { m.vy = approach(m.vy, 2.2f, 20f * dt) } else { m.vy -= 30f * dt; if (m.vy < -50f) m.vy = -50f }
        val hx = !move(m, 0, m.vx * dt)
        val hz = !move(m, 2, m.vz * dt)
        m.onGround = false
        if (!move(m, 1, m.vy * dt)) { if (m.vy < 0) m.onGround = true; m.vy = 0f }
        if (hx) m.vx = 0f
        if (hz) m.vz = 0f
        if (m.y < -10f) m.deathTime = 1f
        return hx || hz
    }

    private val posBuf = FloatArray(3)

    private fun move(m: Mob, axis: Int, delta: Float): Boolean {
        posBuf[0] = m.x; posBuf[1] = m.y; posBuf[2] = m.z
        val ok = com.vishucraft.game.world.Collision.sweep(world, posBuf, m.halfWidth, m.height, axis, delta)
        m.x = posBuf[0]; m.y = posBuf[1]; m.z = posBuf[2]
        return ok
    }

    fun voice(t: MobType) = when (t) {
        MobType.WARDEN -> "enderman"
        MobType.DROWNED -> "zombie"; MobType.RABBIT, MobType.SQUID, MobType.COD -> "none"
        MobType.IRON_GOLEM, MobType.SNOW_GOLEM, MobType.TURTLE, MobType.AXOLOTL -> "none"
        MobType.MOOSHROOM -> "cow"; MobType.POLAR_BEAR, MobType.PANDA -> "bear"
        else -> t.name.lowercase()
    }

    fun skyExposed(x: Int, y: Int, z: Int): Boolean {
        for (yy in max(y, 0) until Chunk.HEIGHT) if (Blocks.blocksLight[world.getBlock(x, yy, z)]) return false
        return true
    }

    /** Damage with knockback away from (fromX, fromZ); pass 0,0 direction for none. */
    fun damage(m: Mob, amount: Float, kx: Float, kz: Float) {
        if (m.dead) return
        m.health -= amount
        m.hurtTime = 0.35f
        m.vx += kx * 6f; m.vz += kz * 6f
        if (kx != 0f || kz != 0f) m.vy = max(m.vy, 5f)
        if (m.type.neutral) {
            m.angry = true
            // Bees and polar bears call their friends.
            if (m.type == MobType.BEE || m.type == MobType.POLAR_BEAR) for (o in list) if (o.type == m.type && (o.x - m.x) * (o.x - m.x) + (o.z - m.z) * (o.z - m.z) < 144f) o.angry = true
        } else if (!m.type.hostile) m.fleeTime = 5f
        if (m.type == MobType.CRAWLER || m.type == MobType.ENDERMAN) m.angry = true
        if (m.type == MobType.ENDERMAN && m.health > amount && rnd.nextInt(3) == 0) teleportNear(m, m.x, m.z)
        if (m.type == MobType.WISP && m.health > 0f) {
            // Blink a few blocks away.
            m.x += (rnd.nextFloat() - 0.5f) * 8f; m.y += rnd.nextFloat() * 3f; m.z += (rnd.nextFloat() - 0.5f) * 8f
        }
        if (m.health <= 0f) {
            m.deathTime = 0f; m.fuse = -1f; onDeath?.invoke(m)
            // Big slimes split into small ones.
            if (m.type == MobType.SLIME && m.age >= 0f) repeat(2 + rnd.nextInt(2)) {
                list.add(Mob(MobType.SLIME, m.x + rnd.nextFloat() - 0.5f, m.y + 0.3f, m.z + rnd.nextFloat() - 0.5f).also { s -> s.age = -1e9f; s.health = 4f })
            }
        }
    }

    /** Ray against every mob's box (slab test). */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): MobHit? {
        var best: MobHit? = null
        for (m in list) {
            if (m.dead) continue
            val hw = m.halfWidth + 0.1f
            val t = rayBox(ox, oy, oz, dx, dy, dz, m.x - hw, m.y, m.z - hw, m.x + hw, m.y + m.height, m.z + hw)
            if (t in 0f..maxDist && (best == null || t < best.distance)) best = MobHit(m, t)
        }
        return best
    }

    private fun rayBox(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                       x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Float {
        var tMin = -Float.MAX_VALUE; var tMax = Float.MAX_VALUE
        val o = floatArrayOf(ox, oy, oz); val d = floatArrayOf(dx, dy, dz)
        val lo = floatArrayOf(x0, y0, z0); val hi = floatArrayOf(x1, y1, z1)
        for (i in 0..2) {
            if (abs(d[i]) < 1e-6f) { if (o[i] < lo[i] || o[i] > hi[i]) return -1f; continue }
            var a = (lo[i] - o[i]) / d[i]; var b = (hi[i] - o[i]) / d[i]
            if (a > b) { val t = a; a = b; b = t }
            tMin = max(tMin, a); tMax = minOf(tMax, b)
            if (tMin > tMax) return -1f
        }
        return if (tMax < 0f) -1f else max(tMin, 0f)
    }

    // ---------------------------------------------------------------- spawning

    private fun surfaceY(x: Int, z: Int): Int {
        for (y in Chunk.HEIGHT - 2 downTo 1) {
            val b = world.getBlock(x, y, z)
            if (b != Blocks.AIR && Blocks[b].render != com.vishucraft.game.world.RenderType.CROSS) return y
        }
        return -1
    }

    private fun roomAt(x: Int, y: Int, z: Int, height: Int): Boolean {
        for (i in 0 until height) if (Blocks.solid[world.getBlock(x, y + i, z)] || world.getBlock(x, y + i, z) == Blocks.WATER) return false
        return Blocks.solid[world.getBlock(x, y - 1, z)]
    }

    private var villagersSeen = 0

    /** Which animal lives where. */
    private fun animalFor(biome: com.vishucraft.game.world.Biome, ground: Int, y: Int): MobType {
        val r = rnd.nextInt(12)
        return when {
            biome == com.vishucraft.game.world.Biome.MUSHROOM -> MobType.MOOSHROOM
            ground == Blocks.SAND && y <= 64 && r < 3 -> MobType.TURTLE
            biome == com.vishucraft.game.world.Biome.SNOW -> when (r) { 0, 1, 2 -> MobType.POLAR_BEAR; 3, 4, 5 -> MobType.FOX; 6, 7 -> MobType.RABBIT; 8, 9 -> MobType.GOAT; else -> MobType.WOLF }
            y > 88 -> if (r < 8) MobType.GOAT else MobType.LLAMA
            biome == com.vishucraft.game.world.Biome.JUNGLE -> when (r) { 0, 1, 2, 3 -> MobType.PARROT; 4, 5 -> MobType.PANDA; 6, 7 -> MobType.CHICKEN; else -> MobType.PIG }
            biome == com.vishucraft.game.world.Biome.SWAMP -> when (r) { 0, 1, 2, 3, 4 -> MobType.FROG; 5, 6 -> MobType.COW; else -> MobType.CHICKEN }
            biome == com.vishucraft.game.world.Biome.CHERRY -> when (r) { 0, 1, 2, 3 -> MobType.BEE; 4, 5 -> MobType.RABBIT; 6, 7 -> MobType.SHEEP; else -> MobType.PIG }
            biome == com.vishucraft.game.world.Biome.SAVANNA || biome == com.vishucraft.game.world.Biome.BADLANDS -> when (r) { 0, 1, 2 -> MobType.LLAMA; 3, 4 -> MobType.HORSE; 5 -> MobType.RABBIT; else -> MobType.COW }
            biome == com.vishucraft.game.world.Biome.DESERT -> MobType.RABBIT
            biome == com.vishucraft.game.world.Biome.FOREST -> when (r) { 0, 1 -> MobType.FOX; 2 -> MobType.BEE; 3, 4 -> MobType.WOLF; 5, 6 -> MobType.PIG; 7, 8 -> MobType.CHICKEN; else -> MobType.COW }
            else -> when (r) {
                0, 1 -> MobType.COW; 2, 3 -> MobType.PIG; 4, 5 -> MobType.SHEEP; 6, 7 -> MobType.CHICKEN
                8 -> MobType.RABBIT; 9 -> MobType.HORSE; 10 -> if (rnd.nextBoolean()) MobType.BEE else MobType.WOLF; else -> MobType.CAT
            }
        }
    }

    private fun trySpawn(game: Game) {
        val p = game.player
        val passive = list.count { !it.type.hostile && it.type != MobType.VILLAGER && it.type != MobType.BAT && it.type != MobType.IRON_GOLEM && !it.tamed }
        val hostile = list.count { it.type.hostile }

        // Villagers waiting from world generation.
        while (true) {
            val (x, y, z) = world.pendingVillagers.poll() ?: break
            list.add(Mob(MobType.VILLAGER, x + 0.5f, y.toFloat(), z + 0.5f))
            // Every few villagers come with an iron golem to guard them.
            if (++villagersSeen % 4 == 0) list.add(Mob(MobType.IRON_GOLEM, x + 1.5f, y.toFloat(), z + 0.5f))
        }

        if (game.dimension == com.vishucraft.game.world.Dimension.SKY && hostileEnabled && !game.level.bossDefeated &&
            list.none { it.type == MobType.WARDEN } && parked.none { it.type == MobType.WARDEN }) {
            // The Sky Warden guards the Sky Isles until it is defeated.
            list.add(Mob(MobType.WARDEN, p.x + 20f, p.y + 14f, p.z + 20f))
            game.uiEvents.add("toast:The Sky Warden has noticed you!")
            game.sound("enderman", p.x, p.y + 10f, p.z, 1.5f)
        }
        if (game.dimension != com.vishucraft.game.world.Dimension.OVERWORLD) {
            if (!hostileEnabled || hostile >= 8) return
            val (x, z) = ringPoint(p.x, p.z, 14f, 40f)
            if (!world.isLoaded(x, z)) return
            val type = if (game.dimension == com.vishucraft.game.world.Dimension.EMBER) MobType.CINDER else MobType.WISP
            val y = surfaceY(x, z)
            if (y > 0 && roomAt(x, y + 1, z, 2)) list.add(Mob(type, x + 0.5f, y + 1f, z + 0.5f))
            return
        }

        if (passive < 10) {
            val (x, z) = ringPoint(p.x, p.z, 24f, 64f)
            if (world.isLoaded(x, z)) {
                val y = surfaceY(x, z)
                val ground = world.getBlock(x, y, z)
                if (y > 0 && ground == Blocks.WATER && rnd.nextInt(2) == 0) {
                    // Squid and fish in lakes and seas.
                    val type = when (rnd.nextInt(8)) { 0, 1 -> MobType.SQUID; 2 -> MobType.AXOLOTL; else -> MobType.COD }
                    repeat(if (type == MobType.COD) 3 else 1) { list.add(Mob(type, x + 0.5f + it * 0.4f, y - 1.5f, z + 0.5f)) }
                } else if (y > 0 && (ground == Blocks.GRASS || ground == Blocks.SNOW_GRASS || ground == Blocks.SAND || ground == Blocks.MYCELIUM ||
                        ground == Blocks.RED_SAND || ground == Blocks.PODZOL || ground == Blocks.MUD || ground == Blocks.SNOW_LAYER || ground == Blocks.STONE) &&
                    roomAt(x, y + 1, z, 2)) {
                    val type = animalFor(world.generator.biomeAt(x, z), ground, y)
                    repeat(1 + rnd.nextInt(3)) {
                        val ox = x + rnd.nextInt(3) - 1; val oz = z + rnd.nextInt(3) - 1
                        val oy = surfaceY(ox, oz)
                        if (oy > 0 && roomAt(ox, oy + 1, oz, 2)) list.add(Mob(type, ox + 0.5f, oy + 1f, oz + 0.5f).also { m -> m.yaw = rnd.nextFloat() * 6.28f })
                    }
                }
            }
        }

        // Bats flutter about in dark caves.
        if (rnd.nextInt(6) == 0 && list.count { it.type == MobType.BAT } < 4) {
            val (x, z) = ringPoint(p.x, p.z, 10f, 32f)
            val y = 8 + rnd.nextInt(45)
            if (world.isLoaded(x, z) && roomAt(x, y, z, 2) && !skyExposed(x, y, z)) list.add(Mob(MobType.BAT, x + 0.5f, y + 0.5f, z + 0.5f))
        }
        if (hostileEnabled && hostile < 8) {
            val (x, z) = ringPoint(p.x, p.z, 18f, 48f)
            if (!world.isLoaded(x, z)) return
            var type = when (rnd.nextInt(14)) {
                0, 1 -> MobType.BOOMLING; 2 -> MobType.RATTLER; 3, 4 -> MobType.SKELETON; 5 -> MobType.CRAWLER
                6 -> MobType.SLIME; 7 -> MobType.WITCH; 8 -> MobType.ENDERMAN; else -> MobType.ZOMBIE
            }
            if (type == MobType.ZOMBIE && world.getBlock(x, surfaceY(x, z), z) == Blocks.WATER) type = MobType.DROWNED
            if (game.daylight < 0.3f && rnd.nextInt(6) == 0 && list.count { it.type == MobType.GLIDER } < 2) {
                val y = surfaceY(x, z)
                if (y > 0) list.add(Mob(MobType.GLIDER, x + 0.5f, y + 12f, z + 0.5f))
                return
            }
            if (game.daylight < 0.3f) {
                // Night: anywhere on the surface.
                val y = surfaceY(x, z)
                if (y > 0 && roomAt(x, y + 1, z, 2)) list.add(Mob(type, x + 0.5f, y + 1f, z + 0.5f))
            } else {
                // Day: only in dark caves.
                val y = 6 + rnd.nextInt(50)
                if (roomAt(x, y, z, 2) && !skyExposed(x, y, z)) list.add(Mob(type, x + 0.5f, y.toFloat(), z + 0.5f))
            }
        }
    }

    private fun ringPoint(cx: Float, cz: Float, minR: Float, maxR: Float): Pair<Int, Int> {
        val a = rnd.nextFloat() * 6.2832f
        val r = minR + rnd.nextFloat() * (maxR - minR)
        return floorInt(cx + cos(a) * r) to floorInt(cz + sin(a) * r)
    }
}
