package com.vishucraft.desktop

import java.io.File
import java.util.Properties

/** Where DhruvilCraft keeps worlds, settings and crash logs on this computer. */
object Paths {
    val home: File by lazy {
        val override = System.getProperty("vishucraft.home")
        val appData = System.getenv("APPDATA")
        val base = if (appData != null) File(appData) else File(System.getProperty("user.home"))
        val dir = override?.let { File(it) } ?: File(base, if (appData != null) "DhruvilCraft" else ".dhruvilcraft")
        // The game used to be called VishuCraft: keep the worlds made before the rename.
        val old = File(base, if (appData != null) "VishuCraft" else ".vishucraft")
        if (override == null && !dir.exists() && old.isDirectory) old.renameTo(dir)
        dir.apply { mkdirs() }
    }
    val worlds: File get() = File(home, "worlds").apply { mkdirs() }
}

/** Player preferences (same options as on Android). */
class Prefs {
    private val file = File(Paths.home, "settings.properties")
    private val p = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    private fun save() = file.outputStream().use { p.store(it, "DhruvilCraft settings") }
    private fun f(k: String, d: Float) = p.getProperty(k)?.toFloatOrNull() ?: d
    private fun i(k: String, d: Int) = p.getProperty(k)?.toIntOrNull() ?: d
    private fun b(k: String, d: Boolean) = p.getProperty(k)?.toBooleanStrictOrNull() ?: d

    var sensitivity: Float get() = f("sensitivity", 1f); set(v) { p.setProperty("sensitivity", v.toString()); save() }
    var fov: Int get() = i("fov", 75); set(v) { p.setProperty("fov", v.toString()); save() }
    var renderDistance: Int get() = i("renderDistance", 8); set(v) { p.setProperty("renderDistance", v.toString()); save() }
    var soundVolume: Int get() = i("soundVolume", 80); set(v) { p.setProperty("soundVolume", v.toString()); save() }
    var musicVolume: Int get() = i("musicVolume", 50); set(v) { p.setProperty("musicVolume", v.toString()); save() }
    var showDebug: Boolean get() = b("showDebug", false); set(v) { p.setProperty("showDebug", v.toString()); save() }
    var fullscreen: Boolean get() = b("fullscreen", false); set(v) { p.setProperty("fullscreen", v.toString()); save() }
    var playerName: String
        get() = p.getProperty("playerName") ?: ("Player" + (100..999).random()).also { p.setProperty("playerName", it); save() }
        set(v) { p.setProperty("playerName", v); save() }

    private fun <T> next(options: List<T>, cur: T) = options[(options.indexOf(cur) + 1).mod(options.size)]

    /** Label + "change" action for each option, shown as buttons on the settings screen. */
    fun rows(): List<Pair<() -> String, () -> Unit>> = listOf(
        { "Mouse sensitivity: ${"%.2f".format(sensitivity)}x" } to { sensitivity = next(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), sensitivity) },
        { "Field of view: $fov°" } to { fov = next(listOf(60, 75, 90, 105), fov) },
        { "Render distance: $renderDistance chunks" } to { renderDistance = next(listOf(4, 6, 8, 10, 12), renderDistance) },
        { "Sound effects: $soundVolume%" } to { soundVolume = next(listOf(0, 40, 80, 100), soundVolume) },
        { "Music: $musicVolume%" } to { musicVolume = next(listOf(0, 25, 50, 100), musicVolume) },
        { "Fullscreen: ${if (fullscreen) "On" else "Off"} (F11)" } to { fullscreen = !fullscreen },
        { "FPS & coordinates: ${if (showDebug) "On" else "Off"} (F3)" } to { showDebug = !showDebug },
    )
}
