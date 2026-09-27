package com.vishucraft.game.engine

import com.vishucraft.game.render.ChunkMesher
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.ChestEntity
import com.vishucraft.game.world.ItemDef
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.ItemUse
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.ToolType
import java.util.Random

/*
 * Pack 3: the work blocks (stonecutter, grindstone, smithing table, loom...), dispensers and droppers,
 * note blocks, planting berries / kelp / lily pads, spawn eggs, golems and the new animals' interactions.
 */

private val rnd = Random()

private fun Game.toast(text: String) { uiEvents.add("toast:$text") }

/** Stonecutter: one block in, its cut form out (more pieces for slabs). */
private val STONECUTTER = mapOf(
    Blocks.STONE to (Blocks.STONE_BRICKS to 1), Blocks.STONE_BRICKS to (Blocks.CHISELED_STONE_BRICKS to 1),
    Blocks.COBBLESTONE to (Blocks.COBBLESTONE_SLAB to 2), Blocks.SMOOTH_STONE to (Blocks.STONE_SLAB to 2),
    Blocks.BRICKS to (Blocks.BRICK_SLAB to 2), Blocks.SANDSTONE to (Blocks.SMOOTH_SANDSTONE to 1),
    Blocks.SMOOTH_SANDSTONE to (Blocks.SANDSTONE_SLAB to 2), Blocks.GRANITE to (Blocks.POLISHED_GRANITE to 1),
    Blocks.DIORITE to (Blocks.POLISHED_DIORITE to 1), Blocks.ANDESITE to (Blocks.POLISHED_ANDESITE to 1),
    Blocks.COBBLED_DEEPSLATE to (Blocks.POLISHED_DEEPSLATE to 1), Blocks.POLISHED_DEEPSLATE to (Blocks.DEEPSLATE_BRICKS to 1),
    Blocks.DEEPSLATE_BRICKS to (Blocks.DEEPSLATE_TILES to 1), Blocks.COPPER_BLOCK to (Blocks.CUT_COPPER to 4),
    Blocks.PRISMARINE to (Blocks.PRISMARINE_BRICKS to 1), Blocks.MUD_BRICKS to (Blocks.MUD_BRICKS to 1),
    Blocks.QUARTZ_BLOCK to (Blocks.QUARTZ_BLOCK to 1), Blocks.OAK_SLAB to (Blocks.OAK_SLAB to 1),
)

/** Takes one of the held item. */
private fun Game.takeHeld(): Boolean {
    val s = heldStack() ?: return false
    s.count--
    if (s.count <= 0) inventory.slots[input.selectedSlot] = null
    return true
}

/** Gives an item (drops it if the inventory is full). */
internal fun Game.give(id: Int, n: Int = 1) {
    val left = inventory.add(id, n)
    if (left > 0) drops.spawn(ItemStack(id, left), player.x, player.y + 1f, player.z)
    uiEvents.add("place")
}

/** Spawns a creature standing at (x, y, z). */
internal fun Game.spawnMob(type: MobType, x: Float, y: Float, z: Float): Mob {
    val m = Mob(type, x, y, z).also { it.yaw = rnd.nextFloat() * 6.28f }
    mobs.list.add(m)
    return m
}

/**
 * Early uses (before eating / attacking): planting berries, kelp and lily pads, spawn eggs and the goat horn.
 * Returns true when the tap was used.
 */
