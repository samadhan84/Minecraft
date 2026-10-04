package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import kotlin.math.abs

/*
 * Metro lines that join up by themselves: every metro station (and city metro line) remembers the open ends of
 * its track. When a new one is built, its nearest end is joined to the nearest open end of another station by a
 * new line, however far apart they are: across valleys on a viaduct with pillars, through hills in a tunnel, with
 * ramps where the two are at different heights.
 *
 * A line can be any length, so it is not built all at once: the route is worked out straight away and the track
 * is laid one chunk at a time, as each piece of ground along it is loaded (while you travel, or as a train gets
 * there). Trains wait at the edge of ground that is still loading.
 */

/** An open end of a metro track: the last rail at (x, y, z), with the track carrying on outwards towards (dx, dz). */
class TrackEnd(val x: Int, val y: Int, val z: Int, val dx: Int, val dz: Int, val group: Int, var joined: Boolean = false)

/**
 * The route of a line from end A to end B: straight runs between the corner points [pts] (x0, z0, x1, z1, ...),
 * level with A ([ya]) and then sloping one block per rail to B's height ([yb]) over the last stretch.
 * [ends] holds A's and B's own rails (ax, ay, az, bx, by, bz), which get re-shaped as the line reaches them.
 */
class MetroLine(val pts: IntArray, val ya: Int, val yb: Int, val ends: IntArray) {
    /** Chunks (by [Chunk.key]) where this line's track has already been laid. */
    val laid = HashSet<Long>()
    private val segs = pts.size / 2 - 1
    /** Index of the first rail of each straight run; the last entry is the number of rails. */
    private val start = IntArray(segs + 1)

    init {
        var k = 0
        for (i in 0 until segs) {
            start[i] = k
            k += len(i) + if (i == 0) 1 else 0
        }
        start[segs] = k
    }

    val size get() = start[segs]
    private fun len(i: Int) = abs(pts[i * 2 + 2] - pts[i * 2]) + abs(pts[i * 2 + 3] - pts[i * 2 + 1])
    private fun sx(i: Int) = Integer.signum(pts[i * 2 + 2] - pts[i * 2])
    private fun sz(i: Int) = Integer.signum(pts[i * 2 + 3] - pts[i * 2 + 1])
    /** Steps along run [i] of its first rail (the corner it starts from is the previous run's last rail). */
    private fun t0(i: Int) = if (i == 0) 0 else 1

    /** Where rail [k] is: (x, z). */
    fun cell(k: Int): IntArray {
        var i = 0
        while (i < segs - 1 && k >= start[i + 1]) i++
        val t = k - start[i] + t0(i)
        return intArrayOf(pts[i * 2] + sx(i) * t, pts[i * 2 + 1] + sz(i) * t)
    }

    /** The height of rail [k]: counting back from B, each rail is one block nearer A's height until level with it. */
    fun y(k: Int): Int {
        val rise = yb - ya
        return yb - Integer.signum(rise) * minOf(size - k, abs(rise))
    }

    /** Calls [f] with the index of every rail inside chunk (cx, cz). */
    fun railsIn(cx: Int, cz: Int, f: (Int) -> Unit) {
        val x0 = cx * 16; val z0 = cz * 16
        for (i in 0 until segs) {
            val px = pts[i * 2]; val pz = pts[i * 2 + 1]
            val dx = sx(i); val dz = sz(i)
            var lo = t0(i); var hi = len(i)
            if (lo > hi) continue
            // Clip the run's steps t to the chunk on each axis.
            fun clip(p: Int, d: Int, c0: Int) {
                if (d == 0) { if (p < c0 || p > c0 + 15) hi = lo - 1; return }
                val a = (c0 - p) * d; val b = (c0 + 15 - p) * d
                lo = maxOf(lo, minOf(a, b)); hi = minOf(hi, maxOf(a, b))
            }
            clip(px, dx, x0); clip(pz, dz, z0)
            for (t in lo..hi) f(start[i] + t - t0(i))
        }
    }

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(pts.size); for (v in pts) d.writeInt(v)
        d.writeInt(ya); d.writeInt(yb)
        for (v in ends) d.writeInt(v)
        d.writeInt(laid.size); for (k in laid) d.writeLong(k)
    }

    companion object {
        fun read(d: java.io.DataInputStream): MetroLine {
            val pts = IntArray(d.readInt()) { d.readInt() }
            val ya = d.readInt(); val yb = d.readInt()
            val ends = IntArray(6) { d.readInt() }
            return MetroLine(pts, ya, yb, ends).also { l -> repeat(d.readInt()) { l.laid.add(d.readLong()) } }
        }
    }
}

