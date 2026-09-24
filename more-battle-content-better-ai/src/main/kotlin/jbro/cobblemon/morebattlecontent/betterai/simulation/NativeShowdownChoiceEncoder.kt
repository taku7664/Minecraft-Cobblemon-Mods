package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot

/** Translates validated BetterAI actions into Showdown's request choice grammar. */
internal object NativeShowdownChoiceEncoder {
    fun encode(
        action: BattleActionCandidate,
        side: BattleSide,
        frame: NativeBattleFrame,
    ): String = when (action.kind) {
        BattleActionKind.COMPOSITE -> encodeComposite(action, side, frame)
        else -> encodePrimitive(action, side, frame)
    }

    private fun encodeComposite(
        action: BattleActionCandidate,
        side: BattleSide,
        frame: NativeBattleFrame,
    ): String {
        require(action.componentActions.isNotEmpty()) {
            "Native composite choices require their concrete component actions"
        }
        val slots = action.componentActions.map { component ->
            requireNotNull(component.actorSlot) {
                "Every native composite component must identify its active slot"
            }
        }
        require(slots.distinct().size == slots.size) {
            "Native composite choices require one action per active slot"
        }
        val components = action.componentActions.sortedBy { requireNotNull(it.actorSlot) }
        return components.joinToString(", ") { encodePrimitive(it, side, frame) }
    }

    private fun encodePrimitive(
        action: BattleActionCandidate,
        side: BattleSide,
        frame: NativeBattleFrame,
    ): String = when (action.kind) {
        BattleActionKind.USE_MOVE -> encodeMove(action, side, frame)
        BattleActionKind.SWITCH -> encodeSwitch(action, side, frame)
        BattleActionKind.WAIT -> encodePass(action, side, frame)
        BattleActionKind.FORFEIT -> throw IllegalArgumentException(
            "Forfeit is a battle command, not a native Showdown request choice",
        )
        BattleActionKind.COMPOSITE -> throw IllegalArgumentException("Nested native composite choices are unsupported")
    }

    private fun encodeMove(
        action: BattleActionCandidate,
        side: BattleSide,
        frame: NativeBattleFrame,
    ): String {
        val actorSlot = requireNotNull(action.actorSlot)
        val moveSlot = requireNotNull(action.moveSlot)
        val actor = activeTeam(side, frame).singleOrNull { it.activeSlot == actorSlot }
        requireNotNull(actor) { "Native active slot $actorSlot does not exist for $side" }
        val nativeMove = actor.moves.getOrNull(moveSlot)
        requireNotNull(nativeMove) { "Native move slot $moveSlot does not exist for active slot $actorSlot" }
        action.moveId?.let { candidateMove ->
            val candidateId = nativeId(candidateMove)
            val nativeMoveId = nativeId(nativeMove.id)
            require(candidateId == nativeMoveId) {
                "Candidate move $candidateId disagrees with native slot $nativeMoveId"
            }
        }

        val target = when (action.targets.size) {
            0 -> ""
            1 -> " ${relativeTarget(action.targets.single(), side)}"
            else -> ""
        }
        val mechanic = action.mechanic?.let { " ${mechanicSuffix(it.mechanicId)}" }.orEmpty()
        return "move ${moveSlot + 1}$target$mechanic"
    }

    private fun encodeSwitch(
        action: BattleActionCandidate,
        side: BattleSide,
        frame: NativeBattleFrame,
    ): String {
        val actorSlot = requireNotNull(action.actorSlot)
        require(activeTeam(side, frame).any { it.activeSlot == actorSlot }) {
            "Native active slot $actorSlot does not exist for $side"
        }
        val switchId = requireNotNull(action.switchPokemonId).toString()
        val teamSlot = fullTeam(side, frame).indexOfFirst { it.uuid == switchId }
        require(teamSlot >= 0) { "Native switch Pokemon $switchId does not exist for $side" }
        return "switch ${teamSlot + 1}"
    }

    private fun encodePass(
        action: BattleActionCandidate,
        side: BattleSide,
        frame: NativeBattleFrame,
    ): String {
        action.actorSlot?.let { actorSlot ->
            require(activeTeam(side, frame).any { it.activeSlot == actorSlot }) {
                "Native active slot $actorSlot does not exist for $side"
            }
        }
        return "pass"
    }

    private fun relativeTarget(target: BattleTargetSlot, actorSide: BattleSide): Int =
        if (target.side == actorSide) -(target.slot + 1) else target.slot + 1

    private fun mechanicSuffix(mechanicId: String): String = when (nativeId(mechanicId)) {
        "mega" -> "mega"
        "dynamax" -> "dynamax"
        "tera", "terastallize", "terastallization" -> "terastallize"
        else -> throw IllegalArgumentException("Unsupported native battle mechanic $mechanicId")
    }

    private fun activeTeam(side: BattleSide, frame: NativeBattleFrame): List<NativePokemonFrame> = when (side) {
        BattleSide.ALLY -> frame.p1Active
        BattleSide.OPPONENT -> frame.p2Active
    }

    private fun fullTeam(side: BattleSide, frame: NativeBattleFrame): List<NativePokemonFrame> = when (side) {
        BattleSide.ALLY -> frame.p1Team
        BattleSide.OPPONENT -> frame.p2Team
    }

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
