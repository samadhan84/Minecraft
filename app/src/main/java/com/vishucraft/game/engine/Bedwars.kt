package com.vishucraft.game.engine

import com.vishucraft.game.render.ChunkMesher
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.GameMode
import com.vishucraft.game.world.ItemStack
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.LevelData
import com.vishucraft.game.world.Shapes
import com.vishucraft.game.world.floorInt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Bedwars. Four teams (red is yours) each have an island in the sky with a bed, a generator that makes iron and
 * gold, and a shop. You (with one bot as your team-mate) play against three teams of two bots. Spend iron and gold
 * at the shop on wool to bridge across the sky, swords and armour; break the other teams' beds (they cannot come
 * back to life once their bed is gone) and defend your own. The last team standing wins. Fall into the sky and you
 * lose what you carry. The bots do all of this too: they collect resources, buy blocks and swords, bridge across the
 * void to your island, fight, break beds and defend theirs. The difficulty (Easy, Medium, Hard) is how fast, hard-
 * hitting and clever they are.
 */

const val BEDWARS_DIR = "bedwars"
val DIFFICULTY_NAMES = listOf("Easy", "Medium", "Hard")

/** The standing height on every island (the top blocks are one lower). */
private const val SURF = 64

enum class BwTeam(val label: String, val dye: Int, val wool: Int, val mob: MobType, val ox: Int, val oz: Int) {
    RED("Red", 14, Blocks.WOOL_RED, MobType.BOT_RED, 1, 0),
    BLUE("Blue", 11, Blocks.WOOL_BLUE, MobType.BOT_BLUE, 0, 1),
    GREEN("Green", 13, Blocks.WOOL_GREEN, MobType.BOT_GREEN, -1, 0),
    YELLOW("Yellow", 4, Blocks.WOOL_YELLOW, MobType.BOT_YELLOW, 0, -1);

    /** The middle of the team's island. */
    val cx get() = ox * 40
    val cz get() = oz * 40
    /** The bed's first (foot) block, on the outer side of the island, and the second (head) block next to it. */
    val bedX get() = cx + ox * 5
    val bedZ get() = cz + oz * 5
    val bedFacing get() = if (ox != 0) 2 else 4
    /** Shop block number [k] (0 to 8): a row along the side of the island, out of the way of anyone crossing it. */
    fun shopX(k: Int) = cx - oz * 5 + ox * (k - 4)
    fun shopZ(k: Int) = cz + ox * 5 + oz * (k - 4)
    /** Which way the signs on the shop row face (towards the middle of the island's side). */
    val shopFace get() = if (ox > 0) 3 else if (ox < 0) 2 else if (oz > 0) 4 else 5
    val bedHeadX get() = bedX + ChunkMesher.NORMALS[bedFacing][0]
    val bedHeadZ get() = bedZ + ChunkMesher.NORMALS[bedFacing][2]
}

/** What makes the bots easier or harder. */
class BwLevel(val income: Float, val delay: Float, val speed: Float, val hitCooldown: Float, val damage: Float,
                    val bridge: Float, val breakTime: Float, val sight: Float)
private val LEVELS: List<BwLevel> = listOf(
    BwLevel(income = 0.6f, delay = 150f, speed = 3.4f, hitCooldown = 1.4f, damage = 0.5f, bridge = 0.5f, breakTime = 8f, sight = 5f),     // Easy
    BwLevel(income = 0.9f, delay = 75f, speed = 4.3f, hitCooldown = 1.0f, damage = 0.8f, bridge = 0.3f, breakTime = 5f, sight = 7f),      // Medium
    BwLevel(income = 1.3f, delay = 30f, speed = 5.0f, hitCooldown = 0.7f, damage = 1.0f, bridge = 0.15f, breakTime = 3f, sight = 10f),    // Hard
)

/** One bot player. */
class BwBot(val team: BwTeam, val index: Int) {
    var mob: Mob? = null
    var defender = false
    var iron = 0f; var gold = 0f; var diamond = 0f
    var sword = 0; var armor = 0; var blocks = 12
    /** Seconds until it comes back after dying (its bed must still be there). */
    var respawn = 0f
    /** Gone for good (died with no bed). */
    var out = false
    internal var hitCooldown = 0f; internal var placeCooldown = 0f; internal var breakTime = 0f
    internal var buyTimer = 0f; internal var wanderTimer = 0f; internal var wanderX = 0f; internal var wanderZ = 0f
    internal var target: BwTeam? = null
    /** Hard bots go to a diamond island for diamonds (x, z), or null. */
    internal var errand: IntArray? = null
    internal var errandTime = 0f
    internal var stuck = 0f
    /** What it is doing, for debugging. */
    internal var why = ""
    internal var blockedFor = 0f; internal var detour = 0f; internal var detourSign = 1f
    internal var lastX = 0f; internal var lastZ = 0f
}

