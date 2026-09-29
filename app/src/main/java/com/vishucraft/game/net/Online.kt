package com.vishucraft.game.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/*
 * Online play with room codes. The host and each friend connect out to a small relay on the internet
 * (relay/ in the repository), which passes the bytes between them. The game itself doesn't change: on the
 * host, every friend becomes an ordinary connection to the Wi-Fi game on this device; on the friend's device,
 * the game joins a local address that leads through the relay.
 */
object Online {
    /** Where the build publishes the relay's address (written by the GitHub workflow that deploys it). */
    const val RELAY_ADDRESS_URL = "https://github.com/samadhan84/Minecraft/releases/download/latest/relay.txt"

    /** Set by tests and the desktop's --relay flag. */
    @Volatile var relayOverride: String? = null
    @Volatile private var cached: String? = null

    /** The relay's websocket base address (wss://…). Call off the main thread. */
    fun relayUrl(): String {
        relayOverride?.let { return it }
        cached?.let { return it }
        val text = try {
            val c = java.net.URL(RELAY_ADDRESS_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
            c.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            throw IOException("Couldn't reach the internet. Check your connection and try again.")
        }
        if (!text.startsWith("https://") && !text.startsWith("http://")) throw IOException("Online play isn't set up yet.")
        return text.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/').also { cached = it }
    }

    private val rnd = SecureRandom()

    /** A new 6-digit room code. */
    fun newCode(): String = (100000 + rnd.nextInt(900000)).toString()

    /** Keeps only letters and digits, upper case (people may type spaces or dashes). */
    fun cleanCode(code: String) = code.filter { it.isLetterOrDigit() }.uppercase()

    fun roomUrl(code: String, role: String) = "${relayUrl()}/room/${cleanCode(code)}?role=$role"
}

/** Thrown when the relay turns a connection down (for example: no game with that code). */
class RelayRefused(val status: Int, message: String) : IOException(message)

/** A small websocket client (RFC 6455): enough for the relay's binary and text messages. */
class WebSocketClient(url: String, timeoutMs: Int = 10000) {
    private val socket: Socket
    private val input: DataInputStream
    private val output: OutputStream
    private val rnd = SecureRandom()
    @Volatile var open = true
        private set

    init {
        val u = URI(url)
        val tls = u.scheme == "wss"
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        val raw = Socket()
        raw.connect(InetSocketAddress(u.host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        raw.tcpNoDelay = true
        socket = if (tls) ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, u.host, port, true) as SSLSocket).also { it.startHandshake() } else raw
        output = socket.getOutputStream()
        input = DataInputStream(socket.getInputStream().buffered())
        val keyBytes = ByteArray(16).also { rnd.nextBytes(it) }
        val path = (u.rawPath.ifEmpty { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = if (u.port > 0) "${u.host}:${u.port}" else u.host
        output.write(("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: ${base64(keyBytes)}\r\nSec-WebSocket-Version: 13\r\nUser-Agent: DhruvVishu\r\n\r\n").toByteArray())
        output.flush()
        val status = readLine().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        var length = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (status != 101) {
            val body = ByteArray(length.coerceIn(0, 300)).also { runCatching { input.readFully(it) } }
            socket.close(); open = false
            throw RelayRefused(status, String(body).trim().ifEmpty { "The relay said $status" })
        }
        socket.soTimeout = 0
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("The relay closed the connection")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 4096) throw IOException("Bad reply from the relay")
        }
        return sb.toString()
    }

    fun sendBinary(data: ByteArray, off: Int = 0, len: Int = data.size) = send(2, data, off, len)
    fun sendText(text: String) = text.toByteArray().let { send(1, it, 0, it.size) }

    @Synchronized private fun send(opcode: Int, data: ByteArray, off: Int, len: Int) {
        if (!open) throw IOException("closed")
        val head = ByteArrayOutputStream(14)
        head.write(0x80 or opcode)
        when {
            len < 126 -> head.write(0x80 or len)
            len < 65536 -> { head.write(0x80 or 126); head.write(len shr 8); head.write(len and 255) }
            else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write(((len.toLong() shr (8 * i)) and 255).toInt()) }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        head.write(mask)
        val body = ByteArray(len)
        for (i in 0 until len) body[i] = (data[off + i].toInt() xor mask[i and 3].toInt()).toByte()
        output.write(head.toByteArray()); output.write(body); output.flush()
    }

    /** One message from the relay: text is a String, binary a ByteArray; null when closed. */
    fun read(): Any? {
        var message: ByteArrayOutputStream? = null
        var messageOp = 0
        while (true) {
            val b0 = try { input.readUnsignedByte() } catch (e: Exception) { open = false; return null }
            val b1 = input.readUnsignedByte()
            val op = b0 and 15
            var len = (b1 and 127).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
            if (len > 8_000_000) throw IOException("message too big")
            val mask = if (b1 and 128 != 0) ByteArray(4).also { input.readFully(it) } else null
            val data = ByteArray(len.toInt()).also { input.readFully(it) }
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                8 -> { close(); return null }
                9 -> { try { send(10, data, 0, data.size) } catch (_: Exception) {}; continue }
                10 -> continue
                0 -> message?.write(data)
                else -> { message = ByteArrayOutputStream().also { it.write(data) }; messageOp = op }
            }
            if (b0 and 0x80 != 0 && message != null) {
                val bytes = message.toByteArray()
                return if (messageOp == 1) String(bytes) else bytes
            }
        }
    }

    fun close() {
        if (!open) return
        try { send(8, byteArrayOf(0x03, 0xE8.toByte()), 0, 2) } catch (_: Exception) {}
        open = false
        try { socket.close() } catch (_: Exception) {}
    }

