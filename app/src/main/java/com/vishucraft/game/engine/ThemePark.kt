package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * In survival worlds every new village gets a theme park beside it: a steel roller coaster with a loop (see
 * Coaster), a big Ferris wheel with coloured cabins, an entrance arch with lights, and a plaza with lamps.
 */

/** Opens a theme park beside each village that world generation has just made (survival worlds only). */
internal fun Game.openThemeParks() {
    while (true) {
        val (x, y, z) = world.pendingParks.poll() ?: return
        if (isClient || !survival || dimension != com.vishucraft.game.world.Dimension.OVERWORLD) continue
        buildThemePark(x, y, z)
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
