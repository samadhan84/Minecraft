package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.floorInt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * Festivals of India (and Christmas). The game follows the calendar on the device: from 5 days before a festival
 * until 1 day after it ends, that festival's update is on: a "Festival" tab in the creative inventory with its
 * items (sweets to eat, colours to throw, flags, idols, trees and rangolis to put up, fireworks...), and a message
 * when you open a world. When the window closes the items stop working until the festival comes round again.
 *
 * Dates that move every year (Holi, Diwali, Eid...) are in the table below; the fixed ones (Sankranti, Republic
 * Day, Baisakhi, Independence Day, Christmas) repeat every year. Add a year to the table to extend it.
 */

/** One festival item: how it looks (a [shape] in [rgb]) and what it does ([action]). */
class FestItem(val name: String, val shape: String, val rgb: Int, val action: String, val food: Int = 0)

class Festival(val key: String, val name: String, val greeting: String, val days: Int,
               val fixed: String?, val dates: Map<Int, String>, val items: List<FestItem>) {
    /** The first day of the festival in [year] (yyyy-MM-dd), or null when this year isn't in the table. */
    fun start(year: Int): String? = fixed?.let { "$year-$it" } ?: dates[year]?.let { "$year-$it" }
}

/** A festival whose window is open today. */
class ActiveFestival(val festival: Festival, val daysToStart: Int, val daysSinceEnd: Int) {
    val started get() = daysToStart <= 0
}

object Festivals {
    /** Days before the festival that its update arrives, and days after it ends that it stays. */
    const val BEFORE = 5
    const val AFTER = 1

    private fun f(name: String, shape: String, rgb: Int, action: String, food: Int = 0) = FestItem(name, shape, rgb, action, food)
    private val SAFFRON = 0xFF9933; private val GREEN = 0x138808
    private val diya = f("Diya", "lamp", 0xE08A1E, "cracker")
    private val rangoli = f("Rangoli", "mandala", 0xE0457B, "build:rangoli")
    private val flag = f("Tricolour Flag", "flag", SAFFRON, "build:flag")
    private val tiranga = f("Tiranga Rocket", "rocket", SAFFRON, "cracker")
    private val prasad = f("Prasad", "ball", 0xE8A33C, "food", 4)

    /** The fireworks of Diwali, already items (see Fireworks.kt). */
    private val crackers = Cracker.ORIGINAL.map { FestItem(it.itemName, "", 0, "existing") }

