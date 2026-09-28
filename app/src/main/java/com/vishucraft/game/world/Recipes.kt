package com.vishucraft.game.world

import java.util.Random

/** One ingredient: any of [ids], [count] of them in total. */
class Ingredient(val ids: IntArray, val count: Int) {
    fun available(inv: Inventory) = ids.sumOf { inv.count(it) }
}

/**
 * A recipe. Shaped recipes have a [pattern] (rows of key characters, space = empty) that must be laid out in
 * the crafting grid; the others only need the right ingredients anywhere in the grid.
 */
class Recipe(
    val result: Int, val count: Int, val ingredients: List<Ingredient>, val needsTable: Boolean,
    val pattern: List<String>? = null, val key: Map<Char, IntArray> = emptyMap(),
) {
    fun canCraft(inv: Inventory) = ingredients.all { it.available(inv) >= it.count }

    /** Takes the ingredients (in the order the alternatives are listed). */
    fun consume(inv: Inventory): Boolean {
        if (!canCraft(inv)) return false
        for (ing in ingredients) {
            var left = ing.count
            for (id in ing.ids) {
                val n = minOf(left, inv.count(id))
                if (n > 0) { inv.remove(id, n); left -= n }
                if (left == 0) break
            }
        }
        return true
    }
}

/**
 * Crafting works like a recipe book: pick a recipe and, if you have the ingredients, it is made.
 * Small recipes work anywhere; the rest need a crafting table.
 */
object Recipes {
    val crafting: List<Recipe>
    /** Furnace: input -> output. */
    val smelting: Map<Int, Int>

    private fun i(name: String) = Items.find(name)

