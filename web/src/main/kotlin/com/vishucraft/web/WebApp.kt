package com.vishucraft.web

import com.vishucraft.client.ClientApp
import com.vishucraft.client.GameSession
import com.vishucraft.client.Ui
import com.vishucraft.client.Ui.Companion.rgba
import com.vishucraft.client.fonts
import com.vishucraft.game.engine.GameInput
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

const val WEB_CONTROLS_HELP =
    "Left thumb: move (push all the way to run)    ·    Right thumb: drag to look around\n" +
        "Tap: place blocks, open doors and chests, eat, ride    ·    Touch and hold: mine and attack\n" +
        "▲ jump / swim / fly up    ·    ▼ fly down, ride an elevator down, get out of a ride\n" +
        "FLY: fly (Creative)    ·    Hotbar: tap a slot    ·    •••: inventory & crafting    ·    II: game menu\n" +
        "On a computer: W A S D walk, mouse look, left click mine, right click place, Space jump,\n" +
        "Shift down, E inventory, F fly, Q drop, 1-9 hotbar, Esc game menu"

/**
 * The browser version: the shared menus and screens (ClientApp) with touch controls for iPhones and iPads, and
 * keyboard and mouse for computers.
 */
class WebApp : ClientApp(null) {
    override val prefs = WebPrefs()
    override val worldsDir: File = File(Storage.ROOT, "worlds").apply { mkdirs() }
    override val version = buildVersion()
    override val savedWhere = "Worlds are saved in this browser. Keep website data so they stay."
    override val controlsHelp = WEB_CONTROLS_HELP
    private val webAudio = WebAudio()

    init {
        fonts = ::browserFonts
        ui = Ui()
        audio = webAudio
    }

    // ---------------------------------------------------------------- touch state

    private class Touch(val id: Int, val role: Int, val x0: Float, val y0: Float, val t0: Double) {
        var x = x0; var y = y0
        var moved = 0f
        var mining = false
        var scrolled = false
    }

    private companion object {
        const val UI = 0; const val MOVE = 1; const val LOOK = 2; const val BUTTON = 3; const val HOTBAR = 4
        const val HOLD_MS = 260.0
    }

    private val touches = HashMap<Int, Touch>()
    private var pointerX = -1000f
    private var pointerY = -1000f
    /** Joystick direction (-1..1) from the move thumb. */
    private var stickX = 0f
    private var stickY = 0f
    private var jumpDown = false
    private var downDown = false
    private var pressedButton = ""
    private var liftPointer = false

    // Keyboard and mouse (computers)
    private val keys = HashSet<String>()
    private var mouseLeft = false
    private var mouseRight = false
    private var rightRepeat = 0f
    private var wasLocked = false
    private var lastSpace = 0.0

    /** Pixels per UI unit, and the touch-button size. */
    private val k get() = ui.scale
    private val big get() = if (prefs.largeButtons) 1.25f else 1f

    // ---------------------------------------------------------------- layout (UI units)

    private class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun has(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
    }

    private fun hotbarX0() = ui.width / 2 - 44f * 4.5f
    private fun hotbarY0() = ui.height - 44f - 8
    private fun buttons(s: GameSession): Map<String, Box> {
        val w = ui.width; val h = ui.height
        val b = 64f * big
        val g = s.game
        val out = LinkedHashMap<String, Box>()
        out["pause"] = Box(10f, 10f, 56f, 44f)
        out["inv"] = Box(hotbarX0() + 44f * 9 + 8, hotbarY0(), 54f, 44f)
        out["drop"] = Box(hotbarX0() + 44f * 9 + 68, hotbarY0(), 60f, 44f)
        out["jump"] = Box(w - b - 30, h - b - 70, b, b)
        val riding = g.carts.riding != null || g.boats.riding != null || g.vehicles.riding != null || g.mount != null
        if (g.player.flying || g.lift.standingOn || riding) out["down"] = Box(w - b * 2 - 44, h - b - 40, b, b)
        if (!g.survival) out["fly"] = Box(w - 150, 44f, 70f, 38f)
        return out
    }

    private fun stickCenter() = Pair(130f * big, ui.height - 150f * big)

    // ---------------------------------------------------------------- input from the browser

