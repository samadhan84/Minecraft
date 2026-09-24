package com.vishucraft.game.render

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.RenderType
import com.vishucraft.game.world.Tiles
import com.vishucraft.game.world.World

/** Vertex layout: x, y, z, u, v, light. */
const val FLOATS_PER_VERTEX = 6

class FloatBuilder(initial: Int = 4096) {
    var data = FloatArray(initial)
    var size = 0

    fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun put(x: Float, y: Float, z: Float, u: Float, v: Float, l: Float) {
        val d = data
        var i = size
        d[i++] = x; d[i++] = y; d[i++] = z; d[i++] = u; d[i++] = v; d[i++] = l
        size = i
    }

    fun toArray(): FloatArray = data.copyOf(size)
    fun quads() = size / (FLOATS_PER_VERTEX * 4)
}

class MeshData(
    val cx: Int, val cz: Int, val version: Int,
    val opaque: FloatArray, val translucent: FloatArray,
)

/**
 * Builds chunk geometry with face culling, smooth sky lighting and per-vertex ambient occlusion.
 * Needs the chunk's 8 neighbours to be loaded.
 */
class ChunkMesher {
    companion object {
        private const val P = Chunk.SIZE + 2 // padded width
        private const val H = Chunk.HEIGHT
        const val TILE_UV = 1f / 16f

        // Face order: +Y, -Y, +Z, -Z, +X, -X
        val NORMALS = arrayOf(
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0), intArrayOf(0, 0, 1),
            intArrayOf(0, 0, -1), intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        )

        // Counter-clockwise corners seen from outside.
        val CORNERS = arrayOf(
            arrayOf(intArrayOf(0, 1, 1), intArrayOf(1, 1, 1), intArrayOf(1, 1, 0), intArrayOf(0, 1, 0)),
            arrayOf(intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(1, 0, 1), intArrayOf(0, 0, 1)),
            arrayOf(intArrayOf(0, 0, 1), intArrayOf(1, 0, 1), intArrayOf(1, 1, 1), intArrayOf(0, 1, 1)),
            arrayOf(intArrayOf(1, 0, 0), intArrayOf(0, 0, 0), intArrayOf(0, 1, 0), intArrayOf(1, 1, 0)),
            arrayOf(intArrayOf(1, 0, 1), intArrayOf(1, 0, 0), intArrayOf(1, 1, 0), intArrayOf(1, 1, 1)),
            arrayOf(intArrayOf(0, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 1, 1), intArrayOf(0, 1, 0)),
        )
        val UVS = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
        val FACE_SHADE = floatArrayOf(1.0f, 0.5f, 0.8f, 0.8f, 0.65f, 0.65f)
        val AO_CURVE = floatArrayOf(0.45f, 0.62f, 0.8f, 1.0f)

        /** For each face and corner: offsets (relative to the block) of side1, side2 and the diagonal cell. */
        val AO_OFFSETS: Array<Array<IntArray>> = Array(6) { f ->
            val n = NORMALS[f]
            val axis = if (n[0] != 0) 0 else if (n[1] != 0) 1 else 2
            val tangents = (0..2).filter { it != axis }
            Array(4) { c ->
                val corner = CORNERS[f][c]
                val s1 = IntArray(3) { n[it] }
                val s2 = IntArray(3) { n[it] }
                val t1 = tangents[0]; val t2 = tangents[1]
                s1[t1] += if (corner[t1] == 1) 1 else -1
                s2[t2] += if (corner[t2] == 1) 1 else -1
                val d = IntArray(3) { n[it] }
                d[t1] = s1[t1]; d[t2] = s2[t2]
                intArrayOf(s1[0], s1[1], s1[2], s2[0], s2[1], s2[2], d[0], d[1], d[2])
            }
        }

