package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Rails
import com.vishucraft.game.world.RedstoneIds

/*
 * Train call buttons stand on every station's platforms. Press one and the nearest train of the station's own kind
 * (a metro at a metro station, a railway train at a railway station, a bullet train at a bullet train station)
 * comes straight to the platform (a new one if there is none yet), just for you. You are put on board and pick a
 * station; it runs there non-stop, through any other train in the way and through every station and city on the
 * line. Once you get off, it goes back into service.
 */

/** The kinds of train a call button offers, as shown to the player. */
val TRAIN_CHOICES = listOf("Metro", "Bullet Train", "Railway Train")

/** Tapping a call button (with an empty hand or an item) asks which kind of train to call. */
internal fun Game.callButtonTap(t: RayHit, sel: Int): Boolean {
    if (t.block != Blocks.CALL_BUTTON || (sel > 0 && !Items.isItem(sel))) return false
    sound("click", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f)
    // Each station calls its own kind of train: a metro, a railway train or a bullet train.
    val s = metroNet.stations.minByOrNull { (it[0] - t.x).toLong() * (it[0] - t.x) + (it[2] - t.z).toLong() * (it[2] - t.z) }
    callTrain(when (s?.get(3)) { TrackEnd.BULLET -> "Bullet Train"; TrackEnd.RAILWAY -> "Railway Train"; else -> "Metro" }, t.x, t.y, t.z)
    return true
}

/** Brings the nearest train of kind [choice] (one of [TRAIN_CHOICES]) to the track next to the button at (x, y, z). */
fun Game.callTrain(choice: String, x: Int, y: Int, z: Int) {
    if (isClient) return
    val want = when (choice) { "Metro" -> Cart.METRO; "Bullet Train" -> Cart.BULLET; else -> Cart.ENGINE }
    val rail = nearestRail(x, y, z) ?: run { uiEvents.add("toast:There is no track next to this button"); return }
    val (rx, ry, rz) = rail
    val e = Rails.exits(Rails.shape(world.getBlock(rx, ry, rz), world.getMeta(rx, ry, rz)))[0]
    // The nearest train of that kind, or a new one with two coaches.
    var head = carts.list.filter { it.leader == null && carts.power(it)?.kind == want }
        .minByOrNull { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) }
    if (head == null) {
        head = Cart(rx + 0.5f, ry.toFloat(), rz + 0.5f, want)
        carts.list.add(head)
        var lead: Cart = head
        val c = Cart(rx + 0.5f, ry.toFloat(), rz + 0.5f, Cart.COACH).also { it.leader = lead }; carts.list.add(c); lead = c
        addRearCabs()
    }
    val cars = carts.chain(head)
    // Anyone travelling in it gets off here first.
    for (m in mobs.list) if (cars.any { it === m.ride }) { m.ride = null; m.x = x + 0.5f; m.y = y.toFloat(); m.z = z + 0.5f }
    // Line the cars up along the track behind the front one, all facing the same way.
    for ((k, c) in cars.withIndex()) {
        var cx = rx; var cz = rz
        for (step in 1..k * 3) if (Rails.isRail(world.getBlock(cx - e[0], ry, cz - e[1]))) { cx -= e[0]; cz -= e[1] }
        c.x = cx + 0.5f; c.y = ry.toFloat(); c.z = cz + 0.5f
        c.hx = e[0].toFloat(); c.hz = e[1].toFloat(); c.yaw = kotlin.math.atan2(c.hx, -c.hz)
        c.speed = 0f; c.atEnd = false; c.trail.clear(); c.vy = 0f
    }
    for (k in 1 until cars.size) {
        val front = cars[k - 1]; val back = cars[k]
        for (s in 0..20) { val f = s / 20f; front.trail.addLast(floatArrayOf(back.x + (front.x - back.x) * f, back.y, back.z + (front.z - back.z) * f)) }
    }
    head.private = true
    head.dest = null
    head.stopTimer = 600f   // waits for you to pick a station
    head.lastStation = if (world.getBlock(rx, ry, rz) == Blocks.STOP_RAIL) RedstoneIds.pack(rx, ry, rz) else Long.MIN_VALUE
    // On board.
    aircraft.riding = null; boats.riding = null; vehicles.riding = null
    carts.riding = cars.getOrNull(1) ?: head
    sound(if (want == Cart.ENGINE) "train_horn" else "metro_chime", rx + 0.5f, ry + 2f, rz + 0.5f, 0.8f)
    uiEvents.add("toast:Your own ${choice.lowercase()} is here! Pick a station and it goes there non-stop")
    // The stations on this line (the network the button's station belongs to).
    val here = metroNet.stations.minByOrNull { (it[0] - x).toLong() * (it[0] - x) + (it[2] - z).toLong() * (it[2] - z) }
    offerMetroStations(head, here?.get(3))
}

