package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.World
import com.vishucraft.game.world.floorInt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Airplanes and helicopters. Airplanes fly themselves from wherever they stand to the airport you pick:
 * a take-off run, a climb, a cruise high above the land, an approach and a landing on the runway.
 * Helicopters are flown by the rider and can take off and land anywhere.
 */

/** An airport: where planes land (the start of its runway) and which way the runway points. */
class Airport(val name: String, val x: Float, val y: Float, val z: Float, val dx: Float, val dz: Float)

class Aircraft(var x: Float, var y: Float, var z: Float, val kind: Int) {
    var yaw = 0f
    var speed = 0f
    var vy = 0f
    /** Rotor / propeller angle (for drawing). */
    var spin = 0f
    var phase = PARKED
    var dest: Airport? = null

    val isPlane get() = kind == PLANE
    /** Half the length of the body (for tapping it). */
    val half get() = if (isPlane) 4f else 2.2f

    companion object {
        const val PLANE = 0
        const val HELICOPTER = 1

        const val PARKED = 0
        const val TAKEOFF = 1
        const val CRUISE = 2
        const val APPROACH = 3
        const val ROLLOUT = 4

        const val CRUISE_SPEED = 42f
        fun itemName(kind: Int) = if (kind == PLANE) "Airplane" else "Helicopter"
    }
}

class Aircrafts(private val world: World) {
    val list = ArrayList<Aircraft>()
    val airports = ArrayList<Airport>()
    var riding: Aircraft? = null

    private fun solid(x: Float, y: Float, z: Float) = Blocks.solid[world.getBlock(floorInt(x), floorInt(y), floorInt(z))]

