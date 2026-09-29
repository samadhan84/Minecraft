package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.ItemDef
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Recipes
import com.vishucraft.game.world.Shapes

/*
 * The kitchen and home pack: pots and pans cook on a gas stove, the microwave, oven, toaster and mixer make
 * food, the fridge keeps it (and makes ice cream), the sink gives water, the washing machine washes wool,
 * lamps, TVs, fans and ACs switch on and off, and chairs and sofas can be sat on.
 */

/** Something cooking (or washing): [result] pops out on top when [left] runs out. */
class KitchenJob(val x: Int, val y: Int, val z: Int, val block: Int, val result: Int, val count: Int, var left: Float) {
    /** Seconds until the next pressure cooker whistle. */
    var whistle = 2.5f
    var whistles = 0
}

class Kitchen {
    val jobs = ArrayList<KitchenJob>()
    /** The chair or sofa the player is sitting on. */
    var seat: IntArray? = null

    companion object {
        /** Seconds each appliance takes. */
        fun seconds(block: Int) = when (block) {
            Blocks.PRESSURE_COOKER -> 7.5f
            Blocks.KETTLE -> 5f
            Blocks.OVEN -> 6f
            Blocks.FRIDGE -> 6f
            Blocks.WASHING_MACHINE -> 6f
            Blocks.MICROWAVE, Blocks.TOASTER -> 3f
            Blocks.MIXER -> 2f
            else -> 4f // tawa, frying pan
        }

        private fun n(name: String) = Items.find(name)

        /** Raw food that any heat cooks (the furnace's food recipes). */
        private fun cooked(input: Int): Int? = Recipes.smelting[input]?.takeIf { (Items[it]?.food ?: 0) > 0 }

        /** What [block] makes from one [input] (a block or item id): result and how many. */
        fun recipe(block: Int, input: Int): Pair<Int, Int>? {
            val name = Items[input]?.name
            return when (block) {
                Blocks.PRESSURE_COOKER -> when (name) {
                    "Rice" -> n("Steamed Rice") to 2; "Lentils" -> n("Dal") to 2; "Raw Chicken" -> n("Chicken Curry") to 1
                    else -> null
                }
                Blocks.TAWA -> when (name) {
                    "Dough" -> n("Roti") to 2; "Dosa Batter" -> n("Dosa") to 2; "Egg" -> n("Omelette") to 1
                    else -> null
                }
                Blocks.FRYING_PAN -> when (name) {
                    "Egg" -> n("Omelette") to 1; "Potato" -> n("French Fries") to 2
                    else -> cooked(input)?.let { it to 1 }
                }
                Blocks.KETTLE -> when (name) {
                    "Tea Leaves" -> n("Masala Chai") to 2; "Cocoa Beans" -> n("Hot Chocolate") to 1
                    else -> null
                }
                Blocks.MICROWAVE -> cooked(input)?.let { it to 1 }
                Blocks.OVEN -> when (name) {
                    "Dough" -> n("Bread") to 1; "Raw Pizza" -> n("Pizza") to 1
                    else -> cooked(input)?.let { it to 1 }
                }
                Blocks.TOASTER -> if (name == "Bread") n("Toast") to 2 else null
                Blocks.MIXER -> when {
                    name == "Wheat" -> n("Flour") to 1
                    name == "Apple" -> n("Apple Juice") to 1
                    name == "Melon Slice" -> n("Watermelon Juice") to 1
                    name == "Carrot" -> n("Carrot Juice") to 1
                    name == "Cocoa Beans" -> n("Chocolate") to 1
                    name == "Rice" -> n("Dosa Batter") to 1
                    input == Blocks.SUGAR_CANE -> n("Sugarcane Juice") to 1
                    else -> null
                }
                Blocks.WASHING_MACHINE -> when {
                    input in Blocks.CARPET_FIRST + 1 until Blocks.CARPET_FIRST + 16 -> Blocks.CARPET_FIRST to 1
                    Blocks.isBed(input) && input != Blocks.BED_FIRST -> Blocks.BED_FIRST to 1
                    input != Blocks.WOOL_WHITE && !Items.isItem(input) && Blocks[input].name.endsWith(" Wool") -> Blocks.WOOL_WHITE to 1
                    else -> null
                }
                else -> null
            }
        }
    }
}

private fun Game.say(text: String) { uiEvents.add("toast:$text") }

/** The block the fridge keeps its food in (the bottom half). */
private fun Game.fridgeBase(x: Int, y: Int, z: Int) = if (world.getMeta(x, y, z) and Shapes.UPPER != 0) y - 1 else y

