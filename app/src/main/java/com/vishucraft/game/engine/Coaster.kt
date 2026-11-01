package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.floorInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A real steel roller coaster. Tap the ground with a Roller Coaster and it is set up there: smooth red tube track
 * on white steel supports, starting at a station. The train clanks up a 30-block chain lift, tips over the top
 * into a near-vertical drop, races through a full vertical loop (upside down at the top!), floats over an
 * airtime hill, swings round a steeply banked turn, over a camelback hill and round another banked turn back
 * into the station. Tap the train at the station to get in; it sets off after a moment and you can only get out
 * when it is back in the station.
 */

/** Physics for the old block-track coaster cars (kind [Cart.COASTER]) from earlier versions. */
internal object CoasterRide {
    const val PULL = 13f
    const val CRAWL = 3.2f
    const val LAUNCH = 7f
}

/**
 * One coaster: the track is worked out from where it starts ([ox], [oy], [oz]) and the way it faces ([fx], [fz]),
 * as points every [DS] blocks along it, each with the track's forward, up (banked) and right directions.
 */
class CoasterTrack(val ox: Float, val oy: Float, val oz: Float, val fx: Int, val fz: Int) {
    val px: FloatArray; val py: FloatArray; val pz: FloatArray
    /** Forward, up and right at each point. */
    val f: Array<FloatArray>; val u: Array<FloatArray>; val r: Array<FloatArray>
    /** Points on the chain lift. */
    val lift: BooleanArray
    val n: Int
    val length: Float
    /** Support columns: x, z, top, bottom. */
    val supports = ArrayList<FloatArray>()

    // The train.
    var s = STOP; var speed = 0f; var running = false; var arriving = false; var wait = 3f
    internal var clack = 0f
    internal var screamed = false
    internal var loopScreamed = false

    init {
        val pts = layout()
        n = pts.size
        px = FloatArray(n); py = FloatArray(n); pz = FloatArray(n)
        f = Array(n) { FloatArray(3) }; u = Array(n) { FloatArray(3) }; r = Array(n) { FloatArray(3) }
        lift = BooleanArray(n)
        val rx = -fz; val rz = fx   // to the right
        for ((i, p) in pts.withIndex()) {
            // Local (forward a, up y, right b) to world.
            fun wx(a: Double, b: Double) = (fx * a + rx * b).toFloat()
            fun wz(a: Double, b: Double) = (fz * a + rz * b).toFloat()
            px[i] = ox + wx(p.pos[0], p.pos[2]); py[i] = oy + p.pos[1].toFloat(); pz[i] = oz + wz(p.pos[0], p.pos[2])
            for ((dst, v) in listOf(f[i] to p.f, u[i] to p.u, r[i] to p.r)) { dst[0] = wx(v[0], v[2]); dst[1] = v[1].toFloat(); dst[2] = wz(v[0], v[2]) }
            lift[i] = p.lift
        }
        length = (n - 1) * DS
    }

    /** Index of the point at distance [at] along the track (wrapping round). */
    fun index(at: Float): Int { var k = (at / DS).toInt() % (n - 1); if (k < 0) k += n - 1; return k }
    /** Position at distance [at]: (x, y, z). */
    fun pos(at: Float): FloatArray {
        var a = at % length; if (a < 0) a += length
        val k = min((a / DS).toInt(), n - 2); val t = a / DS - k
        return floatArrayOf(px[k] + (px[k + 1] - px[k]) * t, py[k] + (py[k + 1] - py[k]) * t, pz[k] + (pz[k + 1] - pz[k]) * t)
    }
    val inStation get() = !running

