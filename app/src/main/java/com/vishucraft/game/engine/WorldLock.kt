package com.vishucraft.game.engine

/*
 * World passwords. A world has no password until its player chooses one (when leaving the world, or from the game
 * menu). Whatever the player types becomes that world's password; it is kept in the world's file as a salted hash
 * (never as text). Opening a world that has a password asks for it. Taking a password away needs the secret code.
 */
object WorldLock {
    /** The secret code that is needed to take a world's password away. */
    private const val SECRET = "dhu@123"
    const val MIN_LENGTH = 3

    fun secretOk(typed: String?) = typed?.trim() == SECRET

    /** What to store for [password]: a random salt, then the hash of salt + password. */
    fun make(password: String, salt: String = randomSalt()): String = salt + "$" + sha256(salt + password.trim())

    /** Whether [typed] is the password kept as [stored] (a world without a password has "" and always opens). */
    fun check(stored: String, typed: String?): Boolean {
        if (stored.isEmpty()) return true
        val at = stored.indexOf('$')
        if (at <= 0 || typed == null) return false
        return make(typed, stored.substring(0, at)) == stored
    }

    /** Why [password] cannot be used, or null when it can. */
    fun problem(password: String, again: String): String? = when {
        password.trim().length < MIN_LENGTH -> "Use at least $MIN_LENGTH characters"
        password.trim() != again.trim() -> "The two passwords are not the same"
        else -> null
    }

    private fun randomSalt(): String {
        val r = java.util.Random()
        return buildString { repeat(8) { append("0123456789abcdef"[r.nextInt(16)]) } }
    }

    // ---- SHA-256, written out so it works the same on phones, PCs and in the browser.
    private val K = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
    ).map { it.toInt() }.toIntArray()

    internal fun sha256(text: String): String {
        val bytes = text.encodeToByteArray()
        val bitLen = bytes.size.toLong() * 8
        val padded = ByteArray(((bytes.size + 8) / 64 + 1) * 64)
        bytes.copyInto(padded)
        padded[bytes.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        var h0 = 0x6a09e667; var h1 = 0xbb67ae85L.toInt(); var h2 = 0x3c6ef372; var h3 = 0xa54ff53aL.toInt()
        var h4 = 0x510e527f; var h5 = 0x9b05688cL.toInt(); var h6 = 0x1f83d9ab; var h7 = 0x5be0cd19
        val w = IntArray(64)
        for (chunk in 0 until padded.size / 64) {
            for (i in 0 until 16) {
                val o = chunk * 64 + i * 4
                w[i] = ((padded[o].toInt() and 255) shl 24) or ((padded[o + 1].toInt() and 255) shl 16) or ((padded[o + 2].toInt() and 255) shl 8) or (padded[o + 3].toInt() and 255)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4; var f = h5; var g = h6; var h = h7
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = h + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
        }
        return intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).joinToString("") { v -> (0 until 8).joinToString("") { "0123456789abcdef"[(v ushr (28 - it * 4)) and 15].toString() } }
    }
}