/**
 * Taps on the kitchen and home blocks. Holding a block usually means "put it here" (a microwave on the counter,
 * a cooker on the stove), so those taps are left to normal placing. Returns true when handled.
 */
internal fun Game.kitchenBlock(t: RayHit, item: ItemDef?, sel: Int): Boolean {
    if (kitchen.seat != null) { standUp(); return true }
    val x = t.x; val y = t.y; val z = t.z
    val block = t.block
    if (!Blocks.isKitchenAppliance(block)) return false
    val holdingBlock = sel > 0 && !Items.isItem(sel)
    val meta = world.getMeta(x, y, z)
    val cx = x + 0.5f; val cy = y + 0.5f; val cz = z + 0.5f
    when (block) {
        Blocks.GAS_STOVE -> {
            if (holdingBlock) return false
            if (item != null && listOf(Blocks.PRESSURE_COOKER, Blocks.TAWA, Blocks.FRYING_PAN, Blocks.KETTLE).any { Kitchen.recipe(it, sel) != null }) {
                say("Put a pressure cooker, tawa, frying pan or kettle on the stove, then cook in it")
                return true
            }
            setBlock(x, y, z, block, meta xor 8)
            sound("click", cx, cy, cz)
            if (meta and 8 == 0) sound("fuse", cx, cy + 0.5f, cz, 0.3f)
            return true
        }
        Blocks.PRESSURE_COOKER, Blocks.TAWA, Blocks.FRYING_PAN, Blocks.KETTLE -> {
            if (holdingBlock) return false
            if (world.getBlock(x, y - 1, z) != Blocks.GAS_STOVE) { say("${Blocks[block].name}s cook on a gas stove"); return true }
            return startCooking(x, y, z, block, sel)
        }
        Blocks.MICROWAVE, Blocks.OVEN, Blocks.TOASTER, Blocks.MIXER, Blocks.WASHING_MACHINE -> {
            if (holdingBlock && !(block == Blocks.WASHING_MACHINE || (block == Blocks.MIXER && sel == Blocks.SUGAR_CANE))) return false
            return startCooking(x, y, z, block, sel)
        }
        Blocks.FRIDGE -> {
            if (holdingBlock) return false
            val by = fridgeBase(x, y, z)
            when (item?.name) {
                "Milk Bucket" -> {
                    val sugar = Items.find("Sugar")
                    if (survival && inventory.count(sugar) < 1) { say("Ice cream needs sugar too"); return true }
                    if (busy(x, by, z)) return true
                    if (survival) { inventory.remove(sugar, 1); inventory.slots[input.selectedSlot] = ItemStack(Items.find("Bucket"), 1) }
                    kitchen.jobs.add(KitchenJob(x, by, z, block, Items.find("Ice Cream"), 3, Kitchen.seconds(block)))
                    say("Freezing ice cream…")
                    return true
                }
                "Water Bucket" -> {
                    if (busy(x, by, z)) return true
                    if (survival) inventory.slots[input.selectedSlot] = ItemStack(Items.find("Bucket"), 1)
                    kitchen.jobs.add(KitchenJob(x, by, z, block, Blocks.ICE, 1, Kitchen.seconds(block)))
                    say("Making ice…")
                    return true
                }
            }
            world.blockEntities.chest(x, by, z); net?.openContainer(x, by, z, 0); uiEvents.add("open:chest:$x,$by,$z")
            sound("door", cx, cy, cz, 0.4f)
            return true
        }
        Blocks.KITCHEN_SINK -> {
            if (holdingBlock) return false
            when (item?.name) {
                "Bucket" -> { consumeHeld(); give(Items.find("Water Bucket")) }
                "Flour" -> { consumeHeld(); give(Items.find("Dough")); say("Mixed the flour with water into dough") }
                "Water Bucket" -> say("The bucket is already full")
                else -> say("Fresh water from the tap. Fill a bucket, or add water to flour to make dough")
            }
            sound("splash", cx, cy + 0.5f, cz, 0.5f)
            return true
        }
        Blocks.KITCHEN_COUNTER, Blocks.STUDY_TABLE, Blocks.DRESSING_TABLE, Blocks.WARDROBE -> {
            if (holdingBlock) return false
            val by = if (block == Blocks.WARDROBE) fridgeBase(x, y, z) else y
            world.blockEntities.chest(x, by, z); net?.openContainer(x, by, z, 0); uiEvents.add("open:chest:$x,$by,$z")
            sound("door", cx, cy, cz, 0.4f)
            return true
        }
        Blocks.WASH_BASIN, Blocks.WATER_COOLER -> {
            if (holdingBlock) return false
            when (item?.name) {
                "Bucket" -> { consumeHeld(); give(Items.find("Water Bucket")) }
                "Flour" -> { consumeHeld(); give(Items.find("Dough")) }
                else -> say(if (block == Blocks.WATER_COOLER) "Ahh, cool water! (Fill a bucket here too)" else "You washed your hands")
            }
            sound("splash", cx, cy + 0.5f, cz, 0.4f)
            return true
        }
        Blocks.DUSTBIN -> {
            val held = heldStack()
            if (held == null) { say("Tap the dustbin while holding something to throw it away"); return true }
            say("Threw away ${held.count} × ${Items.displayName(held.id)}")
            inventory.slots[input.selectedSlot] = null
            sound("hit_wood", cx, cy, cz, 0.6f)
            return true
        }
        Blocks.WALL_CLOCK -> {
            if (holdingBlock) return false
            // The day starts at 6 in the morning (time 0) and noon is a quarter of the way round.
            val minutes = ((timeOfDay * 24f * 60f).toInt() + 6 * 60) % (24 * 60)
            val h = minutes / 60; val m = minutes % 60
            val h12 = if (h % 12 == 0) 12 else h % 12
            say("It's $h12:${m.toString().padStart(2, '0')} ${if (h < 12) "in the morning" else if (h < 17) "in the afternoon" else if (h < 20) "in the evening" else "at night"}")
            sound("click", cx, cy, cz, 0.5f)
            return true
        }
        Blocks.MIRROR -> { if (holdingBlock) return false; say("Looking good!"); return true }
        Blocks.BATHTUB -> {
            if (holdingBlock) return false
            setBlock(x, y, z, block, meta xor 8)
            sound("splash", cx, cy, cz, 0.6f)
            say(if (meta and 8 == 0) "The bath is full of warm water" else "The bath drains away")
            return true
        }
        Blocks.TV, Blocks.TABLE_LAMP, Blocks.CEILING_FAN, Blocks.AIR_CONDITIONER, Blocks.COMPUTER, Blocks.CEILING_LIGHT,
        Blocks.CURTAIN, Blocks.SHOWER -> {
            if (holdingBlock) return false
            setBlock(x, y, z, block, meta xor 8)
            sound("click", cx, cy, cz)
            if (meta and 8 == 0) when (block) {
                Blocks.TV -> { sound("tv", cx, cy, cz, 0.6f); say("Cartoons are on!") }
                Blocks.AIR_CONDITIONER -> say("Cool air fills the room")
                Blocks.COMPUTER -> say("The computer starts up")
                Blocks.SHOWER -> { sound("splash", cx, cy, cz); say("The shower is on") }
            }
            return true
        }
        Blocks.CHAIR, Blocks.SOFA, Blocks.ARMCHAIR, Blocks.BEAN_BAG, Blocks.TOILET, Blocks.SWING -> {
            if (holdingBlock) return false
            if (block != Blocks.SWING && world.getBlock(x, y + 1, z) != Blocks.AIR) return false
            if (block == Blocks.TOILET) sound("splash", cx, cy, cz)
            kitchen.seat = intArrayOf(x, y, z)
            player.x = cx; player.z = cz; player.y = y + 0.05f
            player.vx = 0f; player.vy = 0f; player.vz = 0f
            say("Sitting down. Move or jump to stand up")
            return true
        }
    }
    return false
}

