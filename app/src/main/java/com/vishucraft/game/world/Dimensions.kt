package com.vishucraft.game.world

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The Ember Realm: a closed cavern world between a bedrock floor and ceiling, with a lava sea,
 * glowing clusters hanging from the roof, ash sand fields and rare ancient debris.
 */
class EmberGenerator(private val seed: Long) : WorldGenerator {
    private val cave = Noise(seed + 101)
    private val cave2 = Noise(seed + 102)
    private val floor = Noise(seed + 103)
    companion object { const val LAVA_SEA = 32 }

    override fun biomeAt(x: Int, z: Int) = Biome.EMBER

    private fun solidAt(x: Int, y: Int, z: Int): Boolean {
        if (y <= 2 || y >= 110) return true
        val n = cave.noise3(x * 0.03, y * 0.05, z * 0.03) + cave2.noise3(x * 0.07, y * 0.1, z * 0.07) * 0.35
        // More open in the middle heights.
        val bias = abs(y - 64) / 64.0 * 0.9 - 0.25
        return n + bias > 0.0
    }

    override fun surfaceHeight(x: Int, z: Int): Int {
        for (y in 40..100) if (solidAt(x, y, z) && !solidAt(x, y + 1, z) && !solidAt(x, y + 2, z)) return y
        return 64
    }

    override fun generate(chunk: Chunk) {
        val rnd = Random(seed xor (chunk.cx * 49157L) xor (chunk.cz * 98317L))
        for (z in 0 until 16) for (x in 0 until 16) {
            val wx = chunk.cx * 16 + x; val wz = chunk.cz * 16 + z
            val ash = floor.noise2(wx * 0.04, wz * 0.04) > 0.35
            for (y in 0 until Chunk.HEIGHT) {
                val id = when {
                    y == 0 || y >= Chunk.HEIGHT - 2 -> Blocks.BEDROCK
                    y >= 110 -> Blocks.NETHERRACK
                    solidAt(wx, y, wz) -> {
                        val airAbove = !solidAt(wx, y + 1, wz)
                        when {
                            airAbove && ash && y > LAVA_SEA -> Blocks.SOUL_SAND
                            y in 8..22 && rnd.nextInt(900) == 0 -> Blocks.ANCIENT_DEBRIS
                            rnd.nextInt(160) == 0 -> Blocks.MAGMA
                            else -> Blocks.NETHERRACK
                        }
                    }
                    y <= LAVA_SEA -> Blocks.LAVA
                    else -> Blocks.AIR
                }
                chunk.set(x, y, z, id)
            }
        }
        // Glowing clusters hanging from ceilings.
        repeat(3) {
            val x = 1 + rnd.nextInt(14); val z = 1 + rnd.nextInt(14)
            var y = 100
            while (y > 40 && chunk.get(x, y, z) != Blocks.AIR) y--
            if (y <= 40 || chunk.get(x, y + 1, z) != Blocks.NETHERRACK) return@repeat
            for (i in 0 until 3 + rnd.nextInt(4)) {
                val gx = (x + rnd.nextInt(3) - 1).coerceIn(0, 15); val gz = (z + rnd.nextInt(3) - 1).coerceIn(0, 15)
                if (chunk.get(gx, y - i, gz) == Blocks.AIR) chunk.set(gx, y - i, gz, Blocks.GLOWSTONE)
            }
        }
    }
}

/** The Sky Isles: floating islands of pale stone over an endless void, with sky stone towers. */
class SkyGenerator(private val seed: Long) : WorldGenerator {
    private val mask = Noise(seed + 201)
    private val detail = Noise(seed + 202)
    override fun biomeAt(x: Int, z: Int) = Biome.SKY

