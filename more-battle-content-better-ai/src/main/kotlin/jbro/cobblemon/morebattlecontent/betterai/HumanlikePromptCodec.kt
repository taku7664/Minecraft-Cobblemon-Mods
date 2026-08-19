package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.morebattlecontent.api.ai.*

internal object HumanlikePromptCodec {
    private val gson = Gson()

    fun requestJson(
        config: BetterAiConfig,
        open: BattleBrainOpenContext,
        context: BattleDecisionContext,
        modelCapabilities: OpenRouterModelCapabilities? = null,
    ): String {
        val reasoningRequested = modelCapabilities?.supportsReasoning == true
        val root = JsonObject().apply {
            addProperty("model", config.model)
            if (modelCapabilities == null || "temperature" in modelCapabilities.supportedParameters) {
                addProperty("temperature", 0.25)
            }
            addProperty("max_tokens", config.decisionMode.maximumOutputTokens)
            addProperty("stream", false)
            if (reasoningRequested) {
                add("reasoning", JsonObject().apply {
                    addProperty("effort", config.decisionMode.reasoningEffort)
                    addProperty("exclude", true)
                })
            }
            add("messages", JsonArray().apply {
                val summaryDoctrine = if (config.logDecisionSummary) DECISION_SUMMARY_DOCTRINE else ""
                add(message("system", SYSTEM_DOCTRINE + difficultyDoctrine(open.trainerProfile.difficulty.tier) + summaryDoctrine))
                add(message("user", gson.toJson(digest(open, context))))
            })
            add("provider", JsonObject().apply {
                addProperty("require_parameters", config.requireStructuredOutput || reasoningRequested)
            })
            if (config.requireStructuredOutput) {
                add("response_format", structuredResponseFormat(config.logDecisionSummary))
            }
        }
        return gson.toJson(root)
    }

    fun parseDecision(
        content: String,
        context: BattleDecisionContext,
        mindGamesEnabled: Boolean = true,
        difficultyTier: BattleTrainerTier = BattleTrainerTier.ADVANCED,
    ): BattleDecision {
        val root = JsonParser.parseString(content).asJsonObject
        val actionId = root.requiredString("actionId")
        require(context.candidates.any { it.actionId == actionId }) { "External decision selected an unknown action" }
        val prediction = if (difficultyTier == BattleTrainerTier.INTRODUCTORY) null else parsePrediction(root, context)
        val planUpdate = if (difficultyTier == BattleTrainerTier.INTRODUCTORY) {
            BattlePlanUpdate.clear()
        } else {
            parsePlanUpdate(root, context)
        }
        val mindGameIntent = parseMindGameIntent(root, prediction, context, mindGamesEnabled)
        return BattleDecision(
            requestId = context.requestId,
            actionId = actionId,
            confidence = runCatching { root["confidence"]?.asDouble?.takeIf { it in 0.0..1.0 } }.getOrNull(),
            tags = setOf("openrouter_brain_choice_v14", "difficulty_${difficultyTier.name.lowercase()}"),
            advice = BattleDecisionAdvice(
                prediction = prediction,
                planUpdate = planUpdate,
                reasonCodes = runCatching {
                    root.getAsJsonArray("reasonCodes")?.mapTo(linkedSetOf()) {
                        enumValue<BattleDecisionReason>(it.asString)
                    }.orEmpty()
                }.getOrDefault(emptySet()),
                mindGameIntent = mindGameIntent,
            ),
        )
    }

    fun parseDecisionSummary(content: String): String? = runCatching {
        val root = JsonParser.parseString(content).asJsonObject
        OpenRouterDecisionSummaryDiagnostics.sanitizeSummary(root.optionalString("decisionSummary"))
    }.getOrNull()

    private fun parsePrediction(root: JsonObject, context: BattleDecisionContext): BattlePrediction? = runCatching {
        val response = enumValue<BattlePredictedResponse>(root.requiredString("predictionResponse"))
        val confidence = root["predictionConfidence"].asDouble
        val actorSlot = root["predictionActorSlot"].let { value ->
            if (value == null || value.isJsonNull) null else value.asInt
        }
        if (response == BattlePredictedResponse.UNKNOWN) {
            require(confidence == 0.0 && actorSlot == null)
            null
        } else {
            if (actorSlot != null) {
                require(context.state.pokemon.any {
                    it.side == BattleSide.OPPONENT && it.activeSlot == actorSlot && !it.fainted
                }) { "Prediction actor slot must identify an active opponent" }
            }
            BattlePrediction(response, confidence, actorSlot)
        }
    }.getOrNull()

