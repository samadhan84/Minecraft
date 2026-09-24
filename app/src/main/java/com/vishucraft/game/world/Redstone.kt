package com.vishucraft.game.world

import com.vishucraft.game.render.ChunkMesher
import java.util.ArrayDeque
import java.util.Random
import kotlin.math.sqrt

/**
 * A compact redstone simulation, stepped at 10 ticks per second.
 *
 * - Sources: lever (on), stone button (pressed, 1 s), redstone block, redstone torch (lit).
 * - Dust carries power 15 and loses 1 per block; it climbs up or down one block like stairs.
 * - Levers, buttons and torches strongly power the block they sit on / above; strongly powered blocks
 *   feed adjacent dust. Powered dust weakly powers the block under it and blocks beside it.
 * - A torch turns off when the block it stands on is powered (the classic inverter / clock).
 * - Lamps light, pistons push (up to 12 blocks) and pull (sticky), TNT gets primed.
 *
 * Block edits go through [set] so the game can re-mesh and keep the component registry in sync.
 */
class Redstone(private val world: World, private val set: (Int, Int, Int, Int, Int) -> Unit) {
    companion object {
        const val TICK_SECONDS = 0.1f
        const val EXTENDED = 8
        private val N = ChunkMesher.NORMALS
    }

    private val buttons = HashMap<Long, Int>()
    private val fuses = HashMap<Long, Int>()
    private val rnd = Random()

    /** Called with the explosion centre so the game can shake the camera / knock the player back. */
    var onExplosion: ((Float, Float, Float) -> Unit)? = null

    private fun id(p: Long) = world.getBlock(RedstoneIds.x(p), RedstoneIds.y(p), RedstoneIds.z(p))
    private fun meta(p: Long) = world.getMeta(RedstoneIds.x(p), RedstoneIds.y(p), RedstoneIds.z(p))
    private fun offset(p: Long, dx: Int, dy: Int, dz: Int): Long {
        val y = RedstoneIds.y(p) + dy
        if (y < 0 || y >= Chunk.HEIGHT) return -1
        return RedstoneIds.pack(RedstoneIds.x(p) + dx, y, RedstoneIds.z(p) + dz)
    }
    private fun dir(p: Long, f: Int) = offset(p, N[f][0], N[f][1], N[f][2])
    private fun setAt(p: Long, id: Int, meta: Int = 0) = set(RedstoneIds.x(p), RedstoneIds.y(p), RedstoneIds.z(p), id, meta)
    private fun opaqueAt(p: Long) = p >= 0 && Blocks.opaque[id(p)]

    fun pressButton(x: Int, y: Int, z: Int) {
        set(x, y, z, Blocks.STONE_BUTTON, 1)
        buttons[RedstoneIds.pack(x, y, z)] = 10
    }

    fun toggleLever(x: Int, y: Int, z: Int) {
        set(x, y, z, Blocks.LEVER, if (world.getMeta(x, y, z) != 0) 0 else 1)
    }

    fun prime(x: Int, y: Int, z: Int, ticks: Int = 40) {
        val p = RedstoneIds.pack(x, y, z)
        if (!fuses.containsKey(p)) fuses[p] = ticks
    }

    fun tick() {
        // Buttons release after a second.
        if (buttons.isNotEmpty()) {
            val it = buttons.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                e.setValue(e.value - 1)
                if (e.value <= 0) {
                    if (id(e.key) == Blocks.STONE_BUTTON) setAt(e.key, Blocks.STONE_BUTTON, 0)
                    it.remove()
                }
            }
        }

        val comps = world.components.toList()
        if (comps.isNotEmpty()) simulate(comps)

