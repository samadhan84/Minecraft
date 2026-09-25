package com.vishucraft.game.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue

/** Message types for the Wi-Fi protocol. Every message is [type:byte][length:int][payload]. */
object Msg {
    const val PORT = 25580
    const val DISCOVERY_PORT = 25581
    const val MAGIC = "VISHUCRAFT1"

    const val HELLO = 1        // c->h name
    const val WELCOME = 2      // h->c id, seed, mode, time, name
    const val CHUNK_REQ = 3    // c->h cx, cz
    const val CHUNK = 4        // h->c cx, cz, gzip(blocks + meta)
    const val SET_BLOCK = 5    // both ways x, y, z, id, meta
    const val POS = 6          // c->h x, y, z, yaw
    const val PLAYERS = 7      // h->c count, (id, name, x, y, z, yaw)*
    const val MOBS = 8         // h->c count, (uid, type, x, y, z, yaw, flags, phase)*
    const val ATTACK = 9       // c->h uid, damage, kx, kz
    const val HURT = 10        // h->c amount, fromX, fromZ
    const val TIME = 11        // h->c timeOfDay, rain
    const val BYE = 12
}

class Packet(val type: Int, val data: ByteArray, val from: Connection)

/** One TCP connection with a reader thread and a writer thread. */
class Connection(private val socket: Socket, private val inbox: ConcurrentLinkedQueue<Packet>) {
    @Volatile var open = true
        private set
    var id = -1
    var name = "Player"
    private val outbox = LinkedBlockingQueue<ByteArray>()

    init {
        socket.tcpNoDelay = true
        Thread({ readLoop() }, "net-read").apply { isDaemon = true; start() }
        Thread({ writeLoop() }, "net-write").apply { isDaemon = true; start() }
    }

    val address: String get() = socket.inetAddress.hostAddress ?: "?"

    fun send(type: Int, body: (DataOutputStream) -> Unit = {}) {
        if (!open) return
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(body)
        val payload = bytes.toByteArray()
        val frame = ByteArrayOutputStream(payload.size + 5)
        DataOutputStream(frame).use { it.writeByte(type); it.writeInt(payload.size); it.write(payload) }
        outbox.offer(frame.toByteArray())
    }

    private fun readLoop() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (open) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > 8_000_000) break
                val data = ByteArray(len)
                input.readFully(data)
                inbox.add(Packet(type, data, this))
            }
        } catch (_: Exception) {
        }
        close()
    }

    private fun writeLoop() {
        try {
            val out = BufferedOutputStream(socket.getOutputStream())
            while (open) {
                val frame = outbox.take()
                if (frame.isEmpty()) break
                out.write(frame)
                if (outbox.isEmpty()) out.flush()
            }
        } catch (_: Exception) {
        }
        close()
    }

    fun close() {
        if (!open) return
        open = false
        outbox.offer(ByteArray(0))
        try { socket.close() } catch (_: Exception) {}
    }
}

fun reader(data: ByteArray) = DataInputStream(ByteArrayInputStream(data))

/** Finds hosts on the local network (they broadcast their name every second). */
object Discovery {
    class Host(val name: String, val address: String, val players: Int)

    /** Listens for [millis] and returns the games heard. Call off the main thread. */
    fun scan(millis: Int): List<Host> {
        val found = LinkedHashMap<String, Host>()
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(Msg.DISCOVERY_PORT))
                s.soTimeout = 300
                val end = System.currentTimeMillis() + millis
                val buf = ByteArray(512)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length, Charsets.UTF_8).split('|')
                    if (text.size >= 3 && text[0] == Msg.MAGIC) {
                        val addr = p.address.hostAddress ?: continue
                        found[addr] = Host(text[1], addr, text[2].toIntOrNull() ?: 1)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found.values.toList()
    }

    fun announce(socket: DatagramSocket, name: String, players: Int) {
        val bytes = "${Msg.MAGIC}|$name|$players".toByteArray(Charsets.UTF_8)
        try {
            socket.broadcast = true
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), Msg.DISCOVERY_PORT))
        } catch (_: Exception) {
        }
    }
}