internal fun Game.pack3Early(t: RayHit?, item: ItemDef?, sel: Int): Boolean {
    if (item?.use == ItemUse.HORN) {
        if (hornCooldown > clockSeconds) return true
        hornCooldown = clockSeconds + 5f
        sound("goat_horn", player.x, player.eyeY, player.z, 1.4f)
        return true
    }
    if (item?.name == "Sweet Berries" && t != null && t.ny == 1 &&
        (t.block == Blocks.GRASS || t.block == Blocks.DIRT || t.block == Blocks.PODZOL || t.block == Blocks.COARSE_DIRT) &&
        world.getBlock(t.x, t.y + 1, t.z) == Blocks.AIR) {
        setBlock(t.x, t.y + 1, t.z, Blocks.BERRY_BUSH, 0)
        if (survival) takeHeld()
        blockSound(Blocks.GRASS, t.x, t.y + 1, t.z, 0.7f)
        return true
    }
    if (item?.use == ItemUse.SPAWN && t != null) {
        val type = Items.spawnType(sel) ?: return false
        val x = t.x + t.nx; val y = t.y + t.ny; val z = t.z + t.nz
        spawnMob(type, x + 0.5f, y.toFloat(), z + 0.5f)
        if (item.name.endsWith("Bucket")) {
            if (survival) { takeHeld(); give(Items.find("Bucket")) }
        } else if (survival) takeHeld()
        sound("pop", x + 0.5f, y + 0.5f, z + 0.5f)
        return true
    }
    // Water plants and lily pads are placed on / in water.
    if (sel == Blocks.LILY_PAD || sel == Blocks.KELP || sel == Blocks.SEAGRASS) {
        val w = Raycast.cast(world, player.x, player.eyeY, player.z, dir[0], dir[1], dir[2], Game.REACH, hitWater = true) ?: return true
        if (sel == Blocks.LILY_PAD) {
            if (w.block == Blocks.WATER && world.getBlock(w.x, w.y + 1, w.z) == Blocks.AIR) {
                setBlock(w.x, w.y + 1, w.z, Blocks.LILY_PAD, 0)
                if (survival) takeHeld()
            } else toast("Lily pads go on top of water")
            return true
        }
        // Kelp and seagrass: aim at the water just above the sea floor (or a kelp top).
        val x = if (w.block == Blocks.WATER) w.x else w.x + w.nx
        val y = if (w.block == Blocks.WATER) w.y else w.y + w.ny
        val z = if (w.block == Blocks.WATER) w.z else w.z + w.nz
        val below = world.getBlock(x, y - 1, z)
        if (world.getBlock(x, y, z) == Blocks.WATER && (Blocks.solid[below] || (sel == Blocks.KELP && below == Blocks.KELP))) {
            setBlock(x, y, z, sel, 0)
            if (survival) takeHeld()
        } else toast("Plant it under water, on the sea floor")
        return true
    }
    return false
}

