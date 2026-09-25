package com.vishucraft.game.render

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.RenderType
import com.vishucraft.game.world.Tiles
import com.vishucraft.game.world.World

/** Vertex layout: x, y, z, u, v, sky light (x shade x AO), block light (torches, lava...). */
const val FLOATS_PER_VERTEX = 7

class FloatBuilder(initial: Int = 4096) {
    var data = FloatArray(initial)
    var size = 0

    fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun put(x: Float, y: Float, z: Float, u: Float, v: Float, l: Float, b: Float = 0f) {
        val d = data
        var i = size
        d[i++] = x; d[i++] = y; d[i++] = z; d[i++] = u; d[i++] = v; d[i++] = l; d[i++] = b
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
        const val TILE_UV = 1f / TextureAtlas.TILES_PER_ROW

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

        /** Darkness floor for places the sky cannot reach. */
        const val CAVE = 0.05f
        private const val LW = Chunk.SIZE * 3 // light region: the chunk and all 8 neighbours

        fun tileU(tile: Int) = (tile % TextureAtlas.TILES_PER_ROW) * TILE_UV
        fun tileV(tile: Int) = (tile / TextureAtlas.TILES_PER_ROW) * TILE_UV
    }

    private val pad = ByteArray(P * P * H)
    private val light = ByteArray(LW * LW * H)
    private var lightQueue = IntArray(4096)
    private val grid = arrayOfNulls<Chunk>(9)
    private val padMeta = ByteArray(P * P * H)
    private val topY = IntArray(P * P)
    private val opaqueOut = FloatBuilder(1 shl 16)
    private val transOut = FloatBuilder(1 shl 12)
    private val opaque = Blocks.opaque
    private val blocksLight = Blocks.blocksLight

    private fun pidx(px: Int, y: Int, pz: Int) = (y * P + pz) * P + px

    private fun block(px: Int, y: Int, pz: Int): Int {
        if (y < 0) return Blocks.BEDROCK
        if (y >= H) return Blocks.AIR
        return pad[pidx(px, y, pz)].toInt() and 0xFF
    }

    private fun sky(px: Int, y: Int, pz: Int): Float {
        if (y >= H) return 1f
        if (y < 0) return 0f
        return if (y > topY[pz * P + px]) 1f else 0f
    }

