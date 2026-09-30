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

    fun invalidate() = mesh.invalidate()

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
             vehicles: com.vishucraft.game.engine.Vehicles? = null) {
        buf.size = 0
        carts?.list?.forEach { c ->
            if (c.isTrain) train(c.x, c.y, c.z, c.yaw, c.kind, carts.power(c)?.kind ?: c.kind) else cart(c.x, c.y, c.z, c.yaw)
        }
        boats?.list?.forEach { b -> boat(b.x, b.y, b.z, b.yaw) }
        aircraft?.list?.forEach { a -> if (a.isPlane) airplane(a.x, a.y, a.z, a.yaw) else helicopter(a.x, a.y, a.z, a.yaw, a.spin) }
        vehicles?.list?.forEach { v -> if (v.isBus) bus(v.x, v.y, v.z, v.yaw) else car(v.x, v.y, v.z, v.yaw, v.color) }
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
    private val metroCoachTiles by lazy { intArrayOf(tile("metro_roof"), tile("metro_roof"), tile("metro_roof"), tile("metro_roof"), tile("metro_side"), tile("metro_side")) }
    private val wheelTile by lazy { tile("train_wheel") }

    /**
     * An engine, metro car or coach: a long body with see-through windows (so a rider sitting inside can look
     * out), a floor and wheels. Coaches behind a metro are painted like the metro. Local -Z is the front.
     */
    private fun train(cx: Float, cy: Float, cz: Float, yaw: Float, kind: Int, headKind: Int) {
        val c = cos(yaw); val s = sin(yaw)
        val metroStyle = kind == com.vishucraft.game.engine.Cart.METRO || headKind == com.vishucraft.game.engine.Cart.METRO
        val half = when (kind) { com.vishucraft.game.engine.Cart.ENGINE -> 1.35f; com.vishucraft.game.engine.Cart.METRO -> 1.6f; else -> 1.4f }
        val w = if (metroStyle) 0.6f else 0.55f
        val top = if (metroStyle) 2.4f else 2.3f
        val body = when {
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
        val floor = body[0]
        box(floatArrayOf(-w + 0.02f, 0.3f, -half + 0.02f, w - 0.02f, 0.4f, half - 0.02f), IntArray(6) { floor })
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
