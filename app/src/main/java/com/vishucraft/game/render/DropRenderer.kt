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

    fun draw(shader: Shader, drops: ItemEntities, camX: Float, camZ: Float, maxDist: Float, time: Float,
             carts: com.vishucraft.game.engine.Carts? = null, arrows: com.vishucraft.game.engine.Projectiles? = null) {
        buf.size = 0
        carts?.list?.forEach { c -> cart(c.x, c.y, c.z, c.yaw) }
        arrows?.list?.forEach { a ->
            val tile = when (a.kind) {
                com.vishucraft.game.engine.Projectile.SNOWBALL -> snowballTile
                com.vishucraft.game.engine.Projectile.EGG -> eggTile
                com.vishucraft.game.engine.Projectile.PEARL -> pearlTile
                else -> arrowTile
            }
            sprite(a.x, a.y - 0.2f, a.z, if (a.kind == 0) 0.2f else 0.12f, kotlin.math.atan2(a.vz, a.vx), tile)
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
        val side = com.vishucraft.game.world.Tiles.id("cart_side")
        val floor = com.vishucraft.game.world.Tiles.id("cart_floor")
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
