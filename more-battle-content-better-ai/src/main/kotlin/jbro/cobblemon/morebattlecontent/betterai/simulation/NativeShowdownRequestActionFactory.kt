package jbro.cobblemon.morebattlecontent.betterai.simulation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMechanicCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveDamageCategory
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicActionCatalogView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.betterai.mechanics.PublicSwitchEntryHazardCalculator
import jbro.cobblemon.morebattlecontent.betterai.mechanics.StandardTypeEffectiveness

/** Builds only actions that the synthetic Showdown side's current request makes legal. */
internal object NativeShowdownRequestActionFactory {
    fun actions(
        side: BattleSide,
        frame: NativeBattleFrame,
        maxVoluntarySwitchTargetsPerSlot: Int? = null,
        publicState: BattleStateView? = null,
        publicActionCatalog: BattlePublicActionCatalogView? = null,
    ): List<BattleActionCandidate> {
        require(maxVoluntarySwitchTargetsPerSlot == null || maxVoluntarySwitchTargetsPerSlot >= 0)
        if (frame.ended) return emptyList()
        val request = parseRequest(requestJson(side, frame))
        require(!request.boolean("teamPreview")) {
            "Native lookahead cannot branch before team preview is resolved"
        }
        if (request.boolean("wait")) {
            return listOf(BattleActionCandidate(nativeActionId(side, "wait"), BattleActionKind.WAIT))
        }
        request.getAsJsonArray("forceSwitch")?.let { forced ->
            val bySlot = forced.mapIndexed { slot, element ->
                if (element.asBoolean) switchActions(side, slot, frame)
                else listOf(passAction(side, slot))
            }
            return combine(side, bySlot)
        }
        val active = request.getAsJsonArray("active")
            ?: throw IllegalArgumentException("Native Showdown request has no move, switch or wait action")
        // Forced and pivot replacements returned above are never pruned. Only future ordinary
        // move requests receive a bounded voluntary-switch set, before double combinations form.
        val permittedSwitches = maxVoluntarySwitchTargetsPerSlot?.takeIf { it > 0 }?.let { limit ->
            preferredVoluntarySwitches(side, frame, active, limit, publicState, publicActionCatalog)
        }
        val bySlot = active.mapIndexed { slot, element ->
            if (element.isJsonNull) {
                listOf(passAction(side, slot))
            } else {
                val activeRequest = element.asJsonObject
                moveActions(side, slot, activeRequest, frame) +
                    if (activeRequest.boolean("trapped") || maxVoluntarySwitchTargetsPerSlot == 0) emptyList() else
                        switchActions(side, slot, frame, permittedSwitches?.get(slot))
            }
        }
        return combine(side, bySlot)
    }

    private fun moveActions(
        side: BattleSide,
        actorSlot: Int,
        request: JsonObject,
        frame: NativeBattleFrame,
    ): List<BattleActionCandidate> {
        val actor = activeTeam(side, frame).singleOrNull { it.activeSlot == actorSlot }
        requireNotNull(actor) { "Native request names missing active slot $actorSlot for $side" }
        val moves = request.getAsJsonArray("moves")
            ?: throw IllegalArgumentException("Native move request is missing its moves")
        return moves.flatMapIndexed { moveSlot, element ->
            val move = element.asJsonObject
            if (move.boolean("disabled")) return@flatMapIndexed emptyList()
            val moveId = nativeId(move.get("id")?.asString ?: move.get("move")?.asString.orEmpty())
            require(moveId.isNotBlank()) { "Native move request contains a blank move ID" }
            require(actor.moves.getOrNull(moveSlot)?.id?.let(::nativeId) == moveId) {
                "Native request move $moveId disagrees with active slot $actorSlot move $moveSlot"
            }
            val targets = targetVariants(
                target = nativeId(move.get("target")?.asString.orEmpty()),
                side = side,
                actorSlot = actorSlot,
                frame = frame,
            )
            val mechanics = buildList<String?> {
                add(null)
                if (request.enabled("canMegaEvo")) add("mega")
                if (request.enabled("canDynamax")) add("dynamax")
                if (request.enabled("canTerastallize")) add("tera")
            }
            targets.flatMap { targetSlots ->
                mechanics.map { mechanic ->
                    BattleActionCandidate(
                        actionId = nativeActionId(
                            side,
                            "slot:$actorSlot:move:$moveSlot:$moveId:" +
                                targetSlots.joinToString("+") { "${it.side.name.lowercase()}:${it.slot}" } +
                                ":${mechanic ?: "base"}",
                        ),
                        kind = BattleActionKind.USE_MOVE,
                        actorSlot = actorSlot,
                        moveSlot = moveSlot,
                        moveId = moveId,
                        targets = targetSlots,
                        mechanic = mechanic?.let { BattleMechanicCandidate(it, null, null) },
                        tags = setOf("native_showdown_request"),
                    )
                }
            }
        }
    }

