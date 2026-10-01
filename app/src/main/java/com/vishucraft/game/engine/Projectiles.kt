package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.RedstoneIds
import com.vishucraft.game.world.World
import com.vishucraft.game.world.floorInt
import kotlin.math.abs
import kotlin.math.sqrt

class Projectile(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float,
                 val fromPlayer: Boolean, val damage: Float, val kind: Int = ARROW) {
    var age = 0f
    var stuck = false

    companion object {
        const val ARROW = 0
        const val SNOWBALL = 1
        const val EGG = 2
        const val PEARL = 3
        /** A Rattler's thorn and a witch's splash potion (monster projectiles). */
        const val THORN = 4
        const val POTION = 5
        /** The Sky Warden's fireball. */
        const val FIREBALL = 6
    }
}

/** Arrows from the player's bow and thorns from Rattlers. */
class Projectiles(private val world: World) {
    val list = ArrayList<Projectile>()

    fun shoot(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float, fromPlayer: Boolean, damage: Float,
              kind: Int = Projectile.ARROW) {
        list.add(Projectile(x, y, z, vx, vy, vz, fromPlayer, damage, kind))
    }

    fun update(dt: Float, game: Game) {
        val it = list.iterator()
        while (it.hasNext()) {
            val a = it.next()
            a.age += dt
            if (a.age > 20f || a.y < -10f) { it.remove(); continue }
            if (a.stuck) continue
            a.vy -= 12f * dt
            // Sub-steps so fast arrows don't skip through thin walls or mobs.
            val steps = 4
            var hit = false
            for (s in 0 until steps) {
                a.x += a.vx * dt / steps; a.y += a.vy * dt / steps; a.z += a.vz * dt / steps
                if (a.fromPlayer) {
                    val m = game.mobs.list.firstOrNull { !it.dead && abs(it.x - a.x) < it.halfWidth + 0.3f && abs(it.z - a.z) < it.halfWidth + 0.3f && a.y >= it.y - 0.1f && a.y <= it.y + it.height + 0.25f }
                    if (m != null) {
                        val len = sqrt(a.vx * a.vx + a.vz * a.vz).coerceAtLeast(0.1f)
                        // Snowballs and eggs only knock back; they do no damage.
                        game.mobs.damage(m, a.damage, a.vx / len * 0.6f, a.vz / len * 0.6f)
                        game.sound("arrow_hit", a.x, a.y, a.z, 0.8f)
                        if (a.kind == Projectile.PEARL) game.pearlLanded(a.x, m.y, a.z)
                        if (a.kind == Projectile.EGG) game.eggLanded(a.x, m.y, a.z)
                        hit = true; break
                    }
                } else {
                    val t = game.nearestTarget(a.x, a.z)
                    if (abs(t.x - a.x) < 0.45f && abs(t.z - a.z) < 0.45f && a.y >= t.y && a.y <= t.y + 1.8f) {
                        game.hurtTarget(t, a.damage, a.x - a.vx, a.z - a.vz)
                        hit = true; break
                    }
                }
                val b = world.getBlock(floorInt(a.x), floorInt(a.y), floorInt(a.z))
                if (Blocks.solid[b]) {
                    game.sound("arrow_hit", a.x, a.y, a.z, 0.5f)
                    if (a.kind == Projectile.EGG) game.eggLanded(a.x - a.vx * 0.03f, a.y - a.vy * 0.03f, a.z - a.vz * 0.03f)
                    if (a.kind == Projectile.PEARL) {
                        // Land on top of (or just in front of) the block that was hit.
                        val bx = a.x - a.vx * 0.03f; val bz = a.z - a.vz * 0.03f
                        var by = a.y - a.vy * 0.03f
                        while (Blocks.solid[world.getBlock(floorInt(bx), floorInt(by), floorInt(bz))] && by < 127f) by += 1f
                        game.pearlLanded(bx, floorInt(by).toFloat(), bz)
                    }
                    // Player arrows can be picked up again (survival).
                    if (a.kind == Projectile.ARROW && a.fromPlayer && game.survival) {
                        game.drops.spawn(ItemStack(Items.find("Arrow")), a.x - a.vx * 0.02f, a.y - a.vy * 0.02f, a.z - a.vz * 0.02f)
                    }
                    hit = true; break
                }
            }
            if (hit) it.remove()
        }
    }
}