    init {
        val list = ArrayList<Recipe>()
        fun r(result: Int, count: Int, table: Boolean, vararg ing: Pair<Any, Int>) {
            list.add(Recipe(result, count, ing.map { (what, n) ->
                Ingredient(if (what is IntArray) what else intArrayOf(what as Int), n)
            }, table))
        }
        /** A shaped recipe; needs a crafting table when the pattern is wider or taller than 2. */
        fun sh(result: Int, count: Int, pattern: List<String>, vararg keys: Pair<Char, Any>) {
            val key = keys.associate { (c, what) -> c to (if (what is IntArray) what else intArrayOf(what as Int)) }
            val ing = key.map { (c, ids) -> Ingredient(ids, pattern.sumOf { row -> row.count { it == c } }) }
            val table = pattern.size > 2 || pattern.any { it.length > 2 }
            list.add(Recipe(result, count, ing, table, pattern, key))
        }
        val planks = intArrayOf(Blocks.PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.CHERRY_PLANKS, Blocks.MANGROVE_PLANKS, Blocks.BAMBOO_PLANKS)
        val cobble = intArrayOf(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE)
        val stick = i("Stick"); val coal = intArrayOf(i("Coal"), i("Charcoal"))
        val iron = i("Iron Ingot"); val gold = i("Gold Ingot"); val diamond = i("Diamond")

        // Wood
        for ((log, plank) in listOf(Blocks.LOG to Blocks.PLANKS, Blocks.SPRUCE_LOG to Blocks.SPRUCE_PLANKS,
            Blocks.BIRCH_LOG to Blocks.BIRCH_PLANKS, Blocks.JUNGLE_LOG to Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_LOG to Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_LOG to Blocks.DARK_OAK_PLANKS,
            Blocks.CHERRY_LOG to Blocks.CHERRY_PLANKS, Blocks.MANGROVE_LOG to Blocks.MANGROVE_PLANKS)) r(plank, 4, false, log to 1)
        sh(stick, 4, listOf("M", "M"), 'M' to planks)
        sh(Blocks.CRAFTING_TABLE, 1, listOf("MM", "MM"), 'M' to planks)
        r(Blocks.TORCH, 4, false, coal to 1, stick to 1)
        sh(Blocks.CHEST, 1, listOf("MMM", "M M", "MMM"), 'M' to planks)
        sh(Blocks.FURNACE, 1, listOf("MMM", "M M", "MMM"), 'M' to cobble)
        sh(Blocks.OAK_SLAB, 6, listOf("MMM"), 'M' to planks)
        r(Blocks.BOOKSHELF, 1, true, planks to 6, i("Book") to 3)
        r(i("Paper"), 3, true, Blocks.SUGAR_CANE to 3)
        r(i("Book"), 1, false, i("Paper") to 3, i("Leather") to 1)

        // Tools
        val mats = listOf(
            "Wooden" to planks, "Stone" to cobble, "Iron" to intArrayOf(iron), "Golden" to intArrayOf(gold),
            "Diamond" to intArrayOf(diamond),
        )
        for ((name, m) in mats) {
            sh(i("$name Sword"), 1, listOf("M", "M", "S"), 'M' to m, 'S' to stick)
            sh(i("$name Pickaxe"), 1, listOf("MMM", " S ", " S "), 'M' to m, 'S' to stick)
            sh(i("$name Axe"), 1, listOf("MM", "MS", " S"), 'M' to m, 'S' to stick)
            sh(i("$name Shovel"), 1, listOf("M", "S", "S"), 'M' to m, 'S' to stick)
            sh(i("$name Hoe"), 1, listOf("MM", " S", " S"), 'M' to m, 'S' to stick)
        }
        val netherite = i("Netherite Ingot")
        for (kind in listOf("Sword", "Pickaxe", "Axe", "Shovel", "Hoe", "Helmet", "Chestplate", "Leggings", "Boots")) {
            r(i("Netherite $kind"), 1, true, i("Diamond $kind") to 1, netherite to 1)
        }
        r(netherite, 1, true, i("Netherite Scrap") to 4, gold to 4)

        // Armor
        for ((name, m) in listOf("Leather" to i("Leather"), "Golden" to gold, "Iron" to iron, "Diamond" to diamond)) {
            sh(i("$name Helmet"), 1, listOf("MMM", "M M"), 'M' to m)
            sh(i("$name Chestplate"), 1, listOf("M M", "MMM", "MMM"), 'M' to m)
            sh(i("$name Leggings"), 1, listOf("MMM", "M M", "M M"), 'M' to m)
            sh(i("$name Boots"), 1, listOf("M M", "M M"), 'M' to m)
        }

        // Storage blocks and back
        for ((block, item) in listOf(Blocks.IRON_BLOCK to iron, Blocks.GOLD_BLOCK to gold, Blocks.DIAMOND_BLOCK to diamond,
            Blocks.EMERALD_BLOCK to i("Emerald"), Blocks.LAPIS_BLOCK to i("Lapis Lazuli"), Blocks.COAL_BLOCK to i("Coal"),
            Blocks.COPPER_BLOCK to i("Copper Ingot"), Blocks.NETHERITE_BLOCK to netherite)) {
            r(block, 1, true, item to 9)
            r(item, 9, false, block to 1)
        }
        r(Blocks.REDSTONE_BLOCK, 1, true, Blocks.REDSTONE_DUST to 9)
        r(Blocks.REDSTONE_DUST, 9, false, Blocks.REDSTONE_BLOCK to 1)
        r(Blocks.GLOWSTONE, 1, false, i("Glowstone Dust") to 4)
        r(Blocks.HAY_BALE, 1, true, i("Wheat") to 9)
        r(i("Wheat"), 9, false, Blocks.HAY_BALE to 1)

        // Building blocks
        r(Blocks.STONE_BRICKS, 4, false, Blocks.STONE to 4)
        r(Blocks.BRICKS, 1, false, i("Brick") to 4)
        r(Blocks.CLAY, 1, false, i("Clay Ball") to 4)
        r(Blocks.SANDSTONE, 1, false, Blocks.SAND to 4)
        r(Blocks.RED_SANDSTONE, 1, false, Blocks.RED_SAND to 4)
        r(Blocks.POLISHED_GRANITE, 4, false, Blocks.GRANITE to 4)
        r(Blocks.POLISHED_DIORITE, 4, false, Blocks.DIORITE to 4)
        r(Blocks.POLISHED_ANDESITE, 4, false, Blocks.ANDESITE to 4)
        r(Blocks.MOSSY_COBBLESTONE, 1, false, Blocks.COBBLESTONE to 1, Blocks.TALL_GRASS to 1)
        r(Blocks.WOOL_WHITE, 1, false, i("String") to 4)
        sh(Blocks.STONE_SLAB, 6, listOf("MMM"), 'M' to Blocks.SMOOTH_STONE)
        sh(Blocks.COBBLESTONE_SLAB, 6, listOf("MMM"), 'M' to Blocks.COBBLESTONE)
        sh(Blocks.STONE_BRICK_SLAB, 6, listOf("MMM"), 'M' to Blocks.STONE_BRICKS)
        sh(Blocks.BRICK_SLAB, 6, listOf("MMM"), 'M' to Blocks.BRICKS)
        sh(Blocks.SANDSTONE_SLAB, 6, listOf("MMM"), 'M' to Blocks.SANDSTONE)
        r(Blocks.JACK_O_LANTERN, 1, false, Blocks.PUMPKIN to 1, Blocks.TORCH to 1)
        r(Blocks.MELON, 1, true, i("Melon Slice") to 9)

        // Food
        r(i("Bread"), 1, true, i("Wheat") to 3)
        r(i("Golden Apple"), 1, true, i("Apple") to 1, gold to 8)
        r(i("Bone Meal"), 3, false, i("Bone") to 1)

        // Redstone and gadgets
        r(Blocks.REDSTONE_TORCH, 1, false, Blocks.REDSTONE_DUST to 1, stick to 1)
        r(Blocks.LEVER, 1, false, cobble to 1, stick to 1)
        r(Blocks.STONE_BUTTON, 1, false, Blocks.STONE to 1)
        r(Blocks.REDSTONE_LAMP, 1, true, Blocks.REDSTONE_DUST to 4, Blocks.GLOWSTONE to 1)
        r(Blocks.PISTON, 1, true, planks to 3, cobble to 4, iron to 1, Blocks.REDSTONE_DUST to 1)
        r(Blocks.STICKY_PISTON, 1, false, Blocks.PISTON to 1, i("Slimeball") to 1)
        r(Blocks.TNT, 1, true, i("Gunpowder") to 5, Blocks.SAND to 4)
        r(Blocks.NOTE_BLOCK, 1, true, planks to 8, Blocks.REDSTONE_DUST to 1)
        r(Blocks.JUKEBOX, 1, true, planks to 8, diamond to 1)
        r(i("Flint and Steel"), 1, false, iron to 1, i("Flint") to 1)
        sh(i("Bucket"), 1, listOf("M M", " M "), 'M' to iron)
        sh(i("Minecart"), 1, listOf("M M", "MMM"), 'M' to iron)
        sh(Blocks.RAIL, 16, listOf("M M", "MSM", "M M"), 'M' to iron, 'S' to stick)
        sh(Blocks.POWERED_RAIL, 6, listOf("M M", "MSM", "MRM"), 'M' to gold, 'S' to stick, 'R' to Blocks.REDSTONE_DUST)
        r(Blocks.REPEATER, 1, true, Blocks.REDSTONE_TORCH to 2, Blocks.REDSTONE_DUST to 1, Blocks.STONE to 3)
        r(Blocks.OBSERVER, 1, true, cobble to 6, Blocks.REDSTONE_DUST to 2, i("Iron Ingot") to 1)
        r(Blocks.DAYLIGHT_SENSOR, 1, true, Blocks.GLASS to 3, planks to 3, Blocks.REDSTONE_DUST to 1)
        r(Blocks.PRESSURE_PLATE, 1, false, Blocks.STONE to 2)
        sh(Blocks.HOPPER, 1, listOf("M M", "MCM", " M "), 'M' to iron, 'C' to Blocks.CHEST)
        sh(Blocks.IRON_DOOR, 3, listOf("MM", "MM", "MM"), 'M' to iron)
        sh(Blocks.IRON_TRAPDOOR, 1, listOf("MM", "MM"), 'M' to iron)
        sh(Blocks.OAK_STAIRS, 4, listOf("M  ", "MM ", "MMM"), 'M' to planks)
        sh(Blocks.COBBLESTONE_STAIRS, 4, listOf("M  ", "MM ", "MMM"), 'M' to Blocks.COBBLESTONE)
        sh(Blocks.STONE_BRICK_STAIRS, 4, listOf("M  ", "MM ", "MMM"), 'M' to Blocks.STONE_BRICKS)
        sh(Blocks.BRICK_STAIRS, 4, listOf("M  ", "MM ", "MMM"), 'M' to Blocks.BRICKS)
        sh(Blocks.SANDSTONE_STAIRS, 4, listOf("M  ", "MM ", "MMM"), 'M' to Blocks.SANDSTONE)
        val allPlanks = listOf(Blocks.PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS)
        for ((k, pl) in allPlanks.withIndex()) {
            val door = if (k == 0) Blocks.OAK_DOOR else Blocks.WOOD_DOOR_FIRST + k - 1
            val trap = if (k == 0) Blocks.OAK_TRAPDOOR else Blocks.WOOD_TRAPDOOR_FIRST + k - 1
            val fence = if (k == 0) Blocks.OAK_FENCE else Blocks.WOOD_FENCE_FIRST + k - 1
            val gate = if (k == 0) Blocks.OAK_FENCE_GATE else Blocks.WOOD_GATE_FIRST + k - 1
            sh(door, 3, listOf("MM", "MM", "MM"), 'M' to pl)
            sh(trap, 2, listOf("MMM", "MMM"), 'M' to pl)
            sh(fence, 3, listOf("MSM", "MSM"), 'M' to pl, 'S' to stick)
            sh(gate, 1, listOf("SMS", "SMS"), 'M' to pl, 'S' to stick)
        }
        // Beds: three wool of one colour over three planks (any wood).
        val woolIds = mapOf(
            "white" to Blocks.WOOL_WHITE, "orange" to Blocks.WOOL_ORANGE, "magenta" to Blocks.WOOL_MAGENTA,
            "light_blue" to Blocks.WOOL_LIGHT_BLUE, "yellow" to Blocks.WOOL_YELLOW, "lime" to Blocks.WOOL_LIME,
            "pink" to Blocks.WOOL_PINK, "gray" to Blocks.WOOL_GRAY, "light_gray" to Blocks.WOOL_LIGHT_GRAY,
            "cyan" to Blocks.WOOL_CYAN, "purple" to Blocks.WOOL_PURPLE, "blue" to Blocks.WOOL_BLUE,
            "brown" to Blocks.WOOL_BROWN, "green" to Blocks.WOOL_GREEN, "red" to Blocks.WOOL_RED, "black" to Blocks.WOOL_BLACK,
        )
        for ((k, c) in Blocks.DYES.withIndex()) sh(Blocks.BED_FIRST + k, 1, listOf("WWW", "PPP"), 'W' to woolIds.getValue(c), 'P' to planks)
        sh(Blocks.LADDER, 3, listOf("S S", "SSS", "S S"), 'S' to stick)
        sh(Blocks.GLASS_PANE, 16, listOf("MMM", "MMM"), 'M' to Blocks.GLASS)
        sh(Blocks.IRON_BARS, 16, listOf("MMM", "MMM"), 'M' to iron)
        sh(i("Bow"), 1, listOf(" SW", "S W", " SW"), 'S' to stick, 'W' to i("String"))
        r(i("Arrow"), 4, true, i("Flint") to 1, stick to 1, i("Feather") to 1)

        // Buttons and pressure plates for every wood, plus the weighted plates.
        for ((k, pl) in allPlanks.withIndex()) {
            r(Blocks.WOOD_BUTTON_FIRST + k, 1, false, pl to 1)
            sh(Blocks.WOOD_PLATE_FIRST + k, 1, listOf("MM"), 'M' to pl)
        }
        sh(Blocks.GOLD_PLATE, 1, listOf("MM"), 'M' to gold)
        sh(Blocks.IRON_PLATE, 1, listOf("MM"), 'M' to iron)

        // ---- Item pack 2
        r(i("Iron Nugget"), 9, false, iron to 1)
        r(iron, 1, true, i("Iron Nugget") to 9)
        r(i("Gold Nugget"), 9, false, gold to 1)
        r(gold, 1, true, i("Gold Nugget") to 9)
        r(i("Blaze Powder"), 2, false, i("Blaze Rod") to 1)
        r(i("Eye of Ender"), 1, false, i("Ender Pearl") to 1, i("Blaze Powder") to 1)
        r(i("Fermented Spider Eye"), 1, false, i("Spider Eye") to 1, Blocks.BROWN_MUSHROOM to 1, i("Sugar") to 1)
        r(i("Magma Cream"), 1, false, i("Slimeball") to 1, i("Blaze Powder") to 1)
        r(i("Sugar"), 1, false, Blocks.SUGAR_CANE to 1)
        sh(i("Bowl"), 4, listOf("M M", " M "), 'M' to planks)
        sh(i("Glass Bottle"), 3, listOf("M M", " M "), 'M' to Blocks.GLASS)
        r(i("Mushroom Stew"), 1, false, Blocks.BROWN_MUSHROOM to 1, Blocks.RED_MUSHROOM to 1, i("Bowl") to 1)
        r(i("Beetroot Soup"), 1, true, i("Beetroot") to 6, i("Bowl") to 1)
        r(i("Rabbit Stew"), 1, true, i("Cooked Rabbit") to 1, i("Carrot") to 1, i("Baked Potato") to 1, Blocks.BROWN_MUSHROOM to 1, i("Bowl") to 1)
        sh(i("Cookie"), 8, listOf("WCW"), 'W' to i("Wheat"), 'C' to i("Cocoa Beans"))
        r(i("Pumpkin Pie"), 1, false, Blocks.PUMPKIN to 1, i("Sugar") to 1, i("Egg") to 1)
        sh(i("Golden Carrot"), 1, listOf("NNN", "NCN", "NNN"), 'N' to i("Gold Nugget"), 'C' to i("Carrot"))
        sh(i("Shears"), 1, listOf(" M", "M "), 'M' to iron)
        sh(i("Fishing Rod"), 1, listOf("  S", " SW", "S W"), 'S' to stick, 'W' to i("String"))
        sh(i("Shield"), 1, listOf("PIP", "PPP", " P "), 'P' to planks, 'I' to iron)
        sh(i("Crossbow"), 1, listOf("SIS", "W W", " S "), 'S' to stick, 'I' to iron, 'W' to i("String"))
        sh(i("Compass"), 1, listOf(" I ", "IRI", " I "), 'I' to iron, 'R' to Blocks.REDSTONE_DUST)
        sh(i("Clock"), 1, listOf(" G ", "GRG", " G "), 'G' to gold, 'R' to Blocks.REDSTONE_DUST)
        sh(i("Spyglass"), 1, listOf("A", "C", "C"), 'A' to i("Amethyst Shard"), 'C' to i("Copper Ingot"))
        sh(i("Empty Map"), 1, listOf("PPP", "PCP", "PPP"), 'P' to i("Paper"), 'C' to i("Compass"))
        r(i("Book and Quill"), 1, false, i("Book") to 1, i("Ink Sac") to 1, i("Feather") to 1)
        sh(i("Lead"), 2, listOf("SS ", "SB ", "  S"), 'S' to i("String"), 'B' to i("Slimeball"))
        r(i("Firework Rocket"), 3, false, i("Paper") to 1, i("Gunpowder") to 1)
        r(i("Fire Charge"), 3, false, i("Blaze Powder") to 1, coal to 1, i("Gunpowder") to 1)
        sh(i("Turtle Shell"), 1, listOf("SSS", "S S"), 'S' to i("Turtle Scute"))
        sh(i("Mace"), 1, listOf("B", "S"), 'B' to Blocks.IRON_BLOCK, 'S' to i("Blaze Rod"))
        // Potions: a glass bottle and one ingredient, mixed at a crafting table.
        val potionIngredient = mapOf(
            "healing" to i("Golden Carrot"), "regeneration" to i("Ghast Tear"), "swiftness" to i("Sugar"),
            "leaping" to i("Rabbit's Foot"), "fire_resistance" to i("Magma Cream"), "strength" to i("Blaze Powder"),
            "slow_falling" to i("Phantom Membrane"),
        )
        for ((name, key) in Items.POTIONS) r(i("Potion of $name"), 1, true, i("Glass Bottle") to 1, potionIngredient.getValue(key) to 1)
        // Dyes from flowers and materials, mixed dyes, and dyeing white wool.
        fun dye(c: String) = i("${c.split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }} Dye")
        r(dye("red"), 1, false, Blocks.FLOWER_RED to 1)
        r(dye("yellow"), 1, false, Blocks.FLOWER_YELLOW to 1)
        r(dye("light_blue"), 1, false, Blocks.BLUE_ORCHID to 1)
        r(dye("blue"), 1, false, i("Lapis Lazuli") to 1)
        r(dye("white"), 1, false, i("Bone Meal") to 1)
        r(dye("black"), 1, false, i("Ink Sac") to 1)
        r(dye("brown"), 1, false, i("Cocoa Beans") to 1)
        r(dye("orange"), 2, false, dye("red") to 1, dye("yellow") to 1)
        r(dye("pink"), 2, false, dye("red") to 1, dye("white") to 1)
        r(dye("lime"), 2, false, dye("green") to 1, dye("white") to 1)
        r(dye("gray"), 2, false, dye("black") to 1, dye("white") to 1)
        r(dye("light_gray"), 2, false, dye("gray") to 1, dye("white") to 1)
        r(dye("cyan"), 2, false, dye("blue") to 1, dye("green") to 1)
        r(dye("purple"), 2, false, dye("blue") to 1, dye("red") to 1)
        r(dye("magenta"), 2, false, dye("purple") to 1, dye("pink") to 1)
        for (c in Blocks.DYES) if (c != "white") r(woolIds.getValue(c), 1, false, Blocks.WOOL_WHITE to 1, dye(c) to 1)

        // ---- Block pack 2
        val logs = intArrayOf(Blocks.LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG)
        for ((k, c) in Blocks.DYES.withIndex()) {
            sh(Blocks.CARPET_FIRST + k, 3, listOf("WW"), 'W' to woolIds.getValue(c))
            sh(Blocks.BANNER_FIRST + k, 1, listOf("WWW", "WWW", " S "), 'W' to woolIds.getValue(c), 'S' to stick)
        }
        sh(Blocks.LANTERN, 1, listOf("NNN", "NTN", "NNN"), 'N' to i("Iron Nugget"), 'T' to Blocks.TORCH)
        sh(Blocks.CAMPFIRE, 1, listOf(" S ", "SCS", "LLL"), 'S' to stick, 'C' to coal, 'L' to logs)
        sh(Blocks.BARREL, 1, listOf("PSP", "P P", "PSP"), 'P' to planks, 'S' to Blocks.OAK_SLAB)
        sh(Blocks.ENDER_CHEST, 1, listOf("OOO", "OEO", "OOO"), 'O' to Blocks.OBSIDIAN, 'E' to i("Eye of Ender"))
        sh(Blocks.ANVIL, 1, listOf("BBB", " I ", "III"), 'B' to Blocks.IRON_BLOCK, 'I' to iron)
        sh(Blocks.COMPOSTER, 1, listOf("S S", "S S", "SSS"), 'S' to Blocks.OAK_SLAB)
        sh(Blocks.BELL, 1, listOf("SSS", "GGG", "G G"), 'S' to stick, 'G' to gold)
        sh(Blocks.SCAFFOLDING, 6, listOf("SWS", "S S", "S S"), 'S' to stick, 'W' to i("String"))
        sh(Blocks.BREWING_STAND, 1, listOf(" B ", "CCC"), 'B' to i("Blaze Rod"), 'C' to cobble)
        sh(Blocks.CAKE, 1, listOf("MMM", "SES", "WWW"), 'M' to i("Milk Bucket"), 'S' to i("Sugar"), 'E' to i("Egg"), 'W' to i("Wheat"))
        sh(Blocks.SIGN, 3, listOf("PPP", "PPP", " S "), 'P' to planks, 'S' to stick)
        sh(Blocks.PAINTING, 1, listOf("SSS", "SWS", "SSS"), 'S' to stick, 'W' to woolIds.values.toIntArray())
        sh(Blocks.ITEM_FRAME, 1, listOf("SSS", "SLS", "SSS"), 'S' to stick, 'L' to i("Leather"))
        sh(i("Oak Boat"), 1, listOf("P P", "PPP"), 'P' to planks)

        // ---- Pack 3: nature and work blocks
        val bamboo = Blocks.BAMBOO
        sh(Blocks.BAMBOO_PLANKS, 2, listOf("BB", "BB"), 'B' to bamboo)
        sh(stick, 1, listOf("B", "B"), 'B' to bamboo)
        sh(Blocks.SCAFFOLDING, 6, listOf("BSB", "B B", "B B"), 'B' to bamboo, 'S' to i("String"))
        sh(i("Bamboo Raft"), 1, listOf("P P", "PPP"), 'P' to Blocks.BAMBOO_PLANKS)
        sh(Blocks.CUT_COPPER, 4, listOf("CC", "CC"), 'C' to Blocks.COPPER_BLOCK)
        sh(Blocks.POLISHED_DEEPSLATE, 4, listOf("CC", "CC"), 'C' to Blocks.COBBLED_DEEPSLATE)
        sh(Blocks.DEEPSLATE_BRICKS, 4, listOf("CC", "CC"), 'C' to Blocks.POLISHED_DEEPSLATE)
        sh(Blocks.DEEPSLATE_TILES, 4, listOf("CC", "CC"), 'C' to Blocks.DEEPSLATE_BRICKS)
        sh(Blocks.AMETHYST_BLOCK, 1, listOf("AA", "AA"), 'A' to i("Amethyst Shard"))
        sh(Blocks.MUD_BRICKS, 4, listOf("MM", "MM"), 'M' to Blocks.MUD)
        r(Blocks.MUD, 1, false, Blocks.DIRT to 1, i("Water Bucket") to 1)
        sh(Blocks.HONEYCOMB_BLOCK, 1, listOf("HH", "HH"), 'H' to i("Honeycomb"))
        sh(Blocks.HONEY_BLOCK, 1, listOf("HH", "HH"), 'H' to i("Honey Bottle"))
        sh(Blocks.SLIME_BLOCK, 1, listOf("SSS", "SSS", "SSS"), 'S' to i("Slimeball"))
        r(i("Slimeball"), 9, false, Blocks.SLIME_BLOCK to 1)
        sh(Blocks.BEE_NEST, 1, listOf("PPP", "HHH", "PPP"), 'P' to planks, 'H' to i("Honeycomb"))
        sh(Blocks.MOSS_BLOCK, 1, listOf("LL", "LL"), 'L' to intArrayOf(Blocks.LEAVES, Blocks.JUNGLE_LEAVES))
        val allLogs = intArrayOf(Blocks.LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG,
            Blocks.DARK_OAK_LOG, Blocks.CHERRY_LOG, Blocks.MANGROVE_LOG)
        sh(Blocks.SMOKER, 1, listOf(" L ", "LFL", " L "), 'L' to allLogs, 'F' to Blocks.FURNACE)
        sh(Blocks.BLAST_FURNACE, 1, listOf("III", "IFI", "SSS"), 'I' to iron, 'F' to Blocks.FURNACE, 'S' to Blocks.SMOOTH_STONE)
        sh(Blocks.STONECUTTER, 1, listOf(" I ", "SSS"), 'I' to iron, 'S' to Blocks.STONE)
        sh(Blocks.GRINDSTONE, 1, listOf("SLS", "P P"), 'S' to stick, 'L' to Blocks.STONE_SLAB, 'P' to planks)
        sh(Blocks.SMITHING_TABLE, 1, listOf("II", "PP", "PP"), 'I' to iron, 'P' to planks)
        sh(Blocks.LOOM, 1, listOf("SS", "PP"), 'S' to i("String"), 'P' to planks)
        sh(Blocks.CARTOGRAPHY_TABLE, 1, listOf("AA", "PP", "PP"), 'A' to i("Paper"), 'P' to planks)
        sh(Blocks.FLETCHING_TABLE, 1, listOf("FF", "PP", "PP"), 'F' to i("Flint"), 'P' to planks)
        sh(Blocks.LECTERN, 1, listOf("SSS", " B ", " S "), 'S' to Blocks.OAK_SLAB, 'B' to Blocks.BOOKSHELF)
        sh(Blocks.DISPENSER, 1, listOf("CCC", "CBC", "CRC"), 'C' to cobble, 'B' to i("Bow"), 'R' to Blocks.REDSTONE_DUST)
        sh(Blocks.DROPPER, 1, listOf("CCC", "C C", "CRC"), 'C' to cobble, 'R' to Blocks.REDSTONE_DUST)
        sh(Blocks.TARGET, 1, listOf(" R ", "RHR", " R "), 'R' to Blocks.REDSTONE_DUST, 'H' to Blocks.HAY_BALE)
        sh(Blocks.SNOW_LAYER, 6, listOf("SSS"), 'S' to Blocks.SNOW)
        fun dyeOf(c: String) = i("${c.split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }} Dye")
        for ((k, dye) in listOf(0 to "pink", 1 to "blue", 2 to "white", 3 to "light_gray", 4 to "light_gray", 5 to "magenta", 6 to "red")) {
            r(dyeOf(dye), 1, false, Blocks.FLOWER_FIRST + k to 1)
        }
        r(i("Mushroom Stew"), 1, false, Blocks.RED_MUSHROOM to 1, Blocks.BROWN_MUSHROOM to 1, i("Bowl") to 1)

        // ---- Kitchen and home
        val smooth = Blocks.SMOOTH_STONE; val glass = Blocks.GLASS; val red = Blocks.REDSTONE_DUST
        val wools = (0 until Blocks.COUNT).filter { !Items.isItem(it) && Blocks[it].name.endsWith(" Wool") }.toIntArray()
        val nugget = i("Iron Nugget")
        sh(Blocks.GAS_STOVE, 1, listOf("I I", "SFS", "SSS"), 'I' to iron, 'F' to i("Flint and Steel"), 'S' to smooth)
        sh(Blocks.PRESSURE_COOKER, 1, listOf(" N ", "I I", "III"), 'N' to nugget, 'I' to iron)
        sh(Blocks.TAWA, 1, listOf("III", " S "), 'I' to iron, 'S' to stick)
        sh(Blocks.FRYING_PAN, 1, listOf("I I", " IS"), 'I' to iron, 'S' to stick)
        sh(Blocks.KETTLE, 1, listOf("NI ", "I I", "III"), 'N' to nugget, 'I' to iron)
        sh(Blocks.MICROWAVE, 1, listOf("III", "GRI", "III"), 'I' to iron, 'G' to glass, 'R' to red)
        sh(Blocks.OVEN, 1, listOf("III", "GFI", "III"), 'I' to iron, 'G' to glass, 'F' to Blocks.FURNACE)
        sh(Blocks.TOASTER, 1, listOf("I I", "IRI"), 'I' to iron, 'R' to red)
        sh(Blocks.MIXER, 1, listOf(" G ", "IRI"), 'I' to iron, 'G' to glass, 'R' to red)
        sh(Blocks.FRIDGE, 1, listOf("III", "ICI", "IRI"), 'I' to iron, 'C' to Blocks.CHEST, 'R' to red)
        sh(Blocks.KITCHEN_SINK, 1, listOf("IBI", "SSS"), 'I' to iron, 'B' to i("Bucket"), 'S' to smooth)
        sh(Blocks.KITCHEN_COUNTER, 1, listOf("SSS", "PCP"), 'S' to smooth, 'P' to planks, 'C' to Blocks.CHEST)
        sh(Blocks.DINING_TABLE, 1, listOf("PPP", "S S", "S S"), 'P' to planks, 'S' to stick)
        sh(Blocks.CHAIR, 2, listOf("S  ", "PPP", "S S"), 'P' to planks, 'S' to stick)
        sh(Blocks.SOFA, 1, listOf("W  ", "WWW", "P P"), 'W' to wools, 'P' to planks)
        sh(Blocks.TV, 1, listOf("GGG", "GRG", " I "), 'G' to glass, 'R' to red, 'I' to iron)
        sh(Blocks.CEILING_FAN, 1, listOf(" I ", "IRI", " I "), 'I' to iron, 'R' to red)
        sh(Blocks.TABLE_LAMP, 1, listOf(" W ", " T ", " I "), 'W' to wools, 'T' to Blocks.TORCH, 'I' to iron)
        sh(Blocks.WASHING_MACHINE, 1, listOf("III", "IGI", "IRI"), 'I' to iron, 'G' to glass, 'R' to red)
        sh(Blocks.AIR_CONDITIONER, 1, listOf("III", "RPI"), 'I' to iron, 'R' to red, 'P' to Blocks.PACKED_ICE)
        r(i("Raw Pizza"), 1, false, i("Dough") to 1, i("Tomato") to 1, i("Cheese") to 1)
        r(i("Cheese"), 4, false, i("Milk Bucket") to 1)
        r(i("Dal Chawal"), 1, false, i("Steamed Rice") to 1, i("Dal") to 1)
        r(i("Indian Thali"), 1, true, i("Roti") to 2, i("Dal") to 1, i("Steamed Rice") to 1, i("Bowl") to 1)
        r(i("Sandwich"), 1, false, i("Bread") to 1, i("Cheese") to 1, i("Tomato") to 1)
        // ---- Railway
        r(Blocks.STATION_PLATFORM, 4, false, smooth to 2, i("Yellow Dye") to 1)
        r(Blocks.RAILWAY_BALLAST, 4, false, Blocks.GRAVEL to 2, stick to 1)
        sh(i("Train Engine"), 1, listOf("  I", "IFI", "IMI"), 'I' to iron, 'F' to Blocks.FURNACE, 'M' to i("Minecart"))
        sh(i("Metro Train"), 1, listOf("GGG", "IMI", "IRI"), 'G' to glass, 'I' to iron, 'M' to i("Minecart"), 'R' to red)
        sh(i("Train Coach"), 1, listOf("GGG", "IMI"), 'G' to glass, 'I' to iron, 'M' to i("Minecart"))
        crafting = list

        smelting = mapOf(
            Blocks.COBBLESTONE to Blocks.STONE,
            Blocks.STONE to Blocks.SMOOTH_STONE,
            Blocks.COBBLED_DEEPSLATE to Blocks.DEEPSLATE,
            Blocks.SAND to Blocks.GLASS,
            Blocks.RED_SAND to Blocks.GLASS,
            Blocks.CLAY to Blocks.TERRACOTTA,
            Blocks.WET_SPONGE to Blocks.SPONGE,
            Blocks.IRON_ORE to iron,
            Blocks.GOLD_ORE to gold,
            Blocks.COPPER_ORE to i("Copper Ingot"),
            Blocks.ANCIENT_DEBRIS to i("Netherite Scrap"),
            i("Clay Ball") to i("Brick"),
            i("Raw Beef") to i("Steak"),
            i("Raw Porkchop") to i("Cooked Porkchop"),
            i("Raw Mutton") to i("Cooked Mutton"),
            Blocks.LOG to i("Charcoal"), Blocks.SPRUCE_LOG to i("Charcoal"), Blocks.BIRCH_LOG to i("Charcoal"),
            Blocks.JUNGLE_LOG to i("Charcoal"), Blocks.ACACIA_LOG to i("Charcoal"), Blocks.DARK_OAK_LOG to i("Charcoal"),
            Blocks.NETHERRACK to Blocks.NETHER_BRICKS,
            Blocks.CACTUS to i("Green Dye"),
            i("Raw Iron") to iron, i("Raw Gold") to gold, i("Raw Copper") to i("Copper Ingot"),
            i("Raw Chicken") to i("Cooked Chicken"), i("Raw Cod") to i("Cooked Cod"), i("Raw Salmon") to i("Cooked Salmon"),
            i("Raw Rabbit") to i("Cooked Rabbit"),
            i("Potato") to i("Baked Potato"),
            Blocks.KELP to i("Dried Kelp"),
            Blocks.CHERRY_LOG to i("Charcoal"), Blocks.MANGROVE_LOG to i("Charcoal"),
            Blocks.DEEPSLATE_ORE_FIRST to i("Coal"), Blocks.DEEPSLATE_ORE_FIRST + 1 to iron, Blocks.DEEPSLATE_ORE_FIRST + 2 to gold,
            Blocks.DEEPSLATE_ORE_FIRST + 3 to diamond, Blocks.DEEPSLATE_ORE_FIRST + 5 to i("Lapis Lazuli"),
            Blocks.DEEPSLATE_BRICKS to Blocks.DEEPSLATE_TILES,
            Blocks.MUD to Blocks.MUD_BRICKS,
        )
    }