    /** The airport nearest to a point (within [range] blocks). */
    fun nearestAirport(x: Float, z: Float, range: Float = 60f): Airport? =
        airports.minByOrNull { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) }
            ?.takeIf { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) < range * range }

    fun update(dt: Float, game: Game) {
        for (a in list) {
            a.spin += dt * (if (a.isPlane) a.speed * 0.8f else if (riding === a || a.y > groundBelow(a) + 0.2f) 30f else 0f)
            if (a.isPlane) fly(a, dt, game) else hover(a, dt, game)
        }
        riding?.let { a ->
            val p = game.player
            // Sit in the cockpit, a little towards the nose.
            val fx = sin(a.yaw); val fz = -cos(a.yaw)
            val forward = if (a.isPlane) 2.5f else 0.6f
            p.x = a.x + fx * forward; p.z = a.z + fz * forward; p.y = a.y + (if (a.isPlane) 0.4f else 0.1f)
            p.vx = 0f; p.vy = 0f; p.vz = 0f
        }
    }

    private fun groundBelow(a: Aircraft): Float {
        var y = floorInt(a.y + 0.5f)
        while (y > 0 && !Blocks.solid[world.getBlock(floorInt(a.x), y - 1, floorInt(a.z))]) y--
        return y.toFloat()
    }

    /** The helicopter: the rider steers where they look, jump climbs and crouch descends. */
    private fun hover(a: Aircraft, dt: Float, game: Game) {
        val ridden = riding === a
        val input = game.input
        if (ridden) {
            a.yaw = game.player.yaw
            val fx = sin(a.yaw); val fz = -cos(a.yaw)
            val rx = cos(a.yaw); val rz = sin(a.yaw)
            val airborne = a.y > groundBelow(a) + 0.05f
            val move = if (airborne || input.jumpHeld) 14f else 0f
            val wantX = (fx * input.moveForward + rx * input.moveStrafe) * move
            val wantZ = (fz * input.moveForward + rz * input.moveStrafe) * move
            a.speed = sqrt(wantX * wantX + wantZ * wantZ)
            val nx = a.x + wantX * dt
            val nz = a.z + wantZ * dt
            if (!solid(nx, a.y + 0.5f, a.z) && !solid(nx, a.y + 1.5f, a.z)) a.x = nx
            if (!solid(a.x, a.y + 0.5f, nz) && !solid(a.x, a.y + 1.5f, nz)) a.z = nz
            val wantVy = when { input.jumpHeld -> 7f; input.descendHeld -> -6f; else -> 0f }
            a.vy += (wantVy - a.vy) * minOf(1f, dt * 4f)
        } else {
            // Nobody at the controls: it settles gently to the ground.
            a.vy = maxOf(-4f, a.vy - 6f * dt)
        }
        var ny = a.y + a.vy * dt
        if (ny > Chunk.HEIGHT - 3) { ny = Chunk.HEIGHT - 3f; a.vy = 0f }
        if (a.vy < 0f && solid(a.x, ny, a.z)) { ny = floorInt(ny) + 1f; a.vy = 0f }
        if (a.vy > 0f && solid(a.x, ny + 2.2f, a.z)) { a.vy = 0f; ny = a.y }
        a.y = ny
    }

    /** Starts a flight to [dest]. */
    fun takeOff(a: Aircraft, dest: Airport, game: Game) {
        a.dest = dest
        a.phase = Aircraft.TAKEOFF
        a.speed = 0f
        game.sound("train_horn", a.x, a.y, a.z, 0.5f)
        game.uiEvents.add("toast:Flight to ${dest.name}. Fasten your seat belt!")
    }

    /** The airplane's autopilot. */
    private fun fly(a: Aircraft, dt: Float, game: Game) {
        val dest = a.dest
        if (a.phase == Aircraft.PARKED || dest == null) {
            a.speed = maxOf(0f, a.speed - 10f * dt)
            // Parked planes sit on the ground.
            val g = groundBelow(a)
            if (a.y > g + 0.01f) a.y = maxOf(g, a.y - 8f * dt)
            return
        }
        val tx = dest.x - a.x; val tz = dest.z - a.z
        val dist = sqrt(tx * tx + tz * tz)
        val cruiseY = (Chunk.HEIGHT - 8).toFloat()
        fun turnTowards(targetYaw: Float, rate: Float) {
            var d = targetYaw - a.yaw
            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            a.yaw += d.coerceIn(-rate * dt, rate * dt)
        }
        when (a.phase) {
            Aircraft.TAKEOFF -> {
                a.speed = minOf(Aircraft.CRUISE_SPEED, a.speed + 7f * dt)
                if (a.speed > 16f) a.vy = 7f
                if (a.y > groundBelow(a) + 12f) turnTowards(atan2(tx, -tz), 0.6f)
                if (a.y >= cruiseY - 1f) a.phase = Aircraft.CRUISE
            }
            Aircraft.CRUISE -> {
                a.vy = (cruiseY - a.y).coerceIn(-6f, 6f)
                a.speed = minOf(Aircraft.CRUISE_SPEED, a.speed + 5f * dt)
                // Head for a point out in front of the runway, so the plane arrives lined up with it.
                val px = dest.x - dest.dx * 120f; val pz = dest.z - dest.dz * 120f
                turnTowards(atan2(px - a.x, -(pz - a.z)), 0.5f)
                val pd = sqrt((px - a.x) * (px - a.x) + (pz - a.z) * (pz - a.z))
                if (pd < 40f || dist < 130f) a.phase = Aircraft.APPROACH
            }
            Aircraft.APPROACH -> {
                a.speed = maxOf(18f, minOf(a.speed, 12f + dist * 0.15f))
                turnTowards(atan2(tx, -tz), 1.2f)
                // Glide down to the runway: the height left shrinks with the distance left.
                val want = dest.y + (dist / 120f).coerceIn(0f, 1f) * (cruiseY - dest.y)
                a.vy = ((want - a.y) * 1.5f).coerceIn(-12f, 4f)
                if (dist < 2.5f + a.speed * dt * 2f) {
                    a.phase = Aircraft.ROLLOUT
                    // Touch down on the runway's centre line, pointing along it.
                    a.yaw = atan2(dest.dx, -dest.dz)
                    a.x = dest.x; a.z = dest.z
                    a.y = dest.y; a.vy = 0f
                    game.sound("hit_stone", a.x, a.y, a.z)
                }
            }
            Aircraft.ROLLOUT -> {
                a.y = dest.y; a.vy = 0f
                a.speed = maxOf(0f, a.speed - 9f * dt)
                if (a.speed == 0f) {
                    a.phase = Aircraft.PARKED
                    a.dest = null
                    if (riding === a) game.uiEvents.add("toast:Welcome to ${dest.name}! Tap to get out")
                }
            }
        }
        a.x += sin(a.yaw) * a.speed * dt; a.z += -cos(a.yaw) * a.speed * dt
        a.y = (a.y + a.vy * dt).coerceIn(1f, Chunk.HEIGHT - 3f)
    }

    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): Aircraft? {
        var t = 0f
        while (t < maxDist) {
            val px = ox + dx * t; val py = oy + dy * t; val pz = oz + dz * t
            list.firstOrNull {
                val fx = sin(it.yaw); val fz = -cos(it.yaw)
                val rx = px - it.x; val rz = pz - it.z
                val along = abs(rx * fx + rz * fz); val across = abs(rx * fz - rz * fx)
                val wide = if (it.isPlane) (if (along < 1f) 4.5f else 0.9f) else 1.2f
                along < it.half && across < wide && py >= it.y - 0.2f && py <= it.y + 2.6f
            }?.let { return it }
            t += 0.15f
        }
        return null
    }

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(list.size)
        for (a in list) { d.writeByte(a.kind); d.writeFloat(a.x); d.writeFloat(a.y); d.writeFloat(a.z); d.writeFloat(a.yaw) }
        d.writeInt(airports.size)
        for (p in airports) { d.writeUTF(p.name); d.writeFloat(p.x); d.writeFloat(p.y); d.writeFloat(p.z); d.writeFloat(p.dx); d.writeFloat(p.dz) }
    }

    fun read(d: java.io.DataInputStream) {
        repeat(d.readInt()) {
            val kind = d.readByte().toInt()
            list.add(Aircraft(d.readFloat(), d.readFloat(), d.readFloat(), kind).also { it.yaw = d.readFloat() })
        }
        repeat(d.readInt()) { airports.add(Airport(d.readUTF(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat())) }
    }
}

