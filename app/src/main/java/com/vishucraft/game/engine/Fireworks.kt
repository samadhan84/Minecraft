package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.floorInt
import kotlin.math.cos
import kotlin.math.sin

/*
 * Diwali fireworks, until 13 November: the crackers Indian families light on Diwali. Tap the ground with one to put
 * it down and light it; after a short fuse it goes off. Bombs (Sutli, Lakshmi, Atom and the little Bijli) bang and
 * flash without breaking anything, the Ladi crackles away a long string of bangs, the Chakri spins sparks round on
 * the ground, the Anar throws up a fountain of sparks, rockets and sky shots burst in colours high in the air,
 * colour pencils fizz, the snake tablet grows a curling black snake of ash, and the Phuljhadi sparkles in your
 * hand. Sparks only: nothing is ever set on fire or blown up.
 */

/** The Diwali fireworks, in the order shown, with what each one does. */
enum class Cracker(val itemName: String, val icon: String, val fuse: Float, val runs: Float) {
    SUTLI("Sutli Bomb", "sutli_bomb", 2.5f, 0f),
    LAKSHMI("Lakshmi Bomb", "lakshmi_bomb", 2f, 0f),
    ATOM("Atom Bomb", "atom_bomb", 3f, 0f),
    BIJLI("Bijli Cracker", "bijli_cracker", 1.2f, 0f),
    LADI("Ladi (1000 Wala)", "ladi", 2f, 6f),
    CHAKRI("Chakri", "chakri", 1.5f, 7f),
    ANAR("Anar", "anar", 1.5f, 8f),
    ROCKET("Diwali Rocket", "diwali_rocket", 1.5f, 2.2f),
    SKY_SHOT("Sky Shot", "sky_shot", 2f, 8f),
    PENCIL("Colour Pencil", "colour_pencil", 1f, 6f),
    SNAKE("Snake Tablet", "snake_tablet", 1f, 6f),
    PHULJHADI("Phuljhadi", "phuljhadi", 0f, 12f);

    companion object {
        fun of(itemName: String?) = values().firstOrNull { it.itemName == itemName }
    }
}

/** Until when the Diwali fireworks can be lit (inclusive), as yyyy-mm-dd. */
const val DIWALI_UNTIL = "2026-11-13"

/** Whether it is still Diwali time on this device. */
fun diwaliTime(today: String = PlayLimit.today()) = today <= DIWALI_UNTIL

/** A firework that has been lit: it waits for its fuse, then goes off for [Cracker.runs] seconds. */
class LitCracker(val kind: Cracker, var x: Float, var y: Float, var z: Float) {
    var age = 0f
    var shots = 0
    /** For the Phuljhadi: it stays in the player's hand. */
    var held = false
}

/** One spark: glowing colour [tile], moving and fading until [life] runs out. */
class Spark(var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, var vz: Float, var life: Float, val tile: Int, val gravity: Float = 9f)

class Fireworks {
    val lit = ArrayList<LitCracker>()
    val sparks = ArrayList<Spark>()
    private val rnd = java.util.Random()
    /** A white flash on the screen after a big bang (fades out). */
    var flash = 0f

    private val colours by lazy { listOf("spark_red", "spark_gold", "spark_green", "spark_blue", "spark_pink", "spark_white").map { com.vishucraft.game.world.Tiles.id(it) } }
    private fun c(name: String) = com.vishucraft.game.world.Tiles.id(name)