/** The state of a Bedwars match. */
class Bedwars(val difficulty: Int) {
    val bedAlive = BooleanArray(4) { true }
    val bots = ArrayList<BwBot>()
    var time = 0f
    var finished = false
    var victory = false
    var playerOut = false
    internal var ironTimer = 0f; internal var goldTimer = 0f; internal var diamondTimer = 0f; internal var emeraldTimer = 0f
    internal var checkTimer = 0f; internal var celebrate = 0f; internal var timeFix = 0f
    internal var warnTimer = 0f
    val level: BwLevel get() = LEVELS[(difficulty - 1).coerceIn(0, 2)]
}

private val DIAMOND_ISLANDS = listOf(22 to 22, -22 to 22, 22 to -22, -22 to -22)

/** The level data for a new match on [difficulty] 1 (Easy) to 3 (Hard): you are on the red island with a wooden sword and some wool. */
fun newBedwarsLevel(difficulty: Int): LevelData {
    val l = LevelData.create(2026L, "Bedwars", GameMode.SURVIVAL)
    l.bedwars = difficulty.coerceIn(1, 3); l.passwordAsked = true
    l.hasPlayer = true; l.x = BwTeam.RED.cx - 1.5f; l.y = SURF.toFloat(); l.z = BwTeam.RED.cz + 0.5f
    l.yaw = -Math.PI.toFloat() / 2   // looking towards the middle of the map
    l.timeOfDay = 0.3f
    l.inventory.slots[0] = ItemStack(Items.find("Wooden Sword"), 1)
    l.inventory.slots[1] = ItemStack(BwTeam.RED.wool, 16)
    return l
}

// ---------------------------------------------------------------- building the map

private fun Game.island(cx: Int, cz: Int, r: Double, top: Int = SURF - 1, topId: Int = Blocks.GRASS) {
    val ri = r.toInt() + 1
    for (x in cx - ri..cx + ri) for (z in cz - ri..cz + ri) {
        val d2 = ((x - cx) * (x - cx) + (z - cz) * (z - cz)).toDouble()
        if (d2 > r * r) continue
        setBlock(x, top, z, topId)
        setBlock(x, top - 1, z, Blocks.DIRT); setBlock(x, top - 2, z, Blocks.DIRT)
        // The underside narrows to a point, like a floating island.
        val depth = ((1 - sqrt(d2) / r) * 6).toInt() + 1
        for (k in 3..2 + depth) setBlock(x, top - k, z, Blocks.STONE)
    }
}

private fun Game.buildBedwarsMap() {
    for (cz in -4..3) for (cx in -4..3) world.ensureLoaded(cx, cz)
    island(0, 0, 8.5)                                                      // the middle island (emeralds)
    setBlock(0, SURF, 0, Blocks.EMERALD_BLOCK)
    for ((x, z) in DIAMOND_ISLANDS) { island(x, z, 4.0, topId = Blocks.SAND); setBlock(x, SURF, z, Blocks.DIAMOND_BLOCK) }
    val shop = listOf("Wool x16 - 4 Iron", "Planks x8 - 4 Gold", "Stone Sword - 10 Iron", "Iron Sword - 7 Gold", "Diamond Sword - 4 Diamonds",
        "Iron Armour - 24 Iron", "Bow + 8 Arrows - 12 Gold", "Golden Apple - 3 Gold", "Ender Pearl - 4 Emeralds")
    for (t in BwTeam.values()) {
        island(t.cx, t.cz, 7.5)
        // A pad of the team's wool where you stand, and the generator in the middle.
        for (dx in -1..1) for (dz in -1..1) setBlock(t.cx + dx, SURF - 1, t.cz + dz, t.wool)
        setBlock(t.cx, SURF, t.cz, Blocks.GOLD_BLOCK)
        // The bed with a wall of the team's wool all round and over it.
        for (dx in -2..2) for (dz in -2..2) for (h in 0..1) {
            val x = t.bedX + dx; val z = t.bedZ + dz
            val onBed = (x == t.bedX && z == t.bedZ) || (x == t.bedHeadX && z == t.bedHeadZ)
            val inRing = x in minOf(t.bedX, t.bedHeadX) - 1..maxOf(t.bedX, t.bedHeadX) + 1 && z in minOf(t.bedZ, t.bedHeadZ) - 1..maxOf(t.bedZ, t.bedHeadZ) + 1
            if (inRing && !(onBed && h == 0)) setBlock(x, SURF + h, z, t.wool)
        }
        setBlock(t.bedX, SURF, t.bedZ, Blocks.BED_FIRST + t.dye, t.bedFacing)
        setBlock(t.bedHeadX, SURF, t.bedHeadZ, Blocks.BED_FIRST + t.dye, t.bedFacing or Shapes.UPPER)
        // Nine shop blocks in a row along the side of the island, each with a sign saying what it sells.
        for (k in 0..8) {
            val x = t.shopX(k); val z = t.shopZ(k)
            setBlock(x, SURF, z, Blocks.BW_SHOP, k)
            setBlock(x, SURF + 1, z, Blocks.SIGN, t.shopFace)
            world.blockEntities.sign(x, SURF + 1, z)?.text = shop[k]
        }
        // Light posts at the corners of the island.
        for ((dx, dz) in listOf(5 to 5, -5 to 5, 5 to -5, -5 to -5)) { setBlock(t.cx + dx, SURF, t.cz + dz, Blocks.OAK_FENCE); setBlock(t.cx + dx, SURF + 1, t.cz + dz, Blocks.SEA_LANTERN) }
    }
}