private fun Game.busy(x: Int, y: Int, z: Int): Boolean {
    val j = kitchen.jobs.firstOrNull { it.x == x && it.y == y && it.z == z } ?: return false
    say("Still busy… ${kotlin.math.ceil(j.left).toInt()} s")
    return true
}

private fun Game.startCooking(x: Int, y: Int, z: Int, block: Int, sel: Int): Boolean {
    if (busy(x, y, z)) return true
    val (result, count) = Kitchen.recipe(block, sel) ?: run {
        say(when (block) {
            Blocks.PRESSURE_COOKER -> "Put in rice, lentils or raw chicken"
            Blocks.TAWA -> "Put dough (roti), dosa batter or an egg on the tawa"
            Blocks.FRYING_PAN -> "Fry an egg, potatoes, meat or fish"
            Blocks.KETTLE -> "Put tea leaves (chai) or cocoa beans (hot chocolate) in the kettle"
            Blocks.MICROWAVE -> "Warm up raw meat, fish or potatoes"
            Blocks.OVEN -> "Bake dough (bread), a raw pizza, meat or potatoes"
            Blocks.TOASTER -> "Put in bread to make toast"
            Blocks.MIXER -> "Grind wheat, rice or cocoa, or make juice from apples, melon, carrots or sugar cane"
            else -> "Put in coloured wool, carpets or beds to wash them white"
        })
        return true
    }
    // The washing machine takes the whole stack; everything else one at a time.
    val n = if (block == Blocks.WASHING_MACHINE) (heldStack()?.count ?: 1) else 1
    consumeHeld(n)
    kitchen.jobs.add(KitchenJob(x, y, z, block, result, count * n, Kitchen.seconds(block)))
    setBlock(x, y, z, block, world.getMeta(x, y, z) or 8)
    if (Blocks.isStovePot(block)) world.getMeta(x, y - 1, z).let { if (it and 8 == 0) setBlock(x, y - 1, z, Blocks.GAS_STOVE, it or 8) }
    val cx = x + 0.5f; val cy = y + 0.5f; val cz = z + 0.5f
    when (block) {
        Blocks.MIXER -> sound("mixer", cx, cy, cz)
        Blocks.WASHING_MACHINE -> sound("mixer", cx, cy, cz, 0.4f)
        Blocks.FRYING_PAN, Blocks.TAWA -> sound("fuse", cx, cy, cz, 0.4f)
        else -> sound("click", cx, cy, cz)
    }
    say("${Items.displayName(result)} in ${Kitchen.seconds(block).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }} s…")
    return true
}

