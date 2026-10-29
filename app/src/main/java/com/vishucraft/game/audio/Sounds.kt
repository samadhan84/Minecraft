package com.vishucraft.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import java.io.DataOutputStream
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Plays synthesised effects with distance fade and stereo panning, plus the generative background music. */
class Sounds(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(16)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ids = HashMap<String, Int>()
    private val dir = File(context.cacheDir, "sfx_v1")
    @Volatile var volume = 0.8f
    @Volatile var musicVolume = 0.5f
    @Volatile private var listenerX = 0f
    @Volatile private var listenerY = 0f
    @Volatile private var listenerZ = 0f
    @Volatile private var listenerYaw = 0f
    private var rainStream = 0
    private val music = Music()

    init {
        // Synthesising takes a moment, so do it off the UI thread.
        Thread({
            dir.mkdirs()
            for ((name, pcm) in Synth.all()) {
                val f = File(dir, "$name.wav")
                if (!f.exists()) writeWav(f, pcm)
                ids[name] = pool.load(f.path, 1)
            }
        }, "sfx-init").start()
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { d ->
            fun le32(v: Int) { d.write(v and 255); d.write(v shr 8 and 255); d.write(v shr 16 and 255); d.write(v shr 24 and 255) }
            fun le16(v: Int) { d.write(v and 255); d.write(v shr 8 and 255) }
            d.writeBytes("RIFF"); le32(36 + pcm.size * 2); d.writeBytes("WAVE")
            d.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(Synth.RATE); le32(Synth.RATE * 2); le16(2); le16(16)
            d.writeBytes("data"); le32(pcm.size * 2)
            for (s in pcm) le16(s.toInt())
        }
        tmp.renameTo(f)
    }

    fun setListener(x: Float, y: Float, z: Float, yaw: Float) {
        listenerX = x; listenerY = y; listenerZ = z; listenerYaw = yaw
    }

    /** Plays [name] at a world position (NaN x = not positional). */
    fun play(name: String, x: Float = Float.NaN, y: Float = 0f, z: Float = 0f, gain: Float = 1f, pitch: Float = 1f) {
        val id = ids[name] ?: return
        var left = 1f; var right = 1f; var v = gain * volume
        if (!x.isNaN()) {
            val dx = x - listenerX; val dy = y - listenerY; val dz = z - listenerZ
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > 28f) return
            v *= (1f - d / 28f).coerceIn(0f, 1f)
            // Angle relative to where the player looks; forward is (sin yaw, -cos yaw).
            val a = atan2(dx, -dz) - listenerYaw
            val pan = sin(a) * 0.7f
            left = (1f - pan).coerceIn(0.2f, 1f); right = (1f + pan).coerceIn(0.2f, 1f)
        }
        if (v <= 0.01f) return
        pool.play(id, (v * left).coerceIn(0f, 1f), (v * right).coerceIn(0f, 1f), 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun setRain(on: Boolean, strength: Float) {
        val id = ids["rain"] ?: return
        if (on && rainStream == 0) rainStream = pool.play(id, 0f, 0f, 0, -1, 1f)
        if (!on && rainStream != 0) { pool.stop(rainStream); rainStream = 0 }
        if (rainStream != 0) { val v = strength * volume * 0.5f; pool.setVolume(rainStream, v, v) }
    }

    fun resume() { pool.autoResume(); music.start() }
    fun pause() { pool.autoPause(); music.stop() }
    fun release() { music.stop(); pool.release() }

    /**
     * Endless gentle background music, composed on the fly: slow piano-like notes picked from a pentatonic
     * scale over a simple chord cycle, with long quiet gaps between phrases.
     */
    private inner class Music {
        @Volatile private var running = false
        private var thread: Thread? = null

        fun start() {
            if (running) return
            running = true
            thread = Thread({ loop() }, "music").apply { isDaemon = true; start() }
        }

        fun stop() { running = false; thread = null }

        private fun loop() {
            val rate = Synth.RATE
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(minBuf, rate))
                    .build()
            } catch (e: Exception) { return }
            track.play()
            val gen = MusicGen(rate)
            val block = FloatArray(rate / 4)
            val out = ShortArray(block.size)
            while (running) {
                if (musicVolume <= 0.01f) { Thread.sleep(300); continue }
                gen.fill(block)
                val g = musicVolume * 0.6f
                for (i in block.indices) out[i] = (block[i] * g * 32767).toInt().coerceIn(-32768, 32767).toShort()
                track.write(out, 0, out.size)
            }
            track.stop(); track.release()
        }
    }

    companion object {
        /** Sound family for a block (digging, placing, footsteps). */
        fun material(id: Int): String = Synth.material(id)
    }
}
