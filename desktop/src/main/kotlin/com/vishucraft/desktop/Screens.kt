package com.vishucraft.desktop

import com.vishucraft.desktop.Ui.Companion.rgba
import com.vishucraft.game.engine.Game
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Category
import com.vishucraft.game.world.ChestEntity
import com.vishucraft.game.world.FurnaceEntity
import com.vishucraft.game.world.HopperEntity
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Recipe
import com.vishucraft.game.world.Recipes

private val PANEL = rgba(198, 198, 198)
private val DARK = rgba(40, 40, 40)
private val SLOT = rgba(139, 139, 139)
private val SLOT_DARK = rgba(110, 110, 110)
private val YELLOW = rgba(255, 235, 90)
private val INK = rgba(50, 50, 50)

/** Item icon with its stack count and durability bar. */
fun Ui.stack(s: ItemStack?, x: Float, y: Float, size: Float, showCount: Boolean = true) {
    if (s == null) return
    val pad = size * 0.12f
    icon(s.id, x + pad, y + pad, size - 2 * pad)
    val def = Items[s.id]
    if (def != null && def.durability > 0 && s.damage > 0) {
        val f = (1f - s.damage.toFloat() / def.durability).coerceIn(0f, 1f)
        val by = y + size - pad
        rect(x + pad, by, size - 2 * pad, size * 0.07f, rgba(0, 0, 0))
        val c = java.awt.Color.HSBtoRGB(f / 3f, 1f, 1f)
        rect(x + pad, by, (size - 2 * pad) * f, size * 0.05f, c or 0xFF000000.toInt())
    }
    if (showCount && s.count > 1) text(s.count.toString(), x + size - 2, y + size - size * 0.42f, size * 0.38f, align = 2)
}

/** A name label that follows the mouse. */
fun Ui.tooltip(label: String) {
    val w = textWidth(label, 16f) + 14
    val x = (mouseX + 14).coerceAtMost(width - w - 4); val y = (mouseY - 30).coerceAtLeast(4f)
    rect(x, y, w, 26f, rgba(20, 8, 30, 235))
    frame(x, y, w, 26f, rgba(90, 40, 160), 2f)
    text(label, x + 7, y + 4, 16f)
}

private fun stackName(s: ItemStack): String {
    val ench = Items[s.id]?.enchantments
    return Items.displayName(s.id) + if (ench != null) " ($ench)" else ""
}

// ==================================================================== HUD

class Hud(private val game: Game) {
    private var toast = ""
    private var toastTime = 0f
    var hurt = 0f
    var sleep = 0f
    var swing = 0f

    fun toast(s: String, seconds: Float = 2f) { toast = s; toastTime = seconds }

