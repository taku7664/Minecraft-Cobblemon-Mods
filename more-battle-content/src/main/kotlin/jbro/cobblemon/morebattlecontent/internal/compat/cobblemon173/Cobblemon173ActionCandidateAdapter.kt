package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.battles.ActiveBattlePokemon
import com.cobblemon.mod.common.battles.InBattleGimmickMove
import com.cobblemon.mod.common.battles.InBattleMove
import com.cobblemon.mod.common.battles.MoveActionResponse
import com.cobblemon.mod.common.battles.MoveTarget
import com.cobblemon.mod.common.battles.PassActionResponse
import com.cobblemon.mod.common.battles.ShowdownActionResponse
import com.cobblemon.mod.common.battles.ShowdownMoveset
import com.cobblemon.mod.common.battles.SwitchActionResponse
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMechanicCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveCandidateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveTargetPattern
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic

/** Contains every direct Cobblemon 1.7.3 action-request dependency used by the public Brain boundary. */
internal object Cobblemon173ActionCandidateAdapter {
    fun prepare(actor: BattleActor, mechanicPolicy: Cobblemon173MechanicPolicy): Cobblemon173ActionPreparation = try {
        prepareValidated(actor, mechanicPolicy)
    } catch (_: RuntimeException) {
        Cobblemon173ActionPreparation.failed(Cobblemon173ActionPreparationStatus.INVALID_REQUEST)
    }

    private fun prepareValidated(
        actor: BattleActor,
        mechanicPolicy: Cobblemon173MechanicPolicy,
    ): Cobblemon173ActionPreparation {
        val request = actor.request
            ?: return Cobblemon173ActionPreparation.failed(Cobblemon173ActionPreparationStatus.NO_REQUEST)
        val format = when (actor.battle.format.battleType.pokemonPerSide) {
            1 -> BattleFormat.SINGLE
            2 -> BattleFormat.DOUBLE
            else -> return Cobblemon173ActionPreparation.failed(
                Cobblemon173ActionPreparationStatus.UNSUPPORTED_FORMAT,
            )
        }
        if (request.wait) {
            val wait = Cobblemon173ActionChoice(
                candidate = BattleActionCandidate("wait", BattleActionKind.WAIT),
                responses = passResponses(request, actor.activePokemon),
            )
            return Cobblemon173ActionPreparation.ready(
                choices = listOf(wait),
                format = format,
                status = Cobblemon173ActionPreparationStatus.WAITING,
            )
        }

        val slotCount = maxOf(request.active?.size ?: 0, request.forceSwitch.size)
        if (slotCount == 0 || slotCount > format.activeSlotsPerSide || actor.activePokemon.size < slotCount) {
            return Cobblemon173ActionPreparation.failed(Cobblemon173ActionPreparationStatus.INVALID_REQUEST, format)
        }

        val choicesBySlot = List(slotCount) { slot ->
            choicesForSlot(
                actor = actor,
                active = actor.activePokemon[slot],
                moveset = request.active?.getOrNull(slot),
                forceSwitch = request.forceSwitch.getOrElse(slot) { false },
                slot = slot,
                mechanicPolicy = mechanicPolicy,
            )
        }
        if (choicesBySlot.any { it.isEmpty() }) {
            return Cobblemon173ActionPreparation.failed(Cobblemon173ActionPreparationStatus.NO_LEGAL_ACTIONS, format)
        }
        val choices = if (slotCount == 1) choicesBySlot.single() else combine(choicesBySlot)
        if (choices.isEmpty()) {
            return Cobblemon173ActionPreparation.failed(Cobblemon173ActionPreparationStatus.NO_LEGAL_ACTIONS, format)
        }
        return Cobblemon173ActionPreparation.ready(choices, format)
    }

    internal fun allowedGimmick(
        moveset: ShowdownMoveset,
        policy: Cobblemon173MechanicPolicy,
    ): ShowdownMoveset.Gimmick? {
        if (policy.consumed) return null
        val requested = when (policy.selected) {
            MajorBattleMechanic.MEGA -> ShowdownMoveset.Gimmick.MEGA_EVOLUTION
            MajorBattleMechanic.DYNAMAX -> ShowdownMoveset.Gimmick.DYNAMAX
            MajorBattleMechanic.TERA -> ShowdownMoveset.Gimmick.TERASTALLIZATION
            null -> return null
        }
        return requested.takeIf { it in moveset.getGimmicks() }
    }

    /** Matches Cobblemon's AIBattleActor response cardinality, including a zero-slot initial wait. */
    internal fun passResponses(
        request: com.cobblemon.mod.common.battles.ShowdownActionRequest,
        activePokemon: List<ActiveBattlePokemon>,
    ): List<ShowdownActionResponse> = request.iterate(activePokemon) { _, _, _ -> PassActionResponse }

