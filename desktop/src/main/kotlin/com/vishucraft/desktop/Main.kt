package com.vishucraft.desktop

import com.vishucraft.game.engine.placeBuilding
import com.vishucraft.game.engine.flyTo
import com.vishucraft.game.engine.liftTo
import com.vishucraft.client.*
import com.vishucraft.client.Ui.Companion.rgba
import com.vishucraft.game.engine.GameInput
import com.vishucraft.game.net.ClientSession
import com.vishucraft.game.net.Discovery
import com.vishucraft.game.net.HostSession
import com.vishucraft.game.net.Net
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Dimension
import com.vishucraft.game.world.GameMode
import com.vishucraft.game.world.LevelData
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.*
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.image.BufferedImage
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    // Build step: writes the installer / shortcut icon.
    if (args.firstOrNull() == "--make-icon") { writeIcons(File(args.getOrElse(1) { "." })); return }
    val demo = args.indexOf("--demo").takeIf { it >= 0 }?.let { File(args.getOrElse(it + 1) { "screenshots" }) }
    if (demo != null) System.setProperty("vishucraft.home", File(demo, "home").absolutePath)
    try {
        App(demo).run()
    } catch (e: Throwable) {
        // Keep a crash log next to the worlds so problems can be reported.
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        try { File(Paths.home, "crash.txt").writeText(sw.toString()) } catch (_: Exception) {}
        System.err.println(sw)
        try {
            javax.swing.JOptionPane.showMessageDialog(null, "DhruvVishu stopped because of an error:\n\n${e}\n\nDetails were saved to ${File(Paths.home, "crash.txt")}",
                "DhruvVishu", javax.swing.JOptionPane.ERROR_MESSAGE)
        } catch (_: Throwable) {}
        kotlin.system.exitProcess(1)
    }
}

/** Writes icon.png and a Windows icon.ico (PNG-compressed entries, 16 to 256 px). */
fun writeIcons(dir: File) {
    dir.mkdirs()
    ImageIO.write(image(IconAtlas.appIcon(256), 256), "png", File(dir, "icon.png"))
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
    val pngs = sizes.map { s -> java.io.ByteArrayOutputStream().also { ImageIO.write(image(IconAtlas.appIcon(s), s), "png", it) }.toByteArray() }
    java.io.DataOutputStream(File(dir, "icon.ico").outputStream().buffered()).use { d ->
        fun le16(v: Int) { d.write(v and 255); d.write(v shr 8 and 255) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16(v ushr 16) }
        le16(0); le16(1); le16(sizes.size)
        var offset = 6 + 16 * sizes.size
        for ((i, s) in sizes.withIndex()) {
            d.write(if (s >= 256) 0 else s); d.write(if (s >= 256) 0 else s)
            d.write(0); d.write(0); le16(1); le16(32)
            le32(pngs[i].size); le32(offset)
            offset += pngs[i].size
        }
        for (p in pngs) d.write(p)
    }
}

const val CONTROLS_HELP =
    "W A S D: walk    ·    Mouse: look around\n" +
        "Left mouse: mine blocks (hold) and attack\n" +
        "Right mouse: place blocks, use doors, beds, chests, eat, ride minecarts\n" +
        "Space: jump / swim / fly up    ·    Shift: fly down\n" +
        "Double-tap Space or F: fly (Creative)    ·    Ctrl: sprint\n" +
        "Minecart: place it on a rail, Space next to it to get in, Shift to get out\n" +
        "1-9 or mouse wheel: choose hotbar slot    ·    Q: drop item (Ctrl+Q: whole stack)\n" +
        "E: inventory & crafting    ·    Esc: pause menu\n" +
        "F3: FPS & coordinates    ·    F11: fullscreen    ·    F2: screenshot"

class App(demo: File?) : ClientApp(demo) {
    private var window = NULL
    override val prefs = Prefs()
    override val worldsDir: File get() = Paths.worlds
    override val net: NetSupport = DesktopNet()
    override val canQuit = true
    override fun quit() = glfwSetWindowShouldClose(window, true)
    override val version = App::class.java.`package`?.implementationVersion ?: "dev"
    override val savedWhere get() = "Worlds are saved in ${Paths.worlds}"
    override val controlsHelp = CONTROLS_HELP

    // Input
    private val keys = BooleanArray(GLFW_KEY_LAST + 1)
    private var lastMouseX = Double.NaN
    private var lastMouseY = Double.NaN
    private var mouseCaptured = false
    private var leftDown = false
    private var rightDown = false
    private var rightRepeat = 0f
    private var lastSpace = 0.0
    private var windowedPos = intArrayOf(100, 100, 1280, 720)

    fun run() {
        if (!glfwInit()) error("Could not start the window system (GLFW)")
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_DEPTH_BITS, 24)
        window = glfwCreateWindow(1280, 720, "DhruvVishu", NULL, NULL)
        if (window == NULL) error("Could not open a window. Please update your graphics driver (OpenGL 2.1 is needed).")
        glfwMakeContextCurrent(window)
        GL.createCapabilities()
        glfwSwapInterval(1)
        setIcon()
        glfwShowWindow(window)
        if (prefs.fullscreen && demo == null) setFullscreen(true)
        installCallbacks()

