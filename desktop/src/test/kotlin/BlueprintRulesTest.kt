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

/** In creative one tap breaks anything; in survival blocks still take time. */
class CreativeBreakTest {
    private fun game(creative: Boolean): Game {
        val dir = kotlin.io.path.createTempDirectory("brk").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "B", if (creative) GameMode.CREATIVE else GameMode.SURVIVAL).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        return Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
    }

    @Test fun creativeBreaksAnythingInOneFrame() {
        val g = game(true)
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        g.player.x = 0.5f; g.player.y = y.toFloat(); g.player.z = 0.5f; g.player.flying = true
        g.player.yaw = (Math.PI / 2).toFloat(); g.player.pitch = 0f      // looking along +x at eye height
        val by = kotlin.math.floor(g.player.eyeY).toInt()
        for (id in listOf(Blocks.OBSIDIAN, Blocks.IRON_BLOCK, Blocks.STONE, Blocks.GRASS)) {
            g.setBlock(2, by, 0, id)
            g.input.breakHeld = false
            g.update(1 / 60f)
            g.input.breakHeld = true
            g.update(1 / 60f)
            assertEquals(Blocks.AIR, g.world.getBlock(2, by, 0), "block $id broke in one tap")
            g.input.breakHeld = false
            g.update(1 / 60f)
        }
    }

    @Test fun survivalStillTakesTime() {
        val g = game(false)
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        g.player.x = 0.5f; g.player.y = y.toFloat(); g.player.z = 0.5f
        g.player.yaw = (Math.PI / 2).toFloat(); g.player.pitch = 0f
        val by = kotlin.math.floor(g.player.eyeY).toInt()
        g.setBlock(2, by, 0, Blocks.OBSIDIAN)
        g.input.breakHeld = true
        g.update(1 / 60f)
        assertEquals(Blocks.OBSIDIAN, g.world.getBlock(2, by, 0))
    }
}

/** The password asked for before a world opens. */
class WorldLockTest {
    @Test fun onlyThePasswordWorks() {
        assertTrue(WorldLock.ok("dhruvil@123"))
        assertTrue(WorldLock.ok("  dhruvil@123 "))
        assertFalse(WorldLock.ok("Dhruvil@123"))
        assertFalse(WorldLock.ok(""))
        assertFalse(WorldLock.ok(null))
        assertTrue(Items.shown.none { it.name.endsWith("Spawn Egg") && (it.name.startsWith("Man") || it.name.startsWith("Woman") || it.name.startsWith("Child")) })
    }
}
