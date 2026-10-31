import com.vishucraft.game.engine.*
import com.vishucraft.game.world.*
import kotlin.test.*

/** The showcase world (everything built, creative only) and the glass car. */
class ShowcaseTest {
    @Test fun glassCarsAreMadeAndPlaced() {
        assertEquals("Glass Car", Vehicle.itemName(Vehicle.GLASS_CAR))
        assertTrue(Items.find("Glass Car") > 0)
        assertTrue(Recipes.crafting.any { it.result == Items.find("Glass Car") })
    }
}
