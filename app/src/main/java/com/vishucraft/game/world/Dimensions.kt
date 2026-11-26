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
            for (y in 0 until Chunk.BASE) {
                val id = when {
                    y == 0 || y >= Chunk.BASE - 2 -> Blocks.BEDROCK
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

/** The Sky Isles: floating islands of pale stone over an endless void, with sky stone towers; the dragon arena is in the middle. */
class SkyGenerator(private val seed: Long) : WorldGenerator {
    companion object {
        /** How many pillars (and crystals) the arena has. */
        const val CRYSTALS = 8
        /** Height of obsidian pillar [i] (0..7) above the platform; each crystal stands on top of one. */
        fun pillarHeight(i: Int) = 12 + (i * 3 % 5) * 3
        const val ROOM_Z = -34
    }

    /** Eight obsidian pillars around the middle, and the room (with the way home) at the edge of the platform. */
    private fun arena(chunk: Chunk) {
        fun put(wx: Int, y: Int, wz: Int, id: Int) {
            val x = wx - chunk.cx * 16; val z = wz - chunk.cz * 16
            if (x in 0..15 && z in 0..15 && y in 1 until Chunk.BASE) chunk.set(x, y, z, id)
        }
        val top = 64
        for (i in 0 until CRYSTALS) {
            val a = i * 2.0 * Math.PI / CRYSTALS
            val px = Math.round(Math.cos(a) * 26.0).toInt(); val pz = Math.round(Math.sin(a) * 26.0).toInt()
            if (abs(px - chunk.cx * 16 - 8) > 12 || abs(pz - chunk.cz * 16 - 8) > 12) continue
            val h = pillarHeight(i)
            for (dx in -2..2) for (dz in -2..2) if (dx * dx + dz * dz <= 5) for (y in top + 1..top + h) put(px + dx, y, pz + dz, Blocks.OBSIDIAN)
        }
        // The room: 11 x 9 inside, purpur walls, a roof, torches, a wide doorway to the arena and the portal home in the back wall.
        val rz = ROOM_Z
        for (dx in -5..5) for (dz in -4..4) {
            val wall = abs(dx) == 5 || abs(dz) == 4
            put(dx, top, rz + dz, Blocks.PURPUR)
            for (y in top + 1..top + 5) put(dx, y, rz + dz, if (wall) Blocks.PURPUR else Blocks.AIR)
            put(dx, top + 6, rz + dz, Blocks.PURPUR)
        }
        for (dx in -1..1) for (y in top + 1..top + 3) put(dx, y, rz + 4, Blocks.AIR)          // the doorway towards the arena (+z)
        // The portal in the back wall (-z): a quartz frame around a 3 x 3 opening, open.
        val bz = rz - 4
        for (dx in -2..2) for (y in top + 1..top + 4) {
            val edge = abs(dx) == 2 || y == top + 4
            put(dx, y, bz, if (edge) Blocks.QUARTZ_BLOCK else Blocks.SKY_PORTAL)
        }
        for ((dx, dz) in listOf(-4 to -3, 4 to -3, -4 to 3, 4 to 3, -4 to 0, 4 to 0)) put(dx, top + 3, rz + dz, Blocks.TORCH)
        for (dx in -1..1) put(dx, top + 1, rz + 2, Blocks.END_STONE)   // a little step inside, nothing else: the room is for arriving
    }

    private val mask = Noise(seed + 201)
    private val detail = Noise(seed + 202)
    override fun biomeAt(x: Int, z: Int) = Biome.SKY

    /** Island top height at (x, z), or -1 over the void. */
    private fun island(x: Int, z: Int): Pair<Int, Int> {
        val d = sqrt((x * x + z * z).toDouble())
        // The arena: a round platform 40 blocks wide in every direction, flat, where the dragon fight happens.
        if (d <= 40.0) return 64 to (4 + ((40.0 - d) * 0.35).toInt())
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
        arena(chunk)
        // Occasional sky stone towers topped with a lantern (not on the arena).
        if (abs(chunk.cx) > 3 || abs(chunk.cz) > 3) if (rnd.nextInt(6) == 0) {
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

/** Where villages are: the village plaza (x, y, z) in region (rx, rz) of the 144-block grid, or null if none. */
fun villageIn(seed: Long, generator: WorldGenerator, rx: Int, rz: Int): IntArray? {
    val region = 144
    if (generator !is TerrainGenerator) return null   // flat worlds (with or without sea) have no villages
    val gen = generator
    val rnd = Random(seed xor (rx * 341873128712L) xor (rz * 132897987541L) xor 0x5EED)
    val cx = rx * region + 48 + rnd.nextInt(region - 96)
    val cz = rz * region + 48 + rnd.nextInt(region - 96)
    val h = gen.surfaceHeight(cx, cz)
    if (h <= TerrainGenerator.SEA_LEVEL + 1 || h > 88) return null
    return when (gen.biomeAt(cx, cz)) {
        Biome.PLAINS, Biome.SAVANNA, Biome.FOREST, Biome.SNOW, Biome.CHERRY, Biome.JUNGLE, Biome.SWAMP -> intArrayOf(cx, h, cz)
        else -> null
    }
}

/** Villages, desert temples and dungeons, generated chunk by chunk from a deterministic layout. */
class Structures(private val seed: Long, private val gen: WorldGenerator, private val world: World) {
    companion object {
        const val GATE_CELL = 256

        /** The centre (x, y, z) of the sky gate room in grid cell (rx, rz), or null if that cell has none. */
        fun gateRoomAt(seed: Long, gen: WorldGenerator, rx: Int, rz: Int): IntArray? {
            val rnd = Random(seed xor (rx * 60493L) xor (rz * 94781L) xor 0x6A7E)
            val x = rx * GATE_CELL + 24 + rnd.nextInt(GATE_CELL - 48); val z = rz * GATE_CELL + 24 + rnd.nextInt(GATE_CELL - 48)
            val y = 10 + rnd.nextInt(6)
            if (gen.surfaceHeight(x, z) - 12 < y) return null
            return intArrayOf(x, y, z)
        }

        /** The sky gate room nearest to (x, z), looked for in the cells round it, or null if there is none near. */
        fun gateRoomNear(seed: Long, gen: WorldGenerator, x: Float, z: Float): IntArray? {
            if (gen is FlatGenerator) return null
            val cx = Math.floorDiv(x.toInt(), GATE_CELL); val cz = Math.floorDiv(z.toInt(), GATE_CELL)
            var best: IntArray? = null; var bestD = Float.MAX_VALUE
            for (rz in cz - 3..cz + 3) for (rx in cx - 3..cx + 3) {
                val r = gateRoomAt(seed, gen, rx, rz) ?: continue
                val d = (r[0] - x) * (r[0] - x) + (r[2] - z) * (r[2] - z)
                if (d < bestD) { bestD = d; best = r }
            }
            return best
        }
    }

    private class Put(val chunk: Chunk) {
        val x0 = chunk.cx * 16; val z0 = chunk.cz * 16
        fun inside(x: Int, z: Int) = x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16
    }

    private fun set(p: Put, x: Int, y: Int, z: Int, id: Int, meta: Int = 0) {
        if (!p.inside(x, z) || y < 1 || y >= Chunk.BASE) return
        p.chunk.set(x - p.x0, y, z - p.z0, id, meta)
    }

    private fun lootChest(p: Put, x: Int, y: Int, z: Int, rnd: Random, loot: List<Pair<Int, IntRange>>, facing: Int = 2) {
        // The dice are always rolled, in every chunk, so a building comes out the same wherever its pieces are made.
        val rolls = loot.map { (id, range) -> Triple(id, range.first + rnd.nextInt(range.last - range.first + 1), rnd.nextInt(27)) }
        if (!p.inside(x, z)) return
        set(p, x, y, z, Blocks.CHEST, facing)
        val chest = ChestEntity()
        for ((id, n, slot) in rolls) if (n > 0) chest.slots[slot] = ItemStack(id, n)
        world.blockEntities.map.putIfAbsent(RedstoneIds.pack(x, y, z), chest)
    }

    /**
     * Makes a flat, level pad for a building: hills are cut away (trees and plants too), dips are filled with dirt,
     * and the ground cover of the spot is carried to the new level. Without it a building on a slope ends up half
     * buried on one side and floating on the other.
     */
    private fun levelPad(p: Put, cx: Int, cz: Int, rx: Int, rz: Int, y0: Int) {
        for (dx in -rx..rx) for (dz in -rz..rz) {
            val x = cx + dx; val z = cz + dz
            if (!p.inside(x, z)) continue
            val s = gen.surfaceHeight(x, z)
            val cover = p.chunk.get(x - p.x0, s.coerceIn(1, Chunk.BASE - 1), z - p.z0).let { if (it == Blocks.AIR || Blocks.isLiquid(it)) Blocks.GRASS else it }
            for (y in y0 + 1..maxOf(s, y0) + 12) set(p, x, y, z, Blocks.AIR)
            for (y in s + 1 until y0) set(p, x, y, z, Blocks.DIRT)
            if (s != y0) set(p, x, y0, z, cover)
        }
    }

    /** The middle height of a spot and four points around it (a good level for a building), or -1 if it is too steep or wet. */
    private fun siteLevel(x: Int, z: Int, r: Int): Int {
        val hs = intArrayOf(gen.surfaceHeight(x, z), gen.surfaceHeight(x - r, z - r), gen.surfaceHeight(x + r, z - r), gen.surfaceHeight(x - r, z + r), gen.surfaceHeight(x + r, z + r))
        if (hs.min() <= TerrainGenerator.SEA_LEVEL + 1 || hs.max() - hs.min() > 7) return -1
        return hs.sorted()[2]
    }

    /** Old worlds: adds the XP farm of any village whose chunk was generated before farms existed (see World.ensureLoaded). */
    fun placeFarms(chunk: Chunk) { place(chunk, 1) }

    /** Old worlds: adds forts, sky-gate rooms and mansions that belong in this chunk. */
    fun placeBig(chunk: Chunk) { place(chunk, 2) }

    /** A structure part is built into a chunk once: this records (chunk, structure) and says whether it is new. */
    private fun firstTime(p: Put, kind: Int, x: Int, z: Int): Boolean =
        p.x0 + 15 >= x - 16 && p.x0 <= x + 16 && p.z0 + 15 >= z - 16 && p.z0 <= z + 16 &&
        world.bigDone.add(Chunk.key(p.chunk.cx, p.chunk.cz) * 31L + ((x * 73856093L) xor (z * 19349663L) xor kind.toLong()))

    /** Where the smithy of the village at (cx, cz) stands. (The same angles once also placed a farm tower and a portal shrine.) */
    private fun smithySite(cx: Int, cz: Int): IntArray {
        val fa = Random(seed xor (cx * 341873128712L) xor (cz * 132897987541L) xor 0xFA12).nextDouble() * Math.PI * 2
        val sa = fa + Math.PI * (0.6 + Random(seed xor (cx * 91L) xor (cz * 7919L)).nextDouble() * 0.8)
        return intArrayOf(cx + (Math.cos(sa) * 28).toInt(), cz + (Math.sin(sa) * 28).toInt())
    }

    /**
     * Old worlds: villages made before this update had an XP farm tower, a sky portal shrine and a sky portal inside the
     * smithy. They are taken away, once per chunk. New chunks mark themselves as done and never get here.
     */
    private fun retire(p: Put, cx: Int, cz: Int) {
        if (!world.bigDone.add(Chunk.key(p.chunk.cx, p.chunk.cz) * 31L + ((cx * 73856093L) xor (cz * 19349663L) xor 7L))) return
        val fa = Random(seed xor (cx * 341873128712L) xor (cz * 132897987541L) xor 0xFA12).nextDouble() * Math.PI * 2
        // The farm tower and its platform.
        val fx = cx + (Math.cos(fa) * 36).toInt(); val fz = cz + (Math.sin(fa) * 36).toInt()
        if (p.x0 + 15 >= fx - 4 && p.x0 <= fx + 4 && p.z0 + 15 >= fz - 4 && p.z0 <= fz + 7) {
            val g = gen.surfaceHeight(fx, fz)
            for (x in fx - 3..fx + 3) for (z in fz - 3..fz + 6) if (p.inside(x, z)) {
                for (y in g + 1..g + 31) set(p, x, y, z, Blocks.AIR)
                set(p, x, g, z, Blocks.GRASS); set(p, x, g - 1, z, Blocks.DIRT)
                for (y in g - 1..g) world.blockEntities.map.remove(RedstoneIds.pack(x, y, z))
            }
        }
        // The portal shrine.
        val ea = fa + Math.PI * 0.5
        val ex = cx + (Math.cos(ea) * 40).toInt(); val ez = cz + (Math.sin(ea) * 40).toInt()
        if (p.x0 + 15 >= ex - 6 && p.x0 <= ex + 6 && p.z0 + 15 >= ez - 6 && p.z0 <= ez + 6) {
            val g = gen.surfaceHeight(ex, ez)
            for (x in ex - 5..ex + 5) for (z in ez - 5..ez + 5) if (p.inside(x, z)) {
                for (y in g + 1..g + 6) set(p, x, y, z, Blocks.AIR)
                set(p, x, g, z, Blocks.GRASS); set(p, x, g - 1, z, Blocks.DIRT)
            }
        }
        // The portal in the smithy's floor: the stones and the opening go, the floor stays.
        val (sx, sz) = smithySite(cx, cz).let { it[0] to it[1] }
        if (p.x0 + 15 >= sx - 3 && p.x0 <= sx + 3 && p.z0 + 15 >= sz - 3 && p.z0 <= sz + 3) {
            val y0 = gen.surfaceHeight(sx, sz)
            for (x in sx - 2..sx + 2) for (z in sz - 2..sz + 2) {
                val b = if (p.inside(x, z)) p.chunk.get(x - p.x0, y0 + 1, z - p.z0) else Blocks.AIR
                if (b == Blocks.SKY_GATE_EYE || b == Blocks.SKY_GATE || b == Blocks.SKY_PORTAL) set(p, x, y0 + 1, z, Blocks.AIR)
            }
        }
    }

    /** The blacksmith's home: a stone-brick forge house 11 x 9 with furnaces, anvil, grindstone, loot chests and a villager. */
    private fun smithy(p: Put, sx: Int, sz: Int, vx: Int, vz: Int) {
        val y0 = gen.surfaceHeight(sx, sz)
        levelPad(p, sx, sz, 6, 5, y0)
        val rnd = Random(seed xor (sx * 31L) xor (sz * 17L))
        val doorOnX = abs(vx - sx) > abs(vz - sz)
        val doorX = if (doorOnX) (if (vx > sx) 5 else -5) else 0
        val doorZ = if (doorOnX) 0 else (if (vz > sz) 4 else -4)
        for (dx in -5..5) for (dz in -4..4) {
            val edge = abs(dx) == 5 || abs(dz) == 4
            val corner = abs(dx) == 5 && abs(dz) == 4
            for (y in y0 - 3 until y0) set(p, sx + dx, y, sz + dz, Blocks.STONE_BRICKS)
            set(p, sx + dx, y0, sz + dz, Blocks.STONE_BRICKS)
            for (y in y0 + 1..y0 + 4) {
                val id = when {
                    corner -> Blocks.LOG
                    edge && y == y0 + 2 && (dx + dz) % 2 == 0 && !(dx == doorX && dz == doorZ) -> Blocks.GLASS_PANE
                    edge -> if (rnd.nextInt(5) == 0) Blocks.MOSSY_STONE_BRICKS else Blocks.STONE_BRICKS
                    else -> Blocks.AIR
                }
                set(p, sx + dx, y, sz + dz, id)
            }
            set(p, sx + dx, y0 + 5, sz + dz, Blocks.COBBLESTONE_SLAB)
        }
        // The doorway.
        for (y in y0 + 1..y0 + 2) set(p, sx + doorX, y, sz + doorZ, Blocks.AIR)
        // The forge along the back wall: furnaces, a blast furnace, an anvil and a grindstone; a chimney above.
        val bx = if (doorOnX) -(doorX / 5) * 4 else 0; val bz = if (doorOnX) 0 else -(doorZ / 4) * 3
        val ax = if (doorOnX) 0 else 1; val az = if (doorOnX) 1 else 0
        set(p, sx + bx + ax * -2, y0 + 1, sz + bz + az * -2, Blocks.FURNACE)
        set(p, sx + bx + ax * -1, y0 + 1, sz + bz + az * -1, Blocks.BLAST_FURNACE)
        set(p, sx + bx, y0 + 1, sz + bz, Blocks.FURNACE)
        set(p, sx + bx + ax * 1, y0 + 1, sz + bz + az * 1, Blocks.ANVIL)
        set(p, sx + bx + ax * 2, y0 + 1, sz + bz + az * 2, Blocks.GRINDSTONE)
        for (y in y0 + 5..y0 + 7) { set(p, sx + bx + ax * -2, y, sz + bz + az * -2, Blocks.COBBLESTONE); set(p, sx + bx, y, sz + bz, Blocks.COBBLESTONE) }
        // A table and a bed in the middle of the room, torches in the corners and loot.
        set(p, sx - 1, y0 + 1, sz, Blocks.CRAFTING_TABLE, 2)
        for ((dx, dz) in listOf(-4 to -3, 4 to -3, -4 to 3, 4 to 3)) set(p, sx + dx, y0 + 1, sz + dz, Blocks.TORCH)
        val loot = listOf(
            Items.find("Iron Ingot") to 2..7, Items.find("Coal") to 3..9, Items.find("Iron Pickaxe") to (if (rnd.nextInt(2) == 0) 1..1 else 0..0),
            Items.find("Iron Sword") to (if (rnd.nextInt(2) == 0) 1..1 else 0..0), Items.find("Diamond") to (if (rnd.nextInt(4) == 0) 1..2 else 0..0),
            Items.find("Bread") to 1..3, Items.find("Gold Ingot") to 0..3,
        )
        lootChest(p, sx - 4 * (if (doorOnX) 0 else 1) + (if (doorOnX) 0 else 0), y0 + 1, sz + (if (doorOnX) 3 else 0) - (if (doorOnX) 0 else 0), rnd, loot, 2)
        lootChest(p, sx + (if (doorOnX) 0 else 3), y0 + 1, sz - (if (doorOnX) 3 else 0), rnd, loot, 2)
        if (p.inside(sx + 2, sz + 2)) world.pendingVillagers.add(Triple(sx + 2, y0 + 1, sz + 2))
    }

    fun place(chunk: Chunk, mode: Int = 0) {
        val p = Put(chunk)
        val farmsOnly = mode == 1
        // Surface structures on a 144-block grid (a village about every 150 blocks), dungeons on a 64-block grid.
        val region = 144
        if (mode != 2) for (rz in (p.z0 - 64).floorDiv(region)..(p.z0 + 80).floorDiv(region))
            for (rx in (p.x0 - 64).floorDiv(region)..(p.x0 + 80).floorDiv(region)) {
                val rnd = Random(seed xor (rx * 341873128712L) xor (rz * 132897987541L) xor 0x5EED)
                val cx = rx * region + 48 + rnd.nextInt(region - 96)
                val cz = rz * region + 48 + rnd.nextInt(region - 96)
                if (abs(cx - p.x0 - 8) > 64 || abs(cz - p.z0 - 8) > 64) continue
                if (gen is FlatGenerator) continue   // no villages in flat worlds
                val h = gen.surfaceHeight(cx, cz)
                if (h <= TerrainGenerator.SEA_LEVEL + 1 || h > 88) continue
                when (gen.biomeAt(cx, cz)) {
                    Biome.PLAINS, Biome.SAVANNA, Biome.FOREST, Biome.SNOW, Biome.CHERRY, Biome.JUNGLE, Biome.SWAMP -> if (farmsOnly) retire(p, cx, cz) else village(p, cx, cz, rnd)
                    Biome.DESERT -> if (!farmsOnly) temple(p, cx, h, cz, rnd)
                    else -> {}
                }
            }
        if (farmsOnly || gen is FlatGenerator) return   // no dungeons under flat worlds
        if (mode != 2) {
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
        // Abandoned mines: long timber-framed tunnels under the hills, on a 96-block grid.
        val mineCell = 96
        for (rz in (p.z0 - 40).floorDiv(mineCell)..(p.z0 + 56).floorDiv(mineCell))
            for (rx in (p.x0 - 40).floorDiv(mineCell)..(p.x0 + 56).floorDiv(mineCell)) {
                val rnd = Random(seed xor (rx * 49979687L) xor (rz * 86028121L) xor 0xA11CE)
                if (rnd.nextInt(3) != 0) continue
                val x = rx * mineCell + 20 + rnd.nextInt(mineCell - 40); val z = rz * mineCell + 20 + rnd.nextInt(mineCell - 40)
                val y = 16 + rnd.nextInt(14)
                if (gen.surfaceHeight(x, z) - 10 < y) continue
                mine(p, x, y, z, rnd)
            }
        }
        // Underground forts (a hall with four corner towers and loot) and woodland mansions, each on a big grid.
        val fortCell = 128
        for (rz in (p.z0 - 24).floorDiv(fortCell)..(p.z0 + 40).floorDiv(fortCell))
            for (rx in (p.x0 - 24).floorDiv(fortCell)..(p.x0 + 40).floorDiv(fortCell)) {
                val rnd = Random(seed xor (rx * 91238477L) xor (rz * 37159823L) xor 0xF0A7)
                if (rnd.nextInt(3) == 0) continue
                val x = rx * fortCell + 24 + rnd.nextInt(fortCell - 48); val z = rz * fortCell + 24 + rnd.nextInt(fortCell - 48)
                val y = 12 + rnd.nextInt(10)
                if (gen.surfaceHeight(x, z) - 12 < y) continue
                if (firstTime(p, 1, x, z)) fort(p, x, y, z, rnd)
            }
        // Sky gate rooms deep under the hills, one in a while.
        val gateCell = GATE_CELL
        for (rz in (p.z0 - 16).floorDiv(gateCell)..(p.z0 + 32).floorDiv(gateCell))
            for (rx in (p.x0 - 16).floorDiv(gateCell)..(p.x0 + 32).floorDiv(gateCell)) {
                val r = gateRoomAt(seed, gen, rx, rz) ?: continue
                val rnd = Random(seed xor (rx * 60493L) xor (rz * 94781L) xor 0x6A7F)
                if (firstTime(p, 2, r[0], r[2])) skyGateRoom(p, r[0], r[1], r[2], rnd)
            }
        val mansionCell = 192
        for (rz in (p.z0 - 24).floorDiv(mansionCell)..(p.z0 + 40).floorDiv(mansionCell))
            for (rx in (p.x0 - 24).floorDiv(mansionCell)..(p.x0 + 40).floorDiv(mansionCell)) {
                val rnd = Random(seed xor (rx * 51239471L) xor (rz * 77100221L) xor 0x3A5)
                val x = rx * mansionCell + 32 + rnd.nextInt(mansionCell - 64); val z = rz * mansionCell + 32 + rnd.nextInt(mansionCell - 64)
                val h = gen.surfaceHeight(x, z)
                if (h <= TerrainGenerator.SEA_LEVEL + 1 || h > 80) continue
                if (gen.biomeAt(x, z) != Biome.FOREST && gen.biomeAt(x, z) != Biome.PLAINS) continue
                if (firstTime(p, 3, x, z)) mansion(p, x, h, z, rnd)
            }
    }

    // ---------------------------------------------------------------- underground fort

    /** A stone-brick keep 21 x 21 under the hills: a central hall, four corner towers, crossing corridors and loot. */
    private fun fort(p: Put, x: Int, y: Int, z: Int, rnd: Random) {
        for (dx in -10..10) for (dz in -10..10) for (dy in -1..6) {
            val edge = abs(dx) == 10 || abs(dz) == 10 || dy == -1 || dy == 6
            val id = if (!edge) Blocks.AIR else if (rnd.nextInt(4) == 0) Blocks.MOSSY_STONE_BRICKS else Blocks.STONE_BRICKS
            set(p, x + dx, y + dy, z + dz, id)
        }
        // Inner walls make a cross of corridors and four rooms.
        for (d in -9..9) for (dy in 0..4) {
            if (abs(d) > 1) { set(p, x + d, y + dy, z - 3, Blocks.STONE_BRICKS); set(p, x + d, y + dy, z + 3, Blocks.STONE_BRICKS) }
            if (abs(d) > 1) { set(p, x - 3, y + dy, z + d, Blocks.STONE_BRICKS); set(p, x + 3, y + dy, z + d, Blocks.STONE_BRICKS) }
        }
        for (dy in 0..4) for (dx in -3..3) for (dz in -3..3) if (abs(dx) == 3 || abs(dz) == 3) { if (abs(dx) > 1 && abs(dz) > 1 || (abs(dx) == 3 && abs(dz) == 3)) set(p, x + dx, y + dy, z + dz, Blocks.STONE_BRICKS) }
        // Towers at the four corners are taller.
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            for (dx in -2..2) for (dz in -2..2) for (dy in 0..5) {
                val wall = abs(dx) == 2 || abs(dz) == 2
                set(p, x + sx * 7 + dx, y + dy, z + sz * 7 + dz, if (wall) Blocks.STONE_BRICKS else Blocks.AIR)
            }
            set(p, x + sx * 7, y + 1, z + sz * 7, Blocks.TORCH)
            for (dx in -1..1) set(p, x + sx * 7 + dx, y + 2, z + sz * 5, Blocks.AIR)
        }
        // The great hall's torches and a grave of bookshelves.
        for (d in intArrayOf(-8, 8)) { set(p, x + d, y + 2, z, Blocks.TORCH); set(p, x, y + 2, z + d, Blocks.TORCH) }
        set(p, x, y, z, Blocks.BOOKSHELF); set(p, x, y + 1, z, Blocks.BOOKSHELF)
        // The way out is a doorway in the middle of one wall, with iron bars across it.
        for (dy in 0..2) { set(p, x, y + dy, z - 10, Blocks.IRON_BARS) }
        val loot = listOf(
            Items.find("Iron Ingot") to 1..6, Items.find("Gold Ingot") to 0..5, Items.find("Diamond") to (if (rnd.nextInt(2) == 0) 1..3 else 0..0),
            Items.find("Emerald") to 0..3, Items.find("Golden Apple") to (if (rnd.nextInt(3) == 0) 1..1 else 0..0),
            Items.find("Enchanted Diamond Sword") to (if (rnd.nextInt(8) == 0) 1..1 else 0..0),
            Items.find("Bread") to 1..4, Items.find("Bone") to 0..4,
        )
        lootChest(p, x - 6, y, z - 6, rnd, loot, 3); lootChest(p, x + 6, y, z - 6, rnd, loot, 3)
        lootChest(p, x - 6, y, z + 6, rnd, loot, 2); lootChest(p, x + 6, y, z + 6, rnd, loot, 2)
    }

    // ---------------------------------------------------------------- sky gate room

    /** A 13 x 13 stone-brick room with twelve gate stones around a lava pool-free 3 x 3 floor, and a chest. */
    private fun skyGateRoom(p: Put, x: Int, y: Int, z: Int, rnd: Random) {
        for (dx in -6..6) for (dz in -6..6) for (dy in -1..6) {
            val edge = abs(dx) == 6 || abs(dz) == 6 || dy == -1 || dy == 6
            set(p, x + dx, y + dy, z + dz, if (!edge) Blocks.AIR else if (rnd.nextInt(4) == 0) Blocks.MOSSY_STONE_BRICKS else Blocks.STONE_BRICKS)
        }
        for (dx in -1..1) for (dz in -1..1) set(p, x + dx, y - 1, z + dz, Blocks.STONE_BRICKS)
        for (d in -1..1) for (e in intArrayOf(-2, 2)) { set(p, x + e, y, z + d, Blocks.SKY_GATE); set(p, x + d, y, z + e, Blocks.SKY_GATE) }
        for (sx in intArrayOf(-5, 5)) for (sz in intArrayOf(-5, 5)) set(p, x + sx, y + 1, z + sz, Blocks.TORCH)
        val loot = listOf(Items.find("Ender Pearl") to 2..5, Items.find("Blaze Powder") to 1..3, Items.find("Eye of Ender") to 0..2, Items.find("Iron Ingot") to 1..4, Items.find("Bread") to 1..3)
        lootChest(p, x, y, z - 5, rnd, loot, 3)
    }

    // ---------------------------------------------------------------- mansion

    /** A big dark-oak house on the surface: 3 floors, windows, a stone-brick base, rooms with carpet, bookshelves and chests. */
    private fun mansion(p: Put, cx: Int, h: Int, cz: Int, rnd: Random) {
        val w = 14; val d = 10
        // Foundation down to the ground.
        for (dx in -w..w) for (dz in -d..d) for (y in h - 5 until h) set(p, cx + dx, y, cz + dz, Blocks.STONE_BRICKS)
        for (floor in 0..2) {
            val y0 = h + floor * 5
            for (dx in -w..w) for (dz in -d..d) {
                val edge = abs(dx) == w || abs(dz) == d
                val corner = abs(dx) == w && abs(dz) == d
                set(p, cx + dx, y0, cz + dz, Blocks.DARK_OAK_PLANKS)   // floor
                for (dy in 1..4) {
                    val id = when {
                        corner -> Blocks.DARK_OAK_LOG
                        edge && dy in 2..3 && (abs(dx) + abs(dz)) % 3 == 0 -> Blocks.GLASS_PANE
                        edge -> if (dy == 4) Blocks.STONE_BRICKS else Blocks.DARK_OAK_PLANKS
                        else -> Blocks.AIR
                    }
                    set(p, cx + dx, y0 + dy, cz + dz, id)
                }
            }
            // Inner partition walls with doorways, making rooms.
            for (dz in -d + 1 until d) if (abs(dz) > 1) for (dy in 1..4) { set(p, cx - 5, y0 + dy, cz + dz, Blocks.DARK_OAK_PLANKS); set(p, cx + 5, y0 + dy, cz + dz, Blocks.DARK_OAK_PLANKS) }
            // Carpet and bookshelves and a chest in the side rooms; a torch in each room.
            for (side in intArrayOf(-1, 1)) {
                set(p, cx + side * 9, y0 + 3, cz, Blocks.TORCH)
                for (dz in -3..3 step 2) set(p, cx + side * 12, y0 + 1, cz + dz, Blocks.BOOKSHELF)
                if (rnd.nextInt(3) != 0) {
                    val loot = listOf(
                        Items.find("Gold Ingot") to 0..4, Items.find("Iron Ingot") to 0..5, Items.find("Bread") to 1..4,
                        Items.find("Diamond") to (if (rnd.nextInt(4) == 0) 1..2 else 0..0), Items.find("Emerald") to 0..2,
                        Items.find("Enchanted Diamond Pickaxe") to (if (rnd.nextInt(14) == 0) 1..1 else 0..0),
                    )
                    lootChest(p, cx + side * 12, y0 + 1, cz + 6, rnd, loot, if (side < 0) 5 else 4)
                }
            }
            set(p, cx, y0 + 3, cz - d + 1, Blocks.TORCH); set(p, cx, y0 + 3, cz + d - 1, Blocks.TORCH)
        }
        // The roof: a flat top and a front doorway.
        val top = h + 15
        for (dx in -w - 1..w + 1) for (dz in -d - 1..d + 1) set(p, cx + dx, top, cz + dz, Blocks.DARK_OAK_PLANKS)
        for (dx in -1..1) for (dy in 1..3) set(p, cx + dx, h + dy, cz - d, Blocks.AIR)
        // A ladder-like stair of planks up the middle so every floor can be reached.
        for (floor in 0..1) for (i in 0..4) set(p, cx + 1, h + floor * 5 + i + 1, cz + 3 + (if (i % 2 == 0) 0 else 1), Blocks.PLANKS)
        for (floor in 0..1) for (dz in 3..4) for (dx in 1..1) set(p, cx + dx, h + floor * 5 + 5, cz + dz, Blocks.AIR)
    }

    // ---------------------------------------------------------------- abandoned mine

    /** A tunnel [length] long from (x, y, z) towards +x (or +z when [alongZ]): 3 wide, 3 tall, with timber frames, cobwebs and torches. */
    private fun mineTunnel(p: Put, x: Int, y: Int, z: Int, length: Int, alongZ: Boolean, rnd: Random) {
        for (i in 0 until length) {
            val tx = if (alongZ) x else x + i; val tz = if (alongZ) z + i else z
            for (w in -1..1) for (h in 0..2) {
                val px = if (alongZ) tx + w else tx; val pz = if (alongZ) tz else tz + w
                set(p, px, y + h, pz, Blocks.AIR)
            }
            // Floor planks now and then, like old boardwalks.
            if (i % 3 != 2) for (w in -1..1) { val px = if (alongZ) tx + w else tx; val pz = if (alongZ) tz else tz + w; if (rnd.nextInt(4) != 0) set(p, px, y - 1, pz, Blocks.PLANKS) }
            // A timber frame every four blocks: two posts and a beam.
            if (i % 4 == 0) {
                for (side in intArrayOf(-1, 1)) {
                    val px = if (alongZ) tx + side else tx; val pz = if (alongZ) tz else tz + side
                    set(p, px, y, pz, Blocks.OAK_FENCE); set(p, px, y + 1, pz, Blocks.OAK_FENCE)
                }
                for (w in -1..1) { val px = if (alongZ) tx + w else tx; val pz = if (alongZ) tz else tz + w; set(p, px, y + 2, pz, Blocks.PLANKS) }
                if (rnd.nextInt(3) == 0) set(p, tx, y + 1, tz, Blocks.COBWEB)
            }
            if (i % 8 == 6 && rnd.nextInt(2) == 0) set(p, if (alongZ) tx - 1 else tx, y + 1, if (alongZ) tz else tz - 1, Blocks.TORCH)
        }
    }

    private fun mine(p: Put, x: Int, y: Int, z: Int, rnd: Random) {
        val main = 28 + rnd.nextInt(20)
        mineTunnel(p, x, y, z, main, false, rnd)
        // Side tunnels leave the main one every so often, in both directions.
        var i = 8
        while (i < main - 6) {
            val len = 10 + rnd.nextInt(14)
            mineTunnel(p, x + i, y, z + 1, len, true, rnd)
            if (rnd.nextBoolean()) mineTunnel(p, x + i, y, z - len, len, true, rnd)
            if (rnd.nextInt(2) == 0) {
                val loot = listOf(
                    Items.find("Coal") to 1..6, Items.find("Iron Ingot") to 0..4, Items.find("Gold Ingot") to 0..2, Items.find("Bread") to 0..3,
                    Blocks.TORCH to 2..8, Blocks.REDSTONE_DUST to 0..4, Items.find("Lapis Lazuli") to 0..4, Items.find("Diamond") to (if (rnd.nextInt(4) == 0) 1..1 else 0..0),
                    Items.find("Iron Pickaxe") to (if (rnd.nextInt(5) == 0) 1..1 else 0..0),
                )
                lootChest(p, x + i + 1, y, z + len - 1, rnd, loot, 4)
            }
            i += 10 + rnd.nextInt(8)
        }
        // A little ore at the end of the main tunnel.
        for (k in 0..3) set(p, x + main + k % 2, y + k / 2, z + 1, if (rnd.nextBoolean()) Blocks.IRON_ORE else Blocks.COAL_ORE)
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

    /*
     * Villages are plain, old-fashioned ones: a cobblestone well on a gravel square, dirt paths with lamp posts, wooden
     * houses with a stone base, windows, a door, a pitched roof and a bed, crop farms, and the blacksmith's stone house.
     */
    private fun village(p: Put, cx: Int, cz: Int, rnd: Random) {
        // Every decision about the village is made the same way in every chunk (the dice are always rolled),
        // so the houses are whole wherever the chunk borders fall.
        world.bigDone.add(Chunk.key(p.chunk.cx, p.chunk.cz) * 31L + ((cx * 73856093L) xor (cz * 19349663L) xor 7L))   // nothing old to take away here
        val villagers = 3 + rnd.nextInt(3)
        val wh = gen.surfaceHeight(cx, cz)
        levelPad(p, cx, cz, 8, 8, wh)
        // The square: gravel with a cobblestone edge, and a well in the middle.
        for (dx in -5..5) for (dz in -5..5) {
            val ring = abs(dx) == 5 || abs(dz) == 5
            for (y in wh - 3 until wh) set(p, cx + dx, y, cz + dz, Blocks.DIRT)
            set(p, cx + dx, wh, cz + dz, if (ring) Blocks.COBBLESTONE else if ((dx + dz) % 2 == 0) Blocks.GRAVEL else Blocks.DIRT_PATH)
            for (y in wh + 1..wh + 6) set(p, cx + dx, y, cz + dz, Blocks.AIR)
        }
        for (dx in -1..1) for (dz in -1..1) {
            val rim = dx != 0 || dz != 0
            if (rim) set(p, cx + dx, wh + 1, cz + dz, Blocks.COBBLESTONE)
            else { set(p, cx, wh, cz, Blocks.WATER); set(p, cx, wh - 1, cz, Blocks.WATER); set(p, cx, wh - 2, cz, Blocks.COBBLESTONE) }
        }
        for ((dx, dz) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) { set(p, cx + dx, wh + 2, cz + dz, Blocks.OAK_FENCE); set(p, cx + dx, wh + 3, cz + dz, Blocks.OAK_FENCE) }
        for (dx in -1..1) for (dz in -1..1) set(p, cx + dx, wh + 4, cz + dz, Blocks.OAK_SLAB)
        for ((dx, dz) in listOf(-4 to -4, 4 to -4, -4 to 4, 4 to 4)) lamp(p, cx + dx, wh + 1, cz + dz)
        if (p.inside(cx, cz)) repeat(villagers) { world.pendingVillagers.add(Triple(cx + 3, wh + 1, cz + 3)) }

        // The blacksmith's house, on a side of the village of its own.
        val (sx, sz) = smithySite(cx, cz).let { it[0] to it[1] }
        if (p.x0 + 15 >= sx - 8 && p.x0 <= sx + 8 && p.z0 + 15 >= sz - 8 && p.z0 <= sz + 8) smithy(p, sx, sz, cx, cz)

        // The houses: on two rings round the square, only where the ground is level enough and dry, never on top of each other or of the smithy.
        val layout = Random(seed xor (cx * 5851L) xor (cz * 19937L) xor 0x40C5)
        val sites = ArrayList<IntArray>()      // x, z, half width, level, 1 = crop farm
        for ((ring, count) in listOf(13 to 5, 20 to 7)) {
            val jitter = layout.nextDouble() * 6.2832
            for (i in 0 until count) {
                val a = jitter + i * 2 * Math.PI / count + (layout.nextDouble() - 0.5) * 0.25
                val hx = cx + (Math.cos(a) * ring).toInt(); val hz = cz + (Math.sin(a) * ring).toInt()
                val w = 2 + layout.nextInt(2)
                val farm = layout.nextInt(4) == 0
                val level = siteLevel(hx, hz, w + 2)
                if (level < 0) continue
                if (sites.any { (it[0] - hx) * (it[0] - hx) + (it[1] - hz) * (it[1] - hz) < 11 * 11 }) continue
                if ((sx - hx) * (sx - hx) + (sz - hz) * (sz - hz) < (w + 10) * (w + 10)) continue
                sites.add(intArrayOf(hx, hz, w, level, if (farm) 1 else 0))
            }
        }
        for (s in sites) levelPad(p, s[0], s[1], s[2] + 3, s[2] + 3, s[3])
        for (s in sites) path(p, cx, cz, s[0], s[1])
        for (s in sites) {
            val hr = Random(seed xor (s[0] * 7349L) xor (s[1] * 9157L) xor 0x40E)
            if (s[4] == 1) farm(p, s[0], s[1], s[3], hr) else house(p, s[0], s[1], s[3], s[2], hr, cx, cz)
        }
    }

    /** A street lamp: a fence post two high with a torch on top. */
    private fun lamp(p: Put, x: Int, y: Int, z: Int) {
        set(p, x, y, z, Blocks.OAK_FENCE); set(p, x, y + 1, z, Blocks.OAK_FENCE); set(p, x, y + 2, z, Blocks.TORCH)
    }

    /** A dirt path, two blocks wide, with a lamp post every 8 blocks. */
    private fun path(p: Put, x0: Int, z0: Int, x1: Int, z1: Int) {
        val steps = max(abs(x1 - x0), abs(z1 - z0))
        val alongX = abs(x1 - x0) >= abs(z1 - z0)
        for (i in 0..steps) {
            val x = x0 + (x1 - x0) * i / max(1, steps); val z = z0 + (z1 - z0) * i / max(1, steps)
            for (w in 0..1) {
                val px = if (alongX) x else x + w; val pz = if (alongX) z + w else z
                if (!p.inside(px, pz) || (abs(px - x0) <= 5 && abs(pz - z0) <= 5)) continue
                val y = gen.surfaceHeight(px, pz)
                val cur = p.chunk.get(px - p.x0, y, pz - p.z0)
                if (cur == Blocks.GRASS || cur == Blocks.SNOW_GRASS || cur == Blocks.DIRT || cur == Blocks.SAND) set(p, px, y, pz, Blocks.DIRT_PATH)
                if (Blocks[p.chunk.get(px - p.x0, y + 1, pz - p.z0)].render == RenderType.CROSS) set(p, px, y + 1, pz, Blocks.AIR)
            }
            if (i % 8 == 4 && i < steps - 4) {
                val lx = if (alongX) x else x - 1; val lz = if (alongX) z - 1 else z
                if (p.inside(lx, lz)) lamp(p, lx, gen.surfaceHeight(lx, lz) + 1, lz)
            }
        }
    }

    /**
     * A wooden house, whole from the ground up: a cobblestone base, plank walls with log corners, windows, a door that
     * faces the square, a pitched roof of stairs with closed gable ends, and a bed, a table, a chest and a torch inside.
     */
    private fun house(p: Put, hx: Int, hz: Int, y0: Int, w: Int, rnd: Random, cx: Int, cz: Int) {   // half width w -> 5x5 or 7x7
        val wallId = intArrayOf(Blocks.PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS)[rnd.nextInt(3)]
        val roofKind = rnd.nextInt(3)
        val stair = intArrayOf(Blocks.OAK_STAIRS, Blocks.BRICK_STAIRS, Blocks.COBBLESTONE_STAIRS)[roofKind]
        val solid = intArrayOf(Blocks.PLANKS, Blocks.BRICKS, Blocks.COBBLESTONE)[roofKind]
        val chestLoot = listOf(
            Items.find("Bread") to 1..4, Items.find("Apple") to 0..3, Items.find("Wheat") to 0..6,
            Items.find("Iron Ingot") to 0..2, Items.find("Wheat Seeds") to 0..5, Blocks.SAPLING_FIRST to 0..2,
            Items.find("Emerald") to 0..2,
        )
        // Door faces the square.
        val dx = cx - hx; val dz = cz - hz
        val doorSide = if (abs(dx) > abs(dz)) (if (dx > 0) 4 else 5) else (if (dz > 0) 2 else 3)
        val (ddx, ddz) = when (doorSide) { 2 -> 0 to w; 3 -> 0 to -w; 4 -> w to 0; else -> -w to 0 }
        // Floor and base.
        for (x in -w..w) for (z in -w..w) {
            for (y in y0 - 3 until y0) set(p, hx + x, y, hz + z, Blocks.COBBLESTONE)
            set(p, hx + x, y0, hz + z, if (abs(x) == w || abs(z) == w) Blocks.COBBLESTONE else Blocks.PLANKS)
        }
        // Walls, four high: a cobblestone course, planks above it, log corners, glass panes for windows.
        for (x in -w..w) for (z in -w..w) {
            val edge = abs(x) == w || abs(z) == w
            if (!edge) { for (y in y0 + 1..y0 + 4) set(p, hx + x, y, hz + z, Blocks.AIR); continue }
            val corner = abs(x) == w && abs(z) == w
            val window = !corner && (if (abs(z) == w) abs(x) == 1 else abs(z) == 1)
            for (y in y0 + 1..y0 + 4) {
                val id = when {
                    corner -> Blocks.LOG
                    y == y0 + 1 -> Blocks.COBBLESTONE
                    window && y == y0 + 2 -> Blocks.GLASS_PANE
                    else -> wallId
                }
                set(p, hx + x, y, hz + z, id)
            }
        }
        // The pitched roof: the ridge runs along x. Each row is a line of stairs, one block higher and one block closer to the ridge.
        for (k in 0..w) {
            val rz = w + 1 - k; val y = y0 + 4 + k
            for (x in -w - 1..w + 1) {
                set(p, hx + x, y, hz + rz, stair, 2)      // the south slope: its low side faces +z
                set(p, hx + x, y, hz - rz, stair, 3)      // the north slope
            }
            // The gable ends are closed with planks up to the roof.
            for (z in -rz + 1..rz - 1) { set(p, hx - w, y, hz + z, wallId); set(p, hx + w, y, hz + z, wallId) }
            if (k >= 1) for (z in -rz + 1..rz - 1) for (x in -w + 1..w - 1) set(p, hx + x, y, hz + z, Blocks.AIR)
        }
        for (x in -w - 1..w + 1) set(p, hx + x, y0 + 5 + w, hz, solid)       // the ridge
        for (x in -w + 1..w - 1) set(p, hx + x, y0 + 4, hz + w, wallId)
        // Door and a step.
        set(p, hx + ddx, y0 + 1, hz + ddz, Blocks.OAK_DOOR, doorSide)
        set(p, hx + ddx, y0 + 2, hz + ddz, Blocks.OAK_DOOR, doorSide or Shapes.UPPER)
        // Inside: a table with a torch on it, a chest, and in the bigger house a bed and a furnace.
        set(p, hx - w + 1, y0 + 1, hz - w + 1, Blocks.CRAFTING_TABLE, 2)
        set(p, hx - w + 1, y0 + 2, hz - w + 1, Blocks.TORCH)
        lootChest(p, hx + w - 1, y0 + 1, hz - w + 1, rnd, chestLoot)
        if (w == 3) {
            set(p, hx - 2, y0 + 1, hz + 2, Blocks.BED_FIRST + 14, 4)
            set(p, hx - 1, y0 + 1, hz + 2, Blocks.BED_FIRST + 14, 4 or Shapes.UPPER)
            set(p, hx + 2, y0 + 1, hz + 2, Blocks.FURNACE, 3)
            set(p, hx + 2, y0 + 2, hz + 2, Blocks.TORCH)
        }
    }

    /** A crop farm: a log-cornered plank border, rows of crops either side of a water channel. */
    private fun farm(p: Put, fx: Int, fz: Int, y: Int, rnd: Random) {
        val crop = when (rnd.nextInt(3)) { 0 -> Blocks.CARROTS; 1 -> Blocks.POTATOES; else -> Blocks.WHEAT_CROP }
        for (x in -3..3) for (z in -2..2) {
            val border = abs(x) == 3 || abs(z) == 2
            for (yy in y - 2 until y) set(p, fx + x, yy, fz + z, Blocks.DIRT)
            set(p, fx + x, y, fz + z, if (border) (if (abs(x) == 3 && abs(z) == 2) Blocks.LOG else Blocks.PLANKS) else if (x == 0) Blocks.WATER else Blocks.FARMLAND)
            set(p, fx + x, y + 1, fz + z, if (!border && x != 0) crop else Blocks.AIR, if (!border && x != 0) rnd.nextInt(8) else 0)
            for (yy in y + 2..y + 3) set(p, fx + x, yy, fz + z, Blocks.AIR)
        }
        set(p, fx - 3, y + 1, fz - 2, Blocks.TORCH); set(p, fx + 3, y + 1, fz + 2, Blocks.TORCH)
    }
}