    fun available(inv: Inventory, table: Boolean): List<Recipe> = crafting.filter { table || !it.needsTable }

    // ---------------------------------------------------------------- crafting grid

    /** The recipe matching what is laid out in a [size] x [size] grid, or null. */
    fun match(grid: Array<ItemStack?>, size: Int): Recipe? {
        var minR = size; var maxR = -1; var minC = size; var maxC = -1
        for (r in 0 until size) for (c in 0 until size) if (grid[r * size + c] != null) {
            minR = minOf(minR, r); maxR = maxOf(maxR, r); minC = minOf(minC, c); maxC = maxOf(maxC, c)
        }
        if (maxR < 0) return null
        val h = maxR - minR + 1; val w = maxC - minC + 1
        fun at(r: Int, c: Int) = grid[(minR + r) * size + minC + c]?.id
        for (rec in crafting) {
            val pat = rec.pattern
            if (pat != null) {
                val pw = pat.maxOf { it.length }
                if (pat.size != h || pw != w) continue
                for (mirror in listOf(false, true)) {
                    var ok = true
                    loop@ for (r in 0 until h) for (c in 0 until w) {
                        val ch = pat[r].getOrElse(if (mirror) w - 1 - c else c) { ' ' }
                        val id = at(r, c)
                        if (ch == ' ') { if (id != null) { ok = false; break@loop } }
                        else if (id == null || id !in rec.key.getValue(ch)) { ok = false; break@loop }
                    }
                    if (ok) return rec
                }
            } else {
                // Shapeless: every item must belong to an ingredient and the counts must match exactly.
                val counts = IntArray(rec.ingredients.size)
                var ok = true
                for (s in grid) {
                    if (s == null) continue
                    val k = rec.ingredients.indexOfFirst { s.id in it.ids }
                    if (k < 0) { ok = false; break }
                    counts[k]++
                }
                if (ok && rec.ingredients.withIndex().all { (k, ing) -> counts[k] == ing.count }) return rec
            }
        }
        return null
    }

