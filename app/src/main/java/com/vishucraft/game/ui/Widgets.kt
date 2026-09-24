package com.vishucraft.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.vishucraft.game.engine.GameInput
import kotlin.math.hypot
import kotlin.math.min

fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.dpi(v: Float) = (v * resources.displayMetrics.density).toInt()

/** Blocky stone-style button used in menus. */
fun menuButton(ctx: Context, label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
    text = label
    setTextColor(Color.WHITE)
    textSize = 18f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setShadowLayer(0.01f, ctx.dp(2f), ctx.dp(2f), Color.rgb(40, 40, 40))
    val normal = GradientDrawable().apply {
        setColor(Color.rgb(112, 112, 112)); setStroke(ctx.dpi(3f), Color.rgb(30, 30, 30))
    }
    background = normal
    setPadding(ctx.dpi(24f), ctx.dpi(10f), ctx.dpi(24f), ctx.dpi(10f))
    isClickable = true
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> (v.background as GradientDrawable).setColor(Color.rgb(126, 136, 190))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> (v.background as GradientDrawable).setColor(Color.rgb(112, 112, 112))
        }
        false
    }
    setOnClickListener { onClick() }
}

/** Floating joystick: the base appears where the thumb lands. */
@SuppressLint("ViewConstructor")
class JoystickView(ctx: Context, private val input: GameInput) : View(ctx) {
    private val radius = ctx.dp(56f)
    private var cx = -1f; private var cy = -1f
    private var kx = 0f; private var ky = 0f
    private var pointer = -1
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 230, 230, 230) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> if (pointer == -1) {
                val i = e.actionIndex
                pointer = e.getPointerId(i)
                cx = e.getX(i); cy = e.getY(i); kx = cx; ky = cy
                update()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i >= 0) { kx = e.getX(i); ky = e.getY(i); update() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex) == pointer) {
                    pointer = -1
                    input.moveForward = 0f; input.moveStrafe = 0f
                    cx = -1f
                    invalidate()
                }
            }
        }
        return true
    }

    private fun update() {
        var dx = kx - cx; var dy = ky - cy
        val d = hypot(dx, dy)
        if (d > radius) { dx *= radius / d; dy *= radius / d; kx = cx + dx; ky = cy + dy }
        input.moveStrafe = dx / radius
        input.moveForward = -dy / radius
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bx = if (cx < 0) width / 2f else cx
        val by = if (cx < 0) height / 2f else cy
        canvas.drawCircle(bx, by, radius, basePaint)
        canvas.drawCircle(bx, by, radius, ringPaint)
        val kxx = if (cx < 0) bx else kx
        val kyy = if (cx < 0) by else ky
        canvas.drawCircle(kxx, kyy, radius * 0.42f, knobPaint)
    }
}

/** Square translucent touch button with a text glyph. Reports press / release. */
@SuppressLint("ViewConstructor")
class HudButton(ctx: Context, var label: String, private val onChange: (Boolean) -> Unit) : View(ctx) {
    var pressedState = false
        private set
    var toggled = false
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f); color = Color.argb(160, 255, 255, 255)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedState = true; onChange(true); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressedState = false; onChange(false); invalidate() }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(8f)
        rect.set(border.strokeWidth, border.strokeWidth, width - border.strokeWidth, height - border.strokeWidth)
        bg.color = when {
            pressedState -> Color.argb(170, 180, 180, 180)
            toggled -> Color.argb(150, 90, 140, 220)
            else -> Color.argb(90, 40, 40, 40)
        }
        canvas.drawRoundRect(rect, r, r, bg)
        canvas.drawRoundRect(rect, r, r, border)
        text.textSize = min(width, height) * (if (label.length > 2) 0.26f else 0.42f)
        val y = height / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, width / 2f, y, text)
    }

    private fun dp(v: Float) = context.dp(v)
}

