package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject
import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID
import java.util.zip.ZipInputStream

/** Test adapter, deliberately separate from privileged team definitions and referee state. */
internal object EmbeddedTeamInput {
    private val declarativeEffects by lazy {
        ZipInputStream(requireNotNull(javaClass.getResourceAsStream("/data/cobblemon/showdown.zip"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("Missing embedded data/moves.js")
                if (entry.name == "data/moves.js") return@use BattleDeclarativeMoveEffects.parse(zip.readBytes().toString(Charsets.UTF_8))
            }
            @Suppress("UNREACHABLE_CODE")
            emptyMap<String, BattleMoveEffectsView>()
        }
    }
    private data class Seen(val ident: String, var species: String, var level: Int, var hp: Double,
        var status: String?, var active: Boolean = true, var types: Set<String> = emptySet(),
        var added: String? = null, val stages: MutableMap<String, Int> = mutableMapOf(),
        val moves: MutableSet<String> = linkedSetOf(), var ability: String? = null, var item: String? = null,
        val volatileEffects: MutableSet<String> = linkedSetOf(), var originalMoves: Set<String>? = null,
        var gastroAcid: Boolean = false, var abilityEnded: Boolean = false)

    fun identity(ident: String): String = ident.take(2) + ":" + ident.substringAfter(':').trim()
    private fun uuid(ident: String) = UUID.nameUUIDFromBytes(identity(ident).toByteArray(Charsets.UTF_8))
    private fun activeSlot(ident: String): Int? = ident.getOrNull(2)?.takeIf { it in 'a'..'b' }?.minus('a')
    private fun id(text: String) = text.substringAfter(": ").lowercase().filter(Char::isLetterOrDigit)
    private fun condition(value: String): Pair<Double, String?> {
        val parts = value.split(' ')
        val hp = parts[0].split('/')
        return (if (hp[0] == "0") 0.0 else hp[0].toDouble() / hp[1].toDouble()) to
            parts.getOrNull(1)?.takeUnless { it == "fnt" }
    }

    fun context(
        input: JsonObject,
        battleId: UUID,
        turn: Int,
        requestNumber: Int,
        memory: (BattleStateView) -> BattleTacticalMemoryView = { BattleTacticalMemoryView.empty() },
    ): BattleDecisionContext {
        val format = battleFormat(input)
        val ownSide = input["side"].asString
        fun side(ident: String) = if (ident.startsWith(ownSide)) BattleSide.ALLY else BattleSide.OPPONENT
        val speciesData = input.getAsJsonObject("species")
        val moveData = input.getAsJsonObject("moves")
        val publicLearnsets = input.getAsJsonObject("publicLearnsets") ?: JsonObject()
        val ruleMoves = JsonObject().apply {
            publicLearnsets.entrySet().forEach { (_, pool) ->
                pool.asJsonObject.getAsJsonObject("moves").entrySet().forEach { (move, data) -> add(move, data) }
            }
            moveData.entrySet().forEach { (move, data) -> add(move, data) }
        }
        fun maximumPp(move: String): Int? = ruleMoves.getAsJsonObject(move)?.let {
            it["maxPp"]?.asInt ?: (it["pp"].asInt * 8 / 5)
        }
        val pp = EmbeddedPublicPp()
        val seen = linkedMapOf<String, Seen>()
        val constraints = EmbeddedPublicActionConstraints()
        val sizes = mutableMapOf<BattleSide, Int>()
        val events = mutableListOf<BattleObservedEventView>()
        val sideEffects = BattleSide.entries.associateWith { linkedMapOf<String, Int>() }
        val fields = linkedSetOf<String>()
        var weather: String? = null
        var eventTurn = 0
        fun baseTypes(species: String): Set<String> = if (species.startsWith("arceus")) emptySet() else
            speciesData.getAsJsonObject(species)?.getAsJsonArray("types")?.map { it.asString }?.toSet().orEmpty()
        input.getAsJsonArray("publicLog").forEachIndexed { index, element ->
            constraints.observe(element.asString)
            val p = element.asString.split('|')
            val kind = p.getOrNull(1) ?: return@forEachIndexed
            val actor = p.getOrNull(2).orEmpty()
            val key = identity(actor)
            val current = seen[key]
            val metadata = p.getOrNull(3)?.let(::id)?.let { ruleMoves.getAsJsonObject(it) }
            val pressureLoss = if (kind == "move") EmbeddedPublicPressure.loss(actor,
                p.getOrNull(4)?.takeIf(String::isNotBlank),
                metadata?.get("pressureTarget")?.asString ?: metadata?.get("target")?.asString,
                seen.values.filter { it.active && it.hp > 0.0 }.map {
                    EmbeddedPublicPressure.Participant(it.ident, it.ability, it.item, it.gastroAcid || it.abilityEnded)
                }, preparing = metadata?.get("charge")?.asBoolean == true && "[still]" in p) else 0
            pp.observe(element.asString, pressureLoss, ::maximumPp)
            fun event(eventKind: BattleObservedEventKind, value: String? = null) {
                events += BattleObservedEventView(index.toLong() * 2, eventTurn, eventKind,
                    actorPokemonId = current?.let { uuid(it.ident) }, publicValueId = value)
            }
            when (kind) {
                "turn" -> eventTurn = actor.toInt()
                "teamsize" -> sizes[side(actor)] = p[3].toInt()
                "switch", "drag", "replace" -> {
                    val inherited = if (kind == "replace" || kind == "switch" &&
                        p.drop(5).singleOrNull { it.startsWith("[from] ") }?.removePrefix("[from] ")
                            ?.let(::id) in setOf("batonpass", "shedtail")) {
                        seen.values.singleOrNull { it.active && it.ident.take(2) == actor.take(2) }
                            ?.volatileEffects.orEmpty().toSet()
                    } else emptySet()
                    seen.values.filter {
                        it.ident.take(2) == actor.take(2) && activeSlot(it.ident) == activeSlot(actor)
                    }.forEach {
                        if (kind != "replace") {
                            it.originalMoves?.let { moves -> it.moves.clear(); it.moves.addAll(moves) }
                            it.originalMoves = null
                        }
                        it.active = false; it.stages.clear(); it.added = null; it.types = baseTypes(it.species); it.volatileEffects.clear()
                    }
                    val details = p[3].split(',').map(String::trim)
                    val species = id(details[0])
                    val hp = condition(p[4])
                    val entry = Seen(actor, species, details.firstOrNull { it.matches(Regex("L\\d+")) }?.drop(1)?.toInt() ?: 100,
                        hp.first, hp.second, types = baseTypes(species))
                    current?.let { entry.moves += it.moves; entry.ability = it.ability; entry.item = it.item
                        entry.originalMoves = it.originalMoves }
                    entry.volatileEffects += inherited
                    seen[key] = entry
                    events += BattleObservedEventView(index.toLong() * 2, eventTurn, BattleObservedEventKind.SWITCHED, uuid(actor))
                }
                "-damage", "-heal" -> {
                    current?.let { val hp = condition(p[3]); it.hp = hp.first; it.status = hp.second }
                    val source = p.drop(4).singleOrNull { it.startsWith("[from] ") }?.removePrefix("[from] ")
                    val ownerTags = p.drop(4).filter { it.startsWith("[of]") }
                    val owner = when {
                        ownerTags.isEmpty() -> current
                        ownerTags.size == 1 && ownerTags.single().startsWith("[of] ") ->
                            seen[identity(ownerTags.single().removePrefix("[of] "))]
                        else -> null
                    }
                    if (source != null && owner != null) {
                        val resource = id(source).takeIf(String::isNotEmpty)
                        if (resource != null) when {
                            source.startsWith("item: ") -> owner.item = resource
                            source.startsWith("ability: ") -> owner.ability = resource
                        }
                    }
                }
                "faint" -> current?.let { it.hp = 0.0; it.volatileEffects.clear(); event(BattleObservedEventKind.FAINTED) }
                "move" -> current?.let { it.moves += id(p[3]); event(BattleObservedEventKind.MOVE_USED, id(p[3])) }
                "-status" -> current?.let { it.status = id(p[3]) }
                "-curestatus" -> current?.let { it.status = null }
                "-ability" -> current?.let { it.ability = id(p[3]); it.abilityEnded = false }
                "-endability" -> current?.let { it.abilityEnded = true }
                "-item" -> current?.let { it.item = id(p[3]) }
                "-enditem" -> current?.let { it.item = null }
                "-boost", "-unboost", "-setboost" -> current?.let {
                    val stat = statName(p[3]); val amount = p[4].toInt()
                    it.stages[stat] = (if (kind == "-setboost") amount else (it.stages[stat] ?: 0) +
                        if (kind == "-unboost") -amount else amount).coerceIn(-6, 6)
                }
                "-clearboost" -> current?.stages?.clear()
                "-clearallboost" -> seen.values.forEach { it.stages.clear() }
                "-start" -> current?.let {
                    if (id(p[3]) == "gastroacid") it.gastroAcid = true
                    if (id(p[3]) == "substitute") it.volatileEffects += "substitute"
                    when (p[3]) {
                        "typechange" -> { it.types = p.getOrNull(4)?.takeUnless { value -> value.startsWith('[') || value == "???" }
                            ?.lowercase()?.split('/')?.toSet().orEmpty(); it.added = null }
                        "typeadd" -> it.added = p.getOrNull(4)?.lowercase()
                    }
                }
                "-end" -> {
                    if (id(p[3]) == "gastroacid") current?.gastroAcid = false
                    if (id(p[3]) == "neutralizinggas") current?.abilityEnded = true
                    if (p[3] == "typeadd") current?.added = null
                    if (id(p[3]) == "substitute") current?.volatileEffects?.remove("substitute")
                }
                "detailschange", "-formechange" -> current?.let {
                    it.species = id(p[3].substringBefore(',')); it.types = baseTypes(it.species); it.added = null
                }
                "-transform" -> current?.let {
                    if (it.originalMoves == null) it.originalMoves = it.moves.toSet()
                    val copied = p.getOrNull(3)?.let(::identity)?.let(seen::get)?.moves.orEmpty().toSet()
                    it.moves.clear(); it.moves.addAll(copied)
                    it.types = emptySet(); it.added = null
                }
                "-terastallize" -> current?.let { it.types = emptySet(); it.added = null }
                "-weather" -> weather = id(actor).takeUnless { it == "none" }
                "-fieldstart" -> fields += id(actor)
                "-fieldend" -> fields -= id(actor)
                "-sidestart" -> { val effects = sideEffects.getValue(side(actor)); val name = id(p[3])
                    effects[name] = ((effects[name] ?: 0) + 1).coerceAtMost(if (name == "spikes") 3 else if (name == "toxicspikes") 2 else 1) }
                "-sideend" -> sideEffects.getValue(side(actor)).remove(id(p[3]))
            }
            EmbeddedPublicMoveOutcomes.read(element.asString, eventTurn, index.toLong() * 2 + 1) { ident ->
                seen[identity(ident)]?.let { uuid(it.ident) }
            }?.let { outcome ->
                if (!EmbeddedPublicMoveOutcomes.duplicatesMiss(events.lastOrNull(), outcome)) events += outcome
            }
        }
        val request = input.getAsJsonObject("request")
        val own = request.getAsJsonObject("side").getAsJsonArray("pokemon").map { it.asJsonObject }
        val ownActiveSlots = input.getAsJsonObject("ownActiveSlots") ?: JsonObject()
        fun privateMap(name: String, ident: String) = input.getAsJsonObject(name).entrySet()
            .firstOrNull { identity(it.key) == identity(ident) }?.value
        val allies = own.map { pokemon ->
            val ident = pokemon["ident"].asString
            val details = pokemon["details"].asString.split(',').map(String::trim)
            val hp = condition(pokemon["condition"].asString)
            val stats = pokemon.getAsJsonObject("stats")
            val maxHp = pokemon["condition"].asString.substringBefore(' ').substringAfter('/', "0").toInt()
            BattlePokemonStateView(uuid(ident), BattleSide.ALLY,
                if (pokemon["active"].asBoolean) ownActiveSlots[identity(ident)]?.asInt else null,
                id(details[0]), null, details.firstOrNull { it.matches(Regex("L\\d+")) }?.drop(1)?.toInt() ?: 100,
                hp.first, hp.second, seen[identity(ident)]?.stages.orEmpty(),
                pokemon.getAsJsonArray("moves").map { id(it.asString) }.toSet(),
                privateMap("ownAbilities", ident)?.asString?.takeIf { it.isNotEmpty() },
                privateMap("ownItems", ident)?.asString?.takeIf { it.isNotEmpty() }, hp.first == 0.0,
                privateMap("ownTypes", ident)?.asJsonArray?.map { it.asString }?.toSet().orEmpty(),
                combatStats = if (maxHp > 0) BattleCombatStatRangesView.exact(maxHp, stats["atk"].asInt,
                    stats["def"].asInt, stats["spa"].asInt, stats["spd"].asInt, stats["spe"].asInt) else null,
                actionConstraints = constraints.forPokemon(ident),
                knownVolatileEffectIds = if (pokemon["active"].asBoolean && hp.first > 0.0)
                    seen[identity(ident)]?.volatileEffects.orEmpty() else emptySet())
        }
        val opponents = seen.values.filter { side(it.ident) == BattleSide.OPPONENT }.map { pokemon ->
            val stats = speciesData.getAsJsonObject(pokemon.species)?.getAsJsonObject("baseStats")
            BattlePokemonStateView(uuid(pokemon.ident), BattleSide.OPPONENT,
                if (pokemon.active) activeSlot(pokemon.ident) else null,
                pokemon.species, null, pokemon.level, pokemon.hp, pokemon.status, pokemon.stages, pokemon.moves,
                pokemon.ability, pokemon.item, pokemon.hp == 0.0,
                if (pokemon.types.isEmpty()) emptySet() else pokemon.types + listOfNotNull(pokemon.added),
                combatStats = stats?.let { BattlePublicStatRanges.fromBaseStats(pokemon.level, it["hp"].asInt,
                    it["atk"].asInt, it["def"].asInt, it["spa"].asInt, it["spd"].asInt, it["spe"].asInt) },
                actionConstraints = constraints.forPokemon(pokemon.ident),
                knownVolatileEffectIds = if (pokemon.active && pokemon.hp > 0.0) pokemon.volatileEffects else emptySet())
        }
        fun moveDetails(moveId: String, pp: Int): BattleMoveCandidateView {
            val move = requireNotNull(ruleMoves.getAsJsonObject(moveId)) { "Missing public move metadata $moveId" }
            val target = when (move["target"].asString) {
                "self" -> BattleMoveTargetPattern.SELF
                "all" -> BattleMoveTargetPattern.ALL_ACTIVE
                "allAdjacent" -> BattleMoveTargetPattern.ALL_ADJACENT
                "allAdjacentFoes" -> BattleMoveTargetPattern.ALL_OPPONENTS
                "allySide", "foeSide" -> BattleMoveTargetPattern.SIDE
                "allyTeam" -> BattleMoveTargetPattern.ALL_ALLIES
                "randomNormal" -> BattleMoveTargetPattern.RANDOM_OPPONENT
                "adjacentAlly" -> BattleMoveTargetPattern.SELECTED_ALLY
                "adjacentAllyOrSelf" -> BattleMoveTargetPattern.SELECTED_ALLY_OR_SELF
                else -> BattleMoveTargetPattern.SELECTED_OPPONENT
            }
            return BattleMoveCandidateView(move["type"].asString, BattleMoveDamageCategory.valueOf(move["category"].asString.uppercase()),
                move["power"].asDouble, move["accuracy"].asDouble, move["priority"].asInt, pp, target,
                effects = declarativeEffects[moveId])
        }
        fun targets(
            details: BattleMoveCandidateView,
            actorSlot: Int,
            targetLoc: Int?,
        ): List<BattleTargetSlot> = when {
            targetLoc != null && targetLoc > 0 -> listOf(BattleTargetSlot(BattleSide.OPPONENT, targetLoc - 1))
            targetLoc != null && targetLoc < 0 -> listOf(BattleTargetSlot(BattleSide.ALLY, -targetLoc - 1))
            details.targetPattern == BattleMoveTargetPattern.SELF ->
                listOf(BattleTargetSlot(BattleSide.ALLY, actorSlot))
            details.targetPattern == BattleMoveTargetPattern.ALL_ACTIVE ->
                (allies.filter { it.activeSlot != null && !it.fainted }.map {
                    BattleTargetSlot(BattleSide.ALLY, requireNotNull(it.activeSlot))
                } + opponents.filter { it.activeSlot != null && !it.fainted }.map {
                    BattleTargetSlot(BattleSide.OPPONENT, requireNotNull(it.activeSlot))
                })
            details.targetPattern == BattleMoveTargetPattern.ALL_ADJACENT ->
                (allies.filter { it.activeSlot != null && it.activeSlot != actorSlot && !it.fainted }.map {
                    BattleTargetSlot(BattleSide.ALLY, requireNotNull(it.activeSlot))
                } + opponents.filter { it.activeSlot != null && !it.fainted }.map {
                    BattleTargetSlot(BattleSide.OPPONENT, requireNotNull(it.activeSlot))
                })
            details.targetPattern == BattleMoveTargetPattern.ALL_OPPONENTS ->
                opponents.filter { it.activeSlot != null && !it.fainted }.map {
                    BattleTargetSlot(BattleSide.OPPONENT, requireNotNull(it.activeSlot))
                }
            details.targetPattern == BattleMoveTargetPattern.ALL_ALLIES ->
                allies.filter { it.activeSlot != null && !it.fainted }.map {
                    BattleTargetSlot(BattleSide.ALLY, requireNotNull(it.activeSlot))
                }
            details.targetPattern == BattleMoveTargetPattern.SELECTED_ALLY_OR_SELF ->
                listOf(BattleTargetSlot(BattleSide.ALLY, actorSlot))
            details.targetPattern == BattleMoveTargetPattern.SIDE ||
                details.targetPattern == BattleMoveTargetPattern.SELECTED_ALLY -> emptyList()
            else -> opponents.filter { it.activeSlot != null && !it.fainted }.take(1).map {
                BattleTargetSlot(BattleSide.OPPONENT, requireNotNull(it.activeSlot))
            }
        }
        fun atomic(action: JsonObject): BattleActionCandidate? {
            val actorSlot = action["actorSlot"]?.asInt ?: 0
            val actionId = action["id"]?.asString ?: "slot$actorSlot:${action["command"].asString}"
            return if (action["kind"].asString == "pass") {
                null
            } else if (action["kind"].asString == "switch") {
                val slot = action["slot"].asInt
                BattleActionCandidate(actionId, BattleActionKind.SWITCH,
                    actorSlot = actorSlot, switchPokemonId = allies[slot].battlePokemonId)
            } else {
                val slot = action["slot"].asInt
                val moveId = id(action["moveId"].asString)
                val moveRequest = request.getAsJsonArray("active")[actorSlot].asJsonObject
                    .getAsJsonArray("moves")[slot].asJsonObject
                val details = moveDetails(moveId, moveRequest["pp"]?.asInt ?: 1)
                BattleActionCandidate(actionId, BattleActionKind.USE_MOVE, actorSlot = actorSlot, moveSlot = slot,
                    moveId = moveId, targets = targets(details, actorSlot, action["targetLoc"]?.takeUnless { it.isJsonNull }?.asInt),
                    moveDetails = details)
            }
        }
        val candidates = input.getAsJsonArray("actions").map { it.asJsonObject }.map { action ->
            if (action["kind"].asString != "composite") {
                requireNotNull(atomic(action))
            } else {
                val components = action.getAsJsonArray("components").mapNotNull { component -> atomic(component.asJsonObject) }
                BattleActionCandidate(
                    action["id"].asString,
                    BattleActionKind.COMPOSITE,
                    componentActionIds = components.map { it.actionId },
                    componentActions = components,
                )
            }
        }
        val pokemon = allies + opponents
        val identById = (own.map { it["ident"].asString } + seen.values.map { it.ident }).associateBy(::uuid)
        fun remaining(creature: BattlePokemonStateView, move: String): Int {
            val ident = identById.getValue(creature.battlePokemonId)
            val actual = if (creature.side == BattleSide.ALLY)
                input.getAsJsonObject("ownCurrentPp")?.entrySet()
                    ?.firstOrNull { identity(it.key) == identity(ident) }?.value?.asJsonObject?.get(move)?.asInt else null
            return actual ?: pp.remaining(ident, move, maximumPp(move) ?: 0)
        }
        val catalog = BattlePublicActionCatalogView(pokemon.map { creature ->
            BattlePokemonActionCatalogView(creature.battlePokemonId, creature.knownMoveIds.filter { ruleMoves.has(it) }.map {
                BattlePublicMoveOptionView(it, moveDetails(it, remaining(creature, it)),
                    if (creature.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
            }, moveSetComplete = false) // PP/Transform/temporary move availability is not a complete future catalog.
        }, candidatePools = opponents.filterNot { it.fainted || pp.isTransformed(identById.getValue(it.battlePokemonId)) }
            .mapNotNull { creature -> publicLearnsets.getAsJsonObject(creature.speciesId)?.let { pool ->
                require(pool["coverage"].asString == "PARTIAL")
                val ids = pool.getAsJsonObject("moves").keySet()
                BattlePublicMoveCandidatePoolView(creature.battlePokemonId, creature.speciesId, creature.formId,
                    ids, pool["sourceId"].asString, ids.associateWith { moveDetails(it, remaining(creature, it)) })
            } })
        fun effect(name: String) = BattleTimedEffectView(name, null)
        val field = BattleFieldStateView(weather?.let(::effect), fields.firstOrNull { it.endsWith("terrain") }?.let(::effect),
            fields.filter { it.endsWith("room") }.map(::effect), fields.filterNot { it.endsWith("terrain") || it.endsWith("room") }.map(::effect),
            sideEffects.mapValues { (_, values) -> values.map { (name, stacks) -> BattleTimedEffectView(name, null, stacks) } })
        val state = BattleStateView(battleId, format, turn, pokemon, field,
            mapOf(BattleSide.ALLY to allies.count { !it.fainted }, BattleSide.OPPONENT to
                ((sizes[BattleSide.OPPONENT] ?: error("Missing public team size")) - opponents.count { it.fainted })),
            observedEvents = events.takeLast(64), inferences = emptyList())
        return BattleDecisionContext(UUID.nameUUIDFromBytes("$battleId:$ownSide:$requestNumber".toByteArray()), state,
            candidates, System.currentTimeMillis() + 20000, memory = memory(state), publicActionCatalog = catalog)
    }

    internal fun battleFormat(input: JsonObject): BattleFormat {
        val declared = input["format"]?.asString ?: "SINGLE"
        require(declared in setOf("SINGLE", "DOUBLE")) { "Native team input supports SINGLE and DOUBLE only" }
        val expectedGameType = if (declared == "DOUBLE") "doubles" else "singles"
        input.getAsJsonArray("publicLog")?.forEach { line ->
            val parts = line.asString.split('|')
            if (parts.getOrNull(1) == "gametype") {
                require(parts.getOrNull(2) == expectedGameType) { "Declared format disagrees with public game type" }
            }
        }
        val result = if (declared == "DOUBLE") BattleFormat.DOUBLE else BattleFormat.SINGLE
        val request = input.getAsJsonObject("request") ?: return result
        val activeLimit = if (declared == "DOUBLE") 2 else 1
        require((request.getAsJsonArray("active")?.size() ?: 0) <= activeLimit) { "Too many active requests for $declared" }
        require((request.getAsJsonArray("forceSwitch")?.size() ?: 0) <= activeLimit) { "Too many forced slots for $declared" }
        val own = request.getAsJsonObject("side")?.getAsJsonArray("pokemon")
        require((own?.count { it.asJsonObject["active"]?.asBoolean == true } ?: 0) <= activeLimit) {
            "Too many active Pokemon for $declared"
        }
        return result
    }

    private fun statName(value: String) = when (value) {
        "atk" -> "attack"; "def" -> "defense"; "spa" -> "special_attack"; "spd" -> "special_defense"; "spe" -> "speed"
        else -> value
    }
}
