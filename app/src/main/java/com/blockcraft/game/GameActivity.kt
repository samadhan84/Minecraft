package com.blockcraft.game

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.blockcraft.game.engine.Game
import com.blockcraft.game.engine.GameInput
import com.blockcraft.game.render.GameRenderer
import com.blockcraft.game.ui.CrosshairView
import com.blockcraft.game.ui.HotbarView
import com.blockcraft.game.ui.HudButton
import com.blockcraft.game.ui.InventoryView
import com.blockcraft.game.ui.JoystickView
import com.blockcraft.game.ui.dp
import com.blockcraft.game.ui.dpi
import com.blockcraft.game.ui.menuButton
import com.blockcraft.game.world.Blocks
import com.blockcraft.game.world.LevelData
import com.blockcraft.game.world.World
import java.io.File
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.abs

class GameActivity : Activity() {
    companion object {
        const val EXTRA_SEED = "seed"
        fun worldDir(activity: Activity) = File(activity.filesDir, "world")
    }

    private lateinit var glView: GLSurfaceView
    private lateinit var game: Game
    private lateinit var world: World
    private lateinit var level: LevelData
    private val input = GameInput()
    private lateinit var hotbar: HotbarView
    private lateinit var stats: TextView
    private lateinit var toast: TextView
    private lateinit var pauseMenu: LinearLayout
    private lateinit var inventory: InventoryView
    private lateinit var flyButton: HudButton
    private lateinit var downButton: HudButton
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dir = worldDir(this)
        level = LevelData.read(dir) ?: LevelData(intent.getLongExtra(EXTRA_SEED, System.currentTimeMillis()))
        world = World(level.seed, dir)
        game = Game(world, level, input, dir)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(DepthConfigChooser())
            preserveEGLContextOnPause = true
            setRenderer(GameRenderer(game) { text -> runOnUiThread { onStats(text) } })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener(LookTouchHandler())
        }

        root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        root.addView(CrosshairView(this), FrameLayout.LayoutParams(-1, -1))
        buildHud()
        setContentView(root)
        hideSystemUi()
    }

    private fun lp(w: Float, h: Float, gravity: Int, l: Float = 0f, t: Float = 0f, r: Float = 0f, b: Float = 0f) =
        FrameLayout.LayoutParams(if (w < 0) w.toInt() else dpi(w), if (h < 0) h.toInt() else dpi(h), gravity).apply {
            setMargins(dpi(l), dpi(t), dpi(r), dpi(b))
        }

    private fun buildHud() {
        // Movement
        root.addView(JoystickView(this, input), lp(200f, 200f, Gravity.BOTTOM or Gravity.START, l = 8f, b = 52f))

        val jump = HudButton(this, "▲") { input.jumpHeld = it }
        root.addView(jump, lp(76f, 76f, Gravity.BOTTOM or Gravity.END, r = 24f, b = 64f))
        downButton = HudButton(this, "▼") { input.descendHeld = it }
        root.addView(downButton, lp(64f, 64f, Gravity.BOTTOM or Gravity.END, r = 112f, b = 64f))
        downButton.visibility = View.GONE

        flyButton = HudButton(this, "FLY") { if (it) input.actions.add(GameInput.Action.TOGGLE_FLY) }
        root.addView(flyButton, lp(56f, 44f, Gravity.TOP or Gravity.END, t = 12f, r = 76f))
        val menu = HudButton(this, "II") { if (it) handler.post { showPause(true) } }
        root.addView(menu, lp(52f, 44f, Gravity.TOP or Gravity.END, t = 12f, r = 16f))

        // Hotbar + inventory button
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hotbar = HotbarView(this, level.hotbar) { slot -> selectSlot(slot) }
        hotbar.selected = level.selectedSlot
        bar.addView(hotbar, LinearLayout.LayoutParams(dpi(9 * 42f), dpi(42f)))
        val inv = HudButton(this, "•••") { if (it) handler.post { showInventory(true) } }
        bar.addView(inv, LinearLayout.LayoutParams(dpi(46f), dpi(42f)).apply { leftMargin = dpi(4f) })
        root.addView(bar, lp(-2f, -2f, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 4f))

        stats = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        root.addView(stats, lp(-2f, -2f, Gravity.TOP or Gravity.START, l = 12f, t = 8f))

        toast = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            setShadowLayer(2f, 2f, 2f, Color.BLACK)
            alpha = 0f
        }
        root.addView(toast, lp(-2f, -2f, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 56f))

        inventory = InventoryView(this) { id ->
            if (id != null) {
                level.hotbar[hotbar.selected] = id
                selectSlot(hotbar.selected)
                hotbar.invalidate()
            }
            showInventory(false)
        }
        inventory.visibility = View.GONE
        root.addView(inventory, FrameLayout.LayoutParams(-1, -1))

        pauseMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            isClickable = true
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            text = "Game menu"; setTextColor(Color.WHITE); textSize = 24f
            setShadowLayer(2f, 3f, 3f, Color.DKGRAY); gravity = Gravity.CENTER
        }
        pauseMenu.addView(title, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dpi(16f) })
        fun addMenu(label: String, action: (TextView) -> Unit) {
            lateinit var b: TextView
            b = menuButton(this, label) { action(b) }
            pauseMenu.addView(b, LinearLayout.LayoutParams(dpi(300f), -2).apply { bottomMargin = dpi(10f) })
        }
        addMenu("Back to game") { showPause(false) }
        addMenu("Render distance: ${game.renderDistance}") { b ->
            val next = when (game.renderDistance) { 4 -> 6; 6 -> 8; 8 -> 10; else -> 4 }
            glView.queueEvent { game.renderDistance = next }
            b.text = "Render distance: $next"
        }
        addMenu("Skip to next morning / night") {
            glView.queueEvent { game.timeOfDay = if (game.daylight > 0.5f) 0.52f else 0.0f }
            showPause(false)
        }
        addMenu("Save and quit") { finish() }
        root.addView(pauseMenu, FrameLayout.LayoutParams(-1, -1))
    }

    private fun selectSlot(slot: Int) {
        level.selectedSlot = slot
        val id = level.hotbar[slot]
        input.selectedBlock = id
        toast.text = Blocks[id].name
        toast.animate().cancel()
        toast.alpha = 1f
        toast.animate().alpha(0f).setStartDelay(1200).setDuration(500).start()
    }

    private fun showInventory(show: Boolean) {
        inventory.visibility = if (show) View.VISIBLE else View.GONE
        if (show) releaseInputs()
    }

    private fun showPause(show: Boolean) {
        pauseMenu.visibility = if (show) View.VISIBLE else View.GONE
        if (show) releaseInputs()
    }

    private fun releaseInputs() {
        input.moveForward = 0f; input.moveStrafe = 0f
        input.jumpHeld = false; input.descendHeld = false; input.breakHeld = false
    }

    private fun onStats(text: String) {
        stats.text = text
        val flying = game.player.flying
        flyButton.toggled = flying
        downButton.visibility = if (flying) View.VISIBLE else View.GONE
    }

    /** Drag to look, tap to place, touch-and-hold to mine. */
    private inner class LookTouchHandler : View.OnTouchListener {
        private var pointer = -1
        private var lastX = 0f; private var lastY = 0f
        private var downTime = 0L
        private var moved = 0f
        private var mining = false
        private val slop = dp(10f)
        private val startMining = Runnable { if (pointer != -1) { mining = true; input.breakHeld = true } }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (pointer == -1) {
                    val i = e.actionIndex
                    pointer = e.getPointerId(i)
                    lastX = e.getX(i); lastY = e.getY(i)
                    downTime = SystemClock.uptimeMillis()
                    moved = 0f; mining = false
                    handler.postDelayed(startMining, 260)
                }
                MotionEvent.ACTION_MOVE -> {
                    val i = e.findPointerIndex(pointer)
                    if (i >= 0) {
                        val dx = e.getX(i) - lastX; val dy = e.getY(i) - lastY
                        lastX = e.getX(i); lastY = e.getY(i)
                        moved += abs(dx) + abs(dy)
                        if (moved > slop && !mining) handler.removeCallbacks(startMining)
                        val d = resources.displayMetrics.density
                        input.addLook(dx / d, dy / d)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val lifted = e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex) == pointer
                    if (lifted) {
                        handler.removeCallbacks(startMining)
                        val quick = SystemClock.uptimeMillis() - downTime < 260
                        if (!mining && quick && moved < slop && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                            input.actions.add(GameInput.Action.PLACE)
                        }
                        mining = false
                        input.breakHeld = false
                        pointer = -1
                    }
                }
            }
            return true
        }
    }

    /** Prefers a 24-bit depth buffer (less z-fighting), falls back to 16-bit. */
    private class DepthConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            for (depth in intArrayOf(24, 16)) {
                val attribs = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
                    EGL10.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0) return configs[0]!!
            }
            throw IllegalStateException("No suitable EGL config")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inventory.visibility == View.VISIBLE -> showInventory(false)
            pauseMenu.visibility == View.VISIBLE -> showPause(false)
            else -> showPause(true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        releaseInputs()
        glView.onPause() // blocks until the GL thread has paused, so saving below is safe
        game.save()
        super.onPause()
    }

    override fun onDestroy() {
        world.shutdown()
        super.onDestroy()
    }
}