    private fun parsePlanUpdate(root: JsonObject, context: BattleDecisionContext): BattlePlanUpdate = runCatching {
        when (val operation = enumValue<BattlePlanUpdateOperation>(root.requiredString("planOperation"))) {
            BattlePlanUpdateOperation.KEEP -> BattlePlanUpdate.keep().also {
                requireEmptyPlanPayload(root, operation)
            }
            BattlePlanUpdateOperation.CLEAR -> BattlePlanUpdate.clear().also {
                requireEmptyPlanPayload(root, operation)
            }
            BattlePlanUpdateOperation.REPLACE -> BattlePlanUpdate.replace(
                BattlePlanView(
                    intent = enumValue(root.requiredString("planIntent")),
                    targetRole = root.optionalString("targetRole")?.let(::enumValue),
                    expiresAtTurn = root["expiresAtTurn"].asInt.also {
                        require(it in context.state.turn..context.state.turn + MAX_PLAN_TURNS)
                    },
                    abortIf = root.getAsJsonArray("abortIf").mapTo(linkedSetOf()) {
                        enumValue<BattlePlanAbortCondition>(it.asString)
                    },
                ),
            )
        }
    }.getOrElse { BattlePlanUpdate.keep() }

    private fun requireEmptyPlanPayload(root: JsonObject, operation: BattlePlanUpdateOperation) {
        require(operation != BattlePlanUpdateOperation.REPLACE)
        require(root["planIntent"]?.isJsonNull != false && root["targetRole"]?.isJsonNull != false)
        require(root["expiresAtTurn"]?.asInt == 0 && root.getAsJsonArray("abortIf")?.isEmpty != false)
    }

    private fun parseMindGameIntent(
        root: JsonObject,
        prediction: BattlePrediction?,
        context: BattleDecisionContext,
        enabled: Boolean,
    ): BattleMindGameIntent {
        val requested = runCatching {
            enumValue<BattleMindGameIntent>(root.requiredString("mindGameIntent"))
        }.getOrDefault(BattleMindGameIntent.NONE)
        if (requested == BattleMindGameIntent.NONE) return requested
        val calibration = context.memory.predictionCalibration
        val calibrated = calibration.samples < CALIBRATION_SAMPLE_FLOOR ||
            requireNotNull(calibration.hitRate) >= MINIMUM_CALIBRATED_HIT_RATE
        return requested.takeIf {
            enabled && prediction != null && prediction.confidence >= MINIMUM_MIND_GAME_CONFIDENCE &&
                calibration.consecutiveMisses < MAXIMUM_CONSECUTIVE_MISSES && calibrated
        } ?: BattleMindGameIntent.NONE
    }

