package com.vishucraft.game

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vishucraft.game.ui.BlockIcons
import com.vishucraft.game.ui.dpi
import com.vishucraft.game.ui.menuButton
import com.vishucraft.game.ui.settingsDialog
import com.vishucraft.game.world.Tiles
import java.io.File

class MenuActivity : Activity() {
    private lateinit var playButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        // Tiled, darkened dirt background generated from the texture atlas.
        val tile = BlockIcons.tile(Tiles.id("dirt"))
        val big = Bitmap.createScaledBitmap(tile, dpi(48f), dpi(48f), false)
        root.background = BitmapDrawable(resources, big).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            setColorFilter(Color.rgb(110, 110, 110), android.graphics.PorterDuff.Mode.MULTIPLY)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "VishuCraft"
            textSize = 54f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.rgb(230, 230, 230))
            setShadowLayer(0.01f, dpi(4f).toFloat(), dpi(4f).toFloat(), Color.rgb(50, 50, 50))
            gravity = Gravity.CENTER
        }
        col.addView(title, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dpi(4f) })
        val subtitle = TextView(this).apply {
            text = "Build anything. Explore forever."
            textSize = 15f
            setTextColor(Color.rgb(255, 255, 110))
            gravity = Gravity.CENTER
        }
        col.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dpi(24f) })

        playButton = menuButton(this, "Play") { if (hasWorld()) showWorlds() else newWorld() }
        col.addView(playButton, LinearLayout.LayoutParams(dpi(320f), -2).apply { bottomMargin = dpi(10f) })
        col.addView(menuButton(this, "Join Wi-Fi game") { joinGame() }, LinearLayout.LayoutParams(dpi(320f), -2).apply { bottomMargin = dpi(10f) })
        col.addView(menuButton(this, "Settings") { settingsDialog(this) }, LinearLayout.LayoutParams(dpi(320f), -2).apply { bottomMargin = dpi(10f) })
        col.addView(menuButton(this, "How to play") { help() }, LinearLayout.LayoutParams(dpi(320f), -2))

        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        playButton.requestFocus()
        CrashReporter.install(this)
    }

    override fun onResume() {
        super.onResume()
        CrashReporter.takeReport(this)?.let { report ->
            AlertDialog.Builder(this)
                .setTitle("The game stopped last time")
                .setMessage("Sorry! Please send this to the developer:\n\n$report")
                .setPositiveButton("OK", null)
                .show()
        }
        migrateOldWorld()
        playButton.text = if (hasWorld()) "Play" else "Create world"
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
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

    private fun startGame(dirName: String, seed: Long? = null, name: String? = null, survival: Boolean = false) {
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

    private fun enterAddress() {
        val input = EditText(this).apply { hint = "e.g. 192.168.1.23"; inputType = InputType.TYPE_CLASS_PHONE }
        AlertDialog.Builder(this).setTitle("Host address").setView(input)
            .setPositiveButton("Join") { _, _ -> connect(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
        input.requestFocus()
    }

    private fun connect(address: String) {
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
            modeButton.text = if (survival) "Game mode: Survival" else "Game mode: Creative"
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
