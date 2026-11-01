package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import com.vishucraft.game.world.floorInt
import com.vishucraft.game.world.villageIn

/*
 * Every village gets a theme park beside it: a steel roller coaster with a loop (see Coaster), a big Ferris wheel
 * with coloured cabins, an entrance arch with lights, and a plaza with lamps. Villages from before the futuristic
 * look are made over when you come near them: white walls, coloured corners, glass windows, glowing roof trim,
 * solar panels, smooth lit streets and a glowing plaza instead of the old well.
 */

/** Villages (by grid region) that already have their theme park and new look; saved with the world. */
internal class VillageRecord(private val file: java.io.File?) {
    private val done = HashSet<Long>()
    init { try { file?.takeIf { it.exists() }?.readLines()?.forEach { it.trim().toLongOrNull()?.let(done::add) } } catch (_: Exception) {} }
    operator fun contains(key: Long) = key in done
    fun add(key: Long) { done.add(key); try { file?.writeText(done.joinToString("\n")) } catch (_: Exception) {} }
}

private fun regionKey(rx: Int, rz: Int) = (rx.toLong() shl 32) or (rz.toLong() and 0xffffffffL)

/** Theme parks for villages world generation has just made, then a look round for older villages nearby. */
internal fun Game.openThemeParks() {
    if (isClient || dimension != com.vishucraft.game.world.Dimension.OVERWORLD) { world.pendingParks.clear(); return }
    while (true) {
        val (x, y, z) = world.pendingParks.poll() ?: break
        val rx = (x - 48).floorDiv(144); val rz = (z - 48).floorDiv(144)
        if (regionKey(rx, rz) in villages) continue
        if (!hasPark(x, z)) buildThemePark(x, y, z)
        villages.add(regionKey(rx, rz))
    }
    villageCheck -= 1
    if (villageCheck > 0) return
    villageCheck = 120
    // Villages around the player whose ground has been made already (older villages are made over).
    val prx = floorInt(player.x).floorDiv(144); val prz = floorInt(player.z).floorDiv(144)
    for (rz in prz - 1..prz + 1) for (rx in prx - 1..prx + 1) {
        val key = regionKey(rx, rz)
        if (key in villages) continue
        val v = villageIn(world.seed, world.generator, rx, rz) ?: continue
        val (cx, cy, cz) = Triple(v[0], v[1], v[2])
        if ((cx - player.x) * (cx - player.x) + (cz - player.z) * (cz - player.z) > 160f * 160f) continue
        if (!world.isLoaded(cx, cz)) continue   // not made yet: it will come with the new look and a park
        if (isOldVillage(cx, cy, cz)) { makeOverVillage(cx, cy, cz); uiEvents.add("toast:This village has been rebuilt in the new futuristic style!") }
        if (!hasPark(cx, cz)) buildThemePark(cx, cy, cz)
        villages.add(key)
    }
}

/** Whether a theme park's roller coaster already stands beside the village at (cx, cz). */
private fun Game.hasPark(cx: Int, cz: Int) = coasters.list.any { kotlin.math.abs(it.ox - (cx + 8.5f)) < 3f && kotlin.math.abs(it.oz - (cz + 32.5f)) < 3f }

/** The old village well: water ringed by cobblestone. */
private fun Game.isOldVillage(cx: Int, cy: Int, cz: Int) =
    world.getBlock(cx, cy, cz) == Blocks.WATER && world.getBlock(cx + 2, cy, cz + 2) == Blocks.COBBLESTONE && world.getBlock(cx + 2, cy + 1, cz + 2) == Blocks.OAK_FENCE

