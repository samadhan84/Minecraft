package com.vishucraft.game

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/** Asks Claude to write, change, fix or explain an Arduino sketch. Runs on a background thread. */
object ClaudeCoder {
    private const val MODEL = "claude-opus-5-5"

    private val SYSTEM = """
        You are an Arduino teacher and expert helping a beginner who writes code on a TV with a remote control.
        They cannot compile on the TV: they copy your sketch to the Arduino IDE on a computer and upload it from there.

        Rules for every answer:
        - Start with a short explanation in plain, simple words: what the sketch does, the wiring (which board pin
          goes to which part, and resistors needed), and any library to install from the Library Manager.
        - Then give exactly one complete sketch that compiles as-is for the board named in the request, inside a
          single ```cpp code block. Never give partial code or several code blocks.
        - Use pins that exist on that board. On ESP32, avoid input-only pins (34-39) for outputs and remember the
          on-board LED is usually GPIO 2. Use Serial.begin(115200) on ESP32 and 9600 on AVR boards.
        - Keep code simple and add short comments a beginner can follow.
        - When asked only to explain, do not include a code block.
    """.trimIndent()

    class Answer(val text: String, val code: String?)

    class Failure(message: String) : Exception(message)

    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    @Synchronized
    private fun client(key: String): AnthropicClient {
        if (key != cachedKey || cachedClient == null) {
            cachedClient = AnthropicOkHttpClient.builder().apiKey(key).build()
            cachedKey = key
        }
        return cachedClient!!
    }

    /** [task] is "write", "change", "fix" or "explain"; [language] is the language for the explanation. */
    fun ask(key: String, task: String, board: String, language: String, message: String, code: String): Answer {
        val request = buildString {
            append("Board: $board\n")
            append("Write the explanation in $language (code comments stay in English).\n\n")
            when (task) {
                "write" -> append("Write a new sketch that does this:\n$message")
                "change" -> append("Change my sketch like this: $message\n\nMy sketch:\n```cpp\n$code\n```")
                "fix" -> append(
                    "My sketch does not work. Explain the problem simply and give the fixed sketch.\n" +
                        "Error message or what goes wrong: ${message.ifBlank { "(none given - look for mistakes)" }}\n\n" +
                        "My sketch:\n```cpp\n$code\n```"
                )
                else -> append(
                    "Explain this sketch step by step for a beginner, and list the wiring it expects." +
                        (if (message.isNotBlank()) "\nMy question: $message" else "") +
                        "\n\n```cpp\n$code\n```"
                )
            }
        }

        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(SYSTEM)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            // If a safety check declines the request, the API retries it on a suitable model by itself.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessage(request)
            .build()

        val response = try {
            client(key).beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            throw Failure("The API key is not valid. Open Settings and check it.")
        } catch (e: PermissionDeniedException) {
            throw Failure("This API key is not allowed to use Claude. Check your account at console.anthropic.com.")
        } catch (e: RateLimitException) {
            throw Failure("Too many requests right now. Wait a minute and try again.")
        } catch (e: AnthropicServiceException) {
            throw Failure(
                if (e.statusCode() == 400 && (e.message ?: "").contains("credit", ignoreCase = true))
                    "Your Anthropic account has no credit left. Add credit at console.anthropic.com."
                else "Claude had a problem (error ${e.statusCode()}). Try again."
            )
        } catch (e: AnthropicIoException) {
            throw Failure("Couldn't reach Claude. Check the TV's internet connection.")
        }

        if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            throw Failure("Claude declined this request. Try asking in a different way.")
        }

        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
        if (text.isEmpty()) throw Failure("Claude sent an empty answer. Try again.")

        // The sketch is the first ```cpp / ```arduino / ``` block; everything else is the explanation.
        val block = Regex("```[a-zA-Z+]*\\s*\\n([\\s\\S]*?)```").find(text)
        val code = block?.groupValues?.get(1)?.trimEnd()?.plus("\n")
        val explanation = if (block != null) text.removeRange(block.range).trim() else text
        if (response.stopReason().orElse(null) == BetaStopReason.MAX_TOKENS && code == null) {
            throw Failure("The answer was too long and got cut off. Ask for something smaller.")
        }
        return Answer(explanation, if (task == "explain") null else code)
    }
}