    internal fun combine(choicesBySlot: List<List<Cobblemon173ActionChoice>>): List<Cobblemon173ActionChoice> =
        choicesBySlot.fold(listOf(emptyList<Cobblemon173ActionChoice>())) { combinations, slotChoices ->
            combinations.flatMap { combination -> slotChoices.map { combination + it } }
        }.filter { combination ->
            val switchIds = combination.mapNotNull { it.candidate.switchPokemonId }
            val mechanicUses = combination.count { it.candidate.mechanic != null }
            switchIds.distinct().size == switchIds.size && mechanicUses <= 1
        }.map { combination ->
            val componentCandidates = combination.map { it.candidate }
            val componentIds = componentCandidates.map { it.actionId }
            Cobblemon173ActionChoice(
                candidate = BattleActionCandidate(
                    actionId = "turn:${componentIds.joinToString("|")}",
                    kind = BattleActionKind.COMPOSITE,
                    componentActionIds = componentIds,
                    componentActions = componentCandidates,
                ),
                responses = combination.flatMap { it.responses },
                componentCandidates = componentCandidates,
            )
        }

    private fun choicesForSlot(
        actor: BattleActor,
        active: ActiveBattlePokemon,
        moveset: ShowdownMoveset?,
        forceSwitch: Boolean,
        slot: Int,
        mechanicPolicy: Cobblemon173MechanicPolicy,
    ): List<Cobblemon173ActionChoice> = buildList {
        if (!forceSwitch && moveset != null) {
            val gimmick = allowedGimmick(moveset, mechanicPolicy)
            moveset.moves.forEachIndexed { moveSlot, move ->
                addMoveChoices(active, moveset, forceSwitch, slot, moveSlot, move, null)
                if (gimmick != null) {
                    val transformed = transformedMove(moveset, moveSlot, gimmick)
                    if (gimmick != ShowdownMoveset.Gimmick.DYNAMAX || transformed != null) {
                        addMoveChoices(active, moveset, forceSwitch, slot, moveSlot, move, gimmick, transformed)
                    }
                }
            }
        }
        actor.pokemonList.forEach { pokemon ->
            val response = SwitchActionResponse(pokemon.uuid)
            if (response.isValid(active, moveset, forceSwitch)) {
                add(
                    Cobblemon173ActionChoice(
                        candidate = BattleActionCandidate(
                            actionId = "switch:$slot:${pokemon.uuid}",
                            kind = BattleActionKind.SWITCH,
                            actorSlot = slot,
                            switchPokemonId = pokemon.uuid,
                        ),
                        responses = listOf(response),
                    ),
                )
            }
        }
    }

    private fun MutableList<Cobblemon173ActionChoice>.addMoveChoices(
        active: ActiveBattlePokemon,
        moveset: ShowdownMoveset,
        forceSwitch: Boolean,
        actorSlot: Int,
        moveSlot: Int,
        move: InBattleMove,
        gimmick: ShowdownMoveset.Gimmick?,
        transformed: InBattleGimmickMove? = null,
    ) {
        val targetType = transformed?.target ?: move.target
        val targets = targetType.targetList(active)
            ?.filterIsInstance<ActiveBattlePokemon>()
            ?.takeIf { it.isNotEmpty() }
        if (targets == null) {
            addMoveIfValid(
                active, moveset, forceSwitch, actorSlot, moveSlot, move, gimmick, transformed, targetType, null,
            )
        } else {
            targets.forEach { target ->
                addMoveIfValid(
                    active, moveset, forceSwitch, actorSlot, moveSlot, move, gimmick, transformed, targetType, target,
                )
            }
        }
    }

    private fun MutableList<Cobblemon173ActionChoice>.addMoveIfValid(
        active: ActiveBattlePokemon,
        moveset: ShowdownMoveset,
        forceSwitch: Boolean,
        actorSlot: Int,
        moveSlot: Int,
        move: InBattleMove,
        gimmick: ShowdownMoveset.Gimmick?,
        transformed: InBattleGimmickMove?,
        targetType: MoveTarget,
        target: ActiveBattlePokemon?,
    ) {
        if (!isMoveChoiceAvailable(move, gimmick, transformed)) return
        val targetPnx = target?.getPNX()
        val response = MoveActionResponse(move.id, targetPnx, gimmick?.id)
        if (!response.isValid(active, moveset, forceSwitch)) return
        val targetView = target?.let {
            BattleTargetSlot(
                side = if (it.getSide() == active.getSide()) BattleSide.ALLY else BattleSide.OPPONENT,
                slot = it.getSide().activePokemon.indexOf(it),
            )
        }
        val variantId = gimmick?.id ?: "base"
        add(
            Cobblemon173ActionChoice(
                candidate = BattleActionCandidate(
                    actionId = "move:$actorSlot:$moveSlot:${targetPnx ?: "auto"}:$variantId",
                    kind = BattleActionKind.USE_MOVE,
                    actorSlot = actorSlot,
                    moveSlot = moveSlot,
                    moveId = move.id,
                    moveDetails = moveDetails(move, transformed, targetType),
                    targets = listOfNotNull(targetView),
                    mechanic = gimmick?.let {
                        BattleMechanicCandidate(
                            mechanicId = mechanicId(it),
                            target = null,
                            publicCost = null,
                            transformedMoveId = transformed?.move,
                        )
                    },
                ),
                responses = listOf(response),
            ),
        )
    }