/** Gives an old village the futuristic look, block by block, and a glowing plaza where the well was. */
private fun Game.makeOverVillage(cx: Int, cy: Int, cz: Int) {
    val r = 27
    for (z in cz - r..cz + r) for (x in cx - r..cx + r) world.ensureLoaded(x shr 4, z shr 4)
    val white = Blocks.CONCRETE_FIRST
    val accents = intArrayOf(9, 3, 11, 10).map { Blocks.CONCRETE_FIRST + it }
    val houseBlocks = setOf(Blocks.PLANKS, Blocks.COBBLESTONE, Blocks.GLASS_PANE, Blocks.OAK_DOOR, white, Blocks.GLASS)
    fun near(x: Int, y: Int, z: Int, ids: Set<Int>) = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dz) -> world.getBlock(x + dx, y, z + dz) in ids }
    // First the walls, floors, roofs, windows and streets...
    for (z in cz - r..cz + r) for (x in cx - r..cx + r) {
        val g = world.generator.surfaceHeight(x, z)
        for (y in g - 1..g + 6) when (world.getBlock(x, y, z)) {
            Blocks.DIRT_PATH -> setBlock(x, y, z, Blocks.SMOOTH_STONE)
            Blocks.PLANKS -> setBlock(x, y, z, white)
            Blocks.COBBLESTONE -> if (y >= g) setBlock(x, y, z, if (y == g) Blocks.QUARTZ_BLOCK else white)
            Blocks.GLASS_PANE -> setBlock(x, y, z, Blocks.GLASS)
            Blocks.TORCH -> setBlock(x, y, z, Blocks.SEA_LANTERN)
        }
    }
    // ...then the corner posts, farm borders and roof slabs, which depend on what is next to them.
    for (z in cz - r..cz + r) for (x in cx - r..cx + r) {
        val g = world.generator.surfaceHeight(x, z)
        for (y in g - 1..g + 6) when (world.getBlock(x, y, z)) {
            Blocks.LOG -> when {
                near(x, y, z, houseBlocks) -> setBlock(x, y, z, accents[((x * 31 + z * 17) and 0x7fffffff) % accents.size])
                near(x, y, z, setOf(Blocks.FARMLAND, Blocks.WATER)) || near(x, y + 1, z, setOf(Blocks.WHEAT_CROP, Blocks.CARROTS, Blocks.POTATOES)) -> setBlock(x, y, z, Blocks.QUARTZ_BLOCK)
            }
            Blocks.OAK_SLAB -> setBlock(x, y, z, when {
                !Blocks.solid[world.getBlock(x, y - 1, z)] -> Blocks.SEA_LANTERN                      // the roof's edge glows
                (x + z) % 2 == 0 -> Blocks.SOLAR_PANEL
                else -> Blocks.AIR
            }, if ((x + z) % 2 == 0 && Blocks.solid[world.getBlock(x, y - 1, z)]) 2 else 0)
        }
    }
    futuristicPlaza(cx, cy, cz)
}

/** The glowing plaza in the middle of a village: white with a light blue ring, a fountain and a light tower. */
private fun Game.futuristicPlaza(cx: Int, wh: Int, cz: Int) {
    for (dx in -5..5) for (dz in -5..5) {
        val ring = kotlin.math.abs(dx) == 5 || kotlin.math.abs(dz) == 5
        setBlock(cx + dx, wh, cz + dz, if (ring) Blocks.CONCRETE_FIRST + 3 else Blocks.CONCRETE_FIRST)
        for (y in wh + 1..wh + 8) setBlock(cx + dx, y, cz + dz, Blocks.AIR)
    }
    for (dx in -2..2) for (dz in -2..2) {
        val rim = kotlin.math.abs(dx) == 2 || kotlin.math.abs(dz) == 2
        setBlock(cx + dx, wh, cz + dz, if (rim) Blocks.QUARTZ_BLOCK else Blocks.WATER)
        if (rim) setBlock(cx + dx, wh + 1, cz + dz, if ((dx + dz) % 2 == 0) Blocks.QUARTZ_BLOCK else Blocks.GLASS_PANE)
    }
    for (y in wh + 1..wh + 5) setBlock(cx, y, cz, if (y % 2 == 0) Blocks.SEA_LANTERN else Blocks.GLASS)
    setBlock(cx, wh + 6, cz, Blocks.QUARTZ_BLOCK)
    for ((dx, dz) in listOf(4 to 4, -4 to 4, 4 to -4, -4 to -4)) {
        setBlock(cx + dx, wh + 1, cz + dz, Blocks.STREET_LAMP, 2 or 8); setBlock(cx + dx, wh + 2, cz + dz, Blocks.STREET_LAMP, 2 or 8); setBlock(cx + dx, wh + 3, cz + dz, Blocks.STREET_LAMP, 2)
    }
}

