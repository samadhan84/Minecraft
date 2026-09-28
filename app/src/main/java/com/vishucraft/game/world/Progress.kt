package com.vishucraft.game.world

/** Experience levels: the same curve as the classic game (levels get slowly more expensive). */
object Xp {
    /** Points needed to go from [level] to the next one. */
    fun needed(level: Int) = when {
        level < 16 -> 2 * level + 7
        level < 31 -> 5 * level - 38
        else -> 9 * level - 158
    }

    /** (level, progress 0..1 towards the next level) for a total number of points. */
    fun levelOf(total: Int): Pair<Int, Float> {
        var left = total; var level = 0
        while (left >= needed(level)) { left -= needed(level); level++ }
        return level to left.toFloat() / needed(level)
    }
}

/** One villager offer: give [giveCount] of [give] (and optionally a second item), get [getCount] of [get]. */
class Trade(val give: Int, val giveCount: Int, val get: Int, val getCount: Int)

/** Villager jobs and what they trade (emeralds are the money). Each villager's job comes from its id. */
object Trades {
    class Job(val title: String, val offers: List<Trade>)

    val jobs: List<Job> by lazy {
        fun i(n: String) = Items.find(n)
        val em = i("Emerald")
        listOf(
            Job("Farmer", listOf(
                Trade(i("Wheat"), 20, em, 1), Trade(i("Carrot"), 22, em, 1), Trade(i("Potato"), 26, em, 1),
                Trade(em, 1, i("Bread"), 6), Trade(em, 1, i("Pumpkin Pie"), 4), Trade(em, 3, i("Golden Carrot"), 3), Trade(em, 1, i("Cookie"), 18),
            )),
            Job("Librarian", listOf(
                Trade(i("Paper"), 24, em, 1), Trade(i("Book"), 4, em, 1), Trade(em, 1, Blocks.BOOKSHELF, 1),
                Trade(em, 5, i("Enchanted Book"), 1), Trade(em, 1, i("Book and Quill"), 1), Trade(em, 5, i("Compass"), 1), Trade(em, 5, i("Clock"), 1),
            )),
            Job("Blacksmith", listOf(
                Trade(i("Coal"), 15, em, 1), Trade(i("Iron Ingot"), 4, em, 1), Trade(em, 3, i("Iron Pickaxe"), 1),
                Trade(em, 7, i("Iron Sword"), 1), Trade(em, 12, i("Diamond Pickaxe"), 1), Trade(em, 15, i("Diamond Sword"), 1),
                Trade(em, 9, i("Iron Chestplate"), 1), Trade(em, 4, i("Shield"), 1),
            )),
            Job("Cleric", listOf(
                Trade(i("Rotten Flesh"), 32, em, 1), Trade(i("Gold Ingot"), 3, em, 1), Trade(em, 1, Blocks.REDSTONE_DUST, 2),
                Trade(em, 1, i("Lapis Lazuli"), 1), Trade(em, 4, i("Glowstone Dust"), 4), Trade(em, 5, i("Ender Pearl"), 1),
                Trade(em, 3, i("Potion of Healing"), 1), Trade(em, 6, i("Totem of Undying"), 1),
            )),
            Job("Fisherman", listOf(
                Trade(i("String"), 20, em, 1), Trade(i("Raw Cod"), 6, em, 1), Trade(em, 1, i("Cooked Cod"), 6),
                Trade(em, 3, i("Fishing Rod"), 1), Trade(em, 2, i("Cooked Salmon"), 6),
            )),
            Job("Shepherd", listOf(
                Trade(Blocks.WOOL_WHITE, 18, em, 1), Trade(em, 2, i("Shears"), 1), Trade(em, 1, Blocks.WOOL_RED, 1),
                Trade(em, 1, Blocks.WOOL_BLUE, 1), Trade(em, 3, Blocks.BED_FIRST + 14, 1), Trade(em, 1, i("Saddle"), 1),
            )),
        )
    }

    fun jobFor(uid: Int): Job = jobs[Math.floorMod(uid * 7919, jobs.size)]
}

/** Achievements (key -> title and how to get it). */
object Achievements {
    class A(val key: String, val title: String, val hint: String)

    val all = listOf(
        // Keys stay the same (they are saved); the titles are DhruvVishu's own.
        A("wood", "First Log", "Collect a log"),
        A("bench", "Workbench Ready", "Make a crafting table"),
        A("pickaxe", "Pick It Up", "Make a pickaxe"),
        A("furnace", "Fired Up", "Make a furnace"),
        A("iron", "Iron Age", "Get an iron ingot"),
        A("diamond", "Shiny Treasure", "Get a diamond"),
        A("bread", "Fresh from the Oven", "Make bread"),
        A("kill", "Night Guard", "Defeat a monster"),
        A("leather", "Leather Maker", "Collect leather"),
        A("tame", "New Buddy", "Tame a wolf, cat, fox or parrot"),
        A("ride", "Saddle Up", "Ride a saddled horse"),
        A("cart", "All Aboard", "Ride a minecart"),
        A("sleep", "Good Night", "Sleep in a bed"),
        A("fish", "Gone Fishing", "Catch a fish"),
        A("potion", "Potion Taster", "Drink a potion"),
        A("trade", "Good Trade", "Trade with a villager"),
        A("level10", "Level Ten", "Reach level 10"),
        A("ember", "Into the Embers", "Enter the Ember Realm"),
        A("sky", "Above the Clouds", "Enter the Sky Isles"),
        A("elytra", "Wings Out", "Glide with Glider Wings"),
        A("totem", "Second Chance", "Be saved by a Totem of Life"),
        A("boss", "Warden Defeated", "Defeat the Sky Warden"),
    )

    fun title(key: String) = all.firstOrNull { it.key == key }?.title ?: key
}
