package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.RedstoneIds
import com.vishucraft.game.world.World
import com.vishucraft.game.world.floorInt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Cars and buses. On their own they drive along roads, keeping to the left lane (the lane one block left of the
 * centre of a five-wide road): straight on, turning at junctions now and then and where a road ends, keeping
 * their distance from the vehicle (or person) in front; buses stop at bus stops. Tap one to get in and drive it
 * yourself (look to steer, forward and back for speed); tap again to get out.
 */

class Vehicle(var x: Float, var y: Float, var z: Float, val kind: Int, val color: Int) {
    var yaw = 0f
    /** Heading along the road grid (one of the four directions). */
    var hx = 0; var hz = -1
    var speed = 0f
    var stopTimer = 0f
    var lastStop = Long.MIN_VALUE
    /** The road cell the vehicle last made a decision in. */
    var cell = Long.MIN_VALUE
    /** The turn chosen at this junction: [NONE], [STRAIGHT] or [RIGHT] (left turns happen straight away). */
    var plan = 0
    /** Road cells driven since the last turn (no turning again straight away unless the road ends). */
    var sinceTurn = 0
    /** Seconds spent waiting behind something; negative while squeezing past after a long wait, so traffic never locks up. */
    var waited = 0f

    val isBus get() = kind == BUS
    val half get() = if (isBus) 3f else 1.5f

    companion object {
        const val CAR = 0
        const val BUS = 1
        const val NONE = 0
        const val STRAIGHT = 1
        const val RIGHT = 2
        fun itemName(kind: Int) = if (kind == BUS) "Bus" else "Car"
    }
}

class Vehicles(private val world: World) {
    val list = ArrayList<Vehicle>()
    var riding: Vehicle? = null
    private val rnd = java.util.Random()

    fun isRoad(id: Int) = id == Blocks.ROAD || id == Blocks.ROAD_MARKING || id == Blocks.ZEBRA_CROSSING
    private fun roadAt(x: Int, y: Int, z: Int) = isRoad(world.getBlock(x, y, z))

    fun update(dt: Float, game: Game) {
        for (v in list) {
            if (!world.isLoaded(floorInt(v.x), floorInt(v.z))) continue
            if (riding === v) drive(v, dt, game) else auto(v, dt, game)
        }
        riding?.let { v ->
            val p = game.player
            // Seated low inside, looking out through the windscreen.
            p.x = v.x; p.z = v.z; p.y = v.y + if (v.isBus) 0.6f else -0.25f
            p.vx = 0f; p.vy = 0f; p.vz = 0f
        }
    }

    /** Driving by the player: look to steer, forward to go, back to brake and reverse. */
    private fun drive(v: Vehicle, dt: Float, game: Game) {
        val max = if (v.isBus) 10f else 16f
        val throttle = game.input.moveForward
        // Turn smoothly towards where the driver looks.
        var d = game.player.yaw - v.yaw
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        v.yaw += d.coerceIn(-2.5f * dt, 2.5f * dt) * (if (abs(v.speed) > 0.3f) 1f else 0.5f)
        v.speed = when {
            throttle > 0f -> minOf(max, v.speed + 8f * throttle * dt)
            throttle < 0f -> maxOf(-4f, v.speed + 12f * throttle * dt)
            else -> v.speed * (1f - minOf(1f, dt * 1.5f))
        }
        move(v, sin(v.yaw) * v.speed * dt, -cos(v.yaw) * v.speed * dt)
    }

    /** Moves with the ground: up a one-block step, down slopes, stopped by walls. */
    private fun move(v: Vehicle, dx: Float, dz: Float) {
        val nx = v.x + dx; val nz = v.z + dz
        val bx = floorInt(nx); val bz = floorInt(nz); val by = floorInt(v.y + 0.01f)
        var ground = Int.MIN_VALUE
        for (y in by + 1 downTo by - 3) if (Blocks.solid[world.getBlock(bx, y, bz)]) { ground = y + 1; break }
        if (ground == Int.MIN_VALUE) { v.x = nx; v.z = nz; v.y -= 0.5f; return }
        if (ground - v.y > 1.01f || Blocks.solid[world.getBlock(bx, ground + 1, bz)]) { v.speed = 0f; return }
        v.x = nx; v.z = nz; v.y = ground.toFloat()
    }

