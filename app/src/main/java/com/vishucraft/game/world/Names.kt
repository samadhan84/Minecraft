package com.vishucraft.game.world

/**
 * The names players see. A few things kept their older names inside the game (saves and recipes look them up by
 * those), but on screen they use DhruvVishu's own names.
 */
object Names {
    private val renames = listOf(
        "Enderman" to "Shadow Walker",
        "Ender Pearl" to "Warp Pearl",
        "Eye of Ender" to "Warp Eye",
        "Ender Chest" to "Warp Chest",
        "Ender" to "Warp",
        "Netherite" to "Emberite",
        "Nether Star" to "Ember Star",
        "Nether Quartz" to "Ember Quartz",
        "Nether" to "Ember",
        "Elytra" to "Glider Wings",
        "Redstone" to "Sparkstone",
        "Mooshroom" to "Mushroom Cow",
        "Totem of Undying" to "Totem of Life",
        "Ghast" to "Wraith",
        "Blaze" to "Flame",
        "Prismarine" to "Sea Prism",
        "Minecraft" to "DhruvVishu",
    ).map { (old, new) -> Regex("\\b" + Regex.escape(old), RegexOption.IGNORE_CASE) to new }

    /** Replaces the old names in any text shown to the player (keeps lower case for lower-case words). */
    fun show(text: String): String {
        var s = text
        for ((re, new) in renames) {
            if (!re.containsMatchIn(s)) continue
            s = re.replace(s) { m -> if (m.value[0].isLowerCase()) new.lowercase() else new }
        }
        return s
    }
}