    val all: List<Festival> = listOf(
        Festival("sankranti", "Makar Sankranti & Pongal", "Happy Makar Sankranti! Fly the kites!", 2, "01-14", emptyMap(), listOf(
            f("Patang (Kite)", "kite", 0xF0602A, "kite"), f("Til Laddu", "ball", 0x8A5A2B, "food", 4), f("Sweet Pongal", "pot", 0xF2D36B, "food", 5), rangoli)),
        Festival("republic", "Republic Day", "Happy Republic Day! Jai Hind!", 1, "01-26", emptyMap(), listOf(
            flag, tiranga, f("Tricolour Barfi", "slice", GREEN, "food", 4))),
        Festival("shivratri", "Maha Shivratri", "Har Har Mahadev! Happy Maha Shivratri!", 1, null,
            mapOf(2026 to "02-15", 2027 to "03-06"), listOf(diya, f("Bel Prasad", "ball", 0x5BA85B, "food", 4))),
        Festival("holi", "Holi", "Happy Holi! Bura na mano, Holi hai!", 2, null,
            mapOf(2026 to "03-03", 2027 to "03-21"), listOf(
                f("Gulal (Holi Colour)", "powder", 0xE0457B, "gulal"), f("Pichkari", "bottle", 0x2E86DE, "jet"),
                f("Gujiya", "slice", 0xD9A441, "food", 5), f("Thandai", "bottle", 0xF3EBD3, "food", 4))),
        Festival("gudipadwa", "Gudi Padwa & Ugadi", "Happy Gudi Padwa & Ugadi! Navin Varsh!", 1, null,
            mapOf(2026 to "03-19", 2027 to "04-07"), listOf(f("Gudi", "gudi", 0xF5C518, "build:gudi"), f("Puran Poli", "slice", 0xE9B04A, "food", 5), rangoli)),
        Festival("eid_fitr", "Eid ul-Fitr", "Eid Mubarak! (the date can move by a day)", 1, null,
            mapOf(2026 to "03-21", 2027 to "03-10"), listOf(f("Eid Lantern", "lantern", 0xF5C518, "build:lantern"), f("Sheer Khurma", "pot", 0xEBD9B0, "food", 5), f("Sewai", "pot", 0xD9B26B, "food", 4))),
        Festival("ramnavami", "Ram Navami", "Jai Shri Ram! Happy Ram Navami!", 1, null,
            mapOf(2026 to "03-26", 2027 to "04-15"), listOf(diya, f("Panakam", "bottle", 0xE0B84C, "food", 3), prasad)),
        Festival("baisakhi", "Baisakhi", "Happy Baisakhi! Bhangra time!", 1, "04-14", emptyMap(), listOf(
            f("Dhol", "drum", 0xB8651B, "dhol"), f("Pinni", "ball", 0xD9A441, "food", 4), f("Lassi", "bottle", 0xF3EBD3, "food", 3))),
        Festival("eid_adha", "Eid al-Adha", "Eid al-Adha Mubarak! (the date can move by a day)", 1, null,
            mapOf(2026 to "05-27", 2027 to "05-17"), listOf(f("Eid Lantern", "lantern", 0xF5C518, "build:lantern"), f("Sheer Khurma", "pot", 0xEBD9B0, "food", 5))),
        Festival("independence", "Independence Day", "Happy Independence Day! Jai Hind!", 1, "08-15", emptyMap(), listOf(
            flag, tiranga, f("Tricolour Barfi", "slice", GREEN, "food", 4))),
        Festival("onam", "Onam", "Happy Onam! Onashamsakal!", 1, null,
            mapOf(2026 to "08-26"), listOf(f("Pookalam", "mandala", 0xF2B01E, "build:pookalam"), f("Payasam", "pot", 0xF2E2B3, "food", 5), f("Banana Chips", "slice", 0xE8C547, "food", 3))),
        Festival("rakhi", "Raksha Bandhan", "Happy Raksha Bandhan! Rakhi Mubarak!", 1, null,
            mapOf(2026 to "08-28", 2027 to "08-17"), listOf(f("Rakhi", "rakhi", 0xE0457B, "rakhi"), f("Rasgulla", "ball", 0xF7F2E2, "food", 4), f("Kaju Katli", "slice", 0xD7D2B5, "food", 4))),
        Festival("janmashtami", "Janmashtami", "Jai Shri Krishna! Happy Janmashtami!", 1, null,
            mapOf(2026 to "09-04", 2027 to "08-25"), listOf(f("Bansuri", "flute", 0x6FA8DC, "flute"), f("Makhan", "ball", 0xF6E58D, "food", 4), prasad)),
        Festival("ganesh", "Ganesh Chaturthi", "Ganpati Bappa Morya! Happy Ganesh Chaturthi!", 10, null,
            mapOf(2026 to "09-14", 2027 to "09-04"), listOf(f("Ganesh Idol", "idol", 0xF08A24, "build:ganesh"), f("Modak", "ball", 0xF2E2B3, "food", 5), diya)),
        Festival("navratri", "Navratri & Dussehra", "Happy Navratri & Dussehra! Garba time!", 10, null,
            mapOf(2026 to "10-11", 2027 to "10-01"), listOf(f("Dandiya Sticks", "stick", 0xD94F70, "dandiya"), f("Ravan Effigy", "effigy", 0xC0392B, "build:effigy"), rangoli, diya)),
        Festival("diwali", "Diwali", "Happy Diwali! Shubh Deepavali!", 6, null,
            mapOf(2026 to "11-06", 2027 to "10-27"), crackers + listOf(diya, tiranga, rangoli, f("Mithai Box", "gift", 0xE0457B, "food", 6))),
        Festival("gurunanak", "Guru Nanak Jayanti", "Happy Guru Nanak Jayanti! Waheguru!", 1, null,
            mapOf(2026 to "11-24", 2027 to "11-15"), listOf(diya, f("Langar Halwa", "slice", 0xE8A33C, "food", 5))),
        Festival("christmas", "Christmas", "Merry Christmas! Ho ho ho!", 1, "12-25", emptyMap(), listOf(
            f("Christmas Tree", "tree", 0x1F7A3A, "build:tree"), f("Gift Box", "gift", 0xD62839, "gift"), f("Plum Cake", "slice", 0x6B3A1E, "food", 6),
            f("Candy Cane", "cane", 0xE63946, "food", 3), f("Snowman", "snowman", 0xF2F6FA, "build:snowman"))),
    )

    private val byName: Map<String, FestItem> = all.flatMap { it.items }.associateBy { it.name }

    /** Every festival item that is not one of the original Diwali fireworks (those are listed with the other items). */
    val newItems: List<FestItem> = all.flatMap { it.items }.filter { it.action != "existing" }.distinctBy { it.name }

