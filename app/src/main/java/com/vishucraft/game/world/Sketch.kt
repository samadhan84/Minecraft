package com.vishucraft.game.world

/*
 * The Arduino IDE in the game: a small Arduino-style language that a board runs. Tap a board to open the editor, write
 * a sketch and press Upload. The language has what a first sketch needs:
 *   void setup() { ... }   void loop() { ... }      (or just statements, which run over and over)
 *   pinMode(pin, OUTPUT);  digitalWrite(pin, HIGH or LOW);  delay(milliseconds);
 *   if (condition) { ... } else { ... }     while (condition) { ... }
 *   conditions: digitalRead(pin) == HIGH, !isNight(), wifiRead(A) == LOW, true ...
 *   ESP32 only: wifiSend(A, HIGH);  wifiRead(A)      (channels A, B and C)
 * The "pin" is not checked: the board has one output (the OUT side) and one input (every other side). When the code has a
 * mistake, Upload says which line and what to change, like a helper sitting next to you.
 */
class SketchError(val line: Int, message: String) : Exception(message)

class Cond(val kind: Int, val arg: Int, val expectHigh: Boolean) {
    companion object { const val INPUT = 0; const val WIFI = 1; const val NIGHT = 2; const val CONST_HIGH = 3 }
    fun eval(input: Boolean, night: Boolean, wifi: BooleanArray): Boolean {
        val v = when (kind) { INPUT -> input; WIFI -> wifi.getOrElse(arg) { false }; NIGHT -> night; else -> true }
        return if (expectHigh) v else !v
    }
}

class Op(val code: Int, val a: Int = 0, val b: Int = 0, val cond: Cond? = null) {
    companion object { const val WRITE = 0; const val DELAY = 1; const val SEND = 2; const val JF = 3; const val JMP = 4 }
}

class Sketch(val setup: List<Op>, val loop: List<Op>)

/** A sketch that is running on one board. [step] is called every redstone tick (a tenth of a second). */
class SketchRun(private val sketch: Sketch) {
    var out = false
    val sent = BooleanArray(3)
    private var inSetup = true
    private var pc = 0
    private var wait = 0

    fun step(input: Boolean, night: Boolean, wifi: BooleanArray) {
        if (wait > 0) { wait--; return }
        var steps = 0
        while (steps++ < 400) {
            val ops = if (inSetup) sketch.setup else sketch.loop
            if (pc >= ops.size) {
                if (inSetup) { inSetup = false; pc = 0; continue }
                pc = 0; return                       // one pass of loop() per tick at most, so an empty loop never hangs the game
            }
            val op = ops[pc]
            when (op.code) {
                Op.WRITE -> { out = op.a == 1; pc++ }
                Op.SEND -> { sent[op.a] = op.b == 1; pc++ }
                Op.DELAY -> { pc++; if (op.a > 1) { wait = op.a - 1; return } }
                Op.JF -> pc = if (op.cond!!.eval(input, night, wifi)) pc + 1 else op.a
                Op.JMP -> pc = op.a
            }
        }
    }
}

object Sketches {
    const val MAX_LENGTH = 1500

    class Example(val title: String, val code: String, val needsWifi: Boolean = false)

    val examples = listOf(
        Example("Blink", "void setup() {\n  pinMode(LED_BUILTIN, OUTPUT);\n}\n\nvoid loop() {\n  digitalWrite(LED_BUILTIN, HIGH);\n  delay(1000);\n  digitalWrite(LED_BUILTIN, LOW);\n  delay(1000);\n}\n"),
        Example("Button turns on the light", "void loop() {\n  if (digitalRead(2) == HIGH) {\n    digitalWrite(13, HIGH);\n  } else {\n    digitalWrite(13, LOW);\n  }\n}\n"),
        Example("Night light", "void loop() {\n  if (isNight()) {\n    digitalWrite(13, HIGH);\n  } else {\n    digitalWrite(13, LOW);\n  }\n}\n"),
        Example("Alarm: fast blink when the input is on", "void loop() {\n  if (digitalRead(2) == HIGH) {\n    digitalWrite(13, HIGH);\n    delay(200);\n    digitalWrite(13, LOW);\n    delay(200);\n  } else {\n    digitalWrite(13, LOW);\n  }\n}\n"),
        Example("SOS signal", "void loop() {\n  digitalWrite(13, HIGH); delay(200); digitalWrite(13, LOW); delay(200);\n  digitalWrite(13, HIGH); delay(200); digitalWrite(13, LOW); delay(200);\n  digitalWrite(13, HIGH); delay(200); digitalWrite(13, LOW); delay(600);\n  digitalWrite(13, HIGH); delay(600); digitalWrite(13, LOW); delay(200);\n  digitalWrite(13, HIGH); delay(600); digitalWrite(13, LOW); delay(1500);\n}\n"),
        Example("Wi-Fi sender (ESP32)", "void loop() {\n  if (digitalRead(2) == HIGH) {\n    wifiSend(A, HIGH);\n  } else {\n    wifiSend(A, LOW);\n  }\n}\n", needsWifi = true),
        Example("Wi-Fi receiver (ESP32)", "void loop() {\n  if (wifiRead(A) == HIGH) {\n    digitalWrite(13, HIGH);\n  } else {\n    digitalWrite(13, LOW);\n  }\n}\n", needsWifi = true),
    )

