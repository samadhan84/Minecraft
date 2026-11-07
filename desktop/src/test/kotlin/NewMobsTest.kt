import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Flame Spinners, Tear Drifters and raids. */
class NewMobsTest {
    private fun game(survival: Boolean = true, flat: Boolean = true): Game {
        val dir = kotlin.io.path.createTempDirectory("mobs").toFile()
        val level = LevelData.create(11L, "t", if (survival) GameMode.SURVIVAL else GameMode.CREATIVE)
        val world = World(level.seed, dir, Dimension.OVERWORLD, flat = flat)
        val g = Game(world, level, GameInput(), dir)
        for (cx in -4..4) for (cz in -4..4) world.ensureLoaded(cx, cz)
        g.timeOfDay = 0.3f
        return g
    }
    private fun run(g: Game, seconds: Float) { var t = 0f; while (t < seconds) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f } }

    @Test fun flyingShootersThrowFireballsAndDropLoot() {
        for (type in listOf(MobType.FLAME_SPINNER, MobType.TEAR_DRIFTER)) {
            val g = game()
            g.mobs.hostileEnabled = true
            val y = g.world.generator.surfaceHeight(0, 0) + 1f
            g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
            val m = Mob(type, 6f, y + 4f, 6f); g.mobs.list.add(m)
            var fired = false
            var t = 0f
            while (t < 12f && !fired) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f; fired = g.projectiles.list.any { it.kind == Projectile.FIREBALL } }
            assertTrue(fired, "$type throws a fireball")
            assertTrue(m.y > y + 1f, "$type hovers instead of walking")
            assertTrue(!m.dead)
        }
    }

    @Test fun raidersShootArrows() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        g.mobs.list.add(Mob(MobType.RAIDER, 8f, y, 0.5f))
        var shot = false; var t = 0f
        while (t < 10f && !shot) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f; shot = g.projectiles.list.any { it.kind == Projectile.ARROW } }
        assertTrue(shot, "a raider shoots")
    }

    @Test fun aRaidComesInThreeWavesAndPaysARewardWhenBeaten() {
        val g = game()
        g.mobs.hostileEnabled = true
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        g.mobs.list.add(Mob(MobType.VILLAGER, 4f, y, 4f)); g.mobs.list.add(Mob(MobType.VILLAGER, -4f, y, 4f))
        g.mobs.raidCooldown = 0f
        run(g, 0.3f)
        assertEquals(1, g.mobs.raidWave)
        assertTrue(g.mobs.list.count { it.type == MobType.RAIDER } >= 3, "wave one")
        val emeralds = Items.find("Emerald")
        for (wave in 1..3) {
            // Defeat every raider in this wave.
            for (r in g.mobs.list.filter { it.type == MobType.RAIDER }) g.mobs.damage(r, 999f, 0f, 0f)
            run(g, 1.5f)
            if (wave < 3) assertEquals(wave + 1, g.mobs.raidWave, "wave ${wave + 1} follows")
        }
        assertEquals(0, g.mobs.raidWave, "the raid is over")
        val reward = g.inventory.count(emeralds) + g.drops.list.filter { it.stack.id == emeralds }.sumOf { it.stack.count }
        assertTrue(reward >= 6, "the reward is paid, got $reward")
    }
}