        fonts = ::awtFonts
        ui = Ui()
        audio = Audio()
        applySettings()
        if (demo != null) audio.volume = 0f.also { audio.musicVolume = 0f }

        var last = System.nanoTime()
        var frames = 0; var fpsTimer = 0f; var fps = 0
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            frames++; fpsTimer += dt
            if (fpsTimer >= 0.5f) { fps = (frames / fpsTimer).toInt(); frames = 0; fpsTimer = 0f }
            val w = IntArray(1); val h = IntArray(1)
            glfwGetFramebufferSize(window, w, h)
            if (w[0] > 0 && h[0] > 0 && (w[0] != fbW || h[0] != fbH)) { fbW = w[0]; fbH = h[0]; session?.resize(fbW, fbH) }
            val mx = DoubleArray(1); val my = DoubleArray(1)
            glfwGetCursorPos(window, mx, my)
            val winW = IntArray(1); val winH = IntArray(1)
            glfwGetWindowSize(window, winW, winH)
            val ratio = if (winW[0] > 0) fbW.toFloat() / winW[0] else 1f
            ui.shift = keys[GLFW_KEY_LEFT_SHIFT] || keys[GLFW_KEY_RIGHT_SHIFT]
            frame(dt, fps, mx[0].toFloat() * ratio, my[0].toFloat() * ratio)
            demoStep()
            glfwSwapBuffers(window)
        }
        leaveWorld()
        audio.close()
        glfwDestroyWindow(window)
        glfwTerminate()
    }


    override fun updateGameInput(s: GameSession, dt: Float) {
        val input = s.input
        val playing = overlay == Overlay.NONE
        setCapture(playing && glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE)
        if (!playing) { releaseInputs(input); return }
        fun k(vararg codes: Int) = codes.any { keys[it] }
        input.moveForward = (if (k(GLFW_KEY_W, GLFW_KEY_UP)) 1f else 0f) - (if (k(GLFW_KEY_S, GLFW_KEY_DOWN)) 1f else 0f)
        input.moveStrafe = (if (k(GLFW_KEY_D, GLFW_KEY_RIGHT)) 1f else 0f) - (if (k(GLFW_KEY_A, GLFW_KEY_LEFT)) 1f else 0f)
        input.sprint = k(GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL) && input.moveForward > 0f
        input.jumpHeld = k(GLFW_KEY_SPACE)
        input.descendHeld = k(GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT, GLFW_KEY_C)
        input.breakHeld = leftDown
        if (leftDown && hud!!.swing <= 0f) hud!!.swing = 0.25f
        // Holding the right button keeps placing, like tapping repeatedly.
        if (rightDown) {
            rightRepeat -= dt
            if (rightRepeat <= 0f) { input.actions.add(GameInput.Action.PLACE); hud!!.swing = 0.25f; rightRepeat = 0.25f }
        }
    }

    override fun releaseInputs(input: GameInput) {
        super.releaseInputs(input)
        leftDown = false; rightDown = false
    }

    override fun setCapture(on: Boolean) {
        if (on == mouseCaptured) return
        mouseCaptured = on
        glfwSetInputMode(window, GLFW_CURSOR, if (on) GLFW_CURSOR_DISABLED else GLFW_CURSOR_NORMAL)
        if (on && glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE)
        if (!on) {
            val w = IntArray(1); val h = IntArray(1)
            glfwGetWindowSize(window, w, h)
            glfwSetCursorPos(window, w[0] / 2.0, h[0] / 2.0)
        }
        lastMouseX = Double.NaN
    }

    // ---------------------------------------------------------------- input callbacks

    private fun installCallbacks() {
        glfwSetKeyCallback(window) { _, key, _, action, mods -> onKey(key, action, mods) }
        glfwSetCharCallback(window) { _, cp -> ui.type(cp) }
        glfwSetMouseButtonCallback(window) { _, button, action, _ -> onMouseButton(button, action) }
        glfwSetScrollCallback(window) { _, _, dy -> onScroll(dy.toFloat()) }
        glfwSetCursorPosCallback(window) { _, x, y ->
            if (mouseCaptured) {
                if (!lastMouseX.isNaN()) session?.input?.addLook(((x - lastMouseX) * 0.35).toFloat(), ((y - lastMouseY) * 0.35).toFloat())
                lastMouseX = x; lastMouseY = y
            }
        }
        glfwSetWindowFocusCallback(window) { _, focused ->
            // Alt-tabbing away pauses the game.
            if (!focused && session != null && overlay == Overlay.NONE && demo == null) overlay = Overlay.PAUSE
        }
    }

    private fun onKey(key: Int, action: Int, mods: Int) {
        if (key in keys.indices) keys[key] = action != GLFW_RELEASE
        if (action == GLFW_RELEASE) return
        val first = action == GLFW_PRESS
        if (ui.focus != null) {
            when (key) {
                GLFW_KEY_BACKSPACE -> ui.backspace()
                GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> ui.submitted = true
                GLFW_KEY_ESCAPE, GLFW_KEY_TAB -> ui.focus = null
                GLFW_KEY_V -> if (mods and GLFW_MOD_CONTROL != 0) glfwGetClipboardString(window)?.forEach { ui.type(it.code) }
            }
            return
        }
        if (!first) return
        when (key) {
            GLFW_KEY_F11 -> { prefs.fullscreen = !prefs.fullscreen; setFullscreen(prefs.fullscreen); return }
            GLFW_KEY_F3 -> { prefs.showDebug = !prefs.showDebug; return }
            GLFW_KEY_F2 -> { screenshot(File(Paths.home, "screenshots/${java.time.LocalDateTime.now().toString().replace(':', '-')}.png")); session?.let { hud?.toast("Screenshot saved in ${File(Paths.home, "screenshots")}") }; return }
        }
        val s = session
        if (s == null) {
            if (key == GLFW_KEY_ESCAPE && menu != Menu.TITLE) { message = null; menu = Menu.TITLE }
            return
        }
        val input = s.input
        when (overlay) {
            Overlay.NONE -> when (key) {
                GLFW_KEY_ESCAPE -> overlay = Overlay.PAUSE
                GLFW_KEY_E, GLFW_KEY_I -> openInventory()
                GLFW_KEY_F -> input.actions.add(GameInput.Action.TOGGLE_FLY)
                GLFW_KEY_Q -> input.actions.add(if (mods and GLFW_MOD_CONTROL != 0) GameInput.Action.DROP_STACK else GameInput.Action.DROP)
                GLFW_KEY_SPACE -> {
                    val now = glfwGetTime()
                    if (now - lastSpace < 0.3 && !s.game.survival) input.actions.add(GameInput.Action.TOGGLE_FLY)
                    lastSpace = now
                }
                in GLFW_KEY_1..GLFW_KEY_9 -> input.selectedSlot = key - GLFW_KEY_1
            }
            Overlay.PAUSE -> if (key == GLFW_KEY_ESCAPE) overlay = Overlay.NONE
            Overlay.TRADE -> if (key == GLFW_KEY_ESCAPE || key == GLFW_KEY_E) overlay = Overlay.NONE
            Overlay.SIGN, Overlay.FLIGHTS, Overlay.FLOORS -> if (key == GLFW_KEY_ESCAPE) { ui.focus = null; overlay = Overlay.NONE }
            Overlay.ENDING -> if (key == GLFW_KEY_ESCAPE) overlay = Overlay.NONE
            Overlay.INTERNET -> if (key == GLFW_KEY_ESCAPE) overlay = Overlay.PAUSE
            Overlay.SETTINGS, Overlay.CONTROLS, Overlay.NAME_MOB, Overlay.ACHIEVEMENTS -> if (key == GLFW_KEY_ESCAPE) { ui.focus = null; overlay = Overlay.PAUSE }
            Overlay.CREATIVE, Overlay.CONTAINER -> when (key) {
                GLFW_KEY_ESCAPE, GLFW_KEY_E, GLFW_KEY_I -> closeOverlay()
                in GLFW_KEY_1..GLFW_KEY_9 -> input.selectedSlot = key - GLFW_KEY_1
            }
        }
    }

    private fun onMouseButton(button: Int, action: Int) {
        val down = action == GLFW_PRESS
        val s = session
        if (s != null && overlay == Overlay.NONE && mouseCaptured) {
            when (button) {
                GLFW_MOUSE_BUTTON_LEFT -> {
                    leftDown = down
                    if (down) { s.input.actions.add(GameInput.Action.ATTACK); hud?.swing = 0.25f }
                }
                GLFW_MOUSE_BUTTON_RIGHT -> { rightDown = down; rightRepeat = 0f }
                GLFW_MOUSE_BUTTON_MIDDLE -> if (down) pickBlock(s)
            }
            return
        }
        if (!down) return
        if (button == GLFW_MOUSE_BUTTON_LEFT) ui.clicked = true
        if (button == GLFW_MOUSE_BUTTON_RIGHT) ui.rightClicked = true
    }

    /** Middle click in creative: put the looked-at block in your hand. */
    private fun pickBlock(s: GameSession) {
        val t = s.game.target ?: return
        if (s.game.survival) {
            val i = (0 until 9).firstOrNull { s.game.inventory.slots[it]?.id == t.block } ?: return
            s.input.selectedSlot = i
        } else s.game.inventory.slots[s.input.selectedSlot] = com.vishucraft.game.world.ItemStack(t.block, 1)
    }

    private fun onScroll(dy: Float) {
        val s = session
        if (s != null && overlay == Overlay.NONE) {
            if (dy != 0f) s.input.selectedSlot = (s.input.selectedSlot - kotlin.math.sign(dy).toInt() + 9) % 9
        } else ui.wheel += dy
    }

    // ---------------------------------------------------------------- window helpers

    override fun settingChanged(label: String) { if (label.startsWith("Fullscreen")) setFullscreen(prefs.fullscreen) }

    private fun setFullscreen(on: Boolean) {
        val monitor = glfwGetPrimaryMonitor()
        val mode = glfwGetVideoMode(monitor) ?: return
        if (on) {
            val x = IntArray(1); val y = IntArray(1); val w = IntArray(1); val h = IntArray(1)
            glfwGetWindowPos(window, x, y); glfwGetWindowSize(window, w, h)
            if (glfwGetWindowMonitor(window) == NULL) windowedPos = intArrayOf(x[0], y[0], w[0], h[0])
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate())
        } else {
            val (x, y, w, h) = windowedPos.toList()
            glfwSetWindowMonitor(window, NULL, x, y, w, h, 0)
        }
    }

    private fun setIcon() {
        try {
            val img = image(IconAtlas.appIcon(64), 64)
            val w = img.width; val h = img.height
            val buf = BufferUtils.createByteBuffer(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                val c = img.getRGB(x, y)
                buf.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put((c ushr 24).toByte())
            }
            buf.flip()
            val images = org.lwjgl.glfw.GLFWImage.malloc(1)
            images.position(0).width(w).height(h).pixels(buf)
            glfwSetWindowIcon(window, images)
            images.free()
        } catch (_: Throwable) {}
    }

    private fun screenshot(file: File) {
        file.parentFile.mkdirs()
        val buf = BufferUtils.createByteBuffer(fbW * fbH * 4)
        glReadPixels(0, 0, fbW, fbH, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        val img = BufferedImage(fbW, fbH, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until fbH) for (x in 0 until fbW) {
            val i = ((fbH - 1 - y) * fbW + x) * 4
            img.setRGB(x, y, ((buf.get(i).toInt() and 255) shl 16) or ((buf.get(i + 1).toInt() and 255) shl 8) or (buf.get(i + 2).toInt() and 255))
        }
        ImageIO.write(img, "png", file)
    }

    // ---------------------------------------------------------------- demo / self-test mode

    private var demoFrame = 0

    /** Moves the demo player to the nearest spot of [biome]. */
    private fun demoVisit(g: com.vishucraft.game.engine.Game, biome: com.vishucraft.game.world.Biome) {
        val gen = g.world.generator
        val px = g.player.x.toInt(); val pz = g.player.z.toInt()
        for (r in 0..6000 step 48) for (a in 0 until 24) {
            val x = px + (kotlin.math.cos(a * 0.2618) * r).toInt(); val z = pz + (kotlin.math.sin(a * 0.2618) * r).toInt()
            if (gen.biomeAt(x, z) == biome && gen.biomeAt(x + 24, z) == biome && gen.biomeAt(x - 24, z) == biome) {
                g.player.x = x + 0.5f; g.player.z = z + 0.5f; g.player.y = gen.surfaceHeight(x, z) + 12f
                g.player.flying = true; g.player.vy = 0f
                return
            }
        }
    }
    private var demoWorld = 0

    /**
     * `--demo <folder>` walks through the menus and a creative and a survival world by itself,
     * saving screenshots along the way. Used to check the build on machines without a person at the keyboard.
     */
    private fun demoStep() {
        val dir = demo ?: return
        demoFrame++
        fun shot(name: String) { screenshot(File(dir, "$name.png")); println("screenshot $name") }
        val f = demoFrame
        when {
            f == 10 -> shot("01-title")
            f == 12 -> { menu = Menu.CREATE; nameField.text = "Demo creative"; seedField.text = "12345"; survival = false }
            f == 16 -> shot("02-create")
            f == 18 -> demoCreate = true
            f == 400 -> shot("03-creative-world")
            f == 402 -> overlay = Overlay.CREATIVE
            f == 406 -> shot("04-creative-inventory")
            f == 407 -> creative?.tab = 5
            f == 409 -> shot("04b-creative-items")
            f == 410 -> creative?.tab = 4
            f == 412 -> shot("04c-creative-home")
            f == 413 -> {
                overlay = Overlay.NONE
                // A short track straight ahead with a minecart on it.
                session?.game?.let { g ->
                    val p = g.player
                    p.pitch = -0.35f
                    val fx = kotlin.math.round(kotlin.math.sin(p.yaw)).toInt(); val fz = kotlin.math.round(-kotlin.math.cos(p.yaw)).toInt()
                    val (sx, sz) = if (fx != 0) fx to 0 else 0 to (if (fz == 0) -1 else fz)
                    val y = com.vishucraft.game.world.floorInt(p.y)
                    for (i in 2..9) {
                        val x = com.vishucraft.game.world.floorInt(p.x) + sx * i; val z = com.vishucraft.game.world.floorInt(p.z) + sz * i
                        g.setBlock(x, y - 1, z, Blocks.STONE); g.setBlock(x, y, z, if (i in 5..6) Blocks.POWERED_RAIL else Blocks.RAIL, if (sx != 0) 1 else 0)
                        g.setBlock(x, y + 1, z, Blocks.AIR); g.setBlock(x, y + 2, z, Blocks.AIR)
                    }
                    g.carts.list.add(com.vishucraft.game.engine.Cart(com.vishucraft.game.world.floorInt(p.x) + sx * 3 + 0.5f, y.toFloat(), com.vishucraft.game.world.floorInt(p.z) + sz * 3 + 0.5f))
                }
            }
            f == 440 -> shot("04d-minecart")
            f == 441 -> session?.game?.let { g ->
                // A line-up of the new creatures in front of the player.
                g.carts.list.clear()
                val p = g.player
                p.pitch = -0.15f
                val fx = kotlin.math.sin(p.yaw); val fz = -kotlin.math.cos(p.yaw)
                val rx = kotlin.math.cos(p.yaw); val rz = kotlin.math.sin(p.yaw)
                val types = listOf(com.vishucraft.game.engine.MobType.CHICKEN, com.vishucraft.game.engine.MobType.RABBIT, com.vishucraft.game.engine.MobType.HORSE,
                    com.vishucraft.game.engine.MobType.WOLF, com.vishucraft.game.engine.MobType.CAT, com.vishucraft.game.engine.MobType.SKELETON,
                    com.vishucraft.game.engine.MobType.SLIME, com.vishucraft.game.engine.MobType.WITCH, com.vishucraft.game.engine.MobType.ENDERMAN,
                    com.vishucraft.game.engine.MobType.DROWNED)
                g.mobs.list.clear(); g.mobs.hostileEnabled = true
                for ((i, t) in types.withIndex()) {
                    val side = (i - 4.5f) * 1.5f
                    val x = p.x + fx * 7f + rx * side; val z = p.z + fz * 7f + rz * side
                    var y = p.y.toInt() + 6
                    while (y > 1 && !Blocks.solid[g.world.getBlock(kotlin.math.floor(x).toInt(), y - 1, kotlin.math.floor(z).toInt())]) y--
                    g.mobs.list.add(com.vishucraft.game.engine.Mob(t, x, y.toFloat(), z).also { it.yaw = p.yaw + 3.14159f; it.customName = "demo" })
                }
                g.timeOfDay = 0.25f
            }
            f == 443 -> session?.game?.mobs?.list?.forEach { it.vx = 0f; it.vz = 0f }
            f == 470 -> shot("04e-creatures")
            f == 472 -> session?.game?.let { g ->
                // Pack 3 creatures.
                val p = g.player
                p.pitch = -0.15f
                val fx = kotlin.math.sin(p.yaw); val fz = -kotlin.math.cos(p.yaw)
                val rx = kotlin.math.cos(p.yaw); val rz = kotlin.math.sin(p.yaw)
                val types = listOf("IRON_GOLEM", "SNOW_GOLEM", "FOX", "GOAT", "PANDA", "POLAR_BEAR", "LLAMA", "MOOSHROOM", "TURTLE", "FROG", "AXOLOTL", "PARROT", "BEE")
                    .map { com.vishucraft.game.engine.MobType.valueOf(it) }
                g.mobs.list.clear(); g.mobs.hostileEnabled = false
                for ((i, t) in types.withIndex()) {
                    val side = (i - 6f) * 1.4f
                    val x = p.x + fx * 9f + rx * side; val z = p.z + fz * 9f + rz * side
                    var y = p.y.toInt() + 6
                    while (y > 1 && !Blocks.solid[g.world.getBlock(kotlin.math.floor(x).toInt(), y - 1, kotlin.math.floor(z).toInt())]) y--
                    g.mobs.list.add(com.vishucraft.game.engine.Mob(t, x, y.toFloat(), z).also { it.yaw = p.yaw + 3.14159f; it.customName = "demo"; it.tamed = true; it.sitting = true })
                }
            }
            f == 473 -> session?.game?.let { g ->
                // A small stone wall with switches on its face, the floor and under an overhang.
                val p = g.player
                val fx = kotlin.math.round(kotlin.math.sin(p.yaw)).toInt(); val fz = kotlin.math.round(-kotlin.math.cos(p.yaw)).toInt()
                val (sx, sz) = if (fx != 0) fx to 0 else 0 to (if (fz == 0) -1 else fz)
                val bx = com.vishucraft.game.world.floorInt(p.x) + sx * 3; val bz = com.vishucraft.game.world.floorInt(p.z) + sz * 3
                val y = com.vishucraft.game.world.floorInt(p.y)
                val rx = -sz; val rz = sx
                val S = com.vishucraft.game.world.Shapes
                // The wall's face towards the player is on side "toward player", so the switch's support is away from the player.
                val away = when { sx == 1 -> 4; sx == -1 -> 5; sz == 1 -> 2; else -> 3 }
                for (i in -2..2) for (dy in 0..2) g.setBlock(bx + rx * i, y + dy, bz + rz * i, Blocks.STONE_BRICKS)
                for (i in -2..2) for (dy in 0..2) g.setBlock(bx - sx + rx * i, y + dy, bz - sz + rz * i, Blocks.AIR)
                g.setBlock(bx - sx - rx, y + 1, bz - sz - rz, Blocks.LEVER, S.attachMeta(away))
                g.setBlock(bx - sx, y + 1, bz - sz, Blocks.STONE_BUTTON, S.attachMeta(away))
                g.setBlock(bx - sx + rx, y + 1, bz - sz + rz, Blocks.WOOD_BUTTON_FIRST, S.attachMeta(away))
                g.setBlock(bx - sx + rx * 2, y + 1, bz - sz + rz * 2, Blocks.LEVER, S.attachMeta(away) or 1)
                g.setBlock(bx - sx * 2 + rx, y, bz - sz * 2 + rz, Blocks.LEVER, S.attachMeta(1))
                g.mobs.list.clear()
                p.pitch = -0.1f
            }
            f == 490 -> shot("04e2-wall-switches")
            f in 474..500 -> session?.game?.mobs?.list?.forEach { it.vx = 0f; it.vz = 0f }
            f == 501 -> shot("04f-new-creatures")
            f == 502 -> session?.game?.let { g -> g.mobs.list.clear(); g.timeOfDay = 0.485f; g.player.pitch = 0.05f }
            f == 530 -> shot("04g-sunset")
            f == 531 -> session?.game?.let { g -> g.timeOfDay = 0.75f; g.player.pitch = 0.9f }
            f == 560 -> shot("04h-night-stars")
            f == 561 -> session?.game?.let { g ->
                // Visit a cherry grove, then the badlands.
                g.timeOfDay = 0.2f; g.player.pitch = -0.2f
                demoVisit(g, com.vishucraft.game.world.Biome.CHERRY)
            }
            f == 760 -> shot("04i-cherry-grove")
            f == 761 -> session?.game?.let { g -> demoVisit(g, com.vishucraft.game.world.Biome.BADLANDS) }
            f == 960 -> shot("04j-badlands")
            f == 961 -> session?.game?.let { g ->
                // A kitchen and living room floating in the sky: every home block, some of them switched on.
                val p = g.player
                p.flying = true; p.vy = 0f; p.pitch = -0.25f; g.timeOfDay = 0.25f
                val fx = kotlin.math.round(kotlin.math.sin(p.yaw)).toInt(); val fz = kotlin.math.round(-kotlin.math.cos(p.yaw)).toInt()
                val (sx, sz) = if (fx != 0) fx to 0 else 0 to (if (fz == 0) -1 else fz)
                val rx = -sz; val rz = sx
                val toPlayer = when { sx == 1 -> 5; sx == -1 -> 4; sz == 1 -> 3; else -> 2 }
                val bx = com.vishucraft.game.world.floorInt(p.x); val bz = com.vishucraft.game.world.floorInt(p.z)
                val y = com.vishucraft.game.world.floorInt(p.y) - 2
                fun at(f: Int, r: Int, dy: Int, id: Int, meta: Int = 0) = g.setBlock(bx + sx * f + rx * r, y + dy, bz + sz * f + rz * r, id, meta)
                for (f in -1..6) for (r in -6..6) { at(f, r, 0, Blocks.SMOOTH_STONE); for (dy in 1..4) at(f, r, dy, Blocks.AIR) }
                for (r in -6..6) for (dy in 1..4) at(6, r, dy, Blocks.CONCRETE_FIRST)
                val B = Blocks
                // Kitchen on the left.
                at(5, -6, 1, B.FRIDGE, toPlayer); at(5, -6, 2, B.FRIDGE, toPlayer or com.vishucraft.game.world.Shapes.UPPER)
                at(5, -5, 1, B.KITCHEN_COUNTER, toPlayer); at(5, -5, 2, B.MICROWAVE, toPlayer or 8)
                at(5, -4, 1, B.GAS_STOVE, toPlayer or 8); at(5, -4, 2, B.PRESSURE_COOKER, toPlayer or 8)
                at(5, -3, 1, B.GAS_STOVE, toPlayer or 8); at(5, -3, 2, B.TAWA, toPlayer or 8)
                at(5, -2, 1, B.KITCHEN_SINK, toPlayer)
                at(5, -1, 1, B.KITCHEN_COUNTER, toPlayer); at(5, -1, 2, B.TOASTER, toPlayer)
                at(4, -6, 1, B.OVEN, toPlayer or 8)
                at(3, -6, 1, B.KITCHEN_COUNTER, toPlayer); at(3, -6, 2, B.MIXER, toPlayer); at(2, -6, 1, B.KITCHEN_COUNTER, toPlayer); at(2, -6, 2, B.KETTLE, toPlayer)
                at(2, -3, 1, B.DINING_TABLE); at(2, -2, 1, B.DINING_TABLE); at(1, -3, 1, B.CHAIR, toPlayer xor 1); at(3, -2, 1, B.CHAIR, toPlayer)
                at(2, -3, 2, B.FRYING_PAN, toPlayer or 8)
                // Living room on the right.
                at(5, 3, 1, B.DINING_TABLE); at(5, 3, 2, B.TV, toPlayer or 8)
                at(5, 5, 1, B.WASHING_MACHINE, toPlayer or 8)
                at(2, 2, 1, B.SOFA, toPlayer xor 1); at(2, 3, 1, B.SOFA, toPlayer xor 1); at(2, 4, 1, B.SOFA, toPlayer xor 1)
                at(5, 1, 1, B.DINING_TABLE); at(5, 1, 2, B.TABLE_LAMP, 8)
                at(5, 4, 4, B.AIR_CONDITIONER, toPlayer or 8)
                for (r in 1..5) for (f in 1..4) at(f, r, 5, B.SMOOTH_STONE)
                at(3, 3, 4, B.CEILING_FAN, 8)
                // Household pack along the back wall and in the middle.
                at(5, 0, 1, B.WARDROBE, toPlayer); at(5, 0, 2, B.WARDROBE, toPlayer or com.vishucraft.game.world.Shapes.UPPER)
                at(5, 2, 1, B.STUDY_TABLE, toPlayer); at(5, 2, 2, B.COMPUTER, toPlayer or 8)
                at(5, 1, 3, B.WALL_CLOCK, toPlayer); at(5, 3, 3, B.MIRROR, toPlayer)
                at(3, 0, 1, B.ARMCHAIR, toPlayer); at(3, 1, 1, B.BEAN_BAG, toPlayer); at(4, 0, 1, B.PLANT_POT)
                at(2, 1, 4, B.CEILING_LIGHT, 8)
                at(1, 5, 1, B.BATHTUB, toPlayer or 8); at(1, 6, 1, B.TOILET, toPlayer); at(3, 6, 1, B.WASH_BASIN, toPlayer)
                at(4, 6, 1, B.WATER_COOLER, toPlayer); at(2, 6, 1, B.DUSTBIN)
                g.mobs.list.clear(); g.carts.list.clear()
                p.x = bx + 0.5f - sx * 1.5f; p.z = bz + 0.5f - sz * 1.5f; p.y = y + 2.2f
            }
            f == 990 -> shot("04k-kitchen-and-home")
            f == 991 -> session?.game?.let { g ->
                // A station with a metro, and an engine pulling coaches on the next track.
                val p = g.player
                val fx = kotlin.math.round(kotlin.math.sin(p.yaw)).toInt(); val fz = kotlin.math.round(-kotlin.math.cos(p.yaw)).toInt()
                val (sx, sz) = if (fx != 0) fx to 0 else 0 to (if (fz == 0) -1 else fz)
                val rx = -sz; val rz = sx
                val bx = com.vishucraft.game.world.floorInt(p.x) + sx * 10; val bz = com.vishucraft.game.world.floorInt(p.z) + sz * 10
                val y = com.vishucraft.game.world.floorInt(p.y) + 2
                fun at(f: Int, r: Int, dy: Int, id: Int, meta: Int = 0) = g.setBlock(bx + sx * f + rx * r, y + dy, bz + sz * f + rz * r, id, meta)
                val railMeta = if (rx != 0) 1 else 0
                for (r in -24..24) {
                    for (f in -3..3) { at(f, r, -1, Blocks.RAILWAY_BALLAST); for (dy in 0..4) at(f, r, dy, Blocks.AIR) }
                    at(-1, r, 0, Blocks.RAIL, railMeta); at(2, r, 0, Blocks.RAIL, railMeta)
                    if (r in -6..6) { at(-2, r, 0, Blocks.STATION_PLATFORM); at(-3, r, 0, Blocks.STATION_PLATFORM); at(0, r, 0, Blocks.STATION_PLATFORM) }
                }
                g.carts.list.clear()
                fun car(f: Int, r: Float, kind: Int) = com.vishucraft.game.engine.Cart(bx + sx * f + rx * r + 0.5f, y.toFloat(), bz + sz * f + rz * r + 0.5f, kind)
                    .also { it.hx = rx.toFloat(); it.hz = rz.toFloat(); it.yaw = kotlin.math.atan2(it.hx, -it.hz); g.carts.list.add(it) }
                val metro = car(-1, 2f, com.vishucraft.game.engine.Cart.METRO)
                car(-1, -1.4f, com.vishucraft.game.engine.Cart.COACH).leader = metro
                val eng = car(2, 6f, com.vishucraft.game.engine.Cart.ENGINE)
                val c1 = car(2, 3.1f, com.vishucraft.game.engine.Cart.COACH).also { it.leader = eng }
                car(2, 0.2f, com.vishucraft.game.engine.Cart.COACH).leader = c1
                p.x = bx + 0.5f - sx * 9f + rx * 3f; p.z = bz + 0.5f - sz * 9f + rz * 3f; p.y = y + 4f; p.pitch = -0.3f
            }
            f == 1030 -> shot("04l-metro-and-train")
            f == 1031 -> session?.game?.let { g -> g.carts.list.firstOrNull { it.kind == com.vishucraft.game.engine.Cart.METRO }?.let { g.carts.enter(it, it.hx, it.hz); g.player.pitch = 0f } }
            f == 1060 -> shot("04m-inside-the-metro")
            f == 1061 -> session?.game?.let { g -> g.carts.leave(g); g.carts.list.clear() }
            f == 1062 -> session?.game?.let { g ->
                // Ready-made buildings side by side: a small house, a farm and a modern house.
                val p = g.player
                p.flying = true; p.vy = 0f
                val fx = kotlin.math.round(kotlin.math.sin(p.yaw)).toInt(); val fz = kotlin.math.round(-kotlin.math.cos(p.yaw)).toInt()
                val (sx, sz) = if (fx != 0) fx to 0 else 0 to (if (fz == 0) -1 else fz)
                p.yaw = kotlin.math.atan2(sx.toFloat(), -sz.toFloat())
                val rx = -sz; val rz = sx
                val bx = com.vishucraft.game.world.floorInt(p.x) + sx * 8 - rx * 70; val bz = com.vishucraft.game.world.floorInt(p.z) + sz * 8 - rz * 70
                val y = g.world.generator.surfaceHeight(bx, bz)
                for ((i, name) in listOf("Small House", "Farm", "Modern House").withIndex()) {
                    val off = (i - 1) * 13
                    g.placeBuilding(com.vishucraft.game.engine.RayHit(bx + rx * off, y, bz + rz * off, 0, 1, 0, Blocks.GRASS), name)
                }
                p.x = bx + 0.5f - sx * 9f; p.z = bz + 0.5f - sz * 9f; p.y = y + 14f; p.pitch = -0.6f
            }
            f == 1100 -> shot("04n-ready-made-buildings")
            f == 1101 -> session?.game?.let { g ->
                // The mansion blueprint, seen from the front garden.
                val p = g.player
                val bx = com.vishucraft.game.world.floorInt(p.x) + 90; val bz = com.vishucraft.game.world.floorInt(p.z)
                val y = g.world.generator.surfaceHeight(bx, bz)
                p.x = bx + 0.5f; p.z = bz + 12.5f; p.y = y + 12f; p.yaw = 0f; p.pitch = -0.35f; p.flying = true
                g.placeBuilding(com.vishucraft.game.engine.RayHit(bx, y, bz, 0, 1, 0, Blocks.GRASS), "Mansion")
            }
            f == 1108 -> shot("04o-mansion")
            f == 1109 -> session?.game?.let { g -> val p = g.player; p.z -= 21f; p.y -= 11f; p.pitch = -0.1f }
            f == 1110 -> shot("04p-mansion-inside")
            f == 1111 -> session?.game?.let { g ->
                // An airport, seen from above the terminal, with the airplane and helicopter taking off.
                val p = g.player
                val bx = com.vishucraft.game.world.floorInt(p.x) + 200; val bz = com.vishucraft.game.world.floorInt(p.z)
                val y = g.world.generator.surfaceHeight(bx, bz)
                p.yaw = 0f
                g.placeBuilding(com.vishucraft.game.engine.RayHit(bx, y, bz, 0, 1, 0, Blocks.GRASS), "Airport")
                g.aircraft.list.lastOrNull { !it.isPlane }?.let { it.y += 6f }
                p.x = bx + 30.5f; p.z = bz - 2.5f; p.y = y + 22f; p.yaw = -0.9f; p.pitch = -0.45f; p.flying = true
            }
            f == 1150 -> shot("04q-airport")
            f == 1151 -> session?.game?.let { g ->
                // Inside the airplane's cabin.
                val a = g.aircraft.list.first { it.isPlane }
                g.aircraft.riding = a; g.player.yaw = a.yaw + 0.5f; g.player.pitch = -0.1f
            }
            f == 1165 -> shot("04r-airplane-cabin")
            f == 1166 -> session?.game?.let { g ->
                g.aircraft.riding = null
                // The smart city, seen from the sky behind the first road.
                val p = g.player
                val bx = com.vishucraft.game.world.floorInt(p.x) + 200; val bz = com.vishucraft.game.world.floorInt(p.z) - 200
                val y = g.world.generator.surfaceHeight(bx, bz)
                p.yaw = 0f
                g.placeBuilding(com.vishucraft.game.engine.RayHit(bx, y, bz, 0, 1, 0, Blocks.GRASS), "Smart City")
                p.x = bx + 0.5f; p.z = bz + 14.5f; p.y = y + 40f; p.yaw = 0f; p.pitch = -0.45f; p.flying = true
            }
            f == 1215 -> shot("04s-smart-city")
            f == 1216 -> session?.game?.let { g ->
                // Street level on the main road, cars and a bus coming.
                val p = g.player
                p.x += 2f; p.z -= 23f; p.y -= 37.5f; p.yaw = 0.1f; p.pitch = -0.05f
            }
            f == 1235 -> shot("04t-city-street")
            f == 1236 -> session?.game?.let { g ->
                // Up on the west metro platform.
                val p = g.player
                p.x -= 34f; p.z -= 36f; p.y += 11f; p.yaw = 1.5708f; p.pitch = -0.1f
            }
            f == 1255 -> shot("04u-city-metro")
            f == 1256 -> session?.game?.let { g ->
                // On the far platform, looking at the station's billboard across the tracks.
                val p = g.player
                p.x -= 4f; p.z -= 8f; p.y -= 0.5f; p.yaw = 3.14159f; p.pitch = 0.3f
            }
            f == 1260 -> shot("04v-station-billboard")
            f == 1261 -> overlay = Overlay.PAUSE
            f == 1263 -> shot("05-pause")
            f == 1267 -> { overlay = Overlay.NONE; leaveWorld(); menu = Menu.CREATE; nameField.text = "Demo survival"; seedField.text = "777"; survival = true }
            f == 1269 -> demoCreate = true
            f == 1500 -> shot("06-survival-world")
            f == 1502 -> session?.let {
                it.game.inventory.add(com.vishucraft.game.world.Items.find("Minecart"), 1)
                it.game.inventory.add(Blocks.LOG, 8)
                openInventory()
            }
            f == 1506 -> shot("07-survival-inventory")
            f == 1508 -> { closeOverlay(); openContainer(ContainerScreen.Mode.CRAFTING) }
            f == 1512 -> shot("08-crafting-table")
            f == 1514 -> { closeOverlay(); leaveWorld(); menu = Menu.WORLDS }
            f == 1518 -> shot("09-worlds")
            f == 1520 -> glfwSetWindowShouldClose(window, true)
        }
    }
}