        fun tileU(tile: Int) = (tile and 15) * TILE_UV
        fun tileV(tile: Int) = (tile shr 4) * TILE_UV
    }

    private val pad = ByteArray(P * P * H)
    private val topY = IntArray(P * P)
    private val opaqueOut = FloatBuilder(1 shl 16)
    private val transOut = FloatBuilder(1 shl 12)
    private val opaque = Blocks.opaque
    private val blocksLight = Blocks.blocksLight

    private fun pidx(px: Int, y: Int, pz: Int) = (y * P + pz) * P + px

    private fun block(px: Int, y: Int, pz: Int): Int {
        if (y < 0) return Blocks.BEDROCK
        if (y >= H) return Blocks.AIR
        return pad[pidx(px, y, pz)].toInt()
    }

    private fun sky(px: Int, y: Int, pz: Int): Float {
        if (y >= H) return 1f
        if (y < 0) return 0f
        return if (y > topY[pz * P + px]) 1f else 0f
    }

    /** Copies the chunk plus a one-block border from its neighbours. Returns false if a neighbour is missing. */
    private fun gather(world: World, chunk: Chunk): Boolean {
        val grid = arrayOfNulls<Chunk>(9)
        for (dz in -1..1) for (dx in -1..1) {
            grid[(dz + 1) * 3 + dx + 1] = world.getChunk(chunk.cx + dx, chunk.cz + dz) ?: return false
        }
        for (pz in 0 until P) {
            val lzRaw = pz - 1
            val gz = if (lzRaw < 0) 0 else if (lzRaw >= Chunk.SIZE) 2 else 1
            val lz = lzRaw and 15
            for (px in 0 until P) {
                val lxRaw = px - 1
                val gx = if (lxRaw < 0) 0 else if (lxRaw >= Chunk.SIZE) 2 else 1
                val lx = lxRaw and 15
                val src = grid[gz * 3 + gx]!!.blocks
                var top = -1
                for (y in 0 until H) {
                    val b = src[Chunk.index(lx, y, lz)]
                    pad[pidx(px, y, pz)] = b
                    if (blocksLight[b.toInt()]) top = y
                }
                topY[pz * P + px] = top
            }
        }
        return true
    }

    fun build(world: World, chunk: Chunk, version: Int): MeshData? {
        if (!gather(world, chunk)) return null
        opaqueOut.size = 0
        transOut.size = 0

        for (y in 0 until H) for (z in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            val px = x + 1; val pz = z + 1
            val id = pad[pidx(px, y, pz)].toInt()
            if (id == Blocks.AIR) continue
            val def = Blocks[id]
            when (def.render) {
                RenderType.CUBE -> {
                    val out = if (def.translucent) transOut else opaqueOut
                    for (f in 0 until 6) {
                        val n = NORMALS[f]
                        val nb = block(px + n[0], y + n[1], pz + n[2])
                        if (opaque[nb]) continue
                        if (nb == id && def.cullSelf) continue
                        if (def.translucent && nb != Blocks.AIR && Blocks[nb].translucent) continue
                        val tile = when (f) { 0 -> def.top; 1 -> def.bottom; else -> def.side }
                        emitFace(out, px, y, pz, f, tile, def.emissive, 1f)
                    }
                }
                RenderType.LIQUID -> {
                    val above = block(px, y + 1, pz)
                    val surface = above != id
                    for (f in 0 until 6) {
                        val n = NORMALS[f]
                        val nb = block(px + n[0], y + n[1], pz + n[2])
                        if (nb == id || opaque[nb]) continue
                        if (f != 0 && nb != Blocks.AIR && Blocks[nb].translucent) continue
                        emitFace(transOut, px, y, pz, f, def.top, false, if (surface) 0.875f else 1f)
                    }
                }
                RenderType.CROSS -> emitCross(opaqueOut, px, y, pz, def.top)
                RenderType.NONE -> {}
            }
        }
        return MeshData(chunk.cx, chunk.cz, version, opaqueOut.toArray(), transOut.toArray())
    }

    private fun emitFace(out: FloatBuilder, px: Int, y: Int, pz: Int, f: Int, tile: Int, emissive: Boolean, height: Float) {
        val n = NORMALS[f]
        val lx = px + n[0]; val ly = y + n[1]; val lz = pz + n[2]
        val shade = FACE_SHADE[f]
        val corners = CORNERS[f]
        val aoOff = AO_OFFSETS[f]
        val u0 = tileU(tile); val v0 = tileV(tile)
        val baseSky = sky(lx, ly, lz)

        val light = FloatArray(4)
        for (c in 0 until 4) {
            if (emissive) { light[c] = 2f; continue }
            val o = aoOff[c]
            val s1x = px + o[0]; val s1y = y + o[1]; val s1z = pz + o[2]
            val s2x = px + o[3]; val s2y = y + o[4]; val s2z = pz + o[5]
            val dx = px + o[6]; val dy = y + o[7]; val dz = pz + o[8]
            val s1 = opaque[block(s1x, s1y, s1z)]
            val s2 = opaque[block(s2x, s2y, s2z)]
            val d = opaque[block(dx, dy, dz)]
            val ao = if (s1 && s2) 0 else 3 - ((if (s1) 1 else 0) + (if (s2) 1 else 0) + (if (d) 1 else 0))
            var skySum = baseSky
            var cnt = 1
            if (!s1) { skySum += sky(s1x, s1y, s1z); cnt++ }
            if (!s2) { skySum += sky(s2x, s2y, s2z); cnt++ }
            if (!d && !(s1 && s2)) { skySum += sky(dx, dy, dz); cnt++ }
            val s = skySum / cnt
            light[c] = shade * AO_CURVE[ao] * (0.28f + 0.72f * s)
        }

        out.ensure(4 * FLOATS_PER_VERTEX)
        val bx = (px - 1).toFloat(); val bz = (pz - 1).toFloat(); val by = y.toFloat()
        // Rotate the quad so its diagonal follows the AO gradient (avoids anisotropy artefacts).
        val start = if (light[0] + light[2] < light[1] + light[3]) 1 else 0
        for (k in 0 until 4) {
            val c = (start + k) and 3
            val cv = corners[c]
            val uv = UVS[c]
            val vy = if (cv[1] == 1) height else 0f
            val vTex = if (f >= 2 && cv[1] == 1 && height < 1f) 1f - height else uv[1]
            out.put(
                bx + cv[0], by + vy, bz + cv[2],
                u0 + uv[0] * TILE_UV, v0 + vTex * TILE_UV,
                light[c],
            )
        }
    }

    private fun emitCross(out: FloatBuilder, px: Int, y: Int, pz: Int, tile: Int) {
        val l = 0.9f * (0.28f + 0.72f * sky(px, y, pz))
        val x0 = (px - 1) + 0.15f; val x1 = (px - 1) + 0.85f
        val z0 = (pz - 1) + 0.15f; val z1 = (pz - 1) + 0.85f
        val y0 = y.toFloat(); val y1 = y + 1f
        val u0 = tileU(tile); val v0 = tileV(tile)
        val u1 = u0 + TILE_UV; val v1 = v0 + TILE_UV
        out.ensure(16 * FLOATS_PER_VERTEX)
        // Two diagonal planes, each emitted with both windings.
        out.put(x0, y0, z0, u0, v1, l); out.put(x1, y0, z1, u1, v1, l); out.put(x1, y1, z1, u1, v0, l); out.put(x0, y1, z0, u0, v0, l)
        out.put(x1, y0, z1, u1, v1, l); out.put(x0, y0, z0, u0, v1, l); out.put(x0, y1, z0, u0, v0, l); out.put(x1, y1, z1, u1, v0, l)
        out.put(x0, y0, z1, u0, v1, l); out.put(x1, y0, z0, u1, v1, l); out.put(x1, y1, z0, u1, v0, l); out.put(x0, y1, z1, u0, v0, l)
        out.put(x1, y0, z0, u1, v1, l); out.put(x0, y0, z1, u0, v1, l); out.put(x0, y1, z1, u0, v0, l); out.put(x1, y1, z0, u1, v0, l)
    }
}
