package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Rails
import com.vishucraft.game.world.floorInt

/*
 * A roller coaster ride. Tap the ground with a Roller Coaster and the whole ride is set up there: a station with
 * a platform, a tall lift hill, a big drop, a second hill, a run of bumps on the way back, and four turns, all on
 * coloured track standing on pillars, with a coaster car waiting at the station. Get in and it sets off; it
 * crawls up the hills, races down the drops and comes back round to the station, round and round until you get
 * out. An empty car waits at the station.
 */

/** Roller coaster physics, used by [Carts] for cars of kind [Cart.COASTER]. */
internal object CoasterRide {
    /** How hard the slopes pull (stronger than for a minecart, so the drops feel fast). */
    const val PULL = 13f
    /** The car never goes slower than this out on the track (the chain pulls it up the hills). */
    const val CRAWL = 3.2f
    /** Speed it leaves the station at. */
    const val LAUNCH = 7f
}

/** Where each piece of the ride goes, in steps forward (u) and to the right (v), and how high (y). */
internal fun coasterLayout(): List<IntArray> {
    // Heights along each side of the ride: flat runs, hills up and down (a flat piece between every slope change).
    fun leg(vararg parts: Pair<Char, Int>): List<Int> {
        val out = ArrayList<Int>(); var y = 0
        for ((what, n) in parts) repeat(n) { when (what) { 'u' -> y++; 'd' -> y-- }; out.add(y) }
        check(y == 0)
        return out
    }
    val first = leg('f' to 7, 'u' to 11, 'f' to 3, 'd' to 11, 'f' to 3)                       // the lift hill and the big drop
    val second = leg('f' to 2, 'u' to 5, 'f' to 2, 'd' to 5, 'f' to 2)                        // the second hill
    val third = leg('f' to 2, 'u' to 3, 'f' to 1, 'd' to 3, 'f' to 2, 'u' to 3, 'f' to 1, 'd' to 3, 'f' to 2,
        'u' to 2, 'f' to 1, 'd' to 2, 'f' to first.size - 25)                                   // bumps on the way back
    val fourth = leg('f' to second.size)
    val cells = ArrayList<IntArray>()
    val n1 = first.size; val n2 = second.size
    for (k in 0 until n1) cells.add(intArrayOf(k, 0, first[k]))
    cells.add(intArrayOf(n1, 0, 0))                                                            // turn
    for (k in 0 until n2) cells.add(intArrayOf(n1, k + 1, second[k]))
    cells.add(intArrayOf(n1, n2 + 1, 0))                                                       // turn
    for (k in 0 until n1) cells.add(intArrayOf(n1 - 1 - k, n2 + 1, third[k]))
    cells.add(intArrayOf(-1, n2 + 1, 0))                                                       // turn
    for (k in 0 until n2) cells.add(intArrayOf(-1, n2 - k, fourth[k]))
    cells.add(intArrayOf(-1, 0, 0))                                                            // turn back into the station
    return cells
}

/** Using a Roller Coaster item on the ground. Returns true when handled. */
internal fun Game.coasterUse(item: com.vishucraft.game.world.ItemDef?, t: RayHit?): Boolean {
    if (item?.name != "Roller Coaster") return false
    if (t == null || t.ny != 1) { uiEvents.add("toast:Tap the ground to set up the roller coaster"); return true }
    if (isClient) return true
    // Facing the nearest of the four directions; the ride goes ahead and to the right.
    val (fx, fz) = when (((player.yaw / (Math.PI / 2)).let { kotlin.math.round(it).toInt() } % 4 + 4) % 4) {
        0 -> 0 to -1; 1 -> 1 to 0; 2 -> 0 to 1; else -> -1 to 0
    }
    buildCoaster(t.x + fx * 3, t.y + 1, t.z + fz * 3, fx, fz)
    consumeHeld()
    uiEvents.add("toast:Roller coaster ready! Get in the car at the station and hold on")
    return true
}

/** Builds the ride with its station at (x, y, z), going forward along (fx, fz); returns the car. */
fun Game.buildCoaster(x: Int, y: Int, z: Int, fx: Int, fz: Int): Cart {
    val rx = -fz; val rz = fx   // to the right
    val cells = coasterLayout()
    fun at(c: IntArray) = intArrayOf(x + fx * c[0] + rx * c[1], y + c[2], z + fz * c[0] + rz * c[1])
    val pos = cells.map { at(it) }
    for (p in pos) world.ensureLoaded(p[0] shr 4, p[2] shr 4)
    val colours = intArrayOf(14, 4, 11, 5)   // red, yellow, blue, lime track
    for ((i, p) in pos.withIndex()) {
        val prev = pos[(i - 1 + pos.size) % pos.size]; val next = pos[(i + 1) % pos.size]
        val dIn = intArrayOf(p[0] - prev[0], p[2] - prev[2]); val dOut = intArrayOf(next[0] - p[0], next[2] - p[2])
        val shape = when {
            next[1] > p[1] -> (2..5).first { Rails.rise(it)!!.let { r -> r[0] == dOut[0] && r[1] == dOut[1] } }
            prev[1] > p[1] -> (2..5).first { Rails.rise(it)!!.let { r -> r[0] == -dIn[0] && r[1] == -dIn[1] } }
            dIn[0] != dOut[0] || dIn[1] != dOut[1] -> (6..9).first { s ->
                val e = Rails.exits(s)
                e.any { it[0] == -dIn[0] && it[1] == -dIn[1] } && e.any { it[0] == dOut[0] && it[1] == dOut[1] }
            }
            dOut[0] != 0 -> 1
            else -> 0
        }
        // Room for the car and its riders, then the track on a coloured bed.
        for (dy in 0..3) setBlock(p[0], p[1] + dy, p[2], Blocks.AIR)
        val station = i in 2..4
        setBlock(p[0], p[1], p[2], if (station) Blocks.POWERED_RAIL else Blocks.RAIL, shape)
        setBlock(p[0], p[1] - 1, p[2], Blocks.CONCRETE_FIRST + colours[(i / 6) % colours.size])
        // Pillars down to the ground under the high track.
        if (i % 3 == 0) {
            var py = p[1] - 2
            while (py > p[1] - 40 && py > 1 && !Blocks.solid[world.getBlock(p[0], py, p[2])]) { setBlock(p[0], py, p[2], Blocks.IRON_BARS); py-- }
        }
    }
    // The station platform on the left, with lamps at the ends.
    for (u in 0..6) {
        val px = x + fx * u - rx; val pz = z + fz * u - rz
        for (dy in 0..3) setBlock(px, y + dy, pz, Blocks.AIR)
        setBlock(px, y - 1, pz, Blocks.SMOOTH_STONE)
        if (u == 0 || u == 6) setBlock(px - rx, y, pz - rz, Blocks.OAK_FENCE)
    }
    for (u in 0..6) { val px = x + fx * u - rx * 2; val pz = z + fz * u - rz * 2; if (!Blocks.solid[world.getBlock(px, y - 1, pz)]) setBlock(px, y - 1, pz, Blocks.SMOOTH_STONE) }
    // The car, waiting at the station.
    val s = pos[3]
    val car = Cart(s[0] + 0.5f, s[1].toFloat(), s[2] + 0.5f, Cart.COASTER)
    car.hx = fx.toFloat(); car.hz = fz.toFloat(); car.yaw = kotlin.math.atan2(car.hx, -car.hz)
    carts.list.add(car)
    return car
}
