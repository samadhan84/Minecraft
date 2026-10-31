package com.vishucraft.game.engine

import com.vishucraft.game.world.Blocks
import com.vishucraft.game.world.Items

/*
 * The showcase world, opened from the home screen: a creative-only flat world that comes with everything already
 * built. Three cities joined by metro and railway lines, metro, railway and bullet train stations (the bullet
 * line runs under a lake) with call buttons, two airports with planes flying between them, the houses, mansion,
 * farm, tower and pool, cars (and a glass car), a roller coaster, an escalator, the helper robot, villagers with their iron golem,
 * and animals.
 */

/** The folder and name of the showcase world. */
const val SHOWCASE_DIR = "showcase"
const val SHOWCASE_NAME = "Showcase World"

/** How many worlds a player can have. */
const val MAX_WORLDS = 5
const val MAX_WORLDS_MESSAGE = "You can have up to 5 worlds. Delete one to make a new world."

val Game.isShowcase get() = level.name == SHOWCASE_NAME

/** Builds the showcase the first time the world is opened (it has no stations until then). */
internal fun Game.buildShowcaseIfNew() {
    if (!isShowcase || isClient || metroNet.stations.isNotEmpty()) return
    fun ground(x: Int, z: Int): Int { world.ensureLoaded(x shr 4, z shr 4); return world.generator.surfaceHeight(x, z) }
    fun build(name: String, x: Int, z: Int) {
        player.yaw = 0f   // everything faces the same way: it is built northwards from (x, z)
        placeBuilding(RayHit(x, ground(x, z), z, 0, 1, 0, Blocks.GRASS), name)
    }
    // Cities first (their metro and railway lines join each other), then the stations beyond their open ends.
    build("Mega City", 0, -30)
    build("Big City", 330, -30)
    build("Smart City", -280, -30)
    build("Metro Station", -400, -76)
    build("Metro Station", 480, -100)
    build("Railway Station", -410, -133)
    build("Railway Station", 490, -181)
    build("Airport", -150, 160)
    build("Airport", 150, 160)
    // Two bullet train stations with a lake between them: their line runs in a glass tube under the water.
    build("Bullet Train Station", -150, 262)
    val lakeTop = ground(0, 258)
    for (x in -100..100) for (z in 240..275) {
        world.ensureLoaded(x shr 4, z shr 4)
        for (y in lakeTop - 14..lakeTop) setBlock(x, y, z, Blocks.WATER)
    }
    build("Bullet Train Station", 150, 262)
    // A row of buildings in front of the city.
    for ((name, x) in listOf("Small House" to -110, "Modern House" to -80, "Mansion" to -40, "Farm" to 30,
        "Watch Tower" to 55, "Swimming Pool" to 75, "Small House" to 100)) build(name, x, 20)

    val g = ground(0, 40) + 1
    // A roller coaster to ride, beside the row of houses.
    buildCoaster(30, ground(30, 70) + 1, 70, 1, 0)
    // An escalator up to a little lookout, beside where you start.
    for (k in 0 until 5) setBlock(-14 - k, g + k, 44, Blocks.ESCALATOR, 4)
    for (x in -22..-19) for (z in 43..45) { for (y in g until g + 4) setBlock(x, y, z, Blocks.STONE_BRICKS); setBlock(x, g + 4, z, Blocks.OAK_FENCE) }
    for (z in 43..45) setBlock(-19, g + 4, z, Blocks.AIR)
    // Cars to try (a red car, a glass car and a bus), the robot, villagers with their golem, and animals.
    vehicles.list.add(Vehicle(-6.5f, g.toFloat(), 34.5f, Vehicle.GLASS_CAR, 0).also { it.hx = 0; it.hz = -1 })
    vehicles.list.add(Vehicle(-10.5f, g.toFloat(), 34.5f, Vehicle.CAR, 14).also { it.hx = 0; it.hz = -1 })
    vehicles.list.add(Vehicle(-16.5f, g.toFloat(), 34.5f, Vehicle.BUS, 0).also { it.hx = 0; it.hz = -1 })
    mobs.list.add(Mob(MobType.ROBOT, 2.5f, g + 1f, 38.5f))
    for (k in 0..2) mobs.list.add(Mob(MobType.VILLAGER, 12.5f + k * 2, g.toFloat(), 30.5f))
    mobs.list.add(Mob(MobType.IRON_GOLEM, 16.5f, g.toFloat(), 33.5f))
    for ((t, x) in listOf(MobType.COW to 20, MobType.PIG to 23, MobType.SHEEP to 26, MobType.CHICKEN to 29, MobType.HORSE to 32))
        mobs.list.add(Mob(t, x + 0.5f, g.toFloat(), 40.5f))
    // Handy things in the hotbar.
    for ((slot, name) in listOf(5 to "Glass Car", 6 to "Robot Spawn Egg", 7 to "Car")) inventory.slots[slot] = com.vishucraft.game.world.ItemStack(Items.find(name), 1)
    inventory.slots[8] = com.vishucraft.game.world.ItemStack(Blocks.ESCALATOR, 64)
    while (uiEvents.poll() != null) {}
    // Start in front of it all, looking at the city.
    player.x = 0.5f; player.y = g.toFloat(); player.z = 40.5f; player.yaw = 0f; player.pitch = 0f
    uiEvents.add("toast:Welcome to the showcase world! Everything is built: walk around, ride the metro, fly between the airports, tap the robot")
}
