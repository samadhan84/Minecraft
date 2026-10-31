import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Three-part trains, lines over cities, the tunnel only under water, and lines that can't be joined onto. */
class LineRulesTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("rules").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "T", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        return Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
    }
    private fun Game.build(x: Int, z: Int, what: String): Boolean {
        world.ensureLoaded(x shr 4, z shr 4)
        player.yaw = 0f; player.x = x + 0.5f; player.z = z + 4.5f
        return placeBuilding(RayHit(x, world.generator.surfaceHeight(x, z), z, 0, 1, 0, Blocks.GRASS), what)
    }

    @Test fun metroAndBulletTrainsHaveACabAtEachEndAndADriver() {
        val g = game()
        assertTrue(g.build(0, 0, "Metro Station"))
        assertTrue(g.build(0, -200, "Bullet Train Station"))
        val trains = g.carts.list.filter { it.leader == null && it.kind != Cart.ENGINE }.map { g.carts.chain(it).map { c -> c.kind } }
        println("trains: $trains")
        assertEquals(4, trains.size)
        for (t in trains) { assertEquals(3, t.size); assertEquals(Cart.COACH, t[1]); assertEquals(t[0], t[2]) }
        // An older train (a metro and a coach) gets its back driving car when the world is opened.
        val g2 = game()
        val y = g2.world.generator.surfaceHeight(0, 0) + 1f
        val m = Cart(0.5f, y, 0.5f, Cart.METRO).apply { hx = 1f; hz = 0f }
        g2.carts.list.add(m); g2.carts.list.add(Cart(-2.5f, y, 0.5f, Cart.COACH).also { it.leader = m; it.hx = 1f })
        g2.metroNet.cabsChecked = false
        g2.layMetro()
        assertEquals(listOf(Cart.METRO, Cart.COACH, Cart.METRO), g2.carts.chain(m).map { it.kind })
    }

    @Test fun bulletLinesStayUpWithoutWaterAndGoOverCities() {
        val g = game()
        assertTrue(g.build(-300, 0, "Bullet Train Station"))
        assertTrue(g.build(-300, 400, "Bullet Train Station"))
        val dry = g.metroNet.lines.first()
        println("dry bullet line: deep=${dry.deep}")
        assertEquals(0, dry.deep, "no water, no tunnel")
        // A city between two more bullet stations: the line goes high over it.
        g.metroNet.ends.filter { it.kind == TrackEnd.BULLET && !it.joined }.forEach { it.joined = true }
        assertTrue(g.build(300, -30, "Smart City"))
        assertTrue(g.build(300, 160, "Bullet Train Station"))
        val before = g.metroNet.lines.size
        assertTrue(g.build(300, -260, "Bullet Train Station"))
        val over = g.metroNet.lines[before]
        val oy = g.world.generator.surfaceHeight(300, -30) + 1
        println("line past the city: deep=${over.deep} (city ground $oy), ${over.size} rails, ends ${over.ends.toList()}")
        assertTrue(over.deep >= oy + 50, "high over the city")
    }

    @Test fun railsCantBeJoinedOntoALine() {
        val g = game()
        assertTrue(g.build(0, 0, "Metro Station"))
        val s = g.metroNet.stations.first()
        // Next to the station's track: refused. Far away: fine.
        val y = s[1]
        assertTrue(g.wouldJoinLine(s[0] + 10, y, s[2] - 2), "beside the near track")
        assertFalse(g.wouldJoinLine(s[0] + 100, y, s[2] + 100))
    }
}
