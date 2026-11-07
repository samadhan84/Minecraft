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

    @Test fun aBeaconWithABaseGivesPowersNearby() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        g.setBlock(0, y, 0, Blocks.BEACON)
        run(g, 1.5f); assertFalse(g.hasEffect("swiftness"), "no base, no power")
        for (dx in -1..1) for (dz in -1..1) g.setBlock(dx, y - 1, dz, Blocks.IRON_BLOCK)
        assertEquals(1, g.beaconTiers(0, y, 0))
        g.player.x = 5f; g.player.y = y.toFloat(); g.player.z = 0.5f
        run(g, 1.5f); assertTrue(g.hasEffect("swiftness"))
        assertFalse(g.hasEffect("leaping"))
        for (dx in -2..2) for (dz in -2..2) g.setBlock(dx, y - 2, dz, Blocks.GOLD_BLOCK)
        assertEquals(2, g.beaconTiers(0, y, 0))
        run(g, 1.5f); assertTrue(g.hasEffect("leaping"))
        assertTrue(Recipes.crafting.any { it.result == Blocks.BEACON })
    }

    @Test fun goldHogsFightUnlessYouWearGoldAndTradeForIngots() {
        val g = game()
        g.mobs.hostileEnabled = true
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        val hog = Mob(MobType.GOLD_HOG, 3f, y, 0.5f); g.mobs.list.add(hog)
        val before = g.health
        run(g, 4f)
        assertTrue(g.health < before, "an angry hog hurts you")
        // Wearing gold calms it.
        val g2 = game()
        g2.mobs.hostileEnabled = true
        g2.player.x = 0.5f; g2.player.y = y; g2.player.z = 0.5f
        g2.inventory.armor[3] = ItemStack(Items.find("Golden Helmet"), 1)
        g2.mobs.list.add(Mob(MobType.GOLD_HOG, 3f, y, 0.5f))
        val calm = g2.health
        run(g2, 4f)
        assertEquals(calm, g2.health, "a hog leaves you alone when you wear gold")
        // And trades a gold ingot for something.
        val ingot = Items.find("Gold Ingot")
        g2.inventory.slots[0] = ItemStack(ingot, 2); g2.input.selectedSlot = 0
        val dropsBefore = g2.drops.list.size
        assertTrue(g2.petActions(Items[ingot], ingot, g2.mobs.list.first { it.type == MobType.GOLD_HOG }))
        assertEquals(1, g2.inventory.count(ingot)); assertTrue(g2.drops.list.size > dropsBefore, "the hog gives something back")
    }

    @Test fun seaSentinelsHurtSwimmers() {
        val g = game(flat = false)
        // A pool of water in the air.
        val y = 120
        for (dx in -9..9) for (dz in -9..9) {
            g.setBlock(dx, y - 1, dz, Blocks.STONE)
            for (dy in 0..6) g.setBlock(dx, y + dy, dz, if (Math.abs(dx) == 9 || Math.abs(dz) == 9) Blocks.STONE else Blocks.WATER)
        }
        g.player.x = 0.5f; g.player.y = y + 1f; g.player.z = 0.5f; g.player.flying = false
        g.mobs.hostileEnabled = true
        g.mobs.list.add(Mob(MobType.SEA_SENTINEL, 4f, y + 2f, 0.5f))
        val before = g.health
        run(g, 5f)
        assertTrue(g.health < before, "the beam hits you in the water")
    }
}

class WoodTest {
    @Test fun woodTakesAWhileWithBareHands() {
        val dir = kotlin.io.path.createTempDirectory("wood").toFile()
        val level = LevelData.create(3L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        g.inventory.slots[0] = null; g.input.selectedSlot = 0
        assertTrue(g.mineTime(Blocks.LOG) >= 2f, "a log takes seconds by hand: ${g.mineTime(Blocks.LOG)}")
        assertTrue(g.mineTime(Blocks.PLANKS) >= 2f)
        g.inventory.slots[0] = ItemStack(Items.find("Iron Axe"), 1)
        assertTrue(g.mineTime(Blocks.LOG) < 0.7f, "an axe is much faster: ${g.mineTime(Blocks.LOG)}")
    }
}