private fun Game.startBedwars() {
    val bw = Bedwars(level.bedwars)
    bedwars = bw
    buildBedwarsMap()
    val red = BwTeam.RED
    level.hasBedSpawn = true; level.bedX = red.bedX; level.bedY = SURF; level.bedZ = red.bedZ
    player.x = red.cx - 1.5f; player.y = SURF.toFloat(); player.z = red.cz + 0.5f
    player.yaw = -Math.PI.toFloat() / 2
    mobs.hostileEnabled = false
    timeOfDay = 0.3f
    for (t in BwTeam.values()) for (i in 0 until (if (t == red) 1 else 2)) {
        val b = BwBot(t, i)
        // Medium and Hard teams keep one bot at home to guard the bed.
        b.defender = bw.difficulty >= 2 && t != red && i == 0
        b.buyTimer = 3f + i
        bw.bots.add(b)
        spawnBot(b)
    }
    uiEvents.add("toast:Bedwars - ${DIFFICULTY_NAMES[bw.difficulty - 1]}! Collect iron and gold, buy wool at the shop, break the other beds and protect yours")
}

private fun Game.spawnBot(b: BwBot) {
    val t = b.team
    // Beside the middle of the island, the two bots of a team a little apart.
    val side = (b.index * 2 - 1) * 1.5f
    val m = Mob(t.mob, t.cx + 0.5f - t.ox * 2 + (if (t.ox == 0) side else 0f), SURF.toFloat(), t.cz + 0.5f - t.oz * 2 + (if (t.ox != 0) side else 0f))
    m.customName = "${t.label} bot"
    m.yaw = atan2(-t.ox.toFloat(), t.oz.toFloat())
    if (b.armor > 0) m.health += b.armor * 6f
    mobs.list.add(m)
    b.mob = m
    b.lastX = m.x; b.lastZ = m.z
}

// ---------------------------------------------------------------- the match

internal fun Game.updateBedwars(dt: Float) {
    if (isClient || level.bedwars == 0) return
    if (bedwars == null) startBedwars()
    val bw = bedwars ?: return
    bw.time += dt
    food = 20f   // no hunger in a match
    bw.timeFix -= dt
    if (bw.timeFix <= 0f) { bw.timeFix = 5f; timeOfDay = 0.3f }
    // Falling into the sky costs you what you carry.
    if (player.y < 40f && playerAlive) hurtPlayer(100f, player.x, player.z, knockback = false)
    if (bw.finished) {
        if (bw.victory && bw.celebrate > 0f) {
            bw.celebrate -= dt
            if (Math.random() < dt * 3.0) fireworks.colourBurst(player.x + (Math.random().toFloat() - 0.5f) * 14f, player.y + 12f + Math.random().toFloat() * 8f, player.z + (Math.random().toFloat() - 0.5f) * 14f,
                120, 8f, Fireworks.HOLI, 2f, 3f)
        }
        return
    }
    generators(bw, dt)
    for (b in bw.bots) updateBot(b, bw, dt)
    bw.checkTimer -= dt
    if (bw.checkTimer <= 0f) { bw.checkTimer = 0.5f; checkMatch(bw) }
}