/**
 * A minecart that follows rails, can be ridden and is boosted by powered rails. Trains are carts too: an engine
 * or a metro drives itself, and coaches hooked on behind ([leader]) follow the track it has just run along.
 */
class Cart(var x: Float, var y: Float, var z: Float, var kind: Int = MINECART) {
    var speed = 0f
    var hx = 1f; var hz = 0f
    var vy = 0f
    var yaw = 0f
    /** The car this one is coupled behind. */
    var leader: Cart? = null
    /** Where this car has been (x, y, z), newest last; coaches behind it follow these points. */
    val trail = ArrayDeque<FloatArray>()
    /** Seconds left standing at a station (metros). */
    var stopTimer = 0f
    /** The station rail this metro last stopped at, so it doesn't stop there again straight away. */
    var lastStation = Long.MIN_VALUE
    /** Standing at the end of the track. */
    var atEnd = false
    /** The station rail last announced as the next station (not saved). */
    var announced = Long.MIN_VALUE
    /** Seconds stopped behind another train on the same track (not saved). */
    var waited = 0f

    val isTrain get() = kind != MINECART
    /** Half the length of the car body. */
    val half get() = when (kind) { ENGINE -> 1.35f; METRO -> 1.6f; BULLET -> 1.9f; COACH -> 1.4f; else -> 0.6f }
    val height get() = if (isTrain) 2.4f else 0.8f
    val maxSpeed get() = when (kind) { ENGINE -> 16f; METRO -> 20f; BULLET -> 50f; else -> 12f }   // the bullet train beats an airplane (42)
    /** Metros and bullet trains chime and announce stations; engines blow the horn. */
    val isMetroLike get() = kind == METRO || kind == BULLET

    companion object {
        const val MINECART = 0
        const val ENGINE = 1
        const val METRO = 2
        const val COACH = 3
        /** Runs on metro tracks too (it can share them), much faster. */
        const val BULLET = 4

        fun itemName(kind: Int) = when (kind) { ENGINE -> "Train Engine"; METRO -> "Metro Train"; COACH -> "Train Coach"; BULLET -> "Bullet Train"; else -> "Minecart" }
        fun kindOf(itemName: String?) = when (itemName) { "Train Engine" -> ENGINE; "Metro Train" -> METRO; "Train Coach" -> COACH; "Bullet Train" -> BULLET; else -> MINECART }
    }
}

/** A rowing boat: floats on water, steered by the player sitting in it. */
class Boat(var x: Float, var y: Float, var z: Float) {
    var yaw = 0f
    var speed = 0f
    var vy = 0f
}

class Boats(private val world: World) {
    val list = ArrayList<Boat>()
    var riding: Boat? = null

    private fun block(x: Float, y: Float, z: Float) = world.getBlock(floorInt(x), floorInt(y), floorInt(z))

    fun update(dt: Float, game: Game) {
        for (b in list) if (world.isLoaded(floorInt(b.x), floorInt(b.z))) step(b, dt, game)
        riding?.let { b ->
            val p = game.player
            p.x = b.x; p.z = b.z; p.y = b.y + 0.25f
            p.vx = 0f; p.vy = 0f; p.vz = 0f
        }
    }

