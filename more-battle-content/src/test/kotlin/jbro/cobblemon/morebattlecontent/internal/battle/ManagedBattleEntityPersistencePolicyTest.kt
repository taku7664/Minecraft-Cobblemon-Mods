package jbro.cobblemon.morebattlecontent.internal.battle

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManagedBattleEntityPersistencePolicyTest {
    private val managedBattle = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherBattle = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `entity in a registered MBC battle is never saved`() {
        assertTrue(isTransient(battleId = managedBattle, lifecycleOwned = false))
    }

    @Test
    fun `entity recalled after its battle ended stays unsaved while the lifecycle owns it`() {
        assertTrue(isTransient(battleId = null, lifecycleOwned = true))
    }

    @Test
    fun `battles and entities outside MBC keep Cobblemon persistence`() {
        assertFalse(isTransient(battleId = otherBattle, lifecycleOwned = false))
        assertFalse(isTransient(battleId = null, lifecycleOwned = false))
    }

    @Test
    fun `lifecycle ownership is not queried when the battle already decides`() {
        var queried = false
        ManagedBattleEntityPersistencePolicy.isTransient(
            battleId = managedBattle,
            isManagedBattle = { it == managedBattle },
            isLifecycleOwned = { queried = true; false },
        )
        assertFalse(queried)
    }

    @Test
    fun `mixin is wired to the entity save gate used by chunk storage`() {
        val mixin = Files.readString(
            Path.of("src/main/java/jbro/cobblemon/morebattlecontent/internal/mixin/EntityManagedBattlePersistenceMixin.java"),
        )
        assertTrue(mixin.contains("@Mixin(Entity.class)"))
        assertTrue(mixin.contains("method = \"saveAsPassenger\""))
        assertTrue(mixin.contains("Cobblemon173ManagedBattleEntityPersistence.isTransient(pokemonEntity)"))
        assertTrue(mixin.contains("callbackInfo.setReturnValue(false)"))
    }

    private fun isTransient(battleId: UUID?, lifecycleOwned: Boolean): Boolean =
        ManagedBattleEntityPersistencePolicy.isTransient(
            battleId = battleId,
            isManagedBattle = { it == managedBattle },
            isLifecycleOwned = { lifecycleOwned },
        )
}
