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

/** World passwords: optional, chosen by the player, kept per world as a salted hash. */
class WorldLockTest {
    @Test fun sha256IsCorrect() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", WorldLock.sha256("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", WorldLock.sha256(""))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", WorldLock.sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
    }

    @Test fun anyPasswordTheyChooseBecomesTheirPassword() {
        val stored = WorldLock.make("my secret 42")
        assertFalse(stored.contains("my secret"), "never kept as text")
        assertTrue(WorldLock.check(stored, "my secret 42"))
        assertTrue(WorldLock.check(stored, "  my secret 42 "))
        assertFalse(WorldLock.check(stored, "my secret 43")); assertFalse(WorldLock.check(stored, "")); assertFalse(WorldLock.check(stored, null))
        // A different salt gives a different stored value, and a world with no password always opens.
        assertNotEquals(stored, WorldLock.make("my secret 42"))
        assertTrue(WorldLock.check("", null)); assertTrue(WorldLock.check("", "anything"))
        assertNull(WorldLock.problem("abc", "abc")); assertNotNull(WorldLock.problem("ab", "ab")); assertNotNull(WorldLock.problem("abcd", "abce"))
    }

    @Test fun onlyTheSecretCodeTakesItAway() {
        assertTrue(WorldLock.secretOk("dhu@123")); assertFalse(WorldLock.secretOk("dhruvil@123")); assertFalse(WorldLock.secretOk(null))
    }

    @Test fun theWorldFileKeepsThePassword() {
        val dir = kotlin.io.path.createTempDirectory("pw").toFile()
        val l = LevelData.create(5L, "Locked", GameMode.SURVIVAL)
        l.password = WorldLock.make("hello123"); l.passwordAsked = true; l.bedwars = 2
        l.write(dir)
        val back = LevelData.read(dir)!!
        assertTrue(WorldLock.check(back.password, "hello123")); assertFalse(WorldLock.check(back.password, "nope"))
        assertTrue(back.passwordAsked); assertEquals(2, back.bedwars)
        // A world with no password reads back with none.
        val free = LevelData.create(6L, "Free", GameMode.SURVIVAL); val d2 = kotlin.io.path.createTempDirectory("pw2").toFile(); free.write(d2)
        assertEquals("", LevelData.read(d2)!!.password); assertFalse(LevelData.read(d2)!!.passwordAsked)
        assertTrue(Items.shown.none { it.name.endsWith("Spawn Egg") && (it.name.startsWith("Man") || it.name.startsWith("Woman") || it.name.startsWith("Child")) })
    }
}
