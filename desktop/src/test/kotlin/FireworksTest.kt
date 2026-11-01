import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Diwali fireworks: all of them are items, they light and go off with sparks, never break blocks, and stop after 13 November. */
class FireworksTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("fireworks").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "F", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        return Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
    }

    @Test fun everyCrackerIsAnItemWithARecipe() {
        for (k in Cracker.values()) {
            val id = Items.find(k.itemName)
            assertTrue(Items.isItem(id), k.itemName)
            assertTrue(Recipes.crafting.any { it.result == id }, "recipe for ${k.itemName}")
        }
    }

    @Test fun everyCrackerGoesOffWithoutBreakingBlocks() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(8, 8)
        val before = IntArray(9 * 9 * 9) { k -> g.world.getBlock(4 + k % 9, y - 4 + k / 81, 4 + (k / 9) % 9) }
        for ((n, k) in (Cracker.ORIGINAL + Cracker.TIRANGA).withIndex()) {
            if (k == Cracker.PHULJHADI) g.fireworks.light(k, g.player.x, g.player.y + 1f, g.player.z, held = true)
            else g.fireworks.light(k, 8.5f, y + 1f, 8.5f)
            var most = 0
            repeat(20 * 30) { g.fireworks.update(0.05f, g); most = maxOf(most, g.fireworks.sparks.size) }
            assertTrue(most > 0, "${k.itemName} made sparks")
            assertTrue(g.fireworks.lit.isEmpty(), "${k.itemName} finished (step $n)")
        }
        val after = IntArray(9 * 9 * 9) { k -> g.world.getBlock(4 + k % 9, y - 4 + k / 81, 4 + (k / 9) % 9) }
        assertContentEquals(before, after)
    }

    @Test fun robotPutsOnAFireworksShow() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        g.player.y = g.world.generator.surfaceHeight(0, 0) + 1f
        val r = Mob(MobType.ROBOT, 1.5f, g.player.y + 1f, 0.5f); g.mobs.list.add(r)
        g.robotCommand(r.uid, "light diwali fireworks")
        assertEquals(Cracker.ORIGINAL.size - 1, g.fireworks.lit.size)
        g.fireworks.lit.clear()
        g.robotCommand(r.uid, "light a sutli bomb please")
        assertEquals(listOf(Cracker.SUTLI), g.fireworks.lit.map { it.kind })
        repeat(200) { g.fireworks.update(0.05f, g) }
        assertTrue(g.fireworks.lit.isEmpty())
    }
}