    /**
     * Cobblemon 1.7.3's MoveActionResponse validation treats any available gimmick move as permission to
     * submit the disabled base move too. Keep the selected action's availability authoritative instead.
     */
    internal fun isMoveChoiceAvailable(
        move: InBattleMove,
        gimmick: ShowdownMoveset.Gimmick?,
        transformed: InBattleGimmickMove?,
    ): Boolean = when (gimmick) {
        ShowdownMoveset.Gimmick.DYNAMAX -> transformed != null && !transformed.disabled
        else -> move.canBeUsed()
    }

    private fun transformedMove(
        moveset: ShowdownMoveset,
        moveSlot: Int,
        gimmick: ShowdownMoveset.Gimmick,
    ): InBattleGimmickMove? = when (gimmick) {
        ShowdownMoveset.Gimmick.DYNAMAX -> moveset.maxMoves?.getOrNull(moveSlot)?.takeUnless { it.disabled }
        else -> null
    }

    private fun moveDetails(
        move: InBattleMove,
        transformed: InBattleGimmickMove?,
        target: MoveTarget,
    ): BattleMoveCandidateView? {
        val moveId = transformed?.move ?: move.move
        val template = Moves.getByName(moveId) ?: return null
        val category = when (template.damageCategory.name.lowercase()) {
            "physical" -> BattleMoveDamageCategory.PHYSICAL
            "special" -> BattleMoveDamageCategory.SPECIAL
            "status" -> BattleMoveDamageCategory.STATUS
            else -> return null
        }
        return BattleMoveCandidateView(
            typeId = template.elementalType.name,
            damageCategory = category,
            power = template.power,
            accuracy = publicAccuracy(template.accuracy),
            priority = template.priority,
            currentPp = move.pp,
            targetPattern = publicTargetPattern(target),
            effects = publicMoveEffects(moveId),
        )
    }

    /** A datapack script with the same ID supersedes the embedded Showdown definition. */
    internal fun publicMoveEffects(moveId: String) = Cobblemon173ShowdownMoveEffects.resolve(moveId).takeUnless {
        val canonicalId = moveId.substringAfter(':').lowercase().filter(Char::isLetterOrDigit)
        hasRuntimeMoveOverride(canonicalId)
    }

    /** Cobblemon publishes this JVM method as Kotlin-internal, so the version adapter contains the reflection. */
    private fun hasRuntimeMoveOverride(canonicalId: String): Boolean = runCatching {
        val movesClass = Moves::class.java
        val instance = movesClass.getField("INSTANCE").get(null)
        val scripts = movesClass.getMethod("getMoveScripts\$common").invoke(instance) as Map<*, *>
        scripts.keys.filterIsInstance<String>().any { scriptId ->
                scriptId.lowercase().filter(Char::isLetterOrDigit) == canonicalId
        }
    }.getOrDefault(true)

    private fun mechanicId(gimmick: ShowdownMoveset.Gimmick): String = when (gimmick) {
        ShowdownMoveset.Gimmick.MEGA_EVOLUTION -> MajorBattleMechanic.MEGA.id
        ShowdownMoveset.Gimmick.DYNAMAX -> MajorBattleMechanic.DYNAMAX.id
        ShowdownMoveset.Gimmick.TERASTALLIZATION -> MajorBattleMechanic.TERA.id
        ShowdownMoveset.Gimmick.ULTRA_BURST,
        ShowdownMoveset.Gimmick.Z_POWER,
        -> error("Unsupported major mechanic leaked into the MBC candidate adapter: $gimmick")
    }

    internal fun publicAccuracy(cobblemonAccuracy: Double): Double =
        if (cobblemonAccuracy < 0.0) 100.0 else cobblemonAccuracy

