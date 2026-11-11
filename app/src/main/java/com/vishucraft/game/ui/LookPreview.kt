package com.vishucraft.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.vishucraft.game.world.Appearance

/** A flat picture of the character from the front (the home-screen customizer's preview). */
class LookPreview(ctx: Context, var look: IntArray) : View(ctx) {
    private val paint = Paint().apply { isAntiAlias = false }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width / 20f, height / 38f)
        val x0 = (width - 16 * scale) / 2f; val y0 = (height - 36 * scale) / 2f
        for (r in Appearance.flat(look)) {
            val c = Appearance.palette[r[4].coerceIn(0, Appearance.palette.size - 1)]
            paint.color = android.graphics.Color.rgb(c[0], c[1], c[2])
            canvas.drawRect(x0 + r[0] * scale, y0 + r[1] * scale, x0 + (r[0] + r[2]) * scale, y0 + (r[1] + r[3]) * scale, paint)
        }
    }
}
