package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleMoveCandidateView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveCandidatePoolView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleMoveDamageCategory
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectCoverage
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectTarget
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectView
import jbro.cobblemon.mcc.internal.ai.BattleMoveEffectsView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveGroup
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveInferenceView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveSlotView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentMoveSource
import jbro.cobblemon.mcc.internal.ai.BattleStatusMoveCategory
import jbro.cobblemon.mcc.internal.ai.BattleTrainerTier
import jbro.cobblemon.mcc.betterai.state.LocalMoveUsageLookup
import jbro.cobblemon.mcc.betterai.state.LocalStatusMoveBinder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LocalStatusMoveBinderTest {
    @Test
    fun `introductory never imagines an opponent status move`() {
        val inference = inference(guess(2), guess(3))

        assertSame(inference, bind(inference, BattleTrainerTier.INTRODUCTORY))
    }

    @Test
    fun `standard names only a near certain signature status move`() {
        val bound = bind(inference(guess(2), other(3)), BattleTrainerTier.STANDARD)
        assertEquals("spore", bound.slots[2].moveId)
        assertEquals(BattleOpponentMoveKnowledge.EXPECTED, bound.slots[2].knowledge)
        assertEquals(BattleStatusMoveCategory.STATUS_INFLICTION, bound.slots[2].statusCategory)

        val withoutSignature = inference(guess(2), other(3))
        val uncommon = LocalMoveUsageLookup { _, _, move -> if (move == "spore") 0.5 else USAGE.rate("", null, move) }
        assertSame(withoutSignature, LocalStatusMoveBinder.bind(
            withoutSignature, SPECIES, null, LEARNSET, BattleTrainerTier.STANDARD, uncommon))
    }

    @Test
    fun `advanced fills each known status slot with the most used plausible move`() {
        val bound = bind(inference(guess(2), guess(3)), BattleTrainerTier.ADVANCED)

        assertEquals(listOf("spore", "synthesis"), bound.slots.drop(2).map { it.moveId })
    }

    @Test
    fun `boss follows the read category even over a more used move`() {
        val bound = bind(
            inference(guess(2, BattleStatusMoveCategory.PROTECTION), guess(3, BattleStatusMoveCategory.RECOVERY)),
            BattleTrainerTier.BOSS,
        )

        assertEquals(listOf("protect", "synthesis"), bound.slots.drop(2).map { it.moveId })
        assertEquals(
            listOf(BattleStatusMoveCategory.PROTECTION, BattleStatusMoveCategory.RECOVERY),
            bound.slots.drop(2).map { it.statusCategory },
        )
    }

    @Test
    fun `a category with no used move and a species without usage stay unnamed`() {
        val hazard = inference(guess(2, BattleStatusMoveCategory.HAZARD), other(3))
        assertSame(hazard, bind(hazard, BattleTrainerTier.BOSS))

        val unknownSpecies = inference(guess(2), guess(3))
        assertSame(unknownSpecies, LocalStatusMoveBinder.bind(
            unknownSpecies, SPECIES, null, LEARNSET, BattleTrainerTier.BOSS, LocalMoveUsageLookup { _, _, _ -> null }))
    }

    @Test
    fun `catalog binding names status slots from the matching public learnset pool`() {
        val opponent = BattlePokemonStateView(OPPONENT, BattleSide.OPPONENT, 0, SPECIES, null, 50, 1.0, null,
            emptyMap(), emptySet(), null, null, false)
        val state = BattleStateView(UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(opponent),
            BattleFieldStateView.empty(), mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to 1), emptyList(), emptyList())
        val catalog = BattlePublicActionCatalogView(
            emptyList(),
            candidatePools = listOf(BattlePublicMoveCandidatePoolView(
                OPPONENT, SPECIES, null, LEARNSET.keys, "fixture", LEARNSET)),
        ).withOpponentMoveInferences(listOf(inference(guess(2), other(3))))

        val bound = LocalStatusMoveBinder.bindCatalog(state, catalog, BattleTrainerTier.STANDARD, USAGE)

        assertEquals("spore", bound.inferredMovesForPokemon(OPPONENT)?.slots?.get(2)?.moveId)
        assertSame(catalog, LocalStatusMoveBinder.bindCatalog(state, catalog, BattleTrainerTier.INTRODUCTORY, USAGE))
    }

    private fun bind(inference: BattleOpponentMoveInferenceView, tier: BattleTrainerTier) =
        LocalStatusMoveBinder.bind(inference, SPECIES, null, LEARNSET, tier, USAGE)

    private fun inference(vararg tail: BattleOpponentMoveSlotView) = BattleOpponentMoveInferenceView(
        OPPONENT,
        listOf(
            BattleOpponentMoveSlotView(0, "gigadrain", BattleOpponentMoveGroup.STAB_ATTACK,
                BattleOpponentMoveKnowledge.EXPECTED, BattleOpponentMoveSource.LEARNSET_EXPECTATION, attack()),
            BattleOpponentMoveSlotView(1, "sludgebomb", BattleOpponentMoveGroup.STAB_ATTACK,
                BattleOpponentMoveKnowledge.EXPECTED, BattleOpponentMoveSource.LEARNSET_EXPECTATION, attack()),
        ) + tail,
    )

    private fun guess(slot: Int, category: BattleStatusMoveCategory? = null) = BattleOpponentMoveSlotView(
        slot, null, BattleOpponentMoveGroup.STATUS_OTHER, BattleOpponentMoveKnowledge.GUESS,
        BattleOpponentMoveSource.GROUP_GUESS, statusCategory = category,
    )

    private fun other(slot: Int) = BattleOpponentMoveSlotView(
        slot, null, BattleOpponentMoveGroup.OTHER, BattleOpponentMoveKnowledge.GUESS, BattleOpponentMoveSource.GROUP_GUESS,
    )

    private companion object {
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000701")
        const val SPECIES = "cobblemon:amoonguss"

        fun attack() = BattleMoveCandidateView("grass", BattleMoveDamageCategory.SPECIAL, 75.0, 100.0, 0, 10)

        fun status(effect: BattleMoveEffectView) = BattleMoveCandidateView(
            "grass", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, listOf(effect), false),
        )

        val LEARNSET = mapOf(
            "gigadrain" to attack(),
            "sludgebomb" to attack(),
            "spore" to status(BattleMoveEffectView(
                BattleMoveEffectKind.STATUS, BattleMoveEffectTarget.SELECTED_TARGET, 1.0, valueId = "slp")),
            "synthesis" to status(BattleMoveEffectView(BattleMoveEffectKind.STAT_STAGE, BattleMoveEffectTarget.SELECTED_TARGET,
                statStages = mapOf("speed" to -1))).copy(effects = null),
            "protect" to status(BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER)),
            "stealthrock" to status(BattleMoveEffectView(BattleMoveEffectKind.SIDE_CONDITION, BattleMoveEffectTarget.TARGET_SIDE,
                valueId = "stealthrock")),
        )

        /** Amoonguss-like singles usage; Stealth Rock is legal but never used. */
        val USAGE = LocalMoveUsageLookup { _, _, move ->
            when (move) {
                "spore" -> 0.92
                "synthesis" -> 0.57
                "protect" -> 0.21
                "gigadrain", "sludgebomb" -> 0.4
                else -> null
            }
        }
    }
}
