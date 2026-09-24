package jbro.cobblemon.morebattlecontent.betterai.simulation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMechanicCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot

/** Builds only actions that the synthetic Showdown side's current request makes legal. */
internal object NativeShowdownRequestActionFactory {
    fun actions(side: BattleSide, frame: NativeBattleFrame): List<BattleActionCandidate> {
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
        val bySlot = active.mapIndexed { slot, element ->
            if (element.isJsonNull) {
                listOf(passAction(side, slot))
            } else {
                val activeRequest = element.asJsonObject
                moveActions(side, slot, activeRequest, frame) +
                    if (activeRequest.boolean("trapped")) emptyList() else switchActions(side, slot, frame)
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
    ): List<BattleActionCandidate> = fullTeam(side, frame).asSequence()
        .filter { it.activeSlot == null && it.hp > 0 }
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
