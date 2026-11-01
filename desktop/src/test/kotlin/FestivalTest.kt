import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** The festival calendar: 5 days before, until 1 day after; the items, the tab and the decorations. */
class FestivalTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("fest").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "F", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        return Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
    }

    @Test fun updateComesFiveDaysBeforeAndStaysOneDayAfter() {
        // Holi 2027 is 21-22 March: the update is on from 16 March to 23 March.
        assertNull(Festivals.active("2027-03-15"))
        assertEquals("holi", Festivals.active("2027-03-16")!!.festival.key)
        assertEquals(5, Festivals.active("2027-03-16")!!.daysToStart)
        assertEquals("holi", Festivals.active("2027-03-22")!!.festival.key)
        assertEquals("holi", Festivals.active("2027-03-23")!!.festival.key)
        assertNull(Festivals.active("2027-03-24"))
        // Fixed festivals repeat every year: Christmas, and Independence Day.
        assertEquals("christmas", Festivals.active("2030-12-20")!!.festival.key)
        assertEquals("christmas", Festivals.active("2030-12-26")!!.festival.key)
        assertNull(Festivals.active("2030-12-27"))
        assertEquals("independence", Festivals.active("2028-08-10")!!.festival.key)
        // Diwali 2026 (6-11 November) is on today and gone after 12 November.
        assertEquals("diwali", Festivals.active("2026-11-01")!!.festival.key)
        assertEquals("diwali", Festivals.active("2026-11-12")!!.festival.key)
        assertNull(Festivals.active("2026-11-13"))
        // Gudi Padwa, Ganesh Chaturthi (10 days) and Navratri.
        assertNotNull(Festivals.active("2026-03-14")); assertEquals("gudipadwa", Festivals.active("2026-03-19")!!.festival.key)
        assertEquals("ganesh", Festivals.active("2026-09-24")!!.festival.key)
        assertNotNull(Festivals.next("2026-11-13"))
        assertTrue(Festivals.announcement("2027-03-18")!!.contains("Holi"))
    }

    @Test fun itemsAreOnlyUsableDuringTheirFestival() {
        assertTrue(Festivals.usable("Gulal (Holi Colour)", "2027-03-20"))
        assertFalse(Festivals.usable("Gulal (Holi Colour)", "2027-04-20"))
        assertTrue(Festivals.usable("Diya", "2026-11-08")); assertTrue(Festivals.usable("Diya", "2026-10-08"))
        assertTrue(Festivals.usable("Sutli Bomb", "2026-11-08")); assertFalse(Festivals.usable("Sutli Bomb", "2026-12-20"))
        assertTrue(Festivals.usable("Christmas Tree", "2026-12-25"))
        assertTrue(Festivals.whenAgain("Gulal (Holi Colour)", "2026-11-20").startsWith("Holi"))
    }

    @Test fun everyFestivalItemExistsWithARecipeAndIcon() {
        for (f in Festivals.all) for (fi in f.items) {
            val id = Items.find(fi.name)
            assertTrue(Items.isItem(id), fi.name)
            assertTrue(Recipes.crafting.any { it.result == id }, "recipe for ${fi.name}")
            if (fi.food > 0) assertEquals(fi.food, Items[id]!!.food)
        }
        assertTrue(Festivals.all.size >= 18)
    }

    @Test fun decorationsAreBuiltAndItemsWork() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0)
        g.player.yaw = 0f; g.player.x = 0.5f; g.player.z = 0.5f; g.player.y = y + 1f
        // Build each kind (the festival gate is time based, so call the builder).
        for (kind in listOf("flag", "gudi", "tree", "snowman", "ganesh", "effigy", "rangoli", "pookalam", "lantern")) {
            for (cz in -4..0) for (cx in -1..1) g.world.ensureLoaded(cx, cz)
            // Clear the spot (and put the grass back) so only this decoration is counted.
            for (dx in -8..8) for (dz in -9..2) { g.setBlock(dx, y, dz, Blocks.GRASS); for (h in 1..16) g.setBlock(dx, y + h, dz, Blocks.AIR) }
            g.buildFestival(kind, 0, y, 0, 0f, -1f)
            val blocks = (-8..8).sumOf { dx -> (-9..2).sumOf { dz -> (1..16).count { h -> g.world.getBlock(dx, y + h, dz) != Blocks.AIR } } }
            val painted = (-8..8).sumOf { dx -> (-9..2).count { dz -> g.world.getBlock(dx, y, dz) in Blocks.CONCRETE_FIRST until Blocks.CONCRETE_FIRST + 16 } }
            assertTrue(blocks + painted > 3, "$kind put something up")
        }
        // Eating a sweet works (food), and a gulal burst makes sparks.
        g.fireworks.colourBurst(0f, y + 2f, 0f, 50, 3f, Fireworks.HOLI, 1.5f, 3f)
        assertTrue(g.fireworks.sparks.size >= 50)
        // Festival items are kept out of the ordinary Items tab.
        assertTrue(Festivals.isFestivalItem("Modak") && !Festivals.isFestivalItem("Bread"))
    }

    @Test fun diyaBurnsForTwoMinutesThenGoes() {
        val g = game()
        g.fireworks.light(Cracker.DIYA, 1.5f, 70f, 1.5f)
        repeat(20 * 60) { g.fireworks.update(0.05f, g) }
        assertEquals(1, g.fireworks.lit.size, "still burning after a minute")
        repeat(20 * 70) { g.fireworks.update(0.05f, g) }
        assertTrue(g.fireworks.lit.isEmpty())
    }
}
