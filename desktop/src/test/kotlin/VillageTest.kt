import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** More villages, and iron golems that defend themselves and their villagers. */
class VillageTest {
    private fun game(): Game {
        val world = World(7L, null, Dimension.OVERWORLD, true)
        for (cz in -2..2) for (cx in -2..2) { val c = Chunk(cx, cz); world.generator.generate(c); world.chunks[Chunk.key(cx, cz)] = c }
        val lvl = LevelData.create(7L, "T", GameMode.SURVIVAL).apply { hasPlayer = true; x = 0.5f; y = 65f; z = 0.5f }
        return Game(world, lvl, GameInput(), null).also { it.mobs.hostileEnabled = false; it.timeOfDay = 0.25f }
    }

    @Test fun villagesAreCommon() {
        // Count village wells (water ringed by cobblestone under a slab roof) across a 1 km square of new terrain.
        val world = World(12345L, null, Dimension.OVERWORLD, false)
        var villages = 0
        val gen = world.generator
        for (rz in -4..3) for (rx in -4..3) {
            val rnd = java.util.Random(12345L xor (rx * 341873128712L) xor (rz * 132897987541L) xor 0x5EED)
            val cx = rx * 144 + 48 + rnd.nextInt(144 - 96); val cz = rz * 144 + 48 + rnd.nextInt(144 - 96)
            val h = gen.surfaceHeight(cx, cz)
            if (h <= TerrainGenerator.SEA_LEVEL + 1 || h > 88) continue
            if (gen.biomeAt(cx, cz) in setOf(Biome.PLAINS, Biome.SAVANNA, Biome.FOREST, Biome.SNOW, Biome.CHERRY, Biome.JUNGLE, Biome.SWAMP)) villages++
        }
        println("$villages villages in about 1150 x 1150 blocks")
        assertTrue(villages >= 12)
    }

    @Test fun theIronGolemFightsBack() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        val golem = Mob(MobType.IRON_GOLEM, 3.5f, y, 0.5f)
        g.mobs.list.add(golem)
        val before = g.health
        // Leave it alone: it doesn't attack.
        repeat(120) { g.update(1 / 60f) }
        assertEquals(before, g.health)
        // Hit it: it comes after you.
        g.mobs.damage(golem, 2f, 1f, 0f)
        var hurt = false
        repeat(60 * 6) { g.update(1 / 60f); if (g.health < before) hurt = true }
        println("health $before -> ${g.health}")
        assertTrue(hurt, "the golem hit back")
        // Run far away and it calms down.
        g.player.x = 60.5f
        repeat(30) { g.update(1 / 60f) }
        assertFalse(golem.angry)
    }

    @Test fun hurtingAVillagerAngersTheGolem() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f; g.player.yaw = kotlin.math.PI.toFloat() / 2f; g.player.pitch = -0.2f
        val villager = Mob(MobType.VILLAGER, 2f, y, 0.5f).also { it.aiTimer = 99f; it.wandering = false }
        val golem = Mob(MobType.IRON_GOLEM, 12.5f, y, 6.5f)
        val built = Mob(MobType.IRON_GOLEM, 12.5f, y, -6.5f).also { it.tamed = true }
        g.mobs.list.addAll(listOf(villager, golem, built))
        g.inventory.clear()
        g.input.actions.add(GameInput.Action.ATTACK); g.update(1 / 60f)
        assertTrue(villager.health < villager.type.maxHealth, "hit the villager")
        assertTrue(golem.angry, "the village's golem is angry")
        assertFalse(built.angry, "a golem you built stays friendly")
    }
}
