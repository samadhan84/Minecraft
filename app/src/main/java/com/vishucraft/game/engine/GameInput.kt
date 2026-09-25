package com.vishucraft.game.engine

import java.util.concurrent.ConcurrentLinkedQueue

/** Touch state shared between the UI thread (writer) and the game thread (reader). */
class GameInput {
    enum class Action { PLACE, TOGGLE_FLY }

    @Volatile var moveForward = 0f
    @Volatile var moveStrafe = 0f
    @Volatile var jumpHeld = false
    @Volatile var descendHeld = false
    /** True while the player is holding a finger on the world to mine. */
    @Volatile var breakHeld = false
    /** Selected hotbar slot (0..8). */
    @Volatile var selectedSlot = 0
    /** Continuous look input from gamepad sticks / remote keys, -1..1 (x: right, y: up). */
    @Volatile var lookStickX = 0f
    @Volatile var lookStickY = 0f

    private var lookDx = 0f
    private var lookDy = 0f
    private val lock = Any()

    val actions = ConcurrentLinkedQueue<Action>()

    fun addLook(dx: Float, dy: Float) = synchronized(lock) { lookDx += dx; lookDy += dy }

    /** Returns and clears accumulated look deltas (pixels). */
    fun consumeLook(out: FloatArray) = synchronized(lock) {
        out[0] = lookDx; out[1] = lookDy
        lookDx = 0f; lookDy = 0f
    }
}