/**
 * Taps involving aircraft: getting in and out, picking one up with a sword or axe, and placing them.
 * Returns true when handled.
 */
internal fun Game.aircraftUse(item: com.vishucraft.game.world.ItemDef?, t: RayHit?): Boolean {
    aircraft.riding?.let { a ->
        val landed = if (a.isPlane) a.phase == Aircraft.PARKED else Blocks.solid[world.getBlock(floorInt(a.x), floorInt(a.y - 0.1f), floorInt(a.z))]
        if (!landed) {
            uiEvents.add(if (a.isPlane) "toast:Enjoy the flight! You can get out after landing" else "toast:Land first: hold crouch (down) to go down")
            return true
        }
        aircraft.riding = null
        val rx = cos(a.yaw); val rz = sin(a.yaw)
        player.x = a.x + rx * (if (a.isPlane) 2f else 1.8f); player.z = a.z + rz * (if (a.isPlane) 2f else 1.8f); player.y = a.y + 0.2f
        return true
    }
    aircraft.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 6f)?.let { a ->
        if (item?.tool == com.vishucraft.game.world.ToolType.SWORD || item?.tool == com.vishucraft.game.world.ToolType.AXE) {
            aircraft.list.remove(a)
            if (survival) drops.spawn(ItemStack(Items.find(Aircraft.itemName(a.kind))), a.x, a.y + 0.5f, a.z)
            return true
        }
        aircraft.riding = a
        carts.riding = null; boats.riding = null
        if (a.isPlane) {
            val here = aircraft.nearestAirport(a.x, a.z)
            val choices = aircraft.airports.filter { it !== here }
            if (choices.isEmpty()) uiEvents.add("toast:Build another airport (Blueprint: Airport) to fly there")
            else uiEvents.add("flights:" + choices.joinToString("|") { it.name })
        } else uiEvents.add("toast:Helicopter: look where to go and move; jump to go up, crouch to go down. Tap to get out after landing")
        return true
    }
    if ((item?.name == "Airplane" || item?.name == "Helicopter") && t != null) {
        val kind = if (item.name == "Airplane") Aircraft.PLANE else Aircraft.HELICOPTER
        val a = Aircraft(t.x + 0.5f, (if (t.ny == 1) t.y + 1 else t.y).toFloat(), t.z + 0.5f, kind).also { it.yaw = player.yaw }
        aircraft.list.add(a)
        consumeHeld()
        sound("hit_stone", a.x, a.y, a.z, 0.6f)
        return true
    }
    return false
}

/** The rider picked a destination from the flights list. */
fun Game.flyTo(airportName: String) {
    val a = aircraft.riding ?: return
    if (!a.isPlane || a.phase != Aircraft.PARKED) return
    val dest = aircraft.airports.firstOrNull { it.name == airportName } ?: return
    aircraft.takeOff(a, dest, this)
}
