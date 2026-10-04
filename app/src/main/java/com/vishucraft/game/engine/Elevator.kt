package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.floorInt

/*
 * Elevators: put elevator blocks straight above each other, one on every floor. Standing on one, jump to ride
 * up a floor or crouch to ride down; or tap one to pick a floor. The ride glides smoothly through the shaft.
 */

class Lift {
    /** Where the ride is going (feet height), or NaN when not riding. */
    var targetY = Float.NaN
    var x = 0f
    var z = 0f
    var floor = 0
    val riding get() = !targetY.isNaN()
    /** True while the player stands on an elevator block (the phone shows the down button then). */
    @Volatile var standingOn = false
    /** The elevator block last stepped on, so the hint shows once per step. */
    var lastBlock = Long.MIN_VALUE
}

/** The elevator blocks in the column at (x, z), lowest first. */
internal fun Game.liftFloors(x: Int, z: Int): List<Int> =
    (1 until world.columnHeight(x, z) - 1).filter { world.getBlock(x, it, z) == Blocks.ELEVATOR }

/** The elevator block the player stands on, if any: (x, y, z). */
private fun Game.liftUnderfoot(): IntArray? {
    val x = player.blockX(); val z = player.blockZ()
    val y = floorInt(player.y - 0.1f)
    if (player.y - (y + 1) > 0.3f) return null
    return if (world.getBlock(x, y, z) == Blocks.ELEVATOR) intArrayOf(x, y, z) else null
}

/** Starts a ride to floor [index] (0 = lowest) of the elevator column at (x, z). */
fun Game.liftTo(x: Int, z: Int, index: Int) {
    val floors = liftFloors(x, z)
    val y = floors.getOrNull(index) ?: return
    lift.x = x + 0.5f; lift.z = z + 0.5f
    lift.targetY = y + 1f
    lift.floor = index
    // Arriving shows the floor number, not the elevator hint again.
    lift.lastBlock = com.vishucraft.game.world.RedstoneIds.pack(x, y, z)
    player.x = lift.x; player.z = lift.z
    sound("click", player.x, player.y, player.z)
}

/** Jump rides up a floor, crouch rides down, when standing on an elevator. Returns true when used. */
internal fun Game.liftInput(jumpPressed: Boolean, crouchPressed: Boolean): Boolean {
    if (lift.riding || (!jumpPressed && !crouchPressed)) return false
    val here = liftUnderfoot() ?: return false
    val floors = liftFloors(here[0], here[2])
    val i = floors.indexOf(here[1])
    val next = if (jumpPressed) i + 1 else i - 1
    if (next !in floors.indices) {
        if (floors.size < 2) uiEvents.add("toast:Put another elevator block straight above or below to make floors")
        return false
    }
    liftTo(here[0], here[2], next)
    return true
}

/** Tapping an elevator block shows its floors. */
internal fun Game.liftTap(t: RayHit, sel: Int): Boolean {
    if (t.block != Blocks.ELEVATOR || (sel > 0 && !Items.isItem(sel))) return false
    val floors = liftFloors(t.x, t.z)
    if (floors.size < 2) { uiEvents.add("toast:Elevator: put more elevator blocks straight above it, one on each floor"); return true }
    val current = floors.indexOf(t.y)
    uiEvents.add("floors:${t.x},${t.z},$current," + floors.indices.joinToString("|") { if (it == 0) "Ground floor" else "Floor $it" })
    return true
}

/** Moves a rider smoothly to the chosen floor, and explains the elevator when someone steps on one. */
internal fun Game.updateLift(dt: Float) {
    val here = if (lift.riding) null else liftUnderfoot()
    lift.standingOn = here != null
    if (here != null) {
        val key = com.vishucraft.game.world.RedstoneIds.pack(here[0], here[1], here[2])
        if (key != lift.lastBlock) {
            lift.lastBlock = key
            val floors = liftFloors(here[0], here[2])
            val i = floors.indexOf(here[1])
            uiEvents.add("toast:" + if (floors.size < 2) "Elevator: put more elevator blocks straight above this one, one on each floor"
                else "Elevator (${if (i == 0) "ground floor" else "floor $i"} of ${floors.size}): jump to go up, ▼ / crouch to go down, or tap it to pick a floor")
        }
    } else if (!lift.riding) lift.lastBlock = Long.MIN_VALUE
    if (!lift.riding) return
    val dy = lift.targetY - player.y
    // Express in tall towers: up to 40 blocks a second, slowing down for the last few floors.
    val step = minOf(40f, 7f + kotlin.math.abs(dy) * 0.8f) * dt
    player.x = lift.x; player.z = lift.z
    player.vx = 0f; player.vy = 0f; player.vz = 0f
    if (kotlin.math.abs(dy) <= step) {
        player.y = lift.targetY
        lift.targetY = Float.NaN
        sound("ding", player.x, player.y, player.z)
        uiEvents.add("toast:" + if (lift.floor == 0) "Ground floor" else "Floor ${lift.floor}")
    } else player.y += if (dy > 0) step else -step
}
