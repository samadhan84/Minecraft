package com.vishucraft.web

import com.vishucraft.client.ClientAudio
import com.vishucraft.client.ClientPrefs
import com.vishucraft.client.FontSheet
import com.vishucraft.game.audio.MusicGen
import com.vishucraft.game.audio.Synth
import com.vishucraft.game.engine.PlayLimit
import java.io.File
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/** Chunk loading, saving and meshing run here between frames, a few milliseconds at a time (no threads). */
object Tasks : java.util.concurrent.Executor {
    private val queue = ArrayDeque<Runnable>()
    override fun execute(r: Runnable) { queue.addLast(r) }
    val pending get() = queue.size
    fun run(budgetMs: Double) {
        val end = now() + budgetMs
        while (queue.isNotEmpty() && now() < end) queue.removeFirst().run()
    }
    fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
}

/** The UI font, drawn by the browser (see platform.js). */
fun browserFonts(chars: List<Char>, cell: Int): FontSheet {
    fontSheet(chars.joinToString(""), cell)
    val w = fontW(); val h = fontH()
    val px = IntArray(w * h)
    fontFill(px)
    return FontSheet(px, w, h, IntArray(chars.size) { fontX(it) }, IntArray(chars.size) { fontY(it) }, FloatArray(chars.size) { fontWidth(it).toFloat() })
}

/**
 * Saved worlds: the game writes them to TeaVM's in-memory files, and they are copied to the browser's IndexedDB
 * (see platform.js) after saving, and back into memory when the page opens.
 */
object Storage {
    const val ROOT = "/dhruvvishu"
    private val known = HashMap<String, Long>()

    private fun sig(f: File) = f.lastModified() * 31 + f.length()

    fun restore() {
        for (i in 0 until storedCount()) {
            val path = storedPath(i)
            val bytes = ByteArray(storedSize(i))
            storedFill(i, bytes)
            val f = File(path)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            known[path] = sig(f)
        }
        storedDone()
    }

    /** Copies new and changed files to the browser's storage and forgets deleted ones. */
    fun sync() {
        val seen = HashSet<String>()
        fun walk(d: File) {
            for (f in d.listFiles().orEmpty()) {
                if (f.isDirectory) { walk(f); continue }
                val path = f.path
                seen.add(path)
                val s = sig(f)
                if (known[path] != s) { store(path, f.readBytes()); known[path] = s }
            }
        }
        walk(File(ROOT))
        for (k in known.keys.toList()) if (k !in seen) { unstore(k); known.remove(k) }
    }
}

/** Settings, kept in the browser's local storage. */
class WebPrefs : ClientPrefs {
    private fun f(k: String, d: Float) = pref(k).toFloatOrNull() ?: d
    private fun i(k: String, d: Int) = pref(k).toIntOrNull() ?: d
    private fun b(k: String, d: Boolean) = when (pref(k)) { "true" -> true; "false" -> false; else -> d }

    override var sensitivity: Float get() = f("sensitivity", 1f); set(v) = setPref("sensitivity", v.toString())
    override var fov: Int get() = i("fov", 72); set(v) = setPref("fov", v.toString())
    override var renderDistance: Int get() = i("renderDistance", 5); set(v) = setPref("renderDistance", v.toString())
    override var soundVolume: Int get() = i("soundVolume", 80); set(v) = setPref("soundVolume", v.toString())
    override var musicVolume: Int get() = i("musicVolume", 50); set(v) = setPref("musicVolume", v.toString())
    override var showDebug: Boolean get() = b("showDebug", false); set(v) = setPref("showDebug", v.toString())
    override var weather: Boolean get() = b("weather", false); set(v) = setPref("weather", v.toString())
    override var fancy: Boolean get() = b("fancy", true); set(v) = setPref("fancy", v.toString())
    override var playerName: String
        get() = pref("playerName").ifEmpty { ("Player" + (100..999).random()).also { setPref("playerName", it) } }
        set(v) = setPref("playerName", v)
    var largeButtons: Boolean get() = b("largeButtons", false); set(v) = setPref("largeButtons", v.toString())

    private fun <T> next(options: List<T>, cur: T) = options[(options.indexOf(cur) + 1).mod(options.size)]

    override fun rows(): List<Pair<() -> String, () -> Unit>> = listOf(
        { "Look sensitivity: ${(sensitivity * 100).toInt()}%" } to { sensitivity = next(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), sensitivity) },
        { "Field of view: $fov°" } to { fov = next(listOf(60, 72, 85, 100), fov) },
        { "Render distance: $renderDistance chunks" } to { renderDistance = next(listOf(3, 4, 5, 6, 8), renderDistance) },
        { "Sound effects: $soundVolume%" } to { soundVolume = next(listOf(0, 40, 80, 100), soundVolume) },
        { "Music: $musicVolume%" } to { musicVolume = next(listOf(0, 25, 50, 100), musicVolume) },
        { "Weather (rain, snow, storms): ${if (weather) "On" else "Off"}" } to { weather = !weather },
        { "Fancy graphics (waving grass, 3D clouds, stars): ${if (fancy) "On" else "Off"}" } to { fancy = !fancy },
        { "Touch buttons: ${if (largeButtons) "Large" else "Normal"}" } to { largeButtons = !largeButtons },
        { "FPS & coordinates: ${if (showDebug) "On" else "Off"}" } to { showDebug = !showDebug },
    )

    override fun playLimit() = PlayLimit(pref("playDay"), f("playUsed", 0f))
    override fun savePlayLimit(l: PlayLimit) { setPref("playDay", l.day); setPref("playUsed", l.used.toString()) }
}

/** Sound through Web Audio: effects as buffers, rain as a loop, and music made a little ahead of time. */
class WebAudio : ClientAudio {
    override var volume = 0.8f
    override var musicVolume = 0.5f
    private var lx = 0f; private var ly = 0f; private var lz = 0f; private var lyaw = 0f
    private var loaded = false
    private val music = MusicGen(Synth.RATE)
    private val block = FloatArray(Synth.RATE / 2)

    override fun setListener(x: Float, y: Float, z: Float, yaw: Float) { lx = x; ly = y; lz = z; lyaw = yaw }

    override fun play(name: String, x: Float, y: Float, z: Float, gain: Float, pitch: Float) {
        if (!loaded) return
        var pan = 0f; var v = gain * volume
        if (!x.isNaN()) {
            val dx = x - lx; val dy = y - ly; val dz = z - lz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > 28f) return
            v *= (1f - d / 28f).coerceIn(0f, 1f)
            pan = sin(atan2(dx, -dz) - lyaw) * 0.7f
        }
        if (v > 0.01f) playSound(name, v, pan, pitch.coerceIn(0.5f, 2f))
    }

    override fun setRain(strength: Float) { if (loaded) jsSetRain(strength * volume * 0.5f) }
    override fun close() {}

    /** Called every frame: the sounds are made once sound is allowed (after the first tap), and music keeps flowing. */
    fun update() {
        if (!audioReady()) return
        if (!loaded) {
            for ((name, pcm) in Synth.all()) addSound(name, pcm, Synth.RATE)
            loaded = true
        }
        val mv = musicVolume
        if (mv > 0.01f && musicQueued() < 1.0) {
            music.fill(block)
            queueMusic(block, block.size, Synth.RATE, mv * 0.6f)
        }
    }
}
