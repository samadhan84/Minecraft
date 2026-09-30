package com.vishucraft.desktop

import com.vishucraft.game.engine.placeBuilding
import com.vishucraft.game.engine.flyTo
import com.vishucraft.game.engine.liftTo
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
            javax.swing.JOptionPane.showMessageDialog(null, "DhruvVishu stopped because of an error:\n\n${e}\n\nDetails were saved to ${File(Paths.home, "crash.txt")}",
                "DhruvVishu", javax.swing.JOptionPane.ERROR_MESSAGE)
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
        "Minecart: place it on a rail, Space next to it to get in, Shift to get out\n" +
        "1-9 or mouse wheel: choose hotbar slot    ·    Q: drop item (Ctrl+Q: whole stack)\n" +
        "E: inventory & crafting    ·    Esc: pause menu\n" +
        "F3: FPS & coordinates    ·    F11: fullscreen    ·    F2: screenshot"

class App(private val demo: File?) {
    private enum class Menu { TITLE, WORLDS, CREATE, JOIN, SETTINGS, CONTROLS, CONFIRM_DELETE }
    private enum class Overlay { NONE, PAUSE, CREATIVE, CONTAINER, SETTINGS, CONTROLS, NAME_MOB, TRADE, ACHIEVEMENTS, SIGN, ENDING, INTERNET, FLIGHTS, FLOORS }

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
    private val addressField = Ui.Field("", "Host address: 192.168.1.23, a VPN or internet address")
    private val roomField = Ui.Field("", "Room code (6 numbers)", 8)
    /** The online room this world is open in (host), if any. */
    @Volatile private var onlineRoom: com.vishucraft.game.net.HostTunnel? = null
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
        window = glfwCreateWindow(1280, 720, "DhruvVishu", NULL, NULL)
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