    /** Island top height at (x, z), or -1 over the void. */
    private fun island(x: Int, z: Int): Pair<Int, Int> {
        val d = sqrt((x * x + z * z).toDouble())
        val centre = max(0.0, 1.0 - d / 40.0) // a guaranteed island at the origin
        val m = mask.fbm2(x * 0.012, z * 0.012, 3) + centre * 0.9
        if (m < 0.22) return -1 to 0
        val top = 64 + (detail.noise2(x * 0.05, z * 0.05) * 4).toInt()
        val thickness = ((m - 0.22) * 40).toInt().coerceIn(2, 22)
        return top to thickness
    }

    override fun surfaceHeight(x: Int, z: Int): Int = island(x, z).first.let { if (it < 0) 64 else it }

    override fun generate(chunk: Chunk) {
        val rnd = Random(seed xor (chunk.cx * 7919L) xor (chunk.cz * 104729L))
        for (z in 0 until 16) for (x in 0 until 16) {
            val (top, thick) = island(chunk.cx * 16 + x, chunk.cz * 16 + z)
            if (top < 0) continue
            for (y in top - thick..top) chunk.set(x, y, z, Blocks.END_STONE)
        }
        // Occasional sky stone towers topped with a lantern.
        if (rnd.nextInt(6) == 0) {
            val x = 3 + rnd.nextInt(10); val z = 3 + rnd.nextInt(10)
            val (top, _) = island(chunk.cx * 16 + x, chunk.cz * 16 + z)
            if (top > 0) {
                val h = 6 + rnd.nextInt(8)
                for (y in top + 1..top + h) for (dz in -1..1) for (dx in -1..1) {
                    if (abs(dx) + abs(dz) < 2 || y % 4 == 0) chunk.set(x + dx, y, z + dz, Blocks.PURPUR)
                }
                chunk.set(x, top + h + 1, z, Blocks.SEA_LANTERN)
            }
        }
    }
}

/** Villages, desert temples and dungeons, generated chunk by chunk from a deterministic layout. */
class Structures(private val seed: Long, private val gen: TerrainGenerator, private val world: World) {
    private class Put(val chunk: Chunk) {
        val x0 = chunk.cx * 16; val z0 = chunk.cz * 16
        fun inside(x: Int, z: Int) = x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16
    }

    private fun set(p: Put, x: Int, y: Int, z: Int, id: Int, meta: Int = 0) {
        if (!p.inside(x, z) || y < 1 || y >= Chunk.HEIGHT) return
        p.chunk.set(x - p.x0, y, z - p.z0, id, meta)
    }

    private fun lootChest(p: Put, x: Int, y: Int, z: Int, rnd: Random, loot: List<Pair<Int, IntRange>>, facing: Int = 2) {
        if (!p.inside(x, z)) return
        set(p, x, y, z, Blocks.CHEST, facing)
        val chest = ChestEntity()
        for ((id, range) in loot) {
            val n = range.first + rnd.nextInt(range.last - range.first + 1)
            if (n > 0) chest.slots[rnd.nextInt(27)] = ItemStack(id, n)
        }
        world.blockEntities.map.putIfAbsent(RedstoneIds.pack(x, y, z), chest)
    }

    fun place(chunk: Chunk) {
        val p = Put(chunk)
        // Surface structures on a 288-block grid, dungeons on a 64-block grid.
        val region = 288
        for (rz in (p.z0 - 64).floorDiv(region)..(p.z0 + 80).floorDiv(region))
            for (rx in (p.x0 - 64).floorDiv(region)..(p.x0 + 80).floorDiv(region)) {
                val rnd = Random(seed xor (rx * 341873128712L) xor (rz * 132897987541L) xor 0x5EED)
                val cx = rx * region + 48 + rnd.nextInt(region - 96)
                val cz = rz * region + 48 + rnd.nextInt(region - 96)
                if (abs(cx - p.x0 - 8) > 64 || abs(cz - p.z0 - 8) > 64) continue
                val h = gen.surfaceHeight(cx, cz)
                if (h <= TerrainGenerator.SEA_LEVEL + 1 || h > 88) continue
                when (gen.biomeAt(cx, cz)) {
                    Biome.PLAINS, Biome.SAVANNA, Biome.FOREST, Biome.SNOW -> village(p, cx, cz, rnd)
                    Biome.DESERT -> temple(p, cx, h, cz, rnd)
                    else -> {}
                }
            }
        val cell = 64
        for (rz in (p.z0 - 8).floorDiv(cell)..(p.z0 + 24).floorDiv(cell))
            for (rx in (p.x0 - 8).floorDiv(cell)..(p.x0 + 24).floorDiv(cell)) {
                val rnd = Random(seed xor (rx * 73856093L) xor (rz * 19349663L) xor 0xD06)
                if (rnd.nextInt(3) != 0) continue
                val x = rx * cell + 8 + rnd.nextInt(cell - 16); val z = rz * cell + 8 + rnd.nextInt(cell - 16)
                val y = 14 + rnd.nextInt(24)
                if (gen.surfaceHeight(x, z) - 8 < y) continue
                dungeon(p, x, y, z, rnd)
            }
    }

