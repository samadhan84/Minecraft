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
            Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS)
        val cobble = intArrayOf(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE)
        val stick = i("Stick"); val coal = intArrayOf(i("Coal"), i("Charcoal"))
        val iron = i("Iron Ingot"); val gold = i("Gold Ingot"); val diamond = i("Diamond")

        // Wood
        for ((log, plank) in listOf(Blocks.LOG to Blocks.PLANKS, Blocks.SPRUCE_LOG to Blocks.SPRUCE_PLANKS,
            Blocks.BIRCH_LOG to Blocks.BIRCH_PLANKS, Blocks.JUNGLE_LOG to Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_LOG to Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_LOG to Blocks.DARK_OAK_PLANKS)) r(plank, 4, false, log to 1)
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
    fun harvestTier(id: Int): Int = when (id) {
        Blocks.IRON_ORE, Blocks.IRON_BLOCK, Blocks.COPPER_ORE, Blocks.COPPER_BLOCK, Blocks.LAPIS_ORE, Blocks.LAPIS_BLOCK -> 1
        Blocks.GOLD_ORE, Blocks.GOLD_BLOCK, Blocks.DIAMOND_ORE, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_ORE,
        Blocks.EMERALD_BLOCK, Blocks.REDSTONE_ORE -> 2
        Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.ANCIENT_DEBRIS, Blocks.NETHERITE_BLOCK -> 3
        else -> 0
    }

    fun canHarvest(id: Int, tool: ItemDef?): Boolean {
        val def = Blocks[id]
        if (def.tool == ToolType.PICKAXE) return tool?.tool == ToolType.PICKAXE && tool.tier >= harvestTier(id)
        if (id == Blocks.COBWEB) return tool?.tool == ToolType.SWORD
        return true
    }

    private fun i(name: String) = Items.find(name)

    fun forBlock(id: Int, tool: ItemDef?, meta: Int = 0): List<Pair<Int, Int>> {
        if (!canHarvest(id, tool)) return emptyList()
        fun one(x: Int) = listOf(x to 1)
        return when (id) {
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
                    else -> emptyList()
                }
            }
            Blocks.SNOW -> listOf(i("Snowball") to 4)
            Blocks.TALL_GRASS, Blocks.FERN -> if (tool?.name == "Shears") one(id) else if (rnd.nextInt(100) < 15) one(i("Wheat Seeds")) else emptyList()
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
