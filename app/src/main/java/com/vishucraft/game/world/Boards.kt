package com.vishucraft.game.world

/*
 * Arduino and ESP32 boards. A board is a block you place like a repeater: its OUT side (the face with the arrow)
 * is where it gives power (to dust, a lamp, a piston, a door ...); power coming into any other side is its input.
 * Tap it to change the program it runs: the program is kept in the block's meta (bits 4 to 7), whether the output is
 * on is bit 3 and the direction bits 0 to 2. The ESP32 can also talk over Wi-Fi: boards that send on channel A, B or C
 * switch every board that receives on that channel, however far away (in the loaded world).
 */
enum class Prog(val title: String) {
    FOLLOW("Follow the input"), INVERT("Invert the input (NOT)"), TOGGLE("Toggle on each press"),
    BLINK("Blink once a second"), BLINK_WHILE("Blink while the input is on"), PULSE("Pulse for 3 seconds after a press"),
    NIGHT("Night light (on in the dark)"), DELAY("Delay the input by 2 seconds"), ON("Always on"),
    SEND_A("Wi-Fi send on channel A"), SEND_B("Wi-Fi send on channel B"), SEND_C("Wi-Fi send on channel C"),
    RECV_A("Wi-Fi receive channel A"), RECV_B("Wi-Fi receive channel B"), RECV_C("Wi-Fi receive channel C"),
    RECV_A_NOT("Wi-Fi receive channel A, inverted"),
}

object Boards {
    private val arduino = listOf(Prog.FOLLOW, Prog.INVERT, Prog.TOGGLE, Prog.BLINK, Prog.BLINK_WHILE, Prog.PULSE, Prog.NIGHT, Prog.DELAY, Prog.ON)
    private val esp = listOf(Prog.SEND_A, Prog.SEND_B, Prog.SEND_C, Prog.RECV_A, Prog.RECV_B, Prog.RECV_C, Prog.RECV_A_NOT,
        Prog.FOLLOW, Prog.TOGGLE, Prog.BLINK, Prog.NIGHT)

    fun isBoard(id: Int) = id == Blocks.ARDUINO || id == Blocks.ESP32
    fun programs(id: Int) = if (id == Blocks.ESP32) esp else arduino
    fun program(id: Int, meta: Int): Prog = programs(id).let { it[(meta shr 4) % it.size] }
    fun isOn(meta: Int) = meta and 8 != 0
    /** The meta of the board after tapping it: the next program, the same direction. */
    fun next(id: Int, meta: Int): Int = (meta and 15) or ((((meta shr 4) + 1) % programs(id).size) shl 4)
    fun wifiChannel(p: Prog) = when (p) { Prog.SEND_A, Prog.RECV_A, Prog.RECV_A_NOT -> 0; Prog.SEND_B, Prog.RECV_B -> 1; Prog.SEND_C, Prog.RECV_C -> 2; else -> -1 }
}