    companion object {
        const val DS = 0.25f
        /** Where the train stops in the station, and how long the station is. */
        const val STOP = 6f
        const val STATION = 10f
        /** Gravity along the track and the chain lift's speed. */
        const val G = 50f
        const val CHAIN = 6f

        private class Pt(val pos: DoubleArray, val f: DoubleArray, val u: DoubleArray, val r: DoubleArray, val lift: Boolean)

        /** The ride, worked out like a turtle drawing it: straights, climbs and dips, a loop and banked turns. */
        private fun layout(): List<Pt> {
            val out = ArrayList<Pt>()
            val p = doubleArrayOf(0.0, 0.0, 0.0)
            var fw = doubleArrayOf(1.0, 0.0, 0.0); var up = doubleArrayOf(0.0, 1.0, 0.0); var rt = doubleArrayOf(0.0, 0.0, 1.0)
            var roll = 0.0
            val step = DS.toDouble()
            fun add(v: DoubleArray, w: DoubleArray, k: Double) { for (i in 0..2) v[i] += w[i] * k }
            fun record(lift: Boolean) {
                val c = cos(roll); val s = sin(roll)
                val bu = DoubleArray(3) { up[it] * c + rt[it] * s }
                val br = DoubleArray(3) { rt[it] * c - up[it] * s }
                out.add(Pt(p.copyOf(), fw.copyOf(), bu, br, lift))
            }
            fun straight(len: Double, lift: Boolean = false) {
                repeat(maxOf(1, (len / step).toInt())) { add(p, fw, len / maxOf(1, (len / step).toInt())); record(lift) }
            }
            /** Nose up (or down, for a negative angle) round a curve of [radius]. */
            fun pitch(deg: Double, radius: Double, lift: Boolean = false, shift: Double = 0.0) {
                val len = abs(Math.toRadians(deg)) * radius
                val k = maxOf(1, (len / step).toInt())
                val d = Math.toRadians(deg) / k
                repeat(k) {
                    val c = cos(d); val s = sin(d)
                    val nf = DoubleArray(3) { fw[it] * c + up[it] * s }
                    val nu = DoubleArray(3) { up[it] * c - fw[it] * s }
                    fw = nf; up = nu
                    add(p, fw, len / k); add(p, rt, shift / k)
                    record(lift)
                }
            }
            /** A level turn to the right (negative: left), banking in and out by [bank] degrees. */
            fun turn(deg: Double, radius: Double, bank: Double) {
                val len = abs(Math.toRadians(deg)) * radius
                val k = maxOf(1, (len / step).toInt())
                val d = Math.toRadians(deg) / k
                repeat(k) { i ->
                    val c = cos(d); val s = sin(d)
                    val nf = DoubleArray(3) { fw[it] * c + rt[it] * s }
                    val nr = DoubleArray(3) { rt[it] * c - fw[it] * s }
                    fw = nf; rt = nr
                    add(p, fw, len / k)
                    // Bank in over the first fifth, out over the last.
                    val t = (i + 1.0) / k
                    roll = Math.toRadians(bank) * minOf(1.0, t * 5, (1 - t) * 5) * (if (deg < 0) -1 else 1)
                    record(false)
                }
                roll = 0.0
            }
            straight(STATION.toDouble() + 3)
            pitch(40.0, 5.0, lift = true)
            straight((30 - 2 * 5 * (1 - cos(Math.toRadians(40.0)))) / sin(Math.toRadians(40.0)), lift = true)
            pitch(-40.0, 5.0, lift = true)
            straight(2.0)
            // The big drop: tip over to 75 degrees, fall, and pull out level at the ground.
            pitch(-75.0, 6.0)
            straight(maxOf(1.0, (p[1] - 10 * (1 - cos(Math.toRadians(75.0)))) / sin(Math.toRadians(75.0))))
            pitch(75.0, 10.0)
            straight(6.0)
            // A full vertical loop, moving 3 blocks to the side so it clears its own track.
            pitch(360.0, 9.0, shift = 3.0)
            straight(6.0)
            // Airtime hill.
            pitch(30.0, 14.0); pitch(-60.0, 14.0); pitch(30.0, 14.0)
            straight(4.0)
            turn(180.0, 10.0, 55.0)
            straight(4.0)
            // Camelback.
            pitch(20.0, 12.0); pitch(-40.0, 12.0); pitch(20.0, 12.0)
            // Then along to the last turn, which brings it back in line with the station.
            val side = p[2]
            val endTurnA = p[0]
            straight(maxOf(0.0, endTurnA - (-8.0)))
            turn(180.0, side / 2, 45.0)
            straight(8.0)
            // Close the loop exactly: spread the small leftover gap over the last stretch.
            val last = out.size - 1
            val gap = DoubleArray(3) { -out[last].pos[it] }
            val span = minOf(160, out.size)
            for (k in 0 until span) {
                val i = last - span + 1 + k; val t = (k + 1.0) / span
                for (c in 0..2) out[i].pos[c] += gap[c] * t
            }
            // The first point is the start again, so the lap joins up.
            out.add(0, Pt(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), false))
            return out
        }
    }
}

