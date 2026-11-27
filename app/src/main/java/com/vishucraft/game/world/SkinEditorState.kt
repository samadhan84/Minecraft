package com.vishucraft.game.world

/** What the skin painter has chosen (part, face, colour, tool) and the painting itself, shared by every screen that paints. */
class SkinEditorState {
    var part = 0
    var face = 3
    /** A colour of [Appearance.palette]. */
    var color = 3
    /** 0 brush, 1 fill, 2 eraser. */
    var tool = 0
    /** Also paint the mirror pixel on the other side of the face (good for faces and sleeves). */
    var mirror = true
    private val undo = ArrayList<IntArray>()

    /** Call when a stroke starts, so that it can be undone as one step. */
    fun begin() {
        undo.add(SkinPaint.pixels.copyOf())
        if (undo.size > 30) undo.removeAt(0)
    }

    fun undoLast() {
        if (undo.isEmpty()) return
        val s = undo.removeAt(undo.size - 1)
        for (i in s.indices) SkinPaint.pixels[i] = s[i]
        SkinPaint.touch()
    }

    private fun paintAt(x: Int, y: Int, argb: Int) {
        SkinPaint.set(part, face, x, y, argb)
        if (mirror) SkinPaint.set(part, face, SkinPaint.N - 1 - x, y, argb)
    }

    /** The pointer is on pixel (x, y) of the face being painted. */
    fun apply(x: Int, y: Int) {
        if (x !in 0 until SkinPaint.N || y !in 0 until SkinPaint.N) return
        when (tool) {
            0 -> paintAt(x, y, SkinPaint.color(color))
            2 -> paintAt(x, y, 0)
            else -> flood(x, y, SkinPaint.color(color))
        }
    }

    /** Fills the connected area of one colour (or of nothing) that (x, y) is in. */
    private fun flood(x: Int, y: Int, argb: Int) {
        val from = SkinPaint.get(part, face, x, y)
        if (from == argb) return
        val stack = ArrayList<Int>(); stack.add(y * SkinPaint.N + x)
        while (stack.isNotEmpty()) {
            val i = stack.removeAt(stack.size - 1)
            val cx = i % SkinPaint.N; val cy = i / SkinPaint.N
            if (cx !in 0 until SkinPaint.N || cy !in 0 until SkinPaint.N || SkinPaint.get(part, face, cx, cy) != from) continue
            SkinPaint.set(part, face, cx, cy, argb)
            if (cx > 0) stack.add(cy * SkinPaint.N + cx - 1); if (cx < SkinPaint.N - 1) stack.add(cy * SkinPaint.N + cx + 1)
            if (cy > 0) stack.add((cy - 1) * SkinPaint.N + cx); if (cy < SkinPaint.N - 1) stack.add((cy + 1) * SkinPaint.N + cx)
        }
    }

    fun clearFace() { begin(); SkinPaint.fillFace(part, face, 0) }
    fun clearAll() { begin(); SkinPaint.clearAll() }
    fun fillFace() { begin(); SkinPaint.fillFace(part, face, SkinPaint.color(color)) }
}
