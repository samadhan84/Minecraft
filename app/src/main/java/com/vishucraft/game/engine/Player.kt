package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.World
import com.vishucraft.game.world.floorInt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** First-person player with AABB collision, walking, swimming and creative flight. */
class Player {
    companion object {
        const val HALF_WIDTH = 0.3f
        const val HEIGHT = 1.8f
        const val EYE = 1.62f
        const val WALK_SPEED = 4.3f
        const val FLY_SPEED = 10.5f
        const val GRAVITY = 30f
        const val JUMP_VELOCITY = 9.2f
        const val MAX_PITCH = 1.55f
    }

    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var yaw = 0f
    var pitch = 0f
    var onGround = false
    var flying = false
    var inWater = false
    var headInWater = false
    /** Distance walked, drives view bobbing. */
    var walkDist = 0f

    val eyeY get() = y + EYE

    fun lookDir(out: FloatArray) {
        val cp = cos(pitch)
        out[0] = cp * sin(yaw)
        out[1] = sin(pitch)
        out[2] = -cp * cos(yaw)
    }

    fun rotate(dYaw: Float, dPitch: Float) {
        yaw = (yaw + dYaw) % (2 * Math.PI.toFloat())
        pitch = (pitch + dPitch).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    /**
     * @param moveF forward input (-1..1), [moveS] strafe input (-1..1)
     */
    fun update(dt: Float, world: World, moveF: Float, moveS: Float, jump: Boolean, descend: Boolean) {
        val feet = world.getBlock(floorInt(x), floorInt(y + 0.1f), floorInt(z))
        val head = world.getBlock(floorInt(x), floorInt(eyeY), floorInt(z))
        inWater = feet == Blocks.WATER
        headInWater = head == Blocks.WATER

        val sy = sin(yaw); val cy = cos(yaw)
        // forward = (sin yaw, 0, -cos yaw), right = (cos yaw, 0, sin yaw)
        var wx = sy * moveF + cy * moveS
        var wz = -cy * moveF + sy * moveS
        val len = sqrt(wx * wx + wz * wz)
        if (len > 1f) { wx /= len; wz /= len }

        if (flying) {
            val speed = FLY_SPEED
            vx = approach(vx, wx * speed, 40f * dt)
            vz = approach(vz, wz * speed, 40f * dt)
            val targetY = (if (jump) speed else 0f) - (if (descend) speed else 0f)
            vy = approach(vy, targetY, 40f * dt)
        } else if (inWater) {
            val speed = WALK_SPEED * 0.55f
            vx = approach(vx, wx * speed, 20f * dt)
            vz = approach(vz, wz * speed, 20f * dt)
            vy -= GRAVITY * 0.25f * dt
            if (jump) vy = approach(vy, 4f, 30f * dt)
            vy = vy.coerceIn(-3f, 5f)
        } else {
            val speed = WALK_SPEED
            val accel = if (onGround) 50f else 12f
            vx = approach(vx, wx * speed, accel * dt)
            vz = approach(vz, wz * speed, accel * dt)
            vy -= GRAVITY * dt
            if (vy < -55f) vy = -55f
            if (jump && onGround) vy = JUMP_VELOCITY
        }

        val startX = x; val startZ = z
        val wantX = vx * dt; val wantZ = vz * dt
        val hitX = !moveAxis(world, 0, wantX)
        val hitZ = !moveAxis(world, 2, wantZ)
        onGround = false
        if (!moveAxis(world, 1, vy * dt)) {
            if (vy < 0) onGround = true
            vy = 0f
        }
        if (hitX) vx = 0f
        if (hitZ) vz = 0f

        // Auto-jump onto single-block steps when walking into them (mobile friendly).
        if (!flying && onGround && (hitX || hitZ) && len > 0.1f && canStepUp(world, wantX, wantZ)) {
            vy = JUMP_VELOCITY
        }

        if (onGround) {
            val dx = x - startX; val dz = z - startZ
            walkDist += sqrt(dx * dx + dz * dz)
        }
        if (onGround && flying) flying = false
    }

    private fun approach(v: Float, target: Float, step: Float): Float =
        if (v < target) minOf(v + step, target) else maxOf(v - step, target)

    private fun canStepUp(world: World, dx: Float, dz: Float): Boolean {
        val ax = x + Math.signum(dx) * (HALF_WIDTH + 0.3f)
        val az = z + Math.signum(dz) * (HALF_WIDTH + 0.3f)
        val bx = floorInt(ax); val bz = floorInt(az)
        val by = floorInt(y + 0.01f)
        return Blocks.solid[world.getBlock(bx, by, bz)] &&
            !Blocks.solid[world.getBlock(bx, by + 1, bz)] &&
            !Blocks.solid[world.getBlock(bx, by + 2, bz)] &&
            !Blocks.solid[world.getBlock(floorInt(x), by + 2, floorInt(z))]
    }

    /** Moves along one axis, stopping at solid blocks. Returns false if blocked. */
    private fun moveAxis(world: World, axis: Int, delta: Float): Boolean {
        if (delta == 0f) return true
        var remaining = delta
        // Sub-step so fast movement cannot tunnel through blocks.
        while (remaining != 0f) {
            val step = remaining.coerceIn(-0.45f, 0.45f)
            remaining -= step
            when (axis) {
                0 -> x += step
                1 -> y += step
                else -> z += step
            }
            val minX = x - HALF_WIDTH; val maxX = x + HALF_WIDTH
            val minY = y; val maxY = y + HEIGHT
            val minZ = z - HALF_WIDTH; val maxZ = z + HALF_WIDTH
            for (bx in floorInt(minX)..floorInt(maxX - 1e-4f))
                for (by in floorInt(minY)..floorInt(maxY - 1e-4f))
                    for (bz in floorInt(minZ)..floorInt(maxZ - 1e-4f)) {
                        if (!Blocks.solid[world.getBlock(bx, by, bz)]) continue
                        when (axis) {
                            0 -> x = if (step > 0) bx - HALF_WIDTH - 1e-3f else bx + 1 + HALF_WIDTH + 1e-3f
                            1 -> y = if (step > 0) by - HEIGHT - 1e-3f else by + 1f
                            else -> z = if (step > 0) bz - HALF_WIDTH - 1e-3f else bz + 1 + HALF_WIDTH + 1e-3f
                        }
                        return false
                    }
        }
        return true
    }

    /** True if a block at (bx, by, bz) would overlap the player's body. */
    fun intersectsBlock(bx: Int, by: Int, bz: Int): Boolean =
        x + HALF_WIDTH > bx && x - HALF_WIDTH < bx + 1 &&
            y + HEIGHT > by && y < by + 1 &&
            z + HALF_WIDTH > bz && z - HALF_WIDTH < bz + 1

    fun blockX() = floor(x).toInt()
    fun blockZ() = floor(z).toInt()
}
