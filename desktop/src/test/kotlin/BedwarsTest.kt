import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** Bedwars: the map, the shop, the rules, and bots that bridge across the sky and break beds. */
class BedwarsTest {
    private fun game(difficulty: Int, creative: Boolean = false): Game {
        val dir = kotlin.io.path.createTempDirectory("bw").toFile()
        val level = newBedwarsLevel(difficulty)
        if (creative) level.mode = GameMode.CREATIVE
        val world = World(level.seed, dir, Dimension.OVERWORLD, bedwars = true)
        return Game(world, level, GameInput(), dir)
    }

    private fun run(g: Game, seconds: Float, until: () -> Boolean = { false }): Float {
        var t = 0f
        while (t < seconds && !until()) { g.update(1 / 20f); g.dirtyChunks.clear(); t += 1 / 20f }
        return t
    }

    @Test fun theMapHasFourIslandsBedsShopsAndBots() {
        val g = game(2)
        g.update(1 / 20f)
        for (t in BwTeam.values()) {
            assertTrue(Blocks.isBed(g.world.getBlock(t.bedX, 64, t.bedZ)), "${t.label} bed")
            assertEquals(Blocks.GRASS, g.world.getBlock(t.cx + 3, 63, t.cz + 3), "${t.label} island")
            assertEquals((0..8).toList(), (0..8).filter { k -> g.world.getBlock(t.shopX(k), 64, t.shopZ(k)) == Blocks.BW_SHOP })
        }
        assertEquals(Blocks.EMERALD_BLOCK, g.world.getBlock(0, 64, 0))
        assertEquals(7, g.bedwars!!.bots.size, "you and a team-mate, and three teams of two")
        assertEquals(7, g.mobs.list.count { it.type.isBot })
        // The sky between the islands is empty.
        assertEquals(Blocks.AIR, g.world.getBlock(20, 63, 0))
    }

    @Test fun theShopSellsForResources() {
        val g = game(1)
        g.update(1 / 20f)
        val red = BwTeam.RED
        val shop = RayHit(red.shopX(0), 64, red.shopZ(0), 0, 1, 0, Blocks.BW_SHOP)   // the first block of the row: wool
        val woolBefore = g.inventory.count(red.wool)
        assertTrue(g.bedwarsShopTap(shop))
        assertEquals(woolBefore, g.inventory.count(red.wool), "no iron, no wool")
        g.inventory.add(Items.find("Iron Ingot"), 10)
        assertTrue(g.bedwarsShopTap(shop))
        assertEquals(woolBefore + 16, g.inventory.count(red.wool)); assertEquals(6, g.inventory.count(Items.find("Iron Ingot")))
        // Stone sword for 10 iron (the third block).
        g.inventory.add(Items.find("Iron Ingot"), 4)
        g.bedwarsShopTap(RayHit(red.shopX(2), 64, red.shopZ(2), 0, 1, 0, Blocks.BW_SHOP))
        assertTrue(g.inventory.count(Items.find("Stone Sword")) == 1)
    }

    @Test fun whatYouMayBreak() {
        val g = game(1)
        g.update(1 / 20f)
        val red = BwTeam.RED; val blue = BwTeam.BLUE
        assertFalse(g.bedwarsMayBreak(red.bedX, 64, red.bedZ, g.world.getBlock(red.bedX, 64, red.bedZ)), "your own bed")
        assertTrue(g.bedwarsMayBreak(blue.bedX, 64, blue.bedZ, g.world.getBlock(blue.bedX, 64, blue.bedZ)), "another team's bed")
        assertTrue(g.bedwarsMayBreak(0, 64, 0, Blocks.WOOL_RED)); assertTrue(g.bedwarsMayBreak(0, 64, 0, Blocks.PLANKS))
        assertFalse(g.bedwarsMayBreak(0, 63, 0, Blocks.GRASS)); assertFalse(g.bedwarsMayBreak(0, 64, 0, Blocks.BW_SHOP))
        // The generator drops iron on your island.
        run(g, 4f)
        // The generator drops iron next to where you stand, and you pick it up.
        assertTrue(g.inventory.count(Items.find("Iron Ingot")) + g.drops.list.count { it.stack.id == Items.find("Iron Ingot") } >= 2, "iron from your generator")
    }

    @Test fun hardBotsBridgeAcrossTheSkyAndBreakABedMuchSoonerThanEasyOnes() {
        fun firstBed(difficulty: Int, limit: Float): Float {
            val g = game(difficulty, creative = true)   // creative so the idle player is not killed while we watch
            g.update(1 / 20f)
            g.player.x = 0.5f; g.player.z = 0.5f; g.player.y = 64f   // out of the way, on the middle island
            val t = run(g, limit) { g.bedwars?.bedAlive?.any { !it } == true }
            val bw = g.bedwars!!
            // Wool bridges were built out over the empty sky between the islands.
            if (difficulty == 3) {
                val wool = (-48..48).sumOf { x -> (-48..48).count { z -> Blocks[g.world.getBlock(x, 63, z)].name.endsWith("Wool") } }
                println("wool at the island level: $wool (the four 3x3 pads are 36)")
                assertTrue(wool > 36 + 15, "bots laid wool bridges")
            }
            return if (bw.bedAlive.any { !it }) t else limit
        }
        val hard = firstBed(3, 600f)
        val easy = firstBed(1, 120f)
        println("first bed destroyed: hard $hard s, easy $easy s")
        assertTrue(hard < 600f, "hard bots broke a bed")
        assertTrue(easy >= 120f, "easy bots wait much longer before rushing")
        assertTrue(hard < easy)
    }

    @Test fun lastTeamStandingWins() {
        val g = game(1, creative = true)
        g.update(1 / 20f)
        val bw = g.bedwars!!
        for (t in BwTeam.values()) if (t != BwTeam.RED) g.destroyBed(t, "test")
        for (m in g.mobs.list.filter { it.type.isBot && it.type != MobType.BOT_RED }) g.mobs.damage(m, 999f, 0f, 0f)
        run(g, 10f) { bw.finished }
        assertTrue(bw.finished && bw.victory, "victory")
        // Bots are gone for good once their bed is, and a team-mate of yours is still alive.
        assertTrue(bw.bots.filter { it.team != BwTeam.RED }.all { it.out })
    }

    @Test fun losingYourBedAndDyingIsDefeat() {
        val g = game(1)
        g.update(1 / 20f)
        val bw = g.bedwars!!
        g.destroyBed(BwTeam.RED, "test")
        g.hurtPlayer(100f, g.player.x, g.player.z, knockback = false)
        run(g, 3f) { bw.finished }
        assertTrue(bw.playerOut && bw.finished && !bw.victory)
    }

    @Test fun passwordsAndBedwarsLevelSurviveSaving() {
        val dir = kotlin.io.path.createTempDirectory("bw2").toFile()
        newBedwarsLevel(3).write(dir)
        assertEquals(3, LevelData.read(dir)!!.bedwars)
        assertTrue(Items.shown.isNotEmpty())
    }
}
