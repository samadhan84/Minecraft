package com.vishucraft.game.render

import android.opengl.GLES30.*
import com.vishucraft.game.engine.Mob
import com.vishucraft.game.engine.MobType
import com.vishucraft.game.engine.Mobs
import com.vishucraft.game.world.Tiles
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Blocky box models for mobs, animated on the CPU and drawn with the block shader. */
object MobModels {
    /** A box in model space (feet at origin, facing -Z). swing: 0 none, 1/-1 legs in opposite phase, 2 arms forward. */
    class Part(val b: FloatArray, val pivotY: Float, val pivotZ: Float, val swing: Int, val tiles: IntArray)

    private fun t(n: String) = Tiles.id(n)

    /** Tiles in face order +Y, -Y, +Z(back), -Z(front), +X, -X. */
    private fun tiles(all: String, front: String = all, top: String = all, back: String = all) =
        intArrayOf(t(top), t(all), t(back), t(front), t(all), t(all))

    private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, tiles: IntArray,
                    swing: Int = 0, pivotY: Float = y1, pivotZ: Float = (z0 + z1) / 2) =
        Part(floatArrayOf(x0, y0, z0, x1, y1, z1), pivotY, pivotZ, swing, tiles)

    private fun legs(halfSpanX: Float, halfSpanZ: Float, size: Float, height: Float, tile: String): List<Part> {
        val out = ArrayList<Part>()
        for ((sx, sz) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
            val cx = sx * halfSpanX; val cz = sz * halfSpanZ
            val phase = if (sx == sz) 1 else -1
            out.add(box(cx - size / 2, 0f, cz - size / 2, cx + size / 2, height, cz + size / 2, tiles(tile), phase))
        }
        return out
    }

    val models: Map<MobType, List<Part>> = mapOf(
        MobType.COW to listOf(
            box(-0.45f, 0.6f, -0.65f, 0.45f, 1.35f, 0.65f, tiles("cow_hide")),
            box(-0.28f, 1.0f, -1.05f, 0.28f, 1.5f, -0.65f, tiles("cow_hide", front = "cow_face")),
            box(-0.32f, 1.45f, -0.95f, -0.2f, 1.6f, -0.85f, tiles("horn")),
            box(0.2f, 1.45f, -0.95f, 0.32f, 1.6f, -0.85f, tiles("horn")),
        ) + legs(0.25f, 0.45f, 0.22f, 0.6f, "cow_leg"),
        MobType.PIG to listOf(
            box(-0.35f, 0.4f, -0.5f, 0.35f, 0.95f, 0.5f, tiles("pig_skin")),
            box(-0.28f, 0.55f, -0.85f, 0.28f, 1.05f, -0.5f, tiles("pig_skin", front = "pig_face")),
        ) + legs(0.2f, 0.32f, 0.2f, 0.4f, "pig_leg"),
        MobType.SHEEP to listOf(
            box(-0.38f, 0.55f, -0.55f, 0.38f, 1.2f, 0.55f, tiles("sheep_wool")),
            box(-0.2f, 0.95f, -0.85f, 0.2f, 1.4f, -0.5f, tiles("sheep_wool", front = "sheep_face")),
        ) + legs(0.2f, 0.35f, 0.18f, 0.6f, "sheep_leg"),
        MobType.ZOMBIE to listOf(
            box(-0.25f, 0f, -0.125f, 0f, 0.75f, 0.125f, tiles("zombie_pants"), 1),
            box(0f, 0f, -0.125f, 0.25f, 0.75f, 0.125f, tiles("zombie_pants"), -1),
            box(-0.25f, 0.75f, -0.14f, 0.25f, 1.45f, 0.14f, tiles("zombie_shirt")),
            box(-0.25f, 1.45f, -0.25f, 0.25f, 1.95f, 0.25f, tiles("zombie_skin", front = "zombie_face", top = "zombie_hair", back = "zombie_hair")),
            box(-0.5f, 0.75f, -0.125f, -0.25f, 1.45f, 0.125f, tiles("zombie_skin", top = "zombie_shirt"), 2, pivotY = 1.35f),
            box(0.25f, 0.75f, -0.125f, 0.5f, 1.45f, 0.125f, tiles("zombie_skin", top = "zombie_shirt"), 2, pivotY = 1.35f),
        ),
        MobType.BOOMLING to listOf(
            box(-0.4f, 0.35f, -0.4f, 0.4f, 1.1f, 0.4f, tiles("boomling_shell", front = "boomling_face")),
            box(-0.3f, 1.1f, -0.3f, 0.3f, 1.2f, 0.3f, tiles("boomling_shell")),
            box(-0.05f, 1.2f, -0.05f, 0.05f, 1.45f, 0.05f, tiles("boomling_fuse")),
        ) + legs(0.22f, 0.22f, 0.25f, 0.35f, "boomling_leg"),
    )
}