class MetroNetwork {
    val ends = ArrayList<TrackEnd>()
    val lines = ArrayList<MetroLine>()
    /** City metro lines (by group) whose cities were given 66- to 100-floor towers, to be put back to 12 floors. */
    val upgraded = HashSet<Int>()
    private var nextGroup = 1

    fun newGroup() = nextGroup++

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(nextGroup)
        d.writeInt(ends.size)
        for (e in ends) { d.writeInt(e.x); d.writeInt(e.y); d.writeInt(e.z); d.writeByte(e.dx + 1); d.writeByte(e.dz + 1); d.writeInt(e.group); d.writeBoolean(e.joined) }
        d.writeInt(lines.size)
        for (l in lines) l.write(d)
        d.writeInt(upgraded.size)
        for (g in upgraded) d.writeInt(g)
    }

    fun read(d: java.io.DataInputStream) {
        nextGroup = d.readInt()
        repeat(d.readInt()) {
            ends.add(TrackEnd(d.readInt(), d.readInt(), d.readInt(), d.readByte() - 1, d.readByte() - 1, d.readInt(), d.readBoolean()))
        }
        // Saves from before lines were laid chunk by chunk stop here (their lines were built in full).
        try {
            val n = d.readInt()
            repeat(n) { lines.add(MetroLine.read(d)) }
            repeat(d.readInt()) { upgraded.add(d.readInt()) }
        } catch (_: java.io.EOFException) {}
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
    val line = planLine(a, b)
    a.joined = true; b.joined = true
    net.lines.add(line)
    // Lay it wherever the ground is already loaded; the rest follows as more is loaded.
    for (key in world.chunks.keys.toList()) layLine(line, (key shr 32).toInt(), key.toInt())
    return line.size
}

/** Works out the route from end [a] to end [b]. */
private fun planLine(a: TrackEnd, b: TrackEnd): MetroLine {
    // Out from each end in a straight line first (long enough at b for a ramp to b's height), then across.
    val rise = b.y - a.y
    val outA = 3
    val outB = abs(rise) + 4
    val ax = a.x + a.dx * outA; val az = a.z + a.dz * outA
    val bx = b.x + b.dx * outB; val bz = b.z + b.dz * outB
    val pts = ArrayList<Int>()
    fun point(x: Int, z: Int) { pts.add(x); pts.add(z) }
    point(a.x + a.dx, a.z + a.dz)
    point(ax, az)
    // Across: first along a's direction if that heads towards b, otherwise sideways first.
    val alongX = a.dx != 0
    val ahead = if (alongX) (bx - ax) * a.dx >= 0 else (bz - az) * a.dz >= 0
    if (alongX == ahead) point(bx, az) else point(ax, bz)
    point(bx, bz)
    point(b.x + b.dx, b.z + b.dz)
    return MetroLine(pts.toIntArray(), a.y, b.y, intArrayOf(a.x, a.y, a.z, b.x, b.y, b.z))
}

/** Lays [line]'s track inside chunk (cx, cz) if it runs through there, it is loaded, and it has not been laid yet. */
private fun Game.layLine(line: MetroLine, cx: Int, cz: Int): Boolean {
    val key = Chunk.key(cx, cz)
    if (key in line.laid || world.getChunk(cx, cz) == null) return false
    val rails = ArrayList<Int>()
    line.railsIn(cx, cz) { rails.add(it) }
    if (rails.isEmpty()) return false
    line.laid.add(key)
    val deck = Blocks.CONCRETE_FIRST + 8
    for (k in rails) {
        val (x, z) = line.cell(k).let { it[0] to it[1] }
        val y = line.y(k)
        if (y < 2 || y >= Chunk.HEIGHT - 4) continue
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
    // Shape the rails into straights, curves and slopes, including the ones next to this chunk's stretch
    // (already laid in a neighbouring chunk, or a station's own track end).
    val shape = LinkedHashSet<Int>()
    for (k in rails) for (j in k - 1..k + 1) shape.add(j)
    fun reshape(k: Int) {
        val e = line.ends
        when {
            k < 0 -> updateRail(e[0], e[1], e[2])
            k >= line.size -> updateRail(e[3], e[4], e[5])
            else -> { val c = line.cell(k); updateRail(c[0], line.y(k), c[1]) }
        }
    }
    for (k in shape) reshape(k)
    for (k in shape) reshape(k)
    return true
}

/** Lays any metro track due in newly loaded chunks. */
internal fun Game.layMetro() {
    val lines = metroNet.lines
    while (true) {
        val key = world.arrived.poll() ?: break
        if (isClient) continue
        for (l in lines) layLine(l, (key shr 32).toInt(), key.toInt())
    }
}

/** A train is about to enter the block at (x, z): make sure any line through there has its track down. */
internal fun Game.metroCatchUp(x: Int, z: Int) {
    if (isClient) return
    for (l in metroNet.lines) layLine(l, x shr 4, z shr 4)
}
