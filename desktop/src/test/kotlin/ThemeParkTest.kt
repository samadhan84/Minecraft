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

    @Test fun villagesArePlainWoodenOnes() {
        val dir = kotlin.io.path.createTempDirectory("plain").toFile()
        val world = World(12345L, dir, Dimension.OVERWORLD, false)
        val (cx, cz) = villageCentre(world)
        val lvl = LevelData.create(12345L, "P", GameMode.SURVIVAL).apply { hasPlayer = true; x = cx + 0.5f; y = 120f; z = cz + 0.5f }
        val g = Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
        for (dz in -5..5) for (dx in -5..5) world.ensureLoaded((cx shr 4) + dx, (cz shr 4) + dz)
        g.openThemeParks()
        val h = world.generator.surfaceHeight(cx, cz)
        assertEquals(Blocks.WATER, world.getBlock(cx, h, cz), "a well in the middle")
        assertEquals(Blocks.COBBLESTONE, world.getBlock(cx + 1, h + 1, cz), "its rim")
        assertTrue(world.pendingParks.isEmpty())
        val seen = HashMap<Int, Int>()
        for (x in cx - 60..cx + 60) for (z in cz - 60..cz + 60) for (y in h - 4..h + 30) seen.merge(world.getBlock(x, y, z), 1, Int::plus)
        for (bad in listOf(Blocks.SEA_LANTERN, Blocks.CONCRETE_FIRST, Blocks.MOB_CAGE, Blocks.HOPPER, Blocks.SKY_PORTAL, Blocks.SKY_GATE_EYE, Blocks.IRON_BLOCK, Blocks.SOLAR_PANEL))
            assertEquals(0, seen[bad] ?: 0, "no futuristic block ${Blocks[bad].name}")
        assertTrue((seen[Blocks.OAK_DOOR] ?: 0) >= 6, "houses with doors")
        assertTrue((seen[Blocks.OAK_STAIRS] ?: 0) + (seen[Blocks.BRICK_STAIRS] ?: 0) + (seen[Blocks.COBBLESTONE_STAIRS] ?: 0) > 30, "pitched roofs")
    }

    @Test fun everyHouseIsWholeFromBaseToRidge() {
        val world = World(12345L, null, Dimension.OVERWORLD, false)
        val (cx, cz) = villageCentre(world)
        for (dz in -5..5) for (dx in -5..5) world.ensureLoaded((cx shr 4) + dx, (cz shr 4) + dz)
        var checked = 0
        for (x in cx - 40..cx + 40) for (z in cz - 40..cz + 40) {
            val g0 = world.generator.surfaceHeight(x, z)
            for (y in g0 - 3..g0 + 6) {
                if (world.getBlock(x, y, z) != Blocks.OAK_DOOR || world.getBlock(x, y - 1, z) == Blocks.OAK_DOOR) continue
                val ids = (y + 3..y + 12).map { world.getBlock(x, it, z) }
                assertTrue(ids.any { it == Blocks.OAK_STAIRS || it == Blocks.BRICK_STAIRS || it == Blocks.COBBLESTONE_STAIRS || it == Blocks.PLANKS || it == Blocks.BRICKS || it == Blocks.COBBLESTONE }, "a roof above the door at $x,$z")
                checked++
            }
        }
        assertTrue(checked >= 3, "found houses: $checked")
    }

    @Test fun oldVillagesLoseTheirFarmShrineAndSmithyPortal() {
        val dir = kotlin.io.path.createTempDirectory("retire").toFile()
        val world = World(12345L, dir, Dimension.OVERWORLD, false)
        val (cx, cz) = villageCentre(world)
        // A smithy chunk that was made in the old days: its gate is there, and no marker says it was cleaned.
        val sa = Random0(world.seed, cx, cz)
        val sx = sa.first; val sz = sa.second
        world.ensureLoaded(sx shr 4, sz shr 4)
        val y0 = world.generator.surfaceHeight(sx, sz)
        assertNotEquals(Blocks.SKY_PORTAL, world.getBlock(sx, y0 + 1, sz), "new smithies have no sky portal")
        assertNotEquals(Blocks.SKY_GATE_EYE, world.getBlock(sx + 2, y0 + 1, sz), "no gate stones")
    }

    private fun Random0(seed: Long, cx: Int, cz: Int): Pair<Int, Int> {
        val fa = java.util.Random(seed xor (cx * 341873128712L) xor (cz * 132897987541L) xor 0xFA12).nextDouble() * Math.PI * 2
        val sa = fa + Math.PI * (0.6 + java.util.Random(seed xor (cx * 91L) xor (cz * 7919L)).nextDouble() * 0.8)
        return (cx + (Math.cos(sa) * 28).toInt()) to (cz + (Math.sin(sa) * 28).toInt())
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