        if (demo == null) {
            playLimit.tick(com.vishucraft.game.engine.PlayLimit.today(), dt)?.let { hud?.toast(it, 5f) }
            limitSaveTimer += dt
            if (limitSaveTimer > 10f || playLimit.over) { limitSaveTimer = 0f; prefs.savePlayLimit(playLimit) }
            if (playLimit.over) { leaveWorld(); menu = Menu.TITLE; limitNotice = true; return }
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
            Overlay.NAME_MOB -> nameMobScreen(s)
            Overlay.TRADE -> tradeScreen(s)
            Overlay.ACHIEVEMENTS -> achievementsScreen(s)
            Overlay.SIGN -> signScreen(s)
            Overlay.FLIGHTS -> flightsScreen(s)
            Overlay.FLOORS -> floorsScreen(s)
            Overlay.ENDING -> endingScreen()
            Overlay.INTERNET -> internetScreen()
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
            e == "ending" -> overlay = Overlay.ENDING
            e == "open:ender" -> openContainer(ContainerScreen.Mode.CHEST, chest = s.level.enderChest)
            e.startsWith("signedit:") -> {
                signPos = e.removePrefix("signedit:").split(',').map { it.toInt() }
                signField.text = s.game.signText(signPos[0], signPos[1], signPos[2]); ui.focus = signField; overlay = Overlay.SIGN
            }
            e.startsWith("floors:") -> { floorsEvent = e.removePrefix("floors:").split(',', limit = 4); overlay = Overlay.FLOORS }
            e.startsWith("flights:") -> { flights = e.removePrefix("flights:").split('|'); overlay = Overlay.FLIGHTS }
            e.startsWith("achievement:") -> hud.toast("Achievement unlocked!\n" + e.removePrefix("achievement:"), 4f)
            e.startsWith("trade:") -> { tradeUid = e.removePrefix("trade:").toIntOrNull() ?: -1; tradeMessage = ""; overlay = Overlay.TRADE }
            e.startsWith("name:") -> { namingUid = e.removePrefix("name:").toIntOrNull() ?: -1; nameField.text = ""; ui.focus = nameField; overlay = Overlay.NAME_MOB }
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

    /** Today's play time; the demo run doesn't count (see engine/PlayLimit). */
    private val playLimit by lazy { prefs.playLimit() }
    private var limitSaveTimer = 0f
    /** Shown on the title screen once the daily limit is over. */
    private var limitNotice = false

    private fun startSession(s: GameSession) {
        if (demo == null && playLimit.minutesLeft() <= 0) {
            // No playing until tomorrow: close the world that was just opened.
            s.close(); session = null; overlay = Overlay.NONE; setCapture(false)
            menu = Menu.TITLE; limitNotice = true
            return
        }
        val first = session == null
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
        if (first && demo == null) hud?.toast("Play time left today: ${playLimit.minutesLeft()} minutes", 4f)
    }

    private fun leaveWorld() {
        if (demo == null && session != null) prefs.savePlayLimit(playLimit)
        onlineRoom?.let { r -> Thread { r.close() }.start() }
        onlineRoom = null
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
            g.weatherEnabled = prefs.weather
            g.fancyGraphics = prefs.fancy
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
                if (limitNotice) {
                    title(com.vishucraft.game.engine.PlayLimit.TITLE, ui.height * 0.3f)
                    for ((k, line) in com.vishucraft.game.engine.PlayLimit.MESSAGE.split("\n").filter { it.isNotBlank() }.withIndex())
                        ui.text(line, cx, ui.height * 0.3f + 60 + k * 30, 20f, rgba(230, 230, 230), 1)
                    if (ui.button("OK", cx - 100, ui.height * 0.62f, 200f)) limitNotice = false
                    return
                }
                ui.text("DhruvVishu", cx, ui.height * 0.16f, 72f, rgba(255, 220, 90), 1)
                ui.text("Build, explore and survive", cx, ui.height * 0.16f + 84, 20f, rgba(220, 220, 220), 1)
                var y = ui.height * 0.42f
                if (ui.button("Play", cx - bw / 2, y, bw, 44f)) menu = Menu.WORLDS
                y += 54
                if (ui.button("Join a game (online or Wi-Fi)", cx - bw / 2, y, bw, 44f)) { menu = Menu.JOIN; scan() }
                y += 54
                if (ui.button("Settings", cx - bw / 2, y, bw / 2 - 5, 44f)) menu = Menu.SETTINGS
                if (ui.button("Controls", cx + 5, y, bw / 2 - 5, 44f)) menu = Menu.CONTROLS
                y += 54
                if (ui.button("Quit", cx - bw / 2, y, bw, 44f)) glfwSetWindowShouldClose(window, true)
                ui.text("Worlds are saved in ${Paths.worlds}", 10f, ui.height - 26, 14f, rgba(170, 170, 170))
                val version = "Version " + (App::class.java.`package`?.implementationVersion ?: "dev")
                ui.text(version, ui.width - ui.textWidth(version, 16f) - 14, 12f, 16f, rgba(230, 230, 230))
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
        if (ui.button("Game mode: " + if (survival) "Survival" else "Creative (flat world)", cx - w / 2, y, w, 42f)) survival = !survival
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

    private var namingUid = -1
    private var signPos = listOf(0, 0, 0)
    private val signField = Ui.Field("", "Text on the sign", 60)

    @Volatile private var internetText = ""
    private var internetTitle = "Play over the internet"

    private fun roomText(code: String) = "Room code: ${code.chunked(3).joinToString(" ")}\n\n" +
        "Friends anywhere (Wi-Fi or mobile data) choose \"Join online game\" on the DhruvVishu title screen and type this code.\n" +
        "Keep the game open while they play."

    private fun internetScreen() {
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 200))
        title(internetTitle, 50f)
        var y = 110f
        val words = internetText.ifEmpty { if (internetTitle == "Play online") "Opening your game online…" else "Checking your internet address…" }
        // Simple word wrap.
        for (para in words.split('\n')) {
            var line = ""
            for (w in para.split(' ')) {
                if (ui.textWidth("$line $w", 17f) > minOf(900f, ui.width - 80)) { ui.text(line, ui.width / 2, y, 17f, -1, 1); y += 24; line = w }
                else line = if (line.isEmpty()) w else "$line $w"
            }
            ui.text(line, ui.width / 2, y, 17f, -1, 1); y += 24
        }
        if (ui.button("Back", ui.width / 2 - 120, y + 20, 240f, 44f)) overlay = Overlay.PAUSE
    }

