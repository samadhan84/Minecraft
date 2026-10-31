import com.vishucraft.game.render.TextureAtlas
import com.vishucraft.game.world.Tiles
import kotlin.test.*

/** Every texture fits in the atlas (when it didn't, the game failed to start on every device). */
class AtlasTest {
    @Test fun allTexturesFit() {
        val px = TextureAtlas.pixels
        assertEquals(TextureAtlas.SIZE * TextureAtlas.SIZE, px.size)
        val used = Tiles.all().size
        println("atlas tiles used: $used of ${TextureAtlas.TILES_PER_ROW * TextureAtlas.TILES_PER_ROW}")
        assertTrue(used < TextureAtlas.TILES_PER_ROW * TextureAtlas.TILES_PER_ROW)
    }
}
