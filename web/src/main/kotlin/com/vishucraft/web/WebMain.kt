package com.vishucraft.web

import com.vishucraft.game.render.Buffers
import com.vishucraft.game.world.World

/** Starts the browser version (see index.html): the page has loaded the saved worlds before calling this. */
fun main() {
    Buffers.wrapArrays = true
    World.makeWorkers = { Tasks }
    Storage.restore()
    val app = WebApp()
    // Test hook (?look=pitch): opens a flat world looking down, to check how the ground is drawn.
    lookTest()?.let { app.testWorld(it) }
    onHide { app.hidden() }
    var last = -1.0
    loop { t ->
        val dt = if (last < 0) 0.016f else ((t - last) / 1000.0).toFloat().coerceIn(0f, 0.05f)
        last = t
        app.step(dt)
    }
    log("DhruvVishu started")
}