    fun draw(ui: Ui, dt: Float, debug: String?, showHotbar: Boolean) {
        val w = ui.width; val h = ui.height
        if (hurt > 0f) { ui.rect(0f, 0f, w, h, rgba(220, 0, 0, (110 * hurt).toInt())); hurt = maxOf(0f, hurt - dt * 3f) }
        if (sleep > 0f) {
            // Fade to black and back while the night passes.
            val a = if (sleep > 1.5f) (3f - sleep) / 1.5f else sleep / 1.5f
            ui.rect(0f, 0f, w, h, rgba(0, 0, 0, (255 * a.coerceIn(0f, 1f)).toInt()))
            sleep = maxOf(0f, sleep - dt)
        }
        // Crosshair
        ui.rect(w / 2 - 10, h / 2 - 1.5f, 20f, 3f, rgba(255, 255, 255, 200))
        ui.rect(w / 2 - 1.5f, h / 2 - 10, 3f, 20f, rgba(255, 255, 255, 200))

        if (showHotbar) {
            val cell = 44f
            val x0 = w / 2 - cell * 4.5f; val y0 = h - cell - 8
            ui.rect(x0 - 3, y0 - 3, cell * 9 + 6, cell + 6, rgba(20, 20, 20, 150))
            for (i in 0 until 9) {
                ui.frame(x0 + i * cell + 1, y0 + 1, cell - 2, cell - 2, rgba(110, 110, 110, 200), 2f)
                ui.stack(game.inventory.slots[i], x0 + i * cell, y0, cell, game.survival)
            }
            val sel = game.input.selectedSlot
            ui.frame(x0 + sel * cell - 2, y0 - 2, cell + 4, cell + 4, -1, 3f)

            if (game.survival) {
                val y = y0 - 28
                for (i in 0 until 10) {
                    val v = game.health - i * 2
                    ui.text("♥", x0 + i * 19, y, 20f, if (v >= 1f) rgba(220, 30, 40) else rgba(60, 30, 30))
                    val f = game.food - i * 2
                    ui.text("●", x0 + cell * 9 - 18 - i * 19, y, 20f, if (f >= 1f) rgba(196, 120, 60) else rgba(60, 40, 30))
                }
                val armor = game.inventory.armorPoints()
                for (i in 0 until 10) if (armor > i * 2) ui.text("◆", x0 + i * 19, y - 22, 18f, rgba(210, 214, 222))
            }

            // The held item, bottom right, dips when you swing.
            val held = game.heldId()
            if (held != 0) {
                val s = 120f
                val dip = if (swing > 0f) kotlin.math.sin(swing / 0.25f * Math.PI).toFloat() * 40f else 0f
                ui.icon(held, w - s - 150 - dip, h - s - 10 + dip, s)
            }
            if (swing > 0f) swing = maxOf(0f, swing - dt)
        }

        if (toastTime > 0f) {
            toastTime -= dt
            val a = (toastTime / 0.5f).coerceIn(0f, 1f)
            var y = h - 130f
            for (line in toast.split('\n').reversed()) {
                ui.text(line, w / 2, y, 20f, rgba(255, 255, 255, (255 * a).toInt()), 1)
                y -= 24f
            }
        }
        if (debug != null) {
            var y = 8f
            for (line in debug.split('\n')) { ui.text(line, 10f, y, 16f); y += 20f }
        }
    }
}

// ==================================================================== creative inventory

/** Creative inventory: tabs of blocks and items; click one to put it in the selected hotbar slot. */
class CreativeScreen(private val game: Game) {
    private val tabs: List<Pair<String, List<Int>>> =
        Category.values().map { it.title to Blocks.inCategory(it) } + ("Tools & items" to Items.all.map { it.id })
    private var tab = 0
    private var scroll = 0f
    private val search = Ui.Field("", "Search…", 24)

    /** Returns false when the screen should close. */
    fun draw(ui: Ui): Boolean {
        val cell = 42f
        val cols = 12
        val pw = cell * cols + 40; val ph = minOf(ui.height - 40, 600f)
        val px = (ui.width - pw) / 2; val py = (ui.height - ph) / 2
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        if (ui.clicked && !ui.hover(px, py, pw, ph)) return false
        ui.rect(px, py, pw, ph, PANEL); ui.frame(px, py, pw, ph, DARK, 3f)

        // Tabs
        val tw = (pw - 20) / tabs.size
        for ((i, t) in tabs.withIndex()) {
            val x = px + 10 + i * tw
            ui.rect(x + 2, py + 10, tw - 4, 32f, if (i == tab) rgba(235, 235, 235) else rgba(160, 160, 160))
            ui.text(t.first, x + tw / 2, py + 17, 16f, INK, 1, shadow = false)
            if (ui.clicked && ui.hover(x, py + 10, tw, 32f)) { tab = i; scroll = 0f }
        }
        ui.field(search, px + 10, py + 48, pw - 20, 32f)
        val q = search.text.trim().lowercase()
        val list = if (q.isEmpty()) tabs[tab].second
        else tabs.flatMap { it.second }.distinct().filter { Items.displayName(it).lowercase().contains(q) }

        // Grid
        val gx = px + 20; val gy = py + 90
        val hotY = py + ph - cell - 14
        val gh = hotY - gy - 12
        val rows = (list.size + cols - 1) / cols
        val maxScroll = maxOf(0f, rows * cell - gh)
        if (ui.hover(gx, gy, cell * cols, gh)) scroll = (scroll - ui.wheel * cell).coerceIn(0f, maxScroll)
        scroll = scroll.coerceIn(0f, maxScroll)
        var hovered: Int? = null
        for ((i, id) in list.withIndex()) {
            val x = gx + (i % cols) * cell; val y = gy + (i / cols) * cell - scroll
            if (y + cell < gy || y > gy + gh) continue
            // Rows cut by the edges are skipped instead of clipped.
            if (y < gy - 1 || y + cell > gy + gh + 1) continue
            ui.rect(x + 1, y + 1, cell - 2, cell - 2, SLOT)
            ui.stack(ItemStack(id), x, y, cell)
            if (ui.hover(x, y, cell, cell)) {
                hovered = id
                ui.frame(x, y, cell, cell, YELLOW, 2f)
                if (ui.clicked) game.inventory.slots[game.input.selectedSlot] = ItemStack(id, 1)
            }
        }
        if (maxScroll > 0f) {
            val bh = gh * gh / (rows * cell)
            ui.rect(px + pw - 14, gy + (gh - bh) * (scroll / maxScroll), 6f, bh, rgba(90, 90, 90))
        }

        // Hotbar: click a slot to choose where picked items go, right-click to clear it.
        ui.text("Hotbar (click to choose a slot, right-click to empty it):", gx, hotY - 22, 15f, INK, shadow = false)
        val hx = gx
        for (i in 0 until 9) {
            val x = hx + i * cell
            ui.rect(x + 1, hotY + 1, cell - 2, cell - 2, SLOT_DARK)
            ui.stack(game.inventory.slots[i], x, hotY, cell)
            if (i == game.input.selectedSlot) ui.frame(x, hotY, cell, cell, -1, 3f)
            if (ui.hover(x, hotY, cell, cell)) {
                if (ui.clicked) game.input.selectedSlot = i
                if (ui.rightClicked) game.inventory.slots[i] = null
                game.inventory.slots[i]?.let { hovered = it.id }
            }
        }
        hovered?.let { id -> Items[id]?.enchantments?.let { ui.tooltip("${Items.displayName(id)} ($it)") } ?: ui.tooltip(Items.displayName(id)) }
        return true
    }
}