    /** Reads this frame's input events from the page. */
    fun pollInput() {
        val n = evCount()
        for (i in 0 until n) {
            val kind = evKind(i)
            when (kind) {
                0 -> touchStart(evId(i), evX(i).toFloat(), evY(i).toFloat())
                1 -> touchMove(evId(i), evX(i).toFloat(), evY(i).toFloat())
                2 -> touchEnd(evId(i), evX(i).toFloat(), evY(i).toFloat())
                3 -> session?.input?.addLook(evX(i).toFloat() * 0.35f / pixelRatio().toFloat(), evY(i).toFloat() * 0.35f / pixelRatio().toFloat())
                4 -> mouseDown(evId(i), evX(i).toFloat(), evY(i).toFloat())
                5 -> { if (evId(i) == 0) { mouseLeft = false }; if (evId(i) == 2) mouseRight = false }
                6 -> if (session != null && overlay == Overlay.NONE) session!!.input.selectedSlot = (session!!.input.selectedSlot - evY(i).toInt() + 9) % 9
                    else ui.wheel += evY(i).toFloat()
                7 -> keyDown(evText(i))
                8 -> keys.remove(evText(i))
                9 -> for (c in evText(i)) ui.type(c.code)
                10 -> { pointerX = evX(i).toFloat(); pointerY = evY(i).toFloat() }
                11 -> if (evId(i) == 1) hidden()
            }
        }
        evClear()
    }

    private fun playing() = session != null && overlay == Overlay.NONE

    private fun touchStart(id: Int, px: Float, py: Float) {
        val x = px / k; val y = py / k
        var role = UI
        val s = session
        if (s != null && overlay == Overlay.NONE) {
            val hit = buttons(s).entries.firstOrNull { it.value.has(x, y) }
            val hx0 = hotbarX0()
            role = when {
                hit != null -> { pressedButton = hit.key; pressButton(hit.key, true); BUTTON }
                x >= hx0 && x < hx0 + 44f * 9 && y >= hotbarY0() -> { s.input.selectedSlot = ((x - hx0) / 44f).toInt().coerceIn(0, 8); HOTBAR }
                x < ui.width * 0.4f && y > ui.height * 0.3f && touches.values.none { it.role == MOVE } -> MOVE
                touches.values.none { it.role == LOOK } -> LOOK
                else -> -1
            }
        } else { pointerX = px; pointerY = py }
        if (role < 0) return
        touches[id] = Touch(id, role, x, y, now())
        if (role == MOVE) updateStick(touches[id]!!)
    }

    private fun touchMove(id: Int, px: Float, py: Float) {
        val t = touches[id] ?: return
        val x = px / k; val y = py / k
        val dx = x - t.x; val dy = y - t.y
        t.x = x; t.y = y
        t.moved += abs(dx) + abs(dy)
        when (t.role) {
            MOVE -> updateStick(t)
            LOOK -> session?.input?.addLook(dx * k / pixelRatio().toFloat() * 1.1f, dy * k / pixelRatio().toFloat() * 1.1f)
            UI -> {
                pointerX = px; pointerY = py
                // Dragging up and down scrolls lists (worlds, creative items, recipes).
                if (t.moved > 12f) { t.scrolled = true; ui.wheel += dy / 42f }
            }
        }
    }

    private fun touchEnd(id: Int, px: Float, py: Float) {
        val t = touches.remove(id) ?: return
        when (t.role) {
            MOVE -> { stickX = 0f; stickY = 0f }
            BUTTON -> { pressButton(pressedButton, false); pressedButton = "" }
            LOOK -> {
                val s = session
                if (s != null && !t.mining && now() - t.t0 < HOLD_MS && t.moved < 12f) {
                    s.input.actions.add(GameInput.Action.PLACE); hud?.swing = 0.25f
                }
                s?.input?.breakHeld = false
            }
            UI -> {
                pointerX = px; pointerY = py
                if (!t.scrolled) {
                    // A long press is a right click (half a stack in the inventory).
                    if (now() - t.t0 > 450.0) ui.rightClicked = true else ui.clicked = true
                }
                // Fingers don't hover: forget where it was after this frame (except while carrying an item).
                liftPointer = overlay != Overlay.CONTAINER
            }
        }
    }

    private fun updateStick(t: Touch) {
        val (cx, cy) = stickCenter()
        val r = 70f * big
        var dx = (t.x - cx) / r; var dy = (t.y - cy) / r
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        stickX = dx; stickY = dy
    }

