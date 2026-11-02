package com.vishucraft.game.engine

/** The password asked for before any world is opened (a simple lock, kept the same on phones, TVs, PCs and the web). */
object WorldLock {
    private const val PASSWORD = "dhruvil@123"
    /** The secret code that is needed to turn the password lock off (in Settings). */
    private const val SECRET = "dhu@123"
    fun ok(typed: String?) = typed?.trim() == PASSWORD
    fun secretOk(typed: String?) = typed?.trim() == SECRET
    const val SECRET_TITLE = "Secret code"
    const val SECRET_MESSAGE = "Type the secret code to turn the password off."
    const val SECRET_WRONG = "Wrong secret code"
    const val TITLE = "Password"
    const val MESSAGE = "Type the password to play."
    const val WRONG = "Wrong password"
}