    fun item(name: String?): FestItem? = byName[name]
    fun isFestivalItem(name: String?) = name != null && name in byName

    // ---------------------------------------------------------------- the calendar

    /** Days since 1970-01-01 of a date written yyyy-MM-dd. */
    fun dayNumber(date: String): Long {
        val y0 = date.substring(0, 4).toInt(); val m = date.substring(5, 7).toInt(); val d = date.substring(8, 10).toInt()
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }

    private fun today() = PlayLimit.today()

    /** The festival whose update is on today (the earliest to start if two overlap), or null. */
    fun active(today: String = today()): ActiveFestival? {
        val now = dayNumber(today); val year = today.substring(0, 4).toInt()
        var best: ActiveFestival? = null; var bestStart = Long.MAX_VALUE
        for (f in all) for (y in year - 1..year + 1) {
            val s = dayNumber(f.start(y) ?: continue)
            val end = s + f.days - 1
            if (now < s - BEFORE || now > end + AFTER) continue
            if (s < bestStart) { bestStart = s; best = ActiveFestival(f, (s - now).toInt(), (now - end).toInt().coerceAtLeast(0)) }
        }
        return best
    }

    /** The next festival to start after today, and in how many days (0 = it has started). */
    fun next(today: String = today()): Pair<Festival, Int>? {
        val now = dayNumber(today); val year = today.substring(0, 4).toInt()
        var best: Pair<Festival, Int>? = null
        for (f in all) for (y in year..year + 1) {
            val s = dayNumber(f.start(y) ?: continue)
            if (s + f.days - 1 < now) continue
            val d = (s - now).toInt().coerceAtLeast(0)
            if (best == null || d < best.second) best = f to d
        }
        return best
    }

    /** Whether the item can be used today: some festival whose update is on has it. */
    fun usable(name: String?, today: String = today()): Boolean = active(today)?.festival?.items?.any { it.name == name } == true

    /** The festival (and date) an item belongs to next, for the "come back" message. */
    fun whenAgain(name: String?, today: String = today()): String {
        val now = dayNumber(today); val year = today.substring(0, 4).toInt()
        var best: Pair<Festival, Long>? = null
        for (f in all) if (f.items.any { it.name == name }) for (y in year..year + 1) {
            val s = dayNumber(f.start(y) ?: continue) - BEFORE
            if (s >= now && (best == null || s < best.second)) best = f to s
        }
        return best?.let { (f, s) -> "${f.name}: in ${s - now} days" } ?: "its festival"
    }

    /** What to tell the player when a world opens, or null when no festival is near. */
    fun announcement(today: String = today()): String? {
        val a = active(today) ?: return null
        val f = a.festival
        return when {
            a.daysToStart > 0 -> "${f.name} is coming in ${a.daysToStart} day${if (a.daysToStart == 1) "" else "s"}! The ${f.name} update is on: find it in the Festival tab"
            a.daysSinceEnd > 0 -> "${f.name} is over. The update goes away tomorrow: enjoy it one last time!"
            else -> f.greeting + " The ${f.name} items are in the Festival tab"
        }
    }
}

// ---------------------------------------------------------------- using the items

