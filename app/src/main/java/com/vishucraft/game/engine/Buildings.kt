package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Shapes

/*
 * Ready-made buildings: a blueprint builds a whole house, farm, tower, metro station or pool in front of
 * the player, with its door facing them.
 */

/**
 * Places blocks in building coordinates: [a] across (to the player's right), [d] away from the player,
 * [h] up from the ground. Faces are given relative to the player too.
 */
private class Plan(val g: Game, val ox: Int, val oy: Int, val oz: Int, val fx: Int, val fz: Int) {
    val rx = -fz; val rz = fx
    /** Face index pointing back at the player, away from them, and to their left and right. */
    val toward = when { fx == 1 -> 5; fx == -1 -> 4; fz == 1 -> 3; else -> 2 }
    val away = toward xor 1
    val right = when { rx == 1 -> 4; rx == -1 -> 5; rz == 1 -> 2; else -> 3 }
    val left = right xor 1
    /** Rails running across (to the right) or away from the player. */
    val railAcross = if (rx != 0) 1 else 0

    fun x(a: Int, d: Int) = ox + rx * a + fx * d
    fun z(a: Int, d: Int) = oz + rz * a + fz * d

    fun set(a: Int, d: Int, h: Int, id: Int, meta: Int = 0) {
        val y = oy + h
        if (y < 1 || y >= Chunk.HEIGHT) return
        g.setBlock(x(a, d), y, z(a, d), id, meta)
    }

    /** Clears the space above the footprint and fills any gap underneath so it stands on solid ground. */
    fun site(a0: Int, a1: Int, d0: Int, d1: Int, height: Int, floor: Int) {
        for (a in a0..a1) for (d in d0..d1) {
            for (h in 0..height) set(a, d, h, Blocks.AIR)
            set(a, d, -1, floor)
            var h = -2
            while (h > -8 && !Blocks.solid[g.world.getBlock(x(a, d), oy + h, z(a, d))]) { set(a, d, h, Blocks.DIRT); h-- }
        }
    }

    fun door(a: Int, d: Int, id: Int = Blocks.OAK_DOOR) { set(a, d, 0, id, toward); set(a, d, 1, id, toward or Shapes.UPPER) }

    /** A bed with its head [headDir] (a face index) from the foot at (a, d). */
    fun bed(a: Int, d: Int, da: Int, dd: Int, headDir: Int, color: Int = 14) {
        set(a, d, 0, Blocks.BED_FIRST + color, headDir)
        set(a + da, d + dd, 0, Blocks.BED_FIRST + color, headDir or Shapes.UPPER)
    }
}

internal fun Game.placeBuilding(t: RayHit, name: String): Boolean {
    // Build on top of the tapped block, starting one step in front of it.
    val fx0 = kotlin.math.sin(player.yaw); val fz0 = -kotlin.math.cos(player.yaw)
    val (fx, fz) = if (kotlin.math.abs(fx0) > kotlin.math.abs(fz0)) (if (fx0 > 0) 1 to 0 else -1 to 0) else (if (fz0 > 0) 0 to 1 else 0 to -1)
    val oy = if (t.ny == 1) t.y + 1 else t.y
    if (oy + 16 >= Chunk.HEIGHT) { uiEvents.add("toast:No room to build here"); return false }
    val p = Plan(this, t.x, oy, t.z, fx, fz)
    when (name) {
        "Small House" -> smallHouse(p)
        "Modern House" -> modernHouse(p)
        "Farm" -> farm(p)
        "Watch Tower" -> watchTower(p)
        "Metro Station" -> metroStation(p)
        "Swimming Pool" -> pool(p)
        else -> return false
    }
    sound("ding", player.x, player.y, player.z)
    uiEvents.add("toast:Built a ${name.lowercase()}!")
    return true
}

/** The name of the building on a blueprint item. */
internal fun blueprintOf(itemName: String?) = itemName?.removePrefix("Blueprint: ")?.takeIf { it in Items.BUILDINGS }

private fun Game.smallHouse(p: Plan) {
    p.site(-3, 3, 1, 7, 8, Blocks.COBBLESTONE)
    for (a in -3..3) for (d in 1..7) for (h in 0..3) {
        val edgeA = a == -3 || a == 3; val edgeD = d == 1 || d == 7
        if (!edgeA && !edgeD) continue
        p.set(a, d, h, if (edgeA && edgeD) Blocks.LOG else Blocks.PLANKS)
    }
    // Windows
    for (h in 1..2) { p.set(-3, 4, h, Blocks.GLASS_PANE); p.set(3, 4, h, Blocks.GLASS_PANE); p.set(0, 7, h, Blocks.GLASS_PANE); p.set(-2, 1, h, Blocks.GLASS_PANE); p.set(2, 1, h, Blocks.GLASS_PANE) }
    p.door(0, 1)
    // Stepped roof
    for (a in -3..3) for (d in 1..7) p.set(a, d, 4, Blocks.SPRUCE_PLANKS)
    for (a in -2..2) for (d in 2..6) p.set(a, d, 5, Blocks.SPRUCE_PLANKS)
    for (a in -1..1) for (d in 3..5) p.set(a, d, 6, Blocks.SPRUCE_PLANKS)
    // Inside
    p.bed(-2, 5, 0, 1, p.away)
    p.set(2, 6, 0, Blocks.CRAFTING_TABLE)
    p.set(2, 5, 0, Blocks.FURNACE, p.left)
    p.set(2, 4, 0, Blocks.CHEST, p.left)
    for (a in -1..1) for (d in 3..5) p.set(a, d, 0, Blocks.CARPET_FIRST + 14)
    p.set(-2, 2, 0, Blocks.TORCH); p.set(2, 2, 0, Blocks.TORCH)
    p.set(-2, 6, 3, Blocks.LANTERN); p.set(2, 2, 3, Blocks.LANTERN)
}

