import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Bullet train stations have their own line, deep in a glass tube under the sea. */
class BulletLineTest {
    @Test fun bulletStationsJoinUnderTheSea() {
        val dir = kotlin.io.path.createTempDirectory("bullet").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, false)
        val lvl = LevelData.create(42L, "T", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f; flat = false }
        val g = Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false; it.timeOfDay = 0.25f }
        fun ground(x: Int, z: Int): Int { world.ensureLoaded(x shr 4, z shr 4); return world.generator.surfaceHeight(x, z) }
        fun build(x: Int, z: Int, what: String): Boolean {
            g.player.yaw = 0f; g.player.x = x + 0.5f; g.player.z = z + 4.5f
            return g.placeBuilding(RayHit(x, ground(x, z), z, 0, 1, 0, Blocks.GRASS), what)
        }
        assertTrue(Items.find("Blueprint: Bullet Train Station") > 0)
        assertTrue(build(0, 0, "Bullet Train Station"))
        assertTrue(build(200, -40, "Metro Station"))
        assertTrue(g.metroNet.lines.isEmpty(), "a metro station doesn't join a bullet station")
        // A sea between here and the next bullet station.
        val sea = TerrainGenerator.SEA_LEVEL
        for (x in 60..300) for (z in -30..20) { world.ensureLoaded(x shr 4, z shr 4); for (y in 30..sea) g.setBlock(x, y, z, Blocks.WATER) }
        for (x in 60..300) for (z in -30..20) for (y in sea + 1..sea + 30) if (world.getBlock(x, y, z) != Blocks.AIR) g.setBlock(x, y, z, Blocks.AIR)
        assertTrue(build(400, 0, "Bullet Train Station"))
        val lines = g.metroNet.lines
        assertEquals(2, lines.size, "two tracks")
        assertTrue(lines.all { it.kind == TrackEnd.BULLET && it.deep > 0 })
        for (l in lines) {
            val keys = LinkedHashSet<Long>()
            for (k in 0 until l.size) { val c = l.cell(k); keys.add(Chunk.key(c[0] shr 4, c[1] shr 4)) }
            for (key in keys) world.ensureLoaded((key shr 32).toInt(), key.toInt())
        }
        g.layMetro()
        g.player.x = 200f; g.player.z = 0f; g.player.y = 90f; g.player.flying = true
        repeat(120) { g.update(1 / 30f); g.dirtyChunks.clear() }   // let the water settle
        val l = lines.first()
        var underSea = 0; var wet = 0; var glass = 0
        for (k in 0 until l.size) {
            val c = l.cell(k); val y = l.y(k)
            world.ensureLoaded(c[0] shr 4, c[1] shr 4)
            assertTrue(Rails.isRail(world.getBlock(c[0], y, c[1])), "rail $k at ${c[0]}, $y, ${c[1]}")
            if (c[0] in 70..290 && y < sea) {
                underSea++
                if (world.getBlock(c[0], y + 1, c[1]) == Blocks.WATER || world.getBlock(c[0], y + 2, c[1]) == Blocks.WATER) wet++
                if (world.getBlock(c[0], y + 3, c[1]) == Blocks.GLASS) glass++
            }
        }
        println("line ${l.size} rails, tunnel at y=${l.deep} (sea level $sea): $underSea under the sea, $glass with a glass roof, $wet flooded")
        assertTrue(underSea > 150)
        assertEquals(0, wet, "no water in the tube")
        assertTrue(glass > underSea / 2, "glass roof in the sea")
        // Call a bullet train at the first station and ride it to the far one.
        val s0 = g.metroNet.stations.first { it[3] == TrackEnd.BULLET }
        g.player.x = s0[0] + 0.5f; g.player.z = s0[2] + 0.5f; g.player.y = s0[1] + 1f
        for (cz in -2..2) for (cx in -3..3) world.ensureLoaded((s0[0] shr 4) + cx, (s0[2] shr 4) + cz)
        g.callTrain("Bullet Train", s0[0], s0[1], s0[2])
        val head = g.carts.head(g.carts.riding!!)
        val offer = generateSequence { g.uiEvents.poll() }.toList().first { it.startsWith("metro:") }.removePrefix("metro:").split('|')
        println("offers $offer")
        g.metroTo(offer.single())
        var arrived = false; var lowest = 999f
        repeat(60 * 90) {
            if (arrived) return@repeat
            g.update(1 / 60f); g.dirtyChunks.clear()
            val front = g.carts.head(g.carts.riding!!)   // the front car changes when it turns round
            lowest = minOf(lowest, front.y)
            if (lowest < sea - 5 && front.stopTimer > 0.5f && kotlin.math.abs(front.x - 400f) < 30f) arrived = true
        }
        println("bullet went as deep as y=$lowest and arrived: $arrived (at ${head.x}, ${head.y}, ${head.z})")
        assertTrue(lowest < sea - 5, "it went under the sea")
        assertTrue(arrived)
    }
}
