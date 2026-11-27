package com.vishucraft.game.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.vishucraft.game.render.DollRenderer
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.SkinEditorState
import com.vishucraft.game.world.SkinPaint

/** Opens the skin painter full screen. */
object SkinEditor {
    fun show(activity: Activity, look: IntArray, armor: Array<ItemStack?>?) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = SkinEditorView(activity, look, armor) { SkinPaint.save(); dialog.dismiss() }
        dialog.setContentView(view)
        dialog.setOnDismissListener { SkinPaint.save() }
        dialog.show()
        view.requestFocus()
    }
}

/**
 * The skin painter: pick a part of the body and one of its faces, then paint its 16 x 16 pixels by touch (drag to paint), or with
 * a remote: the arrow keys move, OK paints, Back switches to the buttons (arrows choose, OK presses) and back again.
 * What is painted shows at once on the turning 3D figure.
 */
class SkinEditorView(ctx: Context, private val look: IntArray, private val armor: Array<ItemStack?>?, private val onDone: () -> Unit) : View(ctx) {
    private val ed = SkinEditorState()
    private val paint = Paint().apply { isAntiAlias = false }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }

    private class Ctl(val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val swatch: Int, val selected: Boolean, val action: () -> Unit)
    private val ctls = ArrayList<Ctl>()
    /** -1: painting with the arrow keys; otherwise the button the arrow keys have chosen. */
    private var focus = -1
    private var cx = 8
    private var cy = 8
    private var k = 1f; private var ox = 0f; private var oy = 0f

    private val gx = 170f; private val gy = 40f; private val cell = 28f

    init { isFocusable = true; isFocusableInTouchMode = true }

    private fun layout() {
        ctls.clear()
        val P = SkinPaint
        var y = 40f
        for (i in 0 until P.PARTS) { val s = ed.part == i; ctls.add(Ctl(P.partNames[i], 10f, y, 140f, 32f, 0, s) { ed.part = i }); y += 36f }
        y += 22f
        for (i in 0 until P.FACES) { val s = ed.face == i; ctls.add(Ctl(P.faceNames[i], 10f, y, 140f, 32f, 0, s) { ed.face = i }); y += 36f }
        val px0 = 640f
        val sw = 26f
        for (i in 0 until 30) ctls.add(Ctl("", px0 + (i % 8) * (sw + 4), 60f + (i / 8) * (sw + 4), sw, sw, 1 + i, ed.color == i && ed.tool != 2) { ed.color = i; if (ed.tool == 2) ed.tool = 0 })
        val ty = 60f + 4 * (sw + 4) + 14f
        val tools = listOf("Brush", "Fill", "Eraser")
        for ((i, n) in tools.withIndex()) ctls.add(Ctl(n, px0 + i * 80f, ty, 76f, 32f, 0, ed.tool == i) { ed.tool = i })
        ctls.add(Ctl("Mirror: " + (if (ed.mirror) "on" else "off"), px0, ty + 40f, 156f, 32f, 0, ed.mirror) { ed.mirror = !ed.mirror })
        ctls.add(Ctl("Undo", px0 + 160f, ty + 40f, 76f, 32f, 0, false) { ed.undoLast() })
        ctls.add(Ctl("Fill face", px0, ty + 80f, 116f, 32f, 0, false) { ed.fillFace() })
        ctls.add(Ctl("Clear face", px0 + 120f, ty + 80f, 116f, 32f, 0, false) { ed.clearFace() })
        ctls.add(Ctl("Clear everything", px0, ty + 120f, 236f, 32f, 0, false) { ed.clearAll() })
        ctls.add(Ctl("Done", 390f, 508f, 160f, 40f, 0, false) { onDone() })
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(34, 36, 44))
        k = minOf(width / 1000f, height / 560f); ox = (width - 1000f * k) / 2f; oy = (height - 560f * k) / 2f
        canvas.save(); canvas.translate(ox, oy); canvas.scale(k, k)
        layout()
        val P = SkinPaint
        text.textSize = 22f; text.textAlign = Paint.Align.CENTER
        canvas.drawText("Paint your skin", 500f, 28f, text)
        text.textSize = 15f
        // The picture being painted: a chequer shows what is not painted yet.
        for (y in 0 until 16) for (x in 0 until 16) {
            paint.color = if ((x + y) % 2 == 0) Color.rgb(170, 170, 176) else Color.rgb(140, 140, 148)
            canvas.drawRect(gx + x * cell, gy + y * cell, gx + (x + 1) * cell, gy + (y + 1) * cell, paint)
            val c = P.get(ed.part, ed.face, x, y)
            if (c != 0) { paint.color = c; canvas.drawRect(gx + x * cell, gy + y * cell, gx + (x + 1) * cell, gy + (y + 1) * cell, paint) }
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.BLACK
        canvas.drawRect(gx, gy, gx + 16 * cell, gy + 16 * cell, paint)
        if (focus < 0) {
            paint.color = Color.rgb(255, 235, 90)
            canvas.drawRect(gx + cx * cell, gy + cy * cell, gx + (cx + 1) * cell, gy + (cy + 1) * cell, paint)
        }
        paint.style = Paint.Style.FILL
        // The turning figure.
        val gw = 56; val gh = 100
        val yaw = ((System.currentTimeMillis() / 40) % 628) / 100f
        val pix = DollRenderer.render(look, DollRenderer.armorColors(armor), false, yaw, gw, gh)
        val dc = 2.3f
        DollRenderer.runs(pix, gw, gh) { px, py, len, c ->
            paint.color = c
            canvas.drawRect(900f - gw * dc / 2f + px * dc, 60f + py * dc, 900f - gw * dc / 2f + (px + len) * dc + 0.5f, 60f + (py + 1) * dc + 0.5f, paint)
        }
        // Buttons and colours.
        for ((i, c) in ctls.withIndex()) {
            if (c.swatch > 0) {
                paint.color = if (c.selected) Color.WHITE else Color.rgb(20, 20, 24)
                canvas.drawRect(c.x - 2, c.y - 2, c.x + c.w + 2, c.y + c.h + 2, paint)
                paint.color = SkinPaint.color(c.swatch - 1)
                canvas.drawRect(c.x, c.y, c.x + c.w, c.y + c.h, paint)
            } else {
                paint.color = if (c.selected) Color.rgb(84, 112, 196) else Color.rgb(96, 98, 106)
                canvas.drawRect(c.x, c.y, c.x + c.w, c.y + c.h, paint)
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(c.label, c.x + c.w / 2f, c.y + c.h / 2f + 5f, text)
            }
            if (i == focus) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.rgb(255, 235, 90)
                canvas.drawRect(c.x - 3, c.y - 3, c.x + c.w + 3, c.y + c.h + 3, paint)
                paint.style = Paint.Style.FILL
            }
        }
        text.textAlign = Paint.Align.LEFT; text.textSize = 13f
        canvas.drawText(if (focus < 0) "Arrows move, OK paints, Back = buttons" else "Arrows choose, OK presses, Back = paint", 170f, 530f, text)
        canvas.restore()
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x - ox) / k; val y = (e.y - oy) / k
        val onGrid = x >= gx && x < gx + 16 * cell && y >= gy && y < gy + 16 * cell
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                focus = -1
                if (onGrid) { ed.begin(); cx = ((x - gx) / cell).toInt(); cy = ((y - gy) / cell).toInt(); ed.apply(cx, cy) }
                else for (c in ctls) if (x >= c.x && x < c.x + c.w && y >= c.y && y < c.y + c.h) { c.action(); break }
            }
            MotionEvent.ACTION_MOVE -> if (onGrid) { cx = ((x - gx) / cell).toInt(); cy = ((y - gy) / cell).toInt(); ed.apply(cx, cy) }
        }
        invalidate()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val center = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
        if (focus >= 0) {
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> focus = (focus - 1 + ctls.size) % ctls.size
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> focus = (focus + 1) % ctls.size
                center -> ctls.getOrNull(focus)?.action?.invoke()
                keyCode == KeyEvent.KEYCODE_BACK -> focus = -1
                else -> return super.onKeyDown(keyCode, event)
            }
        } else {
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> cx = (cx - 1).coerceAtLeast(0)
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> cx = (cx + 1).coerceAtMost(15)
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> cy = (cy - 1).coerceAtLeast(0)
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> cy = (cy + 1).coerceAtMost(15)
                center -> { ed.begin(); ed.apply(cx, cy) }
                keyCode == KeyEvent.KEYCODE_BACK -> focus = 0
                else -> return super.onKeyDown(keyCode, event)
            }
        }
        invalidate()
        return true
    }
}
