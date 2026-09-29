package com.vishucraft.game.world

import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

enum class Biome { PLAINS, FOREST, DESERT, SNOW, JUNGLE, SAVANNA, EMBER, SKY, SWAMP, BADLANDS, MUSHROOM, CHERRY }

/** Terrain for one dimension. */
interface WorldGenerator {
    fun generate(chunk: Chunk)
    fun surfaceHeight(x: Int, z: Int): Int
    fun biomeAt(x: Int, z: Int): Biome
}

/** A flat world for building: bedrock, stone, three layers of dirt and grass on top. Nothing grows on it. */
class FlatGenerator : WorldGenerator {
    companion object { const val TOP = 64 }

    override fun generate(chunk: Chunk) {
        for (z in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            chunk.set(x, 0, z, Blocks.BEDROCK)
            for (y in 1 until TOP - 3) chunk.set(x, y, z, Blocks.STONE)
            for (y in TOP - 3 until TOP) chunk.set(x, y, z, Blocks.DIRT)
            chunk.set(x, TOP, z, Blocks.GRASS)
        }
    }

    override fun surfaceHeight(x: Int, z: Int) = TOP
    override fun biomeAt(x: Int, z: Int) = Biome.PLAINS
}

/** Deterministic procedural terrain: heightmap + biomes + caves + ores + vegetation. */
class TerrainGenerator(private val seed: Long, private val world: World? = null) : WorldGenerator {
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
    private val rareNoise = Noise(seed + 97)

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    override fun surfaceHeight(x: Int, z: Int): Int {
        val base = heightNoise.fbm2(x * 0.0055, z * 0.0055, 5)
        val detail = detailNoise.fbm2(x * 0.03, z * 0.03, 3)
        val m = smoothstep(0.05, 0.55, mountainNoise.fbm2(x * 0.0035, z * 0.0035, 4))
        var h = 64 + base * 16 + detail * 3 + m * (28 + 22 * abs(detail))
        // Swamps are flat and wet: pull the land down to just above the water.
        val sw = swampiness(x, z)
        if (sw > 0.0 && h > SEA_LEVEL - 1) h += (SEA_LEVEL + 0.3 - h) * sw * 0.9
        // Badlands: flat-topped mesas in steps, showing their coloured stripes.
        val bad = smoothstep(0.18, 0.2, temp(x, z)) * smoothstep(-0.2, -0.23, humid(x, z))
        if (bad > 0.0 && h > SEA_LEVEL + 1) {
            val n = mountainNoise.fbm2(x * 0.02 + 40.0, z * 0.02, 3)
            if (n > -0.05) h += floor((minOf(n + 0.05, 0.5) * 70 * bad) / 5) * 5
        }
        return h.toInt().coerceIn(6, Chunk.HEIGHT - 12)
    }

    private fun temp(x: Int, z: Int) = tempNoise.fbm2(x * 0.0022, z * 0.0022, 3)
    private fun humid(x: Int, z: Int) = humidNoise.fbm2(x * 0.0028, z * 0.0028, 3)

    /** 0..1: how swampy a spot is (temperate and very humid). */
    private fun swampiness(x: Int, z: Int): Double {
        val t = temp(x, z); val hu = humid(x, z)
        return smoothstep(0.24, 0.34, hu) * (1 - smoothstep(0.12, 0.18, t)) * smoothstep(-0.22, -0.16, t)
    }