/** Using a festival item. Returns true when handled (food is eaten the normal way, fireworks by Fireworks.kt). */
internal fun Game.festivalUse(item: com.vishucraft.game.world.ItemDef?, t: RayHit?): Boolean {
    val fi = Festivals.item(item?.name) ?: return false
    if (fi.action == "food" || fi.action == "existing" || fi.action == "cracker") return false
    if (!Festivals.usable(fi.name)) {
        uiEvents.add("toast:${fi.name} is for a festival that is over. It comes back with ${Festivals.whenAgain(fi.name)}")
        return true
    }
    val p = player
    val fx = sin(p.yaw); val fz = -cos(p.yaw)
    val fw = fireworks
    when {
        fi.action == "gulal" -> {
            // A cloud of colour thrown all round you.
            fw.colourBurst(p.x + fx * 2f, p.y + 1.6f, p.z + fz * 2f, 160, 5f, Fireworks.HOLI, 1.6f, 3f)
            sound("pop", p.x, p.y, p.z, 0.8f); consumeHeld()
        }
        fi.action == "jet" -> {
            // The pichkari squirts a jet of colour forward.
            repeat(70) {
                val s = 9f + Math.random().toFloat() * 4f
                fw.sparks.add(Spark(p.x + fx * 0.8f, p.y + 1.3f, p.z + fz * 0.8f, fx * s + (Math.random().toFloat() - 0.5f) * 1.5f, 2f + Math.random().toFloat() * 2f,
                    fz * s + (Math.random().toFloat() - 0.5f) * 1.5f, 1.3f, Fireworks.HOLI[(Math.random() * Fireworks.HOLI.size).toInt()], 7f))
            }
            sound("splash", p.x, p.y, p.z, 0.6f)
        }
        fi.action == "kite" -> {
            fw.colourBurst(p.x + fx * 3f, p.y + 3f, p.z + fz * 3f, 90, 3.5f, Fireworks.HOLI, 3.5f, -0.8f)
            sound("pop", p.x, p.y, p.z, 0.6f)
        }
        fi.action == "dandiya" -> {
            for (k in 0 until 100) {
                val a = k * 0.3f; val r = 0.5f + k * 0.03f
                fw.sparks.add(Spark(p.x + cos(a) * r, p.y + 1.2f + k * 0.02f, p.z + sin(a) * r, cos(a) * 1.5f, 1f, sin(a) * 1.5f, 1.6f, Fireworks.HOLI[k % Fireworks.HOLI.size], 1f))
            }
            sound("tv", p.x, p.y, p.z, 0.6f)
        }
        fi.action == "dhol" -> { sound("dhol", p.x, p.y, p.z, 1.4f); fw.colourBurst(p.x + fx * 1.5f, p.y + 1.2f, p.z + fz * 1.5f, 50, 3f, Fireworks.HOLI, 1.2f, 3f) }
        fi.action == "flute" -> { sound("metro_chime", p.x, p.y, p.z, 1f); fw.colourBurst(p.x + fx * 1.5f, p.y + 1.6f, p.z + fz * 1.5f, 60, 2f, Fireworks.BLUES, 2.2f, -0.3f) }
        fi.action == "rakhi" -> {
            heal(4f)
            fw.colourBurst(p.x, p.y + 1.8f, p.z, 60, 2f, Fireworks.PINKS, 2f, -0.4f)
            sound("ding", p.x, p.y, p.z, 0.8f); consumeHeld()
        }
        fi.action == "gift" -> {
            val prizes = listOf("Iron Ingot" to 3, "Gold Ingot" to 2, "Emerald" to 1, "Golden Apple" to 1, "Diamond" to 1, "Cookie" to 6)
            val (name, n) = prizes[(Math.random() * prizes.size).toInt()]
            drops.spawn(ItemStack(Items.find(name), n), p.x + fx, p.y + 1f, p.z + fz)
            fw.colourBurst(p.x + fx * 1.2f, p.y + 1.2f, p.z + fz * 1.2f, 60, 3f, Fireworks.HOLI, 1.5f, 3f)
            uiEvents.add("toast:A gift! You got $n × $name"); sound("ding", p.x, p.y, p.z, 0.8f); consumeHeld()
        }
        fi.action.startsWith("build:") -> {
            if (t == null || t.ny != 1) { uiEvents.add("toast:Tap the ground to put up the ${fi.name}"); return true }
            buildFestival(fi.action.removePrefix("build:"), t.x, t.y, t.z, sin(p.yaw), -cos(p.yaw))
            consumeHeld(); sound("hit_wood", t.x + 0.5f, t.y + 1f, t.z + 0.5f, 0.8f)
        }
    }
    return true
}