    /** Copies the chunk plus a one-block border from its neighbours. Returns false if a neighbour is missing. */
    private fun gather(world: World, chunk: Chunk): Boolean {
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
                val srcChunk = grid[gz * 3 + gx]!!
                val src = srcChunk.blocks
                val srcMeta = srcChunk.meta
                var top = -1
                for (y in 0 until H) {
                    val si = Chunk.index(lx, y, lz)
                    val b = src[si]
                    val pi = pidx(px, y, pz)
                    pad[pi] = b
                    padMeta[pi] = srcMeta[si]
                    if (blocksLight[b.toInt() and 0xFF]) top = y
                }
                topY[pz * P + px] = top
            }
        }
        return true
    }

    private fun lidx(lx: Int, y: Int, lz: Int) = (y * LW + lz) * LW + lx

    private fun blockAtL(lx: Int, y: Int, lz: Int): Int =
        grid[(lz shr 4) * 3 + (lx shr 4)]!!.blocks[Chunk.index(lx and 15, y, lz and 15)].toInt() and 0xFF

    /** Flood-fills block light from every emitter in the 3x3 chunk area (light travels at most 14 blocks). */
    private fun computeLight() {
        light.fill(0)
        var qn = 0
        fun push(i: Int) {
            if (qn == lightQueue.size) lightQueue = lightQueue.copyOf(qn * 2)
            lightQueue[qn++] = i
        }
        for (g in 0 until 9) {
            val c = grid[g]!!
            val ox = (g % 3) * 16; val oz = (g / 3) * 16
            val b = c.blocks
            for (i in b.indices) {
                val id = b[i].toInt() and 0xFF
                if (id == 0) continue
                val level = Blocks.lightLevel(id, c.meta[i].toInt() and 0xFF)
                if (level <= 0) continue
                val li = lidx(ox + (i and 15), i shr 8, oz + ((i shr 4) and 15))
                if (level > light[li]) { light[li] = level.toByte(); push(li) }
            }
        }
        var head = 0
        while (head < qn) {
            val li = lightQueue[head++]
            val l = light[li] - 1
            if (l <= 0) continue
            val lx = li % LW; val lz = (li / LW) % LW; val y = li / (LW * LW)
            for (f in 0 until 6) {
                val n = NORMALS[f]
                val nx = lx + n[0]; val ny = y + n[1]; val nz = lz + n[2]
                if (nx < 0 || nz < 0 || nx >= LW || nz >= LW || ny < 0 || ny >= H) continue
                val ni = lidx(nx, ny, nz)
                if (light[ni] >= l) continue
                if (opaque[blockAtL(nx, ny, nz)]) continue
                light[ni] = l.toByte()
                push(ni)
            }
        }
    }

    /** Block light (0..15) at a padded coordinate. */
    private fun blockLight(px: Int, y: Int, pz: Int): Int {
        if (y < 0 || y >= H) return 0
        return light[lidx(px + 15, y, pz + 15)].toInt()
    }

    private fun lightCurve(level: Float): Float {
        val t = level / 15f
        return t * t * (0.6f + 0.4f * t) * 1.05f
    }

    fun build(world: World, chunk: Chunk, version: Int): MeshData? {
        if (!gather(world, chunk)) return null
        computeLight()
        opaqueOut.size = 0
        transOut.size = 0

        for (y in 0 until H) for (z in 0 until Chunk.SIZE) for (x in 0 until Chunk.SIZE) {
            val px = x + 1; val pz = z + 1
            val pi = pidx(px, y, pz)
            val id = pad[pi].toInt() and 0xFF
            if (id == Blocks.AIR) continue
            val meta = padMeta[pi].toInt() and 0xFF
            val def = Blocks[id]
            val emissive = Blocks.isEmissive(id, meta)
            when (def.render) {
                RenderType.CUBE -> {
                    val out = if (def.translucent) transOut else opaqueOut
                    for (f in 0 until 6) {
                        val n = NORMALS[f]
                        val nb = block(px + n[0], y + n[1], pz + n[2])
                        if (opaque[nb]) continue
                        if (nb == id && def.cullSelf) continue
                        if (def.translucent && nb != Blocks.AIR && Blocks[nb].translucent && Blocks[nb].cullSelf && nb == id) continue
                        emitFace(out, px, y, pz, f, Blocks.tile(id, meta, f), emissive, 1f)
                    }
                }
                RenderType.LIQUID -> {
                    val above = block(px, y + 1, pz)
                    val surface = above != id
                    // Flowing liquid gets lower the further it is from its source.
                    val h = if (!surface) 1f else if (meta == 0) 0.875f else ((8 - meta.coerceIn(1, 7)) / 9f).coerceAtLeast(0.12f)
                    val out = if (def.translucent) transOut else opaqueOut
                    for (f in 0 until 6) {
                        val n = NORMALS[f]
                        val nb = block(px + n[0], y + n[1], pz + n[2])
                        if (nb == id || opaque[nb]) continue
                        if (f != 0 && nb != Blocks.AIR && Blocks[nb].translucent) continue
                        emitFace(out, px, y, pz, f, def.top, emissive, h)
                    }
                }
                RenderType.CROSS -> emitCross(opaqueOut, px, y, pz, Blocks.tile(id, meta, 0), emissive)
                RenderType.FLAT -> emitFlat(opaqueOut, px, y, pz, Blocks.tile(id, meta, 0))
                RenderType.BOX -> emitBox(opaqueOut, px, y, pz, def.box!!, id, meta, emissive)
                RenderType.PISTON_HEAD -> {
                    val f = (meta and 7).coerceIn(0, 5)
                    val plate = FloatArray(6); val arm = FloatArray(6)
                    val n = NORMALS[f]
                    for (a in 0..2) {
                        when {
                            n[a] > 0 -> { plate[a] = 0.75f; plate[a + 3] = 1f; arm[a] = -0.25f; arm[a + 3] = 0.75f }
                            n[a] < 0 -> { plate[a] = 0f; plate[a + 3] = 0.25f; arm[a] = 0.25f; arm[a + 3] = 1.25f }
                            else -> { plate[a] = 0f; plate[a + 3] = 1f; arm[a] = 0.375f; arm[a + 3] = 0.625f }
                        }
                    }
                    val frontTile = if (meta and 8 != 0) Tiles.id("piston_sticky_front") else def.top
                    emitBox(opaqueOut, px, y, pz, plate, id, meta, false, frontFace = f, frontTile = frontTile)
                    emitBox(opaqueOut, px, y, pz, arm, id, meta, false, sideTile = def.side)
                }
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
        val bl = FloatArray(4)
        val baseBlock = blockLight(lx, ly, lz).toFloat()
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
            var blockSum = baseBlock
            var cnt = 1
            if (!s1) { skySum += sky(s1x, s1y, s1z); blockSum += blockLight(s1x, s1y, s1z); cnt++ }
            if (!s2) { skySum += sky(s2x, s2y, s2z); blockSum += blockLight(s2x, s2y, s2z); cnt++ }
            if (!d && !(s1 && s2)) { skySum += sky(dx, dy, dz); blockSum += blockLight(dx, dy, dz); cnt++ }
            val s = skySum / cnt
            light[c] = shade * AO_CURVE[ao] * (CAVE + (1f - CAVE) * s)
            bl[c] = shade * AO_CURVE[ao] * lightCurve(blockSum / cnt)
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
                light[c], bl[c],
            )
        }
    }

    private fun emitCross(out: FloatBuilder, px: Int, y: Int, pz: Int, tile: Int, emissive: Boolean) {
        val l = if (emissive) 2f else 0.9f * (CAVE + (1f - CAVE) * sky(px, y, pz))
        val b = 0.9f * lightCurve(blockLight(px, y, pz).toFloat())
        val x0 = (px - 1) + 0.15f; val x1 = (px - 1) + 0.85f
        val z0 = (pz - 1) + 0.15f; val z1 = (pz - 1) + 0.85f
        val y0 = y.toFloat(); val y1 = y + 1f
        val u0 = tileU(tile); val v0 = tileV(tile)
        val u1 = u0 + TILE_UV; val v1 = v0 + TILE_UV
        out.ensure(16 * FLOATS_PER_VERTEX)
        // Two diagonal planes, each emitted with both windings.
        out.put(x0, y0, z0, u0, v1, l, b); out.put(x1, y0, z1, u1, v1, l, b); out.put(x1, y1, z1, u1, v0, l, b); out.put(x0, y1, z0, u0, v0, l, b)
        out.put(x1, y0, z1, u1, v1, l, b); out.put(x0, y0, z0, u0, v1, l, b); out.put(x0, y1, z0, u0, v0, l, b); out.put(x1, y1, z1, u1, v0, l, b)
        out.put(x0, y0, z1, u0, v1, l, b); out.put(x1, y0, z0, u1, v1, l, b); out.put(x1, y1, z0, u1, v0, l, b); out.put(x0, y1, z1, u0, v0, l, b)
        out.put(x1, y0, z0, u1, v1, l, b); out.put(x0, y0, z1, u0, v1, l, b); out.put(x0, y1, z1, u0, v0, l, b); out.put(x1, y1, z0, u1, v0, l, b)
    }

    /** A flat decal lying on top of the block below (redstone dust). */
    private fun emitFlat(out: FloatBuilder, px: Int, y: Int, pz: Int, tile: Int) {
        val l = CAVE + (1f - CAVE) * sky(px, y, pz)
        val b = lightCurve(blockLight(px, y, pz).toFloat())
        val x0 = (px - 1).toFloat(); val z0 = (pz - 1).toFloat(); val yy = y + 1f / 32f
        val u0 = tileU(tile); val v0 = tileV(tile)
        val u1 = u0 + TILE_UV; val v1 = v0 + TILE_UV
        out.ensure(4 * FLOATS_PER_VERTEX)
        out.put(x0, yy, z0 + 1, u0, v1, l, b); out.put(x0 + 1, yy, z0 + 1, u1, v1, l, b)
        out.put(x0 + 1, yy, z0, u1, v0, l, b); out.put(x0, yy, z0, u0, v0, l, b)
    }

    /**
     * An axis-aligned box inside the cell (slabs, buttons, piston heads). Texture coordinates come from the
     * vertex position so partial faces show the matching part of the tile.
     */
    private fun emitBox(
        out: FloatBuilder, px: Int, y: Int, pz: Int, b: FloatArray, id: Int, meta: Int, emissive: Boolean,
        frontFace: Int = -1, frontTile: Int = 0, sideTile: Int = -1,
    ) {
        val bx = (px - 1).toFloat(); val bz = (pz - 1).toFloat(); val by = y.toFloat()
        for (f in 0 until 6) {
            val n = NORMALS[f]
            // Faces flush with the cell boundary can be hidden by an opaque neighbour.
            val onBoundary = when (f) {
                0 -> b[4] >= 1f; 1 -> b[1] <= 0f; 2 -> b[5] >= 1f; 3 -> b[2] <= 0f; 4 -> b[3] >= 1f; else -> b[0] <= 0f
            }
            if (onBoundary && opaque[block(px + n[0], y + n[1], pz + n[2])]) continue
            val tile = when {
                f == frontFace -> frontTile
                sideTile >= 0 -> sideTile
                else -> Blocks.tile(id, meta, f)
            }
            val l = if (emissive) 2f else {
                val s = if (onBoundary) sky(px + n[0], y + n[1], pz + n[2]) else sky(px, y, pz)
                FACE_SHADE[f] * (CAVE + (1f - CAVE) * s)
            }
            val boxLight = FACE_SHADE[f] * lightCurve(
                (if (onBoundary) blockLight(px + n[0], y + n[1], pz + n[2]) else blockLight(px, y, pz)).toFloat())
            val u0 = tileU(tile); val v0 = tileV(tile)
            out.ensure(4 * FLOATS_PER_VERTEX)
            for (cv in CORNERS[f]) {
                val lx = if (cv[0] == 1) b[3] else b[0]
                val ly = if (cv[1] == 1) b[4] else b[1]
                val lz = if (cv[2] == 1) b[5] else b[2]
                val (u, v) = when (f) {
                    0 -> lx to lz
                    1 -> lx to 1 - lz
                    2 -> lx to 1 - ly
                    3 -> 1 - lx to 1 - ly
                    4 -> 1 - lz to 1 - ly
                    else -> lz to 1 - ly
                }
                out.put(bx + lx, by + ly, bz + lz,
                    u0 + u.coerceIn(0f, 1f) * TILE_UV, v0 + v.coerceIn(0f, 1f) * TILE_UV, l, boxLight)
            }
        }
    }
}
