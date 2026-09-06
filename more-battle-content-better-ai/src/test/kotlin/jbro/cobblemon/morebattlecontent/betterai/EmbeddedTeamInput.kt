package jbro.cobblemon.morebattlecontent.betterai

import com.google.gson.JsonObject
import jbro.cobblemon.morebattlecontent.api.ai.*
import java.util.UUID

/** Test adapter, deliberately separate from privileged team definitions and referee state. */
internal object EmbeddedTeamInput {
    private data class Seen(val ident: String, var species: String, var level: Int, var hp: Double,
        var status: String?, var active: Boolean = true, var types: Set<String> = emptySet(),
        var added: String? = null, val stages: MutableMap<String, Int> = mutableMapOf(),
        val moves: MutableSet<String> = linkedSetOf(), var ability: String? = null, var item: String? = null)

    fun identity(ident: String): String = ident.take(2) + ":" + ident.substringAfter(':').trim()
    private fun uuid(ident: String) = UUID.nameUUIDFromBytes(identity(ident).toByteArray(Charsets.UTF_8))
    private fun id(text: String) = text.substringAfter(": ").lowercase().filter(Char::isLetterOrDigit)
    private fun condition(value: String): Pair<Double, String?> {
        val parts = value.split(' ')
        val hp = parts[0].split('/')
        return (if (hp[0] == "0") 0.0 else hp[0].toDouble() / hp[1].toDouble()) to
            parts.getOrNull(1)?.takeUnless { it == "fnt" }
    }

