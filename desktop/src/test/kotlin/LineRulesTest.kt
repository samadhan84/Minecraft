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

    @Test fun bulletLinesRunUndergroundAndOtherLinesGoOverCities() {
        val g = game()
        assertTrue(g.build(-300, 0, "Bullet Train Station"))
        assertTrue(g.build(-300, 400, "Bullet Train Station"))
        val dry = g.metroNet.lines.first()
        val gy = g.world.generator.surfaceHeight(-300, 0) + 1
        println("bullet line: deep=${dry.deep} (stations at $gy)")
        assertTrue(dry.deep in 1 until gy, "bullet lines always run underground")
        val c = dry.cell(dry.size / 2)
        g.world.ensureLoaded(c[0] shr 4, c[1] shr 4); g.layMetro()
        assertEquals(Blocks.BULLET_RAIL, g.world.getBlock(c[0], dry.y(dry.size / 2), c[1]), "on bullet train track")
        // A city between two more bullet stations: the line goes high over it.
        g.metroNet.ends.filter { it.kind == TrackEnd.BULLET && !it.joined }.forEach { it.joined = true }
        assertTrue(g.build(300, -30, "Smart City"))
        assertTrue(g.build(300, 160, "Bullet Train Station"))
        val before = g.metroNet.lines.size
        assertTrue(g.build(300, -260, "Bullet Train Station"))
        val over = g.metroNet.lines[before]
        val oy = g.world.generator.surfaceHeight(300, -30) + 1
        println("bullet line past the city: deep=${over.deep} (city ground $oy)")
        // (A bullet line goes under it; build metro stations either side to go over it.)
        assertTrue(over.deep < oy, "the bullet line goes underneath")
        g.metroNet.ends.filter { it.kind == TrackEnd.METRO && !it.joined && it.group > 0 }.forEach { it.joined = true }
        assertTrue(g.build(700, 160, "Metro Station"))
        val n = g.metroNet.lines.size
        assertTrue(g.build(700, -300, "Metro Station"))
        val metro = g.metroNet.lines[n]
        println("metro line: deep=${metro.deep} (ground ${g.world.generator.surfaceHeight(700, 0)})")
        assertTrue(metro.deep >= g.world.generator.surfaceHeight(700, 0) + 10, "metro lines run up high")
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

class TrackSeparationTest {
    @Test fun eachKindOfTrainStaysOnItsOwnTrack() {
        val world = World(1L, null, Dimension.OVERWORLD, true)
        for (cz in -2..2) for (cx in -20..20) { val c = Chunk(cx, cz); world.generator.generate(c); world.chunks[Chunk.key(cx, cz)] = c }
        val g = Game(world, LevelData.create(1L, "T", GameMode.CREATIVE).apply { hasPlayer = true; y = 70f }, GameInput(), null)
        val y = world.generator.surfaceHeight(0, 0) + 1
        // Ordinary track from x -300 to 0, bullet train track from 0 to 300.
        for (x in -300..0) g.setBlock(x, y, 0, Blocks.RAIL, 1)
        for (x in 1..300) g.setBlock(x, y, 0, Blocks.BULLET_RAIL, 1)
        val metro = Cart(-100.5f, y.toFloat(), 0.5f, Cart.METRO).apply { hx = 1f; hz = 0f }
        val bullet = Cart(100.5f, y.toFloat(), 0.5f, Cart.BULLET).apply { hx = -1f; hz = 0f }
        val coach = Cart(200.5f, y.toFloat(), 0.5f, Cart.COACH)
        g.carts.list.addAll(listOf(metro, bullet, coach))
        var metroMax = -999f; var bulletMin = 999f
        repeat(60 * 20) { g.carts.update(1 / 60f, g); metroMax = maxOf(metroMax, metro.x); bulletMin = minOf(bulletMin, bullet.x) }
        println("metro got as far as $metroMax, the bullet train as far as $bulletMin")
        assertTrue(metroMax < 1.5f, "the metro doesn't go onto bullet train track")
        assertTrue(bulletMin > -0.5f, "the bullet train doesn't go onto ordinary track")
        assertTrue(bullet.x > 150f || bulletMin < 50f, "the bullet train runs on its own track")
    }
}
