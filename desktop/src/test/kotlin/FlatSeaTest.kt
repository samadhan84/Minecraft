import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** New flat worlds: flat land with oceans and beaches, and no villages or theme parks. */
class FlatSeaTest {
    @Test fun flatWorldsHaveOceansButNoVillages() {
        val world = World(777L, null, Dimension.OVERWORLD, flat = true, flatSea = true)
        val gen = world.generator as FlatGenerator
        // Land where you start.
        for (x in -100..100 step 10) for (z in -100..100 step 10) assertEquals(FlatGenerator.TOP, gen.surfaceHeight(x, z))
        var water = 0
        for (x in -1500..1500 step 25) for (z in -1500..1500 step 25) if (gen.ocean(x, z) > 0) water++
        assertTrue(water > 300, "oceans")
        // No villages, so no theme parks.
        for (rz in -8..8) for (rx in -8..8) assertNull(villageIn(777L, gen, rx, rz))
        for (cz in -6..6) for (cx in -6..6) world.ensureLoaded(cx, cz)
        assertTrue(world.pendingParks.isEmpty())
        // Out in the open sea it is as deep as the world allows: the floor is just above bedrock.
        val deepest = (-1500..1500 step 25).flatMap { x -> (-1500..1500 step 25).map { z -> gen.surfaceHeight(x, z) } }.min()
        assertTrue(deepest <= 8, "deep sea, floor at $deepest")
        // The sea is real water over a sandy floor.
        val (ox, oz) = (-1500..1500 step 25).flatMap { x -> (-1500..1500 step 25).map { z -> x to z } }.first { gen.ocean(it.first, it.second) > 0.1 }
        world.ensureLoaded(ox shr 4, oz shr 4)
        assertEquals(Blocks.WATER, world.getBlock(ox, FlatGenerator.TOP - 1, oz))
        assertEquals(Blocks.SAND, world.getBlock(ox, gen.surfaceHeight(ox, oz), oz))
    }

    @Test fun plainFlatWorldsStayPlain() {
        val world = World(777L, null, Dimension.OVERWORLD, flat = true)
        val gen = world.generator as FlatGenerator
        for (rz in -3..3) for (rx in -3..3) assertNull(villageIn(777L, gen, rx, rz))
        assertTrue((-2000..2000 step 50).none { gen.ocean(it, it) > 0 })
    }

    @Test fun newFlatWorldsGetTheSea() {
        val l = LevelData.create(1L, "F", GameMode.CREATIVE, flat = true)
        assertTrue(l.flat && l.flatSea)
        val dir = kotlin.io.path.createTempDirectory("lvl").toFile()
        l.write(dir)
        assertTrue(LevelData.read(dir)!!.flatSea)
    }
}
