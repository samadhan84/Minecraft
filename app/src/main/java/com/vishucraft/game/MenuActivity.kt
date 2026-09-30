package com.vishucraft.game

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vishucraft.game.ui.dpi
import com.vishucraft.game.ui.menuButton
import com.vishucraft.game.ui.settingsDialog
import java.io.File

class MenuActivity : Activity() {
    private lateinit var playButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        // The DhruvVishu castle picture fills the screen; it already shows the game's name.
        root.addView(android.widget.ImageView(this).apply {
            setImageResource(resources.getIdentifier("menu_background", "drawable", packageName))
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, -1))

        // The buttons sit in a stone-and-wood panel on the left so the castle and the logo stay in view.
        val pixel = try { Typeface.createFromAsset(assets, "fonts/PressStart2P.ttf") } catch (e: Exception) { Typeface.MONOSPACE }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(170, 28, 22, 16)); cornerRadius = dpi(6f).toFloat()
                setStroke(dpi(3f), Color.rgb(122, 84, 44))
            }
        }
        fun add(label: String, last: Boolean = false, onClick: () -> Unit): TextView {
            val b = homeButton(label, pixel, onClick)
            col.addView(b, LinearLayout.LayoutParams(dpi(212f), dpi(38f)).apply { if (!last) bottomMargin = dpi(8f) })
            return b
        }

        playButton = add("Play") { if (hasWorld()) showWorlds() else newWorld() }
        add("Join online game") { joinOnline() }
        add("Join Wi-Fi game") { joinGame() }
        add("Settings") { settingsDialog(this) }
        add("Back up / restore") { backupMenu() }
        if (Updater.enabled(this)) add("Check for updates") { Updater.check(this, manual = true) }
        add("How to play", last = true) { help() }

        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            setMargins(dpi(16f), dpi(12f), 0, dpi(12f))
        })

        // The installed version, bottom right (the same number the updater compares).
        val version = TextView(this).apply {
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            text = "Version ${info.versionName} ($code)"
            typeface = pixel
            textSize = 9f
            setTextColor(Color.rgb(255, 244, 214))
            setShadowLayer(0.01f, dpi(2f).toFloat(), dpi(2f).toFloat(), Color.rgb(30, 22, 12))
            setPadding(dpi(8f), dpi(5f), dpi(8f), dpi(5f))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(150, 28, 22, 16)); cornerRadius = dpi(4f).toFloat()
            }
        }
        root.addView(version, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dpi(14f), dpi(12f))
        })
        setContentView(root)
        playButton.requestFocus()
        CrashReporter.install(this)
    }

    /**
     * A menu button in the style of the title picture: grey stone with a dark wooden edge and cream pixel
     * lettering; the focused / pressed one glows gold (easy to follow with a TV remote).
     */
    private fun homeButton(label: String, font: Typeface, onClick: () -> Unit): TextView = menuButton(this, label, onClick).apply {
        typeface = font
        textSize = 10f
        setTextColor(Color.rgb(255, 244, 214))
        setShadowLayer(0.01f, dpi(2f).toFloat(), dpi(2f).toFloat(), Color.rgb(40, 28, 14))
        setPadding(dpi(8f), 0, dpi(8f), 0)
        fun state(fill: Int, border: Int, width: Float) = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill); setStroke(dpi(width), border); cornerRadius = dpi(3f).toFloat()
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), state(Color.rgb(196, 150, 60), Color.rgb(255, 220, 90), 3f))
            addState(intArrayOf(android.R.attr.state_focused), state(Color.rgb(150, 112, 56), Color.rgb(255, 214, 70), 3f))
            addState(intArrayOf(), state(Color.rgb(108, 108, 112), Color.rgb(70, 46, 22), 3f))
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Updater.handleStatus(this, intent)
    }

    override fun onResume() {
        super.onResume()
        // The game updates itself: a newer build is downloaded and Android asks to install it.
        if (!Updater.handleStatus(this, intent.also { setIntent(Intent()) })) Updater.attach(this)
        CrashReporter.takeReport(this)?.let { report ->
            AlertDialog.Builder(this)
                .setTitle("The game stopped last time")
                .setMessage("Sorry! Please send this to the developer:\n\n$report")
                .setPositiveButton("OK", null)
                .show()
        }
        migrateOldWorld()
        limitOver()
        playButton.text = if (hasWorld()) "Play" else "Create world"
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    override fun onPause() {
        Updater.detach(this)
        super.onPause()
    }

    // ---------------------------------------------------------------- backup

    private val requestBackup = 41
    private val requestRestore = 42

    /** Save all worlds to a zip file the player chooses (e.g. in Downloads), or bring them back from one. */
    private fun backupMenu() {
        buttonDialog("Back up / restore worlds", listOf(
            "Back up all worlds to a file" to {
                val name = "DhruvVishu-worlds-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, name)
                try { startActivityForResult(i, requestBackup) } catch (e: Exception) { noFilePicker() }
            },
            "Restore worlds from a file" to {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                try { startActivityForResult(i, requestRestore) } catch (e: Exception) { noFilePicker() }
            },
        ))
    }

    private fun noFilePicker() {
        AlertDialog.Builder(this).setTitle("No file picker")
            .setMessage("This device has no file picker. Your worlds are still kept safe when the app is updated.")
            .setPositiveButton("OK", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val root = GameActivity.worldsRoot(this)
        Thread {
            val msg = try {
                if (requestCode == requestBackup) {
                    val n = contentResolver.openOutputStream(uri)!!.use { com.vishucraft.game.world.Backup.write(root, it) }
                    "Saved $n world${if (n == 1) "" else "s"} to the backup file."
                } else {
                    val names = contentResolver.openInputStream(uri)!!.use { com.vishucraft.game.world.Backup.restore(it, root) }
                    if (names.isEmpty()) "No worlds found in that file." else "Restored ${names.size} world${if (names.size == 1) "" else "s"}."
                }
            } catch (e: Exception) { "Something went wrong: ${e.message}" }
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Backup").setMessage(msg).setPositiveButton("OK", null).show()
                playButton.text = if (hasWorld()) "Play" else "Create world"
            }
        }.start()
    }

    /** Moves the single world from older versions into the save-slot folder. */
    private fun migrateOldWorld() {
        val old = File(filesDir, "world")
        if (old.exists() && File(old, "level.dat").exists()) {
            val target = File(GameActivity.worldsRoot(this), "world1")
            if (!target.exists()) old.renameTo(target)
        }
    }

    private class WorldInfo(val dir: File, val level: com.vishucraft.game.world.LevelData)

    private fun worlds(): List<WorldInfo> = GameActivity.worldsRoot(this).listFiles().orEmpty()
        .mapNotNull { d -> com.vishucraft.game.world.LevelData.read(d)?.let { WorldInfo(d, it) } }
        .sortedByDescending { File(it.dir, "level.dat").lastModified() }

    private fun hasWorld() = worlds().isNotEmpty()

    /** True (after saying so) when today's 45 minutes of play are used up (see engine/PlayLimit). */
    private fun limitOver(): Boolean {
        if (com.vishucraft.game.ui.Settings(this).playLimit().minutesLeft() > 0) return false
        AlertDialog.Builder(this).setTitle(com.vishucraft.game.engine.PlayLimit.TITLE)
            .setMessage(com.vishucraft.game.engine.PlayLimit.MESSAGE).setPositiveButton("OK", null).show()
        return true
    }

    private fun startGame(dirName: String, seed: Long? = null, name: String? = null, survival: Boolean = false) {
        if (limitOver()) return
        val i = Intent(this, GameActivity::class.java)
        i.putExtra(GameActivity.EXTRA_WORLD, dirName)
        if (seed != null) i.putExtra(GameActivity.EXTRA_SEED, seed)
        if (name != null) i.putExtra(GameActivity.EXTRA_NAME, name)
        i.putExtra(GameActivity.EXTRA_MODE, survival)
        startActivity(i)
    }

    /** A dialog with a vertical list of focusable buttons. */
    private fun buttonDialog(title: String, buttons: List<Pair<String, () -> Unit>>): AlertDialog {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(4f))
        }
        lateinit var dialog: AlertDialog
        for ((label, action) in buttons) {
            col.addView(menuButton(this, label) { dialog.dismiss(); action() },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(8f) })
        }
        dialog = AlertDialog.Builder(this).setTitle(title)
            .setView(android.widget.ScrollView(this).apply { addView(col) })
            .setNegativeButton("Back", null).create()
        dialog.show()
        col.getChildAt(0)?.requestFocus()
        return dialog
    }

    private fun showWorlds() {
        val list = worlds()
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        buttons.add("+  Create new world" to { newWorld() })
        for (w in list) {
            val mode = if (w.level.mode == com.vishucraft.game.world.GameMode.SURVIVAL) "Survival" else "Creative"
            buttons.add("${w.level.name}  ·  $mode" to { startGame(w.dir.name) })
        }
        if (list.isNotEmpty()) buttons.add("Delete a world…" to { deleteWorld() })
        buttonDialog("Select world", buttons)
    }

    private fun deleteWorld() {
        buttonDialog("Delete which world?", worlds().map { w ->
            "Delete \"${w.level.name}\"" to {
                AlertDialog.Builder(this).setTitle("Delete ${w.level.name}?")
                    .setMessage("This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> w.dir.deleteRecursively(); onResume() }
                    .setNegativeButton("Cancel", null).show()
                Unit
            }
        })
    }

    /** Looks for games on the local Wi-Fi, or lets the player type an address. */
    private fun joinGame() {
        val scanning = AlertDialog.Builder(this).setTitle("Join Wi-Fi game").setMessage("Looking for games on your Wi-Fi…")
            .setNegativeButton("Cancel", null).show()
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
        val lock = wifi?.createMulticastLock("vishucraft")?.apply { setReferenceCounted(false); acquire() }
        Thread {
            val hosts = com.vishucraft.game.net.Discovery.scan(2500)
            lock?.release()
            runOnUiThread {
                if (!scanning.isShowing) return@runOnUiThread
                scanning.dismiss()
                val buttons = ArrayList<Pair<String, () -> Unit>>()
                for (h in hosts) buttons.add("${h.name}  ·  ${h.players} playing" to { connect(h.address) })
                buttons.add("Enter address…" to { enterAddress() })
                buttonDialog(if (hosts.isEmpty()) "No games found" else "Games on your Wi-Fi", buttons)
            }
        }.start()
    }

    /** Joins a friend's game anywhere with the room code they see. */
    private fun joinOnline() {
        if (limitOver()) return
        val input = EditText(this).apply { hint = "Room code (6 numbers)"; inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle("Join online game").setView(input)
            .setMessage("Ask your friend to open their game with \"Play online (room code)\" in the game menu, then type the code they see.")
            .setPositiveButton("Join") { _, _ -> joinRoom(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
        input.requestFocus()
    }

    private fun joinRoom(code: String) {
        val wait = AlertDialog.Builder(this).setTitle("Joining…").setMessage("Looking for room ${code.trim()}").show()
        val name = com.vishucraft.game.ui.Settings(this).playerName
        Thread {
            var tunnel: com.vishucraft.game.net.GuestTunnel? = null
            val result = try {
                tunnel = com.vishucraft.game.net.GuestTunnel.join(code)
                Result.success(com.vishucraft.game.net.ClientSession.connect(tunnel.localAddress, name, 12000))
            } catch (e: Exception) { tunnel?.close(); Result.failure(e) }
            runOnUiThread {
                wait.dismiss()
                result.onSuccess {
                    com.vishucraft.game.net.Net.pendingClient = it
                    startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_JOIN, true))
                }.onFailure {
                    AlertDialog.Builder(this).setTitle("Could not join").setMessage(it.message ?: "Check the code and your internet connection.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun enterAddress() {
        val input = EditText(this).apply { hint = "e.g. 192.168.1.23 or a VPN / internet address"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        AlertDialog.Builder(this).setTitle("Host address").setView(input)
            .setPositiveButton("Join") { _, _ -> connect(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
        input.requestFocus()
    }

    private fun connect(address: String) {
        if (limitOver()) return
        val wait = AlertDialog.Builder(this).setTitle("Joining…").setMessage("Connecting to $address").show()
        val name = com.vishucraft.game.ui.Settings(this).playerName
        Thread {
            val result = try { com.vishucraft.game.net.ClientSession.connect(address, name) } catch (e: Exception) { null }
            runOnUiThread {
                wait.dismiss()
                if (result == null) {
                    AlertDialog.Builder(this).setTitle("Could not join")
                        .setMessage("No game answered at $address. Make sure both devices are on the same Wi-Fi and the host chose \"Open to Wi-Fi\".")
                        .setPositiveButton("OK", null).show()
                } else {
                    com.vishucraft.game.net.Net.pendingClient = result
                    startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_JOIN, true))
                }
            }
        }.start()
    }

    private fun newWorld() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(8f), dpi(20f), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "World name"
            setText("World ${worlds().size + 1}")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val seedInput = EditText(this).apply {
            hint = "Seed (leave empty for random)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        var survival = true
        lateinit var modeButton: TextView
        modeButton = menuButton(this, "Game mode: Survival") {
            survival = !survival
            modeButton.text = if (survival) "Game mode: Survival" else "Game mode: Creative (flat world)"
        }
        col.addView(nameInput); col.addView(seedInput)
        col.addView(modeButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8f) })
        AlertDialog.Builder(this)
            .setTitle("Create new world")
            .setView(col)
            .setPositiveButton("Create") { _, _ ->
                val text = seedInput.text.toString().trim()
                val seed = when {
                    text.isEmpty() -> System.nanoTime()
                    else -> text.toLongOrNull() ?: text.hashCode().toLong()
                }
                var n = 1
                while (File(GameActivity.worldsRoot(this), "world$n").exists()) n++
                val name = nameInput.text.toString().trim().ifEmpty { "World $n" }
                startGame("world$n", seed, name, survival)
            }
            .setNegativeButton("Cancel", null)
            .show()
        modeButton.requestFocus()
    }

    private fun help() {
        AlertDialog.Builder(this)
            .setTitle("How to play")
            .setMessage(
                "• Left thumb: joystick to walk (you auto-jump up single blocks).\n" +
                    "• Drag anywhere else to look around.\n" +
                    "• Tap to place the selected block.\n" +
                    "• Touch and hold to mine the block under the crosshair.\n" +
                    "• ▲ jumps / swims / flies up, ▼ flies down.\n" +
                    "• FLY toggles creative flight.\n" +
                    "• Tap a hotbar slot to select it, ••• opens your inventory.\n" +
                    "• Survival: punch trees for logs, craft planks, sticks, a crafting table and tools. " +
                    "Smelt ore in a furnace. Eat food to refill hunger. Armor protects you.\n" +
                    "• Your world saves automatically when you leave."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
