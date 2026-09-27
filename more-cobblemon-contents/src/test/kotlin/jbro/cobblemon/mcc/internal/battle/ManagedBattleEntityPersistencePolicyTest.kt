package jbro.cobblemon.mcc.internal.battle

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
    fun `entity in a registered MCC battle is never saved`() {
        assertTrue(isTransient(battleId = managedBattle, lifecycleOwned = false))
    }

    @Test
    fun `entity recalled after its battle ended stays unsaved while the lifecycle owns it`() {
        assertTrue(isTransient(battleId = null, lifecycleOwned = true))
    }

    @Test
    fun `battle clone is never saved after its battle and lifecycle entry are gone`() {
        // Singleplayer quit: the battle is over and cleanup could not find the entity to discard it.
        assertTrue(isTransient(battleClone = true, battleId = null, lifecycleOwned = false))
        assertTrue(isTransient(battleClone = true, battleId = otherBattle, lifecycleOwned = false))
    }

    @Test
    fun `battles and entities outside MCC keep Cobblemon persistence`() {
        assertFalse(isTransient(battleId = otherBattle, lifecycleOwned = false))
        assertFalse(isTransient(battleId = null, lifecycleOwned = false))
    }

    @Test
    fun `registries are not queried for a battle clone`() {
        var queried = false
        ManagedBattleEntityPersistencePolicy.isTransient(
            isBattleClone = true,
            battleId = managedBattle,
            isManagedBattle = { queried = true; true },
            isLifecycleOwned = { queried = true; false },
        )
        assertFalse(queried)
    }

    @Test
    fun `lifecycle ownership is not queried when the battle already decides`() {
        var queried = false
        ManagedBattleEntityPersistencePolicy.isTransient(
            isBattleClone = false,
            battleId = managedBattle,
            isManagedBattle = { it == managedBattle },
            isLifecycleOwned = { queried = true; false },
        )
        assertFalse(queried)
    }

    @Test
    fun `mixin is wired to the entity save gate used by chunk storage`() {
        val mixin = Files.readString(
            Path.of("src/main/java/jbro/cobblemon/mcc/internal/mixin/EntityManagedBattlePersistenceMixin.java"),
        )
        assertTrue(mixin.contains("@Mixin(Entity.class)"))
        assertTrue(mixin.contains("method = \"saveAsPassenger\""))
        assertTrue(mixin.contains("Cobblemon173ManagedBattleEntityPersistence.isTransient(pokemonEntity)"))
        assertTrue(mixin.contains("callbackInfo.setReturnValue(false)"))
        val adapter = Files.readString(
            Path.of("src/main/kotlin/jbro/cobblemon/mcc/internal/compat/cobblemon173/Cobblemon173ManagedBattleEntityPersistence.kt"),
        )
        assertTrue(adapter.contains("isBattleClone = entity.isBattleClone()"))
    }

    private fun isTransient(battleId: UUID?, lifecycleOwned: Boolean, battleClone: Boolean = false): Boolean =
        ManagedBattleEntityPersistencePolicy.isTransient(
            isBattleClone = battleClone,
            battleId = battleId,
            isManagedBattle = { it == managedBattle },
            isLifecycleOwned = { lifecycleOwned },
        )
}
