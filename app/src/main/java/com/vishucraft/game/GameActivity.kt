package com.vishucraft.game

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.vishucraft.game.engine.Game
import com.vishucraft.game.engine.GameInput
import com.vishucraft.game.render.GameRenderer
import com.vishucraft.game.ui.CrosshairView
import com.vishucraft.game.ui.HotbarView
import com.vishucraft.game.ui.HudButton
import com.vishucraft.game.ui.InventoryView
import com.vishucraft.game.ui.JoystickView
import com.vishucraft.game.ui.dp
import com.vishucraft.game.ui.dpi
import com.vishucraft.game.ui.menuButton
import android.widget.ImageView
import com.vishucraft.game.ui.BlockIcons
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.LevelData
import com.vishucraft.game.world.GameMode
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.ui.ContainerScreen
import com.vishucraft.game.ui.Settings
import com.vishucraft.game.ui.StatusView
import com.vishucraft.game.ui.settingsDialog
import com.vishucraft.game.world.World
import java.io.File
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.abs

class GameActivity : Activity() {
    companion object {
        const val EXTRA_SEED = "seed"
        const val EXTRA_WORLD = "world"
        const val EXTRA_NAME = "name"
        const val EXTRA_MODE = "mode"
        const val EXTRA_JOIN = "join"

        fun worldsRoot(activity: Activity) = File(activity.filesDir, "worlds").apply { mkdirs() }

        const val CONTROLS_HELP =
            "TV remote:\n" +
                "• Up / Down: walk forward / back\n" +
                "• Left / Right: turn\n" +
                "• Channel + / Channel −: look up / down\n" +
                "• OK: place, use, attack. Hold OK: mine\n" +
                "• Menu: block inventory\n" +
                "• Rewind / Fast-forward: previous / next hotbar slot\n" +
                "• Back: pause menu\n\n" +
                "Gamepad:\n" +
                "• Left stick: move, right stick: look\n" +
                "• RT: mine, LT: place / attack\n" +
                "• A: jump / fly up, left stick click: fly down\n" +
                "• X: toggle flying, Y: inventory\n" +
                "• LB / RB: hotbar slot, B or Start: pause\n\n" +
                "Keyboard:\n" +
                "• W A S D: move, arrows: turn, R / V: look up / down\n" +
                "• Space: jump, C or Shift: fly down, F: fly\n" +
                "• Enter or K: place, J: mine, E: inventory, 1-9 / Q / Tab: slots"
    }

    private lateinit var glView: GLSurfaceView
    private lateinit var game: Game
    private lateinit var world: World
    private lateinit var level: LevelData
    private val input = GameInput()
    private lateinit var hotbar: HotbarView
    private lateinit var stats: TextView
    private lateinit var toast: TextView
    private lateinit var hand: ImageView
    private lateinit var hurtFlash: View
    private lateinit var pauseMenu: LinearLayout
    private lateinit var inventory: InventoryView
    private lateinit var flyButton: HudButton
    private lateinit var downButton: HudButton
    private lateinit var root: FrameLayout
    private lateinit var settings: Settings
    private lateinit var worldDirForDim: File
    private lateinit var sounds: com.vishucraft.game.audio.Sounds
    private lateinit var status: StatusView
    private lateinit var screen: ContainerScreen
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val client = if (intent.getBooleanExtra(EXTRA_JOIN, false)) com.vishucraft.game.net.Net.pendingClient else null
        val dir = File(worldsRoot(this), intent.getStringExtra(EXTRA_WORLD) ?: "world1")
        level = if (client != null) LevelData.create(client.seed, client.worldName, client.mode).apply {
            hasPlayer = true; x = client.spawnX + 1.5f; y = client.spawnY + 0.5f; z = client.spawnZ + 0.5f; timeOfDay = client.time
        } else LevelData.read(dir) ?: LevelData.create(
            intent.getLongExtra(EXTRA_SEED, System.currentTimeMillis()),
            intent.getStringExtra(EXTRA_NAME) ?: "My World",
            if (intent.getBooleanExtra(EXTRA_MODE, false)) GameMode.SURVIVAL else GameMode.CREATIVE,
        )
        // Each dimension keeps its own chunks in a sub-folder of the world.
        val dimDir = when (level.dimension) {
            com.vishucraft.game.world.Dimension.OVERWORLD -> dir
            com.vishucraft.game.world.Dimension.EMBER -> File(dir, "ember")
            com.vishucraft.game.world.Dimension.SKY -> File(dir, "sky")
        }
        world = World(level.seed, if (client != null) null else dimDir, level.dimension)
        if (client != null) world.remoteLoader = { cx, cz -> client.requestChunk(cx, cz) }
        game = Game(world, level, input, if (client != null) null else dir)
        worldDirForDim = dir
        if (client != null) { game.net = client; com.vishucraft.game.net.Net.pendingClient = null }
        settings = Settings(this)
        sounds = com.vishucraft.game.audio.Sounds(this)
        game.soundSink = { name, x, y, z, gain ->
            if (name.startsWith("mat:")) sounds.play(com.vishucraft.game.audio.Sounds.material(name.substring(4).toInt()), x, y, z, gain, 0.9f + Math.random().toFloat() * 0.2f)
            else sounds.play(name, x, y, z, gain)
        }
        game.listener = { x, y, z, yaw -> sounds.setListener(x, y, z, yaw) }
        applySettings()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(DepthConfigChooser())
            preserveEGLContextOnPause = true
            setRenderer(GameRenderer(game, { e -> runOnUiThread { onGameEvent(e) } }) { text -> runOnUiThread { onStats(text) } })
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
        val touch = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        if (touch) root.addView(JoystickView(this, input), lp(200f, 200f, Gravity.BOTTOM or Gravity.START, l = 8f, b = 52f))

