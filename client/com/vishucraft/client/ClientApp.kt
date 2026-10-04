package com.vishucraft.client

import android.opengl.GLES20.*
import com.vishucraft.client.Ui.Companion.rgba
import com.vishucraft.game.engine.flyTo
import com.vishucraft.game.engine.liftTo
import com.vishucraft.game.net.ClientSession
import com.vishucraft.game.net.Discovery
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Dimension
import com.vishucraft.game.world.GameMode
import com.vishucraft.game.world.LevelData
import java.io.File

/** Player preferences, kept by each platform (a settings file on computers, the browser's storage on the web). */
interface ClientPrefs {
    var sensitivity: Float
    var fov: Int
    var renderDistance: Int
    var soundVolume: Int
    var musicVolume: Int
    var showDebug: Boolean
    var weather: Boolean
    var fancy: Boolean
    var playerName: String
    /** Label + "change" action for each option, shown as buttons on the settings screen. */
    fun rows(): List<Pair<() -> String, () -> Unit>>
    fun playLimit(): com.vishucraft.game.engine.PlayLimit
    fun savePlayLimit(l: com.vishucraft.game.engine.PlayLimit)
}

/** Sound effects, music and rain (Java Sound on computers, Web Audio in the browser). */
interface ClientAudio {
    var volume: Float
    var musicVolume: Float
    fun setListener(x: Float, y: Float, z: Float, yaw: Float)
    fun play(name: String, x: Float = Float.NaN, y: Float = 0f, z: Float = 0f, gain: Float = 1f, pitch: Float = 1f)
    fun setRain(strength: Float)
    fun close()
}

/** Wi-Fi and online play (computers only for now; the browser version has none, so its menus leave them out). */
interface NetSupport {
    fun scan(): List<Discovery.Host>
    fun connect(address: String, name: String): ClientSession?
    fun connectRoom(code: String, name: String): ClientSession
    /** Connects a joined game's world to the host. */
    fun attach(client: ClientSession, world: com.vishucraft.game.world.World, game: com.vishucraft.game.engine.Game)
    /** Opens [game] to Wi-Fi players (sets game.net). */
    fun host(game: com.vishucraft.game.engine.Game, worldName: String, playerName: String)
    fun localAddress(): String
    /** Opens the world online and returns its room code. */
    fun openRoom(): String
    fun roomCode(): String?
    fun closeRoom()
    fun internetHelp(): String
}

/**
 * Everything the desktop and browser versions share around the game: the title menus, the world list, the pause
 * menu, the in-game screens (inventory, signs, trades, elevators, flights...) and the game's events. Each
 * platform adds its window, input and sound, and calls [frame] every frame.
 */
abstract class ClientApp(protected val demo: File?) {
    protected enum class Menu { TITLE, WORLDS, CREATE, JOIN, SETTINGS, CONTROLS, CONFIRM_DELETE }
    protected enum class Overlay { NONE, PAUSE, CREATIVE, CONTAINER, SETTINGS, CONTROLS, NAME_MOB, TRADE, ACHIEVEMENTS, SIGN, ENDING, INTERNET, FLIGHTS, FLOORS }

    // ---------------------------------------------------------------- platform parts
    protected abstract val prefs: ClientPrefs
    protected abstract val worldsDir: File
    protected lateinit var ui: Ui
    protected lateinit var audio: ClientAudio
    /** Wi-Fi and online play, or null where there is none (the browser). */
    protected open val net: NetSupport? = null
    protected open val canQuit = false
    protected open fun quit() {}
    protected abstract val version: String
    protected abstract val savedWhere: String
    protected abstract val controlsHelp: String
    /** Reads keys / touches into the game's input while playing. */
    protected abstract fun updateGameInput(s: GameSession, dt: Float)
    protected open fun releaseInputs(input: com.vishucraft.game.engine.GameInput) {
        input.moveForward = 0f; input.moveStrafe = 0f
        input.jumpHeld = false; input.descendHeld = false; input.breakHeld = false; input.sprint = false
    }
    /** Captures the mouse for looking around (desktop). */
    protected open fun setCapture(on: Boolean) {}
    /** On-screen controls (touch screens), drawn over the game while playing. */
    protected open fun drawControls(s: GameSession) {}
    protected open fun settingChanged(label: String) {}

