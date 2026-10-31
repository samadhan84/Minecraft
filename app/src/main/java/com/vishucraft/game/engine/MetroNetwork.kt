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
 * Metro lines have two tracks, 2 blocks apart: trains keep to the left one, so one track carries trains one way
 * and the other track the other way. At the end of the line a train crosses over to the other track to go back.
 *
 * A line can be any length, so it is not built all at once: the route is worked out straight away and the track
 * is laid one chunk at a time, as each piece of ground along it is loaded (while you travel, or as a train gets
 * there). Trains wait at the edge of ground that is still loading.
 */

/**
 * An open end of a metro track: the last rail at (x, y, z), with the track carrying on outwards towards (dx, dz).
 * A two-track end has its second track at (x + ox, z + oz), 2 blocks to one side; (0, 0) for a single track.
 * [kind] is [METRO] or [RAILWAY]: metro stations join metro stations, railway stations join railway stations.
 */
class TrackEnd(val x: Int, val y: Int, val z: Int, val dx: Int, val dz: Int, val group: Int, var joined: Boolean = false,
               var ox: Int = 0, var oz: Int = 0, var kind: Int = METRO) {
    val double get() = ox != 0 || oz != 0
    /** The end of the other track. */
    fun twin() = TrackEnd(x + ox, y, z + oz, dx, dz, group, joined, -ox, -oz, kind)

    companion object {
        const val METRO = 0
        const val RAILWAY = 1
        /** Bullet train lines: they dive deep and run in a glass tube under the sea (and through the rock under land). */
        const val BULLET = 2
    }
}

/** Which side of direction (dx, dz) the offset (ox, oz) points to: 1 for the right, -1 for the left, 0 for neither. */
internal fun sideOf(ox: Int, oz: Int, dx: Int, dz: Int) = Integer.signum(-ox * dz + oz * dx)

/**
 * The route of a line from end A to end B: straight runs between the corner points [pts] (x0, z0, x1, z1, ...),
 * level with A ([ya]) and then sloping one block per rail to B's height ([yb]) over the last stretch.
 * [ends] holds A's and B's own rails (ax, ay, az, bx, by, bz), which get re-shaped as the line reaches them.
 */
class MetroLine(val pts: IntArray, val ya: Int, val yb: Int, val ends: IntArray) {
    /** Chunks (by [Chunk.key]) where this line's track has already been laid. */
    val laid = HashSet<Long>()
    /** Built in full when it was made (lines from older versions). */
    var complete = false
    /** [TrackEnd.METRO] (on a concrete viaduct) or [TrackEnd.RAILWAY] (on ballast, with stone pillars). */
    var kind = TrackEnd.METRO
    /**
     * For a line that leaves the stations' height: the height it runs at, or 0. Below the stations it is a tunnel
     * (a bullet line under the sea); above them a high viaduct (a line going over a city).
     */
    var deep = 0
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
        // A deep line slopes down from A at one block per rail, runs level at its tunnel height, and climbs to B;
        // a high one climbs, runs level up there and comes down again.
        if (deep > 0) return if (deep <= minOf(ya, yb)) maxOf(deep, ya - k, yb - (size - k)) else minOf(deep, ya + k, yb + (size - k))
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