    // ---------------------------------------------------------------- dungeon

    private fun dungeon(p: Put, x: Int, y: Int, z: Int, rnd: Random) {
        for (dx in -4..4) for (dz in -4..4) for (dy in -1..4) {
            val wall = abs(dx) == 4 || abs(dz) == 4 || dy == -1 || dy == 4
            val id = if (!wall) Blocks.AIR else if (rnd.nextInt(3) == 0) Blocks.MOSSY_COBBLESTONE else Blocks.COBBLESTONE
            set(p, x + dx, y + dy, z + dz, id)
        }
        set(p, x, y + 3, z, Blocks.TORCH)
        val loot = listOf(
            Items.find("Iron Ingot") to 0..4, Items.find("Gold Ingot") to 0..3, Items.find("Bread") to 1..3,
            Items.find("Bone") to 1..5, Items.find("Gunpowder") to 0..4, Items.find("String") to 0..3,
            Items.find("Golden Apple") to (if (rnd.nextInt(5) == 0) 1..1 else 0..0),
            Items.find("Enchanted Diamond Pickaxe") to (if (rnd.nextInt(12) == 0) 1..1 else 0..0),
            Items.find("Diamond") to (if (rnd.nextInt(3) == 0) 1..2 else 0..0),
        )
        lootChest(p, x - 3, y, z, rnd, loot, 4)
        if (rnd.nextBoolean()) lootChest(p, x + 3, y, z, rnd, loot, 5)
    }

    // ---------------------------------------------------------------- desert temple

    private fun temple(p: Put, cx: Int, h: Int, cz: Int, rnd: Random) {
        val base = h
        // Stepped pyramid, 21 wide.
        for (layer in 0..9) {
            val r = 10 - layer
            for (dx in -r..r) for (dz in -r..r) {
                val edge = abs(dx) == r || abs(dz) == r
                val y = base + layer
                set(p, cx + dx, y, cz + dz, if (edge) (if ((dx + dz) % 4 == 0) Blocks.RED_SANDSTONE else Blocks.SANDSTONE) else Blocks.AIR)
            }
        }
        for (dx in -10..10) for (dz in -10..10) {
            set(p, cx + dx, base - 1, cz + dz, Blocks.SANDSTONE)
            for (y in base - 6 until base - 1) set(p, cx + dx, y, cz + dz, Blocks.SANDSTONE)
        }
        // Entrance.
        for (y in base..base + 2) for (dx in -1..1) set(p, cx + dx, y, cz + 10, Blocks.AIR)
        // Hidden treasure room below, reached through the floor in the middle... with a trap.
        for (dx in -3..3) for (dz in -3..3) for (y in base - 6..base - 2) {
            val wall = abs(dx) == 3 || abs(dz) == 3 || y == base - 6
            set(p, cx + dx, y, cz + dz, if (wall) Blocks.SANDSTONE else Blocks.AIR)
        }
        set(p, cx, base - 1, cz, Blocks.AIR)
        set(p, cx, base - 6, cz, Blocks.TNT)
        set(p, cx, base - 5, cz, Blocks.PRESSURE_PLATE)
        val loot = listOf(
            Items.find("Gold Ingot") to 2..7, Items.find("Diamond") to 0..3, Items.find("Emerald") to 0..3,
            Items.find("Bone") to 1..4, Items.find("Rotten Flesh") to 1..5,
            Items.find("Golden Apple") to (if (rnd.nextInt(3) == 0) 1..1 else 0..0),
            Items.find("Enchanted Diamond Sword") to (if (rnd.nextInt(6) == 0) 1..1 else 0..0),
            Items.find("Enchanted Diamond Chestplate") to (if (rnd.nextInt(8) == 0) 1..1 else 0..0),
        )
        lootChest(p, cx - 2, base - 5, cz, rnd, loot, 4)
        lootChest(p, cx + 2, base - 5, cz, rnd, loot, 5)
        lootChest(p, cx, base - 5, cz - 2, rnd, loot, 2)
        lootChest(p, cx, base - 5, cz + 2, rnd, loot, 3)
    }

