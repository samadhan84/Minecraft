import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Petrol pumps, electric cars, and the petrol station in the city. */
class PetrolTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("petrol").toFile()
        val world = World(42L, dir, Dimension.OVERWORLD, true)
        val lvl = LevelData.create(42L, "P", GameMode.CREATIVE).apply { hasPlayer = true; x = 0.5f; y = 90f; z = 0.5f }
        return Game(world, lvl, GameInput(), dir).also { it.mobs.hostileEnabled = false }
    }

    @Test fun smartCityHasAPetrolStationAndElectricCars() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        g.player.yaw = 0f
        assertTrue(g.placeBuilding(RayHit(0, g.world.generator.surfaceHeight(0, 0), 0, 0, 1, 0, Blocks.GRASS), "Smart City"))
        for (cz in -10..2) for (cx in -6..6) g.world.ensureLoaded(cx, cz)
        var pumps = 0
        for (z in -130..0) for (x in -60..60) for (y in g.world.generator.surfaceHeight(0, 0)..g.world.generator.surfaceHeight(0, 0) + 3)
            if (g.world.getBlock(x, y, z) == Blocks.PETROL_PUMP) pumps++
        assertEquals(3, pumps, "three petrol pumps")
        assertTrue(g.vehicles.list.count { it.kind == Vehicle.EV_CAR } >= 4, "electric cars")
        assertTrue(g.vehicles.list.any { it.kind == Vehicle.CAR }, "and petrol ones")
    }

    @Test fun electricCarItemAndSaving() {
        val g = game()
        assertTrue(Items.shown.any { it.name == "EV Car" } && Blocks.inCategory(Category.HOME).contains(Blocks.PETROL_PUMP))
        assertTrue(Recipes.crafting.any { it.result == Items.find("EV Car") })
        assertTrue(Recipes.crafting.any { it.result == Blocks.PETROL_PUMP })
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0)
        assertTrue(g.vehicleUse(Items[Items.find("EV Car")], RayHit(2, y, 2, 0, 1, 0, Blocks.GRASS)))
        assertEquals(Vehicle.EV_CAR, g.vehicles.list.single().kind)
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { g.vehicles.write(it) }
        val back = Vehicles(g.world)
        back.read(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(Vehicle.EV_CAR, back.list.single().kind)
    }

    @Test fun youSinkSlowlyInDeepWater() {
        val g = game()
        g.world.ensureLoaded(0, 0)
        for (y in 20..60) g.setBlock(0, y, 0, Blocks.WATER)
        g.player.x = 0.5f; g.player.y = 55f; g.player.z = 0.5f; g.player.flying = false
        repeat(60) { g.player.update(1 / 60f, g.world, 0f, 0f, false, false) }
        assertTrue(g.player.vy >= -1.7f && g.player.vy < 0f, "drifting down slowly: ${g.player.vy}")
        assertTrue(g.player.y < 55f && g.player.y > 50f, "sank a little: ${g.player.y}")
    }
}