    /** The traffic: follow the road, keep a gap, turn at junctions, and (buses) stop at bus stops. */
    private fun auto(v: Vehicle, dt: Float, game: Game) {
        val gy = floorInt(v.y + 0.01f) - 1
        val bx = floorInt(v.x); val bz = floorInt(v.z)
        if (!roadAt(bx, gy, bz)) { v.speed = maxOf(0f, v.speed - 8f * dt); return }
        if (v.stopTimer > 0f) { v.stopTimer -= dt; v.speed = 0f; return }
        // Decide once per cell, near its middle.
        val key = RedstoneIds.pack(bx, gy, bz)
        val cx = bx + 0.5f; val cz = bz + 0.5f
        if (key != v.cell && (v.x - cx) * v.hx + (v.z - cz) * v.hz >= -0.05f) {
            v.cell = key
            // Keep to the middle of the lane.
            if (v.hx != 0) v.z = cz else v.x = cx
            v.sinceTurn++
            val hx = v.hx; val hz = v.hz
            val rx = -hz; val rz = hx
            /** Road [side] blocks to the right (negative: left) and [ahead] blocks ahead. */
            fun road(side: Int, ahead: Int) = roadAt(bx + rx * side + hx * ahead, gy, bz + rz * side + hz * ahead)
            fun turn(nx: Int, nz: Int) { v.hx = nx; v.hz = nz; v.x = cx; v.z = cz; v.plan = Vehicle.NONE; v.sinceTurn = 0 }
            val free = v.sinceTurn > 3
            when {
                // One row before the middle of a road on the left: the place to turn left.
                road(-3, 0) && road(-3, -1) && !road(-3, -2) -> {
                    val onward = road(0, 4)
                    val rightToo = road(4, 2)
                    val pick = rnd.nextInt(4)
                    when {
                        !onward && (!rightToo || pick < 2) -> turn(-rx, -rz)
                        !onward -> v.plan = Vehicle.RIGHT
                        free && pick == 0 -> turn(-rx, -rz)
                        free && pick == 1 && rightToo -> v.plan = Vehicle.RIGHT
                        else -> v.plan = Vehicle.STRAIGHT
                    }
                }
                // One row past the middle of a road on the right: the place to turn right.
                road(4, 0) && road(4, 1) && !road(4, 2) -> {
                    val onward = road(0, 2)
                    if (v.plan == Vehicle.RIGHT || !onward || (v.plan == Vehicle.NONE && free && rnd.nextInt(4) == 0)) turn(rx, rz)
                    else v.plan = Vehicle.NONE
                }
                // The end of the road: turn round into the other lane.
                !road(0, 1) -> when {
                    road(1, 0) && road(2, 0) && road(3, 0) -> { turn(-hx, -hz); v.x += rx * 2; v.z += rz * 2 }
                    road(1, 0) -> turn(rx, rz)
                    road(-1, 0) -> turn(-rx, -rz)
                    else -> turn(-hx, -hz)
                }
            }
            // Buses stop for a few seconds next to a bus stop.
            if (v.isBus) for (s in -2..2) for (t in -2..2) {
                val sx = bx + s; val sz = bz + t
                for (dy in 0..2) if (world.getBlock(sx, gy + dy, sz) == Blocks.BUS_STOP) {
                    val stop = RedstoneIds.pack(sx, gy + dy, sz)
                    if (stop != v.lastStop) { v.lastStop = stop; v.stopTimer = 5f; game.sound("beep", v.x, v.y, v.z, 0.5f) }
                }
            }
        }
        // Keep a gap to anything in front: other vehicles, and people crossing.
        fun ahead(x: Float, y: Float, z: Float, half: Float): Boolean {
            if (abs(y - v.y) >= 2f) return false
            val ox = x - v.x; val oz = z - v.z
            val along = ox * v.hx + oz * v.hz; val across = abs(ox * v.hz - oz * v.hx)
            return along > 0f && along < v.half + half + 1.5f && across < 1.2f
        }
        val p = game.player
        // After waiting 4 seconds (vehicles stuck nose to nose in a junction) it creeps past for a moment.
        if (v.waited < 0f) v.waited = minOf(0f, v.waited + dt)
        val blocked = v.waited >= 0f && (list.any { o -> o !== v && ahead(o.x, o.y, o.z, o.half) } ||
            (riding == null && ahead(p.x, p.y, p.z, 0.3f)) || game.mobs.list.any { ahead(it.x, it.y, it.z, 0.4f) })
        if (blocked) { v.waited += dt; if (v.waited > 4f) v.waited = -1.5f } else if (v.waited > 0f) v.waited = 0f
        val target = if (blocked) 0f else if (v.isBus) 6f else 8f
        v.speed = if (v.speed < target) minOf(target, v.speed + 4f * dt) else maxOf(target, v.speed - 12f * dt)
        v.x += v.hx * v.speed * dt; v.z += v.hz * v.speed * dt
        // Turn the body smoothly towards the heading.
        val want = atan2(v.hx.toFloat(), -v.hz.toFloat())
        var d = want - v.yaw
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        v.yaw += d.coerceIn(-5f * dt, 5f * dt)
        v.y = gy + 1f
    }