/** Builds a theme park next to the village whose plaza is at (cx, y, cz). */
fun Game.buildThemePark(cx: Int, y: Int, cz: Int) {
    // The park's plaza, south of the village, between the Ferris wheel and the coaster's station.
    val px = cx; val pz = cz + 32
    fun ground(x: Int, z: Int): Int { world.ensureLoaded(x shr 4, z shr 4); return world.generator.surfaceHeight(x, z) }
    val gy = ground(px, pz)
    for (x in px - 24..px + 6) for (z in pz - 4..pz + 4) {
        ground(x, z)
        val edge = abs(z - pz) == 4
        setBlock(x, gy, z, if (edge) Blocks.CONCRETE_FIRST + 3 else Blocks.SMOOTH_STONE)
        for (h in 1..5) setBlock(x, gy + h, z, Blocks.AIR)
        if (edge && x % 6 == 0) { setBlock(x, gy + 1, z, Blocks.STREET_LAMP, 2 or 8); setBlock(x, gy + 2, z, Blocks.STREET_LAMP, 2 or 8); setBlock(x, gy + 3, z, Blocks.STREET_LAMP, 2) }
    }
    // Entrance arch on the village side, lit up.
    for (h in 1..5) { setBlock(px - 3, gy + h, pz - 4, Blocks.QUARTZ_BLOCK); setBlock(px + 3, gy + h, pz - 4, Blocks.QUARTZ_BLOCK) }
    for (x in px - 3..px + 3) setBlock(x, gy + 6, pz - 4, if (x % 2 == 0) Blocks.SEA_LANTERN else Blocks.CONCRETE_FIRST + 14)
    for (h in 1..4) setBlock(px, gy + h, pz - 4, Blocks.AIR)
    ferrisWheel(px - 16, gy, pz + 2)
    // The roller coaster, its station at the east end of the plaza.
    buildCoaster(px + 8.5f, gy + 1f, pz + 0.5f, 1, 0)
    uiEvents.add("toast:A theme park has opened next to the village! Ride the roller coaster")
}

/** A Ferris wheel 20 blocks across, facing north-south, centred over (x, z), with A-frame legs and eight cabins. */
private fun Game.ferrisWheel(x: Int, gy: Int, z: Int) {
    val r = 9; val cy = gy + r + 3
    val colours = intArrayOf(14, 1, 4, 5, 3, 11, 10, 6)   // red, orange, yellow, lime, light blue, blue, purple, pink
    // The rim, the spokes and the hub.
    for (k in 0 until 120) {
        val a = k * Math.PI * 2 / 120
        setBlock(x + (cos(a) * r).roundToInt(), cy + (sin(a) * r).roundToInt(), z, if (k % 10 == 0) Blocks.SEA_LANTERN else Blocks.CONCRETE_FIRST)
    }
    for (s in 0 until 8) {
        val a = s * Math.PI / 4
        for (d in 1 until r) setBlock(x + (cos(a) * d).roundToInt(), cy + (sin(a) * d).roundToInt(), z, Blocks.IRON_BARS)
        // A cabin hanging below each spoke's end.
        val bx = x + (cos(a) * (r + 1)).roundToInt(); val by = cy + (sin(a) * (r + 1)).roundToInt() - 1
        for (dz in -1..1) {
            setBlock(bx, by, z + dz, Blocks.CONCRETE_FIRST + colours[s])
            setBlock(bx, by + 1, z + dz, if (dz == 0) Blocks.AIR else Blocks.GLASS_PANE)
            setBlock(bx, by + 2, z + dz, Blocks.CONCRETE_FIRST + colours[s])
        }
    }
    setBlock(x, cy, z, Blocks.IRON_BLOCK)
    // A-frame legs down to the ground on both sides of the wheel.
    for (side in intArrayOf(-2, 2)) for (h in 0..r + 3) {
        val off = (h * 5) / (r + 3)
        setBlock(x - off, cy - h, z + side, Blocks.IRON_BLOCK)
        setBlock(x + off, cy - h, z + side, Blocks.IRON_BLOCK)
    }
    for (side in intArrayOf(-2, -1, 1, 2)) setBlock(x, cy, z + side, Blocks.IRON_BLOCK)
}
