package com.vishucraft.desktop

import com.vishucraft.desktop.Ui.Companion.rgba
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
            javax.swing.JOptionPane.showMessageDialog(null, "DhruvilCraft stopped because of an error:\n\n${e}\n\nDetails were saved to ${File(Paths.home, "crash.txt")}",
                "DhruvilCraft", javax.swing.JOptionPane.ERROR_MESSAGE)
        } catch (_: Throwable) {}
        kotlin.system.exitProcess(1)
    }
}

/** Writes icon.png and a Windows icon.ico (PNG-compressed entries, 16 to 256 px). */
fun writeIcons(dir: File) {
    dir.mkdirs()
    ImageIO.write(IconAtlas.appIcon(256), "png", File(dir, "icon.png"))
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
    val pngs = sizes.map { s -> java.io.ByteArrayOutputStream().also { ImageIO.write(IconAtlas.appIcon(s), "png", it) }.toByteArray() }
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
        "1-9 or mouse wheel: choose hotbar slot\n" +
        "E: inventory & crafting    ·    Esc: pause menu\n" +
        "F3: FPS & coordinates    ·    F11: fullscreen    ·    F2: screenshot"

class App(private val demo: File?) {
    private enum class Menu { TITLE, WORLDS, CREATE, JOIN, SETTINGS, CONTROLS, CONFIRM_DELETE }
    private enum class Overlay { NONE, PAUSE, CREATIVE, CONTAINER, SETTINGS, CONTROLS }

    private var window = NULL
    private val prefs = Prefs()
    private lateinit var ui: Ui
    private lateinit var audio: Audio
    private var fbW = 1280
    private var fbH = 720

    private var menu = Menu.TITLE
    private var overlay = Overlay.NONE
    private var session: GameSession? = null
    private var hud: Hud? = null
    private var creative: CreativeScreen? = null
    private var container: ContainerScreen? = null
    private var message: String? = null

    // Create-world form
    private val nameField = Ui.Field("", "World name")
    private val seedField = Ui.Field("", "Seed (leave empty for random)")
    private var survival = true
    private var deleteTarget: File? = null

    // Join form
    private val addressField = Ui.Field("", "Host address, e.g. 192.168.1.23")
    @Volatile private var hosts: List<Discovery.Host> = emptyList()
    @Volatile private var scanning = false
    @Volatile private var joining: String? = null
    @Volatile private var joined: ClientSession? = null

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
        window = glfwCreateWindow(1280, 720, "DhruvilCraft", NULL, NULL)
        if (window == NULL) error("Could not open a window. Please update your graphics driver (OpenGL 2.1 is needed).")
        glfwMakeContextCurrent(window)
        GL.createCapabilities()
        glfwSwapInterval(1)
        setIcon()
        glfwShowWindow(window)
        if (prefs.fullscreen && demo == null) setFullscreen(true)
        installCallbacks()

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
            frame(dt, fps)
            demoStep()
            glfwSwapBuffers(window)
        }
        leaveWorld()
        audio.close()
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    // ---------------------------------------------------------------- frame

    private fun frame(dt: Float, fps: Int) {
        val s = session
        val mx = DoubleArray(1); val my = DoubleArray(1)
        glfwGetCursorPos(window, mx, my)
        val winW = IntArray(1); val winH = IntArray(1)
        glfwGetWindowSize(window, winW, winH)
        val ratio = if (winW[0] > 0) fbW.toFloat() / winW[0] else 1f
        ui.shift = keys[GLFW_KEY_LEFT_SHIFT] || keys[GLFW_KEY_RIGHT_SHIFT]
        if (s == null) {
            glViewport(0, 0, fbW, fbH)
            glClearColor(0.1f, 0.1f, 0.12f, 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            ui.begin(fbW, fbH)
            ui.mouseX = mx[0].toFloat() * ratio / ui.scale; ui.mouseY = my[0].toFloat() * ratio / ui.scale
            menus()
            ui.end()
            return
        }

        updateGameInput(s, dt)
        val events = ArrayList<String>()
        s.frame(dt, events)
        for (e in events) onGameEvent(s, e)
        if (session !== s) return // travelled to another dimension this frame
        val game = s.game
        val exposed = game.mobs.skyExposed(game.player.blockX(), game.player.blockY(), game.player.blockZ())
        audio.setRain(if (game.rain > 0.05f) game.rain * (if (exposed) 1f else 0.3f) else 0f)

        ui.begin(fbW, fbH)
        ui.mouseX = mx[0].toFloat() * ratio / ui.scale; ui.mouseY = my[0].toFloat() * ratio / ui.scale
        val p = game.player
        val debug = if (prefs.showDebug) {
            val hours = ((game.timeOfDay * 24 + 6) % 24).toInt()
            val mins = (((game.timeOfDay * 24 + 6) % 1) * 60).toInt()
            "%d fps  XYZ %.1f / %.1f / %.1f  %02d:%02d%s".format(fps, p.x, p.y, p.z, hours, mins, if (p.flying) "  [flying]" else "") +
                "\n" + game.world.generator.biomeAt(p.blockX(), p.blockZ()).name.lowercase().replaceFirstChar { it.uppercase() } +
                "  mobs ${game.mobs.list.size}" + (game.net?.let { "\n" + it.status } ?: "")
        } else null
        hud!!.draw(ui, dt, debug, overlay == Overlay.NONE || overlay == Overlay.PAUSE)
        when (overlay) {
            Overlay.NONE -> {}
            Overlay.PAUSE -> pauseMenu(s)
            Overlay.CREATIVE -> if (!creative!!.draw(ui)) closeOverlay()
            Overlay.CONTAINER -> if (!container!!.draw(ui)) closeOverlay()
            Overlay.SETTINGS -> settingsScreen { overlay = Overlay.PAUSE }
            Overlay.CONTROLS -> controlsScreen { overlay = Overlay.PAUSE }
        }
        ui.end()
    }

    private fun updateGameInput(s: GameSession, dt: Float) {
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

    private fun releaseInputs(input: GameInput) {
        input.moveForward = 0f; input.moveStrafe = 0f
        input.jumpHeld = false; input.descendHeld = false; input.breakHeld = false; input.sprint = false
        leftDown = false; rightDown = false
    }

    private fun setCapture(on: Boolean) {
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

    private fun closeOverlay() {
        if (overlay == Overlay.CONTAINER) container?.close()
        overlay = Overlay.NONE
        ui.focus = null
    }

    // ---------------------------------------------------------------- game events

    private fun onGameEvent(s: GameSession, e: String) {
        val hud = hud!!
        val world = s.world
        fun pos() = e.substringAfterLast(':').split(',').map { it.toInt() }
        when {
            e == "hurt" -> hud.hurt = 1f
            e == "died" -> hud.toast("You died! Respawning…")
            e == "sleep" -> hud.sleep = 3f
            e.startsWith("toast:") -> hud.toast(e.removePrefix("toast:"), 3f)
            e == "craft" -> audio.play("craft")
            e == "open:craft" -> openContainer(ContainerScreen.Mode.CRAFTING)
            e.startsWith("open:hopper:") -> { val (x, y, z) = pos(); openContainer(ContainerScreen.Mode.CHEST, chest = world.blockEntities.hopper(x, y, z)) }
            e.startsWith("open:chest:") -> { val (x, y, z) = pos(); openContainer(ContainerScreen.Mode.CHEST, chest = world.blockEntities.chest(x, y, z)) }
            e.startsWith("open:furnace:") -> { val (x, y, z) = pos(); openContainer(ContainerScreen.Mode.FURNACE, furnace = world.blockEntities.furnace(x, y, z)) }
            e.startsWith("dimension:") -> travel(s, Dimension.valueOf(e.removePrefix("dimension:")))
        }
    }

    private fun openContainer(m: ContainerScreen.Mode, chest: com.vishucraft.game.world.ChestEntity? = null, furnace: com.vishucraft.game.world.FurnaceEntity? = null) {
        container!!.open(m, chest, furnace)
        overlay = Overlay.CONTAINER
    }

    private fun openInventory() {
        val s = session ?: return
        if (s.game.survival) openContainer(ContainerScreen.Mode.INVENTORY) else overlay = Overlay.CREATIVE
    }

    /** Saves and reopens the world in the other dimension. */
    private fun travel(s: GameSession, target: Dimension) {
        val dir = s.dir ?: return
        if (s.game.net != null) { hud?.toast("Portals are closed during Wi-Fi games"); return }
        s.save()
        s.level.dimension = target
        s.level.arriving = true
        s.level.write(dir)
        s.close()
        val next = GameSession(dir, s.level)
        startSession(next)
        val name = when (target) { Dimension.EMBER -> "the Ember Realm"; Dimension.SKY -> "the Sky Isles"; Dimension.OVERWORLD -> "the Overworld" }
        hud?.toast("Welcome to $name")
    }

    private fun startSession(s: GameSession) {
        session = s
        s.resize(fbW, fbH)
        s.game.soundSink = { name, x, y, z, gain ->
            if (name.startsWith("mat:")) audio.play(com.vishucraft.game.audio.Synth.material(name.substring(4).toInt()), x, y, z, gain, 0.9f + Math.random().toFloat() * 0.2f)
            else audio.play(name, x, y, z, gain)
        }
        s.game.listener = { x, y, z, yaw -> audio.setListener(x, y, z, yaw) }
        hud = Hud(s.game)
        creative = CreativeScreen(s.game)
        container = ContainerScreen(s.game)
        overlay = Overlay.NONE
        applySettings()
    }

    private fun leaveWorld() {
        val s = session ?: return
        container?.close()
        s.close()
        session = null
        overlay = Overlay.NONE
        setCapture(false)
        audio.setRain(0f)
    }

    private fun applySettings() {
        audio.volume = prefs.soundVolume / 100f
        audio.musicVolume = prefs.musicVolume / 100f
        session?.game?.let { g ->
            g.lookScale = prefs.sensitivity
            g.fov = prefs.fov.toFloat()
            g.renderDistance = prefs.renderDistance
        }
    }

    // ---------------------------------------------------------------- menus

    private fun background() {
        // Darkened dirt, like a cave wall.
        val t = 64f
        val tile = Blocks[Blocks.DIRT].top
        var y = 0f
        while (y < ui.height) { var x = 0f; while (x < ui.width) { ui.tile(tile, x, y, t, t, rgba(90, 90, 90)); x += t }; y += t }
    }

    private fun title(text: String, y: Float = 60f) = ui.text(text, ui.width / 2, y, 34f, -1, 1)

    private fun menus() {
        background()
        val cx = ui.width / 2
        val bw = 400f
        when (menu) {
            Menu.TITLE -> {
                ui.text("DhruvilCraft", cx, ui.height * 0.16f, 72f, rgba(255, 220, 90), 1)
                ui.text("Build, explore and survive", cx, ui.height * 0.16f + 84, 20f, rgba(220, 220, 220), 1)
                var y = ui.height * 0.42f
                if (ui.button("Play", cx - bw / 2, y, bw, 44f)) menu = Menu.WORLDS
                y += 54
                if (ui.button("Join Wi-Fi game", cx - bw / 2, y, bw, 44f)) { menu = Menu.JOIN; scan() }
                y += 54
                if (ui.button("Settings", cx - bw / 2, y, bw / 2 - 5, 44f)) menu = Menu.SETTINGS
                if (ui.button("Controls", cx + 5, y, bw / 2 - 5, 44f)) menu = Menu.CONTROLS
                y += 54
                if (ui.button("Quit", cx - bw / 2, y, bw, 44f)) glfwSetWindowShouldClose(window, true)
                ui.text("Worlds are saved in ${Paths.worlds}", 10f, ui.height - 26, 14f, rgba(170, 170, 170))
            }
            Menu.WORLDS -> worldsScreen()
            Menu.CREATE -> createScreen()
            Menu.JOIN -> joinScreen()
            Menu.SETTINGS -> settingsScreen { menu = Menu.TITLE }
            Menu.CONTROLS -> controlsScreen { menu = Menu.TITLE }
            Menu.CONFIRM_DELETE -> {
                val d = deleteTarget
                val name = d?.let { LevelData.read(it)?.name } ?: "?"
                title("Delete \"$name\"?", ui.height * 0.3f)
                ui.text("This cannot be undone.", cx, ui.height * 0.3f + 50, 20f, rgba(255, 180, 180), 1)
                if (ui.button("Delete", cx - 205, ui.height * 0.5f, 200f)) { d?.deleteRecursively(); menu = Menu.WORLDS }
                if (ui.button("Cancel", cx + 5, ui.height * 0.5f, 200f)) menu = Menu.WORLDS
            }
        }
        message?.let { m ->
            val w = ui.textWidth(m, 18f) + 30
            ui.rect(cx - w / 2, ui.height - 80, w, 34f, rgba(0, 0, 0, 200))
            ui.text(m, cx, ui.height - 72, 18f, rgba(255, 220, 160), 1)
        }
    }

    private class WorldInfo(val dir: File, val level: LevelData)

    private fun worlds(): List<WorldInfo> = Paths.worlds.listFiles().orEmpty()
        .mapNotNull { d -> LevelData.read(d)?.let { WorldInfo(d, it) } }
        .sortedByDescending { File(it.dir, "level.dat").lastModified() }

    private var worldScroll = 0f

    private fun worldsScreen() {
        val cx = ui.width / 2
        title("Select world")
        val list = worlds()
        val w = 560f; val rowH = 50f
        val top = 120f; val bottom = ui.height - 90
        val maxScroll = maxOf(0f, list.size * rowH - (bottom - top))
        worldScroll = (worldScroll - ui.wheel * rowH).coerceIn(0f, maxScroll)
        if (list.isEmpty()) ui.text("No worlds yet. Create one!", cx, top + 20, 20f, rgba(220, 220, 220), 1)
        for ((i, wi) in list.withIndex()) {
            val y = top + i * rowH - worldScroll
            if (y < top - 1 || y + rowH > bottom + 1) continue
            val mode = if (wi.level.mode == GameMode.SURVIVAL) "Survival" else "Creative"
            if (ui.button("${wi.level.name}  ·  $mode", cx - w / 2, y, w - 110, 42f)) {
                message = null
                startSession(GameSession(wi.dir, wi.level))
                return
            }
            if (ui.button("Delete", cx + w / 2 - 100, y, 100f, 42f)) { deleteTarget = wi.dir; menu = Menu.CONFIRM_DELETE }
        }
        if (ui.button("Create new world", cx - w / 2, ui.height - 70, w / 2 - 5, 44f)) {
            nameField.text = "World ${list.size + 1}"; seedField.text = ""; survival = true
            menu = Menu.CREATE
        }
        if (ui.button("Back", cx + 5, ui.height - 70, w / 2 - 5, 44f)) menu = Menu.TITLE
    }

    private fun createScreen() {
        val cx = ui.width / 2
        val w = 460f
        title("Create new world")
        var y = 150f
        ui.text("World name", cx - w / 2, y - 24, 16f, rgba(220, 220, 220))
        ui.field(nameField, cx - w / 2, y, w)
        y += 80
        ui.text("Seed", cx - w / 2, y - 24, 16f, rgba(220, 220, 220))
        ui.field(seedField, cx - w / 2, y, w)
        y += 64
        if (ui.button("Game mode: " + if (survival) "Survival" else "Creative", cx - w / 2, y, w, 42f)) survival = !survival
        ui.text(if (survival) "Collect resources, craft tools, watch your health and hunger." else "Unlimited blocks, flying, no damage.",
            cx, y + 50, 16f, rgba(200, 200, 200), 1)
        y += 100
        if (ui.button("Create", cx - w / 2, y, w / 2 - 5, 44f) || (demo != null && demoCreate)) {
            demoCreate = false
            val text = seedField.text.trim()
            val seed = if (text.isEmpty()) System.nanoTime() else text.toLongOrNull() ?: text.hashCode().toLong()
            var n = 1
            while (File(Paths.worlds, "world$n").exists()) n++
            val name = nameField.text.trim().ifEmpty { "World $n" }
            ui.focus = null
            startSession(GameSession.create(File(Paths.worlds, "world$n"), name, seed, survival))
            menu = Menu.WORLDS
            return
        }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { ui.focus = null; menu = Menu.WORLDS }
    }

    private fun scan() {
        if (scanning) return
        scanning = true
        Thread({ hosts = try { Discovery.scan(2500) } catch (_: Exception) { emptyList() }; scanning = false }, "scan").start()
    }

    private fun connect(address: String) {
        if (joining != null) return
        joining = address
        message = null
        val name = prefs.playerName
        Thread({
            val result = try { ClientSession.connect(address, name) } catch (_: Exception) { null }
            if (result == null) message = "No game answered at $address. Are both on the same Wi-Fi, and did the host choose \"Open to Wi-Fi\"?"
            joined = result
            joining = null
        }, "join").start()
    }

    private fun joinScreen() {
        val cx = ui.width / 2
        val w = 520f
        title("Join Wi-Fi game")
        joined?.let { c ->
            joined = null
            val level = LevelData.create(c.seed, c.worldName, c.mode).apply {
                hasPlayer = true; x = c.spawnX + 1.5f; y = c.spawnY + 0.5f; z = c.spawnZ + 0.5f; timeOfDay = c.time
            }
            startSession(GameSession(null, level, c))
            return
        }
        var y = 120f
        when {
            joining != null -> ui.text("Connecting to $joining…", cx, y, 20f, rgba(220, 220, 220), 1)
            scanning -> ui.text("Looking for games on your Wi-Fi…", cx, y, 20f, rgba(220, 220, 220), 1)
            hosts.isEmpty() -> ui.text("No games found. The host must choose \"Open to Wi-Fi\" in the pause menu.", cx, y, 18f, rgba(220, 220, 220), 1)
            else -> for (h in hosts) {
                if (ui.button("${h.name}  ·  ${h.players} playing", cx - w / 2, y, w, 42f)) connect(h.address)
                y += 50
            }
        }
        y = maxOf(y + 60, 260f)
        ui.text("Or type the host's address:", cx - w / 2, y - 24, 16f, rgba(220, 220, 220))
        ui.field(addressField, cx - w / 2, y, w - 130)
        if (ui.button("Join", cx + w / 2 - 120, y, 120f, 36f, addressField.text.isNotBlank())) connect(addressField.text.trim())
        ui.text("Your name: ${prefs.playerName}", cx - w / 2, y + 50, 16f, rgba(200, 200, 200))
        if (ui.button("Search again", cx - w / 2, ui.height - 70, w / 2 - 5, 44f, !scanning)) scan()
        if (ui.button("Back", cx + 5, ui.height - 70, w / 2 - 5, 44f)) { ui.focus = null; message = null; menu = Menu.TITLE }
    }

    private fun settingsScreen(back: () -> Unit) {
        val cx = ui.width / 2
        if (session != null) ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 170))
        title("Settings")
        var y = 110f
        for ((label, change) in prefs.rows()) {
            if (ui.button(label(), cx - 220, y, 440f, 40f)) {
                change()
                applySettings()
                if (label().startsWith("Fullscreen")) setFullscreen(prefs.fullscreen)
            }
            y += 48
        }
        if (ui.button("Done", cx - 220, y + 16, 440f, 44f)) back()
    }

    private fun controlsScreen(back: () -> Unit) {
        val cx = ui.width / 2
        if (session != null) ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 170))
        title("Controls")
        var y = 120f
        for (line in CONTROLS_HELP.split('\n')) { ui.text(line, cx, y, 19f, -1, 1); y += 34 }
        if (ui.button("Done", cx - 200, y + 20, 400f, 44f)) back()
    }

    private fun pauseMenu(s: GameSession) {
        val game = s.game
        val cx = ui.width / 2
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Game menu", ui.height * 0.12f)
        val bw = 420f
        var y = ui.height * 0.12f + 60
        fun b(label: String, action: () -> Unit) { if (ui.button(label, cx - bw / 2, y, bw, 40f)) action(); y += 48 }
        b("Back to game") { overlay = Overlay.NONE }
        b("Skip to next morning / night") { game.timeOfDay = if (game.daylight > 0.5f) 0.52f else 0.0f }
        b(if (game.mobs.hostileEnabled) "Mobs: Normal" else "Mobs: Peaceful (no monsters)") { game.mobs.hostileEnabled = !game.mobs.hostileEnabled }
        b("Weather: " + when { game.rain < 0.5f -> "clear"; !game.thunder -> "rain"; else -> "thunderstorm" } + " (click to change)") {
            val next = when { game.rain < 0.5f -> 1; !game.thunder -> 2; else -> 0 }
            game.setWeather(next > 0, next == 2)
        }
        b(when { game.isClient -> "Wi-Fi: joined"; game.net != null -> "Wi-Fi: open (${Net.localAddress()})"; else -> "Open to Wi-Fi" }) {
            if (game.net != null) hud?.toast(game.net!!.status)
            else {
                try {
                    game.net = HostSession(game, s.level.name, prefs.playerName)
                    hud?.toast("Open! Friends on the same Wi-Fi can join from the title screen.\nYour address: ${Net.localAddress()}", 5f)
                } catch (e: Exception) { hud?.toast("Could not open the game: ${e.message}", 4f) }
            }
        }
        b("Settings") { overlay = Overlay.SETTINGS }
        b("Controls") { overlay = Overlay.CONTROLS }
        b("Save and quit to title") { leaveWorld(); menu = Menu.TITLE }
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
                GLFW_KEY_ESCAPE, GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER, GLFW_KEY_TAB -> ui.focus = null
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
                GLFW_KEY_SPACE -> {
                    val now = glfwGetTime()
                    if (now - lastSpace < 0.3 && !s.game.survival) input.actions.add(GameInput.Action.TOGGLE_FLY)
                    lastSpace = now
                }
                in GLFW_KEY_1..GLFW_KEY_9 -> input.selectedSlot = key - GLFW_KEY_1
            }
            Overlay.PAUSE -> if (key == GLFW_KEY_ESCAPE) overlay = Overlay.NONE
            Overlay.SETTINGS, Overlay.CONTROLS -> if (key == GLFW_KEY_ESCAPE) overlay = Overlay.PAUSE
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
            val img = IconAtlas.appIcon(64)
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
    private var demoCreate = false
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
            f == 407 -> creative?.tab = 4
            f == 409 -> shot("04b-creative-items")
            f == 410 -> creative?.tab = 3
            f == 412 -> shot("04c-creative-redstone")
            f == 413 -> overlay = Overlay.PAUSE
            f == 415 -> shot("05-pause")
            f == 417 -> { leaveWorld(); menu = Menu.CREATE; nameField.text = "Demo survival"; seedField.text = "777"; survival = true }
            f == 419 -> demoCreate = true
            f == 800 -> shot("06-survival-world")
            f == 802 -> session?.let {
                it.game.inventory.add(com.vishucraft.game.world.Items.find("Minecart"), 1)
                it.game.inventory.add(Blocks.LOG, 8)
                openInventory()
            }
            f == 806 -> shot("07-survival-inventory")
            f == 808 -> { closeOverlay(); openContainer(ContainerScreen.Mode.CRAFTING) }
            f == 812 -> shot("08-crafting-table")
            f == 814 -> { closeOverlay(); leaveWorld(); menu = Menu.WORLDS }
            f == 818 -> shot("09-worlds")
            f == 820 -> glfwSetWindowShouldClose(window, true)
        }
    }
}