    protected var fbW = 1280
    protected var fbH = 720

    protected var menu = Menu.TITLE
    protected var overlay = Overlay.NONE
    protected var session: GameSession? = null
    protected var hud: Hud? = null
    protected var creative: CreativeScreen? = null
    protected var container: ContainerScreen? = null
    protected var message: String? = null

    // Create-world form
    protected val nameField = Ui.Field("", "World name")
    protected val seedField = Ui.Field("", "Seed (leave empty for random)")
    protected var survival = true
    protected var deleteTarget: File? = null

    // Join form
    protected val addressField = Ui.Field("", "Host address: 192.168.1.23, a VPN or internet address")
    protected val roomField = Ui.Field("", "Room code (6 numbers)", 8)
    @Volatile protected var hosts: List<Discovery.Host> = emptyList()
    @Volatile protected var scanning = false
    @Volatile protected var joining: String? = null
    @Volatile protected var joined: ClientSession? = null
    protected var demoCreate = false

    /** One frame: the game and its screens, or the menus. [pointerX], [pointerY]: mouse / finger in pixels. */
    protected fun frame(dt: Float, fps: Int, pointerX: Float, pointerY: Float) {
        val s = session
        if (s == null) {
            glViewport(0, 0, fbW, fbH)
            glClearColor(0.1f, 0.1f, 0.12f, 1f)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            ui.begin(fbW, fbH)
            ui.mouseX = pointerX / ui.scale; ui.mouseY = pointerY / ui.scale
            menus()
            ui.end()
            return
        }

        if (demo == null) {
            playLimit.tick(com.vishucraft.game.engine.PlayLimit.today(), dt)?.let { hud?.toast(it, 5f) }
            limitSaveTimer += dt
            if (limitSaveTimer > 10f || playLimit.over) { limitSaveTimer = 0f; prefs.savePlayLimit(playLimit) }
            // Time's up: save the world and close it, then say why on the title screen.
            if (playLimit.over) { s.save(); leaveWorld(); menu = Menu.TITLE; limitNotice = true; return }
        }
        hud?.timeLeft = playLimit.clock(); hud?.timeLow = playLimit.used > com.vishucraft.game.engine.PlayLimit.LIMIT - 5 * 60
        updateGameInput(s, dt)
        val events = ArrayList<String>()
        s.frame(dt, events)
        for (e in events) onGameEvent(s, e)
        if (session !== s) return // travelled to another dimension this frame
        val game = s.game
        val exposed = game.mobs.skyExposed(game.player.blockX(), game.player.blockY(), game.player.blockZ())
        audio.setRain(if (game.rain > 0.05f) game.rain * (if (exposed) 1f else 0.3f) else 0f)

        ui.begin(fbW, fbH)
        ui.mouseX = pointerX / ui.scale; ui.mouseY = pointerY / ui.scale
        val p = game.player
        val debug = if (prefs.showDebug) {
            val hours = ((game.timeOfDay * 24 + 6) % 24).toInt()
            val mins = (((game.timeOfDay * 24 + 6) % 1) * 60).toInt()
            "%d fps  XYZ %.1f / %.1f / %.1f  %02d:%02d%s".format(fps, p.x, p.y, p.z, hours, mins, if (p.flying) "  [flying]" else "") +
                "\n" + game.world.generator.biomeAt(p.blockX(), p.blockZ()).name.lowercase().replaceFirstChar { it.uppercase() } +
                "  mobs ${game.mobs.list.size}" + (game.net?.let { "\n" + it.status } ?: "")
        } else null
        hud!!.draw(ui, dt, debug, overlay == Overlay.NONE || overlay == Overlay.PAUSE)
        if (overlay == Overlay.NONE) drawControls(s)
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

    protected fun closeOverlay() {
        if (overlay == Overlay.CONTAINER) container?.close()
        overlay = Overlay.NONE
        ui.focus = null
    }

    // ---------------------------------------------------------------- game events

    protected fun onGameEvent(s: GameSession, e: String) {
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

    protected fun openContainer(m: ContainerScreen.Mode, chest: com.vishucraft.game.world.ChestEntity? = null, furnace: com.vishucraft.game.world.FurnaceEntity? = null) {
        container!!.open(m, chest, furnace)
        overlay = Overlay.CONTAINER
    }

    protected fun openInventory() {
        val s = session ?: return
        if (s.game.survival) openContainer(ContainerScreen.Mode.INVENTORY) else overlay = Overlay.CREATIVE
    }

    /** Saves and reopens the world in the other dimension. */
    protected fun travel(s: GameSession, target: Dimension) {
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
    protected val playLimit by lazy { prefs.playLimit() }
    protected var limitSaveTimer = 0f
    /** Shown on the title screen once the daily limit is over. */
    protected var limitNotice = false

    protected open fun startSession(s: GameSession) {
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
            else audio.play(name, x, y, z, gain, 1f)
        }
        s.game.listener = { x, y, z, yaw -> audio.setListener(x, y, z, yaw) }
        hud = Hud(s.game)
        creative = CreativeScreen(s.game)
        container = ContainerScreen(s.game)
        overlay = Overlay.NONE
        applySettings()
        if (first && demo == null) hud?.toast("Play time left today: ${playLimit.minutesLeft()} minutes", 4f)
    }

    protected open fun leaveWorld() {
        if (demo == null && session != null) prefs.savePlayLimit(playLimit)
        net?.closeRoom()
        val s = session ?: return
        container?.close()
        s.close()
        session = null
        overlay = Overlay.NONE
        setCapture(false)
        audio.setRain(0f)
    }

    protected fun applySettings() {
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

    protected fun background() {
        // Darkened dirt, like a cave wall.
        val t = 64f
        val tile = Blocks[Blocks.DIRT].top
        var y = 0f
        while (y < ui.height) { var x = 0f; while (x < ui.width) { ui.tile(tile, x, y, t, t, rgba(90, 90, 90)); x += t }; y += t }
    }

    protected fun title(text: String, y: Float = 60f) = ui.text(text, ui.width / 2, y, 34f, -1, 1)

    protected fun menus() {
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
                if (net != null) {
                    if (ui.button("Join a game (online or Wi-Fi)", cx - bw / 2, y, bw, 44f)) { menu = Menu.JOIN; scan() }
                    y += 54
                }
                if (ui.button("Settings", cx - bw / 2, y, bw / 2 - 5, 44f)) menu = Menu.SETTINGS
                if (ui.button("Controls", cx + 5, y, bw / 2 - 5, 44f)) menu = Menu.CONTROLS
                y += 54
                if (canQuit && ui.button("Quit", cx - bw / 2, y, bw, 44f)) quit()
                ui.text(savedWhere, 10f, ui.height - 26, 14f, rgba(170, 170, 170))
                val version = "Version $version"
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

    protected class WorldInfo(val dir: File, val level: LevelData)

    protected fun worlds(): List<WorldInfo> = worldsDir.listFiles().orEmpty()
        .mapNotNull { d -> LevelData.read(d)?.let { WorldInfo(d, it) } }
        .sortedByDescending { File(it.dir, "level.dat").lastModified() }

    protected var worldScroll = 0f

    protected fun worldsScreen() {
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

    protected fun createScreen() {
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
            while (File(worldsDir, "world$n").exists()) n++
            val name = nameField.text.trim().ifEmpty { "World $n" }
            ui.focus = null
            startSession(GameSession.create(File(worldsDir, "world$n"), name, seed, survival))
            menu = Menu.WORLDS
            return
        }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { ui.focus = null; menu = Menu.WORLDS }
    }

    protected fun scan() {
        val n = net ?: return
        if (scanning) return
        scanning = true
        Thread({ hosts = try { n.scan() } catch (_: Exception) { emptyList() }; scanning = false }, "scan").start()
    }

    protected var namingUid = -1
    protected var signPos = listOf(0, 0, 0)
    protected val signField = Ui.Field("", "Text on the sign", 60)

    @Volatile protected var internetText = ""
    protected var internetTitle = "Play over the internet"

    protected fun roomText(code: String) = "Room code: ${code.chunked(3).joinToString(" ")}\n\n" +
        "Friends anywhere (Wi-Fi or mobile data) choose \"Join online game\" on the DhruvVishu title screen and type this code.\n" +
        "Keep the game open while they play."

    protected fun internetScreen() {
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

    protected fun endingScreen() {
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

    protected var flights = listOf<String>()
    protected var floorsEvent = listOf<String>()

    /** Tapped an elevator: pick a floor. */
    protected fun floorsScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 420f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Elevator", ui.height * 0.15f)
        val (lx, lz, cur, list) = floorsEvent
        val names = list.split('|')
        var y = ui.height * 0.15f + 60
        if (names.size <= 10) {
            for ((i, name) in names.withIndex()) {
                val label = if (i == cur.toInt()) "$name  (you are here)" else name
                if (ui.button(label, cx - w / 2, y, w, 40f)) { s.game.liftTo(lx.toInt(), lz.toInt(), i); overlay = Overlay.NONE }
                y += 46
            }
        } else {
            // A tall tower: a grid of floor numbers (G is the ground floor), the current one marked with a star.
            val cols = 10
            val gap = 4f
            val rows = (names.size + cols - 1) / cols
            val bh = ((ui.height * 0.8f - y - 60) / rows - gap).coerceIn(18f, 40f)
            val bw = minOf(64f, (ui.width - 32f) / cols - gap)
            val x0 = cx - (cols * (bw + gap) - gap) / 2
            for (i in names.indices) {
                val label = (if (i == 0) "G" else "$i") + if (i == cur.toInt()) "*" else ""
                if (ui.button(label, x0 + (i % cols) * (bw + gap), y + (i / cols) * (bh + gap), bw, bh)) {
                    s.game.liftTo(lx.toInt(), lz.toInt(), i); overlay = Overlay.NONE
                }
            }
            y += rows * (bh + gap)
        }
        if (ui.button("Cancel", cx - w / 2, y + 10, w, 40f)) overlay = Overlay.NONE
    }

    /** Got into an airplane: pick an airport to fly to. */
    protected fun flightsScreen(s: GameSession) {
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

    protected fun signScreen(s: GameSession) {
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
    protected var tradeUid = -1
    protected var tradeMessage = ""

    /** A villager's offers: click one to trade. */
    protected fun tradeScreen(s: GameSession) {
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

    protected fun achievementsScreen(s: GameSession) {
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
    protected fun nameMobScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 420f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Name this creature", ui.height * 0.25f)
        ui.field(nameField, cx - w / 2, ui.height * 0.4f, w)
        val y = ui.height * 0.4f + 60
        if (ui.button("OK", cx - w / 2, y, w / 2 - 5, 44f) || ui.takeSubmit()) { s.game.nameMob(namingUid, nameField.text); ui.focus = null; overlay = Overlay.NONE }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { ui.focus = null; overlay = Overlay.NONE }
    }

    protected fun connect(address: String) {
        val n = net ?: return
        if (joining != null) return
        joining = address
        message = null
        val name = prefs.playerName
        Thread({
            val result = try { n.connect(address, name) } catch (_: Exception) { null }
            if (result == null) message = "No game answered at $address. Are both on the same Wi-Fi, and did the host choose \"Open to Wi-Fi\"?"
            joined = result
            joining = null
        }, "join").start()
    }

    /** Joins a friend's game anywhere through the online relay, with the room code they see. */
    protected fun connectRoom(code: String) {
        val n = net ?: return
        if (joining != null) return
        joining = "room $code"
        message = null
        val name = prefs.playerName
        Thread({
            try { joined = n.connectRoom(code, name) } catch (e: Exception) { message = e.message ?: "Could not join room $code" }
            joining = null
        }, "join-online").start()
    }

    protected fun joinScreen() {
        val cx = ui.width / 2
        val w = 520f
        title("Join a game")
        joined?.let { c ->
            joined = null
            val level = LevelData.create(c.seed, c.worldName, c.mode).apply {
                hasPlayer = true; x = c.spawnX + 1.5f; y = c.spawnY + 0.5f; z = c.spawnZ + 0.5f; timeOfDay = c.time
            }
            val n = net ?: return
            startSession(GameSession(null, level) { w, g -> n.attach(c, w, g) })
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

    protected fun settingsScreen(back: () -> Unit) {
        val cx = ui.width / 2
        if (session != null) ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 170))
        title("Settings")
        var y = 110f
        for ((label, change) in prefs.rows()) {
            if (ui.button(label(), cx - 220, y, 440f, 40f)) {
                change()
                applySettings()
                settingChanged(label())
            }
            y += 48
        }
        if (ui.button("Done", cx - 220, y + 16, 440f, 44f)) back()
    }

    protected fun controlsScreen(back: () -> Unit) {
        val cx = ui.width / 2
        if (session != null) ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 170))
        title("Controls")
        var y = 120f
        for (line in controlsHelp.split('\n')) { ui.text(line, cx, y, 19f, -1, 1); y += 34 }
        if (ui.button("Done", cx - 200, y + 20, 400f, 44f)) back()
    }

    protected fun pauseMenu(s: GameSession) {
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
        val n = net
        if (n != null) {
            b(when { game.isClient -> "Wi-Fi: joined"; game.net != null -> "Wi-Fi: open (${n.localAddress()})"; else -> "Open to Wi-Fi" }) {
                if (game.net != null) hud?.toast(game.net!!.status)
                else {
                    try {
                        n.host(game, s.level.name, prefs.playerName)
                        hud?.toast("Open! Friends on the same Wi-Fi can join from the title screen.\nYour address: ${n.localAddress()}", 5f)
                    } catch (e: Exception) { hud?.toast("Could not open the game: ${e.message}", 4f) }
                }
            }
            b(n.roomCode()?.let { "Online: room $it" } ?: "Play online (room code)") {
                if (game.isClient) { hud?.toast("Only the host can invite more friends"); return@b }
                internetTitle = "Play online"
                internetText = ""; overlay = Overlay.INTERNET
                val code = n.roomCode()
                if (code != null) internetText = roomText(code)
                else {
                    if (game.net == null) try { n.host(game, s.level.name, prefs.playerName) } catch (e: Exception) { internetText = "Could not open the game: ${e.message}"; return@b }
                    Thread {
                        internetText = try { roomText(n.openRoom()) } catch (e: Exception) { "Couldn't open online: ${e.message}" }
                    }.start()
                }
            }
            b("Other ways to play online") {
                internetTitle = "Play over the internet"
                internetText = ""; overlay = Overlay.INTERNET
                Thread { internetText = n.internetHelp() }.start()
            }
        }
        b("Achievements") { overlay = Overlay.ACHIEVEMENTS }
        b("Settings") { overlay = Overlay.SETTINGS }
        b("Controls") { overlay = Overlay.CONTROLS }
    }
}