/** Every coaster in the world. */
class Coasters {
    val list = ArrayList<CoasterTrack>()
    /** Changes whenever one is added or removed (the renderer rebuilds the track then). */
    var version = 0
    var riding: CoasterTrack? = null

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(list.size)
        for (c in list) {
            d.writeFloat(c.ox); d.writeFloat(c.oy); d.writeFloat(c.oz); d.writeByte(c.fx); d.writeByte(c.fz)
            d.writeInt(c.supports.size); for (s in c.supports) for (v in s) d.writeFloat(v)
        }
    }

    fun read(d: java.io.DataInputStream) {
        repeat(d.readInt()) {
            val c = CoasterTrack(d.readFloat(), d.readFloat(), d.readFloat(), d.readByte().toInt(), d.readByte().toInt())
            repeat(d.readInt()) { c.supports.add(FloatArray(4) { d.readFloat() }) }
            list.add(c)
        }
        version++
    }
}

/** Using a Roller Coaster item on the ground. Returns true when handled. */
internal fun Game.coasterUse(item: com.vishucraft.game.world.ItemDef?, t: RayHit?): Boolean {
    if (item?.name != "Roller Coaster") return false
    if (t == null || t.ny != 1) { uiEvents.add("toast:Tap the ground to set up the roller coaster"); return true }
    if (isClient) return true
    val (fx, fz) = when (((player.yaw / (Math.PI / 2)).let { kotlin.math.round(it).toInt() } % 4 + 4) % 4) {
        0 -> 0 to -1; 1 -> 1 to 0; 2 -> 0 to 1; else -> -1 to 0
    }
    buildCoaster(t.x + fx * 3 + 0.5f, t.y + 1f, t.z + fz * 3 + 0.5f, fx, fz)
    consumeHeld()
    uiEvents.add("toast:Roller coaster ready! Tap the train in the station to get in... if you dare!")
    return true
}

