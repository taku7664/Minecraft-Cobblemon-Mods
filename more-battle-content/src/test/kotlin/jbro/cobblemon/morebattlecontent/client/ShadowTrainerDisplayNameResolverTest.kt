package jbro.cobblemon.morebattlecontent.client

import com.cobblemon.mod.common.api.battles.model.actor.ActorType
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.client.battle.ClientBattle
import com.cobblemon.mod.common.client.battle.ClientBattleActor
import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.shadow.ShadowTrainerProjection
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ShadowTrainerDisplayNameResolverTest {
    @Test
    fun `shadow keeps the player appearance profile but uses the opposing battle actor name`() {
        val battleId = UUID.randomUUID()
        val playerId = UUID.randomUUID()
        val projection = projection(battleId, playerId)
        val battle = ClientBattle(battleId, BattleFormat.GEN_9_SINGLES)
        val playerActor = ClientBattleActor("p1", Component.literal("Park_JH"), playerId, ActorType.PLAYER)
        val trainerActor = ClientBattleActor(
            "p2",
            Component.translatable("trainer.cobblemon_more_battle_content.tower_trainer_001"),
            UUID.randomUUID(),
            ActorType.NPC,
        )
        battle.side1.actors.add(playerActor)
        battle.side2.actors.add(trainerActor)

        val displayName = ShadowTrainerDisplayNameResolver.resolve(projection, battle, playerId)

        assertSame(trainerActor.displayName, displayName)
        assertEquals("Park_JH", projection.profileName)
        assertEquals(playerId, projection.profileId)
    }

    @Test
    fun `shadow does not borrow a name from a different battle`() {
        val playerId = UUID.randomUUID()
        val battle = ClientBattle(UUID.randomUUID(), BattleFormat.GEN_9_SINGLES)

        assertNull(ShadowTrainerDisplayNameResolver.resolve(projection(UUID.randomUUID(), playerId), battle, playerId))
    }

    private fun projection(battleId: UUID, playerId: UUID) = ShadowTrainerProjection(
        battleId = battleId,
        profileId = playerId,
        profileName = "Park_JH",
        x = 4.5,
        y = 72.0,
        z = 14.5,
        yaw = 180.0F,
    )
}
