package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.LocalTacticalBrain
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class EmbeddedAiLoopResult(val final: JsonObject, val inputs: List<JsonObject>, val choices: List<String>)

/** Restricted smoke adapter. No referee opponent objects, fabricated stats or recursive-search claim. */
internal object EmbeddedAiDecisionLoop {
    fun run(directory: Path, hiddenVariant: Boolean = false): EmbeddedAiLoopResult {
        val brain = LocalTacticalBrain()
        val session = brain.openSession(BattleBrainOpenContext(BATTLE, BattleFormat.SINGLE))
        val choices = mutableListOf<String>()
        val inputs = mutableListOf<JsonObject>()
        var outcome = BattleBrainCloseOutcome.CANCELLED
        try {
            repeat(12) { step ->
                val reply = EmbeddedShowdownOracle.aiDecisionReplay(directory.resolve("step-$step"), choices, hiddenVariant)
                if (reply["status"].asString == "COMPLETE") {
                    outcome = if (reply["winner"].asString == "p1") BattleBrainCloseOutcome.VICTORY else BattleBrainCloseOutcome.DEFEAT
                    return EmbeddedAiLoopResult(reply, inputs, choices.toList())
                }
                check(reply["status"].asString == "WAITING")
                val input = reply.getAsJsonObject("decisionInput")
                val context = context(input)
                inputs += input.deepCopy()
                val decision = brain.decide(session, context).toCompletableFuture().get(5, TimeUnit.SECONDS)
                check(decision.requestId == context.requestId)
                check(context.candidates.any { it.actionId == decision.actionId })
                choices += decision.actionId
            }
            error("Native AI smoke exceeded 12 decisions")
        } finally {
            brain.closeSession(session, BattleBrainCloseResult(outcome, choices.size))
        }
    }

    private fun context(input: JsonObject): BattleDecisionContext {
        val own = input.getAsJsonObject("own")
        val log = input.getAsJsonArray("publicLog").map { it.asString }
        val switchIndex = log.indexOfLast { it.startsWith("|switch|p2a:") }
        require(switchIndex >= 0)
        val switch = log[switchIndex].split('|')
        val details = switch[3].split(',').map(String::trim)
        val species = canonical(details.first())
        val level = details.firstOrNull { it.matches(Regex("L[0-9]+")) }?.drop(1)?.toInt() ?: 100
        val currentEvents = log.drop(switchIndex)
        val condition = currentEvents.lastOrNull {
            it.startsWith("|-damage|p2a:") || it.startsWith("|-heal|p2a:")
        }?.split('|')?.get(3) ?: switch[4]
        val hp = condition.substringBefore(' ').split('/')
        val fraction = if (hp[0] == "0") 0.0 else hp[0].toDouble() / hp[1].toDouble()
        val publicMoves = log.filter { it.startsWith("|move|${switch[2]}|") }
            .map { canonical(it.split('|')[3]) }.toSet()
        val candidates = own.getAsJsonArray("moves").map { it.asJsonObject }
            .filter { !it["disabled"].asBoolean && it["pp"].asInt > 0 }.map { move ->
                BattleActionCandidate("move ${move["slot"].asInt + 1}", BattleActionKind.USE_MOVE, actorSlot = 0,
                    moveSlot = move["slot"].asInt, moveId = move["id"].asString,
                    targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                    moveDetails = BattleMoveCandidateView(move["typeId"].asString,
                        BattleMoveDamageCategory.valueOf(move["category"].asString.uppercase()),
                        move["power"].asDouble, move["accuracyProbability"].asDouble * 100.0,
                        move["priority"].asInt, move["pp"].asInt))
            }
        val ownState = BattlePokemonStateView(UUID(0, 1001), BattleSide.ALLY, 0, own["speciesId"].asString,
            null, own["level"].asInt, own["hpFraction"].asDouble, null, emptyMap(),
            candidates.mapNotNull { it.moveId }.toSet(), null, null, own["fainted"].asBoolean,
            own.getAsJsonArray("types").map { it.asString }.toSet())
        val opponent = BattlePokemonStateView(UUID.nameUUIDFromBytes(switch[2].toByteArray()), BattleSide.OPPONENT,
            0, species, null, level, fraction, null, emptyMap(), publicMoves, null, null, fraction == 0.0,
            input.getAsJsonObject("publicSpecies").getAsJsonObject(species).getAsJsonArray("types").map { it.asString }.toSet())
        val turn = input["turn"].asInt
        return BattleDecisionContext(UUID(0, 2000L + turn), BattleStateView(BATTLE, BattleFormat.SINGLE, turn,
            listOf(ownState, opponent), BattleFieldStateView.empty(),
            mapOf(BattleSide.ALLY to 1, BattleSide.OPPONENT to input["opponentRemaining"].asInt),
            emptyList(), emptyList()), candidates, System.currentTimeMillis() + 20000)
    }

    private fun canonical(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
    private val BATTLE = UUID(0, 1000)
}
