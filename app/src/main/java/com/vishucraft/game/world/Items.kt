package com.vishucraft.game.world

enum class ItemUse { NONE, TILL, PATH, IGNITE, BUCKET, WATER_BUCKET, EAT, BOW, GROW, LAVA_BUCKET, CART }

/** 0 helmet, 1 chestplate, 2 leggings, 3 boots; -1 when not armor. */
typealias ArmorSlot = Int

class ItemDef(
    /** Slot value (always >= [Items.BASE]). */
    val id: Int,
    val name: String,
    val icon: Int,
    val tool: ToolType = ToolType.NONE,
    /** Mining speed multiplier when used on the matching block type. */
    val speed: Float = 1f,
    val attack: Int = 1,
    /** Enchantments baked into the item (there is no enchanting table). */
    val enchantments: String? = null,
    val use: ItemUse = ItemUse.NONE,
    val maxStack: Int = 64,
    /** Uses before breaking (0 = unbreakable / not a tool). */
    val durability: Int = 0,
    /** Mining tier: 0 wood/gold, 1 stone, 2 iron, 3 diamond, 4 netherite. */
    val tier: Int = 0,
    /** Hunger points restored when eaten. */
    val food: Int = 0,
    val armorSlot: ArmorSlot = -1,
    val armorPoints: Int = 0,
    /** Seconds of furnace fuel. */
    val fuel: Float = 0f,
) {
    val enchanted get() = enchantments != null
}

/**
 * Tools, materials, food and armor. Slots store either a block id (< BASE) or an item id (>= BASE).
 * Item ids are assigned in declaration order, so new items are only ever appended.
 */
object Items {
    const val BASE = 1000

    private class Tier(val name: String, val key: String, val speed: Float, val attack: Int, val durability: Int, val level: Int)

    private val tiers = listOf(
        Tier("Wooden", "wood", 2f, 0, 59, 0),
        Tier("Stone", "stone", 4f, 1, 131, 1),
        Tier("Iron", "iron", 6f, 2, 250, 2),
        Tier("Golden", "gold", 12f, 0, 32, 0),
        Tier("Diamond", "diamond", 8f, 3, 1561, 3),
        Tier("Netherite", "netherite", 9f, 4, 2031, 4),
    )

    val all: List<ItemDef>
    private val byId = HashMap<Int, ItemDef>()
    private val byName = HashMap<String, Int>()

