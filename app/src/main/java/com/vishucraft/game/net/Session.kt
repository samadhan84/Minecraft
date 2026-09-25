package com.vishucraft.game.net

import com.vishucraft.game.engine.Game
import com.vishucraft.game.engine.Mob
import com.vishucraft.game.engine.MobType
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.GameMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt

/** Another player in a Wi-Fi game (drawn as an explorer figure). */
class RemotePlayer(val id: Int, var name: String) {
    val proxy = Mob(MobType.EXPLORER, 0f, 0f, 0f)
    var x = 0f; var y = 0f; var z = 0f
    fun moveTo(nx: Float, ny: Float, nz: Float, yaw: Float) {
        val d = sqrt((nx - x) * (nx - x) + (nz - z) * (nz - z))
        x = nx; y = ny; z = nz
        proxy.x = nx; proxy.y = ny; proxy.z = nz; proxy.yaw = yaw
        proxy.moving = d > 0.01f
        proxy.walkPhase += d * 3.2f
    }
}

/** Shared by host and client: the other players and the message inbox. */
abstract class Session {
    val inbox = ConcurrentLinkedQueue<Packet>()
    val players = ConcurrentHashMap<Int, RemotePlayer>()
    abstract val isClient: Boolean
    abstract fun poll(game: Game, dt: Float)
    abstract fun blockChanged(x: Int, y: Int, z: Int, id: Int, meta: Int)
    open fun attack(uid: Int, damage: Float, kx: Float, kz: Float) {}
    open fun hurtRemote(id: Int, amount: Float, fromX: Float, fromZ: Float) {}
    abstract fun close()
    abstract val status: String

    protected fun chunkBytes(c: Chunk): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(c.toBytes()) }
        return bytes.toByteArray()
    }
}