    private fun digest(open: BattleBrainOpenContext, context: BattleDecisionContext): Map<String, Any?> {
        val aliases = pokemonAliases(context.state)
        val strategy = open.strategy
        val hasJointCandidates = context.candidates.any { it.kind == BattleActionKind.COMPOSITE }
        val root = linkedMapOf<String, Any?>(
            "promptVersion" to EFFECTIVE_PROMPT_VERSION,
            "turn" to context.state.turn,
            "format" to open.format.name,
            "knowledgePolicy" to open.knowledgePolicy.name,
            "trainer" to linkedMapOf(
                "skillLevel" to open.trainerProfile.skillLevel,
                "difficulty" to open.trainerProfile.difficulty,
                "personality" to open.trainerProfile.personality,
                "strategyId" to strategy?.strategyId,
                "aiSummary" to strategy?.aiSummary,
                "objectives" to strategy?.objectives?.map { it.name }.orEmpty(),
                "members" to strategy?.members?.map { member ->
                    linkedMapOf(
                        "speciesId" to member.speciesId,
                        "roles" to member.roles.map { it.name },
                        "tacticalSummary" to member.tacticalSummary,
                        "preferredMoveIds" to member.preferredMoveIds.sorted(),
                        "leadPriority" to member.leadPriority,
                        "preservationPriority" to member.preservationPriority,
                    )
                }.orEmpty(),
            ),
            "remainingPokemonBySide" to context.state.remainingPokemonBySide.mapKeys { (side, _) -> side.name },
            "board" to context.state.pokemon.map { pokemon ->
                linkedMapOf(
                    "slot" to aliases.getValue(pokemon.battlePokemonId),
                    "side" to pokemon.side.name,
                    "activeSlot" to pokemon.activeSlot,
                    "speciesId" to pokemon.speciesId,
                    "formId" to pokemon.formId,
                    "level" to pokemon.level,
                    "hpFraction" to pokemon.hpFraction,
                    "statusId" to pokemon.statusId,
                    "statStages" to pokemon.statStages,
                    "types" to pokemon.knownTypeIds.sorted(),
                    "revealedMoves" to pokemon.knownMoveIds.sorted(),
                    "revealedAbility" to pokemon.knownAbilityId,
                    "revealedItem" to pokemon.knownHeldItemId,
                    "fainted" to pokemon.fainted,
                    "combatStats" to pokemon.combatStats,
                )
            },
            "field" to context.state.field,
            "recentEvents" to recentEvents(context, aliases),
            "inferences" to context.state.inferences.map { inference ->
                linkedMapOf(
                    "subject" to aliases[inference.subjectPokemonId],
                    "relatedPokemon" to inference.relatedPokemonId?.let(aliases::get),
                    "categoryId" to inference.categoryId,
                    "candidateId" to inference.candidateId,
                    "confidence" to inference.confidence.name,
                    "abilityAvailability" to inference.abilityAvailability?.name,
                    "probabilityRange" to inference.probabilityRange,
                    "basis" to inference.basis.map { it.name }.sorted(),
                    "evidenceEventSequences" to inference.evidenceEventSequences,
                )
            },
            "candidates" to context.candidates.map { candidate ->
                if (candidate.kind == BattleActionKind.COMPOSITE) jointCandidateDigest(candidate)
                else candidateDigest(candidate, aliases, context)
            },
            "memory" to linkedMapOf(
                "activePlan" to context.memory.activePlan,
                "tendencies" to context.memory.tendencies,
                "predictionCalibration" to context.memory.predictionCalibration,
                "turnsSinceLastSwitch" to context.memory.turnsSinceLastSwitch,
                "switchesThisBattle" to context.memory.switchesThisBattle,
                "lastMoveId" to context.memory.lastMoveId,
                "sameMoveRepeatCount" to context.memory.sameMoveRepeatCount,
            ),
        )
        if (hasJointCandidates) {
            root["atomicActions"] = context.candidates
                .flatMap { candidate -> candidate.componentActions }
                .distinctBy { candidate -> candidate.actionId }
                .map { candidate -> candidateDigest(candidate, aliases, context) }
        }
        return root
    }

    private fun pokemonAliases(state: BattleStateView): Map<java.util.UUID, String> = buildMap {
        BattleSide.entries.forEach { side ->
            val sideName = side.name.lowercase()
            state.pokemon.filter { it.side == side && it.activeSlot != null }
                .sortedBy { it.activeSlot }
                .forEach { pokemon -> put(pokemon.battlePokemonId, "$sideName${pokemon.activeSlot}") }
            state.pokemon.filter { it.side == side && it.activeSlot == null }
                .sortedWith(
                    compareBy<BattlePokemonStateView> { it.speciesId }
                        .thenBy { it.formId.orEmpty() }
                        .thenBy { it.battlePokemonId.toString() },
                )
                .forEachIndexed { index, pokemon -> put(pokemon.battlePokemonId, "${sideName}Bench$index") }
        }
    }

