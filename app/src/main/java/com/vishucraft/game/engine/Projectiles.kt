package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
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

/** A minecart that follows rails, can be ridden and is boosted by powered rails. */
class Cart(var x: Float, var y: Float, var z: Float) {
    var speed = 0f
    var hx = 1f; var hz = 0f
    var vy = 0f
    var yaw = 0f
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
        if (c.speed < 0.5f) aim(c, lookX, lookZ)
    }

    /** The cart right in front of the player (within reach of a jump), if any. */
    fun nearby(px: Float, py: Float, pz: Float, lookX: Float, lookZ: Float): Cart? = list.filter {
        val dx = it.x - px; val dz = it.z - pz
        val d = kotlin.math.sqrt(dx * dx + dz * dz)
        d < 2.2f && abs(it.y - py) < 1.6f && (d < 0.8f || (dx * lookX + dz * lookZ) / d > 0.2f)
    }.minByOrNull { (it.x - px) * (it.x - px) + (it.z - pz) * (it.z - pz) }

    fun update(dt: Float, game: Game) {
        // Carts in chunks that are not loaded wait there (they would otherwise fall through the missing ground).
        for (c in list) if (world.isLoaded(floorInt(c.x), floorInt(c.z))) step(c, dt, game)
        riding?.let { c ->
            val p = game.player
            p.x = c.x; p.z = c.z; p.y = c.y + 0.35f
            p.vx = 0f; p.vy = 0f; p.vz = 0f
            // Push the cart in the direction the player is looking along the track.
            val push = game.input.moveForward
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
        if (rail == Blocks.POWERED_RAIL) {
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
        c.speed = (c.speed * (1f - 0.15f * dt)).coerceAtMost(12f)
        val nx = c.x + c.hx * c.speed * dt; val nz = c.z + c.hz * c.speed * dt
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
            list.firstOrNull { abs(it.x - px) < 0.5f && abs(it.z - pz) < 0.5f && py >= it.y && py <= it.y + 0.8f }?.let { return it }
            t += 0.1f
        }
        return null
    }
}