    private fun switchActions(
        side: BattleSide,
        actorSlot: Int,
        frame: NativeBattleFrame,
        permittedPokemonUuids: Set<String>? = null,
    ): List<BattleActionCandidate> = fullTeam(side, frame).asSequence()
        .filter { it.activeSlot == null && it.hp > 0 }
        .filter { permittedPokemonUuids == null || it.uuid in permittedPokemonUuids }
        .map { pokemon ->
            BattleActionCandidate(
                actionId = nativeActionId(side, "slot:$actorSlot:switch:${pokemon.uuid}"),
                kind = BattleActionKind.SWITCH,
                actorSlot = actorSlot,
                switchPokemonId = java.util.UUID.fromString(pokemon.uuid),
                tags = setOf("native_showdown_request"),
            )
        }
        .toList()

    /** Cheap ordering proxy, not a claim that the retained switch is globally optimal. */
    private fun preferredVoluntarySwitches(
        side: BattleSide,
        frame: NativeBattleFrame,
        active: com.google.gson.JsonArray,
        limit: Int,
        publicState: BattleStateView?,
        publicActionCatalog: BattlePublicActionCatalogView?,
    ): Map<Int, Set<String>> {
        val opposingTypes = activeTeam(opposite(side), frame).asSequence()
            .filter { it.hp > 0 }
            .flatMap { it.types.asSequence() }
            .toSet()
        val revealedMoves = activeTeam(opposite(side), frame).asSequence()
            .filter { it.hp > 0 }
            .flatMap { opponent ->
                publicActionCatalog?.forPokemon(UUID.fromString(opponent.uuid)).orEmpty().asSequence()
                    .filter { it.knowledge == BattlePublicMoveKnowledge.PUBLICLY_REVEALED &&
                        it.details.damageCategory != BattleMoveDamageCategory.STATUS && it.details.power > 0.0 }
                    .map { option -> opponent to option.details }
            }.toList()
        val bench = fullTeam(side, frame).asSequence()
            .filter { it.activeSlot == null && it.hp > 0 }
            .sortedWith(compareByDescending<NativePokemonFrame> { pokemon ->
                val revealedThreat = revealedMoves.maxOfOrNull { (opponent, details) ->
                    details.power * StandardTypeEffectiveness.multiplier(
                        details.typeId, pokemon.types.toSet()) *
                        (if (opponent.types.any { it.equals(details.typeId.substringAfter(':'), true) }) 1.5 else 1.0)
                }
                val worstStab = opposingTypes.maxOfOrNull { attackingType ->
                    StandardTypeEffectiveness.multiplier(attackingType, pokemon.types.toSet())
                } ?: 1.0
                val hazardLoss = publicState?.pokemon?.firstOrNull {
                    it.battlePokemonId.toString() == pokemon.uuid && it.side == side
                }?.let { PublicSwitchEntryHazardCalculator.hpLoss(publicState, side, it) } ?: 0.0
                val remainingHp = (pokemon.hp.toDouble() / pokemon.maxHp - hazardLoss).coerceAtLeast(0.0)
                if (revealedThreat != null) remainingHp / maxOf(40.0, revealedThreat)
                else remainingHp / maxOf(0.5, worstStab)
            }.thenBy { it.uuid })
            .toList()
        val reserved = mutableSetOf<String>()
        return active.mapIndexedNotNull { slot, element ->
            val request = element.takeIf { it.isJsonObject }?.asJsonObject
            if (request == null || request.boolean("trapped") || bench.isEmpty()) return@mapIndexedNotNull null
            // Distinct first choices preserve at least one legal simultaneous double switch.
            val first = bench.firstOrNull { it.uuid !in reserved } ?: bench.first()
            reserved += first.uuid
            val chosen = (listOf(first) + bench.filter { it.uuid != first.uuid }.take(limit - 1))
                .mapTo(linkedSetOf()) { it.uuid }
            slot to chosen
        }.toMap()
    }

