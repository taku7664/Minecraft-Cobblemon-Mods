package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import jbro.cobblemon.morebattlecontent.api.ai.*
import jbro.cobblemon.morebattlecontent.betterai.brain.OpenRouterTacticalBrain
import jbro.cobblemon.morebattlecontent.betterai.brain.OpenRouterTransport
import jbro.cobblemon.morebattlecontent.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.morebattlecontent.betterai.router.BetterAiConfig
import jbro.cobblemon.morebattlecontent.betterai.router.BetterAiConfigStore
import jbro.cobblemon.morebattlecontent.betterai.router.BetterAiDecisionMode
import jbro.cobblemon.morebattlecontent.betterai.router.HumanlikePromptCodec
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterDecisionSummarySink
import jbro.cobblemon.morebattlecontent.betterai.router.OpenRouterModelCapabilities
import jbro.cobblemon.morebattlecontent.betterai.router.RouterActivationMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HumanlikeExternalBrainTest {
    private val battleId = UUID.randomUUID()
    private val allyId = UUID.randomUUID()
    private val opponentId = UUID.randomUUID()
    private val open = BattleBrainOpenContext(
        battleId,
        BattleFormat.SINGLE,
        trainerProfile = BattleTrainerProfile.balanced(4),
    )

    @Test
    fun `default config is written disabled without credentials`() {
        val directory = Files.createTempDirectory("mbc-better-ai-config")
        val path = directory.resolve("config.json")
        try {
            val config = BetterAiConfigStore.loadOrCreate(path)
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject

            assertFalse(config.externallyUsable)
            assertEquals(3, config.schemaVersion)
            assertEquals(BetterAiDecisionMode.QUALITY, config.decisionMode)
            assertEquals(3, json["schemaVersion"].asInt)
            assertEquals("QUALITY", json["decisionMode"].asString)
            assertFalse(json["enabled"].asBoolean)
            assertEquals("", json["apiKey"].asString)
            assertEquals("", json["model"].asString)
            assertEquals(10_000, json["timeoutMillis"].asLong)
            assertFalse(config.logDecisionSummary)
            assertFalse(json["logDecisionSummary"].asBoolean)
            assertEquals(0, json["softTimeoutMillis"].asLong)
            assertTrue(json["endpoint"].asString.startsWith("https://"))
            assertFalse(json.has("maximumCallsPerBattle"))
            val policy = json.getAsJsonObject("routerPolicy")
            assertEquals("LOCAL_ONLY", policy["defaultMode"].asString)
            assertEquals(
                "BOSS_ONLY",
                policy.getAsJsonObject("contentRules")
                    .getAsJsonObject(BattleBrainContentIds.BATTLE_TOWER)["mode"].asString,
            )
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `schema one config loads as quality without rewriting the file`() {
        val directory = Files.createTempDirectory("mbc-better-ai-config-v1")
        val path = directory.resolve("config.json")
        val legacy = """{
          "schemaVersion": 1,
          "enabled": true,
          "apiKey": "legacy-key",
          "model": "test/model",
          "endpoint": "https://openrouter.ai/api/v1/chat/completions",
          "timeoutMillis": 9000,
          "softTimeoutMillis": 0,
          "maximumConcurrentRequests": 3,
          "maximumQueuedRequests": 16,
          "maximumCallsPerBattle": 1,
          "mindGamesEnabled": true,
          "promptVersion": "humanlike-v1",
          "requireStructuredOutput": true,
          "appUrl": "",
          "appName": "Legacy Server"
        }"""
        try {
            Files.writeString(path, legacy)

            val config = BetterAiConfigStore.loadOrCreate(path)

            assertEquals(3, config.schemaVersion)
            assertEquals(BetterAiDecisionMode.QUALITY, config.decisionMode)
            assertEquals(1, config.maximumCallsPerBattle)
            assertEquals(RouterActivationMode.ALL, config.routerPolicy.defaultMode)
            assertEquals(legacy, Files.readString(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `schema two reads the selected decision mode`() {
        val directory = Files.createTempDirectory("mbc-better-ai-config-v2")
        val path = directory.resolve("config.json")
        try {
            Files.writeString(path, """{
              "schemaVersion": 2,
              "decisionMode": "ECONOMY",
              "logDecisionSummary": true
            }""")

            val config = BetterAiConfigStore.loadOrCreate(path)

            assertEquals(BetterAiDecisionMode.ECONOMY, config.decisionMode)
            assertEquals(2_048, config.decisionMode.maximumOutputTokens)
            assertTrue(config.logDecisionSummary)
            assertEquals(RouterActivationMode.ALL, config.routerPolicy.defaultMode)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `schema three reads per-content boss and difficulty routing without conflating boss skill with boss encounters`() {
        val directory = Files.createTempDirectory("mbc-better-ai-config-v3")
        val path = directory.resolve("config.json")
        try {
            Files.writeString(path, """{
              "schemaVersion": 3,
              "routerPolicy": {
                "defaultMode": "LOCAL_ONLY",
                "contentRules": {
                  "${BattleBrainContentIds.BATTLE_TOWER}": {"mode": "BOSS_ONLY"},
                  "${BattleBrainContentIds.BATTLE_FACTORY}": {
                    "mode": "DIFFICULTY_TIERS",
                    "tiers": ["ADVANCED", "BOSS"]
                  }
                }
              }
            }""")

            val config = BetterAiConfigStore.loadOrCreate(path)

            assertFalse(config.routerPolicy.allows(selection("example:unknown", BattleEncounterRole.BOSS)))
            assertTrue(config.routerPolicy.allows(selection(BattleBrainContentIds.BATTLE_TOWER, BattleEncounterRole.BOSS)))
            assertFalse(config.routerPolicy.allows(selection(BattleBrainContentIds.BATTLE_TOWER, BattleEncounterRole.REGULAR)))
            assertTrue(
                config.routerPolicy.allows(
                    selection(
                        BattleBrainContentIds.BATTLE_FACTORY,
                        BattleEncounterRole.REGULAR,
                        BattleTrainerTier.BOSS,
                    ),
                ),
            )
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `config rejects malformed https endpoint before provider registration`() {
        assertThrows(IllegalArgumentException::class.java) {
            BetterAiConfig(endpoint = "https://")
        }
    }

    @Test
    fun `config accepts a twenty second router timeout`() {
        assertEquals(20_000L, BetterAiConfig(timeoutMillis = 20_000).timeoutMillis)
    }

    @Test
    fun `config file load enforces endpoint schema and resource limits without rewriting rejected input`() {
        val invalidDocuments = mapOf(
            "plain HTTP endpoint" to """{"schemaVersion":2,"endpoint":"http://openrouter.ai/api/v1/chat/completions"}""",
            "timeout above the hard limit" to """{"schemaVersion":2,"timeoutMillis":20001}""",
            "soft timeout below the minimum" to """{"schemaVersion":2,"softTimeoutMillis":499}""",
            "zero concurrent requests" to """{"schemaVersion":2,"maximumConcurrentRequests":0}""",
            "oversized request queue" to """{"schemaVersion":2,"maximumQueuedRequests":257}""",
            "unknown schema" to """{"schemaVersion":4}""",
            "schema three without router policy" to """{"schemaVersion":3}""",
            "difficulty route without tiers" to """{
              "schemaVersion":3,
              "routerPolicy":{
                "defaultMode":"LOCAL_ONLY",
                "contentRules":{"${BattleBrainContentIds.BATTLE_FACTORY}":{"mode":"DIFFICULTY_TIERS"}}
              }
            }""",
            "unknown decision mode" to """{"schemaVersion":2,"decisionMode":"UNBOUNDED"}""",
        )

        invalidDocuments.forEach { (label, document) ->
            val path = Files.createTempFile("mbc-better-ai-invalid-config", ".json")
            try {
                Files.writeString(path, document)

                val failure = runCatching { BetterAiConfigStore.loadOrCreate(path) }.exceptionOrNull()

                assertTrue(failure is IllegalArgumentException, "$label should be rejected but failed with $failure")
                assertEquals(document, Files.readString(path), "$label must not rewrite the rejected config")
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

    private fun selection(
        contentId: String,
        encounterRole: BattleEncounterRole,
        tier: BattleTrainerTier = BattleTrainerTier.BOSS,
    ) = BattleBrainSelectionContext(contentId, encounterRole, tier)

    @Test
    fun `config string representation never exposes the API key`() {
        val secret = "secret-that-must-never-appear"

        val description = BetterAiConfig(apiKey = secret).toString()

        assertFalse(description.contains(secret))
        assertTrue(description.contains("apiKey=<redacted>"))
    }

    @Test
    fun `decision modes alter reasoning budget but never the factual prompt`() {
        val context = PublicBattleTacticalCalculator.calculate(context(turn = 1))
        val capabilities = OpenRouterModelCapabilities(
            supportedParameters = setOf("reasoning", "temperature", "max_tokens", "structured_outputs"),
            reasoningMetadataPresent = true,
        )

        fun request(mode: BetterAiDecisionMode) = JsonParser.parseString(
            HumanlikePromptCodec.requestJson(
                BetterAiConfig(
                    enabled = true,
                    apiKey = "test",
                    model = "test/model",
                    decisionMode = mode,
                ),
                open,
                context,
                capabilities,
            ),
        ).asJsonObject

        val quality = request(BetterAiDecisionMode.QUALITY)
        val balanced = request(BetterAiDecisionMode.BALANCED)
        val economy = request(BetterAiDecisionMode.ECONOMY)

        assertEquals(8_192, quality["max_tokens"].asInt)
        assertEquals("high", quality.getAsJsonObject("reasoning")["effort"].asString)
        assertTrue(quality.getAsJsonObject("reasoning")["exclude"].asBoolean)
        assertEquals(4_096, balanced["max_tokens"].asInt)
        assertEquals("medium", balanced.getAsJsonObject("reasoning")["effort"].asString)
        assertEquals(2_048, economy["max_tokens"].asInt)
        assertEquals("low", economy.getAsJsonObject("reasoning")["effort"].asString)
        assertEquals(promptDigest(quality), promptDigest(balanced))
        assertEquals(promptDigest(quality), promptDigest(economy))
    }

    @Test
    fun `unknown or non reasoning model omits reasoning without weakening the router request`() {
        val context = PublicBattleTacticalCalculator.calculate(context(turn = 1))
        fun request(capabilities: OpenRouterModelCapabilities?) = JsonParser.parseString(
            HumanlikePromptCodec.requestJson(
                BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
                open,
                context,
                capabilities,
            ),
        ).asJsonObject

        val unknown = request(null)
        val unsupported = request(
            OpenRouterModelCapabilities(
                supportedParameters = setOf("temperature", "max_tokens", "structured_outputs"),
                reasoningMetadataPresent = false,
            ),
        )

        assertNull(unknown["reasoning"])
        assertNull(unsupported["reasoning"])
        assertEquals(8_192, unknown["max_tokens"].asInt)
        assertTrue(unknown.has("response_format"))
        assertEquals(promptDigest(unknown), promptDigest(unsupported))
    }

    @Test
    fun `known model omits unsupported temperature instead of restricting provider routing`() {
        val request = JsonParser.parseString(
            HumanlikePromptCodec.requestJson(
                BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
                open,
                PublicBattleTacticalCalculator.calculate(context(turn = 1)),
                OpenRouterModelCapabilities(
                    supportedParameters = setOf("reasoning", "max_tokens", "structured_outputs"),
                    reasoningMetadataPresent = true,
                ),
            ),
        ).asJsonObject

        assertFalse(request.has("temperature"))
        assertTrue(request.has("reasoning"))
        assertTrue(request.has("response_format"))
    }

    @Test
    fun `reasoning still requires a supporting provider when structured output is disabled`() {
        val request = JsonParser.parseString(
            HumanlikePromptCodec.requestJson(
                BetterAiConfig(
                    enabled = true,
                    apiKey = "test",
                    model = "test/model",
                    requireStructuredOutput = false,
                ),
                open,
                PublicBattleTacticalCalculator.calculate(context(turn = 1)),
                OpenRouterModelCapabilities(
                    supportedParameters = setOf("reasoning", "temperature", "max_tokens"),
                    reasoningMetadataPresent = true,
                ),
            ),
        ).asJsonObject

        assertTrue(request.getAsJsonObject("provider")["require_parameters"].asBoolean)
        assertTrue(request.has("reasoning"))
        assertFalse(request.has("response_format"))
    }

    @Test
    fun `prompt digest contains factual mechanics but no local utility rank or raw uuid`() {
        val context = PublicBattleTacticalCalculator.calculate(context(turn = 1))
        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "not-serialized", model = "test/model"),
            open,
            context,
        )
        val requestRoot = JsonParser.parseString(request).asJsonObject

        assertFalse(request.contains(battleId.toString()))
        assertFalse(request.contains(allyId.toString()))
        assertFalse(request.contains(opponentId.toString()))
        assertFalse(request.contains("not-serialized"))
        assertTrue(request.contains("ally0"))
        assertTrue(request.contains("response_format"))
        assertTrue(request.contains("additionalProperties"))
        assertTrue(request.contains("typeChartMultiplier"))
        assertTrue(request.contains("standardDamageFractionRange"))
        assertTrue(request.contains("standardDamageRollKoProbabilityRange"))
        assertTrue(request.contains("DYNAMIC_DAMAGE_MODIFIERS"))
        assertTrue(request.contains("calculationCoverage"))
        assertFalse(request.contains("expectedUtility"))
        assertFalse(request.contains("localRank"))
        assertFalse(request.contains("recommendedAction"))
        assertFalse(request.contains("patternResponseShiftEvidence"))
        assertFalse(request.contains("opponentResponseVolatility"))
        assertFalse(request.contains("nonProgressControlStreak"))
        assertEquals(8_192, requestRoot["max_tokens"].asInt)
        assertTrue(request.contains("Server-issued legal action ID"))
    }

    @Test
    fun `router receives the same explicit four stage difficulty contract as the local brain`() {
        fun request(profile: BattleTrainerProfile) = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            BattleBrainOpenContext(battleId, BattleFormat.SINGLE, trainerProfile = profile),
            PublicBattleTacticalCalculator.calculate(context(turn = 1)),
        )

        val introductory = request(BattleTrainerProfile.balanced(1))
        val boss = request(BattleTrainerProfile.champion(4))
        val introductoryDigest = promptDigest(introductory).getAsJsonObject("trainer").getAsJsonObject("difficulty")
        val bossDigest = promptDigest(boss).getAsJsonObject("trainer").getAsJsonObject("difficulty")

        assertEquals("INTRODUCTORY", introductoryDigest["tier"].asString)
        assertEquals(3, introductoryDigest["maximumHypothesesPerPokemon"].asInt)
        assertEquals(1, introductoryDigest["lookaheadPlies"].asInt)
        assertEquals("BOSS", bossDigest["tier"].asString)
        assertEquals(16, bossDigest["maximumHypothesesPerPokemon"].asInt)
        assertEquals(4, bossDigest["lookaheadPlies"].asInt)
        assertTrue(introductory.contains("Compare one complete turn"))
        assertTrue(boss.contains("Compare four complete turns"))
    }

    @Test
    fun `router receives aliased public future actions without local search scores`() {
        val base = context(turn = 1)
        val moveDetails = BattleMoveCandidateView(
            "ghost",
            BattleMoveDamageCategory.SPECIAL,
            80.0,
            100.0,
            0,
            10,
        )
        val decisionContext = BattleDecisionContext(
            requestId = base.requestId,
            state = base.state,
            candidates = base.candidates,
            deadlineEpochMillis = base.deadlineEpochMillis,
            memory = base.memory,
            publicActionCatalog = BattlePublicActionCatalogView(
                listOf(
                    BattlePokemonActionCatalogView(
                        opponentId,
                        listOf(
                            BattlePublicMoveOptionView(
                                "cobblemon:shadow_ball",
                                moveDetails,
                                BattlePublicMoveKnowledge.PUBLICLY_REVEALED,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            open,
            PublicBattleTacticalCalculator.calculate(decisionContext),
        )
        val digest = promptDigest(request)
        val future = digest.getAsJsonArray("publicFutureActions")[0].asJsonObject

        assertEquals("opponent0", future["pokemon"].asString)
        assertEquals("cobblemon:shadow_ball", future.getAsJsonArray("moves")[0].asJsonObject["moveId"].asString)
        assertEquals(
            "PUBLICLY_REVEALED",
            future.getAsJsonArray("moves")[0].asJsonObject["knowledge"].asString,
        )
        assertFalse(request.contains(opponentId.toString()))
        assertFalse(request.contains("lookaheadUtility"))
        assertFalse(request.contains("comparisonValue"))
    }

    @Test
    fun `router receives authoritative switch hp and public type chart facts even on introductory difficulty`() {
        val activeAlly = pokemon(
            allyId,
            BattleSide.ALLY,
            "showdown:garchomp",
            hpFraction = 0.0,
            knownTypeIds = setOf("dragon", "ground"),
        )
        val salamenceId = UUID.randomUUID()
        val gengarId = UUID.randomUUID()
        val salamence = pokemon(
            salamenceId,
            BattleSide.ALLY,
            "showdown:salamence",
            activeSlot = null,
            hpFraction = 0.01,
            knownTypeIds = setOf("dragon", "flying"),
        )
        val gengar = pokemon(
            gengarId,
            BattleSide.ALLY,
            "showdown:gengar",
            activeSlot = null,
            hpFraction = 1.0,
            knownTypeIds = setOf("ghost", "poison"),
        )
        val abomasnow = pokemon(
            opponentId,
            BattleSide.OPPONENT,
            "showdown:abomasnow",
            knownTypeIds = setOf("grass", "ice"),
        )
        val decisionContext = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = battleId,
                format = BattleFormat.SINGLE,
                turn = 3,
                pokemon = listOf(activeAlly, salamence, gengar, abomasnow),
                field = BattleFieldStateView.empty(),
                remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
                observedEvents = emptyList(),
                inferences = emptyList(),
            ),
            candidates = listOf(
                BattleActionCandidate(
                    actionId = "switch-salamence",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = 0,
                    switchPokemonId = salamenceId,
                ),
                BattleActionCandidate(
                    actionId = "switch-gengar",
                    kind = BattleActionKind.SWITCH,
                    actorSlot = 0,
                    switchPokemonId = gengarId,
                ),
            ),
            deadlineEpochMillis = Long.MAX_VALUE,
        )

        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            BattleBrainOpenContext(
                battleId,
                BattleFormat.SINGLE,
                trainerProfile = BattleTrainerProfile.balanced(1),
            ),
            PublicBattleTacticalCalculator.calculate(decisionContext),
        )
        val requestRoot = JsonParser.parseString(request).asJsonObject
        val systemDoctrine = requestRoot.getAsJsonArray("messages")[0].asJsonObject["content"].asString
        val candidates = promptDigest(request).getAsJsonArray("candidates").associateBy {
            it.asJsonObject["actionId"].asString
        }
        val salamenceFacts = candidates.getValue("switch-salamence").asJsonObject
            .getAsJsonObject("switchTargetPublicFacts")
        val gengarFacts = candidates.getValue("switch-gengar").asJsonObject
            .getAsJsonObject("switchTargetPublicFacts")
        fun typeMultipliers(facts: com.google.gson.JsonObject) = facts
            .getAsJsonArray("activeOpponentTypeChartMultipliers")
            .associate { entry ->
                val value = entry.asJsonObject
                value["attackingTypeId"].asString to value["multiplier"].asDouble
            }

        assertEquals(0.01, salamenceFacts["hpFraction"].asDouble)
        assertFalse(salamenceFacts.has("hp"))
        assertEquals(setOf("dragon", "flying"), salamenceFacts.getAsJsonArray("types").map { it.asString }.toSet())
        assertEquals(4.0, typeMultipliers(salamenceFacts).getValue("ice"))
        assertEquals(0.25, typeMultipliers(salamenceFacts).getValue("grass"))
        assertEquals(1.0, gengarFacts["hpFraction"].asDouble)
        assertFalse(gengarFacts.has("hp"))
        assertEquals(1.0, typeMultipliers(gengarFacts).getValue("ice"))
        assertFalse(request.contains(salamenceId.toString()))
        assertFalse(request.contains(gengarId.toString()))
        assertFalse(request.contains(opponentId.toString()))
        assertTrue(systemDoctrine.contains("Difficulty changes planning depth, never factual accuracy"))
        assertTrue(systemDoctrine.contains("do not claim that one switch target is safer"))
        assertTrue(systemDoctrine.contains("abilityAvailability=REGULAR|HIDDEN"))
        assertTrue(systemDoctrine.contains("Never select a damaging move with an authoritative typeChartMultiplier of 0"))
        assertTrue(systemDoctrine.contains("Repeated non-damaging actions require net public progress"))
        assertTrue(systemDoctrine.contains("do not stack a second screen by habit"))
    }

    @Test
    fun `strict response schema omits unsupported array uniqueness constraints`() {
        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(
                enabled = true,
                apiKey = "test",
                model = "openai/gpt-5.6-luna",
                logDecisionSummary = true,
            ),
            open,
            PublicBattleTacticalCalculator.calculate(context(turn = 1)),
            OpenRouterModelCapabilities(
                supportedParameters = setOf("reasoning", "max_tokens", "response_format", "structured_outputs"),
                reasoningMetadataPresent = true,
                supportedReasoningEfforts = setOf("high"),
            ),
        )
        val schema = JsonParser.parseString(request).asJsonObject
            .getAsJsonObject("response_format")
            .getAsJsonObject("json_schema")
            .getAsJsonObject("schema")

        assertFalse(schema.toString().contains("\"uniqueItems\""))
        assertTrue(schema.getAsJsonObject("properties").has("decisionSummary"))
        assertTrue(schema.getAsJsonArray("required").any { it.asString == "decisionSummary" })
    }

    @Test
    fun `disabled decision summary is absent from the model request`() {
        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "openai/gpt-5.6-luna"),
            open,
            PublicBattleTacticalCalculator.calculate(context(turn = 1)),
        )
        val root = JsonParser.parseString(request).asJsonObject
        val system = root.getAsJsonArray("messages")[0].asJsonObject["content"].asString
        val schema = root.getAsJsonObject("response_format")
            .getAsJsonObject("json_schema")
            .getAsJsonObject("schema")

        assertFalse(system.contains("decisionSummary"))
        assertFalse(schema.getAsJsonObject("properties").has("decisionSummary"))
        assertFalse(schema.getAsJsonArray("required").any { it.asString == "decisionSummary" })
    }

    @Test
    fun `router decision summary is parsed separately from the executable decision`() {
        val decisionContext = context(turn = 4)
        val content = validResponse(decisionContext.candidates.first().actionId, turn = 4)

        val summary = HumanlikePromptCodec.parseDecisionSummary(content)

        assertEquals("공개된 상성 우위와 현재 체력으로 즉시 공격을 선택했습니다.", summary)
    }

    @Test
    fun `prompt v4 exposes declarative move effects with their incompleteness caveat`() {
        val base = context(turn = 1)
        val move = base.candidates.first()
        val detailedMove = BattleActionCandidate(
            actionId = move.actionId,
            kind = move.kind,
            actorSlot = move.actorSlot,
            moveSlot = move.moveSlot,
            moveId = "cobblemon:recover",
            targets = move.targets,
            moveDetails = requireNotNull(move.moveDetails).copy(
                damageCategory = BattleMoveDamageCategory.STATUS,
                power = 0.0,
                effects = BattleMoveEffectsView(
                    coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    effects = listOf(
                        BattleMoveEffectView(
                            kind = BattleMoveEffectKind.HEAL_FRACTION,
                            target = BattleMoveEffectTarget.USER,
                            probability = 1.0,
                            fractionRange = BattleFractionRange(0.5, 0.5),
                        ),
                    ),
                    scriptedBehavior = false,
                ),
            ),
        )
        val decisionContext = BattleDecisionContext(
            requestId = base.requestId,
            state = base.state,
            candidates = listOf(detailedMove, base.candidates.last()),
            deadlineEpochMillis = base.deadlineEpochMillis,
            memory = base.memory,
        )

        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            open,
            PublicBattleTacticalCalculator.calculate(decisionContext),
        )
        val digest = promptDigest(request)

        assertEquals("brain-choice-v22", digest["promptVersion"].asString)
        assertTrue(request.contains("DECLARATIVE_PARTIAL"))
        assertTrue(request.contains("HEAL_FRACTION"))
        assertTrue(request.contains("scriptedBehavior"))
        assertTrue(request.contains("does not mean impossible"))
        assertTrue(request.contains("publicOutcomeProjection"))
        assertTrue(request.contains("top 40%"))
        assertTrue(request.contains("A switch is valuable only when its net public gain"))
        assertTrue(request.contains("prefer realizing the gain earlier"))
        assertTrue(request.contains("actionConstraints"))
        assertTrue(request.contains("mustRecharge"))
        assertFalse(request.contains("localRank"))
        assertFalse(request.contains("recommendedAction"))
    }

    @Test
    fun `double prompt exposes exact shared protection chance without local utility`() {
        val base = context(turn = 3)
        val protect = BattleActionCandidate(
            actionId = "protect",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:protect",
            moveDetails = BattleMoveCandidateView(
                typeId = "normal",
                damageCategory = BattleMoveDamageCategory.STATUS,
                power = 0.0,
                accuracy = 100.0,
                priority = 4,
                currentPp = 10,
                targetPattern = BattleMoveTargetPattern.SELF,
                effects = BattleMoveEffectsView(
                    coverage = BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                    effects = listOf(
                        BattleMoveEffectView(BattleMoveEffectKind.PROTECT_USER, BattleMoveEffectTarget.USER),
                    ),
                    scriptedBehavior = true,
                    mechanicFlags = setOf("stalling_move"),
                ),
            ),
        )
        val protectionEvents = listOf("protect", "detect").flatMapIndexed { index, moveId ->
            val turn = index + 1
            val sequence = index.toLong() * 2L + 1L
            listOf(
                BattleObservedEventView(
                    sequence = sequence,
                    turn = turn,
                    kind = BattleObservedEventKind.MOVE_USED,
                    actorPokemonId = allyId,
                    publicValueId = "cobblemon:$moveId",
                ),
                BattleObservedEventView(
                    sequence = sequence + 1L,
                    turn = turn,
                    kind = BattleObservedEventKind.MOVE_OUTCOME,
                    targetPokemonIds = listOf(allyId),
                    moveOutcome = BattleMoveOutcomeView(
                        kind = BattleMoveOutcomeKind.PROTECTION_STARTED,
                        publicEffectId = "protect",
                    ),
                ),
            )
        }
        val stateWithProtectionHistory = BattleStateView(
            battleId = base.state.battleId,
            format = BattleFormat.DOUBLE,
            turn = base.state.turn,
            pokemon = base.state.pokemon,
            field = base.state.field,
            remainingPokemonBySide = base.state.remainingPokemonBySide,
            observedEvents = protectionEvents,
            inferences = base.state.inferences,
        )
        val decisionContext = BattleDecisionContext(
            requestId = base.requestId,
            state = stateWithProtectionHistory,
            candidates = listOf(protect),
            deadlineEpochMillis = base.deadlineEpochMillis,
            memory = BattleTacticalMemoryView(
                lastMoveId = "cobblemon:detect",
                sameMoveRepeatCount = 1,
            ),
        )

        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            open.copy(format = BattleFormat.DOUBLE),
            decisionContext,
        )
        val protection = promptDigest(request).getAsJsonArray("candidates")[0].asJsonObject
            .getAsJsonObject("stallingProtection")

        assertEquals(2, protection["consecutiveSuccessfulUses"].asInt)
        assertEquals(1.0 / 9.0, protection["nextSuccessProbability"].asDouble, 1e-9)
        assertFalse(request.contains("expectedUtility"))
        assertFalse(request.contains("localRank"))
    }

    @Test
    fun `prompt identifies double battle targets by active slot instead of species order`() {
        val allyLeft = pokemon(UUID.randomUUID(), BattleSide.ALLY, "showdown:z_ally", activeSlot = 0)
        val allyRight = pokemon(UUID.randomUUID(), BattleSide.ALLY, "showdown:a_ally", activeSlot = 1)
        val opponentLeft = pokemon(UUID.randomUUID(), BattleSide.OPPONENT, "showdown:z_opponent", activeSlot = 0)
        val opponentRight = pokemon(UUID.randomUUID(), BattleSide.OPPONENT, "showdown:a_opponent", activeSlot = 1)
        val state = BattleStateView(
            battleId = battleId,
            format = BattleFormat.DOUBLE,
            turn = 3,
            pokemon = listOf(allyLeft, allyRight, opponentLeft, opponentRight),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 4, BattleSide.OPPONENT to 4),
            observedEvents = emptyList(),
            inferences = emptyList(),
        )
        val leftTarget = BattleActionCandidate(
            actionId = "p1a:move 1 p2a",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:tackle",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
            moveDetails = BattleMoveCandidateView(
                "normal", BattleMoveDamageCategory.PHYSICAL, 50.0, 100.0, 0, 10,
            ),
        )
        val rightTarget = BattleActionCandidate(
            actionId = "p1a:move 1 p2b",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 0,
            moveSlot = 0,
            moveId = "cobblemon:tackle",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 1)),
            moveDetails = leftTarget.moveDetails,
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = state,
            candidates = listOf(leftTarget, rightTarget),
            deadlineEpochMillis = Long.MAX_VALUE,
        )

        val digest = promptDigest(
            HumanlikePromptCodec.requestJson(
                BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
                BattleBrainOpenContext(
                    battleId,
                    BattleFormat.DOUBLE,
                    trainerProfile = BattleTrainerProfile.balanced(5, BattleDifficultyProfiles.BOSS),
                ),
                context,
            ),
        )
        val boardBySpecies = digest.getAsJsonArray("board").associateBy {
            it.asJsonObject["speciesId"].asString
        }
        val candidates = digest.getAsJsonArray("candidates").map { it.asJsonObject }

        assertEquals("opponent0", boardBySpecies.getValue("showdown:z_opponent").asJsonObject["slot"].asString)
        assertEquals("opponent1", boardBySpecies.getValue("showdown:a_opponent").asJsonObject["slot"].asString)
        assertEquals("opponent0", candidates[0].getAsJsonArray("targets")[0].asJsonObject["activeAlias"].asString)
        assertEquals("opponent1", candidates[1].getAsJsonArray("targets")[0].asJsonObject["activeAlias"].asString)
        assertEquals(4, digest.getAsJsonObject("trainer").getAsJsonObject("difficulty")["lookaheadPlies"].asInt)
    }

    @Test
    fun `prompt preserves public board detail and the complete trainer strategy brief`() {
        val strategy = BattleStrategyBrief(
            strategyId = "mbc:rain_pressure",
            displayNameKey = "strategy.rain_pressure",
            descriptionKey = "strategy.rain_pressure.description",
            aiSummary = "Establish rain, then preserve the cleaner for the endgame.",
            objectives = setOf(BattleStrategyObjective.FIELD_CONTROL, BattleStrategyObjective.PRESERVE_CORE),
            members = listOf(
                BattleTeamMemberPlan(
                    speciesId = "showdown:ally",
                    roles = setOf(BattleTeamRole.FIELD_SUPPORT),
                    tacticalSummary = "Use Rain Dance when the field is clear.",
                    preferredMoveIds = setOf("cobblemon:rain_dance"),
                    leadPriority = 80,
                    preservationPriority = 60,
                ),
            ),
        )
        val state = context(turn = 4).state
        val enrichedAlly = BattlePokemonStateView(
            battlePokemonId = allyId,
            side = BattleSide.ALLY,
            activeSlot = 0,
            speciesId = "showdown:ally",
            formId = "showdown:ally-rain",
            level = 50,
            hpFraction = 1.0,
            statusId = null,
            statStages = mapOf("attack" to 2, "speed" to -1),
            knownMoveIds = setOf("cobblemon:rain_dance"),
            knownAbilityId = null,
            knownHeldItemId = null,
            fainted = false,
            knownTypeIds = setOf("water"),
            combatStats = BattleCombatStatRangesView.exact(180, 140, 120, 100, 110, 90),
        )
        val context = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = BattleStateView(
                battleId = battleId,
                format = BattleFormat.SINGLE,
                turn = 4,
                pokemon = listOf(enrichedAlly, state.pokemon.single { it.side == BattleSide.OPPONENT }),
                field = BattleFieldStateView(
                    weather = BattleTimedEffectView(
                        effectId = "raindance",
                        remainingTurns = null,
                        remainingTurnsRange = BattleIntegerRange(4, 7),
                    ),
                    terrain = null,
                    roomEffects = listOf(BattleTimedEffectView("trickroom", 3)),
                    globalEffects = emptyList(),
                    sideConditions = BattleSide.entries.associateWith { emptyList() },
                ),
                remainingPokemonBySide = mapOf(BattleSide.ALLY to 2, BattleSide.OPPONENT to 1),
                observedEvents = emptyList(),
                inferences = listOf(
                    BattleInferenceView(
                        subjectPokemonId = opponentId,
                        categoryId = "ability",
                        candidateId = "roughskin",
                        confidence = BattleInferenceConfidence.POSSIBLE,
                        basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                        abilityAvailability = BattleAbilityAvailability.REGULAR,
                    ),
                    BattleInferenceView(
                        subjectPokemonId = opponentId,
                        categoryId = "ability",
                        candidateId = "sandveil",
                        confidence = BattleInferenceConfidence.POSSIBLE,
                        basis = setOf(BattleInferenceBasis.PUBLIC_SPECIES_RULES),
                        abilityAvailability = BattleAbilityAvailability.HIDDEN,
                    ),
                ),
            ),
            candidates = context(turn = 4).candidates,
            deadlineEpochMillis = Long.MAX_VALUE,
        )

        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            open.copy(strategy = strategy),
            context,
        )
        val digest = promptDigest(request)
        val trainer = digest.getAsJsonObject("trainer")
        val boardAlly = digest.getAsJsonArray("board")[0].asJsonObject

        assertEquals("brain-choice-v22", digest["promptVersion"].asString)
        assertEquals("Establish rain, then preserve the cleaner for the endgame.", trainer["aiSummary"].asString)
        assertEquals("Use Rain Dance when the field is clear.", trainer.getAsJsonArray("members")[0].asJsonObject["tacticalSummary"].asString)
        assertEquals(2, digest.getAsJsonObject("remainingPokemonBySide")["ALLY"].asInt)
        assertEquals("showdown:ally-rain", boardAlly["formId"].asString)
        assertEquals(50, boardAlly["level"].asInt)
        assertEquals(2, boardAlly.getAsJsonObject("statStages")["attack"].asInt)
        assertEquals("EXACT_OWN", boardAlly.getAsJsonObject("combatStats")["knowledge"].asString)
        val inference = digest.getAsJsonArray("inferences")[0].asJsonObject
        val hiddenInference = digest.getAsJsonArray("inferences")[1].asJsonObject
        assertEquals("roughskin", inference["candidateId"].asString)
        assertEquals("POSSIBLE", inference["confidence"].asString)
        assertEquals("REGULAR", inference["abilityAvailability"].asString)
        assertEquals("PUBLIC_SPECIES_RULES", inference.getAsJsonArray("basis")[0].asString)
        assertEquals("sandveil", hiddenInference["candidateId"].asString)
        assertEquals("HIDDEN", hiddenInference["abilityAvailability"].asString)
        val field = digest.getAsJsonObject("field")
        assertEquals(4, field.getAsJsonObject("weather").getAsJsonObject("remainingTurnsRange")["minimum"].asInt)
        assertEquals(7, field.getAsJsonObject("weather").getAsJsonObject("remainingTurnsRange")["maximum"].asInt)
        assertEquals(3, field.getAsJsonArray("roomEffects")[0].asJsonObject["remainingTurns"].asInt)
        assertTrue(request.contains("Never collapse a range to one invented value"))
    }

    @Test
    fun `prompt exposes public action evidence as aliases without turning it into speed or damage certainty`() {
        val base = context(turn = 4)
        val actionSequence = 20L
        val state = BattleStateView(
            battleId = battleId,
            format = BattleFormat.SINGLE,
            turn = 4,
            pokemon = base.state.pokemon,
            field = base.state.field,
            remainingPokemonBySide = base.state.remainingPokemonBySide,
            observedEvents = listOf(
                BattleObservedEventView(
                    actionSequence,
                    4,
                    BattleObservedEventKind.ACTION_ORDER,
                    opponentId,
                    publicValueId = "quickattack",
                    baseMovePriority = 1,
                    actorSlot = 0,
                ),
                BattleObservedEventView(
                    21,
                    4,
                    BattleObservedEventKind.MOVE_USED,
                    opponentId,
                    targetPokemonIds = listOf(allyId),
                    publicValueId = "quickattack",
                ),
                BattleObservedEventView(
                    22,
                    4,
                    BattleObservedEventKind.ACTION_ORDER,
                    allyId,
                    publicValueId = "protect",
                    baseMovePriority = 1,
                ),
                BattleObservedEventView(
                    23,
                    4,
                    BattleObservedEventKind.HP_CHANGED,
                    allyId,
                    hpFractionDelta = -0.25,
                    precedingActionSequence = actionSequence,
                    precedingActionActorPokemonId = opponentId,
                    precedingActionMoveId = "quickattack",
                ),
                BattleObservedEventView(
                    24,
                    4,
                    BattleObservedEventKind.HP_CHANGED,
                    opponentId,
                    hpFractionDelta = -0.0625,
                    publicSourceEffectId = "brn",
                ),
                BattleObservedEventView(
                    25,
                    4,
                    BattleObservedEventKind.MOVE_OUTCOME,
                    targetPokemonIds = listOf(allyId),
                    moveOutcome = BattleMoveOutcomeView(BattleMoveOutcomeKind.CRITICAL_HIT),
                ),
                BattleObservedEventView(
                    26,
                    4,
                    BattleObservedEventKind.MOVE_OUTCOME,
                    targetPokemonIds = listOf(allyId),
                    moveOutcome = BattleMoveOutcomeView(BattleMoveOutcomeKind.HIT_COUNT, hitCount = 3),
                ),
                BattleObservedEventView(
                    27,
                    4,
                    BattleObservedEventKind.MOVE_OUTCOME,
                    targetPokemonIds = listOf(allyId),
                    moveOutcome = BattleMoveOutcomeView(
                        BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED,
                        publicEffectId = "substitute",
                    ),
                ),
                BattleObservedEventView(
                    28,
                    4,
                    BattleObservedEventKind.MOVE_OUTCOME,
                    targetPokemonIds = listOf(allyId),
                    moveOutcome = BattleMoveOutcomeView(
                        BattleMoveOutcomeKind.PROTECTION_STARTED,
                        publicEffectId = "protect",
                    ),
                ),
            ),
            inferences = listOf(
                BattleInferenceView(
                    subjectPokemonId = opponentId,
                    categoryId = "observed_action_order",
                    candidateId = "BEFORE_AT_SAME_BASE_PRIORITY",
                    confidence = BattleInferenceConfidence.CONFIRMED,
                    basis = setOf(BattleInferenceBasis.ACTION_ORDER),
                    evidenceEventSequences = listOf(20, 22),
                    relatedPokemonId = allyId,
                ),
            ),
        )
        val decisionContext = BattleDecisionContext(
            base.requestId,
            state,
            base.candidates,
            base.deadlineEpochMillis,
            base.memory,
        )

        val request = HumanlikePromptCodec.requestJson(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            open,
            decisionContext,
        )
        val digest = promptDigest(request)
        val events = digest.getAsJsonArray("recentEvents").associateBy { it.asJsonObject["sequence"].asLong }
        val action = events.getValue(20).asJsonObject
        val damage = events.getValue(23).asJsonObject
        val residual = events.getValue(24).asJsonObject
        val critical = events.getValue(25).asJsonObject.getAsJsonObject("moveOutcome")
        val hitCount = events.getValue(26).asJsonObject.getAsJsonObject("moveOutcome")
        val substitute = events.getValue(27).asJsonObject.getAsJsonObject("moveOutcome")
        val protection = events.getValue(28).asJsonObject.getAsJsonObject("moveOutcome")
        val relation = digest.getAsJsonArray("inferences")[0].asJsonObject

        assertEquals(1, action["baseMovePriority"].asInt)
        assertEquals(0, action["actorSlot"].asInt)
        assertEquals("opponent0", damage.getAsJsonObject("precedingAction")["actor"].asString)
        assertEquals("quickattack", damage.getAsJsonObject("precedingAction")["moveId"].asString)
        assertEquals("brn", residual["publicSourceEffectId"].asString)
        assertEquals("CRITICAL_HIT", critical["kind"].asString)
        assertEquals(3, hitCount["hitCount"].asInt)
        assertEquals("SUBSTITUTE_DAMAGED", substitute["kind"].asString)
        assertEquals("substitute", substitute["publicEffectId"].asString)
        assertEquals("ally0", events.getValue(27).asJsonObject.getAsJsonArray("targets")[0].asString)
        assertEquals("PROTECTION_STARTED", protection["kind"].asString)
        assertEquals("protect", protection["publicEffectId"].asString)
        assertEquals("ally0", relation["relatedPokemon"].asString)
        assertEquals("observed_action_order", relation["categoryId"].asString)
        assertFalse(request.contains(allyId.toString()))
        assertFalse(request.contains(opponentId.toString()))
        assertTrue(request.contains("not proof of raw Speed"))
        assertTrue(request.contains("does not prove damage causation"))
        assertTrue(request.contains("public outcome signal does not identify an unstated cause"))
        assertTrue(request.contains("does not mean the real Pokemon lost HP"))
        assertTrue(request.contains("does not guarantee a later block"))
    }

    @Test
    fun `double prompt defines atomic actions once and joint candidates by reference`() {
        val first = context(turn = 2).candidates[0]
        val second = BattleActionCandidate(
            actionId = "p1b:move 1 p2b",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 1,
            moveSlot = 0,
            moveId = "cobblemon:tackle",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 1)),
            moveDetails = first.moveDetails,
        )
        val alternative = BattleActionCandidate(
            actionId = "p1b:move 2 p2b",
            kind = BattleActionKind.USE_MOVE,
            actorSlot = 1,
            moveSlot = 1,
            moveId = "cobblemon:growl",
            targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 1)),
            moveDetails = BattleMoveCandidateView(
                "normal", BattleMoveDamageCategory.STATUS, 0.0, 100.0, 0, 10,
            ),
        )
        fun joint(id: String, right: BattleActionCandidate) = BattleActionCandidate(
            actionId = id,
            kind = BattleActionKind.COMPOSITE,
            componentActionIds = listOf(first.actionId, right.actionId),
            componentActions = listOf(first, right),
        )
        val base = context(turn = 2)
        val decisionContext = BattleDecisionContext(
            requestId = UUID.randomUUID(),
            state = base.state,
            candidates = listOf(joint("turn:a", second), joint("turn:b", alternative)),
            deadlineEpochMillis = Long.MAX_VALUE,
        )

        val digest = promptDigest(
            HumanlikePromptCodec.requestJson(
                BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
                BattleBrainOpenContext(battleId, BattleFormat.DOUBLE),
                decisionContext,
            ),
        )
        val atomics = digest.getAsJsonArray("atomicActions").map { it.asJsonObject }
        val joints = digest.getAsJsonArray("candidates").map { it.asJsonObject }

        assertEquals(3, atomics.size)
        assertEquals(setOf(first.actionId, second.actionId, alternative.actionId), atomics.map { it["actionId"].asString }.toSet())
        assertEquals(listOf(first.actionId, second.actionId), joints[0].getAsJsonArray("componentActionIds").map { it.asString })
        assertFalse(joints[0].has("components"))
        assertFalse(joints[0].has("move"))
    }

    @Test
    fun `strict response produces action prediction and bounded plan`() {
        val context = context(turn = 4)
        val decision = HumanlikePromptCodec.parseDecision(validResponse("move", turn = 4), context)

        assertEquals("move", decision.actionId)
        assertEquals(setOf("openrouter_brain_choice_v14", "difficulty_advanced"), decision.tags)
        assertEquals(BattlePredictedResponse.SWITCH, decision.advice?.prediction?.response)
        assertEquals(0, decision.advice?.prediction?.actorSlot)
        assertEquals(BattlePlanIntent.APPLY_PRESSURE, decision.advice?.planUpdate?.plan?.intent)
        assertEquals(6, decision.advice?.planUpdate?.plan?.expiresAtTurn)
    }

    @Test
    fun `invalid advice metadata is discarded without replacing the router action`() {
        val base = context(turn = 4)
        val afterMisses = BattleDecisionContext(
            base.requestId,
            base.state,
            base.candidates,
            base.deadlineEpochMillis,
            BattleTacticalMemoryView(
                predictionCalibration = BattlePredictionCalibrationView(2, 0, 2),
            ),
        )

        assertEquals(
            "move",
            HumanlikePromptCodec.parseDecision(validResponse("move", 4), afterMisses).actionId,
        )
        assertEquals(
            BattleMindGameIntent.NONE,
            HumanlikePromptCodec.parseDecision(validResponse("move", 4), base, mindGamesEnabled = false)
                .advice?.mindGameIntent,
        )
    }

    @Test
    fun `router decides every multi candidate request even when a local scorer would disagree`() {
        val calls = AtomicInteger()
        val transport = OpenRouterTransport { _, _ ->
            calls.incrementAndGet()
            CompletableFuture.completedFuture(keepResponse("status"))
        }
        val brain = OpenRouterTacticalBrain(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            transport,
        )
        val session = brain.openSession(open)

        assertEquals("status", brain.decide(session, context(turn = 1)).toCompletableFuture().join().actionId)
        assertEquals("status", brain.decide(session, context(turn = 2)).toCompletableFuture().join().actionId)
        assertEquals(2, calls.get())
    }

    @Test
    fun `router never receives a publicly immune action while a viable action exists`() {
        val calls = AtomicInteger()
        val brain = OpenRouterTacticalBrain(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            OpenRouterTransport { _, _ ->
                calls.incrementAndGet()
                CompletableFuture.completedFuture(keepResponse("move"))
            },
        )
        val base = context(turn = 1)
        val ghostState = BattleStateView(
            battleId = base.state.battleId,
            format = base.state.format,
            turn = base.state.turn,
            pokemon = listOf(
                pokemon(allyId, BattleSide.ALLY, "showdown:ally"),
                pokemon(opponentId, BattleSide.OPPONENT, "showdown:ghost", knownTypeIds = setOf("ghost")),
            ),
            field = base.state.field,
            remainingPokemonBySide = base.state.remainingPokemonBySide,
            observedEvents = base.state.observedEvents,
            inferences = base.state.inferences,
        )
        val decisionContext = BattleDecisionContext(
            requestId = base.requestId,
            state = ghostState,
            candidates = base.candidates,
            deadlineEpochMillis = base.deadlineEpochMillis,
            memory = base.memory,
        )

        val decision = brain.decide(brain.openSession(open), decisionContext).toCompletableFuture().join()

        assertEquals("status", decision.actionId)
        assertEquals(0, calls.get())
    }

    @Test
    fun `legacy maximum calls setting cannot suppress router decisions`() {
        val calls = AtomicInteger()
        val transport = OpenRouterTransport { _, _ ->
            calls.incrementAndGet()
            CompletableFuture.completedFuture(keepResponse("move"))
        }
        val brain = OpenRouterTacticalBrain(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model", maximumCallsPerBattle = 1),
            transport,
        )
        val session = brain.openSession(open)

        (1..20).forEach { turn ->
            brain.decide(session, context(turn, boardChange = turn == 5 || turn == 10)).toCompletableFuture().join()
        }

        assertEquals(20, calls.get())
    }

    @Test
    fun `decision modes never reduce router calls`() {
        BetterAiDecisionMode.entries.forEach { mode ->
            val calls = AtomicInteger()
            val brain = OpenRouterTacticalBrain(
                BetterAiConfig(
                    enabled = true,
                    apiKey = "test",
                    model = "test/model",
                    decisionMode = mode,
                ),
                OpenRouterTransport { _, _ ->
                    calls.incrementAndGet()
                    CompletableFuture.completedFuture(keepResponse("move"))
                },
            )
            val session = brain.openSession(open)

            (1..5).forEach { turn ->
                brain.decide(session, context(turn)).toCompletableFuture().join()
            }

            assertEquals(5, calls.get(), "$mode must consult Router for every multi-candidate request")
        }
    }

    @Test
    fun `router failure is propagated for the outer fallback chain to handle`() {
        val brain = OpenRouterTacticalBrain(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            OpenRouterTransport { _, _ -> CompletableFuture.completedFuture("{}") },
        )

        assertThrows(CompletionException::class.java) {
            brain.decide(brain.openSession(open), context(turn = 1)).toCompletableFuture().join()
        }
    }

    @Test
    fun `decision summary journal failure cannot replace a legal router decision`() {
        val brain = OpenRouterTacticalBrain(
            BetterAiConfig(
                enabled = true,
                apiKey = "test",
                model = "test/model",
                logDecisionSummary = true,
            ),
            OpenRouterTransport { _, _ -> CompletableFuture.completedFuture(validResponse("move", 1)) },
            decisionSummarySink = OpenRouterDecisionSummarySink {
                throw IllegalStateException("journal unavailable")
            },
        )

        val decision = brain.decide(brain.openSession(open), context(turn = 1)).toCompletableFuture().join()

        assertEquals("move", decision.actionId)
    }

    @Test
    fun `decision summary journal cannot block a completed router decision`() {
        val appendEntered = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val brain = OpenRouterTacticalBrain(
            BetterAiConfig(
                enabled = true,
                apiKey = "test",
                model = "test/model",
                logDecisionSummary = true,
            ),
            OpenRouterTransport { _, _ -> CompletableFuture.completedFuture(validResponse("move", 1)) },
            decisionSummarySink = OpenRouterDecisionSummarySink {
                appendEntered.countDown()
                releaseAppend.await(5, TimeUnit.SECONDS)
            },
        )

        val caller = CompletableFuture.supplyAsync {
            brain.decide(brain.openSession(open), context(turn = 1)).toCompletableFuture().join()
        }
        try {
            assertEquals("move", caller.get(1, TimeUnit.SECONDS).actionId)
            assertTrue(appendEntered.await(1, TimeUnit.SECONDS))
        } finally {
            releaseAppend.countDown()
        }
    }

    @Test
    fun `single legal action is returned without a network call`() {
        val calls = AtomicInteger()
        val brain = OpenRouterTacticalBrain(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            OpenRouterTransport { _, _ ->
                calls.incrementAndGet()
                CompletableFuture.completedFuture(keepResponse("move"))
            },
        )
        val base = context(turn = 1)
        val only = BattleDecisionContext(
            base.requestId,
            base.state,
            listOf(base.candidates.first()),
            base.deadlineEpochMillis,
            base.memory,
        )

        assertEquals("move", brain.decide(brain.openSession(open), only).toCompletableFuture().join().actionId)
        assertEquals(0, calls.get())
    }

    @Test
    fun `cancelling a router decision cancels the in flight transport`() {
        val transportFuture = CompletableFuture<String>()
        val brain = OpenRouterTacticalBrain(
            BetterAiConfig(enabled = true, apiKey = "test", model = "test/model"),
            OpenRouterTransport { _, _ -> transportFuture },
        )

        val decisionFuture = brain.decide(brain.openSession(open), context(turn = 1)).toCompletableFuture()
        decisionFuture.cancel(true)

        assertTrue(transportFuture.isCancelled)
    }

    private fun context(turn: Int, boardChange: Boolean = false): BattleDecisionContext = BattleDecisionContext(
        requestId = UUID.randomUUID(),
        state = BattleStateView(
            battleId = battleId,
            format = BattleFormat.SINGLE,
            turn = turn,
            pokemon = listOf(
                pokemon(allyId, BattleSide.ALLY, "showdown:ally"),
                pokemon(opponentId, BattleSide.OPPONENT, "showdown:opponent"),
            ),
            field = BattleFieldStateView.empty(),
            remainingPokemonBySide = mapOf(BattleSide.ALLY to 3, BattleSide.OPPONENT to 3),
            observedEvents = if (boardChange) {
                listOf(BattleObservedEventView(turn.toLong(), turn, BattleObservedEventKind.SWITCHED, opponentId))
            } else {
                emptyList()
            },
            inferences = emptyList(),
        ),
        candidates = listOf(
            BattleActionCandidate(
                actionId = "move",
                kind = BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 0,
                moveId = "cobblemon:tackle",
                targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                moveDetails = BattleMoveCandidateView(
                    "normal",
                    BattleMoveDamageCategory.PHYSICAL,
                    50.0,
                    100.0,
                    0,
                    10,
                ),
            ),
            BattleActionCandidate(
                actionId = "status",
                kind = BattleActionKind.USE_MOVE,
                actorSlot = 0,
                moveSlot = 1,
                moveId = "cobblemon:growl",
                targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)),
                moveDetails = BattleMoveCandidateView(
                    "normal",
                    BattleMoveDamageCategory.STATUS,
                    0.0,
                    100.0,
                    0,
                    10,
                ),
            ),
        ),
        deadlineEpochMillis = Long.MAX_VALUE,
    )

    private fun pokemon(
        id: UUID,
        side: BattleSide,
        species: String,
        activeSlot: Int? = 0,
        hpFraction: Double = 1.0,
        knownTypeIds: Set<String> = setOf("normal"),
    ) = BattlePokemonStateView(
        battlePokemonId = id,
        side = side,
        activeSlot = activeSlot,
        speciesId = species,
        formId = null,
        level = 50,
        hpFraction = hpFraction,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = emptySet(),
        knownAbilityId = null,
        knownHeldItemId = null,
        fainted = false,
        knownTypeIds = knownTypeIds,
        combatStats = if (side == BattleSide.ALLY) {
            BattleCombatStatRangesView.exact(180, 140, 120, 100, 110, 90)
        } else {
            BattleCombatStatRangesView(
                maxHp = BattleIntegerRange(150, 200),
                attack = BattleIntegerRange(90, 160),
                defence = BattleIntegerRange(90, 160),
                specialAttack = BattleIntegerRange(90, 160),
                specialDefence = BattleIntegerRange(90, 160),
                speed = BattleIntegerRange(80, 150),
                knowledge = BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
            )
        },
    )

    private fun validResponse(actionId: String, turn: Int) = """{
      "actionId":"$actionId","confidence":0.8,
      "predictionResponse":"SWITCH","predictionConfidence":0.7,
      "predictionActorSlot":0,
      "planOperation":"REPLACE","planIntent":"APPLY_PRESSURE","targetRole":null,
      "expiresAtTurn":${turn + 2},"abortIf":["OPPONENT_BOARD_CHANGED"],
      "reasonCodes":["EXPECTED_SWITCH"],"mindGameIntent":"PREDICT_SWITCH",
      "decisionSummary":"공개된 상성 우위와 현재 체력으로 즉시 공격을 선택했습니다."
    }"""

    private fun keepResponse(actionId: String) = """{
      "actionId":"$actionId","confidence":0.8,
      "predictionResponse":"UNKNOWN","predictionConfidence":0.0,
      "predictionActorSlot":null,
      "planOperation":"KEEP","planIntent":null,"targetRole":null,
      "expiresAtTurn":0,"abortIf":[],"reasonCodes":["MINIMUM_VARIANCE"],"mindGameIntent":"NONE",
      "decisionSummary":"공개 정보 기준으로 가장 안정적인 행동을 선택했습니다."
    }"""

    private fun promptDigest(request: String) = JsonParser.parseString(
        JsonParser.parseString(request).asJsonObject
            .getAsJsonArray("messages")[1].asJsonObject["content"].asString,
    ).asJsonObject

    private fun promptDigest(request: com.google.gson.JsonObject) = JsonParser.parseString(
        request.getAsJsonArray("messages")[1].asJsonObject["content"].asString,
    ).asJsonObject
}
