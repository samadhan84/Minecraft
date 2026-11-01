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

    @Test fun creativeVillagesGetOneToo() {
        val dir = kotlin.io.path.createTempDirectory("park2").toFile()
        val world = World(12345L, dir, Dimension.OVERWORLD, false)
        val (cx, cz) = villageCentre(world)
        val lvl = LevelData.create(12345L, "P", GameMode.CREATIVE).apply { hasPlayer = true; x = cx + 0.5f; y = 120f; z = cz + 0.5f }
        val g = Game(world, lvl, GameInput(), dir)
        world.ensureLoaded(cx shr 4, cz shr 4)
        g.openThemeParks()
        assertEquals(1, g.coasters.list.size)
        // Only once, even after looking round again.
        repeat(300) { g.openThemeParks() }
        assertEquals(1, g.coasters.list.size)
    }

    @Test fun oldVillagesAreMadeOver() {
        val dir = kotlin.io.path.createTempDirectory("park3").toFile()
        val world = World(12345L, dir, Dimension.OVERWORLD, false)
        val (cx, cz) = villageCentre(world)
        val lvl = LevelData.create(12345L, "P", GameMode.CREATIVE).apply { hasPlayer = true; x = cx + 0.5f; y = 120f; z = cz + 0.5f }
        val g = Game(world, lvl, GameInput(), dir)
        for (dz in -2..2) for (dx in -2..2) world.ensureLoaded((cx shr 4) + dx, (cz shr 4) + dz)
        world.pendingParks.clear()
        val h = world.generator.surfaceHeight(cx, cz)
        // Turn it back into an old village: the well, and a wooden wall with a log corner and a path.
        g.setBlock(cx, h, cz, Blocks.WATER); g.setBlock(cx + 2, h, cz + 2, Blocks.COBBLESTONE); g.setBlock(cx + 2, h + 1, cz + 2, Blocks.OAK_FENCE)
        val hx = cx + 14; val hy = world.generator.surfaceHeight(hx, cz)
        g.setBlock(hx, hy + 1, cz, Blocks.PLANKS); g.setBlock(hx + 1, hy + 1, cz, Blocks.LOG); g.setBlock(hx - 1, hy, cz, Blocks.DIRT_PATH)
        g.openThemeParks()
        assertEquals(Blocks.CONCRETE_FIRST, world.getBlock(hx, hy + 1, cz), "planks became white walls")
        assertNotEquals(Blocks.LOG, world.getBlock(hx + 1, hy + 1, cz), "log corner became a coloured corner")
        assertEquals(Blocks.SMOOTH_STONE, world.getBlock(hx - 1, hy, cz))
        assertEquals(Blocks.QUARTZ_BLOCK, world.getBlock(cx + 2, h, cz + 2), "new plaza")
        assertEquals(1, g.coasters.list.size, "and a theme park")
    }

    @Test fun coasterTrackAndCart() {
        val dir = kotlin.io.path.createTempDirectory("track").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "T", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        val g = Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
        world.ensureLoaded(0, 0)
        val y = world.generator.surfaceHeight(0, 0) + 1
        // A straight run of coaster track with a station rail at the start, then a slope down.
        g.setBlock(1, y + 1, 1, Blocks.STOP_RAIL, 1)
        for (x in 2..6) g.setBlock(x, y + 1, 1, Blocks.COASTER_RAIL, 1)
        g.setBlock(7, y + 1, 1, Blocks.COASTER_RAIL, 3)   // slope down towards +x
        for (x in 8..14) g.setBlock(x, y, 1, Blocks.COASTER_RAIL, 1)
        assertTrue(Rails.joins(Blocks.COASTER_RAIL, Blocks.COASTER_RAIL))
        assertFalse(Rails.joins(Blocks.RAIL, Blocks.COASTER_RAIL))
        val cart = Cart(1.5f, y + 1f, 1.5f, Cart.kindOf("Coaster Cart")).also { it.hx = 1f; it.hz = 0f }
        assertEquals(Cart.COASTER, cart.kind)
        g.carts.list.add(cart)
        // Empty, it waits at the station; with a rider it sets off down the track.
        repeat(40) { g.carts.update(0.05f, g) }
        assertEquals(0f, cart.speed)
        g.carts.enter(cart, 1f, 0f)
        repeat(60) { g.carts.update(0.05f, g) }
        assertTrue(cart.x > 8f, "rode along the coaster track: ${cart.x}")
        // A minecart can't use coaster track.
        val mc = Cart(4.5f, y + 1f, 1.5f, Cart.MINECART).also { it.hx = 1f; it.speed = 5f }
        g.carts.riding = null
        g.carts.list.add(mc)
        g.carts.update(0.05f, g)
        assertTrue(Items.shown.any { it.name == "Coaster Cart" })
        assertTrue(Blocks.inCategory(Category.REDSTONE).contains(Blocks.COASTER_RAIL))
    }
}
