package com.vishucraft.game.render

import android.opengl.GLES20.*
import com.vishucraft.game.engine.ItemEntities
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items
import com.vishucraft.game.world.RenderType
import kotlin.math.cos
import kotlin.math.sin

/** Spinning, bobbing dropped items: small cubes for blocks, flat sprites for everything else. */
class DropRenderer {
    private val mesh = GpuMesh(GL_DYNAMIC_DRAW)
    private val buf = FloatBuilder(4096)

    fun invalidate() { mesh.invalidate(); coasterMesh.invalidate(); coasterVersion = -1 }

    /** Draws minecarts and flying arrows too (they share this batch). */
    private val arrowTile = Items[Items.find("Arrow")]!!.icon
    private val snowballTile = Items[Items.find("Snowball")]!!.icon
    private val eggTile = Items[Items.find("Egg")]!!.icon
    private val pearlTile = Items[Items.find("Ender Pearl")]!!.icon
    private val potionTile = Items[Items.find("Potion of Healing")]!!.icon
    private val fireTile = Items[Items.find("Fire Charge")]!!.icon

    fun draw(shader: Shader, drops: ItemEntities, camX: Float, camZ: Float, maxDist: Float, time: Float,
             carts: com.vishucraft.game.engine.Carts? = null, arrows: com.vishucraft.game.engine.Projectiles? = null,
             boats: com.vishucraft.game.engine.Boats? = null, aircraft: com.vishucraft.game.engine.Aircrafts? = null,
             vehicles: com.vishucraft.game.engine.Vehicles? = null, world: com.vishucraft.game.world.World? = null,
             fireworks: com.vishucraft.game.engine.Fireworks? = null, coasters: com.vishucraft.game.engine.Coasters? = null) {
        if (coasters != null) drawCoasterTrack(shader, coasters)
        buf.size = 0
        coasters?.list?.forEach { coasterTrain(it) }
        if (fireworks != null) fireworks(fireworks, camX, camZ, maxDist)
        if (world != null) billboards(world, camX, camZ, maxDist)
        carts?.list?.forEach { c ->
            if (c.isTrain) {
                val cab = c.kind == com.vishucraft.game.engine.Cart.METRO || c.kind == com.vishucraft.game.engine.Cart.BULLET || c.kind == com.vishucraft.game.engine.Cart.ENGINE
                // A driving car at the back faces backwards; the one in front has the driver in it.
                val back = cab && c.leader != null
                train(c.x, c.y, c.z, if (back) c.yaw + Math.PI.toFloat() else c.yaw, c.kind, carts.power(c)?.kind ?: c.kind, driver = cab && !back)
            } else if (c.kind == com.vishucraft.game.engine.Cart.COASTER) coasterCar(c.x, c.y, c.z, c.yaw) else cart(c.x, c.y, c.z, c.yaw)
        }
        boats?.list?.forEach { b -> boat(b.x, b.y, b.z, b.yaw) }
        aircraft?.list?.forEach { a -> if (a.isPlane) airplane(a.x, a.y, a.z, a.yaw) else helicopter(a.x, a.y, a.z, a.yaw, a.spin) }
        vehicles?.list?.forEach { v -> when (v.kind) {
            com.vishucraft.game.engine.Vehicle.BUS -> bus(v.x, v.y, v.z, v.yaw)
            com.vishucraft.game.engine.Vehicle.GLASS_CAR -> glassCar(v.x, v.y, v.z, v.yaw)
            com.vishucraft.game.engine.Vehicle.EV_CAR -> evCar(v.x, v.y, v.z, v.yaw, v.color)
            else -> car(v.x, v.y, v.z, v.yaw, v.color)
        } }
        arrows?.list?.forEach { a ->
            val tile = when (a.kind) {
                com.vishucraft.game.engine.Projectile.SNOWBALL -> snowballTile
                com.vishucraft.game.engine.Projectile.EGG -> eggTile
                com.vishucraft.game.engine.Projectile.PEARL -> pearlTile
                com.vishucraft.game.engine.Projectile.POTION -> potionTile
                com.vishucraft.game.engine.Projectile.FIREBALL -> fireTile
                else -> arrowTile
            }
            val arrowLike = a.kind == com.vishucraft.game.engine.Projectile.ARROW || a.kind == com.vishucraft.game.engine.Projectile.THORN
            sprite(a.x, a.y - 0.2f, a.z, if (arrowLike) 0.2f else 0.12f, kotlin.math.atan2(a.vz, a.vx), tile)
        }
        for (e in drops.list) {
            val dx = e.x - camX; val dz = e.z - camZ
            if (dx * dx + dz * dz > maxDist * maxDist) continue
            val spin = e.age * 1.6f
            val bob = sin(e.age * 2.5f) * 0.06f + 0.18f
            val id = e.stack.id
            val block = if (Items.isItem(id)) null else Blocks[id]
            val copies = if (e.stack.count > 1) 2 else 1
            for (c in 0 until copies) {
                val ox = c * 0.08f; val oy = c * 0.06f
                if (block != null && (block.render == RenderType.CUBE || block.render == RenderType.BOX)) {
                    cube(e.x + ox, e.y + bob + oy, e.z + ox, 0.13f, spin, id)
                } else {
                    val tile = Items[id]?.icon ?: Blocks.tile(id, 0, 0)
                    sprite(e.x + ox, e.y + bob + oy, e.z, 0.22f, spin, tile)
                }
            }
        }
        if (buf.size == 0) return
        mesh.upload(buf.data, buf.size)
        glUniform3f(shader.u("uOffset"), 0f, 0f, 0f)
        glDisable(GL_CULL_FACE)
        mesh.draw()
        glEnable(GL_CULL_FACE)
    }

