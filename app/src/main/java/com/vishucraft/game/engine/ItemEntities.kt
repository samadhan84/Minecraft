package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.World
import com.vishucraft.game.world.floorInt
import java.util.Random
import kotlin.math.sqrt

/** A dropped stack lying in the world. */
class ItemEntity(val stack: ItemStack, var x: Float, var y: Float, var z: Float) {
    var vx = 0f; var vy = 0f; var vz = 0f
    var age = 0f
    var pickupDelay = 0.5f
}

/** Dropped items: simple physics, merging, magnet pickup and despawning. */
class ItemEntities(private val world: World) {
    val list = ArrayList<ItemEntity>()
    private val rnd = Random()

    fun spawn(stack: ItemStack, x: Float, y: Float, z: Float, throwX: Float = 0f, throwZ: Float = 0f) {
        if (stack.count <= 0) return
        list.add(ItemEntity(stack, x, y, z).apply {
            vx = throwX + (rnd.nextFloat() - 0.5f) * 2f
            vz = throwZ + (rnd.nextFloat() - 0.5f) * 2f
            vy = 4f
        })
    }

    fun update(dt: Float, game: Game) {
        val p = game.player
        val it = list.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.age += dt
            e.pickupDelay -= dt
            if (e.age > 300f || e.y < -20f || e.stack.count <= 0) { it.remove(); continue }
            if (!world.isLoaded(floorInt(e.x), floorInt(e.z))) continue

            // Pull towards a nearby living player, then pick up.
            val dx = p.x - e.x; val dy = p.y + 0.8f - e.y; val dz = p.z - e.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (game.playerAlive && e.pickupDelay <= 0f && d < 3f) {
                if (d < 0.9f) {
                    val left = game.inventory.add(e.stack.id, e.stack.count, e.stack.damage)
                    if (left < e.stack.count) game.onPickup()
                    e.stack.count = left
                    if (left == 0) { it.remove(); continue }
                } else {
                    e.vx += dx / d * 30f * dt; e.vy += dy / d * 30f * dt; e.vz += dz / d * 30f * dt
                }
            }

            val inWater = world.getBlock(floorInt(e.x), floorInt(e.y), floorInt(e.z)) == Blocks.WATER
            e.vy -= (if (inWater) 4f else 22f) * dt
            if (inWater) e.vy = e.vy.coerceAtLeast(-1f)
            val nx = e.x + e.vx * dt; val nz = e.z + e.vz * dt
            if (!Blocks.solid[world.getBlock(floorInt(nx), floorInt(e.y + 0.05f), floorInt(e.z))]) e.x = nx else e.vx = 0f
            if (!Blocks.solid[world.getBlock(floorInt(e.x), floorInt(e.y + 0.05f), floorInt(nz))]) e.z = nz else e.vz = 0f
            val ny = e.y + e.vy * dt
            val below = world.getBlock(floorInt(e.x), floorInt(ny), floorInt(e.z))
            val top = floorInt(ny) + Blocks.height[below]
            if (e.vy <= 0f && Blocks.solid[below] && ny < top) {
                e.y = top.toFloat(); e.vy = 0f; e.vx *= 0.6f; e.vz *= 0.6f
            } else e.y = ny
        }
        // Merge identical stacks lying next to each other.
        if (list.size > 1) {
            for (i in list.indices) {
                val a = list[i]
                if (a.stack.count <= 0) continue
                for (j in i + 1 until list.size) {
                    val b = list[j]
                    if (b.stack.count <= 0 || b.stack.id != a.stack.id || b.stack.damage != a.stack.damage) continue
                    val ddx = a.x - b.x; val ddy = a.y - b.y; val ddz = a.z - b.z
                    if (ddx * ddx + ddy * ddy + ddz * ddz > 0.8f) continue
                    val room = a.stack.maxStack - a.stack.count
                    if (room <= 0) continue
                    val n = minOf(room, b.stack.count)
                    a.stack.count += n; b.stack.count -= n
                }
            }
        }
    }
}
