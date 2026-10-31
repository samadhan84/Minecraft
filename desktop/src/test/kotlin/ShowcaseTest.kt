import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** The showcase world (everything built, creative only) and the glass car. */
class ShowcaseTest {
    @Test fun theShowcaseWorldHasEverythingBuilt() {
        val dir = kotlin.io.path.createTempDirectory("showcase").toFile()
        val lvl = LevelData.create(2026L, SHOWCASE_NAME, GameMode.CREATIVE)
        val world = World(2026L, dir, Dimension.OVERWORLD, lvl.flat)
        val g = Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
        val t0 = System.currentTimeMillis()
        var frames = 0
        while (g.metroNet.stations.isEmpty() && frames < 600) { g.update(1 / 30f); g.dirtyChunks.clear(); frames++ }
        val ms = System.currentTimeMillis() - t0
        val net = g.metroNet
        println("built in $ms ms: ${net.stations.size} stations (${net.stations.count { it[3] == TrackEnd.RAILWAY }} railway), " +
            "${net.lines.size} lines, ${net.ends.count { !it.joined }} open ends, ${g.aircraft.airports.size} airports, " +
            "${g.vehicles.list.size} vehicles, ${g.carts.list.size} train cars, ${g.mobs.list.size} creatures")
        assertTrue(g.isShowcase)
        assertEquals(2, g.aircraft.airports.size)
        assertTrue(net.stations.count { it[3] == TrackEnd.METRO } >= 10)
        assertTrue(net.stations.count { it[3] == TrackEnd.RAILWAY } >= 5)
        assertEquals(2, net.stations.count { it[3] == TrackEnd.BULLET })
        assertEquals(2, net.lines.count { it.kind == TrackEnd.BULLET })
        // Every city and station on one metro line and one railway line: only the two outermost ends are open.
        assertEquals(2, net.ends.count { !it.joined && it.kind == TrackEnd.METRO })
        assertEquals(2, net.ends.count { !it.joined && it.kind == TrackEnd.RAILWAY })
        assertTrue(g.vehicles.list.any { it.kind == Vehicle.GLASS_CAR })
        assertTrue(g.mobs.list.any { it.type == MobType.ROBOT })
        assertTrue(g.mobs.list.any { it.type == MobType.IRON_GOLEM })
        // Creative only, and built just once.
        assertEquals("The showcase world is creative only", g.switchMode())
        assertFalse(g.survival)
        val stations = net.stations.size
        repeat(30) { g.update(1 / 30f) }
        assertEquals(stations, net.stations.size)
    }

    @Test fun glassCarsAreMadeAndPlaced() {
        assertEquals("Glass Car", Vehicle.itemName(Vehicle.GLASS_CAR))
        assertTrue(Items.find("Glass Car") > 0)
        assertTrue(Recipes.crafting.any { it.result == Items.find("Glass Car") })
    }
}