    private fun cube(cx: Float, cy: Float, cz: Float, h: Float, yaw: Float, id: Int) {
        val c = cos(yaw); val s = sin(yaw)
        for (f in 0 until 6) {
            val tile = Blocks.tile(id, 0, f)
            val u0 = ChunkMesher.tileU(tile); val v0 = ChunkMesher.tileV(tile)
            val light = if (Blocks.isEmissive(id, 0)) 2f else ChunkMesher.FACE_SHADE[f]
            buf.ensure(4 * FLOATS_PER_VERTEX)
            for ((k, cv) in ChunkMesher.CORNERS[f].withIndex()) {
                val lx = (cv[0] * 2 - 1) * h; val ly = (cv[1] * 2 - 1) * h; val lz = (cv[2] * 2 - 1) * h
                val uv = ChunkMesher.UVS[k]
                buf.put(cx + lx * c - lz * s, cy + h + ly, cz + lx * s + lz * c,
                    u0 + uv[0] * ChunkMesher.TILE_UV, v0 + uv[1] * ChunkMesher.TILE_UV, light)
            }
        }
    }

    /** An open-topped box on wheels. */
    private fun cart(cx: Float, cy: Float, cz: Float, yaw: Float) {
        val c = cos(yaw); val s = sin(yaw)
        val side = com.vishucraft.game.world.Tiles.CART_SIDE
        val floor = com.vishucraft.game.world.Tiles.CART_FLOOR
        val boxes = listOf(
            floatArrayOf(-0.45f, 0.1f, -0.6f, 0.45f, 0.2f, 0.6f) to floor,
            floatArrayOf(-0.45f, 0.1f, -0.6f, -0.37f, 0.65f, 0.6f) to side,
            floatArrayOf(0.37f, 0.1f, -0.6f, 0.45f, 0.65f, 0.6f) to side,
            floatArrayOf(-0.45f, 0.1f, -0.6f, 0.45f, 0.65f, -0.52f) to side,
            floatArrayOf(-0.45f, 0.1f, 0.52f, 0.45f, 0.65f, 0.6f) to side,
        )
        for ((b, tile) in boxes) for (f in 0 until 6) {
            val u0 = ChunkMesher.tileU(tile); val v0 = ChunkMesher.tileV(tile)
            buf.ensure(4 * FLOATS_PER_VERTEX)
            for ((k, cv) in ChunkMesher.CORNERS[f].withIndex()) {
                val lx = if (cv[0] == 1) b[3] else b[0]; val ly = if (cv[1] == 1) b[4] else b[1]; val lz = if (cv[2] == 1) b[5] else b[2]
                val uv = ChunkMesher.UVS[k]
                buf.put(cx + lx * c - lz * s, cy + ly, cz + lx * s + lz * c,
                    u0 + uv[0] * ChunkMesher.TILE_UV, v0 + uv[1] * ChunkMesher.TILE_UV, ChunkMesher.FACE_SHADE[f])
            }
        }
    }

    private fun tile(name: String) = com.vishucraft.game.world.Tiles.id(name)
    private val engineTiles by lazy { intArrayOf(tile("train_roof"), tile("train_roof"), tile("train_front"), tile("train_front"), tile("train_side"), tile("train_side")) }
    private val coachTiles by lazy { intArrayOf(tile("train_roof"), tile("train_roof"), tile("train_roof"), tile("train_roof"), tile("coach_side"), tile("coach_side")) }
    private val metroTiles by lazy { intArrayOf(tile("metro_roof"), tile("metro_roof"), tile("metro_front"), tile("metro_front"), tile("metro_side"), tile("metro_side")) }
    private val bulletTiles by lazy { intArrayOf(tile("bullet_roof"), tile("bullet_roof"), tile("bullet_front"), tile("bullet_front"), tile("bullet_side"), tile("bullet_side")) }
    private val bulletCoachTiles by lazy { intArrayOf(tile("bullet_roof"), tile("bullet_roof"), tile("bullet_roof"), tile("bullet_roof"), tile("bullet_side"), tile("bullet_side")) }
    private val metroCoachTiles by lazy { intArrayOf(tile("metro_roof"), tile("metro_roof"), tile("metro_roof"), tile("metro_roof"), tile("metro_side"), tile("metro_side")) }
    private val wheelTile by lazy { tile("train_wheel") }