        val jump = HudButton(this, "▲") { input.jumpHeld = it }
        if (touch) root.addView(jump, lp(76f, 76f, Gravity.BOTTOM or Gravity.END, r = 24f, b = 64f))
        downButton = HudButton(this, "▼") { input.descendHeld = it }
        if (touch) root.addView(downButton, lp(64f, 64f, Gravity.BOTTOM or Gravity.END, r = 112f, b = 64f))
        downButton.visibility = View.GONE

        flyButton = HudButton(this, "FLY") { if (it) input.actions.add(GameInput.Action.TOGGLE_FLY) }
        if (!game.survival) root.addView(flyButton, lp(56f, 44f, Gravity.TOP or Gravity.END, t = 12f, r = 76f))
        val menu = HudButton(this, "II") { if (it) handler.post { showPause(true) } }
        root.addView(menu, lp(52f, 44f, Gravity.TOP or Gravity.END, t = 12f, r = 16f))

        // Hotbar + inventory button
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hotbar = HotbarView(this, game.inventory, game.survival) { slot -> selectSlot(slot) }
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
            gravity = Gravity.CENTER
            alpha = 0f
        }
        root.addView(toast, lp(-2f, -2f, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 78f))

        status = StatusView(this)
        if (game.survival) root.addView(status, lp(9 * 42f, 36f, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, l = 0f, r = 50f, b = 50f))
        hurtFlash = View(this).apply { setBackgroundColor(Color.argb(110, 220, 0, 0)); alpha = 0f }
        root.addView(hurtFlash, 1, FrameLayout.LayoutParams(-1, -1))

        // The held block or tool, bottom right, swings when you mine or place.
        hand = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            rotation = -18f
            pivotX = dp(110f); pivotY = dp(110f)
        }
        root.addView(hand, 1, lp(110f, 110f, Gravity.BOTTOM or Gravity.END, r = 150f, b = 30f))
        updateHand()

        inventory = InventoryView(this) { id ->
            if (id != null) {
                val slot = hotbar.selected
                glView.queueEvent {
                    game.inventory.slots[slot] = ItemStack(id, 1)
                    runOnUiThread { selectSlot(slot); hotbar.invalidate() }
                }
            }
            showInventory(false)
        }
        inventory.visibility = View.GONE
        root.addView(inventory, FrameLayout.LayoutParams(-1, -1))

        screen = ContainerScreen(this, game, { action ->
            glView.queueEvent { action(game); runOnUiThread { screen.invalidate(); hotbar.invalidate(); updateHand() } }
        }) { updateHand() }
        screen.visibility = View.GONE
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))

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
        addMenu("Skip to next morning / night") {
            glView.queueEvent { game.timeOfDay = if (game.daylight > 0.5f) 0.52f else 0.0f }
            showPause(false)
        }
        addMenu("Mobs: Normal") { b ->
            val hostile = !game.mobs.hostileEnabled
            glView.queueEvent { game.mobs.hostileEnabled = hostile }
            b.text = if (hostile) "Mobs: Normal" else "Mobs: Peaceful (no monsters)"
        }
        addMenu("Weather: change") { b ->
            glView.queueEvent {
                val next = when { game.rain < 0.5f -> 1; !game.thunder -> 2; else -> 0 }
                game.setWeather(next > 0, next == 2)
            }
            b.text = "Weather: changing…"
            handler.postDelayed({ b.text = "Weather: change" }, 1500)
        }
        addMenu(if (game.isClient) "Wi-Fi: joined" else "Open to Wi-Fi") { b ->
            if (game.net != null) { showToast(game.net!!.status); return@addMenu }
            val name = settings.playerName
            glView.queueEvent {
                val result = try {
                    game.net = com.vishucraft.game.net.HostSession(game, level.name, name)
                    "Open! Friends on the same Wi-Fi can join from the title screen.\nYour address: ${com.vishucraft.game.net.Net.localAddress()}"
                } catch (e: Exception) { "Could not open the game: ${e.message}" }
                runOnUiThread { showToast(result); b.text = if (game.net != null) "Wi-Fi: open" else "Open to Wi-Fi" }
            }
        }
        addMenu("Settings") { settingsDialog(this) { applySettings() } }
        addMenu("Controls") { showControls() }
        addMenu("Save and quit") { finish() }
        root.addView(pauseMenu, FrameLayout.LayoutParams(-1, -1))
        if (!touch) handler.postDelayed({ showToast("Press MENU / Y for controls help in the pause menu (Back)") }, 800)
    }

    private fun updateHand() {
        val id = game.heldId()
        if (id == 0) { hand.setImageDrawable(null); return }
        val bmp = BlockIcons.get(id)
        hand.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp).apply { paint.isFilterBitmap = false })
    }

    fun swing() {
        hand.animate().cancel()
        hand.rotation = -18f
        hand.animate().rotation(-60f).setDuration(90).withEndAction {
            hand.animate().rotation(-18f).setDuration(140).start()
        }.start()
    }

    private val swingLoop = object : Runnable {
        override fun run() {
            if (!input.breakHeld) return
            swing()
            handler.postDelayed(this, 260)
        }
    }

    private fun selectSlot(slot: Int) {
        input.selectedSlot = slot
        val id = game.inventory.slots[slot]?.id ?: 0
        updateHand()
        if (id == 0) { showToast("Empty hand"); return }
        val ench = Items[id]?.enchantments
        toast.text = if (ench != null) "${Items.displayName(id)}\n$ench" else Items.displayName(id)
        toast.animate().cancel()
        toast.alpha = 1f
        toast.animate().alpha(0f).setStartDelay(1200).setDuration(500).start()
    }

    private fun showInventory(show: Boolean) {
        if (show && game.survival) { releaseInputs(); screen.open(ContainerScreen.Mode.INVENTORY); return }
        inventory.visibility = if (show) View.VISIBLE else View.GONE
        if (show) releaseInputs()
    }

    private fun applySettings() {
        game.lookScale = settings.sensitivity
        game.fov = settings.fov.toFloat()
        game.renderDistance = settings.renderDistance
        sounds.volume = settings.soundVolume / 100f
        sounds.musicVolume = settings.musicVolume / 100f
        if (::stats.isInitialized) stats.visibility = if (settings.showDebug) View.VISIBLE else View.GONE
    }

    private fun showPause(show: Boolean) {
        pauseMenu.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            releaseInputs()
            pauseMenu.getChildAt(1)?.requestFocus()
        }
    }

    private fun releaseInputs() {
        keyFwd = false; keyBack = false; keyLeft = false; keyRight = false
        keyTurnL = false; keyTurnR = false; keyLookU = false; keyLookD = false
        input.lookStickX = 0f; input.lookStickY = 0f
        input.moveForward = 0f; input.moveStrafe = 0f
        input.jumpHeld = false; input.descendHeld = false; input.breakHeld = false
    }

    private fun showControls() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Controls")
            .setMessage(CONTROLS_HELP)
            .setPositiveButton("OK", null)
            .show()
    }

    /** Saves, switches dimension and reloads the game screen in the new world. */
    private fun travel(target: com.vishucraft.game.world.Dimension) {
        val name = when (target) {
            com.vishucraft.game.world.Dimension.EMBER -> "the Ember Realm"
            com.vishucraft.game.world.Dimension.SKY -> "the Sky Isles"
            com.vishucraft.game.world.Dimension.OVERWORLD -> "the Overworld"
        }
        if (game.net != null) { showToast("Portals are closed during Wi-Fi games"); return }
        showToast("Travelling to $name…")
        glView.queueEvent {
            game.save()
            level.dimension = target
            level.arriving = true
            level.write(worldDirForDim)
            runOnUiThread { recreate() }
        }
    }

    private fun showToast(text: String) {
        toast.text = text
        toast.animate().cancel()
        toast.alpha = 1f
        toast.animate().alpha(0f).setStartDelay(1500).setDuration(500).start()
    }

    private fun onGameEvent(e: String) {
        when {
            e == "hurt" -> {
                hurtFlash.animate().cancel()
                hurtFlash.alpha = 1f
                hurtFlash.animate().alpha(0f).setDuration(350).start()
            }
            e == "died" -> showToast("You died! Respawning…")
            e == "sleep" -> {
                // Fade to black and back while the night passes.
                hurtFlash.animate().cancel()
                hurtFlash.setBackgroundColor(Color.BLACK)
                hurtFlash.alpha = 0f
                hurtFlash.animate().alpha(1f).setDuration(700).withEndAction {
                    hurtFlash.animate().alpha(0f).setStartDelay(500).setDuration(900).withEndAction {
                        hurtFlash.setBackgroundColor(Color.argb(110, 220, 0, 0))
                    }.start()
                }.start()
            }
            e.startsWith("toast:") -> showToast(e.removePrefix("toast:"))
            e == "open:craft" -> { releaseInputs(); screen.open(ContainerScreen.Mode.CRAFTING) }
            e.startsWith("dimension:") -> travel(com.vishucraft.game.world.Dimension.valueOf(e.removePrefix("dimension:")))
            e.startsWith("open:hopper:") -> {
                val (x, y, z) = e.substringAfterLast(':').split(',').map { it.toInt() }
                releaseInputs()
                screen.open(ContainerScreen.Mode.CHEST, chestEntity = world.blockEntities.hopper(x, y, z))
            }
            e.startsWith("open:chest:") || e.startsWith("open:furnace:") -> {
                val (x, y, z) = e.substringAfterLast(':').split(',').map { it.toInt() }
                releaseInputs()
                if (e.startsWith("open:chest:")) screen.open(ContainerScreen.Mode.CHEST, chestEntity = world.blockEntities.chest(x, y, z))
                else screen.open(ContainerScreen.Mode.FURNACE, furnaceEntity = world.blockEntities.furnace(x, y, z))
            }
            e == "craft" -> { sounds.play("craft"); updateHand() }
            e == "place" || e == "eat" || e == "pickup" || e == "break_tool" -> updateHand()
        }
        status.update(game.health, game.food, game.inventory.armorPoints())
    }

    private fun onStats(text: String) {
        val exposed = game.mobs.skyExposed(game.player.blockX(), game.player.blockY(), game.player.blockZ())
        sounds.setRain(game.rain > 0.05f, game.rain * (if (exposed) 1f else 0.3f))
        status.update(game.health, game.food, game.inventory.armorPoints())
        updateHand()
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
        private val startMining = Runnable {
            if (pointer != -1) { mining = true; input.breakHeld = true; handler.post(swingLoop) }
        }

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
                            swing()
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

    /**
     * Prefers RGB888 with a 24-bit depth buffer (less z-fighting), then falls back step by step so that
     * older TVs and boxes with OpenGL ES 2.0-only GPUs still get a working surface.
     */
    private class DepthConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es2 = 4 // EGL_OPENGL_ES2_BIT
            val attempts = listOf(
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16),
                intArrayOf(EGL10.EGL_RED_SIZE, 5, EGL10.EGL_GREEN_SIZE, 6, EGL10.EGL_BLUE_SIZE, 5, EGL10.EGL_DEPTH_SIZE, 16),
                intArrayOf(EGL10.EGL_DEPTH_SIZE, 16),
            )
            for (a in attempts) {
                val attribs = a + intArrayOf(EGL10.EGL_RENDERABLE_TYPE, es2, EGL10.EGL_NONE)
                val configs = arrayOfNulls<EGLConfig>(1)
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0) return configs[0]!!
            }
            throw IllegalStateException("This device has no OpenGL ES 2.0 display configuration")
        }
    }

    // ---------------------------------------------------------------- TV remote, keyboard and gamepad

    private var keyFwd = false; private var keyBack = false; private var keyLeft = false; private var keyRight = false
    private var keyTurnL = false; private var keyTurnR = false; private var keyLookU = false; private var keyLookD = false
    private var okMining = false
    private var okDown = false
    private var triggerPlace = false
    private val okHold = Runnable { if (okDown) { okMining = true; input.breakHeld = true; handler.post(swingLoop) } }

    private fun applyKeys() {
        input.moveForward = (if (keyFwd) 1f else 0f) - (if (keyBack) 1f else 0f)
        input.moveStrafe = (if (keyRight) 1f else 0f) - (if (keyLeft) 1f else 0f)
        input.lookStickX = (if (keyTurnR) 1f else 0f) - (if (keyTurnL) 1f else 0f)
        input.lookStickY = (if (keyLookU) 1f else 0f) - (if (keyLookD) 1f else 0f)
    }

    private fun cycleSlot(delta: Int) {
        val slot = (hotbar.selected + delta + 9) % 9
        hotbar.selected = slot
        selectSlot(slot)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (screen.visibility == View.VISIBLE) {
            if (screen.handleKey(e)) return true
            return super.dispatchKeyEvent(e)
        }
        if (inventory.visibility == View.VISIBLE) {
            if (inventory.handleKey(e)) return true
            return super.dispatchKeyEvent(e)
        }
        if (pauseMenu.visibility == View.VISIBLE) {
            if (e.keyCode == KeyEvent.KEYCODE_BUTTON_B || e.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
                if (e.action == KeyEvent.ACTION_UP) showPause(false)
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> keyFwd = down
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> keyBack = down
            KeyEvent.KEYCODE_A -> keyLeft = down
            KeyEvent.KEYCODE_D -> keyRight = down
            KeyEvent.KEYCODE_DPAD_LEFT -> keyTurnL = down
            KeyEvent.KEYCODE_DPAD_RIGHT -> keyTurnR = down
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_NUMPAD_8, KeyEvent.KEYCODE_R -> keyLookU = down
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_NUMPAD_2, KeyEvent.KEYCODE_V -> keyLookD = down
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_K -> {
                // Short press: place / use / attack. Hold: mine.
                if (first) { okDown = true; okMining = false; handler.postDelayed(okHold, 300) }
                if (!down) {
                    handler.removeCallbacks(okHold)
                    if (okDown && !okMining) { input.actions.add(GameInput.Action.PLACE); swing() }
                    okDown = false; okMining = false; input.breakHeld = false
                }
            }
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_BUTTON_R2 -> {
                input.breakHeld = down
                if (first) handler.post(swingLoop)
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> if (first) { input.actions.add(GameInput.Action.PLACE); swing() }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> input.jumpHeld = down
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_C -> input.descendHeld = down
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_BUTTON_X -> if (first) input.actions.add(GameInput.Action.TOGGLE_FLY)
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_TV_CONTENTS_MENU -> if (first) showInventory(true)
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (first) cycleSlot(-1)
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> if (first) cycleSlot(1)
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> if (first) { hotbar.selected = e.keyCode - KeyEvent.KEYCODE_1; selectSlot(hotbar.selected) }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (!down) showPause(true)
            else -> return super.dispatchKeyEvent(e)
        }
        applyKeys()
        return true
    }

    private fun axis(e: MotionEvent, a: Int): Float {
        val v = e.getAxisValue(a)
        return if (abs(v) < 0.18f) 0f else v
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        val isStick = e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        if (!isStick || e.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e)
        if (inventory.visibility == View.VISIBLE || pauseMenu.visibility == View.VISIBLE) return true
        val hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        input.moveStrafe = (axis(e, MotionEvent.AXIS_X) + hatX).coerceIn(-1f, 1f)
        input.moveForward = (-axis(e, MotionEvent.AXIS_Y) - hatY).coerceIn(-1f, 1f)
        input.lookStickX = axis(e, MotionEvent.AXIS_Z)
        input.lookStickY = -axis(e, MotionEvent.AXIS_RZ)
        val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val mining = rt > 0.5f
        if (mining && !input.breakHeld) handler.post(swingLoop)
        input.breakHeld = mining
        if (lt > 0.5f && !triggerPlace) { input.actions.add(GameInput.Action.PLACE); swing() }
        triggerPlace = lt > 0.5f
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            screen.visibility == View.VISIBLE -> screen.close()
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
        sounds.resume()
    }

    override fun onPause() {
        sounds.pause()
        releaseInputs()
        glView.onPause() // blocks until the GL thread has paused, so saving below is safe
        game.save()
        super.onPause()
    }

    override fun onDestroy() {
        val n = game.net
        if (n != null) Thread { n.close() }.start()
        sounds.release()
        world.shutdown()
        super.onDestroy()
    }
}