private fun Game.modernHouse(p: Plan) {
    val white = Blocks.CONCRETE_FIRST
    p.site(-5, 5, 1, 9, 6, Blocks.POLISHED_DIORITE)
    for (a in -5..5) for (d in 1..9) for (h in 0..3) {
        if (a != -5 && a != 5 && d != 1 && d != 9) continue
        val glass = h in 1..2 && ((d == 1 && (a in -4..-2 || a in 2..4)) || ((a == -5 || a == 5) && d in 3..7))
        p.set(a, d, h, if (glass) Blocks.GLASS else white)
    }
    p.door(0, 1)
    for (a in -5..5) for (d in 1..9) p.set(a, d, 4, Blocks.SMOOTH_STONE)
    p.set(-2, 5, 4, Blocks.SEA_LANTERN); p.set(3, 5, 4, Blocks.SEA_LANTERN)
    // Kitchen along the back wall, on the left.
    p.set(-4, 8, 0, Blocks.FRIDGE, p.toward); p.set(-4, 8, 1, Blocks.FRIDGE, p.toward or Shapes.UPPER)
    p.set(-3, 8, 0, Blocks.KITCHEN_COUNTER, p.toward); p.set(-3, 8, 1, Blocks.MICROWAVE, p.toward)
    p.set(-2, 8, 0, Blocks.GAS_STOVE, p.toward); p.set(-2, 8, 1, Blocks.PRESSURE_COOKER, p.toward)
    p.set(-1, 8, 0, Blocks.KITCHEN_SINK, p.toward)
    p.set(0, 8, 0, Blocks.KITCHEN_COUNTER, p.toward); p.set(0, 8, 1, Blocks.TOASTER, p.toward)
    p.set(-4, 7, 0, Blocks.GAS_STOVE, p.right); p.set(-4, 7, 1, Blocks.KETTLE, p.right)
    // Dining
    p.set(-3, 5, 0, Blocks.DINING_TABLE); p.set(-2, 5, 0, Blocks.DINING_TABLE)
    p.set(-3, 4, 0, Blocks.CHAIR, p.toward); p.set(-2, 4, 0, Blocks.CHAIR, p.toward)
    p.set(-3, 6, 0, Blocks.CHAIR, p.away); p.set(-2, 6, 0, Blocks.CHAIR, p.away)
    // Living room on the right: TV, sofa, lamp, fan and AC.
    p.set(3, 8, 0, Blocks.DINING_TABLE); p.set(3, 8, 1, Blocks.TV, p.toward)
    for (a in 2..4) p.set(a, 4, 0, Blocks.SOFA, p.away)
    p.set(4, 8, 0, Blocks.DINING_TABLE); p.set(4, 8, 1, Blocks.TABLE_LAMP)
    p.set(3, 6, 3, Blocks.CEILING_FAN)
    p.set(3, 8, 3, Blocks.AIR_CONDITIONER, p.toward)
    // Bedroom corner and the washing machine.
    p.bed(1, 6, 0, 1, p.away, 11)
    p.set(4, 2, 0, Blocks.WASHING_MACHINE, p.left)
    p.set(-4, 2, 0, Blocks.KITCHEN_COUNTER, p.right)
}

private fun Game.farm(p: Plan) {
    p.site(-5, 5, 1, 11, 3, Blocks.FARMLAND)
    val crops = intArrayOf(Blocks.WHEAT_CROP, Blocks.RICE_CROP, Blocks.LENTIL_CROP, Blocks.BEETROOT_CROP,
        Blocks.TOMATO_CROP, Blocks.CARROTS, Blocks.POTATOES, Blocks.WHEAT_CROP)
    val rnd = java.util.Random()
    for (a in -5..5) for (d in 1..11) {
        val edge = a == -5 || a == 5 || d == 1 || d == 11
        when {
            edge -> { p.set(a, d, -1, Blocks.GRASS); p.set(a, d, 0, if (a == 0 && d == 1) Blocks.OAK_FENCE_GATE else Blocks.OAK_FENCE, p.toward) }
            a == 0 && d == 6 -> { p.set(a, d, -1, Blocks.GRASS); p.set(a, d, 0, Blocks.SCARECROW, p.toward) }
            a == 0 -> p.set(a, d, -1, Blocks.WATER)
            (a == -3 || a == 3) && d == 6 -> p.set(a, d, 0, Blocks.SPRINKLER)
            else -> p.set(a, d, 0, crops[if (a < 0) a + 4 else a + 3], 3 + rnd.nextInt(5))
        }
    }
    p.set(-5, 0, 0, Blocks.CHEST, p.toward)
    p.set(-4, 0, 0, Blocks.COMPOSTER)
}