    private fun candidateDigest(
        candidate: BattleActionCandidate,
        aliases: Map<java.util.UUID, String>,
        context: BattleDecisionContext,
    ): Map<String, Any?> =
        linkedMapOf(
            "actionId" to candidate.actionId,
            "kind" to candidate.kind.name,
            "actorSlot" to candidate.actorSlot,
            "moveId" to candidate.moveId,
            "move" to candidate.moveDetails,
            "targets" to candidate.targets.map { target ->
                linkedMapOf(
                    "side" to target.side.name,
                    "slot" to target.slot,
                    "activeAlias" to "${target.side.name.lowercase()}${target.slot}",
                )
            },
            "switchTo" to candidate.switchPokemonId?.let(aliases::get),
            "switchTargetPublicFacts" to PublicSwitchTypeFactsCalculator.forCandidate(candidate, context)?.let { facts ->
                linkedMapOf(
                    "hpFraction" to facts.hpFraction,
                    "types" to facts.knownTypeIds,
                    "activeOpponentTypeChartMultipliers" to facts.activeOpponentTypeChartMultipliers.map { multiplier ->
                        linkedMapOf(
                            "opponent" to "opponent${multiplier.opponentActiveSlot}",
                            "attackingTypeId" to multiplier.attackingTypeId,
                            "multiplier" to multiplier.multiplier,
                            "basis" to BattleCalculationBasis.PUBLIC_TYPES.name,
                        )
                    },
                )
            },
            "mechanic" to candidate.mechanic,
            "facts" to candidate.facts,
            "components" to candidate.componentActions.map { candidateDigest(it, aliases, context) },
        )

    private fun jointCandidateDigest(candidate: BattleActionCandidate): Map<String, Any?> = linkedMapOf(
        "actionId" to candidate.actionId,
        "kind" to candidate.kind.name,
        "componentActionIds" to candidate.componentActionIds,
    )

    private fun recentEvents(
        context: BattleDecisionContext,
        aliases: Map<java.util.UUID, String>,
    ): List<Map<String, Any?>> {
        val firstTurn = (context.state.turn - RECENT_EVENT_TURNS + 1).coerceAtLeast(0)
        return context.state.observedEvents.filter { it.turn >= firstTurn }.map { event ->
            linkedMapOf(
                "sequence" to event.sequence,
                "turn" to event.turn,
                "kind" to event.kind.name,
                "actor" to event.actorPokemonId?.let(aliases::get),
                "targets" to event.targetPokemonIds.mapNotNull(aliases::get),
                "publicValueId" to event.publicValueId,
                "hpFractionDelta" to event.hpFractionDelta,
                "baseMovePriority" to event.baseMovePriority,
                "precedingAction" to event.precedingActionSequence?.let { actionSequence ->
                    linkedMapOf(
                        "sequence" to actionSequence,
                        "actor" to event.precedingActionActorPokemonId?.let(aliases::get),
                        "moveId" to event.precedingActionMoveId,
                    )
                },
                "publicSourceEffectId" to event.publicSourceEffectId,
                "moveOutcome" to event.moveOutcome?.let { outcome ->
                    linkedMapOf(
                        "kind" to outcome.kind.name,
                        "moveId" to outcome.moveId,
                        "publicEffectId" to outcome.publicEffectId,
                        "hitCount" to outcome.hitCount,
                    )
                },
            )
        }
    }

    private fun message(role: String, content: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    private fun difficultyDoctrine(tier: BattleTrainerTier): String = when (tier) {
        BattleTrainerTier.INTRODUCTORY ->
            " Difficulty contract: INTRODUCTORY. Play like a novice using obvious current-turn value only. " +
                "Do not predict, condition, or maintain a multi-turn plan; return UNKNOWN prediction, CLEAR plan, " +
                "and NONE mindGameIntent. Do not intentionally choose an illegal action."
        BattleTrainerTier.STANDARD ->
            " Difficulty contract: STANDARD. Make a competent current-turn choice and use the supplied strategy, " +
                "but do not reason beyond the immediate opponent response. Avoid speculative mind games."
        BattleTrainerTier.ADVANCED ->
            " Difficulty contract: ADVANCED. Evaluate the current action and the most credible immediate response, " +
                "maintain a short plan, and use calibrated predictions only when public evidence supports them."
        BattleTrainerTier.BOSS ->
            " Difficulty contract: BOSS. Play as a champion. Evaluate the opponent response and your counter-response, " +
                "protect win conditions, use the complete strategy brief, and exploit every fair public inference " +
                "within the supplied hypothesis budget."
    }

    private fun structuredResponseFormat(includeDecisionSummary: Boolean): JsonObject = JsonObject().apply {
        addProperty("type", "json_schema")
        add("json_schema", JsonObject().apply {
            addProperty("name", "battle_decision")
            addProperty("strict", true)
            val schema = JsonParser.parseString(RESPONSE_SCHEMA).asJsonObject
            if (includeDecisionSummary) {
                schema.getAsJsonObject("properties").add(
                    "decisionSummary",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty(
                            "description",
                            "One concise sentence naming only the decisive public battle facts behind actionId; " +
                                "never hidden chain-of-thought.",
                        )
                    },
                )
                schema.getAsJsonArray("required").add("decisionSummary")
            }
            add("schema", schema)
        })
    }