    private fun step(b: Boat, dt: Float, game: Game) {
        val inWater = block(b.x, b.y + 0.1f, b.z) == Blocks.WATER
        val sunk = block(b.x, b.y + 0.55f, b.z) == Blocks.WATER
        when {
            sunk -> b.vy = 2.5f // bob back up to the surface
            inWater -> { b.vy = 0f; b.y = floorInt(b.y + 0.1f) + 0.5f }
            else -> b.vy -= 20f * dt
        }
        val onLand = !inWater
        if (riding === b) {
            b.yaw = game.player.yaw
            val want = game.input.moveForward * (if (onLand) 1.2f else 7.5f)
            b.speed += (want - b.speed) * minOf(1f, dt * 2.5f)
        } else b.speed *= (1f - dt * 1.5f).coerceAtLeast(0f)
        val nx = b.x + kotlin.math.sin(b.yaw) * b.speed * dt
        val nz = b.z - kotlin.math.cos(b.yaw) * b.speed * dt
        if (Blocks.solid[block(nx, b.y + 0.3f, b.z)]) b.speed *= 0.3f else b.x = nx
        if (Blocks.solid[block(b.x, b.y + 0.3f, nz)]) b.speed *= 0.3f else b.z = nz
        val ny = b.y + b.vy * dt
        if (b.vy < 0f && Blocks.solid[block(b.x, ny, b.z)]) { b.vy = 0f; b.y = floorInt(ny) + 1f } else b.y = ny
        if (b.y < -10f) b.y = -10f
    }

    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): Boat? {
        var t = 0f
        while (t < maxDist) {
            val px = ox + dx * t; val py = oy + dy * t; val pz = oz + dz * t
            list.firstOrNull { abs(it.x - px) < 0.7f && abs(it.z - pz) < 0.7f && py >= it.y - 0.1f && py <= it.y + 0.7f }?.let { return it }
            t += 0.1f
        }
        return null
    }

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(list.size)
        for (b in list) { d.writeFloat(b.x); d.writeFloat(b.y); d.writeFloat(b.z); d.writeFloat(b.yaw) }
    }

    fun read(d: java.io.DataInputStream) {
        repeat(d.readInt()) { list.add(Boat(d.readFloat(), d.readFloat(), d.readFloat()).also { it.yaw = d.readFloat() }) }
    }
}