// ==================================================================== survival containers

/**
 * Survival screens: player inventory (2x2 crafting), crafting table (3x3), chest / hopper and furnace.
 * Works like on a computer: click to pick up a stack, click to put it down, right-click for one / half,
 * shift-click to move a stack across.
 */
class ContainerScreen(private val game: Game) {
    enum class Mode { INVENTORY, CRAFTING, CHEST, FURNACE }

    private var mode = Mode.INVENTORY
    private var chest: ChestEntity? = null
    private var furnace: FurnaceEntity? = null
    private var held: ItemStack? = null
    private var recipeScroll = 0f

    private class Cell(val kind: Int, val index: Int, val x: Float, val y: Float, val size: Float)
    private val cells = ArrayList<Cell>()

    private companion object {
        const val INV = 0; const val ARMOR = 1; const val CHEST_SLOT = 2; const val FURNACE_SLOT = 3
        const val GRID = 5; const val OUTPUT = 6
    }

    fun open(m: Mode, chestEntity: ChestEntity? = null, furnaceEntity: FurnaceEntity? = null) {
        mode = m; chest = chestEntity; furnace = furnaceEntity; held = null; recipeScroll = 0f
    }

    /** Puts anything still on the cursor or in the crafting grid back into the inventory. */
    fun close() {
        held?.let { s ->
            val left = game.inventory.add(s.id, s.count, s.damage)
            if (left > 0) game.drops.spawn(ItemStack(s.id, left, s.damage), game.player.x, game.player.y + 1f, game.player.z)
        }
        held = null
        game.returnCraftGrid()
    }

    private fun gridSize() = if (mode == Mode.CRAFTING) 3 else 2
    private fun gridArray(): Array<ItemStack?> = Array(gridSize() * gridSize()) { game.craftGrid[it] }
    private fun gridResult(): ItemStack? = Recipes.match(gridArray(), gridSize())?.let { ItemStack(it.result, it.count) }

    private fun get(kind: Int, i: Int): ItemStack? = when (kind) {
        GRID -> game.craftGrid[i]
        OUTPUT -> gridResult()
        INV -> game.inventory.slots[i]
        ARMOR -> game.inventory.armor[i]
        CHEST_SLOT -> chest?.slots?.get(i)
        FURNACE_SLOT -> furnace?.let { f -> when (i) { 0 -> f.input; 1 -> f.fuel; else -> f.output } }
        else -> null
    }

