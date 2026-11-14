package com.vishucraft.game.world

import java.util.Random

/** The kinds of world you can start. [kind]: 0 normal land, 2 Sky Block, 3 One Block (1 is unused). */
enum class WorldType(val title: String, val blurb: String, val survival: Boolean, val hardcore: Boolean, val flat: Boolean, val kind: Int) {
    SURVIVAL("Normal Survival", "Hills, forests, caves and villages. Collect, craft and survive.", true, false, false, 0),
    CREATIVE("Creative: Normal World", "Unlimited blocks and flying over real land. No damage.", false, false, false, 0),
    FLAT("Creative: Flat World", "A flat world with seas and beaches, made for building.", false, false, true, 0),
    SKYBLOCK("Sky Block", "A tiny island in the sky over the void. Survive and grow it.", true, false, false, 2),
    ONEBLOCK("One Block", "Start on one block. Break it and it turns into another. Every 50 breaks it levels up.", true, false, false, 3),
    HARDCORE("Hardcore Survival", "Normal survival with ONE life. If you die you cannot respawn.", true, true, false, 0);

    /** A little symbol for the picture on the card. */
    val icon get() = when (this) { SURVIVAL -> Blocks.GRASS; CREATIVE -> Blocks.DIAMOND_ORE; FLAT -> Blocks.SAND; SKYBLOCK -> Blocks.LEAVES; ONEBLOCK -> Blocks.GRASS; HARDCORE -> Blocks.OBSIDIAN }

    companion object {
        /** What a saved world is. */
        fun of(level: LevelData): WorldType = when {
            level.worldKind == 2 -> SKYBLOCK
            level.worldKind == 3 -> ONEBLOCK
            level.mode == GameMode.CREATIVE -> if (level.flat) FLAT else CREATIVE
            level.hardcore -> HARDCORE
            else -> SURVIVAL
        }
    }
}

/** The worlds that are empty sky: a Sky Block island, or just the One Block. */
class VoidGenerator(private val kind: Int) : WorldGenerator {
    override fun biomeAt(x: Int, z: Int) = Biome.PLAINS
    override fun surfaceHeight(x: Int, z: Int): Int = OneBlock.Y

    override fun generate(chunk: Chunk) {
        if (chunk.cx !in -1..0 || chunk.cz !in -1..0) return
        val y = OneBlock.Y
        fun put(wx: Int, py: Int, wz: Int, id: Int) {
            val x = wx - chunk.cx * 16; val z = wz - chunk.cz * 16
            if (x in 0..15 && z in 0..15) chunk.set(x, py, z, id)
        }
        if (kind == 3) { put(0, y, 0, Blocks.GRASS); return }
        // Sky Block: a 5 x 5 island around (0, 0), grass over two layers of dirt over a stone core, and a small tree.
        for (dx in -2..2) for (dz in -2..2) {
            put(dx, y, dz, Blocks.GRASS); put(dx, y - 1, dz, Blocks.DIRT); put(dx, y - 2, dz, Blocks.DIRT)
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) put(dx, y - 3, dz, Blocks.STONE)
        }
        val tx = 2; val tz = 2
        for (h in 1..4) put(tx, y + h, tz, Blocks.LOG)
        for (dx in -2..2) for (dz in -2..2) for (dy in 3..5) {
            if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue
            if (dy == 5 && (Math.abs(dx) > 1 || Math.abs(dz) > 1)) continue
            if (dx != 0 || dz != 0 || dy == 5) put(tx + dx, y + dy, tz + dz, Blocks.LEAVES)
        }
    }
}

/** The One Block: where it is, what it turns into, and how it levels up. */
object OneBlock {
    const val X = 0; const val Y = 70; const val Z = 0
    const val BREAKS_PER_LEVEL = 50
    const val MAX_LEVEL = 10

    fun level(breaks: Int) = (breaks / BREAKS_PER_LEVEL).coerceAtMost(MAX_LEVEL)

    /** The blocks added at each level; level n can turn into everything from levels 0..n. */
    private fun add(level: Int): List<Int> = when (level) {
        0 -> listOf(Blocks.GRASS, Blocks.DIRT, Blocks.SAND, Blocks.GRAVEL, Blocks.LOG, Blocks.LEAVES, Blocks.SAPLING_FIRST, Blocks.CLAY)
        1 -> listOf(Blocks.STONE, Blocks.COBBLESTONE, Blocks.COAL_ORE, Blocks.PLANKS, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.SNOW_GRASS)
        2 -> listOf(Blocks.IRON_ORE, Blocks.SANDSTONE, Blocks.MOSSY_COBBLESTONE, Blocks.PUMPKIN, Blocks.CACTUS, Blocks.FLOWER_RED, Blocks.FLOWER_YELLOW)
        3 -> listOf(Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.GLASS, Blocks.COPPER_ORE, Blocks.ICE)
        4 -> listOf(Blocks.DIAMOND_ORE, Blocks.BOOKSHELF, Blocks.OBSIDIAN, Blocks.STONE_BRICKS, Blocks.BRICKS)
        5 -> listOf(Blocks.EMERALD_ORE, Blocks.GLOWSTONE, Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.TNT)
        6 -> listOf(Blocks.END_STONE, Blocks.PURPUR, Blocks.MAGMA, Blocks.SEA_LANTERN, Blocks.PRISMARINE)
        7 -> listOf(Blocks.CRYING_OBSIDIAN, Blocks.DEEPSLATE, Blocks.TERRACOTTA, Blocks.SMOOTH_STONE)
        8 -> listOf(Blocks.CHEST, Blocks.CHEST, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE)
        9 -> listOf(Blocks.CHEST, Blocks.GOLD_ORE, Blocks.OBSIDIAN)
        else -> listOf(Blocks.CHEST, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.CRYING_OBSIDIAN)
    }

    fun pool(level: Int): List<Int> { val out = ArrayList<Int>(); for (l in 0..level.coerceIn(0, MAX_LEVEL)) out.addAll(add(l)); return out }

    /** A random block for [level]. */
    fun pick(level: Int, rnd: Random): Int = pool(level).let { it[rnd.nextInt(it.size)] }

    /** Passive creatures that sometimes appear on the block from level 2, and monsters from level 4. */
    fun creatureChance(level: Int) = if (level >= 2) 0.07f else 0f
}
