package com.vishucraft.game.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.vishucraft.game.engine.Game
import com.vishucraft.game.world.ChestEntity
import com.vishucraft.game.world.FurnaceEntity
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Recipe
import com.vishucraft.game.world.Recipes
import kotlin.math.abs
import kotlin.math.hypot

/** Draws an item stack icon with its count and durability bar. */
object StackPainter {
    private val icon = Paint().apply { isFilterBitmap = false }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    private val barBg = Paint().apply { color = Color.BLACK }
    private val bar = Paint()
    private val dst = Rect()

    fun draw(c: Canvas, s: ItemStack?, l: Float, t: Float, size: Float, showCount: Boolean = true) {
        if (s == null) return
        val pad = size * 0.14f
        dst.set((l + pad).toInt(), (t + pad).toInt(), (l + size - pad).toInt(), (t + size - pad).toInt())
        c.drawBitmap(BlockIcons.get(s.id), null, dst, icon)
        val def = Items[s.id]
        if (def != null && def.durability > 0 && s.damage > 0) {
            val f = (1f - s.damage.toFloat() / def.durability).coerceIn(0f, 1f)
            val y = t + size - pad * 0.9f
            c.drawRect(l + pad, y, l + size - pad, y + size * 0.07f, barBg)
            bar.color = Color.HSVToColor(floatArrayOf(f * 120f, 1f, 1f))
            c.drawRect(l + pad, y, l + pad + (size - 2 * pad) * f, y + size * 0.05f, bar)
        }
        if (showCount && s.count > 1) {
            text.textSize = size * 0.32f; shadow.textSize = text.textSize
            c.drawText(s.count.toString(), l + size - pad * 0.4f + 2, t + size - pad * 0.4f + 2, shadow)
            c.drawText(s.count.toString(), l + size - pad * 0.4f, t + size - pad * 0.4f, text)
        }
    }
}

/**
 * Survival screens: player inventory (with 2x2 recipes), crafting table, chest and furnace.
 * Tap an item, then tap where it should go. Tap a recipe to craft it. Works with a D-pad too.
 * All changes are applied on the game thread through [act].
 */
