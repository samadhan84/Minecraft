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

/**
 * The Arduino IDE window (used from the home screen and from a board in the world): a code editor, examples, Check or
 * Upload, and Ask Claude. On the home screen (no [upload]) it checks the code and shows a preview of what it would do;
 * in the world it uploads to the board.
 */
object ArduinoIde {
    fun show(
        a: Activity, title: String, code0: String, wifi0: Boolean, wifiFixed: Boolean,
        upload: ((String, (String?) -> Unit) -> Unit)?,
        presets: List<String> = emptyList(), onPreset: (Int) -> Unit = {},
    ) {
        val settings = Settings(a)
        var wifi = wifi0
        val dp = a.resources.displayMetrics.density
        val message = TextView(a).apply { typeface = Typeface.MONOSPACE; textSize = 14f; visibility = android.view.View.GONE; setPadding(0, 0, 0, (8 * dp).toInt()) }
        val edit = EditText(a).apply {
            setText(code0); typeface = Typeface.MONOSPACE; textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP; minLines = 10; maxLines = 18
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, click: () -> Unit) = Button(a).apply { text = label; setOnClickListener { click() } }
            .also { row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        column.addView(message); column.addView(edit); column.addView(row)

        fun say(text: String) { message.text = text; message.visibility = android.view.View.VISIBLE }

        val dialog = AlertDialog.Builder(a).setTitle(title).setView(ScrollView(a).apply { addView(column) })
            .setPositiveButton(if (upload != null) "Upload" else "Check", null)
            .setNegativeButton("Close", null).create()
        dialog.setOnDismissListener { if (upload == null) settings.ideSketch = edit.text.toString() }

        button("Examples") {
            val ex = Sketches.examples
            val names = (ex.map { it.title } + presets.map { "Ready-made: $it" } + (if (settings.ideSketch.isNotBlank()) listOf("My sketch from the home screen") else emptyList())).toTypedArray()
            AlertDialog.Builder(a).setTitle("Examples").setItems(names) { _, i ->
                when {
                    i < ex.size -> { edit.setText(ex[i].code); message.visibility = android.view.View.GONE }
                    i < ex.size + presets.size -> { onPreset(i - ex.size); dialog.dismiss() }
                    else -> edit.setText(settings.ideSketch)
                }
            }.setNegativeButton("Back", null).show()
        }
        button("Ask Claude") { askClaude(a, settings, edit.text.toString(), message.text.toString().takeIf { message.visibility == android.view.View.VISIBLE && it.startsWith("⚠") }) { edit.setText(it); say("✓ Claude's sketch is in the editor. Read it, then ${if (upload != null) "press Upload" else "press Check"}.") } }
        if (!wifiFixed) {
            val board = button("Board: Arduino") {}
            board.setOnClickListener { wifi = !wifi; board.text = if (wifi) "Board: ESP32" else "Board: Arduino" }
            board.text = if (wifi) "Board: ESP32" else "Board: Arduino"
        }

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val txt = edit.text.toString()
            if (upload != null) upload(txt) { err -> if (err == null) dialog.dismiss() else say("⚠ $err") }
            else try { say("✓ No mistakes found.\n\n" + Sketches.preview(txt, wifi)) } catch (e: SketchError) { say("⚠ Line ${e.line}: ${e.message}") }
        }
    }

    /** Asks for a question (and the key the first time), sends it to Claude and offers to put its sketch in the editor. */
    private fun askClaude(a: Activity, settings: Settings, code: String, error: String?, useCode: (String) -> Unit) {
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
