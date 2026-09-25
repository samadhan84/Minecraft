package com.vishucraft.game.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Every sound in the game is synthesised here from noise and simple waveforms (no audio files).
 * Output is 16-bit mono PCM at [RATE] Hz.
 */
object Synth {
    const val RATE = 22050
    private val rnd = Random(7)

    private fun buf(seconds: Float) = FloatArray((seconds * RATE).toInt())

    /** Decaying filtered noise burst: [bright] 0..1 controls the low-pass cutoff. */
    private fun noiseBurst(out: FloatArray, start: Int, len: Int, amp: Float, decay: Float, bright: Float) {
        var lp = 0f
        for (i in 0 until len) {
            val j = start + i
            if (j >= out.size) break
            val n = rnd.nextFloat() * 2 - 1
            lp += (n - lp) * bright
            out[j] += lp * amp * exp(-i / (RATE * decay))
        }
    }

    private fun tone(out: FloatArray, start: Int, len: Int, freq: (Float) -> Float, amp: Float, decay: Float,
                     attack: Float = 0.005f, harmonics: FloatArray = floatArrayOf(1f)) {
        var phase = 0.0
        for (i in 0 until len) {
            val j = start + i
            if (j >= out.size) break
            val t = i.toFloat() / RATE
            phase += 2 * PI * freq(t) / RATE
            var v = 0.0
            for ((h, a) in harmonics.withIndex()) v += sin(phase * (h + 1)) * a
            val env = minOf(1f, t / attack) * exp(-t / decay)
            out[j] += (v * amp * env).toFloat()
        }
    }