/** Sets up a coaster starting at (x, y, z) and facing (fx, fz): clears its way and puts up the supports. */
fun Game.buildCoaster(x: Float, y: Float, z: Float, fx: Int, fz: Int): CoasterTrack {
    val c = CoasterTrack(x, y, z, fx, fz)
    for (i in 0 until c.n) world.ensureLoaded(floorInt(c.px[i]) shr 4, floorInt(c.pz[i]) shr 4)
    // Clear the way: the track and room for the riders above it (it may run through a hill).
    for (i in 0 until c.n step 2) for (k in -2..9) for (side in -1..1) {
        val h = k * 0.3f; val w = side * 0.7f
        val bx = floorInt(c.px[i] + c.u[i][0] * h + c.r[i][0] * w)
        val by = floorInt(c.py[i] + c.u[i][1] * h + c.r[i][1] * w)
        val bz = floorInt(c.pz[i] + c.u[i][2] * h + c.r[i][2] * w)
        if (world.getBlock(bx, by, bz) != Blocks.AIR) setBlock(bx, by, bz, Blocks.AIR)
    }
    // The station platform on the left, with a fence along its back.
    for (i in 0 until (CoasterTrack.STATION / CoasterTrack.DS).toInt() + 4) {
        for (w in 1..2) {
            val bx = floorInt(c.px[i] - c.r[i][0] * (0.9f + w)); val bz = floorInt(c.pz[i] - c.r[i][2] * (0.9f + w))
            val by = floorInt(c.py[i]) - 1
            setBlock(bx, by, bz, Blocks.SMOOTH_STONE)
            for (h in 1..3) setBlock(bx, by + h, bz, Blocks.AIR)
            if (w == 2) setBlock(bx, by + 1, bz, Blocks.OAK_FENCE)
        }
    }
    // Supports every few blocks under the track (not where it is upside down or another piece runs beneath).
    val every = (2.5f / CoasterTrack.DS).toInt()
    for (i in 0 until c.n step every) {
        if (c.u[i][1] < 0.8f) continue
        val top = c.py[i] - 0.45f
        var gy = floorInt(top) - 1
        while (gy > 1 && gy > top - 60 && !Blocks.solid[world.getBlock(floorInt(c.px[i]), gy, floorInt(c.pz[i]))]) gy--
        if (top - (gy + 1) < 1f) continue
        var clear = true
        for (j in 0 until c.n step 2) {
            if (abs(j - i) < 12 || c.py[j] > top - 0.5f) continue
            val dx = c.px[j] - c.px[i]; val dz = c.pz[j] - c.pz[i]
            if (dx * dx + dz * dz < 1.5f * 1.5f) { clear = false; break }
        }
        if (clear) c.supports.add(floatArrayOf(c.px[i], c.pz[i], top, gy + 1f))
    }
    coasters.list.add(c); coasters.version++
    return c
}

/** Runs every coaster's train. */
internal fun Game.updateCoasters(dt: Float) {
    if (isClient) return
    for (c in coasters.list) {
        val rider = coasters.riding === c
        if (!c.running) {
            c.speed = 0f
            if (rider) { c.wait -= dt; if (c.wait <= 0f) { c.running = true; c.speed = 4f; c.screamed = false; c.loopScreamed = false; sound("click", c.px[0], c.py[0], c.pz[0]) } }
            else c.wait = 3f
        } else {
            val i = c.index(c.s)
            if (c.lift[i]) {
                // The chain lift: clank, clank, clank...
                c.speed = CoasterTrack.CHAIN
                c.clack -= dt
                if (c.clack <= 0f) { c.clack = 0.22f; sound("click", c.px[i], c.py[i], c.pz[i], 0.6f) }
            } else {
                c.speed += (-CoasterTrack.G * c.f[i][1] - 0.3f - 0.0006f * c.speed * c.speed) * dt
                // Never slow down in the loop (it would hang upside down), and never faster than 90 blocks a second.
                c.speed = min(90f, max(c.speed, if (c.u[i][1] < 0.6f && c.f[i][1] > -0.8f) 28f else 4f))
                // Everyone screams going down the big drop and upside down in the loop.
                if (!c.screamed && c.f[i][1] < -0.8f) {
                    c.screamed = true
                    sound("scream", c.px[i], c.py[i], c.pz[i], 1f)
                    if (rider) uiEvents.add("toast:AAAAAAAHHHH!!!")
                }
                if (!c.loopScreamed && c.u[i][1] < -0.3f) {
                    c.loopScreamed = true
                    sound("scream", c.px[i], c.py[i], c.pz[i], 1f)
                    if (rider) uiEvents.add("toast:AAAAAHHH!!! UPSIDE DOWN!!!")
                }
            }
            if (c.arriving) {
                // Braking into the station.
                c.speed = max(1.5f, min(c.speed, (CoasterTrack.STOP - c.s) * 3f + 1.5f))
                if (c.s >= CoasterTrack.STOP) {
                    c.s = CoasterTrack.STOP; c.speed = 0f; c.running = false; c.arriving = false; c.wait = 6f
                    if (rider) uiEvents.add("toast:Station! Tap or crouch to get off, or stay on for another ride")
                }
            }
            c.s += c.speed * dt
            if (c.s >= c.length) { c.s -= c.length; c.arriving = true }
        }
        if (rider) {
            val k = c.index(c.s); val p = c.pos(c.s)
            val pl = player
            // Sitting in the front row: eyes about a block above the track, along its "up" (upside down in the loop).
            val eye = pl.eyeY - pl.y
            pl.x = p[0] + c.f[k][0] * 0.2f + c.u[k][0] * 1.5f
            pl.y = p[1] + c.f[k][1] * 0.2f + c.u[k][1] * 1.5f - eye
            pl.z = p[2] + c.f[k][2] * 0.2f + c.u[k][2] * 1.5f
            pl.vx = 0f; pl.vy = 0f; pl.vz = 0f
        }
    }
}

