package com.vishucraft.game.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Endless gentle background music, composed on the fly: slow piano-like notes picked from a pentatonic
 * scale over a simple chord cycle, with long quiet gaps between phrases. Shared by Android and desktop.
 */
class MusicGen(private val rate: Int) {
    private val rnd = Random()
    private val scale = intArrayOf(0, 2, 4, 7, 9)
    private val chords = listOf(intArrayOf(0, 4, 7), intArrayOf(9, 12, 16), intArrayOf(5, 9, 12), intArrayOf(7, 11, 14))
    private val voices = ArrayList<FloatArray>() // [freq, age, amp]
    private var t = 0f
    private var nextNote = 2f
    private var phraseLeft = 0
    private var chord = 0
    private var base = 48 + rnd.nextInt(5)

    /** Fills [block] with the next samples (range roughly -1..1). */
    fun fill(block: FloatArray) {
        for (i in block.indices) {
            t += 1f / rate
            if (t >= nextNote) startNote()
            var s = 0f
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val age = v[1]
                if (age > 6f) { it.remove(); continue }
                val env = minOf(1f, age / 0.01f) * exp(-age / 1.4f)
                val ph = 2 * PI * v[0] * age
                s += (v[2] * env * (sin(ph) + 0.35 * sin(2 * ph) * exp(-age) + 0.12 * sin(3 * ph) * exp(-age * 2))).toFloat()
                v[1] = age + 1f / rate
            }
            block[i] = s
        }
    }

    private fun startNote() {
        if (phraseLeft <= 0) {
            // Rest between phrases, then start a new one in a (possibly) new key.
            phraseLeft = 6 + rnd.nextInt(10)
            nextNote = t + 6f + rnd.nextFloat() * 14f
            if (rnd.nextInt(3) == 0) base = 45 + rnd.nextInt(8)
            return
        }
        phraseLeft--
        if (phraseLeft % 4 == 0) chord = (chord + 1 + rnd.nextInt(2)) % chords.size
        val note = if (rnd.nextInt(3) == 0) base + chords[chord][rnd.nextInt(3)] - 12
        else base + 12 + scale[rnd.nextInt(scale.size)] + 12 * rnd.nextInt(2)
        voices.add(floatArrayOf(440f * Math.pow(2.0, (note - 69) / 12.0).toFloat(), 0f, 0.18f + rnd.nextFloat() * 0.08f))
        nextNote = t + listOf(0.5f, 0.75f, 1f, 1.5f)[rnd.nextInt(4)]
    }
}