    init {
        val list = ArrayList<ItemDef>()
        fun add(name: String, icon: String, tool: ToolType = ToolType.NONE, speed: Float = 1f, attack: Int = 1,
                ench: String? = null, use: ItemUse = ItemUse.NONE, maxStack: Int = 64, durability: Int = 0,
                tier: Int = 0, food: Int = 0, armorSlot: Int = -1, armorPoints: Int = 0, fuel: Float = 0f) {
            list.add(ItemDef(BASE + list.size, name, Tiles.id(icon), tool, speed, attack, ench,
                if (food > 0) ItemUse.EAT else use, maxStack, durability, tier, food, armorSlot, armorPoints, fuel))
        }
        val kinds = listOf(
            Triple("Sword", "sword", ToolType.SWORD), Triple("Pickaxe", "pickaxe", ToolType.PICKAXE),
            Triple("Axe", "axe", ToolType.AXE), Triple("Shovel", "shovel", ToolType.SHOVEL),
            Triple("Hoe", "hoe", ToolType.HOE),
        )
        for (tier in tiers) for ((kind, key, type) in kinds) {
            val attack = when (type) {
                ToolType.SWORD -> 4 + tier.attack
                ToolType.AXE -> 7 + tier.attack / 2
                else -> 2 + tier.attack / 2
            }
            val use = when (type) { ToolType.HOE -> ItemUse.TILL; ToolType.SHOVEL -> ItemUse.PATH; else -> ItemUse.NONE }
            add("${tier.name} $kind", "${key}_${tier.key}", type, tier.speed, attack, use = use, maxStack = 1,
                durability = tier.durability, tier = tier.level, fuel = if (tier.key == "wood") 5f else 0f)
        }
        // Ready-made enchanted gear (Unbreaking III: four times the durability).
        for (tier in tiers.filter { it.key == "diamond" || it.key == "netherite" }) {
            val d = tier.durability * 4
            add("Enchanted ${tier.name} Sword", "sword_${tier.key}", ToolType.SWORD, tier.speed, 4 + tier.attack + 3,
                "Sharpness V · Fire Aspect II · Looting III · Unbreaking III", maxStack = 1, durability = d, tier = tier.level)
            add("Enchanted ${tier.name} Pickaxe", "pickaxe_${tier.key}", ToolType.PICKAXE, tier.speed + 26f, 2,
                "Efficiency V · Fortune III · Unbreaking III · Mending", maxStack = 1, durability = d, tier = tier.level)
            add("Enchanted ${tier.name} Axe", "axe_${tier.key}", ToolType.AXE, tier.speed + 26f, 9 + tier.attack / 2,
                "Efficiency V · Sharpness V · Unbreaking III", maxStack = 1, durability = d, tier = tier.level)
            add("Enchanted ${tier.name} Shovel", "shovel_${tier.key}", ToolType.SHOVEL, tier.speed + 26f, 2,
                "Efficiency V · Silk Touch · Unbreaking III", ItemUse.PATH, maxStack = 1, durability = d, tier = tier.level)
            add("Enchanted ${tier.name} Hoe", "hoe_${tier.key}", ToolType.HOE, tier.speed + 26f, 2,
                "Efficiency V · Unbreaking III", ItemUse.TILL, maxStack = 1, durability = d, tier = tier.level)
        }
        add("Flint and Steel", "flint_and_steel", use = ItemUse.IGNITE, maxStack = 1, durability = 64)
        add("Bucket", "bucket", use = ItemUse.BUCKET, maxStack = 16)
        add("Water Bucket", "water_bucket", use = ItemUse.WATER_BUCKET, maxStack = 1)

        // ---- Materials
        add("Stick", "stick", fuel = 2f)
        add("Coal", "coal", fuel = 32f)
        add("Charcoal", "charcoal", fuel = 32f)
        add("Iron Ingot", "iron_ingot")
        add("Gold Ingot", "gold_ingot")
        add("Copper Ingot", "copper_ingot")
        add("Diamond", "diamond")
        add("Emerald", "emerald")
        add("Lapis Lazuli", "lapis")
        add("Netherite Scrap", "netherite_scrap")
        add("Netherite Ingot", "netherite_ingot")
        add("Flint", "flint")
        add("Leather", "leather")
        add("String", "string")
        add("Feather", "feather")
        add("Bone", "bone")
        add("Gunpowder", "gunpowder")
        add("Wheat Seeds", "wheat_seeds")
        add("Wheat", "wheat")
        add("Brick", "brick_item")
        add("Clay Ball", "clay_ball")
        add("Paper", "paper")
        add("Book", "book")
        add("Glowstone Dust", "glowstone_dust")
        add("Slimeball", "slimeball")
        add("Arrow", "arrow")
        add("Bow", "bow", use = ItemUse.BOW, maxStack = 1, durability = 384, attack = 1)

        // ---- Food
        add("Apple", "apple", food = 4)
        add("Bread", "bread", food = 5)
        add("Raw Beef", "raw_beef", food = 3)
        add("Steak", "steak", food = 8)
        add("Raw Porkchop", "raw_porkchop", food = 3)
        add("Cooked Porkchop", "cooked_porkchop", food = 8)
        add("Raw Mutton", "raw_mutton", food = 2)
        add("Cooked Mutton", "cooked_mutton", food = 6)
        add("Rotten Flesh", "rotten_flesh", food = 4)
        add("Melon Slice", "melon_slice", food = 2)
        add("Golden Apple", "golden_apple", food = 4)

        // ---- Armor: helmet, chestplate, leggings, boots.
        val pieces = listOf("Helmet" to "helmet", "Chestplate" to "chestplate", "Leggings" to "leggings", "Boots" to "boots")
        val armorSets = listOf(
            Triple("Leather", "leather", intArrayOf(1, 3, 2, 1)),
            Triple("Golden", "gold", intArrayOf(2, 5, 3, 1)),
            Triple("Iron", "iron", intArrayOf(2, 6, 5, 2)),
            Triple("Diamond", "diamond", intArrayOf(3, 8, 6, 3)),
            Triple("Netherite", "netherite", intArrayOf(3, 8, 6, 3)),
        )
        for ((name, key, points) in armorSets) for ((i, piece) in pieces.withIndex()) {
            add("$name ${piece.first}", "${piece.second}_$key", maxStack = 1, armorSlot = i, armorPoints = points[i])
        }
        for ((name, key, points) in armorSets.filter { it.second == "diamond" || it.second == "netherite" }) {
            for ((i, piece) in pieces.withIndex()) {
                add("Enchanted $name ${piece.first}", "${piece.second}_$key", maxStack = 1, armorSlot = i,
                    armorPoints = points[i] + 1, ench = "Protection IV · Unbreaking III")
            }
        }

        // ---- Farming and more (appended so older saves keep their ids)
        add("Carrot", "carrot", food = 3)
        add("Potato", "potato", food = 1)
        add("Baked Potato", "baked_potato", food = 5)
        add("Bone Meal", "bone_meal", use = ItemUse.GROW)
        add("Lava Bucket", "lava_bucket", use = ItemUse.LAVA_BUCKET, maxStack = 1, fuel = 100f)
        add("Minecart", "minecart", use = ItemUse.CART, maxStack = 1)

        all = list
        for (i in all) { byId[i.id] = i; byName[i.name] = i.id }
    }

    fun isItem(slot: Int) = slot >= BASE
    operator fun get(slot: Int): ItemDef? = byId[slot]

    fun isValidSlot(slot: Int): Boolean =
        if (isItem(slot)) byId.containsKey(slot) else slot in 1 until Blocks.COUNT && Blocks[slot].inInventory

    fun displayName(slot: Int): String = get(slot)?.name ?: Blocks[slot].name

    fun find(name: String): Int = byName[name] ?: error("unknown item $name")

    fun maxStack(slot: Int): Int = get(slot)?.maxStack ?: 64

    /** Furnace fuel seconds for any slot value (logs and planks burn too). */
    fun fuel(slot: Int): Float {
        get(slot)?.let { return it.fuel }
        val d = Blocks[slot]
        return when {
            slot == Blocks.COAL_BLOCK -> 320f
            d.tool == ToolType.AXE && d.category == Category.NATURE -> 12f // logs
            d.tool == ToolType.AXE -> 6f // planks, crafting table, chest...
            slot == Blocks.OAK_SLAB -> 3f
            else -> 0f
        }
    }
}
