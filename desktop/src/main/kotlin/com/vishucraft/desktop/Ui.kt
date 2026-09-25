package com.vishucraft.desktop

import com.vishucraft.game.render.TextureAtlas
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Facing
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.RenderType
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15.*
import org.lwjgl.opengl.GL20.*
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.ByteBuffer

/**
 * A tiny immediate-mode 2D toolkit on top of OpenGL: coloured rectangles, textured images, text and buttons.
 * Coordinates are in "UI units" (pixels divided by [scale]) with the origin at the top left.
 */
class Ui {
    var width = 1f
    var height = 1f
    var scale = 1f

    // Mouse state for this frame (in UI units).
    var mouseX = 0f
    var mouseY = 0f
    var clicked = false
    var rightClicked = false
    var mouseDown = false
    var shift = false
    var wheel = 0f

    private val program: Int
    private val vbo: Int
    private val data = FloatArray(8 * 6 * 4096)
    private var count = 0
    private var boundTex = -1
    private val white: Int
    private val font: FontAtlas
    private val icons = IconAtlas()
    val atlasTex: Int

    init {
        program = link(
            """#version 120
attribute vec2 aPos; attribute vec2 aUv; attribute vec4 aColor;
uniform vec2 uSize; varying vec2 vUv; varying vec4 vColor;
void main() { gl_Position = vec4(aPos.x / uSize.x * 2.0 - 1.0, 1.0 - aPos.y / uSize.y * 2.0, 0.0, 1.0); vUv = aUv; vColor = aColor; }""",
            """#version 120
uniform sampler2D uTex; varying vec2 vUv; varying vec4 vColor;
void main() { gl_FragColor = texture2D(uTex, vUv) * vColor; }""",
        )
        vbo = glGenBuffers()
        white = texture(1, 1, intArrayOf(-1), false)
        atlasTex = texture(TextureAtlas.SIZE, TextureAtlas.SIZE, TextureAtlas.pixels, false)
        font = FontAtlas()
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String) = glCreateShader(type).also { s ->
            glShaderSource(s, src); glCompileShader(s)
            if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) error(glGetShaderInfoLog(s))
        }
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, vs)); glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
        glBindAttribLocation(p, 0, "aPos"); glBindAttribLocation(p, 1, "aUv"); glBindAttribLocation(p, 2, "aColor")
        glLinkProgram(p)
        if (glGetProgrami(p, GL_LINK_STATUS) == 0) error(glGetProgramInfoLog(p))
        return p
    }

    companion object {
        /** Uploads ARGB pixels as a texture. */
        fun texture(w: Int, h: Int, argb: IntArray, smooth: Boolean): Int {
            val buf: ByteBuffer = BufferUtils.createByteBuffer(w * h * 4)
            for (c in argb) buf.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte()).put((c ushr 24).toByte())
            buf.flip()
            val t = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, t)
            val f = if (smooth) GL_LINEAR else GL_NEAREST
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, f)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, f)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, 0x812F)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, 0x812F)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            return t
        }

        fun rgba(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ---------------------------------------------------------------- frame

    fun begin(pixelW: Int, pixelH: Int) {
        // Keep the UI a comfortable size on small and large screens.
        scale = (minOf(pixelW / 960f, pixelH / 540f)).coerceIn(0.75f, 3f)
        width = pixelW / scale; height = pixelH / scale
        glViewport(0, 0, pixelW, pixelH)
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glUseProgram(program)
        glUniform2f(glGetUniformLocation(program, "uSize"), width, height)
        glUniform1i(glGetUniformLocation(program, "uTex"), 0)
        glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0)
        boundTex = -1
    }

    fun end() {
        flush()
        clicked = false; rightClicked = false; wheel = 0f
        // The 3D renderer expects these states to stay as it set them up.
        glEnable(GL_DEPTH_TEST); glEnable(GL_CULL_FACE); glDisable(GL_BLEND); glDepthMask(true)
    }

    private fun bind(tex: Int) {
        if (tex == boundTex) return
        flush()
        glBindTexture(GL_TEXTURE_2D, tex)
        boundTex = tex
    }

    fun flush() {
        if (count == 0) return
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        val buf = BufferUtils.createFloatBuffer(count)
        buf.put(data, 0, count).flip()
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW)
        for (i in 0..2) glEnableVertexAttribArray(i)
        for (i in 3..7) glDisableVertexAttribArray(i)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 32, 0L)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 32, 8L)
        glVertexAttribPointer(2, 4, GL_FLOAT, false, 32, 16L)
        glDrawArrays(GL_TRIANGLES, 0, count / 8)
        count = 0
    }

    private fun vert(x: Float, y: Float, u: Float, v: Float, c: Int) {
        if (count + 8 > data.size) flush()
        data[count++] = x; data[count++] = y; data[count++] = u; data[count++] = v
        data[count++] = ((c shr 16) and 255) / 255f; data[count++] = ((c shr 8) and 255) / 255f
        data[count++] = (c and 255) / 255f; data[count++] = (c ushr 24) / 255f
    }

    private fun quad(x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, c: Int) {
        vert(x, y, u0, v0, c); vert(x + w, y, u1, v0, c); vert(x + w, y + h, u1, v1, c)
        vert(x, y, u0, v0, c); vert(x + w, y + h, u1, v1, c); vert(x, y + h, u0, v1, c)
    }

    // ---------------------------------------------------------------- drawing

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) { bind(white); quad(x, y, w, h, 0f, 0f, 1f, 1f, color) }

    fun frame(x: Float, y: Float, w: Float, h: Float, color: Int, t: Float = 2f) {
        rect(x, y, w, t, color); rect(x, y + h - t, w, t, color); rect(x, y, t, h, color); rect(x + w - t, y, t, h, color)
    }

    /** A 16x16 tile from the block atlas. */
    fun tile(index: Int, x: Float, y: Float, w: Float, h: Float, tint: Int = -1) {
        bind(atlasTex)
        val n = TextureAtlas.TILES_PER_ROW.toFloat()
        val u0 = (index % TextureAtlas.TILES_PER_ROW) / n; val v0 = (index / TextureAtlas.TILES_PER_ROW) / n
        quad(x, y, w, h, u0, v0, u0 + 1f / n, v0 + 1f / n, tint)
    }

    /** The icon for a block or item (isometric cube for blocks). */
    fun icon(slot: Int, x: Float, y: Float, size: Float) {
        val (tex, u0, v0, u1, v1) = icons.get(slot)
        bind(tex)
        quad(x, y, size, size, u0, v0, u1, v1, -1)
    }

    fun textWidth(s: String, size: Float) = font.width(s) * size / FontAtlas.CELL

    /** Draws text; [align] 0 = left, 1 = centre, 2 = right. */
    fun text(s: String, x: Float, y: Float, size: Float = 16f, color: Int = -1, align: Int = 0, shadow: Boolean = true) {
        val w = textWidth(s, size)
        var cx = when (align) { 1 -> x - w / 2; 2 -> x - w; else -> x }
        bind(font.tex)
        val k = size / FontAtlas.CELL
        for (ch in s) {
            val g = font.glyph(ch)
            if (shadow) quad(cx + k * 2, y + k * 2, g.w * k, FontAtlas.CELL * k, g.u0, g.v0, g.u1, g.v1, (color and 0xFF000000.toInt()) or 0x202020)
            quad(cx, y, g.w * k, FontAtlas.CELL * k, g.u0, g.v0, g.u1, g.v1, color)
            cx += g.w * k
        }
    }

    fun hover(x: Float, y: Float, w: Float, h: Float) = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h

    /** A stone-style button; returns true when clicked. */
    fun button(label: String, x: Float, y: Float, w: Float, h: Float = 36f, enabled: Boolean = true): Boolean {
        val over = enabled && hover(x, y, w, h)
        rect(x, y, w, h, if (!enabled) rgba(70, 70, 70) else if (over) rgba(96, 124, 204) else rgba(112, 112, 112))
        frame(x, y, w, h, if (over) rgba(255, 235, 90) else rgba(30, 30, 30), if (over) 3f else 2f)
        text(label, x + w / 2, y + h / 2 - 9f, 18f, if (enabled) -1 else rgba(160, 160, 160), 1)
        return over && clicked
    }

    /** A one-line text box; click to focus, then type. */
    class Field(var text: String, val hint: String = "", val maxLength: Int = 32) { var focused = false }

    /** The focused field receives typed characters (see [type] / [backspace]). */
    var focus: Field? = null

    fun field(f: Field, x: Float, y: Float, w: Float, h: Float = 36f) {
        if (clicked) { if (hover(x, y, w, h)) focus = f else if (focus === f) focus = null }
        f.focused = focus === f
        rect(x, y, w, h, rgba(20, 20, 20))
        frame(x, y, w, h, if (f.focused) rgba(255, 235, 90) else rgba(150, 150, 150), 2f)
        val caret = if (f.focused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        if (f.text.isEmpty() && !f.focused) text(f.hint, x + 10, y + h / 2 - 9f, 18f, rgba(130, 130, 130), shadow = false)
        else text(f.text + caret, x + 10, y + h / 2 - 9f, 18f)
    }

    fun type(c: Int) {
        val f = focus ?: return
        if (c in 32..126 && f.text.length < f.maxLength) f.text += c.toChar()
    }

    fun backspace() { focus?.let { if (it.text.isNotEmpty()) it.text = it.text.dropLast(1) } }

    // ---------------------------------------------------------------- font

    /** Glyphs rendered once from the computer's sans-serif font into a texture. */
    private class FontAtlas {
        companion object { const val CELL = 32 }
        class Glyph(val w: Float, val u0: Float, val v0: Float, val u1: Float, val v1: Float)
        private val glyphs = HashMap<Char, Glyph>()
        val tex: Int

        init {
            val chars = (32..126).map { it.toChar() } + listOf('°', '·', '…', '•', '▲', '▼', '♥', 'é', '●', '◆', '×', '→')
            val cols = 16
            val rows = (chars.size + cols - 1) / cols
            val img = BufferedImage(cols * CELL * 2, rows * CELL, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
            g.color = java.awt.Color.WHITE
            val fm = g.fontMetrics
            val cellW = CELL * 2
            for ((i, ch) in chars.withIndex()) {
                val x = (i % cols) * cellW; val y = (i / cols) * CELL
                g.drawString(ch.toString(), x + 1, y + fm.ascent - 2)
                val w = fm.charWidth(ch).toFloat() + 1
                glyphs[ch] = Glyph(w, x / img.width.toFloat(), y / img.height.toFloat(), (x + w) / img.width.toFloat(), (y + CELL) / img.height.toFloat())
            }
            g.dispose()
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            tex = texture(img.width, img.height, px, true)
        }

        fun glyph(c: Char) = glyphs[c] ?: glyphs['?']!!
        fun width(s: String) = s.sumOf { glyph(it).w.toDouble() }.toFloat()
    }
}

/** Block and item icons drawn with Java2D from the atlas and packed into one texture. */
class IconAtlas {
    data class Ref(val tex: Int, val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    private val size = 64
    private val perRow = 32
    private val img = BufferedImage(size * perRow, size * perRow, BufferedImage.TYPE_INT_ARGB)
    private val slots = HashMap<Int, Int>()
    private var tex = 0
    private var dirty = true
    operator fun get(slot: Int): Ref {
        val index = slots.getOrPut(slot) { draw(slots.size, slot); dirty = true; slots.size }
        if (dirty) upload()
        val n = perRow.toFloat()
        val u0 = (index % perRow) / n; val v0 = (index / perRow) / n
        return Ref(tex, u0, v0, u0 + 1f / n, v0 + 1f / n)
    }

    private fun upload() {
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        if (tex != 0) org.lwjgl.opengl.GL11.glDeleteTextures(tex)
        tex = Ui.texture(img.width, img.height, px, false)
        dirty = false
    }

    private fun draw(index: Int, slot: Int) {
        val g = img.createGraphics()
        paint(g, slot, (index % perRow) * size, (index / perRow) * size, size)
        g.dispose()
    }

    companion object {
        private val atlas: BufferedImage by lazy {
            BufferedImage(TextureAtlas.SIZE, TextureAtlas.SIZE, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, TextureAtlas.SIZE, TextureAtlas.SIZE, TextureAtlas.pixels, 0, TextureAtlas.SIZE)
            }
        }

        private fun tile(i: Int): BufferedImage = atlas.getSubimage((i % TextureAtlas.TILES_PER_ROW) * 16, (i / TextureAtlas.TILES_PER_ROW) * 16, 16, 16)

        /** Affine map of the unit tile square to the parallelogram (p0, p1, p2) = (top-left, top-right, bottom-left). */
        private fun face(g: java.awt.Graphics2D, t: BufferedImage, p: FloatArray, shade: Float, ox: Int, oy: Int) {
            val at = AffineTransform((p[2] - p[0]) / 16.0, (p[3] - p[1]) / 16.0, (p[4] - p[0]) / 16.0, (p[5] - p[1]) / 16.0, (ox + p[0]).toDouble(), (oy + p[1]).toDouble())
            val shaded = if (shade >= 1f) t else BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { s ->
                for (y in 0 until 16) for (x in 0 until 16) {
                    val c = t.getRGB(x, y)
                    val r = (((c shr 16) and 255) * shade).toInt(); val gg = (((c shr 8) and 255) * shade).toInt(); val b = ((c and 255) * shade).toInt()
                    s.setRGB(x, y, (c and 0xFF000000.toInt()) or (r shl 16) or (gg shl 8) or b)
                }
            }
            g.drawImage(shaded, at, null)
        }

        /** Draws the icon for a block (isometric cube) or item into [g] at (ox, oy). */
        fun paint(g: java.awt.Graphics2D, slot: Int, ox: Int, oy: Int, size: Int) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            val item = Items[slot]
            val s = size.toFloat()
            if (item != null) {
                g.drawImage(tile(item.icon), ox, oy, size, size, null)
                if (item.enchanted) {
                    g.color = java.awt.Color(190, 110, 255, 90)
                    g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_ATOP, 0.5f)
                    for (k in -size until size * 2 step 22) g.fillPolygon(intArrayOf(ox + k, ox + k + 10, ox + k + 10 - size, ox + k - size), intArrayOf(oy, oy, oy + size, oy + size), 4)
                    g.composite = java.awt.AlphaComposite.SrcOver
                }
            } else if (slot in 1 until Blocks.COUNT) {
                val d = Blocks[slot]
                if (d.render == RenderType.CROSS || d.render == RenderType.FLAT || d.render == RenderType.RAIL) {
                    g.drawImage(tile(d.top), ox, oy, size, size, null)
                } else {
                    val top = if (d.facing == Facing.ALL) d.front else d.top
                    val left = if (d.facing == Facing.HORIZONTAL) d.front else d.side
                    face(g, tile(top), floatArrayOf(0f, s * 0.25f, s * 0.5f, 0f, s * 0.5f, s * 0.5f), 1f, ox, oy)
                    face(g, tile(left), floatArrayOf(0f, s * 0.25f, s * 0.5f, s * 0.5f, 0f, s * 0.75f), 0.8f, ox, oy)
                    face(g, tile(d.side), floatArrayOf(s * 0.5f, s * 0.5f, s, s * 0.25f, s * 0.5f, s), 0.6f, ox, oy)
                }
            }
        }

        /** The game's icon: a grass block. */
        fun appIcon(size: Int): BufferedImage = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { img ->
            val g = img.createGraphics()
            paint(g, Blocks.GRASS, 0, 0, size)
            g.dispose()
        }
    }
}
