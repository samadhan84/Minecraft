package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * The builder robot: tap the ground with it, type what you want ("castle", "red tower 20",
 * "glass pyramid", "write DHRUV"...) and it builds it in front of you, a few blocks at a time.
 * It understands words, not sentences: a thing to build, and optionally a size, a colour or material,
 * and "big" / "small".
 */

class Robot {
    /** Blocks still to place: x, y, z, id, meta. */
    val queue = ArrayDeque<IntArray>()
    /** Where the robot was pointed (the ground block tapped) and which way the player was facing. */
    var target: IntArray? = null
    var building = ""
    /** Blocks the robot places each frame. */
    var speed = 24

    companion object {
        /** Everything the robot knows how to build (shown as ideas). */
        val IDEAS = listOf(
            "house", "modern house", "farm", "castle", "tower 20", "skyscraper 10", "pyramid", "bridge 20", "tree", "wall",
            "road", "sphere", "dome", "igloo", "cube", "lighthouse", "fountain", "temple", "garden", "stairs 10", "railway",
            "metro station", "pool", "statue", "heart", "write HELLO",
        )
        const val MAX_QUEUE = 60000

        private val COLORS = linkedMapOf(
            "light blue" to 3, "light gray" to 8, "light grey" to 8, "white" to 0, "orange" to 1, "magenta" to 2, "yellow" to 4,
            "lime" to 5, "pink" to 6, "gray" to 7, "grey" to 7, "cyan" to 9, "purple" to 10, "blue" to 11, "brown" to 12,
            "green" to 13, "red" to 14, "black" to 15,
        )
        private val MATERIALS = linkedMapOf(
            "gold" to Blocks.GOLD_BLOCK, "golden" to Blocks.GOLD_BLOCK, "diamond" to Blocks.DIAMOND_BLOCK, "iron" to Blocks.IRON_BLOCK,
            "emerald" to Blocks.EMERALD_BLOCK, "glass" to Blocks.GLASS, "wood" to Blocks.PLANKS, "wooden" to Blocks.PLANKS,
            "brick" to Blocks.BRICKS, "bricks" to Blocks.BRICKS, "stone" to Blocks.STONE_BRICKS, "cobble" to Blocks.COBBLESTONE,
            "cobblestone" to Blocks.COBBLESTONE, "sand" to Blocks.SANDSTONE, "sandstone" to Blocks.SANDSTONE, "quartz" to Blocks.QUARTZ_BLOCK,
            "marble" to Blocks.QUARTZ_BLOCK, "ice" to Blocks.PACKED_ICE, "snow" to Blocks.SNOW, "obsidian" to Blocks.OBSIDIAN,
            "copper" to Blocks.COPPER_BLOCK, "amethyst" to Blocks.AMETHYST_BLOCK, "mud" to Blocks.MUD_BRICKS, "deepslate" to Blocks.DEEPSLATE_BRICKS,
            "leaf" to Blocks.LEAVES, "leaves" to Blocks.LEAVES, "wool" to Blocks.WOOL_WHITE, "glowstone" to Blocks.GLOWSTONE,
            "lava" to Blocks.LAVA, "water" to Blocks.WATER,
        )

        /** The block a request asks for by colour or material, if any. */
        fun material(text: String): Int? {
            val t = " ${text.lowercase()} "
            for ((w, id) in MATERIALS) if (t.contains(" $w ")) return id
            for ((w, i) in COLORS) if (t.contains(" $w ")) return Blocks.CONCRETE_FIRST + i
            return null
        }

        /** The first number in a request, if any. */
        fun number(text: String): Int? = Regex("\\d+").find(text)?.value?.toIntOrNull()

        /** "big" and "small" make things bigger or smaller. */
        fun scale(text: String): Float {
            val t = " ${text.lowercase()} "
            return when {
                listOf(" huge ", " giant ", " enormous ", " massive ").any { t.contains(it) } -> 2f
                listOf(" big ", " large ", " tall ").any { t.contains(it) } -> 1.5f
                listOf(" small ", " tiny ", " mini ", " little ").any { t.contains(it) } -> 0.6f
                else -> 1f
            }
        }

        /** 5x7 capital letters and digits for "write …". */
        val FONT: Map<Char, Array<String>> = mapOf(
            'A' to arrayOf(" ### ", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"),
            'B' to arrayOf("#### ", "#   #", "#   #", "#### ", "#   #", "#   #", "#### "),
            'C' to arrayOf(" ### ", "#   #", "#    ", "#    ", "#    ", "#   #", " ### "),
            'D' to arrayOf("#### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#### "),
            'E' to arrayOf("#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####"),
            'F' to arrayOf("#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#    "),
            'G' to arrayOf(" ### ", "#   #", "#    ", "# ###", "#   #", "#   #", " ####"),
            'H' to arrayOf("#   #", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"),
            'I' to arrayOf(" ### ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "),
            'J' to arrayOf("  ###", "   # ", "   # ", "   # ", "   # ", "#  # ", " ##  "),
            'K' to arrayOf("#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #"),
            'L' to arrayOf("#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####"),
            'M' to arrayOf("#   #", "## ##", "# # #", "# # #", "#   #", "#   #", "#   #"),
            'N' to arrayOf("#   #", "##  #", "# # #", "#  ##", "#   #", "#   #", "#   #"),
            'O' to arrayOf(" ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "),
            'P' to arrayOf("#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    "),
            'Q' to arrayOf(" ### ", "#   #", "#   #", "#   #", "# # #", "#  # ", " ## #"),
            'R' to arrayOf("#### ", "#   #", "#   #", "#### ", "# #  ", "#  # ", "#   #"),
            'S' to arrayOf(" ####", "#    ", "#    ", " ### ", "    #", "    #", "#### "),
            'T' to arrayOf("#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  "),
            'U' to arrayOf("#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "),
            'V' to arrayOf("#   #", "#   #", "#   #", "#   #", "#   #", " # # ", "  #  "),
            'W' to arrayOf("#   #", "#   #", "#   #", "# # #", "# # #", "# # #", " # # "),
            'X' to arrayOf("#   #", "#   #", " # # ", "  #  ", " # # ", "#   #", "#   #"),
            'Y' to arrayOf("#   #", "#   #", " # # ", "  #  ", "  #  ", "  #  ", "  #  "),
            'Z' to arrayOf("#####", "    #", "   # ", "  #  ", " #   ", "#    ", "#####"),
            '0' to arrayOf(" ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### "),
            '1' to arrayOf("  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "),
            '2' to arrayOf(" ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####"),
            '3' to arrayOf("#####", "   # ", "  #  ", "   # ", "    #", "#   #", " ### "),
            '4' to arrayOf("   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # "),
            '5' to arrayOf("#####", "#    ", "#### ", "    #", "    #", "#   #", " ### "),
            '6' to arrayOf("  ## ", " #   ", "#    ", "#### ", "#   #", "#   #", " ### "),
            '7' to arrayOf("#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   "),
            '8' to arrayOf(" ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### "),
            '9' to arrayOf(" ### ", "#   #", "#   #", " ####", "    #", "   # ", " ##  "),
            '!' to arrayOf("  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "     ", "  #  "),
            '?' to arrayOf(" ### ", "#   #", "    #", "   # ", "  #  ", "     ", "  #  "),
            '-' to arrayOf("     ", "     ", "     ", "#####", "     ", "     ", "     "),
            '.' to arrayOf("     ", "     ", "     ", "     ", "     ", " ##  ", " ##  "),
            '+' to arrayOf("     ", "  #  ", "  #  ", "#####", "  #  ", "  #  ", "     "),
            '&' to arrayOf(" ##  ", "#  # ", " ##  ", " #   ", "# # #", "#  # ", " ## #"),
            ' ' to arrayOf("     ", "     ", "     ", "     ", "     ", "     ", "     "),
        )

        val HEART = arrayOf(" ##   ## ", "#### ####", "#########", "#########", " ####### ", "  #####  ", "   ###   ", "    #    ")
    }
}

private fun Game.say(text: String) { uiEvents.add("toast:$text") }

/** The robot was tapped on a block: remember where, and ask what to build. */
internal fun Game.pointRobot(t: RayHit) {
    val fx0 = kotlin.math.sin(player.yaw); val fz0 = -kotlin.math.cos(player.yaw)
    val (fx, fz) = if (abs(fx0) > abs(fz0)) (if (fx0 > 0) 1 to 0 else -1 to 0) else (if (fz0 > 0) 0 to 1 else 0 to -1)
    val oy = if (t.ny == 1) t.y + 1 else t.y
    robot.target = intArrayOf(t.x, oy, t.z, fx, fz)
    uiEvents.add("robot")
}

/**
 * Builds what [request] asks for at the spot the robot was pointed at. Returns what the robot says.
 * Call on the game thread.
 */
fun Game.robotBuild(request: String): String {
    val text = request.trim()
    val t = robot.target ?: return "Tap the ground with the robot first"
    if (text.isEmpty()) return "Tell the robot what to build"
    if (robot.queue.size > Robot.MAX_QUEUE / 2) return "The robot is still busy building ${robot.building}"
    val q = ArrayDeque<IntArray>()
    val p = Plan(this, t[0], t[1], t[2], t[3], t[4], q)
    val low = " ${text.lowercase()} "
    val mat = Robot.material(text)
    val num = Robot.number(text)
    val k = Robot.scale(text)
    fun size(default: Int, min: Int, max: Int) = ((num ?: default) * (if (num == null) k else 1f)).toInt().coerceIn(min, max)
    fun has(vararg words: String) = words.any { low.contains(" $it ") || low.contains(" $it") && it.length > 4 }
    val room = Chunk.HEIGHT - 2 - t[1]
    val name: String = when {
        low.trimStart().startsWith("write ") || low.trimStart().startsWith("text ") -> {
            val words = text.substringAfter(' ').trim().uppercase()
            if (words.isEmpty()) return "Tell the robot what to write, like: write DHRUV"
            writeText(p, words.take(24), mat ?: Blocks.GOLD_BLOCK); "the words \"$words\""
        }
        has("modern house", "villa", "bungalow", "modern home") -> { buildReady(p, "Modern House"); "a modern house" }
        has("metro station", "railway station", "station", "metro") -> { buildReady(p, "Metro Station"); "a metro station" }
        has("swimming pool", "pool") -> { buildReady(p, "Swimming Pool"); "a swimming pool" }
        has("watch tower", "watchtower") -> { buildReady(p, "Watch Tower"); "a watch tower" }
        has("farm", "field") -> { buildReady(p, "Farm"); "a farm" }
        has("skyscraper", "building", "apartment", "office", "hotel", "school", "hospital", "mall") -> {
            skyscraper(p, size(8, 2, room / 4), mat ?: Blocks.GLASS); "a skyscraper"
        }
        has("lighthouse") -> { lighthouse(p, size(16, 8, room - 4)); "a lighthouse" }
        has("castle", "fort", "palace", "qila", "kingdom") -> { castle(p, mat ?: Blocks.STONE_BRICKS, (15 * k).toInt().coerceIn(11, 31)); "a castle" }
        has("tower") -> { tower(p, size(14, 4, room - 3), mat ?: Blocks.STONE_BRICKS); "a tower" }
        has("pyramid") -> { pyramid(p, size(8, 2, 30), mat ?: Blocks.SANDSTONE); "a pyramid" }
        has("bridge") -> { bridge(p, size(16, 4, 80), mat ?: Blocks.PLANKS); "a bridge" }
        has("railway", "railroad", "train", "track", "rail") -> { railway(p, size(24, 10, 80)); "a railway with a train" }
        has("tree") -> { tree(p, size(8, 4, 30), low); "a tree" }
        has("wall", "fence") -> { wall(p, size(15, 3, 80), mat ?: Blocks.STONE_BRICKS); "a wall" }
        has("road", "street", "path", "highway") -> { road(p, size(24, 5, 100)); "a road" }
        has("igloo") -> { dome(p, size(4, 2, 12), Blocks.SNOW, door = true); "an igloo" }
        has("dome") -> { dome(p, size(6, 2, 24), mat ?: Blocks.GLASS, door = true); "a dome" }
        has("sphere", "ball", "globe", "planet") -> { sphere(p, size(5, 2, 20), mat ?: Blocks.CONCRETE_FIRST + 11); "a sphere" }
        has("cube", "box", "block") -> { cube(p, size(6, 2, 30), mat ?: Blocks.CONCRETE_FIRST + 14); "a cube" }
        has("fountain") -> { fountain(p); "a fountain" }
        has("temple", "mandir", "church", "mosque", "shrine") -> { temple(p, mat ?: Blocks.SANDSTONE); "a temple" }
        has("garden", "park") -> { garden(p); "a garden" }
        has("stairs", "staircase", "steps", "stair") -> { stairs(p, size(10, 2, room - 2)); "stairs" }
        has("statue", "robot", "golem") -> { statue(p, mat ?: Blocks.IRON_BLOCK); "a robot statue" }
        has("heart", "love") -> { shape(p, Robot.HEART, mat ?: Blocks.CONCRETE_FIRST + 14); "a heart" }
        has("house", "home", "hut", "cottage", "ghar") -> { buildReady(p, "Small House"); "a house" }
        else -> return "The robot doesn't know how to build \"$text\" yet. Try: " + Robot.IDEAS.shuffled().take(6).joinToString(", ")
    }
    if (q.isEmpty()) return "There's no room to build that here"
    robot.queue.addAll(q.take(Robot.MAX_QUEUE))
    robot.building = name
    robot.speed = (q.size / 90).coerceIn(12, 120)
    sound("click", player.x, player.y, player.z)
    return "Beep boop! Building $name…"
}

/** Places a few queued blocks each frame. */
internal fun Game.updateRobot() {
    if (robot.queue.isEmpty()) return
    repeat(robot.speed) {
        val b = robot.queue.removeFirstOrNull() ?: return@repeat
        setBlock(b[0], b[1], b[2], b[3], b[4])
    }
    if (clockSeconds % 0.25f < 0.02f) sound("hit_stone", player.x, player.y, player.z, 0.3f)
    if (robot.queue.isEmpty()) { sound("ding", player.x, player.y, player.z); say("The robot finished building ${robot.building}!") }
}

// ---------------------------------------------------------------- the robot's own shapes

/** A picture made of rows (top first), standing up a few steps in front, facing the player. */
private fun shape(p: Plan, rows: Array<String>, block: Int, d: Int = 4, a0: Int = -rows[0].length / 2) {
    for ((r, row) in rows.withIndex()) for ((c, ch) in row.withIndex()) if (ch != ' ') p.set(a0 + c, d, rows.size - 1 - r, block)
}

private fun writeText(p: Plan, text: String, block: Int) {
    val glyphs = text.map { Robot.FONT[it] ?: Robot.FONT.getValue('?') }
    val width = glyphs.size * 6 - 1
    for ((i, g) in glyphs.withIndex()) shape(p, g, block, d = 4, a0 = -width / 2 + i * 6)
}

private fun castle(p: Plan, m: Int, w: Int) {
    val h = w / 2 + 1
    p.site(-h - 1, h + 1, 1, w + 2, 12, Blocks.COBBLESTONE)
    for (a in -h..h) for (d in 1..w) p.set(a, d, -1, if (a in -1..1 || d == 1) Blocks.COBBLESTONE else Blocks.GRASS)
    // Walls with battlements.
    for (a in -h..h) for (d in 1..w) {
        if (a != -h && a != h && d != 1 && d != w) continue
        for (y in 0..5) p.set(a, d, y, m)
        if ((a + d) % 2 == 0) p.set(a, d, 6, m)
    }
    // Corner towers.
    for ((ca, cd) in listOf(-h to 1, h to 1, -h to w, h to w)) {
        for (a in ca - 1..ca + 1) for (d in cd - 1..cd + 1) for (y in 0..9) p.set(a, d, y, m)
        for (a in ca - 1..ca + 1) for (d in cd - 1..cd + 1) if ((a + d) % 2 == 0) p.set(a, d, 10, m)
        p.set(ca, cd, 10, Blocks.LANTERN)
    }
    // Gate and a keep in the middle.
    for (a in -1..1) for (y in 0..2) p.set(a, 1, y, if (y == 2 && a != 0) m else Blocks.AIR)
    for (y in 0..1) p.set(0, 1, y, Blocks.AIR)
    val kd = w / 2 + 2
    for (a in -2..2) for (d in kd - 2..kd + 2) for (y in 0..7) if (abs(a) == 2 || abs(d - kd) == 2) p.set(a, d, y, m)
    for (a in -2..2) for (d in kd - 2..kd + 2) p.set(a, d, 8, m)
    for (a in -2..2) for (d in kd - 2..kd + 2) if ((a + d) % 2 == 0 && (abs(a) == 2 || abs(d - kd) == 2)) p.set(a, d, 9, m)
    for (y in 0..1) p.set(0, kd - 2, y, Blocks.AIR)
    p.set(0, kd, 9, Blocks.BANNER_FIRST + 14, p.toward)
}

private fun tower(p: Plan, height: Int, m: Int) {
    p.site(-3, 3, 1, 7, height + 3, Blocks.COBBLESTONE)
    for (a in -2..2) for (d in 2..6) for (y in 0 until height) {
        if (a != -2 && a != 2 && d != 2 && d != 6) continue
        p.set(a, d, y, if ((y % 5 == 3) && (a == 0 || d == 4)) Blocks.GLASS_PANE else m)
    }
    p.door(0, 2)
    for (y in 0..height) p.set(0, 5, y, Blocks.LADDER, p.toward)
    for (a in -3..3) for (d in 1..7) if (!(a == 0 && d == 5)) p.set(a, d, height, m)
    for (a in -3..3) for (d in 1..7) if (a == -3 || a == 3 || d == 1 || d == 7) p.set(a, d, height + 1, if ((a + d) % 2 == 0) m else Blocks.AIR)
    p.set(-3, 1, height + 1, Blocks.LANTERN); p.set(3, 7, height + 1, Blocks.LANTERN)
}

private fun skyscraper(p: Plan, floors: Int, m: Int) {
    val frame = Blocks.CONCRETE_FIRST + 7
    p.site(-4, 4, 1, 9, floors * 4 + 2, Blocks.SMOOTH_STONE)
    for (f in 0 until floors) {
        val base = f * 4
        for (a in -3..3) for (d in 2..8) {
            p.set(a, d, base - (if (f == 0) 1 else 0), if (f == 0) Blocks.POLISHED_DIORITE else Blocks.SMOOTH_STONE)
            for (y in base + 1..base + 3) {
                val edge = a == -3 || a == 3 || d == 2 || d == 8
                if (!edge) continue
                val corner = (a == -3 || a == 3) && (d == 2 || d == 8)
                p.set(a, d, y, if (corner) frame else m)
            }
        }
        p.set(0, 5, base + 3, Blocks.SEA_LANTERN)
        for (y in base..base + 3) p.set(2, 7, y, Blocks.LADDER, p.left)
        if (f > 0) p.set(2, 7, base, Blocks.LADDER, p.left)
    }
    for (a in -3..3) for (d in 2..8) p.set(a, d, floors * 4, frame)
    p.set(2, 7, floors * 4, Blocks.AIR)
    for (y in 1..2) p.set(0, 2, y, Blocks.AIR)
    p.set(0, 2, 0, Blocks.AIR)
    p.door(0, 2, Blocks.IRON_DOOR)
}

private fun lighthouse(p: Plan, height: Int) {
    p.site(-3, 3, 1, 7, height + 5, Blocks.STONE_BRICKS)
    for (y in 0 until height) for (a in -2..2) for (d in 2..6) {
        val r = abs(a) + abs(d - 4)
        if (r !in 2..3 || (abs(a) == 2 && abs(d - 4) == 2)) continue
        p.set(a, d, y, if ((y / 2) % 2 == 0) Blocks.CONCRETE_FIRST else Blocks.CONCRETE_FIRST + 14)
    }
    for (y in 0 until height) p.set(0, 5, y, Blocks.LADDER, p.toward)
    p.door(0, 2)
    for (a in -3..3) for (d in 1..7) p.set(a, d, height, Blocks.STONE_BRICKS)
    p.set(0, 5, height, Blocks.AIR)
    for (a in -1..1) for (d in 3..5) for (y in height + 1..height + 3) p.set(a, d, y, if (a == 0 && d == 4) Blocks.SEA_LANTERN else Blocks.GLASS)
    p.set(0, 5, height + 1, Blocks.AIR); p.set(0, 5, height + 2, Blocks.AIR)
    for (a in -1..1) for (d in 3..5) p.set(a, d, height + 4, Blocks.CONCRETE_FIRST + 14)
    for (a in -3..3) for (d in 1..7) if (a == -3 || a == 3 || d == 1 || d == 7) p.set(a, d, height + 1, Blocks.OAK_FENCE)
}

private fun pyramid(p: Plan, half: Int, m: Int) {
    val cd = half + 1
    p.site(-half, half, 1, 2 * half + 1, 0, m)
    for (y in 0..half) {
        val r = half - y
        for (a in -r..r) for (d in cd - r..cd + r) if (abs(a) == r || abs(d - cd) == r || y == half) p.set(a, d, y, m)
    }
    for (y in 0..1) p.set(0, 1, y, Blocks.AIR)
    p.set(0, cd, half + 1, Blocks.GOLD_BLOCK)
}

private fun bridge(p: Plan, len: Int, m: Int) {
    for (d in 1..len) {
        for (a in -1..1) { p.set(a, d, -1, m); for (y in 0..3) p.set(a, d, y, Blocks.AIR) }
        for (a in intArrayOf(-2, 2)) { p.set(a, d, -1, m); p.set(a, d, 0, if (d % 6 == 3) Blocks.LANTERN else Blocks.OAK_FENCE) }
        // Pillars down to the ground or water every few blocks.
        if (d % 6 == 0) for (a in intArrayOf(-2, 2)) {
            var y = -2
            while (y > -24 && !Blocks.solid[p.g.world.getBlock(p.x(a, d), p.oy + y, p.z(a, d))]) { p.set(a, d, y, Blocks.STONE_BRICKS); y-- }
        }
    }
}

private fun railway(p: Plan, half: Int) {
    for (a in -half..half) {
        for (d in 2..4) for (y in 0..3) p.set(a, d, y, Blocks.AIR)
        p.set(a, 3, -1, Blocks.RAILWAY_BALLAST)
        p.set(a, 3, 0, if (a == 0) Blocks.STOP_RAIL else Blocks.RAIL, p.railAcross)
        p.set(a, 2, -1, Blocks.RAILWAY_BALLAST); p.set(a, 4, -1, Blocks.RAILWAY_BALLAST)
        if (a in -4..4) { p.set(a, 2, 0, Blocks.STATION_PLATFORM); p.set(a, 4, 0, Blocks.STATION_PLATFORM) }
    }
    // A train: an engine pulling two coaches, waiting at the stop.
    val g = p.g
    fun car(a: Float, kind: Int) = Cart(p.x(0, 3) + p.rx * a + 0.5f, p.oy.toFloat(), p.z(0, 3) + p.rz * a + 0.5f, kind)
        .also { it.hx = p.rx.toFloat(); it.hz = p.rz.toFloat(); it.yaw = kotlin.math.atan2(it.hx, -it.hz); g.carts.list.add(it) }
    val engine = car(0f, Cart.ENGINE)
    val c1 = car(-3.1f, Cart.COACH).also { it.leader = engine }
    car(-6.2f, Cart.COACH).leader = c1
}

private fun tree(p: Plan, height: Int, words: String) {
    val (log, leaves) = when {
        words.contains("cherry") || words.contains("pink") -> Blocks.CHERRY_LOG to Blocks.CHERRY_LEAVES
        words.contains("birch") -> Blocks.BIRCH_LOG to Blocks.BIRCH_LEAVES
        words.contains("spruce") || words.contains("pine") || words.contains("christmas") -> Blocks.SPRUCE_LOG to Blocks.SPRUCE_LEAVES
        words.contains("jungle") -> Blocks.JUNGLE_LOG to Blocks.JUNGLE_LEAVES
        words.contains("dark") -> Blocks.DARK_OAK_LOG to Blocks.DARK_OAK_LEAVES
        else -> Blocks.LOG to Blocks.LEAVES
    }
    val d0 = 4
    val r = (height / 2).coerceAtLeast(2)
    for (y in 0 until height) p.set(0, d0, y, log)
    for (a in -r..r) for (d in -r..r) for (y in -r..r) {
        if (a * a + d * d + y * y > r * r) continue
        if (a == 0 && d == 0 && y < 0) continue
        p.set(a, d0 + d, height - 1 + y, leaves)
    }
    if (words.contains("christmas")) for (a in intArrayOf(-r / 2, r / 2)) p.set(a, d0, height - 1, Blocks.GLOWSTONE)
}

private fun wall(p: Plan, len: Int, m: Int) {
    for (a in -len / 2..len / 2) {
        for (y in 0..3) p.set(a, 3, y, m)
        if (a % 2 == 0) p.set(a, 3, 4, m)
        var y = -1
        while (y > -8 && !Blocks.solid[p.g.world.getBlock(p.x(a, 3), p.oy + y, p.z(a, 3))]) { p.set(a, 3, y, m); y-- }
    }
}

private fun road(p: Plan, len: Int) {
    val asphalt = Blocks.CONCRETE_FIRST + 7; val paint = Blocks.CONCRETE_FIRST
    for (d in 1..len) {
        for (a in -3..3) { for (y in 0..3) p.set(a, d, y, Blocks.AIR); p.set(a, d, -1, if (abs(a) == 3) Blocks.SMOOTH_STONE else asphalt) }
        if (d % 4 < 2) p.set(0, d, -1, paint)
        if (d % 10 == 5) for (a in intArrayOf(-3, 3)) { p.set(a, d, 0, Blocks.OAK_FENCE); p.set(a, d, 1, Blocks.OAK_FENCE); p.set(a, d, 2, Blocks.LANTERN) }
    }
}

private fun sphere(p: Plan, r: Int, m: Int) {
    val cd = r + 2
    for (a in -r..r) for (d in -r..r) for (y in -r..r) {
        val dist = sqrt((a * a + d * d + y * y).toFloat())
        if (dist <= r + 0.5f && dist > r - 0.7f) p.set(a, cd + d, r + y, m)
    }
}

private fun dome(p: Plan, r: Int, m: Int, door: Boolean) {
    val cd = r + 2
    p.site(-r, r, cd - r, cd + r, r + 1, if (m == Blocks.SNOW) Blocks.SNOW else Blocks.SMOOTH_STONE)
    for (a in -r..r) for (d in -r..r) for (y in 0..r) {
        val dist = sqrt((a * a + d * d + y * y).toFloat())
        if (dist <= r + 0.5f && dist > r - 0.7f) p.set(a, cd + d, y, m)
    }
    if (door) for (y in 0..1) p.set(0, cd - r, y, Blocks.AIR)
    if (door) p.set(0, cd, 0, Blocks.TORCH)
}

private fun cube(p: Plan, n: Int, m: Int) {
    for (a in 0 until n) for (d in 0 until n) for (y in 0 until n) {
        val edges = listOf(a == 0 || a == n - 1, d == 0 || d == n - 1, y == 0 || y == n - 1).count { it }
        if (edges >= 1) p.set(a - n / 2, d + 2, y, m)
    }
}

private fun fountain(p: Plan) {
    p.site(-3, 3, 1, 7, 5, Blocks.STONE_BRICKS)
    for (a in -3..3) for (d in 1..7) {
        val edge = abs(a) == 3 || d == 1 || d == 7
        p.set(a, d, 0, if (edge) Blocks.STONE_BRICK_SLAB else Blocks.WATER)
        if (!edge) p.set(a, d, -1, Blocks.CONCRETE_FIRST + 3)
    }
    for (y in 0..2) p.set(0, 4, y, Blocks.QUARTZ_BLOCK)
    p.set(0, 4, 3, Blocks.WATER)
}

private fun temple(p: Plan, m: Int) {
    p.site(-5, 5, 1, 11, 16, m)
    // Stepped base.
    for (step in 0..1) for (a in -5 + step..5 - step) for (d in 1 + step..11 - step) p.set(a, d, step, m)
    for (a in -3..3) for (d in 3..9) for (y in 2..6) if (abs(a) == 3 || d == 3 || d == 9) p.set(a, d, y, m)
    for (y in 2..4) p.set(0, 3, y, Blocks.AIR)
    for (a in -1..1) p.set(a, 2, 1, Blocks.STONE_BRICK_SLAB)
    // Tapering tower on top with a golden tip.
    for (level in 0..5) {
        val r = 3 - level / 2
        for (a in -r..r) for (d in 6 - r..6 + r) p.set(a, d, 7 + level, m)
    }
    p.set(0, 6, 13, Blocks.GOLD_BLOCK); p.set(0, 6, 14, Blocks.GOLD_BLOCK); p.set(0, 6, 15, Blocks.BANNER_FIRST + 1, p.toward)
    p.set(0, 7, 2, Blocks.BELL); p.set(-2, 8, 2, Blocks.LANTERN); p.set(2, 8, 2, Blocks.LANTERN)
    for (a in -2..2) for (d in 4..8) if (!(a == 0 && d == 7)) p.set(a, d, 1, Blocks.CARPET_FIRST + 14)
}

private fun Game.garden(p: Plan) {
    p.site(-6, 6, 1, 13, 1, Blocks.GRASS)
    val flowers = intArrayOf(Blocks.FLOWER_RED, Blocks.FLOWER_YELLOW, Blocks.FLOWER_FIRST + 1, Blocks.FLOWER_FIRST + 2, Blocks.FLOWER_FIRST + 6)
    val rnd = java.util.Random()
    for (a in -6..6) for (d in 1..13) {
        val path = a == 0 || d == 7
        val pond = abs(a) <= 1 && abs(d - 7) <= 1
        when {
            pond -> p.set(a, d, -1, Blocks.WATER)
            path -> p.set(a, d, -1, Blocks.DIRT_PATH)
            abs(a) == 6 || d == 1 || d == 13 -> p.set(a, d, 0, Blocks.OAK_FENCE)
            rnd.nextInt(3) == 0 -> p.set(a, d, 0, flowers[rnd.nextInt(flowers.size)])
        }
    }
    p.set(0, 1, 0, Blocks.OAK_FENCE_GATE, p.toward)
    p.set(0, 7, -1, Blocks.WATER); p.set(0, 7, 0, Blocks.LILY_PAD)
    for ((a, d) in listOf(-3 to 4, 3 to 4, -3 to 10, 3 to 10)) p.set(a, d, 0, Blocks.SAPLING_FIRST + rnd.nextInt(6))
    p.set(-2, 7, 0, Blocks.SOFA, p.right); p.set(2, 7, 0, Blocks.SOFA, p.left)
    p.set(-1, 3, 0, Blocks.LANTERN); p.set(1, 11, 0, Blocks.LANTERN)
}

private fun stairs(p: Plan, height: Int) {
    for (k in 0 until height) {
        p.set(-1, 1 + k, k, Blocks.STONE_BRICK_STAIRS, p.toward); p.set(0, 1 + k, k, Blocks.STONE_BRICK_STAIRS, p.toward); p.set(1, 1 + k, k, Blocks.STONE_BRICK_STAIRS, p.toward)
        for (a in -1..1) for (y in k + 1..k + 3) p.set(a, 1 + k, y, Blocks.AIR)
        for (a in -1..1) for (y in 0 until k) p.set(a, 1 + k, y, Blocks.STONE_BRICKS)
    }
}

private fun statue(p: Plan, m: Int) {
    val d = 4
    for (y in 0..2) { p.set(-1, d, y, m); p.set(1, d, y, m) }           // legs
    for (a in -2..2) for (y in 3..6) p.set(a, d, y, m)                   // body
    for (y in 3..6) { p.set(-3, d, y, m); p.set(3, d, y, m) }            // arms
    for (a in -1..1) for (y in 7..9) p.set(a, d, y, m)                   // head
    p.set(-1, d - 1, 8, Blocks.REDSTONE_LAMP_ON); p.set(1, d - 1, 8, Blocks.REDSTONE_LAMP_ON)   // glowing eyes
    p.set(0, d, 10, Blocks.IRON_BARS)
}
