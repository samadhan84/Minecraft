package com.vishucraft.game

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vishucraft.game.ui.ArduinoIde
import com.vishucraft.game.ui.Settings
import com.vishucraft.game.world.SketchError
import com.vishucraft.game.world.Sketches

/**
 * The Arduino IDE, a screen of its own: the teal toolbar with Verify and Upload, the sketch tab, the editor with line
 * numbers and coloured words, the message bar and the black console. Code and Upload happen here; afterwards tap an
 * Arduino or ESP32 board in the game to put the uploaded sketch on it.
 */
class IdeActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var edit: EditText
    private lateinit var gutter: TextView
    private lateinit var status: TextView
    private lateinit var console: TextView
    private lateinit var boardButton: Button
    private var esp = false
    private var lastError: String? = null

    private val teal = Color.rgb(0, 151, 157)
    private val darkTeal = Color.rgb(0, 92, 95)
    private val tabTeal = Color.rgb(0, 121, 126)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // The toolbar.
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(teal); setPadding(dp(10), dp(6), dp(10), dp(6)) }
        fun round(text: String, color: Int, click: () -> Unit) = Button(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 20f; isAllCaps = false
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE) }
            setOnClickListener { click() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) }) }
        fun flat(text: String, click: () -> Unit) = Button(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 14f; isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(darkTeal) }
            setOnClickListener { click() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { rightMargin = dp(8) }) }
        round("✓", darkTeal) { verify() }
        round("→", darkTeal) { upload() }
        flat("Examples") { examples() }
        flat("Ask Claude") { ArduinoIde.askClaude(this, settings, edit.text.toString(), lastError) { edit.setText(it); say("Claude's sketch is in the editor. Read it, then press ✓ or →.", false) } }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        boardButton = flat("Arduino Uno ▾") { esp = !esp; boardButton.text = if (esp) "ESP32 Dev Module ▾" else "Arduino Uno ▾" }
        flat("✕  Close") { finish() }
        root.addView(bar)

        // The sketch tab.
        val tabs = LinearLayout(this).apply { setBackgroundColor(darkTeal); setPadding(dp(10), dp(4), 0, 0) }
        tabs.addView(TextView(this).apply { text = "sketch_dhruvil.ino"; setTextColor(Color.WHITE); textSize = 13f; setBackgroundColor(tabTeal); setPadding(dp(16), dp(5), dp(16), dp(5)) })
        root.addView(tabs)

        // The editor: line numbers and the code.
        gutter = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 15f; setTextColor(Color.rgb(120, 120, 120)); setBackgroundColor(Color.rgb(232, 236, 236)); gravity = Gravity.END; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        edit = EditText(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 15f; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP; setPadding(dp(8), dp(8), dp(8), dp(8)); setHorizontallyScrolling(true)
            setText(settings.ideSketch.ifBlank { Sketches.examples[0].code })
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) { colour(s); numbers(s.toString()) }
        })
        val code = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        code.addView(gutter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        code.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { })
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(code, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // The message bar and the console.
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setBackgroundColor(tabTeal); setPadding(dp(12), dp(6), dp(12), dp(6)); text = "Ready." }
        console = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f; setTextColor(Color.rgb(230, 230, 230)); setBackgroundColor(Color.rgb(28, 32, 32)); setPadding(dp(12), dp(8), dp(12), dp(8)); minLines = 4; maxLines = 6 }
        root.addView(status)
        root.addView(ScrollView(this).apply { addView(console) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(110)))
        setContentView(root)
        colour(edit.text); numbers(edit.text.toString())
        edit.requestFocus()
    }

    private val orange = Color.rgb(211, 84, 0)
    private val tealWord = Color.rgb(0, 121, 126)
    private val blueWord = Color.rgb(0, 70, 160)
    private val keywords = setOf("void", "if", "else", "while", "int", "const")
    private val commands = setOf("pinMode", "digitalWrite", "digitalRead", "delay", "wifiSend", "wifiRead", "isNight", "setup", "loop")
    private val constants = setOf("HIGH", "LOW", "OUTPUT", "INPUT", "LED_BUILTIN", "true", "false", "A", "B", "C")

    /** Colours the words like the Arduino IDE does: keywords orange, commands teal, constants blue, comments grey. */
    private fun colour(s: Editable) {
        for (sp in s.getSpans(0, s.length, ForegroundColorSpan::class.java)) s.removeSpan(sp)
        for (sp in s.getSpans(0, s.length, StyleSpan::class.java)) s.removeSpan(sp)
        val text = s.toString()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                val e = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                s.setSpan(ForegroundColorSpan(Color.rgb(125, 135, 135)), i, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); i = e
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val e = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                s.setSpan(ForegroundColorSpan(Color.rgb(125, 135, 135)), i, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); i = e
            } else if (c.isLetter() || c == '_') {
                var e = i + 1
                while (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_')) e++
                val w = text.substring(i, e)
                val col = when (w) { in keywords -> orange; in commands -> tealWord; in constants -> blueWord; else -> 0 }
                if (col != 0) {
                    s.setSpan(ForegroundColorSpan(col), i, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (w in commands || w in keywords) s.setSpan(StyleSpan(Typeface.BOLD), i, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                i = e
            } else i++
        }
    }

    private fun numbers(text: String) { gutter.text = (1..text.count { it == '\n' } + 1).joinToString("\n") }

    private fun say(text: String, error: Boolean) {
        status.text = text; status.setBackgroundColor(if (error) Color.rgb(176, 64, 0) else tabTeal)
    }

    private fun log(text: String, error: Boolean = false) {
        console.setTextColor(if (error) Color.rgb(255, 150, 90) else Color.rgb(230, 230, 230)); console.text = text
    }

    /** Checks the code like Verify does; returns true when there is nothing to fix. */
    private fun verify(): Boolean {
        val txt = edit.text.toString()
        val board = if (esp) "ESP32 Dev Module" else "Arduino Uno"
        return try {
            val preview = Sketches.preview(txt, esp)
            lastError = null
            say("Done compiling.", false)
            log("Sketch compiled for $board.\n\n$preview")
            true
        } catch (e: SketchError) {
            lastError = "Line ${e.line}: ${e.message}"
            say("Error compiling for board $board.", true)
            log("sketch_dhruvil.ino:${e.line}: error: ${e.message}", true)
            false
        }
    }

    private fun upload() {
        if (!verify()) return
        settings.uploadedSketch = edit.text.toString()
        say("Done uploading.", false)
        log("Uploading…\nDone uploading.\n\nThe sketch is ready. Go to the game and tap an Arduino or ESP32 board to put it on that board.")
    }

    private fun examples() {
        val ex = Sketches.examples
        AlertDialog.Builder(this).setTitle("Examples").setItems(ex.map { it.title }.toTypedArray()) { _, i ->
            edit.setText(ex[i].code); say("Ready.", false); log("")
            if (ex[i].needsWifi) { esp = true; boardButton.text = "ESP32 Dev Module ▾" }
        }.setNegativeButton("Cancel", null).show()
    }

    override fun onPause() {
        super.onPause()
        settings.ideSketch = edit.text.toString()
    }
}
