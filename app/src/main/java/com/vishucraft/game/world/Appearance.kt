package com.vishucraft.game.world

/**
 * How the player looks: skin, hair, eyes, mouth, face extras, clothes, shoes, hat and build. Every part can be changed on its own.
 * The choices are stored as one number per category (see [categories]); colours come from the [palette] (tiles p4_c_N).
 */
object Appearance {
    class Category(val title: String, val options: List<String>)

    /** The colours any part can have. */
    val palette: List<IntArray> = listOf(
        intArrayOf(255, 255, 255), intArrayOf(20, 20, 24), intArrayOf(120, 120, 128), intArrayOf(200, 40, 40), intArrayOf(240, 130, 30),
        intArrayOf(240, 210, 50), intArrayOf(60, 160, 70), intArrayOf(50, 100, 200), intArrayOf(110, 60, 170), intArrayOf(230, 120, 170),
        intArrayOf(110, 70, 34), intArrayOf(60, 200, 220), intArrayOf(40, 50, 90), intArrayOf(150, 150, 60),
        // skin tones
        intArrayOf(255, 224, 196), intArrayOf(240, 200, 160), intArrayOf(214, 160, 110), intArrayOf(176, 128, 84), intArrayOf(120, 80, 52), intArrayOf(80, 52, 36),
        intArrayOf(240, 170, 170), intArrayOf(190, 210, 210),
        // hair colours
        intArrayOf(24, 18, 14), intArrayOf(70, 48, 28), intArrayOf(150, 100, 50), intArrayOf(224, 190, 110), intArrayOf(170, 50, 30), intArrayOf(220, 220, 226),
        // more
        intArrayOf(90, 130, 90), intArrayOf(170, 120, 60), intArrayOf(255, 215, 0), intArrayOf(150, 30, 40),
    )
    const val C_WHITE = 0; const val C_BLACK = 1; const val C_GREY = 2; const val C_RED = 3; const val C_ORANGE = 4; const val C_YELLOW = 5
    const val C_GREEN = 6; const val C_BLUE = 7; const val C_PURPLE = 8; const val C_PINK = 9; const val C_BROWN = 10; const val C_AQUA = 11
    const val C_NAVY = 12; const val C_OLIVE = 13
    const val C_SKIN0 = 14   // 8 skin tones: 14..21
    const val C_HAIR0 = 22   // 6 hair colours: 22..27
    const val C_GOLD = 30; const val C_MOUTH = 31

    val colorNames = listOf("White", "Black", "Grey", "Red", "Orange", "Yellow", "Green", "Blue", "Purple", "Pink", "Brown", "Aqua", "Navy", "Olive")
    private val shirtColors = listOf(C_RED, C_BLUE, C_GREEN, C_YELLOW, C_ORANGE, C_PURPLE, C_BLACK, C_WHITE, C_PINK, C_AQUA, C_GREY, C_BROWN)
    private val pantsColors = listOf(C_NAVY, C_BLACK, C_GREY, C_BROWN, C_OLIVE, C_BLUE, C_WHITE, C_RED)
    private val shoeColors = listOf(C_BROWN, C_BLACK, C_WHITE, C_RED, C_BLUE, C_GREY)
    private fun names(c: List<Int>) = c.map { colorNames.getOrElse(it) { "Colour" } }

    // Indices into the appearance array.
    const val SKIN = 0; const val HAIR_STYLE = 1; const val HAIR_COLOR = 2; const val EYES = 3; const val MOUTH = 4; const val FACE_EXTRA = 5
    const val SHIRT_STYLE = 6; const val SHIRT_COLOR = 7; const val PANTS_STYLE = 8; const val PANTS_COLOR = 9; const val SHOES = 10; const val HAT = 11; const val BUILD = 12

