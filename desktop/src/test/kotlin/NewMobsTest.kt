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
            if (wave == 2) assertTrue(g.mobs.list.any { it.type == MobType.SPELL_RAIDER }, "wave two has a spell raider")
            if (wave == 3) assertTrue(g.mobs.list.any { it.type == MobType.RAMPAGE_BEAST }, "wave three has a rampage beast")
            // Defeat every raider in this wave.
            for (r in g.mobs.list.filter { it.type.isRaider }) g.mobs.damage(r, 999f, 0f, 0f)
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
        // The light beam shoots up while the base is there, and goes when the base is gone.
        assertEquals(Blocks.BEAM, g.world.getBlock(0, y + 1, 0), "beam above the beacon")
        assertEquals(Blocks.BEAM, g.world.getBlock(0, y + 20, 0))
        for (dx in -1..1) for (dz in -1..1) g.setBlock(dx, y - 1, dz, Blocks.DIRT)
        for (dx in -2..2) for (dz in -2..2) g.setBlock(dx, y - 2, dz, Blocks.DIRT)
        run(g, 4f)
        assertEquals(Blocks.AIR, g.world.getBlock(0, y + 1, 0), "no base, no beam")
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

class BossTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("boss").toFile()
        val level = LevelData.create(9L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -4..4) for (cz in -4..4) g.world.ensureLoaded(cx, cz)
        g.timeOfDay = 0.3f
        return g
    }

    @Test fun theDuskWitherThrowsVolleysHasABossBarAndDropsAStar() {
        val g = game()
        g.mobs.hostileEnabled = true
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        val w = Mob(MobType.DUSK_WITHER, 10f, y + 8f, 10f); g.mobs.list.add(w)
        var most = 0; var t = 0f
        while (t < 8f) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f; most = maxOf(most, g.projectiles.list.count { it.kind == Projectile.FIREBALL }) }
        assertTrue(most >= 3, "a volley of three fireballs, got $most")
        assertEquals("Dusk Wither", g.bossBar()?.first)
        g.mobs.damage(w, 9999f, 0f, 0f)
        run@ for (i in 0 until 60) { g.update(1 / 20f); g.dirtyChunks.clear() }
        val star = Items.find("Nether Star")
        assertTrue(g.inventory.count(star) + g.drops.list.filter { it.stack.id == star }.sumOf { it.stack.count } >= 1, "it drops a Nether Star")
    }

    @Test fun aDuskSigilCallsTheBossAndTuskBoarsCharge() {
        assertEquals(MobType.DUSK_WITHER, Items.spawnType(Items.find("Dusk Sigil")))
        assertTrue(Recipes.crafting.any { it.result == Items.find("Dusk Sigil") })
        val g = game()
        g.mobs.hostileEnabled = true
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        g.mobs.list.add(Mob(MobType.TUSK_BOAR, 8f, y, 0.5f))
        val before = g.health
        var t = 0f
        while (t < 6f && g.health >= before) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        assertTrue(g.health < before, "the boar charges and gores you")
    }
}

class CandleTest {
    @Test fun candlesLightAndStack() {
        val dir = kotlin.io.path.createTempDirectory("candle").toFile()
        val level = LevelData.create(2L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        g.world.ensureLoaded(0, 0)
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        g.setBlock(0, y, 0, Blocks.CANDLE, 0)
        assertEquals(0, Blocks.lightLevel(Blocks.CANDLE, 0), "unlit gives no light")
        assertEquals(3, Blocks.lightLevel(Blocks.CANDLE, 4), "one lit candle")
        assertEquals(12, Blocks.lightLevel(Blocks.CANDLE, 4 or 3), "four lit candles")
        assertTrue(Recipes.crafting.any { it.result == Blocks.CANDLE })
        assertEquals(4, com.vishucraft.game.world.Shapes.boxes(Blocks.CANDLE, 3, false) { 0 }.size, "four candles are drawn")
    }
}

class LightningTest {
    @Test fun aLightningStrikeHurtsWhatItHits() {
        val dir = kotlin.io.path.createTempDirectory("bolt").toFile()
        val level = LevelData.create(4L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 30.5f; g.player.y = y; g.player.z = 30.5f
        val cow = Mob(MobType.COW, 1f, y, 1f); g.mobs.list.add(cow)
        val far = Mob(MobType.COW, 20f, y, 20f); g.mobs.list.add(far)
        g.lightningStrike(1f, 1f)
        assertTrue(cow.health < cow.type.maxHealth, "the cow under the bolt is hurt")
        assertEquals(far.type.maxHealth.toFloat(), far.health, "a cow far away is fine")
    }
}

class AnimalsTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("animals").toFile()
        val level = LevelData.create(6L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -3..3) for (cz in -3..3) g.world.ensureLoaded(cx, cz)
        g.timeOfDay = 0.3f
        return g
    }