    /** The code on one line without comments, for screens that only have a one-line text box. */
    fun flatten(code: String): String {
        val sb = StringBuilder(); var space = true
        for (c in strip(code)) {
            if (c.isWhitespace()) { if (!space) sb.append(' '); space = true } else { sb.append(c); space = false }
        }
        return sb.toString().trim()
    }

    /** [code] without its // and /* */ comments (lines are kept so line numbers stay right). */
    private fun strip(code: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '/' && i + 1 < code.length && code[i + 1] == '/') { while (i < code.length && code[i] != '\n') i++ }
            else if (c == '/' && i + 1 < code.length && code[i + 1] == '*') {
                i += 2
                while (i + 1 < code.length && !(code[i] == '*' && code[i + 1] == '/')) { if (code[i] == '\n') sb.append('\n'); i++ }
                i += 2; sb.append(' ')
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** What the sketch would do in 20 seconds, with the input off and with the input on (shown in the IDE after Check). */
    fun preview(code: String, wifi: Boolean): String {
        val sketch = compile(code, wifi)
        fun scenario(input: Boolean): String {
            val run = SketchRun(sketch)
            var last = false; var changes = 0
            val sb = StringBuilder()
            for (tick in 0 until 200) {
                run.step(input, false, BooleanArray(3))
                if (run.out != last || (tick == 0 && run.out)) {
                    last = run.out
                    if (changes++ < 10) sb.append("  ").append(tick / 10).append('.').append(tick % 10).append(" s: OUT ").append(if (last) "ON" else "OFF").append('\n')
                }
            }
            if (changes == 0) sb.append("  OUT stays OFF\n") else if (changes > 10) sb.append("  ...\n")
            return sb.toString()
        }
        return "Preview, the first 20 seconds\nInput OFF:\n" + scenario(false) + "Input ON:\n" + scenario(true)
    }

    private class Tok(val text: String, val line: Int)

    private fun tokenize(code: String): List<Tok> {
        val src = code
        val out = ArrayList<Tok>()
        var i = 0; var line = 1
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> while (i < src.length && src[i] != '\n') i++
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) { if (src[i] == '\n') line++; i++ }
                    i += 2
                }
                c.isLetter() || c == '_' || c == '#' -> { val s = i; i++; while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++; out.add(Tok(src.substring(s, i), line)) }
                c.isDigit() -> { val s = i; while (i < src.length && src[i].isDigit()) i++; out.add(Tok(src.substring(s, i), line)) }
                (c == '=' || c == '!') && i + 1 < src.length && src[i + 1] == '=' -> { out.add(Tok("$c=", line)); i += 2 }
                else -> { out.add(Tok(c.toString(), line)); i++ }
            }
        }
        return out
    }

    private val known = listOf("digitalWrite", "delay", "pinMode", "digitalRead", "wifiSend", "wifiRead", "isNight", "if", "else", "while", "void", "setup", "loop", "HIGH", "LOW")

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1].equals(b[j - 1], true)) 0 else 1)
        return d[a.length][b.length]
    }

    private fun suggest(word: String): String {
        val best = known.minByOrNull { distance(word, it) }
        return if (best != null && distance(word, best) <= 3) " Did you mean $best?" else ""
    }

    /** Turns [code] into something a board can run, or throws a [SketchError] saying what to fix. */
    fun compile(code: String, wifiAllowed: Boolean): Sketch {
        val toks = tokenize(code)
        if (toks.none { it.text.isNotEmpty() }) throw SketchError(1, "The sketch is empty")
        return Parser(toks, wifiAllowed).program()
    }

    private class Parser(val t: List<Tok>, val wifi: Boolean) {
        var p = 0
        val ops = ArrayList<Op>()
        val line get() = t.getOrNull(p)?.line ?: (t.lastOrNull()?.line ?: 1)
        fun peek() = t.getOrNull(p)?.text
        fun fail(msg: String): Nothing = throw SketchError(line, msg)
        fun next(): String = t.getOrNull(p++)?.text ?: fail("The sketch ends too soon: something is missing (a } or a ;)")
        fun expect(s: String) { val n = t.getOrNull(p)?.text; if (n != s) fail(if (n == null) "The sketch ends too soon: add $s here" else "Expected $s here, found $n"); p++ }

        fun program(): Sketch {
            val setup = ArrayList<Op>(); var loop: List<Op>? = null
            if (peek() == "void" || peek() == "#define" || peek() == "int" || peek() == "const") {
                while (p < t.size) {
                    when (peek()) {
                        "void" -> {
                            p++
                            val name = next()
                            if (name != "setup" && name != "loop") fail("Only void setup() and void loop() are known here, not $name.${suggest(name)}")
                            expect("("); expect(")")
                            ops.clear(); block()
                            if (name == "setup") setup.addAll(ops) else loop = ArrayList(ops)
                        }
                        "#define" -> { val ln = line; p++; while (p < t.size && t[p].line == ln) p++ }
                        "int", "const", "byte", "long" -> { while (p < t.size && peek() != ";") p++; if (p < t.size) p++ }
                        else -> fail("Put your commands inside void setup() { } or void loop() { }")
                    }
                }
            } else {
                // Just commands: they run over and over.
                while (p < t.size) statement()
                loop = ArrayList(ops)
            }
            return Sketch(setup, loop ?: emptyList())
        }

        fun block() {
            if (peek() != "{") { statement(); return }
            p++
            while (peek() != "}") { if (p >= t.size) fail("The sketch ends too soon: a } is missing"); statement() }
            p++
        }

        fun pin() { val n = next(); if (!(n[0].isLetterOrDigit() || n[0] == '_')) fail("Expected a pin here (like 13 or LED_BUILTIN), found $n") }

        fun level(): Int = when (val v = next()) {
            "HIGH", "1", "true" -> 1
            "LOW", "0", "false" -> 0
            else -> fail("Expected HIGH or LOW here, found $v.${suggest(v)}")
        }

        fun channel(): Int = when (val c = next().uppercase()) { "A", "1", "CHANNEL_A" -> 0; "B", "2", "CHANNEL_B" -> 1; "C", "3", "CHANNEL_C" -> 2; else -> fail("Wi-Fi channels are A, B and C, not $c") }

        fun needWifi(name: String) { if (!wifi) fail("$name needs Wi-Fi: only an ESP32 board has it. Tap an ESP32 board, or take the Wi-Fi lines out.") }

        fun cond(): Cond {
            var negate = false
            while (peek() == "!") { p++; negate = !negate }
            val name = next()
            var c: Cond = when (name) {
                "digitalRead" -> { expect("("); pin(); expect(")"); Cond(Cond.INPUT, 0, true) }
                "wifiRead" -> { needWifi("wifiRead"); expect("("); val ch = channel(); expect(")"); Cond(Cond.WIFI, ch, true) }
                "isNight" -> { expect("("); expect(")"); Cond(Cond.NIGHT, 0, true) }
                "HIGH", "true", "1" -> Cond(Cond.CONST_HIGH, 0, true)
                "LOW", "false", "0" -> Cond(Cond.CONST_HIGH, 0, false)
                else -> fail("I do not know $name in a condition. Use digitalRead(pin), wifiRead(A), isNight(), HIGH or LOW.${suggest(name)}")
            }
            if (peek() == "==" || peek() == "!=") {
                val same = next() == "=="
                val lv = level() == 1
                c = Cond(c.kind, c.arg, if (same) c.expectHigh == lv else c.expectHigh != lv)
            }
            return if (negate) Cond(c.kind, c.arg, !c.expectHigh) else c
        }

        fun statement() {
            val ln = line
            when (val name = next()) {
                "{" -> { p--; block() }
                ";" -> {}
                "if" -> {
                    expect("("); val c = cond(); expect(")")
                    val jf = ops.size; ops.add(Op(Op.JF, 0, 0, c))
                    block()
                    if (peek() == "else") {
                        p++
                        val jmp = ops.size; ops.add(Op(Op.JMP))
                        ops[jf] = Op(Op.JF, ops.size, 0, c)
                        block()
                        ops[jmp] = Op(Op.JMP, ops.size)
                    } else ops[jf] = Op(Op.JF, ops.size, 0, c)
                }
                "while" -> {
                    val start = ops.size
                    expect("("); val c = cond(); expect(")")
                    val jf = ops.size; ops.add(Op(Op.JF, 0, 0, c))
                    block()
                    ops.add(Op(Op.JMP, start))
                    ops[jf] = Op(Op.JF, ops.size, 0, c)
                }
                "else" -> throw SketchError(ln, "else without an if before it")
                "for" -> throw SketchError(ln, "for loops are not in this IDE yet: use while (...) { } instead")
                "pinMode" -> { expect("("); pin(); expect(","); next(); expect(")"); expect(";") }
                "digitalWrite" -> { expect("("); pin(); expect(","); val v = level(); expect(")"); expect(";"); ops.add(Op(Op.WRITE, v)) }
                "delay" -> {
                    expect("("); val n = next()
                    val ms = n.toIntOrNull() ?: throw SketchError(ln, "delay needs a number of milliseconds, like delay(500), not $n")
                    expect(")"); expect(";")
                    if (ms > 60000) throw SketchError(ln, "delay can be at most 60000 milliseconds (one minute)")
                    ops.add(Op(Op.DELAY, (ms + 50) / 100))
                }
                "wifiSend" -> { needWifi("wifiSend"); expect("("); val ch = channel(); expect(","); val v = level(); expect(")"); expect(";"); ops.add(Op(Op.SEND, ch, v)) }
                else -> throw SketchError(ln, "I do not know the command $name.${suggest(name)}")
            }
        }
    }
}