    override fun biomeAt(x: Int, z: Int): Biome {
        val t = temp(x, z)
        val hu = humid(x, z)
        val rare = rareNoise.fbm2(x * 0.004, z * 0.004, 2)
        return when {
            rare > 0.42 && t in -0.15..0.15 -> Biome.MUSHROOM
            t > 0.18 && hu < -0.2 -> Biome.BADLANDS
            t > 0.18 && hu < 0.0 -> Biome.DESERT
            t > 0.18 && hu > 0.22 -> Biome.JUNGLE
            t > 0.18 -> Biome.SAVANNA
            t < -0.22 -> Biome.SNOW
            swampiness(x, z) > 0.5 -> Biome.SWAMP
            hu > 0.08 -> Biome.FOREST
            t < -0.1 && hu < -0.05 -> Biome.CHERRY
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

    override fun generate(chunk: Chunk) {
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
                Biome.BADLANDS -> { top = Blocks.RED_SAND; filler = Blocks.TERRACOTTA; fillerDepth = 30 }
                Biome.MUSHROOM -> { top = Blocks.MYCELIUM; filler = Blocks.DIRT }
                Biome.SWAMP -> { top = if (floorNoise.noise2(wx * 0.08, wz * 0.08) > 0.25) Blocks.MUD else Blocks.GRASS; filler = Blocks.DIRT }
                else -> { top = Blocks.GRASS; filler = Blocks.DIRT }
            }
            if (h >= 100) { top = Blocks.SNOW; filler = Blocks.STONE }
            else if (h >= 90 && biome != Biome.SNOW && biome != Biome.BADLANDS) { top = Blocks.STONE; filler = Blocks.STONE }
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
                    y < h - fillerDepth -> when {
                        biome == Biome.DESERT && y >= h - fillerDepth - 3 -> Blocks.SANDSTONE
                        y < 10 || (y < 14 && rnd.nextInt(14 - y + 1) == 0) -> Blocks.DEEPSLATE
                        else -> Blocks.STONE
                    }
                    y < h -> if (filler == Blocks.TERRACOTTA) badlandsBand(y) else filler
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
                if (h <= SEA_LEVEL + 1 && y >= SEA_LEVEL - 6) continue
                // Deep caves end in lava lakes.
                if (isCave(wx, y, wz)) chunk.set(x, y, z, if (y <= 10) Blocks.LAVA else Blocks.AIR)
            }
        }

