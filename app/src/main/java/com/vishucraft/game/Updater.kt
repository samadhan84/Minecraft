package com.vishucraft.game

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the game up to date by itself. Every build is published at a fixed link together with its version
 * number. The game checks that link when it starts and every 30 minutes while it runs; a newer version is
 * downloaded in the background straight away. On the menu it is then handed to Android's installer, which
 * always asks the player to tap Install once for apps from outside the Play Store. Worlds are kept.
 */
object Updater {
    private const val BASE = "https://github.com/samadhan84/Minecraft/releases/download/latest/"
    private const val EVERY_MS = 30 * 60 * 1000L
    const val ACTION_STATUS = "com.vishucraft.game.INSTALL_STATUS"

    private val main = Handler(Looper.getMainLooper())
    private var current: Activity? = null
    @Volatile private var busy = false
    /** Version of the APK already downloaded and waiting in the cache (0 = none). */
    @Volatile private var readyVersion = 0L
    private var lastCheck = 0L
    private var asked = false

    private val tick = object : Runnable {
        override fun run() {
            current?.let { check(it) }
            main.postDelayed(this, EVERY_MS)
        }
    }

    /** Called from each screen's onResume: checks now (at most every 30 minutes) and keeps checking. */
    fun attach(a: Activity) {
        current = a
        main.removeCallbacks(tick)
        if (System.currentTimeMillis() - lastCheck >= EVERY_MS || lastCheck == 0L) check(a)
        else if (readyVersion > 0 && a is MenuActivity) offerInstall(a)
        main.postDelayed(tick, EVERY_MS)
    }

    fun detach(a: Activity) {
        if (current === a) { current = null; main.removeCallbacks(tick) }
    }

    private fun installedVersion(a: Activity): Long {
        val info = a.packageManager.getPackageInfo(a.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 30000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "DhruvVishu")
    }

    private fun apkFile(a: Activity) = File(a.cacheDir, "update.apk")

    /** Looks at the link for a newer version and downloads it in the background. [manual] reports the result. */
    fun check(a: Activity, manual: Boolean = false) {
        if (busy) { if (manual) toast(a, "Already downloading the update…"); return }
        busy = true
        lastCheck = System.currentTimeMillis()
        val app = a.applicationContext
        Thread {
            var message: String? = null
            try {
                val latest = open(BASE + "version.txt").inputStream.bufferedReader().use { it.readText().trim().toLongOrNull() }
                val mine = installedVersion(a)
                when {
                    latest == null -> message = if (manual) "Couldn't check for updates" else null
                    latest <= mine -> message = if (manual) "You have the newest version" else null
                    latest != readyVersion -> {
                        main.post { toast(a, "Downloading a new version of DhruvVishu…") }
                        val tmp = File(app.cacheDir, "update.part")
                        open(BASE + "DhruvVishu.apk").inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        tmp.renameTo(apkFile(a))
                        readyVersion = latest
                        asked = false
                    }
                }
            } catch (e: Exception) {
                if (manual) message = "Couldn't check for updates (no internet?)"
            }
            busy = false
            main.post {
                message?.let { toast(a, it) }
                val here = current ?: a
                if (readyVersion > installedVersion(here)) {
                    if (here is MenuActivity || manual) offerInstall(here)
                    else if (!asked) toast(here, "Update downloaded. It installs when you go back to the menu.")
                }
            }
        }.start()
    }

    private fun toast(a: Activity, text: String) {
        if (!a.isFinishing) Toast.makeText(a, text, Toast.LENGTH_LONG).show()
    }

    /** Installs the downloaded update (Android shows its Install screen). */
    private fun offerInstall(a: Activity) {
        if (a.isFinishing || asked) return
        asked = true
        // Android needs permission once to install apps that don't come from the Play Store.
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a).setTitle("A new version is ready")
                .setMessage("To update by itself, DhruvVishu needs permission to install updates. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    asked = false
                    try {
                        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                    } catch (e: Exception) {
                        AlertDialog.Builder(a).setTitle("Settings not found")
                            .setMessage("Open Settings → Apps → Special app access → Install unknown apps, and allow DhruvVishu.")
                            .setPositiveButton("OK", null).show()
                    }
                }
                .setNegativeButton("Later") { _, _ -> asked = false }
                .show()
            return
        }
        try { install(a, apkFile(a)) } catch (e: Exception) {
            asked = false
            toast(a, "The update couldn't be installed (${e.message}). It will try again later.")
        }
    }

    /** Hands the APK to Android's installer; it replies through [handleStatus]. */
    private fun install(a: Activity, apk: File) {
        val installer = a.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("DhruvVishu", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(a, MenuActivity::class.java).setAction(ACTION_STATUS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getActivity(a, 0, intent, flags).intentSender)
        }
    }

    /** The installer's reply: shows the Install screen, or reports a problem. */
    fun handleStatus(a: Activity, intent: Intent?): Boolean {
        if (intent?.action != ACTION_STATUS) return false
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) try { a.startActivity(confirm) } catch (e: Exception) { }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                asked = false
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    toast(a, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update couldn't be installed.")
                }
            }
        }
        return true
    }
}
