package com.vishucraft.game.world

/** The clothes the player can wear (shirt, trousers, hair), and the colour of each kind of armor on the body. */
object Outfits {
    class Fit(val name: String, val shirt: IntArray, val pants: IntArray, val hair: IntArray)

    val all = listOf(
        Fit("Explorer", intArrayOf(139, 96, 52), intArrayOf(64, 84, 130), intArrayOf(70, 48, 28)),
        Fit("Red Jersey", intArrayOf(200, 40, 40), intArrayOf(40, 50, 90), intArrayOf(30, 24, 20)),
        Fit("Blue Hoodie", intArrayOf(50, 100, 200), intArrayOf(50, 50, 56), intArrayOf(110, 70, 30)),
        Fit("Green Ranger", intArrayOf(60, 140, 70), intArrayOf(96, 72, 44), intArrayOf(60, 40, 24)),
        Fit("Purple Wizard", intArrayOf(110, 60, 170), intArrayOf(40, 30, 70), intArrayOf(200, 200, 210)),
        Fit("Orange Racer", intArrayOf(236, 140, 30), intArrayOf(30, 30, 34), intArrayOf(20, 20, 20)),
        Fit("Night Black", intArrayOf(30, 30, 36), intArrayOf(20, 20, 26), intArrayOf(16, 16, 20)),
        Fit("Snow White", intArrayOf(230, 232, 238), intArrayOf(150, 160, 180), intArrayOf(224, 190, 110)),
    )

    fun name(i: Int) = all[i.coerceIn(0, all.size - 1)].name
    fun next(i: Int) = (i + 1) % all.size

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
}