    fun context(input: JsonObject, battleId: UUID, turn: Int, requestNumber: Int): BattleDecisionContext {
        val ownSide = input["side"].asString
        fun side(ident: String) = if (ident.startsWith(ownSide)) BattleSide.ALLY else BattleSide.OPPONENT
        val speciesData = input.getAsJsonObject("species")
        val moveData = input.getAsJsonObject("moves")
        val seen = linkedMapOf<String, Seen>()
        val sizes = mutableMapOf<BattleSide, Int>()
        val events = mutableListOf<BattleObservedEventView>()
        val sideEffects = BattleSide.entries.associateWith { linkedMapOf<String, Int>() }
        val fields = linkedSetOf<String>()
        var weather: String? = null
        var eventTurn = 0
        fun baseTypes(species: String): Set<String> = if (species.startsWith("arceus")) emptySet() else
            speciesData.getAsJsonObject(species)?.getAsJsonArray("types")?.map { it.asString }?.toSet().orEmpty()
        input.getAsJsonArray("publicLog").forEachIndexed { index, element ->
            val p = element.asString.split('|')
            val kind = p.getOrNull(1) ?: return@forEachIndexed
            val actor = p.getOrNull(2).orEmpty()
            val key = identity(actor)
            val current = seen[key]
            fun event(eventKind: BattleObservedEventKind, value: String? = null) {
                events += BattleObservedEventView(index.toLong(), eventTurn, eventKind,
                    actorPokemonId = current?.let { uuid(it.ident) }, publicValueId = value)
            }
            when (kind) {
                "turn" -> eventTurn = actor.toInt()
                "teamsize" -> sizes[side(actor)] = p[3].toInt()
                "switch", "drag", "replace" -> {
                    seen.values.filter { it.ident.take(2) == actor.take(2) }.forEach {
                        it.active = false; it.stages.clear(); it.added = null; it.types = baseTypes(it.species)
                    }
                    val details = p[3].split(',').map(String::trim)
                    val species = id(details[0])
                    val hp = condition(p[4])
                    val entry = Seen(actor, species, details.firstOrNull { it.matches(Regex("L\\d+")) }?.drop(1)?.toInt() ?: 100,
                        hp.first, hp.second, types = baseTypes(species))
                    current?.let { entry.moves += it.moves; entry.ability = it.ability; entry.item = it.item }
                    seen[key] = entry
                    events += BattleObservedEventView(index.toLong(), eventTurn, BattleObservedEventKind.SWITCHED, uuid(actor))
                }
                "-damage", "-heal" -> current?.let { val hp = condition(p[3]); it.hp = hp.first; it.status = hp.second }
                "faint" -> current?.let { it.hp = 0.0; event(BattleObservedEventKind.FAINTED) }
                "move" -> current?.let { it.moves += id(p[3]); event(BattleObservedEventKind.MOVE_USED, id(p[3])) }
                "-status" -> current?.let { it.status = id(p[3]) }
                "-curestatus" -> current?.let { it.status = null }
                "-ability" -> current?.let { it.ability = id(p[3]) }
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
                    when (p[3]) {
                        "typechange" -> { it.types = p.getOrNull(4)?.takeUnless { value -> value.startsWith('[') || value == "???" }
                            ?.lowercase()?.split('/')?.toSet().orEmpty(); it.added = null }
                        "typeadd" -> it.added = p.getOrNull(4)?.lowercase()
                    }
                }
                "-end" -> if (p[3] == "typeadd") current?.added = null
                "detailschange", "-formechange" -> current?.let {
                    it.species = id(p[3].substringBefore(',')); it.types = baseTypes(it.species); it.added = null
                }
                "-transform", "-terastallize" -> current?.let { it.types = emptySet(); it.added = null }
                "-weather" -> weather = id(actor).takeUnless { it == "none" }
                "-fieldstart" -> fields += id(actor)
                "-fieldend" -> fields -= id(actor)
                "-sidestart" -> { val effects = sideEffects.getValue(side(actor)); val name = id(p[3])
                    effects[name] = ((effects[name] ?: 0) + 1).coerceAtMost(if (name == "spikes") 3 else if (name == "toxicspikes") 2 else 1) }
                "-sideend" -> sideEffects.getValue(side(actor)).remove(id(p[3]))
            }
        }
        val request = input.getAsJsonObject("request")
        val own = request.getAsJsonObject("side").getAsJsonArray("pokemon").map { it.asJsonObject }
        fun privateMap(name: String, ident: String) = input.getAsJsonObject(name).entrySet()
            .firstOrNull { identity(it.key) == identity(ident) }?.value
        val allies = own.map { pokemon ->
            val ident = pokemon["ident"].asString
            val details = pokemon["details"].asString.split(',').map(String::trim)
            val hp = condition(pokemon["condition"].asString)
            val stats = pokemon.getAsJsonObject("stats")
            val maxHp = pokemon["condition"].asString.substringBefore(' ').substringAfter('/', "0").toInt()
            BattlePokemonStateView(uuid(ident), BattleSide.ALLY, if (pokemon["active"].asBoolean) 0 else null,
                id(details[0]), null, details.firstOrNull { it.matches(Regex("L\\d+")) }?.drop(1)?.toInt() ?: 100,
                hp.first, hp.second, seen[identity(ident)]?.stages.orEmpty(),
                pokemon.getAsJsonArray("moves").map { id(it.asString) }.toSet(),
                privateMap("ownAbilities", ident)?.asString?.takeIf { it.isNotEmpty() },
                privateMap("ownItems", ident)?.asString?.takeIf { it.isNotEmpty() }, hp.first == 0.0,
                privateMap("ownTypes", ident)?.asJsonArray?.map { it.asString }?.toSet().orEmpty(),
                combatStats = if (maxHp > 0) BattleCombatStatRangesView.exact(maxHp, stats["atk"].asInt,
                    stats["def"].asInt, stats["spa"].asInt, stats["spd"].asInt, stats["spe"].asInt) else null)
        }
        val opponents = seen.values.filter { side(it.ident) == BattleSide.OPPONENT }.map { pokemon ->
            val stats = speciesData.getAsJsonObject(pokemon.species)?.getAsJsonObject("baseStats")
            BattlePokemonStateView(uuid(pokemon.ident), BattleSide.OPPONENT, if (pokemon.active) 0 else null,
                pokemon.species, null, pokemon.level, pokemon.hp, pokemon.status, pokemon.stages, pokemon.moves,
                pokemon.ability, pokemon.item, pokemon.hp == 0.0,
                if (pokemon.types.isEmpty()) emptySet() else pokemon.types + listOfNotNull(pokemon.added),
                combatStats = stats?.let { BattlePublicStatRanges.fromBaseStats(pokemon.level, it["hp"].asInt,
                    it["atk"].asInt, it["def"].asInt, it["spa"].asInt, it["spd"].asInt, it["spe"].asInt) })
        }
        fun moveDetails(moveId: String, pp: Int): BattleMoveCandidateView {
            val move = requireNotNull(moveData.getAsJsonObject(moveId)) { "Missing exposed move metadata $moveId" }
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
                move["power"].asDouble, move["accuracy"].asDouble, move["priority"].asInt, pp, target)
        }
        val candidates = input.getAsJsonArray("actions").map { it.asJsonObject }.map { action ->
            val slot = action["slot"].asInt
            if (action["kind"].asString == "switch") BattleActionCandidate(action["id"].asString, BattleActionKind.SWITCH,
                actorSlot = 0, switchPokemonId = allies[slot].battlePokemonId)
            else {
                val moveId = id(action["moveId"].asString)
                val moveRequest = request.getAsJsonArray("active")[0].asJsonObject.getAsJsonArray("moves")[slot].asJsonObject
                val details = moveDetails(moveId, moveRequest["pp"]?.asInt ?: 1)
                BattleActionCandidate(action["id"].asString, BattleActionKind.USE_MOVE, actorSlot = 0, moveSlot = slot,
                    moveId = moveId, targets = when (details.targetPattern) {
                        BattleMoveTargetPattern.SELF, BattleMoveTargetPattern.SELECTED_ALLY_OR_SELF -> listOf(BattleTargetSlot(BattleSide.ALLY, 0))
                        BattleMoveTargetPattern.ALL_ACTIVE ->
                            listOf(BattleTargetSlot(BattleSide.ALLY, 0), BattleTargetSlot(BattleSide.OPPONENT, 0))
                        BattleMoveTargetPattern.SIDE, BattleMoveTargetPattern.ALL_ALLIES, BattleMoveTargetPattern.SELECTED_ALLY -> emptyList()
                        else -> listOf(BattleTargetSlot(BattleSide.OPPONENT, 0))
                    }, moveDetails = details)
            }
        }
        val pokemon = allies + opponents
        val catalog = BattlePublicActionCatalogView(pokemon.map { creature ->
            BattlePokemonActionCatalogView(creature.battlePokemonId, creature.knownMoveIds.filter { moveData.has(it) }.map {
                BattlePublicMoveOptionView(it, moveDetails(it, moveData.getAsJsonObject(it)["pp"].asInt),
                    if (creature.side == BattleSide.ALLY) BattlePublicMoveKnowledge.EXACT_OWN else BattlePublicMoveKnowledge.PUBLICLY_REVEALED)
            }, moveSetComplete = false) // PP/Transform/temporary move availability is not a complete future catalog.
        })
        fun effect(name: String) = BattleTimedEffectView(name, null)
        val field = BattleFieldStateView(weather?.let(::effect), fields.firstOrNull { it.endsWith("terrain") }?.let(::effect),
            fields.filter { it.endsWith("room") }.map(::effect), fields.filterNot { it.endsWith("terrain") || it.endsWith("room") }.map(::effect),
            sideEffects.mapValues { (_, values) -> values.map { (name, stacks) -> BattleTimedEffectView(name, null, stacks) } })
        val state = BattleStateView(battleId, BattleFormat.SINGLE, turn, pokemon, field,
            mapOf(BattleSide.ALLY to allies.count { !it.fainted }, BattleSide.OPPONENT to
                ((sizes[BattleSide.OPPONENT] ?: error("Missing public team size")) - opponents.count { it.fainted })),
            observedEvents = events.takeLast(64), inferences = emptyList())
        return BattleDecisionContext(UUID.nameUUIDFromBytes("$battleId:$ownSide:$requestNumber".toByteArray()), state,
            candidates, System.currentTimeMillis() + 20000, publicActionCatalog = catalog)
    }

    private fun statName(value: String) = when (value) {
        "atk" -> "attack"; "def" -> "defense"; "spa" -> "special_attack"; "spd" -> "special_defense"; "spe" -> "speed"
        else -> value
    }
}
