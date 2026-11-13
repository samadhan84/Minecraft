package com.vishucraft.client

import android.opengl.GLES20.*
import com.vishucraft.client.Ui.Companion.rgba
import com.vishucraft.game.engine.flyTo
import com.vishucraft.game.engine.metroTo
import com.vishucraft.game.engine.callTrain
import com.vishucraft.game.engine.robotCommand
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
    protected enum class Menu { TITLE, WORLDS, CREATE, JOIN, SETTINGS, CONTROLS, CONFIRM_DELETE, PASSWORD, LOOK }
    protected enum class Overlay { NONE, PAUSE, CREATIVE, CONTAINER, SETTINGS, CONTROLS, NAME_MOB, TRADE, ACHIEVEMENTS, SIGN, ENDING, INTERNET, FLIGHTS, FLOORS, METRO, CALL_TRAIN, ROBOT, PASSWORD_ASK, PASSWORD_SET, PASSWORD_REMOVE, LOOK, HARDCORE_OVER }

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
    private val passwordField = Ui.Field("", "Password", 32, secret = true)
    private var afterPassword: (() -> Unit)? = null
    private var passwordBack = Menu.WORLDS
    private var wrongPassword = false
    private var passwordStored = ""
    protected val seedField = Ui.Field("", "Seed (leave empty for random)")
    protected var survival = true
    protected var hardcoreWorld = false
    /** A new creative world on a flat world (false: normal land with hills, trees and water). */
    protected var flatWorld = false
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
        hud?.timeLeft = if (com.vishucraft.game.engine.PlayLimit.ENABLED) playLimit.clock() else null; hud?.timeLow = playLimit.used > com.vishucraft.game.engine.PlayLimit.LIMIT - 5 * 60
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
            Overlay.METRO -> metroScreen(s)
            Overlay.CALL_TRAIN -> callTrainScreen(s)
            Overlay.ROBOT -> robotScreen(s)
            Overlay.PASSWORD_ASK -> passwordAskScreen(s)
            Overlay.PASSWORD_SET -> passwordSetScreen(s)
            Overlay.PASSWORD_REMOVE -> passwordRemoveScreen(s)
            Overlay.FLOORS -> floorsScreen(s)
            Overlay.ENDING -> endingScreen()
            Overlay.LOOK -> lookScreen(s)
            Overlay.HARDCORE_OVER -> hardcoreOverScreen(s)
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
            e == "hardcore_over" -> { overlay = Overlay.HARDCORE_OVER; setCapture(false) }
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
            e.startsWith("robot:") -> { robotUid = e.removePrefix("robot:").toInt(); robotField.text = ""; ui.focus = robotField; overlay = Overlay.ROBOT }
            e.startsWith("calltrain:") -> { callAt = e.removePrefix("calltrain:").split(',').map { it.toInt() }; overlay = Overlay.CALL_TRAIN }
            e.startsWith("metro:") -> { metroStations = e.removePrefix("metro:").split('|'); overlay = Overlay.METRO }
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
        if (first && demo == null && com.vishucraft.game.engine.PlayLimit.ENABLED) hud?.toast("Play time left today: ${playLimit.minutesLeft()} minutes", 4f)
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
                ui.text("Dhruvil Craft", cx, ui.height * 0.16f, 72f, rgba(255, 220, 90), 1)
                ui.text("Dream it, build it", cx, ui.height * 0.16f + 84, 20f, rgba(220, 220, 220), 1)
                var y = ui.height * 0.42f
                if (ui.button("Play", cx - bw / 2, y, bw, 44f)) menu = Menu.WORLDS
                y += 54
                if (net != null) {
                    if (ui.button("Join a game (online or Wi-Fi)", cx - bw / 2, y, bw, 44f)) { menu = Menu.JOIN; scan() }
                    y += 54
                }
                if (ui.button("Customize character (outfit, skin, face)", cx - bw / 2, y, bw, 44f)) {
                    homeLook = com.vishucraft.game.world.Appearance.load(com.vishucraft.game.world.Appearance.fileFor(worldsDir)) ?: com.vishucraft.game.world.Appearance.default()
                    menu = Menu.LOOK
                }
                y += 54
                if (ui.button("Settings", cx - bw / 2, y, bw / 2 - 5, 44f)) menu = Menu.SETTINGS
                if (ui.button("Controls", cx + 5, y, bw / 2 - 5, 44f)) menu = Menu.CONTROLS
                y += 54
                if (canQuit && ui.button("Quit", cx - bw / 2, y, bw, 44f)) quit()
                ui.text(savedWhere, 10f, ui.height - 26, 14f, rgba(170, 170, 170))
                val version = "Version $version"
                ui.text(version, ui.width - ui.textWidth(version, 16f) - 14, 12f, 16f, rgba(230, 230, 230))
            }
            Menu.LOOK -> homeLookScreen()
            Menu.WORLDS -> worldsScreen()
            Menu.PASSWORD -> passwordScreen()
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
        .filter { it.name != com.vishucraft.game.engine.SHOWCASE_DIR }
        .mapNotNull { d -> LevelData.read(d)?.let { WorldInfo(d, it) } }
        .sortedByDescending { File(it.dir, "level.dat").lastModified() }

    protected var worldScroll = 0f

    /** Asks for the world's password (see engine/WorldLock) if it has one, then runs [then] (straight away in the demo run). */
    protected fun needPassword(back: Menu, stored: String, then: () -> Unit) {
        if (demo != null || stored.isEmpty()) { then(); return }
        afterPassword = then; passwordBack = back; passwordStored = stored; wrongPassword = false
        passwordField.text = ""; ui.focus = passwordField
        menu = Menu.PASSWORD
    }

    private fun passwordScreen() {
        val cx = ui.width / 2
        title("This world has a password")
        val w = 360f
        ui.text("Type the password to play.", cx, ui.height * 0.34f, 20f, rgba(220, 220, 220), 1)
        ui.field(passwordField, cx - w / 2, ui.height * 0.42f, w)
        if (wrongPassword) ui.text("Wrong password", cx, ui.height * 0.42f + 56, 18f, rgba(255, 120, 120), 1)
        val y = ui.height * 0.42f + 90
        if (ui.button("OK", cx - w / 2, y, w / 2 - 5, 44f) || ui.takeSubmit()) {
            if (com.vishucraft.game.engine.WorldLock.check(passwordStored, passwordField.text)) {
                val go = afterPassword; afterPassword = null; ui.focus = null; passwordField.text = ""
                menu = passwordBack
                go?.invoke()
            } else { wrongPassword = true; passwordField.text = ""; ui.focus = passwordField }
        }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { afterPassword = null; ui.focus = null; passwordField.text = ""; menu = passwordBack }
    }

    // ---- Adding and taking away a world's password while playing.
    private val passNew = Ui.Field("", "Password", 32, secret = true)
    private val passAgain = Ui.Field("", "Type it again", 32, secret = true)
    private val removeField = Ui.Field("", "Secret code", 32, secret = true)
    private var passProblem: String? = null
    private var leaveAfterSet = false

    private fun leaveToTitle() { leaveWorld(); menu = Menu.TITLE }

    /** The pause menu's "Save and quit": a world with no password yet asks whether to give it one. */
    protected fun saveAndQuit(s: GameSession) {
        val l = s.level
        if (demo != null || s.game.isClient || l.password.isNotEmpty() || l.passwordAsked || l.bedwars > 0) { leaveToTitle(); return }
        overlay = Overlay.PASSWORD_ASK
    }

    private fun startSetPassword(leaveAfter: Boolean) {
        leaveAfterSet = leaveAfter; passNew.text = ""; passAgain.text = ""; passProblem = null
        ui.focus = passNew; overlay = Overlay.PASSWORD_SET
    }

    protected fun passwordAskScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 440f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Add a password to this world?", ui.height * 0.22f)
        ui.text("Only people who know the password can open it. You choose the password.", cx, ui.height * 0.22f + 62, 18f, rgba(220, 220, 220), 1)
        var y = ui.height * 0.42f
        if (ui.button("Add password", cx - w / 2, y, w, 44f)) startSetPassword(true)
        y += 54
        if (ui.button("No thanks", cx - w / 2, y, w, 44f)) { s.level.passwordAsked = true; overlay = Overlay.NONE; leaveToTitle() }
        y += 54
        if (ui.button("Back to game", cx - w / 2, y, w, 44f)) overlay = Overlay.NONE
    }

    protected fun passwordSetScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 420f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Choose a password", ui.height * 0.2f)
        ui.text("Anything you like (at least ${com.vishucraft.game.engine.WorldLock.MIN_LENGTH} characters).", cx, ui.height * 0.2f + 62, 18f, rgba(220, 220, 220), 1)
        ui.field(passNew, cx - w / 2, ui.height * 0.38f, w)
        ui.field(passAgain, cx - w / 2, ui.height * 0.38f + 60, w)
        passProblem?.let { ui.text(it, cx, ui.height * 0.38f + 122, 18f, rgba(255, 120, 120), 1) }
        val y = ui.height * 0.38f + 156
        if (ui.button("Save password", cx - w / 2, y, w / 2 - 5, 44f) || ui.takeSubmit()) {
            val problem = com.vishucraft.game.engine.WorldLock.problem(passNew.text, passAgain.text)
            if (problem != null) passProblem = problem
            else {
                s.level.password = com.vishucraft.game.engine.WorldLock.make(passNew.text); s.level.passwordAsked = true
                passNew.text = ""; passAgain.text = ""; ui.focus = null
                overlay = Overlay.PAUSE; hud?.toast("Password saved", 3f)
                if (leaveAfterSet) leaveToTitle()
            }
        }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { passNew.text = ""; passAgain.text = ""; ui.focus = null; overlay = if (leaveAfterSet) Overlay.PASSWORD_ASK else Overlay.PAUSE }
    }

    protected fun passwordRemoveScreen(s: GameSession) {
        val cx = ui.width / 2; val w = 420f
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Secret code", ui.height * 0.25f)
        ui.text(if (passProblem != null) passProblem!! else "Type the secret code to take this world's password away.", cx, ui.height * 0.25f + 62, 18f,
            if (passProblem != null) rgba(255, 120, 120) else rgba(220, 220, 220), 1)
        ui.field(removeField, cx - w / 2, ui.height * 0.4f, w)
        val y = ui.height * 0.4f + 60
        if (ui.button("OK", cx - w / 2, y, w / 2 - 5, 44f) || ui.takeSubmit()) {
            if (com.vishucraft.game.engine.WorldLock.secretOk(removeField.text)) {
                s.level.password = ""; removeField.text = ""; passProblem = null; ui.focus = null
                overlay = Overlay.PAUSE; hud?.toast("Password removed", 3f)
            } else { passProblem = "Wrong secret code"; removeField.text = ""; ui.focus = removeField }
        }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { removeField.text = ""; passProblem = null; ui.focus = null; overlay = Overlay.PAUSE }
    }

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
                needPassword(Menu.WORLDS, wi.level.password) { startSession(GameSession(wi.dir, wi.level)) }
                return
            }
            if (ui.button("Delete", cx + w / 2 - 100, y, 100f, 42f)) { deleteTarget = wi.dir; menu = Menu.CONFIRM_DELETE }
        }
        if (ui.button("Create new world", cx - w / 2, ui.height - 70, w / 2 - 5, 44f)) {
            if (list.size >= com.vishucraft.game.engine.MAX_WORLDS) { message = com.vishucraft.game.engine.MAX_WORLDS_MESSAGE; return }
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
        if (ui.button("Game mode: " + when { survival && hardcoreWorld -> "Hardcore"; survival -> "Survival"; flatWorld -> "Creative (flat world)"; else -> "Creative (normal world)" }, cx - w / 2, y, w, 42f)) {
            // Survival, hardcore, creative on normal land (hills, trees, water), or creative on a flat world.
            if (survival && !hardcoreWorld) hardcoreWorld = true
            else if (survival) { survival = false; hardcoreWorld = false; flatWorld = false }
            else if (!flatWorld) flatWorld = true else survival = true
        }
        ui.text(if (survival && hardcoreWorld) "Survival with one life: if you die you cannot respawn." else if (survival) "Collect resources, craft tools, watch your health and hunger." else "Unlimited blocks, flying, no damage.",
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
            val dir = File(worldsDir, "world$n"); val sv = survival; val fl = flatWorld
            startSession(GameSession.create(dir, name, seed, sv, fl)); menu = Menu.WORLDS
            if (sv && hardcoreWorld) session?.game?.level?.hardcore = true
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
        "Friends anywhere (Wi-Fi or mobile data) choose \"Join online game\" on the Dhruvil Craft title screen and type this code.\n" +
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
                "Thank you for playing Dhruvil Craft.\n\n" +
                "Made for Dhruv and Vishu.\n" +
                "Every block, creature, sound and song in this game was made from code.\n\n" +
                "The world is still yours: keep building, exploring and playing."
        for (line in text.split('\n')) { ui.text(line, ui.width / 2, y, 19f, rgba(230, 230, 255), 1); y += 28 }
        if (ui.button("Keep playing", ui.width / 2 - 140, y + 20, 280f, 44f)) overlay = Overlay.NONE
    }

    protected var flights = listOf<String>()
    protected var metroStations = listOf<String>()
    protected var callAt = listOf(0, 0, 0)
    protected var robotUid = -1
    protected val robotField = Ui.Field("", "e.g. follow me, mine, chop trees, build a house", 80)

    /** Tapped the robot: type what it should do. */
    protected fun robotScreen(s: GameSession) {
        val cx = ui.width / 2; val w = minOf(620f, ui.width - 32f)
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("What should the robot do?", ui.height * 0.25f)
        ui.field(robotField, cx - w / 2, ui.height * 0.4f, w)
        val y = ui.height * 0.4f + 60
        if (ui.button("Do it", cx - w / 2, y, w / 2 - 5, 44f) || ui.takeSubmit()) {
            s.game.robotCommand(robotUid, robotField.text); ui.focus = null; overlay = Overlay.NONE
        }
        if (ui.button("Cancel", cx + 5, y, w / 2 - 5, 44f)) { ui.focus = null; overlay = Overlay.NONE }
        // Quick choices.
        val quick = listOf("follow me", "mine", "chop trees", "farm", "guard me", "build a house")
        for ((i, h) in quick.withIndex()) {
            if (ui.button(h, cx - w / 2 + (i % 3) * (w / 3), y + 64 + (i / 3) * 46f, w / 3 - 6, 40f)) {
                s.game.robotCommand(robotUid, h); ui.focus = null; overlay = Overlay.NONE
            }
        }
    }

    /** Pressed a station's call button: which kind of train? */
    protected fun callTrainScreen(s: GameSession) {
        val cx = ui.width / 2
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Call a train, just for you", ui.height * 0.2f)
        var y = ui.height * 0.2f + 60
        for (name in com.vishucraft.game.engine.TRAIN_CHOICES) {
            if (ui.button(name, cx - 210f, y, 420f, 44f)) { s.game.callTrain(name, callAt[0], callAt[1], callAt[2]); overlay = Overlay.NONE }
            y += 52
        }
        if (ui.button("Cancel", cx - 210f, y + 10, 420f, 44f)) overlay = Overlay.NONE
    }

    /** Got on a metro or bullet train: pick a station to go to non-stop. */
    protected fun metroScreen(s: GameSession) {
        val cx = ui.width / 2
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        title("Which station? (non-stop)", ui.height * 0.12f)
        val cols = if (metroStations.size > 6) 2 else 1
        val w = if (cols == 2) minOf(300f, (ui.width - 48f) / 2) else 420f
        val x0 = cx - (cols * w + (cols - 1) * 10f) / 2
        var y = ui.height * 0.12f + 56
        for ((i, name) in metroStations.take(16).withIndex()) {
            val col = i % cols; val row = i / cols
            if (ui.button(name, x0 + col * (w + 10f), y + row * 46f, w, 40f)) { s.game.metroTo(name); overlay = Overlay.NONE }
        }
        y += ((minOf(metroStations.size, 16) + cols - 1) / cols) * 46f + 10
        if (ui.button("Stop at every station", cx - 210f, y, 420f, 40f)) { s.game.metroTo(""); overlay = Overlay.NONE }
    }
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

    protected var settingsTab = 0

    /** A button that shows whether it is the chosen one (gold) or not (grey). Returns true when clicked. */
    private fun modeButton(label: String, x: Float, y: Float, w: Float, h: Float, chosen: Boolean): Boolean {
        if (!chosen) return ui.button(label, x, y, w, h)
        val over = ui.hover(x, y, w, h)
        ui.rect(x, y, w, h, rgba(214, 170, 40))
        ui.frame(x, y, w, h, if (over) rgba(255, 255, 255) else rgba(255, 235, 140), 3f)
        ui.text(label, x + w / 2, y + h / 2 - 9f, 18f, rgba(40, 28, 4), 1, shadow = false)
        return over && ui.clicked
    }

    /** The settings, in sections: Game (modes), Day & Night, Character & Camera, Graphics, Sound and Controls. */
    protected fun settingsScreen(back: () -> Unit) {
        val se = session
        val game = se?.game
        ui.rect(0f, 0f, ui.width, ui.height, if (se != null) rgba(0, 0, 0, 190) else rgba(0, 0, 0, 110))
        val cx = ui.width / 2
        ui.text("Settings", cx, 18f, 40f, rgba(255, 220, 90), 1)
        ui.rect(cx - 90f, 66f, 180f, 4f, rgba(255, 220, 90))
        val tabs = if (game != null) listOf("Game", "Day & Night", "Character & Camera", "Graphics", "Sound", "Controls")
            else listOf("Character & Camera", "Graphics", "Sound", "Controls")
        if (settingsTab >= tabs.size) settingsTab = 0
        // The sections down the left side.
        val sx = 30f; val sw = 230f
        var ty = 96f
        for ((i, name) in tabs.withIndex()) {
            if (modeButton(name, sx, ty, sw, 46f, i == settingsTab)) settingsTab = i
            ty += 54f
        }
        // The card with this section's settings.
        val px = sx + sw + 24f; val pw = ui.width - px - 30f
        val ph = ui.height - 96f - 80f
        ui.rect(px, 96f, pw, ph, rgba(18, 18, 26, 225))
        ui.frame(px, 96f, pw, ph, rgba(214, 170, 40), 3f)
        val ix = px + 28f; val iw = pw - 56f
        var y = 116f
        val name = tabs[settingsTab]
        ui.text(name, ix, y, 28f, rgba(255, 220, 90)); y += 40f
        fun note(t: String) { ui.text(t, ix, y, 16f, rgba(200, 200, 210)); y += 28f }
        fun modes(labels: List<String>, chosen: Int, pick: (Int) -> Unit) {
            val n = labels.size; val gap = 10f; val w = (iw - gap * (n - 1)) / n
            for ((i, l) in labels.withIndex()) if (modeButton(l, ix + i * (w + gap), y, w, 48f, i == chosen)) pick(i)
            y += 60f
        }
        fun row(label: String, action: () -> Unit) { if (ui.button(label, ix, y, iw, 44f)) action(); y += 52f }
        val rows = prefs.rows()
        fun prefRows(pick: (String) -> Boolean) {
            for ((label, change) in rows) if (pick(label())) row(label()) { change(); applySettings(); settingChanged(label()) }
        }
        when (name) {
            "Game" -> {
                val g = game!!
                note("Pick how this world is played. You can change it any time.")
                modes(listOf("Creative", "Survival", "Hardcore"), g.modeIndex()) { hud?.toast(g.setMode(it), 4f) }
                note(when (g.modeIndex()) {
                    0 -> "Creative: every block, flying, no damage."
                    1 -> "Survival: collect, craft, watch health and hunger. You respawn."
                    else -> "Hardcore: survival with ONE life. If you die you cannot respawn."
                })
                y += 8f
                ui.text("Monsters", ix, y, 20f, rgba(255, 220, 90)); y += 30f
                modes(listOf("Normal", "Peaceful (no monsters)"), if (g.mobs.hostileEnabled) 0 else 1) { g.mobs.hostileEnabled = it == 0 }
                ui.text("Weather", ix, y, 20f, rgba(255, 220, 90)); y += 30f
                modes(listOf("On (rain, snow, storms)", "Off"), if (prefs.weather) 0 else 1) { prefs.weather = it == 0; applySettings() }
            }
            "Day & Night" -> {
                val g = game!!
                note("Choose how time passes in this world.")
                modes(listOf("Normal cycle", "Always day", "Always night"), g.timeMode) { hud?.toast(g.setTimeMode(it), 3f) }
                y += 8f
                ui.text("Change the time now", ix, y, 20f, rgba(255, 220, 90)); y += 30f
                modes(listOf("Set to morning", "Set to night"), if (g.daylight > 0.5f) 0 else 1) { g.timeOfDay = if (it == 0) 0.02f else 0.6f }
            }
            "Character & Camera" -> {
                note("How you see the game, and how you look.")
                val g = game
                if (g != null) {
                    ui.text("Camera", ix, y, 20f, rgba(255, 220, 90)); y += 30f
                    modes(listOf("First person", "Third person (behind)", "Second person (in front)"), g.viewMode) { g.viewMode = it }
                }
                row("Customize character (skin, face, clothes)") {
                    if (g != null) { lookBefore = g.viewMode; g.viewMode = 2; overlay = Overlay.LOOK }
                    else { homeLook = com.vishucraft.game.world.Appearance.load(com.vishucraft.game.world.Appearance.fileFor(worldsDir)) ?: com.vishucraft.game.world.Appearance.default(); menu = Menu.LOOK }
                }
            }
            "Graphics" -> {
                note("Make the game look better, or run faster.")
                prefRows { it.startsWith("Field of view") || it.startsWith("Render distance") || it.startsWith("Fancy") || it.startsWith("Fullscreen") }
            }
            "Sound" -> {
                note("Volume of effects and music.")
                prefRows { it.startsWith("Sound") || it.startsWith("Music") }
            }
            else -> {
                note("How the game reacts to you.")
                prefRows { it.contains("ensitivity") || it.startsWith("Touch") || it.startsWith("FPS") || it.startsWith("Show FPS") }
            }
        }
        if (ui.button("Done", cx - 120f, ui.height - 64f, 240f, 44f)) back()
    }

    protected var lookBefore = 0

    /** The character customizer: every part of how you look. The game shows you from the front behind the buttons. */
    protected fun lookScreen(s: GameSession) {
        val game = s.game
        val A = com.vishucraft.game.world.Appearance
        title("Customize your character", 20f)
        val w = 300f; val gap = 10f
        val left = ui.width / 2 - w - gap / 2
        var y = 80f
        // Two columns of buttons at the sides, so the character in the middle stays visible.
        val colX = floatArrayOf(20f, ui.width - w - 20f)
        for (i in 0 until A.SIZE) {
            val col = i % 2; val row = i / 2
            if (ui.button(A.categories[i].title + ": " + A.optionName(game.appearance, i), colX[col], y + row * 46, w, 40f)) game.changeLook(i)
        }
        val by = ui.height - 60f
        if (ui.button("Random look", ui.width / 2 - 215, by, 140f, 40f)) game.randomLook()
        if (ui.button("Turn around", ui.width / 2 - 65, by, 130f, 40f)) game.viewMode = if (game.viewMode == 2) 1 else 2
        if (ui.button("Done", ui.width / 2 + 75, by, 140f, 40f)) { game.viewMode = lookBefore; overlay = Overlay.PAUSE }
    }

    protected var homeLook: IntArray = com.vishucraft.game.world.Appearance.default()

    /** Draws the character from the front as coloured blocks ([scale] pixels per grid cell), with its feet at the bottom of the picture. */
    protected fun drawLookPreview(look: IntArray, x: Float, y: Float, scale: Float) {
        val A = com.vishucraft.game.world.Appearance
        for (r in A.flat(look)) {
            val c = A.palette[r[4].coerceIn(0, A.palette.size - 1)]
            ui.rect(x + r[0] * scale, y + r[1] * scale, r[2] * scale, r[3] * scale, rgba(c[0], c[1], c[2]))
        }
    }

    /** The home-screen customizer: the same choices as in the game, with a picture of you. Your look is kept for every world. */
    protected fun homeLookScreen() {
        val A = com.vishucraft.game.world.Appearance
        title("Customize your character", 20f)
        val w = 300f
        val colX = floatArrayOf(20f, ui.width - w - 20f)
        for (i in 0 until A.SIZE) {
            val col = i % 2; val row = i / 2
            if (ui.button(A.categories[i].title + ": " + A.optionName(homeLook, i), colX[col], 80f + row * 46, w, 40f)) {
                A.cycle(homeLook, i); A.save(A.fileFor(worldsDir), homeLook)
            }
        }
        val scale = ((ui.height - 190f) / 36f).coerceAtMost(11f)
        drawLookPreview(homeLook, ui.width / 2 - 8 * scale, 90f, scale)
        val by = ui.height - 60f
        if (ui.button("Random look", ui.width / 2 - 215, by, 140f, 40f)) { A.randomize(homeLook, java.util.Random()); A.save(A.fileFor(worldsDir), homeLook) }
        if (ui.button("Done", ui.width / 2 + 75, by, 140f, 40f)) menu = Menu.TITLE
    }

    protected fun hardcoreOverScreen(s: GameSession) {
        ui.rect(0f, 0f, ui.width, ui.height, rgba(80, 0, 0, 170))
        title("You died", ui.height * 0.25f)
        ui.text("This was a hardcore world: you cannot respawn.", ui.width / 2, ui.height * 0.25f + 70, 22f, -1, 1)
        if (ui.button("Back to title", ui.width / 2 - 150, ui.height * 0.25f + 130, 300f, 46f)) leaveToTitle()
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
        b("Save and quit to title") { saveAndQuit(s) }
        y += 10
        twoColumns = true
        b("Mode: " + game.modeName() + " (change: Creative / Survival / Hardcore)") {
            if (game.isClient) hud?.toast("The host picks the game mode")
            else { hud?.toast(game.switchMode(), 4f); overlay = Overlay.NONE }
        }
        b("Camera: " + game.viewName()) { hud?.toast(game.cycleView(), 3f); overlay = Overlay.NONE }
        b("Customize character (skin, face, clothes)") { lookBefore = game.viewMode; game.viewMode = 2; overlay = Overlay.LOOK }
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
        if (!game.isClient) b(if (s.level.password.isEmpty()) "World password: Off (tap to add)" else "World password: On (tap to take away)") {
            if (s.level.password.isEmpty()) startSetPassword(false)
            else { removeField.text = ""; passProblem = null; ui.focus = removeField; overlay = Overlay.PASSWORD_REMOVE }
        }
        b("Achievements") { overlay = Overlay.ACHIEVEMENTS }
        b("Settings") { overlay = Overlay.SETTINGS }
        b("Controls") { overlay = Overlay.CONTROLS }
    }
}