    val categories = listOf(
        Category("Skin", listOf("Light", "Fair", "Tan", "Olive", "Brown", "Dark", "Rosy", "Ghost")),
        Category("Hair style", listOf("Short", "Long", "Spiky", "Bun", "Mohawk", "Bald")),
        Category("Hair colour", listOf("Black", "Brown", "Auburn", "Blonde", "Red", "Silver")),
        Category("Eyes", listOf("Brown", "Blue", "Green", "Grey", "Purple", "Red")),
        Category("Mouth", listOf("Smile", "Neutral", "Grin", "Surprised")),
        Category("Face extras", listOf("None", "Freckles", "Beard", "Glasses")),
        Category("Shirt style", listOf("T-shirt", "Long sleeve", "Striped", "Hoodie")),
        Category("Shirt colour", names(shirtColors)),
        Category("Trousers", listOf("Long", "Shorts")),
        Category("Trousers colour", names(pantsColors)),
        Category("Shoes", names(shoeColors)),
        Category("Hat", listOf("None", "Cap", "Beanie", "Top hat", "Crown")),
        Category("Build", listOf("Slim", "Normal", "Broad")),
    )

    val SIZE = categories.size
    fun default() = intArrayOf(2, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)

    fun value(a: IntArray, cat: Int) = a.getOrElse(cat) { 0 }.coerceIn(0, categories[cat].options.size - 1)
    fun optionName(a: IntArray, cat: Int) = categories[cat].options[value(a, cat)]
    fun cycle(a: IntArray, cat: Int, delta: Int = 1) { a[cat] = (value(a, cat) + delta + categories[cat].options.size) % categories[cat].options.size }
    fun randomize(a: IntArray, rnd: java.util.Random) { for (i in 0 until SIZE) a[i] = rnd.nextInt(categories[i].options.size) }

    fun skin(a: IntArray) = C_SKIN0 + value(a, SKIN)
    fun hair(a: IntArray) = C_HAIR0 + value(a, HAIR_COLOR)
    fun eyes(a: IntArray) = listOf(C_BROWN, C_BLUE, C_GREEN, C_GREY, C_PURPLE, C_RED)[value(a, EYES)]
    fun shirt(a: IntArray) = shirtColors[value(a, SHIRT_COLOR)]
    fun pants(a: IntArray) = pantsColors[value(a, PANTS_COLOR)]
    fun shoes(a: IntArray) = shoeColors[value(a, SHOES)]

    /** The armor colour index (tile p4_armor_N) for an armor item's name. */
    fun armorColor(itemName: String): Int = when {
        itemName.contains("Leather") -> 0
        itemName.contains("Golden") || itemName.contains("Gold") -> 1
        itemName.contains("Iron") -> 2
        itemName.contains("Diamond") -> 3
        itemName.contains("Netherite") -> 4
        itemName.contains("Chainmail") -> 5
        else -> 6
    }
    val armorRgb = listOf(
        intArrayOf(150, 100, 60), intArrayOf(240, 200, 60), intArrayOf(205, 205, 210), intArrayOf(80, 220, 220),
        intArrayOf(64, 56, 64), intArrayOf(170, 172, 184), intArrayOf(70, 160, 80),
    )

    // ---------------------------------------------------------------- the saved look (shared by every world)

    /** The file that remembers your look for every world and for the home screen (next to the worlds). */
    fun fileFor(worldsDir: java.io.File) = java.io.File(worldsDir, "look.dat")

    fun load(file: java.io.File?): IntArray? {
        if (file == null || !file.exists()) return null
        return try {
            val parts = file.readText().trim().split(',').map { it.trim().toInt() }
            val a = default()
            for (i in 0 until minOf(SIZE, parts.size)) a[i] = parts[i]
            a
        } catch (_: Exception) { null }
    }