/** The world's owner: simulates everything and keeps every client in sync. */
class HostSession(private val game: Game, private val worldName: String, private val hostName: String) : Session() {
    override val isClient = false
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Msg.PORT)) }
    private val clients = ConcurrentLinkedQueue<Connection>()
    private var nextId = 1
    @Volatile private var running = true
    private val pendingChunks = ArrayList<Triple<Connection, Int, Int>>()
    private var playerTimer = 0f; private var mobTimer = 0f; private var timeTimer = 0f

    init {
        Thread({
            while (running) {
                try {
                    val s: Socket = server.accept()
                    val c = Connection(s, inbox)
                    c.id = nextId++
                    clients.add(c)
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "net-accept").apply { isDaemon = true; start() }
        Thread({
            DatagramSocket().use { udp ->
                while (running) {
                    Discovery.announce(udp, worldName, 1 + clients.size)
                    Thread.sleep(1000)
                }
            }
        }, "net-announce").apply { isDaemon = true; start() }
    }

    override val status get() = "Hosting \"$worldName\" · ${clients.count { it.open }} joined"

    private fun broadcast(type: Int, except: Connection? = null, body: (DataOutputStream) -> Unit) {
        for (c in clients) if (c.open && c !== except) c.send(type, body)
    }

    override fun blockChanged(x: Int, y: Int, z: Int, id: Int, meta: Int) =
        broadcast(Msg.SET_BLOCK) { it.writeInt(x); it.writeInt(y); it.writeInt(z); it.writeShort(id); it.writeByte(meta) }

    override fun hurtRemote(id: Int, amount: Float, fromX: Float, fromZ: Float) {
        clients.firstOrNull { it.id == id }?.send(Msg.HURT) { it.writeFloat(amount); it.writeFloat(fromX); it.writeFloat(fromZ) }
    }

    override fun poll(game: Game, dt: Float) {
        while (true) {
            val p = inbox.poll() ?: break
            val d = reader(p.data)
            val c = p.from
            when (p.type) {
                Msg.HELLO -> {
                    c.name = d.readUTF()
                    players[c.id] = RemotePlayer(c.id, c.name).also { it.moveTo(game.player.x, game.player.y, game.player.z, 0f) }
                    c.send(Msg.WELCOME) {
                        it.writeInt(c.id); it.writeLong(game.world.seed); it.writeInt(game.level.mode.ordinal)
                        it.writeFloat(game.timeOfDay); it.writeUTF(worldName)
                        it.writeFloat(game.player.x); it.writeFloat(game.player.y); it.writeFloat(game.player.z)
                    }
                    game.uiEvents.add("toast:${c.name} joined the game")
                }
                Msg.CHUNK_REQ -> pendingChunks.add(Triple(c, d.readInt(), d.readInt()))
                Msg.SET_BLOCK -> {
                    val x = d.readInt(); val y = d.readInt(); val z = d.readInt(); val id = d.readShort().toInt(); val meta = d.readUnsignedByte()
                    game.remoteEdit(x, y, z, id, meta)
                }
                Msg.POS -> players[c.id]?.moveTo(d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat())
                Msg.ATTACK -> {
                    val uid = d.readInt(); val dmg = d.readFloat(); val kx = d.readFloat(); val kz = d.readFloat()
                    game.mobs.list.firstOrNull { it.uid == uid }?.let { game.mobs.damage(it, dmg, kx, kz) }
                }
                Msg.BYE -> c.close()
            }
        }
        // Players who left.
        for (c in clients) if (!c.open) {
            clients.remove(c)
            players.remove(c.id)?.let { game.uiEvents.add("toast:${it.name} left the game") }
        }
        // Serve requested chunks (loading them first if needed).
        val it = pendingChunks.iterator()
        var sent = 0
        while (it.hasNext() && sent < 12) {
            val (c, cx, cz) = it.next()
            if (!c.open) { it.remove(); continue }
            val chunk = game.world.getChunk(cx, cz)
            if (chunk == null) { game.world.request(cx, cz); continue }
            val data = chunkBytes(chunk)
            c.send(Msg.CHUNK) { o -> o.writeInt(cx); o.writeInt(cz); o.writeInt(data.size); o.write(data) }
            it.remove(); sent++
        }
        playerTimer += dt; mobTimer += dt; timeTimer += dt
        if (playerTimer >= 0.1f) {
            playerTimer = 0f
            val all = ArrayList<Triple<Int, String, FloatArray>>()
            all.add(Triple(0, hostName, floatArrayOf(game.player.x, game.player.y, game.player.z, game.player.yaw)))
            for (r in players.values) all.add(Triple(r.id, r.name, floatArrayOf(r.x, r.y, r.z, r.proxy.yaw)))
            for (c in clients) if (c.open) c.send(Msg.PLAYERS) { o ->
                val others = all.filter { a -> a.first != c.id }
                o.writeInt(others.size)
                for ((id, name, p) in others) { o.writeInt(id); o.writeUTF(name); for (v in p) o.writeFloat(v) }
            }
        }
        if (mobTimer >= 0.16f) {
            mobTimer = 0f
            val mobs = game.mobs.list.toList()
            broadcast(Msg.MOBS) { o ->
                o.writeInt(mobs.size)
                for (m in mobs) {
                    o.writeInt(m.uid); o.writeByte(m.type.ordinal)
                    o.writeFloat(m.x); o.writeFloat(m.y); o.writeFloat(m.z); o.writeFloat(m.yaw)
                    var f = 0
                    if (m.hurtTime > 0f) f = f or 1
                    if (m.dead) f = f or 2
                    if (m.fuse >= 0f) f = f or 4
                    if (m.loveTime > 0f) f = f or 8
                    if (m.age < 0f) f = f or 16
                    if (m.moving) f = f or 32
                    if (m.burning) f = f or 64
                    o.writeByte(f); o.writeFloat(m.walkPhase); o.writeFloat(maxOf(m.fuse, m.deathTime))
                }
            }
        }
        if (timeTimer >= 1f) {
            timeTimer = 0f
            broadcast(Msg.TIME) { o -> o.writeFloat(game.timeOfDay); o.writeFloat(game.rain) }
        }
    }

    fun connectedCount() = clients.count { it.open }

    override fun close() {
        running = false
        for (c in clients) { c.send(Msg.BYE); c.close() }
        try { server.close() } catch (_: Exception) {}
    }
}

/** A player who joined someone else's world. The host owns the world; we mirror it. */
class ClientSession private constructor(private val conn: Connection) : Session() {
    override val isClient = true
    var myId = 0; var seed = 0L; var mode = GameMode.CREATIVE; var time = 0.3f; var worldName = ""
    var spawnX = 0f; var spawnY = 80f; var spawnZ = 0f
    private var posTimer = 0f
    private val pendingChunks = HashSet<Long>()

    companion object {
        /** Connects and waits for the host's welcome (call off the main thread). */
        fun connect(address: String, name: String, timeoutMs: Int = 5000): ClientSession {
            val socket = Socket()
            socket.connect(InetSocketAddress(address, Msg.PORT), timeoutMs)
            val inbox = ConcurrentLinkedQueue<Packet>()
            val c = Connection(socket, inbox)
            val s = ClientSession(c)
            c.send(Msg.HELLO) { it.writeUTF(name) }
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val p = inbox.poll()
                if (p == null) { Thread.sleep(20); continue }
                if (p.type == Msg.WELCOME) {
                    val d = reader(p.data)
                    s.myId = d.readInt(); s.seed = d.readLong(); s.mode = GameMode.values()[d.readInt().coerceIn(0, 1)]
                    s.time = d.readFloat(); s.worldName = d.readUTF()
                    s.spawnX = d.readFloat(); s.spawnY = d.readFloat(); s.spawnZ = d.readFloat()
                    // Keep anything that arrived after the welcome.
                    while (true) s.inbox.add(inbox.poll() ?: break)
                    s.relay(inbox)
                    return s
                }
            }
            c.close()
            throw java.io.IOException("The host did not answer")
        }
    }

    /** Moves packets from the connection's queue into ours. */
    private fun relay(from: ConcurrentLinkedQueue<Packet>) {
        Thread({
            while (conn.open) {
                val p = from.poll()
                if (p == null) Thread.sleep(5) else inbox.add(p)
            }
        }, "net-relay").apply { isDaemon = true; start() }
    }

    val connected get() = conn.open
    override val status get() = if (conn.open) "Playing on \"$worldName\" · ${players.size + 1} players" else "Disconnected"

    fun requestChunk(cx: Int, cz: Int) {
        if (pendingChunks.add(Chunk.key(cx, cz))) conn.send(Msg.CHUNK_REQ) { it.writeInt(cx); it.writeInt(cz) }
    }

    override fun blockChanged(x: Int, y: Int, z: Int, id: Int, meta: Int) =
        conn.send(Msg.SET_BLOCK) { it.writeInt(x); it.writeInt(y); it.writeInt(z); it.writeShort(id); it.writeByte(meta) }

    override fun attack(uid: Int, damage: Float, kx: Float, kz: Float) =
        conn.send(Msg.ATTACK) { it.writeInt(uid); it.writeFloat(damage); it.writeFloat(kx); it.writeFloat(kz) }

    override fun poll(game: Game, dt: Float) {
        while (true) {
            val p = inbox.poll() ?: break
            val d = reader(p.data)
            when (p.type) {
                Msg.CHUNK -> {
                    val cx = d.readInt(); val cz = d.readInt(); val len = d.readInt()
                    val data = ByteArray(len); d.readFully(data)
                    val c = Chunk(cx, cz)
                    c.fromBytes(GZIPInputStream(data.inputStream()).use { it.readBytes() })
                    c.version = 1
                    pendingChunks.remove(Chunk.key(cx, cz))
                    game.world.receiveChunk(c)
                }
                Msg.SET_BLOCK -> game.applyRemoteBlock(d.readInt(), d.readInt(), d.readInt(), d.readShort().toInt(), d.readUnsignedByte())
                Msg.PLAYERS -> {
                    val seen = HashSet<Int>()
                    repeat(d.readInt()) {
                        val id = d.readInt(); val name = d.readUTF()
                        val r = players.getOrPut(id) { RemotePlayer(id, name) }
                        r.moveTo(d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat())
                        seen.add(id)
                    }
                    players.keys.retainAll(seen)
                }
                Msg.MOBS -> {
                    val old = game.mobs.list.associateBy { it.uid }
                    val fresh = ArrayList<Mob>()
                    repeat(d.readInt()) {
                        val uid = d.readInt(); val type = MobType.values()[d.readUnsignedByte()]
                        val m = old[uid]?.takeIf { it.type == type } ?: Mob(type, 0f, 0f, 0f, uid)
                        m.x = d.readFloat(); m.y = d.readFloat(); m.z = d.readFloat(); m.yaw = d.readFloat()
                        val f = d.readUnsignedByte()
                        m.hurtTime = if (f and 1 != 0) 0.2f else 0f
                        m.moving = f and 32 != 0
                        m.burning = f and 64 != 0
                        m.loveTime = if (f and 8 != 0) 1f else 0f
                        m.age = if (f and 16 != 0) -1f else 0f
                        m.walkPhase = d.readFloat()
                        val timer = d.readFloat()
                        m.fuse = if (f and 4 != 0) timer else -1f
                        m.deathTime = if (f and 2 != 0) timer.coerceAtLeast(0f) else -1f
                        fresh.add(m)
                    }
                    game.mobs.list.clear(); game.mobs.list.addAll(fresh)
                }
                Msg.HURT -> game.hurtPlayer(d.readFloat(), d.readFloat(), d.readFloat())
                Msg.TIME -> { game.timeOfDay = d.readFloat(); game.setRain(d.readFloat()) }
                Msg.BYE -> conn.close()
            }
        }
        posTimer += dt
        if (posTimer >= 0.1f) {
            posTimer = 0f
            val p = game.player
            conn.send(Msg.POS) { it.writeFloat(p.x); it.writeFloat(p.y); it.writeFloat(p.z); it.writeFloat(p.yaw) }
        }
        if (!conn.open && !notified) { notified = true; game.uiEvents.add("toast:Lost connection to the host") }
    }

    private var notified = false

    override fun close() { conn.send(Msg.BYE); conn.close() }
}

/** Hands a connected client from the title screen to the game screen. */
object Net {
    @Volatile var pendingClient: ClientSession? = null

    /** This device's Wi-Fi address, to show to friends who want to join. */
    fun localAddress(): String {
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is java.net.Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: continue
            }
        } catch (_: Exception) {
        }
        return "unknown"
    }
}
