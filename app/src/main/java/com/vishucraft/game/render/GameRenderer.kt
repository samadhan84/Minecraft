package com.vishucraft.game.render

import android.opengl.GLSurfaceView
import com.vishucraft.game.engine.Game
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Drives the game loop from the GL thread: simulate, then draw. */
class GameRenderer(private val game: Game, private val onStats: (String) -> Unit) : GLSurfaceView.Renderer {
    private val world = WorldRenderer(game)
    private var lastNanos = 0L
    private var frames = 0
    private var statTimer = 0f


    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        world.onSurfaceCreated()
        lastNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        world.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        game.update(dt)
        if (game.dirtyChunks.isNotEmpty()) {
            for (key in game.dirtyChunks) world.remeshNow((key shr 32).toInt(), key.toInt())
            game.dirtyChunks.clear()
        }
        val p = game.player
        val bob = if (p.onGround && !p.flying) p.walkDist * 1.6f else 0f
        world.draw(bob)

        frames++
        statTimer += dt
        if (statTimer >= 0.5f) {
            val fps = (frames / statTimer).toInt()
            frames = 0; statTimer = 0f
            val hours = ((game.timeOfDay * 24 + 6) % 24).toInt()
            val mins = (((game.timeOfDay * 24 + 6) % 1) * 60).toInt()
            onStats(
                "%d fps  XYZ %.1f / %.1f / %.1f  %02d:%02d%s".format(
                    fps, p.x, p.y, p.z, hours, mins, if (p.flying) "  [flying]" else ""
                ) + "\n" + game.world.generator.biomeAt(p.blockX(), p.blockZ()).name.lowercase()
                    .replaceFirstChar { it.uppercase() } + "  chunks ${world.visibleChunks}"
            )
        }
    }
}