    private fun targetVariants(
        target: String,
        side: BattleSide,
        actorSlot: Int,
        frame: NativeBattleFrame,
    ): List<List<BattleTargetSlot>> {
        val allies = activeTeam(side, frame).filter { it.hp > 0 }.sortedBy { it.activeSlot }
        val opponents = activeTeam(opposite(side), frame).filter { it.hp > 0 }.sortedBy { it.activeSlot }
        // A singles command has no target location even for a selected move.
        val singleTargetBattle = allies.size <= 1 && opponents.size <= 1
        return when (target) {
            "normal", "adjacentfoe" -> if (singleTargetBattle) listOf(emptyList()) else opponents.map {
                listOf(BattleTargetSlot(opposite(side), requireNotNull(it.activeSlot)))
            }
            "adjacentally" -> allies.filter { it.activeSlot != actorSlot }.map {
                listOf(BattleTargetSlot(side, requireNotNull(it.activeSlot)))
            }
            "adjacentallyorself" -> allies.map {
                listOf(BattleTargetSlot(side, requireNotNull(it.activeSlot)))
            }
            "any" -> (allies.filter { it.activeSlot != actorSlot }.map {
                BattleTargetSlot(side, requireNotNull(it.activeSlot))
            } + opponents.map {
                BattleTargetSlot(opposite(side), requireNotNull(it.activeSlot))
            }).map(::listOf)
            else -> listOf(emptyList())
        }
    }

    private fun combine(
        side: BattleSide,
        bySlot: List<List<BattleActionCandidate>>,
    ): List<BattleActionCandidate> {
        if (bySlot.isEmpty() || bySlot.any { it.isEmpty() }) return emptyList()
        if (bySlot.size == 1) return bySlot.single()
        return bySlot.fold(listOf(emptyList<BattleActionCandidate>())) { combinations, slotActions ->
            combinations.flatMap { combination -> slotActions.map { combination + it } }
        }.filter { components ->
            val switchIds = components.mapNotNull(BattleActionCandidate::switchPokemonId)
            switchIds.distinct().size == switchIds.size && components.count { it.mechanic != null } <= 1
        }.map { components ->
            val ids = components.map(BattleActionCandidate::actionId)
            BattleActionCandidate(
                actionId = nativeActionId(side, "joint:${ids.joinToString("|")}"),
                kind = BattleActionKind.COMPOSITE,
                componentActionIds = ids,
                componentActions = components,
                tags = setOf("native_showdown_request", "native_joint_action"),
            )
        }
    }

    private fun passAction(side: BattleSide, actorSlot: Int) = BattleActionCandidate(
        actionId = nativeActionId(side, "slot:$actorSlot:pass"),
        kind = BattleActionKind.WAIT,
        actorSlot = actorSlot,
        tags = setOf("native_showdown_request"),
    )

    private fun parseRequest(json: String): JsonObject {
        val parsed = JsonParser.parseString(json)
        require(parsed.isJsonObject) { "Native Showdown request is unavailable" }
        return parsed.asJsonObject
    }

    private fun requestJson(side: BattleSide, frame: NativeBattleFrame): String = when (side) {
        BattleSide.ALLY -> frame.p1RequestJson
        BattleSide.OPPONENT -> frame.p2RequestJson
    }

    private fun activeTeam(side: BattleSide, frame: NativeBattleFrame): List<NativePokemonFrame> = when (side) {
        BattleSide.ALLY -> frame.p1Active
        BattleSide.OPPONENT -> frame.p2Active
    }

    private fun fullTeam(side: BattleSide, frame: NativeBattleFrame): List<NativePokemonFrame> = when (side) {
        BattleSide.ALLY -> frame.p1Team
        BattleSide.OPPONENT -> frame.p2Team
    }

    private fun opposite(side: BattleSide): BattleSide = when (side) {
        BattleSide.ALLY -> BattleSide.OPPONENT
        BattleSide.OPPONENT -> BattleSide.ALLY
    }

    private fun nativeActionId(side: BattleSide, suffix: String): String =
        "native:${side.name.lowercase(Locale.ROOT)}:$suffix"

    private fun nativeId(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun JsonObject.boolean(name: String): Boolean = get(name)?.let { element ->
        element.isJsonPrimitive && element.asJsonPrimitive.isBoolean && element.asBoolean
    } == true

    private fun JsonObject.enabled(name: String): Boolean = get(name)?.let { it.isEnabled() } == true

    private fun JsonElement.isEnabled(): Boolean = when {
        isJsonNull -> false
        isJsonPrimitive && asJsonPrimitive.isBoolean -> asBoolean
        isJsonPrimitive && asJsonPrimitive.isString -> asString.isNotBlank()
        isJsonArray -> asJsonArray.size() > 0
        isJsonObject -> asJsonObject.size() > 0
        else -> false
    }
}
