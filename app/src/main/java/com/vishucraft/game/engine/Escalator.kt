package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.floorInt

/*
 * Escalators: place escalator blocks in a diagonal line, each one block further and one block higher, facing the
 * way you were looking (the low end towards you). Step on and it carries you along smoothly, up or down, and you
 * step off onto the floor at the end. Tap one to switch the whole escalator between going up and going down.
 * Jump to hop off on the way.
 *
 * Or just place two escalator blocks, one higher up and one lower down, anywhere near each other: the steps in
 * between fill in by themselves, with a flat walkway (and a little landing) where the two are not in a straight
 * diagonal line.
 */

private val FRONT = arrayOf(intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(-1, 0)) // faces 2..5

/** The way an escalator facing [meta] rises: away from its front. */
private fun upDir(meta: Int): IntArray { val f = (meta and 7).coerceIn(2, 5); return intArrayOf(-FRONT[f - 2][0], -FRONT[f - 2][1]) }

/** How high the moving steps are at (x, z) on the escalator block at (bx, by, bz): from by at its low end to by + 1. */
private fun surface(bx: Int, by: Int, bz: Int, meta: Int, x: Float, z: Float): Float {
    val u = upDir(meta)
    val s = ((x - bx - 0.5f) * u[0] + (z - bz - 0.5f) * u[1] + 0.5f).coerceIn(0f, 1f)
    return by + s
}

/** The escalator block the player at (x, y, z) is riding, if any: (bx, by, bz). */
private fun Game.escalatorAt(x: Float, y: Float, z: Float): IntArray? {
    val bx = floorInt(x); val bz = floorInt(z)
    for (by in floorInt(y - 1.05f)..floorInt(y + 0.2f)) {
        if (world.getBlock(bx, by, bz) != Blocks.ESCALATOR) continue
        if (kotlin.math.abs(surface(bx, by, bz, world.getMeta(bx, by, bz), x, z) - y) < 0.6f) return intArrayOf(bx, by, bz)
    }
    return null
}

/** Carries the player along an escalator. Returns true when it moved the player this frame (instead of walking). */
internal fun Game.rideEscalator(dt: Float): Boolean {
    val p = player
    if (p.flying || input.jumpHeld) return false
    val e = escalatorAt(p.x, p.y, p.z) ?: return false
    val meta = world.getMeta(e[0], e[1], e[2])
    val u = upDir(meta)
    val down = meta and 8 != 0
    val dx = if (down) -u[0] else u[0]; val dz = if (down) -u[1] else u[1]
    // Steady speed, a little faster when walking along with it; drift to the middle of the steps.
    val along = (kotlin.math.sin(p.yaw) * dx - kotlin.math.cos(p.yaw) * dz) * input.moveForward
    val speed = 2.5f + maxOf(0f, along) * 2f
    var nx = p.x + dx * speed * dt; var nz = p.z + dz * speed * dt
    if (dx != 0) nz += (e[2] + 0.5f - nz) * minOf(1f, dt * 4f) else nx += (e[0] + 0.5f - nx) * minOf(1f, dt * 4f)
    val next = escalatorAt(nx, p.y + (if (down) -0.5f else 0.5f) * speed * dt * 2f, nz)
        ?: escalatorAt(nx, p.y, nz)
    p.x = nx; p.z = nz
    p.y = if (next != null) surface(next[0], next[1], next[2], world.getMeta(next[0], next[1], next[2]), nx, nz)
        else (if (down) e[1].toFloat() else e[1] + 1f)   // stepping off at the end, onto the floor
    p.vx = 0f; p.vy = 0f; p.vz = 0f
    return true
}

/** Tapping an escalator (with an empty hand or an item) switches the whole escalator between up and down. */
internal fun Game.escalatorTap(t: RayHit, sel: Int): Boolean {
    if (t.block != Blocks.ESCALATOR || (sel > 0 && !Items.isItem(sel))) return false
    val meta = world.getMeta(t.x, t.y, t.z)
    val u = upDir(meta)
    val flip = (meta xor 8) and 8
    // Every block of this escalator: along the line both ways, one up and one along each step.
    for (dir in intArrayOf(1, -1)) {
        var x = t.x; var y = t.y; var z = t.z
        while (world.getBlock(x, y, z) == Blocks.ESCALATOR && (world.getMeta(x, y, z) and 7) == (meta and 7)) {
            setBlock(x, y, z, Blocks.ESCALATOR, (world.getMeta(x, y, z) and 8.inv()) or flip)
            x += u[0] * dir; y += dir; z += u[1] * dir
        }
    }
    uiEvents.add("toast:Escalator: now going " + if (flip != 0) "down" else "up")
    sound("click", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f)
    return true
}

