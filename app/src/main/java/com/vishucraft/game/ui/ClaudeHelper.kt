package com.vishucraft.game.ui

import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks Claude (through the Anthropic API) for help with a sketch in the Arduino IDE. It uses the player's OWN API key,
 * typed once into the game and kept only on that device (see Settings.claudeKey); no key is part of the game.
 */
object ClaudeHelper {
    private const val MODEL = "claude-sonnet-5-5"

    private const val SYSTEM = "You help a child write sketches for a small Arduino-style language inside a Minecraft-like game. " +
        "The language is NOT full Arduino C. It has only: void setup() { } and void loop() { } (or plain statements that repeat), " +
        "pinMode(pin, OUTPUT); digitalWrite(pin, HIGH or LOW); delay(milliseconds); if (condition) { } else { }; while (condition) { }; " +
        "conditions are digitalRead(pin) == HIGH, !isNight(), HIGH, LOW, and on ESP32 boards also wifiRead(A) == HIGH. " +
        "ESP32 only: wifiSend(A, HIGH); channels are A, B and C. The pin number is ignored: a board has one output and one input. " +
        "There are NO variables, for loops, functions, arrays, Serial, analogWrite or libraries. " +
        "Answer in simple words, in two or three short sentences, and put the full sketch in one ``` code block. " +
        "If you are given an error message from the editor, explain what went wrong and fix it."

    /** Sends [question] (and the current [code] and the editor's last [error]) to Claude and returns its answer. Call this off the UI thread. */
    fun ask(key: String, question: String, code: String, error: String?): String {
        val user = buildString {
            append(if (question.isBlank()) "Please check my sketch and fix it." else question)
            append("\n\nMy sketch now:\n```\n").append(code.take(1500)).append("\n```")
            if (!error.isNullOrBlank()) append("\n\nThe editor said: ").append(error)
        }
        val body = "{\"model\":\"$MODEL\",\"max_tokens\":1200,\"system\":${quote(SYSTEM)},\"messages\":[{\"role\":\"user\",\"content\":${quote(user)}}]}"
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000; c.doOutput = true
            c.setRequestProperty("x-api-key", key.trim())
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code2 = c.responseCode
            if (code2 != 200) {
                val err = (c.errorStream ?: c.inputStream).bufferedReader().use { it.readText() }
                throw IllegalStateException(when (code2) {
                    401, 403 -> "Claude did not accept the key. Check it and try again."
                    429 -> "Too many questions right now. Wait a little and try again."
                    529, 503 -> "Claude is busy. Try again in a minute."
                    else -> "Claude said no ($code2): " + (text(err) ?: err.take(160))
                })
            }
            val reply = c.inputStream.bufferedReader().use { it.readText() }
            return text(reply) ?: throw IllegalStateException("Claude sent nothing back")
        } finally { c.disconnect() }
    }

    /** The first sketch inside a ``` block of [answer], or null. */
    fun codeIn(answer: String): String? {
        val start = answer.indexOf("```").takeIf { it >= 0 } ?: return null
        val nl = answer.indexOf('\n', start).takeIf { it >= 0 } ?: return null
        val end = answer.indexOf("```", nl).takeIf { it >= 0 } ?: return null
        return answer.substring(nl + 1, end).trim().ifEmpty { null }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\"); c == '\n' -> sb.append("\\n")
            c == '\r' -> {}; c == '\t' -> sb.append("\\t"); c < ' ' -> sb.append("\\u%04x".format(c.code)); else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** The first "text" string in an API reply (the answer, or an error's message). */
    private fun text(json: String): String? {
        var at = json.indexOf("\"text\"")
        if (at < 0) at = json.indexOf("\"message\"")
        if (at < 0) return null
        var i = json.indexOf('"', json.indexOf(':', at) + 1) + 1
        if (i <= 0) return null
        val sb = StringBuilder()
        while (i < json.length && json[i] != '"') {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                i++
                when (val e = json[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> {}; 'b', 'f' -> {}
                    'u' -> { if (i + 4 < json.length) { sb.append(json.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: '?'); i += 4 } }
                    else -> sb.append(e)
                }
            } else sb.append(c)
            i++
        }
        return sb.toString()
    }
}
