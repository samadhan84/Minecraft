package com.vishucraft.game.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vishucraft.game.world.SketchError
import com.vishucraft.game.world.Sketches

/** The Ask Claude part of the Arduino IDE (see IdeActivity). */
object ArduinoIde {
    /** Asks for a question (and the key the first time), sends it to Claude and offers to put its sketch in the editor. */
    fun askClaude(a: Activity, settings: Settings, code: String, error: String?, useCode: (String) -> Unit) {
        if (settings.claudeKey.isBlank()) {
            val key = EditText(a).apply { hint = "Your Anthropic API key"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            AlertDialog.Builder(a).setTitle("Ask Claude")
                .setMessage("To ask Claude you need your own Anthropic API key (from console.anthropic.com). It is kept only on this device, and questions use your account.")
                .setView(key).setPositiveButton("Save") { _, _ ->
                    val k = key.text.toString().trim()
                    if (k.isNotEmpty()) { settings.claudeKey = k; askClaude(a, settings, code, error, useCode) }
                }.setNegativeButton("Not now", null).show()
            return
        }
        val q = EditText(a).apply { hint = "What do you want? (empty: check my sketch)"; minLines = 2 }
        AlertDialog.Builder(a).setTitle("Ask Claude").setView(q)
            .setPositiveButton("Ask") { _, _ ->
                val question = q.text.toString()
                Toast.makeText(a, "Claude is thinking…", Toast.LENGTH_SHORT).show()
                Thread {
                    try {
                        val answer = ClaudeHelper.ask(settings.claudeKey, question, code, error)
                        a.runOnUiThread { showAnswer(a, answer, useCode) }
                    } catch (e: Exception) {
                        a.runOnUiThread { Toast.makeText(a, e.message ?: "Could not reach Claude (is the internet on?)", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
            .setNeutralButton("Change key") { _, _ -> settings.claudeKey = ""; askClaude(a, settings, code, error, useCode) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAnswer(a: Activity, answer: String, useCode: (String) -> Unit) {
        val sketch = ClaudeHelper.codeIn(answer)
        val b = AlertDialog.Builder(a).setTitle("Claude says").setMessage(answer).setNegativeButton("Close", null)
        if (sketch != null) b.setPositiveButton("Use this sketch") { _, _ -> useCode(sketch) }
        b.show()
    }
}
