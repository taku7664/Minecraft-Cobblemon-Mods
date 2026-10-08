package jbro.cobblemon.mcc.betterai

import com.google.gson.JsonObject
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.internal.compat.cobblemon173.Cobblemon173PublicEffectDurationKnowledge
import jbro.cobblemon.mcc.internal.compat.cobblemon173.FieldEffectScope
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
        var gastroAcid: Boolean = false, var abilityEnded: Boolean = false,
        /** The Tera type once it terastallized; it lasts through switches. */
        var tera: String? = null)

    fun identity(ident: String): String = ident.take(2) + ":" + ident.substringAfter(':').trim()
    private fun uuid(ident: String) = UUID.nameUUIDFromBytes(identity(ident).toByteArray(Charsets.UTF_8))
    private fun activeSlot(ident: String): Int? = ident.getOrNull(2)?.takeIf { it in 'a'..'b' }?.minus('a')
    internal fun ownActiveSlot(
        format: BattleFormat,
        ownActiveSlots: JsonObject,
        ident: String,
        active: Boolean,
    ): Int? {
        if (!active) return null
        val mapped = ownActiveSlots[identity(ident)]?.takeUnless { it.isJsonNull }?.asInt
        if (mapped != null) {
            val maximum = if (format == BattleFormat.SINGLE) 0 else 1
            require(mapped in 0..maximum) { "Own active slot $mapped is invalid for $format" }
            return mapped
        }
        require(format == BattleFormat.SINGLE) { "Missing own active slot mapping for $ident in $format" }
        return 0
    }
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
        val nativeInputs = input.has("ownBuilds")
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
        // The turn each timed effect started, as the Cobblemon observer counts it, so the state carries the
        // same remaining-turn ranges production does.
        val started = mutableMapOf<String, Int>()
        var eventTurn = 0
        // The move whose direct damage the game's observer links to the next HP loss: open from the move line until
        // the turn, its upkeep or a switch closes it.
        class ActionWindow(val turn: Int, val sequence: Long, val actor: UUID, val moveId: String, val targets: Set<UUID>)
        var actionWindow: ActionWindow? = null
        fun baseTypes(species: String): Set<String> = if (species.startsWith("arceus")) emptySet() else
            speciesData.getAsJsonObject(species)?.getAsJsonArray("types")?.map { it.asString }?.toSet().orEmpty()
        fun types(pokemon: Seen): Set<String> = pokemon.tera?.takeUnless { it == "stellar" }?.let { setOf(it) } ?: baseTypes(pokemon.species)
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
                    actorPokemonId = current?.let { uuid(it.ident) }, publicValueId = value,
                    // The game's observer records the actor's active slot; the native session matches by it.
                    actorSlot = if (nativeInputs && current != null) activeSlot(actor) else null)
            }
            if (kind in setOf("turn", "upkeep", "switch", "drag")) actionWindow = null
            when (kind) {
                "turn" -> eventTurn = actor.toInt()
                "teamsize" -> sizes[side(actor)] = p[3].toInt()
                "switch", "drag", "replace" -> {
                    // A broken Illusion is announced by "replace" without a condition: the HP is the disguise's.
                    val disguise = seen.values.firstOrNull {
                        it.active && it.ident.take(2) == actor.take(2) && activeSlot(it.ident) == activeSlot(actor)
                    }
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
                        it.active = false; it.stages.clear(); it.added = null; it.types = types(it); it.volatileEffects.clear()
                    }
                    val details = p[3].split(',').map(String::trim)
                    val species = id(details[0])
                    val hp = p.getOrNull(4)?.let(::condition) ?: disguise?.let { it.hp to it.status } ?: (1.0 to null)
                    val entry = Seen(actor, species, details.firstOrNull { it.matches(Regex("L\\d+")) }?.drop(1)?.toInt() ?: 100,
                        hp.first, hp.second, types = baseTypes(species))
                    current?.let { entry.moves += it.moves; entry.ability = it.ability; entry.item = it.item
                        entry.originalMoves = it.originalMoves; entry.tera = it.tera; entry.types = types(entry) }
                    entry.volatileEffects += inherited
                    seen[key] = entry
                    events += BattleObservedEventView(index.toLong() * 2, eventTurn, BattleObservedEventKind.SWITCHED, uuid(actor),
                        actorSlot = if (nativeInputs) activeSlot(actor) else null)
                }
                "-damage", "-heal" -> {
                    val previousHp = current?.hp
                    current?.let { val hp = condition(p[3]); it.hp = hp.first; it.status = hp.second }
                    val source = p.drop(4).singleOrNull { it.startsWith("[from] ") }?.removePrefix("[from] ")
                    // As the game's observer records it: every HP change, with direct move damage linked to its move.
                    if (current != null) {
                        val target = uuid(current.ident)
                        val delta = previousHp?.let { current.hp - it }
                        val sourceId = source?.let(::id)?.takeIf(String::isNotEmpty)
                        val link = actionWindow?.takeIf {
                            kind == "-damage" && sourceId == null && delta != null && delta < 0.0 &&
                                it.turn == eventTurn && target in it.targets
                        }
                        events += BattleObservedEventView(index.toLong() * 2, eventTurn, BattleObservedEventKind.HP_CHANGED,
                            target, hpFractionDelta = delta, precedingActionSequence = link?.sequence,
                            precedingActionActorPokemonId = link?.actor, precedingActionMoveId = link?.moveId,
                            publicSourceEffectId = sourceId)
                    }
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
                            // Like the game's observer: an effect source never brings back an item already gone.
                            source.startsWith("item: ") -> {
                                if (owner.item != "") owner.item = resource
                                events += BattleObservedEventView(index.toLong() * 2 + 1, eventTurn,
                                    BattleObservedEventKind.HELD_ITEM_REVEALED, uuid(owner.ident), publicValueId = resource,
                                    actorSlot = if (nativeInputs && owner.active) activeSlot(owner.ident) else null)
                            }
                            source.startsWith("ability: ") -> {
                                owner.ability = resource
                                // After the line's HP change, as the game's observer orders them.
                                events += BattleObservedEventView(index.toLong() * 2 + 1, eventTurn,
                                    BattleObservedEventKind.ABILITY_REVEALED, uuid(owner.ident), publicValueId = resource,
                                    actorSlot = if (nativeInputs && owner.active) activeSlot(owner.ident) else null)
                            }
                        }
                    }
                }
                "faint" -> current?.let { it.hp = 0.0; it.volatileEffects.clear(); event(BattleObservedEventKind.FAINTED) }
                "move" -> current?.let {
                    it.moves += id(p[3]); event(BattleObservedEventKind.MOVE_USED, id(p[3]))
                    val targets = p.getOrNull(4)?.takeIf { target -> target.startsWith("p") }
                        ?.let { target -> seen[identity(target)] }?.let { target -> setOf(uuid(target.ident)) }.orEmpty()
                    actionWindow = ActionWindow(eventTurn, index.toLong() * 2, uuid(it.ident), id(p[3]), targets)
                }
                "-status" -> current?.let { it.status = id(p[3]) }
                "-curestatus" -> current?.let { it.status = null }
                // The game's observer records every ability reveal as an event; the native opening pins it to the set.
                "-ability" -> current?.let {
                    it.ability = id(p[3]); it.abilityEnded = false
                    event(BattleObservedEventKind.ABILITY_REVEALED, id(p[3]))
                }
                "-endability" -> current?.let { it.abilityEnded = true }
                // Both reveal the item as the game's observer records it.
                "-item" -> current?.let { it.item = id(p[3]); event(BattleObservedEventKind.HELD_ITEM_REVEALED, id(p[3])) }
                // "" is a confirmed absence, as the game's observer records it; null is not yet seen.
                "-enditem" -> current?.let { it.item = ""; event(BattleObservedEventKind.HELD_ITEM_REVEALED, id(p[3])) }
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
                "-terastallize" -> current?.let { it.tera = id(p[3]); it.types = types(it); it.added = null }
                "-weather" -> {
                    val next = id(actor).takeUnless { it == "none" }
                    if ("[upkeep]" !in p) started["weather"] = maxOf(eventTurn, 1)
                    weather = next
                }
                "-fieldstart" -> { fields += id(actor); started["field:${id(actor)}"] = maxOf(eventTurn, 1) }
                "-fieldend" -> fields -= id(actor)
                "-sidestart" -> { val effects = sideEffects.getValue(side(actor)); val name = id(p[3])
                    effects[name] = ((effects[name] ?: 0) + 1).coerceAtMost(if (name == "spikes") 3 else if (name == "toxicspikes") 2 else 1)
                    started["side:${side(actor)}:$name"] = maxOf(eventTurn, 1) }
                "-sideend" -> sideEffects.getValue(side(actor)).remove(id(p[3]))
            }
            EmbeddedPublicMoveOutcomes.read(element.asString, eventTurn, index.toLong() * 2 + 1) { ident ->
                seen[identity(ident)]?.let { uuid(it.ident) }
            }?.let { outcome ->
                if (!EmbeddedPublicMoveOutcomes.duplicatesMiss(events.lastOrNull(), outcome)) events += outcome
            }
            // The game's observer reveals an item named as any line's source, not only an HP change's (handled above).
            val itemSource = p.drop(3).singleOrNull { it.startsWith("[from] item: ") }?.removePrefix("[from] item: ")
                ?.let(::id)?.takeIf(String::isNotEmpty)
            if (itemSource != null && kind !in setOf("-damage", "-heal") && events.lastOrNull()?.sequence != index.toLong() * 2 + 1) {
                val ownerTag = p.drop(3).singleOrNull { it.startsWith("[of] ") }?.removePrefix("[of] ")
                seen[identity(ownerTag ?: actor)]?.let { owner ->
                    if (owner.item != "") owner.item = itemSource
                    events += BattleObservedEventView(index.toLong() * 2 + 1, eventTurn,
                        BattleObservedEventKind.HELD_ITEM_REVEALED, uuid(owner.ident), publicValueId = itemSource,
                        actorSlot = if (nativeInputs && owner.active) activeSlot(owner.ident) else null)
                }
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
                ownActiveSlot(format, ownActiveSlots, ident, pokemon["active"].asBoolean),
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
                knownTeraTypeId = pokemon.tera,
                knownBaseStabTypeIds = baseTypes(pokemon.species),
                combatStats = stats?.let { BattlePublicStatRanges.fromBaseStats(pokemon.level, it["hp"].asInt,
                    it["atk"].asInt, it["def"].asInt, it["spa"].asInt, it["spd"].asInt, it["spe"].asInt) },
                actionConstraints = constraints.forPokemon(pokemon.ident),
                knownVolatileEffectIds = if (pokemon.active && pokemon.hp > 0.0) pokemon.volatileEffects else emptySet())
        }
        fun moveDetails(moveId: String, pp: Int): BattleMoveCandidateView =
            publicMoveDetails(requireNotNull(ruleMoves.getAsJsonObject(moveId)) { "Missing public move metadata $moveId" }, moveId, pp)
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
            }, // PP/Transform/temporary move availability is not a complete future catalog. With the game's native
                // inputs the adapter marks the trainer's own sets complete, as the game's public catalog does.
                moveSetComplete = nativeInputs && creature.side == BattleSide.ALLY)
        }, candidatePools = opponents.filterNot { it.fainted || pp.isTransformed(identById.getValue(it.battlePokemonId)) }
            .mapNotNull { creature -> publicLearnsets.getAsJsonObject(creature.speciesId)?.let { pool ->
                require(pool["coverage"].asString == "PARTIAL")
                val ids = pool.getAsJsonObject("moves").keySet()
                BattlePublicMoveCandidatePoolView(creature.battlePokemonId, creature.speciesId, creature.formId,
                    ids, pool["sourceId"].asString, ids.associateWith { moveDetails(it, remaining(creature, it)) })
            } })
        // The Cobblemon observer's reading: the public duration range less the turns since the effect started.
        fun timed(name: String, key: String, duration: BattleIntegerRange?, stacks: Int? = null): BattleTimedEffectView {
            val elapsed = (turn - (started[key] ?: turn)).coerceAtLeast(0)
            val maximum = duration?.let { it.maximum - elapsed } ?: return BattleTimedEffectView(name, null, stacks)
            if (maximum <= 0) return BattleTimedEffectView(name, null, stacks)
            val minimum = (duration.minimum - elapsed).coerceAtLeast(1)
            return if (minimum == maximum) BattleTimedEffectView(name, minimum, stacks)
            else BattleTimedEffectView(name, null, stacks, remainingTurnsRange = BattleIntegerRange(minimum, maximum))
        }
        fun field(name: String, scope: FieldEffectScope) =
            timed(name, "field:$name", Cobblemon173PublicEffectDurationKnowledge.field(name, scope))
        val field = BattleFieldStateView(weather?.let { timed(it, "weather", Cobblemon173PublicEffectDurationKnowledge.weather(it)) },
            fields.firstOrNull { it.endsWith("terrain") }?.let { field(it, FieldEffectScope.TERRAIN) },
            fields.filter { it.endsWith("room") }.map { field(it, FieldEffectScope.ROOM) },
            fields.filterNot { it.endsWith("terrain") || it.endsWith("room") }.map { field(it, FieldEffectScope.GLOBAL) },
            sideEffects.mapValues { (side, values) -> values.map { (name, stacks) ->
                timed(name, "side:$side:$name", Cobblemon173PublicEffectDurationKnowledge.side(name), stacks) } })
        val state = BattleStateView(battleId, format, turn, pokemon, field,
            mapOf(BattleSide.ALLY to allies.count { !it.fainted }, BattleSide.OPPONENT to
                ((sizes[BattleSide.OPPONENT] ?: error("Missing public team size")) - opponents.count { it.fainted })),
            observedEvents = if (nativeInputs) gameNumbered(events).takeLast(128) else events.takeLast(64),
            inferences = emptyList())
        return BattleDecisionContext(UUID.nameUUIDFromBytes("$battleId:$ownSide:$requestNumber".toByteArray()), state,
            candidates, System.currentTimeMillis() + 20000, memory = memory(state), publicActionCatalog = catalog)
    }

    /** The game's observer numbers public events 1, 2, 3 and keeps the last 128; the native session needs that. */
    private fun gameNumbered(events: List<BattleObservedEventView>): List<BattleObservedEventView> {
        val renumbered = events.withIndex().associate { (index, event) -> event.sequence to index + 1L }
        return events.mapIndexed { index, event -> gameNumbered(event, index + 1L, renumbered) }
    }

    private fun gameNumbered(event: BattleObservedEventView, sequence: Long, renumbered: Map<Long, Long>) =
        BattleObservedEventView(
            sequence = sequence,
            turn = event.turn,
            kind = event.kind,
            actorPokemonId = event.actorPokemonId,
            targetPokemonIds = event.targetPokemonIds,
            publicValueId = event.publicValueId,
            hpFractionDelta = event.hpFractionDelta,
            baseMovePriority = event.baseMovePriority,
            precedingActionSequence = event.precedingActionSequence?.let(renumbered::getValue),
            precedingActionActorPokemonId = event.precedingActionActorPokemonId,
            precedingActionMoveId = event.precedingActionMoveId,
            publicSourceEffectId = event.publicSourceEffectId,
            moveOutcome = event.moveOutcome,
            actorSlot = event.actorSlot,
        )

    internal fun publicMoveDetails(move: JsonObject, moveId: String, pp: Int): BattleMoveCandidateView {
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

    /**
     * The game's native search inputs: the opposing team preview as Cobblemon's FormData enriches it
     * (types, public stat ranges, abilities, gender ratio, learnset) and the trainer's own exact sets.
     * Present only when the bridge ran with `teamPreview`.
     */
    internal fun nativeInputs(input: JsonObject): Pair<BattleOpponentTeamPreviewView, BattleExactOwnTeamView>? {
        val foe = input.getAsJsonArray("foePreview") ?: return null
        val own = input.getAsJsonArray("ownBuilds") ?: return null
        val stats = listOf("hp", "atk", "def", "spa", "spd", "spe")
        val preview = BattleOpponentTeamPreviewView(foe.size(), foe.map { value ->
            val entry = value.asJsonObject
            val species = entry["species"].asString
            val level = entry["level"].asInt
            val base = entry.getAsJsonObject("baseStats")
            fun stat(name: String) = base[name].asInt
            val learnset = entry.getAsJsonObject("learnset")
            val gender = entry["gender"]?.takeUnless { it.isJsonNull }?.asString
            val ratio = entry["genderRatio"]?.takeUnless { it.isJsonNull }?.asJsonObject
            val genderRates = when {
                gender != null -> mapOf(gender to 1.0)
                ratio != null -> linkedMapOf("M" to ratio["M"].asDouble, "F" to ratio["F"].asDouble)
                else -> linkedMapOf("M" to 0.5, "F" to 0.5)
            }.filterValues { it > 0.0 }
            BattleOpponentTeamPreviewPokemonView(
                previewSlotId = entry["slot"].asInt,
                speciesId = species,
                formId = null,
                level = level,
                knownTypeIds = entry.getAsJsonArray("types").map { it.asString }.toSet(),
                combatStats = BattlePublicStatRanges.fromBaseStats(level, stat("hp"), stat("atk"), stat("def"),
                    stat("spa"), stat("spd"), stat("spe")),
                moveCandidatePool = BattleOpponentPreviewMovePoolView(species, null, learnset.keySet(),
                    "embedded:cobblemon/gen9_move_pool",
                    learnset.keySet().associateWith { id ->
                        val move = learnset.getAsJsonObject(id)
                        publicMoveDetails(move, id, move["maxPp"].asInt)
                    }),
                buildCandidatePool = BattleOpponentPreviewBuildPoolView(species, null,
                    entry.getAsJsonArray("abilities").map { it.asJsonObject }.distinctBy { it["id"].asString }.map {
                        BattleOpponentPreviewAbilityView(it["id"].asString,
                            if (it["hidden"].asBoolean) BattleAbilityAvailability.HIDDEN else BattleAbilityAvailability.REGULAR)
                    }, genderRates, "embedded:showdown/species_abilities_and_gender", stats.associateWith(::stat)),
                showdownSpeciesId = species,
            )
        })
        fun spread(value: JsonObject) = stats.associateWith { value[it].asInt }
        val team = BattleExactOwnTeamView(own.map { value ->
            val build = value.asJsonObject
            // The same Cobblemon-shaped IDs the game hands over (namespaced item and nature).
            BattleExactPokemonBuildView(
                battlePokemonId = uuid(build["ident"].asString),
                abilityId = build["ability"].asString,
                heldItemId = build["item"].asString.takeIf(String::isNotEmpty)?.let { "cobblemon:$it" },
                natureId = "cobblemon:" + build["nature"].asString,
                gender = build["gender"].asString,
                evs = spread(build.getAsJsonObject("evs")),
                ivs = spread(build.getAsJsonObject("ivs")),
                teraTypeId = build["teraType"]?.takeUnless { it.isJsonNull }?.asString,
                showdownSpeciesId = build["species"].asString,
            )
        })
        return preview to team
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