    /**
     * The second track: this line moved 2 blocks to its right ([side] 1) or left (-1) all the way along, from
     * A's other track to B's other track. Null if the route turns too tightly for a track alongside it.
     */
    fun twin(side: Int): MetroLine? {
        // Corner points without repeats or straight-through points.
        val p = ArrayList<IntArray>()
        for (i in 0..segs) {
            val q = intArrayOf(pts[i * 2], pts[i * 2 + 1])
            if (p.isNotEmpty() && p.last()[0] == q[0] && p.last()[1] == q[1]) continue
            if (p.size >= 2) {
                val a = p[p.size - 2]; val b = p.last()
                val d1x = Integer.signum(b[0] - a[0]); val d1z = Integer.signum(b[1] - a[1])
                val d2x = Integer.signum(q[0] - b[0]); val d2z = Integer.signum(q[1] - b[1])
                if (d1x == d2x && d1z == d2z) p.removeAt(p.size - 1)
            }
            p.add(q)
        }
        if (p.size < 2) return null
        val n = p.size - 1
        // Each run's direction and the offset to its side.
        val ux = IntArray(n) { Integer.signum(p[it + 1][0] - p[it][0]) }
        val uz = IntArray(n) { Integer.signum(p[it + 1][1] - p[it][1]) }
        val nx = IntArray(n) { -uz[it] * side * 2 }; val nz = IntArray(n) { ux[it] * side * 2 }
        val out = IntArray((n + 1) * 2)
        for (i in 0..n) {
            var x = p[i][0]; var z = p[i][1]
            if (i > 0) { x += nx[i - 1]; z += nz[i - 1] }
            if (i < n && (i == 0 || ux[i] != ux[i - 1] || uz[i] != uz[i - 1])) { x += nx[i]; z += nz[i] }
            out[i * 2] = x; out[i * 2 + 1] = z
        }
        // Every run must still go the same way (a run on the inside of a tight bend can vanish, but not reverse).
        for (i in 0 until n) {
            val dx = out[i * 2 + 2] - out[i * 2]; val dz = out[i * 2 + 3] - out[i * 2 + 1]
            if (dx * ux[i] < 0 || dz * uz[i] < 0 || (ux[i] == 0 && dx != 0) || (uz[i] == 0 && dz != 0)) return null
        }
        val e = ends
        val twinEnds = intArrayOf(e[0] + nx[0], e[1], e[2] + nz[0], e[3] + nx[n - 1], e[4], e[5] + nz[n - 1])
        return MetroLine(out, ya, yb, twinEnds).also { it.kind = kind; it.deep = deep }
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
    /**
     * Where the stations are (x, y, z: the middle of the platforms, and the kind: [TrackEnd.METRO] or
     * [TrackEnd.RAILWAY]); their names are on their signs.
     */
    val stations = ArrayList<IntArray>()
    /** 2 once stations and lines have two tracks (older saves are brought up to date when loaded); 3 with railways. */
    var version = VERSION
    private var nextGroup = 1
    /** Older trains have been given their back driving cars this session. */
    var cabsChecked = false

    fun newGroup() = nextGroup++

    fun write(d: java.io.DataOutputStream) {
        d.writeInt(nextGroup)
        d.writeInt(ends.size)
        for (e in ends) { d.writeInt(e.x); d.writeInt(e.y); d.writeInt(e.z); d.writeByte(e.dx + 1); d.writeByte(e.dz + 1); d.writeInt(e.group); d.writeBoolean(e.joined) }
        d.writeInt(lines.size)
        for (l in lines) l.write(d)
        d.writeInt(upgraded.size)
        for (g in upgraded) d.writeInt(g)
        // Version 2: second tracks and the list of stations.
        d.writeInt(if (version < 2) version else VERSION)
        for (e in ends) { d.writeByte(e.ox + 2); d.writeByte(e.oz + 2) }
        for (l in lines) d.writeBoolean(l.complete)
        d.writeInt(stations.size)
        for (s in stations) { d.writeInt(s[0]); d.writeInt(s[1]); d.writeInt(s[2]) }
        // Version 3: railways.
        for (e in ends) d.writeByte(e.kind)
        for (l in lines) d.writeByte(l.kind)
        for (s in stations) d.writeByte(s[3])
        // Version 5: deep (bullet train) lines.
        for (l in lines) d.writeInt(l.deep)
    }

    fun read(d: java.io.DataInputStream) {
        version = 0
        nextGroup = d.readInt()
        repeat(d.readInt()) {
            ends.add(TrackEnd(d.readInt(), d.readInt(), d.readInt(), d.readByte() - 1, d.readByte() - 1, d.readInt(), d.readBoolean()))
        }
        // Older saves stop at one of these points.
        try {
            val n = d.readInt()
            repeat(n) { lines.add(MetroLine.read(d)) }
            repeat(d.readInt()) { upgraded.add(d.readInt()) }
            version = d.readInt()
            for (e in ends) { e.ox = d.readByte() - 2; e.oz = d.readByte() - 2 }
            for (l in lines) l.complete = d.readBoolean()
            repeat(d.readInt()) { stations.add(intArrayOf(d.readInt(), d.readInt(), d.readInt(), TrackEnd.METRO)) }
            if (version >= 3) {
                for (e in ends) e.kind = d.readByte().toInt()
                for (l in lines) l.kind = d.readByte().toInt()
                for (s in stations) s[3] = d.readByte().toInt()
            }
            if (version >= 5) for (l in lines) l.deep = d.readInt()
        } catch (_: java.io.EOFException) {}
    }

    companion object {
        /** 3: railways; 4: every station has a train call button; 5: bullet train lines under the sea. */
        const val VERSION = 5
    }
}

/**
 * Adds a station's (or a city line's) track ends to the network and joins the nearest of them to the nearest open
 * end of an earlier station, with two tracks when both have two. Returns the length of the new line in blocks,
 * or 0 when there was nothing to join.
 */
internal fun Game.joinMetro(newEnds: List<TrackEnd>): Int {
    val net = metroNet
    val kind = newEnds.first().kind
    val open = net.ends.filter { !it.joined && it.kind == kind }
    net.ends.addAll(newEnds)
    var best: Pair<TrackEnd, TrackEnd>? = null
    var bestD = Long.MAX_VALUE
    for (a in newEnds) for (b in open) {
        val d = (a.x - b.x).toLong() * (a.x - b.x) + (a.z - b.z).toLong() * (a.z - b.z)
        if (d < bestD) { bestD = d; best = a to b }
    }
    val (a, b) = best ?: return 0
    a.joined = true; b.joined = true
    val new = ArrayList<MetroLine>()
    if (a.double && b.double) {
        // The second track leaves from a's other track, on side s of the line; the line goes to whichever of b's
        // tracks has b's other track on that same side as the trains arrive.
        val s = sideOf(a.ox, a.oz, a.dx, a.dz)
        val to = if (sideOf(b.ox, b.oz, -b.dx, -b.dz) == s) b else b.twin()
        val main = planFor(a, to, kind)
        main.kind = kind
        new.add(main)
        main.twin(s)?.let { new.add(it) }
    } else new.add(planFor(a, b, kind).also { it.kind = kind })
    for (l in new) addLine(l)
    return new[0].size
}

/** Adds a line and lays it wherever the ground is already loaded; the rest follows as more is loaded. */
private fun Game.addLine(line: MetroLine) {
    metroNet.lines.add(line)
    for (key in world.chunks.keys.toList()) layLine(line, (key shr 32).toInt(), key.toInt())
}

/**
 * The route between two ends: a bullet line goes under the sea when there is water on the way; any line that would
 * cross a city goes up over it on a high viaduct instead of through its buildings.
 */
private fun Game.planFor(a: TrackEnd, b: TrackEnd, kind: Int): MetroLine {
    val plain = planLine(a, b, 5)
    if (kind == TrackEnd.BULLET && waterOnTheWay(plain)) return planDeep(a, b)
    val city = citiesOnTheWay(plain)
    if (city > 0) {
        val t = maxOf(city, a.y, b.y)
        return planLine(a, b, t - a.y + 5, t - b.y + 5).also { it.deep = t }
    }
    return plain
}

/** Whether there is sea, a lake or a river under the route (from the terrain, and from what is loaded). */
private fun Game.waterOnTheWay(l: MetroLine): Boolean {
    val sea = com.vishucraft.game.world.TerrainGenerator.SEA_LEVEL
    for (k in 0 until l.size step 3) {
        val c = l.cell(k)
        if (world.generator.surfaceHeight(c[0], c[1]) < sea - 1) return true
        if (world.isLoaded(c[0], c[1])) {
            var y = world.columnHeight(c[0], c[1]) - 1
            while (y > 1 && world.getBlock(c[0], y, c[1]) == Blocks.AIR) y--
            if (world.getBlock(c[0], y, c[1]) == Blocks.WATER) return true
        }
    }
    return false
}

/**
 * If the route crosses a city, the height to fly over it at (above its tallest towers); otherwise 0. Cities are
 * found from their metro lines' track ends (see [siteOf]).
 */
private fun Game.citiesOnTheWay(l: MetroLine): Int {
    var high = 0
    for ((_, ends) in metroNet.ends.filter { it.kind == TrackEnd.METRO }.groupBy { it.group }) {
        val s = siteOf(ends) ?: continue
        if (s.city == 0) continue
        val half = s.city * 12
        val far = 3 + s.city * 24 + 2 + 11
        // The city's footprint, from its plan corners.
        val xs = intArrayOf(s.x(-half - 1, 1), s.x(half + 1, far)); val zs = intArrayOf(s.z(-half - 1, 1), s.z(half + 1, far))
        val x0 = xs.min(); val x1 = xs.max(); val z0 = zs.min(); val z1 = zs.max()
        for (k in 0 until l.size) {
            val c = l.cell(k)
            if (c[0] in x0..x1 && c[1] in z0..z1) { high = maxOf(high, s.oy + 58); break }
        }
    }
    return high
}

/**
 * A bullet train route: straight out of each station for long enough to slope down to its tunnel, deep under
 * the sea, and level along the bottom.
 */
private fun planDeep(a: TrackEnd, b: TrackEnd): MetroLine {
    val t = minOf(com.vishucraft.game.world.TerrainGenerator.SEA_LEVEL - 12, a.y - 4, b.y - 4).coerceAtLeast(6)
    return planLine(a, b, a.y - t + 5, b.y - t + 5).also { it.deep = t }
}

/** Works out the route from end [a] to end [b], going straight out of [a] for [outA] blocks first. */
private fun planLine(a: TrackEnd, b: TrackEnd, outA: Int, outBDeep: Int = -1): MetroLine {
    // Out from each end in a straight line first (long enough at b for a ramp to b's height), then across.
    val rise = b.y - a.y
    val outB = if (outBDeep > 0) outBDeep else abs(rise) + 4
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
    if (line.complete) return false
    val key = Chunk.key(cx, cz)
    if (key in line.laid || world.getChunk(cx, cz) == null) return false
    val rails = ArrayList<Int>()
    line.railsIn(cx, cz) { rails.add(it) }
    if (rails.isEmpty()) return false
    line.laid.add(key)
    val railway = line.kind == TrackEnd.RAILWAY
    val deck = if (railway) Blocks.RAILWAY_BALLAST else Blocks.CONCRETE_FIRST + 8
    val pillar = if (railway) Blocks.STONE_BRICKS else deck
    for (k in rails) {
        val (x, z) = line.cell(k).let { it[0] to it[1] }
        val y = line.y(k)
        if (y < 2 || y >= Chunk.HEIGHT - 4) continue
        if (line.deep > 0) { tube(line, k, x, y, z); continue }
        // A tunnel through anything in the way (except the other track's rails), and a deck underneath.
        for (h in 0..3) world.getBlock(x, y + h, z).let { if (it != Blocks.AIR && !com.vishucraft.game.world.Rails.isRail(it)) setBlock(x, y + h, z, Blocks.AIR) }
        if (!Blocks.solid[world.getBlock(x, y - 1, z)] || world.getBlock(x, y - 1, z) == Blocks.WATER) setBlock(x, y - 1, z, deck)
        // Pillars down to the ground every 8 rails where the line is up in the air.
        if (k % 8 == 0) {
            var h = y - 2
            while (h > 1 && y - h < 64 && !Blocks.solid[world.getBlock(x, h, z)]) { setBlock(x, h, z, pillar); h-- }
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

/**
 * One rail of a deep line, in a glass tube: room for the train inside, and glass all round wherever there is
 * water, air or anything loose (so the sea stays out and you can see it), rock left as it is.
 */
private fun Game.tube(line: MetroLine, k: Int, x: Int, y: Int, z: Int) {
    val isRail = { id: Int -> com.vishucraft.game.world.Rails.isRail(id) }
    fun seal(px: Int, py: Int, pz: Int) {
        val id = world.getBlock(px, py, pz)
        if (isRail(id) || id == Blocks.GLASS) return
        if (!Blocks.solid[id] || id == Blocks.WATER || Blocks.isLiquid(id)) setBlock(px, py, pz, Blocks.GLASS)
    }
    // The rails before and after this one are part of the tube, not its walls.
    val prev = if (k > 0) line.cell(k - 1) else intArrayOf(line.ends[0], line.ends[2])
    val next = if (k < line.size - 1) line.cell(k + 1) else intArrayOf(line.ends[3], line.ends[5])
    for (h in 0..2) world.getBlock(x, y + h, z).let { if (it != Blocks.AIR && !isRail(it)) setBlock(x, y + h, z, Blocks.AIR) }
    for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
        val nx = x + dx; val nz = z + dz
        if ((nx == prev[0] && nz == prev[1]) || (nx == next[0] && nz == next[1])) continue
        // The other track's rail, two blocks over, is never next to this one; anything else beside it is wall.
        for (h in -1..3) seal(nx, y + h, nz)
    }
    seal(x, y - 1, z)
    seal(x, y + 3, z)
    setBlock(x, y, z, Blocks.RAIL, 0)
}

/** Lays any metro track due in newly loaded chunks (and brings older saves' metro up to date first). */
internal fun Game.layMetro() {
    if (!isClient && metroNet.version < 2) upgradeMetro()
    if (!isClient && metroNet.version < 4) {
        // Older stations get their train call buttons.
        metroNet.version = MetroNetwork.VERSION
        for (s in metroNet.stations) { world.ensureLoaded(s[0] shr 4, s[2] shr 4); placeCallButton(s[0], s[1], s[2]) }
    }
    val lines = metroNet.lines
    if (!metroNet.cabsChecked && !isClient) { metroNet.cabsChecked = true; addRearCabs() }
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

// ---------------------------------------------------------------- stations and the non-stop service

/** The name of the station at [s] (x, y, z), from its sign or billboard. */
internal fun Game.metroStationName(s: IntArray): String = world.blockEntities.nameNear(s[0], s[1], s[2], 14) ?: "Station"

/** Getting on a metro or bullet train: offer the stations it can run to non-stop. */
internal fun Game.offerMetroStations(c: Cart, line: Int? = null) {
    val power = carts.power(c) ?: return
    // The line of the station the train is at (a bullet train can run on metro tracks or on its own line).
    val near = metroNet.stations.minByOrNull { (it[0] - c.x) * (it[0] - c.x) + (it[2] - c.z) * (it[2] - c.z) }
        ?.takeIf { (it[0] - c.x) * (it[0] - c.x) + (it[2] - c.z) * (it[2] - c.z) < 40f * 40f }
    val kind = line ?: near?.get(3) ?: if (power.isMetroLike) TrackEnd.METRO else TrackEnd.RAILWAY
    val here = c.x to c.z
    val choices = metroNet.stations.filter { it[3] == kind }.filter { (it[0] - here.first) * (it[0] - here.first) + (it[2] - here.second) * (it[2] - here.second) > 30f * 30f }
    if (choices.isEmpty()) return
    uiEvents.add("metro:" + choices.joinToString("|") { metroStationName(it) })
}

/** The rider picked a station to go to non-stop, or "" to stop at every station. */
fun Game.metroTo(name: String) {
    val c = carts.riding ?: return
    val h = carts.power(c) ?: return
    val head = carts.head(c)
    if (name.isEmpty()) { head.dest = null; uiEvents.add("toast:Stopping at every station"); return }
    val kind = if (h.isMetroLike) TrackEnd.METRO else TrackEnd.RAILWAY
    val s = metroNet.stations.firstOrNull { it[3] == kind && metroStationName(it) == name }
        ?: metroNet.stations.firstOrNull { metroStationName(it) == name } ?: return
    head.dest = s
    head.stopTimer = minOf(head.stopTimer, 1f)
    uiEvents.add("toast:Non-stop to $name")
}

// ---------------------------------------------------------------- bringing older metros up to date

/** What a group of track ends belongs to: a metro station or a city line, with its building plan. */
private class Site(val city: Int, val ox: Int, val oy: Int, val oz: Int, val fx: Int, val fz: Int, val a: TrackEnd, val b: TrackEnd) {
    val rx = -fz; val rz = fx
    fun x(a: Int, d: Int) = ox + rx * a + fx * d
    fun z(a: Int, d: Int) = oz + rz * a + fz * d
}

/** Finds the station or city that a group's two track ends belong to, from how far apart they are. */
private fun siteOf(ends: List<TrackEnd>): Site? {
    if (ends.size != 2) return null
    val (e0, e1) = ends
    // b is the end the other one points at: the ends point away from each other.
    val (a, b) = if (e1.dx * (e1.x - e0.x) + e1.dz * (e1.z - e0.z) > 0) e0 to e1 else e1 to e0
    val len = abs(b.x - a.x) + abs(b.z - a.z)
    if (a.y != b.y || a.dx != -b.dx || a.dz != -b.dz || b.x - a.x != b.dx * len || b.z - a.z != b.dz * len) return null
    val fx = b.dz; val fz = -b.dx
    val n = when (len) { 48 -> 0; 100 -> 4; 148 -> 6; 196 -> 8; else -> return null }
    // Stations: track at row 4, ends 24 either side. Cities: metro over row mr, near track at mr - 1, 11 up.
    val half = if (n == 0) 24 else n * 12 + 2
    val row = if (n == 0) 4 else 3 + n / 2 * 24 - 1
    val mx = a.x + b.dx * half; val mz = a.z + b.dz * half
    return Site(n, mx - fx * row, if (n == 0) a.y else a.y - 11, mz - fz * row, fx, fz, a, b)
}

/**
 * Saves from before metros had two tracks: every station gets its second track (on whichever side lets its lines
 * run alongside without crossing), every line between two-track stations and cities gets a second line beside it,
 * and the stations are listed for the non-stop service.
 */
private fun Game.upgradeMetro() {
    val net = metroNet
    net.version = 2
    val groups = net.ends.groupBy { it.group }
    val sites = HashMap<Int, Site>()
    for ((g, e) in groups) siteOf(e)?.let { sites[g] = it }
    // Cities already have two tracks (the far one 2 blocks further away from where the city was built from).
    for ((_, s) in sites) if (s.city > 0) for (e in listOf(s.a, s.b)) { e.ox = s.fx * 2; e.oz = s.fz * 2 }

    // Lines from before lines were kept: work out which ends were joined, the same way it happened.
    fun covered(e: TrackEnd) = net.lines.any { l -> (l.ends[0] == e.x && l.ends[1] == e.y && l.ends[2] == e.z) || (l.ends[3] == e.x && l.ends[4] == e.y && l.ends[5] == e.z) }
    if (net.ends.any { it.joined && !covered(it) }) {
        val sim = HashSet<TrackEnd>()
        val seen = ArrayList<TrackEnd>()
        for (g in groups.keys.sorted()) {
            val mine = groups.getValue(g)
            val open = seen.filter { it !in sim }
            var best: Pair<TrackEnd, TrackEnd>? = null; var bestD = Long.MAX_VALUE
            for (a in mine) for (b in open) {
                val d = (a.x - b.x).toLong() * (a.x - b.x) + (a.z - b.z).toLong() * (a.z - b.z)
                if (d < bestD) { bestD = d; best = a to b }
            }
            seen.addAll(mine)
            val (a, b) = best ?: continue
            sim.add(a); sim.add(b)
            if (a.joined && b.joined && !covered(a) && !covered(b)) net.lines.add(planLine(a, b, 3).also { it.complete = true })
        }
    }

    // Which station end each line joins.
    fun endAt(x: Int, y: Int, z: Int) = net.ends.firstOrNull { it.x == x && it.y == y && it.z == z }
    val links = net.lines.toList().mapNotNull { l -> val a = endAt(l.ends[0], l.ends[1], l.ends[2]); val b = endAt(l.ends[3], l.ends[4], l.ends[5]); if (a != null && b != null) Triple(l, a, b) else null }
    // Each station's second track goes on the side its lines need: start from the cities (whose side is fixed) and
    // work along the lines; a line between two fixed sides that don't match stays single track.
    val sigma = HashMap<Int, Int>()
    for ((g, s) in sites) if (s.city > 0) sigma[g] = 1
    fun stationSide(e: TrackEnd, s: Site, want: Int) = want * sideOf(s.fx, s.fz, e.dx, e.dz)
    var changed = true
    while (changed) {
        changed = false
        for ((_, a, b) in links) for ((from, to) in listOf(a to b, b to a)) {
            val fs = sites[from.group] ?: continue; val ts = sites[to.group] ?: continue
            if (from.group !in sigma || to.group in sigma || ts.city > 0) continue
            val o = sigma.getValue(from.group)
            val need = -sideOf(fs.fx * o, fs.fz * o, from.dx, from.dz)
            sigma[to.group] = stationSide(to, ts, need)
            changed = true
        }
        if (!changed) sites.keys.firstOrNull { it !in sigma && sites.getValue(it).city == 0 }?.let { sigma[it] = 1; changed = true }
    }
    for ((g, s) in sites) {
        val o = sigma[g] ?: continue
        for (e in listOf(s.a, s.b)) { e.ox = s.fx * 2 * o; e.oz = s.fz * 2 * o }
        if (s.city == 0) {
            val name = world.blockEntities.nameNear(s.x(0, 4), s.oy + 1, s.z(0, 4), 14) ?: "Station"
            rebuildStation(s.ox, s.oy, s.oz, s.fx, s.fz, o, name)
            net.stations.add(intArrayOf(s.x(0, 4 + o), s.oy, s.z(0, 4 + o), TrackEnd.METRO))
        } else {
            val n = s.city; val half = n * 12; val mr = 3 + n / 2 * 24
            for (sa in listOfNotNull(-half + 12, half - 12, if (n >= 6) 12 else null)) net.stations.add(intArrayOf(s.x(sa, mr), s.oy + 11, s.z(sa, mr), TrackEnd.METRO))
        }
    }
    // The second track beside each line whose two ends now have matching second tracks.
    for ((l, a, b) in links) {
        if (!a.double || !b.double) continue
        val sa = sideOf(a.ox, a.oz, a.dx, a.dz)
        if (sideOf(b.ox, b.oz, -b.dx, -b.dz) != sa) continue
        l.twin(sa)?.let { addLine(it) }
    }
}

/** Whether the rail at (x, y, z) is part of a metro, railway or bullet line, or of a station's tracks. */
internal fun Game.isLineRail(x: Int, y: Int, z: Int): Boolean {
    if (!com.vishucraft.game.world.Rails.isRail(world.getBlock(x, y, z))) return false
    for (s in metroNet.stations) if (abs(s[0] - x) <= 36 && abs(s[2] - z) <= 36 && abs(s[1] - y) <= 2) return true
    for (l in metroNet.lines) {
        var hit = false
        l.railsIn(x shr 4, z shr 4) { k -> if (!hit) { val c = l.cell(k); if (c[0] == x && c[1] == z && l.y(k) == y) hit = true } }
        if (hit) return true
    }
    return false
}

/** Placing a rail next to a line's track would join onto it: metro lines keep just their two tracks. */
internal fun Game.wouldJoinLine(x: Int, y: Int, z: Int): Boolean {
    for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) for (dy in -1..1) if (isLineRail(x + dx, y + dy, z + dz)) return true
    return false
}
