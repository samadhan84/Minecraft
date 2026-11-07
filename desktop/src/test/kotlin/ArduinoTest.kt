import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Arduino and ESP32 boards: programs, the OUT side, Wi-Fi channels. */
class ArduinoTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("ard").toFile()
        val level = LevelData.create(7L, "t", GameMode.CREATIVE)
        val world = World(level.seed, dir, Dimension.OVERWORLD, flat = true)
        val g = Game(world, level, GameInput(), dir)
        for (cx in -3..3) for (cz in -3..3) world.ensureLoaded(cx, cz)
        return g
    }

    private fun run(g: Game, seconds: Float) { var t = 0f; while (t < seconds) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f } }

    /** A board at (x,100,5) facing east (power goes out towards +x), a lamp in front and a place behind for a power source. */
    private fun board(g: Game, id: Int, x: Int, prog: Int, z: Int = 5) {
        g.setBlock(x, 100, z, id, 4 or (prog shl 4))
        g.setBlock(x + 1, 100, z, Blocks.REDSTONE_LAMP)
    }
    private fun lit(g: Game, x: Int, z: Int = 5) = g.world.getBlock(x + 1, 100, z) == Blocks.REDSTONE_LAMP_ON

    @Test fun anArduinoFollowsItsInputOnTheOutSide() {
        val g = game()
        board(g, Blocks.ARDUINO, 5, 0)
        run(g, 1f); assertFalse(lit(g, 5), "no input, no light")
        g.setBlock(4, 100, 5, Blocks.REDSTONE_BLOCK)      // behind the board
        run(g, 1f); assertTrue(lit(g, 5), "the lamp in front lights")
        g.setBlock(4, 100, 5, Blocks.AIR)
        run(g, 1f); assertFalse(lit(g, 5), "and goes out again")
        // Power coming from the OUT side is not an input (no feedback).
        g.setBlock(6, 100, 5, Blocks.REDSTONE_BLOCK)      // where the lamp was: right at the OUT face
        run(g, 1f); assertFalse(Boards.isOn(g.world.getMeta(5, 100, 5)), "the OUT face is not an input")
    }

    @Test fun programsBlinkInvertAndToggle() {
        val g = game()
        board(g, Blocks.ARDUINO, 5, 3)                      // blink
        var on = 0; var off = 0
        repeat(60) { run(g, 0.1f); if (lit(g, 5)) on++ else off++ }
        assertTrue(on > 15 && off > 15, "it blinks (on $on, off $off)")
        board(g, Blocks.ARDUINO, 15, 1)                     // NOT: on without input
        run(g, 1f); assertTrue(lit(g, 15))
        board(g, Blocks.ARDUINO, 25, 8)                     // always on
        run(g, 1f); assertTrue(lit(g, 25))
    }

    @Test fun anEspTalksToAnotherOverWifi() {
        val g = game()
        board(g, Blocks.ESP32, 5, 0, z = 5)                 // send on A
        board(g, Blocks.ESP32, 40, 3, z = -30)              // receive on A, far away
        board(g, Blocks.ESP32, 45, 4, z = -30)              // receive on B
        run(g, 1f); assertFalse(lit(g, 40, -30))
        g.setBlock(4, 100, 5, Blocks.REDSTONE_BLOCK)
        run(g, 1f); assertTrue(lit(g, 40, -30), "channel A arrives"); assertFalse(lit(g, 45, -30), "channel B stays quiet")
        g.setBlock(4, 100, 5, Blocks.AIR)
        run(g, 1f); assertFalse(lit(g, 40, -30))
    }

    @Test fun boardsCanBeCraftedAndReprogrammed() {
        assertTrue(Recipes.crafting.any { it.result == Blocks.ARDUINO }); assertTrue(Recipes.crafting.any { it.result == Blocks.ESP32 })
        var m = 4
        val n = Boards.programs(Blocks.ARDUINO).size
        repeat(n) { m = Boards.next(Blocks.ARDUINO, m) }
        assertEquals(4, m, "tapping through every program comes back to the first, keeping the direction")
        assertTrue(Boards.programs(Blocks.ESP32).any { it.name.startsWith("SEND") } && Boards.programs(Blocks.ESP32).any { it.name.startsWith("RECV") })
    }
}

class SketchTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("sk").toFile()
        val level = LevelData.create(7L, "t", GameMode.CREATIVE)
        val world = World(level.seed, dir, Dimension.OVERWORLD, flat = true)
        val g = Game(world, level, GameInput(), dir)
        for (cx in -3..3) for (cz in -3..3) world.ensureLoaded(cx, cz)
        return g
    }
    private fun run(g: Game, seconds: Float) { var t = 0f; while (t < seconds) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f } }
    private fun lit(g: Game, x: Int, z: Int = 5) = g.world.getBlock(x + 1, 100, z) == Blocks.REDSTONE_LAMP_ON

    @Test fun everyExampleCompilesAndMistakesAreExplained() {
        for (e in Sketches.examples) { Sketches.compile(e.code, true); Sketches.compile(Sketches.flatten(e.code), true) }
        for (e in Sketches.examples) if (!e.needsWifi) Sketches.compile(e.code, false)
        fun err(code: String, wifi: Boolean = false) = assertFailsWith<SketchError> { Sketches.compile(code, wifi) }
        assertTrue(err("digitalWrte(13, HIGH);").message!!.contains("digitalWrite"), "a typo gets a suggestion")
        assertEquals(2, err("digitalWrite(13, HIGH);\ndelay(abc);").line)
        assertTrue(err("wifiSend(A, HIGH);").message!!.contains("ESP32"))
        assertTrue(err("void loop() { digitalWrite(13, HIGH);").message!!.contains("}"))
        Sketches.compile("wifiSend(A, HIGH);", true)
    }

    @Test fun aSketchRunsOnTheBoard() {
        val g = game()
        g.setBlock(5, 100, 5, Blocks.ARDUINO, 4); g.setBlock(6, 100, 5, Blocks.REDSTONE_LAMP)
        assertNull(g.uploadSketch(5, 100, 5, "void loop() { digitalWrite(13, HIGH); delay(1000); digitalWrite(13, LOW); delay(1000); }"))
        var on = 0; var off = 0
        repeat(60) { run(g, 0.1f); if (lit(g, 5)) on++ else off++ }
        assertTrue(on > 15 && off > 15, "the sketch blinks (on $on, off $off)")
        // A mistake is refused and the old sketch keeps running.
        assertNotNull(g.uploadSketch(5, 100, 5, "digitalWrite(13, HI);"))
        // The input: button turns on the light.
        assertNull(g.uploadSketch(5, 100, 5, "if (digitalRead(2) == HIGH) { digitalWrite(13, HIGH); } else { digitalWrite(13, LOW); }"))
        run(g, 1f); assertFalse(lit(g, 5))
        g.setBlock(4, 100, 5, Blocks.REDSTONE_BLOCK)
        run(g, 1f); assertTrue(lit(g, 5))
        // Empty code goes back to the ready-made program.
        assertNull(g.uploadSketch(5, 100, 5, ""))
        assertEquals("", g.signText(5, 100, 5))
    }

    @Test fun anEspSketchSendsOverWifi() {
        val g = game()
        g.setBlock(5, 100, 5, Blocks.ESP32, 4); g.setBlock(4, 100, 5, Blocks.REDSTONE_BLOCK)
        g.setBlock(40, 100, -30, Blocks.ESP32, 4); g.setBlock(41, 100, -30, Blocks.REDSTONE_LAMP)
        assertNull(g.uploadSketch(5, 100, 5, "if (digitalRead(2) == HIGH) { wifiSend(B, HIGH); } else { wifiSend(B, LOW); }"))
        assertNull(g.uploadSketch(40, 100, -30, "if (wifiRead(B) == HIGH) { digitalWrite(13, HIGH); } else { digitalWrite(13, LOW); }"))
        run(g, 1.5f); assertTrue(lit(g, 40, -30))
        g.setBlock(4, 100, 5, Blocks.AIR)
        run(g, 1.5f); assertFalse(lit(g, 40, -30))
    }

    @Test fun theHomeScreenPreviewShowsWhatASketchDoes() {
        val blink = Sketches.preview(Sketches.examples[0].code, false)
        assertTrue(blink.contains("0.0 s: OUT ON") && blink.contains("1.0 s: OUT OFF"), blink)
        val button = Sketches.preview(Sketches.examples[1].code, false)
        assertTrue(button.contains("Input OFF:\n  OUT stays OFF") && button.contains("Input ON:\n  0.0 s: OUT ON"), button)
        assertEquals("digitalWrite(13, HIGH); delay(5);", Sketches.flatten("// hello\ndigitalWrite(13, HIGH);   /* x\n y */ delay(5);"))
    }
}
