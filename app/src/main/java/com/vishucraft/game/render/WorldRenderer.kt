package com.vishucraft.game.render

import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.opengl.Matrix
import com.vishucraft.game.engine.Game
import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Chunk
import com.vishucraft.game.world.Noise
import com.vishucraft.game.world.RenderType
import com.vishucraft.game.world.Tiles
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Owns all GL state for the 3D world: chunk meshes, sky, clouds, selection and break overlay. */
class WorldRenderer(private val game: Game) {
    private class ChunkGpu(val chunk: Chunk) {
        val opaque = GpuMesh()
        val translucent = GpuMesh()
        fun delete() { opaque.delete(); translucent.delete() }
    }

    private val meshes = HashMap<Long, ChunkGpu>()
    private val results = ConcurrentLinkedQueue<MeshData>()
    private val localMesher = ChunkMesher()
    private val workerMesher = object : ThreadLocal<ChunkMesher>() {
        override fun initialValue() = ChunkMesher()
    }
    private var inFlight = 0
    private val inFlightLock = Any()

    private lateinit var blockShader: Shader
    private lateinit var simpleShader: Shader
    private var atlasTex = 0
    private val dynamic = GpuMesh(GL_DYNAMIC_DRAW)
    private val lines = GpuMesh(GL_DYNAMIC_DRAW)
    private val mobRenderer = MobRenderer()
    private val dropRenderer = DropRenderer()
    private val dyn = FloatBuilder(4096)
    private val cloudNoise = Noise(game.world.seed + 999)

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val frustum = Array(6) { FloatArray(4) }
    private val dir = FloatArray(3)
    private var width = 1
    private var height = 1

    var visibleChunks = 0
        private set

    fun onSurfaceCreated() {
        // Any previous context is gone: forget stale GL names and re-mesh everything.
        for (m in meshes.values) { m.opaque.invalidate(); m.translucent.invalidate(); m.chunk.uploadedVersion = -1; m.chunk.requestedVersion = -1 }
        meshes.clear()
        dynamic.invalidate(); lines.invalidate(); mobRenderer.invalidate(); dropRenderer.invalidate()
        QuadIndices.reset()
        for (c in game.world.chunks.values) { c.requestedVersion = -1; c.uploadedVersion = -1; c.meshing = false }

        blockShader = Shader(Shaders.BLOCK_VS, Shaders.BLOCK_FS)
        simpleShader = Shader(Shaders.SIMPLE_VS, Shaders.SIMPLE_FS)
        atlasTex = createAtlasTexture()
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        width = w; height = max(1, h)
        glViewport(0, 0, w, h)
    }

    private fun createAtlasTexture(): Int {
        val bmp = Bitmap.createBitmap(TextureAtlas.pixels, TextureAtlas.SIZE, TextureAtlas.SIZE, Bitmap.Config.ARGB_8888)
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return ids[0]
    }

    // ---------------------------------------------------------------- mesh scheduling

    /** Re-mesh a chunk right away (used for edits next to the player). */
    fun remeshNow(cx: Int, cz: Int) {
        val c = game.world.getChunk(cx, cz) ?: return
        val v = c.version
        val data = localMesher.build(game.world, c, v) ?: return
        c.requestedVersion = v
        upload(data)
    }

    private fun upload(data: MeshData) {
        val chunk = game.world.getChunk(data.cx, data.cz) ?: return
        if (data.version < chunk.uploadedVersion) return
        val key = Chunk.key(data.cx, data.cz)
        var gpu = meshes[key]
        if (gpu != null && gpu.chunk !== chunk) { gpu.delete(); gpu = null }
        if (gpu == null) { gpu = ChunkGpu(chunk); meshes[key] = gpu }
        gpu.opaque.upload(data.opaque)
        gpu.translucent.upload(data.translucent)
        chunk.uploadedVersion = data.version
    }

    private fun scheduleMeshes() {
        // Upload finished background meshes.
        var uploads = 0
        while (uploads < 6) {
            val r = results.poll() ?: break
            val chunk = game.world.getChunk(r.cx, r.cz)
            chunk?.meshing = false
            if (r.version < 0) { chunk?.requestedVersion = -1; continue }
            upload(r)
            uploads++
        }

        // Drop GPU data for unloaded chunks.
        val it = meshes.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (game.world.chunks[e.key] !== e.value.chunk) { e.value.delete(); it.remove() }
        }