/** Iron and gold on every island, diamonds on the small islands and emeralds in the middle. */
private fun Game.generators(bw: Bedwars, dt: Float) {
    val lv = bw.level
    fun credit(x: Int, z: Int, range: Float, what: Int, amount: Float) {
        for (b in bw.bots) {
            val m = b.mob ?: continue
            if (m.dead || hypot(m.x - x, m.z - z) > range) continue
            when (what) { 0 -> b.iron += amount; 1 -> b.gold += amount; 2 -> b.diamond += amount }
        }
    }
    // A bot that is out and about picks up a little iron on the way, so it never runs dry out in the sky.
    fun scavenge() { for (b in bw.bots) { val m = b.mob ?: continue; if (!m.dead && hypot(m.x - b.team.cx, m.z - b.team.cz) > 14f) b.iron += 1.0f * lv.income } }
    fun drop(id: Int, x: Int, z: Int) {
        val near = drops.list.count { abs(it.x - x) < 3f && abs(it.z - z) < 3f }
        if (near < 40) drops.spawn(ItemStack(id, 1), x + 0.5f, SURF + 1.2f, z + 0.5f)
    }
    bw.ironTimer += dt; bw.goldTimer += dt; bw.diamondTimer += dt; bw.emeraldTimer += dt
    if (bw.ironTimer >= 1.5f) {
        bw.ironTimer = 0f
        scavenge()
        for (t in BwTeam.values()) { credit(t.cx, t.cz, 14f, 0, lv.income); if (t == BwTeam.RED) drop(Items.find("Iron Ingot"), t.cx, t.cz) }
    }
    if (bw.goldTimer >= 6f) {
        bw.goldTimer = 0f
        for (t in BwTeam.values()) { credit(t.cx, t.cz, 14f, 1, lv.income); if (t == BwTeam.RED) drop(Items.find("Gold Ingot"), t.cx, t.cz) }
    }
    if (bw.diamondTimer >= 20f) {
        bw.diamondTimer = 0f
        for ((x, z) in DIAMOND_ISLANDS) { credit(x, z, 7f, 2, 1f); drop(Items.find("Diamond"), x, z) }
    }
    if (bw.emeraldTimer >= 40f) { bw.emeraldTimer = 0f; drop(Items.find("Emerald"), 0, 0) }
}

private fun Game.checkMatch(bw: Bedwars) {
    fun teamAlive(t: BwTeam) = bw.bedAlive[t.ordinal] || bw.bots.any { it.team == t && !it.out } || (t == BwTeam.RED && !bw.playerOut)
    if (bw.playerOut) {
        bw.finished = true; bw.victory = false
        uiEvents.add("toast:DEFEAT! You were out of the game. Open the game menu to leave.")
        return
    }
    if (BwTeam.values().filter { it != BwTeam.RED }.none { teamAlive(it) }) {
        bw.finished = true; bw.victory = true; bw.celebrate = 15f
        uiEvents.add("toast:VICTORY! Your team is the last one standing! Open the game menu to leave.")
        sound("ding", player.x, player.y, player.z, 1f)
    }
}

/** Called after the player died and was put back at the bed: with no bed left, that was the last life. */
internal fun Game.bedwarsPlayerDied() {
    val bw = bedwars ?: return
    if (bw.bedAlive[BwTeam.RED.ordinal]) { uiEvents.add("toast:You died! Back at your bed: everything you carried was lost"); return }
    bw.playerOut = true
    player.x = 0.5f; player.y = SURF.toFloat(); player.z = 0.5f
    uiEvents.add("toast:Your bed is gone and you died: you are out of the game")
}

