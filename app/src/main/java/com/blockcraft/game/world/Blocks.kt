package com.blockcraft.game.world

/** Atlas tile indices (16x16 tiles in a 256x256 atlas). */
object Tiles {
    const val GRASS_TOP = 0
    const val GRASS_SIDE = 1
    const val DIRT = 2
    const val STONE = 3
    const val COBBLESTONE = 4
    const val PLANKS = 5
    const val LOG_SIDE = 6
    const val LOG_TOP = 7
    const val LEAVES = 8
    const val SAND = 9
    const val GRAVEL = 10
    const val GLASS = 11
    const val WATER = 12
    const val BEDROCK = 13
    const val COAL_ORE = 14
    const val IRON_ORE = 15
    const val GOLD_ORE = 16
    const val DIAMOND_ORE = 17
    const val BRICKS = 18
    const val SNOW = 19
    const val SNOW_SIDE = 20
    const val CACTUS_SIDE = 21
    const val CACTUS_TOP = 22
    const val FLOWER_RED = 23
    const val FLOWER_YELLOW = 24
    const val TALL_GRASS = 25
    const val GLOWSTONE = 26
    const val OBSIDIAN = 27
    const val SANDSTONE_SIDE = 28
    const val SANDSTONE_TOP = 29
    const val BOOKSHELF = 30
    const val WOOL_WHITE = 31
    const val WOOL_RED = 32
    const val WOOL_BLUE = 33
    const val WOOL_GREEN = 34
    const val WOOL_YELLOW = 35
    const val BIRCH_SIDE = 36
    const val ICE = 37
    const val CLAY = 38
    const val STONE_BRICKS = 39
    const val MOSSY_COBBLESTONE = 40
    const val BIRCH_LEAVES = 41
    const val PUMPKIN_SIDE = 42
    const val PUMPKIN_TOP = 43
    const val WOOL_BLACK = 44
    const val WOOL_ORANGE = 45
    const val WOOL_PURPLE = 46
    const val DEAD_BUSH = 47
    const val CRACK_0 = 48 // 48..57: 10 break stages
    const val WHITE = 63
    const val COUNT = 64
}

enum class RenderType { NONE, CUBE, CROSS, LIQUID }

class BlockDef(
    val id: Int,
    val name: String,
    val top: Int,
    val side: Int = top,
    val bottom: Int = top,
    val render: RenderType = RenderType.CUBE,
    /** Hides neighbouring faces and casts ambient occlusion. */
    val opaque: Boolean = true,
    /** Has collision. */
    val solid: Boolean = true,
    /** Drawn in the blended (translucent) pass. */
    val translucent: Boolean = false,
    /** Stops sky light. */
    val blocksLight: Boolean = opaque,
    val emissive: Boolean = false,
    /** Seconds needed to break. */
    val hardness: Float = 0.5f,
    val breakable: Boolean = true,
    /** Faces against the same block are culled (glass, water, ice). */
    val cullSelf: Boolean = false,
)

object Blocks {
    const val AIR = 0
    const val GRASS = 1
    const val DIRT = 2
    const val STONE = 3
    const val COBBLESTONE = 4
    const val PLANKS = 5
    const val LOG = 6
    const val LEAVES = 7
    const val SAND = 8
    const val GRAVEL = 9
    const val GLASS = 10
    const val WATER = 11
    const val BEDROCK = 12
    const val COAL_ORE = 13
    const val IRON_ORE = 14
    const val GOLD_ORE = 15
    const val DIAMOND_ORE = 16
    const val BRICKS = 17
    const val SNOW_GRASS = 18
    const val CACTUS = 19
    const val FLOWER_RED = 20
    const val FLOWER_YELLOW = 21
    const val TALL_GRASS = 22
    const val GLOWSTONE = 23
    const val OBSIDIAN = 24
    const val SANDSTONE = 25
    const val BOOKSHELF = 26
    const val WOOL_WHITE = 27
    const val WOOL_RED = 28
    const val WOOL_BLUE = 29
    const val WOOL_GREEN = 30
    const val WOOL_YELLOW = 31
    const val BIRCH_LOG = 32
    const val ICE = 33
    const val CLAY = 34
    const val STONE_BRICKS = 35
    const val MOSSY_COBBLESTONE = 36
    const val BIRCH_LEAVES = 37
    const val SNOW = 38
    const val PUMPKIN = 39
    const val WOOL_BLACK = 40
    const val WOOL_ORANGE = 41
    const val WOOL_PURPLE = 42
    const val DEAD_BUSH = 43
    const val COUNT = 44

