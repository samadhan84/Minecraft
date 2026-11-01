package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.floorInt
import kotlin.math.cos
import kotlin.math.sin

/*
 * People travel too. When a metro, bullet train or railway train stops at a station, city people waiting nearby
 * get on, ride one to three stations and get off onto the platform. When an airplane is about to leave an
 * airport, people in and around the terminal board it and get off at the next airport. New travellers keep
 * arriving at the stations and airports near the player.
 */

/** Which trains were standing at a station and which planes were parked, last frame. */
internal class Travel {
    val stopped = HashSet<Cart>()
    val parked = HashSet<Aircraft>()
    val boarded = HashSet<Aircraft>()
    val rnd = java.util.Random()
    var arrivals = 5f
}

internal fun Game.updatePassengers(dt: Float = 0f) {
    if (isClient) return
    val t = travel
    t.arrivals -= dt
    if (t.arrivals <= 0f) { t.arrivals = 12f; newTravellers() }
    // Travellers sit in their car or plane, two by two.
    val seats = HashMap<Any, Int>()
    for (m in mobs.list) {
        val v = m.ride ?: continue
        if (m.dead) { m.ride = null; continue }
        val seat = seats.merge(v, 1, Int::plus)!! - 1
        val side = if (seat % 2 == 0) -0.45f else 0.45f
        when (v) {
            is Cart -> {
                if (v !in carts.list) { m.ride = null; continue }
                val along = ((seat / 2) % 3 - 1) * 0.8f
                m.x = v.x + v.hx * along - v.hz * side; m.z = v.z + v.hz * along + v.hx * side; m.y = v.y + 0.1f; m.yaw = v.yaw
            }
            is Aircraft -> {
                if (v !in aircraft.list) { m.ride = null; continue }
                val fx = sin(v.yaw); val fz = -cos(v.yaw)
                val along = 1.2f - (seat / 2) * 0.9f
                m.x = v.x + fx * along - fz * side; m.z = v.z + fz * along + fx * side; m.y = v.y + 0.3f; m.yaw = v.yaw
            }
            else -> { m.ride = null; continue }
        }
        m.vx = 0f; m.vy = 0f; m.vz = 0f; m.moving = false
    }

    // Trains: each time one stops at a station, some get off and some get on.
    val heads = carts.list.filter { it.leader == null && it.stopTimer > 0f && carts.power(it) != null }
    for (h in heads) if (h !in t.stopped) {
        val cars = carts.chain(h)
        val off = HashSet<Mob>()
        for (m in mobs.list) if (m.ride is Cart && cars.any { it === m.ride }) {
            if (--m.rideStops <= 0) { getOff(m, m.ride as Cart); off.add(m) }
        }
        if (h.private) continue   // a called train carries only the player
        val seatsFor = cars.filter { it.kind == Cart.COACH }.ifEmpty { cars }
        var aboard = mobs.list.count { m -> cars.any { it === m.ride } }
        for (m in waiting(h.x, h.y, h.z, 14f)) {
            if (m in off) continue
            if (aboard >= seatsFor.size * 6 || t.rnd.nextInt(2) == 0) continue
            m.ride = seatsFor.minByOrNull { c -> mobs.list.count { it.ride === c } }
            m.rideStops = 1 + t.rnd.nextInt(3)
            aboard++
        }
    }
    t.stopped.clear(); t.stopped.addAll(heads)

    // Planes: everyone gets off on arrival; people board shortly before the next flight leaves.
    val parked = aircraft.list.filter { it.isPlane && it.phase == Aircraft.PARKED }
    for (a in parked) {
        if (a !in t.parked) for (m in mobs.list) if (m.ride === a) getOffPlane(m, a)
        if (a !in t.boarded && a.wait < 12f && aircraft.airports.size >= 2 && aircraft.nearestAirport(a.x, a.z, 100f) != null) {
            t.boarded.add(a)
            var aboard = mobs.list.count { it.ride === a }
            for (m in waiting(a.x, a.y, a.z, 40f)) {
                if (aboard >= 8 || t.rnd.nextInt(3) == 0) continue
                m.ride = a; m.rideStops = 1; aboard++
            }
        }
    }
    t.boarded.retainAll(parked.toSet())
    t.parked.clear(); t.parked.addAll(parked)
}