    private fun JsonObject.requiredString(name: String): String = get(name).also {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
    }.asString

    private fun JsonObject.optionalString(name: String): String? = get(name).let {
        if (it == null || it.isJsonNull) null else it.asString
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValueOf(value)

    private const val MAX_PLAN_TURNS = 8
    private const val MINIMUM_MIND_GAME_CONFIDENCE = 0.6
    private const val MAXIMUM_CONSECUTIVE_MISSES = 2
    private const val CALIBRATION_SAMPLE_FLOOR = 4
    private const val MINIMUM_CALIBRATED_HIT_RATE = 0.55
    private const val EFFECTIVE_PROMPT_VERSION = "brain-choice-v14"
    private const val RECENT_EVENT_TURNS = 3
    private const val SYSTEM_DOCTRINE = """You are the sole decision-making brain of one trainer NPC. Choose exactly one actionId from candidates on every decision. In doubles, candidates are joint actions whose componentActionIds reference the full mechanical descriptions in atomicActions. The server computed legality and supplied mechanical facts only; those facts are not recommendations or rankings. Difficulty changes planning depth, never factual accuracy. Every `hpFraction` is a ratio from 0.0 (fainted) through 1.0 (full health), never a raw HP count. For field effects, `remainingTurns` is an exact public count while `remainingTurnsRange.minimum..maximum` is an inclusive public uncertainty range caused by a possibly unrevealed duration extender; if both are absent, duration is unknown or indefinite. Never collapse a range to one invented value. Board hpFraction, known types, and supplied type-chart multipliers are authoritative public facts at every difficulty. For a switch candidate, read switchTargetPublicFacts directly instead of reconstructing its hpFraction or defensive typing from memory. Compare equal multipliers as equal; do not claim that one switch target is safer when its supplied multiplier is equal to or greater than the alternative. Null or UNKNOWN means the mechanic was not resolved, so never invent a precise result. `move.effects.effects` contains declarative effects, `move.effects.requirements` contains statically extracted use conditions, and `move.effects.mechanicFlags` preserves the engine's declared move flags. A requirement is a mechanical gate, not a recommendation; if the public board proves it false, expect the move to fail, while a condition that depends on hidden or simultaneous information remains uncertain. `DECLARATIVE_PARTIAL` means the lists are intentionally incomplete: an absent effect or requirement does not mean impossible. `scriptedBehavior=true` means callbacks exist beyond the listed facts. Each `move.effects.effects[].probability` is conditional on the move reaching that effect; it is not a final hit, immunity, or success probability. `facts.statusEffectProbability` combines only base accuracy and that declared chance, while modifiers and immunities remain unknown. Inferences are hypotheses, not hidden facts: `POSSIBLE` means only that public rules allow the candidate, and no unstated probability or preference may be assumed. For ability inferences, `abilityAvailability=REGULAR|HIDDEN` is the public species ability classification, not a probability. Consider the strategic downside of every supplied possible immunity, absorption, redirection, or retaliation ability when comparing actions, while never assuming which unrevealed ability is actually present. Use probabilityRange only when supplied and read basis as its public provenance. `observed_action_order` reports only which public actions appeared first when their moves had the same base priority; it is not proof of raw Speed because ties and dynamic ordering modifiers may exist. `precedingAction` only identifies the still-open public action window for a source-less HP loss on an explicit target; it does not prove damage causation. A `MOVE_OUTCOME` records only fields explicitly declared by the public battle message; a public outcome signal does not identify an unstated cause or hidden modifier, and sequence adjacency is not causation. `SUBSTITUTE_DAMAGED` means a public substitute absorbed damage; it does not mean the real Pokemon lost HP and it provides no damage amount or attacker. `PROTECTION_STARTED` means the public Protect effect began; it does not guarantee a later block. `standardDamage*` fields are a non-critical Gen 9 base-formula projection over fair public stat ranges; they exclude every input named in `unknowns`, including dynamic ability, item, field, status, mechanic, and move-specific modifiers. They are bounds for judgment, not the actual future outcome. Distinguish base accuracy and type-chart multipliers from fully resolved hit or knockout odds. Hidden opponent moves, items, abilities, stats, EVs, and bench details are absent unless explicitly revealed or listed as an inference. Use the trainer personality, complete strategy brief, public board, history, tendencies, prior prediction results, and active plan to make the strategic judgment yourself. Continue, replace, or clear a plan as the current board warrants. Predict the opponent only when evidence supports it; samples below 3 are weak and UNKNOWN is valid. In doubles, predictionActorSlot must identify the specific opponent active slot being predicted. Return schema fields only and no prose."""
    private const val DECISION_SUMMARY_DOCTRINE = """ Set decisionSummary to one concise sentence explaining only the decisive public battle facts behind the selected action. Do not expose hidden chain-of-thought, private reasoning, or undisclosed information."""
    private const val RESPONSE_SCHEMA = """{
      "type":"object","additionalProperties":false,
      "properties":{
        "actionId":{"type":"string","description":"Server-issued legal action ID chosen from candidates."},
        "confidence":{"type":"number","minimum":0,"maximum":1,"description":"Confidence that this action is strategically best, not its hit chance."},
        "predictionResponse":{"type":"string","enum":["MOVE","SWITCH","OTHER","UNKNOWN"],"description":"Most likely response of the declared opposing active slot."},
        "predictionConfidence":{"type":"number","minimum":0,"maximum":1,"description":"Probability assigned to predictionResponse; use 0 with UNKNOWN."},
        "predictionActorSlot":{"type":["integer","null"],"minimum":0,"maximum":1,"description":"Opposing active slot being predicted; required for a specific doubles read and null with UNKNOWN."},
        "planOperation":{"type":"string","enum":["KEEP","REPLACE","CLEAR"],"description":"Whether the persistent strategic plan still fits the public board."},
        "planIntent":{"type":["string","null"],"enum":["APPLY_PRESSURE","CREATE_SAFE_ENTRY","PRESERVE_CORE","ESTABLISH_FIELD","DENY_SETUP","CLOSE_GAME",null]},
        "targetRole":{"type":["string","null"],"enum":["ACE","SETUP_ENABLER","WEAKNESS_COVER","WALLBREAKER","CLEANER","PIVOT","SPEED_CONTROL","FIELD_SUPPORT","DISRUPTOR","GENERALIST",null]},
        "expiresAtTurn":{"type":"integer","minimum":0},
        "abortIf":{"type":"array","items":{"type":"string","enum":["TARGET_ROLE_UNAVAILABLE","ACTIVE_BELOW_CRITICAL_HP","OPPONENT_BOARD_CHANGED","WIN_PATH_CHANGED"]}},
        "reasonCodes":{"type":"array","items":{"type":"string","enum":["PRESERVE_WIN_CONDITION","SAFE_AGAINST_SECOND_RESPONSE","EXPECTED_SWITCH","EXPECTED_MOVE","PLAN_CONTINUATION","MINIMUM_VARIANCE","MATCHUP_REVERSAL"]}},
        "mindGameIntent":{"type":"string","enum":["NONE","PREDICT_SWITCH","CONDITION_THEN_BREAK","BAIT","INFORMATION_DENIAL"]}
      },
      "required":["actionId","confidence","predictionResponse","predictionConfidence","predictionActorSlot","planOperation","planIntent","targetRole","expiresAtTurn","abortIf","reasonCodes","mindGameIntent"]
    }"""
}