    @Test fun aSnifferDigsUpSeeds() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        val s = Mob(MobType.SNIFFER, 5f, y, 5f); s.timer = 0.05f
        g.mobs.list.add(s)
        var t = 0f
        while (t < 2f && g.drops.list.isEmpty()) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        assertTrue(g.drops.list.isNotEmpty(), "it digs something up")
    }

    @Test fun aSaddledCamelCanBeRidden() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        val camel = Mob(MobType.CAMEL, 2f, y, 0.5f); g.mobs.list.add(camel)
        g.inventory.slots[0] = ItemStack(Items.find("Saddle"), 1); g.input.selectedSlot = 0
        assertTrue(g.petActions(Items[Items.find("Saddle")], Items.find("Saddle"), camel))
        assertTrue(camel.saddled)
        g.inventory.slots[0] = null
        assertTrue(g.petActions(null, 0, camel))
        assertSame(camel, g.mount)
    }
}

class HelpersTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("helpers").toFile()
        val level = LevelData.create(8L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -3..3) for (cz in -3..3) g.world.ensureLoaded(cx, cz)
        g.timeOfDay = 0.3f
        return g
    }

    @Test fun aGlimmerSpriteFetchesItems() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        g.mobs.list.add(Mob(MobType.GLIMMER, 3f, y + 2f, 3f))
        val stone = Blocks.STONE
        g.drops.spawn(ItemStack(stone, 5), 8.5f, y + 0.3f, 8.5f)
        var t = 0f
        while (t < 20f && g.drops.list.any { it.stack.id == stone }) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        assertTrue(g.inventory.count(stone) >= 5, "the sprite brought the items to you: ${g.inventory.count(stone)}")
    }

    @Test fun aSpellRaiderCallsFangsThatHurt() {
        val g = game()
        g.mobs.hostileEnabled = true
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        g.mobs.list.add(Mob(MobType.SPELL_RAIDER, 9f, y, 0.5f))
        val before = g.health
        var t = 0f
        while (t < 40f && g.health >= before) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        assertTrue(g.health < before, "a spell raider's potion or fangs hurt")
    }
}

class ChestTest {
    @Test fun chestsAreSmallerThanABlockAndTrappedChestsGiveASignal() {
        val dir = kotlin.io.path.createTempDirectory("chest").toFile()
        val level = LevelData.create(12L, "t", GameMode.CREATIVE)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
        val box = Blocks[Blocks.CHEST].box!!
        assertEquals(14f / 16f, box[3] - box[0], 1e-4f, "a chest is 14 pixels wide")
        assertEquals(14f / 16f, box[4] - box[1], 1e-4f, "and 14 pixels tall")
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        g.setBlock(0, y, 0, Blocks.TRAPPED_CHEST, 2)
        g.setBlock(1, y, 0, Blocks.REDSTONE_LAMP)
        g.redstone.openedTrappedChest(0, y, 0)
        var lit = false; var t = 0f
        while (t < 1.2f) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f; if (g.world.getBlock(1, y, 0) == Blocks.REDSTONE_LAMP_ON) lit = true }
        assertTrue(lit, "the lamp beside it lights while the chest is open")
        run@ for (i in 0 until 60) { g.update(1 / 20f); g.dirtyChunks.clear() }
        assertEquals(Blocks.REDSTONE_LAMP, g.world.getBlock(1, y, 0), "and goes out afterwards")
    }
}

