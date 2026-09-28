package com.vishucraft.game.world

/**
 * Models for the kitchen and home blocks. Each is drawn facing +Z (the side towards the player who placed it)
 * and turned to the block's facing. A box may carry a 7th value: a texture tile used for all of its faces
 * (handles, knobs and legs in another material).
 */
object HomeShapes {
    private const val P = 1f / 16f

    private fun b(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, tile: String? = null) =
        if (tile == null) floatArrayOf(x0 * P, y0 * P, z0 * P, x1 * P, y1 * P, z1 * P)
        else floatArrayOf(x0 * P, y0 * P, z0 * P, x1 * P, y1 * P, z1 * P, Tiles.id(tile).toFloat())

    /** Turns a box drawn with its front at +Z (face 2) to face [f]. */
    private fun turn(box: FloatArray, f: Int): FloatArray {
        fun map(x: Float, z: Float): Pair<Float, Float> = when (f) {
            3 -> (1 - x) to (1 - z)
            4 -> z to (1 - x)
            5 -> (1 - z) to x
            else -> x to z
        }
        val (ax, az) = map(box[0], box[2]); val (cx, cz) = map(box[3], box[5])
        val out = box.copyOf()
        out[0] = minOf(ax, cx); out[3] = maxOf(ax, cx); out[2] = minOf(az, cz); out[5] = maxOf(az, cz)
        return out
    }