@SuppressLint("ViewConstructor")
class ContainerScreen(
    ctx: Context,
    private val game: Game,
    private val act: ((Game) -> Unit) -> Unit,
    private val onClose: () -> Unit,
) : View(ctx) {
    enum class Mode { INVENTORY, CRAFTING, CHEST, FURNACE }

    private var mode = Mode.INVENTORY
    private var chest: ChestEntity? = null
    private var furnace: FurnaceEntity? = null

    /** An interactive cell: a slot in some container, or a recipe row. */
    private class Cell(val kind: Int, val index: Int, val rect: RectF)
    private val cells = ArrayList<Cell>()
    private var selected: Cell? = null
    private var cursor = -1
    private var recipeScroll = 0f
    private var recipes: List<Recipe> = emptyList()
    private val recipeArea = RectF()
    private val arrowRect = RectF()

    private fun gridSize() = if (mode == Mode.CRAFTING) 3 else 2

    /** Items in the grid, packed into a size x size array for recipe matching. */
    private fun gridArray(g: Game): Array<ItemStack?> {
        val n = gridSize()
        return Array(n * n) { g.craftGrid[it] }
    }

    private fun gridResult(g: Game): ItemStack? = Recipes.match(gridArray(g), gridSize())?.let { ItemStack(it.result, it.count) }

    private val dim = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val panel = Paint().apply { color = Color.rgb(198, 198, 198) }
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3f); color = Color.rgb(40, 40, 40) }
    private val slotPaint = Paint().apply { color = Color.rgb(139, 139, 139) }
    private val slotDark = Paint().apply { color = Color.rgb(110, 110, 110) }
    private val selPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3f); color = Color.rgb(255, 235, 90) }
    private val curPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3f); color = Color.WHITE }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(50, 50, 50); textSize = ctx.dp(15f); typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(50, 50, 50); textSize = ctx.dp(12f) }
    private val rowOk = Paint().apply { color = Color.rgb(170, 200, 160) }
    private val rowNo = Paint().apply { color = Color.rgb(170, 170, 170) }
    private val progress = Paint().apply { color = Color.rgb(250, 250, 250) }
    private val flame = Paint().apply { color = Color.rgb(255, 150, 40) }

    companion object {
        const val INV = 0; const val ARMOR = 1; const val CHEST_SLOT = 2; const val FURNACE_SLOT = 3; const val RECIPE = 4
        const val GRID = 5; const val OUTPUT = 6
    }

    fun open(m: Mode, chestEntity: ChestEntity? = null, furnaceEntity: FurnaceEntity? = null) {
        mode = m; chest = chestEntity; furnace = furnaceEntity
        selected = null; cursor = -1; recipeScroll = 0f
        visibility = VISIBLE
        invalidate()
    }

    private fun dp(v: Float) = context.dp(v)

    // ---------------------------------------------------------------- layout

    private fun layoutCells(): RectF {
        cells.clear()
        val showRecipes = mode == Mode.INVENTORY || mode == Mode.CRAFTING
        val cell = minOf(dp(40f), (width - dp(48f)) / (if (showRecipes || mode == Mode.FURNACE) 17f else 11f), (height - dp(90f)) / 8f)
        val leftW = cell * 10.2f
        val rightW = if (showRecipes) cell * 7.5f else if (mode == Mode.FURNACE) cell * 5f else 0f
        val w = leftW + rightW + dp(24f)
        val rows = if (mode == Mode.CHEST) 7.6f else 4.6f + if (mode == Mode.INVENTORY) 0f else 0f
        val h = dp(40f) + cell * maxOf(rows, if (showRecipes) 7.2f else 4.6f) + dp(26f)
        val p = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        var y = p.top + dp(34f)
        val x0 = p.left + dp(12f) + cell * 1.2f
        if (mode == Mode.CHEST) {
            val n = chest?.slots?.size ?: 27
            for (i in 0 until n) cells.add(Cell(CHEST_SLOT, i, RectF(x0 + (i % 9) * cell, y + (i / 9) * cell, x0 + (i % 9 + 1) * cell, y + (i / 9 + 1) * cell)))
            y += cell * 3.3f
        }
        // Armor column on the left.
        for (i in 0 until 4) {
            val ax = p.left + dp(10f)
            cells.add(Cell(ARMOR, i, RectF(ax, y + i * cell, ax + cell, y + (i + 1) * cell)))
        }
        // Main inventory rows (9..35) then the hotbar (0..8) slightly separated.
        for (i in 9 until 36) {
            val r = (i - 9) / 9; val c = (i - 9) % 9
            cells.add(Cell(INV, i, RectF(x0 + c * cell, y + r * cell, x0 + (c + 1) * cell, y + (r + 1) * cell)))
        }
        val hy = y + cell * 3.2f
        for (i in 0 until 9) cells.add(Cell(INV, i, RectF(x0 + i * cell, hy, x0 + (i + 1) * cell, hy + cell)))

        val rx = p.left + dp(12f) + leftW + dp(6f)
        if (showRecipes) {
            recipes = Recipes.available(game.inventory, mode == Mode.CRAFTING).sortedByDescending { it.canCraft(game.inventory) }
            // Crafting grid (2x2 in the inventory, 3x3 at a crafting table) with its output slot.
            val gs = gridSize()
            val gy = p.top + dp(34f)
            for (i in 0 until gs * gs) {
                val gx = rx + (i % gs) * cell; val gyy = gy + (i / gs) * cell
                cells.add(Cell(GRID, i, RectF(gx, gyy, gx + cell, gyy + cell)))
            }
            val ox = rx + (gs + 1.2f) * cell; val oy = gy + (gs - 1) * cell / 2
            cells.add(Cell(OUTPUT, 0, RectF(ox, oy, ox + cell, oy + cell)))
            arrowRect.set(rx + gs * cell + dp(4f), oy + cell * 0.4f, ox - dp(4f), oy + cell * 0.6f)
            recipeArea.set(rx, gy + gs * cell + dp(22f), p.right - dp(10f), p.bottom - dp(24f))
            val rh = cell * 0.95f
            for ((i, _) in recipes.withIndex()) {
                val top = recipeArea.top + i * rh - recipeScroll
                cells.add(Cell(RECIPE, i, RectF(rx, top, recipeArea.right, top + rh - dp(3f))))
            }
        } else if (mode == Mode.FURNACE) {
            val fy = p.top + dp(40f)
            cells.add(Cell(FURNACE_SLOT, 0, RectF(rx + cell * 0.5f, fy, rx + cell * 1.5f, fy + cell)))
            cells.add(Cell(FURNACE_SLOT, 1, RectF(rx + cell * 0.5f, fy + cell * 2f, rx + cell * 1.5f, fy + cell * 3f)))
            cells.add(Cell(FURNACE_SLOT, 2, RectF(rx + cell * 3f, fy + cell, rx + cell * 4.2f, fy + cell * 2.2f)))
        }
        return p
    }

    // ---------------------------------------------------------------- slot access (game thread)

    private fun get(g: Game, kind: Int, i: Int): ItemStack? = when (kind) {
        GRID -> g.craftGrid[i]
        OUTPUT -> gridResult(g)
        INV -> g.inventory.slots[i]
        ARMOR -> g.inventory.armor[i]
        CHEST_SLOT -> chest?.slots?.get(i)
        FURNACE_SLOT -> furnace?.let { f -> when (i) { 0 -> f.input; 1 -> f.fuel; else -> f.output } }
        else -> null
    }

    private fun set(g: Game, kind: Int, i: Int, s: ItemStack?) {
        val v = if (s != null && s.count <= 0) null else s
        when (kind) {
            GRID -> g.craftGrid[i] = v
            INV -> g.inventory.slots[i] = v
            ARMOR -> g.inventory.armor[i] = v
            CHEST_SLOT -> chest?.slots?.set(i, v)
            FURNACE_SLOT -> furnace?.let { f -> when (i) { 0 -> f.input = v; 1 -> f.fuel = v; else -> f.output = v } }
        }
    }

    private fun accepts(kind: Int, i: Int, s: ItemStack?): Boolean {
        if (s == null) return true
        return when (kind) {
            OUTPUT -> false
            ARMOR -> Items[s.id]?.armorSlot == i
            FURNACE_SLOT -> when (i) { 0 -> Recipes.smelting.containsKey(s.id); 1 -> Items.fuel(s.id) > 0f; else -> false }
            else -> true
        }
    }

    /** Moves the stack in [a] onto [b]: merge, or swap if both sides accept. */
    private fun move(a: Cell, b: Cell) = act { g ->
        val sa = get(g, a.kind, a.index) ?: return@act
        val sb = get(g, b.kind, b.index)
        if (sb != null && sb.id == sa.id && sb.damage == sa.damage && sb.count < sb.maxStack) {
            val n = minOf(sa.count, sb.maxStack - sb.count)
            sb.count += n; sa.count -= n
            if (sa.count <= 0) set(g, a.kind, a.index, null)
            return@act
        }
        if (!accepts(b.kind, b.index, sa) || !accepts(a.kind, a.index, sb)) return@act
        set(g, a.kind, a.index, sb); set(g, b.kind, b.index, sa)
    }

    /** One tap in chest / furnace screens moves a stack straight across. */
    private fun quickMove(c: Cell) = act { g ->
        val s = get(g, c.kind, c.index) ?: return@act
        when {
            c.kind == INV && mode == Mode.CHEST -> {
                val slots = chest?.slots ?: return@act
                for (i in slots.indices) {
                    val t = slots[i]
                    if (t != null && t.id == s.id && t.count < t.maxStack) { val n = minOf(s.count, t.maxStack - t.count); t.count += n; s.count -= n }
                    if (s.count == 0) break
                }
                for (i in slots.indices) if (s.count > 0 && slots[i] == null) { slots[i] = s.copy(); s.count = 0 }
                if (s.count <= 0) set(g, INV, c.index, null)
            }
            c.kind == INV && mode == Mode.FURNACE -> {
                val target = if (Recipes.smelting.containsKey(s.id)) 0 else if (Items.fuel(s.id) > 0f) 1 else return@act
                val t = get(g, FURNACE_SLOT, target)
                if (t == null) { set(g, FURNACE_SLOT, target, s.copy()); set(g, INV, c.index, null) }
                else if (t.id == s.id) { val n = minOf(s.count, t.maxStack - t.count); t.count += n; s.count -= n; if (s.count <= 0) set(g, INV, c.index, null) }
            }
            c.kind == CHEST_SLOT || c.kind == FURNACE_SLOT -> {
                val left = g.inventory.add(s.id, s.count, s.damage)
                s.count = left
                if (left <= 0) set(g, c.kind, c.index, null)
            }
            c.kind == INV && Items[s.id]?.armorSlot?.let { it >= 0 } == true -> {
                val slot = Items[s.id]!!.armorSlot
                val old = g.inventory.armor[slot]
                g.inventory.armor[slot] = s; g.inventory.slots[c.index] = old
            }
            c.kind == ARMOR -> if (g.inventory.add(s.id, 1, s.damage) == 0) g.inventory.armor[c.index] = null
            c.kind == GRID -> { s.count = g.inventory.add(s.id, s.count, s.damage); if (s.count <= 0) g.craftGrid[c.index] = null }
        }
    }

    private fun craft(i: Int) = act { g ->
        val r = recipes.getOrNull(i) ?: return@act
        if (!r.consume(g.inventory)) return@act
        val left = g.inventory.add(r.result, r.count)
        if (left > 0) g.drops.spawn(ItemStack(r.result, left), g.player.x, g.player.y + 1f, g.player.z)
        g.uiEvents.add("craft")
    }

    /** Takes the grid's result: one of each ingredient is used up. */
    private fun craftFromGrid() = act { g ->
        val size = gridSize()
        val grid = gridArray(g)
        val r = Recipes.match(grid, size) ?: return@act
        Recipes.consumeGrid(grid)
        for (i in grid.indices) g.craftGrid[i] = grid[i]
        val left = g.inventory.add(r.result, r.count)
        if (left > 0) g.drops.spawn(ItemStack(r.result, left), g.player.x, g.player.y + 1f, g.player.z)
        g.uiEvents.add("craft")
    }

    /**
     * Moves a stack into a grid cell. Tapping a grid cell with a selected stack places ONE item,
     * so a whole stack can be spread over the grid by tapping cell after cell.
     */
    private fun placeOne(from: Cell, to: Cell) = act { g ->
        val src = get(g, from.kind, from.index) ?: return@act
        val dst = g.craftGrid[to.index]
        if (dst == null) g.craftGrid[to.index] = ItemStack(src.id, 1, src.damage)
        else if (dst.id == src.id && dst.count < dst.maxStack) dst.count++
        else return@act
        src.count--
        if (src.count <= 0) set(g, from.kind, from.index, null)
    }

    private fun tap(c: Cell) {
        if (c.kind == OUTPUT) { craftFromGrid(); refresh(); return }
        val sel0 = selected
        if (c.kind == GRID && sel0 != null && sel0.kind != GRID) {
            // Keep the selection so the player can keep dropping items into the grid.
            placeOne(sel0, c); refresh(); return
        }
        if (c.kind == RECIPE) { craft(c.index); selected = null; refresh(); return }
        val sel = selected
        val quick = mode == Mode.CHEST || mode == Mode.FURNACE || c.kind == ARMOR
        when {
            sel == null && quick -> quickMove(c)
            sel == null -> if (get(game, c.kind, c.index) != null) selected = c
            sel.kind == c.kind && sel.index == c.index -> {
                // Tapping the same slot again quick-equips armor.
                quickMove(c); selected = null
            }
            else -> { move(sel, c); selected = null }
        }
        refresh()
    }

    private fun refresh() { postDelayed({ invalidate() }, 40); invalidate() }

    // ---------------------------------------------------------------- input

    private var downY = 0f; private var lastY = 0f; private var dragged = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = layoutCells()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; lastY = e.y; dragged = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.y - downY) > dp(8f)) dragged = true
                if (dragged && recipeArea.contains(e.x, downY)) {
                    recipeScroll = (recipeScroll - (e.y - lastY)).coerceIn(0f, maxRecipeScroll()); invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> if (!dragged) {
                if (!p.contains(e.x, e.y)) { close(); return true }
                val c = cells.firstOrNull { it.rect.contains(e.x, e.y) && (it.kind != RECIPE || recipeArea.contains(e.x, e.y)) }
                if (c != null) { cursor = -1; tap(c) }
            }
        }
        return true
    }

    private fun maxRecipeScroll(): Float {
        val rh = cells.firstOrNull { it.kind == RECIPE }?.rect?.let { it.height() + dp(3f) } ?: return 0f
        return (recipes.size * rh - recipeArea.height()).coerceAtLeast(0f)
    }

    fun close() {
        visibility = GONE; selected = null
        act { g -> g.returnCraftGrid() }
        onClose()
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode != KeyEvent.KEYCODE_BACK
        layoutCells()
        if (cells.isEmpty()) return false
        if (cursor !in cells.indices) cursor = cells.indexOfFirst { it.kind == INV && it.index == 0 }.coerceAtLeast(0)
        val cur = cells[cursor]
        val (dx, dy) = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                tap(cur); return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_E -> {
                if (selected != null) { selected = null; invalidate() } else close()
                return true
            }
            else -> return false
        }
        // Spatial navigation: nearest cell in the pressed direction.
        val cx = cur.rect.centerX(); val cy = cur.rect.centerY()
        var best = -1; var bestScore = Float.MAX_VALUE
        for ((i, c) in cells.withIndex()) {
            if (i == cursor) continue
            val ox = c.rect.centerX() - cx; val oy = c.rect.centerY() - cy
            val along = ox * dx + oy * dy
            if (along <= dp(2f)) continue
            val across = abs(ox * dy) + abs(oy * dx)
            val score = along + across * 2.5f
            if (score < bestScore) { bestScore = score; best = i }
        }
        if (best >= 0) {
            cursor = best
            val c = cells[best]
            if (c.kind == RECIPE) {
                if (c.rect.top < recipeArea.top) recipeScroll -= recipeArea.top - c.rect.top
                if (c.rect.bottom > recipeArea.bottom) recipeScroll += c.rect.bottom - recipeArea.bottom
                recipeScroll = recipeScroll.coerceIn(0f, maxRecipeScroll())
            }
        }
        invalidate()
        return true
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val p = layoutCells()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(p, panel); canvas.drawRect(p, border)
        val name = when (mode) {
            Mode.INVENTORY -> "Inventory"; Mode.CRAFTING -> "Crafting Table"; Mode.FURNACE -> "Furnace"
            Mode.CHEST -> if (chest is com.vishucraft.game.world.HopperEntity) "Hopper" else "Chest"
        }
        canvas.drawText(name, p.left + dp(12f), p.top + dp(24f), title)
        val hint = when (mode) {
            Mode.CHEST, Mode.FURNACE -> "Tap an item to move it across"
            else -> "Pick an item, tap grid squares to lay it out, then tap the result · or tap a recipe"
        }
        canvas.drawText(hint, p.left + dp(12f), p.bottom - dp(8f), small)

        for ((i, c) in cells.withIndex()) {
            if (c.kind == RECIPE) continue
            val r = c.rect
            canvas.drawRect(r.left + 2, r.top + 2, r.right - 2, r.bottom - 2, if (c.kind == ARMOR) slotDark else slotPaint)
            StackPainter.draw(canvas, get(game, c.kind, c.index), r.left, r.top, r.width())
            if (selected?.let { it.kind == c.kind && it.index == c.index } == true) canvas.drawRect(r, selPaint)
            if (i == cursor) canvas.drawRect(r, curPaint)
        }

        if (mode == Mode.INVENTORY || mode == Mode.CRAFTING) {
            canvas.drawRect(arrowRect, slotDark)
            canvas.drawText("Recipes (tap to craft):", recipeArea.left, recipeArea.top - dp(6f), small)
            drawRecipes(canvas)
        }
        if (mode == Mode.FURNACE) furnace?.let { f ->
            val out = cells.first { it.kind == FURNACE_SLOT && it.index == 2 }.rect
            val inp = cells.first { it.kind == FURNACE_SLOT && it.index == 0 }.rect
            val arrowL = inp.right + dp(6f); val arrowR = out.left - dp(6f)
            val ay = out.centerY()
            canvas.drawRect(arrowL, ay - dp(3f), arrowR, ay + dp(3f), slotDark)
            canvas.drawRect(arrowL, ay - dp(3f), arrowL + (arrowR - arrowL) * (f.progress / FurnaceEntity.SMELT_SECONDS), ay + dp(3f), progress)
            if (f.burnLeft > 0f && f.burnTotal > 0f) {
                val h = inp.height() * (f.burnLeft / f.burnTotal).coerceIn(0f, 1f)
                canvas.drawRect(inp.left + inp.width() * 0.3f, inp.bottom + inp.height() - h, inp.right - inp.width() * 0.3f, inp.bottom + inp.height(), flame)
            }
            postInvalidateDelayed(200)
        }
    }

    private fun drawRecipes(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(recipeArea)
        val inv = game.inventory
        for ((i, c) in cells.withIndex()) {
            if (c.kind != RECIPE) continue
            val r = c.rect
            if (r.bottom < recipeArea.top || r.top > recipeArea.bottom) continue
            val rec = recipes[c.index]
            val ok = rec.canCraft(inv)
            canvas.drawRect(r, if (ok) rowOk else rowNo)
            val s = r.height()
            StackPainter.draw(canvas, ItemStack(rec.result, rec.count), r.left, r.top, s)
            var x = r.left + s * 1.1f
            small.color = if (ok) Color.rgb(30, 60, 30) else Color.rgb(90, 90, 90)
            canvas.drawText("=", x, r.centerY() + dp(4f), small)
            x += dp(10f)
            for (ing in rec.ingredients) {
                val have = ing.available(inv)
                StackPainter.draw(canvas, ItemStack(ing.ids.firstOrNull { inv.count(it) > 0 } ?: ing.ids[0], ing.count), x, r.top + s * 0.1f, s * 0.8f)
                if (have < ing.count) canvas.drawLine(x + s * 0.1f, r.top + s * 0.2f, x + s * 0.7f, r.bottom - s * 0.2f, selPaint)
                x += s * 0.8f
            }
            if (i == cursor) canvas.drawRect(r, curPaint)
        }
        canvas.restore()
        small.color = Color.rgb(50, 50, 50)
        if (recipes.isEmpty()) canvas.drawText("No recipes", recipeArea.left, recipeArea.top + dp(20f), small)
    }
}
