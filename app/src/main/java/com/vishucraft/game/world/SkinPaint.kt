package com.vishucraft.game.world

import java.io.File

/**
 * Your painted skin: 24 little pictures of 16 x 16 pixels, one for each face of the head, body, arm and leg (both arms and
 * both legs share theirs). A painted pixel is drawn on a thin shell round the body; unpainted pixels show the chosen look
 * (skin, clothes, hair) underneath, and worn armor goes over everything. It is kept for every world in skin.dat.
 */
object SkinPaint {
    const val PARTS = 4
    const val FACES = 6
    const val N = 16
    val partNames = listOf("Head", "Body", "Arm", "Leg")
    /** In the order the game draws faces: top, bottom, back, front, right side, left side. */
    val faceNames = listOf("Top", "Bottom", "Back", "Front", "Right side", "Left side")

    /** ARGB, 0 = not painted. */
    val pixels = IntArray(PARTS * FACES * N * N)
    /** Goes up with every change, so pictures know when to refresh. */
    @Volatile var version = 1
        private set
    private var file: File? = null
    private var loadedFrom: File? = null
    private var painted = -1
    private var paintedVersion = -1

    fun tileName(part: Int, face: Int) = "skin_${part}_$face"
    fun fileFor(worldsDir: File) = File(worldsDir, "skin.dat")

    private fun idx(part: Int, face: Int, x: Int, y: Int) = ((part * FACES + face) * N + y) * N + x

    fun get(part: Int, face: Int, x: Int, y: Int): Int = pixels[idx(part, face, x.coerceIn(0, N - 1), y.coerceIn(0, N - 1))]

    fun set(part: Int, face: Int, x: Int, y: Int, argb: Int) {
        if (x !in 0 until N || y !in 0 until N) return
        val i = idx(part, face, x, y)
        if (pixels[i] != argb) { pixels[i] = argb; version++ }
    }

    fun fillFace(part: Int, face: Int, argb: Int) {
        for (y in 0 until N) for (x in 0 until N) pixels[idx(part, face, x, y)] = argb
        version++
    }

    fun clearAll() { pixels.fill(0); version++ }

    /** Tell the pictures that [pixels] were changed directly. */
    fun touch() { version++ }

    /** True while nothing at all is painted (then the body has no shell). */
    fun isEmpty(): Boolean {
        if (paintedVersion != version) { painted = pixels.count { it != 0 }; paintedVersion = version }
        return painted == 0
    }

    /** The colour of painting colour [n] of the palette (an opaque ARGB). */
    fun color(n: Int): Int {
        val c = Appearance.palette[n.coerceIn(0, Appearance.palette.size - 1)]
        return (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    /** Makes the saved skin in [f] the current one (loaded once; later changes are saved there). */
    fun attach(f: File?) {
        if (f == null || f == loadedFrom) return
        loadedFrom = f; file = f
        pixels.fill(0)
        try {
            if (f.exists()) for (line in f.readLines()) {
                val p = line.trim().split(' ')
                if (p.size == 5) set(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toLong(16).toInt())
            }
        } catch (_: Exception) {}
        version++
    }

    fun save() {
        val f = file ?: return
        try {
            val sb = StringBuilder()
            for (part in 0 until PARTS) for (face in 0 until FACES) for (y in 0 until N) for (x in 0 until N) {
                val c = pixels[idx(part, face, x, y)]
                if (c != 0) sb.append(part).append(' ').append(face).append(' ').append(x).append(' ').append(y).append(' ').append(java.lang.Long.toHexString(c.toLong() and 0xFFFFFFFFL)).append('\n')
            }
            f.writeText(sb.toString())
        } catch (_: Exception) {}
    }
}