    internal fun publicMoveDetails(moveId: String): BattleMoveCandidateView? {
        val template = Moves.getByName(moveId) ?: return null
        val category = when (template.damageCategory.name.lowercase()) {
            "physical" -> BattleMoveDamageCategory.PHYSICAL
            "special" -> BattleMoveDamageCategory.SPECIAL
            "status" -> BattleMoveDamageCategory.STATUS
            else -> return null
        }
        return BattleMoveCandidateView(
            typeId = template.elementalType.name,
            damageCategory = category,
            power = template.power,
            accuracy = publicAccuracy(template.accuracy),
            priority = template.priority,
            currentPp = 1,
            targetPattern = publicTargetPattern(template.target),
            effects = publicMoveEffects(moveId),
        )
    }

    internal fun publicTargetPattern(target: MoveTarget): BattleMoveTargetPattern =
        when (target) {
            MoveTarget.all -> BattleMoveTargetPattern.ALL_ACTIVE
            MoveTarget.allAdjacent -> BattleMoveTargetPattern.ALL_ADJACENT
            MoveTarget.allAdjacentFoes -> BattleMoveTargetPattern.ALL_OPPONENTS
            MoveTarget.self -> BattleMoveTargetPattern.SELF
            MoveTarget.allies -> BattleMoveTargetPattern.ALL_ALLIES
            MoveTarget.allySide,
            MoveTarget.allyTeam,
            MoveTarget.foeSide,
            -> BattleMoveTargetPattern.SIDE
            MoveTarget.scripted -> BattleMoveTargetPattern.SCRIPTED
            MoveTarget.normal,
            MoveTarget.adjacentFoe,
            -> BattleMoveTargetPattern.SELECTED_OPPONENT
            MoveTarget.adjacentAlly -> BattleMoveTargetPattern.SELECTED_ALLY
            MoveTarget.adjacentAllyOrSelf -> BattleMoveTargetPattern.SELECTED_ALLY_OR_SELF
            MoveTarget.randomNormal -> BattleMoveTargetPattern.RANDOM_OPPONENT
            MoveTarget.any -> BattleMoveTargetPattern.SELECTED
        }

    private val BattleFormat.activeSlotsPerSide: Int
        get() = if (this == BattleFormat.SINGLE) 1 else 2
}

internal data class Cobblemon173MechanicPolicy(
    val selected: MajorBattleMechanic?,
    val consumed: Boolean,
)

internal enum class Cobblemon173ActionPreparationStatus {
    READY,
    NO_REQUEST,
    WAITING,
    UNSUPPORTED_FORMAT,
    INVALID_REQUEST,
    NO_LEGAL_ACTIONS,
}

internal class Cobblemon173ActionPreparation private constructor(
    val status: Cobblemon173ActionPreparationStatus,
    val format: BattleFormat?,
    candidates: List<BattleActionCandidate>,
    responsesByActionId: Map<String, List<ShowdownActionResponse>>,
) {
    val candidates: List<BattleActionCandidate> = candidates.toList()
    private val responsesByActionId: Map<String, List<ShowdownActionResponse>> =
        responsesByActionId.mapValues { (_, responses) -> responses.toList() }.toMap()

    fun responsesFor(actionId: String): List<ShowdownActionResponse>? = responsesByActionId[actionId]?.toList()

    companion object {
        fun ready(
            choices: List<Cobblemon173ActionChoice>,
            format: BattleFormat? = null,
            status: Cobblemon173ActionPreparationStatus = Cobblemon173ActionPreparationStatus.READY,
        ): Cobblemon173ActionPreparation {
            require(
                status == Cobblemon173ActionPreparationStatus.READY ||
                    status == Cobblemon173ActionPreparationStatus.WAITING,
            )
            require(choices.isNotEmpty())
            require(choices.map { it.candidate.actionId }.distinct().size == choices.size)
            require(status == Cobblemon173ActionPreparationStatus.WAITING || choices.all { it.responses.isNotEmpty() })
            return Cobblemon173ActionPreparation(
                status = status,
                format = format,
                candidates = choices.map { it.candidate },
                responsesByActionId = choices.associate { choice ->
                    choice.candidate.actionId to choice.responses
                },
            )
        }

        fun failed(
            status: Cobblemon173ActionPreparationStatus,
            format: BattleFormat? = null,
        ): Cobblemon173ActionPreparation {
            require(
                status != Cobblemon173ActionPreparationStatus.READY &&
                    status != Cobblemon173ActionPreparationStatus.WAITING,
            )
            return Cobblemon173ActionPreparation(status, format, emptyList(), emptyMap())
        }
    }
}

internal class Cobblemon173ActionChoice(
    val candidate: BattleActionCandidate,
    responses: List<ShowdownActionResponse>,
    componentCandidates: List<BattleActionCandidate> = listOf(candidate),
) {
    val responses: List<ShowdownActionResponse> = responses.toList()
    val componentCandidates: List<BattleActionCandidate> = componentCandidates.toList()
}