        val pcx = game.player.blockX() shr 4
        val pcz = game.player.blockZ() shr 4
        val r = game.renderDistance
        val candidates = ArrayList<Chunk>()
        for (c in game.world.chunks.values) {
            if (abs(c.cx - pcx) > r || abs(c.cz - pcz) > r) continue
            if (c.version == c.requestedVersion || c.meshing) continue
            if (!neighboursLoaded(c)) continue
            candidates.add(c)
        }
        candidates.sortBy { (it.cx - pcx) * (it.cx - pcx) + (it.cz - pcz) * (it.cz - pcz) }
        var sync = 0
        for (c in candidates) {
            val near = abs(c.cx - pcx) <= 1 && abs(c.cz - pcz) <= 1
            if (near && c.uploadedVersion >= 0 && sync < 3) {
                // Edits around the player must show up in the same frame.
                remeshNow(c.cx, c.cz); sync++
                continue
            }
            synchronized(inFlightLock) { if (inFlight >= 4) return }
            val v = c.version
            c.requestedVersion = v
            c.meshing = true
            synchronized(inFlightLock) { inFlight++ }
            game.world.workers.execute {
                try {
                    val data = workerMesher.get()!!.build(game.world, c, v)
                    if (data != null) results.add(data)
                    else results.add(MeshData(c.cx, c.cz, -1, FloatArray(0), FloatArray(0)))
                } finally {
                    synchronized(inFlightLock) { inFlight-- }
                }
            }
        }
    }

    private fun neighboursLoaded(c: Chunk): Boolean {
        for (dz in -1..1) for (dx in -1..1) if (game.world.getChunk(c.cx + dx, c.cz + dz) == null) return false
        return true
    }

    // ---------------------------------------------------------------- frame

    fun draw(bobbing: Float) {
        scheduleMeshes()

        val p = game.player
        val daylight = game.daylight
        val underwater = p.headInWater || p.headInLava
        val sky = skyColor(daylight)
        val dim = game.dimension
        val fog = when {
            p.headInLava -> floatArrayOf(0.8f, 0.25f, 0.02f)
            dim == com.vishucraft.game.world.Dimension.EMBER -> floatArrayOf(0.32f, 0.07f, 0.04f)
            dim == com.vishucraft.game.world.Dimension.SKY -> floatArrayOf(0.12f, 0.08f, 0.2f)
            p.headInWater -> floatArrayOf(0.05f, 0.14f, 0.38f)
            else -> sky
        }
        glClearColor(fog[0], fog[1], fog[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val far = game.renderDistance * 16f + 24f
        Matrix.perspectiveM(proj, 0, game.fov, width.toFloat() / height, 0.05f, 400f)
        p.lookDir(dir)
        val bobY = sin(bobbing * 2f) * 0.04f
        val shake = game.shake * 0.25f
        val ex = p.x + (Math.random().toFloat() - 0.5f) * shake
        val ey = p.eyeY + bobY + (Math.random().toFloat() - 0.5f) * shake
        val ez = p.z + (Math.random().toFloat() - 0.5f) * shake
        Matrix.setLookAtM(view, 0, ex, ey, ez, ex + dir[0], ey + dir[1], ez + dir[2], 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        extractFrustum()

        val fogEnd = when {
            p.headInLava -> 3f
            underwater -> 14f
            dim == com.vishucraft.game.world.Dimension.EMBER -> minOf(far - 20f, 70f)
            else -> (far - 20f) * (1f - 0.3f * game.rain)
        }
        val fogStart = if (underwater) 0f else fogEnd * 0.55f

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, atlasTex)

        if (!underwater && dim == com.vishucraft.game.world.Dimension.OVERWORLD) drawSky(ex, ey, ez, daylight)

        // Opaque + cutout pass.
        blockShader.use()
        glUniformMatrix4fv(blockShader.u("uViewProj"), 1, false, viewProj, 0)
        glUniform3f(blockShader.u("uCamPos"), ex, ey, ez)
        glUniform1i(blockShader.u("uTex"), 0)
        glUniform1f(blockShader.u("uDaylight"), daylight)
        glUniform3f(blockShader.u("uFogColor"), fog[0], fog[1], fog[2])
        glUniform1f(blockShader.u("uFogStart"), fogStart)
        glUniform1f(blockShader.u("uFogEnd"), fogEnd)
        glUniform1f(blockShader.u("uCutout"), 0.5f)
        if (underwater) glUniform3f(blockShader.u("uTint"), 0.55f, 0.7f, 1f)
        else glUniform3f(blockShader.u("uTint"), 1f, 1f, 1f)

        val visible = ArrayList<ChunkGpu>()
        for (m in meshes.values) {
            val bx = m.chunk.cx * 16f; val bz = m.chunk.cz * 16f
            if (!boxVisible(bx, 0f, bz, bx + 16f, Chunk.HEIGHT.toFloat(), bz + 16f)) continue
            visible.add(m)
            if (m.opaque.quads == 0) continue
            glUniform3f(blockShader.u("uOffset"), bx, 0f, bz)
            m.opaque.draw()
        }
        visibleChunks = visible.size

        // Mobs use the same shader (lighting, fog) with a per-mob tint for hurt / fuse flashes.
        glUniform1f(blockShader.u("uCutout"), 0.5f)
        mobRenderer.draw(blockShader, game.mobs, ex, ez, far)
        dropRenderer.draw(blockShader, game.drops, ex, ez, far, game.timeOfDay, game.carts, game.projectiles)
        if (underwater) glUniform3f(blockShader.u("uTint"), 0.55f, 0.7f, 1f)

        drawSelection()
        if (dim == com.vishucraft.game.world.Dimension.OVERWORLD) {
            drawClouds(ex, ez, daylight, far)
            drawWeather(ex, ey, ez)
        }

        // Translucent pass, back to front.
        blockShader.use()
        glUniform1f(blockShader.u("uCutout"), 0.01f)
        glEnable(GL_BLEND)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        visible.sortByDescending { val dx = it.chunk.cx * 16 + 8 - ex; val dz = it.chunk.cz * 16 + 8 - ez; dx * dx + dz * dz }
        for (m in visible) {
            if (m.translucent.quads == 0) continue
            glUniform3f(blockShader.u("uOffset"), m.chunk.cx * 16f, 0f, m.chunk.cz * 16f)
            m.translucent.draw()
        }
        glDepthMask(true)
        glEnable(GL_CULL_FACE)
        glDisable(GL_BLEND)
    }

    private fun skyColor(daylight: Float): FloatArray {
        val day = floatArrayOf(0.53f, 0.74f, 1.0f)
        val night = floatArrayOf(0.02f, 0.03f, 0.08f)
        val out = FloatArray(3) { night[it] + (day[it] - night[it]) * daylight }
        // Overcast: pull the sky towards grey while it rains.
        val grey = floatArrayOf(0.42f, 0.45f, 0.5f)
        for (i in 0..2) out[i] += (grey[i] * daylight + 0.02f - out[i]) * game.rain * 0.75f
        // Warm tint around sunrise and sunset.
        val s = sin(game.timeOfDay * 2 * Math.PI).toFloat()
        val dusk = (1f - abs(s) * 4f).coerceIn(0f, 1f) * 0.6f
        out[0] += (1.0f - out[0]) * dusk * 0.8f
        out[1] += (0.55f - out[1]) * dusk * 0.6f
        out[2] += (0.3f - out[2]) * dusk * 0.6f
        return out
    }

    private fun simpleUniforms(color: FloatArray, ex: Float, ez: Float, fogStart: Float, fogEnd: Float, fog: FloatArray? = null) {
        simpleShader.use()
        glUniformMatrix4fv(simpleShader.u("uViewProj"), 1, false, viewProj, 0)
        glUniform3f(simpleShader.u("uCamPos"), ex, 0f, ez)
        glUniform1i(simpleShader.u("uTex"), 0)
        glUniform4f(simpleShader.u("uColor"), color[0], color[1], color[2], color[3])
        glUniform1f(simpleShader.u("uFogStart"), fogStart)
        glUniform1f(simpleShader.u("uFogEnd"), fogEnd)
        if (fog != null) glUniform3f(simpleShader.u("uFogColor"), fog[0], fog[1], fog[2])
    }

    private fun quad(b: FloatBuilder, cx: Float, cy: Float, cz: Float, ax: FloatArray, ay: FloatArray, tile: Int) {
        val u0 = ChunkMesher.tileU(tile); val v0 = ChunkMesher.tileV(tile)
        val u1 = u0 + ChunkMesher.TILE_UV; val v1 = v0 + ChunkMesher.TILE_UV
        b.ensure(4 * FLOATS_PER_VERTEX)
        b.put(cx - ax[0] - ay[0], cy - ax[1] - ay[1], cz - ax[2] - ay[2], u0, v1, 1f)
        b.put(cx + ax[0] - ay[0], cy + ax[1] - ay[1], cz + ax[2] - ay[2], u1, v1, 1f)
        b.put(cx + ax[0] + ay[0], cy + ax[1] + ay[1], cz + ax[2] + ay[2], u1, v0, 1f)
        b.put(cx - ax[0] + ay[0], cy - ax[1] + ay[1], cz - ax[2] + ay[2], u0, v0, 1f)
    }

    private fun drawSky(ex: Float, ey: Float, ez: Float, daylight: Float) {
        val a = (game.timeOfDay * 2 * Math.PI).toFloat()
        // Sun travels east -> overhead -> west; the moon is opposite.
        val sd = floatArrayOf(cos(a), sin(a), 0.25f)
        val len = sqrt(sd[0] * sd[0] + sd[1] * sd[1] + sd[2] * sd[2])
        for (i in 0..2) sd[i] /= len
        // Two axes perpendicular to the sun direction.
        val ax = floatArrayOf(0f, 0f, 1f).let { cross(sd, it) }.let { normalize(it) }
        val ay = normalize(cross(ax, sd))
        dyn.size = 0
        val d = 150f
        val s = 18f
        quad(dyn, ex + sd[0] * d, ey + sd[1] * d, ez + sd[2] * d, scaleV(ax, s), scaleV(ay, s), Tiles.SUN)
        quad(dyn, ex - sd[0] * d, ey - sd[1] * d, ez - sd[2] * d, scaleV(ax, -s * 0.8f), scaleV(ay, s * 0.8f), Tiles.MOON)
        dynamic.upload(dyn.data, dyn.size)
        simpleUniforms(floatArrayOf(1f, 1f, 1f, 1f), ex, ez, 0f, 0f)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE)
        glEnable(GL_BLEND)
        dynamic.draw()
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
        glEnable(GL_DEPTH_TEST)
    }

    private fun drawClouds(ex: Float, ez: Float, daylight: Float, far: Float) {
        val cell = 12f
        val y = 118f
        val drift = game.timeOfDay * Game.DAY_LENGTH_SECONDS * 0.8f
        val ox = ex + drift
        val gx0 = floor(ox / cell).toInt(); val gz0 = floor(ez / cell).toInt()
        val r = (far / cell).toInt() + 2
        dyn.size = 0
        val white = Tiles.WHITE
        val half = cell / 2
        for (gz in gz0 - r..gz0 + r) for (gx in gx0 - r..gx0 + r) {
            val n = cloudNoise.noise2(gx * 0.19, gz * 0.19) + cloudNoise.noise2(gx * 0.5, gz * 0.5) * 0.3
            if (n < 0.18) continue
            val cx = gx * cell + half - drift
            val cz = gz * cell + half
            quad(dyn, cx, y, cz, floatArrayOf(half, 0f, 0f), floatArrayOf(0f, 0f, -half), white)
        }
        if (dyn.size == 0) return
        dynamic.upload(dyn.data, dyn.size)
        val b = 0.25f + 0.75f * daylight
        simpleUniforms(floatArrayOf(b, b, b * 1.02f, 0.8f), ex, ez, far * 0.8f, far * 1.6f)
        glEnable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glDepthMask(false)
        dynamic.draw()
        glDepthMask(true)
        glEnable(GL_CULL_FACE)
        glDisable(GL_BLEND)
    }

    // ---------------------------------------------------------------- weather

    private val columnTop = IntArray(21 * 21)
    private var columnX = Int.MIN_VALUE
    private var columnZ = Int.MIN_VALUE
    private var columnTimer = 0f
    private var lastWeatherTime = 0L

    /** Rain streaks (or snowflakes in cold biomes) in the columns that can see the sky. */
    private fun drawWeather(ex: Float, ey: Float, ez: Float) {
        val strength = game.rain
        if (strength < 0.02f) return
        val now = System.nanoTime()
        val dt = if (lastWeatherTime == 0L) 0f else (now - lastWeatherTime) / 1e9f
        lastWeatherTime = now
        val cx = kotlin.math.floor(ex).toInt(); val cz = kotlin.math.floor(ez).toInt()
        columnTimer -= dt
        if (cx != columnX || cz != columnZ || columnTimer <= 0f) {
            columnX = cx; columnZ = cz; columnTimer = 0.5f
            for (dz in -10..10) for (dx in -10..10) {
                var y = Chunk.HEIGHT - 1
                while (y > 0 && !Blocks.blocksLight[game.world.getBlock(cx + dx, y, cz + dz)] && !Blocks.isLiquid(game.world.getBlock(cx + dx, y, cz + dz))) y--
                columnTop[(dz + 10) * 21 + dx + 10] = y
            }
        }
        val snow = game.world.generator.biomeAt(cx, cz) == com.vishucraft.game.world.Biome.SNOW
        val t = (System.nanoTime() / 1_000_000L % 100_000L) / 1000f
        val yaw = game.player.yaw
        val side = if (snow) 0.05f else 0.018f
        val ax = floatArrayOf(kotlin.math.cos(yaw) * side, 0f, kotlin.math.sin(yaw) * side)
        dyn.size = 0
        val white = Tiles.WHITE
        val u = ChunkMesher.tileU(white) + 0.01f; val v = ChunkMesher.tileV(white) + 0.01f
        for (dz in -10..10) for (dx in -10..10) {
            val top = columnTop[(dz + 10) * 21 + dx + 10]
            val h = ((cx + dx) * 73856093 xor (cz + dz) * 19349663) and 1023
            val speed = if (snow) 2.2f else 16f
            val drops = if (snow) 2 else 3
            for (k in 0 until drops) {
                val phase = ((h * (k + 1) * 7) and 1023) / 1023f
                val y = ey + 12f - ((t * speed + phase * 24f) % 24f)
                if (y < top + 1) continue
                val len = if (snow) side * 2 else 0.9f
                val sway = if (snow) kotlin.math.sin(t * 1.3f + phase * 6f) * 0.3f else 0f
                val x = cx + dx + ((h shr 3) and 7) / 8f + sway
                val z = cz + dz + ((h shr 6) and 7) / 8f
                dyn.ensure(4 * FLOATS_PER_VERTEX)
                dyn.put(x - ax[0], y, z - ax[2], u, v, 1f); dyn.put(x + ax[0], y, z + ax[2], u, v, 1f)
                dyn.put(x + ax[0], y + len, z + ax[2], u, v, 1f); dyn.put(x - ax[0], y + len, z - ax[2], u, v, 1f)
            }
        }
        if (dyn.size == 0) return
        dynamic.upload(dyn.data, dyn.size)
        val b = 0.35f + 0.65f * game.daylight
        if (snow) simpleUniforms(floatArrayOf(b, b, b, 0.85f * strength), ex, ez, 0f, 0f)
        else simpleUniforms(floatArrayOf(0.55f * b, 0.62f * b, 0.8f * b, 0.5f * strength), ex, ez, 0f, 0f)
        glEnable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glDepthMask(false)
        dynamic.draw()
        glDepthMask(true)
        glEnable(GL_CULL_FACE)
        glDisable(GL_BLEND)
    }

    private fun drawSelection() {
        val t = game.target ?: return
        val def = Blocks[t.block]
        val e = 0.004f
        val x0 = t.x - e; val y0 = t.y - e; val z0 = t.z - e
        val x1 = t.x + 1 + e; val y1 = t.y + 1 + e; val z1 = t.z + 1 + e
        val p = game.player

        // Crack overlay while mining.
        if (game.breakProgress > 0f && def.render == RenderType.CUBE) {
            val stage = (game.breakProgress * 10).toInt().coerceIn(0, 9)
            dyn.size = 0
            val tile = Tiles.CRACK_0 + stage
            val c = floatArrayOf(t.x + 0.5f, t.y + 0.5f, t.z + 0.5f)
            val h = 0.5f + e * 2
            quad(dyn, c[0], c[1] + h, c[2], floatArrayOf(h, 0f, 0f), floatArrayOf(0f, 0f, -h), tile)
            quad(dyn, c[0], c[1] - h, c[2], floatArrayOf(h, 0f, 0f), floatArrayOf(0f, 0f, h), tile)
            quad(dyn, c[0], c[1], c[2] + h, floatArrayOf(h, 0f, 0f), floatArrayOf(0f, h, 0f), tile)
            quad(dyn, c[0], c[1], c[2] - h, floatArrayOf(-h, 0f, 0f), floatArrayOf(0f, h, 0f), tile)
            quad(dyn, c[0] + h, c[1], c[2], floatArrayOf(0f, 0f, -h), floatArrayOf(0f, h, 0f), tile)
            quad(dyn, c[0] - h, c[1], c[2], floatArrayOf(0f, 0f, h), floatArrayOf(0f, h, 0f), tile)
            dynamic.upload(dyn.data, dyn.size)
            simpleUniforms(floatArrayOf(1f, 1f, 1f, 1f), p.x, p.z, 0f, 0f)
            glEnable(GL_BLEND)
            glDisable(GL_CULL_FACE)
            glEnable(GL_POLYGON_OFFSET_FILL)
            glPolygonOffset(-1f, -1f)
            dynamic.draw()
            glDisable(GL_POLYGON_OFFSET_FILL)
            glEnable(GL_CULL_FACE)
            glDisable(GL_BLEND)
        }

        // Outline: 12 edges as a line list.
        dyn.size = 0
        val u = ChunkMesher.tileU(Tiles.WHITE) + 0.01f
        val v = ChunkMesher.tileV(Tiles.WHITE) + 0.01f
        val xs = floatArrayOf(x0, x1); val ys = floatArrayOf(y0, y1); val zs = floatArrayOf(z0, z1)
        dyn.ensure(24 * FLOATS_PER_VERTEX)
        for (a in 0..1) for (b in 0..1) {
            dyn.put(x0, ys[a], zs[b], u, v, 1f); dyn.put(x1, ys[a], zs[b], u, v, 1f)
            dyn.put(xs[a], y0, zs[b], u, v, 1f); dyn.put(xs[a], y1, zs[b], u, v, 1f)
            dyn.put(xs[a], ys[b], z0, u, v, 1f); dyn.put(xs[a], ys[b], z1, u, v, 1f)
        }
        lines.upload(dyn.data, dyn.size)
        simpleUniforms(floatArrayOf(0f, 0f, 0f, 0.7f), p.x, p.z, 0f, 0f)
        glEnable(GL_BLEND)
        glLineWidth(2f)
        lines.drawArrays(GL_LINES, 24)
        glDisable(GL_BLEND)
    }

    // ---------------------------------------------------------------- math

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun normalize(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private fun scaleV(v: FloatArray, s: Float) = floatArrayOf(v[0] * s, v[1] * s, v[2] * s)

    private fun extractFrustum() {
        val m = viewProj // column-major
        fun row(i: Int) = floatArrayOf(m[i], m[4 + i], m[8 + i], m[12 + i])
        val r0 = row(0); val r1 = row(1); val r2 = row(2); val r3 = row(3)
        val planes = arrayOf(
            FloatArray(4) { r3[it] + r0[it] }, FloatArray(4) { r3[it] - r0[it] },
            FloatArray(4) { r3[it] + r1[it] }, FloatArray(4) { r3[it] - r1[it] },
            FloatArray(4) { r3[it] + r2[it] }, FloatArray(4) { r3[it] - r2[it] },
        )
        for (i in 0..5) System.arraycopy(planes[i], 0, frustum[i], 0, 4)
    }

    private fun boxVisible(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        for (pl in frustum) {
            val px = if (pl[0] >= 0) x1 else x0
            val py = if (pl[1] >= 0) y1 else y0
            val pz = if (pl[2] >= 0) z1 else z0
            if (pl[0] * px + pl[1] * py + pl[2] * pz + pl[3] < 0) return false
        }
        return true
    }
}