/** Uses of the pack 3 blocks under the crosshair. Returns true when handled. */
internal fun Game.pack3Block(t: RayHit, item: ItemDef?, sel: Int): Boolean {
    val x = t.x; val y = t.y; val z = t.z
    val meta = world.getMeta(x, y, z)
    when (t.block) {
        Blocks.DISPENSER, Blocks.DROPPER -> {
            world.blockEntities.chest(x, y, z); net?.openContainer(x, y, z, 0); uiEvents.add("open:chest:$x,$y,$z"); return true
        }
        Blocks.NOTE_BLOCK -> {
            if (item != null) return false
            val pitch = ((meta and 31) + 1) % 25
            setBlock(x, y, z, Blocks.NOTE_BLOCK, (meta and 31.inv()) or pitch)
            playNote(x, y, z)
            toast("Note ${pitch + 1} of 25")
            return true
        }
        Blocks.BERRY_BUSH -> {
            if (item?.use == ItemUse.GROW) return false
            if (meta < 2) return item == null
            give(Items.find("Sweet Berries"), meta - 1 + rnd.nextInt(2))
            setBlock(x, y, z, Blocks.BERRY_BUSH, 1)
            sound("hit_grass", x + 0.5f, y + 0.5f, z + 0.5f)
            return true
        }
        Blocks.BEE_NEST -> {
            val honey = meta shr 3
            val name = item?.name
            if (name != "Glass Bottle" && name != "Shears") return false
            if (honey < 5) { toast("The nest isn't full of honey yet ($honey/5)"); return true }
            setBlock(x, y, z, Blocks.BEE_NEST, meta and 7)
            if (name == "Glass Bottle") { takeHeld(); give(Items.find("Honey Bottle")) }
            else { give(Items.find("Honeycomb"), 3); damageHeld(1) }
            // The bees don't like that.
            for (m in mobs.list) if (m.type == MobType.BEE && !m.dead && (m.x - x) * (m.x - x) + (m.z - z) * (m.z - z) < 100f) m.angry = true
            sound("bee", x + 0.5f, y + 0.5f, z + 0.5f)
            return true
        }
        Blocks.EXPOSED_COPPER, Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER -> {
            if (item?.tool != ToolType.AXE) return false
            setBlock(x, y, z, when (t.block) { Blocks.OXIDIZED_COPPER -> Blocks.WEATHERED_COPPER; Blocks.WEATHERED_COPPER -> Blocks.EXPOSED_COPPER; else -> Blocks.COPPER_BLOCK }, 0)
            damageHeld(1)
            sound("hit_stone", x + 0.5f, y + 0.5f, z + 0.5f)
            return true
        }
        Blocks.STONECUTTER -> {
            val (out, n) = STONECUTTER[sel]?.takeIf { it.first != sel } ?: run { toast("Hold stone, bricks, deepslate or copper to cut it"); return true }
            if (survival) takeHeld()
            give(out, n)
            sound("hit_stone", x + 0.5f, y + 0.5f, z + 0.5f)
            toast("Cut into ${Items.displayName(out)}" + if (n > 1) " ×$n" else "")
            return true
        }
        Blocks.GRINDSTONE -> {
            val name = item?.name
            val plain = name?.takeIf { it.startsWith("Enchanted ") && item.maxStack == 1 }?.removePrefix("Enchanted ")
            val plainId = plain?.let { runCatching { Items.find(it) }.getOrNull() }
            if (plainId == null) { toast("Hold an enchanted tool or armor to grind off its enchantments"); return true }
            inventory.slots[input.selectedSlot] = ItemStack(plainId, 1)
            addXp(5 + rnd.nextInt(5))
            sound("break_tool", x + 0.5f, y + 0.5f, z + 0.5f, 0.5f)
            toast("Enchantments removed (you got some experience)")
            return true
        }
        Blocks.SMITHING_TABLE -> {
            val name = item?.name
            val up = name?.takeIf { it.contains("Diamond ") && item.maxStack == 1 }?.replace("Diamond ", "Netherite ")
            val upId = up?.let { runCatching { Items.find(it) }.getOrNull() }
            if (upId == null) { toast("Hold diamond gear and have a netherite ingot to upgrade it"); return true }
            val ingot = Items.find("Netherite Ingot")
            if (survival && !inventory.remove(ingot, 1)) { toast("You need a netherite ingot"); return true }
            inventory.slots[input.selectedSlot] = ItemStack(upId, 1)
            sound("craft", x + 0.5f, y + 0.5f, z + 0.5f)
            toast("Upgraded to $up!")
            return true
        }
        Blocks.LOOM -> {
            val first = if (Blocks.isBanner(sel)) Blocks.BANNER_FIRST else if (Blocks.isCarpet(sel)) Blocks.CARPET_FIRST else -1
            if (first < 0) { toast("Hold a banner or carpet, with a dye in your inventory"); return true }
            val current = sel - first
            val dyeIdx = Blocks.DYES.indices.firstOrNull { c -> c != current && inventory.count(dyeItem(c)) > 0 }
            if (dyeIdx == null) { toast("You need a dye of another colour"); return true }
            inventory.remove(dyeItem(dyeIdx), 1)
            heldStack()?.let { inventory.slots[input.selectedSlot] = ItemStack(first + dyeIdx, it.count) }
            sound("hit_wool", x + 0.5f, y + 0.5f, z + 0.5f)
            return true
        }
        Blocks.CARTOGRAPHY_TABLE -> {
            if (item?.name != "Paper") { toast("Hold paper to make a map"); return true }
            if (survival) takeHeld()
            give(Items.find("Empty Map"))
            return true
        }
        Blocks.FLETCHING_TABLE -> {
            val stick = Items.find("Stick"); val feather = Items.find("Feather"); val flint = Items.find("Flint")
            if (survival && (inventory.count(stick) < 1 || inventory.count(feather) < 1 || inventory.count(flint) < 1)) {
                toast("Arrows need a flint, a stick and a feather"); return true
            }
            if (survival) { inventory.remove(flint, 1); inventory.remove(stick, 1); inventory.remove(feather, 1) }
            give(Items.find("Arrow"), 6)
            toast("Made 6 arrows")
            return true
        }
        Blocks.LECTERN -> {
            sound("hit_wood", x + 0.5f, y + 0.5f, z + 0.5f, 0.6f)
            toast(if (item?.name == "Book and Quill" || item?.name == "Book") "You flip through the pages…" else "Put a book on the lectern to read it")
            return true
        }
    }
    return false
}

private fun dyeItem(c: Int) = Items.find("${Blocks.DYES[c].split('_').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }} Dye")

internal fun Game.playNote(x: Int, y: Int, z: Int) {
    val pitch = world.getMeta(x, y, z) and 31
    sound("note_${pitch.coerceIn(0, 24)}", x + 0.5f, y + 1f, z + 0.5f)
}

