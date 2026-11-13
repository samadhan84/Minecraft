package com.vishucraft.game.ui

import android.content.Context

/** Player preferences, shared by every world. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Small-memory devices (many TVs) start with a short view so the game does not run out of memory. */
    private val lowMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.let { it.memoryClass <= 128 || it.isLowRamDevice } ?: false

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 1f)
        set(v) = prefs.edit().putFloat("sensitivity", v).apply()
    var fov: Int
        get() = prefs.getInt("fov", 72)
        set(v) = prefs.edit().putInt("fov", v).apply()
    var renderDistance: Int
        get() = prefs.getInt("renderDistance", if (lowMemory) 3 else 5)
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
    /** Rain, snow and thunderstorms. Off by default. */
    var weather: Boolean
        get() = prefs.getBoolean("weather", false)
        set(v) = prefs.edit().putBoolean("weather", v).apply()
    var fancy: Boolean
        get() = prefs.getBoolean("fancy", true)
        set(v) = prefs.edit().putBoolean("fancy", v).apply()
    var largeButtons: Boolean
        get() = prefs.getBoolean("largeButtons", false)
        set(v) = prefs.edit().putBoolean("largeButtons", v).apply()
    /** Today's play time (see engine/PlayLimit). */
    fun playLimit() = com.vishucraft.game.engine.PlayLimit(prefs.getString("playDay", "") ?: "", prefs.getFloat("playUsed", 0f))
    fun savePlayLimit(l: com.vishucraft.game.engine.PlayLimit) = prefs.edit().putString("playDay", l.day).putFloat("playUsed", l.used).apply()

    companion object {
        private fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1).mod(options.size)]

        /** Rows for a settings screen: label provider + action that advances the value. */
        fun rows(s: Settings): List<Pair<() -> String, () -> Unit>> = listOf(
            { "Look sensitivity: ${"%.2f".format(s.sensitivity)}x" } to { s.sensitivity = next(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), s.sensitivity) },
            { "Field of view: ${s.fov}°" } to { s.fov = next(listOf(60, 72, 85, 100), s.fov) },
            { "Render distance: ${s.renderDistance} chunks" } to { s.renderDistance = next(listOf(3, 4, 6, 8, 10), s.renderDistance) },
            { "Sound effects: ${s.soundVolume}%" } to { s.soundVolume = next(listOf(0, 40, 80, 100), s.soundVolume) },
            { "Music: ${s.musicVolume}%" } to { s.musicVolume = next(listOf(0, 25, 50, 100), s.musicVolume) },
            { "Weather (rain, snow, storms): ${if (s.weather) "On" else "Off"}" } to { s.weather = !s.weather },
            { "Fancy graphics (waving grass, 3D clouds, stars): ${if (s.fancy) "On" else "Off"}" } to { s.fancy = !s.fancy },
            { "Touch buttons: ${if (s.largeButtons) "Large" else "Normal"}" } to { s.largeButtons = !s.largeButtons },
            { "Show FPS & coordinates: ${if (s.showDebug) "On" else "Off"}" } to { s.showDebug = !s.showDebug },
        )
    }
}

/** Builds a focusable list of settings buttons (works with touch and TV remotes). */
/** A row of choices where exactly one is chosen (gold, with a dot); tapping another picks it. */
fun modeRow(ctx: android.app.Activity, labels: List<String>, chosen: () -> Int, pick: (Int) -> Unit): android.widget.LinearLayout {
    val row = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
    val buttons = ArrayList<android.widget.TextView>()
    fun refresh() {
        for ((i, b) in buttons.withIndex()) {
            val on = i == chosen()
            b.text = (if (on) "● " else "") + labels[i]
            b.alpha = if (on) 1f else 0.7f
            b.setTextColor(if (on) android.graphics.Color.rgb(255, 224, 110) else android.graphics.Color.WHITE)
            b.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (on) android.graphics.Color.rgb(150, 112, 20) else android.graphics.Color.rgb(112, 112, 112))
                setStroke(ctx.dpi(3f), if (on) android.graphics.Color.rgb(255, 224, 110) else android.graphics.Color.rgb(30, 30, 30))
            }
        }
    }
    for ((i, l) in labels.withIndex()) {
        val b = menuButton(ctx, l) { pick(i); refresh() }
        b.textSize = 14f
        b.setPadding(ctx.dpi(6f), ctx.dpi(10f), ctx.dpi(6f), ctx.dpi(10f))
        buttons.add(b)
        row.addView(b, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ctx.dpi(6f) })
    }
    refresh()
    return row
}

/**
 * The settings, in sections with a gold heading each: whatever sections the game passes (Game, Day & Night, Camera...),
 * then Graphics, Sound and Controls. Choices that have a few modes show them side by side.
 */
fun settingsDialog(ctx: android.app.Activity, extraSections: List<Pair<String, (android.widget.LinearLayout) -> Unit>> = emptyList(), onChanged: () -> Unit = {}) {
    val s = Settings(ctx)
    val col = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(ctx.dpi(16f), ctx.dpi(12f), ctx.dpi(16f), ctx.dpi(4f))
    }
    fun header(title: String) {
        col.addView(android.widget.TextView(ctx).apply {
            text = title; textSize = 20f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.rgb(255, 214, 80))
            setPadding(0, ctx.dpi(14f), 0, ctx.dpi(2f))
        })
        col.addView(android.view.View(ctx).apply { setBackgroundColor(android.graphics.Color.rgb(214, 170, 40)) },
            android.widget.LinearLayout.LayoutParams(-1, ctx.dpi(2f)).apply { bottomMargin = ctx.dpi(8f) })
    }
    for ((title, build) in extraSections) { header(title); build(col) }
    val rows = Settings.rows(s)
    fun section(title: String, pick: (String) -> Boolean) {
        val mine = rows.filter { pick(it.first()) }
        if (mine.isEmpty()) return
        header(title)
        for ((label, action) in mine) {
            lateinit var b: android.widget.TextView
            b = menuButton(ctx, label()) { action(); b.text = label(); onChanged() }
            col.addView(b, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ctx.dpi(8f) })
        }
    }
    section("Graphics") { it.startsWith("Field of view") || it.startsWith("Render distance") || it.startsWith("Fancy") || it.startsWith("Weather") }
    section("Sound") { it.startsWith("Sound") || it.startsWith("Music") }
    section("Controls") { it.contains("ensitivity") || it.startsWith("Touch") || it.startsWith("Show FPS") }
    // Player name (shown to others in Wi-Fi games).
    header("Player")
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
    col.getChildAt(1)?.requestFocus()
}