        placeOres(chunk, rnd)
        decorate(chunk, rnd, tops)
        world?.let { Structures(seed, this, it).place(chunk) }
    }

    /** Coloured stripes of terracotta in the badlands. */
    private fun badlandsBand(y: Int): Int {
        val bands = intArrayOf(-1, 1, 4, -1, 14, 12, 0, 1, -1, 8, 1, 4)
        val c = bands[Math.floorMod(y + (seed and 7).toInt(), bands.size)]
        return if (c < 0) Blocks.TERRACOTTA else Blocks.TERRACOTTA_FIRST + c
    }

    private fun placeOres(chunk: Chunk, rnd: Random) {
        vein(chunk, rnd, Blocks.GRANITE, count = 2, size = 40, maxY = 100)
        vein(chunk, rnd, Blocks.DIORITE, count = 2, size = 40, maxY = 100)
        vein(chunk, rnd, Blocks.ANDESITE, count = 2, size = 40, maxY = 100)
        vein(chunk, rnd, Blocks.TUFF, count = 2, size = 30, maxY = 18)
        vein(chunk, rnd, Blocks.GRAVEL, count = 4, size = 14, maxY = 90)
        vein(chunk, rnd, Blocks.COAL_ORE, count = 18, size = 10, maxY = 110)
        vein(chunk, rnd, Blocks.COPPER_ORE, count = 8, size = 9, maxY = 90)
        vein(chunk, rnd, Blocks.IRON_ORE, count = 12, size = 7, maxY = 64)
        vein(chunk, rnd, Blocks.GOLD_ORE, count = 3, size = 6, maxY = 32)
        vein(chunk, rnd, Blocks.LAPIS_ORE, count = 2, size = 6, maxY = 32)
        vein(chunk, rnd, Blocks.REDSTONE_ORE, count = 6, size = 8, maxY = 16)
        vein(chunk, rnd, Blocks.DIAMOND_ORE, count = 1, size = 6, maxY = 16)
        vein(chunk, rnd, Blocks.EMERALD_ORE, count = 1, size = 1, maxY = 50)
        vein(chunk, rnd, Blocks.DRIPSTONE, count = 1, size = 20, maxY = 50)
        if (rnd.nextInt(6) == 0) vein(chunk, rnd, Blocks.AMETHYST_BLOCK, count = 1, size = 14, maxY = 40)
        vein(chunk, rnd, Blocks.CALCITE, count = 1, size = 12, maxY = 40)
    }

    /** Ores that form in deepslate take on its dark look. */
    private fun deepslateVersion(ore: Int) = when (ore) {
        Blocks.COAL_ORE -> Blocks.DEEPSLATE_ORE_FIRST; Blocks.IRON_ORE -> Blocks.DEEPSLATE_ORE_FIRST + 1
        Blocks.GOLD_ORE -> Blocks.DEEPSLATE_ORE_FIRST + 2; Blocks.DIAMOND_ORE -> Blocks.DEEPSLATE_ORE_FIRST + 3
        Blocks.REDSTONE_ORE -> Blocks.DEEPSLATE_ORE_FIRST + 4; Blocks.LAPIS_ORE -> Blocks.DEEPSLATE_ORE_FIRST + 5
        else -> ore
    }

    private fun vein(chunk: Chunk, rnd: Random, ore: Int, count: Int, size: Int, maxY: Int) {
        repeat(count) {
            var x = rnd.nextInt(Chunk.SIZE)
            var y = 1 + rnd.nextInt(maxY)
            var z = rnd.nextInt(Chunk.SIZE)
            repeat(size) {
                if (x in 0 until Chunk.SIZE && z in 0 until Chunk.SIZE && y in 1 until Chunk.HEIGHT) {
                    val here = chunk.get(x, y, z)
                    if (here == Blocks.STONE) chunk.set(x, y, z, ore)
                    else if (here == Blocks.DEEPSLATE) chunk.set(x, y, z, deepslateVersion(ore))
                }
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
            val r = rnd.nextInt(1000)
            // Sea and lake floors: kelp forests and seagrass.
            if (chunk.get(x, h + 1, z) == Blocks.WATER) {
                val depth = SEA_LEVEL - h
                if (depth >= 4 && r < 70 && biome != Biome.SNOW) {
                    val tall = 1 + rnd.nextInt(depth - 2)
                    for (i in 1..tall) chunk.set(x, h + i, z, Blocks.KELP)
                } else if (depth >= 2 && r < 200) chunk.set(x, h + 1, z, Blocks.SEAGRASS)
                // Lily pads float on swamp water.
                if (biome == Biome.SWAMP && depth in 1..3 && r in 300..380 && chunk.get(x, SEA_LEVEL + 1, z) == Blocks.AIR) {
                    chunk.set(x, SEA_LEVEL + 1, z, Blocks.LILY_PAD)
                }
                continue
            }
            if (chunk.get(x, h + 1, z) != Blocks.AIR) continue
            when (ground) {
                Blocks.GRASS -> {
                    if (h <= SEA_LEVEL + 1 && r < 60 && nearWater(chunk, x, h, z)) {
                        val height = 1 + rnd.nextInt(3)
                        for (i in 1..height) chunk.set(x, h + i, z, Blocks.SUGAR_CANE)
                        continue
                    }
                    val grassChance = when (biome) { Biome.PLAINS, Biome.SAVANNA -> 140; Biome.JUNGLE -> 180; else -> 60 }
                    val plant = if (biome == Biome.JUNGLE) Blocks.FERN else Blocks.TALL_GRASS
                    if (biome == Biome.CHERRY && r < 150) { chunk.set(x, h + 1, z, if (r < 120) Blocks.FLOWER_FIRST else Blocks.FLOWER_FIRST + 3 + r % 4); continue }
                    if (biome == Biome.JUNGLE && r >= 980) {
                        val tall = 4 + rnd.nextInt(6)
                        for (i in 1..tall) if (h + i < Chunk.HEIGHT) chunk.set(x, h + i, z, Blocks.BAMBOO)
                        continue
                    }
                    if ((biome == Biome.FOREST || biome == Biome.PLAINS) && r in 970..973) { chunk.set(x, h + 1, z, Blocks.BERRY_BUSH, 3); continue }
                    if (biome == Biome.SWAMP && r < 12) { chunk.set(x, h + 1, z, Blocks.BLUE_ORCHID); continue }
                    if (biome == Biome.PLAINS && r in 900..915) { chunk.set(x, h + 1, z, Blocks.FLOWER_FIRST + 1 + rnd.nextInt(6)); continue }
                    when {
                        r < 12 -> chunk.set(x, h + 1, z, if (biome == Biome.JUNGLE) Blocks.BLUE_ORCHID else Blocks.FLOWER_RED)
                        r < 24 -> chunk.set(x, h + 1, z, Blocks.FLOWER_YELLOW)
                        r < 24 + grassChance -> chunk.set(x, h + 1, z, plant)
                        r < 26 + grassChance -> chunk.set(x, h + 1, z, if (biome == Biome.JUNGLE) Blocks.MELON else Blocks.PUMPKIN)
                        biome == Biome.FOREST && r < 32 + grassChance ->
                            chunk.set(x, h + 1, z, if (r % 2 == 0) Blocks.BROWN_MUSHROOM else Blocks.RED_MUSHROOM)
                    }
                }
                Blocks.SNOW_GRASS -> when {
                    r < 40 -> chunk.set(x, h + 1, z, Blocks.FERN)
                    r < 46 -> chunk.set(x, h + 1, z, Blocks.BERRY_BUSH, 3)
                    r < 600 -> chunk.set(x, h + 1, z, Blocks.SNOW_LAYER)
                }
                Blocks.SNOW -> if (r < 700) chunk.set(x, h + 1, z, Blocks.SNOW_LAYER)
                Blocks.MYCELIUM -> if (r < 25) chunk.set(x, h + 1, z, if (r % 2 == 0) Blocks.RED_MUSHROOM else Blocks.BROWN_MUSHROOM)
                Blocks.RED_SAND -> if (h > SEA_LEVEL + 1) {
                    if (r < 8) chunk.set(x, h + 1, z, Blocks.DEAD_BUSH)
                    else if (r < 11 && x in 1..14 && z in 1..14) for (i in 1..1 + rnd.nextInt(3)) chunk.set(x, h + i, z, Blocks.CACTUS)
                }
                Blocks.SAND -> if (h <= SEA_LEVEL + 1 && r < 40 && nearWater(chunk, x, h, z)) {
                    val height = 1 + rnd.nextInt(3)
                    for (i in 1..height) chunk.set(x, h + i, z, Blocks.SUGAR_CANE)
                } else if (biome == Biome.DESERT && h > SEA_LEVEL + 1) {
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
            Biome.JUNGLE -> 9
            Biome.SNOW -> 2
            Biome.SAVANNA -> if (rnd.nextInt(2) == 0) 1 else 0
            Biome.PLAINS -> if (rnd.nextInt(3) == 0) 1 else 0
            Biome.DESERT, Biome.BADLANDS -> 0
            Biome.SWAMP -> 3
            Biome.MUSHROOM -> 2
            Biome.CHERRY -> 3
            else -> 0
        }
        repeat(attempts) {
            val x = 2 + rnd.nextInt(Chunk.SIZE - 4)
            val z = 2 + rnd.nextInt(Chunk.SIZE - 4)
            val h = tops[z * Chunk.SIZE + x]
            val ground = chunk.get(x, h, z)
            if (ground != Blocks.GRASS && ground != Blocks.SNOW_GRASS && ground != Blocks.MYCELIUM && ground != Blocks.MUD) return@repeat
            if (h + 16 >= Chunk.HEIGHT) return@repeat
            when {
                biome == Biome.MUSHROOM -> hugeMushroom(chunk, rnd, x, h + 1, z)
                biome == Biome.SWAMP -> if (rnd.nextInt(3) != 0) mangrove(chunk, rnd, x, h + 1, z) else oak(chunk, rnd, x, h + 1, z, Blocks.LOG, Blocks.LEAVES)
                biome == Biome.CHERRY -> { cherry(chunk, rnd, x, h + 1, z); if (rnd.nextInt(4) == 0) beeNest(chunk, x, h + 3, z) }
                biome == Biome.SNOW -> spruce(chunk, rnd, x, h + 1, z)
                biome == Biome.JUNGLE -> oak(chunk, rnd, x, h + 1, z, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES, 4 + rnd.nextInt(6))
                biome == Biome.SAVANNA -> acacia(chunk, rnd, x, h + 1, z)
                biome == Biome.FOREST && rnd.nextInt(6) == 0 -> oak(chunk, rnd, x, h + 1, z, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES)
                rnd.nextInt(5) == 0 -> oak(chunk, rnd, x, h + 1, z, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES)
                else -> {
                    oak(chunk, rnd, x, h + 1, z, Blocks.LOG, Blocks.LEAVES)
                    if (rnd.nextInt(if (biome == Biome.PLAINS) 3 else 12) == 0) beeNest(chunk, x, h + 3, z)
                }
            }
        }
    }

    private fun setIfReplaceable(chunk: Chunk, x: Int, y: Int, z: Int, id: Int) {
        if (x !in 0 until Chunk.SIZE || z !in 0 until Chunk.SIZE || y !in 0 until Chunk.HEIGHT) return
        val cur = chunk.get(x, y, z)
        if (cur == Blocks.AIR || Blocks[cur].render == RenderType.CROSS) {
            chunk.set(x, y, z, id)
        }
    }

    private fun nearWater(chunk: Chunk, x: Int, h: Int, z: Int): Boolean {
        for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val nx = x + dx; val nz = z + dz
            if (nx in 0 until Chunk.SIZE && nz in 0 until Chunk.SIZE && chunk.get(nx, h, nz) == Blocks.WATER) return true
        }
        return false
    }

    private fun acacia(chunk: Chunk, rnd: Random, x: Int, y: Int, z: Int) {
        val trunk = 4 + rnd.nextInt(2)
        val lean = if (rnd.nextBoolean()) 1 else -1
        var tx = x
        for (i in 0 until trunk) {
            if (i == trunk - 2 && tx + lean in 1..14) tx += lean
            chunk.set(tx, y + i, z, Blocks.ACACIA_LOG)
        }
        val top = y + trunk
        for (dz in -2..2) for (dx in -2..2) {
            if (abs(dx) == 2 && abs(dz) == 2) continue
            setIfReplaceable(chunk, tx + dx, top, z + dz, Blocks.ACACIA_LEAVES)
        }
        for (dz in -1..1) for (dx in -1..1) setIfReplaceable(chunk, tx + dx, top + 1, z + dz, Blocks.ACACIA_LEAVES)
        chunk.set(x, y - 1, z, Blocks.DIRT)
    }

    private fun oak(chunk: Chunk, rnd: Random, x: Int, y: Int, z: Int, log: Int, leaves: Int, extra: Int = 0) {
        val trunk = 4 + rnd.nextInt(3) + extra
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

    /** A bee nest hanging on the side of a trunk. */
    private fun beeNest(chunk: Chunk, x: Int, y: Int, z: Int) {
        if (x + 1 < Chunk.SIZE && y < Chunk.HEIGHT && chunk.get(x + 1, y, z).let { it == Blocks.AIR || Blocks[it].name.endsWith("Leaves") }) {
            chunk.set(x + 1, y, z, Blocks.BEE_NEST, 4 or (3 shl 3))
        }
    }

    /** Cherry trees: a short trunk with a wide, round pink crown. */
    private fun cherry(chunk: Chunk, rnd: Random, x: Int, y: Int, z: Int) {
        val trunk = 4 + rnd.nextInt(2)
        val top = y + trunk
        for (ly in top - 2..top + 1) {
            val r = if (ly == top + 1) 1 else if (ly == top - 2) 2 else 2
            for (dz in -r..r) for (dx in -r..r) {
                if (abs(dx) + abs(dz) > r + 1) continue
                setIfReplaceable(chunk, x + dx, ly, z + dz, Blocks.CHERRY_LEAVES)
            }
        }
        for (i in 0 until trunk) chunk.set(x, y + i, z, Blocks.CHERRY_LOG)
        chunk.set(x, y - 1, z, Blocks.DIRT)
    }

    /** Mangroves stand on roots above the swamp water. */
    private fun mangrove(chunk: Chunk, rnd: Random, x: Int, y: Int, z: Int) {
        val lift = 2
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) for (i in -1 until lift) {
            val rx = x + dx; val rz = z + dz
            if (rx in 0 until Chunk.SIZE && rz in 0 until Chunk.SIZE) setIfReplaceable(chunk, rx, y + i, rz, Blocks.MANGROVE_LOG)
            if (rx in 0 until Chunk.SIZE && rz in 0 until Chunk.SIZE && chunk.get(rx, y + i, rz) == Blocks.WATER) chunk.set(rx, y + i, rz, Blocks.MANGROVE_LOG)
        }
        oak(chunk, rnd, x, y + lift, z, Blocks.MANGROVE_LOG, Blocks.MANGROVE_LEAVES, 1)
        for (i in 0 until lift) chunk.set(x, y + i, z, Blocks.MANGROVE_LOG)
    }

    /** A huge mushroom: a thick stem and a red or brown cap. */
    private fun hugeMushroom(chunk: Chunk, rnd: Random, x: Int, y: Int, z: Int) {
        val red = rnd.nextBoolean()
        val stem = 4 + rnd.nextInt(3)
        for (i in 0 until stem) chunk.set(x, y + i, z, Blocks.MUSHROOM_STEM)
        val cap = if (red) Blocks.RED_MUSHROOM_BLOCK else Blocks.BROWN_MUSHROOM_BLOCK
        val top = y + stem
        if (red) {
            for (dz in -1..1) for (dx in -1..1) setIfReplaceable(chunk, x + dx, top, z + dz, cap)
            for (ly in top - 3 until top) for (dz in -2..2) for (dx in -2..2) {
                if ((abs(dx) == 2 || abs(dz) == 2) && !(abs(dx) == 2 && abs(dz) == 2)) setIfReplaceable(chunk, x + dx, ly, z + dz, cap)
            }
        } else {
            for (dz in -3..3) for (dx in -3..3) if (!(abs(dx) == 3 && abs(dz) == 3)) setIfReplaceable(chunk, x + dx, top, z + dz, cap)
        }
    }

    private fun spruce(chunk: Chunk, rnd: Random, x: Int, y: Int, z: Int) {
        val trunk = 6 + rnd.nextInt(3)
        val top = y + trunk
        var radius = 0
        for (ly in top downTo y + 2) {
            for (dz in -radius..radius) for (dx in -radius..radius) {
                if (abs(dx) + abs(dz) > radius + 1) continue
                setIfReplaceable(chunk, x + dx, ly, z + dz, Blocks.SPRUCE_LEAVES)
            }
            radius = if (radius >= 2) 1 else radius + 1
        }
        setIfReplaceable(chunk, x, top + 1, z, Blocks.SPRUCE_LEAVES)
        for (i in 0 until trunk) chunk.set(x, y + i, z, Blocks.SPRUCE_LOG)
        chunk.set(x, y - 1, z, Blocks.DIRT)
    }
}
