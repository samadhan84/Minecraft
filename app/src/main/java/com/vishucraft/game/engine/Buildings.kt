package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Shapes
import kotlin.math.abs

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
        val wx = x(a, d); val wz = z(a, d)
        // Big buildings reach past the loaded area: load (or generate) the ground there first.
        g.world.ensureLoaded(wx shr 4, wz shr 4)
        g.setBlock(wx, y, wz, id, meta)
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
    if (!buildReady(p, name)) return false
    sound("ding", player.x, player.y, player.z)
    uiEvents.add("toast:Built ${if (name[0].lowercaseChar() in "aeiou") "an" else "a"} ${name.lowercase()}!")
    return true
}

/** Builds one of the ready-made buildings with plan [p]. */
private fun Game.buildReady(p: Plan, name: String): Boolean {
    when (name) {
        "Small House" -> smallHouse(p)
        "Modern House" -> modernHouse(p)
        "Farm" -> farm(p)
        "Watch Tower" -> watchTower(p)
        "Metro Station" -> metroStation(p)
        "Swimming Pool" -> pool(p)
        "Mansion" -> mansion(p)
        "Airport" -> airport(p)
        else -> return false
    }
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
    for (a in -len..len) p.set(a, 4, 0, if (a == 0) Blocks.STOP_RAIL else Blocks.RAIL, p.railAcross)
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

/**
 * A two-storey luxury mansion: a front garden with fountains and a gate, a pillared porch, a grand hall with
 * a staircase, a full kitchen and dining room, a living room and library downstairs, bedrooms and a bathroom
 * upstairs, and a roof terrace with a pool.
 */
private fun Game.mansion(p: Plan) {
    val quartz = Blocks.QUARTZ_BLOCK; val white = Blocks.CONCRETE_FIRST; val gold = Blocks.GOLD_BLOCK
    val S = Shapes
    p.site(-11, 11, 1, 21, 14, Blocks.GRASS)

    // ---- Front garden: iron fence with a gate, a quartz path, two fountains and flower beds.
    for (a in -11..11) for (d in 1..21) {
        val edge = a == -11 || a == 11 || d == 1 || d == 21
        if (edge && !(d == 1 && a in -1..1)) p.set(a, d, 0, if ((a + d) % 4 == 0) quartz else Blocks.IRON_BARS)
    }
    for (a in intArrayOf(-11, -7, -3, 3, 7, 11)) { p.set(a, 1, 0, quartz); p.set(a, 1, 1, Blocks.LANTERN) }
    for (d in 1..5) for (a in -1..1) p.set(a, d, -1, quartz)
    for (sa in intArrayOf(-6, 6)) {
        for (a in sa - 1..sa + 1) for (d in 2..4) p.set(a, d, 0, if (a == sa && d == 3) Blocks.AIR else Blocks.STONE_BRICK_SLAB)
        // A little pond with a lily pad (water on top would run all over the garden).
        p.set(sa, 3, -1, Blocks.WATER); p.set(sa, 3, 0, Blocks.LILY_PAD)
    }
    val flowers = intArrayOf(Blocks.FLOWER_RED, Blocks.FLOWER_YELLOW, Blocks.FLOWER_FIRST + 1, Blocks.FLOWER_FIRST + 6)
    for (a in -10..10) for (d in 2..4) if (abs(a) in 3..4 || abs(a) in 8..10) p.set(a, d, 0, flowers[(a + 11 + d) % flowers.size])

    // ---- Porch with pillars.
    for (a in intArrayOf(-4, -2, 2, 4)) for (h in 0..4) p.set(a, 5, h, quartz)
    for (a in -5..5) for (d in 5..6) p.set(a, d, 5, quartz)
    p.set(-5, 5, 6, gold); p.set(5, 5, 6, gold)
    for (a in -1..1) p.set(a, 5, 0, Blocks.CARPET_FIRST + 14)

    // ---- The house: ground floor (floor at -1), upper floor (slab at 5), roof (10).
    val a0 = -9; val a1 = 9; val d0 = 6; val d1 = 20
    for (a in a0..a1) for (d in d0..d1) {
        p.set(a, d, -1, Blocks.POLISHED_DIORITE)
        p.set(a, d, 5, quartz)
        p.set(a, d, 10, if ((a % 4 == 0) && (d % 4 == 2)) Blocks.SEA_LANTERN else quartz)
        val edge = a == a0 || a == a1 || d == d0 || d == d1
        if (!edge) continue
        val column = (a == a0 || a == a1) && (d % 4 == 2) || (d == d0 || d == d1) && (a % 3 == 0) || ((a == a0 || a == a1) && (d == d0 || d == d1))
        for (h in 0..9) {
            if (h == 5) continue
            val window = !column && (h in 1..3 || h in 7..8)
            p.set(a, d, h, if (column) quartz else if (window) Blocks.GLASS else white)
        }
    }
    // Grand entrance: three doors under the porch.
    for (a in -1..1) p.door(a, d0)
    for (a in -1..1) p.set(a, d0, 2, Blocks.GLASS)
    // Gold trim on the roof corners and above the door.
    for ((a, d) in listOf(a0 to d0, a1 to d0, a0 to d1, a1 to d1)) { p.set(a, d, 11, gold); p.set(a, d, 12, Blocks.LANTERN) }
    p.set(0, d0, 11, Blocks.BANNER_FIRST + 4, p.toward)

    // ---- Grand staircase on the left, up to the upper floor.
    for (k in 0..5) for (a in -8..-7) {
        p.set(a, 7 + k, k, Blocks.STONE_BRICK_STAIRS, p.toward)
        for (h in 0 until k) p.set(a, 7 + k, h, quartz)
    }
    for (d in 7..11) for (a in -8..-7) p.set(a, d, 5, Blocks.AIR)
    for (d in 7..12) p.set(-6, d, 6, Blocks.OAK_FENCE)

    // ---- Downstairs: red carpet hall, chandeliers, living room, kitchen, dining, library.
    for (d in 7..13) for (a in -1..1) p.set(a, d, 0, Blocks.CARPET_FIRST + 14)
    for ((a, d) in listOf(-4 to 9, 4 to 9, 0 to 12, -4 to 16, 4 to 16)) p.set(a, d, 4, Blocks.CEILING_LIGHT, 8)
    // Living room (right, front): sofas round a coffee table, facing the TV on the right wall.
    for (d in 8..10) p.set(4, d, 0, Blocks.SOFA, p.right)
    p.set(6, 9, 0, Blocks.COFFEE_TABLE)
    p.set(8, 9, 0, Blocks.DINING_TABLE); p.set(8, 9, 1, Blocks.TV, p.left or 8)
    p.set(6, 7, 0, Blocks.ARMCHAIR, p.away); p.set(6, 11, 0, Blocks.ARMCHAIR, p.toward)
    p.set(8, 7, 0, Blocks.PLANT_POT); p.set(8, 12, 0, Blocks.PLANT_POT); p.set(2, 7, 0, Blocks.PLANT_POT)
    p.set(8, 11, 0, Blocks.TABLE_LAMP, 8)
    // Kitchen along the back wall, on the left.
    p.set(-8, 19, 0, Blocks.FRIDGE, p.toward); p.set(-8, 19, 1, Blocks.FRIDGE, p.toward or S.UPPER)
    p.set(-7, 19, 0, Blocks.KITCHEN_COUNTER, p.toward); p.set(-7, 19, 1, Blocks.MICROWAVE, p.toward)
    p.set(-6, 19, 0, Blocks.GAS_STOVE, p.toward); p.set(-6, 19, 1, Blocks.PRESSURE_COOKER, p.toward)
    p.set(-5, 19, 0, Blocks.KITCHEN_SINK, p.toward)
    p.set(-4, 19, 0, Blocks.KITCHEN_COUNTER, p.toward); p.set(-4, 19, 1, Blocks.TOASTER, p.toward)
    p.set(-3, 19, 0, Blocks.GAS_STOVE, p.toward); p.set(-3, 19, 1, Blocks.KETTLE, p.toward)
    p.set(-2, 19, 0, Blocks.KITCHEN_COUNTER, p.toward); p.set(-2, 19, 1, Blocks.MIXER, p.toward)
    p.set(-8, 17, 0, Blocks.OVEN, p.right); p.set(-8, 16, 0, Blocks.WATER_COOLER, p.right)
    // Long dining table with chairs on both sides.
    for (a in -6..-3) { p.set(a, 15, 0, Blocks.DINING_TABLE); p.set(a, 14, 0, Blocks.CHAIR, p.away); p.set(a, 16, 0, Blocks.CHAIR, p.toward) }
    // Library and lounge (right, back).
    for (a in 2..8) { p.set(a, 19, 0, Blocks.BOOKSHELF); p.set(a, 19, 1, Blocks.BOOKSHELF) }
    p.set(4, 17, 0, Blocks.BEAN_BAG, p.away); p.set(6, 17, 0, Blocks.BEAN_BAG, p.away)
    p.set(8, 15, 0, Blocks.WASHING_MACHINE, p.left)

    // ---- Upstairs: master bedroom, bathroom, kids' room and a study.
    // Master bedroom (right, front).
    p.bed(5, 10, 0, 1, p.away, 0)
    p.set(8, 7, 6, Blocks.WARDROBE, p.left); p.set(8, 7, 7, Blocks.WARDROBE, p.left or S.UPPER)
    p.set(8, 10, 6, Blocks.DRESSING_TABLE, p.left)
    p.set(4, 12, 6, Blocks.COFFEE_TABLE); p.set(4, 12, 7, Blocks.TABLE_LAMP, 8)
    p.set(6, 12, 6, Blocks.COFFEE_TABLE); p.set(6, 12, 7, Blocks.TABLE_LAMP, 8)
    p.set(5, 7, 8, Blocks.AIR_CONDITIONER, p.away or 8)
    p.set(5, 10, 9, Blocks.CEILING_FAN, 8)
    for (a in 3..7) for (d in 8..9) p.set(a, d, 6, Blocks.CARPET_FIRST + 11)
    // Bathroom (right, back) behind a wall with a door.
    for (a in 2..8) for (h in 6..9) p.set(a, 14, h, white)
    p.set(5, 14, 6, Blocks.OAK_DOOR, p.toward); p.set(5, 14, 7, Blocks.OAK_DOOR, p.toward or S.UPPER)
    p.set(7, 18, 6, Blocks.BATHTUB, p.left or 8)
    p.set(3, 19, 6, Blocks.TOILET, p.toward)
    p.set(5, 19, 6, Blocks.WASH_BASIN, p.toward); p.set(5, 19, 8, Blocks.MIRROR, p.toward)
    p.set(8, 16, 8, Blocks.SHOWER, p.left)
    p.set(2, 16, 9, Blocks.CEILING_LIGHT, 8)
    // Kids' room (left, back): two beds, a study table with a computer and a bean bag.
    for (a in -8..-2) for (h in 6..9) p.set(a, 14, h, white)
    p.set(-4, 14, 6, Blocks.OAK_DOOR, p.toward); p.set(-4, 14, 7, Blocks.OAK_DOOR, p.toward or S.UPPER)
    p.bed(-7, 17, 0, 1, p.away, 11)
    p.bed(-5, 17, 0, 1, p.away, 6)
    p.set(-2, 19, 6, Blocks.STUDY_TABLE, p.toward); p.set(-2, 19, 7, Blocks.COMPUTER, p.toward or 8)
    p.set(-3, 16, 6, Blocks.BEAN_BAG, p.away)
    p.set(-8, 15, 6, Blocks.WARDROBE, p.right); p.set(-8, 15, 7, Blocks.WARDROBE, p.right or S.UPPER)
    p.set(-5, 16, 9, Blocks.CEILING_LIGHT, 8)
    // Study at the top of the stairs.
    p.set(-3, 8, 6, Blocks.STUDY_TABLE, p.away); p.set(-3, 8, 7, Blocks.COMPUTER, p.away or 8)
    p.set(-3, 10, 6, Blocks.ARMCHAIR, p.toward)
    p.set(-2, 12, 6, Blocks.PLANT_POT)
    p.set(0, 10, 9, Blocks.CEILING_LIGHT, 8)
    p.set(-1, 7, 8, Blocks.WALL_CLOCK, p.away)

    // ---- Roof terrace: a pool, sun loungers, glass railing, and a ladder up from the upper floor.
    for (a in a0..a1) for (d in d0..d1) {
        val corner = (a == a0 || a == a1) && (d == d0 || d == d1)
        if ((a == a0 || a == a1 || d == d0 || d == d1) && !corner && !(a == 0 && d == d0)) p.set(a, d, 11, Blocks.GLASS_PANE)
    }
    for (a in -4..4) for (d in 9..15) {
        val rim = abs(a) == 4 || d == 9 || d == 15
        p.set(a, d, 11, if (rim) quartz else Blocks.WATER)
        if (!rim) p.set(a, d, 10, Blocks.CONCRETE_FIRST + 3)
    }
    for (a in intArrayOf(-3, -1, 1, 3)) p.set(a, 17, 11, Blocks.SOFA, p.toward)
    p.set(-7, 8, 11, Blocks.PLANT_POT); p.set(7, 8, 11, Blocks.PLANT_POT); p.set(-7, 18, 11, Blocks.PLANT_POT); p.set(7, 18, 11, Blocks.PLANT_POT)
    for (h in 6..10) p.set(0, 19, h, Blocks.LADDER, p.toward)
    // An elevator from the ground floor up to the bedrooms and the roof.
    for (h in intArrayOf(-1, 5, 10)) { p.set(8, 13, h, Blocks.ELEVATOR); for (y in h + 1..h + 2) p.set(8, 13, y, Blocks.AIR) }
}

/**
 * An airport: a long runway running away from the player, a terminal with seats and check-in desks, a control
 * tower and a helipad, with an airplane and a helicopter ready to go. Each airport gets a name; airplanes can
 * fly between any two of them.
 */
private fun Game.airport(p: Plan) {
    val white = Blocks.CONCRETE_FIRST
    val len = 90
    val name = "Airport ${aircraft.airports.size + 1}"
    // Runway with a centre line and edge lights.
    p.site(-5, 5, 1, len, 12, Blocks.RUNWAY)
    for (d in 1..len) {
        p.set(0, d, -1, if (d % 6 < 3) Blocks.RUNWAY_LINE else Blocks.RUNWAY)
        if (d % 8 == 0) { p.set(-5, d, -1, Blocks.SEA_LANTERN); p.set(5, d, -1, Blocks.SEA_LANTERN) }
    }
    for (a in -4..4) { p.set(a, 2, -1, Blocks.RUNWAY_LINE); p.set(a, len - 1, -1, Blocks.RUNWAY_LINE) }
    // Terminal on the right.
    p.site(7, 19, 3, 19, 7, Blocks.POLISHED_DIORITE)
    for (a in 7..19) for (d in 3..19) {
        p.set(a, d, 5, Blocks.SMOOTH_STONE)
        if (a == 7 || a == 19 || d == 3 || d == 19) for (h in 0..4) {
            val column = (a + d) % 4 == 0
            p.set(a, d, h, if (h in 1..3 && !column) Blocks.GLASS else white)
        }
    }
    for (d in 10..11) { p.set(7, d, 0, Blocks.OAK_DOOR, p.left); p.set(7, d, 1, Blocks.OAK_DOOR, p.left or Shapes.UPPER) }
    for (d in intArrayOf(5, 7, 13, 15, 17)) for (a in 10..16 step 2) p.set(a, d, 0, Blocks.SOFA, p.left)
    for (d in 5..8) p.set(18, d, 0, Blocks.KITCHEN_COUNTER, p.left)
    p.set(18, 9, 0, Blocks.COMPUTER, p.left or 8)
    p.set(18, 16, 0, Blocks.WATER_COOLER, p.left); p.set(18, 18, 0, Blocks.PLANT_POT); p.set(8, 18, 0, Blocks.PLANT_POT)
    for ((a, d) in listOf(11 to 6, 15 to 6, 11 to 15, 15 to 15, 13 to 11)) p.set(a, d, 4, Blocks.CEILING_LIGHT, 8)
    for (a in 8..18) p.set(a, 3, 6, if (a % 2 == 0) Blocks.CONCRETE_FIRST + 11 else white)
    p.set(6, 12, 0, Blocks.SIGN, p.left)
    world.blockEntities.sign(p.x(6, 12), p.oy, p.z(6, 12))?.text = name
    // Control tower.
    p.site(10, 16, 21, 27, 20, Blocks.SMOOTH_STONE)
    // A 5x5 shaft as wide as the glass cab on top (an overhang would shade it dark).
    for (h in 0..14) for (a in 11..15) for (d in 22..26) if (a == 11 || a == 15 || d == 22 || d == 26) p.set(a, d, h, if (h % 5 == 2 && (a == 13 || d == 24)) Blocks.GLASS else white)
    for (h in 0..15) p.set(14, 23, h, Blocks.LADDER, p.away)
    p.door(13, 22)
    for (a in 11..15) for (d in 22..26) {
        p.set(a, d, 15, Blocks.SMOOTH_STONE)
        val edge = a == 11 || a == 15 || d == 22 || d == 26
        for (h in 16..17) p.set(a, d, h, if (edge) Blocks.GLASS else Blocks.AIR)
        p.set(a, d, 18, white)
    }
    p.set(14, 23, 15, Blocks.LADDER, p.away)
    p.set(12, 23, 16, Blocks.COMPUTER, p.away or 8); p.set(13, 23, 16, Blocks.COMPUTER, p.away or 8)
    p.set(13, 24, 19, Blocks.REDSTONE_LAMP_ON)
    // An elevator up the tower too (next to the ladder).
    p.set(12, 25, -1, Blocks.ELEVATOR); p.set(12, 25, 15, Blocks.ELEVATOR)
    // Helipad on the left.
    p.site(-14, -8, 9, 15, 8, Blocks.RUNWAY)
    for (a in -13..-9) for (d in 10..14) p.set(a, d, -1, if (a == -11 && d == 12) Blocks.HELIPAD else if (a == -13 || a == -9 || d == 10 || d == 14) Blocks.CONCRETE_FIRST + 4 else Blocks.RUNWAY)
    // The airport's airplane and helicopter, and the airport itself.
    val yaw = kotlin.math.atan2(p.fx.toFloat(), -p.fz.toFloat())
    aircraft.list.add(Aircraft(p.x(0, 7) + 0.5f, p.oy.toFloat(), p.z(0, 7) + 0.5f, Aircraft.PLANE).also { it.yaw = yaw })
    aircraft.list.add(Aircraft(p.x(-11, 12) + 0.5f, p.oy.toFloat(), p.z(-11, 12) + 0.5f, Aircraft.HELICOPTER).also { it.yaw = yaw })
    aircraft.airports.add(Airport(name, p.x(0, 3) + 0.5f, p.oy.toFloat(), p.z(0, 3) + 0.5f, p.fx.toFloat(), p.fz.toFloat()))
    uiEvents.add("toast:$name is open! Build another airport to fly between them")
}