/** The 9-slot hotbar. */
@SuppressLint("ViewConstructor")
class HotbarView(ctx: Context, private val slots: IntArray, private val onSelect: (Int) -> Unit) : View(ctx) {
    var selected = 0
        set(v) { field = v; invalidate() }
    private val frame = Paint().apply { color = Color.argb(150, 20, 20, 20) }
    private val slotBorder = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2f); color = Color.argb(200, 110, 110, 110) }
    private val selPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3f); color = Color.WHITE }
    private val iconPaint = Paint().apply { isFilterBitmap = false }
    private val dst = Rect()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val slot = (e.x / (width / 9f)).toInt().coerceIn(0, 8)
            selected = slot
            onSelect(slot)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width / 9f
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), frame)
        val pad = (w * 0.16f).toInt()
        for (i in 0 until 9) {
            val x0 = i * w
            canvas.drawRect(x0 + 1, 1f, x0 + w - 1, height - 1f, slotBorder)
            dst.set((x0 + pad).toInt(), pad, (x0 + w - pad).toInt(), height - pad)
            canvas.drawBitmap(BlockIcons.get(slots[i]), null, dst, iconPaint)
        }
        val x0 = selected * w
        canvas.drawRect(x0 + 1, 1f, x0 + w - 1, height - 1f, selPaint)
    }
}

/** Creative inventory: grid of every block. */
@SuppressLint("ViewConstructor")
class InventoryView(ctx: Context, private val onPick: (Int?) -> Unit) : View(ctx) {
    private val blocks = com.vishucraft.game.world.Blocks.placeable
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val panel = Paint().apply { color = Color.rgb(198, 198, 198) }
    private val panelBorder = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3f); color = Color.rgb(40, 40, 40) }
    private val cellPaint = Paint().apply { color = Color.rgb(139, 139, 139) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 60, 60); textSize = ctx.dp(16f); typeface = Typeface.DEFAULT_BOLD }
    private val iconPaint = Paint().apply { isFilterBitmap = false }
    private val cols = 11
    private val cell = ctx.dp(46f)
    private val panelRect = RectF()
    private val dst = Rect()

    private fun layoutPanel() {
        val rows = (blocks.size + cols - 1) / cols
        val w = cols * cell + dp(24f)
        val h = rows * cell + dp(56f)
        panelRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        layoutPanel()
        if (!panelRect.contains(e.x, e.y)) { onPick(null); return true }
        val gx = ((e.x - panelRect.left - dp(12f)) / cell).toInt()
        val gy = ((e.y - panelRect.top - dp(44f)) / cell).toInt()
        val i = gy * cols + gx
        if (gx in 0 until cols && gy >= 0 && i < blocks.size) onPick(blocks[i])
        return true
    }

    override fun onDraw(canvas: Canvas) {
        layoutPanel()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(panelRect, panel)
        canvas.drawRect(panelRect, panelBorder)
        canvas.drawText("Blocks — tap one to put it in the selected slot", panelRect.left + dp(12f), panelRect.top + dp(28f), title)
        val pad = (cell * 0.14f).toInt()
        for ((i, id) in blocks.withIndex()) {
            val x = panelRect.left + dp(12f) + (i % cols) * cell
            val y = panelRect.top + dp(44f) + (i / cols) * cell
            canvas.drawRect(x + 2, y + 2, x + cell - 2, y + cell - 2, cellPaint)
            dst.set((x + pad).toInt(), (y + pad).toInt(), (x + cell - pad).toInt(), (y + cell - pad).toInt())
            canvas.drawBitmap(BlockIcons.get(id), null, dst, iconPaint)
        }
    }

    private fun dp(v: Float) = context.dp(v)
}

/** Center crosshair. */
class CrosshairView(ctx: Context) : View(ctx) {
    private val paint = Paint().apply { color = Color.argb(220, 255, 255, 255); strokeWidth = ctx.dp(2f) }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val s = dp(10f)
        canvas.drawLine(cx - s, cy, cx + s, cy, paint)
        canvas.drawLine(cx, cy - s, cx, cy + s, paint)
    }
    private fun dp(v: Float) = context.dp(v)
}
