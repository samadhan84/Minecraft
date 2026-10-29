package com.vishucraft.desktop

import com.vishucraft.client.FontSheet
import com.vishucraft.client.NetSupport
import com.vishucraft.game.engine.Game
import com.vishucraft.game.net.ClientSession
import com.vishucraft.game.net.Discovery
import com.vishucraft.game.net.GuestTunnel
import com.vishucraft.game.net.HostSession
import com.vishucraft.game.net.HostTunnel
import com.vishucraft.game.net.Net
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** The UI font, drawn with the computer's sans-serif font (see client/Ui). */
fun awtFonts(chars: List<Char>, cell: Int): FontSheet {
    val cols = 16
    val rows = (chars.size + cols - 1) / cols
    val cellW = cell * 2
    val img = BufferedImage(cols * cellW, rows * cell, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, cell * 26 / 32)
    g.color = java.awt.Color.WHITE
    val fm = g.fontMetrics
    val xs = IntArray(chars.size); val ys = IntArray(chars.size); val ws = FloatArray(chars.size)
    for ((i, ch) in chars.withIndex()) {
        val x = (i % cols) * cellW; val y = (i / cols) * cell
        g.drawString(ch.toString(), x + 1, y + fm.ascent - 2)
        xs[i] = x; ys[i] = y; ws[i] = fm.charWidth(ch).toFloat() + 1
    }
    g.dispose()
    val px = IntArray(img.width * img.height)
    img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
    return FontSheet(px, img.width, img.height, xs, ys, ws)
}

/** ARGB pixels as an image (icons for the window and the installer). */
fun image(argb: IntArray, size: Int) = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, size, size, argb, 0, size) }

/** Wi-Fi games, and online rooms through the relay. */
class DesktopNet : NetSupport {
    @Volatile private var room: HostTunnel? = null

    override fun scan(): List<Discovery.Host> = Discovery.scan(2500)
    override fun connect(address: String, name: String): ClientSession? = ClientSession.connect(address, name)
    override fun connectRoom(code: String, name: String): ClientSession {
        var tunnel: GuestTunnel? = null
        try {
            tunnel = GuestTunnel.join(code)
            return ClientSession.connect(tunnel.localAddress, name, 12000)
        } catch (e: Exception) { tunnel?.close(); throw e }
    }
    override fun attach(client: ClientSession, world: com.vishucraft.game.world.World, game: Game) {
        world.remoteLoader = { cx, cz -> client.requestChunk(cx, cz) }
        game.net = client
    }
    override fun host(game: Game, worldName: String, playerName: String) { game.net = HostSession(game, worldName, playerName) }
    override fun localAddress(): String = Net.localAddress()
    override fun openRoom(): String = HostTunnel.start().also { room = it }.code
    override fun roomCode(): String? = room?.takeIf { it.open }?.code
    override fun closeRoom() { room?.let { r -> Thread { r.close() }.start() }; room = null }
    override fun internetHelp(): String = Net.internetHelp(Net.internetAddress())
}
