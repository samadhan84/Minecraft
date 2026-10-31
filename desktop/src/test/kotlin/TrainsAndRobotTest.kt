import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Station call buttons, private and express trains, more travellers, the helper robot and the play limit. */
class TrainsAndRobotTest {
    private fun game(flat: Boolean = true, creative: Boolean = true): Game {
        val dir = kotlin.io.path.createTempDirectory("trains").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, flat)
        val lvl = LevelData.create(42L, "T", if (creative) GameMode.CREATIVE else GameMode.SURVIVAL).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        return Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false; it.timeOfDay = 0.25f }
    }
    private fun Game.ground(x: Int, z: Int): Int { world.ensureLoaded(x shr 4, z shr 4); return world.generator.surfaceHeight(x, z) }
    private fun Game.build(x: Int, z: Int, yaw: Float, what: String): Boolean {
        player.yaw = yaw; player.x = x + 0.5f; player.z = z + 4.5f
        return placeBuilding(RayHit(x, ground(x, z), z, 0, 1, 0, Blocks.GRASS), what)
    }
    private fun Game.loadLines() {
        for (l in metroNet.lines) {
            val keys = LinkedHashSet<Long>()
            for (k in 0 until l.size) { val c = l.cell(k); keys.add(Chunk.key(c[0] shr 4, c[1] shr 4)) }
            for (key in keys) world.ensureLoaded((key shr 32).toInt(), key.toInt())
        }
        layMetro()
    }
    private fun Game.button(near: IntArray): IntArray? {
        for (dy in -1..2) for (dz in -4..4) for (dx in -4..4) if (world.getBlock(near[0] + dx, near[1] + dy, near[2] + dz) == Blocks.CALL_BUTTON)
            return intArrayOf(near[0] + dx, near[1] + dy, near[2] + dz)
        return null
    }
    private fun Game.toasts(): List<String> { val out = ArrayList<String>(); while (true) { val e = uiEvents.poll() ?: break; out.add(e) }; return out }

    @Test fun noDailyTimer() {
        val l = PlayLimit("2026-10-29", 99999f)
        assertFalse(l.over)
        assertNull(l.tick("2026-10-29", 5f))
        assertTrue(l.minutesLeft("2026-10-29") > 100000)
    }

    @Test fun trainsStopForFiveSecondsAndBulletTrainsAreFast() {
        assertEquals(5f, Carts.STOP_SECONDS)
        assertTrue(Cart(0f, 0f, 0f, Cart.BULLET).maxSpeed >= 80f)
        val g = game()
        val y = g.ground(0, 0) + 1
        for (x in -300..300) { g.world.ensureLoaded(x shr 4, -1); g.setBlock(x, y, -3, Blocks.BULLET_RAIL, 1) }
        // A car standing in the way on the bullet train's track: the bullet train goes straight through it.
        val metro = Cart(-150.5f, y.toFloat(), -2.5f, Cart.COACH).apply { hx = 1f; hz = 0f }
        val bullet = Cart(-290.5f, y.toFloat(), -2.5f, Cart.BULLET).apply { hx = 1f; hz = 0f }
        g.carts.list.add(metro); g.carts.list.add(bullet)
        var top = 0f; var passed = false
        repeat(60 * 12) { g.carts.update(1 / 60f, g); top = maxOf(top, bullet.speed); if (bullet.x > metro.x + 5f) passed = true }
        println("bullet top speed $top (airplane ${Aircraft.CRUISE_SPEED}); passed the metro: $passed")
        assertTrue(top > 70f)
        assertTrue(passed, "the bullet train goes straight through a slower train in front")
    }

    @Test fun bulletTrainsRunExpress() {
        val g = game()
        assertTrue(g.build(0, 0, 0f, "Bullet Train Station"))
        assertTrue(g.build(300, 0, 0f, "Bullet Train Station"))
        assertTrue(g.build(600, 0, 0f, "Bullet Train Station"))
        g.loadLines()
        g.carts.list.clear()
        // A bullet train leaving the first station towards the others, on its own track (row 4).
        val s0 = g.metroNet.stations[0]
        val bullet = Cart(s0[0] + 8.5f, s0[1].toFloat(), s0[2] + 1.5f, Cart.BULLET).apply { hx = 1f; hz = 0f }
        g.carts.list.add(bullet)
        g.player.x = bullet.x; g.player.y = bullet.y; g.player.z = bullet.z
        g.carts.riding = bullet
        var stoppedMiddle = false; var far = 0f
        repeat(60 * 40) {
            g.update(1 / 60f); g.dirtyChunks.clear()
            val front = g.carts.head(bullet)
            far = maxOf(far, front.x)
            if (front.stopTimer > 0f && kotlin.math.abs(front.x - 300f) < 20f) stoppedMiddle = true
        }
        println("express bullet got to x=$far")
        assertTrue(far > 500f, "it ran along the line")
        assertFalse(stoppedMiddle, "an express bullet train doesn't stop at the stations in between")
    }

    @Test fun everyStationHasACallButtonThatBringsAPrivateTrain() {
        val g = game()
        assertTrue(g.build(0, 0, 0f, "Metro Station"))
        assertTrue(g.build(300, 0, 0f, "Metro Station"))
        assertTrue(g.build(600, 0, 0f, "Metro Station"))
        g.loadLines()
        assertTrue(g.build(0, -200, 0f, "Railway Station"))
        for (s in g.metroNet.stations) assertNotNull(g.button(s), "station at ${s.toList()} has a call button")
        // Press the first station's button and call a metro: the nearest one comes here, just for us.
        val s0 = g.metroNet.stations[0]
        val b = g.button(s0)!!
        g.player.x = b[0] + 0.5f; g.player.z = b[2] + 1.5f; g.player.y = b[1].toFloat()
        g.toasts()
        // Make the nearest metro one from the far station, to see it come all the way. The button calls the
        // station's own kind of train straight away.
        g.carts.list.removeAll { kotlin.math.abs(it.x - s0[0]) < 100f }
        assertTrue(g.callButtonTap(RayHit(b[0], b[1], b[2], 0, 1, 0, Blocks.CALL_BUTTON), 0))
        assertEquals(Cart.METRO, g.carts.power(g.carts.riding!!)!!.kind)
        val riding = g.carts.riding
        assertNotNull(riding, "on board")
        val head = g.carts.head(riding)
        assertTrue(head.private)
        assertTrue(kotlin.math.abs(head.x - b[0]) < 12f && kotlin.math.abs(head.z - b[2]) < 6f, "the train came to this station (${head.x}, ${head.z})")
        val offer = g.toasts().first { it.startsWith("metro:") }.removePrefix("metro:").split('|')
        println("private metro offers $offer")
        assertEquals(2, offer.size)
        // Nobody else gets on, and it goes non-stop to the far station.
        g.metroTo(offer.last())
        g.player.flying = true
        var arrived = false; var stoppedMiddle = false
        repeat(60 * 120) {
            if (arrived) return@repeat
            g.update(1 / 60f); g.dirtyChunks.clear()
            if (g.mobs.list.any { it.ride != null && g.carts.chain(head).any { c -> c === it.ride } }) fail("someone else got on the private train")
            val front = g.carts.head(g.carts.riding!!)   // the front car changes when it turns round
            if (front.stopTimer > 0.5f && kotlin.math.abs(front.x - 300f) < 20f) stoppedMiddle = true
            if (front.stopTimer > 0.5f && kotlin.math.abs(front.x - 600f) < 20f) arrived = true
        }
        assertFalse(stoppedMiddle, "non-stop past the station in between")
        assertTrue(arrived, "arrived at the far station (train at ${head.x})")
        // Getting off puts it back into service.
        val front = g.carts.head(g.carts.riding!!)
        g.carts.leave(g)
        assertFalse(front.private)
    }

    @Test fun olderStationsGetCallButtons() {
        val g = game()
        assertTrue(g.build(0, 0, 0f, "Metro Station"))
        val s = g.metroNet.stations[0]
        val b = g.button(s)!!
        g.setBlock(b[0], b[1], b[2], Blocks.AIR)
        g.metroNet.version = 3
        g.layMetro()
        assertNotNull(g.button(s), "the button is back")
        assertEquals(MetroNetwork.VERSION, g.metroNet.version)
    }

    @Test fun moreTravellersArrive() {
        val g = game()
        assertTrue(g.build(0, 0, 0f, "Metro Station"))
        g.carts.list.clear()
        val s = g.metroNet.stations[0]
        g.mobs.list.removeAll { it.type.isCitizen }
        g.player.x = s[0] + 0.5f; g.player.z = s[2] + 12f; g.player.flying = true
        repeat(60 * 70) { g.update(1 / 60f); g.dirtyChunks.clear() }
        val people = g.mobs.list.count { it.type.isCitizen && kotlin.math.abs(it.x - s[0]) < 16f && kotlin.math.abs(it.z - s[2]) < 16f }
        println("$people people turned up at the station")
        assertTrue(people in 3..8)
    }

    private fun Game.robot(): Mob {
        val r = Mob(MobType.ROBOT, player.x + 1f, player.y, player.z)
        mobs.list.add(r)
        return r
    }

    @Test fun theRobotFollowsAndDoesWhatItIsTold() {
        val g = game(flat = false)
        g.player.flying = true
        val y = g.ground(0, 0) + 1
        g.player.x = 0.5f; g.player.y = y.toFloat(); g.player.z = 0.5f
        for (cz in -3..3) for (cx in -3..3) g.world.ensureLoaded(cx, cz)
        val r = g.robot()
        assertTrue(Items.find("Robot Spawn Egg") > 0)
        // Follows the player around.
        g.player.x = 30.5f
        repeat(60 * 6) { g.update(1 / 60f); g.dirtyChunks.clear() }
        assertTrue(kotlin.math.hypot(r.x - g.player.x, r.z - g.player.z) < 4f, "follows (robot at ${r.x}, ${r.z})")
        // Gives things in creative mode.
        g.inventory.clear(); g.toasts()
        g.robotCommand(r.uid, "please give me 10 diamonds")
        println(g.toasts())
        assertEquals(10, g.inventory.count(Items.find("Diamond")))
        // Mines the ores around and hands them over.
        g.inventory.clear()
        for (k in 0 until 5) g.setBlock(32 + k, y - 3, 2, Blocks.IRON_ORE)
        g.robotCommand(r.uid, "mine")
        repeat(60 * 30) { g.update(1 / 60f); g.dirtyChunks.clear() }
        println("after mining: " + g.toasts().filter { it.startsWith("toast:Robot") })
        assertTrue(g.inventory.count(Items.find("Raw Iron")) >= 5, "got the iron")
        assertTrue((0 until 5).none { g.world.getBlock(32 + it, y - 3, 2) == Blocks.IRON_ORE })
        // Stays, builds, says what it can do, and owns up to things it can't.
        g.robotCommand(r.uid, "stay here"); val sx = r.x
        g.player.x = 60.5f; repeat(120) { g.update(1 / 60f) }
        assertTrue(kotlin.math.abs(r.x - sx) < 1f, "stays")
        g.toasts()
        g.robotCommand(r.uid, "help"); assertTrue(g.toasts().any { it.contains("follow me") })
        g.robotCommand(r.uid, "fly to the moon and bake a cake"); assertTrue(g.toasts().any { it.contains("don't know") || it.contains("Good night") || it.contains("Sorry") })
        g.player.yaw = 0f; g.player.flying = false; g.player.y = g.ground(60, 0) + 1f
        g.robotCommand(r.uid, "build a house")
        assertTrue(g.toasts().any { it.contains("Built small house") }, "builds a house")
    }

    @Test fun theRobotOnlyGivesThingsInCreative() {
        val g = game(creative = false)
        val r = g.robot()
        g.inventory.clear(); g.toasts()
        g.robotCommand(r.uid, "give me 64 diamond blocks")
        assertEquals(0, g.inventory.count(Blocks.DIAMOND_BLOCK))
        assertTrue(g.toasts().any { it.contains("creative") })
    }
}