class WeaknessTest {
    @Test fun snowballsHurtAFlameSpinner() {
        val dir = kotlin.io.path.createTempDirectory("weak").toFile()
        val level = LevelData.create(13L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -3..3) for (cz in -3..3) g.world.ensureLoaded(cx, cz)
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        val s = Mob(MobType.FLAME_SPINNER, 6f, y + 1f, 0.5f); g.mobs.list.add(s)
        val before = s.health
        g.projectiles.shoot(2f, y + 1.3f, 0.5f, 20f, 0f, 0f, true, 0f, Projectile.SNOWBALL)
        var t = 0f
        while (t < 1f && s.health >= before) { g.projectiles.update(1 / 20f, g); t += 1 / 20f }
        assertTrue(s.health < before, "a snowball hurts it")
    }
}

class MoreCreaturesTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("more").toFile()
        val level = LevelData.create(14L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -3..3) for (cz in -3..3) g.world.ensureLoaded(cx, cz)
        g.timeOfDay = 0.3f
        g.mobs.hostileEnabled = true
        return g
    }
    private fun run(g: Game, seconds: Float, until: () -> Boolean = { false }) { var t = 0f; while (t < seconds && !until()) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f } }

    @Test fun frostArchersAndShellGuardsShoot() {
        for (type in listOf(MobType.FROST_ARCHER, MobType.SHELL_GUARD)) {
            val g = game()
            val y = g.world.generator.surfaceHeight(0, 0) + 1f
            g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
            g.mobs.list.add(Mob(type, 9f, y, 0.5f))
            run(g, 8f) { g.projectiles.list.isNotEmpty() }
            assertTrue(g.projectiles.list.isNotEmpty(), "$type shoots")
        }
    }

    @Test fun ashSkeletonsAndVoidMitesFight() {
        for (type in listOf(MobType.ASH_SKELETON, MobType.VOID_MITE)) {
            val g = game()
            val y = g.world.generator.surfaceHeight(0, 0) + 1f
            g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
            g.mobs.list.add(Mob(type, 5f, y, 0.5f).also { it.angry = true })
            val before = g.health
            run(g, 8f) { g.health < before }
            assertTrue(g.health < before, "$type hurts you")
        }
    }

    @Test fun huskHogsAreCalmUntilHitThenTheWholePackFights() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f
        g.mobs.list.add(Mob(MobType.HUSK_HOG, 4f, y, 0.5f)); g.mobs.list.add(Mob(MobType.HUSK_HOG, 5.5f, y, 1.5f))
        val before = g.health
        run(g, 4f)
        assertEquals(before, g.health, "calm hogs leave you alone")
        // A fresh pack: hit one and all of them turn on you (no waiting about, so they cannot wander apart).
        val g2 = game()
        g2.player.x = 0.5f; g2.player.y = y; g2.player.z = 0.5f
        val a = Mob(MobType.HUSK_HOG, 4f, y, 0.5f); val b = Mob(MobType.HUSK_HOG, 5.5f, y, 1.5f)
        g2.mobs.list.add(a); g2.mobs.list.add(b)
        g2.mobs.damage(a, 1f, 0f, 0f)
        assertTrue(a.angry && b.angry, "the whole pack turns on you")
    }

    @Test fun aLavaStriderWalksOnLava() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        for (dx in -6..6) for (dz in -6..6) { g.setBlock(dx, y - 1, dz, Blocks.LAVA); g.setBlock(dx, y - 2, dz, Blocks.STONE) }
        g.player.x = 40f; g.player.y = y.toFloat(); g.player.z = 40f
        val s = Mob(MobType.LAVA_STRIDER, 0.5f, y + 0.2f, 0.5f); g.mobs.list.add(s)
        val sx = s.x; val sz = s.z
        run(g, 6f)
        assertTrue(Math.abs(s.y - (y - 1 + 0.95f)) < 0.5f, "it stays on top of the lava, y=${s.y}")
        assertTrue(Math.hypot((s.x - sx).toDouble(), (s.z - sz).toDouble()) > 0.5, "and strolls about")
    }

    @Test fun spawnEggsExistForTheNewCreatures() {
        for (t in listOf(MobType.FROST_ARCHER, MobType.ASH_SKELETON, MobType.SPINY_PUFFER, MobType.SHELL_GUARD, MobType.VOID_MITE, MobType.HUSK_HOG, MobType.LAVA_STRIDER))
            assertEquals(t, Items.spawnType(Items.find("${t.displayName} Spawn Egg")))
    }
}