/** Puts up a festival decoration on the ground block (x, y, z), facing the way the player looks. */
internal fun Game.buildFestival(kind: String, x: Int, y: Int, z: Int, lookX: Float, lookZ: Float) {
    val sx = if (abs(lookX) > abs(lookZ)) (if (lookX > 0) 1 else -1) else 0
    val sz = if (sx == 0) (if (lookZ > 0) 1 else -1) else 0
    val rx = -sz; val rz = sx   // to the right
    val cx = x + sx * 3; val cz = z + sz * 3   // a few blocks in front, so you don't build on yourself
    val gy = y   // the ground block; things stand on y + 1
    fun put(dx: Int, dy: Int, dz: Int, id: Int, meta: Int = 0) = setBlock(cx + rx * dx + sx * dz, gy + 1 + dy, cz + rz * dx + sz * dz, id, meta)
    fun ground(dx: Int, dz: Int, id: Int) = setBlock(cx + rx * dx + sx * dz, gy, cz + rz * dx + sz * dz, id)
    val C = Blocks.CONCRETE_FIRST
    when (kind) {
        "flag" -> {
            for (h in 0..11) put(0, h, 0, Blocks.OAK_FENCE)
            put(0, 12, 0, Blocks.GOLD_BLOCK)
            for (w in 1..6) { put(w, 11, 0, C + 1); put(w, 10, 0, C + 0); put(w, 9, 0, C + 13) }   // saffron, white, green
            put(3, 10, 0, C + 11)   // the blue wheel
        }
        "gudi" -> {
            for (h in 0..7) put(0, h, 0, Blocks.OAK_FENCE)
            for (h in 4..7) { put(1, h, 0, C + 4); put(-1, h, 0, C + 4) }       // the yellow cloth
            put(0, 8, 0, Blocks.IRON_BLOCK)                                       // the silver pot on top
            put(0, 9, 0, Blocks.GOLD_BLOCK)
            for (dx in -1..1) put(dx, 3, 0, Blocks.LEAVES)                        // neem leaves and flowers
            put(2, 6, 0, C + 14); put(-2, 6, 0, C + 14)
        }
        "tree" -> {
            for (h in 0..1) put(0, h, 0, Blocks.LOG)
            val rnd = java.util.Random((x * 31 + z).toLong())
            for (h in 2..8) {
                val r = when { h <= 3 -> 3; h <= 5 -> 2; h <= 7 -> 1; else -> 0 }
                for (dx in -r..r) for (dz in -r..r) if (abs(dx) + abs(dz) <= r + (if (r > 1) 1 else 0))
                    put(dx, h, dz, if (rnd.nextInt(7) == 0) Blocks.SEA_LANTERN else Blocks.SPRUCE_LEAVES)
            }
            put(0, 9, 0, Blocks.GLOWSTONE)                                         // the star
            for ((dx, dz) in listOf(2 to 2, -2 to 2, 2 to -2, -2 to -2, 3 to 0, -3 to 0)) put(dx, 0, dz, C + intArrayOf(14, 13, 11, 4, 3, 10)[(abs(dx) + abs(dz) * 2) % 6])   // gifts
        }
        "snowman" -> {
            put(0, 0, 0, C + 0); put(0, 1, 0, C + 0); put(0, 2, 0, C + 0)
            put(0, 3, 0, Blocks.PUMPKIN)
            put(1, 2, 0, Blocks.OAK_FENCE); put(-1, 2, 0, Blocks.OAK_FENCE)
            put(0, 1, 1, C + 15); put(0, 2, 1, C + 14)                              // buttons and a red scarf
        }
        "ganesh" -> {
            for (dx in -3..3) for (dz in -3..3) ground(dx, dz, if (abs(dx) == 3 || abs(dz) == 3) C + 4 else C + 1)
            for (dx in -1..0) for (dz in -1..0) for (h in 0..2) put(dx, h, dz, C + 1)   // the idol
            put(0, 3, 0, Blocks.GOLD_BLOCK); put(-1, 3, 0, Blocks.GOLD_BLOCK)            // the crown
            put(0, 1, 1, C + 1); put(0, 0, 1, C + 1)                                    // the trunk
            for ((dx, dz) in listOf(3 to 3, -3 to 3, 3 to -3, -3 to -3)) { put(dx, 0, dz, Blocks.OAK_FENCE); put(dx, 1, dz, Blocks.SEA_LANTERN) }
            for (dx in -2..2) put(dx, 0, -2, C + 14)                                    // red flowers
        }
        "effigy" -> {
            for (h in 0..6) for (dx in -1..1) put(dx, h, 0, C + 14)                    // the body
            for (h in 7..8) for (dx in -2..2) put(dx, h, 0, if (h == 8 && dx % 2 == 0) Blocks.GOLD_BLOCK else C + 1)   // the ten heads (with crowns)
            for (h in 3..4) { put(-3, h, 0, C + 14); put(3, h, 0, C + 14) }             // the arms
            put(-1, 6, 1, Blocks.SEA_LANTERN); put(1, 6, 1, Blocks.SEA_LANTERN)        // glowing eyes
        }
        "rangoli", "pookalam" -> {
            val flower = kind == "pookalam"
            val rings = if (flower) intArrayOf(4, 1, 14, 0, 1) else intArrayOf(4, 14, 11, 1, 10)   // yellow centre then rings
            for (dx in -4..4) for (dz in -4..4) {
                val r = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
                if (r > 4.4) continue
                val ring = r.toInt().coerceIn(0, 4)
                val petal = !flower && ring == 3 && (dx == 0 || dz == 0 || abs(dx) == abs(dz))
                ground(dx, dz, C + if (petal) 3 else rings[ring])
            }
        }
        "lantern" -> {
            for (h in 0..4) put(0, h, 0, Blocks.OAK_FENCE)
            put(0, 5, 0, Blocks.SEA_LANTERN); put(0, 6, 0, Blocks.GLOWSTONE)
            put(1, 6, 0, C + 4); put(-1, 6, 0, C + 4); put(0, 7, 0, C + 4)                // a crescent and star
        }
    }
}