/** City people near (x, y, z) who could get on. */
private fun Game.waiting(x: Float, y: Float, z: Float, range: Float) = mobs.list.filter {
    it.type.isCitizen && it.ride == null && !it.dead && !it.leashed && it.customName == null &&
        kotlin.math.abs(it.y - y) < 14f && (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) < range * range
}

/** Steps off a train onto whichever side is clear (the platform). */
private fun Game.getOff(m: Mob, c: Cart) {
    m.ride = null
    val y = floorInt(c.y + 0.1f)
    for (d in floatArrayOf(2f, -2f, 3f, -3f)) {
        val x = c.x - c.hz * d; val z = c.z + c.hx * d
        val bx = floorInt(x); val bz = floorInt(z)
        if (!Blocks.solid[world.getBlock(bx, y, bz)] && !Blocks.solid[world.getBlock(bx, y + 1, bz)] && Blocks.solid[world.getBlock(bx, y - 1, bz)]) {
            m.x = x; m.y = y.toFloat(); m.z = z; return
        }
    }
    m.x = c.x; m.y = c.y + 2.5f; m.z = c.z
}

/** Steps out beside the plane, clear of its wings. */
private fun getOffPlane(m: Mob, a: Aircraft) {
    m.ride = null
    val rx = cos(a.yaw); val rz = sin(a.yaw)
    val side = if (m.uid % 2 == 0) 6f else -6f
    m.x = a.x + rx * side; m.z = a.z + rz * side; m.y = a.y + 0.5f
}

/** A new traveller turns up at each station and airport near the player that has only a few people waiting. */
private fun Game.newTravellers() {
    if (!PEOPLE) return
    val folk = listOf(MobType.CITIZEN, MobType.CITIZEN_WOMAN, MobType.CITIZEN_KID)
    val near = mobs.list.count { it.type.isCitizen && (it.x - player.x) * (it.x - player.x) + (it.z - player.z) * (it.z - player.z) < 100f * 100f }
    if (near >= 80) return
    val spots = metroNet.stations.map { Triple(it[0], it[1], it[2]) } +
        aircraft.airports.map { Triple(floorInt(it.x), floorInt(it.y), floorInt(it.z)) }
    for ((x, y, z) in spots) {
        val dx = x - player.x; val dz = z - player.z
        if (dx * dx + dz * dz > 80f * 80f || !world.isLoaded(x, z)) continue
        if (waiting(x + 0.5f, y.toFloat(), z + 0.5f, 16f).size >= 6) continue
        val spot = standingSpot(x, y, z) ?: continue
        mobs.list.add(Mob(folk[travel.rnd.nextInt(folk.size)], spot[0] + 0.5f, spot[1].toFloat(), spot[2] + 0.5f).also { it.yaw = travel.rnd.nextFloat() * 6.28f })
    }
}

/** A free place to stand (on a platform or the ground) within a few blocks of (x, y, z), picked at random. */
private fun Game.standingSpot(x: Int, y: Int, z: Int): IntArray? {
    repeat(20) {
        val bx = x + travel.rnd.nextInt(9) - 4; val bz = z + travel.rnd.nextInt(9) - 4
        for (by in y..y + 1) {
            val below = world.getBlock(bx, by - 1, bz)
            if (world.getBlock(bx, by, bz) == Blocks.AIR && world.getBlock(bx, by + 1, bz) == Blocks.AIR &&
                Blocks.solid[below] && !com.vishucraft.game.world.Rails.isRail(below) && below != Blocks.RAILWAY_BALLAST) return intArrayOf(bx, by, bz)
        }
    }
    return null
}
