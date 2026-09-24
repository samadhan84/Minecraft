package com.blockcraft.game.world

import kotlin.math.floor

/** Seeded classic gradient (Perlin) noise in 2D and 3D. */
class Noise(seed: Long) {
    private val perm = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rnd = java.util.Random(seed)
        for (i in 255 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)

    private fun grad2(hash: Int, x: Double, y: Double): Double = when (hash and 7) {
        0 -> x + y
        1 -> -x + y
        2 -> x - y
        3 -> -x - y
        4 -> x
        5 -> -x
        6 -> y
        else -> -y
    }

    private fun grad3(hash: Int, x: Double, y: Double, z: Double): Double {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
    }

    /** Returns roughly [-1, 1]. */
    fun noise2(xIn: Double, yIn: Double): Double {
        val fx = floor(xIn); val fy = floor(yIn)
        val xi = fx.toInt() and 255; val yi = fy.toInt() and 255
        val x = xIn - fx; val y = yIn - fy
        val u = fade(x); val v = fade(y)
        val a = perm[xi] + yi; val b = perm[xi + 1] + yi
        return lerp(v,
            lerp(u, grad2(perm[a], x, y), grad2(perm[b], x - 1, y)),
            lerp(u, grad2(perm[a + 1], x, y - 1), grad2(perm[b + 1], x - 1, y - 1))) * 0.7071
    }

    /** Returns roughly [-1, 1]. */
    fun noise3(xIn: Double, yIn: Double, zIn: Double): Double {
        val fx = floor(xIn); val fy = floor(yIn); val fz = floor(zIn)
        val xi = fx.toInt() and 255; val yi = fy.toInt() and 255; val zi = fz.toInt() and 255
        val x = xIn - fx; val y = yIn - fy; val z = zIn - fz
        val u = fade(x); val v = fade(y); val w = fade(z)
        val a = perm[xi] + yi; val aa = perm[a] + zi; val ab = perm[a + 1] + zi
        val b = perm[xi + 1] + yi; val ba = perm[b] + zi; val bb = perm[b + 1] + zi
        return lerp(w,
            lerp(v,
                lerp(u, grad3(perm[aa], x, y, z), grad3(perm[ba], x - 1, y, z)),
                lerp(u, grad3(perm[ab], x, y - 1, z), grad3(perm[bb], x - 1, y - 1, z))),
            lerp(v,
                lerp(u, grad3(perm[aa + 1], x, y, z - 1), grad3(perm[ba + 1], x - 1, y, z - 1)),
                lerp(u, grad3(perm[ab + 1], x, y - 1, z - 1), grad3(perm[bb + 1], x - 1, y - 1, z - 1))))
    }

    /** Fractal Brownian motion, normalised to roughly [-1, 1]. */
    fun fbm2(x: Double, y: Double, octaves: Int, lacunarity: Double = 2.0, gain: Double = 0.5): Double {
        var sum = 0.0; var amp = 1.0; var freq = 1.0; var norm = 0.0
        for (i in 0 until octaves) {
            sum += noise2(x * freq, y * freq) * amp
            norm += amp
            amp *= gain
            freq *= lacunarity
        }
        return sum / norm
    }
}
