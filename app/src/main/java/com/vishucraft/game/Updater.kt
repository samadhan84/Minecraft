package com.vishucraft.game

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the game up to date: every build is published at a fixed link together with its version number.
 * When the menu opens, a newer version is downloaded and handed to Android's installer (which always asks
 * the player to tap Install once for apps from outside the Play Store). Worlds are kept.
 */
object Updater {
    private const val BASE = "https://github.com/samadhan84/Minecraft/releases/download/latest/"
    const val ACTION_STATUS = "com.vishucraft.game.INSTALL_STATUS"
    @Volatile private var checkedThisRun = false

    private fun installedVersion(a: Activity): Long {
        val info = a.packageManager.getPackageInfo(a.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 20000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "DhruvVishu")
    }

    /** Looks for a newer version (once per app start, or every time when [manual]). */
    fun check(a: Activity, manual: Boolean = false) {
        if (checkedThisRun && !manual) return
        checkedThisRun = true
        Thread {
            val latest = try {
                open(BASE + "version.txt").inputStream.bufferedReader().use { it.readText().trim().toLongOrNull() }
            } catch (e: Exception) { null }
            val mine = installedVersion(a)
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                when {
                    latest == null -> if (manual) info(a, "Couldn't check for updates", "Check the internet connection and try again.")
                    latest <= mine -> if (manual) info(a, "Up to date", "You have the newest version of DhruvVishu.")
                    else -> startUpdate(a)
                }
            }
        }.start()
    }

    private fun info(a: Activity, title: String, msg: String) {
        AlertDialog.Builder(a).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun startUpdate(a: Activity) {
        // Android needs permission once to install apps that don't come from the Play Store.
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a).setTitle("A new version is ready")
                .setMessage("To update by itself, DhruvVishu needs permission to install updates. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                    } catch (e: Exception) {
                        info(a, "Settings not found", "Open Settings → Apps → Special app access → Install unknown apps, and allow DhruvVishu.")
                    }
                    checkedThisRun = false // check again when the player comes back
                }
                .setNegativeButton("Later", null).show()
            return
        }
        val progress = AlertDialog.Builder(a).setTitle("Updating DhruvVishu")
            .setMessage("Downloading the new version… Your worlds are kept.").setCancelable(false).show()
        Thread {
            val result = try {
                val file = File(a.cacheDir, "update.apk")
                open(BASE + "DhruvVishu.apk").inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                install(a, file)
                null
            } catch (e: Exception) { e.message ?: "download failed" }
            a.runOnUiThread {
                progress.dismiss()
                if (result != null) info(a, "Update failed", "Couldn't download the update ($result). It will try again next time.")
            }
        }.start()
    }

    /** Hands the downloaded APK to Android's installer; it replies through [handleStatus]. */
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

    /** Called with the installer's reply: shows the "Install" screen, or reports a problem. */
    fun handleStatus(a: Activity, intent: Intent?): Boolean {
        if (intent?.action != ACTION_STATUS) return false
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) try { a.startActivity(confirm) } catch (e: Exception) { }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                info(a, "Update failed", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update couldn't be installed.")
            }
        }
        return true
    }
}