    /** Uses one item from every filled grid cell. */
    fun consumeGrid(grid: Array<ItemStack?>) {
        for (i in grid.indices) {
            val s = grid[i] ?: continue
            s.count--
            if (s.count <= 0) grid[i] = null
        }
    }
}

/** What a block drops when mined in survival. */
object Drops {
    private val rnd = Random()

    /** Pickaxe tier needed to get anything from the block. */
    fun harvestTier(id: Int): Int = if (Blocks.isDeepslateOre(id)) harvestTier(deepslateBase(id)) else when (id) {
        Blocks.IRON_ORE, Blocks.IRON_BLOCK, Blocks.COPPER_ORE, Blocks.COPPER_BLOCK, Blocks.LAPIS_ORE, Blocks.LAPIS_BLOCK -> 1
        Blocks.GOLD_ORE, Blocks.GOLD_BLOCK, Blocks.DIAMOND_ORE, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_ORE,
        Blocks.EMERALD_BLOCK, Blocks.REDSTONE_ORE -> 2
        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.ANCIENT_DEBRIS, Blocks.NETHERITE_BLOCK -> 3
        Blocks.EXPOSED_COPPER, Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER, Blocks.CUT_COPPER -> 1
        else -> 0
    }

    /** The plain ore a deepslate ore behaves like. */
    fun deepslateBase(id: Int) = when (id - Blocks.DEEPSLATE_ORE_FIRST) {
        0 -> Blocks.COAL_ORE; 1 -> Blocks.IRON_ORE; 2 -> Blocks.GOLD_ORE; 3 -> Blocks.DIAMOND_ORE; 4 -> Blocks.REDSTONE_ORE; else -> Blocks.LAPIS_ORE
    }