    // ---------------------------------------------------------------- village

    private fun village(p: Put, cx: Int, cz: Int, rnd: Random) {
        val wh = gen.surfaceHeight(cx, cz)
        // Well.
        for (dx in -2..2) for (dz in -2..2) {
            val ring = abs(dx) == 2 || abs(dz) == 2
            for (y in wh - 3..wh) set(p, cx + dx, y, cz + dz, if (ring) Blocks.COBBLESTONE else if (y == wh) Blocks.WATER else Blocks.WATER)
            for (y in wh + 1..wh + 4) set(p, cx + dx, y, cz + dz, Blocks.AIR)
            if (abs(dx) == 2 && abs(dz) == 2) for (y in wh + 1..wh + 3) set(p, cx + dx, y, cz + dz, Blocks.OAK_FENCE)
            set(p, cx + dx, wh + 4, cz + dz, Blocks.OAK_SLAB)
        }
        if (p.inside(cx, cz)) repeat(3 + rnd.nextInt(3)) { world.pendingVillagers.add(Triple(cx + 3, wh + 1, cz + 3)) }

        val houses = 4 + rnd.nextInt(4)
        for (i in 0 until houses) {
            val a = i * (Math.PI * 2 / houses) + rnd.nextDouble() * 0.5
            val r = 12 + rnd.nextInt(10)
            val hx = cx + (Math.cos(a) * r).toInt(); val hz = cz + (Math.sin(a) * r).toInt()
            path(p, cx, cz, hx, hz)
            if (rnd.nextInt(4) == 0) farm(p, hx, hz, rnd) else house(p, hx, hz, rnd, cx, cz)
        }
    }

    private fun path(p: Put, x0: Int, z0: Int, x1: Int, z1: Int) {
        val steps = max(abs(x1 - x0), abs(z1 - z0))
        for (i in 0..steps) {
            val x = x0 + (x1 - x0) * i / max(1, steps); val z = z0 + (z1 - z0) * i / max(1, steps)
            if (!p.inside(x, z)) continue
            val y = gen.surfaceHeight(x, z)
            val cur = p.chunk.get(x - p.x0, y, z - p.z0)
            if (cur == Blocks.GRASS || cur == Blocks.SNOW_GRASS || cur == Blocks.DIRT || cur == Blocks.SAND) set(p, x, y, z, Blocks.DIRT_PATH)
            if (Blocks[p.chunk.get(x - p.x0, y + 1, z - p.z0)].render == RenderType.CROSS) set(p, x, y + 1, z, Blocks.AIR)
        }
    }

