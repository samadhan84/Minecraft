import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** The roller coaster: it is built as one closed loop, the car rides all the way round and back, and you stay in till the station. */
class CoasterTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("coaster").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "C", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        return Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
    }

    @Test fun layoutIsAClosedLoop() {
        val cells = coasterLayout()
        for ((i, c) in cells.withIndex()) {
            val n = cells[(i + 1) % cells.size]
            assertEquals(1, kotlin.math.abs(n[0] - c[0]) + kotlin.math.abs(n[1] - c[1]), "step $i")
            assertTrue(kotlin.math.abs(n[2] - c[2]) <= 1)
        }
        assertEquals(cells.size, cells.map { it[0] to it[1] }.toSet().size)
        assertEquals(11, cells.maxOf { it[2] })
    }

    @Test fun rideGoesAllTheWayRoundAndBack() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        val car = g.buildCoaster(0, y, 0, 1, 0)
        // Empty, it waits at the station.
        repeat(60) { g.carts.update(0.05f, g) }
        assertEquals(0f, car.speed)
        g.player.x = car.x; g.player.y = car.y; g.player.z = car.z
        g.carts.enter(car, 1f, 0f)
        var top = 0f; var fastest = 0f; var left = false; var back = -1
        for (step in 0 until 20 * 120) {
            g.carts.update(0.05f, g)
            top = maxOf(top, car.y); fastest = maxOf(fastest, car.speed)
            assertTrue(car.y >= y - 0.01f, "on the track at step $step")
            if (car.x > 10f) left = true
            if (left && car.x < 4.5f && car.z < 1f) { back = step; break }
        }
        assertTrue(back > 0, "came back to the station")
        assertTrue(top >= y + 10f, "went up the lift hill")
        assertTrue(fastest > 12f, "raced down the drop: $fastest")
        // Safe: you can't get out while it is moving.
        if (car.speed > 1f) { g.carts.leave(g); assertSame(car, g.carts.riding) }
    }

    @Test fun itemSetsUpTheRide() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0)
        g.player.yaw = 0f
        assertTrue(g.coasterUse(Items[Items.find("Roller Coaster")], RayHit(0, y, 0, 0, 1, 0, Blocks.GRASS)))
        assertEquals(1, g.carts.list.count { it.kind == Cart.COASTER })
        assertTrue(Recipes.crafting.any { it.result == Items.find("Roller Coaster") })
    }
}
