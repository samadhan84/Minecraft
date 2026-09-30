package com.vishucraft.game.engine

/*
 * The daily play-time limit: 45 minutes a day on this device, counted while a world is open. Warnings come
 * 10, 5 and 1 minute before the end; then the game saves and closes the world, and no world opens again until
 * the next day (a new day starts fresh). The phone and PC versions keep [day] and [used] in their settings.
 */
class PlayLimit(var day: String, var used: Float) {
    val over get() = used >= LIMIT
    /** Whole minutes left today (rounded up). */
    fun minutesLeft(today: String = today()): Int { newDay(today); return ((LIMIT - used).coerceAtLeast(0f) / 60f).let { kotlin.math.ceil(it).toInt() } }

    /** The time left today as a clock, e.g. "32:05". */
    fun clock(): String { val s = (LIMIT - used).coerceAtLeast(0f).toInt(); return "%d:%02d".format(s / 60, s % 60) }

    /** A new date resets the time used. */
    fun newDay(today: String) { if (today != day) { day = today; used = 0f } }

    /** Adds [seconds] of play on [today]; returns a warning to show when one is due, or null. */
    fun tick(today: String, seconds: Float): String? {
        newDay(today)
        val before = used
        used += seconds.coerceIn(0f, 5f)
        for (w in WARNINGS) if (before < LIMIT - w && used >= LIMIT - w)
            return if (w == 60) "Only 1 minute of play left today! Save your work" else "${w / 60} minutes of play left today"
        return null
    }

    companion object {
        const val LIMIT = 45 * 60f
        private val WARNINGS = intArrayOf(10 * 60, 5 * 60, 60)
        const val TITLE = "Daily limit is over"
        const val MESSAGE = "You have played 45 minutes today, so the daily limit is over.\n\nYour world is saved. Come back tomorrow - a new day starts fresh!"

        fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    }
}
