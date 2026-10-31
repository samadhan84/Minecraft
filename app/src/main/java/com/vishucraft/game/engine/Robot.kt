package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.Drops
import com.vishucraft.game.world.floorInt
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A little helper robot that hovers along after you. Tap it and type what it should do, in your own words:
 * it picks out what you mean from the words it knows ("follow me", "stay here", "mine", "chop trees",
 * "harvest the farm", "put up torches", "guard me", "build a house", "give me 10 diamonds", "take me to
 * Old Town", "make it day", "dance"...). Whatever it collects goes straight into your inventory.
 */

/** What one robot is doing. */
internal class RobotBrain {
    var task = "follow"
    /** The block it is working on (x, y, z), or null. */
    var target: IntArray? = null
    /** Where it was told to work or stay. */
    var hx = 0f; var hy = 0f; var hz = 0f
    var timer = 0f
    var done = 0
}

/** Moves and works every robot. */
internal fun Game.updateRobots(dt: Float) {
    if (isClient) return
    for (m in mobs.list) {
        if (m.type != MobType.ROBOT || m.dead) continue
        val b = robots.getOrPut(m.uid) { RobotBrain() }
        m.walkPhase += dt * 6f
        m.vx = 0f; m.vy = 0f; m.vz = 0f
        b.timer -= dt
        when (b.task) {
            "stay" -> hover(m, b.hx, b.hy + 0.6f, b.hz, dt)
            "dance" -> { m.yaw += dt * 8f; hover(m, m.x, b.hy + 0.6f + sin(m.walkPhase) * 0.4f, m.z, dt); if (b.timer <= 0f) b.task = "follow" }
            "mine", "chop", "farm" -> work(m, b, dt)
            "guard" -> guard(m, b, dt)
            else -> followPlayer(m, dt)
        }
    }
    robots.keys.retainAll(mobs.list.filter { it.type == MobType.ROBOT }.map { it.uid }.toSet())
}

/** Flies straight towards (x, y, z); returns the distance left. */
private fun hover(m: Mob, x: Float, y: Float, z: Float, dt: Float, speed: Float = 7f): Float {
    val dx = x - m.x; val dy = y - m.y; val dz = z - m.z
    val d = sqrt(dx * dx + dy * dy + dz * dz)
    m.moving = d > 0.3f
    if (d < 0.05f) return d
    val step = minOf(d, speed * dt)
    m.x += dx / d * step; m.y += dy / d * step; m.z += dz / d * step
    if (dx * dx + dz * dz > 0.01f) m.yaw = atan2(dx, -dz)
    return d
}

private fun Game.followPlayer(m: Mob, dt: Float) {
    val p = player
    // Just behind your right shoulder.
    val tx = p.x - sin(p.yaw) * 1.6f + kotlin.math.cos(p.yaw) * 1.0f
    val tz = p.z + kotlin.math.cos(p.yaw) * 1.6f + sin(p.yaw) * 1.0f
    val far = (tx - m.x) * (tx - m.x) + (tz - m.z) * (tz - m.z)
    if (far > 48f * 48f) { m.x = tx; m.y = p.y + 1f; m.z = tz; return }
    hover(m, tx, p.y + 1.1f + sin(m.walkPhase * 0.5f) * 0.15f, tz, dt, if (far > 100f) 14f else 6f)
    if (!m.moving) m.yaw = p.yaw
}

/** Mining ores, chopping trees or harvesting crops near where it was told, one block at a time. */
private fun Game.work(m: Mob, b: RobotBrain, dt: Float) {
    val t = b.target?.takeIf { wanted(b.task, it[0], it[1], it[2]) } ?: findWork(b).also { b.target = it }
    if (t == null) {
        robotSay(when (b.task) { "mine" -> "I can't find any more ores near here"; "chop" -> "No more trees near here"; else -> "Nothing is ripe yet" } +
            if (b.done > 0) " (I got ${b.done} for you)" else "")
        b.task = "follow"; b.done = 0
        return
    }
    val d = hover(m, t[0] + 0.5f, t[1] + 1.2f, t[2] + 0.5f, dt, 9f)
    if (d > 1.6f || b.timer > 0f) return
    b.timer = 0.35f
    val (x, y, z) = t
    val id = world.getBlock(x, y, z)
    if (b.task == "farm") { if (harvest(x, y, z)) b.done++; b.target = null; return }
    val pick = Items.all.firstOrNull { it.name == "Diamond Pickaxe" }
    for ((drop, n) in Drops.forBlock(id, pick, world.getMeta(x, y, z))) give(drop, n)
    setBlock(x, y, z, Blocks.AIR)
    blockSound(id, x, y, z, 0.6f)
    b.done++; b.target = null
    if (b.done >= 64) { robotSay("That's 64 blocks: I'll stop here. Tell me again for more"); b.task = "follow"; b.done = 0 }
}

/** Whether the block at (x, y, z) is what [task] is after. */
private fun Game.wanted(task: String, x: Int, y: Int, z: Int): Boolean {
    val id = world.getBlock(x, y, z)
    if (id == Blocks.AIR) return false
    val name = Blocks[id].name
    return when (task) {
        "mine" -> name.endsWith(" Ore")
        "chop" -> name.endsWith(" Log") || name == "Log"
        else -> Blocks.isCrop(id) && world.getMeta(x, y, z) >= 7
    }
}

/** The nearest block for the task within reach of where it was told. */
private fun Game.findWork(b: RobotBrain): IntArray? {
    val r = if (b.task == "mine") 12 else 16
    val cx = floorInt(b.hx); val cy = floorInt(b.hy); val cz = floorInt(b.hz)
    var best: IntArray? = null; var bestD = Int.MAX_VALUE
    val ys = if (b.task == "mine") (cy - 24).coerceAtLeast(1)..cy + 8 else cy - 6..cy + 16
    for (y in ys) for (z in cz - r..cz + r) for (x in cx - r..cx + r) {
        val d = (x - cx) * (x - cx) + (z - cz) * (z - cz) + (y - cy) * (y - cy)
        if (d >= bestD || !wanted(b.task, x, y, z)) continue
        bestD = d; best = intArrayOf(x, y, z)
    }
    return best
}

/** Fights monsters near the player. */
private fun Game.guard(m: Mob, b: RobotBrain, dt: Float) {
    val foe = mobs.list.filter { it.type.hostile && !it.dead && !it.tamed && (it.x - player.x) * (it.x - player.x) + (it.z - player.z) * (it.z - player.z) < 20f * 20f }
        .minByOrNull { (it.x - m.x) * (it.x - m.x) + (it.z - m.z) * (it.z - m.z) }
    if (foe == null) { followPlayer(m, dt); return }
    val d = hover(m, foe.x, foe.y + 1f, foe.z, dt, 9f)
    if (d < 1.8f && b.timer <= 0f) {
        b.timer = 0.6f
        mobs.damage(foe, 6f, foe.x - m.x, foe.z - m.z)
        sound("hit", foe.x, foe.y + 1f, foe.z, 0.7f)
    }
}

private fun Game.robotSay(text: String) { uiEvents.add("toast:Robot: $text") }

/** Tapping a robot: tell it what to do. */
internal fun Game.robotTap(mob: Mob): Boolean {
    if (mob.type != MobType.ROBOT) return false
    uiEvents.add("robot:${mob.uid}")
    return true
}

/** What the player told robot [uid] to do, in their own words. */
fun Game.robotCommand(uid: Int, said: String) {
    val m = mobs.list.firstOrNull { it.uid == uid && it.type == MobType.ROBOT } ?: return
    val b = robots.getOrPut(uid) { RobotBrain() }
    val text = said.lowercase().trim()
    val words = text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    fun has(vararg w: String) = w.any { k -> if (' ' in k) text.contains(k) else words.any { it == k || it.startsWith(k) } }
    fun here() { b.hx = player.x; b.hy = player.y; b.hz = player.z }
    b.target = null; b.done = 0
    when {
        text.isEmpty() || has("help", "what can") -> robotSay("I can: follow me, stay, come here, mine, chop trees, farm, light, guard, " +
            "build a house (or any blueprint), give me 10 diamonds, fireworks (on Diwali), take me to <station or airport>, make it day / night, stop the rain, call a train, dance")
        has("give", "want", "need") -> giveItems(text, words)
        has("take me", "teleport", "go to") -> takeMeTo(text)
        has("firework", "diwali", "cracker", "patak", "bomb", "phuljhadi", "anar", "chakri", "rocket", "sky shot", "ladi") -> robotFireworks(text)
        has("build", "make a", "make me") && !has("day", "night") -> robotBuild(text)
        has("day", "morning", "sun") -> { timeOfDay = 0.3f; robotSay("Good morning!") }
        has("night", "dark", "moon") -> { timeOfDay = 0.8f; robotSay("Good night!") }
        has("rain", "weather", "clear") -> { weatherEnabled = false; robotSay("Clear skies from now on") }
        has("train", "metro") -> robotCallTrain()
        has("mine", "dig", "ore", "diamond", "iron", "coal", "gold") -> { here(); b.task = "mine"; robotSay("Mining the ores around here!") }
        has("chop", "tree", "wood", "log", "cut") -> { here(); b.task = "chop"; robotSay("Chopping trees!") }
        has("farm", "harvest", "crop", "wheat", "rice") -> { here(); b.task = "farm"; robotSay("Harvesting and planting again!") }
        has("light", "torch", "lamp") -> { robotTorches(); b.task = "follow" }
        has("guard", "protect", "fight", "attack", "kill", "defend") -> { b.task = "guard"; robotSay("I'll keep the monsters away!") }
        has("dance", "party") -> { b.task = "dance"; b.hy = m.y; b.timer = 6f; robotSay("Dance time!") }
        has("stay", "stop", "wait", "sit", "don") -> { b.hx = m.x; b.hy = m.y - 0.6f; b.hz = m.z; b.task = "stay"; robotSay("OK, I'll wait here") }
        has("come", "here") -> { m.x = player.x + 1f; m.y = player.y + 1f; m.z = player.z; b.task = "follow"; robotSay("Here I am!") }
        has("follow", "with me", "go") -> { b.task = "follow"; robotSay("Following you!") }
        has("hello", "hi", "hey", "namaste", "name") -> robotSay("Namaste! I'm your robot. Tell me what to do, or say \"help\"")
        else -> robotSay("Sorry, I don't know how to do that yet. Say \"help\" to see what I can do")
    }
}

private fun Game.giveItems(text: String, words: List<String>) {
    if (survival) { robotSay("I can only give things in creative mode. Try \"mine\" or \"chop trees\""); return }
    val n = words.firstNotNullOfOrNull { it.toIntOrNull() }?.coerceIn(1, 640) ?: 1
    // The block or item whose name fits the words best.
    val wanted = words.filter { it.length > 2 && it.toIntOrNull() == null && it !in setOf("give", "want", "need", "please", "some", "the", "and") }
        .map { it.removeSuffix("es").removeSuffix("s") }
    if (wanted.isEmpty()) { robotSay("What should I give you? Say for example \"give me 10 diamonds\""); return }
    val names = (1 until Blocks.COUNT).map { it to Blocks[it].name } + Items.all.map { it.id to it.name }
    val best = names.maxByOrNull { (_, name) ->
        val nm = name.lowercase()
        wanted.count { nm.contains(it) } * 10 - nm.length / 10 - (if (nm.endsWith(" spawn egg")) 5 else 0)
    }
    if (best == null || wanted.none { best.second.lowercase().contains(it) }) { robotSay("I don't know what \"${wanted.joinToString(" ")}\" is"); return }
    inventory.add(best.first, n)
    robotSay("Here you are: $n ${best.second}")
}

private fun Game.takeMeTo(text: String) {
    val places = metroNet.stations.map { metroStationName(it) to Triple(it[0] + 0.5f, it[1] + 1f, it[2] + 0.5f) } +
        aircraft.airports.map { it.name to Triple(it.x + 3f, it.y + 1f, it.z) }
    val place = places.firstOrNull { text.contains(it.first.lowercase()) }
        ?: places.firstOrNull { (name, _) -> name.lowercase().split(' ').any { it.length > 3 && text.contains(it) } }
    if (place == null) {
        robotSay(if (places.isEmpty()) "There are no stations or airports yet" else "Where to? I know: " + places.joinToString(", ") { it.first })
        return
    }
    val (x, y, z) = place.second
    carts.riding = null; aircraft.riding = null; boats.riding = null; vehicles.riding = null
    world.ensureLoaded(floorInt(x) shr 4, floorInt(z) shr 4)
    player.x = x; player.y = y + 0.1f; player.z = z; player.vx = 0f; player.vy = 0f; player.vz = 0f
    for (r in mobs.list) if (r.type == MobType.ROBOT && robots[r.uid]?.task != "stay") { r.x = x + 1f; r.y = y + 1f; r.z = z }
    robotSay("Welcome to ${place.first}!")
}

private fun Game.robotBuild(text: String) {
    val name = Items.BUILDINGS.firstOrNull { text.contains(it.lowercase()) }
        ?: Items.BUILDINGS.firstOrNull { b -> b.lowercase().split(' ').any { it.length > 3 && text.contains(it) } }
        ?: if (text.contains("house") || text.contains("home")) "Small House" else null
    if (name == null) { robotSay("I can build: " + Items.BUILDINGS.joinToString(", ")); return }
    // On the ground a few steps in front of you.
    val fx = sin(player.yaw); val fz = -kotlin.math.cos(player.yaw)
    val x = floorInt(player.x + fx * 3f); val z = floorInt(player.z + fz * 3f)
    var y = floorInt(player.y) + 2
    while (y > 1 && !Blocks.solid[world.getBlock(x, y, z)]) y--
    if (placeBuilding(RayHit(x, y, z, 0, 1, 0, world.getBlock(x, y, z)), name)) robotSay("Built ${name.lowercase()} for you!")
    else robotSay("I can't build the ${name.lowercase()} here: try somewhere flatter or lower down")
}

private fun Game.robotTorches() {
    var placed = 0
    val px = floorInt(player.x); val py = floorInt(player.y); val pz = floorInt(player.z)
    for (gz in -2..2) for (gx in -2..2) {
        val x = px + gx * 5; val z = pz + gz * 5
        for (y in py + 4 downTo py - 4) {
            if (world.getBlock(x, y, z) == Blocks.AIR && Blocks.solid[world.getBlock(x, y - 1, z)] && Blocks.opaque[world.getBlock(x, y - 1, z)]) {
                setBlock(x, y, z, Blocks.TORCH); placed++; break
            }
        }
    }
    robotSay(if (placed > 0) "Put up $placed torches around you" else "There's nowhere to put torches here")
}

private fun Game.robotCallTrain() {
    val s = metroNet.stations.minByOrNull { (it[0] - player.x) * (it[0] - player.x) + (it[2] - player.z) * (it[2] - player.z) }
    if (s == null) { robotSay("There are no stations yet. Build a metro or railway station first"); return }
    val d = sqrt((s[0] - player.x) * (s[0] - player.x) + (s[2] - player.z) * (s[2] - player.z))
    if (d > 40f) { robotSay("The nearest station is ${metroStationName(s)}, ${d.toInt()} blocks away. Say \"take me to ${metroStationName(s)}\""); return }
    callTrain(if (s[3] == TrackEnd.RAILWAY) "Railway Train" else "Metro", s[0], s[1], s[2])
}

/**
 * "Fireworks!": the robot sets up a Diwali show a few blocks in front of you and lights it, one cracker after
 * another (just the one you name, like "light a sutli bomb", or all of them). Only until 13 November.
 */
internal fun Game.robotFireworks(text: String) {
    if (!diwaliTime()) { robotSay("Diwali is over: the fireworks were only until 13 November. See you next Diwali!"); return }
    val named = Cracker.values().filter { k -> k.itemName.lowercase().split(" ", "(", ")").filter { it.length > 3 && it != "bomb" && it != "wala" && it != "diwali" }.any { text.contains(it) } }
    val show = (named.ifEmpty { Cracker.values().filter { it != Cracker.PHULJHADI } }).filter { it != Cracker.PHULJHADI || named.size == 1 }
    if (show == listOf(Cracker.PHULJHADI)) { fireworks.light(Cracker.PHULJHADI, player.x, player.y + 1.1f, player.z, held = true); robotSay("Here's a Phuljhadi for you. Happy Diwali!"); return }
    val fx = sin(player.yaw); val fz = -kotlin.math.cos(player.yaw)
    for ((k, kind) in show.withIndex()) {
        // In a row across in front of you, 7 blocks away, each one lit 1.5 seconds after the last.
        val side = (k - (show.size - 1) / 2f) * 2f
        val x = floorInt(player.x + fx * 7f - fz * side); val z = floorInt(player.z + fz * 7f + fx * side)
        var y = floorInt(player.y) + 6
        while (y > floorInt(player.y) - 8 && !Blocks.solid[world.getBlock(x, y - 1, z)]) y--
        fireworks.light(kind, x + 0.5f, y.toFloat(), z + 0.5f)
        fireworks.lit.last().age = -k * 1.5f
    }
    robotSay(if (show.size == 1) "Lighting the ${show[0].itemName}! Stand back!" else "Diwali fireworks show! Stand back and watch. Happy Diwali!")
}