    private val defs = arrayOfNulls<BlockDef>(COUNT)

    private fun reg(d: BlockDef) { defs[d.id] = d }

    init {
        reg(BlockDef(AIR, "Air", 0, render = RenderType.NONE, opaque = false, solid = false, breakable = false))
        reg(BlockDef(GRASS, "Grass", Tiles.GRASS_TOP, Tiles.GRASS_SIDE, Tiles.DIRT, hardness = 0.45f))
        reg(BlockDef(DIRT, "Dirt", Tiles.DIRT, hardness = 0.4f))
        reg(BlockDef(STONE, "Stone", Tiles.STONE, hardness = 0.9f))
        reg(BlockDef(COBBLESTONE, "Cobblestone", Tiles.COBBLESTONE, hardness = 1.0f))
        reg(BlockDef(PLANKS, "Planks", Tiles.PLANKS, hardness = 0.8f))
        reg(BlockDef(LOG, "Log", Tiles.LOG_TOP, Tiles.LOG_SIDE, Tiles.LOG_TOP, hardness = 0.9f))
        reg(BlockDef(LEAVES, "Leaves", Tiles.LEAVES, opaque = false, blocksLight = true, hardness = 0.2f))
        reg(BlockDef(SAND, "Sand", Tiles.SAND, hardness = 0.4f))
        reg(BlockDef(GRAVEL, "Gravel", Tiles.GRAVEL, hardness = 0.45f))
        reg(BlockDef(GLASS, "Glass", Tiles.GLASS, opaque = false, hardness = 0.3f, cullSelf = true))
        reg(BlockDef(WATER, "Water", Tiles.WATER, render = RenderType.LIQUID, opaque = false, solid = false,
            translucent = true, blocksLight = false, breakable = false, cullSelf = true))
        reg(BlockDef(BEDROCK, "Bedrock", Tiles.BEDROCK, breakable = false))
        reg(BlockDef(COAL_ORE, "Coal Ore", Tiles.COAL_ORE, hardness = 1.2f))
        reg(BlockDef(IRON_ORE, "Iron Ore", Tiles.IRON_ORE, hardness = 1.3f))
        reg(BlockDef(GOLD_ORE, "Gold Ore", Tiles.GOLD_ORE, hardness = 1.3f))
        reg(BlockDef(DIAMOND_ORE, "Diamond Ore", Tiles.DIAMOND_ORE, hardness = 1.5f))
        reg(BlockDef(BRICKS, "Bricks", Tiles.BRICKS, hardness = 1.0f))
        reg(BlockDef(SNOW_GRASS, "Snowy Grass", Tiles.SNOW, Tiles.SNOW_SIDE, Tiles.DIRT, hardness = 0.45f))
        reg(BlockDef(CACTUS, "Cactus", Tiles.CACTUS_TOP, Tiles.CACTUS_SIDE, Tiles.CACTUS_TOP, opaque = false,
            blocksLight = true, hardness = 0.3f))
        reg(BlockDef(FLOWER_RED, "Red Flower", Tiles.FLOWER_RED, render = RenderType.CROSS, opaque = false,
            solid = false, hardness = 0f))
        reg(BlockDef(FLOWER_YELLOW, "Yellow Flower", Tiles.FLOWER_YELLOW, render = RenderType.CROSS, opaque = false,
            solid = false, hardness = 0f))
        reg(BlockDef(TALL_GRASS, "Tall Grass", Tiles.TALL_GRASS, render = RenderType.CROSS, opaque = false,
            solid = false, hardness = 0f))
        reg(BlockDef(GLOWSTONE, "Glowstone", Tiles.GLOWSTONE, emissive = true, hardness = 0.4f))
        reg(BlockDef(OBSIDIAN, "Obsidian", Tiles.OBSIDIAN, hardness = 3.0f))
        reg(BlockDef(SANDSTONE, "Sandstone", Tiles.SANDSTONE_TOP, Tiles.SANDSTONE_SIDE, Tiles.SANDSTONE_TOP, hardness = 0.8f))
        reg(BlockDef(BOOKSHELF, "Bookshelf", Tiles.PLANKS, Tiles.BOOKSHELF, Tiles.PLANKS, hardness = 0.7f))
        reg(BlockDef(WOOL_WHITE, "White Wool", Tiles.WOOL_WHITE, hardness = 0.35f))
        reg(BlockDef(WOOL_RED, "Red Wool", Tiles.WOOL_RED, hardness = 0.35f))
        reg(BlockDef(WOOL_BLUE, "Blue Wool", Tiles.WOOL_BLUE, hardness = 0.35f))
        reg(BlockDef(WOOL_GREEN, "Green Wool", Tiles.WOOL_GREEN, hardness = 0.35f))
        reg(BlockDef(WOOL_YELLOW, "Yellow Wool", Tiles.WOOL_YELLOW, hardness = 0.35f))
        reg(BlockDef(BIRCH_LOG, "Birch Log", Tiles.LOG_TOP, Tiles.BIRCH_SIDE, Tiles.LOG_TOP, hardness = 0.9f))
        reg(BlockDef(ICE, "Ice", Tiles.ICE, opaque = false, translucent = true, blocksLight = false,
            hardness = 0.4f, cullSelf = true))
        reg(BlockDef(CLAY, "Clay", Tiles.CLAY, hardness = 0.45f))
        reg(BlockDef(STONE_BRICKS, "Stone Bricks", Tiles.STONE_BRICKS, hardness = 1.0f))
        reg(BlockDef(MOSSY_COBBLESTONE, "Mossy Cobblestone", Tiles.MOSSY_COBBLESTONE, hardness = 1.0f))
        reg(BlockDef(BIRCH_LEAVES, "Birch Leaves", Tiles.BIRCH_LEAVES, opaque = false, blocksLight = true, hardness = 0.2f))
        reg(BlockDef(SNOW, "Snow", Tiles.SNOW, hardness = 0.3f))
        reg(BlockDef(PUMPKIN, "Pumpkin", Tiles.PUMPKIN_TOP, Tiles.PUMPKIN_SIDE, Tiles.PUMPKIN_TOP, hardness = 0.6f))
        reg(BlockDef(WOOL_BLACK, "Black Wool", Tiles.WOOL_BLACK, hardness = 0.35f))
        reg(BlockDef(WOOL_ORANGE, "Orange Wool", Tiles.WOOL_ORANGE, hardness = 0.35f))
        reg(BlockDef(WOOL_PURPLE, "Purple Wool", Tiles.WOOL_PURPLE, hardness = 0.35f))
        reg(BlockDef(DEAD_BUSH, "Dead Bush", Tiles.DEAD_BUSH, render = RenderType.CROSS, opaque = false,
            solid = false, hardness = 0f))
    }

    val all: List<BlockDef> = defs.map { it!! }

    // Flat lookup tables for the hot paths (meshing, physics).
    @JvmField val opaque = BooleanArray(COUNT)
    @JvmField val solid = BooleanArray(COUNT)
    @JvmField val blocksLight = BooleanArray(COUNT)

    init {
        for (d in all) {
            opaque[d.id] = d.opaque
            solid[d.id] = d.solid
            blocksLight[d.id] = d.blocksLight
        }
    }

    operator fun get(id: Int): BlockDef = all[id]

    /** Blocks offered in the creative inventory, in display order. */
    val placeable: List<Int> = buildPlaceable()

    private fun buildPlaceable(): List<Int> {
        val out = ArrayList<Int>()
        for (d in all) if (d.id != AIR && d.id != BEDROCK) out.add(d.id)
        out.add(BEDROCK)
        return out
    }
}