    private fun set(kind: Int, i: Int, s: ItemStack?) {
        val v = if (s != null && s.count <= 0) null else s
        when (kind) {
            GRID -> game.craftGrid[i] = v
            INV -> game.inventory.slots[i] = v
            ARMOR -> game.inventory.armor[i] = v
            CHEST_SLOT -> chest?.slots?.set(i, v)
            FURNACE_SLOT -> furnace?.let { f -> when (i) { 0 -> f.input = v; 1 -> f.fuel = v; else -> f.output = v } }
        }
    }

    private fun accepts(kind: Int, i: Int, s: ItemStack): Boolean = when (kind) {
        OUTPUT -> false
        ARMOR -> Items[s.id]?.armorSlot == i
        FURNACE_SLOT -> when (i) { 0 -> Recipes.smelting.containsKey(s.id); 1 -> Items.fuel(s.id) > 0f; else -> false }
        else -> true
    }

    private fun same(a: ItemStack, b: ItemStack) = a.id == b.id && a.damage == b.damage

    private fun leftClick(c: Cell) {
        if (c.kind == OUTPUT) { takeOutput(false); return }
        val s = get(c.kind, c.index)
        val h = held
        when {
            h == null -> { held = s; set(c.kind, c.index, null) }
            !accepts(c.kind, c.index, h) -> {}
            s == null -> { set(c.kind, c.index, h); held = null }
            same(s, h) && s.count < s.maxStack -> {
                val n = minOf(h.count, s.maxStack - s.count)
                s.count += n; h.count -= n
                if (h.count <= 0) held = null
            }
            else -> { set(c.kind, c.index, h); held = s }
        }
    }

    private fun rightClick(c: Cell) {
        if (c.kind == OUTPUT) { takeOutput(false); return }
        val s = get(c.kind, c.index)
        val h = held
        if (h == null) {
            if (s == null) return
            val half = (s.count + 1) / 2
            held = ItemStack(s.id, half, s.damage)
            s.count -= half
            if (s.count <= 0) set(c.kind, c.index, null)
            return
        }
        if (!accepts(c.kind, c.index, h)) return
        if (s == null) set(c.kind, c.index, ItemStack(h.id, 1, h.damage))
        else if (same(s, h) && s.count < s.maxStack) s.count++
        else return
        h.count--
        if (h.count <= 0) held = null
    }

    /** Crafts from the grid onto the cursor (or straight into the inventory with shift). */
    private fun takeOutput(toInventory: Boolean) {
        val size = gridSize()
        val grid = gridArray()
        val r = Recipes.match(grid, size) ?: return
        val h = held
        if (!toInventory && h != null && (h.id != r.result || h.count + r.count > h.maxStack)) return
        Recipes.consumeGrid(grid)
        for (i in grid.indices) game.craftGrid[i] = grid[i]
        if (toInventory) {
            val left = game.inventory.add(r.result, r.count)
            if (left > 0) game.drops.spawn(ItemStack(r.result, left), game.player.x, game.player.y + 1f, game.player.z)
        } else if (h == null) held = ItemStack(r.result, r.count) else h.count += r.count
        game.uiEvents.add("craft")
    }

    private fun addTo(slots: Array<ItemStack?>, s: ItemStack, range: IntRange = slots.indices) {
        for (i in range) {
            val t = slots[i]
            if (t != null && same(t, s) && t.count < t.maxStack) { val n = minOf(s.count, t.maxStack - t.count); t.count += n; s.count -= n }
            if (s.count == 0) return
        }
        for (i in range) if (s.count > 0 && slots[i] == null) { slots[i] = s.copy(); s.count = 0 }
    }

    private fun shiftClick(c: Cell) {
        if (c.kind == OUTPUT) { takeOutput(true); return }
        val s = get(c.kind, c.index) ?: return
        when {
            c.kind == INV && mode == Mode.CHEST -> chest?.slots?.let { addTo(it, s) }
            c.kind == INV && mode == Mode.FURNACE -> {
                val target = if (Recipes.smelting.containsKey(s.id)) 0 else if (Items.fuel(s.id) > 0f) 1 else return
                val t = get(FURNACE_SLOT, target)
                if (t == null) { set(FURNACE_SLOT, target, s.copy()); s.count = 0 }
                else if (same(t, s)) { val n = minOf(s.count, t.maxStack - t.count); t.count += n; s.count -= n }
            }
            c.kind == INV && (Items[s.id]?.armorSlot ?: -1) >= 0 && game.inventory.armor[Items[s.id]!!.armorSlot] == null -> {
                game.inventory.armor[Items[s.id]!!.armorSlot] = s.copy(); s.count = 0
            }
            // Between the hotbar and the rest of the inventory.
            c.kind == INV -> addTo(game.inventory.slots, s, if (c.index < 9) 9 until 36 else 0 until 9)
            else -> s.count = game.inventory.add(s.id, s.count, s.damage)
        }
        if (s.count <= 0) set(c.kind, c.index, null)
    }

