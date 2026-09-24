package com.blockcraft.game.world

import java.util.Random
import kotlin.math.abs
import kotlin.math.max

enum class Biome { PLAINS, FOREST, DESERT, SNOW }

/** Deterministic procedural terrain: heightmap + biomes + caves + ores + vegetation. */
class TerrainGenerator(private val seed: Long) {
    companion object {
        const val SEA_LEVEL = 62
    }

    private val heightNoise = Noise(seed)
    private val detailNoise = Noise(seed + 11)
    private val mountainNoise = Noise(seed + 23)
    private val tempNoise = Noise(seed + 37)
    private val humidNoise = Noise(seed + 41)
    private val caveNoiseA = Noise(seed + 53)
    private val caveNoiseB = Noise(seed + 67)
    private val cavernNoise = Noise(seed + 71)
    private val floorNoise = Noise(seed + 83)

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun surfaceHeight(x: Int, z: Int): Int {
        val base = heightNoise.fbm2(x * 0.0055, z * 0.0055, 5)
        val detail = detailNoise.fbm2(x * 0.03, z * 0.03, 3)
        val m = smoothstep(0.05, 0.55, mountainNoise.fbm2(x * 0.0035, z * 0.0035, 4))
        val h = 64 + base * 16 + detail * 3 + m * (28 + 22 * abs(detail))
        return h.toInt().coerceIn(6, Chunk.HEIGHT - 12)
    }

    fun biomeAt(x: Int, z: Int): Biome {
        val t = tempNoise.fbm2(x * 0.0022, z * 0.0022, 3)
        val hu = humidNoise.fbm2(x * 0.0028, z * 0.0028, 3)
        return when {
            t > 0.18 && hu < 0.05 -> Biome.DESERT
            t < -0.22 -> Biome.SNOW
            hu > 0.08 -> Biome.FOREST
            else -> Biome.PLAINS
        }
    }

    private fun isCave(x: Int, y: Int, z: Int): Boolean {
        val xs = x * 0.04; val ys = y * 0.075; val zs = z * 0.04
        val a = caveNoiseA.noise3(xs, ys, zs)
        val b = caveNoiseB.noise3(xs, ys, zs)
        if (a * a + b * b < 0.0035) return true
        if (y < 42) {
            val c = cavernNoise.noise3(x * 0.018, y * 0.035, z * 0.018)
            if (c > 0.5 - (42 - y) * 0.004) return true
        }
        return false
    }

