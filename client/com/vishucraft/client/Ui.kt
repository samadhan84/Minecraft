package com.vishucraft.client

import com.vishucraft.game.render.TextureAtlas
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Facing
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.RenderType
import android.opengl.GLES20.*

/**
 * A tiny immediate-mode 2D toolkit on top of OpenGL ES 2 / WebGL: coloured rectangles, textured images, text and
 * buttons, shared by the desktop and browser versions. Coordinates are in "UI units" (pixels divided by [scale])
 * with the origin at the top left.
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
            """attribute vec2 aPos; attribute vec2 aUv; attribute vec4 aColor;
uniform vec2 uSize; varying vec2 vUv; varying vec4 vColor;
void main() { gl_Position = vec4(aPos.x / uSize.x * 2.0 - 1.0, 1.0 - aPos.y / uSize.y * 2.0, 0.0, 1.0); vUv = aUv; vColor = aColor; }""",
            """precision mediump float;
uniform sampler2D uTex; varying vec2 vUv; varying vec4 vColor;
void main() { gl_FragColor = texture2D(uTex, vUv) * vColor; }""",
        )
        vbo = IntArray(1).also { glGenBuffers(1, it, 0) }[0]
        white = texture(1, 1, intArrayOf(-1), false)
        atlasTex = texture(TextureAtlas.SIZE, TextureAtlas.SIZE, TextureAtlas.pixels, false)
        font = FontAtlas()
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String) = glCreateShader(type).also { s ->
            glShaderSource(s, src); glCompileShader(s)
            val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error(glGetShaderInfoLog(s))
        }
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, vs)); glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
        glBindAttribLocation(p, 0, "aPos"); glBindAttribLocation(p, 1, "aUv"); glBindAttribLocation(p, 2, "aColor")
        glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error(glGetProgramInfoLog(p))
        return p
    }

    companion object {
        /** Uploads ARGB pixels as a texture. */
        fun texture(w: Int, h: Int, argb: IntArray, smooth: Boolean): Int {
            val t = IntArray(1).also { glGenTextures(1, it, 0) }[0]
            glBindTexture(GL_TEXTURE_2D, t)
            val f = if (smooth) GL_LINEAR else GL_NEAREST
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, f)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, f)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, 0x812F)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, 0x812F)
            texImageArgb(w, h, argb)
            return t
        }

        fun rgba(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ---------------------------------------------------------------- frame

    fun begin(pixelW: Int, pixelH: Int) {
        // Keep the UI a comfortable size on small and large screens.
        scale = (minOf(pixelW / 960f, pixelH / 540f)).coerceIn(0.75f, 3f)
        width = pixelW / scale; height = pixelH / scale
        fieldRects.clear()
        glViewport(0, 0, pixelW, pixelH)
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glUseProgram(program)
        glUniform2f(glGetUniformLocation(program, "uSize"), width, height)
        glUniform1i(glGetUniformLocation(program, "uTex"), 0)
        glActiveTexture(GL_TEXTURE0)
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
        bufferFloats(GL_ARRAY_BUFFER, data, count, GL_STREAM_DRAW)
        for (i in 0..2) glEnableVertexAttribArray(i)
        for (i in 3..3) glDisableVertexAttribArray(i)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 32, 0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 32, 8)
        glVertexAttribPointer(2, 4, GL_FLOAT, false, 32, 16)
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
    class Field(var text: String, val hint: String = "", val maxLength: Int = 32, val secret: Boolean = false) { var focused = false }

    /** Set when Enter is pressed in a text box; screens use it as "OK". */
    var submitted = false
    fun takeSubmit(): Boolean = submitted.also { submitted = false }

    /** The focused field receives typed characters (see [type] / [backspace]). */
    var focus: Field? = null

    /** Where this frame's text boxes are, in pixels (x, y, w, h each): the browser opens the phone's keyboard there. */
    val fieldRects = ArrayList<Float>()

    fun field(f: Field, x: Float, y: Float, w: Float, h: Float = 36f) {
        fieldRects.add(x * scale); fieldRects.add(y * scale); fieldRects.add(w * scale); fieldRects.add(h * scale)
        if (clicked) { if (hover(x, y, w, h)) focus = f else if (focus === f) focus = null }
        f.focused = focus === f
        rect(x, y, w, h, rgba(20, 20, 20))
        frame(x, y, w, h, if (f.focused) rgba(255, 235, 90) else rgba(150, 150, 150), 2f)
        val caret = if (f.focused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        if (f.text.isEmpty() && !f.focused) text(f.hint, x + 10, y + h / 2 - 9f, 18f, rgba(130, 130, 130), shadow = false)
        else text((if (f.secret) "*".repeat(f.text.length) else f.text) + caret, x + 10, y + h / 2 - 9f, 18f)
    }

    fun type(c: Int) {
        val f = focus ?: return
        if (c in 32..126 && f.text.length < f.maxLength) f.text += c.toChar()
    }

    fun backspace() { focus?.let { if (it.text.isNotEmpty()) it.text = it.text.dropLast(1) } }

    // ---------------------------------------------------------------- font

    /** Glyphs rendered once into a texture by the platform's font (see [Ui.fonts]). */
    private class FontAtlas {
        companion object { const val CELL = 32 }
        class Glyph(val w: Float, val u0: Float, val v0: Float, val u1: Float, val v1: Float)
        private val glyphs = HashMap<Char, Glyph>()
        val tex: Int

        init {
            val chars = (32..126).map { it.toChar() } + listOf('°', '·', '…', '•', '▲', '▼', '♥', 'é', '●', '◆', '×', '→', '✓', '○', '⏱')
            val sheet = fonts(chars, CELL)
            for ((i, ch) in chars.withIndex()) {
                val x = sheet.x[i]; val y = sheet.y[i]; val w = sheet.widths[i]
                glyphs[ch] = Glyph(w, x / sheet.width.toFloat(), y / sheet.height.toFloat(), (x + w) / sheet.width.toFloat(), (y + CELL) / sheet.height.toFloat())
            }
            tex = texture(sheet.width, sheet.height, sheet.pixels, true)
        }

        fun glyph(c: Char) = glyphs[c] ?: glyphs['?']!!
        fun width(s: String): Float { var w = 0f; for (c in s) w += glyph(c).w; return w }
    }
}

/** Text drawn by the platform (Java2D on computers, the browser's canvas on the web): white glyphs on clear. */
class FontSheet(val pixels: IntArray, val width: Int, val height: Int, val x: IntArray, val y: IntArray, val widths: FloatArray)

/** Draws [chars] in cells [cell] pixels high into one image. Set by each platform before the first [Ui]. */
lateinit var fonts: (chars: List<Char>, cell: Int) -> FontSheet

/** Block and item icons (isometric cubes for blocks) painted from the atlas and packed into one texture. */
class IconAtlas {
    data class Ref(val tex: Int, val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    private val size = 64
    private val perRow = 32
    private val img = IntArray(size * perRow * size * perRow)
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
        if (tex != 0) glDeleteTextures(1, intArrayOf(tex), 0)
        tex = Ui.texture(size * perRow, size * perRow, img, false)
        dirty = false
    }

    private fun draw(index: Int, slot: Int) {
        val icon = paint(slot, size)
        val ox = (index % perRow) * size; val oy = (index / perRow) * size
        val stride = size * perRow
        for (y in 0 until size) System.arraycopy(icon, y * size, img, (oy + y) * stride + ox, size)
    }

    companion object {
        private fun texel(tile: Int, u: Int, v: Int): Int {
            val tx = (tile % TextureAtlas.TILES_PER_ROW) * 16 + u.coerceIn(0, 15)
            val ty = (tile / TextureAtlas.TILES_PER_ROW) * 16 + v.coerceIn(0, 15)
            return TextureAtlas.pixels[ty * TextureAtlas.SIZE + tx]
        }

        private fun shade(c: Int, k: Float): Int {
            if (k >= 1f) return c
            val r = (((c shr 16) and 255) * k).toInt(); val g = (((c shr 8) and 255) * k).toInt(); val b = ((c and 255) * k).toInt()
            return (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }

        /** Maps the tile onto the parallelogram (p0, p1, p2) = (top-left, top-right, bottom-left) of [out]. */
        private fun face(out: IntArray, size: Int, tile: Int, p: FloatArray, k: Float) {
            val ax = p[2] - p[0]; val ay = p[3] - p[1]; val bx = p[4] - p[0]; val by = p[5] - p[1]
            val det = ax * by - ay * bx
            if (kotlin.math.abs(det) < 1e-4f) return
            for (y in 0 until size) for (x in 0 until size) {
                val dx = x + 0.5f - p[0]; val dy = y + 0.5f - p[1]
                val u = (dx * by - dy * bx) / det; val v = (ax * dy - ay * dx) / det
                if (u < 0f || u >= 1f || v < 0f || v >= 1f) continue
                val c = texel(tile, (u * 16).toInt(), (v * 16).toInt())
                if (c ushr 24 < 128) continue
                out[y * size + x] = shade(c, k)
            }
        }

        /** The icon for a block (isometric cube) or item, as [size] x [size] ARGB pixels. */
        fun paint(slot: Int, size: Int): IntArray {
            val out = IntArray(size * size)
            val item = Items[slot]
            val s = size.toFloat()
            fun flat(tile: Int) { for (y in 0 until size) for (x in 0 until size) out[y * size + x] = texel(tile, x * 16 / size, y * 16 / size) }
            if (item != null) {
                flat(item.icon)
                if (item.enchanted) for (y in 0 until size) for (x in 0 until size) {
                    val i = y * size + x
                    if (out[i] ushr 24 > 0 && ((x + y) % 22) < 10) {
                        val c = out[i]
                        val r = minOf(255, ((c shr 16) and 255) + 50); val g = minOf(255, ((c shr 8) and 255) + 20); val b = minOf(255, (c and 255) + 70)
                        out[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                    }
                }
            } else if (slot in 1 until Blocks.COUNT) {
                val d = Blocks[slot]
                if (d.render == RenderType.CROSS || d.render == RenderType.FLAT || d.render == RenderType.RAIL || slot == Blocks.LEVER) {
                    flat(d.top)
                } else {
                    val top = if (d.facing == Facing.ALL) d.front else d.top
                    val left = if (d.facing == Facing.HORIZONTAL) d.front else d.side
                    // Buttons and pressure plates are drawn as thin slabs.
                    val h = when {
                        d.render == RenderType.BOX && d.box != null -> d.box!![4].coerceAtLeast(0.25f)
                        else -> Blocks.iconHeight(slot)
                    }
                    val o = (1f - h) * s * 0.5f
                    face(out, size, top, floatArrayOf(0f, s * 0.25f + o, s * 0.5f, o, s * 0.5f, s * 0.5f + o), 1f)
                    face(out, size, left, floatArrayOf(0f, s * 0.25f + o, s * 0.5f, s * 0.5f + o, 0f, s * 0.75f), 0.8f)
                    face(out, size, d.side, floatArrayOf(s * 0.5f, s * 0.5f + o, s, s * 0.25f + o, s * 0.5f, s), 0.6f)
                }
            }
            return out
        }

        /** The game's icon: a grass block. */
        fun appIcon(size: Int): IntArray = paint(Blocks.GRASS, size)
    }
}
