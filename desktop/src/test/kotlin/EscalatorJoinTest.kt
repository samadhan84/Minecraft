import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Two escalator blocks placed apart join up by themselves. */
class EscalatorJoinTest {
    private fun game(): Game {
        val world = World(1L, null, Dimension.OVERWORLD, true)
        for (cz in -3..3) for (cx in -3..3) { val c = Chunk(cx, cz); world.generator.generate(c); world.chunks[Chunk.key(cx, cz)] = c }
        val lvl = LevelData.create(1L, "T", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 65f; z = 0.5f }
        return Game(world, lvl, GameInput(), null).also { it.mobs.hostileEnabled = false; it.player.flying = false }
    }

    @Test fun placingASecondEscalatorFillsTheGap() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        // One on the ground, one 6 up, 14 along and 3 to the side.
        g.setBlock(0, y, 0, Blocks.ESCALATOR, 2); g.joinEscalator(0, y, 0)
        g.setBlock(14, y + 6, 3, Blocks.ESCALATOR, 2)
        while (g.uiEvents.poll() != null) {}
        g.joinEscalator(14, y + 6, 3)
        println(g.uiEvents.toList())
        val steps = (0..6).map { k -> g.world.getBlock(8 + k, y + k, 3) }
        assertTrue(steps.all { it == Blocks.ESCALATOR }, "seven steps rising towards the top block")
        assertTrue((0..6).all { k -> g.world.getMeta(8 + k, y + k, 3) and 7 == 5 }, "all rising the same way (+X)")
        assertTrue(Blocks.solid[g.world.getBlock(15, y + 6, 3)], "a landing at the top")
        assertEquals(Blocks.POLISHED_ANDESITE, g.world.getBlock(4, y - 1, 0), "a walkway from the first block")
        // Walk along the walkway onto the bottom step and ride up.
        val p = g.player
        p.x = 7.5f; p.z = 3.5f; p.y = y.toFloat(); p.yaw = kotlin.math.PI.toFloat() / 2
        g.input.moveForward = 1f; repeat(20) { g.update(1 / 60f) }; g.input.moveForward = 0f
        repeat(60 * 6) { g.update(1 / 60f) }
        println("rode to ${p.x}, ${p.y}, ${p.z}")
        assertTrue(p.y >= y + 6.9f && p.x > 14.5f, "carried up to the landing")
    }

    @Test fun buildingAnEscalatorByHandIsLeftAlone() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        g.setBlock(0, y, 0, Blocks.ESCALATOR, 2); g.joinEscalator(0, y, 0)
        g.setBlock(0, y + 1, -1, Blocks.ESCALATOR, 2); g.joinEscalator(0, y + 1, -1)
        var n = 0
        for (yy in y - 2..y + 10) for (z in -20..20) for (x in -20..20) if (g.world.getBlock(x, yy, z) == Blocks.ESCALATOR) n++
        assertEquals(2, n)
    }
}
