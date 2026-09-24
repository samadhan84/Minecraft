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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.vishucraft.game.engine.GameInput
import kotlin.math.hypot
import kotlin.math.min

fun Context.dp(v: Float) = v * resources.displayMetrics.density
fun Context.dpi(v: Float) = (v * resources.displayMetrics.density).toInt()

/**
 * Blocky stone-style button used in menus. It is focusable, so TV remotes and gamepads can move between
 * buttons: the focused button turns blue with a yellow border, and a pressed one gets lighter.
 */
fun menuButton(ctx: Context, label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
    text = label
    setTextColor(Color.WHITE)
    textSize = 18f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setShadowLayer(0.01f, ctx.dp(2f), ctx.dp(2f), Color.rgb(40, 40, 40))
    fun state(fill: Int, border: Int, width: Float) = GradientDrawable().apply {
        setColor(fill); setStroke(ctx.dpi(width), border)
    }
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), state(Color.rgb(150, 170, 230), Color.rgb(255, 235, 90), 4f))
        addState(intArrayOf(android.R.attr.state_focused), state(Color.rgb(84, 112, 196), Color.rgb(255, 235, 90), 4f))
        addState(intArrayOf(), state(Color.rgb(112, 112, 112), Color.rgb(30, 30, 30), 3f))
    }
    setPadding(ctx.dpi(24f), ctx.dpi(10f), ctx.dpi(24f), ctx.dpi(10f))
    isClickable = true
    isFocusable = true
    setOnFocusChangeListener { v, has ->
        v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
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

/** Creative inventory: tabs of blocks plus a tools tab, scrollable. */
@SuppressLint("ViewConstructor")
class InventoryView(ctx: Context, private val onPick: (Int?) -> Unit) : View(ctx) {
    private val tabs: List<Pair<String, List<Int>>> =
        com.vishucraft.game.world.Category.values().map { it.title to com.vishucraft.game.world.Blocks.inCategory(it) } +
            ("Tools & items" to com.vishucraft.game.world.Items.all.map { it.id })
    private var tab = 0
    private var scroll = 0f
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val panel = Paint().apply { color = Color.rgb(198, 198, 198) }
    private val panelBorder = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3f); color = Color.rgb(40, 40, 40) }
    private val cellPaint = Paint().apply { color = Color.rgb(139, 139, 139) }
    private val tabPaint = Paint().apply { color = Color.rgb(160, 160, 160) }
    private val tabSel = Paint().apply { color = Color.rgb(230, 230, 230) }
    private val cursorPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3f); color = Color.rgb(255, 235, 90) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(50, 50, 50); textSize = ctx.dp(13f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint().apply { isFilterBitmap = false }
    private val cols = 11
    private val cell = ctx.dp(44f)
    private val tabH = ctx.dp(34f)
    private val panelRect = RectF()
    private val gridRect = RectF()
    private val dst = Rect()
    private var downX = 0f; private var downY = 0f; private var lastY = 0f; private var dragged = false

    /** Keyboard / remote cursor; -1 until a key is used. */
    private var cursor = -1

    private fun items() = tabs[tab].second

    /** D-pad navigation: arrows move, OK picks, channel / shoulder buttons switch tabs, Back closes. */
    fun handleKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) {
            return e.keyCode != KeyEvent.KEYCODE_BACK
        }
        layoutPanel()
        val n = items().size
        if (cursor < 0) cursor = 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cursor % cols > 0) cursor--
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cursor % cols < cols - 1 && cursor + 1 < n) cursor++
            KeyEvent.KEYCODE_DPAD_UP -> if (cursor >= cols) cursor -= cols
            KeyEvent.KEYCODE_DPAD_DOWN -> cursor = minOf(cursor + cols, n - 1)
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { tab = (tab + 1) % tabs.size; cursor = 0; scroll = 0f }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_REWIND -> { tab = (tab + tabs.size - 1) % tabs.size; cursor = 0; scroll = 0f }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (cursor in 0 until n) onPick(items()[cursor]); return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_ESCAPE -> { onPick(null); return true }
            else -> return false
        }
        // Keep the cursor row on screen.
        val rowTop = (cursor / cols) * cell
        if (rowTop < scroll) scroll = rowTop
        if (rowTop + cell > scroll + gridRect.height()) scroll = rowTop + cell - gridRect.height()
        scroll = scroll.coerceIn(0f, maxScroll())
        invalidate()
        return true
    }
    private fun rows() = (items().size + cols - 1) / cols

    private fun layoutPanel() {
        val w = cols * cell + dp(24f)
        val h = (height - dp(24f)).coerceAtMost(tabH + dp(20f) + 6 * cell)
        panelRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        gridRect.set(panelRect.left + dp(12f), panelRect.top + tabH + dp(8f), panelRect.right - dp(12f), panelRect.bottom - dp(8f))
    }

    private fun maxScroll() = (rows() * cell - gridRect.height()).coerceAtLeast(0f)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        layoutPanel()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastY = e.y; dragged = false }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(e.y - downY) > dp(8f)) dragged = true
                if (dragged) { scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll()); invalidate() }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> if (!dragged) tap(e.x, e.y)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (!panelRect.contains(x, y)) { onPick(null); return }
        if (y < panelRect.top + tabH) {
            val w = panelRect.width() / tabs.size
            tab = ((x - panelRect.left) / w).toInt().coerceIn(0, tabs.size - 1)
            scroll = 0f
            cursor = -1
            invalidate()
            return
        }
        if (!gridRect.contains(x, y)) return
        val gx = ((x - gridRect.left) / cell).toInt()
        val gy = ((y - gridRect.top + scroll) / cell).toInt()
        val i = gy * cols + gx
        if (gx in 0 until cols && gy >= 0 && i < items().size) onPick(items()[i])
    }

    override fun onDraw(canvas: Canvas) {
        layoutPanel()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(panelRect, panel)
        val tw = panelRect.width() / tabs.size
        for ((i, t) in tabs.withIndex()) {
            val l = panelRect.left + i * tw
            canvas.drawRect(l + 2, panelRect.top + 2, l + tw - 2, panelRect.top + tabH, if (i == tab) tabSel else tabPaint)
            canvas.drawText(t.first, l + tw / 2, panelRect.top + tabH / 2 + dp(5f), title)
        }
        canvas.drawRect(panelRect, panelBorder)
        canvas.save()
        canvas.clipRect(gridRect)
        val pad = (cell * 0.14f).toInt()
        for ((i, id) in items().withIndex()) {
            val x = gridRect.left + (i % cols) * cell
            val y = gridRect.top + (i / cols) * cell - scroll
            if (y + cell < gridRect.top || y > gridRect.bottom) continue
            canvas.drawRect(x + 2, y + 2, x + cell - 2, y + cell - 2, cellPaint)
            dst.set((x + pad).toInt(), (y + pad).toInt(), (x + cell - pad).toInt(), (y + cell - pad).toInt())
            canvas.drawBitmap(BlockIcons.get(id), null, dst, iconPaint)
            if (i == cursor) canvas.drawRect(x + 2, y + 2, x + cell - 2, y + cell - 2, cursorPaint)
        }
        canvas.restore()
    }

    private fun dp(v: Float) = context.dp(v)
}

