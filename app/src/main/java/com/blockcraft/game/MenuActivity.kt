package com.blockcraft.game

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
import com.blockcraft.game.ui.BlockIcons
import com.blockcraft.game.ui.dpi
import com.blockcraft.game.ui.menuButton
import com.blockcraft.game.world.Tiles
import java.io.File

class MenuActivity : Activity() {
    private lateinit var playButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        // Tiled, darkened dirt background generated from the texture atlas.
        val tile = BlockIcons.tile(Tiles.DIRT)
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
            text = "BlockCraft"
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

        playButton = menuButton(this, "Play") { startGame(null) }
        col.addView(playButton, LinearLayout.LayoutParams(dpi(320f), -2).apply { bottomMargin = dpi(10f) })
        col.addView(menuButton(this, "New world") { newWorld() }, LinearLayout.LayoutParams(dpi(320f), -2).apply { bottomMargin = dpi(10f) })
        col.addView(menuButton(this, "How to play") { help() }, LinearLayout.LayoutParams(dpi(320f), -2))

        root.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        playButton.text = if (hasWorld()) "Continue world" else "Play"
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    private fun hasWorld() = File(GameActivity.worldDir(this), "level.dat").exists()

    private fun startGame(seed: Long?) {
        val i = Intent(this, GameActivity::class.java)
        if (seed != null) i.putExtra(GameActivity.EXTRA_SEED, seed)
        startActivity(i)
    }

    private fun newWorld() {
        val seedInput = EditText(this).apply {
            hint = "Seed (leave empty for random)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val msg = if (hasWorld()) "This replaces your current world." else "Create a fresh world."
        AlertDialog.Builder(this)
            .setTitle("New world")
            .setMessage(msg)
            .setView(seedInput)
            .setPositiveButton("Create") { _, _ ->
                GameActivity.worldDir(this).deleteRecursively()
                val text = seedInput.text.toString().trim()
                val seed = when {
                    text.isEmpty() -> System.nanoTime()
                    else -> text.toLongOrNull() ?: text.hashCode().toLong()
                }
                startGame(seed)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                    "• Tap a hotbar slot to select it, ••• opens all blocks.\n" +
                    "• Your world saves automatically when you leave."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