    private fun pressButton(name: String, down: Boolean) {
        val s = session ?: return
        when (name) {
            "jump" -> jumpDown = down
            "down" -> downDown = down
            "pause" -> if (down) { overlay = Overlay.PAUSE; releaseAll(s) }
            "inv" -> if (down) { releaseAll(s); openInventory() }
            "drop" -> if (down) s.input.actions.add(GameInput.Action.DROP)
            "fly" -> if (down) s.input.actions.add(GameInput.Action.TOGGLE_FLY)
        }
    }

    private fun releaseAll(s: GameSession) {
        touches.clear(); stickX = 0f; stickY = 0f; jumpDown = false; downDown = false
        releaseInputs(s.input)
    }

    private fun mouseDown(button: Int, px: Float, py: Float) {
        pointerX = px; pointerY = py
        val s = session
        if (s != null && overlay == Overlay.NONE && !isTouch()) {
            if (!pointerLocked()) return // this click locks the mouse for looking around
            when (button) {
                0 -> { mouseLeft = true; s.input.actions.add(GameInput.Action.ATTACK); hud?.swing = 0.25f }
                2 -> { mouseRight = true; rightRepeat = 0f }
            }
            return
        }
        if (button == 0) ui.clicked = true
        if (button == 2) ui.rightClicked = true
    }

    private fun keyDown(code: String) {
        if (ui.focus != null) {
            when (code) {
                "Backspace" -> ui.backspace()
                "Enter" -> ui.submitted = true
            }
            return
        }
        keys.add(code)
        val s = session
        if (s == null) { if (code == "Escape" && menu != Menu.TITLE) menu = Menu.TITLE; return }
        val input = s.input
        when (overlay) {
            Overlay.NONE -> when (code) {
                "Escape" -> overlay = Overlay.PAUSE
                "KeyE", "KeyI" -> openInventory()
                "KeyF" -> input.actions.add(GameInput.Action.TOGGLE_FLY)
                "KeyQ" -> input.actions.add(GameInput.Action.DROP)
                "Space" -> {
                    val t = now()
                    if (t - lastSpace < 300.0 && !s.game.survival) input.actions.add(GameInput.Action.TOGGLE_FLY)
                    lastSpace = t
                }
                else -> if (code.startsWith("Digit") && code.length == 6 && code[5] in '1'..'9') input.selectedSlot = code[5] - '1'
            }
            Overlay.CREATIVE, Overlay.CONTAINER -> if (code == "Escape" || code == "KeyE" || code == "KeyI") closeOverlay()
            Overlay.PAUSE, Overlay.ENDING -> if (code == "Escape") overlay = Overlay.NONE
            else -> if (code == "Escape") { ui.focus = null; overlay = if (overlay == Overlay.SETTINGS || overlay == Overlay.CONTROLS || overlay == Overlay.ACHIEVEMENTS) Overlay.PAUSE else Overlay.NONE }
        }
    }

    /** The page was hidden (another app, the home screen, or the phone locked): pause and save. */
    fun hidden() {
        val s = session
        if (s != null) {
            if (overlay == Overlay.NONE) overlay = Overlay.PAUSE
            releaseAll(s)
            try { s.save() } catch (_: Exception) {}
        }
        prefs.savePlayLimit(playLimit)
        Tasks.runAll()
        Storage.sync()
    }

    // ---------------------------------------------------------------- ClientApp hooks

    override fun updateGameInput(s: GameSession, dt: Float) {
        val input = s.input
        val playing = overlay == Overlay.NONE
        val touch = isTouch()
        if (!touch) {
            wantPointerLock(playing)
            val locked = pointerLocked()
            // Esc leaves the mouse lock: show the game menu.
            if (wasLocked && !locked && playing) overlay = Overlay.PAUSE
            wasLocked = locked
        }
        if (!playing) { releaseInputs(input); touches.clear(); stickX = 0f; stickY = 0f; jumpDown = false; downDown = false; return }
        fun key(vararg c: String) = c.any { it in keys }
        val kf = (if (key("KeyW", "ArrowUp")) 1f else 0f) - (if (key("KeyS", "ArrowDown")) 1f else 0f)
        val ks = (if (key("KeyD", "ArrowRight")) 1f else 0f) - (if (key("KeyA", "ArrowLeft")) 1f else 0f)
        input.moveForward = if (kf != 0f) kf else -stickY
        input.moveStrafe = if (ks != 0f) ks else stickX
        input.sprint = key("ControlLeft", "ControlRight") && kf > 0f || hypot(stickX, stickY) > 0.97f && stickY < -0.7f
        input.jumpHeld = jumpDown || key("Space")
        input.descendHeld = downDown || key("ShiftLeft", "ShiftRight", "KeyC")
        // Touch and hold to mine.
        val look = touches.values.firstOrNull { it.role == LOOK }
        if (look != null && !look.mining && now() - look.t0 >= HOLD_MS && look.moved < 12f) look.mining = true
        input.breakHeld = look?.mining == true || mouseLeft
        if (input.breakHeld && hud!!.swing <= 0f) hud!!.swing = 0.25f
        if (mouseRight) {
            rightRepeat -= dt
            if (rightRepeat <= 0f) { input.actions.add(GameInput.Action.PLACE); hud!!.swing = 0.25f; rightRepeat = 0.25f }
        }
    }

