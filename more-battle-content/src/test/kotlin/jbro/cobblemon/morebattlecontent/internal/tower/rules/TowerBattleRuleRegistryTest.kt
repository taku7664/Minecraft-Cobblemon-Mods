package jbro.cobblemon.morebattlecontent.internal.tower.rules

import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TowerBattleRuleRegistryTest {
    @Test
    fun `allowed mechanic snapshot distinguishes unmanaged factory tower and pvp battles`() {
        val registry = TowerBattleRuleRegistry()
        val actors = setOf(UUID.randomUUID(), UUID.randomUUID())
        val factoryBattle = UUID.randomUUID()
        val towerBattle = UUID.randomUUID()
        val pvpBattle = UUID.randomUUID()

        assertNull(registry.allowedMechanics(UUID.randomUUID()))
        assertTrue(registry.register(factoryBattle, null, actors))
        assertEquals(emptySet<TowerSubmittedMechanic>(), registry.allowedMechanics(factoryBattle))
        assertTrue(registry.register(towerBattle, MajorBattleMechanic.TERA, actors))
        assertEquals(setOf(TowerSubmittedMechanic.TERA), registry.allowedMechanics(towerBattle))
        assertTrue(
            registry.registerMultiple(
                pvpBattle,
                setOf(TowerSubmittedMechanic.MEGA, TowerSubmittedMechanic.Z_MOVE),
                actors,
            ),
        )
        assertEquals(
            setOf(TowerSubmittedMechanic.MEGA, TowerSubmittedMechanic.Z_MOVE),
            registry.allowedMechanics(pvpBattle),
        )
    }

    private val battleId = UUID.randomUUID()
    private val playerActorId = UUID.randomUUID()
    private val trainerActorId = UUID.randomUUID()

    @Test
    fun `untracked battles are not changed`() {
        val registry = TowerBattleRuleRegistry()

        assertNull(
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(hasBagItem = true, mechanics = listOf(TowerSubmittedMechanic.UNSUPPORTED)),
            ),
        )
    }

    @Test
    fun `tracked battles reject bag items and actors outside the registered sides`() {
        val registry = registered(MajorBattleMechanic.MEGA)

        assertEquals(
            TowerRuleRejection.BAG_ITEMS_DISABLED,
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(hasBagItem = true),
            ),
        )
        assertEquals(
            TowerRuleRejection.ACTOR_NOT_REGISTERED,
            registry.rejectionReason(
                battleId,
                UUID.randomUUID(),
                TowerActionSubmission(),
            ),
        )
    }

    @Test
    fun `only the selected mechanic is accepted and unsupported gimmicks fail closed`() {
        val registry = registered(MajorBattleMechanic.DYNAMAX)

        assertNull(
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.DYNAMAX)),
            ),
        )
        assertEquals(
            TowerRuleRejection.WRONG_MECHANIC,
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.MEGA)),
            ),
        )
        assertEquals(
            TowerRuleRejection.WRONG_MECHANIC,
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.UNSUPPORTED)),
            ),
        )
    }

    @Test
    fun `a regulated battle without a selected mechanic rejects every gimmick`() {
        val registry = TowerBattleRuleRegistry()
        assertTrue(registry.register(battleId, null, setOf(playerActorId, trainerActorId)))

        assertEquals(
            TowerRuleRejection.WRONG_MECHANIC,
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.MEGA)),
            ),
        )
        assertEquals(TowerActorMechanicState(null, false), registry.actorMechanicState(battleId, playerActorId))
    }

    @Test
    fun `double submissions cannot spend more than one mechanic in the same turn`() {
        val registry = registered(MajorBattleMechanic.TERA)

        assertEquals(
            TowerRuleRejection.MULTIPLE_MECHANICS,
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.TERA, TowerSubmittedMechanic.TERA)),
            ),
        )
    }

    @Test
    fun `successful mechanic use is consumed independently for each side`() {
        val registry = registered(MajorBattleMechanic.MEGA)
        val mega = TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.MEGA))

        assertTrue(registry.recordAccepted(battleId, playerActorId, mega))
        assertEquals(
            TowerRuleRejection.MECHANIC_ALREADY_USED,
            registry.rejectionReason(battleId, playerActorId, mega),
        )
        assertNull(registry.rejectionReason(battleId, trainerActorId, mega))
        assertEquals(
            TowerActorMechanicState(MajorBattleMechanic.MEGA, consumed = true),
            registry.actorMechanicState(battleId, playerActorId),
        )
        assertEquals(
            TowerActorMechanicState(MajorBattleMechanic.MEGA, consumed = false),
            registry.actorMechanicState(battleId, trainerActorId),
        )
    }

    @Test
    fun `pvp policy permits every enabled mechanic once per side including z moves`() {
        val registry = TowerBattleRuleRegistry()
        assertTrue(
            registry.registerMultiple(
                battleId,
                setOf(
                    TowerSubmittedMechanic.MEGA,
                    TowerSubmittedMechanic.DYNAMAX,
                    TowerSubmittedMechanic.TERA,
                    TowerSubmittedMechanic.Z_MOVE,
                ),
                setOf(playerActorId, trainerActorId),
            ),
        )
        val firstTurn = TowerActionSubmission(
            mechanics = listOf(TowerSubmittedMechanic.MEGA, TowerSubmittedMechanic.Z_MOVE),
        )
        assertNull(registry.rejectionReason(battleId, playerActorId, firstTurn))
        assertTrue(registry.recordAccepted(battleId, playerActorId, firstTurn))
        assertEquals(
            TowerRuleRejection.MECHANIC_ALREADY_USED,
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.Z_MOVE)),
            ),
        )
        assertNull(
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.TERA)),
            ),
        )
        assertNull(registry.rejectionReason(battleId, trainerActorId, firstTurn))
    }

    @Test
    fun `pvp policy rejects disabled mechanics and duplicate use in one submission`() {
        val registry = TowerBattleRuleRegistry()
        registry.registerMultiple(
            battleId,
            setOf(TowerSubmittedMechanic.MEGA, TowerSubmittedMechanic.Z_MOVE),
            setOf(playerActorId, trainerActorId),
        )

        assertEquals(
            TowerRuleRejection.WRONG_MECHANIC,
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.TERA)),
            ),
        )
        assertEquals(
            TowerRuleRejection.MULTIPLE_MECHANICS,
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(mechanics = listOf(TowerSubmittedMechanic.MEGA, TowerSubmittedMechanic.MEGA)),
            ),
        )
    }

    @Test
    fun `duplicate registration cannot replace active rules and unregister removes them`() {
        val registry = registered(MajorBattleMechanic.MEGA)

        assertTrue(registry.isRegistered(battleId))
        assertEquals(setOf(battleId), registry.registeredBattleIds())

        assertFalse(
            registry.register(
                battleId,
                MajorBattleMechanic.TERA,
                setOf(playerActorId, trainerActorId),
            ),
        )
        assertTrue(registry.unregister(battleId))
        assertFalse(registry.isRegistered(battleId))
        assertTrue(registry.registeredBattleIds().isEmpty())
        assertNull(
            registry.rejectionReason(
                battleId,
                playerActorId,
                TowerActionSubmission(hasBagItem = true),
            ),
        )
    }

    private fun registered(mechanic: MajorBattleMechanic): TowerBattleRuleRegistry =
        TowerBattleRuleRegistry().also {
            assertTrue(it.register(battleId, mechanic, setOf(playerActorId, trainerActorId)))
        }
}
