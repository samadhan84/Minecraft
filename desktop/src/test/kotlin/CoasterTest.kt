import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** The steel roller coaster: one smooth closed track with a big drop and a loop; the train rides round and back. */
class CoasterTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("coaster").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "C", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        return Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
    }

    @Test fun trackIsSmoothClosedAndHasALoopAndADrop() {
        val c = CoasterTrack(0f, 70f, 0f, 1, 0)
        for (i in 0 until c.n - 1) {
            val d = kotlin.math.sqrt((c.px[i + 1] - c.px[i]).let { it * it } + (c.py[i + 1] - c.py[i]).let { it * it } + (c.pz[i + 1] - c.pz[i]).let { it * it })
            assertTrue(d < 0.6f, "gap of $d at point $i")
        }
        assertEquals(c.px[0], c.px[c.n - 1], 0.01f); assertEquals(c.py[0], c.py[c.n - 1], 0.01f); assertEquals(c.pz[0], c.pz[c.n - 1], 0.01f)
        assertTrue(c.py.max() >= 70f + 29f, "a 30-block lift hill")
        assertTrue((0 until c.n).any { c.u[it][1] < -0.95f }, "upside down at the top of the loop")
        assertTrue((0 until c.n).any { c.f[it][1] < -0.95f }, "a near-vertical drop")
        assertTrue(c.length > 200f)
    }

    @Test fun rideGoesRoundFastAndComesBack() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        val c = g.buildCoaster(0.5f, y, 0.5f, 1, 0)
        assertTrue(c.supports.isNotEmpty())
        // Empty, it waits in the station.
        repeat(100) { g.updateCoasters(0.05f) }
        assertFalse(c.running)
        g.coasters.riding = c
        var fastest = 0f; var top = 0f; var upsideDown = false; var lapped = false
        for (step in 0 until 20 * 200) {
            g.updateCoasters(0.05f)
            fastest = maxOf(fastest, c.speed); top = maxOf(top, g.player.y)
            if (c.u[c.index(c.s)][1] < -0.9f) upsideDown = true
            if (c.running && step > 100) {
                // Safe: you can't get out while it is moving.
                g.leaveCoaster(); assertSame(c, g.coasters.riding)
            }
            if (step > 200 && !c.running) { lapped = true; break }
        }
        assertTrue(lapped, "came back to the station")
        assertTrue(fastest > 25f, "fast: $fastest")
        assertTrue(upsideDown, "went through the loop")
        assertTrue(top > y + 28f, "went up the lift hill")
        g.leaveCoaster(); assertNull(g.coasters.riding)
    }

    @Test fun itemSetsUpTheRideAndItIsSaved() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0)
        g.player.yaw = 0f
        assertTrue(g.coasterUse(Items[Items.find("Roller Coaster")], RayHit(0, y, 0, 0, 1, 0, Blocks.GRASS)))
        assertEquals(1, g.coasters.list.size)
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { g.coasters.write(it) }
        val back = Coasters()
        back.read(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(1, back.list.size)
        assertEquals(g.coasters.list[0].supports.size, back.list[0].supports.size)
        assertTrue(Recipes.crafting.any { it.result == Items.find("Roller Coaster") })
    }

    @Test fun oldBlockCoastersAreUpgraded() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        for (cx in 0..2) g.world.ensureLoaded(cx, 0)
        // An old coaster's station (three powered rails) and its track along the first side, facing east.
        for (u in 0..35) g.setBlock(u, y, 0, if (u in 2..4) Blocks.POWERED_RAIL else Blocks.RAIL, 1)
        g.carts.list.add(Cart(3.5f, y.toFloat(), 0.5f, Cart.COASTER).also { it.hx = 1f; it.hz = 0f })
        g.upgradeOldCoasters()
        assertTrue(g.carts.list.none { it.kind == Cart.COASTER })
        assertEquals(1, g.coasters.list.size)
        val c = g.coasters.list[0]
        assertEquals(0.5f, c.ox); assertEquals(1, c.fx); assertEquals(0, c.fz)
        assertEquals(Blocks.AIR, g.world.getBlock(10, y, 0).let { if (Rails.isRail(it)) -1 else Blocks.AIR })
    }
}