/** The face index whose escalator rises towards (sx, sz). */
private fun facingUp(sx: Int, sz: Int) = when { sx > 0 -> 5; sx < 0 -> 4; sz > 0 -> 3; else -> 2 }

/** Whether the escalator at (x, y, z) already has another escalator step next to it (one up or down, one along). */
private fun Game.inRun(x: Int, y: Int, z: Int): Boolean {
    for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) for (dy in intArrayOf(-1, 1))
        if (world.getBlock(x + dx, y + dy, z + dz) == Blocks.ESCALATOR) return true
    return false
}

/**
 * Just placed an escalator block at (x, y, z) on its own: join it to the nearest other lone escalator block
 * (within 48 blocks, at another height) with a full escalator, and a walkway where they aren't lined up.
 */
internal fun Game.joinEscalator(x: Int, y: Int, z: Int) {
    if (isClient || inRun(x, y, z)) return
    var other: IntArray? = null; var best = Int.MAX_VALUE
    for (oy in y - 40..y + 40) {
        if (oy == y || oy < 1 || oy >= com.vishucraft.game.world.Chunk.HEIGHT - 4) continue
        for (oz in z - 48..z + 48) for (ox in x - 48..x + 48) {
            if (world.getBlock(ox, oy, oz) != Blocks.ESCALATOR || inRun(ox, oy, oz)) continue
            val d = (ox - x) * (ox - x) + (oz - z) * (oz - z) + (oy - y) * (oy - y)
            if (d < best) { best = d; other = intArrayOf(ox, oy, oz) }
        }
    }
    val o = other ?: return
    val (lo, up) = if (o[1] < y) o to intArrayOf(x, y, z) else intArrayOf(x, y, z) to o
    val rise = up[1] - lo[1]
    // The steps run along whichever way the two are further apart, ending at the top block.
    val dx = up[0] - lo[0]; val dz = up[2] - lo[2]
    val alongX = if (dx == 0 && dz == 0) true else kotlin.math.abs(dx) >= kotlin.math.abs(dz)
    val sx = if (alongX) (if (dx < 0) -1 else 1) else 0
    val sz = if (alongX) 0 else (if (dz < 0) -1 else 1)
    val face = facingUp(sx, sz)
    // Steps from the bottom of the run (rise blocks back from the top) up to the top block, with headroom.
    val bx = up[0] - sx * rise; val bz = up[2] - sz * rise
    for (k in 0..rise) {
        val ex = bx + sx * k; val ey = lo[1] + k; val ez = bz + sz * k
        for (h in 1..3) if (world.getBlock(ex, ey + h, ez) != Blocks.AIR) setBlock(ex, ey + h, ez, Blocks.AIR)
        setBlock(ex, ey, ez, Blocks.ESCALATOR, face)
    }
    // A walkway from the lower block to the foot of the escalator, if they aren't the same place.
    if (lo[0] != bx || lo[2] != bz) {
        setBlock(lo[0], lo[1], lo[2], Blocks.AIR)
        val fx = bx - sx; val fz = bz - sz   // the floor just in front of the bottom step
        var cx = lo[0]; var cz = lo[2]
        fun floor(px: Int, pz: Int) {
            setBlock(px, lo[1] - 1, pz, Blocks.POLISHED_ANDESITE)
            for (h in 0..2) if (world.getBlock(px, lo[1] + h, pz) != Blocks.AIR && !(px == bx && pz == bz && h == 0)) setBlock(px, lo[1] + h, pz, Blocks.AIR)
        }
        floor(cx, cz)
        while (cx != fx) { cx += Integer.signum(fx - cx); floor(cx, cz) }
        while (cz != fz) { cz += Integer.signum(fz - cz); floor(cx, cz) }
    }
    // A landing at the top, so you step off onto something.
    val tx = up[0] + sx; val tz = up[2] + sz
    if (!Blocks.solid[world.getBlock(tx, up[1], tz)]) setBlock(tx, up[1], tz, Blocks.POLISHED_ANDESITE)
    uiEvents.add("toast:Escalator joined: ${rise + 1} steps")
}