        if (fuses.isNotEmpty()) {
            for ((p, t) in fuses.toList()) {
                if (id(p) != Blocks.TNT) { fuses.remove(p); continue }
                val left = t - 1
                if (left <= 0) {
                    fuses.remove(p)
                    explode(p)
                } else {
                    fuses[p] = left
                    val flash = if ((left / 3) % 2 == 0) 2 else 1
                    if (meta(p) != flash) setAt(p, Blocks.TNT, flash)
                }
            }
        }
    }

    private fun simulate(comps: List<Long>) {
        val sources = HashSet<Long>()
        val strong = HashSet<Long>()
        for (p in comps) {
            when (id(p)) {
                Blocks.LEVER, Blocks.STONE_BUTTON -> if (meta(p) != 0) {
                    sources.add(p); dir(p, 1).let { if (it >= 0) strong.add(it) }
                }
                Blocks.REDSTONE_BLOCK -> sources.add(p)
                Blocks.REDSTONE_TORCH -> if (meta(p) == 0) {
                    sources.add(p); dir(p, 0).let { if (it >= 0) strong.add(it) }
                }
            }
        }

        // Spread power through dust.
        val dust = HashMap<Long, Int>()
        val queue = ArrayDeque<Long>()
        fun seed(q: Long, level: Int) {
            if (q < 0 || id(q) != Blocks.REDSTONE_DUST) return
            if ((dust[q] ?: 0) >= level) return
            dust[q] = level
            queue.add(q)
        }
        for (s in sources) for (f in 0 until 6) seed(dir(s, f), 15)
        for (b in strong) if (opaqueAt(b)) for (f in 0 until 6) seed(dir(b, f), 15)
        while (queue.isNotEmpty()) {
            val q = queue.poll()
            val l = dust[q] ?: continue
            if (l <= 1) continue
            val aboveOpen = !opaqueAt(dir(q, 0))
            for (f in 2 until 6) {
                val n = dir(q, f)
                if (n < 0) continue
                seed(n, l - 1)
                if (aboveOpen) seed(dir(n, 0), l - 1)
                if (!opaqueAt(n)) seed(dir(n, 1), l - 1)
            }
        }

        // Blocks powered by dust (weakly) or by sources (strongly).
        val powered = HashSet<Long>(strong)
        for ((q, l) in dust) {
            if (l <= 0) continue
            dir(q, 1).let { if (opaqueAt(it)) powered.add(it) }
            for (f in 2 until 6) dir(q, f).let { if (opaqueAt(it)) powered.add(it) }
        }

        fun activated(p: Long, ignoreFace: Int = -1): Boolean {
            for (f in 0 until 6) {
                if (f == ignoreFace) continue
                val n = dir(p, f)
                if (n < 0) continue
                if ((dust[n] ?: 0) > 0) return true
                if (n in powered) return true
                // A torch never powers the block it is attached to, but does power neighbours.
                if (n in sources && !(id(n) == Blocks.REDSTONE_TORCH && f == 0)) return true
            }
            return false
        }

        for (p in comps) {
            val id = id(p)
            val meta = meta(p)
            when (id) {
                Blocks.REDSTONE_DUST -> {
                    val l = dust[p] ?: 0
                    if (l != meta) setAt(p, id, l)
                }
                Blocks.REDSTONE_TORCH -> {
                    val off = if (dir(p, 1) in powered) 1 else 0
                    if (off != meta) setAt(p, id, off)
                }
                Blocks.REDSTONE_LAMP, Blocks.REDSTONE_LAMP_ON -> {
                    val target = if (activated(p)) Blocks.REDSTONE_LAMP_ON else Blocks.REDSTONE_LAMP
                    if (target != id) setAt(p, target, 0)
                }
                Blocks.PISTON, Blocks.STICKY_PISTON -> {
                    val facing = (meta and 7).coerceIn(0, 5)
                    val extended = meta and EXTENDED != 0
                    val on = activated(p, ignoreFace = facing)
                    if (on && !extended) extend(p, id, facing)
                    else if (!on && extended) retract(p, id, facing)
                }
                Blocks.TNT -> if (activated(p)) prime(RedstoneIds.x(p), RedstoneIds.y(p), RedstoneIds.z(p))
            }
        }
    }

    private fun fragile(id: Int) = Blocks[id].needsSupport || Blocks[id].render == RenderType.CROSS

    private fun movable(id: Int) = id != Blocks.AIR && Blocks[id].movable && Blocks[id].breakable

    private fun extend(p: Long, pistonId: Int, facing: Int): Boolean {
        val line = ArrayList<Long>()
        var c = dir(p, facing)
        while (true) {
            if (c < 0) return false
            val b = id(c)
            if (b == Blocks.AIR || b == Blocks.WATER) break
            if (fragile(b)) { setAt(c, Blocks.AIR); break }
            if (b == Blocks.PISTON || b == Blocks.STICKY_PISTON) { if (meta(c) and EXTENDED != 0) return false }
            else if (!movable(b)) return false
            if (line.size >= 12) return false
            line.add(c)
            c = dir(c, facing)
        }
        // Move from the far end so nothing is overwritten.
        for (q in line.asReversed()) {
            val b = id(q); val m = meta(q)
            setAt(dir(q, facing), b, m)
        }
        setAt(dir(p, facing), Blocks.PISTON_HEAD, facing or (if (pistonId == Blocks.STICKY_PISTON) EXTENDED else 0))
        setAt(p, pistonId, facing or EXTENDED)
        return true
    }

    private fun retract(p: Long, pistonId: Int, facing: Int) {
        val head = dir(p, facing)
        if (head >= 0 && id(head) == Blocks.PISTON_HEAD) setAt(head, Blocks.AIR)
        setAt(p, pistonId, facing)
        if (pistonId == Blocks.STICKY_PISTON && head >= 0) {
            val far = dir(head, facing)
            if (far < 0) return
            val b = id(far)
            val isExtendedPiston = (b == Blocks.PISTON || b == Blocks.STICKY_PISTON) && meta(far) and EXTENDED != 0
            if (movable(b) && !fragile(b) && !isExtendedPiston && b != Blocks.WATER) {
                setAt(head, b, meta(far))
                setAt(far, Blocks.AIR)
            }
        }
    }

    private fun explode(p: Long) {
        val cx = RedstoneIds.x(p); val cy = RedstoneIds.y(p); val cz = RedstoneIds.z(p)
        setAt(p, Blocks.AIR)
        val r = 3.6f
        for (dy in -4..4) for (dz in -4..4) for (dx in -4..4) {
            val d = sqrt((dx * dx + dy * dy + dz * dz).toFloat())
            if (d > r * (0.75f + rnd.nextFloat() * 0.25f)) continue
            val x = cx + dx; val y = cy + dy; val z = cz + dz
            if (y <= 0 || y >= Chunk.HEIGHT) continue
            val b = world.getBlock(x, y, z)
            when {
                b == Blocks.AIR || b == Blocks.WATER -> {}
                b == Blocks.TNT -> prime(x, y, z, 5 + rnd.nextInt(10))
                !Blocks[b].breakable || b == Blocks.OBSIDIAN || b == Blocks.CRYING_OBSIDIAN || b == Blocks.NETHERITE_BLOCK -> {}
                else -> set(x, y, z, Blocks.AIR, 0)
            }
        }
        onExplosion?.invoke(cx + 0.5f, cy + 0.5f, cz + 0.5f)
    }
}
