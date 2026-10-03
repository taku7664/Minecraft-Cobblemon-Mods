package jbro.cobblemon.mcc.betterai.mechanics

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleDecisionContext
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView

/**
 * Leaves Terastallization and Mega Evolution on the Pokemon after the turn they are used.
 *
 * The legacy projector applied a mechanic only to the damage of the move it was attached to; the
 * next projected turn saw the original types and form again, so a defensive Tera or a Mega's new
 * typing never existed past its first attack. Showdown resolves both before any move of the turn, so
 * they are applied once here, after switches and before moves, from facts the candidate already
 * carries. Dynamax is not projected: its HP, Max Moves and duration live in the native path only.
 */
internal object LocalMechanicActivationProjector {
    /**
     * Candidate-local Mega state for root calculations, using the same activation as turn search.
     * Joint choices activate every Mega before either partner's move is evaluated. Already evolved
     * forms are unchanged, so passing this view through several calculation layers cannot restart
     * an entry ability. Other mechanics keep their existing root interpretation.
     */
    fun forMegaCandidate(
        context: BattleDecisionContext,
        side: BattleSide,
        action: BattleActionCandidate,
    ): BattleDecisionContext {
        if (action.mechanic == null && action.kind != BattleActionKind.COMPOSITE) return context
        val primitives = if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)
        val state = primitives.fold(context.state) { current, primitive ->
            if (primitive.mechanic?.let { mechanicKind(it.mechanicId) } == MEGA) {
                activate(current, side, primitive)
            } else current
        }
        return if (state === context.state) context else context.copy(state = state)
    }

    fun beforeMoves(
        state: BattleStateView,
        side: BattleSide,
        action: BattleActionCandidate,
    ): BattleStateView {
        val primitives = if (action.kind == BattleActionKind.COMPOSITE) action.componentActions else listOf(action)
        return primitives.fold(state) { current, primitive -> activate(current, side, primitive) }
    }

    private fun activate(state: BattleStateView, side: BattleSide, action: BattleActionCandidate): BattleStateView {
        val mechanic = action.mechanic ?: return state
        if (action.kind != BattleActionKind.USE_MOVE) return state
        val actor = state.pokemon.singleOrNull {
            it.side == side && it.activeSlot == action.actorSlot && !it.fainted && it.hpFraction > 0.0
        } ?: return state
        val updated = when (mechanicKind(mechanic.mechanicId)) {
            TERA -> terastallize(actor, action)
            MEGA -> megaEvolve(actor, action)
            else -> null
        } ?: return state
        val evolved = state.copyState(pokemon = state.pokemon.map { if (it.battlePokemonId == actor.battlePokemonId) updated else it })
        // A Mega's new ability starts as it evolves: Charizard-Y's Drought, Tyranitar's Sand Stream.
        return if (mechanicKind(mechanic.mechanicId) == MEGA && updated.knownAbilityId != null) {
            jbro.cobblemon.mcc.betterai.state.LocalEntryAbilityProjector.project(evolved, actor.battlePokemonId)
        } else evolved
    }

    private fun terastallize(actor: BattlePokemonStateView, action: BattleActionCandidate): BattlePokemonStateView? {
        if (actor.knownTeraTypeId != null) return null
        val teraType = LocalMechanicFormResolution.transformedTypeIds(action, actor).singleOrNull() ?: return null
        val stellar = canonical(teraType) == STELLAR
        return actor.copyState(
            // Stellar keeps the defensive typing; every other Tera type replaces it.
            knownTypeIds = if (stellar) actor.knownTypeIds else setOf(teraType),
            knownBaseStabTypeIds = actor.knownBaseStabTypeIds,
            knownTeraTypeId = teraType,
            knownStellarBoostedTypeIds = if (stellar) emptySet() else actor.knownStellarBoostedTypeIds,
        )
    }

    private fun megaEvolve(actor: BattlePokemonStateView, action: BattleActionCandidate): BattlePokemonStateView? {
        val form = LocalMechanicFormResolution.transformedForm(action, actor) ?: return null
        if (canonical(actor.formId) == canonical(form.formId)) return null
        return BattlePokemonStateView(
            battlePokemonId = actor.battlePokemonId,
            side = actor.side,
            activeSlot = actor.activeSlot,
            speciesId = actor.speciesId,
            formId = form.formId,
            level = actor.level,
            hpFraction = actor.hpFraction,
            statusId = actor.statusId,
            statStages = actor.statStages,
            knownMoveIds = actor.knownMoveIds,
            // A Mega has one ability, carried with its form. Unknown, keeping the old one would be a guess.
            knownAbilityId = form.abilityId,
            knownHeldItemId = actor.knownHeldItemId,
            fainted = actor.fainted,
            knownTypeIds = form.knownTypeIds,
            combatStats = form.combatStats,
            knownFormStates = actor.knownFormStates,
            actionConstraints = actor.actionConstraints,
            knownVolatileEffectIds = actor.knownVolatileEffectIds,
            knownBaseStabTypeIds = form.knownTypeIds,
            knownTeraTypeId = actor.knownTeraTypeId,
            knownStellarBoostedTypeIds = actor.knownStellarBoostedTypeIds,
            knownSubstituteHpFractionRange = actor.knownSubstituteHpFractionRange,
        )
    }

    private fun mechanicKind(value: String): String = when (val id = canonical(value)) {
        "tera", "terastallize", "terastallization" -> TERA
        "mega", "megaevolution" -> MEGA
        else -> id
    }

    private fun canonical(value: String?): String =
        PublicIds.canonical(value.orEmpty())

    private const val TERA = "tera"
    private const val MEGA = "mega"
    private const val STELLAR = "stellar"
}