/** Tapping a coaster train in its station gets in (or, riding, tries to get out). Returns true when handled. */
internal fun Game.coasterTap(): Boolean {
    coasters.riding?.let { leaveCoaster(); return true }
    for (c in coasters.list) {
        if (c.running) continue
        val p = c.pos(c.s)
        // Is the player looking at the train?
        val ex = player.x; val ey = player.eyeY; val ez = player.z
        val vx = p[0] - ex; val vy = p[1] + 0.6f - ey; val vz = p[2] - ez
        val along = vx * dir[0] + vy * dir[1] + vz * dir[2]
        if (along < 0f || along > 6f) continue
        val ox = vx - dir[0] * along; val oy = vy - dir[1] * along; val oz = vz - dir[2] * along
        if (ox * ox + oy * oy + oz * oz > 1.6f * 1.6f) continue
        aircraft.riding = null; boats.riding = null; vehicles.riding = null; carts.riding = null
        coasters.riding = c; c.wait = 3f
        uiEvents.add("toast:Hold on tight! It leaves in 3 seconds")
        return true
    }
    return false
}

/** Gets out of the coaster, only when the train is standing in the station. */
internal fun Game.leaveCoaster() {
    val c = coasters.riding ?: return
    if (c.running) { uiEvents.add("toast:Hold on! You can get out when the train is back in the station"); return }
    coasters.riding = null
    val k = c.index(c.s); val p = c.pos(c.s)
    player.x = p[0] - c.r[k][0] * 2.2f; player.y = p[1] + 0.1f; player.z = p[2] - c.r[k][2] * 2.2f
}

// ---------------------------------------------------------------- old coasters

/** The old block-track coaster's pieces: steps forward (u), to the right (v), and height. */
private fun oldCoasterLayout(): List<IntArray> {
    fun leg(vararg parts: Pair<Char, Int>): List<Int> {
        val out = ArrayList<Int>(); var y = 0
        for ((what, n) in parts) repeat(n) { when (what) { 'u' -> y++; 'd' -> y-- }; out.add(y) }
        return out
    }
    val first = leg('f' to 7, 'u' to 11, 'f' to 3, 'd' to 11, 'f' to 3)
    val second = leg('f' to 2, 'u' to 5, 'f' to 2, 'd' to 5, 'f' to 2)
    val third = leg('f' to 2, 'u' to 3, 'f' to 1, 'd' to 3, 'f' to 2, 'u' to 3, 'f' to 1, 'd' to 3, 'f' to 2, 'u' to 2, 'f' to 1, 'd' to 2, 'f' to first.size - 25)
    val n1 = first.size; val n2 = second.size
    val cells = ArrayList<IntArray>()
    for (k in 0 until n1) cells.add(intArrayOf(k, 0, first[k]))
    cells.add(intArrayOf(n1, 0, 0))
    for (k in 0 until n2) cells.add(intArrayOf(n1, k + 1, second[k]))
    cells.add(intArrayOf(n1, n2 + 1, 0))
    for (k in 0 until n1) cells.add(intArrayOf(n1 - 1 - k, n2 + 1, third[k]))
    cells.add(intArrayOf(-1, n2 + 1, 0))
    for (k in 0 until n2) cells.add(intArrayOf(-1, n2 - k, 0))
    cells.add(intArrayOf(-1, 0, 0))
    return cells
}

/**
 * Coasters from before the steel ones (block track with a little car) are replaced by the new steel coaster in
 * the same place, facing the same way. Runs once when a world is opened.
 */
