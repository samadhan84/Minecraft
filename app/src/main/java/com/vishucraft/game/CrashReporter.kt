package com.vishucraft.game

import android.content.Context
import android.os.Build
import java.io.File

/** Saves the last uncaught exception so the title screen can show it on the next launch. */
object CrashReporter {
    private const val FILE = "last_crash.txt"
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.applicationContext.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = error.stackTraceToString().lines().take(25).joinToString("\n")
                file.writeText(
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "Thread: ${thread.name}\n$trace"
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun takeReport(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = try { file.readText() } catch (_: Throwable) { null }
        file.delete()
        return text
    }
}