/** Cooking timers, and keeping a sitting player in the seat. */
internal fun Game.updateKitchen(dt: Float) {
    val it = kitchen.jobs.iterator()
    while (it.hasNext()) {
        val j = it.next()
        val cx = j.x + 0.5f; val cy = j.y + 0.5f; val cz = j.z + 0.5f
        val here = world.getBlock(j.x, j.y, j.z)
        val stoveGone = Blocks.isStovePot(j.block) && world.getBlock(j.x, j.y - 1, j.z) != Blocks.GAS_STOVE
        if (here != j.block || stoveGone) { it.remove(); continue }
        j.left -= dt
        if (j.block == Blocks.PRESSURE_COOKER) {
            j.whistle -= dt
            if (j.whistle <= 0f && j.whistles < 3) { j.whistle = 2.5f; j.whistles++; sound("whistle", cx, cy + 0.5f, cz) }
        }
        if (j.left > 0f) continue
        it.remove()
        drops.spawn(ItemStack(j.result, j.count), cx, j.y + 1.05f, cz)
        when (j.block) {
            Blocks.KETTLE -> sound("whistle", cx, cy, cz, 0.7f)
            Blocks.TOASTER -> sound("pop", cx, cy, cz)
            Blocks.MICROWAVE, Blocks.WASHING_MACHINE -> sound("beep", cx, cy, cz)
            Blocks.OVEN, Blocks.FRIDGE -> sound("ding", cx, cy, cz)
            Blocks.PRESSURE_COOKER -> {}
            else -> sound("pop", cx, cy, cz, 0.6f)
        }
        say("${Items.displayName(j.result)} is ready!")
        if (j.block != Blocks.FRIDGE) setBlock(j.x, j.y, j.z, j.block, world.getMeta(j.x, j.y, j.z) and 8.inv())
        // The flame goes out when nothing else is cooking on this stove.
        if (Blocks.isStovePot(j.block)) {
            val sm = world.getMeta(j.x, j.y - 1, j.z)
            if (sm and 8 != 0) setBlock(j.x, j.y - 1, j.z, Blocks.GAS_STOVE, sm and 8.inv())
        }
    }
    val s = kitchen.seat ?: return
    if (!Blocks.isSeat(world.getBlock(s[0], s[1], s[2])) || input.moveForward != 0f || input.moveStrafe != 0f || input.jumpHeld) {
        standUp(); return
    }
    player.x = s[0] + 0.5f; player.z = s[2] + 0.5f; player.y = s[1] + 0.05f
    player.vx = 0f; player.vy = 0f; player.vz = 0f
}

internal fun Game.standUp() {
    val s = kitchen.seat ?: return
    kitchen.seat = null
    player.y = s[1] + 0.6f
}

/** A seat is drawn and collides like a block, so the sitting player skips normal movement. */
internal val Game.sitting get() = kitchen.seat != null

/** The upper half of a two-block fridge or wardrobe. */
internal fun Game.placeTallTop(id: Int, x: Int, y: Int, z: Int, meta: Int): Boolean {
    if (world.getBlock(x, y + 1, z) != Blocks.AIR) return false
    setBlock(x, y + 1, z, id, meta or Shapes.UPPER)
    return true
}