    private fun craftRecipe(r: Recipe) {
        if (!r.consume(game.inventory)) return
        val left = game.inventory.add(r.result, r.count)
        if (left > 0) game.drops.spawn(ItemStack(r.result, left), game.player.x, game.player.y + 1f, game.player.z)
        game.uiEvents.add("craft")
    }

    /** Returns false when the screen should close. */
    fun draw(ui: Ui): Boolean {
        val cell = 40f
        val showRecipes = mode == Mode.INVENTORY || mode == Mode.CRAFTING
        val leftW = cell * 10.4f
        val rightW = if (showRecipes) cell * 8f else if (mode == Mode.FURNACE) cell * 5.5f else 0f
        val pw = leftW + rightW + 30
        val topRows = if (mode == Mode.CHEST) 3.4f else 0f
        val ph = 50 + cell * (4.4f + topRows) + 40 + (if (showRecipes) cell * 2.4f else 0f)
        val px = (ui.width - pw) / 2; val py = (ui.height - ph) / 2
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        if (ui.clicked && !ui.hover(px, py, pw, ph) && held == null) return false
        ui.rect(px, py, pw, ph, PANEL); ui.frame(px, py, pw, ph, DARK, 3f)
        val title = when (mode) {
            Mode.INVENTORY -> "Inventory"; Mode.CRAFTING -> "Crafting Table"; Mode.FURNACE -> "Furnace"
            Mode.CHEST -> if (chest is HopperEntity) "Hopper" else "Chest"
        }
        ui.text(title, px + 14, py + 10, 20f, INK, shadow = false)

        cells.clear()
        var y = py + 42
        val x0 = px + 14 + cell * 1.3f
        if (mode == Mode.CHEST) {
            val n = chest?.slots?.size ?: 27
            for (i in 0 until n) cells.add(Cell(CHEST_SLOT, i, x0 + (i % 9) * cell, y + (i / 9) * cell, cell))
            y += cell * topRows
        }
        for (i in 0 until 4) cells.add(Cell(ARMOR, i, px + 12, y + i * cell, cell))
        for (i in 9 until 36) cells.add(Cell(INV, i, x0 + (i - 9) % 9 * cell, y + (i - 9) / 9 * cell, cell))
        for (i in 0 until 9) cells.add(Cell(INV, i, x0 + i * cell, y + cell * 3.3f, cell))

        val rx = px + 14 + leftW + 8
        val gy = py + 42
        if (showRecipes) {
            val gs = gridSize()
            for (i in 0 until gs * gs) cells.add(Cell(GRID, i, rx + (i % gs) * cell, gy + (i / gs) * cell, cell))
            val ox = rx + (gs + 1.4f) * cell; val oy = gy + (gs - 1) * cell / 2
            ui.text("→", rx + gs * cell + cell * 0.2f, oy + 6, 26f, INK, shadow = false)
            cells.add(Cell(OUTPUT, 0, ox, oy, cell * 1.1f))
        } else if (mode == Mode.FURNACE) {
            cells.add(Cell(FURNACE_SLOT, 0, rx + cell * 0.5f, gy, cell))
            cells.add(Cell(FURNACE_SLOT, 1, rx + cell * 0.5f, gy + cell * 2f, cell))
            cells.add(Cell(FURNACE_SLOT, 2, rx + cell * 3.2f, gy + cell, cell * 1.2f))
            furnace?.let { f ->
                val ay = gy + cell * 1.6f
                val l = rx + cell * 1.7f; val r = rx + cell * 3.0f
                ui.rect(l, ay - 3, r - l, 6f, SLOT_DARK)
                ui.rect(l, ay - 3, (r - l) * (f.progress / FurnaceEntity.SMELT_SECONDS).coerceIn(0f, 1f), 6f, rgba(250, 250, 250))
                if (f.burnLeft > 0f && f.burnTotal > 0f) {
                    val fh = cell * 0.8f * (f.burnLeft / f.burnTotal).coerceIn(0f, 1f)
                    ui.rect(rx + cell * 0.8f, gy + cell * 1.9f - fh, cell * 0.4f, fh, rgba(255, 150, 40))
                }
            }
        }

        var hoverStack: ItemStack? = null
        for (c in cells) {
            ui.rect(c.x + 2, c.y + 2, c.size - 4, c.size - 4, if (c.kind == ARMOR || c.kind == OUTPUT) SLOT_DARK else SLOT)
            if (c.kind == ARMOR && get(c.kind, c.index) == null) {
                ui.text(listOf("Head", "Body", "Legs", "Feet")[c.index], c.x + c.size / 2, c.y + c.size / 2 - 7, 12f, rgba(80, 80, 80), 1, shadow = false)
            }
            ui.stack(get(c.kind, c.index), c.x, c.y, c.size)
            if (ui.hover(c.x, c.y, c.size, c.size)) {
                ui.rect(c.x + 2, c.y + 2, c.size - 4, c.size - 4, rgba(255, 255, 255, 60))
                hoverStack = get(c.kind, c.index)
                when {
                    ui.clicked && ui.shift -> shiftClick(c)
                    ui.clicked -> leftClick(c)
                    ui.rightClicked -> rightClick(c)
                }
            }
        }

        if (showRecipes) hoverStack = drawRecipes(ui, rx, gy + gridSize() * cell + 16, px + pw - 12, py + ph - 30) ?: hoverStack
        val hint = when (mode) {
            Mode.CHEST, Mode.FURNACE -> "Click to pick up / put down · right-click: half / one · shift-click: move across"
            else -> "Lay items in the grid and take the result, or click a recipe · shift-click: move · right-click: one"
        }
        ui.text(hint, px + 14, py + ph - 24, 14f, INK, shadow = false)

        val h = held
        if (h != null) ui.stack(h, ui.mouseX - cell / 2, ui.mouseY - cell / 2, cell)
        else hoverStack?.let { ui.tooltip(stackName(it)) }
        return true
    }