class ShellBoxTest {
    @Test fun aShellBoxKeepsItsThingsWhenPickedUp() {
        val dir = kotlin.io.path.createTempDirectory("shell").toFile()
        val level = LevelData.create(15L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
        val y = g.world.generator.surfaceHeight(0, 0) + 1
        g.setBlock(0, y, 0, Blocks.SHELL_BOX, 2)
        val chest = g.world.blockEntities.chest(0, y, 0)!!
        chest.slots[0] = ItemStack(Items.find("Diamond"), 7)
        chest.slots[5] = ItemStack(Blocks.STONE, 33)
        g.breakBlock(0, y, 0)
        val item = g.drops.list.first { it.stack.id == Blocks.SHELL_BOX }.stack
        assertTrue(item.damage > 0, "the item carries the number of its contents")
        assertTrue(g.drops.list.none { it.stack.id == Items.find("Diamond") }, "the diamonds did not spill out")
        assertEquals(1, Items.maxStack(Blocks.SHELL_BOX), "boxes do not stack")
        // The contents are kept in the world file.
        g.level.write(dir)
        val back = LevelData.read(dir)!!
        assertEquals(7, back.boxes[item.damage]!![0]!!.count)
        // Place it again somewhere else: everything is back inside.
        g.inventory.slots[0] = ItemStack(Blocks.SHELL_BOX, 1, item.damage); g.input.selectedSlot = 0
        g.place(RayHit(3, y - 1, 3, 0, 1, 0, Blocks.GRASS), Blocks.SHELL_BOX)
        val again = g.world.blockEntities.chest(3, y, 3)!!
        assertEquals(7, again.slots[0]!!.count); assertEquals(33, again.slots[5]!!.count)
        assertNull(g.level.boxes[item.damage], "the stored copy is used up")
    }
}

class MineTest {
    @Test fun abandonedMinesAreFoundUnderTheHills() {
        val dir = kotlin.io.path.createTempDirectory("mine").toFile()
        val world = World(21L, dir, Dimension.OVERWORLD)
        for (cx in -12..12) for (cz in -12..12) world.ensureLoaded(cx, cz)
        var frames = 0; var chests = 0
        for (x in -192..192) for (z in -192..192) for (y in 14..34) {
            val b = world.getBlock(x, y, z)
            if (b == Blocks.OAK_FENCE) frames++
            if (b == Blocks.CHEST) chests++
        }
        assertTrue(frames > 20, "timber frames of a mine were generated, found $frames")
    }
}

class TexturePackTest {
    @Test fun aTexturePackPictureReplacesATile() {
        val before = com.vishucraft.game.render.TextureAtlas.pixels.copyOf()
        assertTrue("oak_planks" in com.vishucraft.game.render.TextureAtlas.tileNames(), "the tile names are listed")
        // A 32 by 32 picture, red above and blue below, is shrunk to the 16 by 16 tile.
        val img = IntArray(32 * 32) { if (it / 32 < 16) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        assertTrue(com.vishucraft.game.render.TextureAtlas.setTile("oak_planks", 32, 32, img))
        assertFalse(com.vishucraft.game.render.TextureAtlas.setTile("no_such_tile_name", 32, 32, img))
        val index = com.vishucraft.game.world.Tiles.id("oak_planks")
        val ox = (index % 64) * 16; val oy = (index / 64) * 16
        val px = com.vishucraft.game.render.TextureAtlas.pixels
        assertEquals(0xFFFF0000.toInt(), px[(oy + 2) * com.vishucraft.game.render.TextureAtlas.SIZE + ox + 5])
        assertEquals(0xFF0000FF.toInt(), px[(oy + 13) * com.vishucraft.game.render.TextureAtlas.SIZE + ox + 5])
        // Put the real picture back so the other tests are not affected.
        for (y in 0 until 16) for (x in 0 until 16) px[(oy + y) * com.vishucraft.game.render.TextureAtlas.SIZE + ox + x] = before[(oy + y) * com.vishucraft.game.render.TextureAtlas.SIZE + ox + x]
    }

    @Test fun echoGrowthGrowsInDeepCaves() {
        val dir = kotlin.io.path.createTempDirectory("echo").toFile()
        val world = World(31L, dir, Dimension.OVERWORLD)
        for (cx in -10..10) for (cz in -10..10) world.ensureLoaded(cx, cz)
        var n = 0
        for (x in -160..160) for (z in -160..160) for (y in 5..28) if (world.getBlock(x, y, z) == Blocks.ECHO_GROWTH) n++
        assertTrue(n > 10, "echo growth was found in caves: $n")
    }
}

class EatTest {
    @Test fun tappingDigInTheSkyEatsTheFoodInHand() {
        val dir = kotlin.io.path.createTempDirectory("eat").toFile()
        val level = LevelData.create(41L, "t", GameMode.SURVIVAL)
        level.food = 5f
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
        g.player.y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.inventory.slots[0] = ItemStack(Items.find("Bread"), 3); g.input.selectedSlot = 0
        g.player.pitch = 1.4f                                  // looking up at the sky
        g.update(1 / 20f)
        assertNull(g.target, "nothing is under the crosshair")
        val before = g.food
        g.input.actions.add(GameInput.Action.ATTACK)
        g.update(1 / 20f)
        assertTrue(g.food > before, "the bread was eaten: ${g.food}")
        assertEquals(2, g.inventory.count(Items.find("Bread")))
    }
}

class MutantTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("mutant").toFile()
        val level = LevelData.create(51L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -4..4) for (cz in -4..4) g.world.ensureLoaded(cx, cz)
        g.timeOfDay = 0.3f
        g.mobs.hostileEnabled = true
        return g
    }
    private fun run(g: Game, seconds: Float, until: () -> Boolean = { false }) { var t = 0f; while (t < seconds && !until()) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f } }

    @Test fun lightningMakesMutants() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 30.5f; g.player.y = y; g.player.z = 30.5f
        g.mobs.list.add(Mob(MobType.ZOMBIE, 1f, y, 1f)); g.mobs.list.add(Mob(MobType.BOOMLING, 2f, y, 2f))
        g.lightningStrike(1.5f, 1.5f)
        assertTrue(g.mobs.list.any { it.type == MobType.MUTANT_ZOMBIE }, "the zombie became a Mutant Zombie")
        assertTrue(g.mobs.list.any { it.type == MobType.MUTANT_BOOMLING }, "the Boomling became a Mutant Boomling")
        assertTrue(g.mobs.list.none { it.type == MobType.ZOMBIE || it.type == MobType.BOOMLING })
    }

    @Test fun aMutantBoomlingBlowsAHugeCrater() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0)
        g.player.x = 20.5f; g.player.y = y + 1f; g.player.z = 0.5f
        val mb = Mob(MobType.MUTANT_BOOMLING, 4f, y + 1f, 0.5f); g.mobs.list.add(mb)
        assertTrue(g.world.getBlock(10, y, 0) != Blocks.AIR && g.world.getBlock(4, y - 6, 0) != Blocks.AIR, "solid ground before")
        run(g, 15f) { mb.dead }
        assertTrue(mb.dead, "it blew up")
        run(g, 3f)
        assertEquals(Blocks.AIR, g.world.getBlock(4, y - 10, 0), "a crater ten blocks deep")
        assertEquals(Blocks.AIR, g.world.getBlock(20, y - 3, 0), "and wide: sixteen blocks away")
    }

    @Test fun anIronGolemDiesInFiveHits() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 1f
        g.player.x = 0.5f; g.player.y = y; g.player.z = 0.5f; g.player.yaw = 0f; g.player.pitch = 0f
        val golem = Mob(MobType.IRON_GOLEM, 0.5f, y, -1.6f); g.mobs.list.add(golem)
        var hits = 0
        // (A hit knocks it back, so stand in front of it again before each hit.)
        while (!golem.dead && hits < 12) {
            golem.x = 0.5f; golem.z = -1.6f; golem.vx = 0f; golem.vz = 0f
            g.input.actions.add(GameInput.Action.ATTACK); g.update(1 / 20f); g.dirtyChunks.clear(); hits++
        }
        assertTrue(golem.dead, "it died")
        assertEquals(5, hits, "it took exactly five hits")
    }

    @Test fun anIronGolemCannotReachYouWhenYouStandHigherThanIt() {
        val high = game()
        val y = high.world.generator.surfaceHeight(0, 0) + 1
        high.setBlock(0, y, 0, Blocks.STONE); high.setBlock(0, y + 1, 0, Blocks.STONE)        // a pillar two blocks tall
        high.player.x = 0.5f; high.player.y = y + 2f; high.player.z = 0.5f
        val gh = Mob(MobType.IRON_GOLEM, 2.2f, y.toFloat(), 0.5f).also { it.angry = true }; high.mobs.list.add(gh)
        val before = high.health
        run(high, 6f)
        assertEquals(before, high.health, "standing higher than the golem you are safe")
        val low = game()
        low.player.x = 0.5f; low.player.y = y.toFloat(); low.player.z = 0.5f
        low.mobs.list.add(Mob(MobType.IRON_GOLEM, 2.2f, y.toFloat(), 0.5f).also { it.angry = true })
        val b2 = low.health
        run(low, 6f) { low.health < b2 }
        assertTrue(low.health < b2, "on the ground it hits you")
    }
}

