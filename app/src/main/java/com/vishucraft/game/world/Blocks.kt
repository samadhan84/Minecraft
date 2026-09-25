package com.vishucraft.game.world

/**
 * Texture tiles are allocated by name; [com.vishucraft.game.render.TextureAtlas] paints each name.
 * Indices are only valid for the running process (never persisted).
 */
object Tiles {
    private val names = LinkedHashMap<String, Int>()

    fun id(name: String): Int = synchronized(names) { names.getOrPut(name) { names.size } }
    fun all(): Map<String, Int> = synchronized(names) { LinkedHashMap(names) }

    val WHITE = id("white")
    val SUN = id("sun")
    val MOON = id("moon")
    val CRACK_0 = id("crack_0").also { for (i in 1..9) id("crack_$i") }
}

enum class RenderType { NONE, CUBE, CROSS, LIQUID, FLAT, BOX, PISTON_HEAD, SHAPE, RAIL, PORTAL }
enum class ToolType { NONE, PICKAXE, AXE, SHOVEL, HOE, SWORD }
enum class Facing { NONE, HORIZONTAL, ALL }
enum class Category(val title: String) { BUILDING("Building"), COLORED("Colored"), NATURE("Nature"), REDSTONE("Redstone") }

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
    /** Seconds needed to break by hand (before tool bonuses). */
    val hardness: Float = 0.5f,
    val breakable: Boolean = true,
    /** Faces against the same block are culled (glass, water, ice). */
    val cullSelf: Boolean = false,
    val tool: ToolType = ToolType.NONE,
    val category: Category = Category.BUILDING,
    val facing: Facing = Facing.NONE,
    /** Tile on the facing side, and on the opposite side (pistons). */
    val front: Int = side,
    val back: Int = side,
    /** Pops off when the block below is removed. */
    val needsSupport: Boolean = false,
    /** Model bounds for [RenderType.BOX]: x0, y0, z0, x1, y1, z1. */
    val box: FloatArray? = null,
    val inInventory: Boolean = true,
    val movable: Boolean = true,
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
    // Stone family
    const val GRANITE = 44
    const val POLISHED_GRANITE = 45
    const val DIORITE = 46
    const val POLISHED_DIORITE = 47
    const val ANDESITE = 48
    const val POLISHED_ANDESITE = 49
    const val DEEPSLATE = 50
    const val COBBLED_DEEPSLATE = 51
    const val TUFF = 52
    const val CALCITE = 53
    const val SMOOTH_STONE = 54
    const val MOSSY_STONE_BRICKS = 55
    const val CRACKED_STONE_BRICKS = 56
    const val CHISELED_STONE_BRICKS = 57
    // Ores
    const val REDSTONE_ORE = 58
    const val LAPIS_ORE = 59
    const val EMERALD_ORE = 60
    const val COPPER_ORE = 61
    // Mineral blocks
    const val COAL_BLOCK = 62
    const val IRON_BLOCK = 63
    const val GOLD_BLOCK = 64
    const val DIAMOND_BLOCK = 65
    const val EMERALD_BLOCK = 66
    const val LAPIS_BLOCK = 67
    const val REDSTONE_BLOCK = 68
    const val COPPER_BLOCK = 69
    const val QUARTZ_BLOCK = 70
    const val NETHERITE_BLOCK = 71
    // Wood
    const val SPRUCE_LOG = 72
    const val SPRUCE_PLANKS = 73
    const val SPRUCE_LEAVES = 74
    const val BIRCH_PLANKS = 75
    const val JUNGLE_LOG = 76
    const val JUNGLE_PLANKS = 77
    const val JUNGLE_LEAVES = 78
    const val ACACIA_LOG = 79
    const val ACACIA_PLANKS = 80
    const val ACACIA_LEAVES = 81
    const val DARK_OAK_LOG = 82
    const val DARK_OAK_PLANKS = 83
    const val DARK_OAK_LEAVES = 84
    // Remaining wool colours
    const val WOOL_LIGHT_GRAY = 85
    const val WOOL_GRAY = 86
    const val WOOL_BROWN = 87
    const val WOOL_CYAN = 88
    const val WOOL_LIGHT_BLUE = 89
    const val WOOL_LIME = 90
    const val WOOL_MAGENTA = 91
    const val WOOL_PINK = 92
    const val CONCRETE_FIRST = 93 // 16 colours, in DYES order
    const val TERRACOTTA = 109
    const val TERRACOTTA_FIRST = 110 // 16 colours
    // Nether / End
    const val NETHERRACK = 126
    const val SOUL_SAND = 127
    const val NETHER_BRICKS = 128
    const val MAGMA = 129
    const val END_STONE = 130
    const val PURPUR = 131
    const val CRYING_OBSIDIAN = 132
    // Ocean
    const val PRISMARINE = 133
    const val PRISMARINE_BRICKS = 134
    const val DARK_PRISMARINE = 135
    const val SEA_LANTERN = 136
    const val SPONGE = 137
    const val WET_SPONGE = 138
    // Misc
    const val CRAFTING_TABLE = 139
    const val FURNACE = 140
    const val CHEST = 141
    const val TNT = 142
    const val HAY_BALE = 143
    const val MELON = 144
    const val JACK_O_LANTERN = 145
    const val MYCELIUM = 146
    const val PODZOL = 147
    const val COARSE_DIRT = 148
    const val PACKED_ICE = 149
    const val BLUE_ICE = 150
    const val RED_SAND = 151
    const val RED_SANDSTONE = 152
    const val FARMLAND = 153
    const val DIRT_PATH = 154
    const val SUGAR_CANE = 155
    const val BROWN_MUSHROOM = 156
    const val RED_MUSHROOM = 157
    const val COBWEB = 158
    const val TORCH = 159
    const val FERN = 160
    const val BLUE_ORCHID = 161
    const val NOTE_BLOCK = 162
    const val JUKEBOX = 163
    const val SMOOTH_SANDSTONE = 164
    // Slabs
    const val STONE_SLAB = 165
    const val COBBLESTONE_SLAB = 166
    const val OAK_SLAB = 167
    const val STONE_BRICK_SLAB = 168
    const val BRICK_SLAB = 169
    const val SANDSTONE_SLAB = 170
    const val STAINED_GLASS_FIRST = 171 // 16 colours
    // Redstone
    const val REDSTONE_DUST = 187
    const val REDSTONE_TORCH = 188
    const val LEVER = 189
    const val STONE_BUTTON = 190
    const val REDSTONE_LAMP = 191
    const val REDSTONE_LAMP_ON = 192
    const val PISTON = 193
    const val STICKY_PISTON = 194
    const val PISTON_HEAD = 195
    const val ANCIENT_DEBRIS = 196
    const val LAVA = 197
    const val WHEAT_CROP = 198
    const val CARROTS = 199
    const val POTATOES = 200
    const val SAPLING_FIRST = 201 // oak, spruce, birch, jungle, acacia, dark oak
    // Building pieces (SHAPE render type, see Shapes)
    const val OAK_DOOR = 207
    const val IRON_DOOR = 208
    const val OAK_TRAPDOOR = 209
    const val OAK_STAIRS = 210
    const val COBBLESTONE_STAIRS = 211
    const val STONE_BRICK_STAIRS = 212
    const val BRICK_STAIRS = 213
    const val SANDSTONE_STAIRS = 214
    const val OAK_FENCE = 215
    const val OAK_FENCE_GATE = 216
    const val LADDER = 217
    const val GLASS_PANE = 218
    const val IRON_BARS = 219
    // More redstone
    const val REPEATER = 220
    const val OBSERVER = 221
    const val DAYLIGHT_SENSOR = 222
    const val PRESSURE_PLATE = 223
    const val HOPPER = 224
    const val RAIL = 225
    const val POWERED_RAIL = 226
    // Portals to the other dimensions
    const val EMBER_PORTAL = 227
    const val SKY_PORTAL = 228
    // Door pack: one of each for every other wood type (spruce, birch, jungle, acacia, dark oak).
    const val WOOD_DOOR_FIRST = 229
    const val IRON_TRAPDOOR = 234
    const val WOOD_TRAPDOOR_FIRST = 235
    const val WOOD_FENCE_FIRST = 240
    const val WOOD_GATE_FIRST = 245
    /** Beds in the 16 dye colours (DYES order). Two blocks: foot and head (meta has Shapes.UPPER). */
    const val BED_FIRST = 250
    /** Buttons and pressure plates for each wood (oak, then EXTRA_WOODS order). */
    const val WOOD_BUTTON_FIRST = 266
    const val WOOD_PLATE_FIRST = 272
    /** Weighted pressure plates: the more things stand on them, the stronger the signal. */
    const val GOLD_PLATE = 278
    const val IRON_PLATE = 279
    const val COUNT = 280

    fun isButton(id: Int) = id == STONE_BUTTON || id in WOOD_BUTTON_FIRST until WOOD_BUTTON_FIRST + 6
    fun isPlate(id: Int) = id == PRESSURE_PLATE || id in WOOD_PLATE_FIRST until WOOD_PLATE_FIRST + 6 || id == GOLD_PLATE || id == IRON_PLATE

    fun isBed(id: Int) = id in BED_FIRST until BED_FIRST + 16

    /** The five extra wood types, in id order for the door pack. */
    val EXTRA_WOODS = listOf("spruce", "birch", "jungle", "acacia", "dark_oak")

    fun isDoor(id: Int) = id == OAK_DOOR || id == IRON_DOOR || id in WOOD_DOOR_FIRST until WOOD_DOOR_FIRST + 5
    fun isTrapdoor(id: Int) = id == OAK_TRAPDOOR || id == IRON_TRAPDOOR || id in WOOD_TRAPDOOR_FIRST until WOOD_TRAPDOOR_FIRST + 5
    fun isFence(id: Int) = id == OAK_FENCE || id in WOOD_FENCE_FIRST until WOOD_FENCE_FIRST + 5
    fun isGate(id: Int) = id == OAK_FENCE_GATE || id in WOOD_GATE_FIRST until WOOD_GATE_FIRST + 5
    /** Doors, trapdoors and gates a player can open by hand (iron ones need redstone). */
    fun opensByHand(id: Int) = (isDoor(id) || isTrapdoor(id) || isGate(id)) && id != IRON_DOOR && id != IRON_TRAPDOOR

    /** Dye colours used for wool, concrete, terracotta and stained glass. */
    val DYES = listOf(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
    )

    private val defs = arrayOfNulls<BlockDef>(COUNT)

    /** Light for blocks without a rule in [lightLevel] (lava...). Declared before init so it exists there. */
    @JvmField val extraLight = IntArray(COUNT)
    private fun t(name: String) = Tiles.id(name)
    private fun reg(d: BlockDef) {
        check(defs[d.id] == null) { "duplicate block id ${d.id}" }
        defs[d.id] = d
    }

    private fun cube(id: Int, name: String, tile: String, hardness: Float, tool: ToolType,
                     cat: Category = Category.BUILDING, emissive: Boolean = false) =
        reg(BlockDef(id, name, t(tile), hardness = hardness, tool = tool, category = cat, emissive = emissive))

    private fun column(id: Int, name: String, top: String, side: String, bottom: String, hardness: Float,
                       tool: ToolType, cat: Category = Category.BUILDING) =
        reg(BlockDef(id, name, t(top), t(side), t(bottom), hardness = hardness, tool = tool, category = cat))

    private fun plant(id: Int, name: String, tile: String, cat: Category = Category.NATURE, emissive: Boolean = false,
                      solid: Boolean = false, hardness: Float = 0f, tool: ToolType = ToolType.NONE) =
        reg(BlockDef(id, name, t(tile), render = RenderType.CROSS, opaque = false, solid = solid, hardness = hardness,
            category = cat, needsSupport = true, emissive = emissive, tool = tool, movable = false))

    private fun leaves(id: Int, name: String, tile: String) =
        reg(BlockDef(id, name, t(tile), opaque = false, blocksLight = true, hardness = 0.2f, tool = ToolType.HOE,
            category = Category.NATURE))

    private fun slab(id: Int, name: String, top: String, side: String, tool: ToolType) =
        reg(BlockDef(id, name, t(top), t(side), t(top), render = RenderType.BOX, opaque = false, blocksLight = true,
            hardness = 0.8f, tool = tool, box = floatArrayOf(0f, 0f, 0f, 1f, 0.5f, 1f)))

    init {
        val P = ToolType.PICKAXE; val A = ToolType.AXE; val S = ToolType.SHOVEL
        val N = Category.NATURE; val C = Category.COLORED

        reg(BlockDef(AIR, "Air", 0, render = RenderType.NONE, opaque = false, solid = false, breakable = false, inInventory = false))
        reg(BlockDef(GRASS, "Grass Block", t("grass_top"), t("grass_side"), t("dirt"), hardness = 0.45f, tool = S, category = N))
        cube(DIRT, "Dirt", "dirt", 0.4f, S, N)
        cube(STONE, "Stone", "stone", 0.9f, P)
        cube(COBBLESTONE, "Cobblestone", "cobblestone", 1.0f, P)
        cube(PLANKS, "Oak Planks", "oak_planks", 0.8f, A)
        column(LOG, "Oak Log", "oak_log_top", "oak_log", "oak_log_top", 0.9f, A, N)
        leaves(LEAVES, "Oak Leaves", "oak_leaves")
        cube(SAND, "Sand", "sand", 0.4f, S, N)
        cube(GRAVEL, "Gravel", "gravel", 0.45f, S, N)
        reg(BlockDef(GLASS, "Glass", t("glass"), opaque = false, hardness = 0.3f, cullSelf = true))
        reg(BlockDef(WATER, "Water", t("water"), render = RenderType.LIQUID, opaque = false, solid = false,
            translucent = true, blocksLight = false, breakable = false, cullSelf = true, inInventory = false, movable = false))
        reg(BlockDef(BEDROCK, "Bedrock", t("bedrock"), breakable = false, movable = false))
        cube(COAL_ORE, "Coal Ore", "coal_ore", 1.2f, P, N)
        cube(IRON_ORE, "Iron Ore", "iron_ore", 1.3f, P, N)
        cube(GOLD_ORE, "Gold Ore", "gold_ore", 1.3f, P, N)
        cube(DIAMOND_ORE, "Diamond Ore", "diamond_ore", 1.5f, P, N)
        cube(BRICKS, "Bricks", "bricks", 1.0f, P)
        reg(BlockDef(SNOW_GRASS, "Snowy Grass Block", t("snow"), t("snow_side"), t("dirt"), hardness = 0.45f, tool = S, category = N))
        reg(BlockDef(CACTUS, "Cactus", t("cactus_top"), t("cactus_side"), t("cactus_top"), opaque = false,
            blocksLight = true, hardness = 0.3f, category = N))
        plant(FLOWER_RED, "Poppy", "flower_red")
        plant(FLOWER_YELLOW, "Dandelion", "flower_yellow")
        plant(TALL_GRASS, "Tall Grass", "tall_grass")
        cube(GLOWSTONE, "Glowstone", "glowstone", 0.4f, ToolType.NONE, emissive = true)
        reg(BlockDef(OBSIDIAN, "Obsidian", t("obsidian"), hardness = 6.0f, tool = P, movable = false))
        column(SANDSTONE, "Sandstone", "sandstone_top", "sandstone_side", "sandstone_top", 0.8f, P)
        column(BOOKSHELF, "Bookshelf", "oak_planks", "bookshelf", "oak_planks", 0.7f, A)
        cube(WOOL_WHITE, "White Wool", "wool_white", 0.35f, ToolType.NONE, C)
        cube(WOOL_RED, "Red Wool", "wool_red", 0.35f, ToolType.NONE, C)
        cube(WOOL_BLUE, "Blue Wool", "wool_blue", 0.35f, ToolType.NONE, C)
        cube(WOOL_GREEN, "Green Wool", "wool_green", 0.35f, ToolType.NONE, C)
        cube(WOOL_YELLOW, "Yellow Wool", "wool_yellow", 0.35f, ToolType.NONE, C)
        column(BIRCH_LOG, "Birch Log", "birch_log_top", "birch_log", "birch_log_top", 0.9f, A, N)
        reg(BlockDef(ICE, "Ice", t("ice"), opaque = false, translucent = true, blocksLight = false,
            hardness = 0.4f, cullSelf = true, tool = P, category = N))
        cube(CLAY, "Clay", "clay", 0.45f, S, N)
        cube(STONE_BRICKS, "Stone Bricks", "stone_bricks", 1.0f, P)
        cube(MOSSY_COBBLESTONE, "Mossy Cobblestone", "mossy_cobblestone", 1.0f, P)
        leaves(BIRCH_LEAVES, "Birch Leaves", "birch_leaves")
        cube(SNOW, "Snow Block", "snow", 0.3f, S, N)
        column(PUMPKIN, "Pumpkin", "pumpkin_top", "pumpkin_side", "pumpkin_top", 0.6f, A, N)
        cube(WOOL_BLACK, "Black Wool", "wool_black", 0.35f, ToolType.NONE, C)
        cube(WOOL_ORANGE, "Orange Wool", "wool_orange", 0.35f, ToolType.NONE, C)
        cube(WOOL_PURPLE, "Purple Wool", "wool_purple", 0.35f, ToolType.NONE, C)
        plant(DEAD_BUSH, "Dead Bush", "dead_bush")

        cube(GRANITE, "Granite", "granite", 0.9f, P)
        cube(POLISHED_GRANITE, "Polished Granite", "polished_granite", 0.9f, P)
        cube(DIORITE, "Diorite", "diorite", 0.9f, P)
        cube(POLISHED_DIORITE, "Polished Diorite", "polished_diorite", 0.9f, P)
        cube(ANDESITE, "Andesite", "andesite", 0.9f, P)
        cube(POLISHED_ANDESITE, "Polished Andesite", "polished_andesite", 0.9f, P)
        column(DEEPSLATE, "Deepslate", "deepslate_top", "deepslate", "deepslate_top", 1.4f, P)
        cube(COBBLED_DEEPSLATE, "Cobbled Deepslate", "cobbled_deepslate", 1.5f, P)
        cube(TUFF, "Tuff", "tuff", 0.9f, P)
        cube(CALCITE, "Calcite", "calcite", 0.6f, P)
        column(SMOOTH_STONE, "Smooth Stone", "smooth_stone", "smooth_stone_side", "smooth_stone", 1.0f, P)
        cube(MOSSY_STONE_BRICKS, "Mossy Stone Bricks", "mossy_stone_bricks", 1.0f, P)
        cube(CRACKED_STONE_BRICKS, "Cracked Stone Bricks", "cracked_stone_bricks", 1.0f, P)
        cube(CHISELED_STONE_BRICKS, "Chiseled Stone Bricks", "chiseled_stone_bricks", 1.0f, P)

        cube(REDSTONE_ORE, "Redstone Ore", "redstone_ore", 1.3f, P, N)
        cube(LAPIS_ORE, "Lapis Lazuli Ore", "lapis_ore", 1.3f, P, N)
        cube(EMERALD_ORE, "Emerald Ore", "emerald_ore", 1.4f, P, N)
        cube(COPPER_ORE, "Copper Ore", "copper_ore", 1.2f, P, N)

        cube(COAL_BLOCK, "Block of Coal", "coal_block", 1.5f, P)
        cube(IRON_BLOCK, "Block of Iron", "iron_block", 1.8f, P)
        cube(GOLD_BLOCK, "Block of Gold", "gold_block", 1.5f, P)
        cube(DIAMOND_BLOCK, "Block of Diamond", "diamond_block", 1.8f, P)
        cube(EMERALD_BLOCK, "Block of Emerald", "emerald_block", 1.8f, P)
        cube(LAPIS_BLOCK, "Block of Lapis Lazuli", "lapis_block", 1.5f, P)
        reg(BlockDef(REDSTONE_BLOCK, "Block of Redstone", t("redstone_block"), hardness = 1.5f, tool = P, category = Category.REDSTONE))
        cube(COPPER_BLOCK, "Block of Copper", "copper_block", 1.5f, P)
        cube(QUARTZ_BLOCK, "Block of Quartz", "quartz_block", 0.8f, P)
        reg(BlockDef(NETHERITE_BLOCK, "Block of Netherite", t("netherite_block"), hardness = 6f, tool = P, movable = false))

        column(SPRUCE_LOG, "Spruce Log", "spruce_log_top", "spruce_log", "spruce_log_top", 0.9f, A, N)
        cube(SPRUCE_PLANKS, "Spruce Planks", "spruce_planks", 0.8f, A)
        leaves(SPRUCE_LEAVES, "Spruce Leaves", "spruce_leaves")
        cube(BIRCH_PLANKS, "Birch Planks", "birch_planks", 0.8f, A)
        column(JUNGLE_LOG, "Jungle Log", "jungle_log_top", "jungle_log", "jungle_log_top", 0.9f, A, N)
        cube(JUNGLE_PLANKS, "Jungle Planks", "jungle_planks", 0.8f, A)
        leaves(JUNGLE_LEAVES, "Jungle Leaves", "jungle_leaves")
        column(ACACIA_LOG, "Acacia Log", "acacia_log_top", "acacia_log", "acacia_log_top", 0.9f, A, N)
        cube(ACACIA_PLANKS, "Acacia Planks", "acacia_planks", 0.8f, A)
        leaves(ACACIA_LEAVES, "Acacia Leaves", "acacia_leaves")
        column(DARK_OAK_LOG, "Dark Oak Log", "dark_oak_log_top", "dark_oak_log", "dark_oak_log_top", 0.9f, A, N)
        cube(DARK_OAK_PLANKS, "Dark Oak Planks", "dark_oak_planks", 0.8f, A)
        leaves(DARK_OAK_LEAVES, "Dark Oak Leaves", "dark_oak_leaves")

        val extraWool = listOf(
            WOOL_LIGHT_GRAY to "light_gray", WOOL_GRAY to "gray", WOOL_BROWN to "brown", WOOL_CYAN to "cyan",
            WOOL_LIGHT_BLUE to "light_blue", WOOL_LIME to "lime", WOOL_MAGENTA to "magenta", WOOL_PINK to "pink",
        )
        for ((id, c) in extraWool) cube(id, "${pretty(c)} Wool", "wool_$c", 0.35f, ToolType.NONE, C)
        for ((i, c) in DYES.withIndex()) {
            cube(CONCRETE_FIRST + i, "${pretty(c)} Concrete", "concrete_$c", 0.9f, P, C)
            cube(TERRACOTTA_FIRST + i, "${pretty(c)} Terracotta", "terracotta_$c", 0.9f, P, C)
            reg(BlockDef(STAINED_GLASS_FIRST + i, "${pretty(c)} Stained Glass", t("stained_glass_$c"), opaque = false,
                translucent = true, blocksLight = false, hardness = 0.3f, cullSelf = true, category = C))
        }
        cube(TERRACOTTA, "Terracotta", "terracotta", 0.9f, P, C)

        cube(NETHERRACK, "Ember Rock", "netherrack", 0.4f, P, N)
        cube(SOUL_SAND, "Ash Sand", "soul_sand", 0.5f, S, N)
        cube(NETHER_BRICKS, "Ember Bricks", "nether_bricks", 1.0f, P)
        cube(MAGMA, "Magma Block", "magma", 0.5f, P, N, emissive = true)
        cube(END_STONE, "Pale Stone", "end_stone", 1.2f, P, N)
        cube(PURPUR, "Sky Stone", "purpur", 1.0f, P)
        reg(BlockDef(CRYING_OBSIDIAN, "Crying Obsidian", t("crying_obsidian"), hardness = 6f, tool = P, emissive = true, movable = false))

        cube(PRISMARINE, "Prismarine", "prismarine", 1.0f, P)
        cube(PRISMARINE_BRICKS, "Prismarine Bricks", "prismarine_bricks", 1.0f, P)
        cube(DARK_PRISMARINE, "Dark Prismarine", "dark_prismarine", 1.0f, P)
        cube(SEA_LANTERN, "Sea Lantern", "sea_lantern", 0.4f, ToolType.NONE, emissive = true)
        cube(SPONGE, "Sponge", "sponge", 0.4f, ToolType.HOE)
        cube(WET_SPONGE, "Wet Sponge", "wet_sponge", 0.4f, ToolType.HOE)

        reg(BlockDef(CRAFTING_TABLE, "Crafting Table", t("crafting_table_top"), t("crafting_table_side"), t("oak_planks"),
            hardness = 0.8f, tool = A, facing = Facing.HORIZONTAL, front = t("crafting_table_front")))
        reg(BlockDef(FURNACE, "Furnace", t("furnace_top"), t("furnace_side"), t("furnace_top"),
            hardness = 1.2f, tool = P, facing = Facing.HORIZONTAL, front = t("furnace_front")))
        reg(BlockDef(CHEST, "Chest", t("chest_top"), t("chest_side"), t("chest_top"),
            hardness = 0.8f, tool = A, facing = Facing.HORIZONTAL, front = t("chest_front")))
        reg(BlockDef(TNT, "TNT", t("tnt_top"), t("tnt_side"), t("tnt_bottom"), hardness = 0f, category = Category.REDSTONE))
        column(HAY_BALE, "Hay Bale", "hay_top", "hay_side", "hay_top", 0.4f, ToolType.HOE, N)
        column(MELON, "Melon", "melon_top", "melon_side", "melon_top", 0.6f, A, N)
        reg(BlockDef(JACK_O_LANTERN, "Jack o'Lantern", t("pumpkin_top"), t("pumpkin_side"), t("pumpkin_top"),
            hardness = 0.6f, tool = A, emissive = true, facing = Facing.HORIZONTAL, front = t("jack_o_lantern_front")))
        column(MYCELIUM, "Mycelium", "mycelium_top", "mycelium_side", "dirt", 0.45f, S, N)
        column(PODZOL, "Podzol", "podzol_top", "podzol_side", "dirt", 0.45f, S, N)
        cube(COARSE_DIRT, "Coarse Dirt", "coarse_dirt", 0.4f, S, N)
        cube(PACKED_ICE, "Packed Ice", "packed_ice", 0.5f, P, N)
        cube(BLUE_ICE, "Blue Ice", "blue_ice", 0.9f, P, N)
        cube(RED_SAND, "Red Sand", "red_sand", 0.4f, S, N)
        column(RED_SANDSTONE, "Red Sandstone", "red_sandstone_top", "red_sandstone_side", "red_sandstone_top", 0.8f, P)
        column(FARMLAND, "Farmland", "farmland", "dirt", "dirt", 0.4f, S, N)
        column(DIRT_PATH, "Dirt Path", "dirt_path_top", "dirt_path_side", "dirt", 0.4f, S, N)
        plant(SUGAR_CANE, "Sugar Cane", "sugar_cane")
        plant(BROWN_MUSHROOM, "Brown Mushroom", "brown_mushroom")
        plant(RED_MUSHROOM, "Red Mushroom", "red_mushroom")
        reg(BlockDef(COBWEB, "Cobweb", t("cobweb"), render = RenderType.CROSS, opaque = false, solid = false,
            hardness = 2f, tool = ToolType.SWORD, category = N, movable = false))
        plant(TORCH, "Torch", "torch", Category.BUILDING, emissive = true)
        plant(FERN, "Fern", "fern")
        plant(BLUE_ORCHID, "Blue Orchid", "blue_orchid")
        column(NOTE_BLOCK, "Note Block", "note_block", "note_block", "note_block", 0.6f, A, Category.REDSTONE)
        column(JUKEBOX, "Jukebox", "jukebox_top", "jukebox_side", "jukebox_side", 1.0f, A)
        cube(SMOOTH_SANDSTONE, "Smooth Sandstone", "sandstone_top", 0.9f, P)

        slab(STONE_SLAB, "Smooth Stone Slab", "smooth_stone", "smooth_stone_side", P)
        slab(COBBLESTONE_SLAB, "Cobblestone Slab", "cobblestone", "cobblestone", P)
        slab(OAK_SLAB, "Oak Slab", "oak_planks", "oak_planks", A)
        slab(STONE_BRICK_SLAB, "Stone Brick Slab", "stone_bricks", "stone_bricks", P)
        slab(BRICK_SLAB, "Brick Slab", "bricks", "bricks", P)
        slab(SANDSTONE_SLAB, "Sandstone Slab", "sandstone_top", "sandstone_side", P)

        val R = Category.REDSTONE
        reg(BlockDef(REDSTONE_DUST, "Redstone Dust", t("redstone_dust"), render = RenderType.FLAT, opaque = false,
            solid = false, hardness = 0f, category = R, needsSupport = true, movable = false))
        t("redstone_dust_on")
        plant(REDSTONE_TORCH, "Redstone Torch", "redstone_torch", R, emissive = true)
        t("redstone_torch_off")
        plant(LEVER, "Lever", "lever", R, hardness = 0.2f)
        t("lever_on")
        reg(BlockDef(STONE_BUTTON, "Stone Button", t("stone"), render = RenderType.BOX, opaque = false, solid = false,
            blocksLight = false, hardness = 0.2f, category = R, needsSupport = true, movable = false,
            box = floatArrayOf(5 / 16f, 0f, 6 / 16f, 11 / 16f, 2 / 16f, 10 / 16f)))
        cube(REDSTONE_LAMP, "Redstone Lamp", "redstone_lamp", 0.4f, ToolType.NONE, R)
        reg(BlockDef(REDSTONE_LAMP_ON, "Redstone Lamp (lit)", t("redstone_lamp_on"), hardness = 0.4f, emissive = true,
            category = R, inInventory = false))
        reg(BlockDef(PISTON, "Piston", t("piston_side"), hardness = 0.6f, tool = P, category = R, facing = Facing.ALL,
            front = t("piston_front"), back = t("piston_back")))
        reg(BlockDef(STICKY_PISTON, "Sticky Piston", t("piston_side"), hardness = 0.6f, tool = P, category = R,
            facing = Facing.ALL, front = t("piston_sticky_front"), back = t("piston_back")))
        t("piston_inner")
        t("furnace_front_on")
        column(ANCIENT_DEBRIS, "Ancient Debris", "ancient_debris_top", "ancient_debris", "ancient_debris_top", 6f, P, N)
        reg(BlockDef(LAVA, "Lava", t("lava"), render = RenderType.LIQUID, opaque = false, solid = false,
            blocksLight = false, emissive = true, breakable = false, cullSelf = true, inInventory = false, movable = false))
        extraLight[LAVA] = 15
        for (i in 0..7) t("wheat_stage_$i")
        for (i in 0..3) { t("carrots_stage_$i"); t("potatoes_stage_$i") }
        reg(BlockDef(WHEAT_CROP, "Wheat Crops", t("wheat_stage_0"), render = RenderType.CROSS, opaque = false, solid = false,
            hardness = 0f, needsSupport = true, inInventory = false, movable = false, category = N))
        reg(BlockDef(CARROTS, "Carrots", t("carrots_stage_0"), render = RenderType.CROSS, opaque = false, solid = false,
            hardness = 0f, needsSupport = true, inInventory = false, movable = false, category = N))
        reg(BlockDef(POTATOES, "Potatoes", t("potatoes_stage_0"), render = RenderType.CROSS, opaque = false, solid = false,
            hardness = 0f, needsSupport = true, inInventory = false, movable = false, category = N))
        for ((i, w) in listOf("Oak", "Spruce", "Birch", "Jungle", "Acacia", "Dark Oak").withIndex()) {
            plant(SAPLING_FIRST + i, "$w Sapling", "sapling_${w.lowercase().replace(' ', '_')}")
        }

        fun shape(id: Int, name: String, top: String, side: String = top, bottom: String = top, hardness: Float, tool: ToolType,
                  cat: Category = Category.BUILDING, facing: Facing = Facing.HORIZONTAL, solid: Boolean = true,
                  blocksLight: Boolean = false, front: String = side) =
            reg(BlockDef(id, name, t(top), t(side), t(bottom), render = RenderType.SHAPE, opaque = false, solid = solid,
                blocksLight = blocksLight, hardness = hardness, tool = tool, category = cat, facing = facing, front = t(front),
                movable = false))
        shape(OAK_DOOR, "Oak Door", "oak_door_bottom", hardness = 0.8f, tool = A)
        t("oak_door_top")
        shape(IRON_DOOR, "Iron Door", "iron_door_bottom", hardness = 1.5f, tool = P, cat = Category.REDSTONE)
        t("iron_door_top")
        shape(OAK_TRAPDOOR, "Oak Trapdoor", "oak_trapdoor", hardness = 0.8f, tool = A)
        shape(OAK_STAIRS, "Oak Stairs", "oak_planks", hardness = 0.8f, tool = A, blocksLight = true)
        shape(COBBLESTONE_STAIRS, "Cobblestone Stairs", "cobblestone", hardness = 1f, tool = P, blocksLight = true)
        shape(STONE_BRICK_STAIRS, "Stone Brick Stairs", "stone_bricks", hardness = 1f, tool = P, blocksLight = true)
        shape(BRICK_STAIRS, "Brick Stairs", "bricks", hardness = 1f, tool = P, blocksLight = true)
        shape(SANDSTONE_STAIRS, "Sandstone Stairs", "sandstone_top", "sandstone_side", hardness = 0.8f, tool = P, blocksLight = true)
        shape(OAK_FENCE, "Oak Fence", "oak_planks", hardness = 0.8f, tool = A, facing = Facing.NONE)
        shape(OAK_FENCE_GATE, "Oak Fence Gate", "oak_planks", hardness = 0.8f, tool = A)
        shape(LADDER, "Ladder", "ladder", hardness = 0.3f, tool = A)
        shape(GLASS_PANE, "Glass Pane", "glass", hardness = 0.3f, tool = ToolType.NONE, facing = Facing.NONE)
        shape(IRON_BARS, "Iron Bars", "iron_bars", hardness = 1.5f, tool = P, facing = Facing.NONE)

        val R2 = Category.REDSTONE
        shape(REPEATER, "Redstone Repeater", "repeater", "smooth_stone_side", "smooth_stone", hardness = 0f, tool = ToolType.NONE, cat = R2)
        t("repeater_on")
        reg(BlockDef(OBSERVER, "Observer", t("observer_top"), t("observer_side"), t("observer_top"), hardness = 1f, tool = P,
            category = R2, facing = Facing.ALL, front = t("observer_front"), back = t("observer_back")))
        t("observer_back_on")
        shape(DAYLIGHT_SENSOR, "Daylight Sensor", "daylight_sensor", "daylight_sensor_side", "oak_planks", hardness = 0.3f,
            tool = A, cat = R2, facing = Facing.NONE)
        shape(PRESSURE_PLATE, "Stone Pressure Plate", "stone", hardness = 0.4f, tool = P, cat = R2, facing = Facing.NONE, solid = false)
        shape(HOPPER, "Hopper", "hopper_top", "hopper_side", "hopper_side", hardness = 1.5f, tool = P, cat = R2, facing = Facing.NONE)
        reg(BlockDef(RAIL, "Rail", t("rail"), render = RenderType.RAIL, opaque = false, solid = false, hardness = 0.4f,
            tool = P, category = R2, needsSupport = true, movable = false))
        t("rail_curve")
        reg(BlockDef(POWERED_RAIL, "Powered Rail", t("powered_rail"), render = RenderType.RAIL, opaque = false, solid = false,
            hardness = 0.4f, tool = P, category = R2, needsSupport = true, movable = false))
        t("powered_rail_on")
        reg(BlockDef(EMBER_PORTAL, "Ember Portal", t("ember_portal"), render = RenderType.PORTAL, opaque = false, solid = false,
            translucent = true, blocksLight = false, breakable = false, emissive = true, inInventory = false, movable = false))
        reg(BlockDef(SKY_PORTAL, "Sky Portal", t("sky_portal"), render = RenderType.PORTAL, opaque = false, solid = false,
            translucent = true, blocksLight = false, breakable = false, emissive = true, inInventory = false, movable = false))
        for ((i, w) in EXTRA_WOODS.withIndex()) {
            val nice = pretty(w)
            shape(WOOD_DOOR_FIRST + i, "$nice Door", "${w}_door_bottom", hardness = 0.8f, tool = A)
            t("${w}_door_top")
            shape(WOOD_TRAPDOOR_FIRST + i, "$nice Trapdoor", "${w}_trapdoor", hardness = 0.8f, tool = A)
            shape(WOOD_FENCE_FIRST + i, "$nice Fence", "${w}_planks", hardness = 0.8f, tool = A, facing = Facing.NONE)
            shape(WOOD_GATE_FIRST + i, "$nice Fence Gate", "${w}_planks", hardness = 0.8f, tool = A)
        }
        for ((i, w) in (listOf("oak") + EXTRA_WOODS).withIndex()) {
            val nice = pretty(w)
            reg(BlockDef(WOOD_BUTTON_FIRST + i, "$nice Button", t("${w}_planks"), render = RenderType.BOX, opaque = false, solid = false,
                blocksLight = false, hardness = 0.2f, tool = A, category = Category.REDSTONE, needsSupport = true, movable = false,
                box = floatArrayOf(5 / 16f, 0f, 6 / 16f, 11 / 16f, 2 / 16f, 10 / 16f)))
            shape(WOOD_PLATE_FIRST + i, "$nice Pressure Plate", "${w}_planks", hardness = 0.4f, tool = A, cat = Category.REDSTONE,
                facing = Facing.NONE, solid = false)
        }
        shape(GOLD_PLATE, "Light Weighted Pressure Plate", "gold_block", hardness = 0.4f, tool = P, cat = Category.REDSTONE,
            facing = Facing.NONE, solid = false)
        shape(IRON_PLATE, "Heavy Weighted Pressure Plate", "iron_block", hardness = 0.4f, tool = P, cat = Category.REDSTONE,
            facing = Facing.NONE, solid = false)
        shape(IRON_TRAPDOOR, "Iron Trapdoor", "iron_trapdoor", hardness = 1.5f, tool = P, cat = Category.REDSTONE)
        for ((i, c) in DYES.withIndex()) {
            shape(BED_FIRST + i, "${pretty(c)} Bed", "bed_foot_$c", "bed_side_$c", "oak_planks", hardness = 0.2f,
                tool = A, cat = Category.COLORED)
            t("bed_head_$c")
        }
        extraLight[EMBER_PORTAL] = 11
        extraLight[SKY_PORTAL] = 11
        reg(BlockDef(PISTON_HEAD, "Piston Head", t("piston_front"), t("oak_planks"), render = RenderType.PISTON_HEAD,
            opaque = false, blocksLight = false, hardness = 0.6f, tool = P, category = R, inInventory = false, movable = false))
    }

    private fun pretty(c: String) = c.split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }

    val all: List<BlockDef> = defs.mapIndexed { i, d -> d ?: error("missing block $i") }

    // Flat lookup tables for the hot paths (meshing, physics).
    @JvmField val opaque = BooleanArray(COUNT)
    @JvmField val solid = BooleanArray(COUNT)
    @JvmField val blocksLight = BooleanArray(COUNT)
    /** Collision height (slabs are half height). */
    @JvmField val height = FloatArray(COUNT)

    init {
        for (d in all) {
            opaque[d.id] = d.opaque
            solid[d.id] = d.solid
            blocksLight[d.id] = d.blocksLight
            height[d.id] = d.box?.get(4) ?: 1f
        }
    }

    operator fun get(id: Int): BlockDef = all[id]

    /** Block light emitted (0..15). */
    fun lightLevel(id: Int, meta: Int): Int = when (id) {
        GLOWSTONE, SEA_LANTERN, JACK_O_LANTERN, REDSTONE_LAMP_ON -> 15
        TORCH -> 14
        FURNACE -> if (meta and 8 != 0) 13 else 0
        CRYING_OBSIDIAN -> 10
        REDSTONE_TORCH -> if (meta == 0) 7 else 0
        MAGMA -> 3
        else -> extraLight[id]
    }


    fun isLiquid(id: Int) = id == WATER || id == LAVA
    fun isCrop(id: Int) = id == WHEAT_CROP || id == CARROTS || id == POTATOES
    fun isSapling(id: Int) = id in SAPLING_FIRST until SAPLING_FIRST + 6

    fun isEmissive(id: Int, meta: Int): Boolean {
        if (id == FURNACE) return meta and 8 != 0
        if (!all[id].emissive) return false
        return !(id == REDSTONE_TORCH && meta != 0)
    }

    /** Texture tile for a face (see ChunkMesher face order: +Y, -Y, +Z, -Z, +X, -X), honouring block state. */
    fun tile(id: Int, meta: Int, face: Int): Int {
        val d = all[id]
        when (id) {
            REDSTONE_DUST -> return if (meta > 0) Tiles.id("redstone_dust_on") else d.top
            REDSTONE_TORCH -> return if (meta != 0) Tiles.id("redstone_torch_off") else d.top
            LEVER -> return if (meta != 0) Tiles.id("lever_on") else d.top
            TNT -> if (meta == 2) return Tiles.id("tnt_flash")
            WHEAT_CROP -> return Tiles.id("wheat_stage_${meta.coerceIn(0, 7)}")
            CARROTS -> return Tiles.id("carrots_stage_${(meta.coerceIn(0, 7)) / 2}")
            POTATOES -> return Tiles.id("potatoes_stage_${(meta.coerceIn(0, 7)) / 2}")
            OAK_DOOR -> return Tiles.id(if (meta and Shapes.UPPER != 0) "oak_door_top" else "oak_door_bottom")
            IRON_DOOR -> return Tiles.id(if (meta and Shapes.UPPER != 0) "iron_door_top" else "iron_door_bottom")
            in BED_FIRST until BED_FIRST + 16 -> if (face == 0 && meta and Shapes.UPPER != 0) return Tiles.id("bed_head_${DYES[id - BED_FIRST]}")
            in WOOD_DOOR_FIRST until WOOD_DOOR_FIRST + 5 -> {
                val w = EXTRA_WOODS[id - WOOD_DOOR_FIRST]
                return Tiles.id(if (meta and Shapes.UPPER != 0) "${w}_door_top" else "${w}_door_bottom")
            }
            REPEATER -> if (face == 0) return Tiles.id(if (meta and Shapes.POWERED != 0) "repeater_on" else "repeater")
            OBSERVER -> if (meta and 8 != 0 && face == ((meta and 7) xor 1)) return Tiles.id("observer_back_on")
            POWERED_RAIL -> return Tiles.id(if (meta and 8 != 0) "powered_rail_on" else "powered_rail")
            RAIL -> return Tiles.id(if ((meta and 15) >= 6) "rail_curve" else "rail")
        }
        if (d.render == RenderType.SHAPE) return when (face) { 0 -> d.top; 1 -> d.bottom; else -> d.side }
        if (d.facing != Facing.NONE) {
            var f = meta and 7
            if (f > 5 || (d.facing == Facing.HORIZONTAL && f < 2)) f = 2
            if (face == f) {
                if (id == FURNACE && meta and 8 != 0) return Tiles.id("furnace_front_on")
                return if ((id == PISTON || id == STICKY_PISTON) && meta and 8 != 0) Tiles.id("piston_inner") else d.front
            }
            if (d.facing == Facing.ALL) return if (face == (f xor 1)) d.back else d.side
        }
        return when (face) { 0 -> d.top; 1 -> d.bottom; else -> d.side }
    }

    /** Blocks offered in the creative inventory for a tab. */
    fun inCategory(c: Category): List<Int> = all.filter { it.inInventory && it.category == c && it.id != BEDROCK }
        .map { it.id }.let { if (c == Category.BUILDING) it + BEDROCK else it }
}
