package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Drops
import com.vishucraft.game.world.ItemDef
import com.vishucraft.game.world.ItemUse
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.World

/*
 * Farming: tap a fully grown crop to harvest it (it is planted again by itself), a sickle harvests the
 * crops around it too, a watering can makes crops grow, and crops near water or a sprinkler grow faster.
 */

object Farming {
    /** What you plant for each crop (and get back to replant it). */
    fun seedFor(crop: Int): Int? = when (crop) {
        Blocks.WHEAT_CROP -> Items.find("Wheat Seeds")
        Blocks.CARROTS -> Items.find("Carrot")
        Blocks.POTATOES -> Items.find("Potato")
        Blocks.RICE_CROP -> Items.find("Rice")
        Blocks.TOMATO_CROP -> Items.find("Tomato")
        Blocks.LENTIL_CROP -> Items.find("Lentils")
        Blocks.BEETROOT_CROP -> Items.find("Beetroot Seeds")
        else -> null
    }

    /** The crop a held item plants on farmland. */
    fun cropFor(itemName: String?): Int = when (itemName) {
        "Wheat Seeds" -> Blocks.WHEAT_CROP; "Carrot" -> Blocks.CARROTS; "Potato" -> Blocks.POTATOES
        "Rice" -> Blocks.RICE_CROP; "Tomato" -> Blocks.TOMATO_CROP; "Lentils" -> Blocks.LENTIL_CROP
        "Beetroot Seeds" -> Blocks.BEETROOT_CROP
        else -> -1
    }

    /**
     * How likely a crop is to grow on a random tick: slow on dry land, faster with water within 4 blocks,
     * fastest next to a sprinkler.
     */
    fun growChance(world: World, x: Int, y: Int, z: Int): Float {
        var water = false
        for (dz in -4..4) for (dx in -4..4) for (dy in -1..1) {
            when (world.getBlock(x + dx, y + dy, z + dz)) {
                Blocks.SPRINKLER -> return 1f
                Blocks.WATER -> water = true
            }
        }
        return if (water) 0.6f else 0.35f
    }
}

private fun Game.say(text: String) { uiEvents.add("toast:$text") }

/** Picks one ripe crop and plants it again. Returns false if it isn't ripe. */
internal fun Game.harvest(x: Int, y: Int, z: Int): Boolean {
    val id = world.getBlock(x, y, z)
    if (!Blocks.isCrop(id) || world.getMeta(x, y, z) < 7) return false
    val seed = Farming.seedFor(id)
    var replanted = false
    for ((dropId, n) in Drops.forBlock(id, null, 7)) {
        var count = n
        if (!replanted && dropId == seed && count > 0) { count--; replanted = true }
        if (count > 0) give(dropId, count)
    }
    // Planting again uses one seed from what was picked (the first crop is always free).
    setBlock(x, y, z, id, 0)
    sound("hit_grass", x + 0.5f, y + 0.5f, z + 0.5f, 0.7f)
    return true
}

/**
 * Farming taps: harvesting ripe crops (by hand or with a sickle) and the watering can.
 * Runs before normal item use so a held seed or tool doesn't get in the way. Returns true when handled.
 */
internal fun Game.farmingUse(t: RayHit?, item: ItemDef?): Boolean {
    if (item?.name == "Watering Can") {
        val stack = heldStack() ?: return false
        val w = Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], Game.REACH, hitWater = true)
        if (w != null && (w.block == Blocks.WATER || w.block == Blocks.KITCHEN_SINK)) {
            stack.damage = 0
            sound("splash", w.x + 0.5f, w.y + 0.5f, w.z + 0.5f, 0.5f)
            say("The watering can is full")
            return true
        }
        t ?: return true
        if (stack.damage >= item.durability) { say("The watering can is empty. Fill it at water or a sink"); return true }
        // Water the crops around the one you look at (or on the farmland you look at).
        val cy = if (Blocks.isCrop(t.block)) t.y else t.y + 1
        var watered = 0
        for (dz in -1..1) for (dx in -1..1) {
            val id = world.getBlock(t.x + dx, cy, t.z + dz)
            val meta = world.getMeta(t.x + dx, cy, t.z + dz)
            if (Blocks.isCrop(id) && meta < 7) { setBlock(t.x + dx, cy, t.z + dz, id, meta + 1); watered++ }
        }
        stack.damage++
        sound("splash", t.x + 0.5f, cy + 0.5f, t.z + 0.5f, 0.4f)
        if (watered == 0) say("Water growing crops to help them grow")
        return true
    }
    t ?: return false
    if (!Blocks.isCrop(t.block) || item?.use == ItemUse.GROW) return false
    if (world.getMeta(t.x, t.y, t.z) < 7) {
        if (item == null || Farming.cropFor(item.name) >= 0) {
            say("Not ready yet. Water it, or use bone meal")
            return true
        }
        return false
    }
    if (item?.name == "Sickle") {
        var n = 0
        for (dz in -1..1) for (dx in -1..1) if (harvest(t.x + dx, t.y, t.z + dz)) n++
        damageHeld(1)
        say("Harvested $n crops")
        return true
    }
    return harvest(t.x, t.y, t.z)
}