    fun save(file: java.io.File?, a: IntArray) {
        if (file == null) return
        try { file.writeText(a.joinToString(",")) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- a flat picture of the character (home screen preview)

    /** The character seen from the front as coloured rectangles [x, y, width, height, palette colour] on a 16 x 36 grid. */
    fun flat(a: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        fun r(x: Int, y: Int, w: Int, h: Int, c: Int) { out.add(intArrayOf(x, y, w, h, c)) }
        val skin = skin(a); val hair = hair(a); val shirt = shirt(a); val pants = pants(a); val shoes = shoes(a)
        val dy = 4
        val half = intArrayOf(3, 4, 5)[value(a, BUILD)]       // half the torso width
        val tx = 8 - half
        // Long hair hangs behind everything.
        if (value(a, HAIR_STYLE) == 1) r(3, dy + 2, 10, 14, hair)
        // Legs and shoes.
        val shorts = value(a, PANTS_STYLE) == 1
        for (side in 0..1) {
            val lx = if (side == 0) tx else 8
            r(lx, dy + 22, half, 8, skin)
            r(lx, dy + 22, half, if (shorts) 4 else 8, pants)
            r(lx, dy + 30, half, 2, shoes)
        }
        // Arms (skin, then the sleeve) and torso.
        val style = value(a, SHIRT_STYLE)
        for (side in 0..1) {
            val ax = if (side == 0) tx - 3 else 8 + half
            r(ax, dy + 12, 3, 10, skin)
            r(ax, dy + 12, 3, if (style == 0) 4 else 10, shirt)
        }
        r(tx, dy + 12, half * 2, 10, shirt)
        if (style == 2) for (y in intArrayOf(14, 17, 20)) r(tx, dy + y, half * 2, 1, C_WHITE)
        if (style == 3) { r(6, dy + 12, 4, 2, shirt); r(7, dy + 14, 2, 6, C_BLACK) }
        // Head.
        r(4, dy + 4, 8, 8, skin)
        when (value(a, HAIR_STYLE)) {
            0 -> { r(3, dy + 2, 10, 3, hair); r(3, dy + 4, 1, 5, hair); r(12, dy + 4, 1, 5, hair) }
            1 -> { r(3, dy + 2, 10, 3, hair); r(3, dy + 4, 1, 12, hair); r(12, dy + 4, 1, 12, hair) }
            2 -> { r(4, dy + 3, 8, 1, hair); r(4, dy + 1, 2, 2, hair); r(7, dy + 0, 2, 3, hair); r(10, dy + 1, 2, 2, hair) }
            3 -> { r(3, dy + 2, 10, 3, hair); r(6, dy - 1, 4, 3, hair) }
            4 -> r(7, dy - 1, 2, 5, hair)
            else -> {}
        }
        // Eyes, brows, mouth.
        for (x in intArrayOf(5, 9)) { r(x, dy + 6, 2, 2, C_WHITE); r(x + 1, dy + 6, 1, 2, eyes(a)); r(x, dy + 5, 2, 1, hair) }
        when (value(a, MOUTH)) {
            0 -> { r(6, dy + 9, 4, 1, C_MOUTH); r(5, dy + 8, 1, 1, C_MOUTH); r(10, dy + 8, 1, 1, C_MOUTH) }
            1 -> r(6, dy + 9, 4, 1, C_MOUTH)
            2 -> { r(5, dy + 9, 6, 2, C_MOUTH); r(6, dy + 9, 4, 1, C_WHITE) }
            else -> r(7, dy + 9, 2, 2, C_MOUTH)
        }
        when (value(a, FACE_EXTRA)) {
            1 -> for ((x, y) in listOf(5 to 8, 6 to 7, 9 to 7, 10 to 8)) r(x, dy + y, 1, 1, C_BROWN)
            2 -> { r(4, dy + 9, 8, 3, hair); r(6, dy + 9, 4, 1, C_MOUTH) }
            3 -> { for (x in intArrayOf(4, 9)) { r(x, dy + 5, 3, 1, C_BLACK); r(x, dy + 8, 3, 1, C_BLACK); r(x, dy + 5, 1, 4, C_BLACK); r(x + 2, dy + 5, 1, 4, C_BLACK) }; r(7, dy + 6, 2, 1, C_BLACK) }
            else -> {}
        }
        when (value(a, HAT)) {
            1 -> { r(3, dy + 1, 10, 3, C_RED); r(3, dy + 3, 12, 1, C_RED) }
            2 -> { r(3, dy, 10, 4, C_AQUA); r(7, dy - 2, 2, 2, C_WHITE) }
            3 -> { r(2, dy + 2, 12, 1, C_BLACK); r(4, dy - 4, 8, 6, C_BLACK); r(4, dy - 1, 8, 1, C_RED) }
            4 -> { r(3, dy + 1, 10, 2, C_GOLD); for (x in intArrayOf(3, 7, 11)) r(x, dy - 1, 2, 2, C_GOLD) }
            else -> {}
        }
        return out
    }
}