private fun Game.watchTower(p: Plan) {
    p.site(-3, 3, 1, 7, 15, Blocks.STONE_BRICKS)
    for (a in -2..2) for (d in 2..6) for (h in 0..11) {
        if (a != -2 && a != 2 && d != 2 && d != 6) continue
        val window = (h == 5 || h == 8) && (a == 0 || d == 4)
        p.set(a, d, h, if (window) Blocks.GLASS_PANE else if ((a == -2 || a == 2) && (d == 2 || d == 6)) Blocks.COBBLESTONE else Blocks.STONE_BRICKS)
    }
    p.door(0, 2)
    for (h in 0..12) p.set(0, 5, h, Blocks.LADDER, p.toward)
    // Lookout platform with a railing and lanterns.
    for (a in -3..3) for (d in 1..7) if (!(a == 0 && d == 5)) p.set(a, d, 12, Blocks.STONE_BRICKS)
    for (a in -3..3) for (d in 1..7) if (a == -3 || a == 3 || d == 1 || d == 7) {
        p.set(a, d, 13, if ((a == -3 || a == 3) && (d == 1 || d == 7)) Blocks.LANTERN else Blocks.OAK_FENCE)
    }
    p.set(0, 5, 13, Blocks.AIR)
}

private fun Game.metroStation(p: Plan) {
    val len = 24
    // Track bed and rails running across in front of the player, through the station.
    p.site(-len, len, 1, 7, 6, Blocks.RAILWAY_BALLAST)
    for (a in -len..len) p.set(a, 4, 0, Blocks.RAIL, p.railAcross)
    for (a in -8..8) {
        for (d in 1..3) p.set(a, d, 0, Blocks.STATION_PLATFORM)
        for (d in 5..7) p.set(a, d, 0, Blocks.STATION_PLATFORM)
        for (d in 1..7) p.set(a, d, 5, Blocks.GLASS)
        if (a % 4 == 0) for (h in 1..4) { p.set(a, 1, h, Blocks.QUARTZ_BLOCK); p.set(a, 7, h, Blocks.QUARTZ_BLOCK) }
        if (a % 4 == 2) { p.set(a, 1, 5, Blocks.SEA_LANTERN); p.set(a, 7, 5, Blocks.SEA_LANTERN) }
    }
    // The station's name board.
    p.set(0, 2, 1, Blocks.SIGN, p.toward)
    world.blockEntities.sign(p.x(0, 2), p.oy + 1, p.z(0, 2))?.text = "DV Metro"
    // And a metro train waiting at the platform.
    val metro = Cart(p.x(-2, 4) + 0.5f, p.oy.toFloat(), p.z(-2, 4) + 0.5f, Cart.METRO)
    metro.hx = p.rx.toFloat(); metro.hz = p.rz.toFloat(); metro.yaw = kotlin.math.atan2(metro.hx, -metro.hz)
    carts.list.add(metro)
    val coach = Cart(p.x(-5, 4) + 0.5f, p.oy.toFloat(), p.z(-5, 4) + 0.5f, Cart.COACH).also { it.leader = metro; it.hx = metro.hx; it.hz = metro.hz }
    carts.list.add(coach)
}

private fun Game.pool(p: Plan) {
    p.site(-4, 4, 1, 8, 3, Blocks.QUARTZ_BLOCK)
    for (a in -3..3) for (d in 2..7) {
        p.set(a, d, -1, Blocks.WATER); p.set(a, d, -2, Blocks.WATER)
        p.set(a, d, -3, if ((a == -3 || a == 3) && (d == 2 || d == 7)) Blocks.SEA_LANTERN else Blocks.CONCRETE_FIRST + 3)
    }
    for (a in -4..4) for (d in 1..8) if (a == -4 || a == 4 || d == 1 || d == 8) { p.set(a, d, -2, Blocks.QUARTZ_BLOCK); p.set(a, d, -3, Blocks.QUARTZ_BLOCK) }
    p.set(0, 1, 0, Blocks.OAK_SLAB)
    p.set(-4, 0, 0, Blocks.SOFA, p.toward); p.set(4, 0, 0, Blocks.SOFA, p.toward)
    p.set(-3, 0, 0, Blocks.LANTERN); p.set(3, 0, 0, Blocks.LANTERN)
}