    private fun endingScreen() {
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 220))
        title("The End", ui.height * 0.15f)
        var y = ui.height * 0.15f + 70
        val text = "You defeated the Sky Warden and freed the Sky Isles!\n\n" +
                "It dropped an Ember Star, Glider Wings, diamonds and a Totem of Life.\n\n" +
                "Thank you for playing DhruvVishu.\n\n" +
                "Made for Dhruv and Vishu.\n" +
                "Every block, creature, sound and song in this game was made from code.\n\n" +
                "The world is still yours: keep building, exploring and playing."
        for (line in text.split('\n')) { ui.text(line, ui.width / 2, y, 19f, rgba(230, 230, 255), 1); y += 28 }
        if (ui.button("Keep playing", ui.width / 2 - 140, y + 20, 280f, 44f)) overlay = Overlay.NONE
    }

    private var flights = listOf<String>()
    private var floorsEvent = listOf<String>()

    /** Tapped an elevator: pick a floor. */
    private fun floorsScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 420f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Elevator", ui.height * 0.15f)
        val (lx, lz, cur, list) = floorsEvent
        var y = ui.height * 0.15f + 60
        for ((i, name) in list.split('|').withIndex().toList().take(10)) {
            val label = if (i == cur.toInt()) "$name  (you are here)" else name
            if (ui.button(label, cx - w / 2, y, w, 40f)) { s.game.liftTo(lx.toInt(), lz.toInt(), i); overlay = Overlay.NONE }
            y += 46
        }
        if (ui.button("Cancel", cx - w / 2, y + 10, w, 40f)) overlay = Overlay.NONE
    }

    /** Got into an airplane: pick an airport to fly to. */
    private fun flightsScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 420f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Where do you want to fly?", ui.height * 0.2f)
        var y = ui.height * 0.2f + 60
        for (name in flights.take(8)) {
            if (ui.button(name, cx - w / 2, y, w, 44f)) { s.game.flyTo(name); overlay = Overlay.NONE }
            y += 52
        }
        if (ui.button("Not now", cx - w / 2, y + 10, w, 44f)) overlay = Overlay.NONE
    }

    private fun signScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 560f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Sign", ui.height * 0.25f)
        ui.field(signField, cx - w / 2, ui.height * 0.4f, w)
        val y = ui.height * 0.4f + 60
        if (ui.button("OK", cx - w / 2, y, w / 2 - 5, 44f) || ui.takeSubmit()) {
            s.game.setSignText(signPos[0], signPos[1], signPos[2], signField.text); ui.focus = null; overlay = Overlay.NONE
        }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { ui.focus = null; overlay = Overlay.NONE }
    }
    private var tradeUid = -1
    private var tradeMessage = ""

    /** A villager's offers: click one to trade. */
    private fun tradeScreen(s: GameSession) {
        val job = com.vishucraft.game.world.Trades.jobFor(tradeUid)
        val cx = ui.width / 2; val w = 560f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 160))
        title("${job.title} · trades", 50f)
        var y = 100f
        for ((i, t) in job.offers.withIndex()) {
            val have = s.game.inventory.count(t.give)
            val ok = !s.game.survival || have >= t.giveCount
            val x = cx - w / 2
            if (ui.button("", x, y, w, 42f, ok)) tradeMessage = s.game.trade(tradeUid, i)
            ui.stack(com.vishucraft.game.world.ItemStack(t.give, t.giveCount), x + 10, y + 3, 36f)
            ui.text("→", x + 70, y + 9, 20f, -1)
            ui.stack(com.vishucraft.game.world.ItemStack(t.get, t.getCount), x + 100, y + 3, 36f)
            ui.text("${t.giveCount} ${com.vishucraft.game.world.Items.displayName(t.give)} for ${t.getCount} ${com.vishucraft.game.world.Items.displayName(t.get)}" +
                if (s.game.survival) "  (you have $have)" else "", x + 150, y + 11, 15f, if (ok) -1 else rgba(180, 180, 180))
            y += 48
        }
        if (tradeMessage.isNotEmpty()) ui.text(tradeMessage, cx, y + 8, 18f, rgba(255, 230, 150), 1)
        if (ui.button("Done", cx - 120, y + 40, 240f, 42f)) overlay = Overlay.NONE
    }

    private fun achievementsScreen(s: GameSession) {
        val got = s.level.achievements
        val list = com.vishucraft.game.world.Achievements.all
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 170))
        title("Achievements ${list.count { it.key in got }} / ${list.size}", 40f)
        val colW = 440f
        for ((i, a) in list.withIndex()) {
            val col = i / 12; val row = i % 12
            val x = ui.width / 2 - colW + col * colW + 10; val y = 90f + row * 36
            val done = a.key in got
            ui.text((if (done) "✓ " else "○ ") + a.title, x, y, 17f, if (done) rgba(140, 255, 120) else rgba(200, 200, 200))
            ui.text(a.hint, x + 24, y + 18, 12f, rgba(170, 170, 170))
        }
        if (ui.button("Back", ui.width / 2 - 120, ui.height - 70, 240f, 42f)) overlay = Overlay.PAUSE
    }

    /** Name tag: type a name for the creature. */
    private fun nameMobScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 420f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Name this creature", ui.height * 0.25f)
        ui.field(nameField, cx - w / 2, ui.height * 0.4f, w)
        val y = ui.height * 0.4f + 60
        if (ui.button("OK", cx - w / 2, y, w / 2 - 5, 44f) || ui.takeSubmit()) { s.game.nameMob(namingUid, nameField.text); ui.focus = null; overlay = Overlay.NONE }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { ui.focus = null; overlay = Overlay.NONE }
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

    /** Joins a friend's game anywhere through the online relay, with the room code they see. */
    private fun connectRoom(code: String) {
        if (joining != null) return
        joining = "room $code"
        message = null
        val name = prefs.playerName
        Thread({
            var tunnel: com.vishucraft.game.net.GuestTunnel? = null
            try {
                tunnel = com.vishucraft.game.net.GuestTunnel.join(code)
                joined = ClientSession.connect(tunnel.localAddress, name, 12000)
            } catch (e: Exception) { tunnel?.close(); message = e.message ?: "Could not join room $code" }
            joining = null
        }, "join-online").start()
    }

    private fun joinScreen() {
        val cx = ui.width / 2
        val w = 520f
        title("Join a game")
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
        ui.text("Online: type the room code your friend sees:", cx - w / 2, y - 24, 16f, rgba(255, 220, 90))
        ui.field(roomField, cx - w / 2, y, w - 130)
        if (ui.button("Join", cx + w / 2 - 120, y, 120f, 36f, roomField.text.isNotBlank() && joining == null)) connectRoom(roomField.text.trim())
        y += 76
        ui.text("Or type the host's Wi-Fi / VPN address:", cx - w / 2, y - 24, 16f, rgba(220, 220, 220))
        ui.field(addressField, cx - w / 2, y, w - 130)
        if (ui.button("Join", cx + w / 2 - 120, y, 120f, 36f, addressField.text.isNotBlank() && joining == null)) connect(addressField.text.trim())
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
        // The main three are full width; the rest go in two columns so everything fits on a 720p screen.
        var twoColumns = false
        var column = 0
        fun b(label: String, action: () -> Unit) {
            if (!twoColumns) { if (ui.button(label, cx - bw / 2, y, bw, 38f)) action(); y += 44; return }
            val w = 330f
            if (ui.button(label, if (column == 0) cx - w - 5 else cx + 5, y, w, 38f)) action()
            if (column == 1) y += 44
            column = 1 - column
        }
        b("Back to game") { overlay = Overlay.NONE }
        b("Save game") { if (game.isClient) hud?.toast("The host saves this world") else { s.save(); hud?.toast("Game saved") } }
        b("Save and quit to title") { leaveWorld(); menu = Menu.TITLE }
        y += 10
        twoColumns = true
        b(if (game.survival) "Mode: Survival (switch to Creative)" else "Mode: Creative (switch to Survival)") {
            if (game.isClient) hud?.toast("The host picks the game mode")
            else { hud?.toast(game.switchMode(), 4f); overlay = Overlay.NONE }
        }
        b("Skip to next morning / night") { game.timeOfDay = if (game.daylight > 0.5f) 0.52f else 0.0f }
        b(if (game.mobs.hostileEnabled) "Mobs: Normal" else "Mobs: Peaceful (no monsters)") { game.mobs.hostileEnabled = !game.mobs.hostileEnabled }
        b(if (prefs.weather) "Weather: On" else "Weather: Off") { prefs.weather = !prefs.weather; applySettings() }
        b(when { game.isClient -> "Wi-Fi: joined"; game.net != null -> "Wi-Fi: open (${Net.localAddress()})"; else -> "Open to Wi-Fi" }) {
            if (game.net != null) hud?.toast(game.net!!.status)
            else {
                try {
                    game.net = HostSession(game, s.level.name, prefs.playerName)
                    hud?.toast("Open! Friends on the same Wi-Fi can join from the title screen.\nYour address: ${Net.localAddress()}", 5f)
                } catch (e: Exception) { hud?.toast("Could not open the game: ${e.message}", 4f) }
            }
        }
        b(onlineRoom?.takeIf { it.open }?.let { "Online: room ${it.code}" } ?: "Play online (room code)") {
            if (game.isClient) { hud?.toast("Only the host can invite more friends"); return@b }
            internetTitle = "Play online"
            internetText = ""; overlay = Overlay.INTERNET
            val room = onlineRoom?.takeIf { it.open }
            if (room != null) internetText = roomText(room.code)
            else {
                if (game.net == null) try { game.net = HostSession(game, s.level.name, prefs.playerName) } catch (e: Exception) { internetText = "Could not open the game: ${e.message}"; return@b }
                Thread {
                    internetText = try { com.vishucraft.game.net.HostTunnel.start().also { onlineRoom = it }.let { roomText(it.code) } }
                    catch (e: Exception) { "Couldn't open online: ${e.message}" }
                }.start()
            }
        }
        b("Other ways to play online") {
            internetTitle = "Play over the internet"
            internetText = ""; overlay = Overlay.INTERNET
            Thread { internetText = Net.internetHelp(Net.internetAddress()) }.start()
        }
        b("Achievements") { overlay = Overlay.ACHIEVEMENTS }
        b("Settings") { overlay = Overlay.SETTINGS }
        b("Controls") { overlay = Overlay.CONTROLS }
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
