package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import kotlin.math.abs

/*
 * Metro lines that join up by themselves: every metro station (and city metro line) remembers the open ends of
 * its track. When a new one is built, its nearest end is joined to the nearest open end of another station by a
 * new line, however far apart they are: across valleys on a viaduct with pillars, through hills in a tunnel, with
 * ramps where the two are at different heights.
 */

/** An open end of a metro track: the last rail at (x, y, z), with the track carrying on outwards towards (dx, dz). */
class TrackEnd(val x: Int, val y: Int, val z: Int, val dx: Int, val dz: Int, val group: Int, var joined: Boolean = false)

class MetroNetwork {
    val ends = ArrayList<TrackEnd>()
    private var nextGroup = 1

    fun newGroup() = nextGroup++

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(nextGroup)
        d.writeInt(ends.size)
        for (e in ends) { d.writeInt(e.x); d.writeInt(e.y); d.writeInt(e.z); d.writeByte(e.dx + 1); d.writeByte(e.dz + 1); d.writeInt(e.group); d.writeBoolean(e.joined) }
    }

    fun read(d: java.io.DataInputStream) {
        nextGroup = d.readInt()
        repeat(d.readInt()) {
            ends.add(TrackEnd(d.readInt(), d.readInt(), d.readInt(), d.readByte() - 1, d.readByte() - 1, d.readInt(), d.readBoolean()))
        }
    }
}

/**
 * Adds a station's (or a city line's) track ends to the network and joins the nearest of them to the nearest open
 * end of an earlier station. Returns the length of the new line in blocks, or 0 when there was nothing to join.
 */
internal fun Game.joinMetro(newEnds: List<TrackEnd>): Int {
    val net = metroNet
    val open = net.ends.filter { !it.joined }
    net.ends.addAll(newEnds)
    var best: Pair<TrackEnd, TrackEnd>? = null
    var bestD = Long.MAX_VALUE
    for (a in newEnds) for (b in open) {
        val d = (a.x - b.x).toLong() * (a.x - b.x) + (a.z - b.z).toLong() * (a.z - b.z)
        if (d < bestD) { bestD = d; best = a to b }
    }
    val (a, b) = best ?: return 0
    val n = buildLine(a, b)
    a.joined = true; b.joined = true
    return n
}

/** Lays track from end [a] to end [b]. Returns the number of rails laid. */
private fun Game.buildLine(a: TrackEnd, b: TrackEnd): Int {
    // Out from each end in a straight line first (long enough at b for a ramp to b's height), then across.
    val rise = b.y - a.y
    val outA = 3
    val outB = abs(rise) + 4
    val ax = a.x + a.dx * outA; val az = a.z + a.dz * outA
    val bx = b.x + b.dx * outB; val bz = b.z + b.dz * outB
    val cells = ArrayList<IntArray>()
    fun add(x: Int, z: Int) { val l = cells.lastOrNull(); if (l == null || l[0] != x || l[1] != z) cells.add(intArrayOf(x, z)) }
    fun walk(x0: Int, z0: Int, x1: Int, z1: Int) {
        // A straight run from (x0, z0) to (x1, z1) along one axis.
        if (x0 != x1) for (x in if (x1 > x0) x0..x1 else x0 downTo x1) add(x, z0)
        else for (z in if (z1 > z0) z0..z1 else z0 downTo z1) add(x0, z)
    }
    walk(a.x + a.dx, a.z + a.dz, ax, az)
    // Across: first along a's direction if that heads towards b, otherwise sideways first.
    val alongX = a.dx != 0
    val ahead = if (alongX) (bx - ax) * a.dx >= 0 else (bz - az) * a.dz >= 0
    if (alongX == ahead) { walk(ax, az, bx, az); walk(bx, az, bx, bz) }
    else { walk(ax, az, ax, bz); walk(ax, bz, bx, bz) }
    walk(bx, bz, b.x + b.dx, b.z + b.dz)

    // Heights: level with a, then up or down one block per rail over the last straight stretch into b.
    // Counting back from b: the rail next to b is one block nearer a's height, and so on until level with a.
    val n = cells.size
    val ys = IntArray(n) { k -> b.y - Integer.signum(rise) * minOf(n - k, abs(rise)) }
    val deck = Blocks.CONCRETE_FIRST + 8
    for ((k, c) in cells.withIndex()) {
        val x = c[0]; val z = c[1]; val y = ys[k]
        if (y < 2 || y >= Chunk.HEIGHT - 4) continue
        world.ensureLoaded(x shr 4, z shr 4)
        // A tunnel through anything in the way, and a deck underneath.
        for (h in 0..3) if (world.getBlock(x, y + h, z) != Blocks.AIR) setBlock(x, y + h, z, Blocks.AIR)
        if (!Blocks.solid[world.getBlock(x, y - 1, z)] || world.getBlock(x, y - 1, z) == Blocks.WATER) setBlock(x, y - 1, z, deck)
        // Pillars down to the ground every 8 rails where the line is up in the air.
        if (k % 8 == 0) {
            var h = y - 2
            while (h > 1 && y - h < 64 && !Blocks.solid[world.getBlock(x, h, z)]) { setBlock(x, h, z, deck); h-- }
        }
        setBlock(x, y, z, Blocks.RAIL, 0)
    }
    // Shape the rails into straights, curves and slopes, including the two old track ends.
    for (c in cells.withIndex()) updateRail(c.value[0], ys[c.index], c.value[1])
    updateRail(a.x, a.y, a.z); updateRail(b.x, b.y, b.z)
    for (c in cells.withIndex()) updateRail(c.value[0], ys[c.index], c.value[1])
    return cells.size
}