    /**
     * An engine, metro car or coach: a long body with see-through windows (so a rider sitting inside can look
     * out), a floor and wheels. Coaches behind a metro are painted like the metro. Local -Z is the front.
     */
    /** Writing on billboards: each one's text, centred across the wall of billboard blocks it is part of. */
    private fun billboards(world: com.vishucraft.game.world.World, camX: Float, camZ: Float, maxDist: Float) {
        for ((p, e) in world.blockEntities.map) {
            val text = (e as? com.vishucraft.game.world.SignEntity)?.text?.trim()
            if (text.isNullOrEmpty()) continue
            val x = com.vishucraft.game.world.RedstoneIds.x(p); val y = com.vishucraft.game.world.RedstoneIds.y(p); val z = com.vishucraft.game.world.RedstoneIds.z(p)
            val dx = x + 0.5f - camX; val dz = z + 0.5f - camZ
            if (dx * dx + dz * dz > maxDist * maxDist) continue
            if (world.getBlock(x, y, z) != Blocks.BILLBOARD) continue
            val meta = world.getMeta(x, y, z)
            val f = (meta and 7).let { if (it < 2 || it > 5) 2 else it }
            val n = ChunkMesher.NORMALS[f]
            val rx = n[2]; val rz = -n[0]   // to the right, seen from the front
            fun board(k: Int, up: Int) = world.getBlock(x + rx * k, y + up, z + rz * k) == Blocks.BILLBOARD && (world.getMeta(x + rx * k, y + up, z + rz * k) and 7) == (meta and 7)
            var left = 0; while (left < 12 && board(-(left + 1), 0)) left++
            var right = 0; while (right < 12 && board(right + 1, 0)) right++
            var below = 0; while (below < 4 && board(0, -(below + 1))) below++
            var above = 0; while (above < 4 && board(0, above + 1)) above++
            val width = (left + right + 1).toFloat(); val height = (below + above + 1).toFloat()
            // Letters as tall as fits: up to 60% of the board's height, and the whole line within 90% of its width.
            val chars = text.length
            val h = minOf(height * 0.6f, width * 0.9f / (chars * 6f / 7f)).coerceAtMost(1.6f)
            val w = h * 5f / 7f; val adv = h * 6f / 7f
            val total = chars * adv - (adv - w)
            val mid = (right - left) / 2f                 // centre of the board, in blocks to the right
            val yc = y + 0.5f + (above - below) / 2f
            val ox = x + 0.5f + n[0] * 0.505f; val oz = z + 0.5f + n[2] * 0.505f
            val tu = ChunkMesher.TILE_UV
            for ((i, ch) in text.withIndex()) {
                val g = com.vishucraft.game.world.PixelFont.cell(ch) ?: continue
                val tile = tile("font_${g / 4}")
                val cu = ChunkMesher.tileU(tile) + (g % 4 % 2) * 0.5f * tu; val cv = ChunkMesher.tileV(tile) + (g % 4 / 2) * 0.5f * tu
                val u0 = cu + tu / 16f; val u1 = cu + tu * 6f / 16f; val v0 = cv; val v1 = cv + tu * 7f / 16f
                val l = mid - total / 2f + i * adv; val r = l + w
                val top = yc + h / 2f; val bot = yc - h / 2f
                buf.ensure(4 * FLOATS_PER_VERTEX)
                buf.put(ox + rx * l, top, oz + rz * l, u0, v0, 2f)
                buf.put(ox + rx * r, top, oz + rz * r, u1, v0, 2f)
                buf.put(ox + rx * r, bot, oz + rz * r, u1, v1, 2f)
                buf.put(ox + rx * l, bot, oz + rz * l, u0, v1, 2f)
            }
        }
    }

    private fun train(cx: Float, cy: Float, cz: Float, yaw: Float, kind: Int, headKind: Int, driver: Boolean = false) {
        val c = cos(yaw); val s = sin(yaw)
        val bullet = kind == com.vishucraft.game.engine.Cart.BULLET || headKind == com.vishucraft.game.engine.Cart.BULLET
        val metroStyle = bullet || kind == com.vishucraft.game.engine.Cart.METRO || headKind == com.vishucraft.game.engine.Cart.METRO
        val half = when (kind) { com.vishucraft.game.engine.Cart.ENGINE -> 1.35f; com.vishucraft.game.engine.Cart.METRO -> 1.6f; com.vishucraft.game.engine.Cart.BULLET -> 1.9f; else -> 1.4f }
        val w = if (metroStyle) 0.6f else 0.55f
        val top = if (bullet) 2.2f else if (metroStyle) 2.4f else 2.3f
        val body = when {
            kind == com.vishucraft.game.engine.Cart.BULLET -> bulletTiles
            bullet -> bulletCoachTiles
            kind == com.vishucraft.game.engine.Cart.METRO -> metroTiles
            kind == com.vishucraft.game.engine.Cart.ENGINE -> engineTiles
            metroStyle -> metroCoachTiles
            else -> coachTiles
        }
        fun box(b: FloatArray, tiles: IntArray) {
            for (f in 0 until 6) {
                val tile = tiles[f]
                val u0 = ChunkMesher.tileU(tile); val v0 = ChunkMesher.tileV(tile)
                buf.ensure(4 * FLOATS_PER_VERTEX)
                for ((k, cv) in ChunkMesher.CORNERS[f].withIndex()) {
                    val lx = if (cv[0] == 1) b[3] else b[0]; val ly = if (cv[1] == 1) b[4] else b[1]; val lz = if (cv[2] == 1) b[5] else b[2]
                    val uv = ChunkMesher.UVS[k]
                    buf.put(cx + lx * c - lz * s, cy + ly, cz + lx * s + lz * c,
                        u0 + uv[0] * ChunkMesher.TILE_UV, v0 + uv[1] * ChunkMesher.TILE_UV, ChunkMesher.FACE_SHADE[f])
                }
            }
        }
        box(floatArrayOf(-w, 0.3f, -half, w, top, half), body)
        if (kind == com.vishucraft.game.engine.Cart.BULLET) {
            // The long pointed nose, in three steps (the front is towards -Z).
            val nose = IntArray(6) { bulletTiles[0] }.also { it[3] = bulletTiles[3] }
            box(floatArrayOf(-w * 0.9f, 0.35f, -half - 0.6f, w * 0.9f, top * 0.72f, -half), nose)
            box(floatArrayOf(-w * 0.7f, 0.4f, -half - 1.1f, w * 0.7f, top * 0.5f, -half - 0.6f), nose)
            box(floatArrayOf(-w * 0.45f, 0.45f, -half - 1.45f, w * 0.45f, top * 0.32f, -half - 1.1f), IntArray(6) { bulletTiles[0] })
        }
        val floor = body[0]
        box(floatArrayOf(-w + 0.02f, 0.3f, -half + 0.02f, w - 0.02f, 0.4f, half - 0.02f), IntArray(6) { floor })
        if (driver) {
            // The driver at the controls, looking ahead out of the cab window.
            val shirt = IntArray(6) { tile("citizen_shirt") }.also { it[3] = tile("citizen_shirt_front") }
            val head = IntArray(6) { tile("citizen_hair") }.also { it[3] = tile("citizen_face") }
            box(floatArrayOf(-0.38f, 0.4f, -half + 0.35f, -0.02f, 0.9f, -half + 0.75f), IntArray(6) { tile("black_plastic") })   // seat
            box(floatArrayOf(-0.34f, 0.9f, -half + 0.45f, -0.06f, 1.45f, -half + 0.65f), shirt)
            box(floatArrayOf(-0.32f, 1.45f, -half + 0.42f, -0.08f, 1.7f, -half + 0.68f), head)
            box(floatArrayOf(-0.4f, 0.95f, -half + 0.12f, 0.1f, 1.1f, -half + 0.3f), IntArray(6) { tile("black_plastic") })   // controls
        }
        val wheels = IntArray(6) { wheelTile }
        for (zc in floatArrayOf(-half + 0.45f, half - 0.45f)) for (side in floatArrayOf(-1f, 1f)) {
            val x0 = if (side < 0) -w + 0.02f else w - 0.1f
            box(floatArrayOf(x0, 0f, zc - 0.2f, x0 + 0.08f, 0.38f, zc + 0.2f), wheels)
        }
    }