class Carts(private val world: World) {
    val list = ArrayList<Cart>()
    var riding: Cart? = null

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(list.size)
        for (c in list) { d.writeFloat(c.x); d.writeFloat(c.y); d.writeFloat(c.z); d.writeFloat(c.hx); d.writeFloat(c.hz) }
    }

    fun read(d: java.io.DataInputStream) {
        repeat(d.readInt()) {
            list.add(Cart(d.readFloat(), d.readFloat(), d.readFloat()).also { c -> c.hx = d.readFloat(); c.hz = d.readFloat() })
        }
    }

    /** Train kinds and couplings, written after everything else so older saves still load. */
    fun writeExtra(d: java.io.DataOutputStream) {
        d.writeInt(list.size)
        for (c in list) { d.writeByte(c.kind); d.writeInt(c.leader?.let { list.indexOf(it) } ?: -1) }
    }

    fun readExtra(d: java.io.DataInputStream) {
        val n = d.readInt()
        val links = IntArray(n)
        for (i in 0 until n) { val kind = d.readByte().toInt(); links[i] = d.readInt(); if (i < list.size) list[i].kind = kind }
        for (i in 0 until minOf(n, list.size)) list[i].leader = list.getOrNull(links[i])?.takeIf { it !== list[i] }
    }

    /** The front car of the train [c] belongs to. */
    fun head(c: Cart): Cart {
        var h = c; var guard = 0
        while (guard++ < 64) h = h.leader?.takeIf { it in list } ?: break
        return h
    }

    /** The car coupled directly behind [c], if any. */
    fun follower(c: Cart): Cart? = list.firstOrNull { it.leader === c }

    /**
     * Hooks a newly placed coach behind the nearest train end within reach. The leading car's trail is filled in
     * back to the coach, so the coach knows where to run.
     */
    fun couple(coach: Cart) {
        val tail = list.filter { it !== coach && it.isTrain && follower(it) == null && head(it) !== coach }
            .minByOrNull { (it.x - coach.x) * (it.x - coach.x) + (it.z - coach.z) * (it.z - coach.z) }
            ?.takeIf { (it.x - coach.x) * (it.x - coach.x) + (it.z - coach.z) * (it.z - coach.z) < 7f * 7f } ?: return
        coach.leader = tail
        tail.trail.clear()
        val steps = 20
        for (k in 0..steps) {
            val f = k / steps.toFloat()
            tail.trail.addLast(floatArrayOf(coach.x + (tail.x - coach.x) * f, coach.y + (tail.y - coach.y) * f, coach.z + (tail.z - coach.z) * f))
        }
    }

    private fun record(c: Cart) {
        val last = c.trail.lastOrNull()
        if (last == null || (last[0] - c.x) * (last[0] - c.x) + (last[2] - c.z) * (last[2] - c.z) > 0.01f) {
            c.trail.addLast(floatArrayOf(c.x, c.y, c.z))
            while (c.trail.size > 600) c.trail.removeFirst()
        }
    }

    /** Puts a coupled car [gap] blocks behind its leader, along the leader's trail. */
    private fun follow(c: Cart, leader: Cart) {
        val gap = leader.half + c.half + 0.35f
        var px = leader.x; var py = leader.y; var pz = leader.z
        var left = gap
        var placed = false
        for (i in leader.trail.indices.reversed()) {
            val q = leader.trail[i]
            val dx = q[0] - px; val dz = q[2] - pz
            val d = kotlin.math.sqrt(dx * dx + dz * dz)
            if (d >= left && d > 0f) {
                val f = left / d
                c.x = px + dx * f; c.y = py + (q[1] - py) * f; c.z = pz + dz * f
                placed = true
                break
            }
            left -= d; px = q[0]; py = q[1]; pz = q[2]
        }
        // Past the end of the trail (just placed or loaded): carry on straight back from the leader's heading.
        if (!placed) { c.x = px - leader.hx * left; c.y = py; c.z = pz - leader.hz * left }
        val fx = leader.x - c.x; val fz = leader.z - c.z
        val len = kotlin.math.sqrt(fx * fx + fz * fz)
        if (len > 0.05f) { c.hx = fx / len; c.hz = fz / len; c.yaw = kotlin.math.atan2(c.hx, -c.hz) }
        c.speed = leader.speed
        c.vy = 0f
    }

    /** Gets the rider out: out of the side door of a train, or just up out of a minecart. */
    fun leave(game: Game) {
        val c = riding ?: return
        riding = null
        val p = game.player
        if (!c.isTrain) { p.y += 0.6f; return }
        for (side in floatArrayOf(1f, -1f)) {
            val x = c.x - c.hz * 1.2f * side; val z = c.z + c.hx * 1.2f * side
            val by = floorInt(c.y + 0.1f)
            if (!Blocks.solid[world.getBlock(floorInt(x), by, floorInt(z))] && !Blocks.solid[world.getBlock(floorInt(x), by + 1, floorInt(z))]) {
                p.x = x; p.z = z; p.y = c.y + 0.1f; return
            }
        }
        p.y = c.y + 2.5f
    }

    private fun railAt(x: Int, y: Int, z: Int): Int = world.getBlock(x, y, z).let { if (com.vishucraft.game.world.Rails.isRail(it)) it else 0 }

    /** Points a cart along its track in the direction closest to (lookX, lookZ). */
    fun aim(c: Cart, lookX: Float, lookZ: Float) {
        val bx = floorInt(c.x); val bz = floorInt(c.z); var by = floorInt(c.y + 0.1f)
        if (railAt(bx, by, bz) == 0) by -= 1
        if (railAt(bx, by, bz) == 0) return
        val exits = com.vishucraft.game.world.Rails.exits(com.vishucraft.game.world.Rails.shape(railAt(bx, by, bz), world.getMeta(bx, by, bz)))
        val best = exits.maxByOrNull { it[0] * lookX + it[1] * lookZ } ?: return
        c.hx = best[0].toFloat(); c.hz = best[1].toFloat()
    }

    /** Get into a cart. A cart that is standing still will head the way you are looking. */
    fun enter(c: Cart, lookX: Float, lookZ: Float) {
        riding = c
        // Trains keep their direction (coaches are pulled; engines are driven with forward and back).
        if (c.speed < 0.5f && !c.isTrain) aim(c, lookX, lookZ)
    }

    /** The cart right in front of the player (within reach of a jump), if any. */
    fun nearby(px: Float, py: Float, pz: Float, lookX: Float, lookZ: Float): Cart? = list.filter {
        val dx = it.x - px; val dz = it.z - pz
        val d = kotlin.math.sqrt(dx * dx + dz * dz)
        if (it.isTrain) {
            // Long cars: close to the body anywhere along its length.
            val along = abs(dx * it.hx + dz * it.hz); val across = abs(dx * it.hz - dz * it.hx)
            along < it.half + 0.8f && across < 1.9f && abs(it.y - py) < 1.6f
        } else d < 2.2f && abs(it.y - py) < 1.6f && (d < 0.8f || (dx * lookX + dz * lookZ) / d > 0.2f)
    }.minByOrNull { (it.x - px) * (it.x - px) + (it.z - pz) * (it.z - pz) }

    /** The cars of the train [head] leads, front to back. */
    fun chain(head: Cart): List<Cart> {
        val out = arrayListOf(head)
        var c = head
        while (out.size < 64) { c = follower(c) ?: break; out.add(c) }
        return out
    }

    /** The engine or metro car that powers this train, if it has one. */
    fun power(c: Cart): Cart? = chain(head(c)).firstOrNull { it.kind == Cart.ENGINE || it.kind == Cart.METRO || it.kind == Cart.BULLET }

    /** The station's name: the nearest sign or billboard around the stop rail at (x, y, z). */
    private fun stationName(x: Int, y: Int, z: Int): String? = world.blockEntities.nameNear(x, y, z, 14)

    /** Whether the player is riding in this train. */
    private fun riderIn(head: Cart) = riding?.let { head(it) === head } == true

    fun update(dt: Float, game: Game) {
        for (c in list) if (c.leader != null && c.leader !in list) c.leader = null
        // Carts in chunks that are not loaded wait there (they would otherwise fall through the missing ground).
        for (c in list) {
            if (c.leader != null || !world.isLoaded(floorInt(c.x), floorInt(c.z))) continue
            // Trains with an engine or a metro car drive themselves.
            if (power(c) != null && autoDrive(c, dt, game)) continue
            // Fast trains move in small steps so they never skip a curve.
            val n = kotlin.math.ceil(c.speed * dt / 0.3f).toInt().coerceIn(1, 14)
            repeat(n) { step(c, dt / n, game) }
            record(c)
        }
        // Coupled cars follow in order, front to back.
        var frontier = list.filter { it.leader == null }
        var guard = 0
        while (frontier.isNotEmpty() && guard++ < 64) {
            val next = list.filter { f -> f.leader != null && frontier.any { it === f.leader } }
            for (f in next) { follow(f, f.leader!!); record(f) }
            frontier = next
        }
        riding?.let { c ->
            val p = game.player
            // In a train you sit low enough to look out of the windows.
            p.x = c.x; p.z = c.z; p.y = c.y + (if (c.isTrain) 0f else 0.35f)
            p.vx = 0f; p.vy = 0f; p.vz = 0f
            val push = game.input.moveForward
            if (c.isTrain) {
                val h = head(c)
                // Forward leaves a station early; a coach on its own can be pushed along slowly.
                if (push > 0f && h.stopTimer > 0.1f && power(h) != null) h.stopTimer = 0.1f
                if (power(h) == null && h === c && push > 0f) c.speed = minOf(4f, c.speed + 3f * push * dt)
                return
            }
            // Push the cart in the direction the player is looking along the track.
            if (push != 0f) {
                val lookX = kotlin.math.sin(p.yaw); val lookZ = -kotlin.math.cos(p.yaw)
                val along = lookX * c.hx + lookZ * c.hz
                val dir = if (along >= 0) 1f else -1f
                if (c.speed == 0f && dir < 0) { c.hx = -c.hx; c.hz = -c.hz; c.speed = 0.1f }
                else c.speed += push * dir * 6f * dt
                if (c.speed < 0f) { c.hx = -c.hx; c.hz = -c.hz; c.speed = -c.speed }
            }
        }
    }

    /**
     * Engines and metros run all the time: full speed on open track, slowing for the next stop rail, standing
     * there for 10 seconds, and turning back at the end of the line. Returns true while standing still.
     */
    private fun autoDrive(h: Cart, dt: Float, game: Game): Boolean {
        val engine = power(h)!!
        if (h.stopTimer > 0f) {
            h.speed = 0f
            h.stopTimer -= dt
            if (h.stopTimer <= 0f) {
                h.stopTimer = 0f
                if (engine.isMetroLike) game.sound("metro_chime", h.x, h.y + 2f, h.z) else game.sound("train_horn", h.x, h.y + 2f, h.z, 0.8f)
                if (riderIn(h)) game.uiEvents.add("toast:" + if (engine.isMetroLike) "Doors closing. Please stand clear" else "All aboard! The train is leaving")
            }
            return true
        }
        if (h.atEnd) { reverse(h, game); return true }
        val ahead = stopAhead(h, game)
        val max = engine.maxSpeed
        // Brake in time for the next station (fast trains look further ahead).
        var target = if (ahead != null) minOf(max, 1.5f + kotlin.math.sqrt(24f * ahead)) else max
        // Trains sharing a track keep their distance; when two meet head on, one turns back.
        val (gap, other) = trainAhead(h)
        if (gap != null) {
            target = minOf(target, kotlin.math.sqrt(2f * 12f * maxOf(0f, gap - 3f)))
            if (target < 0.3f) {
                h.speed = 0f
                h.waited += dt
                val oh = other?.let { head(it) }
                // Head on: the train with track behind it backs off (if both have, the first one does).
                if (oh != null && h.waited > 3f && oh.hx * h.hx + oh.hz * h.hz < -0.5f && canBack(h) && (!canBack(oh) || list.indexOf(h) < list.indexOf(oh))) {
                    h.waited = 0f
                    if (riderIn(h)) game.uiEvents.add("toast:Another train is on this track: turning back")
                    reverse(h, game, quiet = true)
                }
                return true
            }
        }
        h.waited = 0f
        h.speed = if (h.speed < target) minOf(target, h.speed + (if (engine.kind == Cart.BULLET) 10f else 5f) * dt) else maxOf(target, h.speed - 14f * dt)
        if (h.speed < 0.3f) h.speed = 0.3f
        return false
    }

    /**
     * At the end of the track the whole train turns round: the last car becomes the front, every car faces the
     * other way, and it waits 10 seconds like at a station.
     */
    fun reverse(h: Cart, game: Game, quiet: Boolean = false) {
        val cars = chain(h)
        for (c in cars) { c.hx = -c.hx; c.hz = -c.hz; c.yaw = kotlin.math.atan2(c.hx, -c.hz); c.leader = null; c.trail.clear(); c.speed = 0f; c.atEnd = false }
        val rev = cars.reversed()
        for (k in 1 until rev.size) {
            val front = rev[k - 1]; val back = rev[k]
            back.leader = front
            // The front car's trail leads back to the car behind it, so the train keeps its shape.
            for (s in 0..20) {
                val f = s / 20f
                front.trail.addLast(floatArrayOf(back.x + (front.x - back.x) * f, back.y + (front.y - back.y) * f, back.z + (front.z - back.z) * f))
            }
        }
        val nh = rev[0]
        nh.stopTimer = STOP_SECONDS
        nh.lastStation = Long.MIN_VALUE
        game.sound(if (power(nh)?.isMetroLike == true) "metro_chime" else "train_horn", nh.x, nh.y + 2f, nh.z, 0.8f)
        if (quiet) nh.stopTimer = 3f
        else if (riderIn(nh)) game.uiEvents.add("toast:End of the line. The train goes back the other way in 10 seconds")
    }

    /** Whether there is track behind the train's last car, so it can turn back. */
    private fun canBack(h: Cart): Boolean {
        val tail = chain(h).last()
        val d = tail.half + 2f
        val x = floorInt(tail.x - h.hx * d); val z = floorInt(tail.z - h.hz * d); val y = floorInt(tail.y + 0.1f)
        return (-1..1).any { railAt(x, y + it, z) != 0 }
    }

    /**
     * The nearest car of another train (or a minecart) ahead on the same track, within 70 blocks: the gap to it
     * in blocks, and the car. Cars on the next track (2 blocks over) don't count.
     */
    private fun trainAhead(h: Cart): Pair<Float?, Cart?> {
        val mine = chain(h)
        var best: Float? = null; var car: Cart? = null
        for (o in list) {
            if (mine.any { it === o } || kotlin.math.abs(o.y - h.y) > 3f) continue
            val dx = o.x - h.x; val dz = o.z - h.z
            val along = dx * h.hx + dz * h.hz
            if (along <= 0f || along > 140f || kotlin.math.abs(dx * h.hz - dz * h.hx) > 1.3f) continue
            val gap = along - h.half - o.half
            if (best == null || gap < best) { best = gap; car = o }
        }
        return best to car
    }

    private fun railCell(c: Cart): IntArray? {
        val bx = floorInt(c.x); val bz = floorInt(c.z); var by = floorInt(c.y + 0.1f)
        if (railAt(bx, by, bz) == 0) by -= 1
        return if (railAt(bx, by, bz) == 0) null else intArrayOf(bx, by, bz)
    }

    /** Blocks until the next stop rail ahead (further ahead the faster it goes); riders hear which station comes next. */
    private fun stopAhead(c: Cart, game: Game? = null): Int? {
        val cell = railCell(c) ?: return null
        val sx = kotlin.math.round(c.hx).toInt(); val sz = kotlin.math.round(c.hz).toInt()
        val range = maxOf(8, (c.speed * c.speed / 24f).toInt() + 6).coerceAtMost(120)
        for (k in 0..range) {
            val x = cell[0] + sx * k; val z = cell[2] + sz * k
            for (dy in -1..1) {
                val y = cell[1] + dy
                if (world.getBlock(x, y, z) == Blocks.STOP_RAIL && RedstoneIds.pack(x, y, z) != c.lastStation) {
                    val key = RedstoneIds.pack(x, y, z)
                    if (game != null && key != c.announced) {
                        c.announced = key
                        if (riderIn(c)) game.uiEvents.add("toast:Next station: " + (stationName(x, y, z) ?: "Station"))
                    }
                    return k
                }
            }
        }
        return null
    }

    /** A running train stops for 10 seconds on each stop rail (once its middle reaches the rail's middle). */
    private fun checkStation(c: Cart, bx: Int, by: Int, bz: Int, game: Game) {
        if (c.leader != null || c.stopTimer > 0f || world.getBlock(bx, by, bz) != Blocks.STOP_RAIL) return
        val engine = power(c) ?: return
        val key = RedstoneIds.pack(bx, by, bz)
        if (key == c.lastStation) return
        val fx = c.x - (bx + 0.5f); val fz = c.z - (bz + 0.5f)
        if (fx * c.hx + fz * c.hz < 0f) return
        c.lastStation = key
        c.stopTimer = STOP_SECONDS
        c.speed = 0f
        game.sound(if (engine.isMetroLike) "metro_chime" else "train_horn", c.x, c.y + 2f, c.z, 0.8f)
        if (riderIn(c)) {
            val name = stationName(bx, by, bz)
            game.uiEvents.add("toast:" + (if (name != null) "This is $name.\n" else "") + "Station stop for 10 seconds. Crouch or tap to get off, forward to leave now")
        }
    }

    companion object {
        /** Seconds a train stands at a stop rail or the end of the line. */
        const val STOP_SECONDS = 10f
    }

    private fun step(c: Cart, dt: Float, game: Game) {
        val bx = floorInt(c.x); val bz = floorInt(c.z)
        var by = floorInt(c.y + 0.1f)
        var rail = railAt(bx, by, bz)
        if (rail == 0 && railAt(bx, by - 1, bz) != 0) { by -= 1; rail = railAt(bx, by, bz) }
        if (rail == 0) {
            // Off the rails: fall and slide to a stop.
            c.vy -= 20f * dt
            val ny = c.y + c.vy * dt
            if (Blocks.solid[world.getBlock(bx, floorInt(ny), bz)]) { c.vy = 0f; c.y = floorInt(ny) + 1f } else c.y = ny
            c.speed *= (1f - 2f * dt).coerceAtLeast(0f)
            c.x += c.hx * c.speed * dt; c.z += c.hz * c.speed * dt
            return
        }
        c.vy = 0f
        val meta = world.getMeta(bx, by, bz)
        val shape = com.vishucraft.game.world.Rails.shape(rail, meta)
        val exits = com.vishucraft.game.world.Rails.exits(shape)
        // Keep heading along one of the rail's exits (the one not behind us on curves).
        val fx = c.x - (bx + 0.5f); val fz = c.z - (bz + 0.5f)
        val onFirstHalf = fx * c.hx + fz * c.hz < 0f
        var best = exits[0]; var bestDot = -2f
        for (e in exits) {
            val dot = e[0] * c.hx + e[1] * c.hz
            if (dot > bestDot) { bestDot = dot; best = e }
        }
        if (shape >= 6 && !onFirstHalf && bestDot < 0.5f) {
            // Past the centre of a curve: turn to the exit that isn't where we came from.
            best = exits.first { !(it[0] == -c.hx.toInt() && it[1] == -c.hz.toInt()) }
        }
        if (shape < 6 || !onFirstHalf) { c.hx = best[0].toFloat(); c.hz = best[1].toFloat() }
        // Stay centred on the track.
        if (c.hx != 0f) c.z += (bz + 0.5f - c.z) * minOf(1f, dt * 20f)
        if (c.hz != 0f) c.x += (bx + 0.5f - c.x) * minOf(1f, dt * 20f)
        // Slopes pull the cart downhill.
        val rise = com.vishucraft.game.world.Rails.rise(shape)
        if (rise != null) {
            val uphill = rise[0] * c.hx + rise[1] * c.hz
            c.speed -= uphill * 9f * dt
            if (c.speed < 0f) { c.hx = -c.hx; c.hz = -c.hz; c.speed = -c.speed }
        }
        checkStation(c, bx, by, bz, game)
        if (rail == Blocks.POWERED_RAIL && !c.isTrain) {
            // A cart standing on a powered rail next to a wall is pushed away from the wall;
            // otherwise it keeps going the way it was already heading.
            if (meta and 8 != 0 && c.speed < 0.5f) {
                val front = Blocks.solid[world.getBlock(bx + c.hx.toInt(), by, bz + c.hz.toInt())]
                val back = Blocks.solid[world.getBlock(bx - c.hx.toInt(), by, bz - c.hz.toInt())]
                if (front && !back) { c.hx = -c.hx; c.hz = -c.hz }
            }
            if (meta and 8 != 0) c.speed = minOf(12f, c.speed + 14f * dt).coerceAtLeast(if (c.speed < 0.5f) 2f else 0f)
            else c.speed = maxOf(0f, c.speed - 25f * dt)
        }
        c.speed = (c.speed * (1f - 0.15f * dt)).coerceAtMost(c.maxSpeed)
        val nx = c.x + c.hx * c.speed * dt; val nz = c.z + c.hz * c.speed * dt
        // Trains stop at the end of the track instead of rolling off it.
        c.atEnd = false
        if (c.isTrain && (floorInt(nx) != bx || floorInt(nz) != bz) && (-1..1).none { railAt(floorInt(nx), by + it, floorInt(nz)) != 0 }) {
            c.speed = 0f; c.atEnd = true
            c.x += (bx + 0.5f - c.x) * minOf(1f, dt * 10f); c.z += (bz + 0.5f - c.z) * minOf(1f, dt * 10f)
            c.y = by.toFloat(); c.yaw = kotlin.math.atan2(c.hx, -c.hz)
            return
        }
        if (Blocks.solid[world.getBlock(floorInt(nx), by, floorInt(nz))] && railAt(floorInt(nx), by + 1, floorInt(nz)) == 0) {
            c.speed = 0f
        } else { c.x = nx; c.z = nz }
        // Height follows the slope.
        val frac = if (rise != null) ((c.x - bx) * rise[0] + (c.z - bz) * rise[1]).let { if (rise[0] + rise[1] < 0) it + 1f else it } else 0f
        c.y = by + frac.coerceIn(0f, 1f)
        c.yaw = kotlin.math.atan2(c.hx, -c.hz)
    }

    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): Cart? {
        var t = 0f
        while (t < maxDist) {
            val px = ox + dx * t; val py = oy + dy * t; val pz = oz + dz * t
            list.firstOrNull {
                val dx = px - it.x; val dz = pz - it.z
                abs(dx * it.hx + dz * it.hz) < it.half && abs(dx * it.hz - dz * it.hx) < (if (it.isTrain) 0.65f else 0.5f) &&
                    py >= it.y && py <= it.y + it.height
            }?.let { return it }
            t += 0.1f
        }
        return null
    }
}