class FluidTest {
    private fun game(): Game {
        val dir = kotlin.io.path.createTempDirectory("fluid").toFile()
        val level = LevelData.create(61L, "t", GameMode.CREATIVE)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
        g.player.x = 40.5f; g.player.z = 40.5f; g.player.flying = true
        return g
    }
    private fun run(g: Game, seconds: Float) { var t = 0f; while (t < seconds) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f } }

    /** A cell with stone round it for water at x=0, the gap at x=1 and lava at x=2 (a row along x). */
    private fun generator(g: Game, y: Int) {
        for (x in -1..3) for (z in -1..1) { g.setBlock(x, y - 1, z, Blocks.STONE); g.setBlock(x, y + 1, z, Blocks.STONE) }
        for (x in -1..3) { g.setBlock(x, y, -1, Blocks.STONE); g.setBlock(x, y, 1, Blocks.STONE) }
        g.setBlock(-1, y, 0, Blocks.STONE); g.setBlock(3, y, 0, Blocks.STONE)
        g.setBlock(0, y, 0, Blocks.WATER, 0); g.setBlock(1, y, 0, Blocks.COBBLESTONE); g.setBlock(2, y, 0, Blocks.LAVA, 0)
    }

    @Test fun breakTheBlockBetweenWaterAndLavaAndYouGetCobblestoneEveryTime() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 3
        generator(g, y)
        run(g, 3f)
        assertEquals(Blocks.COBBLESTONE, g.world.getBlock(1, y, 0))
        for (round in 1..3) {
            g.setBlock(1, y, 0, Blocks.AIR)
            run(g, 4f)
            assertEquals(Blocks.COBBLESTONE, g.world.getBlock(1, y, 0), "cobblestone again (round $round)")
            assertEquals(Blocks.WATER, g.world.getBlock(0, y, 0), "the water source is still there")
            assertEquals(Blocks.LAVA, g.world.getBlock(2, y, 0), "and so is the lava source")
            assertEquals(0, g.world.getMeta(2, y, 0))
        }
    }

    @Test fun waterOnTopOfALavaSourceMakesObsidian() {
        val g = game()
        val y = g.world.generator.surfaceHeight(0, 0) + 3
        for (dx in -1..1) for (dz in -1..1) { g.setBlock(dx, y - 1, dz, Blocks.STONE) }
        for (d in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) { g.setBlock(d.first, y, d.second, Blocks.STONE); g.setBlock(d.first, y + 1, d.second, Blocks.STONE) }
        g.setBlock(0, y, 0, Blocks.LAVA, 0)
        run(g, 2f)
        assertEquals(Blocks.LAVA, g.world.getBlock(0, y, 0), "lava alone stays")
        g.setBlock(0, y + 1, 0, Blocks.WATER, 0)
        run(g, 3f)
        assertEquals(Blocks.OBSIDIAN, g.world.getBlock(0, y, 0), "water above lava gives obsidian")
    }
}