/** A bed was destroyed: that team can no longer come back to life. */
internal fun Game.destroyBed(team: BwTeam, by: String) {
    val bw = bedwars ?: return
    if (!bw.bedAlive[team.ordinal]) return
    bw.bedAlive[team.ordinal] = false
    setBlock(team.bedX, SURF, team.bedZ, Blocks.AIR); setBlock(team.bedHeadX, SURF, team.bedHeadZ, Blocks.AIR)
    sound("explode", team.bedX + 0.5f, SURF + 1f, team.bedZ + 0.5f, 1.2f)
    fireworks.colourBurst(team.bedX + 0.5f, SURF + 1f, team.bedZ + 0.5f, 80, 5f, Fireworks.PINKS, 1.5f, 4f)
    uiEvents.add("toast:${if (team == BwTeam.RED) "YOUR BED was destroyed by $by! You will not come back if you die" else "${team.label} team's bed was destroyed by $by!"}")
}

/** The player breaks a bed block: whose bed was it? (Called from Game.breakBlock.) */
internal fun Game.bedwarsBedBroken(x: Int, z: Int) {
    val bw = bedwars ?: return
    for (t in BwTeam.values()) if ((x == t.bedX && z == t.bedZ) || (x == t.bedHeadX && z == t.bedHeadZ)) {
        if (!bw.bedAlive[t.ordinal]) return
        bw.bedAlive[t.ordinal] = false
        sound("explode", x + 0.5f, SURF + 1f, z + 0.5f, 1.2f)
        uiEvents.add("toast:${t.label} team's bed was destroyed by YOU!")
        return
    }
}

/** What the player may break in a match: beds of other teams, and the blocks players put down. Returns false (with a message) if not. */
internal fun Game.bedwarsMayBreak(x: Int, y: Int, z: Int, id: Int): Boolean {
    val bw = bedwars ?: return true
    if (Blocks.isBed(id)) {
        val own = BwTeam.RED
        if ((x == own.bedX && z == own.bedZ) || (x == own.bedHeadX && z == own.bedHeadZ)) { notice("You can't break your own bed!"); return false }
        return true
    }
    if (Blocks[id].name.endsWith("Wool") || id == Blocks.PLANKS || id == Blocks.GLASS) return true
    notice("Only beds and the blocks players put down can be broken")
    return false
}

private fun Game.notice(text: String) {
    val bw = bedwars ?: return
    if (bw.time - bw.warnTimer < 2f && bw.warnTimer > 0f) return
    bw.warnTimer = bw.time
    uiEvents.add("toast:$text")
}

// ---------------------------------------------------------------- the shop

private class ShopEntry(val resource: String, val price: Int, val item: Int, val count: Int, val extra: Pair<Int, Int>? = null)

private fun shopEntries(): List<ShopEntry> = listOf(
    ShopEntry("Iron Ingot", 4, BwTeam.RED.wool, 16),
    ShopEntry("Gold Ingot", 4, Blocks.PLANKS, 8),
    ShopEntry("Iron Ingot", 10, Items.find("Stone Sword"), 1),
    ShopEntry("Gold Ingot", 7, Items.find("Iron Sword"), 1),
    ShopEntry("Diamond", 4, Items.find("Diamond Sword"), 1),
    ShopEntry("Iron Ingot", 24, Items.find("Iron Chestplate"), 1, Items.find("Iron Leggings") to 1),
    ShopEntry("Gold Ingot", 12, Items.find("Bow"), 1, Items.find("Arrow") to 8),
    ShopEntry("Gold Ingot", 3, Items.find("Golden Apple"), 1),
    ShopEntry("Emerald", 4, Items.find("Ender Pearl"), 1),
)

/** Tapping a shop block buys what the block sells, paying with iron, gold, diamonds or emeralds from your bag. */
internal fun Game.bedwarsShopTap(t: RayHit?): Boolean {
    if (t == null || t.block != Blocks.BW_SHOP) return false
    val e = shopEntries().getOrNull(world.getMeta(t.x, t.y, t.z)) ?: return true
    val pay = Items.find(e.resource)
    if (inventory.count(pay) < e.price) {
        uiEvents.add("toast:Not enough ${Items.displayName(pay)}: you need ${e.price}")
        sound("hit_stone", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f, 0.5f)
        return true
    }
    inventory.remove(pay, e.price)
    for ((id, n) in listOfNotNull(e.item to e.count, e.extra)) {
        val left = inventory.add(id, n)
        if (left > 0) drops.spawn(ItemStack(id, left), player.x, player.y + 1f, player.z)
    }
    sound("ding", t.x + 0.5f, t.y + 0.5f, t.z + 0.5f, 0.8f)
    uiEvents.add("toast:Bought ${Items.displayName(e.item)}${if (e.count > 1) " x${e.count}" else ""}")
    return true
}

// ---------------------------------------------------------------- the bots

private fun Game.solidAt(x: Float, y: Int, z: Float) = Blocks.solid[world.getBlock(floorInt(x), y, floorInt(z))]

/**
 * Gravity, knock-back and walking for a bot: it steps up a single block, slides along walls (so it can walk round
 * the end of the shop row) and stops at anything taller. Returns true when it could not move at all.
 */
private fun Game.botPhysics(m: Mob, dt: Float, wx: Float, wz: Float, onGround: Boolean): Boolean {
    m.vx *= 1f - min(1f, 5f * dt); m.vz *= 1f - min(1f, 5f * dt)
    val nx = m.x + (wx + m.vx) * dt; val nz = m.z + (wz + m.vz) * dt
    val fy = floorInt(m.y + 0.05f)
    val startX = m.x; val startZ = m.z
    fun free(x: Float, z: Float) = !solidAt(x, fy, z) && !solidAt(x, fy + 1, z)
    var blocked = false
    when {
        free(nx, nz) -> { m.x = nx; m.z = nz }
        onGround && solidAt(nx, fy, nz) && !solidAt(nx, fy + 1, nz) && !solidAt(nx, fy + 2, nz) && !solidAt(m.x, fy + 2, m.z) -> { m.y = fy + 1f; m.x = nx; m.z = nz }
        free(nx, m.z) -> m.x = nx
        free(m.x, nz) -> m.z = nz
        else -> blocked = true
    }
    // Trying to walk but hardly getting anywhere: something is in the way.
    val want = hypot(wx, wz)
    if (want > 0f && hypot(m.x - startX, m.z - startZ) < 0.25f * want * dt) blocked = true
    m.vy -= 28f * dt
    var ny = m.y + m.vy * dt
    m.onGround = false
    if (m.vy <= 0f) {
        val cell = floorInt(ny)
        if (solidAt(m.x, cell, m.z)) { ny = cell + 1f; m.vy = 0f; m.onGround = true }
    } else if (solidAt(m.x, floorInt(ny + 1.85f), m.z)) { m.vy = 0f; ny = m.y }
    m.y = ny
    return blocked
}

private class Foe(val x: Float, val y: Float, val z: Float, val bot: BwBot?)

private fun Game.nearestFoe(b: BwBot, m: Mob, radius: Float): Foe? {
    val bw = bedwars ?: return null
    var best: Foe? = null; var bd = radius * radius
    if (b.team != BwTeam.RED && !bw.playerOut && playerAlive && abs(player.y - m.y) < 4f) {
        val d = (player.x - m.x) * (player.x - m.x) + (player.z - m.z) * (player.z - m.z)
        if (d < bd) { bd = d; best = Foe(player.x, player.y, player.z, null) }
    }
    for (o in bw.bots) {
        if (o.team == b.team || o.out) continue
        val om = o.mob ?: continue
        if (om.dead || abs(om.y - m.y) > 4f) continue
        val d = (om.x - m.x) * (om.x - m.x) + (om.z - m.z) * (om.z - m.z)
        if (d < bd) { bd = d; best = Foe(om.x, om.y, om.z, o) }
    }
    return best
}

private fun Game.updateBot(b: BwBot, bw: Bedwars, dt: Float) {
    if (b.out) return
    val lv = bw.level
    var m = b.mob
    if (m == null) {
        b.respawn -= dt
        if (b.respawn <= 0f) spawnBot(b)
        return
    }
    if (m.dead) {
        m.deathTime += dt
        if (m.deathTime > 0.8f) {
            mobs.list.remove(m); b.mob = null
            b.sword = max(0, b.sword - 1); b.armor = 0; b.blocks = min(b.blocks, 8)
            if (bw.bedAlive[b.team.ordinal]) b.respawn = 5f else b.out = true
        }
        return
    }
    world.ensureLoaded(floorInt(m.x) shr 4, floorInt(m.z) shr 4)
    if (m.y < 40f) { mobs.damage(m, 999f, 0f, 0f); return }
    m.hurtTime = max(0f, m.hurtTime - dt)
    b.hitCooldown -= dt; b.placeCooldown -= dt; b.buyTimer -= dt
    val home = b.team
    if (b.buyTimer <= 0f) { b.buyTimer = 1.5f; botShop(b, m) }

    // What to do now: fight, defend, wait at home, run an errand for diamonds, or go and break a bed.
    val fightRadius = if (b.defender) min(lv.sight, 9f) else lv.sight
    val foe = nearestFoe(b, m, fightRadius)
    // A raider that has reached the bed it came for keeps breaking it, whoever is hitting it.
    val tgt0 = b.target
    val atBed = !b.defender && tgt0 != null && bw.bedAlive[tgt0.ordinal] && bw.time >= lv.delay &&
        hypot(tgt0.bedX + 0.5f - m.x, tgt0.bedZ + 0.5f - m.z) < 3.6f && abs(m.y - SURF) < 3f
    var goalX = m.x; var goalZ = m.z; var moving = false
    var attacking = false
    if (foe != null && !atBed && (!b.defender || hypot(foe.x - home.cx, foe.z - home.cz) < 13f)) {
        goalX = foe.x; goalZ = foe.z; moving = true; b.why = "fight"
        val d = hypot(foe.x - m.x, foe.z - m.z)
        if (d < 2.4f && abs(foe.y - m.y) < 2.5f) {
            moving = false; attacking = true
            m.yaw = atan2(foe.x - m.x, -(foe.z - m.z))
            if (b.hitCooldown <= 0f) {
                b.hitCooldown = lv.hitCooldown
                val dmg = (4 + b.sword) * lv.damage
                if (foe.bot == null) { hurtPlayer(dmg, m.x, m.z); sound("hurt", m.x, m.y + 1f, m.z, 0.5f) }
                else foe.bot.mob?.let { fm -> val dx = fm.x - m.x; val dz = fm.z - m.z; val l = hypot(dx, dz).coerceAtLeast(0.1f); mobs.damage(fm, dmg, dx / l, dz / l) }
            }
        }
        b.breakTime = 0f
    } else if (b.defender || bw.time < lv.delay) {
        // Wait at home: wander about near the island's middle.
        b.why = "wait"
        b.wanderTimer -= dt
        if (b.wanderTimer <= 0f) {
            b.wanderTimer = 2f + Math.random().toFloat() * 3f
            b.wanderX = home.cx + home.ox + (Math.random().toFloat() - 0.5f) * 6f; b.wanderZ = home.cz + home.oz + (Math.random().toFloat() - 0.5f) * 6f
        }
        goalX = b.wanderX; goalZ = b.wanderZ; moving = hypot(goalX - m.x, goalZ - m.z) > 0.8f
    } else {
        val errand = b.errand
        if (bw.difficulty == 3 && errand == null && b.diamond < 3f && b.sword < 3 && bw.time > 45f && b.index == 1 && Math.random() < dt * 0.05) {
            // A diamond run: the small island nearest to home.
            val isl = DIAMOND_ISLANDS.minByOrNull { (it.first - home.cx) * (it.first - home.cx) + (it.second - home.cz) * (it.second - home.cz) }!!
            b.errand = intArrayOf(isl.first, isl.second); b.errandTime = 0f
        }
        val er = b.errand
        if (er != null) {
            b.errandTime += dt; b.why = "errand"
            goalX = er[0] + 0.5f; goalZ = er[1] + 0.5f
            val at = hypot(goalX - m.x, goalZ - m.z) < 3f
            moving = !at
            if (b.diamond >= 3f || b.errandTime > 100f) b.errand = null
        } else {
            // Go and break a bed: the target team's bed, bridging across the sky.
            var tt = b.target
            if (tt == null || !bw.bedAlive[tt.ordinal]) {
                val alive = BwTeam.values().filter { it != home && bw.bedAlive[it.ordinal] }
                tt = when {
                    alive.isEmpty() -> null
                    bw.difficulty == 3 && BwTeam.RED in alive && home != BwTeam.RED -> BwTeam.RED       // Hard goes for you first
                    bw.difficulty == 1 -> alive[(Math.random() * alive.size).toInt()]
                    else -> alive.minByOrNull { (it.bedX - m.x) * (it.bedX - m.x) + (it.bedZ - m.z) * (it.bedZ - m.z) }
                }
                b.target = tt; b.breakTime = 0f
            }
            if (tt != null && (b.blocks >= 32 || hypot(tt.bedX - m.x, tt.bedZ - m.z) < 12f || bw.time > lv.delay + 90f && b.blocks >= 8 || b.blocks >= 8 && hypot(m.x - home.cx, m.z - home.cz) > 14f)) {
                goalX = tt.bedX + 0.5f; goalZ = tt.bedZ + 0.5f; b.why = "tobed"
                val d = hypot(goalX - m.x, goalZ - m.z)
                if (d < 3.2f && abs(m.y - SURF) < 3f) {
                    // At the bed: break it.
                    moving = false; attacking = true; b.why = "breaking"
                    m.yaw = atan2(goalX - m.x, -(goalZ - m.z))
                    b.breakTime += dt
                    if (b.breakTime >= lv.breakTime) { destroyBed(tt, "${home.label} bot"); b.target = null }
                } else moving = true
            } else { b.why = "gohome"; goalX = home.cx.toFloat(); goalZ = home.cz.toFloat(); moving = hypot(goalX - m.x, goalZ - m.z) > 3f }
        }
    }

    // Walk to the goal, bridging over gaps with wool. Where the ground is not solid all round, it steps along one axis
    // at a time (like a person bridging), so every step has a block under it and it never cuts a corner into the sky.
    var wx = 0f; var wz = 0f
    m.moving = false
    if (moving && m.onGround) {
        val dx = goalX - m.x; val dz = goalZ - m.z
        val l = hypot(dx, dz).coerceAtLeast(0.01f)
        val fy = floorInt(m.y + 0.05f)
        fun walkable(x: Float, z: Float) = solidAt(x, fy - 1, z) || solidAt(x, fy - 2, z)
        var ux = dx / l; var uz = dz / l
        // Pushing against something it cannot get past: go sideways for a moment to get round it.
        if (b.detour > 0f) { b.detour -= dt; val px = -uz * b.detourSign; val pz = ux * b.detourSign; ux = px; uz = pz }
        val diagonalOk = walkable(m.x + ux * 0.8f, m.z + uz * 0.8f) && walkable(m.x + ux * 0.8f, m.z) && walkable(m.x, m.z + uz * 0.8f) && walkable(m.x + ux * 1.4f, m.z + uz * 1.4f)
        if (!diagonalOk) {
            if (abs(dx) >= abs(dz)) { ux = if (dx > 0) 1f else -1f; uz = 0f } else { ux = 0f; uz = if (dz > 0) 1f else -1f }
        }
        if (walkable(m.x + ux * 0.8f, m.z + uz * 0.8f)) {
            wx = ux * lv.speed; wz = uz * lv.speed
            // Stay in the middle of the row of blocks while walking along one axis.
            if (ux == 0f) wx = (floorInt(m.x) + 0.5f - m.x) * 4f else if (uz == 0f) wz = (floorInt(m.z) + 0.5f - m.z) * 4f
        } else if (b.blocks > 0 && b.placeCooldown <= 0f) {
            // A gap: put a block down in front, then step onto it.
            val bx = floorInt(m.x + ux * 0.8f); val bz = floorInt(m.z + uz * 0.8f)
            setBlock(bx, min(fy - 1, SURF - 1), bz, home.wool)
            b.blocks--; b.placeCooldown = lv.bridge
            sound("hit_wood", bx + 0.5f, fy.toFloat(), bz + 0.5f, 0.4f)
        }
        m.yaw = atan2(ux, -uz)
        m.moving = wx != 0f || wz != 0f
    }
    val blocked = botPhysics(m, dt, wx, wz, m.onGround)
    b.blockedFor = if (blocked && m.moving) b.blockedFor + dt else 0f
    if (b.blockedFor > 0.4f && b.detour <= 0f) { b.detour = 1.2f; b.detourSign = if (Math.random() < 0.5) 1f else -1f; b.blockedFor = 0f }
    if (m.moving) m.walkPhase += dt * 9f
    // Not getting anywhere (stuck behind something): pick another bit of the plan next time.
    b.stuck = if (m.moving && hypot(m.x - b.lastX, m.z - b.lastZ) < 0.3f * dt) b.stuck + dt else 0f
    if (b.stuck > 4f) { b.stuck = 0f; b.target = null; b.errand = null; b.wanderTimer = 0f }
    b.lastX = m.x; b.lastZ = m.z
}

/** The bot spends what it has collected: blocks for bridging first, then a better sword and armour. */
private fun Game.botShop(b: BwBot, m: Mob) {
    when {
        b.blocks < 40 && b.iron >= 4f -> { b.iron -= 4f; b.blocks += 16 }
        b.sword < 1 && b.iron >= 10f -> { b.iron -= 10f; b.sword = 1 }
        b.sword < 2 && b.gold >= 7f -> { b.gold -= 7f; b.sword = 2 }
        b.armor < 1 && b.iron >= 24f -> { b.iron -= 24f; b.armor = 1; m.health += 6f }
        b.sword < 3 && b.diamond >= 3f -> { b.diamond -= 3f; b.sword = 3 }
        b.armor < 2 && b.diamond >= 6f -> { b.diamond -= 6f; b.armor = 2; m.health += 6f }
        else -> {}
    }
}
