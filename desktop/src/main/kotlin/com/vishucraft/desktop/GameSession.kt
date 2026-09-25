package com.vishucraft.desktop

import com.vishucraft.game.engine.Game
import com.vishucraft.game.engine.GameInput
import com.vishucraft.game.render.WorldRenderer
import com.vishucraft.game.world.Dimension
import com.vishucraft.game.world.GameMode
import com.vishucraft.game.world.LevelData
import com.vishucraft.game.world.World
import java.io.File

/** One open world: the shared engine plus its renderer. Everything runs on the window's thread. */
class GameSession(
    val dir: File?,
    val level: LevelData,
    val client: com.vishucraft.game.net.ClientSession? = null,
) {
    val input = GameInput()
    val world: World
    val game: Game
    val renderer: WorldRenderer
    private var lastWalk = 0f

    init {
        val dimDir = when (level.dimension) {
            Dimension.OVERWORLD -> dir
            Dimension.EMBER -> dir?.let { File(it, "ember") }
            Dimension.SKY -> dir?.let { File(it, "sky") }
        }
        world = World(level.seed, if (client != null) null else dimDir, level.dimension)
        if (client != null) world.remoteLoader = { cx, cz -> client.requestChunk(cx, cz) }
        game = Game(world, level, input, if (client != null) null else dir)
        if (client != null) game.net = client
        renderer = WorldRenderer(game)
        renderer.onSurfaceCreated()
    }

    fun resize(w: Int, h: Int) = renderer.onSurfaceChanged(w, h)

    /** Advances the game and draws the 3D world. Returns UI events (toasts, screens to open...). */
    fun frame(dt: Float, events: MutableList<String>) {
        game.update(dt)
        while (true) events.add(game.uiEvents.poll() ?: break)
        if (game.dirtyChunks.isNotEmpty()) {
            for (key in game.dirtyChunks) renderer.remeshNow((key shr 32).toInt(), key.toInt())
            game.dirtyChunks.clear()
        }
        game.listener?.invoke(game.player.x, game.player.eyeY, game.player.z, game.player.yaw)
        val p = game.player
        val bob = if (p.onGround && !p.flying) p.walkDist * 1.6f else 0f
        renderer.draw(bob)
    }

    fun save() = game.save()

    fun close() {
        try { save() } catch (_: Exception) {}
        game.net?.let { n -> Thread { n.close() }.start() }
        world.shutdown()
        renderer.release()
    }

    companion object {
        fun open(dir: File): GameSession = GameSession(dir, LevelData.read(dir) ?: LevelData.create(System.nanoTime(), dir.name, GameMode.CREATIVE))

        fun create(dir: File, name: String, seed: Long, survival: Boolean): GameSession {
            val level = LevelData.create(seed, name, if (survival) GameMode.SURVIVAL else GameMode.CREATIVE)
            level.write(dir)
            return GameSession(dir, level)
        }
    }
}
