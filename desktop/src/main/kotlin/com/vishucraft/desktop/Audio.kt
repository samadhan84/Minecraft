package com.vishucraft.desktop

import com.vishucraft.game.audio.MusicGen
import com.vishucraft.game.audio.Synth
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sound effects, rain and background music mixed in software into one stereo output line
 * (the same synthesised sounds and music as the Android version).
 */
class Audio {
    @Volatile var volume = 0.8f
    @Volatile var musicVolume = 0.5f
    @Volatile private var listenerX = 0f
    @Volatile private var listenerY = 0f
    @Volatile private var listenerZ = 0f
    @Volatile private var listenerYaw = 0f
    @Volatile private var rain = 0f
    @Volatile var paused = false

    private class Voice(val pcm: ShortArray, val left: Float, val right: Float, val step: Float, val loop: Boolean) { var pos = 0f }

    private val sounds = HashMap<String, ShortArray>()
    private val voices = ArrayList<Voice>()
    private val lock = Any()
    private var rainVoice: Voice? = null
    @Volatile private var running = true
    private val thread: Thread

    init {
        thread = Thread({ run() }, "audio").apply { isDaemon = true; start() }
    }

    fun setListener(x: Float, y: Float, z: Float, yaw: Float) {
        listenerX = x; listenerY = y; listenerZ = z; listenerYaw = yaw
    }

    /** Plays [name] at a world position (NaN x = not positional). */
    fun play(name: String, x: Float = Float.NaN, y: Float = 0f, z: Float = 0f, gain: Float = 1f, pitch: Float = 1f) {
        val pcm = synchronized(lock) { sounds[name] } ?: return
        var left = 1f; var right = 1f; var v = gain * volume
        if (!x.isNaN()) {
            val dx = x - listenerX; val dy = y - listenerY; val dz = z - listenerZ
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > 28f) return
            v *= (1f - d / 28f).coerceIn(0f, 1f)
            val a = atan2(dx, -dz) - listenerYaw
            val pan = sin(a) * 0.7f
            left = (1f - pan).coerceIn(0.2f, 1f); right = (1f + pan).coerceIn(0.2f, 1f)
        }
        if (v <= 0.01f) return
        synchronized(lock) {
            if (voices.size >= 24) voices.removeAt(0)
            voices.add(Voice(pcm, v * left, v * right, pitch.coerceIn(0.5f, 2f), false))
        }
    }

    fun setRain(strength: Float) { rain = strength }

    fun close() { running = false }

    private fun run() {
        for ((name, pcm) in Synth.all()) synchronized(lock) { sounds[name] = pcm }
        val rate = Synth.RATE
        val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
        val line: SourceDataLine = try {
            AudioSystem.getSourceDataLine(format).apply { open(format, rate / 5 * 4); start() }
        } catch (e: Exception) {
            System.err.println("No sound output: ${e.message}")
            return
        }
        val frames = rate / 50
        val mixL = FloatArray(frames); val mixR = FloatArray(frames)
        val music = FloatArray(frames)
        val gen = MusicGen(rate)
        val out = ByteArray(frames * 4)
        while (running) {
            java.util.Arrays.fill(mixL, 0f); java.util.Arrays.fill(mixR, 0f)
            if (!paused) {
                synchronized(lock) {
                    // Rain is a looping voice whose loudness follows the weather.
                    val r = rain * volume * 0.5f
                    if (r > 0.01f && rainVoice == null) sounds["rain"]?.let { rainVoice = Voice(it, 1f, 1f, 1f, true) }
                    if (r <= 0.01f) rainVoice = null
                    rainVoice?.let { mix(it, mixL, mixR, r) }
                    val it = voices.iterator()
                    while (it.hasNext()) if (!mix(it.next(), mixL, mixR, 1f)) it.remove()
                }
                val mv = musicVolume
                if (mv > 0.01f) {
                    gen.fill(music)
                    for (i in 0 until frames) { mixL[i] += music[i] * mv * 0.6f; mixR[i] += music[i] * mv * 0.6f }
                }
            }
            for (i in 0 until frames) {
                val l = (mixL[i].coerceIn(-1f, 1f) * 32767).toInt(); val r = (mixR[i].coerceIn(-1f, 1f) * 32767).toInt()
                out[i * 4] = l.toByte(); out[i * 4 + 1] = (l shr 8).toByte()
                out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = (r shr 8).toByte()
            }
            line.write(out, 0, out.size)
        }
        line.drain(); line.close()
    }

    /** Adds one block of [v] to the mix; false when it has finished. */
    private fun mix(v: Voice, l: FloatArray, r: FloatArray, gain: Float): Boolean {
        val n = v.pcm.size
        for (i in l.indices) {
            var p = v.pos.toInt()
            if (p >= n) { if (v.loop) { v.pos -= n; p = v.pos.toInt() } else return false }
            val s = v.pcm[p] / 32768f * gain
            l[i] += s * v.left; r[i] += s * v.right
            v.pos += v.step
        }
        return v.loop || v.pos < n
    }
}