internal fun Game.upgradeOldCoasters() {
    if (isClient) return
    val old = carts.list.filter { it.kind == Cart.COASTER }
    if (old.isEmpty()) return
    var done = 0
    for (car in old) {
        val bx = floorInt(car.x); val bz = floorInt(car.z)
        world.ensureLoaded(bx shr 4, bz shr 4)
        val by = floorInt(car.y + 0.1f)
        // Coaster carts on the new coaster track are the player's own: leave them be.
        if ((-1..0).any { world.getBlock(bx, by + it, bz).let { b -> b == Blocks.COASTER_RAIL || b == Blocks.STOP_RAIL } }) continue
        val (o, h) = findOldStation(bx, by, bz) ?: continue
        carts.list.remove(car)
        if (carts.riding === car) carts.riding = null
        removeOldCoaster(o[0], o[1], o[2], h[0], h[1])
        buildCoaster(o[0] + 0.5f, o[1].toFloat(), o[2] + 0.5f, h[0], h[1])
        done++
    }
    if (done > 0) uiEvents.add("toast:Your roller coaster" + (if (done > 1) "s have" else " has") + " been upgraded to the new steel coaster with a loop!")
}

/** The old coaster station near (x, y, z): its first cell (x, y, z) and the way it faces (fx, fz), or null. */
private fun Game.findOldStation(x: Int, y: Int, z: Int): Pair<IntArray, IntArray>? {
    val n1 = 35
    for (r in 0..48) for (dy in -12..12) for (dz in -r..r) for (dx in -r..r) {
        if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) continue
        val px = x + dx; val py = y + dy; val pz = z + dz
        if (world.getBlock(px, py, pz) != Blocks.POWERED_RAIL) continue
        for ((hx, hz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            // The first of the three station rails, then two cells back is where the ride starts.
            if (world.getBlock(px - hx, py, pz - hz) == Blocks.POWERED_RAIL) continue
            if (world.getBlock(px + hx, py, pz + hz) != Blocks.POWERED_RAIL || world.getBlock(px + 2 * hx, py, pz + 2 * hz) != Blocks.POWERED_RAIL) continue
            val ox = px - 2 * hx; val oz = pz - 2 * hz
            // The first turn is at the end of the long side, straight ahead.
            world.ensureLoaded((ox + hx * n1) shr 4, (oz + hz * n1) shr 4)
            if (world.getBlock(ox + hx * n1, py, oz + hz * n1) != Blocks.RAIL) continue
            return intArrayOf(ox, py, oz) to intArrayOf(hx, hz)
        }
    }
    return null
}

/** Takes the old coaster's track, its coloured bed, pillars and platform fence away. */
private fun Game.removeOldCoaster(x: Int, y: Int, z: Int, fx: Int, fz: Int) {
    val rx = -fz; val rz = fx
    for (c in oldCoasterLayout()) {
        val px = x + fx * c[0] + rx * c[1]; val py = y + c[2]; val pz = z + fz * c[0] + rz * c[1]
        world.ensureLoaded(px shr 4, pz shr 4)
        if (com.vishucraft.game.world.Rails.isRail(world.getBlock(px, py, pz))) setBlock(px, py, pz, Blocks.AIR)
        val bed = world.getBlock(px, py - 1, pz)
        if (bed in Blocks.CONCRETE_FIRST until Blocks.CONCRETE_FIRST + 16) setBlock(px, py - 1, pz, if (c[2] == 0) Blocks.GRASS else Blocks.AIR)
        var by = py - 2
        while (by > 1 && world.getBlock(px, by, pz) == Blocks.IRON_BARS) { setBlock(px, by, pz, Blocks.AIR); by-- }
    }
    for (u in 0..6) {
        val px = x + fx * u - rx * 2; val pz = z + fz * u - rz * 2
        if (world.getBlock(px, y, pz) == Blocks.OAK_FENCE) setBlock(px, y, pz, Blocks.AIR)
    }
}
