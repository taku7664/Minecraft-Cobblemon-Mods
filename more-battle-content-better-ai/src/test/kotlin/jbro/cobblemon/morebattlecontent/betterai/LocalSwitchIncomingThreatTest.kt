package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.router.BetterAiConfig
import jbro.cobblemon.morebattlecontent.betterai.router.HumanlikePromptCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Router is told what the incoming Pokemon gets hit with, not only what type would hit it.
 *
 * The local ranking has always projected the board after a switch and asked what the opponent can do
 * to it. Router got the switch target's health, its typing, and a type-chart multiplier per opposing
 * *type* - and, in the same doctrine, an instruction never to invent a precise damage result. The one
 * brain that is not allowed to do the arithmetic was the one not given the answer, so a switch into a
 * resisted type that still takes seventy percent of a bar read identically to one that takes twenty.
 *
 * What is published is the same class of fact the move candidates already carry: a Showdown base
 * projection over public stat ranges, from moves the battle has actually revealed. No weight, no
 * ranking, no recommendation.
 */
class LocalSwitchIncomingThreatTest {
    @Test
    fun `a revealed opponent move is projected against the switch target`() {
        val threats = switchThreats(revealOpponentMove = true)
        assertEquals(1, threats.size(), "One revealed move, one projection.")
        val threat = threats.single().asJsonObject
        assertEquals("cobblemon:quake", threat["moveId"].asString)
        assertEquals(2.0, threat["typeChartMultiplier"].asDouble, "Ground into the Electric switch-in.")
        assertTrue(
            threat.getAsJsonObject("standardDamageFractionRange")["maximum"].asDouble > 0.0,
            "A named threat without a number is the gap this closes.",
        )
    }

    @Test
    fun `nothing revealed publishes nothing rather than safety`() {
        val threats = switchThreats(revealOpponentMove = false)
        assertTrue(
            threats.isEmpty,
            "An empty list means no opposing move has been seen. The doctrine has to say that is not " +
                "the same as the switch being safe, and the encoder must not invent an entry to fill it.",
        )
    }

    @Test
    fun `the doctrine explains how to read the list`() {
        val doctrine = requestRoot(revealOpponentMove = true)
            .getAsJsonArray("messages")[0].asJsonObject["content"].asString
        assertTrue(
            doctrine.contains("incomingRevealedMoveThreats"),
            "A field Router is not told about is a field Router will not use.",
        )
        assertTrue(
            doctrine.contains("not the same as the switch being safe"),
            "The empty case is the one a reader gets wrong on its own.",
        )
    }

    private fun switchThreats(revealOpponentMove: Boolean) = promptDigest(requestRoot(revealOpponentMove))
        .getAsJsonArray("candidates")
        .map { it.asJsonObject }
        .single { it["actionId"].asString == "switch-in" }
        .getAsJsonObject("switchTargetPublicFacts")
        .getAsJsonArray("incomingRevealedMoveThreats")

    private fun requestRoot(revealOpponentMove: Boolean): com.google.gson.JsonObject {
        val battleId = UUID.randomUUID()
        val active = mon(BattleSide.ALLY, 0, setOf("normal"))
        val bench = mon(BattleSide.ALLY, null, setOf("electric"))
        val opponent = mon(BattleSide.OPPONENT, 0, setOf("ground"))
        val quake = BattleMoveCandidateView(
            typeId = "ground", damageCategory = BattleMoveDamageCategory.PHYSICAL, power = 100.0,
            accuracy = 100.0, priority = 0, currentPp = 10,
            targetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
        )
        val catalog = BattlePublicActionCatalogView(
            if (revealOpponentMove) {
                listOf(
                    BattlePokemonActionCatalogView(
                        opponent.battlePokemonId,
                        listOf(
                            BattlePublicMoveOptionView(
                                moveId = "cobblemon:quake",
                                details = quake,
                                knowledge = BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                            ),
                        ),
                        moveSetComplete = false,
                    ),
                )
            } else {
                emptyList()
            },
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = battleId, format = BattleFormat.SINGLE, turn = 3,
                pokemon = listOf(active, bench, opponent), field = BattleFieldStateView.empty(),
                remainingPokemonBySide = BattleSide.entries.associateWith { 2 },
                observedEvents = emptyList(), inferences = emptyList(),
            ),
            candidates = listOf(
                BattleActionCandidate(
                    actionId = "switch-in", kind = BattleActionKind.SWITCH, actorSlot = 0,
                    switchPokemonId = bench.battlePokemonId,
                ),
            ),
            deadlineEpochMillis = Long.MAX_VALUE,
            memory = BattleTacticalMemoryView.empty(),
            publicActionCatalog = catalog,
        )
        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            BattleBrainOpenContext(
                battleId,
                BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(1),
            ),
            PublicBattleTacticalCalculator.calculate(context),
        )
        return JsonParser.parseString(request).asJsonObject
    }

    private fun promptDigest(request: com.google.gson.JsonObject) = JsonParser.parseString(
        request.getAsJsonArray("messages")[1].asJsonObject["content"].asString,
    ).asJsonObject

    private fun mon(side: BattleSide, slot: Int?, types: Set<String>) = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(), side = side, activeSlot = slot,
        speciesId = "cobblemon:probe_${side.name.lowercase()}_${slot ?: "bench"}", formId = null,
        level = 50, hpFraction = 1.0, statusId = null, statStages = emptyMap(),
        knownMoveIds = emptySet(), knownAbilityId = null, knownHeldItemId = null, fainted = false,
        knownTypeIds = types,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(200, 100, 100, 100, 100, 100)
        } else {
            publicExactStats(200, 140, 100, 100, 100, 120)
        },
    )
}