/** After placing a pumpkin: build a snow golem (2 snow blocks) or an iron golem (T of iron blocks). */
internal fun Game.checkGolem(x: Int, y: Int, z: Int) {
    val head = world.getBlock(x, y, z)
    if (head != Blocks.PUMPKIN && head != Blocks.JACK_O_LANTERN) return
    if (world.getBlock(x, y - 1, z) == Blocks.SNOW && world.getBlock(x, y - 2, z) == Blocks.SNOW) {
        for (dy in 0..2) setBlock(x, y - dy, z, Blocks.AIR)
        spawnMob(MobType.SNOW_GOLEM, x + 0.5f, (y - 2).toFloat(), z + 0.5f).tamed = true
        toast("A snow golem came to life!")
        return
    }
    if (world.getBlock(x, y - 1, z) != Blocks.IRON_BLOCK || world.getBlock(x, y - 2, z) != Blocks.IRON_BLOCK) return
    for ((ax, az) in listOf(1 to 0, 0 to 1)) {
        if (world.getBlock(x + ax, y - 1, z + az) == Blocks.IRON_BLOCK && world.getBlock(x - ax, y - 1, z - az) == Blocks.IRON_BLOCK) {
            for (dy in 0..2) setBlock(x, y - dy, z, Blocks.AIR)
            setBlock(x + ax, y - 1, z + az, Blocks.AIR); setBlock(x - ax, y - 1, z - az, Blocks.AIR)
            spawnMob(MobType.IRON_GOLEM, x + 0.5f, (y - 2).toFloat(), z + 0.5f).tamed = true
            toast("An iron golem came to life!")
            return
        }
    }
}

/** A dispenser or dropper got a redstone pulse, or a note block. */
internal fun Game.activated(x: Int, y: Int, z: Int, id: Int) {
    if (id == Blocks.NOTE_BLOCK) { playNote(x, y, z); return }
    val e = world.blockEntities.chest(x, y, z) ?: return
    val slot = e.slots.indices.filter { e.slots[it] != null }.let { if (it.isEmpty()) -1 else it[rnd.nextInt(it.size)] }
    sound("click", x + 0.5f, y + 0.5f, z + 0.5f)
    if (slot < 0) return
    val stack = e.slots[slot]!!
    val f = (world.getMeta(x, y, z) and 7).coerceIn(0, 5)
    val n = ChunkMesher.NORMALS[f]
    val fx = x + n[0]; val fy = y + n[1]; val fz = z + n[2]
    val sx = x + 0.5f + n[0] * 0.7f; val sy = y + 0.5f + n[1] * 0.7f; val sz = z + 0.5f + n[2] * 0.7f
    fun useOne() { stack.count--; if (stack.count <= 0) e.slots[slot] = null }
    if (id == Blocks.DROPPER) {
        val target = world.blockEntities.get(fx, fy, fz)
        if (target is ChestEntity) { if (target.insert(stack.id, 1, stack.damage) == 0) useOne(); return }
        drops.toss(ItemStack(stack.id, 1, stack.damage), sx, sy, sz, n[0] * 0.6f, n[1] * 0.6f + 0.1f, n[2] * 0.6f)
        useOne(); return
    }
    val name = Items[stack.id]?.name
    val speed = 22f
    when {
        name == "Arrow" -> { projectiles.shoot(sx, sy, sz, n[0] * speed, n[1] * speed + 1f, n[2] * speed, false, 5f, Projectile.ARROW); useOne() }
        name == "Snowball" || name == "Egg" -> {
            projectiles.shoot(sx, sy, sz, n[0] * speed, n[1] * speed + 1f, n[2] * speed, false, 0f, if (name == "Egg") Projectile.EGG else Projectile.SNOWBALL); useOne()
        }
        name == "Fire Charge" -> { projectiles.shoot(sx, sy, sz, n[0] * 16f, n[1] * 16f, n[2] * 16f, false, 5f, Projectile.FIREBALL); useOne() }
        name == "Water Bucket" || name == "Lava Bucket" -> if (world.getBlock(fx, fy, fz) == Blocks.AIR) {
            setBlock(fx, fy, fz, if (name == "Water Bucket") Blocks.WATER else Blocks.LAVA, 0)
            e.slots[slot] = ItemStack(Items.find("Bucket"), 1)
        }
        name == "Bucket" -> if (Blocks.isLiquid(world.getBlock(fx, fy, fz)) && world.getMeta(fx, fy, fz) == 0) {
            val full = Items.find(if (world.getBlock(fx, fy, fz) == Blocks.LAVA) "Lava Bucket" else "Water Bucket")
            setBlock(fx, fy, fz, Blocks.AIR, 0)
            useOne(); if (e.insert(full, 1) > 0) drops.spawn(ItemStack(full), sx, sy, sz)
        }
        name == "Bone Meal" -> if (nature.boneMeal(fx, fy, fz)) useOne()
        stack.id == Blocks.TNT -> if (world.getBlock(fx, fy, fz) == Blocks.AIR) { setBlock(fx, fy, fz, Blocks.TNT, 0); redstone.prime(fx, fy, fz); useOne() }
        Items[stack.id]?.use == ItemUse.SPAWN -> { Items.spawnType(stack.id)?.let { spawnMob(it, fx + 0.5f, fy.toFloat(), fz + 0.5f) }; useOne() }
        else -> { drops.toss(ItemStack(stack.id, 1, stack.damage), sx, sy, sz, n[0] * 0.6f, n[1] * 0.6f + 0.1f, n[2] * 0.6f); useOne() }
    }
}