    /** The boxes of a home block, or null when [id] is not one. */
    fun boxes(id: Int, meta: Int, collision: Boolean): List<FloatArray>? {
        val on = meta and 8 != 0
        val parts: List<FloatArray> = when (id) {
            Blocks.PRESSURE_COOKER -> listOf(
                b(4f, 0f, 4f, 12f, 7f, 12f),
                b(3.5f, 7f, 3.5f, 12.5f, 8f, 12.5f, "steel"),
                b(7f, 8f, 7f, 9f, 10f, 9f, "black_plastic"),          // whistle
                b(7f, 6f, 12f, 9f, 7.5f, 16f, "black_plastic"),       // handle
            )
            Blocks.TAWA -> listOf(b(2f, 0f, 2f, 14f, 1f, 14f), b(7f, 0.2f, 14f, 9f, 1f, 16f, "oak_planks"))
            Blocks.FRYING_PAN -> listOf(
                b(3f, 0f, 3f, 13f, 1f, 13f), b(3f, 1f, 3f, 13f, 3f, 4f), b(3f, 1f, 12f, 13f, 3f, 13f),
                b(3f, 1f, 4f, 4f, 3f, 12f), b(12f, 1f, 4f, 13f, 3f, 12f), b(4f, 1f, 4f, 12f, 1.5f, 12f),
                b(7f, 2f, 13f, 9f, 3f, 16f, "black_plastic"),
            )
            Blocks.KETTLE -> listOf(
                b(5f, 0f, 5f, 11f, 7f, 11f),
                b(7f, 7f, 7f, 9f, 8f, 9f, "black_plastic"),
                b(7.5f, 3f, 11f, 8.5f, 5f, 14f, "steel"),             // spout
                b(7.5f, 8f, 5.5f, 8.5f, 9f, 10.5f, "black_plastic"),  // handle
            )
            Blocks.MICROWAVE -> listOf(b(1f, 0f, 3f, 15f, 8f, 15f))
            Blocks.OVEN -> listOf(b(1f, 0f, 2f, 15f, 10f, 15f), b(2f, 7f, 15f, 14f, 7.8f, 15.6f, "steel"))
            Blocks.TOASTER -> listOf(b(4f, 0f, 5f, 12f, 6f, 11f), b(12f, 3f, 7f, 13f, 4f, 9f, "black_plastic"))
            Blocks.MIXER -> listOf(
                b(5f, 0f, 5f, 11f, 4f, 11f, "white_plastic"),
                b(5.5f, 4f, 5.5f, 10.5f, 11f, 10.5f),
                b(5.5f, 11f, 5.5f, 10.5f, 12f, 10.5f, "black_plastic"),
                b(7f, 1f, 11f, 9f, 3f, 12f, "black_plastic"),          // knob
            )
            Blocks.KITCHEN_SINK -> listOf(
                b(0f, 0f, 0f, 16f, 16f, 16f),
                b(7f, 16f, 1f, 9f, 21f, 3f, "steel"),                   // tap
                b(7f, 19f, 3f, 9f, 21f, 7f, "steel"),
            )
            Blocks.DINING_TABLE -> if (collision) listOf(b(0f, 0f, 0f, 16f, 16f, 16f)) else listOf(
                b(0f, 14f, 0f, 16f, 16f, 16f),
                b(1f, 0f, 1f, 3f, 14f, 3f), b(13f, 0f, 1f, 15f, 14f, 3f), b(1f, 0f, 13f, 3f, 14f, 15f), b(13f, 0f, 13f, 15f, 14f, 15f),
            )
            Blocks.CHAIR -> if (collision) listOf(b(2f, 0f, 2f, 14f, 9f, 14f)) else listOf(
                b(2f, 7f, 2f, 14f, 9f, 14f),
                b(2f, 0f, 2f, 4f, 7f, 4f), b(12f, 0f, 2f, 14f, 7f, 4f), b(2f, 0f, 12f, 4f, 7f, 14f), b(12f, 0f, 12f, 14f, 7f, 14f),
                b(2f, 9f, 2f, 14f, 20f, 4f),                             // back rest
            )
            Blocks.SOFA -> if (collision) listOf(b(0f, 0f, 1f, 16f, 8f, 15f)) else listOf(
                b(0f, 1f, 1f, 16f, 7f, 15f),
                b(0f, 7f, 1f, 16f, 16f, 5f),                             // back rest
                b(1f, 0f, 2f, 3f, 1f, 4f, "oak_planks"), b(13f, 0f, 2f, 15f, 1f, 4f, "oak_planks"),
                b(1f, 0f, 12f, 3f, 1f, 14f, "oak_planks"), b(13f, 0f, 12f, 15f, 1f, 14f, "oak_planks"),
            )
            Blocks.TV -> listOf(
                b(5f, 0f, 6f, 11f, 1f, 10f, "black_plastic"),
                b(7.5f, 1f, 7.5f, 8.5f, 3f, 8.5f, "black_plastic"),
                b(0f, 3f, 7f, 16f, 13f, 9f),
            )
            Blocks.CEILING_FAN -> {
                val hub = listOf(b(7.5f, 12f, 7.5f, 8.5f, 16f, 8.5f, "steel"), b(6f, 10f, 6f, 10f, 12f, 10f, "white_plastic"))
                if (collision) emptyList()
                else if (on) hub + b(0f, 10.5f, 0f, 16f, 10.8f, 16f, "fan_blur")
                else hub + listOf(b(0f, 10.5f, 7f, 16f, 11f, 9f, "white_plastic"), b(7f, 10.5f, 0f, 9f, 11f, 16f, "white_plastic"))
            }
            Blocks.TABLE_LAMP -> listOf(
                b(6f, 0f, 6f, 10f, 1f, 10f, "steel"),
                b(7.5f, 1f, 7.5f, 8.5f, 8f, 8.5f, "steel"),
                b(4f, 8f, 4f, 12f, 14f, 12f),
            )
            Blocks.WASHING_MACHINE -> listOf(b(1f, 0f, 1f, 15f, 15f, 15f))
            // Hangs on the wall behind it (at -Z before turning).
            Blocks.AIR_CONDITIONER -> listOf(b(0f, 9f, 0f, 16f, 15f, 5f))
            Blocks.SPRINKLER -> listOf(
                b(7f, 0f, 7f, 9f, 10f, 9f), b(5f, 10f, 5f, 11f, 12f, 11f),
                b(7.5f, 12f, 7.5f, 8.5f, 13f, 8.5f, "black_plastic"),
                b(1f, 11f, 7.5f, 15f, 11.5f, 8.5f, "black_plastic"),     // spray arm
            )
            // Straw head (the block's own tiles, with a face on the front), shirt, arms and a pole.
            Blocks.SCARECROW -> if (collision) listOf(b(4f, 0f, 4f, 12f, 16f, 12f)) else listOf(
                b(7f, 0f, 7f, 9f, 6f, 9f, "oak_planks"),
                b(4f, 6f, 5f, 12f, 12f, 11f, "scarecrow"),
                b(0f, 10f, 7f, 16f, 11f, 9f, "oak_planks"),
                b(5f, 12f, 5f, 11f, 16f, 11f),
            )
            else -> return null
        }
        val f = (meta and 7).let { if (it < 2 || it > 5) 2 else it }
        return if (f == 2) parts else parts.map { turn(it, f) }
    }
}
