package com.vishucraft.game.render

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Tiles
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Generates every block and item texture procedurally into a 512x512 ARGB atlas (32x32 tiles of 16px).
 * Pure Kotlin so it can run anywhere; the Android side wraps the pixels in a Bitmap.
 */
object TextureAtlas {
    const val TILE = 16
    const val TILES_PER_ROW = 32
    const val SIZE = TILE * TILES_PER_ROW

    val pixels: IntArray by lazy { build() }

    // ---------------------------------------------------------------- colour helpers

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)

    private fun scale(c: Int, f: Float): Int {
        val a = c ushr 24
        return argb(a, (((c shr 16) and 255) * f).toInt(), (((c shr 8) and 255) * f).toInt(), ((c and 255) * f).toInt())
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0xFFFFFF) or (a shl 24)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * (1 - t) + ((b shr s) and 255) * t).toInt()
        return argb(ch(24), ch(16), ch(8), ch(0))
    }

    private class Tile(val rnd: Random) {
        val px = IntArray(TILE * TILE)
        operator fun get(x: Int, y: Int) = px[(y and 15) * TILE + (x and 15)]
        operator fun set(x: Int, y: Int, c: Int) { px[(y and 15) * TILE + (x and 15)] = c }
        fun fill(f: (Int, Int) -> Int) { for (y in 0 until TILE) for (x in 0 until TILE) this[x, y] = f(x, y) }
        fun jitter(amount: Float) = 1f + (rnd.nextFloat() * 2 - 1) * amount
    }

    /** Tileable smooth value noise over a 16x16 tile. */
    private fun valueNoise(rnd: Random, cell: Int): Array<FloatArray> {
        val n = TILE / cell
        val grid = Array(n) { FloatArray(n) { rnd.nextFloat() } }
        return Array(TILE) { y ->
            FloatArray(TILE) { x ->
                val gx = x.toFloat() / cell; val gy = y.toFloat() / cell
                val x0 = gx.toInt(); val y0 = gy.toInt()
                val tx = gx - x0; val ty = gy - y0
                val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
                val a = grid[y0 % n][x0 % n]; val b = grid[y0 % n][(x0 + 1) % n]
                val c = grid[(y0 + 1) % n][x0 % n]; val d = grid[(y0 + 1) % n][(x0 + 1) % n]
                (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
            }
        }
    }

    /** Tileable Voronoi: returns (cell index, edge distance) per pixel. */
    private fun voronoi(rnd: Random, points: Int): Pair<Array<IntArray>, Array<FloatArray>> {
        val pts = Array(points) { floatArrayOf(rnd.nextFloat() * TILE, rnd.nextFloat() * TILE) }
        val id = Array(TILE) { IntArray(TILE) }
        val edge = Array(TILE) { FloatArray(TILE) }
        for (y in 0 until TILE) for (x in 0 until TILE) {
            var d1 = Float.MAX_VALUE; var d2 = Float.MAX_VALUE; var best = 0
            for ((i, p) in pts.withIndex()) {
                for (oy in -1..1) for (ox in -1..1) {
                    val d = hypot(x + 0.5f - (p[0] + ox * TILE), y + 0.5f - (p[1] + oy * TILE))
                    if (d < d1) { d2 = d1; d1 = d; best = i } else if (d < d2) d2 = d
                }
            }
            id[y][x] = best
            edge[y][x] = d2 - d1
        }
        return id to edge
    }

    // ---------------------------------------------------------------- tile painters

    private fun noisy(t: Tile, base: Int, amount: Float, blotch: Float = 0f) {
        val vn = valueNoise(t.rnd, 4)
        t.fill { x, y -> scale(base, t.jitter(amount) * (1 - blotch / 2 + vn[y][x] * blotch)) }
    }

    private fun dirt(t: Tile) {
        noisy(t, rgb(134, 96, 67), 0.12f, 0.2f)
        repeat(10) {
            val x = t.rnd.nextInt(16); val y = t.rnd.nextInt(16)
            t[x, y] = if (t.rnd.nextBoolean()) rgb(94, 66, 45) else rgb(160, 122, 92)
        }
    }

    private fun grassTop(t: Tile, base: Int = rgb(98, 160, 58)) {
        noisy(t, base, 0.1f, 0.25f)
        repeat(28) {
            val x = t.rnd.nextInt(16); val y = t.rnd.nextInt(16)
            t[x, y] = scale(base, if (t.rnd.nextBoolean()) 0.78f else 1.18f)
        }
    }

    private fun overlayTop(t: Tile, color: Int, depth: Int) {
        for (x in 0 until TILE) {
            val d = depth + t.rnd.nextInt(3) - (if (x % 5 == 0) 0 else 1)
            for (y in 0 until d) t[x, y] = scale(color, t.jitter(0.08f))
            if (d < TILE && t.rnd.nextInt(3) == 0) t[x, d] = scale(color, 0.8f)
        }
    }

    private fun stone(t: Tile, base: Int = rgb(126, 126, 128)) {
        noisy(t, base, 0.06f, 0.3f)
        repeat(4) {
            var x = t.rnd.nextInt(16); var y = t.rnd.nextInt(16)
            repeat(3 + t.rnd.nextInt(3)) {
                t[x, y] = scale(base, 0.75f)
                x += t.rnd.nextInt(3) - 1; y += if (t.rnd.nextBoolean()) 1 else 0
            }
        }
    }

    private fun cobble(t: Tile, moss: Boolean) {
        val (ids, edge) = voronoi(t.rnd, 9)
        val shades = FloatArray(9) { 0.8f + t.rnd.nextFloat() * 0.35f }
        val mossNoise = valueNoise(t.rnd, 8)
        t.fill { x, y ->
            val e = edge[y][x]
            var c = if (e < 1.1f) rgb(70, 70, 72)
            else scale(rgb(128, 128, 130), shades[ids[y][x]] * t.jitter(0.06f) * (if (e < 2.2f) 0.88f else 1f))
            if (moss && mossNoise[y][x] > 0.55f && t.rnd.nextInt(4) != 0) c = scale(rgb(78, 122, 52), t.jitter(0.15f))
            c
        }
    }

    private fun planks(t: Tile, base: Int = rgb(166, 132, 80)) {
        val seams = IntArray(4) { t.rnd.nextInt(16) }
        t.fill { x, y ->
            val row = y / 4
            when {
                y % 4 == 3 -> scale(base, 0.62f)
                x == seams[row] -> scale(base, 0.7f)
                else -> scale(base, (0.92f + ((x * 7 + row * 13) % 5) * 0.03f) * t.jitter(0.04f))
            }
        }
    }

    private fun logSide(t: Tile, base: Int, dark: Int, birch: Boolean) {
        t.fill { x, _ -> scale(base, (if (x % 4 == 0) 0.85f else 1f) * t.jitter(0.08f)) }
        if (birch) {
            repeat(7) {
                val x = t.rnd.nextInt(14); val y = t.rnd.nextInt(16)
                val len = 2 + t.rnd.nextInt(3)
                for (i in 0 until len) t[x + i, y] = dark
            }
        } else {
            for (x in 0 until TILE) for (y in 0 until TILE) if ((x + y * 3 + t.rnd.nextInt(3)) % 7 == 0) t[x, y] = dark
        }
    }

    private fun logTop(t: Tile) {
        t.fill { x, y ->
            val d = max(abs(x - 7.5f), abs(y - 7.5f))
            when {
                d > 6.6f -> scale(rgb(104, 82, 50), t.jitter(0.08f))
                d.toInt() % 2 == 0 -> scale(rgb(180, 146, 90), t.jitter(0.04f))
                else -> scale(rgb(154, 122, 72), t.jitter(0.04f))
            }
        }
    }

    private fun leaves(t: Tile, base: Int) {
        val vn = valueNoise(t.rnd, 4)
        t.fill { x, y ->
            if (t.rnd.nextInt(100) < 22) 0
            else scale(base, (0.75f + vn[y][x] * 0.4f) * t.jitter(0.1f))
        }
    }

    private fun glass(t: Tile) {
        t.fill { x, y ->
            when {
                x == 0 || y == 0 || x == 15 || y == 15 -> argb(255, 214, 236, 240)
                (x == y + 4 && x in 5..9) || (x == y + 7 && x in 9..12) -> argb(200, 255, 255, 255)
                else -> 0
            }
        }
    }

    private fun water(t: Tile) {
        val vn = valueNoise(t.rnd, 4)
        t.fill { x, y ->
            val w = vn[y][x]
            val c = mix(rgb(40, 84, 196), rgb(78, 132, 230), w)
            withAlpha(if ((x + y * 2) % 9 == 0 && w > 0.6f) rgb(120, 170, 240) else c, 175)
        }
    }

    private fun ore(t: Tile, color: Int, clusters: Int) {
        stone(t)
        repeat(clusters) {
            val cx = 2 + t.rnd.nextInt(12); val cy = 2 + t.rnd.nextInt(12)
            for (i in 0 until 4 + t.rnd.nextInt(3)) {
                val x = cx + t.rnd.nextInt(3) - 1; val y = cy + t.rnd.nextInt(3) - 1
                t[x, y] = scale(color, t.jitter(0.12f))
                if (t.rnd.nextInt(3) == 0) t[x + 1, y] = scale(color, 0.7f)
            }
        }
    }

    private fun bricks(t: Tile) {
        t.fill { x, y ->
            val row = y / 4
            val off = if (row % 2 == 0) 0 else 4
            if (y % 4 == 3 || (x + off) % 8 == 7) scale(rgb(176, 170, 160), t.jitter(0.05f))
            else scale(rgb(150, 74, 58), t.jitter(0.1f))
        }
    }

    private fun stoneBricks(t: Tile) {
        t.fill { x, y ->
            val row = y / 8
            val off = if (row % 2 == 0) 0 else 8
            val lx = (x + off) % 16; val ly = y % 8
            when {
                ly == 7 || lx == 15 -> rgb(82, 82, 84)
                ly == 0 || lx == 0 -> scale(rgb(150, 150, 152), t.jitter(0.04f))
                else -> scale(rgb(122, 122, 124), t.jitter(0.07f))
            }
        }
    }

    private fun sandstoneSide(t: Tile) {
        t.fill { _, y ->
            val base = rgb(216, 200, 150)
            when {
                y < 3 -> scale(base, 1.05f * t.jitter(0.03f))
                y == 3 || y == 11 -> scale(base, 0.85f)
                else -> scale(base, (if (y % 3 == 0) 0.95f else 1f) * t.jitter(0.04f))
            }
        }
    }

    private fun bookshelf(t: Tile) {
        planks(t)
        val colors = intArrayOf(rgb(150, 40, 40), rgb(40, 80, 150), rgb(60, 120, 50), rgb(170, 140, 60), rgb(100, 50, 120))
        for (shelf in 0..1) {
            val y0 = 2 + shelf * 7
            var x = 1
            while (x < 15) {
                val w = 1 + t.rnd.nextInt(2)
                val h = 4 + t.rnd.nextInt(2)
                val c = colors[t.rnd.nextInt(colors.size)]
                for (bx in x until min(15, x + w)) for (by in y0 + (5 - h) until y0 + 5) t[bx, by] = scale(c, t.jitter(0.08f))
                x += w
                if (t.rnd.nextInt(4) == 0) x++
            }
            for (bx in 0 until 16) t[bx, y0 + 5] = scale(rgb(110, 84, 50), 1f)
        }
    }

    private fun wool(t: Tile, base: Int) {
        t.fill { x, y -> scale(base, (if ((x + y) % 4 == 0) 0.9f else 1f) * t.jitter(0.05f)) }
    }

    private fun flower(t: Tile, petal: Int) {
        t.fill { _, _ -> 0 }
        for (y in 7 until 16) t[7, y] = rgb(60, 130, 40)
        t[6, 11] = rgb(60, 130, 40); t[5, 10] = rgb(70, 140, 45); t[8, 12] = rgb(60, 130, 40); t[9, 11] = rgb(70, 140, 45)
        for (dy in -2..2) for (dx in -2..2) {
            if (abs(dx) + abs(dy) <= 2 && !(dx == 0 && dy == 0)) t[7 + dx, 5 + dy] = scale(petal, t.jitter(0.1f))
        }
        t[7, 5] = rgb(240, 210, 60)
    }

    private fun tallGrass(t: Tile) {
        t.fill { _, _ -> 0 }
        repeat(9) {
            var x = 1f + t.rnd.nextFloat() * 14
            val h = 6 + t.rnd.nextInt(9)
            val lean = (t.rnd.nextFloat() - 0.5f) * 0.4f
            for (i in 0 until h) {
                t[x.toInt(), 15 - i] = scale(rgb(88, 150, 50), 0.75f + i * 0.03f)
                x += lean
            }
        }
    }

    private fun deadBush(t: Tile) {
        t.fill { _, _ -> 0 }
        val c = rgb(124, 86, 46)
        fun branch(x0: Float, y0: Float, dx: Float, len: Int) {
            var x = x0; var y = y0
            repeat(len) { t[x.toInt(), y.toInt()] = c; x += dx; y -= 1f }
        }
        branch(7.5f, 15f, 0f, 7)
        branch(7.5f, 12f, -0.7f, 7)
        branch(7.5f, 11f, 0.6f, 8)
        branch(5f, 10f, 0.3f, 4)
    }

    private fun glowstone(t: Tile) {
        val (ids, edge) = voronoi(t.rnd, 7)
        val shades = FloatArray(7) { 0.8f + t.rnd.nextFloat() * 0.25f }
        t.fill { x, y ->
            if (edge[y][x] < 1f) rgb(150, 110, 60)
            else scale(rgb(250, 214, 130), shades[ids[y][x]] * t.jitter(0.05f))
        }
    }

    private fun obsidian(t: Tile) {
        noisy(t, rgb(22, 16, 36), 0.15f, 0.4f)
        repeat(6) {
            val x = t.rnd.nextInt(16); val y = t.rnd.nextInt(16)
            t[x, y] = rgb(72, 50, 110); t[x + 1, y] = rgb(52, 36, 84)
        }
    }

    private fun cactusSide(t: Tile) {
        t.fill { x, _ ->
            val base = rgb(58, 128, 44)
            when {
                x == 0 || x == 15 -> scale(base, 0.6f)
                x % 4 == 2 -> scale(base, 0.8f)
                else -> scale(base, t.jitter(0.06f))
            }
        }
        repeat(8) { t[1 + t.rnd.nextInt(14), t.rnd.nextInt(16)] = rgb(220, 220, 180) }
    }

    private fun cactusTop(t: Tile) {
        t.fill { x, y ->
            val d = max(abs(x - 7.5f), abs(y - 7.5f))
            if (d > 6.5f) rgb(40, 96, 30) else scale(rgb(78, 150, 58), (if (d.toInt() % 3 == 0) 0.9f else 1f) * t.jitter(0.05f))
        }
    }

    private fun pumpkinSide(t: Tile) {
        t.fill { x, y ->
            val base = rgb(214, 124, 30)
            when {
                y == 0 || y == 15 -> scale(base, 0.7f)
                x % 5 == 0 -> scale(base, 0.78f)
                else -> scale(base, t.jitter(0.05f))
            }
        }
    }

    private fun pumpkinTop(t: Tile) {
        t.fill { x, y ->
            val d = hypot(x - 7.5f, y - 7.5f)
            when {
                d < 1.6f -> rgb(90, 110, 40)
                ((x + y) % 5 == 0) -> scale(rgb(214, 124, 30), 0.8f)
                else -> scale(rgb(214, 124, 30), t.jitter(0.05f))
            }
        }
    }

    private fun ice(t: Tile) {
        t.fill { x, y ->
            val streak = (x + y * 2) % 11 == 0 || (x * 2 + y) % 13 == 0
            withAlpha(if (streak) rgb(235, 245, 255) else scale(rgb(150, 190, 245), t.jitter(0.05f)), 200)
        }
    }

    private fun bedrock(t: Tile) {
        val (ids, edge) = voronoi(t.rnd, 10)
        val shades = FloatArray(10) { 0.35f + t.rnd.nextFloat() * 0.6f }
        t.fill { x, y -> if (edge[y][x] < 1f) rgb(30, 30, 30) else scale(rgb(140, 140, 140), shades[ids[y][x]]) }
    }

    private fun gravel(t: Tile) {
        val (ids, edge) = voronoi(t.rnd, 14)
        val tones = IntArray(14) {
            when (t.rnd.nextInt(3)) { 0 -> rgb(132, 124, 120); 1 -> rgb(110, 102, 98); else -> rgb(150, 142, 136) }
        }
        t.fill { x, y -> if (edge[y][x] < 0.8f) rgb(84, 78, 76) else scale(tones[ids[y][x]], t.jitter(0.05f)) }
    }

    private fun sun(t: Tile) {
        t.fill { x, y ->
            val d = max(abs(x - 7.5f), abs(y - 7.5f))
            if (d < 5.5f) rgb(255, 250, 200) else if (d < 7.5f) argb(140, 255, 230, 140) else 0
        }
    }

    private fun moon(t: Tile) {
        t.fill { x, y ->
            val d = max(abs(x - 7.5f), abs(y - 7.5f))
            if (d < 5.5f) {
                if ((x == 5 && y == 6) || (x == 9 && y in 8..9) || (x in 6..7 && y == 10)) rgb(180, 184, 196) else rgb(222, 226, 236)
            } else 0
        }
    }

    /** Thin cracks radiating from the centre; later stages reach further out. */
    private fun cracks(stages: Array<Tile>) {
        val rnd = Random(99)
        val rays = Array(7) {
            val angle = it * (Math.PI * 2 / 7) + rnd.nextDouble() * 0.6
            // Pre-compute a jagged path for each ray.
            val path = ArrayList<IntArray>()
            var x = 7.5; var y = 7.5
            var a = angle
            for (i in 0 until 12) {
                a += (rnd.nextDouble() - 0.5) * 0.9
                x += Math.cos(a) * 0.75; y += Math.sin(a) * 0.75
                val px = x.toInt(); val py = y.toInt()
                if (px !in 0..15 || py !in 0..15) break
                if (path.isEmpty() || path.last()[0] != px || path.last()[1] != py) path.add(intArrayOf(px, py))
            }
            path
        }
        for ((s, t) in stages.withIndex()) {
            t.fill { _, _ -> 0 }
            val frac = (s + 1f) / stages.size
            for ((ri, ray) in rays.withIndex()) {
                // Rays start appearing one by one, then grow.
                if (ri > s + 1) continue
                val n = (ray.size * frac).toInt().coerceAtLeast(1)
                for (i in 0 until minOf(n, ray.size)) t[ray[i][0], ray[i][1]] = argb(200, 24, 24, 24)
            }
        }
    }

    // ---------------------------------------------------------------- more painters

    private val DYE_COLORS = mapOf(
        "white" to rgb(234, 236, 236), "orange" to rgb(240, 118, 19), "magenta" to rgb(189, 68, 179),
        "light_blue" to rgb(58, 175, 217), "yellow" to rgb(248, 197, 39), "lime" to rgb(112, 185, 25),
        "pink" to rgb(237, 141, 172), "gray" to rgb(62, 68, 71), "light_gray" to rgb(142, 142, 134),
        "cyan" to rgb(21, 137, 145), "purple" to rgb(121, 42, 172), "blue" to rgb(53, 57, 157),
        "brown" to rgb(114, 71, 40), "green" to rgb(84, 109, 27), "red" to rgb(161, 39, 34),
        "black" to rgb(20, 21, 25),
    )

    private class Wood(val bark: Int, val barkDark: Int, val planks: Int, val leaves: Int)

    private val WOODS = mapOf(
        "oak" to Wood(rgb(108, 84, 52), rgb(72, 55, 34), rgb(166, 132, 80), rgb(62, 126, 42)),
        "spruce" to Wood(rgb(62, 44, 26), rgb(40, 28, 16), rgb(116, 86, 50), rgb(52, 92, 58)),
        "birch" to Wood(rgb(222, 220, 208), rgb(50, 50, 46), rgb(198, 178, 120), rgb(118, 160, 70)),
        "jungle" to Wood(rgb(92, 72, 34), rgb(60, 48, 22), rgb(162, 116, 80), rgb(48, 142, 32)),
        "acacia" to Wood(rgb(106, 100, 92), rgb(72, 68, 62), rgb(172, 92, 50), rgb(104, 132, 42)),
        "dark_oak" to Wood(rgb(62, 48, 30), rgb(40, 30, 18), rgb(70, 46, 22), rgb(42, 92, 26)),
    )

    private fun speckled(t: Tile, base: Int, spot: Int, spots: Int, amount: Float = 0.06f) {
        noisy(t, base, amount, 0.2f)
        repeat(spots) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = scale(spot, t.jitter(0.1f)) }
    }

    private fun bevel(t: Tile, light: Int, dark: Int) {
        for (i in 0 until TILE) { t[i, 0] = light; t[0, i] = light; t[i, 15] = dark; t[15, i] = dark }
    }

    private fun logTopColored(t: Tile, w: Wood) {
        t.fill { x, y ->
            val d = max(abs(x - 7.5f), abs(y - 7.5f))
            when {
                d > 6.6f -> scale(w.bark, t.jitter(0.08f))
                d.toInt() % 2 == 0 -> scale(w.planks, 1.08f * t.jitter(0.04f))
                else -> scale(w.planks, 0.9f * t.jitter(0.04f))
            }
        }
    }

    private fun mineralBlock(t: Tile, c: Int, pattern: Int) {
        t.fill { x, y ->
            val edge = x == 0 || y == 0 || x == 15 || y == 15
            val inner = x in 3..12 && y in 3..12
            when {
                edge -> scale(c, 0.7f)
                x == 1 || y == 1 -> scale(c, 1.25f)
                pattern == 1 && inner && (x + y) % 4 == 0 -> scale(c, 1.2f)
                pattern == 2 && (x % 5 == 2 && y % 5 == 2) -> scale(c, 1.35f)
                else -> scale(c, t.jitter(0.05f))
            }
        }
    }

    private fun polished(t: Tile, base: Int) {
        noisy(t, base, 0.04f, 0.15f)
        bevel(t, scale(base, 1.15f), scale(base, 0.7f))
    }

    private fun lamp(t: Tile, on: Boolean) {
        val (ids, edge) = voronoi(t.rnd, 6)
        val c = if (on) rgb(246, 200, 120) else rgb(110, 64, 34)
        t.fill { x, y ->
            when {
                x == 0 || y == 0 || x == 15 || y == 15 -> if (on) rgb(150, 100, 60) else rgb(60, 36, 20)
                edge[y][x] < 1f -> if (on) rgb(255, 240, 200) else rgb(80, 46, 24)
                else -> scale(c, (0.85f + ids[y][x] * 0.04f) * t.jitter(0.04f))
            }
        }
    }

    private fun sprite(t: Tile) = t.fill { _, _ -> 0 }

    private fun torchSprite(t: Tile, head: Int, glow: Int) {
        sprite(t)
        for (y in 6..15) { t[7, y] = rgb(120, 88, 50); t[8, y] = rgb(96, 70, 40) }
        for (y in 3..5) for (x in 7..8) t[x, y] = head
        t[7, 2] = glow; t[8, 2] = glow; t[6, 4] = scale(glow, 0.8f); t[9, 4] = scale(glow, 0.8f)
    }

    private fun leverSprite(t: Tile, on: Boolean) {
        sprite(t)
        for (y in 12..15) for (x in 4..11) t[x, y] = scale(rgb(120, 120, 122), t.jitter(0.08f))
        for (i in 0..8) {
            val x = if (on) 8 + i / 2 else 7 - i / 2
            t[x, 11 - i] = rgb(120, 88, 50)
        }
        val tipX = if (on) 12 else 3
        t[tipX, 3] = if (on) rgb(220, 30, 20) else rgb(110, 20, 16)
    }

    private fun dust(t: Tile, on: Boolean) {
        sprite(t)
        val c = if (on) rgb(255, 40, 20) else rgb(110, 12, 8)
        for (i in 0 until 16) {
            if (t.rnd.nextInt(5) != 0) t[i, 7 + t.rnd.nextInt(2)] = scale(c, t.jitter(0.2f))
            if (t.rnd.nextInt(5) != 0) t[7 + t.rnd.nextInt(2), i] = scale(c, t.jitter(0.2f))
        }
        repeat(10) { t[5 + t.rnd.nextInt(6), 5 + t.rnd.nextInt(6)] = scale(c, t.jitter(0.25f)) }
    }

    private fun pistonSide(t: Tile) {
        t.fill { x, y ->
            when {
                y < 4 -> scale(rgb(166, 132, 80), (if (y == 3) 0.7f else 1f) * t.jitter(0.05f))
                x == 0 || x == 15 || y == 15 -> rgb(80, 80, 80)
                else -> scale(rgb(120, 120, 120), t.jitter(0.08f))
            }
        }
    }

    private fun pistonFront(t: Tile, sticky: Boolean) {
        planks(t)
        bevel(t, rgb(190, 156, 100), rgb(110, 84, 50))
        for (y in 6..9) for (x in 6..9) t[x, y] = rgb(120, 120, 120)
        if (sticky) for (y in 3..12) for (x in 3..12) {
            if (abs(x - 7.5f) + abs(y - 7.5f) < 7f) t[x, y] = scale(rgb(110, 190, 90), t.jitter(0.1f))
        }
    }

    private fun faceOn(t: Tile, glow: Int) {
        pumpkinSide(t)
        // Carved triangle eyes and a zig-zag grin.
        for (y in 0..2) for (x in 0..(2 - y) * 2) { t[3 + y + x - (2 - y), 4 + y] = glow; t[11 + y + x - (2 - y), 4 + y] = glow }
        for (x in 3..12) { t[x, 10] = glow; t[x, 11] = glow }
        for (x in 3..12 step 3) t[x, 12] = glow
        for (x in 4..12 step 3) t[x, 9] = glow
    }

    private fun craftingTop(t: Tile) {
        planks(t, rgb(150, 110, 64))
        bevel(t, rgb(110, 80, 44), rgb(90, 64, 36))
        for (i in 2..13) { t[i, 5] = rgb(80, 58, 30); t[i, 10] = rgb(80, 58, 30); t[5, i] = rgb(80, 58, 30); t[10, i] = rgb(80, 58, 30) }
    }

    private fun craftingSide(t: Tile, front: Boolean) {
        planks(t)
        for (x in 0 until TILE) for (y in 0..2) t[x, y] = scale(rgb(120, 86, 48), t.jitter(0.05f))
        // A hammer and a saw hanging on the side.
        val iron = rgb(170, 170, 176)
        if (front) {
            for (y in 5..13) t[4, y] = rgb(96, 70, 40)
            for (x in 2..6) { t[x, 5] = iron; t[x, 6] = iron }
            for (y in 5..12) for (x in 9..12) if (x - 9 <= (y - 5) / 2) t[x, y] = iron
            for (y in 4..5) for (x in 9..10) t[x, y] = rgb(96, 70, 40)
        } else {
            for (y in 5..13) t[7, y] = rgb(96, 70, 40)
            for (x in 5..10) { t[x, 5] = iron; t[x, 6] = iron }
        }
    }

    private fun furnaceFront(t: Tile) {
        stone(t, rgb(118, 118, 118))
        bevel(t, rgb(150, 150, 150), rgb(70, 70, 70))
        for (y in 8..13) for (x in 4..11) t[x, y] = if (y == 8 || x == 4 || x == 11) rgb(60, 60, 60) else rgb(26, 24, 24)
        for (x in 3..12) t[x, 5] = rgb(70, 70, 70)
    }

    private fun chest(t: Tile, face: String) {
        val wood = rgb(160, 108, 48)
        t.fill { x, y ->
            val edge = x == 0 || y == 0 || x == 15 || y == 15
            when {
                edge -> rgb(70, 44, 18)
                face != "top" && y == 5 -> rgb(70, 44, 18)
                else -> scale(wood, (if ((y + x / 5) % 4 == 0) 0.9f else 1f) * t.jitter(0.05f))
            }
        }
        if (face == "front") for (y in 4..7) for (x in 7..8) t[x, y] = rgb(200, 200, 210)
    }

    private fun tnt(t: Tile, face: String) {
        val red = rgb(196, 48, 36)
        when (face) {
            "side" -> t.fill { x, y ->
                when {
                    y in 5..10 -> if (y == 5 || y == 10) rgb(40, 40, 40) else if ((x / 2 + y) % 3 == 0) rgb(200, 200, 200) else rgb(236, 232, 224)
                    x % 4 == 0 -> scale(red, 0.75f)
                    else -> scale(red, t.jitter(0.06f))
                }
            }
            "top" -> t.fill { x, y ->
                val d = max(abs(x - 7.5f), abs(y - 7.5f))
                when {
                    d < 1.6f -> rgb(60, 60, 60)
                    d < 3f -> rgb(220, 216, 200)
                    else -> scale(red, t.jitter(0.06f))
                }
            }
            "bottom" -> noisy(t, red, 0.06f, 0.2f)
            else -> noisy(t, rgb(250, 246, 240), 0.03f, 0.1f)
        }
    }

    // ---------------------------------------------------------------- items

    private fun tool(t: Tile, kind: String, mat: Int) {
        sprite(t)
        val handle = rgb(137, 103, 55); val handleDark = rgb(84, 60, 30)
        val dark = scale(mat, 0.55f); val light = scale(mat, 1.3f)
        fun p(x: Int, y: Int, c: Int) { if (x in 0..15 && y in 0..15) t[x, y] = c }
        if (kind == "sword") {
            for (i in 0..9) { p(5 + i, 10 - i, light); p(6 + i, 10 - i, mat); p(5 + i, 11 - i, dark) }
            p(15, 0, dark)
            for (i in -2..2) p(5 + i, 10 + i, dark)
            p(4, 11, handle); p(3, 12, handle); p(2, 13, handle); p(4, 12, handleDark); p(3, 13, handleDark)
            p(1, 14, dark); p(2, 14, dark)
            return
        }
        val mask = TOOL_MASKS.getValue(kind)
        val dy = (16 - mask.size) / 2
        for ((y, row) in mask.withIndex()) for ((x, ch) in row.withIndex()) {
            when (ch) {
                'h' -> p(x, y + dy, handle); 'k' -> p(x, y + dy, handleDark)
                'm' -> p(x, y + dy, mat); 'd' -> p(x, y + dy, dark); 'l' -> p(x, y + dy, light)
            }
        }
    }

    /** Hand-made 16px tool silhouettes: h/k handle, m/d/l head (mid, dark, light). */
    private val TOOL_MASKS = mapOf(
        "pickaxe" to arrayOf(
            "......dddd......",
            "....ddmmmmdd....",
            "...dmlllmmmmd...",
            "....dd..kmmmmd..",
            ".......kh.dmmmd.",
            "......kh...dmmd.",
            ".....kh.....dmd.",
            "....kh......dmd.",
            "...kh........dd.",
            "..kh............",
            ".kh.............",
            "kh..............",
        ),
        "axe" to arrayOf(
            "......dd........",
            ".....dmmd.......",
            "....dlmmmd......",
            "....dlmmmkd.....",
            "....dlmmkhmd....",
            ".....dlkh.dd....",
            "......kh........",
            ".....kh.........",
            "....kh..........",
            "...kh...........",
            "..kh............",
            ".kh.............",
            "kh..............",
        ),
        "shovel" to arrayOf(
            "..........ddd...",
            ".........dlmmd..",
            "........dlmmmmd.",
            "........dmmmmmd.",
            ".........dmmmd..",
            "........kh.dd...",
            ".......kh.......",
            "......kh........",
            ".....kh.........",
            "....kh..........",
            "...kh...........",
            "..kh............",
            ".kh.............",
            "kh..............",
        ),
        "hoe" to arrayOf(
            ".....ddddd......",
            "....dlmmmmd.....",
            ".....ddddkhd....",
            "........kh......",
            ".......kh.......",
            "......kh........",
            ".....kh.........",
            "....kh..........",
            "...kh...........",
            "..kh............",
            ".kh.............",
            "kh..............",
        ),
    )

    private val TIER_COLORS = mapOf(
        "wood" to rgb(160, 124, 70), "stone" to rgb(140, 140, 140), "iron" to rgb(222, 222, 226),
        "gold" to rgb(250, 214, 64), "diamond" to rgb(80, 226, 214), "netherite" to rgb(76, 68, 72),
    )

    private fun flintAndSteel(t: Tile) {
        sprite(t)
        val steel = rgb(190, 190, 196)
        for (a in 0..20) {
            val ang = Math.PI * (0.2 + a / 20.0 * 1.3)
            t[(10 + Math.cos(ang) * 4).toInt(), (5 + Math.sin(ang) * 4).toInt()] = steel
        }
        for (y in 9..14) for (x in 2..7) if (abs(x - 4.5f) + abs(y - 11.5f) < 4f) t[x, y] = scale(rgb(60, 60, 64), t.jitter(0.15f))
    }

    private fun bucket(t: Tile, water: Boolean) {
        sprite(t)
        val metal = rgb(196, 196, 200)
        for (y in 5..14) {
            val inset = (y - 5) / 4
            for (x in 3 + inset..12 - inset) {
                t[x, y] = if (x == 3 + inset || x == 12 - inset || y == 14) scale(metal, 0.7f) else metal
            }
        }
        for (x in 3..12) t[x, 5] = scale(metal, 0.6f)
        if (water) for (x in 4..11) { t[x, 6] = rgb(60, 110, 220); t[x, 7] = rgb(40, 84, 196) }
        for (i in 0..8) t[3 + i, 3 - (if (i in 2..6) 1 else 0) + (if (i == 0 || i == 8) 1 else 0)] = scale(metal, 0.6f)
    }

    // ---------------------------------------------------------------- mob skins (original designs)

    private fun patchy(t: Tile, base: Int, patch: Int, cells: Int, share: Int) {
        val (ids, _) = voronoi(t.rnd, cells)
        val dark = BooleanArray(cells) { t.rnd.nextInt(100) < share }
        t.fill { x, y -> scale(if (dark[ids[y][x]]) patch else base, t.jitter(0.05f)) }
    }

    private fun eyes(t: Tile, y: Int, lx: Int, rx: Int, white: Int = rgb(240, 240, 240), pupil: Int = rgb(20, 20, 20)) {
        t[lx, y] = white; t[lx + 1, y] = pupil; t[rx, y] = pupil; t[rx + 1, y] = white
    }

    private fun hoof(t: Tile, rows: Int, c: Int) { for (y in 16 - rows until 16) for (x in 0 until 16) t[x, y] = scale(c, t.jitter(0.05f)) }

    private fun mobSkin(name: String, t: Tile): Boolean {
        val cowWhite = rgb(236, 232, 222); val cowBrown = rgb(78, 52, 38)
        val pig = rgb(238, 166, 166)
        val zSkin = rgb(104, 132, 96)
        val shell = rgb(62, 60, 66)
        when (name) {
            "cow_hide" -> patchy(t, cowWhite, cowBrown, 9, 40)
            "cow_leg" -> { patchy(t, cowWhite, cowBrown, 5, 40); hoof(t, 3, rgb(52, 42, 36)) }
            "cow_face" -> {
                t.fill { x, _ -> if (x in 6..9) scale(cowWhite, t.jitter(0.04f)) else scale(rgb(112, 76, 52), t.jitter(0.05f)) }
                eyes(t, 6, 3, 11)
                for (y in 10..15) for (x in 2..13) t[x, y] = scale(rgb(224, 188, 172), t.jitter(0.04f))
                t[5, 12] = rgb(90, 60, 60); t[10, 12] = rgb(90, 60, 60)
            }
            "horn" -> noisy(t, rgb(228, 220, 196), 0.04f, 0.1f)
            "pig_skin" -> noisy(t, pig, 0.04f, 0.15f)
            "pig_leg" -> { noisy(t, pig, 0.04f, 0.15f); hoof(t, 2, rgb(150, 92, 92)) }
            "pig_face" -> {
                noisy(t, pig, 0.04f, 0.1f)
                eyes(t, 5, 3, 11)
                for (y in 8..12) for (x in 5..10) t[x, y] = rgb(214, 122, 130)
                t[6, 10] = rgb(140, 64, 74); t[9, 10] = rgb(140, 64, 74)
            }
            "sheep_wool" -> {
                noisy(t, rgb(236, 236, 230), 0.03f, 0.1f)
                repeat(14) {
                    val cx = t.rnd.nextInt(16); val cy = t.rnd.nextInt(16)
                    t[cx, cy] = rgb(208, 208, 200); t[cx + 1, cy] = rgb(214, 214, 206); t[cx, cy + 1] = rgb(214, 214, 206)
                }
            }
            "sheep_leg" -> noisy(t, rgb(200, 182, 160), 0.05f, 0.1f)
            "sheep_face" -> {
                t.fill { x, y ->
                    if (y < 3 || x < 2 || x > 13) scale(rgb(236, 236, 230), t.jitter(0.04f)) else scale(rgb(202, 182, 160), t.jitter(0.04f))
                }
                eyes(t, 7, 4, 10)
                t[7, 11] = rgb(120, 90, 80); t[8, 11] = rgb(120, 90, 80)
            }
            "zombie_skin" -> patchy(t, zSkin, rgb(82, 106, 76), 8, 35)
            "zombie_hair" -> {
                noisy(t, rgb(46, 40, 34), 0.1f, 0.2f)
                repeat(12) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = zSkin }
            }
            "zombie_face" -> {
                patchy(t, zSkin, rgb(88, 112, 80), 6, 30)
                for (x in 0 until 16) { t[x, 0] = rgb(46, 40, 34); t[x, 1] = rgb(46, 40, 34) }
                for (x in 2..6) t[x, 4] = rgb(60, 70, 54)
                for (x in 9..13) t[x, 4] = rgb(60, 70, 54)
                for (y in 6..7) { for (x in 3..5) t[x, y] = rgb(30, 34, 30); for (x in 10..12) t[x, y] = rgb(30, 34, 30) }
                t[4, 6] = rgb(230, 220, 120); t[11, 6] = rgb(230, 220, 120)
                for (x in 4..11) t[x, 11] = rgb(34, 30, 28)
                t[5, 11] = rgb(200, 196, 170); t[8, 11] = rgb(200, 196, 170); t[10, 12] = rgb(34, 30, 28)
                t[13, 8] = rgb(60, 50, 60); t[13, 9] = rgb(60, 50, 60); t[12, 9] = rgb(60, 50, 60)
            }
            "zombie_shirt" -> {
                noisy(t, rgb(112, 52, 40), 0.08f, 0.2f)
                for (y in 9..12) for (x in 2..5) t[x, y] = scale(rgb(132, 112, 72), t.jitter(0.05f))
                for (x in 0 until 16) if (t.rnd.nextInt(3) == 0) t[x, 15] = zSkin
                t[11, 4] = zSkin; t[12, 4] = zSkin; t[11, 5] = zSkin
            }
            "zombie_pants" -> {
                noisy(t, rgb(64, 66, 58), 0.08f, 0.2f)
                for (x in 4..11) if (t.rnd.nextInt(2) == 0) t[x, 8 + t.rnd.nextInt(2)] = zSkin
                for (x in 0 until 16) { t[x, 14] = rgb(40, 36, 32); t[x, 15] = rgb(40, 36, 32) }
            }
            "boomling_shell" -> {
                val (_, edge) = voronoi(t.rnd, 7)
                t.fill { x, y -> if (edge[y][x] < 0.9f) rgb(240, 124, 34) else scale(shell, t.jitter(0.1f)) }
            }
            "boomling_leg" -> noisy(t, rgb(42, 40, 44), 0.1f, 0.2f)
            "boomling_face" -> {
                val (_, edge) = voronoi(t.rnd, 5)
                t.fill { x, y -> if (edge[y][x] < 0.7f && y > 12) rgb(200, 100, 30) else scale(shell, t.jitter(0.08f)) }
                for (y in 4..6) { for (x in 3..5) t[x, y] = rgb(255, 222, 80); for (x in 10..12) t[x, y] = rgb(255, 222, 80) }
                t[4, 5] = rgb(120, 40, 10); t[11, 5] = rgb(120, 40, 10)
                for (x in 3..12) t[x, if (x % 2 == 0) 9 else 10] = rgb(255, 150, 40)
            }
            "boomling_fuse" -> t.fill { _, y ->
                when {
                    y < 3 -> rgb(255, 210, 70)
                    y % 4 == 0 -> rgb(110, 84, 50)
                    else -> scale(rgb(172, 142, 92), t.jitter(0.05f))
                }
            }
            else -> return newMobSkin(name, t)
        }
        return true
    }

    /** Skins for the Stage 3 creatures (all original designs). */
    private fun newMobSkin(name: String, t: Tile): Boolean {
        when (name) {
            "rattler_bark" -> { logSide(t, rgb(92, 70, 46), rgb(60, 44, 28), false); repeat(6) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = rgb(80, 120, 50) } }
            "rattler_chest" -> { logSide(t, rgb(92, 70, 46), rgb(60, 44, 28), false); for (y in 3..12) t[7, y] = rgb(60, 150, 140); t[6, 6] = rgb(60, 150, 140); t[8, 9] = rgb(60, 150, 140) }
            "rattler_hood" -> leaves(t, rgb(56, 104, 44))
            "rattler_face" -> {
                t.fill { x, y -> if (y < 3 || x < 2 || x > 13) scale(rgb(56, 104, 44), t.jitter(0.15f)) else scale(rgb(40, 30, 24), t.jitter(0.1f)) }
                for (x in 4..5) t[x, 7] = rgb(90, 250, 220); for (x in 10..11) t[x, 7] = rgb(90, 250, 220)
            }
            "crawler_shell" -> {
                val (ids, edge) = voronoi(t.rnd, 6)
                t.fill { x, y -> if (edge[y][x] < 0.9f) rgb(40, 48, 70) else scale(rgb(78, 94, 130), (0.85f + ids[y][x] * 0.04f) * t.jitter(0.05f)) }
            }
            "crawler_face" -> {
                noisy(t, rgb(70, 84, 118), 0.06f, 0.2f)
                for ((x, y) in listOf(3 to 5, 5 to 4, 10 to 4, 12 to 5)) { t[x, y] = rgb(255, 190, 60); t[x + 1, y] = rgb(255, 190, 60) }
                for (x in 5..10) t[x, 11] = rgb(30, 30, 40)
            }
            "crawler_leg" -> noisy(t, rgb(50, 58, 84), 0.1f, 0.2f)
            "glider_body" -> noisy(t, rgb(80, 60, 110), 0.08f, 0.2f)
            "glider_wing" -> {
                noisy(t, rgb(96, 70, 140), 0.06f, 0.2f)
                repeat(7) { val x = t.rnd.nextInt(15); val y = t.rnd.nextInt(15); t[x, y] = rgb(220, 210, 240); t[x + 1, y] = rgb(200, 190, 230) }
                for (x in 0 until 16) t[x, 15] = rgb(50, 36, 70)
            }
            "cinder_skin" -> {
                val (_, edge) = voronoi(t.rnd, 8)
                t.fill { x, y -> if (edge[y][x] < 1f) rgb(255, 150, 30) else scale(rgb(70, 30, 22), t.jitter(0.12f)) }
            }
            "cinder_face" -> {
                t.fill { _, _ -> scale(rgb(70, 30, 22), t.jitter(0.1f)) }
                for (x in 3..5) { t[x, 6] = rgb(255, 230, 90); t[x, 7] = rgb(255, 180, 40) }
                for (x in 10..12) { t[x, 6] = rgb(255, 230, 90); t[x, 7] = rgb(255, 180, 40) }
                for (x in 4..11) t[x, 11] = if (x % 2 == 0) rgb(255, 150, 30) else rgb(40, 16, 10)
            }
            "wisp_core" -> {
                val vn = valueNoise(t.rnd, 4)
                t.fill { x, y -> mix(rgb(120, 60, 200), rgb(220, 180, 255), vn[y][x]) }
            }
            "wisp_face" -> {
                val vn = valueNoise(t.rnd, 4)
                t.fill { x, y -> mix(rgb(120, 60, 200), rgb(220, 180, 255), vn[y][x]) }
                for (y in 5..8) { t[5, y] = rgb(20, 10, 40); t[10, y] = rgb(20, 10, 40) }
            }
            "wisp_ring" -> t.fill { x, y -> if ((x + y) % 3 == 0) rgb(250, 240, 180) else rgb(200, 180, 110) }
            "villager_skin" -> noisy(t, rgb(224, 176, 140), 0.03f, 0.08f)
            "villager_face" -> {
                noisy(t, rgb(224, 176, 140), 0.03f, 0.08f)
                for (x in 0 until 16) { t[x, 0] = rgb(110, 70, 40); t[x, 1] = rgb(110, 70, 40) }
                t[4, 6] = rgb(40, 60, 90); t[11, 6] = rgb(40, 60, 90)
                t[3, 9] = rgb(236, 150, 140); t[12, 9] = rgb(236, 150, 140)
                for (x in 6..9) t[x, 11] = rgb(150, 80, 70)
            }
            "villager_hat" -> t.fill { x, y -> scale(if ((x + y) % 3 == 0) rgb(220, 190, 110) else rgb(236, 206, 124), t.jitter(0.04f)) }
            "villager_tunic" -> { noisy(t, rgb(70, 130, 70), 0.06f, 0.15f); for (x in 0 until 16) t[x, 12] = rgb(110, 80, 40) }
            "villager_pants" -> noisy(t, rgb(100, 80, 60), 0.06f, 0.15f)
            "explorer_hair" -> noisy(t, rgb(96, 64, 38), 0.08f, 0.2f)
            "explorer_face" -> {
                t.fill { _, y -> if (y < 4) scale(rgb(96, 64, 38), t.jitter(0.08f)) else scale(rgb(214, 170, 132), t.jitter(0.03f)) }
                t[2, 4] = rgb(96, 64, 38); t[13, 4] = rgb(96, 64, 38); t[2, 5] = rgb(96, 64, 38); t[13, 5] = rgb(96, 64, 38)
                t[4, 7] = rgb(250, 250, 250); t[5, 7] = rgb(60, 110, 60); t[10, 7] = rgb(60, 110, 60); t[11, 7] = rgb(250, 250, 250)
                for (x in 6..9) t[x, 11] = rgb(170, 100, 90)
            }
            "explorer_jacket" -> { noisy(t, rgb(222, 120, 40), 0.05f, 0.15f); for (x in 0 until 16) { t[x, 0] = rgb(60, 140, 90); t[x, 1] = rgb(60, 140, 90) } }
            "explorer_jacket_front" -> {
                noisy(t, rgb(222, 120, 40), 0.05f, 0.15f)
                for (x in 0 until 16) { t[x, 0] = rgb(60, 140, 90); t[x, 1] = rgb(60, 140, 90); t[x, 2] = rgb(50, 120, 80) }
                for (y in 3..15) t[7, y] = rgb(150, 76, 24)
                t[4, 8] = rgb(250, 220, 90); t[10, 8] = rgb(250, 220, 90)
            }
            "explorer_trousers" -> { noisy(t, rgb(62, 62, 72), 0.05f, 0.15f); for (x in 0 until 16) { t[x, 13] = rgb(110, 72, 40); t[x, 14] = rgb(110, 72, 40); t[x, 15] = rgb(90, 58, 32) } }
            "minecart" -> mask(t, arrayOf("dddddddddddd", "dmmmmmmmmmmd", "dmllllllllmd", "dmmmmmmmmmmd", "dmmmmmmmmmmd", ".dddddddddd.", "..aa....aa..", "..aa....aa.."), rgb(150, 150, 156), rgb(60, 60, 60))
            "cart_side" -> t.fill { x, y -> if (y < 2 || y > 13 || x == 0 || x == 15) rgb(90, 90, 96) else scale(rgb(150, 150, 156), t.jitter(0.05f)) }
            "cart_floor" -> planks(t, rgb(130, 100, 60))
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- survival items (original pixel art)

    /** Draws a mask: '.' clear, 'd' dark, 'm' main, 'l' light, 'a'/'b' accent colours. */
    private fun mask(t: Tile, rows: Array<String>, main: Int, accentA: Int = main, accentB: Int = main) {
        sprite(t)
        val dy = (16 - rows.size) / 2
        for ((y, row) in rows.withIndex()) {
            val dx = (16 - row.length) / 2
            for ((x, ch) in row.withIndex()) {
                val c = when (ch) {
                    'd' -> scale(main, 0.55f); 'm' -> main; 'l' -> scale(main, 1.3f)
                    'a' -> accentA; 'b' -> accentB; else -> continue
                }
                t[x + dx, y + dy] = c
            }
        }
    }

    private val INGOT = arrayOf(
        "....dddddd..", "...dllllmmd.", "..dlmmmmmmd.", ".dlmmmmmmmd.", "dmmmmmmmmdd.", "dmmmmmmmdd..", ".ddddddddd..",
    )
    private val GEM = arrayOf(
        "...dddd...", "..dllmmd..", ".dlmmmmmd.", "dlmmmmmmmd", ".dmmmmmmd.", "..dmmmmd..", "...dmmd...", "....dd....",
    )
    private val LUMP = arrayOf(
        "...dddd...", "..dmmmmdd.", ".dmlmmmmmd", "dmmmmmmlmd", "dmmmlmmmmd", ".dmmmmmmd.", "..dddddd..",
    )
    private val DUST = arrayOf(
        "..m...m.", ".mlm.m..", "..m..mlm", ".m.m..m.", "mlm..m..", ".m..mlm.", "...m.m..",
    )
    private val MEAT = arrayOf(
        "....dddd....", "..ddmmmmdd..", ".dmmmmmmmmd.", "dmmmlmmmmmmd", "dmmmmmmmlmmd", ".dmmmmmmmmd.", "..ddmmmmdaa.",
        "....ddddaaa.", "..........aa",
    )
    private val HELMET = arrayOf(
        "..dddddddddd..", ".dmmmmmmmmmmd.", "dmllmmmmmmmmmd", "dmmmmmmmmmmmmd", "dmmdd....ddmmd", "dmd........dmd", "dd..........dd",
    )
    private val CHEST = arrayOf(
        "ddd......ddd", "dmmd....dmmd", "dmmmddddmmmd", ".dmlmmmmmmd.", ".dmmmmmmmmd.", ".dmmmmmmmmd.", ".dmmmmmmmmd.",
        ".dmmmmmmmmd.", ".dmmmmmmmmd.", ".dddddddddd.",
    )
    private val LEGS = arrayOf(
        "dddddddddd", "dmmmmmmmmd", "dmlmmmmmmd", "dmmmddmmmd", "dmmd..dmmd", "dmmd..dmmd", "dmmd..dmmd", "dmmd..dmmd",
        "dddd..dddd",
    )
    private val BOOTS = arrayOf(
        "dmmd....dmmd", "dmmd....dmmd", "dmmd....dmmd", "dmmmd...dmmmd", "dmlmmd..dmlmmd", "ddddddd.ddddddd",
    )

    private fun survivalItem(name: String, t: Tile): Boolean {
        val armorColors = mapOf(
            "leather" to rgb(150, 90, 50), "gold" to rgb(250, 214, 64), "iron" to rgb(214, 214, 218),
            "diamond" to rgb(80, 226, 214), "netherite" to rgb(76, 68, 72),
        )
        for ((key, c) in armorColors) when (name) {
            "helmet_$key" -> { mask(t, HELMET, c); return true }
            "chestplate_$key" -> { mask(t, CHEST, c); return true }
            "leggings_$key" -> { mask(t, LEGS, c); return true }
            "boots_$key" -> { mask(t, BOOTS, c); return true }
        }
        when (name) {
            "stick" -> { sprite(t); for (i in 0..10) { t[3 + i, 13 - i] = rgb(137, 103, 55); t[3 + i, 14 - i] = rgb(84, 60, 30) } }
            "coal" -> mask(t, LUMP, rgb(46, 46, 50))
            "charcoal" -> mask(t, LUMP, rgb(70, 58, 46))
            "iron_ingot" -> mask(t, INGOT, rgb(214, 214, 218))
            "gold_ingot" -> mask(t, INGOT, rgb(248, 206, 56))
            "copper_ingot" -> mask(t, INGOT, rgb(214, 124, 84))
            "netherite_ingot" -> mask(t, INGOT, rgb(80, 72, 76))
            "netherite_scrap" -> mask(t, LUMP, rgb(110, 84, 72))
            "brick_item" -> mask(t, INGOT, rgb(170, 84, 60))
            "diamond" -> mask(t, GEM, rgb(80, 226, 214))
            "emerald" -> mask(t, GEM, rgb(50, 210, 100))
            "lapis" -> mask(t, LUMP, rgb(40, 80, 200))
            "flint" -> mask(t, GEM, rgb(64, 64, 70))
            "clay_ball" -> mask(t, LUMP, rgb(164, 170, 184))
            "slimeball" -> mask(t, LUMP, rgb(112, 196, 90))
            "gunpowder" -> mask(t, DUST, rgb(90, 90, 96))
            "glowstone_dust" -> mask(t, DUST, rgb(250, 214, 110))
            "leather" -> mask(t, arrayOf("..dddddd..", ".dmmmmmmd.", "dmmmlmmmmd", "dmmmmmmmmd", "dmmmmmmmmd", ".dmmmmmmd.", "..dd..dd.."), rgb(150, 90, 50))
            "string" -> { sprite(t); var x = 3f; for (y in 2..13) { t[x.toInt(), y] = rgb(236, 236, 236); x += if (y % 4 < 2) 1f else -0.5f } }
            "feather" -> mask(t, arrayOf("....ll", "...lml", "..lmm.", ".lmm..", ".mm...", "dd....", "d....."), rgb(236, 236, 236))
            "bone" -> mask(t, arrayOf("ll......", "lml.....", ".lmm....", "..mmm...", "...mmm..", "....mml.", ".....lml", "......ll"), rgb(236, 230, 210))
            "wheat_seeds" -> { sprite(t); repeat(7) { t[4 + t.rnd.nextInt(8), 5 + t.rnd.nextInt(7)] = rgb(90, 150, 50) } }
            "wheat" -> { sprite(t); for (k in 0..2) for (y in 2..14) t[5 + k * 3 - (14 - y) / 6, y] = if (y < 7) rgb(214, 180, 70) else rgb(170, 140, 60) }
            "paper" -> mask(t, arrayOf("mmmmmmmmm.", "mdddddddmm", "mmmmmmmmmm", "mdddddddmm", "mmmmmmmmmm", "mddddddmmm", "mmmmmmmmmm", ".mmmmmmmmm"), rgb(236, 232, 214))
            "book" -> mask(t, arrayOf("ddddddddd.", "dmmmmmmmad", "dmlmmmmmad", "dmmmmmmmad", "dmmmmmmmad", "dmmmmmmmad", "ddddddddd."), rgb(130, 60, 40), rgb(236, 232, 214))
            "arrow" -> { sprite(t); for (i in 0..9) t[3 + i, 12 - i] = rgb(137, 103, 55); t[13, 2] = rgb(120, 120, 120); t[12, 2] = rgb(120, 120, 120); t[13, 3] = rgb(120, 120, 120); t[2, 12] = rgb(236, 236, 236); t[3, 13] = rgb(236, 236, 236); t[2, 13] = rgb(236, 236, 236) }
            "bow" -> { sprite(t); for (a in 0..24) { val ang = Math.PI * (0.75 + a / 24.0); t[(11 + Math.cos(ang) * 8).toInt().coerceIn(0, 15), (4 + Math.sin(ang) * -8 + 8).toInt().coerceIn(0, 15)] = rgb(137, 103, 55) }; for (i in 0..11) t[4 + i * 9 / 11, 12 - i] = rgb(230, 230, 230) }
            "apple" -> mask(t, arrayOf("....a....", "...a.....", ".dmmdmmd.", "dmlmmmmmd", "dmmmmmmmd", "dmmmmmmmd", ".dmmmmmd.", "..ddddd.."), rgb(210, 36, 40), rgb(110, 76, 40))
            "golden_apple" -> mask(t, arrayOf("....a....", "...a.....", ".dmmdmmd.", "dmlmmmmmd", "dmmmmmmmd", "dmmmmmmmd", ".dmmmmmd.", "..ddddd.."), rgb(250, 214, 64), rgb(110, 76, 40))
            "bread" -> mask(t, arrayOf("..dddddddd..", ".dmlmlmlmmd.", "dmmmmmmmmmmd", "dmmmmmmmmmmd", ".dddddddddd."), rgb(196, 140, 70))
            "raw_beef" -> mask(t, MEAT, rgb(200, 60, 60), rgb(236, 230, 210))
            "steak" -> mask(t, MEAT, rgb(130, 76, 44), rgb(236, 230, 210))
            "raw_porkchop" -> mask(t, MEAT, rgb(240, 150, 150), rgb(236, 230, 210))
            "cooked_porkchop" -> mask(t, MEAT, rgb(196, 140, 90), rgb(236, 230, 210))
            "raw_mutton" -> mask(t, MEAT, rgb(210, 80, 80), rgb(236, 230, 210))
            "cooked_mutton" -> mask(t, MEAT, rgb(150, 90, 60), rgb(236, 230, 210))
            "rotten_flesh" -> mask(t, MEAT, rgb(120, 110, 60), rgb(90, 120, 70))
            "melon_slice" -> mask(t, arrayOf("aaaaaaaaaa", ".mmmmmmmm.", ".mmdmmdmm.", "..mmmmmm..", "...mmmm...", "....mm...."), rgb(230, 60, 60), rgb(80, 150, 40))
            "ancient_debris" -> t.fill { x, y -> scale(if ((x + y * 3) % 7 == 0) rgb(130, 96, 84) else rgb(90, 66, 60), t.jitter(0.1f)) }
            "ancient_debris_top" -> { noisy(t, rgb(96, 72, 66), 0.1f, 0.3f); for (d in 1..6 step 2) for (i in 7 - d..8 + d) { t[i, 7 - d] = rgb(130, 96, 84); t[i, 8 + d] = rgb(130, 96, 84) } }
            "lava" -> {
                val vn = valueNoise(t.rnd, 4)
                t.fill { x, y -> val w = vn[y][x]; if ((x + y * 2) % 7 == 0 && w > 0.55f) rgb(255, 230, 120) else mix(rgb(200, 60, 10), rgb(255, 150, 30), w) }
            }
            "carrot" -> mask(t, arrayOf("....aa.a", ".....aa.", "....dmd.", "...dmmd.", "..dmmd..", ".dmmd...", "dmmd....", "dd......"), rgb(240, 130, 30), rgb(80, 160, 50))
            "potato" -> mask(t, LUMP, rgb(200, 160, 90))
            "baked_potato" -> mask(t, LUMP, rgb(214, 170, 80), rgb(250, 230, 140))
            "bone_meal" -> mask(t, DUST, rgb(236, 236, 226))
            "lava_bucket" -> { bucket(t, false); for (x in 4..11) { t[x, 6] = rgb(250, 130, 20); t[x, 7] = rgb(220, 80, 10) } }
            "furnace_front_on" -> { furnaceFront(t); for (y in 9..13) for (x in 5..10) t[x, y] = if ((x + y) % 2 == 0) rgb(255, 170, 40) else rgb(250, 110, 20) }
            else -> return farming(name, t)
        }
        return true
    }

    private fun door(t: Tile, wood: Boolean, top: Boolean, woodColor: Int = rgb(150, 112, 62)) {
        val base = if (wood) woodColor else rgb(196, 196, 200)
        val dark = scale(base, 0.62f)
        t.fill { x, y ->
            val frame = x == 0 || x == 15 || (top && y == 0) || (!top && y == 15)
            when {
                frame -> dark
                wood && (x == 5 || x == 10) -> scale(base, 0.8f)
                !wood && (y % 5 == 2) -> scale(base, 0.85f)
                else -> scale(base, t.jitter(0.05f))
            }
        }
        if (top) {
            // A window in the upper half.
            for (y in 3..10) for (x in 3..12) t[x, y] = if (x == 7 || x == 8 || y == 6) dark else withAlpha(rgb(200, 226, 240), if (wood) 255 else 255)
        } else {
            t[12, 2] = if (wood) rgb(60, 60, 60) else rgb(90, 90, 90); t[12, 3] = t[12, 2]
        }
    }

    private fun trapdoor(t: Tile, base: Int, metal: Boolean) = t.fill { x, y ->
        when {
            x == 0 || y == 0 || x == 15 || y == 15 || x == 7 || x == 8 -> scale(base, 0.62f)
            (x in 2..5 || x in 10..13) && (y in 2..5 || y in 10..13) -> if (metal) scale(base, 0.8f) else 0
            else -> scale(base, t.jitter(0.05f))
        }
    }

    private fun buildingAndRedstone(name: String, t: Tile): Boolean {
        for ((key, w) in WOODS) {
            when (name) {
                "${key}_door_bottom" -> { door(t, true, false, w.planks); return true }
                "${key}_door_top" -> { door(t, true, true, w.planks); return true }
                "${key}_trapdoor" -> { trapdoor(t, w.planks, false); return true }
            }
        }
        when (name) {
            "iron_trapdoor" -> trapdoor(t, rgb(196, 196, 200), true)
            "oak_door_bottom" -> door(t, true, false)
            "oak_door_top" -> door(t, true, true)
            "iron_door_bottom" -> door(t, false, false)
            "iron_door_top" -> door(t, false, true)
            "oak_trapdoor" -> t.fill { x, y ->
                when {
                    x == 0 || y == 0 || x == 15 || y == 15 || x == 7 || x == 8 -> rgb(96, 72, 40)
                    (x in 2..5 || x in 10..13) && (y in 2..5 || y in 10..13) -> 0
                    else -> scale(rgb(150, 112, 62), t.jitter(0.05f))
                }
            }
            "ladder" -> t.fill { x, y ->
                when {
                    x in 2..3 || x in 12..13 -> scale(rgb(140, 104, 58), t.jitter(0.05f))
                    y % 4 == 1 && x in 4..11 -> rgb(160, 122, 70)
                    else -> 0
                }
            }
            "iron_bars" -> t.fill { x, y -> if (x % 4 == 1 || y == 1 || y == 14) scale(rgb(120, 122, 126), t.jitter(0.05f)) else 0 }
            "repeater", "repeater_on" -> {
                noisy(t, rgb(160, 160, 160), 0.03f, 0.08f)
                val on = name.endsWith("on")
                val c = if (on) rgb(255, 50, 20) else rgb(110, 20, 16)
                for (y in 2..13) t[7, y] = c
                for (i in 0..2) { t[7 - i, 3 + i] = c; t[8 + i, 3 + i] = c }
                for (y in listOf(4, 10)) { t[6, y] = rgb(120, 88, 50); t[8, y] = rgb(120, 88, 50); t[7, y - 1] = c }
            }
            "observer_top", "observer_side" -> t.fill { x, y ->
                if (y % 5 == 0 || x == 0 || x == 15) rgb(70, 70, 72) else scale(rgb(104, 104, 108), t.jitter(0.05f))
            }
            "observer_front" -> {
                noisy(t, rgb(100, 100, 104), 0.05f, 0.1f)
                for (x in 2..13) { t[x, 4] = rgb(40, 40, 44); t[x, 11] = rgb(40, 40, 44) }
                for (y in 5..10) for (x in 3..12) t[x, y] = if ((x + y) % 3 == 0) rgb(60, 60, 64) else rgb(80, 80, 84)
            }
            "observer_back", "observer_back_on" -> {
                noisy(t, rgb(100, 100, 104), 0.05f, 0.1f)
                val c = if (name.endsWith("on")) rgb(255, 60, 30) else rgb(90, 20, 16)
                for (y in 6..9) for (x in 6..9) t[x, y] = c
            }
            "daylight_sensor" -> {
                planks(t, rgb(150, 112, 62))
                for (y in 2..13) for (x in 2..13) t[x, y] = if ((x + y) % 4 == 0) rgb(170, 200, 230) else rgb(110, 140, 180)
            }
            "daylight_sensor_side" -> planks(t, rgb(150, 112, 62))
            "hopper_top" -> t.fill { x, y ->
                val d = maxOf(abs(x - 7.5f), abs(y - 7.5f))
                if (d > 5.5f) rgb(80, 80, 84) else if (d > 4.5f) rgb(60, 60, 64) else rgb(30, 30, 32)
            }
            "hopper_side" -> t.fill { x, y -> scale(if (y < 3) rgb(90, 90, 94) else rgb(70, 70, 74), t.jitter(0.06f)) }
            "rail", "powered_rail", "powered_rail_on" -> {
                sprite(t)
                val tie = rgb(110, 80, 44)
                for (y in 0 until 16 step 4) for (x in 2..13) { t[x, y + 1] = tie; t[x, y + 2] = scale(tie, 0.85f) }
                val railC = when (name) { "rail" -> rgb(170, 170, 176); "powered_rail" -> rgb(200, 170, 60); else -> rgb(250, 210, 70) }
                for (y in 0 until 16) { t[3, y] = railC; t[12, y] = railC }
                if (name != "rail") for (y in 0 until 16 step 4) { t[7, y + 1] = if (name == "powered_rail_on") rgb(255, 50, 20) else rgb(110, 20, 16); t[8, y + 1] = t[7, y + 1] }
            }
            "rail_curve" -> {
                sprite(t)
                val railC = rgb(170, 170, 176)
                for (a in 0..40) {
                    val ang = a / 40.0 * Math.PI / 2
                    for ((r, c) in listOf(4.5 to railC, 13.0 to railC, 8.5 to rgb(110, 80, 44))) {
                        val x = (16 - r * Math.cos(ang)).toInt(); val y = (16 - r * Math.sin(ang)).toInt()
                        if (x in 0..15 && y in 0..15 && (c == railC || a % 6 < 3)) t[x, y] = c
                    }
                }
            }
            "ember_portal", "sky_portal" -> {
                val (a, b) = if (name == "ember_portal") rgb(190, 60, 20) to rgb(255, 170, 60) else rgb(40, 150, 200) to rgb(180, 240, 255)
                val vn = valueNoise(t.rnd, 4)
                t.fill { x, y -> withAlpha(mix(a, b, (vn[y][x] + ((x * 3 + y * 5) % 7) / 14f).coerceIn(0f, 1f)), 190) }
            }
            else -> return false
        }
        return true
    }

    /** Crop growth stages and saplings. */
    private fun farming(name: String, t: Tile): Boolean {
        val stem = rgb(80, 150, 50)
        when {
            name.startsWith("wheat_stage_") -> {
                val s = name.removePrefix("wheat_stage_").toInt()
                sprite(t)
                val h = 3 + s * 12 / 7
                val ripe = s >= 7
                for (k in 0..4) {
                    val x = 2 + k * 3
                    for (i in 0 until h) t[x, 15 - i] = if (ripe) rgb(200, 170, 70) else if (s >= 5) rgb(150, 160, 60) else stem
                    if (s >= 4) { t[x + 1, 16 - h] = if (ripe) rgb(220, 190, 90) else rgb(170, 180, 70); t[x, 15 - h] = if (ripe) rgb(230, 200, 100) else rgb(160, 170, 70) }
                }
            }
            name.startsWith("carrots_stage_") || name.startsWith("potatoes_stage_") -> {
                val s = name.substringAfterLast('_').toInt()
                sprite(t)
                val h = 3 + s * 3
                for (k in 0..3) {
                    val x = 3 + k * 3
                    for (i in 0 until h) t[x + (if (i % 3 == 1) 1 else 0), 15 - i] = if (i >= h - 2) rgb(96, 170, 60) else stem
                }
                if (s == 3) {
                    val c = if (name.startsWith("carrots")) rgb(240, 130, 30) else rgb(200, 160, 90)
                    for (k in 0..3) { t[3 + k * 3, 15] = c; t[4 + k * 3, 15] = c }
                }
            }
            name.startsWith("sapling_") -> {
                val leaves = WOODS[name.removePrefix("sapling_")]?.leaves ?: rgb(62, 126, 42)
                sprite(t)
                for (y in 8..15) t[7, y] = rgb(110, 80, 50)
                for (y in 2..10) for (x in 3..12) {
                    if (abs(x - 7.5f) + abs(y - 6f) * 1.2f < 5.5f && t.rnd.nextInt(5) != 0) t[x, y] = scale(leaves, t.jitter(0.15f))
                }
            }
            else -> return buildingAndRedstone(name, t)
        }
        return true
    }

    // ---------------------------------------------------------------- dispatch

    private fun paint(name: String, t: Tile) {
        if (mobSkin(name, t)) return
        if (survivalItem(name, t)) return
        when {
            name.startsWith("wool_") -> return wool(t, DYE_COLORS.getValue(name.removePrefix("wool_")))
            name.startsWith("concrete_") -> return noisy(t, DYE_COLORS.getValue(name.removePrefix("concrete_")), 0.02f, 0.05f)
            name.startsWith("terracotta_") -> {
                val c = mix(DYE_COLORS.getValue(name.removePrefix("terracotta_")), rgb(152, 94, 67), 0.4f)
                return noisy(t, scale(c, 0.85f), 0.04f, 0.15f)
            }
            name.startsWith("stained_glass_") -> {
                val c = DYE_COLORS.getValue(name.removePrefix("stained_glass_"))
                return t.fill { x, y ->
                    if (x == 0 || y == 0 || x == 15 || y == 15) withAlpha(scale(c, 1.1f), 230)
                    else if ((x == y + 4 && x in 5..9)) withAlpha(mix(c, rgb(255, 255, 255), 0.6f), 200)
                    else withAlpha(c, 120)
                }
            }
            name.startsWith("crack_") -> return
        }
        for ((key, tier) in TIER_COLORS) for (kind in listOf("sword", "pickaxe", "axe", "shovel", "hoe")) {
            if (name == "${kind}_$key") return tool(t, kind, tier)
        }
        for ((key, w) in WOODS) {
            when (name) {
                "${key}_log" -> return logSide(t, w.bark, w.barkDark, key == "birch")
                "${key}_log_top" -> return logTopColored(t, w)
                "${key}_planks" -> return planks(t, w.planks)
                "${key}_leaves" -> return leaves(t, w.leaves)
            }
        }
        when (name) {
            "white" -> t.fill { _, _ -> rgb(255, 255, 255) }
            "sun" -> sun(t)
            "moon" -> moon(t)
            "dirt" -> dirt(t)
            "grass_top" -> grassTop(t)
            "grass_side" -> { dirt(t); overlayTop(t, rgb(98, 160, 58), 4) }
            "stone" -> stone(t)
            "cobblestone" -> cobble(t, false)
            "mossy_cobblestone" -> cobble(t, true)
            "sand" -> noisy(t, rgb(220, 208, 162), 0.05f, 0.12f)
            "gravel" -> gravel(t)
            "glass" -> glass(t)
            "water" -> water(t)
            "bedrock" -> bedrock(t)
            "coal_ore" -> ore(t, rgb(40, 40, 40), 4)
            "iron_ore" -> ore(t, rgb(216, 176, 146), 3)
            "gold_ore" -> ore(t, rgb(250, 214, 60), 3)
            "diamond_ore" -> ore(t, rgb(90, 226, 222), 3)
            "redstone_ore" -> ore(t, rgb(210, 20, 16), 4)
            "lapis_ore" -> ore(t, rgb(34, 74, 196), 4)
            "emerald_ore" -> ore(t, rgb(40, 206, 96), 2)
            "copper_ore" -> ore(t, rgb(206, 116, 76), 4)
            "bricks" -> bricks(t)
            "stone_bricks" -> stoneBricks(t)
            "mossy_stone_bricks" -> {
                stoneBricks(t)
                val vn = valueNoise(t.rnd, 8)
                for (y in 0 until 16) for (x in 0 until 16) if (vn[y][x] > 0.58f) t[x, y] = scale(rgb(78, 122, 52), t.jitter(0.15f))
            }
            "cracked_stone_bricks" -> {
                stoneBricks(t)
                var x = 3; var y = 0
                while (y < 16) { t[x, y] = rgb(60, 60, 62); x = (x + t.rnd.nextInt(3) - 1).coerceIn(1, 14); y++ }
            }
            "chiseled_stone_bricks" -> t.fill { x, y ->
                val d = max(abs(x - 7.5f), abs(y - 7.5f)).toInt()
                if (d == 7 || d == 4 || d == 1) rgb(84, 84, 86) else scale(rgb(124, 124, 126), t.jitter(0.06f))
            }
            "snow" -> noisy(t, rgb(242, 250, 252), 0.03f, 0.08f)
            "snow_side" -> { dirt(t); overlayTop(t, rgb(242, 250, 252), 4) }
            "cactus_side" -> cactusSide(t)
            "cactus_top" -> cactusTop(t)
            "flower_red" -> flower(t, rgb(220, 40, 40))
            "flower_yellow" -> flower(t, rgb(250, 220, 50))
            "blue_orchid" -> flower(t, rgb(60, 170, 240))
            "tall_grass" -> tallGrass(t)
            "fern" -> {
                sprite(t)
                for (y in 3..15) {
                    t[7, y] = rgb(60, 120, 40)
                    val w = (15 - y) / 3 + 1
                    if (y % 2 == 0) for (i in 1..w) { t[7 - i, y - i / 2] = rgb(76, 140, 50); t[7 + i, y - i / 2] = rgb(70, 132, 46) }
                }
            }
            "dead_bush" -> deadBush(t)
            "glowstone" -> glowstone(t)
            "obsidian" -> obsidian(t)
            "crying_obsidian" -> { obsidian(t); repeat(10) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = rgb(150, 60, 240) } }
            "sandstone_side" -> sandstoneSide(t)
            "sandstone_top" -> noisy(t, rgb(222, 206, 156), 0.04f, 0.1f)
            "bookshelf" -> bookshelf(t)
            "ice" -> ice(t)
            "packed_ice" -> { noisy(t, rgb(150, 186, 246), 0.04f, 0.15f); repeat(6) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = rgb(220, 236, 255) } }
            "blue_ice" -> { noisy(t, rgb(110, 160, 250), 0.04f, 0.2f); for (i in 0 until 16) t[i, (i * 3) % 16] = rgb(190, 220, 255) }
            "clay" -> noisy(t, rgb(160, 166, 180), 0.04f, 0.15f)
            "pumpkin_side" -> pumpkinSide(t)
            "pumpkin_top" -> pumpkinTop(t)
            "jack_o_lantern_front" -> faceOn(t, rgb(255, 220, 90))
            "granite" -> speckled(t, rgb(154, 106, 88), rgb(190, 140, 120), 30)
            "polished_granite" -> polished(t, rgb(160, 110, 92))
            "diorite" -> speckled(t, rgb(196, 196, 196), rgb(110, 110, 112), 34)
            "polished_diorite" -> polished(t, rgb(200, 200, 202))
            "andesite" -> speckled(t, rgb(136, 136, 138), rgb(100, 100, 102), 30)
            "polished_andesite" -> polished(t, rgb(134, 138, 136))
            "deepslate" -> t.fill { _, y -> scale(rgb(80, 80, 86), (if (y % 4 == 0) 0.8f else 1f) * t.jitter(0.08f)) }
            "deepslate_top" -> noisy(t, rgb(84, 84, 90), 0.08f, 0.2f)
            "cobbled_deepslate" -> { cobble(t, false); for (i in t.px.indices) t.px[i] = scale(t.px[i], 0.6f) }
            "tuff" -> speckled(t, rgb(108, 108, 98), rgb(140, 138, 124), 24)
            "calcite" -> speckled(t, rgb(224, 226, 222), rgb(196, 198, 194), 20, 0.03f)
            "smooth_stone" -> { noisy(t, rgb(158, 158, 158), 0.03f, 0.08f); bevel(t, rgb(120, 120, 120), rgb(120, 120, 120)) }
            "smooth_stone_side" -> t.fill { x, y ->
                if (y == 0 || y == 15 || y == 7 || x == 0 || x == 15) rgb(120, 120, 120) else scale(rgb(160, 160, 160), t.jitter(0.03f))
            }
            "coal_block" -> mineralBlock(t, rgb(26, 26, 28), 0)
            "iron_block" -> mineralBlock(t, rgb(220, 220, 222), 1)
            "gold_block" -> mineralBlock(t, rgb(248, 206, 56), 1)
            "diamond_block" -> mineralBlock(t, rgb(96, 226, 220), 2)
            "emerald_block" -> mineralBlock(t, rgb(56, 208, 106), 2)
            "lapis_block" -> mineralBlock(t, rgb(38, 76, 172), 0)
            "redstone_block" -> mineralBlock(t, rgb(176, 20, 10), 2)
            "copper_block" -> mineralBlock(t, rgb(194, 110, 78), 1)
            "netherite_block" -> mineralBlock(t, rgb(68, 62, 64), 0)
            "quartz_block" -> mineralBlock(t, rgb(236, 230, 222), 0)
            "terracotta" -> noisy(t, rgb(152, 94, 67), 0.04f, 0.15f)
            "netherrack" -> { noisy(t, rgb(112, 46, 46), 0.12f, 0.4f); repeat(10) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = rgb(150, 70, 70) } }
            "soul_sand" -> { noisy(t, rgb(84, 64, 50), 0.1f, 0.3f); repeat(5) { val x = t.rnd.nextInt(15); val y = t.rnd.nextInt(15); t[x, y] = rgb(50, 36, 28); t[x + 1, y] = rgb(50, 36, 28) } }
            "nether_bricks" -> t.fill { x, y ->
                val off = if ((y / 4) % 2 == 0) 0 else 4
                if (y % 4 == 3 || (x + off) % 8 == 7) rgb(26, 12, 14) else scale(rgb(64, 30, 36), t.jitter(0.08f))
            }
            "magma" -> {
                val (_, edge) = voronoi(t.rnd, 8)
                t.fill { x, y -> if (edge[y][x] < 1.2f) rgb(255, 150, 40) else scale(rgb(120, 40, 20), t.jitter(0.1f)) }
            }
            "end_stone" -> speckled(t, rgb(220, 222, 160), rgb(196, 196, 130), 24)
            "purpur" -> t.fill { x, y ->
                if (x % 8 == 0 || y % 8 == 0) rgb(140, 96, 140) else scale(rgb(170, 122, 170), t.jitter(0.05f))
            }
            "prismarine" -> {
                val vn = valueNoise(t.rnd, 4)
                t.fill { x, y -> scale(mix(rgb(90, 150, 136), rgb(110, 176, 170), vn[y][x]), t.jitter(0.05f)) }
            }
            "prismarine_bricks" -> t.fill { x, y ->
                val off = if ((y / 4) % 2 == 0) 0 else 4
                if (y % 4 == 3 || (x + off) % 8 == 7) rgb(70, 124, 110) else scale(rgb(104, 172, 156), t.jitter(0.05f))
            }
            "dark_prismarine" -> t.fill { x, y ->
                if (x % 8 == 0 || y % 8 == 0) rgb(34, 64, 52) else scale(rgb(52, 92, 76), t.jitter(0.06f))
            }
            "sea_lantern" -> t.fill { x, y ->
                val d = max(abs(x - 7.5f), abs(y - 7.5f))
                if (d > 6.6f) rgb(170, 200, 190) else if ((x + y) % 5 == 0) rgb(250, 255, 250) else rgb(214, 232, 224)
            }
            "sponge" -> { noisy(t, rgb(202, 192, 72), 0.06f, 0.2f); repeat(12) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = rgb(150, 140, 40) } }
            "wet_sponge" -> { noisy(t, rgb(168, 170, 64), 0.06f, 0.2f); repeat(12) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = rgb(110, 116, 40) } }
            "crafting_table_top" -> craftingTop(t)
            "crafting_table_side" -> craftingSide(t, false)
            "crafting_table_front" -> craftingSide(t, true)
            "furnace_front" -> furnaceFront(t)
            "furnace_side" -> { stone(t, rgb(118, 118, 118)); bevel(t, rgb(150, 150, 150), rgb(70, 70, 70)) }
            "furnace_top" -> { stone(t, rgb(128, 128, 128)); bevel(t, rgb(150, 150, 150), rgb(70, 70, 70)) }
            "chest_front" -> chest(t, "front")
            "chest_side" -> chest(t, "side")
            "chest_top" -> chest(t, "top")
            "tnt_side" -> tnt(t, "side")
            "tnt_top" -> tnt(t, "top")
            "tnt_bottom" -> tnt(t, "bottom")
            "tnt_flash" -> tnt(t, "flash")
            "hay_side" -> t.fill { x, y ->
                if (y in 3..4 || y in 11..12) rgb(150, 60, 30) else scale(rgb(214, 178, 40), (if (x % 3 == 0) 0.85f else 1f) * t.jitter(0.06f))
            }
            "hay_top" -> noisy(t, rgb(200, 164, 40), 0.1f, 0.25f)
            "melon_side" -> t.fill { x, _ -> scale(if (x % 4 < 2) rgb(96, 150, 36) else rgb(140, 190, 50), t.jitter(0.06f)) }
            "melon_top" -> { noisy(t, rgb(120, 170, 44), 0.06f, 0.2f); for (y in 6..9) for (x in 6..9) t[x, y] = rgb(100, 130, 40) }
            "mycelium_top" -> speckled(t, rgb(112, 98, 106), rgb(150, 136, 150), 30)
            "mycelium_side" -> { dirt(t); overlayTop(t, rgb(112, 98, 106), 3) }
            "podzol_top" -> speckled(t, rgb(92, 64, 30), rgb(120, 84, 40), 30)
            "podzol_side" -> { dirt(t); overlayTop(t, rgb(92, 64, 30), 3) }
            "coarse_dirt" -> { dirt(t); repeat(24) { t[t.rnd.nextInt(16), t.rnd.nextInt(16)] = rgb(110, 100, 90) } }
            "red_sand" -> noisy(t, rgb(190, 104, 36), 0.05f, 0.12f)
            "red_sandstone_side" -> { sandstoneSide(t); for (i in t.px.indices) t.px[i] = mix(t.px[i], rgb(186, 100, 36), 0.6f) }
            "red_sandstone_top" -> noisy(t, rgb(184, 98, 36), 0.04f, 0.1f)
            "farmland" -> t.fill { _, y -> scale(rgb(94, 62, 38), (if (y % 4 == 0) 0.7f else 1f) * t.jitter(0.08f)) }
            "dirt_path_top" -> noisy(t, rgb(150, 124, 66), 0.06f, 0.15f)
            "dirt_path_side" -> { dirt(t); overlayTop(t, rgb(150, 124, 66), 2) }
            "sugar_cane" -> {
                sprite(t)
                for (x in intArrayOf(3, 7, 11)) for (y in 0 until 16) {
                    t[x, y] = if (y % 5 == 0) rgb(120, 170, 90) else rgb(146, 196, 104); t[x + 1, y] = rgb(110, 160, 80)
                }
            }
            "brown_mushroom" -> {
                sprite(t)
                for (y in 10..15) for (x in 7..8) t[x, y] = rgb(220, 206, 180)
                for (y in 6..9) for (x in 4..11) if (y > 6 || x in 5..10) t[x, y] = scale(rgb(150, 110, 80), t.jitter(0.08f))
            }
            "red_mushroom" -> {
                sprite(t)
                for (y in 10..15) for (x in 7..8) t[x, y] = rgb(220, 206, 180)
                for (y in 4..9) for (x in 4..11) if (abs(x - 7.5f) < 2.5f + (y - 4) * 0.5f) t[x, y] = rgb(200, 30, 30)
                t[6, 6] = rgb(250, 250, 250); t[9, 5] = rgb(250, 250, 250); t[8, 8] = rgb(250, 250, 250)
            }
            "cobweb" -> {
                sprite(t)
                val c = argb(220, 230, 230, 230)
                for (i in 0 until 16) { t[i, i] = c; t[15 - i, i] = c; t[7, i] = c; t[i, 8] = c }
                for (r in intArrayOf(3, 6)) for (i in -r..r) { t[7 + i, 8 - r] = c; t[7 + i, 8 + r] = c; t[7 - r, 8 + i] = c; t[7 + r, 8 + i] = c }
            }
            "torch" -> torchSprite(t, rgb(255, 200, 60), rgb(255, 250, 200))
            "redstone_torch" -> torchSprite(t, rgb(255, 40, 20), rgb(255, 150, 120))
            "redstone_torch_off" -> torchSprite(t, rgb(100, 20, 16), rgb(80, 16, 12))
            "lever" -> leverSprite(t, false)
            "lever_on" -> leverSprite(t, true)
            "redstone_dust" -> dust(t, false)
            "redstone_dust_on" -> dust(t, true)
            "redstone_lamp" -> lamp(t, false)
            "redstone_lamp_on" -> lamp(t, true)
            "note_block" -> { planks(t, rgb(110, 72, 50)); bevel(t, rgb(80, 52, 34), rgb(60, 40, 26)); for (y in 5..10) for (x in 5..10) if ((x + y) % 2 == 0) t[x, y] = rgb(40, 26, 18) }
            "jukebox_top" -> { planks(t, rgb(110, 72, 50)); bevel(t, rgb(80, 52, 34), rgb(60, 40, 26)); for (x in 3..12) { t[x, 7] = rgb(20, 20, 20); t[x, 8] = rgb(20, 20, 20) } }
            "jukebox_side" -> { planks(t, rgb(110, 72, 50)); bevel(t, rgb(80, 52, 34), rgb(60, 40, 26)) }
            "piston_side" -> pistonSide(t)
            "piston_front" -> pistonFront(t, false)
            "piston_sticky_front" -> pistonFront(t, true)
            "piston_back" -> { stone(t, rgb(110, 110, 110)); bevel(t, rgb(140, 140, 140), rgb(70, 70, 70)); for (y in 5..10) for (x in 5..10) t[x, y] = rgb(90, 90, 90) }
            "piston_inner" -> { stone(t, rgb(110, 110, 110)); for (y in 4..11) for (x in 4..11) t[x, y] = if (x in 6..9 && y in 6..9) rgb(166, 132, 80) else rgb(40, 40, 40) }
            "flint_and_steel" -> flintAndSteel(t)
            "bucket" -> bucket(t, false)
            "water_bucket" -> bucket(t, true)
            else -> t.fill { x, y -> if ((x / 4 + y / 4) % 2 == 0) rgb(255, 0, 255) else rgb(0, 0, 0) }
        }
    }

    private fun build(): IntArray {
        // Make sure every block and item has allocated its tiles.
        Blocks.COUNT.let { Blocks[0] }
        Items.all.size
        MobModels.models.size
        val atlas = IntArray(SIZE * SIZE)
        val tiles = HashMap<Int, Tile>()
        for ((name, index) in Tiles.all()) {
            val t = Tile(Random(1000L + name.hashCode() * 7919L))
            paint(name, t)
            tiles[index] = t
        }
        val crackTiles = Array(10) { tiles.getValue(Tiles.CRACK_0 + it) }
        cracks(crackTiles)

        for ((index, t) in tiles) {
            require(index < TILES_PER_ROW * TILES_PER_ROW) { "atlas full" }
            val ox = (index % TILES_PER_ROW) * TILE
            val oy = (index / TILES_PER_ROW) * TILE
            for (y in 0 until TILE) for (x in 0 until TILE) atlas[(oy + y) * SIZE + ox + x] = t[x, y]
        }
        return atlas
    }
}