    private fun house(p: Put, hx: Int, hz: Int, rnd: Random, cx: Int, cz: Int) {
        val y0 = gen.surfaceHeight(hx, hz)
        val w = 2 + rnd.nextInt(2) // half width -> 5x5 or 7x7
        val wall = if (rnd.nextBoolean()) Blocks.PLANKS else Blocks.COBBLESTONE
        // Door faces the well.
        val dx = cx - hx; val dz = cz - hz
        val doorSide = if (abs(dx) > abs(dz)) (if (dx > 0) 4 else 5) else (if (dz > 0) 2 else 3)
        for (x in -w..w) for (z in -w..w) {
            val edge = abs(x) == w || abs(z) == w
            val corner = abs(x) == w && abs(z) == w
            for (y in y0 - 4 until y0) set(p, hx + x, y, hz + z, Blocks.COBBLESTONE)
            set(p, hx + x, y0, hz + z, if (edge) Blocks.COBBLESTONE else Blocks.PLANKS)
            for (y in y0 + 1..y0 + 3) {
                val id = when {
                    corner -> Blocks.LOG
                    edge && y == y0 + 2 && (x == 0 || z == 0) -> Blocks.GLASS_PANE
                    edge -> wall
                    else -> Blocks.AIR
                }
                set(p, hx + x, y, hz + z, id)
            }
            // Roof: a slab cap one block wider.
            set(p, hx + x, y0 + 4, hz + z, Blocks.PLANKS)
        }
        for (x in -w - 1..w + 1) for (z in -w - 1..w + 1) if (abs(x) == w + 1 || abs(z) == w + 1) set(p, hx + x, y0 + 4, hz + z, Blocks.OAK_SLAB)
        for (x in -w + 1..w - 1) for (z in -w + 1..w - 1) set(p, hx + x, y0 + 5, hz + z, Blocks.OAK_SLAB)
        // Door.
        val (ddx, ddz) = when (doorSide) { 2 -> 0 to w; 3 -> 0 to -w; 4 -> w to 0; else -> -w to 0 }
        set(p, hx + ddx, y0 + 1, hz + ddz, Blocks.OAK_DOOR, doorSide)
        set(p, hx + ddx, y0 + 2, hz + ddz, Blocks.OAK_DOOR, doorSide or Shapes.UPPER)
        set(p, hx, y0 + 3, hz, Blocks.TORCH)
        // Bigger houses get a red bed in a corner, clear of every possible doorway.
        if (w == 3) {
            set(p, hx - 2, y0 + 1, hz + 2, Blocks.BED_FIRST + 14, 4)
            set(p, hx - 1, y0 + 1, hz + 2, Blocks.BED_FIRST + 14, 4 or Shapes.UPPER)
        }
        set(p, hx - w + 1, y0 + 1, hz - w + 1, Blocks.CRAFTING_TABLE, 2)
        lootChest(p, hx + w - 1, y0 + 1, hz - w + 1, rnd, listOf(
            Items.find("Bread") to 1..4, Items.find("Apple") to 0..3, Items.find("Wheat") to 0..6,
            Items.find("Iron Ingot") to 0..2, Items.find("Wheat Seeds") to 0..5, Blocks.SAPLING_FIRST to 0..2,
            Items.find("Emerald") to 0..2,
        ))
    }

    private fun farm(p: Put, fx: Int, fz: Int, rnd: Random) {
        val y = gen.surfaceHeight(fx, fz)
        val crop = when (rnd.nextInt(3)) { 0 -> Blocks.CARROTS; 1 -> Blocks.POTATOES; else -> Blocks.WHEAT_CROP }
        for (x in -3..3) for (z in -2..2) {
            val border = abs(x) == 3 || abs(z) == 2
            for (yy in y - 2 until y) set(p, fx + x, yy, fz + z, Blocks.DIRT)
            set(p, fx + x, y, fz + z, if (border) Blocks.LOG else if (x == 0) Blocks.WATER else Blocks.FARMLAND)
            set(p, fx + x, y + 1, fz + z, if (!border && x != 0) crop else Blocks.AIR, if (!border && x != 0) rnd.nextInt(8) else 0)
            set(p, fx + x, y + 2, fz + z, Blocks.AIR)
        }
    }
}