    override fun releaseInputs(input: GameInput) {
        super.releaseInputs(input)
        mouseLeft = false; mouseRight = false
    }

    override fun drawControls(s: GameSession) {
        if (!isTouch()) return
        val dim = rgba(0, 0, 0, 90); val edge = rgba(255, 255, 255, 110)
        // Move stick.
        val (cx, cy) = stickCenter()
        val r = 70f * big
        ui.rect(cx - r, cy - r, r * 2, r * 2, rgba(0, 0, 0, 60)); ui.frame(cx - r, cy - r, r * 2, r * 2, edge, 2f)
        val kx = cx + stickX * r * 0.7f; val ky = cy + stickY * r * 0.7f
        ui.rect(kx - 26, ky - 26, 52f, 52f, rgba(255, 255, 255, 120))
        for ((name, b) in buttons(s)) {
            val on = name == pressedButton || (name == "fly" && s.game.player.flying)
            ui.rect(b.x, b.y, b.w, b.h, if (on) rgba(90, 120, 200, 170) else dim)
            ui.frame(b.x, b.y, b.w, b.h, edge, 2f)
            val label = when (name) { "pause" -> "II"; "inv" -> "•••"; "drop" -> "DROP"; "jump" -> "▲"; "down" -> "▼"; else -> "FLY" }
            val size = if (name == "jump" || name == "down") 30f * big else 18f
            ui.text(label, b.x + b.w / 2, b.y + b.h / 2 - size * 0.55f, size, -1, 1)
        }
    }

    fun testWorld(pitch: Float) {
        val level = com.vishucraft.game.world.LevelData.create(7L, "Test", com.vishucraft.game.world.GameMode.CREATIVE)
        level.hasPlayer = true; level.x = 8f; level.y = 70f; level.z = 8f; level.pitch = pitch; level.yaw = 0.3f
        startSession(GameSession(null, level))
        session!!.game.player.flying = true
    }

    override fun startSession(s: GameSession) {
        super.startSession(s)
        touches.clear()
    }

    override fun leaveWorld() {
        super.leaveWorld()
        Tasks.runAll()
        Storage.sync()
    }

    // ---------------------------------------------------------------- frame

    private var syncTimer = 0f
    private var frames = 0
    private var fpsTimer = 0f
    private var fps = 0

    fun step(dt: Float) {
        val w = canvasWidth(); val h = canvasHeight()
        if (w != fbW || h != fbH) { fbW = w; fbH = h; session?.resize(fbW, fbH) }
        frames++; fpsTimer += dt
        if (fpsTimer >= 0.5f) { fps = (frames / fpsTimer).toInt(); frames = 0; fpsTimer = 0f }
        pollInput()
        webAudio.update()
        frame(dt, fps, pointerX, pointerY)
        if (liftPointer) { liftPointer = false; if (touches.isEmpty()) { pointerX = -1000f; pointerY = -1000f } }
        // Text boxes: tell the page where they are, and close the phone's keyboard when none is focused.
        val rects = ui.fieldRects
        setFields(FloatArray(rects.size) { rects[it] })
        if (ui.focus == null) focusText(false)
        // Chunk work, and copying saves to the browser's storage now and then.
        Tasks.run(if (session == null) 4.0 else 9.0)
        syncTimer += dt
        if (syncTimer > 8f) { syncTimer = 0f; if (Tasks.pending == 0) Storage.sync() }
    }
}