/** The nearest rail within a few blocks of (x, y, z): (x, y, z). */
private fun Game.nearestRail(x: Int, y: Int, z: Int): IntArray? {
    var best: IntArray? = null; var bestD = Int.MAX_VALUE
    for (dy in -2..1) for (dz in -4..4) for (dx in -4..4) {
        val id = world.getBlock(x + dx, y + dy, z + dz)
        if (!Rails.isRail(id)) continue
        // Straight track only (so the train lines up along it).
        if (Rails.shape(id, world.getMeta(x + dx, y + dy, z + dz)) > 1) continue
        val d = dx * dx + dz * dz + dy * dy
        if (d < bestD) { bestD = d; best = intArrayOf(x + dx, y + dy, z + dz) }
    }
    return best
}

/** Puts a call button on a free spot of the platform nearest to (x, y, z), unless there already is one. */
internal fun Game.placeCallButton(x: Int, y: Int, z: Int) {
    var best: IntArray? = null; var bestD = Int.MAX_VALUE
    for (dy in 0..1) for (dz in -3..3) for (dx in -3..3) {
        val bx = x + dx; val by = y + dy; val bz = z + dz
        val id = world.getBlock(bx, by, bz)
        if (id == Blocks.CALL_BUTTON) return
        val below = world.getBlock(bx, by - 1, bz)
        if (id != Blocks.AIR || !Blocks.solid[below] || Rails.isRail(below) || world.getBlock(bx, by + 1, bz) != Blocks.AIR) continue
        val d = dx * dx + dz * dz + dy * 4
        if (d < bestD) { bestD = d; best = intArrayOf(bx, by, bz) }
    }
    best?.let { setBlock(it[0], it[1], it[2], Blocks.CALL_BUTTON, 2) }
}

/**
 * Metros and bullet trains have three parts: a driving car at the front, a passenger car (or more) in the middle,
 * and a driving car at the back, which becomes the front when the train turns round. Adds the back driving car to
 * any such train that is missing it (trains from older versions, or just built).
 */
internal fun Game.addRearCabs() {
    for (h in carts.list.toList()) {
        if (h.leader != null || (h.kind != Cart.METRO && h.kind != Cart.BULLET)) continue
        val cars = carts.chain(h)
        val tail = cars.last()
        if (cars.size < 2 || tail.kind == h.kind || cars.none { it.kind == Cart.COACH }) continue
        val gap = tail.half + 0.35f + (if (h.kind == Cart.BULLET) 1.9f else 1.6f)
        val cab = Cart(tail.x - tail.hx * gap, tail.y, tail.z - tail.hz * gap, h.kind)
        cab.hx = tail.hx; cab.hz = tail.hz; cab.yaw = tail.yaw; cab.leader = tail
        for (s in 0..20) { val f = s / 20f; tail.trail.addLast(floatArrayOf(cab.x + (tail.x - cab.x) * f, tail.y, cab.z + (tail.z - cab.z) * f)) }
        carts.list.add(cab)
    }
}