    /** One box of a model: [b] in the model's own space (front towards -Z), [tiles] per face (+Y, -Y, +Z, -Z, +X, -X). */
    private fun part(cx: Float, cy: Float, cz: Float, yaw: Float, b: FloatArray, tiles: IntArray) {
        val c = cos(yaw); val s = sin(yaw)
        for (f in 0 until 6) {
            val tile = tiles[f]
            val u0 = ChunkMesher.tileU(tile); val v0 = ChunkMesher.tileV(tile)
            buf.ensure(4 * FLOATS_PER_VERTEX)
            for ((k, cv) in ChunkMesher.CORNERS[f].withIndex()) {
                val lx = if (cv[0] == 1) b[3] else b[0]; val ly = if (cv[1] == 1) b[4] else b[1]; val lz = if (cv[2] == 1) b[5] else b[2]
                val uv = ChunkMesher.UVS[k]
                buf.put(cx + lx * c - lz * s, cy + ly, cz + lx * s + lz * c,
                    u0 + uv[0] * ChunkMesher.TILE_UV, v0 + uv[1] * ChunkMesher.TILE_UV, ChunkMesher.FACE_SHADE[f])
            }
        }
    }

    private fun all6(name: String) = IntArray(6) { tile(name) }
    private val planeBody by lazy { intArrayOf(tile("plane_wing"), tile("plane_wing"), tile("plane_wing"), tile("plane_front"), tile("plane_body"), tile("plane_body")) }
    private val heliBody by lazy { intArrayOf(tile("heli_body"), tile("heli_body"), tile("heli_body"), tile("heli_front"), tile("heli_body"), tile("heli_body")) }