    fun generate(chunk: Chunk) {
        val rnd = Random(seed xor (chunk.cx * 341873128712L) xor (chunk.cz * 132897987541L))
        val bx = chunk.cx * Chunk.SIZE
        val bz = chunk.cz * Chunk.SIZE
        val tops = IntArray(Chunk.SIZE * Chunk.SIZE)

        for (z in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            val wx = bx + x; val wz = bz + z
            val h = surfaceHeight(wx, wz)
            val biome = biomeAt(wx, wz)
            tops[z * Chunk.SIZE + x] = h

            var top: Int
            var filler: Int
            var fillerDepth = 3
            when (biome) {
                Biome.DESERT -> { top = Blocks.SAND; filler = Blocks.SAND; fillerDepth = 4 }
                Biome.SNOW -> { top = Blocks.SNOW_GRASS; filler = Blocks.DIRT }
                else -> { top = Blocks.GRASS; filler = Blocks.DIRT }
            }
            if (h >= 100) { top = Blocks.SNOW; filler = Blocks.STONE }
            else if (h >= 90 && biome != Biome.SNOW) { top = Blocks.STONE; filler = Blocks.STONE }
            if (h <= SEA_LEVEL + 1 && h >= SEA_LEVEL - 1 && biome != Biome.SNOW) {
                top = Blocks.SAND; filler = Blocks.SAND
            } else if (h < SEA_LEVEL - 1) {
                val f = floorNoise.noise2(wx * 0.05, wz * 0.05)
                top = when {
                    f > 0.35 -> Blocks.CLAY
                    f > 0.0 -> Blocks.SAND
                    f > -0.3 -> Blocks.GRAVEL
                    else -> Blocks.DIRT
                }
                filler = if (top == Blocks.CLAY) Blocks.DIRT else top
            }

            val maxY = max(h, SEA_LEVEL)
            for (y in 0..maxY) {
                val id = when {
                    y == 0 -> Blocks.BEDROCK
                    y <= 3 && rnd.nextInt(y + 1) == 0 -> Blocks.BEDROCK
                    y < h - fillerDepth -> if (biome == Biome.DESERT && y >= h - fillerDepth - 3) Blocks.SANDSTONE else Blocks.STONE
                    y < h -> filler
                    y == h -> top
                    y == SEA_LEVEL && biome == Biome.SNOW -> Blocks.ICE
                    else -> Blocks.WATER
                }
                chunk.set(x, y, z, id)
            }

            // Carve caves, keeping a solid lid under oceans and beaches.
            val lidTop = if (h <= SEA_LEVEL + 1) h - 5 else h
            for (y in 4..lidTop) {
                val id = chunk.get(x, y, z)
                if (id == Blocks.BEDROCK || id == Blocks.WATER || id == Blocks.ICE) continue
                if (isCave(wx, y, wz)) chunk.set(x, y, z, Blocks.AIR)
            }
        }

        placeOres(chunk, rnd)
        decorate(chunk, rnd, tops)
    }

    private fun placeOres(chunk: Chunk, rnd: Random) {
        vein(chunk, rnd, Blocks.COAL_ORE, count = 18, size = 10, maxY = 110)
        vein(chunk, rnd, Blocks.IRON_ORE, count = 12, size = 7, maxY = 64)
        vein(chunk, rnd, Blocks.GOLD_ORE, count = 3, size = 6, maxY = 32)
        vein(chunk, rnd, Blocks.DIAMOND_ORE, count = 1, size = 5, maxY = 16)
        vein(chunk, rnd, Blocks.GRAVEL, count = 4, size = 14, maxY = 90)
    }

    private fun vein(chunk: Chunk, rnd: Random, ore: Int, count: Int, size: Int, maxY: Int) {
        repeat(count) {
            var x = rnd.nextInt(Chunk.SIZE)
            var y = 1 + rnd.nextInt(maxY)
            var z = rnd.nextInt(Chunk.SIZE)
            repeat(size) {
                if (x in 0 until Chunk.SIZE && z in 0 until Chunk.SIZE && y in 1 until Chunk.HEIGHT &&
                    chunk.get(x, y, z) == Blocks.STONE
                ) chunk.set(x, y, z, ore)
                when (rnd.nextInt(3)) {
                    0 -> x += rnd.nextInt(3) - 1
                    1 -> y += rnd.nextInt(3) - 1
                    else -> z += rnd.nextInt(3) - 1
                }
            }
        }
    }

