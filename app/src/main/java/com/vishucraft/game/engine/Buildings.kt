package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Shapes
import kotlin.math.abs

/*
 * Ready-made buildings: a blueprint builds a whole house, farm, tower, metro station, pool, mansion, airport or
 * even a smart city in front of the player, with its door facing them.
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
        if (id == Blocks.AIR && g.world.getBlock(wx, y, wz) == Blocks.AIR) return
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
    /** A billboard [w] blocks wide and [h] high facing [face], with [text] written across it (on the middle block). */
    fun billboard(a0: Int, d0: Int, h0: Int, w: Int, h: Int, face: Int, alongA: Boolean, text: String) {
        for (i in 0 until w) for (k in 0 until h) set(if (alongA) a0 + i else a0, if (alongA) d0 else d0 + i, h0 + k, Blocks.BILLBOARD, face)
        val mid = w / 2
        val ma = if (alongA) a0 + mid else a0; val md = if (alongA) d0 else d0 + mid
        g.world.blockEntities.sign(x(ma, md), oy + h0, z(ma, md))?.text = text
    }

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
    val toastsBefore = uiEvents.count { it.startsWith("toast:") }
    if (!buildReady(p, name)) return false
    sound("ding", player.x, player.y, player.z)
    // Buildings that say something themselves (a city's welcome, a station joining the line) keep their message.
    if (uiEvents.count { it.startsWith("toast:") } == toastsBefore) uiEvents.add("toast:Built ${if (name[0].lowercaseChar() in "aeiou") "an" else "a"} ${name.lowercase()}!")
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
        "Smart City" -> {
            if (p.oy + 46 >= Chunk.HEIGHT) { uiEvents.add("toast:Too high up for the city's tallest tower: build lower down"); return false }
            city(p, 4, "the smart city", "DV Metro", "DV Central")
        }
        "Big City", "Mega City" -> {
            if (p.oy + 52 >= Chunk.HEIGHT) { uiEvents.add("toast:Too high up for the city's tallest towers: build lower down"); return false }
            if (name == "Big City") city(p, 6, "the big city", "Big City Metro", "Big City Junction")
            else city(p, 8, "the mega city", "Mega Metro", "Mega City Terminus")
        }
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
    // The station's name, on a sign and a big billboard over the platform (tap either to rename the station).
    val name = stationName(p.x(0, 4), p.z(0, 4))
    p.set(0, 2, 1, Blocks.SIGN, p.toward)
    world.blockEntities.sign(p.x(0, 2), p.oy + 1, p.z(0, 2))?.text = name
    p.billboard(-3, 1, 3, 7, 2, p.away, true, name)
    // Its track joins the nearest other metro station or city line, however far away.
    val group = metroNet.newGroup()
    val line = joinMetro(listOf(
        TrackEnd(p.x(len, 4), p.oy, p.z(len, 4), p.rx, p.rz, group),
        TrackEnd(p.x(-len, 4), p.oy, p.z(-len, 4), -p.rx, -p.rz, group),
    ))
    if (line > 0) uiEvents.add("toast:$name metro station is open! Its track joins the nearest station ($line blocks of new track)")
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
    // The airport's name on a billboard on the terminal roof, facing the runway (tap it to rename the airport).
    p.billboard(7, 7, 6, 9, 2, p.left, false, name)
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

/**
 * A smart city, about 100 x 110 blocks: a grid of five-wide roads (keep-left lanes, centre markings, zebra
 * crossings, traffic lights, street lamps and bus stops) with cars and buses driving about; an elevated metro
 * line over the middle road with two stations and elevators up to them; a railway station with a train; and
 * sixteen city blocks: skyscrapers with elevators, a hospital with a helipad and helicopter, a school, a police
 * station, shops, parks, a cricket ground, houses, and a solar farm with EV chargers.
 */
