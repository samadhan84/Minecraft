package com.vishucraft.game.ui

import android.content.Context

/** Player preferences, shared by every world. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 1f)
        set(v) = prefs.edit().putFloat("sensitivity", v).apply()
    var fov: Int
        get() = prefs.getInt("fov", 72)
        set(v) = prefs.edit().putInt("fov", v).apply()
    var renderDistance: Int
        get() = prefs.getInt("renderDistance", 6)
        set(v) = prefs.edit().putInt("renderDistance", v).apply()
    var showDebug: Boolean
        get() = prefs.getBoolean("showDebug", true)
        set(v) = prefs.edit().putBoolean("showDebug", v).apply()
    var soundVolume: Int
        get() = prefs.getInt("soundVolume", 80)
        set(v) = prefs.edit().putInt("soundVolume", v).apply()
    var musicVolume: Int
        get() = prefs.getInt("musicVolume", 50)
        set(v) = prefs.edit().putInt("musicVolume", v).apply()
    var playerName: String
        get() = prefs.getString("playerName", null) ?: ("Player" + (100..999).random()).also { n -> prefs.edit().putString("playerName", n).apply() }
        set(v) = prefs.edit().putString("playerName", v).apply()
    var largeButtons: Boolean
        get() = prefs.getBoolean("largeButtons", false)
        set(v) = prefs.edit().putBoolean("largeButtons", v).apply()

    companion object {
        private fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1).mod(options.size)]

        /** Rows for a settings screen: label provider + action that advances the value. */
        fun rows(s: Settings): List<Pair<() -> String, () -> Unit>> = listOf(
            { "Look sensitivity: ${"%.2f".format(s.sensitivity)}x" } to { s.sensitivity = next(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), s.sensitivity) },
            { "Field of view: ${s.fov}°" } to { s.fov = next(listOf(60, 72, 85, 100), s.fov) },
            { "Render distance: ${s.renderDistance} chunks" } to { s.renderDistance = next(listOf(4, 6, 8, 10), s.renderDistance) },
            { "Sound effects: ${s.soundVolume}%" } to { s.soundVolume = next(listOf(0, 40, 80, 100), s.soundVolume) },
            { "Music: ${s.musicVolume}%" } to { s.musicVolume = next(listOf(0, 25, 50, 100), s.musicVolume) },
            { "Touch buttons: ${if (s.largeButtons) "Large" else "Normal"}" } to { s.largeButtons = !s.largeButtons },
            { "Show FPS & coordinates: ${if (s.showDebug) "On" else "Off"}" } to { s.showDebug = !s.showDebug },
        )
    }
}

/** Builds a focusable list of settings buttons (works with touch and TV remotes). */
fun settingsDialog(ctx: android.app.Activity, onChanged: () -> Unit = {}) {
    val s = Settings(ctx)
    val col = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(ctx.dpi(16f), ctx.dpi(12f), ctx.dpi(16f), ctx.dpi(4f))
    }
    for ((label, action) in Settings.rows(s)) {
        lateinit var b: android.widget.TextView
        b = menuButton(ctx, label()) { action(); b.text = label(); onChanged() }
        col.addView(b, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dpi(8f) })
    }
    // Player name (shown to others in Wi-Fi games).
    val nameInput = android.widget.EditText(ctx).apply {
        setText(s.playerName); hint = "Player name"
        inputType = android.text.InputType.TYPE_CLASS_TEXT
        setOnFocusChangeListener { _, has -> if (!has) s.playerName = text.toString().trim().ifEmpty { s.playerName } }
    }
    col.addView(android.widget.TextView(ctx).apply { text = "Player name (for Wi-Fi games)" })
    col.addView(nameInput)
    val scroll = android.widget.ScrollView(ctx).apply { addView(col) }
    android.app.AlertDialog.Builder(ctx).setTitle("Settings").setView(scroll)
        .setPositiveButton("Done") { _, _ -> s.playerName = nameInput.text.toString().trim().ifEmpty { s.playerName } }.show()
    col.getChildAt(0)?.requestFocus()
}
