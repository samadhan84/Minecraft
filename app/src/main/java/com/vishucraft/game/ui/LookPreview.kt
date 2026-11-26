package com.vishucraft.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.vishucraft.game.render.DollRenderer

/** The home-screen customizer's preview: your character in 3D, slowly turning. */
class LookPreview(ctx: Context, var look: IntArray) : View(ctx) {
    private val paint = Paint().apply { isAntiAlias = false }
    private val none = IntArray(4) { -1 }

    override fun onDraw(canvas: Canvas) {
        val gw = 56; val gh = 100
        val yaw = ((System.currentTimeMillis() / 40) % 628) / 100f
        val pix = DollRenderer.render(look, none, false, yaw, gw, gh)
        val cell = minOf(width / gw.toFloat(), height / gh.toFloat())
        val x0 = (width - gw * cell) / 2f; val y0 = (height - gh * cell) / 2f
        DollRenderer.runs(pix, gw, gh) { px, py, len, c ->
            paint.color = c
            canvas.drawRect(x0 + px * cell, y0 + py * cell, x0 + (px + len) * cell + 0.6f, y0 + (py + 1) * cell + 0.6f, paint)
        }
        postInvalidateOnAnimation()
    }
}