/** Interactions with the new animals. Returns true when used. */
internal fun Game.pack3Mob(item: ItemDef?, mob: Mob): Boolean {
    val name = item?.name
    when (mob.type) {
        MobType.GOAT -> if (name == "Bucket") { takeHeld(); give(Items.find("Milk Bucket")); sound("goat", mob.x, mob.y + 1f, mob.z); return true }
        MobType.MOOSHROOM -> {
            if (name == "Bucket") { takeHeld(); give(Items.find("Milk Bucket")); return true }
            if (name == "Bowl") { takeHeld(); give(Items.find("Mushroom Stew")); sound("eat", mob.x, mob.y + 1f, mob.z, 0.5f); return true }
            if (item?.use == ItemUse.SHEAR) {
                mobs.list.remove(mob)
                spawnMob(MobType.COW, mob.x, mob.y, mob.z).also { it.yaw = mob.yaw; it.customName = mob.customName }
                drops.spawn(ItemStack(Blocks.RED_MUSHROOM, 5), mob.x, mob.y + 1f, mob.z)
                damageHeld(1)
                return true
            }
        }
        MobType.AXOLOTL, MobType.COD -> if (name == "Water Bucket") {
            mobs.list.remove(mob)
            takeHeld(); give(Items.find(if (mob.type == MobType.AXOLOTL) "Axolotl Bucket" else "Cod Bucket"))
            sound("splash", mob.x, mob.y, mob.z, 0.5f)
            return true
        }
        MobType.PARROT, MobType.FOX -> {
            val food = if (mob.type == MobType.PARROT) name == "Wheat Seeds" else name == "Sweet Berries"
            if (food && !mob.tamed) {
                takeHeld()
                if (rnd.nextInt(3) == 0) {
                    mob.tamed = true; mob.sitting = false; award("tame")
                    toast("The ${mob.type.displayName.lowercase()} trusts you now! Tap it to make it sit or follow")
                } else toast("The ${mob.type.displayName.lowercase()} isn't sure yet… try again")
                return true
            }
            if (mob.tamed && !food) { mob.sitting = !mob.sitting; toast(if (mob.sitting) "Stay!" else "Come on!"); return true }
        }
        MobType.IRON_GOLEM -> if (name == "Iron Ingot" && mob.health < mob.type.maxHealth) {
            takeHeld(); mob.health = minOf(mob.type.maxHealth.toFloat(), mob.health + 25f)
            sound("hit_stone", mob.x, mob.y + 1f, mob.z); return true
        }
        else -> {}
    }
    return false
}

/** Loot from the new animals. */
internal fun newMobDrops(m: Mob): List<Pair<Int, Int>> {
    val r = rnd
    fun i(n: String) = Items.find(n)
    return when (m.type) {
        MobType.IRON_GOLEM -> listOf(i("Iron Ingot") to 3 + r.nextInt(3), Blocks.FLOWER_RED to r.nextInt(3))
        MobType.SNOW_GOLEM -> listOf(i("Snowball") to r.nextInt(16))
        MobType.FOX -> listOf(i("Sweet Berries") to (if (r.nextInt(4) == 0) 1 else 0))
        MobType.TURTLE -> listOf(Blocks.SEAGRASS to r.nextInt(3), i("Turtle Scute") to (if (r.nextInt(4) == 0) 1 else 0))
        MobType.GOAT -> listOf(i("Goat Horn") to (if (r.nextInt(6) == 0) 1 else 0))
        MobType.PARROT -> listOf(i("Feather") to 1 + r.nextInt(2))
        MobType.PANDA -> listOf(Blocks.BAMBOO to r.nextInt(2))
        MobType.POLAR_BEAR -> listOf(i("Raw Cod") to r.nextInt(3), i("Raw Salmon") to r.nextInt(3))
        MobType.LLAMA -> listOf(i("Leather") to r.nextInt(3))
        MobType.MOOSHROOM -> listOf(i("Leather") to r.nextInt(3), i("Raw Beef") to 1 + r.nextInt(3))
        MobType.FROG -> listOf(i("Slimeball") to (if (r.nextInt(5) == 0) 1 else 0))
        else -> emptyList()
    }
}
