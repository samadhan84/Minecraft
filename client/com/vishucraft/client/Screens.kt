package com.vishucraft.client

import com.vishucraft.client.Ui.Companion.rgba
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
        // Green when new, through yellow, to red when nearly worn out.
        val c = if (f > 0.5f) rgba(((1f - f) * 2f * 255).toInt(), 255, 0) else rgba(255, (f * 2f * 255).toInt(), 0)
        rect(x + pad, by, (size - 2 * pad) * f, size * 0.05f, c)
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

    /** Today's play time left (see engine/PlayLimit), shown at the top right; red in the last 5 minutes. */
    var timeLeft: String? = null
    var timeLow = false

    /** The toast alone (drawn over the full-screen settings, which hide the rest of the HUD). */
    fun drawToastOnly(ui: Ui, dt: Float) {
        if (toastTime <= 0f) return
        toastTime -= dt
        val a = (toastTime / 0.5f).coerceIn(0f, 1f)
        var y = ui.height - 130f
        for (line in toast.split('\n').reversed()) {
            ui.text(line, ui.width / 2, y, 20f, rgba(255, 255, 255, (255 * a).toInt()), 1)
            y -= 24f
        }
    }

    fun toast(s: String, seconds: Float = 2f) { toast = com.vishucraft.game.world.Names.show(s); toastTime = seconds }

    /** Seconds since the world opened (the game's name shows big at first). */
    private var shown = 0f

    fun draw(ui: Ui, dt: Float, debug: String?, showHotbar: Boolean) {
        val w = ui.width; val h = ui.height
        // The game's name: big in the middle when the world opens, then small at the top while playing.
        shown += dt
        if (shown < 3.4f) {
            val a = if (shown < 2.5f) 1f else 1f - (shown - 2.5f) / 0.9f
            ui.text("Dhruvil Craft", w / 2 + 4, h * 0.3f + 4, 72f, rgba(50, 50, 50, (255 * a).toInt()), 1)
            ui.text("Dhruvil Craft", w / 2, h * 0.3f, 72f, rgba(235, 235, 235, (255 * a).toInt()), 1)
        } else {
            val a = ((shown - 3.4f) / 0.5f).coerceIn(0f, 1f) * 0.85f
            ui.text("Dhruvil Craft", w / 2 + 1, 9f, 18f, rgba(0, 0, 0, (255 * a).toInt()), 1)
            ui.text("Dhruvil Craft", w / 2, 8f, 18f, rgba(255, 255, 110, (255 * a).toInt()), 1)
        }
        if (hurt > 0f) { ui.rect(0f, 0f, w, h, rgba(220, 0, 0, (110 * hurt).toInt())); hurt = maxOf(0f, hurt - dt * 3f) }
        if (sleep > 0f) {
            // Fade to black and back while the night passes.
            val a = if (sleep > 1.5f) (3f - sleep) / 1.5f else sleep / 1.5f
            ui.rect(0f, 0f, w, h, rgba(0, 0, 0, (255 * a.coerceIn(0f, 1f)).toInt()))
            sleep = maxOf(0f, sleep - dt)
        }
        timeLeft?.let { t ->
            val label = "Time left $t"
            val tw = ui.textWidth(label, 18f)
            ui.rect(w - tw - 28, 6f, tw + 20, 30f, rgba(0, 0, 0, 140))
            ui.text(label, w - tw - 18, 11f, 18f, if (timeLow) rgba(255, 90, 80) else rgba(255, 255, 255))
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
                // Air bubbles while under water.
                if (game.air < 10f) for (i in 0 until 10) if (game.air > 9 - i) ui.text("●", x0 + cell * 9 - 18 - i * 19, y - 22, 16f, rgba(120, 190, 255))
                // Experience bar and level.
                ui.rect(x0, y0 - 7, cell * 9, 5f, rgba(20, 30, 10, 180))
                ui.rect(x0, y0 - 7, cell * 9 * game.xpProgress.coerceIn(0f, 1f), 5f, rgba(128, 230, 60))
                if (game.xpLevel > 0) ui.text(game.xpLevel.toString(), w / 2, y0 - 26, 16f, rgba(128, 255, 60), 1)
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
        // Active potion effects, top right.
        if (game.effects.isNotEmpty()) {
            var y = 10f
            for ((key, left) in game.effects.toSortedMap()) {
                val name = com.vishucraft.game.world.Items.POTIONS.firstOrNull { it.second == key }?.first ?: key
                val secs = left.toInt()
                ui.text("$name ${secs / 60}:${"%02d".format(secs % 60)}", w - 12, y, 16f, rgba(200, 230, 255), 2)
                y += 20f
            }
        }
        if (game.gliding) ui.text("Gliding", w / 2, 40f, 18f, rgba(220, 220, 255), 1)
        game.lookedAtLabel()?.let { ui.text(com.vishucraft.game.world.Names.show(it), w / 2, h / 2 - 50, 18f, -1, 1) }
        // Boss health bar.
        game.bossBar()?.let { (name, hp) ->
            val bw = minOf(600f, w - 80)
            ui.text(name, w / 2, 14f, 18f, rgba(220, 200, 255), 1)
            ui.rect(w / 2 - bw / 2, 38f, bw, 10f, rgba(40, 20, 60, 200))
            ui.rect(w / 2 - bw / 2, 38f, bw * hp, 10f, rgba(170, 110, 250))
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
        Category.values().map { it.title to Blocks.inCategory(it) } +
            ("Items" to Items.shown.filter { com.vishucraft.game.engine.Cracker.of(it.name) == null && !com.vishucraft.game.engine.Festivals.isFestivalItem(it.name) }.map { it.id }) +
            (com.vishucraft.game.engine.Festivals.active()?.let { listOf("Festival" to it.festival.items.map { fi -> Items.find(fi.name) }) } ?: emptyList())
    var tab = 0
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
        mode = m; chest = chestEntity; furnace = furnaceEntity; held = null; recipeScroll = 0f; book = false
    }

    /** Puts anything still on the cursor or in the crafting grid back into the inventory. */
    fun close() {
        held?.let { s ->
            val left = game.inventory.add(s.id, s.count, s.damage)
            if (left > 0) game.drops.spawn(ItemStack(s.id, left, s.damage), game.player.x, game.player.y + 1f, game.player.z)
        }
        held = null
        game.returnCraftGrid()
        game.closeContainer()
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

    private var book = false

    /** The grey Minecraft window: black outline, light top and left edge, dark bottom and right edge. */
    private fun window(ui: Ui, x: Float, y: Float, w: Float, h: Float, u: Float) {
        ui.rect(x, y, w, h, rgba(0, 0, 0))
        ui.rect(x + u, y + u, w - 2 * u, h - 2 * u, PANEL)
        ui.rect(x + u, y + u, w - 2 * u, u, rgba(255, 255, 255))
        ui.rect(x + u, y + u, u, h - 2 * u, rgba(255, 255, 255))
        ui.rect(x + u, y + h - 2 * u, w - 2 * u, u, rgba(85, 85, 85))
        ui.rect(x + w - 2 * u, y + u, u, h - 2 * u, rgba(85, 85, 85))
    }

    /** A slot: sunken, dark on the top and left, light on the bottom and right. */
    private fun slotBox(ui: Ui, x: Float, y: Float, size: Float, u: Float) {
        ui.rect(x, y, size, size, rgba(55, 55, 55))
        ui.rect(x + u, y + u, size - u, size - u, rgba(255, 255, 255))
        ui.rect(x + u, y + u, size - 2 * u, size - 2 * u, SLOT)
    }

    /** Steve-like figure in the box of the inventory screen. */
    private fun figure(ui: Ui, x: Float, y: Float, w: Float, h: Float) {
        ui.rect(x, y, w, h, rgba(20, 20, 20)); ui.rect(x + 2, y + 2, w - 4, h - 4, rgba(60, 60, 64))
        val cx = x + w / 2; val u = h / 36f
        ui.rect(cx - 4 * u, y + 4 * u, 8 * u, 8 * u, rgba(196, 146, 104))        // head
        ui.rect(cx - 4 * u, y + 4 * u, 8 * u, 3 * u, rgba(70, 44, 24))           // hair
        ui.rect(cx - 4 * u, y + 12 * u, 8 * u, 12 * u, rgba(0, 170, 170))        // shirt
        ui.rect(cx - 8 * u, y + 12 * u, 4 * u, 12 * u, rgba(196, 146, 104)); ui.rect(cx + 4 * u, y + 12 * u, 4 * u, 12 * u, rgba(196, 146, 104))
        ui.rect(cx - 4 * u, y + 24 * u, 4 * u, 12 * u, rgba(54, 56, 130)); ui.rect(cx, y + 24 * u, 4 * u, 12 * u, rgba(54, 56, 130))
    }

    /** Returns false when the screen should close. */
    fun draw(ui: Ui): Boolean {
        val u = 2.5f                       // one Minecraft pixel
        val cell = 18 * u
        val hopper = chest is HopperEntity
        val pw = 176 * u
        val ph = (if (mode == Mode.CHEST && hopper) 133 else 166) * u
        val bookW = if (book && (mode == Mode.INVENTORY || mode == Mode.CRAFTING)) cell * 8f + 12 else 0f
        val px = (ui.width - pw - bookW) / 2; val py = (ui.height - ph) / 2
        ui.rect(0f, 0f, ui.width, ui.height, rgba(0, 0, 0, 150))
        if (ui.clicked && !ui.hover(px, py, pw + bookW, ph) && held == null) return false
        window(ui, px, py, pw, ph, u)
        val title = when (mode) {
            Mode.INVENTORY -> "Crafting"; Mode.CRAFTING -> "Crafting"; Mode.FURNACE -> "Furnace"
            Mode.CHEST -> if (hopper) "Item Hopper" else "Chest"
        }
        fun at(gx: Int, gy: Int) = (px + gx * u) to (py + gy * u)

        cells.clear()
        val invY = if (mode == Mode.CHEST && hopper) 51 else 84
        val hotY = if (mode == Mode.CHEST && hopper) 109 else 142
        for (i in 9 until 36) { val (x, y) = at(8 + (i - 9) % 9 * 18, invY + (i - 9) / 9 * 18); cells.add(Cell(INV, i, x, y, cell)) }
        for (i in 0 until 9) { val (x, y) = at(8 + i * 18, hotY); cells.add(Cell(INV, i, x, y, cell)) }
        ui.text(if (mode == Mode.INVENTORY) "Crafting" else title, px + (if (mode == Mode.INVENTORY) 98 else if (mode == Mode.CRAFTING) 28 else 8) * u, py + 6 * u, 15f, INK, shadow = false)
        if (mode != Mode.INVENTORY) ui.text("Inventory", px + 8 * u, py + (invY - 11) * u, 15f, INK, shadow = false)
        var bookBtn: Triple<Float, Float, Float>? = null

        when (mode) {
            Mode.INVENTORY -> {
                for (i in 0 until 4) { val (x, y) = at(8, 8 + i * 18); cells.add(Cell(ARMOR, i, x, y, cell)) }
                val (fx, fy) = at(26, 8); figure(ui, fx, fy, 49 * u, 70 * u)
                for (i in 0 until 4) { val (x, y) = at(98 + (i % 2) * 18, 18 + (i / 2) * 18); cells.add(Cell(GRID, i, x, y, cell)) }
                val (ax, ay) = at(134, 28); ui.text("→", ax, ay, 26f, INK, shadow = false)
                val (ox, oy) = at(154, 28); cells.add(Cell(OUTPUT, 0, ox, oy, cell))
                val (bx, by) = at(104, 60); bookBtn = Triple(bx, by, 20 * u)
            }
            Mode.CRAFTING -> {
                for (i in 0 until 9) { val (x, y) = at(30 + (i % 3) * 18, 17 + (i / 3) * 18); cells.add(Cell(GRID, i, x, y, cell)) }
                val (ax, ay) = at(92, 36); ui.text("→", ax, ay, 26f, INK, shadow = false)
                val (ox, oy) = at(124, 35); cells.add(Cell(OUTPUT, 0, ox - 4 * u, oy - 4 * u, cell + 8 * u))
                val (bx, by) = at(5, 34); bookBtn = Triple(bx, by, 20 * u)
            }
            Mode.CHEST -> {
                val n = chest?.slots?.size ?: 27
                if (hopper) for (i in 0 until n) { val (x, y) = at(44 + i * 18, 20); cells.add(Cell(CHEST_SLOT, i, x, y, cell)) }
                else for (i in 0 until n) { val (x, y) = at(8 + (i % 9) * 18, 18 + (i / 9) * 18); cells.add(Cell(CHEST_SLOT, i, x, y, cell)) }
            }
            Mode.FURNACE -> {
                val (ix, iy) = at(56, 17); cells.add(Cell(FURNACE_SLOT, 0, ix, iy, cell))
                val (fx, fy) = at(56, 53); cells.add(Cell(FURNACE_SLOT, 1, fx, fy, cell))
                val (ox, oy) = at(116, 35); cells.add(Cell(FURNACE_SLOT, 2, ox - 4 * u, oy - 4 * u, cell + 8 * u))
                furnace?.let { f ->
                    // The flame (burns down) and the arrow (fills up) between the slots.
                    val (flx, fly) = at(56, 36)
                    ui.rect(flx + u, fly, 14 * u, 14 * u, rgba(60, 60, 60))
                    if (f.burnLeft > 0f && f.burnTotal > 0f) {
                        val fh = 14 * u * (f.burnLeft / f.burnTotal).coerceIn(0f, 1f)
                        ui.rect(flx + u, fly + 14 * u - fh, 14 * u, fh, rgba(255, 150, 40))
                    }
                    val (arx, ary) = at(79, 34)
                    ui.rect(arx, ary, 24 * u, 17 * u, rgba(139, 139, 139))
                    ui.rect(arx, ary, 24 * u * (f.progress / FurnaceEntity.SMELT_SECONDS).coerceIn(0f, 1f), 17 * u, rgba(250, 250, 250))
                }
            }
        }

        var hoverStack: ItemStack? = null
        for (c in cells) {
            val big = c.size > cell
            slotBox(ui, c.x, c.y, c.size, u)
            if (c.kind == ARMOR && get(c.kind, c.index) == null) {
                ui.text(listOf("H", "B", "L", "F")[c.index], c.x + c.size / 2, c.y + c.size / 2 - 7, 14f, rgba(95, 95, 95), 1, shadow = false)
            }
            ui.stack(get(c.kind, c.index), c.x + (if (big) 4 * u else 0f), c.y + (if (big) 4 * u else 0f), cell)
            if (ui.hover(c.x, c.y, c.size, c.size)) {
                ui.rect(c.x + u, c.y + u, c.size - 2 * u, c.size - 2 * u, rgba(255, 255, 255, 110))
                hoverStack = get(c.kind, c.index)
                when {
                    ui.clicked && ui.shift -> shiftClick(c)
                    ui.clicked -> leftClick(c)
                    ui.rightClicked -> rightClick(c)
                }
            }
        }

        // The recipe book button: open it for a list of recipes to craft from what you carry.
        bookBtn?.let { (bx, by, w) ->
            val h = 18 * u
            ui.rect(bx, by, w, h, rgba(0, 0, 0)); ui.rect(bx + u, by + u, w - 2 * u, h - 2 * u, if (book) rgba(140, 200, 140) else rgba(170, 170, 170))
            ui.text("Book", bx + w / 2, by + h / 2 - 7, 13f, INK, 1, shadow = false)
            if (ui.hover(bx, by, w, h)) {
                ui.frame(bx, by, w, h, YELLOW, 2f)
                if (ui.clicked && held == null) book = !book
            }
        }
        if (bookW > 0f) {
            val rx = px + pw + 8
            ui.rect(rx - 4, py, bookW, ph, rgba(0, 0, 0)); ui.rect(rx - 3, py + 1, bookW - 2, ph - 2, PANEL)
            hoverStack = drawRecipes(ui, rx + 4, py + 18, px + pw + bookW - 8, py + ph - 10) ?: hoverStack
        }

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
