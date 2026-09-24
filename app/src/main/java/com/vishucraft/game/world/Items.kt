package com.vishucraft.game.world

enum class ItemUse { NONE, TILL, PATH, IGNITE, BUCKET, WATER_BUCKET }

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
) {
    val enchanted get() = enchantments != null
}

/**
 * Tools and other non-block items. Hotbar slots store either a block id (< BASE) or an item id (>= BASE).
 */
object Items {
    const val BASE = 1000

    private class Tier(val name: String, val key: String, val speed: Float, val attack: Int)

    private val tiers = listOf(
        Tier("Wooden", "wood", 2f, 0),
        Tier("Stone", "stone", 4f, 1),
        Tier("Iron", "iron", 6f, 2),
        Tier("Golden", "gold", 12f, 0),
        Tier("Diamond", "diamond", 8f, 3),
        Tier("Netherite", "netherite", 9f, 4),
    )

    val all: List<ItemDef>
    private val byId = HashMap<Int, ItemDef>()

    init {
        val list = ArrayList<ItemDef>()
        fun add(name: String, icon: String, tool: ToolType = ToolType.NONE, speed: Float = 1f, attack: Int = 1,
                ench: String? = null, use: ItemUse = ItemUse.NONE) {
            list.add(ItemDef(BASE + list.size, name, Tiles.id(icon), tool, speed, attack, ench, use))
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
            add("${tier.name} $kind", "${key}_${tier.key}", type, tier.speed, attack, use = use)
        }
        // Ready-made enchanted gear.
        for (tier in tiers.filter { it.key == "diamond" || it.key == "netherite" }) {
            add("Enchanted ${tier.name} Sword", "sword_${tier.key}", ToolType.SWORD, tier.speed, 4 + tier.attack + 3,
                "Sharpness V · Fire Aspect II · Looting III · Unbreaking III")
            add("Enchanted ${tier.name} Pickaxe", "pickaxe_${tier.key}", ToolType.PICKAXE, tier.speed + 26f, 2,
                "Efficiency V · Fortune III · Unbreaking III · Mending")
            add("Enchanted ${tier.name} Axe", "axe_${tier.key}", ToolType.AXE, tier.speed + 26f, 9 + tier.attack / 2,
                "Efficiency V · Sharpness V · Unbreaking III")
            add("Enchanted ${tier.name} Shovel", "shovel_${tier.key}", ToolType.SHOVEL, tier.speed + 26f, 2,
                "Efficiency V · Silk Touch · Unbreaking III", ItemUse.PATH)
            add("Enchanted ${tier.name} Hoe", "hoe_${tier.key}", ToolType.HOE, tier.speed + 26f, 2,
                "Efficiency V · Unbreaking III", ItemUse.TILL)
        }
        add("Flint and Steel", "flint_and_steel", use = ItemUse.IGNITE)
        add("Bucket", "bucket", use = ItemUse.BUCKET)
        add("Water Bucket", "water_bucket", use = ItemUse.WATER_BUCKET)
        all = list
        for (i in all) byId[i.id] = i
    }

    fun isItem(slot: Int) = slot >= BASE
    operator fun get(slot: Int): ItemDef? = byId[slot]

    fun isValidSlot(slot: Int): Boolean =
        if (isItem(slot)) byId.containsKey(slot) else slot in 1 until Blocks.COUNT && Blocks[slot].inInventory

    fun displayName(slot: Int): String = get(slot)?.name ?: Blocks[slot].name

    fun find(name: String): Int = all.first { it.name == name }.id
}