    private fun decorate(chunk: Chunk, rnd: Random, tops: IntArray) {
        val biome = biomeAt(chunk.cx * Chunk.SIZE + 8, chunk.cz * Chunk.SIZE + 8)

        // Ground cover.
        for (z in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            val h = tops[z * Chunk.SIZE + x]
            if (h + 1 >= Chunk.HEIGHT) continue
            val ground = chunk.get(x, h, z)
            if (chunk.get(x, h + 1, z) != Blocks.AIR) continue
            val r = rnd.nextInt(1000)
            when (ground) {
                Blocks.GRASS -> {
                    val grassChance = if (biome == Biome.PLAINS) 140 else 60
                    when {
                        r < 12 -> chunk.set(x, h + 1, z, Blocks.FLOWER_RED)
                        r < 24 -> chunk.set(x, h + 1, z, Blocks.FLOWER_YELLOW)
                        r < 24 + grassChance -> chunk.set(x, h + 1, z, Blocks.TALL_GRASS)
                        r < 26 + grassChance -> chunk.set(x, h + 1, z, Blocks.PUMPKIN)
                    }
                }
                Blocks.SAND -> if (biome == Biome.DESERT && h > SEA_LEVEL + 1) {
                    if (r < 6) chunk.set(x, h + 1, z, Blocks.DEAD_BUSH)
                    else if (r < 10 && x in 1..14 && z in 1..14) {
                        val height = 1 + rnd.nextInt(3)
                        for (i in 1..height) chunk.set(x, h + i, z, Blocks.CACTUS)
                    }
                }
            }
        }

        // Trees are kept fully inside the chunk so generation never depends on neighbours.
        val attempts = when (biome) {
            Biome.FOREST -> 7
            Biome.SNOW -> 2
            Biome.PLAINS -> if (rnd.nextInt(3) == 0) 1 else 0
            Biome.DESERT -> 0
        }
        repeat(attempts) {
            val x = 2 + rnd.nextInt(Chunk.SIZE - 4)
            val z = 2 + rnd.nextInt(Chunk.SIZE - 4)
            val h = tops[z * Chunk.SIZE + x]
            val ground = chunk.get(x, h, z)
            if (ground != Blocks.GRASS && ground != Blocks.SNOW_GRASS) return@repeat
            if (h + 12 >= Chunk.HEIGHT) return@repeat
            when {
                biome == Biome.SNOW -> spruce(chunk, rnd, x, h + 1, z)
                rnd.nextInt(5) == 0 -> oak(chunk, rnd, x, h + 1, z, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES)
                else -> oak(chunk, rnd, x, h + 1, z, Blocks.LOG, Blocks.LEAVES)
            }
        }
    }

    private fun setIfReplaceable(chunk: Chunk, x: Int, y: Int, z: Int, id: Int) {
        if (x !in 0 until Chunk.SIZE || z !in 0 until Chunk.SIZE || y !in 0 until Chunk.HEIGHT) return
        val cur = chunk.get(x, y, z)
        if (cur == Blocks.AIR || cur == Blocks.TALL_GRASS || cur == Blocks.FLOWER_RED || cur == Blocks.FLOWER_YELLOW) {
            chunk.set(x, y, z, id)
        }
    }

    private fun oak(chunk: Chunk, rnd: Random, x: Int, y: Int, z: Int, log: Int, leaves: Int) {
        val trunk = 4 + rnd.nextInt(3)
        val top = y + trunk
        for (ly in top - 3..top) {
            val radius = if (ly >= top - 1) 1 else 2
            for (dz in -radius..radius) for (dx in -radius..radius) {
                val corner = abs(dx) == radius && abs(dz) == radius
                if (corner && (ly == top || rnd.nextInt(2) == 0)) continue
                setIfReplaceable(chunk, x + dx, ly, z + dz, leaves)
            }
        }
        setIfReplaceable(chunk, x, top + 1, z, leaves)
        for (i in 0 until trunk) chunk.set(x, y + i, z, log)
        chunk.set(x, y - 1, z, Blocks.DIRT)
    }

    private fun spruce(chunk: Chunk, rnd: Random, x: Int, y: Int, z: Int) {
        val trunk = 6 + rnd.nextInt(3)
        val top = y + trunk
        var radius = 0
        for (ly in top downTo y + 2) {
            for (dz in -radius..radius) for (dx in -radius..radius) {
                if (abs(dx) + abs(dz) > radius + 1) continue
                setIfReplaceable(chunk, x + dx, ly, z + dz, Blocks.LEAVES)
            }
            radius = if (radius >= 2) 1 else radius + 1
        }
        setIfReplaceable(chunk, x, top + 1, z, Blocks.LEAVES)
        for (i in 0 until trunk) chunk.set(x, y + i, z, Blocks.LOG)
        chunk.set(x, y - 1, z, Blocks.DIRT)
    }
}