    companion object {
        private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        fun base64(b: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < b.size) {
                val n = ((b[i].toInt() and 255) shl 16) or ((b.getOrNull(i + 1)?.toInt()?.and(255) ?: 0) shl 8) or (b.getOrNull(i + 2)?.toInt()?.and(255) ?: 0)
                sb.append(B64[(n shr 18) and 63]).append(B64[(n shr 12) and 63])
                sb.append(if (i + 1 < b.size) B64[(n shr 6) and 63] else '=')
                sb.append(if (i + 2 < b.size) B64[n and 63] else '=')
                i += 3
            }
            return sb.toString()
        }
    }
}

/** Sends a keep-alive now and then so the relay and phone networks don't drop a quiet connection. */
private fun keepAlive(ws: WebSocketClient) = Thread({
    while (ws.open) {
        try { Thread.sleep(20000); if (ws.open) ws.sendText("ping") } catch (_: Exception) { break }
    }
}, "relay-ping").apply { isDaemon = true; start() }

private fun pump(from: InputStream, to: (ByteArray, Int) -> Unit, done: () -> Unit) = Thread({
    val buf = ByteArray(16384)
    try {
        while (true) {
            val n = from.read(buf)
            if (n < 0) break
            if (n > 0) to(buf, n)
        }
    } catch (_: Exception) {
    } finally { done() }
}, "relay-pump").apply { isDaemon = true; start() }

/**
 * The host's side: claims a room code at the relay, and connects each friend who arrives to this device's
 * Wi-Fi game (which must already be open on [localPort]).
 */
class HostTunnel private constructor(val code: String, private val ws: WebSocketClient, private val localPort: Int) {
    private val guests = ConcurrentHashMap<Int, Socket>()
    val open get() = ws.open
    val guestCount get() = guests.size

    init {
        keepAlive(ws)
        Thread({ readLoop() }, "relay-host").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        while (true) {
            val m = try { ws.read() } catch (_: Exception) { null } ?: break
            if (m is String) {
                val g = Regex("\"g\":(\\d+)").find(m)?.groupValues?.get(1)?.toLongOrNull()?.toInt() ?: continue
                if (m.contains("\"open\"")) openGuest(g) else if (m.contains("\"close\"")) guests.remove(g)?.let { runCatching { it.close() } }
            } else if (m is ByteArray && m.size >= 4) {
                val g = ((m[0].toInt() and 255) shl 24) or ((m[1].toInt() and 255) shl 16) or ((m[2].toInt() and 255) shl 8) or (m[3].toInt() and 255)
                val s = guests[g] ?: continue
                try { s.getOutputStream().write(m, 4, m.size - 4) } catch (_: Exception) { guests.remove(g); runCatching { s.close() } }
            }
        }
        close()
    }

    private fun openGuest(g: Int) {
        val game = InetSocketAddress(InetAddress.getLoopbackAddress(), localPort)
        val s = try { Socket().apply { connect(game, 3000); tcpNoDelay = true } } catch (_: Exception) { return }
        guests[g] = s
        val prefix = byteArrayOf((g ushr 24).toByte(), (g ushr 16).toByte(), (g ushr 8).toByte(), g.toByte())
        pump(s.getInputStream(), { buf, n ->
            val out = ByteArray(n + 4)
            System.arraycopy(prefix, 0, out, 0, 4); System.arraycopy(buf, 0, out, 4, n)
            ws.sendBinary(out)
        }) { guests.remove(g); runCatching { s.close() } }
    }

    fun close() {
        ws.close()
        for (s in guests.values) runCatching { s.close() }
        guests.clear()
    }

    companion object {
        /** Opens a room for the Wi-Fi game on this device. Call off the main thread. */
        fun start(localPort: Int = Msg.PORT): HostTunnel {
            var last: Exception? = null
            repeat(6) {
                val code = Online.newCode()
                try { return HostTunnel(code, WebSocketClient(Online.roomUrl(code, "host")), localPort) }
                catch (e: RelayRefused) { last = e; if (e.status != 409) throw IOException(e.message) }
            }
            throw IOException(last?.message ?: "Couldn't get a room code")
        }
    }
}

/**
 * A friend's side: joins a room at the relay and offers a local address for the game to connect to.
 * It closes itself when the game disconnects.
 */
class GuestTunnel private constructor(private val ws: WebSocketClient) {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    /** The address to give ClientSession.connect. */
    val localAddress = "127.0.0.1:${server.localPort}"

    init {
        keepAlive(ws)
        Thread({
            val s = try { server.accept().apply { tcpNoDelay = true } } catch (_: Exception) { close(); return@Thread } finally { runCatching { server.close() } }
            pump(s.getInputStream(), { buf, n -> ws.sendBinary(buf, 0, n) }) { close(); runCatching { s.close() } }
            try {
                val out = s.getOutputStream()
                while (true) {
                    val m = ws.read() ?: break
                    if (m is ByteArray) out.write(m)
                }
            } catch (_: Exception) {}
            runCatching { s.close() }
            close()
        }, "relay-guest").apply { isDaemon = true; start() }
    }

    fun close() { ws.close(); runCatching { server.close() } }

    companion object {
        /** Joins room [code]. Call off the main thread; throws with a message to show when there's no such game. */
        fun join(code: String): GuestTunnel {
            val clean = Online.cleanCode(code)
            if (clean.length < 4) throw IOException("Type the room code the host sees (6 numbers)")
            return try { GuestTunnel(WebSocketClient(Online.roomUrl(clean, "guest"))) }
            catch (e: RelayRefused) {
                throw IOException(when (e.status) {
                    404 -> "No game with code $clean. Check the code, and that the host's game is open."
                    403 -> "That game is full."
                    else -> e.message
                })
            }
        }
    }
}
