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
}

class Mob(val type: MobType, var x: Float, var y: Float, var z: Float) {
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
    internal var aiTimer = 0f
    internal var wanderYaw = 0f
    internal var wandering = false
    internal var attackCooldown = 0f
    internal var burnTimer = 0f
    internal var fleeTime = 0f

    val dead get() = deathTime >= 0f
}

/** Result of a ray test against mobs. */
class MobHit(val mob: Mob, val distance: Float)

/** Spawning, AI and physics for all mobs. Runs on the game thread. */
class Mobs(private val world: World) {
    val list = ArrayList<Mob>()
    var hostileEnabled = true
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
        val p = game.player
        val it = list.iterator()
        while (it.hasNext()) {
            val m = it.next()
            val dx = m.x - p.x; val dz = m.z - p.z
            val far = dx * dx + dz * dz > 110f * 110f
            if (far || !world.isLoaded(floorInt(m.x), floorInt(m.z)) || (!hostileEnabled && m.type.hostile) ||
                (m.dead && m.deathTime > 0.8f)) {
                it.remove(); continue
            }
            tick(m, dt, game)
        }
    }

    private fun tick(m: Mob, dt: Float, game: Game) {
        if (m.hurtTime > 0f) m.hurtTime -= dt
        if (m.dead) {
            m.deathTime += dt
            m.vx *= 0.8f; m.vz *= 0.8f
            physics(m, dt)
            return
        }
        val p = game.player
        val dx = p.x - m.x; val dz = p.z - m.z
        val dist = sqrt(dx * dx + dz * dz)
        var wantX = 0f; var wantZ = 0f
        var speed = m.type.speed

        when (m.type) {
            MobType.ZOMBIE -> {
                if (dist < 28f && game.playerAlive) {
                    m.yaw = atan2(dx, -dz)
                    if (dist > 0.9f) { wantX = dx / dist; wantZ = dz / dist }
                    m.attackCooldown -= dt
                    if (dist < 1.4f && abs(p.y - m.y) < 1.6f && m.attackCooldown <= 0f) {
                        m.attackCooldown = 1f
                        game.hurtPlayer(3f, m.x, m.z)
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
                        if (dist < 2.6f && abs(p.y - m.y) < 2f) m.fuse = 0f
                        else { wantX = dx / dist; wantZ = dz / dist }
                    }
                } else { m.fuse = -1f; wander(m, dt).let { wantX = it.first; wantZ = it.second; speed *= 0.5f } }
            }
            else -> {
                if (m.fleeTime > 0f) {
                    m.fleeTime -= dt
                    speed *= 1.8f
                    if (dist > 0.01f) { wantX = -dx / dist; wantZ = -dz / dist; m.yaw = atan2(wantX, -wantZ) }
                } else wander(m, dt).let { wantX = it.first; wantZ = it.second }
            }
        }

        val accel = if (m.onGround) 20f else 5f
        m.vx = approach(m.vx, wantX * speed, accel * dt)
        m.vz = approach(m.vz, wantZ * speed, accel * dt)
        m.moving = abs(wantX) + abs(wantZ) > 0.01f
        val hit = physics(m, dt)
        // Hop up single blocks.
        if (hit && m.onGround && m.moving) m.vy = 8.4f
        if (m.moving && m.onGround) m.walkPhase += sqrt(m.vx * m.vx + m.vz * m.vz) * dt * 3.2f
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

    private fun move(m: Mob, axis: Int, delta: Float): Boolean {
        if (delta == 0f) return true
        var remaining = delta
        val hw = m.type.halfWidth; val h = m.type.height
        while (remaining != 0f) {
            val step = remaining.coerceIn(-0.45f, 0.45f)
            remaining -= step
            when (axis) { 0 -> m.x += step; 1 -> m.y += step; else -> m.z += step }
            for (bx in floorInt(m.x - hw)..floorInt(m.x + hw - 1e-4f))
                for (by in floorInt(m.y) - 1..floorInt(m.y + h - 1e-4f))
                    for (bz in floorInt(m.z - hw)..floorInt(m.z + hw - 1e-4f)) {
                        val id = world.getBlock(bx, by, bz)
                        if (!Blocks.solid[id]) continue
                        val top = by + Blocks.height[id]
                        if (top <= m.y + 1e-4f || by >= m.y + h) continue
                        when (axis) {
                            0 -> m.x = if (step > 0) bx - hw - 1e-3f else bx + 1 + hw + 1e-3f
                            1 -> m.y = if (step > 0) by - h - 1e-3f else top
                            else -> m.z = if (step > 0) bz - hw - 1e-3f else bz + 1 + hw + 1e-3f
                        }
                        return false
                    }
        }
        return true
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
        if (!m.type.hostile) m.fleeTime = 5f
        if (m.health <= 0f) { m.deathTime = 0f; m.fuse = -1f; onDeath?.invoke(m) }
    }

    /** Ray against every mob's box (slab test). */
    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): MobHit? {
        var best: MobHit? = null
        for (m in list) {
            if (m.dead) continue
            val hw = m.type.halfWidth + 0.1f
            val t = rayBox(ox, oy, oz, dx, dy, dz, m.x - hw, m.y, m.z - hw, m.x + hw, m.y + m.type.height, m.z + hw)
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

    private fun trySpawn(game: Game) {
        val p = game.player
        val passive = list.count { !it.type.hostile }
        val hostile = list.count { it.type.hostile }

        if (passive < 10) {
            val (x, z) = ringPoint(p.x, p.z, 24f, 64f)
            if (world.isLoaded(x, z)) {
                val y = surfaceY(x, z)
                val ground = world.getBlock(x, y, z)
                if (y > 0 && (ground == Blocks.GRASS || ground == Blocks.SNOW_GRASS) && roomAt(x, y + 1, z, 2)) {
                    val type = when (rnd.nextInt(3)) { 0 -> MobType.COW; 1 -> MobType.PIG; else -> MobType.SHEEP }
                    repeat(1 + rnd.nextInt(3)) {
                        val ox = x + rnd.nextInt(3) - 1; val oz = z + rnd.nextInt(3) - 1
                        val oy = surfaceY(ox, oz)
                        if (oy > 0 && roomAt(ox, oy + 1, oz, 2)) list.add(Mob(type, ox + 0.5f, oy + 1f, oz + 0.5f).also { m -> m.yaw = rnd.nextFloat() * 6.28f })
                    }
                }
            }
        }

        if (hostileEnabled && hostile < 8) {
            val (x, z) = ringPoint(p.x, p.z, 18f, 48f)
            if (!world.isLoaded(x, z)) return
            val type = if (rnd.nextInt(3) == 0) MobType.BOOMLING else MobType.ZOMBIE
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