    /** A passenger jet: long body with windows, wings with engines, a tail and wheels. */
    private fun airplane(cx: Float, cy: Float, cz: Float, yaw: Float) {
        val wing = all6("plane_wing"); val tail = all6("plane_tail"); val tyre = all6("tyre")
        part(cx, cy, cz, yaw, floatArrayOf(-0.7f, 0.6f, -4f, 0.7f, 2.1f, 4f), planeBody)
        part(cx, cy, cz, yaw, floatArrayOf(-0.5f, 0.6f, -4.8f, 0.5f, 1.3f, -4f), wing)                 // nose, below the windscreen
        part(cx, cy, cz, yaw, floatArrayOf(-5f, 1f, -1f, 5f, 1.25f, 1f), wing)                          // wings
        for (side in floatArrayOf(-1f, 1f)) {
            val x0 = if (side < 0) -2.9f else 2.1f
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0.4f, -1.4f, x0 + 0.8f, 1f, 0.2f), all6("rotor"))  // engines
            part(cx, cy, cz, yaw, floatArrayOf(side * 1.2f - 0.15f, 0f, 0.3f, side * 1.2f + 0.15f, 0.6f, 0.7f), tyre)
        }
        part(cx, cy, cz, yaw, floatArrayOf(-0.15f, 0f, -3.2f, 0.15f, 0.6f, -2.8f), tyre)
        part(cx, cy, cz, yaw, floatArrayOf(-2f, 1.9f, 3f, 2f, 2.05f, 4f), wing)                         // tail wings
        part(cx, cy, cz, yaw, floatArrayOf(-0.08f, 2.1f, 2.8f, 0.08f, 3.7f, 4f), tail)                   // tail fin
    }

    /** A car in one of the 16 paint colours: body, cabin with windows all round, lights and four wheels. */
    private fun car(cx: Float, cy: Float, cz: Float, yaw: Float, color: Int) {
        val paint = all6("concrete_" + com.vishucraft.game.world.Blocks.DYES[color and 15])
        val glass = all6("car_window").also { it[0] = paint[0] }
        val tyre = all6("tyre"); val black = all6("black_plastic")
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            val x0 = if (sx < 0) -0.95f else 0.75f; val z0 = sz * 1f - 0.25f
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0f, z0, x0 + 0.2f, 0.5f, z0 + 0.5f), tyre)
        }
        part(cx, cy, cz, yaw, floatArrayOf(-0.9f, 0.25f, -1.5f, 0.9f, 0.95f, 1.5f), paint)
        part(cx, cy, cz, yaw, floatArrayOf(-0.8f, 0.95f, -0.7f, 0.8f, 1.55f, 0.85f), glass)
        part(cx, cy, cz, yaw, floatArrayOf(-0.92f, 0.22f, -1.56f, 0.92f, 0.4f, -1.48f), black)             // bumpers
        part(cx, cy, cz, yaw, floatArrayOf(-0.92f, 0.22f, 1.48f, 0.92f, 0.4f, 1.56f), black)
        for (sx in floatArrayOf(-1f, 1f)) {
            val x0 = if (sx < 0) -0.8f else 0.5f
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0.6f, -1.53f, x0 + 0.3f, 0.8f, -1.5f), all6("lamp_glow"))
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0.6f, 1.5f, x0 + 0.3f, 0.8f, 1.53f), all6("concrete_red"))
        }
    }

    /** The glass car: the same shape as a car, but glass all over on a thin dark frame, with seats inside. */
    /** A roller coaster car: low and red with a yellow nose, two rows of seats and padded lap bars. Local -Z is the front. */
    private fun coasterCar(cx: Float, cy: Float, cz: Float, yaw: Float) {
        val red = all6("concrete_red"); val yellow = all6("concrete_yellow"); val black = all6("black_plastic"); val steel = all6("steel")
        part(cx, cy, cz, yaw, floatArrayOf(-0.5f, 0.1f, -0.75f, 0.5f, 0.3f, 0.75f), steel)          // chassis and wheels
        part(cx, cy, cz, yaw, floatArrayOf(-0.55f, 0.3f, -0.7f, 0.55f, 0.75f, 0.7f), red)           // body
        part(cx, cy, cz, yaw, floatArrayOf(-0.5f, 0.3f, -1f, 0.5f, 0.85f, -0.7f), yellow)           // nose
        for (z0 in floatArrayOf(-0.55f, 0.1f)) {
            part(cx, cy, cz, yaw, floatArrayOf(-0.45f, 0.75f, z0 + 0.4f, 0.45f, 1.25f, z0 + 0.55f), black)   // seat back
            part(cx, cy, cz, yaw, floatArrayOf(-0.45f, 0.95f, z0, 0.45f, 1.05f, z0 + 0.08f), yellow)       // lap bar
        }
    }

    /** An electric car: the car's shape but lower and sleeker, with a lime stripe, a light bar and a charging flap. */
    private fun evCar(cx: Float, cy: Float, cz: Float, yaw: Float, color: Int) {
        val paint = all6("concrete_" + com.vishucraft.game.world.Blocks.DYES[color and 15])
        val glass = all6("car_window").also { it[0] = paint[0] }
        val tyre = all6("tyre"); val black = all6("black_plastic"); val lime = all6("concrete_lime"); val light = all6("sea_lantern")
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            val x0 = if (sx < 0) -0.95f else 0.75f; val z0 = sz * 1f - 0.25f
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0f, z0, x0 + 0.2f, 0.5f, z0 + 0.5f), tyre)
        }
        part(cx, cy, cz, yaw, floatArrayOf(-0.9f, 0.22f, -1.5f, 0.9f, 0.85f, 1.5f), paint)
        part(cx, cy, cz, yaw, floatArrayOf(-0.8f, 0.85f, -0.5f, 0.8f, 1.35f, 0.95f), glass)
        part(cx, cy, cz, yaw, floatArrayOf(-0.93f, 0.4f, -1.5f, 0.93f, 0.52f, 1.5f), lime)              // the green stripe
        part(cx, cy, cz, yaw, floatArrayOf(-0.7f, 0.55f, -1.54f, 0.7f, 0.68f, -1.5f), light)            // front light bar
        part(cx, cy, cz, yaw, floatArrayOf(-0.92f, 0.22f, -1.56f, 0.92f, 0.34f, -1.48f), black)
        part(cx, cy, cz, yaw, floatArrayOf(-0.92f, 0.22f, 1.48f, 0.92f, 0.34f, 1.56f), black)
        part(cx, cy, cz, yaw, floatArrayOf(0.9f, 0.6f, 0.9f, 0.95f, 0.78f, 1.15f), lime)                // the charging flap
    }

    private fun glassCar(cx: Float, cy: Float, cz: Float, yaw: Float) {
        val glass = all6("glass_car"); val tyre = all6("tyre"); val black = all6("black_plastic"); val seat = all6("concrete_red")
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            val x0 = if (sx < 0) -0.95f else 0.75f; val z0 = sz * 1f - 0.25f
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0f, z0, x0 + 0.2f, 0.5f, z0 + 0.5f), tyre)
        }
        part(cx, cy, cz, yaw, floatArrayOf(-0.9f, 0.22f, -1.5f, 0.9f, 0.3f, 1.5f), black)                 // floor
        part(cx, cy, cz, yaw, floatArrayOf(-0.9f, 0.25f, -1.5f, 0.9f, 0.95f, 1.5f), glass)
        part(cx, cy, cz, yaw, floatArrayOf(-0.8f, 0.95f, -0.7f, 0.8f, 1.55f, 0.85f), glass)
        for (sx in floatArrayOf(-0.55f, 0.1f)) part(cx, cy, cz, yaw, floatArrayOf(sx, 0.3f, 0.05f, sx + 0.45f, 0.75f, 0.5f), seat)
        part(cx, cy, cz, yaw, floatArrayOf(-0.55f, 0.55f, -0.55f, -0.15f, 0.95f, -0.5f), black)        // steering wheel
        part(cx, cy, cz, yaw, floatArrayOf(-0.92f, 0.22f, -1.56f, 0.92f, 0.4f, -1.48f), black)
        part(cx, cy, cz, yaw, floatArrayOf(-0.92f, 0.22f, 1.48f, 0.92f, 0.4f, 1.56f), black)
        for (sx in floatArrayOf(-1f, 1f)) {
            val x0 = if (sx < 0) -0.8f else 0.5f
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0.6f, -1.53f, x0 + 0.3f, 0.8f, -1.5f), all6("lamp_glow"))
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0.6f, 1.5f, x0 + 0.3f, 0.8f, 1.53f), all6("concrete_red"))
        }
    }

    /** A yellow city bus: three body sections with a row of windows, a big windscreen and six wheels. */
    private fun bus(cx: Float, cy: Float, cz: Float, yaw: Float) {
        val side = all6("bus_side").also { it[0] = tile("bus_roof"); it[1] = tile("black_plastic") }
        val tyre = all6("tyre")
        for (z0 in floatArrayOf(-3f, -1f, 1f)) {
            val tiles = side.copyOf()
            if (z0 < -2f) tiles[3] = tile("bus_front")
            part(cx, cy, cz, yaw, floatArrayOf(-1.2f, 0.4f, z0, 1.2f, 3f, z0 + 2f), tiles)
        }
        for (x0 in floatArrayOf(-1.25f, 1f)) for (z0 in floatArrayOf(-2.4f, 1.6f, 2.2f))
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0f, z0 - 0.35f, x0 + 0.25f, 0.75f, z0 + 0.35f), tyre)
        part(cx, cy, cz, yaw, floatArrayOf(-0.6f, 2.72f, -3.04f, 0.6f, 2.95f, -3f), all6("lamp_glow"))    // route sign
    }

    /** A helicopter: cabin with big windows, tail boom and fin, skids, and a spinning rotor. */
    private fun helicopter(cx: Float, cy: Float, cz: Float, yaw: Float, spin: Float) {
        val dark = all6("rotor")
        part(cx, cy, cz, yaw, floatArrayOf(-0.9f, 0.3f, -1.6f, 0.9f, 2.2f, 1.6f), heliBody)
        part(cx, cy, cz, yaw, floatArrayOf(-0.2f, 1.4f, 1.6f, 0.2f, 1.8f, 5f), all6("heli_body"))
        part(cx, cy, cz, yaw, floatArrayOf(-0.08f, 1.8f, 4.5f, 0.08f, 2.9f, 5f), all6("heli_body"))
        for (side in floatArrayOf(-1f, 1f)) {
            val x0 = side * 0.8f - 0.1f
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0f, -1.7f, x0 + 0.2f, 0.12f, 1.7f), dark)
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0.12f, -0.8f, x0 + 0.2f, 0.3f, -0.6f), dark)
            part(cx, cy, cz, yaw, floatArrayOf(x0, 0.12f, 0.6f, x0 + 0.2f, 0.3f, 0.8f), dark)
        }
        part(cx, cy, cz, yaw, floatArrayOf(-0.1f, 2.2f, -0.1f, 0.1f, 2.5f, 0.1f), dark)
        // Rotor blades turn with the spin angle.
        part(cx, cy, cz, yaw + spin, floatArrayOf(-4.5f, 2.5f, -0.15f, 4.5f, 2.58f, 0.15f), dark)
        part(cx, cy, cz, yaw + spin, floatArrayOf(-0.15f, 2.5f, -4.5f, 0.15f, 2.58f, 4.5f), dark)
    }

    /** A flat-bottomed rowing boat made of planks. */
    private fun boat(cx: Float, cy: Float, cz: Float, yaw: Float) {
        val c = cos(yaw); val s = sin(yaw)
        val wood = com.vishucraft.game.world.Blocks[com.vishucraft.game.world.Blocks.PLANKS].top
        val boxes = listOf(
            floatArrayOf(-0.6f, 0f, -0.9f, 0.6f, 0.12f, 0.9f),
            floatArrayOf(-0.6f, 0f, -0.9f, -0.5f, 0.45f, 0.9f),
            floatArrayOf(0.5f, 0f, -0.9f, 0.6f, 0.45f, 0.9f),
            floatArrayOf(-0.6f, 0f, -0.9f, 0.6f, 0.45f, -0.8f),
            floatArrayOf(-0.6f, 0f, 0.8f, 0.6f, 0.45f, 0.9f),
        )
        for (b in boxes) for (f in 0 until 6) {
            val u0 = ChunkMesher.tileU(wood); val v0 = ChunkMesher.tileV(wood)
            buf.ensure(4 * FLOATS_PER_VERTEX)
            for ((k, cv) in ChunkMesher.CORNERS[f].withIndex()) {
                val lx = if (cv[0] == 1) b[3] else b[0]; val ly = if (cv[1] == 1) b[4] else b[1]; val lz = if (cv[2] == 1) b[5] else b[2]
                val uv = ChunkMesher.UVS[k]
                buf.put(cx + lx * c - lz * s, cy + ly, cz + lx * s + lz * c,
                    u0 + uv[0] * ChunkMesher.TILE_UV, v0 + uv[1] * ChunkMesher.TILE_UV, ChunkMesher.FACE_SHADE[f])
            }
        }
    }

    // ---------------------------------------------------------------- roller coasters

    private val coasterMesh = GpuMesh(GL_STATIC_DRAW)
    private var coasterVersion = -1
    private val railTile by lazy { tile("coaster_rail") }
    private val spineTile by lazy { tile("coaster_spine") }
    private val supportTile by lazy { tile("coaster_support") }
    private val carTile by lazy { tile("coaster_car") }

    /** The coasters' track and supports never move, so they are built once into their own mesh. */
    private fun drawCoasterTrack(shader: Shader, cs: com.vishucraft.game.engine.Coasters) {
        if (cs.version != coasterVersion || (coasterMesh.quads == 0 && cs.list.isNotEmpty())) {
            coasterVersion = cs.version
            val b = FloatBuilder(65536)
            for (c in cs.list) coasterTrack(b, c)
            coasterMesh.upload(b.data, b.size)
        }
        if (cs.list.isEmpty()) return
        glUniform3f(shader.u("uOffset"), 0f, 0f, 0f)
        glDisable(GL_CULL_FACE)
        coasterMesh.draw()
        glEnable(GL_CULL_FACE)
    }

    /** One quad of the coaster mesh, lit by which way it faces. */
    private fun cquad(b: FloatBuilder, tile: Int, q: Array<FloatArray>, nx: Float, ny: Float, nz: Float) {
        val u0 = ChunkMesher.tileU(tile) + ChunkMesher.TILE_UV * 0.25f; val v0 = ChunkMesher.tileV(tile) + ChunkMesher.TILE_UV * 0.25f
        val d = ChunkMesher.TILE_UV * 0.5f
        val light = 0.62f + 0.38f * (ny * 0.6f + 0.4f + nx * 0.1f + nz * 0.05f).coerceIn(0f, 1f)
        b.ensure(4 * FLOATS_PER_VERTEX)
        val uv = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
        for (k in 0..3) b.put(q[k][0], q[k][1], q[k][2], u0 + uv[k][0] * d, v0 + uv[k][1] * d, light)
    }

    /** A square tube from point a to point b, [h] thick, with sides along (ua, ra) at a and (ub, rb) at b. */
    private fun tube(bld: FloatBuilder, tile: Int, a: FloatArray, b: FloatArray, ua: FloatArray, ra: FloatArray, ub: FloatArray, rb: FloatArray, h: Float) {
        val sides = arrayOf(1f to 1f, -1f to 1f, -1f to -1f, 1f to -1f)
        fun corner(p: FloatArray, uu: FloatArray, rr: FloatArray, su: Float, sr: Float) = FloatArray(3) { p[it] + (uu[it] * su + rr[it] * sr) * h }
        for (k in 0..3) {
            val (su0, sr0) = sides[k]; val (su1, sr1) = sides[(k + 1) % 4]
            val q = arrayOf(corner(a, ua, ra, su0, sr0), corner(a, ua, ra, su1, sr1), corner(b, ub, rb, su1, sr1), corner(b, ub, rb, su0, sr0))
            val nu = (su0 + su1) * 0.5f; val nr = (sr0 + sr1) * 0.5f
            cquad(bld, tile, q, ua[0] * nu + ra[0] * nr, ua[1] * nu + ra[1] * nr, ua[2] * nu + ra[2] * nr)
        }
    }

    /** A box centred at [c] with half-sizes along forward, up and right. */
    private fun obox(bld: FloatBuilder, tile: Int, c: FloatArray, f: FloatArray, u: FloatArray, r: FloatArray, hf: Float, hu: Float, hr: Float) {
        val a = FloatArray(3) { c[it] - f[it] * hf }; val b = FloatArray(3) { c[it] + f[it] * hf }
        val uu = FloatArray(3) { u[it] * hu }; val rr = FloatArray(3) { r[it] * hr }
        tube(bld, tile, a, b, uu, rr, uu, rr, 1f)
        // End caps.
        for (e in arrayOf(a to -1f, b to 1f)) {
            val p = e.first
            val q = arrayOf(floatArrayOf(p[0] + uu[0] + rr[0], p[1] + uu[1] + rr[1], p[2] + uu[2] + rr[2]), floatArrayOf(p[0] + uu[0] - rr[0], p[1] + uu[1] - rr[1], p[2] + uu[2] - rr[2]),
                floatArrayOf(p[0] - uu[0] - rr[0], p[1] - uu[1] - rr[1], p[2] - uu[2] - rr[2]), floatArrayOf(p[0] - uu[0] + rr[0], p[1] - uu[1] + rr[1], p[2] - uu[2] + rr[2]))
            cquad(bld, tile, q, f[0] * e.second, f[1] * e.second, f[2] * e.second)
        }
    }

    /** Two red running rails, a big grey spine under them, ties joining them, and white support columns. */
    private fun coasterTrack(b: FloatBuilder, c: com.vishucraft.game.engine.CoasterTrack) {
        val step = 2   // every half block
        fun at(i: Int, side: Float, down: Float) = floatArrayOf(c.px[i] + c.r[i][0] * side - c.u[i][0] * down, c.py[i] + c.r[i][1] * side - c.u[i][1] * down, c.pz[i] + c.r[i][2] * side - c.u[i][2] * down)
        var i = 0
        while (i < c.n - 1) {
            val j = minOf(i + step, c.n - 1)
            for (side in floatArrayOf(-0.42f, 0.42f)) tube(b, railTile, at(i, side, 0f), at(j, side, 0f), c.u[i], c.r[i], c.u[j], c.r[j], 0.07f)
            tube(b, spineTile, at(i, 0f, 0.36f), at(j, 0f, 0.36f), c.u[i], c.r[i], c.u[j], c.r[j], 0.14f)
            if ((i / step) % 3 == 0) obox(b, spineTile, at(i, 0f, 0.2f), c.f[i], c.u[i], c.r[i], 0.06f, 0.05f, 0.44f)
            i = j
        }
        val up = floatArrayOf(0f, 1f, 0f); val fx = floatArrayOf(1f, 0f, 0f); val fz = floatArrayOf(0f, 0f, 1f)
        for (s in c.supports) {
            val mid = (s[2] + s[3]) / 2f; val half = (s[2] - s[3]) / 2f
            obox(b, supportTile, floatArrayOf(s[0], mid, s[1]), up, fx, fz, half, 0.22f, 0.22f)
            obox(b, supportTile, floatArrayOf(s[0], s[3] + 0.1f, s[1]), up, fx, fz, 0.1f, 0.45f, 0.45f)   // foot
        }
    }

    /** The train: three cars following each other along the track, each tilted with it (upside down in the loop). */
    private fun coasterTrain(c: com.vishucraft.game.engine.CoasterTrack) {
        for (car in 0..2) {
            val at = c.s - car * 2.1f
            val k = c.index(at); val p = c.pos(at)
            val f = c.f[k]; val u = c.u[k]; val r = c.r[k]
            fun off(df: Float, du: Float, dr: Float) = FloatArray(3) { p[it] + f[it] * df + u[it] * du + r[it] * dr }
            val red = carTile; val black = tile("black_plastic"); val yellow = tile("concrete_yellow"); val steel = tile("steel")
            obox(buf, steel, off(0f, 0.12f, 0f), f, u, r, 0.8f, 0.1f, 0.5f)            // chassis on the rails
            obox(buf, red, off(0f, 0.45f, 0f), f, u, r, 0.85f, 0.25f, 0.6f)           // body
            if (car == 0) obox(buf, yellow, off(0.95f, 0.45f, 0f), f, u, r, 0.15f, 0.28f, 0.55f)   // nose
            for (row in floatArrayOf(-0.35f, 0.35f)) {
                obox(buf, black, off(row - 0.25f, 0.95f, 0f), f, u, r, 0.06f, 0.3f, 0.5f)        // seat back
                obox(buf, yellow, off(row + 0.2f, 0.78f, 0f), f, u, r, 0.04f, 0.04f, 0.45f)     // lap bar
            }
        }
    }

    private val smokeTiles by lazy { setOf(tile("spark_smoke"), tile("spark_ash")) }

    /** Diwali fireworks: the lit crackers, and every spark as a tiny glowing cube (smoke and ash do not glow). */
    private fun fireworks(fw: com.vishucraft.game.engine.Fireworks, camX: Float, camZ: Float, maxDist: Float) {
        for (f in fw.lit) {
            if (f.kind == com.vishucraft.game.engine.Cracker.ROCKET && f.age > f.kind.fuse) continue
            val icon = Items[Items.find(f.kind.itemName)]?.icon ?: continue
            sprite(f.x, f.y, f.z, if (f.held) 0.18f else 0.25f, 0.6f, icon)
            sprite(f.x, f.y, f.z, if (f.held) 0.18f else 0.25f, 0.6f + 1.5708f, icon)
        }
        var n = 0
        for (p in fw.sparks) {
            val dx = p.x - camX; val dz = p.z - camZ
            if (dx * dx + dz * dz > maxDist * maxDist) continue
            if (++n > 3000) break
            val smoke = p.tile in smokeTiles
            val h = if (smoke) 0.09f + (2.5f - p.life).coerceIn(0f, 2.5f) * 0.05f else 0.035f
            spark(p.x, p.y, p.z, h, p.tile, if (smoke) 1f else 2f)
        }
    }

    private fun spark(cx: Float, cy: Float, cz: Float, h: Float, tile: Int, light: Float) {
        val u0 = ChunkMesher.tileU(tile) + ChunkMesher.TILE_UV * 0.25f; val v0 = ChunkMesher.tileV(tile) + ChunkMesher.TILE_UV * 0.25f
        val du = ChunkMesher.TILE_UV * 0.5f
        for (f in 0 until 6) {
            buf.ensure(4 * FLOATS_PER_VERTEX)
            for ((k, cv) in ChunkMesher.CORNERS[f].withIndex()) {
                val uv = ChunkMesher.UVS[k]
                buf.put(cx + (cv[0] * 2 - 1) * h, cy + (cv[1] * 2 - 1) * h, cz + (cv[2] * 2 - 1) * h, u0 + uv[0] * du, v0 + uv[1] * du, light)
            }
        }
    }

    private fun sprite(cx: Float, cy: Float, cz: Float, h: Float, yaw: Float, tile: Int) {
        val c = cos(yaw) * h; val s = sin(yaw) * h
        val u0 = ChunkMesher.tileU(tile); val v0 = ChunkMesher.tileV(tile)
        val u1 = u0 + ChunkMesher.TILE_UV; val v1 = v0 + ChunkMesher.TILE_UV
        buf.ensure(4 * FLOATS_PER_VERTEX)
        buf.put(cx - c, cy, cz - s, u0, v1, 1f)
        buf.put(cx + c, cy, cz + s, u1, v1, 1f)
        buf.put(cx + c, cy + 2 * h, cz + s, u1, v0, 1f)
        buf.put(cx - c, cy + 2 * h, cz - s, u0, v0, 1f)
    }
}