/** Ten hearts; each heart is two health points. */
class HeartsView(ctx: Context) : View(ctx) {
    var health = 20f
        set(v) { if (field != v) { field = v; invalidate() } }
    private val full = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 40) }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 40, 20, 20) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(1.5f); color = Color.rgb(30, 10, 10) }
    private val path = android.graphics.Path()

    private fun heart(cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy + s * 0.45f)
        path.cubicTo(cx - s * 0.9f, cy - s * 0.1f, cx - s * 0.45f, cy - s * 0.75f, cx, cy - s * 0.3f)
        path.cubicTo(cx + s * 0.45f, cy - s * 0.75f, cx + s * 0.9f, cy - s * 0.1f, cx, cy + s * 0.45f)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        val s = height * 0.9f
        val step = width / 10f
        for (i in 0 until 10) {
            val cx = step * i + step / 2; val cy = height / 2f
            heart(cx, cy, s)
            canvas.drawPath(path, empty)
            val hp = health - i * 2
            if (hp >= 2f) canvas.drawPath(path, full)
            else if (hp >= 1f) {
                canvas.save(); canvas.clipRect(cx - s, 0f, cx, height.toFloat()); canvas.drawPath(path, full); canvas.restore()
            }
            canvas.drawPath(path, outline)
        }
    }
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