    fun toPcm(f: FloatArray): ShortArray {
        var peak = 0.0001f
        for (v in f) peak = maxOf(peak, kotlin.math.abs(v))
        val g = if (peak > 0.95f) 0.95f / peak else 1f
        return ShortArray(f.size) { (f[it] * g * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** name -> PCM for every one-shot effect. */
    fun all(): Map<String, ShortArray> {
        val m = LinkedHashMap<String, ShortArray>()
        fun make(name: String, seconds: Float, fill: (FloatArray) -> Unit) { val b = buf(seconds); fill(b); m[name] = toPcm(b) }

        // Material hits: used for digging, placing and footsteps.
        make("hit_stone", 0.12f) { noiseBurst(it, 0, it.size, 0.9f, 0.025f, 0.55f); tone(it, 0, it.size, { 180f }, 0.25f, 0.03f) }
        make("hit_wood", 0.14f) { noiseBurst(it, 0, it.size, 0.5f, 0.02f, 0.3f); tone(it, 0, it.size, { 260f - it * 400 }, 0.5f, 0.04f, harmonics = floatArrayOf(1f, 0.4f)) }
        make("hit_dirt", 0.14f) { noiseBurst(it, 0, it.size, 0.9f, 0.035f, 0.12f) }
        make("hit_sand", 0.16f) { noiseBurst(it, 0, it.size, 0.7f, 0.05f, 0.35f) }
        make("hit_grass", 0.14f) { noiseBurst(it, 0, it.size, 0.7f, 0.04f, 0.5f); noiseBurst(it, 900, it.size, 0.3f, 0.02f, 0.6f) }
        make("hit_glass", 0.3f) { for (k in 0..4) tone(it, k * 400, 3000, { 1800f + k * 470f }, 0.25f, 0.06f); noiseBurst(it, 0, 2000, 0.6f, 0.02f, 0.8f) }
        make("hit_wool", 0.12f) { noiseBurst(it, 0, it.size, 0.6f, 0.03f, 0.06f) }
        make("hit_snow", 0.14f) { noiseBurst(it, 0, it.size, 0.6f, 0.04f, 0.2f); noiseBurst(it, 1200, it.size, 0.3f, 0.02f, 0.25f) }

        make("pop", 0.12f) { tone(it, 0, it.size, { 500f + it * 6000f }, 0.6f, 0.05f) }
        make("click", 0.06f) { noiseBurst(it, 0, 300, 1f, 0.004f, 0.9f); tone(it, 0, it.size, { 1400f }, 0.4f, 0.01f) }
        make("craft", 0.35f) { tone(it, 0, it.size, { 660f }, 0.4f, 0.12f, harmonics = floatArrayOf(1f, 0.3f)); tone(it, 2200, it.size, { 990f }, 0.35f, 0.15f) }
        make("eat", 0.5f) { for (k in 0..3) noiseBurst(it, k * 2600, 2000, 0.7f, 0.03f, 0.25f) }
        make("hurt", 0.25f) { tone(it, 0, it.size, { 220f - it * 300f }, 0.8f, 0.08f, harmonics = floatArrayOf(1f, 0.5f, 0.25f)); noiseBurst(it, 0, 1500, 0.4f, 0.03f, 0.3f) }
        make("break_tool", 0.35f) { noiseBurst(it, 0, it.size, 0.8f, 0.05f, 0.7f); tone(it, 0, it.size, { 900f - it * 1500f }, 0.4f, 0.08f) }
        make("splash", 0.6f) { noiseBurst(it, 0, it.size, 0.9f, 0.18f, 0.35f) }
        make("fuse", 1.5f) { var lp = 0f; for (i in it.indices) { val n = rnd.nextFloat() * 2 - 1; lp += (n - lp) * 0.7f; it[i] = (n - lp) * 0.5f * minOf(1f, i / 2000f) } }
        make("explode", 2.2f) {
            noiseBurst(it, 0, it.size, 1f, 0.45f, 0.08f)
            noiseBurst(it, 0, 6000, 0.8f, 0.08f, 0.4f)
            tone(it, 0, it.size, { 55f - it * 10 }, 0.7f, 0.5f)
        }
        make("thunder", 3.5f) {
            noiseBurst(it, 0, it.size, 1f, 0.9f, 0.03f)
            for (k in 0..5) noiseBurst(it, k * 5000 + rnd.nextInt(3000), 9000, 0.6f, 0.2f, 0.06f)
        }
        make("door", 0.3f) { tone(it, 0, it.size, { 140f + it * 120f }, 0.6f, 0.1f, harmonics = floatArrayOf(1f, 0.6f, 0.3f)); noiseBurst(it, 0, 1200, 0.4f, 0.02f, 0.3f) }
        make("bow", 0.3f) { tone(it, 0, it.size, { 320f - it * 500f }, 0.5f, 0.06f, harmonics = floatArrayOf(1f, 0.5f)); noiseBurst(it, 0, 3000, 0.5f, 0.05f, 0.6f) }
        make("arrow_hit", 0.15f) { noiseBurst(it, 0, it.size, 0.7f, 0.02f, 0.4f); tone(it, 0, it.size, { 400f }, 0.3f, 0.03f) }
        make("level_up", 0.8f) { for ((k, f) in floatArrayOf(523f, 659f, 784f, 1047f).withIndex()) tone(it, k * 2400, 9000, { f }, 0.3f, 0.2f, harmonics = floatArrayOf(1f, 0.3f)) }

        // Mob voices (original, cartoonish).
        make("cow", 1.1f) { tone(it, 0, it.size, { t -> 110f + 25f * sin(t * 5f) + (if (t < 0.2f) t * 150f else 30f) }, 0.6f, 0.6f, 0.08f, floatArrayOf(1f, 0.7f, 0.5f, 0.35f, 0.2f)) }
        make("pig", 0.35f) { for (k in 0..1) tone(it, k * 3500, 3500, { t -> 330f - t * 400f }, 0.5f, 0.07f, 0.01f, floatArrayOf(1f, 0.8f, 0.6f, 0.4f)) }
        make("sheep", 0.8f) { tone(it, 0, it.size, { t -> 380f * (1f + 0.06f * sin(t * 50f)) }, 0.5f, 0.35f, 0.03f, floatArrayOf(1f, 0.6f, 0.4f, 0.3f)) }
        make("zombie", 1.3f) {
            tone(it, 0, it.size, { t -> 90f + 15f * sin(t * 3f) }, 0.6f, 0.7f, 0.2f, floatArrayOf(1f, 0.9f, 0.7f, 0.5f, 0.4f))
            noiseBurst(it, 0, it.size, 0.25f, 0.6f, 0.08f)
        }
        make("boomling", 0.6f) { tone(it, 0, it.size, { t -> 200f + 60f * sin(t * 40f) }, 0.3f, 0.3f, 0.05f, floatArrayOf(1f, 0.5f)); noiseBurst(it, 0, it.size, 0.3f, 0.2f, 0.9f) }
        make("archer", 0.5f) { for (k in 0..3) { noiseBurst(it, k * 2000, 800, 0.7f, 0.01f, 0.8f); tone(it, k * 2000, 800, { 900f }, 0.3f, 0.01f) } }
        make("spider", 0.5f) { noiseBurst(it, 0, it.size, 0.5f, 0.15f, 0.7f); tone(it, 0, it.size, { t -> 700f + 200f * sin(t * 90f) }, 0.15f, 0.2f) }
        make("glider", 1.0f) { tone(it, 0, it.size, { t -> 600f + 300f * sin(t * 7f) }, 0.35f, 0.5f, 0.1f, floatArrayOf(1f, 0.4f)) }
        make("ember", 0.7f) { noiseBurst(it, 0, it.size, 0.6f, 0.3f, 0.25f); tone(it, 0, it.size, { t -> 150f + 40f * sin(t * 25f) }, 0.4f, 0.35f) }

        // Loops.
        make("rain", 2.0f) {
            var lp = 0f
            for (i in it.indices) { val n = rnd.nextFloat() * 2 - 1; lp += (n - lp) * 0.25f; it[i] = lp * 0.5f }
            repeat(120) { k -> val at = rnd.nextInt(it.size - 300); for (i in 0 until 200) it[at + i] += (rnd.nextFloat() - 0.5f) * 0.3f * exp(-i / 40f) }
            // Crossfade the ends so the loop is seamless.
            val fade = 1500
            for (i in 0 until fade) { val a = i / fade.toFloat(); it[i] = it[i] * a + it[it.size - fade + i] * (1 - a) }
        }
        return m
    }

    /** Sound family for a block (digging, placing, footsteps). */
    fun material(id: Int): String {
        val B = com.vishucraft.game.world.Blocks
        val d = B[id]
        return when {
            id == B.SAND || id == B.RED_SAND || id == B.GRAVEL || id == B.SOUL_SAND -> "hit_sand"
            id == B.GLASS || id == B.ICE || id == B.PACKED_ICE || id == B.BLUE_ICE || id == B.SEA_LANTERN || id == B.GLOWSTONE ||
                id in B.STAINED_GLASS_FIRST until B.STAINED_GLASS_FIRST + 16 -> "hit_glass"
            id == B.SNOW || id == B.SNOW_GRASS -> "hit_snow"
            d.name.endsWith("Wool") -> "hit_wool"
            d.render == com.vishucraft.game.world.RenderType.CROSS || d.tool == com.vishucraft.game.world.ToolType.HOE || id == B.GRASS -> "hit_grass"
            d.tool == com.vishucraft.game.world.ToolType.AXE -> "hit_wood"
            d.tool == com.vishucraft.game.world.ToolType.SHOVEL -> "hit_dirt"
            else -> "hit_stone"
        }
    }
}