    fun canHarvest(id: Int, tool: ItemDef?): Boolean {
        val def = Blocks[id]
        // Kitchen and home things come back whatever you break them with.
        if (def.category == Category.HOME) return true
        if (def.tool == ToolType.PICKAXE) return tool?.tool == ToolType.PICKAXE && tool.tier >= harvestTier(id)
        if (id == Blocks.COBWEB) return tool?.tool == ToolType.SWORD
        return true
    }

    private fun i(name: String) = Items.find(name)

    fun forBlock(id: Int, tool: ItemDef?, meta: Int = 0): List<Pair<Int, Int>> {
        if (!canHarvest(id, tool)) return emptyList()
        if (Blocks.isDeepslateOre(id)) return forBlock(deepslateBase(id), tool, meta)
        fun one(x: Int) = listOf(x to 1)
        return when (id) {
            Blocks.BERRY_BUSH -> if (meta >= 2) listOf(i("Sweet Berries") to meta) else one(i("Sweet Berries"))
            Blocks.SEAGRASS -> if (tool?.name == "Shears") one(id) else emptyList()
            Blocks.SNOW_LAYER -> one(i("Snowball"))
            Blocks.RED_MUSHROOM_BLOCK -> listOf(Blocks.RED_MUSHROOM to rnd.nextInt(3))
            Blocks.BROWN_MUSHROOM_BLOCK -> listOf(Blocks.BROWN_MUSHROOM to rnd.nextInt(3))
            Blocks.AMETHYST_BLOCK -> if (tool == null) listOf(i("Amethyst Shard") to 4) else one(id)
            Blocks.CHERRY_LEAVES, Blocks.MANGROVE_LEAVES -> if (tool?.name == "Shears") one(id)
                else if (rnd.nextInt(100) < 7) one(if (id == Blocks.CHERRY_LEAVES) Blocks.CHERRY_SAPLING else i("Stick")) else emptyList()
            Blocks.BEE_NEST -> one(Blocks.BEE_NEST)
            Blocks.FRIDGE -> if (meta and Shapes.UPPER != 0) emptyList() else one(id)
            Blocks.STONE -> one(Blocks.COBBLESTONE)
            Blocks.DEEPSLATE -> one(Blocks.COBBLED_DEEPSLATE)
            Blocks.GRASS, Blocks.SNOW_GRASS, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.FARMLAND, Blocks.DIRT_PATH -> one(Blocks.DIRT)
            Blocks.COAL_ORE -> one(i("Coal"))
            Blocks.IRON_ORE -> one(i("Raw Iron"))
            Blocks.GOLD_ORE -> one(i("Raw Gold"))
            Blocks.COPPER_ORE -> listOf(i("Raw Copper") to 2 + rnd.nextInt(3))
            Blocks.DIAMOND_ORE -> one(i("Diamond"))
            Blocks.EMERALD_ORE -> one(i("Emerald"))
            Blocks.LAPIS_ORE -> listOf(i("Lapis Lazuli") to 4 + rnd.nextInt(5))
            Blocks.REDSTONE_ORE -> listOf(Blocks.REDSTONE_DUST to 4 + rnd.nextInt(2))
            Blocks.GLASS, Blocks.ICE, Blocks.WATER, Blocks.PISTON_HEAD, Blocks.BEDROCK -> emptyList()
            in Blocks.STAINED_GLASS_FIRST until Blocks.STAINED_GLASS_FIRST + 16 -> emptyList()
            Blocks.LEAVES, Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES,
            Blocks.DARK_OAK_LEAVES -> if (tool?.name == "Shears") one(id) else {
                val sapling = Blocks.SAPLING_FIRST + when (id) {
                    Blocks.SPRUCE_LEAVES -> 1; Blocks.BIRCH_LEAVES -> 2; Blocks.JUNGLE_LEAVES -> 3
                    Blocks.ACACIA_LEAVES -> 4; Blocks.DARK_OAK_LEAVES -> 5; else -> 0
                }
                when (rnd.nextInt(100)) {
                    in 0..6 -> one(sapling)
                    in 7..10 -> if (id == Blocks.LEAVES || id == Blocks.DARK_OAK_LEAVES) one(i("Apple")) else emptyList()
                    in 11..13 -> one(i("Stick"))
                    in 14..18 -> if (id == Blocks.JUNGLE_LEAVES || id == Blocks.LEAVES) one(i("Tea Leaves")) else emptyList()
                    else -> emptyList()
                }
            }
            Blocks.SNOW -> listOf(i("Snowball") to 4)
            Blocks.TALL_GRASS, Blocks.FERN -> if (tool?.name == "Shears") one(id) else when (rnd.nextInt(100)) {
                // Grass hides seeds and, now and then, the kitchen basics.
                in 0..14 -> one(i("Wheat Seeds"))
                in 15..19 -> one(i("Rice"))
                in 20..23 -> one(i("Lentils"))
                in 24..26 -> one(i("Tomato"))
                else -> emptyList()
            }
            Blocks.GRAVEL -> if (rnd.nextInt(10) == 0) one(i("Flint")) else one(Blocks.GRAVEL)
            Blocks.CLAY -> listOf(i("Clay Ball") to 4)
            Blocks.GLOWSTONE -> listOf(i("Glowstone Dust") to 2 + rnd.nextInt(3))
            Blocks.MELON -> listOf(i("Melon Slice") to 3 + rnd.nextInt(5))
            Blocks.BOOKSHELF -> listOf(i("Book") to 3)
            Blocks.COBWEB -> one(i("String"))
            Blocks.REDSTONE_LAMP_ON -> one(Blocks.REDSTONE_LAMP)
            Blocks.LAVA -> emptyList()
            Blocks.WHEAT_CROP -> if (meta >= 7) listOf(i("Wheat") to 1, i("Wheat Seeds") to 1 + rnd.nextInt(3)) else one(i("Wheat Seeds"))
            Blocks.CARROTS -> if (meta >= 7) listOf(i("Carrot") to 2 + rnd.nextInt(3)) else one(i("Carrot"))
            Blocks.POTATOES -> if (meta >= 7) listOf(i("Potato") to 2 + rnd.nextInt(3)) else one(i("Potato"))
            else -> one(id)
        }
    }
}
