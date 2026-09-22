package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMechanicsKernel
import jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicAbilityState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalFlagAbilityImmunityTest {
    @Test
    fun `wind rider blocks wind moves but not ordinary flying moves`() {
        assertTrue(project("heatwave", "fire", "windrider", setOf("wind")).publiclyNullified)
        assertFalse(project("bravebird", "flying", "windrider").publiclyNullified)
    }

    @Test
    fun `bulletproof and soundproof use move flags rather than move type`() {
        assertTrue(project("shadowball", "ghost", "bulletproof", setOf("bullet")).publiclyNullified)
        assertTrue(project("boomburst", "normal", "soundproof", setOf("sound")).publiclyNullified)
        assertFalse(project("tackle", "normal", "soundproof").publiclyNullified)
    }

    @Test
    fun `good as gold blocks hostile status and magic bounce recognizes reflectable moves`() {
        assertTrue(projectStatus("thunderwave", "goodasgold").publiclyNullified)
        assertTrue(projectStatus("thunderwave", "magicbounce", setOf("reflectable")).publiclyNullified)
    }

    @Test
    fun `soundproof does not block the users own sound move`() {
        val selfMove = move(
            "clangoroussoul",
            "dragon",
            setOf("sound"),
            BattleMoveDamageCategory.STATUS,
            target = BattleTargetSlot(BattleSide.ALLY, 0),
            pattern = BattleMoveTargetPattern.SELF,
        )
        assertFalse(
            LocalPublicMechanicsKernel.projectMove(
                selfMove,
                context(defenderAbility = null, candidate = selfMove, actorAbility = "soundproof"),
            ).publiclyNullified,
        )
    }

    @Test
    fun `neutralizing gas suppresses levitate unless ability shield protects it`() {
        assertFalse(projectWithNeutralizingGas(targetItem = null).publiclyNullified)
        assertTrue(projectWithNeutralizingGas(targetItem = "abilityshield").publiclyNullified)
        assertTrue(projectWithNeutralizingGas(targetItem = null, gasGastroAcid = true).publiclyNullified)
    }

    @Test
    fun `gastro acid disables neutralizing gas itself`() {
        val gas = mon(
            BattleSide.ALLY,
            "neutralizinggas",
            volatiles = setOf("gastroacid"),
        )
        val state = BattleStateView(
            UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(gas), BattleFieldStateView.empty(),
            BattleSide.entries.associateWith { 1 }, emptyList(), emptyList(),
        )

        assertNull(LocalPublicAbilityState.effectiveKnownAbility(state, gas))
    }

    private fun project(moveId: String, type: String, defenderAbility: String, flags: Set<String> = emptySet()) =
        LocalPublicMechanicsKernel.projectMove(
            move(moveId, type, flags),
            context(defenderAbility, move(moveId, type, flags)),
        )

    private fun projectStatus(moveId: String, defenderAbility: String, flags: Set<String> = emptySet()) =
        move(moveId, "electric", flags, BattleMoveDamageCategory.STATUS).let { candidate ->
            LocalPublicMechanicsKernel.projectMove(candidate, context(defenderAbility, candidate))
        }

    private fun context(
        defenderAbility: String?,
        candidate: BattleActionCandidate,
        actorAbility: String? = null,
    ): BattleDecisionContext {
        val actor = mon(BattleSide.ALLY, actorAbility)
        val target = mon(BattleSide.OPPONENT, defenderAbility)
        return BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(actor, target), BattleFieldStateView.empty(),
                BattleSide.entries.associateWith { 1 }, emptyList(), emptyList(),
            ),
            candidates = listOf(candidate), deadlineEpochMillis = Long.MAX_VALUE,
        )
    }

    private fun projectWithNeutralizingGas(
        targetItem: String?,
        gasGastroAcid: Boolean = false,
    ): jbro.cobblemon.morebattlecontent.betterai.mechanics.LocalPublicMoveProjection {
        val candidate = move("earthquake", "ground", emptySet(), target = BattleTargetSlot(BattleSide.OPPONENT, 0))
        val actor = mon(BattleSide.ALLY, null, slot = 0, types = setOf("ground"))
        val gas = mon(
            BattleSide.ALLY,
            "neutralizinggas",
            slot = 1,
            volatiles = if (gasGastroAcid) setOf("gastroacid") else emptySet(),
        )
        val target = mon(BattleSide.OPPONENT, "levitate", item = targetItem)
        val otherOpponent = mon(BattleSide.OPPONENT, null, slot = 1)
        val context = BattleDecisionContext(
            UUID.randomUUID(),
            BattleStateView(
                UUID.randomUUID(), BattleFormat.DOUBLE, 1, listOf(actor, gas, target, otherOpponent),
                BattleFieldStateView.empty(), BattleSide.entries.associateWith { 2 }, emptyList(), emptyList(),
            ),
            listOf(candidate), Long.MAX_VALUE,
        )
        return LocalPublicMechanicsKernel.projectMove(candidate, context)
    }

    private fun move(
        id: String,
        type: String,
        flags: Set<String>,
        category: BattleMoveDamageCategory = BattleMoveDamageCategory.SPECIAL,
        target: BattleTargetSlot = BattleTargetSlot(BattleSide.OPPONENT, 0),
        pattern: BattleMoveTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
    ) = BattleActionCandidate(
        actionId = id, kind = BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = 0,
        moveId = id, targets = listOf(target),
        moveDetails = BattleMoveCandidateView(
            type, category, if (category == BattleMoveDamageCategory.STATUS) 0.0 else 90.0, 100.0, 0, 10,
            pattern,
            effects = BattleMoveEffectsView(
                coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                effects = emptyList(),
                scriptedBehavior = false,
                mechanicFlags = flags,
            ),
        ),
    )

    private fun mon(
        side: BattleSide,
        ability: String?,
        slot: Int = 0,
        item: String? = null,
        types: Set<String> = setOf("normal"),
        volatiles: Set<String> = emptySet(),
    ) = BattlePokemonStateView(
        UUID.randomUUID(), side, slot, "probe", null, 50, 1.0, null, emptyMap(), emptySet(), ability, item,
        false, types, if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(150, 100, 100, 100, 100, 100)
        } else {
            BattleCombatStatRangesView(
                BattleIntegerRange(140, 160), BattleIntegerRange(90, 110), BattleIntegerRange(90, 110),
                BattleIntegerRange(90, 110), BattleIntegerRange(90, 110), BattleIntegerRange(90, 110),
                BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        }, knownVolatileEffectIds = volatiles,
    )
}
