package com.vishucraft.game.engine

/** The password asked for before any world is opened (a simple lock, kept the same on phones, TVs, PCs and the web). */
object WorldLock {
    private const val PASSWORD = "dhruvil@123"
    fun ok(typed: String?) = typed?.trim() == PASSWORD
    const val TITLE = "Password"
    const val MESSAGE = "Type the password to play."
    const val WRONG = "Wrong password"
}
