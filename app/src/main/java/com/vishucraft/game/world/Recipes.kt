package com.vishucraft.game.world

import java.util.Random

/** One ingredient: any of [ids], [count] of them in total. */
class Ingredient(val ids: IntArray, val count: Int) {
    fun available(inv: Inventory) = ids.sumOf { inv.count(it) }
}

class Recipe(val result: Int, val count: Int, val ingredients: List<Ingredient>, val needsTable: Boolean) {
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
        val planks = intArrayOf(Blocks.PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS)
        val cobble = intArrayOf(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE)
        val stick = i("Stick"); val coal = intArrayOf(i("Coal"), i("Charcoal"))
        val iron = i("Iron Ingot"); val gold = i("Gold Ingot"); val diamond = i("Diamond")

        // Wood
        for ((log, plank) in listOf(Blocks.LOG to Blocks.PLANKS, Blocks.SPRUCE_LOG to Blocks.SPRUCE_PLANKS,
            Blocks.BIRCH_LOG to Blocks.BIRCH_PLANKS, Blocks.JUNGLE_LOG to Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_LOG to Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_LOG to Blocks.DARK_OAK_PLANKS)) r(plank, 4, false, log to 1)
        r(stick, 4, false, planks to 2)
        r(Blocks.CRAFTING_TABLE, 1, false, planks to 4)
        r(Blocks.TORCH, 4, false, coal to 1, stick to 1)
        r(Blocks.CHEST, 1, true, planks to 8)
        r(Blocks.FURNACE, 1, true, cobble to 8)
        r(Blocks.OAK_SLAB, 6, true, planks to 3)
        r(Blocks.BOOKSHELF, 1, true, planks to 6, i("Book") to 3)
        r(i("Paper"), 3, true, Blocks.SUGAR_CANE to 3)
        r(i("Book"), 1, false, i("Paper") to 3, i("Leather") to 1)

        // Tools
        val mats = listOf(
            "Wooden" to planks, "Stone" to cobble, "Iron" to intArrayOf(iron), "Golden" to intArrayOf(gold),
            "Diamond" to intArrayOf(diamond),
        )
        for ((name, m) in mats) {
            r(i("$name Sword"), 1, true, m to 2, stick to 1)
            r(i("$name Pickaxe"), 1, true, m to 3, stick to 2)
            r(i("$name Axe"), 1, true, m to 3, stick to 2)
            r(i("$name Shovel"), 1, true, m to 1, stick to 2)
            r(i("$name Hoe"), 1, true, m to 2, stick to 2)
        }
        val netherite = i("Netherite Ingot")
        for (kind in listOf("Sword", "Pickaxe", "Axe", "Shovel", "Hoe", "Helmet", "Chestplate", "Leggings", "Boots")) {
            r(i("Netherite $kind"), 1, true, i("Diamond $kind") to 1, netherite to 1)
        }
        r(netherite, 1, true, i("Netherite Scrap") to 4, gold to 4)

        // Armor
        for ((name, m) in listOf("Leather" to i("Leather"), "Golden" to gold, "Iron" to iron, "Diamond" to diamond)) {
            r(i("$name Helmet"), 1, true, m to 5)
            r(i("$name Chestplate"), 1, true, m to 8)
            r(i("$name Leggings"), 1, true, m to 7)
            r(i("$name Boots"), 1, true, m to 4)
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
        r(Blocks.STONE_SLAB, 6, true, Blocks.SMOOTH_STONE to 3)
        r(Blocks.COBBLESTONE_SLAB, 6, true, Blocks.COBBLESTONE to 3)
        r(Blocks.STONE_BRICK_SLAB, 6, true, Blocks.STONE_BRICKS to 3)
        r(Blocks.BRICK_SLAB, 6, true, Blocks.BRICKS to 3)
        r(Blocks.SANDSTONE_SLAB, 6, true, Blocks.SANDSTONE to 3)
        r(Blocks.JACK_O_LANTERN, 1, false, Blocks.PUMPKIN to 1, Blocks.TORCH to 1)
        r(Blocks.MELON, 1, true, i("Melon Slice") to 9)

        // Food
        r(i("Bread"), 1, true, i("Wheat") to 3)
        r(i("Golden Apple"), 1, true, i("Apple") to 1, gold to 8)

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
        r(i("Bucket"), 1, true, iron to 3)
        r(i("Bow"), 1, true, stick to 3, i("String") to 3)
        r(i("Arrow"), 4, true, i("Flint") to 1, stick to 1, i("Feather") to 1)
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
            Blocks.CACTUS to Blocks.CONCRETE_FIRST + 13,
        )
    }

    fun available(inv: Inventory, table: Boolean): List<Recipe> = crafting.filter { table || !it.needsTable }
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

    fun forBlock(id: Int, tool: ItemDef?): List<Pair<Int, Int>> {
        if (!canHarvest(id, tool)) return emptyList()
        fun one(x: Int) = listOf(x to 1)
        return when (id) {
            Blocks.STONE -> one(Blocks.COBBLESTONE)
            Blocks.DEEPSLATE -> one(Blocks.COBBLED_DEEPSLATE)
            Blocks.GRASS, Blocks.SNOW_GRASS, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.FARMLAND, Blocks.DIRT_PATH -> one(Blocks.DIRT)
            Blocks.COAL_ORE -> one(i("Coal"))
            Blocks.DIAMOND_ORE -> one(i("Diamond"))
            Blocks.EMERALD_ORE -> one(i("Emerald"))
            Blocks.LAPIS_ORE -> listOf(i("Lapis Lazuli") to 4 + rnd.nextInt(5))
            Blocks.REDSTONE_ORE -> listOf(Blocks.REDSTONE_DUST to 4 + rnd.nextInt(2))
            Blocks.GLASS, Blocks.ICE, Blocks.WATER, Blocks.PISTON_HEAD, Blocks.BEDROCK -> emptyList()
            in Blocks.STAINED_GLASS_FIRST until Blocks.STAINED_GLASS_FIRST + 16 -> emptyList()
            Blocks.LEAVES, Blocks.DARK_OAK_LEAVES -> when (rnd.nextInt(100)) {
                in 0..4 -> one(i("Apple")); in 5..8 -> one(i("Stick")); else -> emptyList()
            }
            Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES ->
                if (rnd.nextInt(100) < 5) one(i("Stick")) else emptyList()
            Blocks.TALL_GRASS, Blocks.FERN -> if (rnd.nextInt(100) < 15) one(i("Wheat Seeds")) else emptyList()
            Blocks.GRAVEL -> if (rnd.nextInt(10) == 0) one(i("Flint")) else one(Blocks.GRAVEL)
            Blocks.CLAY -> listOf(i("Clay Ball") to 4)
            Blocks.GLOWSTONE -> listOf(i("Glowstone Dust") to 2 + rnd.nextInt(3))
            Blocks.MELON -> listOf(i("Melon Slice") to 3 + rnd.nextInt(5))
            Blocks.BOOKSHELF -> listOf(i("Book") to 3)
            Blocks.COBWEB -> one(i("String"))
            Blocks.REDSTONE_LAMP_ON -> one(Blocks.REDSTONE_LAMP)
            else -> one(id)
        }
    }
}
