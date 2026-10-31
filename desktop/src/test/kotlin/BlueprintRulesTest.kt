import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Only the Smart City, Metro Station and Bullet Train Station blueprints are offered; no normal railways; 5 worlds. */
class BlueprintRulesTest {
    @Test fun onlyThreeBlueprints() {
        val blueprints = Items.shown.filter { it.name.startsWith("Blueprint: ") }.map { it.name.removePrefix("Blueprint: ") }
        assertEquals(setOf("Smart City", "Metro Station", "Bullet Train Station"), blueprints.toSet())
        assertTrue(Items.shown.none { it.name == "Train Engine" })
        for (r in Recipes.crafting) assertFalse(Items.hidden(Items[r.result]?.name), "recipe for ${Items[r.result]?.name}")
        assertNull(blueprintOf("Blueprint: Mega City"))
        assertNull(blueprintOf("Blueprint: Railway Station"))
        assertEquals("Smart City", blueprintOf("Blueprint: Smart City"))
        assertEquals(5, MAX_WORLDS)
    }

    @Test fun smartCityHasNoRailway() {
        val dir = kotlin.io.path.createTempDirectory("city").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "C", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        val g = Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
        world.ensureLoaded(0, 0)
        g.player.yaw = 0f
        assertTrue(g.placeBuilding(RayHit(0, world.generator.surfaceHeight(0, 0), 0, 0, 1, 0, Blocks.GRASS), "Smart City"))
        assertTrue(g.metroNet.stations.none { it[3] == TrackEnd.RAILWAY })
        assertTrue(g.carts.list.none { it.kind == Cart.ENGINE })
        assertTrue(g.carts.list.any { it.kind == Cart.METRO })
    }
}
