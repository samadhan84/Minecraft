package com.vishucraft.game

import android.app.Activity
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Arduino AI: write Arduino Uno / ESP32 code on the TV with Claude's help. The screen is a web page in
 * assets/www; this activity hosts it and gives it the API key store, the Claude calls, voice typing and QR codes.
 */
class MenuActivity : Activity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(0x16, 0x18, 0x1c))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Lets the page set its own layout width (1920 on TVs, see index.html).
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("file:///android_asset/www/index.html")
        Updater.handleStatus(this, intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Updater.handleStatus(this, intent)
    }

    override fun onResume() {
        super.onResume()
        Updater.attach(this)
    }

    override fun onPause() {
        Updater.detach(this)
        super.onPause()
    }

    /** Back closes the open dialog on the page; when none is open, it leaves the app. */
    @Deprecated("Deprecated in Java")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            web.evaluateJavascript("window.handleBack ? handleBack() : false") { handled ->
                if (handled != "true") finish()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun js(function: String, vararg args: String) {
        val call = function + "(" + args.joinToString(",") { JSONObject.quote(it) } + ")"
        main.post { web.evaluateJavascript(call, null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            js("onVoice", if (resultCode == RESULT_OK && text != null) text else "")
        }
    }

    inner class Bridge {
        @JavascriptInterface fun getKey(): String = prefs.getString("apiKey", "") ?: ""

        @JavascriptInterface fun setKey(key: String) { prefs.edit().putString("apiKey", key.trim()).apply() }

        @JavascriptInterface fun isTv(): Boolean =
            (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        @JavascriptInterface fun version(): String =
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""

        @JavascriptInterface fun checkUpdate() { main.post { Updater.check(this@MenuActivity, manual = true) } }

        /** Starts a Claude request; the page gets onAiResult(id, json) when it is done. */
        @JavascriptInterface fun ask(id: String, task: String, board: String, language: String, message: String, code: String) {
            val key = getKey()
            Thread {
                val result = JSONObject()
                try {
                    if (key.isEmpty()) throw ClaudeCoder.Failure("Add your Claude API key in Settings first.")
                    val answer = ClaudeCoder.ask(key, task, board, language, message, code)
                    result.put("ok", true).put("text", answer.text).put("code", answer.code ?: JSONObject.NULL)
                } catch (e: ClaudeCoder.Failure) {
                    result.put("ok", false).put("error", e.message)
                } catch (e: Exception) {
                    result.put("ok", false).put("error", "Something went wrong: ${e.message ?: e.javaClass.simpleName}")
                }
                js("onAiResult", id, result.toString())
            }.start()
        }

        /** Voice typing with the remote's microphone; the page gets onVoice(text). */
        @JavascriptInterface fun voice(prompt: String) {
            main.post {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                try {
                    startActivityForResult(intent, VOICE)
                } catch (e: ActivityNotFoundException) {
                    js("onVoiceMissing")
                }
            }
        }

        /** A QR code of [text] as a PNG data URL, or "" when the text is too long for one QR code. */
        @JavascriptInterface fun qr(text: String): String = try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 2,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val scale = maxOf(1, 720 / matrix.width)
            val size = matrix.width * scale
            val pixels = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x / scale, y / scale)) Color.BLACK else Color.WHITE
            }
            val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val VOICE = 7
    }
}