private fun Game.city(p: Plan, n: Int, title: String, metroName: String, railName: String) {
    // n x n city blocks, 24 apart, with roads between and round them.
    val half = n * 12
    val roads = IntArray(n + 1) { -half + it * 24 }    // road centres, across
    val rows = IntArray(n + 1) { 3 + it * 24 }         // road centres, away
    val far = rows.last() + 2                           // the last row of road, away
    val mr = rows[n / 2]                                // the road the metro runs over
    val white = Blocks.CONCRETE_FIRST
    fun concrete(c: Int) = Blocks.CONCRETE_FIRST + c
    val pave = Blocks.SMOOTH_STONE
    p.site(-half - 4, half + 4, 1, far + 11, 50, Blocks.GRASS)

    // ---- Roads, pavements and grass.
    for (a in -half - 2..half + 2) for (d in 1..far) {
        val ra = roads.firstOrNull { abs(a - it) <= 2 }; val rd = rows.firstOrNull { abs(d - it) <= 2 }
        val lotEdge = roads.any { abs(a - it) == 3 } || rows.any { abs(d - it) == 3 }
        p.set(a, d, -1, when {
            ra != null && rd != null -> Blocks.ROAD
            ra != null -> if (a == ra && d % 2 == 0) Blocks.ROAD_MARKING else Blocks.ROAD
            rd != null -> if (d == rd && a % 2 == 0) Blocks.ROAD_MARKING else Blocks.ROAD
            lotEdge -> pave
            else -> Blocks.GRASS
        })
    }
    for (a in -half - 4..half + 4) for (d in far + 1..far + 2) p.set(a, d, -1, pave)
    // Zebra crossings on every road just before each junction (white stripes along the road).
    for (ra in roads) for (rd in rows) for (k in 3..5) for (s in intArrayOf(-1, 1)) for (o in -2..2 step 2) {
        val d = rd + s * k; val a = ra + s * k
        if (d in 1..far && rows.none { abs(d - it) <= 2 }) p.set(ra + o, d, -1, Blocks.ZEBRA_CROSSING)
        if (a in -half - 2..half + 2 && roads.none { abs(a - it) <= 2 }) p.set(a, rd + o, -1, Blocks.ZEBRA_CROSSING)
    }

    fun lamp(a: Int, d: Int, face: Int) {
        p.set(a, d, 0, Blocks.STREET_LAMP, face or 8); p.set(a, d, 1, Blocks.STREET_LAMP, face or 8); p.set(a, d, 2, Blocks.STREET_LAMP, face)
    }
    fun trafficLight(a: Int, d: Int, face: Int) {
        p.set(a, d, 0, Blocks.STREET_LAMP, face or 8); p.set(a, d, 1, Blocks.STREET_LAMP, face or 8); p.set(a, d, 2, Blocks.TRAFFIC_LIGHT, face)
    }
    fun sign(a: Int, d: Int, h: Int, text: String, face: Int = p.toward) {
        p.set(a, d, h, Blocks.SIGN, face)
        world.blockEntities.sign(p.x(a, d), p.oy + h, p.z(a, d))?.text = text
    }
    // ---- The city blocks (centre (ca, cd); each is 17 x 17 inside its pavement).
    fun sub(a: Int, d: Int) = Plan(this, p.x(a, d), p.oy, p.z(a, d), p.fx, p.fz)
    fun tree(a: Int, d: Int) {
        for (h in 0..3) p.set(a, d, h, Blocks.LOG)
        for (x in -2..2) for (z in -2..2) for (h in 3..4) if (abs(x) + abs(z) < 4 && !(x == 0 && z == 0 && h == 3)) p.set(a + x, d + z, h, Blocks.LEAVES)
        for (x in -1..1) for (z in -1..1) if (abs(x) + abs(z) < 2) p.set(a + x, d + z, 5, Blocks.LEAVES)
    }
    fun park(ca: Int, cd: Int) {
        val flowers = intArrayOf(Blocks.FLOWER_RED, Blocks.FLOWER_YELLOW, Blocks.FLOWER_FIRST + 1, Blocks.FLOWER_FIRST + 6)
        for (a in ca - 8..ca + 8) for (d in cd - 8..cd + 8) {
            if (a == ca || d == cd) p.set(a, d, -1, Blocks.DIRT_PATH)
            else if ((a * 7 + d * 3) % 11 == 0) p.set(a, d, 0, flowers[abs(a + d) % flowers.size])
        }
        for ((a, d) in listOf(ca - 5 to cd - 5, ca + 5 to cd - 5, ca - 5 to cd + 5, ca + 5 to cd + 5)) tree(a, d)
        // A pond with lily pads, benches, swings and lamps.
        for (a in ca + 2..ca + 7) for (d in cd + 2..cd + 3) { p.set(a, d, -1, Blocks.WATER); p.set(a, d, -2, Blocks.WATER) }
        p.set(ca + 4, cd + 2, 0, Blocks.LILY_PAD)
        for (a in intArrayOf(ca - 3, ca - 2)) { p.set(a, cd - 1, 0, Blocks.OAK_STAIRS, p.away); p.set(a, cd + 1, 0, Blocks.OAK_STAIRS, p.toward) }
        for (a in intArrayOf(ca - 7, ca - 4)) for (h in 0..1) p.set(a, cd + 6, h, Blocks.OAK_FENCE)
        for (a in ca - 7..ca - 4) p.set(a, cd + 6, 2, Blocks.PLANKS)
        p.set(ca - 6, cd + 6, 1, Blocks.SWING, p.toward); p.set(ca - 5, cd + 6, 1, Blocks.SWING, p.toward)
        lamp(ca + 1, cd - 4, p.left); lamp(ca - 1, cd + 4, p.right)
        sign(ca, cd - 8, 0, "City Park")
    }
    fun skyscraper(ca: Int, cd: Int, floors: Int, frame: Int, name: String) {
        val top = floors * 4 - 1
        for (a in ca - 5..ca + 5) for (d in cd - 5..cd + 5) {
            val edge = a == ca - 5 || a == ca + 5 || d == cd - 5 || d == cd + 5
            for (k in 0..floors) p.set(a, d, k * 4 - 1, if (k == 0) Blocks.POLISHED_DIORITE else if (k == floors) frame else Blocks.QUARTZ_BLOCK)
            if (edge) {
                val column = (a - ca) % 3 == 0 && (d - cd) % 3 == 0 || (abs(a - ca) == 5 && abs(d - cd) == 5)
                for (h in 0 until top) if (h % 4 != 3) p.set(a, d, h, if (column) frame else Blocks.GLASS)
                for (h in 0 until top) if (h % 4 == 3) p.set(a, d, h, frame)
            }
        }
        p.door(ca, cd - 5); p.door(ca - 1, cd - 5)
        sign(ca + 2, cd - 6, 0, name)
        // An elevator to every floor and the roof.
        for (k in 0..floors) p.set(ca + 3, cd + 3, k * 4 - 1, Blocks.ELEVATOR)
        for (k in 0 until floors) {
            val h = k * 4
            p.set(ca, cd, h + 2, Blocks.CEILING_LIGHT, 8)
            if (k == 0) {
                p.set(ca - 3, cd - 1, h, Blocks.KITCHEN_COUNTER, p.toward); p.set(ca - 2, cd - 1, h, Blocks.KITCHEN_COUNTER, p.toward)
                p.set(ca - 3, cd - 1, h + 1, Blocks.COMPUTER, p.toward or 8)
                p.set(ca + 2, cd - 3, h, Blocks.SOFA, p.away); p.set(ca + 3, cd - 3, h, Blocks.SOFA, p.away)
                p.set(ca - 4, cd - 4, h, Blocks.PLANT_POT); p.set(ca + 4, cd - 4, h, Blocks.PLANT_POT)
            } else {
                for (a in intArrayOf(ca - 3, ca - 1)) { p.set(a, cd + 2, h, Blocks.STUDY_TABLE, p.toward); p.set(a, cd + 2, h + 1, Blocks.COMPUTER, p.toward or 8); p.set(a, cd + 1, h, Blocks.CHAIR, p.away) }
                p.set(ca - 4, cd - 4, h, Blocks.WATER_COOLER, p.away); p.set(ca + 4, cd - 4, h, Blocks.PLANT_POT)
            }
        }
        for (a in ca - 5..ca + 5) for (d in cd - 5..cd + 5) if (a == ca - 5 || a == ca + 5 || d == cd - 5 || d == cd + 5) p.set(a, d, top + 1, Blocks.GLASS_PANE)
        p.set(ca - 5, cd - 5, top + 1, Blocks.REDSTONE_LAMP_ON); p.set(ca + 5, cd + 5, top + 1, Blocks.REDSTONE_LAMP_ON)
    }
    fun hospital(ca: Int, cd: Int) {
        val red = concrete(14)
        for (a in ca - 7..ca + 7) for (d in cd - 6..cd + 6) {
            for (h in intArrayOf(-1, 3, 7, 11)) p.set(a, d, h, if (h == -1) Blocks.QUARTZ_BLOCK else white)
            if (a == ca - 7 || a == ca + 7 || d == cd - 6 || d == cd + 6) for (h in 0..10) if (h % 4 != 3)
                p.set(a, d, h, if (h % 4 in 1..2 && (a + d) % 3 != 0) Blocks.GLASS else white)
        }
        // A red cross on the front and the entrance under it.
        for (k in -1..1) { p.set(ca + k, cd - 6, 9, red); p.set(ca, cd - 6, 9 + k, red) }
        p.door(ca, cd - 6); p.door(ca + 1, cd - 6)
        sign(ca - 2, cd - 7, 0, "City Hospital")
        for (a in ca - 6..ca - 3) p.set(a, cd - 4, 0, Blocks.KITCHEN_COUNTER, p.toward)
        p.set(ca - 5, cd - 4, 1, Blocks.COMPUTER, p.toward or 8)
        for (h in intArrayOf(0, 4, 8)) {
            for (a in intArrayOf(ca - 5, ca - 2, ca + 2, ca + 5)) { p.set(a, cd + 4, h, Blocks.BED_FIRST, p.away); p.set(a, cd + 5, h, Blocks.BED_FIRST, p.away or Shapes.UPPER) }
            for (a in intArrayOf(ca - 4, ca + 4)) p.set(a, cd + 5, h, Blocks.CURTAIN, p.toward)
            p.set(ca, cd, h + 2, Blocks.CEILING_LIGHT, 8)
        }
        p.set(ca + 6, cd - 5, 0, Blocks.WATER_COOLER, p.left)
        for (h in intArrayOf(-1, 3, 7, 11)) p.set(ca + 6, cd, h, Blocks.ELEVATOR)
        // Helipad on the roof, with the air ambulance.
        for (a in ca - 3..ca + 3) for (d in cd - 3..cd + 3) p.set(a, d, 11, if (a == ca && d == cd) Blocks.HELIPAD else if (abs(a - ca) == 3 || abs(d - cd) == 3) concrete(4) else Blocks.RUNWAY)
        for (a in ca - 7..ca + 7) for (d in cd - 6..cd + 6) if (a == ca - 7 || a == ca + 7 || d == cd - 6 || d == cd + 6) p.set(a, d, 12, Blocks.GLASS_PANE)
        aircraft.list.add(Aircraft(p.x(ca, cd) + 0.5f, (p.oy + 12).toFloat(), p.z(ca, cd) + 0.5f, Aircraft.HELICOPTER).also { it.yaw = kotlin.math.atan2(p.fx.toFloat(), -p.fz.toFloat()) })
    }
    fun school(ca: Int, cd: Int) {
        val yellow = concrete(4)
        for (a in ca - 7..ca + 7) for (d in cd - 7..cd + 1) {
            for (h in intArrayOf(-1, 3, 7)) p.set(a, d, h, if (h == -1) Blocks.PLANKS else if (h == 7) concrete(14) else yellow)
            if (a == ca - 7 || a == ca + 7 || d == cd - 7 || d == cd + 1) for (h in 0..6) if (h != 3)
                p.set(a, d, h, if (h % 4 in 1..2 && abs(a - ca) % 3 != 0) Blocks.GLASS else yellow)
        }
        p.door(ca, cd - 7)
        sign(ca + 2, cd - 8, 0, "DV Public School")
        for (h in intArrayOf(0, 4)) {
            for (a in ca - 4..ca + 4) for (k in 1..2) p.set(a, cd + 1, h + k, concrete(15))   // blackboard
            p.set(ca, cd - 1, h, Blocks.STUDY_TABLE, p.toward)                              // teacher's desk
            for (a in intArrayOf(ca - 4, ca - 2, ca + 2, ca + 4)) for (d in intArrayOf(cd - 3, cd - 5)) { p.set(a, d, h, Blocks.STUDY_TABLE, p.away); p.set(a, d - 1, h, Blocks.CHAIR, p.away) }
            p.set(ca - 6, cd - 6, h + 2, Blocks.CEILING_LIGHT, 8); p.set(ca + 6, cd - 6, h + 2, Blocks.CEILING_LIGHT, 8)
            p.set(ca - 6, cd, h + 2, Blocks.WALL_CLOCK, p.toward)
        }
        for (k in 0..3) { p.set(ca - 6, cd - 2 - k, k, Blocks.OAK_STAIRS, p.toward); for (h in 0 until k) p.set(ca - 6, cd - 2 - k, h, Blocks.PLANKS) }
        for (d in cd - 4..cd - 2) p.set(ca - 6, d, 3, Blocks.AIR)
        // Playground behind: swings and a sand pit.
        for (a in intArrayOf(ca - 6, ca - 2)) for (h in 0..1) p.set(a, cd + 5, h, Blocks.OAK_FENCE)
        for (a in ca - 6..ca - 2) p.set(a, cd + 5, 2, Blocks.PLANKS)
        for (a in ca - 5..ca - 3) p.set(a, cd + 5, 1, Blocks.SWING, p.toward)
        for (a in ca + 2..ca + 6) for (d in cd + 3..cd + 7) p.set(a, d, -1, Blocks.SAND)
        tree(ca, cd + 7)
    }
    fun police(ca: Int, cd: Int) {
        val blue = concrete(11)
        for (a in ca - 6..ca + 6) for (d in cd - 7..cd + 1) {
            p.set(a, d, -1, Blocks.POLISHED_ANDESITE); p.set(a, d, 4, blue)
            if (a == ca - 6 || a == ca + 6 || d == cd - 7 || d == cd + 1) for (h in 0..3)
                p.set(a, d, h, if (h == 3) white else if (h in 1..2 && abs(a - ca) in 2..4) Blocks.GLASS else blue)
        }
        p.door(ca, cd - 7)
        sign(ca + 1, cd - 8, 0, "Police Station")
        p.set(ca - 1, cd - 7, 5, Blocks.REDSTONE_LAMP_ON); p.set(ca + 1, cd - 7, 5, Blocks.LAPIS_BLOCK)
        for (a in intArrayOf(ca - 4, ca + 2)) { p.set(a, cd - 4, 0, Blocks.STUDY_TABLE, p.toward); p.set(a, cd - 4, 1, Blocks.COMPUTER, p.toward or 8); p.set(a, cd - 3, 0, Blocks.CHAIR, p.toward) }
        // A lock-up at the back.
        for (a in ca + 1..ca + 5) p.set(a, cd - 2, 0, Blocks.IRON_BARS); for (a in ca + 1..ca + 5) p.set(a, cd - 2, 1, Blocks.IRON_BARS)
        p.set(ca + 5, cd, 0, Blocks.BED_FIRST + 7, p.left); p.set(ca + 4, cd, 0, Blocks.BED_FIRST + 7, p.left or Shapes.UPPER)
        p.set(ca, cd - 1, 3, Blocks.CEILING_LIGHT, 8)
        // Parking for the police cars.
        for (a in ca - 7..ca + 7) for (d in cd + 3..cd + 8) p.set(a, d, -1, if (a % 4 == 0) concrete(0) else Blocks.POLISHED_ANDESITE)
    }
    fun shops(ca: Int, cd: Int) {
        for (a in ca - 8..ca + 8) for (d in cd - 6..cd + 4) {
            p.set(a, d, -1, Blocks.POLISHED_DIORITE); p.set(a, d, 5, Blocks.SMOOTH_STONE)
            val edge = a == ca - 8 || a == ca + 8 || d == cd - 6 || d == cd + 4
            val wall = edge || (a == ca - 3 || a == ca + 3)
            if (wall) for (h in 0..4) p.set(a, d, h, if (d == cd - 6 && h in 0..3 && !(a == ca - 3 || a == ca + 3 || abs(a - ca) == 8)) Blocks.GLASS else white)
        }
        val names = listOf("Supermarket", "Bakery", "Cafe")
        for ((k, sa) in intArrayOf(ca - 6, ca, ca + 6).withIndex()) {
            p.door(sa, cd - 6)
            sign(sa + 1, cd - 7, 0, names[k])
            for (a in sa - 2..sa + 2) p.set(a, cd - 6, 4, concrete(intArrayOf(14, 1, 12)[k]))
            p.set(sa, cd - 1, 3, Blocks.CEILING_LIGHT, 8)
            when (k) {
                0 -> {
                    for (a in sa - 2..sa + 2) { p.set(a, cd + 3, 0, Blocks.BARREL); p.set(a, cd + 3, 1, Blocks.BARREL) }
                    p.set(sa - 2, cd + 1, 0, Blocks.FRIDGE, p.right); p.set(sa - 2, cd + 1, 1, Blocks.FRIDGE, p.right or Shapes.UPPER)
                    p.set(sa + 2, cd - 3, 0, Blocks.KITCHEN_COUNTER, p.left); p.set(sa + 2, cd - 3, 1, Blocks.COMPUTER, p.left or 8)
                }
                1 -> {
                    p.set(sa - 1, cd + 3, 0, Blocks.OVEN, p.toward); p.set(sa + 1, cd + 3, 0, Blocks.OVEN, p.toward)
                    for (a in sa - 2..sa + 2) p.set(a, cd + 1, 0, Blocks.KITCHEN_COUNTER, p.toward)
                    p.set(sa, cd + 1, 1, Blocks.CAKE)
                }
                else -> {
                    for (d in intArrayOf(cd - 3, cd + 1)) { p.set(sa, d, 0, Blocks.DINING_TABLE); p.set(sa - 1, d, 0, Blocks.CHAIR, p.right); p.set(sa + 1, d, 0, Blocks.CHAIR, p.left) }
                    p.set(sa - 2, cd + 3, 0, Blocks.KITCHEN_COUNTER, p.toward); p.set(sa - 2, cd + 3, 1, Blocks.KETTLE, p.toward)
                    p.set(sa + 2, cd + 3, 0, Blocks.WATER_COOLER, p.toward)
                }
            }
        }
        for (a in intArrayOf(ca - 6, ca + 6)) tree(a, cd + 7)
    }
    fun cricket(ca: Int, cd: Int) {
        for (a in ca - 8..ca + 8) for (d in cd - 8..cd + 8) {
            val r = kotlin.math.hypot((a - ca).toFloat(), (d - cd).toFloat())
            if (r in 7.3f..8.2f) p.set(a, d, -1, Blocks.WOOL_WHITE)
        }
        for (d in cd - 4..cd + 4) for (a in ca - 1..ca + 1) p.set(a, d, -1, Blocks.SMOOTH_SANDSTONE)
        for (d in intArrayOf(cd - 4, cd + 4)) for (a in ca - 1..ca + 1 step 2) p.set(a, d, 0, Blocks.OAK_FENCE)
        for (d in intArrayOf(cd - 4, cd + 4)) p.set(ca, d, 0, Blocks.OAK_FENCE)
        for ((a, d) in listOf(ca - 8 to cd - 8, ca + 8 to cd - 8, ca - 8 to cd + 8, ca + 8 to cd + 8)) {
            for (h in 0..7) p.set(a, d, h, Blocks.STREET_LAMP, 8)
            p.set(a, d, 8, Blocks.SEA_LANTERN)
        }
        for (a in ca - 5..ca + 5) { p.set(a, cd + 8, 0, Blocks.STONE_BRICK_STAIRS, p.toward); p.set(a, cd + 8, 1, Blocks.AIR) }
        sign(ca, cd - 8, 0, "Cricket Stadium")
    }
    fun solarFarm(ca: Int, cd: Int) {
        for (a in ca - 8..ca + 8) for (d in cd - 8..cd - 1) p.set(a, d, -1, if (d == cd - 5 && a % 3 != 0) concrete(0) else Blocks.POLISHED_ANDESITE)
        for (a in ca - 7..ca + 7 step 3) p.set(a, cd - 1, 0, Blocks.EV_CHARGER, p.toward)
        for (d in cd + 1..cd + 8) if (d % 3 != 0) for (a in ca - 8..ca + 8) p.set(a, d, 0, Blocks.SOLAR_PANEL, p.toward)
        sign(ca + 2, cd - 8, 0, "Solar Farm & EV Charging")
    }
    fun houses(ca: Int, cd: Int) {
        for (a in intArrayOf(ca - 4, ca + 4)) { smallHouse(sub(a, cd - 9)); smallHouse(sub(a, cd - 1)) }
    }
    fun villa(ca: Int, cd: Int) { modernHouse(sub(ca, cd - 9)); pool(sub(ca, cd)) }

    if (n == 4) {
        park(-36, 15); police(-12, 15); shops(12, 15); hospital(36, 15)
        school(-36, 39); skyscraper(-12, 39, 8, concrete(11), "Tech Park"); skyscraper(12, 39, 10, concrete(9), "Sky Towers"); houses(36, 39)
        houses(-36, 63); skyscraper(-12, 63, 9, concrete(7), "City Centre"); cricket(12, 63); solarFarm(36, 63)
        villa(-36, 87); skyscraper(-12, 87, 11, concrete(15), "DV Tower"); villa(12, 87); park(36, 87)
    } else {
        // Bigger cities: a mix of everything, with more towers towards the middle.
        val kinds = listOf("sky", "houses", "shops", "park", "villa", "sky", "school", "houses", "solar", "sky", "cricket", "police", "hospital", "villa", "shops", "sky")
        val towers = listOf("Tech Park", "Sky Towers", "City Centre", "DV Tower", "Lotus Tower", "Ocean View", "Galaxy Heights", "Royal Plaza",
            "Sun Tower", "Silver Oak", "Diamond Point", "Pearl Tower", "Orchid Heights", "Metro Plaza", "Unity Tower", "Rainbow Tower")
        val frames = intArrayOf(11, 9, 7, 15, 3, 14, 13, 1)
        for (i in 0 until n) for (j in 0 until n) {
            val ca = roads[i] + 12; val cd = rows[j] + 12
            val central = abs(i * 2 + 1 - n) <= 2 && abs(j * 2 + 1 - n) <= 2
            val kind = if (central && (i + j) % 2 == 0) "sky" else kinds[(i * 7 + j * 3 + i * j) % kinds.size]
            when (kind) {
                "sky" -> skyscraper(ca, cd, if (central) 10 + (i + j) % 3 else 6 + (i * 3 + j * 5) % 5, concrete(frames[(i + j * 3) % frames.size]), towers[(i * n + j) % towers.size])
                "houses" -> houses(ca, cd)
                "shops" -> shops(ca, cd)
                "park" -> park(ca, cd)
                "villa" -> villa(ca, cd)
                "school" -> school(ca, cd)
                "solar" -> solarFarm(ca, cd)
                "cricket" -> cricket(ca, cd)
                "police" -> police(ca, cd)
                else -> hospital(ca, cd)
            }
        }
    }

    // ---- Street lamps along every pavement, traffic lights at the corners, and bus stops.
    for (i in 0 until n) for (j in 0 until n) {
        val a0 = roads[i] + 3; val a1 = roads[i + 1] - 3; val d0 = rows[j] + 3; val d1 = rows[j + 1] - 3
        for (d in d0 + 2..d1 - 2 step 6) { lamp(a0, d, p.left); lamp(a1, d, p.right) }
        for (a in a0 + 2..a1 - 2 step 6) { lamp(a, d0, p.toward); lamp(a, d1, p.away) }
        trafficLight(a0, d0, p.toward); trafficLight(a1, d0, p.right); trafficLight(a0, d1, p.left); trafficLight(a1, d1, p.away)
    }
    for (j in 0 until n) { p.set(-3, rows[j] + 13, 0, Blocks.BUS_STOP, p.right); p.set(3, rows[j] + 13, 0, Blocks.BUS_STOP, p.left) }
    for (a in intArrayOf(roads[0] + 8, roads[n - 1] + 8)) { p.set(a, mr - 3, 0, Blocks.BUS_STOP, p.away); p.set(a, mr + 3, 0, Blocks.BUS_STOP, p.toward) }

    // ---- The elevated metro over the middle road, with stations at both ends (and the middle in big cities).
    for (a in -half - 2..half + 2) {
        for (d in mr - 3..mr + 3) p.set(a, d, 10, if ((d == mr - 3 || d == mr + 3) && a % 8 == 0) Blocks.SEA_LANTERN else concrete(8))
        p.set(a, mr - 3, 11, Blocks.GLASS_PANE); p.set(a, mr + 3, 11, Blocks.GLASS_PANE)
        p.set(a, mr - 1, 11, Blocks.RAIL, p.railAcross); p.set(a, mr + 1, 11, Blocks.RAIL, p.railAcross)
    }
    for (i in 0 until n) for (h in 0..9) { p.set(roads[i] + 12, mr - 3, h, concrete(8)); p.set(roads[i] + 12, mr + 3, h, concrete(8)) }
    val stations = listOf(roads[0] + 12 to "$metroName West", roads[n - 1] + 12 to "$metroName East") +
        (if (n >= 6) listOf(roads[n / 2] + 12 to "$metroName Central") else emptyList())
    for ((sa, name) in stations) {
        for (a in sa - 8..sa + 8) {
            for (d in intArrayOf(mr - 3, mr - 2, mr + 2, mr + 3)) p.set(a, d, 11, Blocks.STATION_PLATFORM)
            for (d in mr - 3..mr + 3) p.set(a, d, 15, Blocks.GLASS)
            if ((a - sa) % 4 == 0) for (h in 12..14) { p.set(a, mr - 3, h, Blocks.QUARTZ_BLOCK); p.set(a, mr + 3, h, Blocks.QUARTZ_BLOCK) }
            else if (a != sa + 8) { p.set(a, mr - 3, 12, Blocks.GLASS_PANE); p.set(a, mr + 3, 12, Blocks.GLASS_PANE) }
            if ((a - sa) % 4 == 2) { p.set(a, mr - 3, 15, Blocks.SEA_LANTERN); p.set(a, mr + 3, 15, Blocks.SEA_LANTERN) }
        }
        p.set(sa, mr - 1, 11, Blocks.STOP_RAIL, p.railAcross); p.set(sa, mr + 1, 11, Blocks.STOP_RAIL, p.railAcross)
        sign(sa, mr - 3, 12, name); sign(sa, mr + 3, 12, name, p.away)
        // Billboards under the roof, facing the tracks: riders see the station's name as the train pulls in.
        p.billboard(sa - 3, mr - 3, 13, 7, 2, p.away, true, name)
        p.billboard(sa - 3, mr + 3, 13, 7, 2, p.toward, true, name)
        p.set(sa - 3, mr - 2, 12, Blocks.SOFA, p.away); p.set(sa + 3, mr + 2, 12, Blocks.SOFA, p.toward)
        // Elevators up from the pavement on both sides, straight onto the platforms.
        for (d in intArrayOf(mr - 4, mr + 4)) { p.set(sa + 8, d, -1, Blocks.ELEVATOR); p.set(sa + 8, d, 11, Blocks.ELEVATOR); for (h in 0..1) p.set(sa + 8, d, h, Blocks.AIR) }
    }
    // One metro on each track (west station on the near track, east station on the far one), running end to end.
    val west = roads[0] + 12; val east = roads[n - 1] + 12
    for ((d, sa, dir) in listOf(Triple(mr - 1, west, 1), Triple(mr + 1, east, -1))) {
        val metro = Cart(p.x(sa + 5 * dir, d) + 0.5f, (p.oy + 11).toFloat(), p.z(sa + 5 * dir, d) + 0.5f, Cart.METRO)
        metro.hx = p.rx.toFloat() * dir; metro.hz = p.rz.toFloat() * dir; metro.yaw = kotlin.math.atan2(metro.hx, -metro.hz)
        carts.list.add(metro)
        carts.list.add(Cart(p.x(sa + 2 * dir, d) + 0.5f, (p.oy + 11).toFloat(), p.z(sa + 2 * dir, d) + 0.5f, Cart.COACH).also { it.leader = metro; it.hx = metro.hx; it.hz = metro.hz; it.yaw = metro.yaw })
    }
    // A bullet train shares the near track with the metro (trains on one track keep their distance).
    val bullet = Cart(p.x(16, mr - 1) + 0.5f, (p.oy + 11).toFloat(), p.z(16, mr - 1) + 0.5f, Cart.BULLET)
    bullet.hx = p.rx.toFloat(); bullet.hz = p.rz.toFloat(); bullet.yaw = kotlin.math.atan2(bullet.hx, -bullet.hz)
    carts.list.add(bullet)
    var lastCar = bullet
    for (a in intArrayOf(12, 9)) {
        val c = Cart(p.x(a, mr - 1) + 0.5f, (p.oy + 11).toFloat(), p.z(a, mr - 1) + 0.5f, Cart.COACH).also { it.leader = lastCar; it.hx = bullet.hx; it.hz = bullet.hz; it.yaw = bullet.yaw }
        carts.list.add(c); lastCar = c
    }

    // ---- The railway station behind the city, with a train.
    val rb = far + 3                                    // the railway's first row (104 in the smart city)
    for (a in -half - 4..half + 4) {
        for (d in rb..rb + 6) p.set(a, d, -1, Blocks.RAILWAY_BALLAST)
        p.set(a, rb + 3, 0, if (a == 0) Blocks.STOP_RAIL else Blocks.RAIL, p.railAcross)
        if (a !in -10..10) p.set(a, rb - 1, 0, Blocks.IRON_BARS)
    }
    for (a in -10..10) {
        for (d in intArrayOf(rb, rb + 1, rb + 2, rb + 4, rb + 5)) { p.set(a, d, 0, Blocks.STATION_PLATFORM); p.set(a, d, 5, Blocks.GLASS) }
        p.set(a, rb + 3, 5, Blocks.GLASS)
        if (a % 5 == 0) for (h in 1..4) { p.set(a, rb, h, Blocks.BRICKS); p.set(a, rb + 5, h, Blocks.BRICKS) }
        if (a % 5 == 2) { p.set(a, rb, 5, Blocks.SEA_LANTERN); p.set(a, rb + 5, 5, Blocks.SEA_LANTERN) }
    }
    sign(2, rb + 1, 1, "$railName Railway Station")
    p.billboard(-4, rb, 3, 9, 2, p.away, true, railName)
    p.set(-4, rb + 1, 1, Blocks.SOFA, p.away); p.set(4, rb + 1, 1, Blocks.SOFA, p.away)
    val engine = Cart(p.x(4, rb + 3) + 0.5f, p.oy.toFloat(), p.z(4, rb + 3) + 0.5f, Cart.ENGINE)
    engine.hx = p.rx.toFloat(); engine.hz = p.rz.toFloat(); engine.yaw = kotlin.math.atan2(engine.hx, -engine.hz)
    carts.list.add(engine)
    var lead = engine
    for (k in 1..3) {
        val c = Cart(p.x(4 - 3 * k, rb + 3) + 0.5f, p.oy.toFloat(), p.z(4 - 3 * k, rb + 3) + 0.5f, Cart.COACH).also { it.leader = lead; it.hx = engine.hx; it.hz = engine.hz; it.yaw = engine.yaw }
        carts.list.add(c); lead = c
    }

    // ---- Traffic: cars and buses in the left lanes, plus parked ones.
    fun vehicle(a: Int, d: Int, da: Int, dd: Int, kind: Int, color: Int) {
        val v = Vehicle(p.x(a, d) + 0.5f, p.oy.toFloat(), p.z(a, d) + 0.5f, kind, color)
        v.hx = p.rx * da + p.fx * dd; v.hz = p.rz * da + p.fz * dd
        v.yaw = kotlin.math.atan2(v.hx.toFloat(), -v.hz.toFloat())
        vehicles.list.add(v)
    }
    val car = Vehicle.CAR; val bus = Vehicle.BUS
    if (n == 4) {
        vehicle(-1, 15, 0, 1, car, 14); vehicle(1, 40, 0, -1, car, 11); vehicle(-25, 63, 0, 1, car, 0); vehicle(23, 87, 0, 1, car, 15)
        vehicle(-12, 28, 1, 0, car, 4); vehicle(12, 26, -1, 0, car, 5); vehicle(36, 76, 1, 0, car, 1); vehicle(-36, 98, -1, 0, car, 8)
        vehicle(49, 30, 0, -1, car, 10); vehicle(-49, 70, 0, 1, car, 3)
        vehicle(-1, 63, 0, 1, bus, 0); vehicle(-30, 52, 1, 0, bus, 0); vehicle(8, 2, -1, 0, bus, 0)
        // Parked: the police cars, an ambulance and cars at the EV chargers.
        vehicle(-16, 21, 0, 1, car, 11); vehicle(-8, 21, 0, 1, car, 0)
        vehicle(42, 7, 1, 0, car, 0)
        vehicle(32, 59, 0, 1, car, 13); vehicle(38, 59, 0, 1, car, 6)
    } else {
        // Traffic in the middle of every other stretch of road, both ways, and buses on the main avenue.
        var color = 0
        for (i in 0..n) for (j in 0 until n) if ((i + j) % 2 == 0) {
            val dMid = rows[j] + 12
            if ((i + j) % 4 == 0) vehicle(roads[i] - 1, dMid, 0, 1, car, color++ % 16) else vehicle(roads[i] + 1, dMid, 0, -1, car, color++ % 16)
        }
        for (j in 0 until n) if (j % 2 == 1) vehicle(roads[j] + 12, rows[j] + 1, 1, 0, car, color++ % 16)
        for (j in 0 until n step 2) vehicle(-1, rows[j] + 8, 0, 1, bus, 0)
        vehicle(roads[1] + 6, mr + 1, 1, 0, bus, 0)
    }
    // ---- People walking on the pavements and in the parks.
    val rnd = java.util.Random(p.ox * 31L + p.oz)
    val folk = listOf(MobType.CITIZEN, MobType.CITIZEN_WOMAN, MobType.CITIZEN_KID)
    repeat(n * n * 2) {
        val i = rnd.nextInt(n); val j = rnd.nextInt(n)
        val a0 = roads[i] + 3; val d0 = rows[j] + 3
        // On the pavement ring round a city block.
        val k = rnd.nextInt(19)
        val (a, d) = when (rnd.nextInt(4)) { 0 -> a0 + k to d0; 1 -> a0 + k to d0 + 18; 2 -> a0 to d0 + k; else -> a0 + 18 to d0 + k }
        mobs.list.add(Mob(folk[rnd.nextInt(folk.size)], p.x(a, d) + 0.5f, p.oy.toFloat() + 0.5f, p.z(a, d) + 0.5f).also { it.yaw = rnd.nextFloat() * 6.28f })
    }
    // Its metro line joins any other metro station or city by itself.
    val group = metroNet.newGroup()
    val line = joinMetro(listOf(
        TrackEnd(p.x(-half - 2, mr - 1), p.oy + 11, p.z(-half - 2, mr - 1), -p.rx, -p.rz, group),
        TrackEnd(p.x(half + 2, mr - 1), p.oy + 11, p.z(half + 2, mr - 1), p.rx, p.rz, group),
    ))
    uiEvents.add("toast:Welcome to $title! Tap a car or bus to drive it; take the elevators up to the metro" +
        if (line > 0) "\nIts metro line now joins the nearest station ($line blocks of new track)" else "")
}


/** A name for a new metro station, picked from its position (tap its sign or billboard to change it). */
private fun stationName(x: Int, z: Int): String {
    val names = listOf("Central", "Green Park", "Lake View", "Market", "City Hall", "Park Street", "River Side", "Hill Top",
        "Garden City", "Airport Road", "Old Town", "Sunrise", "Tech Park", "Rose Garden", "Station Road", "Lotus")
    return names[((x * 73856093) xor (z * 19349663)).mod(names.size)]
}