    /** Recipe list; click a row to craft it from the inventory. Returns the hovered result, if any. */
    private fun drawRecipes(ui: Ui, x: Float, top: Float, right: Float, bottom: Float): ItemStack? {
        val inv = game.inventory
        val recipes = Recipes.available(inv, mode == Mode.CRAFTING).sortedByDescending { it.canCraft(inv) }
        ui.text("Recipes (click to craft):", x, top - 2, 14f, INK, shadow = false)
        val areaTop = top + 18
        val rh = 34f
        val h = bottom - areaTop
        val maxScroll = maxOf(0f, recipes.size * rh - h)
        if (ui.hover(x, areaTop, right - x, h)) recipeScroll = (recipeScroll - ui.wheel * rh).coerceIn(0f, maxScroll)
        recipeScroll = recipeScroll.coerceIn(0f, maxScroll)
        var hovered: ItemStack? = null
        for ((i, r) in recipes.withIndex()) {
            val y = areaTop + i * rh - recipeScroll
            if (y < areaTop - 1 || y + rh > bottom + 1) continue
            val ok = r.canCraft(inv)
            ui.rect(x, y, right - x, rh - 3, if (ok) rgba(170, 200, 160) else rgba(170, 170, 170))
            ui.stack(ItemStack(r.result, r.count), x, y, rh - 3)
            var ix = x + rh + 6
            ui.text("=", ix, y + 6, 16f, INK, shadow = false)
            ix += 14
            for (ing in r.ingredients) {
                val id = ing.ids.firstOrNull { inv.count(it) > 0 } ?: ing.ids[0]
                ui.stack(ItemStack(id, ing.count), ix, y + 2, rh - 7)
                if (ing.available(inv) < ing.count) ui.rect(ix + 4, y + rh / 2 - 2, rh - 15, 3f, rgba(200, 40, 40))
                ix += rh - 5
            }
            if (ui.hover(x, y, right - x, rh - 3)) {
                ui.frame(x, y, right - x, rh - 3, YELLOW, 2f)
                hovered = ItemStack(r.result, r.count)
                if (ui.clicked && held == null) craftRecipe(r)
            }
        }
        if (recipes.isEmpty()) ui.text("No recipes", x, areaTop + 6, 14f, INK, shadow = false)
        return hovered
    }
}