class MobRenderer {
    private val mesh = GpuMesh(GL_DYNAMIC_DRAW)
    private val buf = FloatBuilder(4096)

    fun invalidate() = mesh.invalidate()

    /** Expects the block shader to be bound with the frame's uniforms. */
    fun draw(shader: Shader, mobs: Mobs, camX: Float, camZ: Float, maxDist: Float) {
        glUniform3f(shader.u("uOffset"), 0f, 0f, 0f)
        for (m in mobs.list) {
            val dx = m.x - camX; val dz = m.z - camZ
            if (dx * dx + dz * dz > maxDist * maxDist) continue
            buf.size = 0
            build(m, mobs)
            mesh.upload(buf.data, buf.size)
            val (r, g, b) = tint(m)
            glUniform3f(shader.u("uTint"), r, g, b)
            mesh.draw()
        }
        glUniform3f(shader.u("uTint"), 1f, 1f, 1f)
    }

    private fun tint(m: Mob): Triple<Float, Float, Float> = when {
        m.hurtTime > 0f || m.dead -> Triple(1.6f, 0.45f, 0.45f)
        m.fuse >= 0f && sin(m.fuse * 22f) > 0f -> Triple(2.4f, 2.4f, 2.4f)
        m.burning -> Triple(1.5f, 0.9f, 0.55f)
        else -> Triple(1f, 1f, 1f)
    }

    private fun build(m: Mob, mobs: Mobs) {
        val parts = MobModels.models.getValue(m.type)
        val swingAmt = if (m.moving) sin(m.walkPhase) * 0.7f else 0f
        val roll = if (m.dead) min(m.deathTime / 0.4f, 1f) * 1.5708f else 0f
        val scale = if (m.fuse >= 0f) 1f + m.fuse * 0.12f else 1f
        val cy = cos(m.yaw); val sy = sin(m.yaw)
        val cr = cos(roll); val sr = sin(roll)
        val exposed = mobs.skyExposed(kotlin.math.floor(m.x).toInt(), kotlin.math.floor(m.y + m.type.height).toInt(), kotlin.math.floor(m.z).toInt())
        val sky = 0.28f + 0.72f * (if (exposed) 1f else 0f)

        for (p in parts) {
            val angle = when (p.swing) {
                1 -> swingAmt
                -1 -> -swingAmt
                2 -> 1.45f + sin(m.walkPhase * 0.5f) * 0.08f
                else -> 0f
            }
            val ca = cos(angle); val sa = sin(angle)
            val u0s = p.tiles
            for (f in 0 until 6) {
                val tile = u0s[f]
                val u0 = ChunkMesher.tileU(tile); val v0 = ChunkMesher.tileV(tile)
                val light = ChunkMesher.FACE_SHADE[f] * sky
                buf.ensure(4 * FLOATS_PER_VERTEX)
                for ((c, cv) in ChunkMesher.CORNERS[f].withIndex()) {
                    var x = if (cv[0] == 1) p.b[3] else p.b[0]
                    var y = if (cv[1] == 1) p.b[4] else p.b[1]
                    var z = if (cv[2] == 1) p.b[5] else p.b[2]
                    // Limb swing around the X axis at the joint.
                    if (angle != 0f) {
                        val ly = y - p.pivotY; val lz = z - p.pivotZ
                        y = p.pivotY + ly * ca - lz * sa
                        z = p.pivotZ + ly * sa + lz * ca
                    }
                    // Tip over when dying.
                    if (roll != 0f) { val nx = x * cr - y * sr; val ny = x * sr + y * cr; x = nx; y = ny }
                    x *= scale; y *= scale; z *= scale
                    // Body yaw: model -Z faces the mob's forward direction.
                    val wx = x * cy - z * sy
                    val wz = x * sy + z * cy
                    val uv = ChunkMesher.UVS[c]
                    buf.put(m.x + wx, m.y + y, m.z + wz, u0 + uv[0] * ChunkMesher.TILE_UV, v0 + uv[1] * ChunkMesher.TILE_UV, light)
                }
            }
        }
    }
}
