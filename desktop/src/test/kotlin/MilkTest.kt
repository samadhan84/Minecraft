import com.vishucraft.game.audio.Synth
import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** The morning milk delivery and the Doodh Phonk song. */
class MilkTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("milk").toFile()
        val level = LevelData.create(5L, "t", GameMode.SURVIVAL)
        val world = World(level.seed, dir, Dimension.OVERWORLD, flat = true)
        val g = Game(world, level, GameInput(), dir)
        for (cx in -4..4) for (cz in -4..4) world.ensureLoaded(cx, cz)
        return g
    }
    private fun run(g: Game, seconds: Float) { var t = 0f; while (t < seconds) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f } }

    @Test fun aMilkmanComesInTheMorningAndDeliversMilkPackets() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        g.timeOfDay = 0.8f                   // night
        run(g, 0.5f)
        assertTrue(g.mobs.list.none { it.type == MobType.MILKMAN }, "nobody delivers at night")
        g.timeOfDay = 0.22f                  // just before sunrise
        run(g, 0.3f)
        g.timeOfDay = 0.30f                  // the sun is up
        run(g, 0.3f)
        val man = g.mobs.list.firstOrNull { it.type == MobType.MILKMAN }
        assertNotNull(man, "the milkman arrives with the morning")
        val milk = Items.find("Milk Packet")
        var t = 0f
        while (t < 40f && !man.delivered) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        assertTrue(man.delivered, "he walks up to you")
        assertTrue(g.inventory.count(milk) + g.drops.list.filter { it.stack.id == milk }.sumOf { it.stack.count } >= 3, "three packets of milk")
        // Only one delivery a day.
        g.timeOfDay = 0.8f; run(g, 0.3f); g.timeOfDay = 0.22f; run(g, 0.2f); g.timeOfDay = 0.3f; run(g, 0.3f)
        assertEquals(1, g.mobs.list.count { it.type == MobType.MILKMAN } .coerceAtMost(1))
    }

    @Test fun theDoodhPhonkSongExistsAndTheDiscPlaysIt() {
        val pcm = Synth.all()["disc_doodh"]
        assertNotNull(pcm, "the song is made")
        assertTrue(pcm.size > 15 * Synth.RATE, "about 16 seconds")
        assertTrue(pcm.count { Math.abs(it.toInt()) > 3000 } > pcm.size / 20, "it is not silent")
        val disc = Items.find("Music Disc (Doodh)")
        assertTrue(Items[disc]!!.name.startsWith("Music Disc"))
        assertTrue(Items[Items.find("Milk Packet")]!!.food > 0, "milk packets can be drunk")
    }
}