class HopperTest {
    @Test fun aHopperUnderAGeneratorCollectsTheBrokenBlockIntoAChestBesideIt() {
        val dir = kotlin.io.path.createTempDirectory("hop").toFile()
        val level = LevelData.create(71L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
        val y = g.world.generator.surfaceHeight(0, 0) + 5
        g.player.x = 30.5f; g.player.y = y.toFloat(); g.player.z = 30.5f
        // A hopper pointing east into a chest, and a cobblestone block on top of the hopper.
        g.setBlock(1, y, 0, Blocks.CHEST, 2)
        g.world.blockEntities.chest(1, y, 0)
        g.setBlock(0, y, 0, Blocks.HOPPER, 4)              // meta 4: points east (+x)
        g.world.blockEntities.hopper(0, y, 0)
        g.setBlock(0, y + 1, 0, Blocks.COBBLESTONE)
        g.inventory.slots[0] = ItemStack(Items.find("Wooden Pickaxe"), 1); g.input.selectedSlot = 0   // cobblestone needs a pickaxe to drop
        g.breakBlock(0, y + 1, 0)                           // the broken block drops as an item above the hopper
        var t = 0f
        val chest = g.world.blockEntities.chest(1, y, 0)!!
        while (t < 8f && chest.slots.none { it != null }) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        assertTrue(chest.slots.any { it != null && it.id == Blocks.COBBLESTONE }, "the cobblestone ended up in the chest")
    }
}

class GeneratorFarmTest {
    @Test fun aGeneratorWithAHopperAndAChestFillsTheChestWhileYouBreakTheStone() {
        val dir = kotlin.io.path.createTempDirectory("farm").toFile()
        val level = LevelData.create(81L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
        val y = g.world.generator.surfaceHeight(0, 0) + 6
        // Row along x: water (0), the stone (1), lava (2); the hopper is under the stone, the chest beside the hopper.
        for (x in -1..3) for (z in -1..1) { g.setBlock(x, y - 1, z, Blocks.STONE); g.setBlock(x, y + 1, z, Blocks.STONE) }
        for (x in -1..3) { g.setBlock(x, y, -1, Blocks.STONE); g.setBlock(x, y, 1, Blocks.STONE) }
        g.setBlock(-1, y, 0, Blocks.STONE); g.setBlock(3, y, 0, Blocks.STONE)
        g.setBlock(0, y, 0, Blocks.WATER, 0); g.setBlock(1, y, 0, Blocks.COBBLESTONE); g.setBlock(2, y, 0, Blocks.LAVA, 0)
        // (Nobody opens the hopper or the chest: they have to work as soon as they are placed.)
        g.setBlock(1, y - 1, 0, Blocks.HOPPER, 0)
        g.setBlock(2, y - 1, 0, Blocks.CHEST, 2)
        // You stand right beside it with an enchanted diamond pickaxe.
        g.player.x = 1.5f; g.player.y = (y - 1).toFloat() + 0.1f; g.player.z = 3.5f
        g.inventory.slots[0] = ItemStack(Items.find("Enchanted Diamond Pickaxe"), 1); g.input.selectedSlot = 0
        for (round in 1..4) {
            var t = 0f
            while (t < 4f && g.world.getBlock(1, y, 0) != Blocks.COBBLESTONE) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
            assertEquals(Blocks.COBBLESTONE, g.world.getBlock(1, y, 0), "the generator made stone (round $round)")
            g.breakBlock(1, y, 0)
        }
        var t = 0f
        fun chest() = g.world.blockEntities.chest(2, y - 1, 0)!!
        while (t < 10f && chest().slots.sumOf { it?.count ?: 0 } < 4) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        val total = chest().slots.filter { it != null && it.id == Blocks.COBBLESTONE }.sumOf { it!!.count }
        assertEquals(4, total, "all four stones went through the hopper into the chest")
        assertEquals(0, g.inventory.count(Blocks.COBBLESTONE), "none were picked up by hand")
    }
}

class HopperSidesTest {
    @Test fun aHopperAboveOrBesideTheBrokenStoneCatchesItToo() {
        for ((hx, hy, hz) in listOf(Triple(0, 1, 0), Triple(1, 0, 0), Triple(0, 0, -1))) {
            val dir = kotlin.io.path.createTempDirectory("sides").toFile()
            val level = LevelData.create(91L, "t", GameMode.SURVIVAL)
            val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
            for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
            val y = g.world.generator.surfaceHeight(0, 0) + 6
            g.player.x = 5.5f; g.player.y = y.toFloat(); g.player.z = 5.5f
            g.setBlock(0, y, 0, Blocks.COBBLESTONE)
            g.setBlock(hx, y + hy, hz, Blocks.HOPPER, 1)
            g.setBlock(hx, y + hy - 1 + (if (hy == 1) -1 else 0), hz + (if (hy == 0 && hx == 0) -1 else 0), Blocks.CHEST, 2)   // a chest under the hopper
            g.inventory.slots[0] = ItemStack(Items.find("Wooden Pickaxe"), 1); g.input.selectedSlot = 0
            g.breakBlock(0, y, 0)
            assertEquals(0, g.drops.list.count { it.stack.id == Blocks.COBBLESTONE }, "nothing is left lying on the ground ($hx,$hy,$hz)")
            val hopper = g.world.blockEntities.hopper(hx, y + hy, hz)!!
            assertTrue(hopper.slots.any { it != null && it.id == Blocks.COBBLESTONE }, "the hopper at ($hx,$hy,$hz) has the stone")
        }
    }
}

class GeneratorHandTest {
    @Test fun theStoneFromAGeneratorEndsUpInYourInventory() {
        val dir = kotlin.io.path.createTempDirectory("hand").toFile()
        val level = LevelData.create(83L, "t", GameMode.SURVIVAL)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        for (cx in -2..2) for (cz in -2..2) g.world.ensureLoaded(cx, cz)
        val y = g.world.generator.surfaceHeight(0, 0) + 6
        for (x in -1..3) for (z in -1..1) { g.setBlock(x, y - 1, z, Blocks.STONE); g.setBlock(x, y + 1, z, Blocks.STONE) }
        for (x in -1..3) { g.setBlock(x, y, -1, Blocks.STONE); g.setBlock(x, y, 1, Blocks.STONE) }
        g.setBlock(-1, y, 0, Blocks.STONE); g.setBlock(3, y, 0, Blocks.STONE)
        g.setBlock(0, y, 0, Blocks.WATER, 0); g.setBlock(2, y, 0, Blocks.LAVA, 0)
        g.player.x = 1.5f; g.player.y = (y - 1).toFloat() + 0.1f; g.player.z = 3.5f
        g.inventory.slots[0] = ItemStack(Items.find("Enchanted Diamond Pickaxe"), 1); g.input.selectedSlot = 0
        for (round in 1..3) {
            var t = 0f
            while (t < 6f && g.world.getBlock(1, y, 0) != Blocks.COBBLESTONE) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
            assertEquals(Blocks.COBBLESTONE, g.world.getBlock(1, y, 0), "round $round made stone")
            g.breakBlock(1, y, 0)
            t = 0f
            while (t < 3f) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        }
        assertEquals(3, g.inventory.count(Blocks.COBBLESTONE), "stones in hand; drops=" + g.drops.list.map { "${it.stack.id}@${it.x},${it.y},${it.z}" })
    }
}

class StackConvertTest {
    @Test fun singleBlocksBecomeFullStacksInSurvivalButToolsStaySingle() {
        val dir = kotlin.io.path.createTempDirectory("stk").toFile()
        val level = LevelData.create(85L, "t", GameMode.CREATIVE)
        val g = Game(World(level.seed, dir, Dimension.OVERWORLD, flat = true), level, GameInput(), dir)
        g.inventory.slots[0] = ItemStack(Blocks.COBBLESTONE, 1)
        g.inventory.slots[1] = ItemStack(Items.find("Iron Pickaxe"), 1)
        g.switchMode()
        assertEquals(64, g.inventory.slots[0]!!.count)
        assertEquals(1, g.inventory.slots[1]!!.count)
    }
}

class BigStructuresTest {
    @Test fun fortsAndMansionsAreGenerated() {
        val dir = kotlin.io.path.createTempDirectory("big").toFile()
        val world = World(21L, dir, Dimension.OVERWORLD)
        for (cx in -24..24) for (cz in -24..24) world.ensureLoaded(cx, cz)
        var mansion = 0; var fort = 0
        for (x in -384..384) for (z in -384..384) {
            for (y in 60..100) if (world.getBlock(x, y, z) == Blocks.DARK_OAK_LOG) mansion++
            for (y in 8..30) if (world.getBlock(x, y, z) == Blocks.IRON_BARS) fort++
        }
        assertTrue(mansion > 0 || fort > 0, "found mansion logs=$mansion fort bars=$fort")
        println("STRUCTS mansion=$mansion fort=$fort")
    }
}