    fun raycast(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float, maxDist: Float): Vehicle? {
        var t = 0f
        while (t < maxDist) {
            val px = ox + dx * t; val py = oy + dy * t; val pz = oz + dz * t
            list.firstOrNull {
                val fx = sin(it.yaw); val fz = -cos(it.yaw)
                val rx = px - it.x; val rz = pz - it.z
                abs(rx * fx + rz * fz) < it.half && abs(rx * fz - rz * fx) < (if (it.isBus) 1.3f else 1f) && py >= it.y - 0.2f && py <= it.y + (if (it.isBus) 3.2f else 1.9f)
            }?.let { return it }
            t += 0.15f
        }
        return null
    }

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(list.size)
        for (v in list) { d.writeByte(v.kind); d.writeByte(v.color); d.writeFloat(v.x); d.writeFloat(v.y); d.writeFloat(v.z); d.writeFloat(v.yaw); d.writeByte(v.hx + 1); d.writeByte(v.hz + 1) }
    }

    fun read(d: java.io.DataInputStream) {
        repeat(d.readInt()) {
            val kind = d.readByte().toInt(); val color = d.readByte().toInt()
            list.add(Vehicle(d.readFloat(), d.readFloat(), d.readFloat(), kind, color).also { v ->
                v.yaw = d.readFloat(); v.hx = d.readByte() - 1; v.hz = d.readByte() - 1
            })
        }
    }
}

/** Getting in and out of cars and buses, picking them up, and placing them. Returns true when handled. */
/** Gets out of the car or bus (onto the driver's side), which then drives itself again. */
internal fun Game.leaveVehicle() {
    val v = vehicles.riding ?: return
    vehicles.riding = null
    v.speed = 0f
    val rx = cos(v.yaw); val rz = sin(v.yaw)
    player.x = v.x - rx * (if (v.isBus) 2f else 1.6f); player.z = v.z - rz * (if (v.isBus) 2f else 1.6f); player.y = v.y + 0.1f
    // Back to driving itself, lined up with the road it faces.
    val fx = sin(v.yaw); val fz = -cos(v.yaw)
    if (abs(fx) > abs(fz)) { v.hx = if (fx > 0) 1 else -1; v.hz = 0 } else { v.hx = 0; v.hz = if (fz > 0) 1 else -1 }
    v.cell = Long.MIN_VALUE
}

internal fun Game.vehicleUse(item: com.vishucraft.game.world.ItemDef?, t: RayHit?): Boolean {
    if (vehicles.riding != null) { leaveVehicle(); return true }
    vehicles.raycast(player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], 5f)?.let { v ->
        if (item?.tool == com.vishucraft.game.world.ToolType.SWORD || item?.tool == com.vishucraft.game.world.ToolType.AXE) {
            vehicles.list.remove(v)
            if (survival) drops.spawn(ItemStack(Items.find(Vehicle.itemName(v.kind))), v.x, v.y + 0.5f, v.z)
            return true
        }
        vehicles.riding = v
        carts.riding = null; boats.riding = null; aircraft.riding = null
        player.yaw = v.yaw
        uiEvents.add("toast:Driving the ${if (v.isBus) "bus" else "car"}: look to steer, forward to go, back to brake. Tap or crouch to get out")
        return true
    }
    if ((item?.name == "Car" || item?.name == "Bus") && t != null) {
        val kind = if (item.name == "Bus") Vehicle.BUS else Vehicle.CAR
        val v = Vehicle(t.x + 0.5f, (if (t.ny == 1) t.y + 1 else t.y).toFloat(), t.z + 0.5f, kind, java.util.Random().nextInt(16))
        val fx = sin(player.yaw); val fz = -cos(player.yaw)
        if (abs(fx) > abs(fz)) v.hx = if (fx > 0) 1 else -1 else { v.hx = 0; v.hz = if (fz > 0) 1 else -1 }
        if (v.hx != 0) v.hz = 0
        v.yaw = atan2(v.hx.toFloat(), -v.hz.toFloat())
        vehicles.list.add(v)
        consumeHeld()
        return true
    }
    return false
}
