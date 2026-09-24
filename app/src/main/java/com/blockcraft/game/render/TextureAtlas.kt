package com.blockcraft.game.render

import com.blockcraft.game.world.Tiles
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Generates every block texture procedurally into a 256x256 ARGB atlas (16x16 tiles of 16px).
 * Pure Kotlin so it can run anywhere; the Android side wraps the pixels in a Bitmap.
 */
object TextureAtlas {
    const val TILE = 16
    const val TILES_PER_ROW = 16
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

    // ---------------------------------------------------------------- assembly

    private fun build(): IntArray {
        val atlas = IntArray(SIZE * SIZE)
        val tiles = HashMap<Int, Tile>()
        fun tile(index: Int, paint: (Tile) -> Unit) {
            val t = Tile(Random(1000L + index * 7919L))
            paint(t)
            tiles[index] = t
        }

        tile(Tiles.DIRT) { dirt(it) }
        tile(Tiles.GRASS_TOP) { grassTop(it) }
        tile(Tiles.GRASS_SIDE) { dirt(it); overlayTop(it, rgb(98, 160, 58), 4) }
        tile(Tiles.STONE) { stone(it) }
        tile(Tiles.COBBLESTONE) { cobble(it, false) }
        tile(Tiles.MOSSY_COBBLESTONE) { cobble(it, true) }
        tile(Tiles.PLANKS) { planks(it) }
        tile(Tiles.LOG_SIDE) { logSide(it, rgb(108, 84, 52), rgb(72, 55, 34), false) }
        tile(Tiles.BIRCH_SIDE) { logSide(it, rgb(222, 220, 208), rgb(50, 50, 46), true) }
        tile(Tiles.LOG_TOP) { logTop(it) }
        tile(Tiles.LEAVES) { leaves(it, rgb(62, 126, 42)) }
        tile(Tiles.BIRCH_LEAVES) { leaves(it, rgb(118, 160, 70)) }
        tile(Tiles.SAND) { noisy(it, rgb(220, 208, 162), 0.05f, 0.12f) }
        tile(Tiles.GRAVEL) { gravel(it) }
        tile(Tiles.GLASS) { glass(it) }
        tile(Tiles.WATER) { water(it) }
        tile(Tiles.BEDROCK) { bedrock(it) }
        tile(Tiles.COAL_ORE) { ore(it, rgb(40, 40, 40), 4) }
        tile(Tiles.IRON_ORE) { ore(it, rgb(216, 176, 146), 3) }
        tile(Tiles.GOLD_ORE) { ore(it, rgb(250, 214, 60), 3) }
        tile(Tiles.DIAMOND_ORE) { ore(it, rgb(90, 226, 222), 3) }
        tile(Tiles.BRICKS) { bricks(it) }
        tile(Tiles.STONE_BRICKS) { stoneBricks(it) }
        tile(Tiles.SNOW) { noisy(it, rgb(242, 250, 252), 0.03f, 0.08f) }
        tile(Tiles.SNOW_SIDE) { dirt(it); overlayTop(it, rgb(242, 250, 252), 4) }
        tile(Tiles.CACTUS_SIDE) { cactusSide(it) }
        tile(Tiles.CACTUS_TOP) { cactusTop(it) }
        tile(Tiles.FLOWER_RED) { flower(it, rgb(220, 40, 40)) }
        tile(Tiles.FLOWER_YELLOW) { flower(it, rgb(250, 220, 50)) }
        tile(Tiles.TALL_GRASS) { tallGrass(it) }
        tile(Tiles.DEAD_BUSH) { deadBush(it) }
        tile(Tiles.GLOWSTONE) { glowstone(it) }
        tile(Tiles.OBSIDIAN) { obsidian(it) }
        tile(Tiles.SANDSTONE_SIDE) { sandstoneSide(it) }
        tile(Tiles.SANDSTONE_TOP) { noisy(it, rgb(222, 206, 156), 0.04f, 0.1f) }
        tile(Tiles.BOOKSHELF) { bookshelf(it) }
        tile(Tiles.WOOL_WHITE) { wool(it, rgb(234, 236, 236)) }
        tile(Tiles.WOOL_RED) { wool(it, rgb(170, 40, 36)) }
        tile(Tiles.WOOL_BLUE) { wool(it, rgb(52, 60, 160)) }
        tile(Tiles.WOOL_GREEN) { wool(it, rgb(86, 120, 30)) }
        tile(Tiles.WOOL_YELLOW) { wool(it, rgb(246, 196, 40)) }
        tile(Tiles.WOOL_BLACK) { wool(it, rgb(26, 26, 30)) }
        tile(Tiles.WOOL_ORANGE) { wool(it, rgb(236, 120, 20)) }
        tile(Tiles.WOOL_PURPLE) { wool(it, rgb(120, 44, 160)) }
        tile(Tiles.ICE) { ice(it) }
        tile(Tiles.CLAY) { noisy(it, rgb(160, 166, 180), 0.04f, 0.15f) }
        tile(Tiles.PUMPKIN_SIDE) { pumpkinSide(it) }
        tile(Tiles.PUMPKIN_TOP) { pumpkinTop(it) }
        tile(SUN) { sun(it) }
        tile(MOON) { moon(it) }
        tile(Tiles.WHITE) { t -> t.fill { _, _ -> rgb(255, 255, 255) } }
        val crackTiles = Array(10) { Tile(Random(it.toLong())) }
        cracks(crackTiles)
        crackTiles.forEachIndexed { i, t -> tiles[Tiles.CRACK_0 + i] = t }

        for ((index, t) in tiles) {
            val ox = (index % TILES_PER_ROW) * TILE
            val oy = (index / TILES_PER_ROW) * TILE
            for (y in 0 until TILE) for (x in 0 until TILE) atlas[(oy + y) * SIZE + ox + x] = t[x, y]
        }
        return atlas
    }

    const val SUN = 60
    const val MOON = 61
}
