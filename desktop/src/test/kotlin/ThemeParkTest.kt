import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Futuristic villages, and the theme park that opens beside them in survival worlds. */
class ThemeParkTest {
    private fun villageCentre(world: World): Pair<Int, Int> {
        val gen = world.generator
        for (rz in -4..3) for (rx in -4..3) {
            val rnd = java.util.Random(world.seed xor (rx * 341873128712L) xor (rz * 132897987541L) xor 0x5EED)
            val cx = rx * 144 + 48 + rnd.nextInt(144 - 96); val cz = rz * 144 + 48 + rnd.nextInt(144 - 96)
            val h = gen.surfaceHeight(cx, cz)
            if (h <= TerrainGenerator.SEA_LEVEL + 1 || h > 88) continue
            if (gen.biomeAt(cx, cz) in setOf(Biome.PLAINS, Biome.SAVANNA, Biome.FOREST, Biome.SNOW, Biome.CHERRY, Biome.JUNGLE, Biome.SWAMP)) return cx to cz
        }
        error("no village")
    }

    @Test fun villagesAreFuturisticAndAskForAThemePark() {
        val world = World(12345L, null, Dimension.OVERWORLD, false)
        val (cx, cz) = villageCentre(world)
        world.ensureLoaded(cx shr 4, cz shr 4)
        val h = world.generator.surfaceHeight(cx, cz)
        assertTrue((h + 1..h + 5).any { world.getBlock(cx, it, cz) == Blocks.SEA_LANTERN }, "glowing tower in the plaza")
        assertEquals(Blocks.QUARTZ_BLOCK, world.getBlock(cx + 2, h, cz + 2), "fountain rim")
        assertTrue(world.pendingParks.any { it.first == cx && it.third == cz })
    }

    @Test fun survivalVillagesGetAThemePark() {
        val dir = kotlin.io.path.createTempDirectory("park").toFile()
        val world = World(12345L, dir, Dimension.OVERWORLD, false)
        val (cx, cz) = villageCentre(world)
        val lvl = LevelData.create(12345L, "P", GameMode.SURVIVAL).apply { hasPlayer = true; x = cx + 0.5f; y = 120f; z = cz + 0.5f }
        val g = Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
        world.ensureLoaded(cx shr 4, cz shr 4)
        g.openThemeParks()
        assertEquals(1, g.coasters.list.size, "roller coaster")
        val gy = world.generator.surfaceHeight(cx, cz + 32)
        assertEquals(Blocks.IRON_BLOCK, world.getBlock(cx - 16, gy + 12, cz + 34), "Ferris wheel hub")
    }

    @Test fun creativeVillagesDoNot() {
        val dir = kotlin.io.path.createTempDirectory("park2").toFile()
        val world = World(12345L, dir, Dimension.OVERWORLD, false)
        val (cx, cz) = villageCentre(world)
        val lvl = LevelData.create(12345L, "P", GameMode.CREATIVE).apply { hasPlayer = true; x = cx + 0.5f; y = 120f; z = cz + 0.5f }
        val g = Game(world, lvl, GameInput(), dir)
        world.ensureLoaded(cx shr 4, cz shr 4)
        g.openThemeParks()
        assertTrue(g.coasters.list.isEmpty())
    }
}