    fun update(dt: Float, game: Game) {
        flash = maxOf(0f, flash - dt * 2.5f)
        val it = lit.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (f.held) { val p = game.player; f.x = p.x + sin(p.yaw) * 0.6f + cos(p.yaw) * 0.35f; f.y = p.y + 1.1f; f.z = p.z - cos(p.yaw) * 0.6f + sin(p.yaw) * 0.35f }
            f.age += dt
            if (f.age < f.kind.fuse) {
                // The fuse fizzing.
                if (rnd.nextFloat() < dt * 25f) sparks.add(Spark(f.x, f.y + 0.3f, f.z, rnd.nextFloat() - 0.5f, 1.5f, rnd.nextFloat() - 0.5f, 0.3f, c("spark_gold"), 3f))
                if (f.age - dt < 0.01f) game.sound("fuse", f.x, f.y, f.z, 0.6f)
                continue
            }
            val t = f.age - f.kind.fuse
            if (goOff(f, t, dt, game)) it.remove()
        }
        val s = sparks.iterator()
        while (s.hasNext()) {
            val p = s.next()
            p.life -= dt
            if (p.life <= 0f) { s.remove(); continue }
            p.vy -= p.gravity * dt
            p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt
            p.vx *= 1f - dt * 0.8f; p.vz *= 1f - dt * 0.8f
        }
        if (sparks.size > 4000) sparks.subList(0, sparks.size - 4000).clear()
    }

    private fun burst(x: Float, y: Float, z: Float, n: Int, speed: Float, tiles: List<Int>, life: Float = 1.6f, gravity: Float = 4f) {
        repeat(n) {
            // Evenly over a sphere.
            val u = rnd.nextFloat() * 2f - 1f; val a = rnd.nextFloat() * 6.2832f; val r = kotlin.math.sqrt(1f - u * u)
            val v = speed * (0.85f + rnd.nextFloat() * 0.3f)
            sparks.add(Spark(x, y, z, r * cos(a) * v, u * v, r * sin(a) * v, life * (0.7f + rnd.nextFloat() * 0.6f), tiles[rnd.nextInt(tiles.size)], gravity))
        }
    }

    /** A bomb going off: a bang, a flash of sparks and smoke, and a push (it never breaks blocks). */
    private fun bang(f: LitCracker, game: Game, size: Float) {
        game.sound(if (size >= 1f) "explode" else "pop", f.x, f.y, f.z, (0.5f + size * 0.6f).coerceAtMost(2f))
        burst(f.x, f.y + 0.3f, f.z, (60 * size).toInt() + 10, 6f * size + 2f, listOf(c("spark_white"), c("spark_gold"), c("spark_red")), 0.5f, 2f)
        burst(f.x, f.y + 0.5f, f.z, (30 * size).toInt() + 5, 1.5f * size + 0.5f, listOf(c("spark_smoke")), 2.5f, -0.6f)
        val p = game.player
        val d = kotlin.math.hypot(p.x - f.x, p.z - f.z)
        if (d < 5f * size) {
            flash = maxOf(flash, (size * 0.5f).coerceAtMost(0.9f) * (1f - d / (5f * size)))
            if (d < 2f * size) { p.vy = maxOf(p.vy, 3f * size); p.vx += (p.x - f.x) / (d + 0.5f) * 3f; p.vz += (p.z - f.z) / (d + 0.5f) * 3f }
        }
        for (m in game.mobs.list) {
            val md = kotlin.math.hypot(m.x - f.x, m.z - f.z)
            if (md < 2.5f * size) { m.vy = maxOf(m.vy, 4f); if (!m.type.hostile) m.fleeTime = 4f }
        }
    }

    /** Runs a firework that has gone off, [t] seconds in; returns true once it has finished. */
    private fun goOff(f: LitCracker, t: Float, dt: Float, game: Game): Boolean {
        when (f.kind) {
            Cracker.SUTLI -> { bang(f, game, 1.6f); return true }
            Cracker.LAKSHMI -> { bang(f, game, 1.1f); burst(f.x, f.y + 0.3f, f.z, 30, 4f, listOf(c("spark_red")), 0.6f); return true }
            Cracker.ATOM -> { bang(f, game, 2.6f); return true }
            Cracker.BIJLI -> { bang(f, game, 0.45f); return true }
            Cracker.LADI -> {
                // A string of crackers popping one after another, crawling along the ground.
                val due = (t / f.kind.runs * 60).toInt()
                while (f.shots < due) {
                    f.shots++
                    val ox = sin(f.shots * 0.7f) * 0.6f
                    val save = f.x
                    f.x += ox
                    bang(f, game, 0.35f)
                    f.x = save; f.z += 0.06f
                }
            }
            Cracker.CHAKRI -> {
                // Spinning on the ground, throwing gold and green sparks round in a circle.
                val spin = t * 25f
                repeat(6) {
                    val a = spin + it * 1.05f
                    sparks.add(Spark(f.x, f.y + 0.1f, f.z, cos(a) * 4f, 0.8f, sin(a) * 4f, 0.45f, if (it % 2 == 0) c("spark_gold") else c("spark_green"), 6f))
                }
                if (rnd.nextFloat() < dt * 6f) game.sound("fuse", f.x, f.y, f.z, 0.3f)
            }
            Cracker.ANAR -> {
                // A fountain of sparks pouring up out of the pot, taller in the middle of its burn.
                val strength = sin((t / f.kind.runs) * 3.1416f).coerceAtLeast(0.2f)
                repeat(14) {
                    val a = rnd.nextFloat() * 6.2832f; val r = rnd.nextFloat() * 1.3f
                    sparks.add(Spark(f.x, f.y + 0.6f, f.z, cos(a) * r, (7f + rnd.nextFloat() * 4f) * strength, sin(a) * r, 1.3f,
                        if (rnd.nextInt(3) == 0) c("spark_white") else c("spark_gold"), 7f))
                }
                if (rnd.nextFloat() < dt * 4f) game.sound("fuse", f.x, f.y, f.z, 0.3f)
            }
            Cracker.ROCKET -> {
                // Whooshes up with a trail, then bursts in colours.
                if (t < f.kind.runs) {
                    f.y += 18f * dt
                    sparks.add(Spark(f.x, f.y, f.z, 0f, -1f, 0f, 0.6f, c("spark_gold"), 0f))
                    if (f.shots == 0) { f.shots = 1; game.sound("fuse", f.x, f.y, f.z, 0.8f) }
                } else {
                    shell(f.x, f.y, f.z, game)
                    return true
                }
            }
            Cracker.SKY_SHOT -> {
                // Eight shells, one after another, bursting high above.
                val due = (t / f.kind.runs * 8).toInt().coerceAtMost(8)
                while (f.shots < due) {
                    f.shots++
                    game.sound("pop", f.x, f.y, f.z, 0.8f)
                    shell(f.x + rnd.nextFloat() * 6f - 3f, f.y + 22f + rnd.nextFloat() * 8f, f.z + rnd.nextFloat() * 6f - 3f, game)
                    for (k in 0..10) sparks.add(Spark(f.x, f.y + k * 2f, f.z, 0f, 0f, 0f, 0.4f + k * 0.03f, c("spark_gold"), 0f))
                }
            }
            Cracker.PENCIL -> {
                val tile = colours[(f.age * 2).toInt() % colours.size]
                repeat(5) { sparks.add(Spark(f.x, f.y + 1f, f.z, rnd.nextFloat() * 2f - 1f, 1.5f + rnd.nextFloat(), rnd.nextFloat() * 2f - 1f, 0.7f, tile, 5f)) }
            }
            Cracker.SNAKE -> {
                // The black ash snake curls up out of the tablet.
                val h = t / f.kind.runs * 1.6f
                if (rnd.nextFloat() < dt * 30f) sparks.add(Spark(f.x + sin(t * 5f) * 0.15f, f.y + h, f.z + cos(t * 5f) * 0.15f, 0f, 0f, 0f, f.kind.runs - t + 2f, c("spark_ash"), 0f))
                if (rnd.nextFloat() < dt * 8f) sparks.add(Spark(f.x, f.y + h + 0.2f, f.z, 0f, 0.6f, 0f, 1.5f, c("spark_smoke"), -0.2f))
            }
            Cracker.PHULJHADI -> {
                repeat(8) {
                    val a = rnd.nextFloat() * 6.2832f; val u = rnd.nextFloat() * 2f - 1f
                    sparks.add(Spark(f.x, f.y + 0.25f, f.z, cos(a) * 1.8f, u * 1.8f, sin(a) * 1.8f, 0.25f, if (it % 3 == 0) c("spark_white") else c("spark_gold"), 1f))
                }
            }
        }
        return t >= f.kind.runs
    }

    /** A colourful shell bursting in the sky: a sphere of one or two colours, and a crack. */
    private fun shell(x: Float, y: Float, z: Float, game: Game) {
        val a = colours[rnd.nextInt(colours.size)]; val b = colours[rnd.nextInt(colours.size)]
        burst(x, y, z, 160, 9f, listOf(a, a, b), 2.2f, 3f)
        game.sound("explode", x, y, z, 0.7f)
    }

    /** Puts a firework down at (x, y, z) and lights it (a Phuljhadi is held in the hand instead). */
    fun light(kind: Cracker, x: Float, y: Float, z: Float, held: Boolean = false) {
        lit.add(LitCracker(kind, x, y, z).also { it.held = held })
    }
}

/** Using a Diwali firework: put it down on the tapped block and light it. Returns true when handled. */
internal fun Game.fireworkUse(item: com.vishucraft.game.world.ItemDef?, t: RayHit?): Boolean {
    val kind = Cracker.of(item?.name) ?: return false
    if (!diwaliTime()) { uiEvents.add("toast:Diwali fireworks were only until 13 November. Happy Diwali!"); return true }
    if (kind == Cracker.PHULJHADI) {
        if (fireworks.lit.any { it.held }) return true
        fireworks.light(kind, player.x, player.y + 1.1f, player.z, held = true)
        consumeHeld()
        uiEvents.add("toast:Phuljhadi lit! Happy Diwali!")
        return true
    }
    if (t == null || t.ny != 1) { uiEvents.add("toast:Put the ${kind.itemName} down on the ground to light it"); return true }
    fireworks.light(kind, t.x + 0.5f, t.y + 1f, t.z + 0.5f)
    consumeHeld()
    if (kind == Cracker.ATOM || kind == Cracker.SUTLI) uiEvents.add("toast:Lit! Stand back!")
    return true
}
