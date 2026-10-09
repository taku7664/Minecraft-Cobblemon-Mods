package jbro.cobblemon.mcc.betterai.simulation

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleOptions
import jbro.cobblemon.mcc.betterai.engine.sim.BattleTracer
import jbro.cobblemon.mcc.betterai.engine.sim.EffectState
import jbro.cobblemon.mcc.betterai.engine.sim.MoveSlot
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.PokemonSet
import jbro.cobblemon.mcc.betterai.engine.sim.Prng
import jbro.cobblemon.mcc.betterai.engine.sim.Side
import jbro.cobblemon.mcc.betterai.engine.sim.activeMove
import jbro.cobblemon.mcc.betterai.engine.sim.fork

/**
 * The native search's branch worker, played on the Kotlin AI engine instead of Showdown in GraalJS.
 *
 * Frames carry the same fields the Showdown bridge (`native-showdown/branch-engine.cjs`) produces, so the
 * world planner, search tree and session reconciler run unchanged. A snapshot is a small token: the battle
 * definition plus every step taken since. Battles for recent tokens stay in memory; any other token is
 * rebuilt by replaying its steps, which gives the same battle because the engine is deterministic.
 */
internal class EngineBranchWorker(
    /** The dex and a digest of the Cobblemon species laid over it ("bundled" when there are none). */
    runtime: Pair<String, EngineDex> = EngineRuntimeDex.current(),
    private val cacheLimit: Int = 4096,
    /** The pool's rules fingerprint when this worker serves a native generation. */
    private val fingerprint: String? = null,
) : NativeBranchWorker {
    private val gson = Gson()
    private val cache = object : LinkedHashMap<String, Battle>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Battle>?): Boolean = size > cacheLimit
    }

    private val dex: EngineDex = runtime.second

    // A pool worker carries its generation's fingerprint unchanged: the pool requires them to be equal, and it is
    // rebuilt on reload, which rereads the species. A standalone worker names its species data itself.
    override val rulesFingerprint: String = fingerprint ?: ("ai-engine:" + dexFingerprint() + ":species=" + runtime.first)

    override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame {
        val token = JsonObject().apply {
            addProperty("engine", 1)
            add("definition", gson.toJsonTree(definition))
            add("steps", JsonArray())
        }.toString()
        val battle = build(definition)
        remember(token, battle)
        return frame(token, battle, emptyList(), null)
    }

    override fun rebindMoves(snapshotJson: String, rebindings: List<NativeMoveSetRebinding>): NativeBattleFrame {
        require(rebindings.isNotEmpty()) { "At least one native move-set rebinding is required" }
        val battle = resolve(snapshotJson).fork()
        rebind(battle, rebindings)
        val token = extend(snapshotJson, JsonArray().apply { add("rebind"); add(gson.toJsonTree(rebindings)) })
        remember(token, battle)
        return frame(token, battle, emptyList(), null)
    }

    override fun renamePokemon(snapshotJson: String, renames: Map<String, String>): NativeBattleFrame {
        require(renames.isNotEmpty()) { "At least one native Pokemon rename is required" }
        val battle = resolve(snapshotJson).fork()
        rename(battle, renames)
        val token = extend(snapshotJson, JsonArray().apply { add("rename"); add(gson.toJsonTree(renames)) })
        remember(token, battle)
        return frame(token, battle, emptyList(), null)
    }

    override val canReseed: Boolean get() = true

    override fun reseed(snapshotJson: String, salt: Int): NativeBattleFrame {
        val battle = resolve(snapshotJson).fork()
        reseed(battle, salt)
        val token = extend(snapshotJson, JsonArray().apply { add("reseed"); add(salt) })
        remember(token, battle)
        return frame(token, battle, emptyList(), null)
    }

    private fun reseed(battle: Battle, salt: Int) {
        val mixer = java.util.Random(battle.prng.seed.fold(salt.toLong()) { hash, part -> hash * 31 + part })
        battle.prng = Prng(IntArray(4) { mixer.nextInt(65536) })
    }

    override val canRestat: Boolean get() = true

    override fun restat(snapshotJson: String, changes: List<NativeStatChange>): NativeBattleFrame {
        require(changes.isNotEmpty()) { "At least one native stat change is required" }
        val battle = resolve(snapshotJson).fork()
        restat(battle, changes)
        val token = extend(snapshotJson, JsonArray().apply { add("restat"); add(gson.toJsonTree(changes)) })
        remember(token, battle)
        return frame(token, battle, emptyList(), null)
    }

    private fun restat(battle: Battle, changes: List<NativeStatChange>) {
        for (change in changes) {
            val matches = battle.sides.flatMap { it.pokemon }.filter { it.uuid == change.pokemonUuid }
            require(matches.size == 1) { "Stat change names unknown or duplicate Pokemon ${change.pokemonUuid}" }
            val pokemon = matches[0]
            require(!pokemon.transformed) { "A transformed Pokemon reads another Pokemon's stats" }
            pokemon.storedStats[change.stat] = change.value
            // A later forme change recomputes the stats from the set, as it would for the real spread.
            pokemon.baseStoredStats?.let { base -> pokemon.baseStoredStats = LinkedHashMap(base).also { it[change.stat] = change.value } }
            if (change.stat == "spe") pokemon.updateSpeed()
        }
    }

    override val canRebindItems: Boolean get() = true

    override fun rebindItems(snapshotJson: String, rebindings: List<NativeItemRebinding>): NativeBattleFrame {
        require(rebindings.isNotEmpty()) { "At least one native item rebinding is required" }
        val battle = resolve(snapshotJson).fork()
        reitem(battle, rebindings)
        val token = extend(snapshotJson, JsonArray().apply { add("reitem"); add(gson.toJsonTree(rebindings)) })
        remember(token, battle)
        return frame(token, battle, emptyList(), null)
    }

    private fun reitem(battle: Battle, rebindings: List<NativeItemRebinding>) {
        for (rebinding in rebindings) {
            val matches = battle.sides.flatMap { it.pokemon }.filter { it.uuid == rebinding.pokemonUuid }
            require(matches.size == 1) { "Item rebinding names unknown or duplicate Pokemon ${rebinding.pokemonUuid}" }
            val pokemon = matches[0]
            val expected = Js.toID(rebinding.expectedItemId)
            // Only an item the hypothesis never used: a consumed, knocked-off or swapped one has history to keep.
            require(pokemon.item == expected && Js.toID(pokemon.set.item) == expected && pokemon.lastItem.isEmpty() &&
                !pokemon.usedItemThisTurn) { "Unsafe history-sensitive item rebinding for ${rebinding.pokemonUuid}" }
            val item = battle.dex.item(rebinding.replacementItemId)
            require(item.exists || rebinding.replacementItemId.isBlank()) { "Unknown rebound item ${rebinding.replacementItemId}" }
            pokemon.item = item.id
            pokemon.itemState = EffectState(item.id).also { it.target = pokemon }
            // A Choice lock the hypothesised item set belongs to that item; the revealed one sets its own.
            if (!item.id.startsWith("choice")) pokemon.volatiles.remove("choicelock")
            pokemon.set = pokemon.set.withItem(item.id)
        }
        val requests = battle.getRequests(battle.requestState)
        for (i in battle.sides.indices) battle.sides[i].activeRequest = requests[i]
    }

    override fun statRange(snapshotJson: String, pokemonUuid: String, stat: String): IntRange? {
        val battle = resolve(snapshotJson)
        val pokemon = battle.sides.flatMap { it.pokemon }.singleOrNull { it.uuid == pokemonUuid } ?: return null
        if (stat !in STAT_NATURES) return null
        fun value(iv: Int, ev: Int, nature: String): Int = battle.spreadModify(pokemon.species.baseStats, PokemonSet(
            species = pokemon.species.name, level = pokemon.level, nature = nature,
            evs = linkedMapOf(stat to ev), ivs = linkedMapOf(stat to iv), moves = emptyList()))
            .getValue(stat)
        val (raising, lowering) = STAT_NATURES.getValue(stat)
        return value(0, 0, lowering)..value(31, 252, raising)
    }

    override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame =
        play(snapshotJson, p1Choice, p2Choice, captureDamageRolls = false, forced = emptyList())

    override fun branchWithDamageEvidence(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame =
        play(snapshotJson, p1Choice, p2Choice, captureDamageRolls = true, forced = emptyList())

    override fun branchWithForcedDamage(snapshotJson: String, p1Choice: String, p2Choice: String,
                                        forcedDamageRolls: List<NativeForcedDamageRoll>): NativeBattleFrame {
        require(forcedDamageRolls.isNotEmpty()) { "At least one observed damage roll is required" }
        return play(snapshotJson, p1Choice, p2Choice, captureDamageRolls = true, forced = forcedDamageRolls)
    }

    override fun close() {
        synchronized(cache) { cache.clear() }
    }

    // region Battles

    private fun build(definition: NativeBattleDefinition): Battle {
        val gameType = if (definition.formatId.contains("doubles")) "doubles" else "singles"
        val opening = definition.openingState?.pokemon?.associateBy { it.uuid }
        val battle = Battle(dex, BattleOptions(gameType = gameType, seed = definition.seed.toIntArray(), log = false,
            strictChoices = true, deferStart = true))
        battle.tracer = Trace()
        battle.setPlayer("p1", "p1", definition.p1Team.map { set(it, opening?.get(it.uuid)) })
        battle.setPlayer("p2", "p2", definition.p2Team.map { set(it, opening?.get(it.uuid)) })
        if (opening != null) {
            for (pokemon in battle.sides.flatMap { it.pokemon }) {
                val seeded = opening.getValue(pokemon.uuid)
                require(pokemon.maxhp == seeded.maxHp) {
                    "Opening max HP disagrees with synthetic set ${pokemon.uuid}: ${seeded.maxHp} != ${pokemon.maxhp}"
                }
            }
            for (side in battle.sides) require(side.pokemon[0].hp > 0) { "Native opening state cannot select a fainted lead Pokemon" }
        }
        battle.start()
        definition.situation?.let { install(battle, it) }
        return battle
    }

    /**
     * Lays a mid-battle position over a battle that has just started with the public actives as leads: the
     * switch-in effects of that start (Intimidate, Drought) are overwritten by the public board, then the
     * move request is rebuilt from the installed position.
     */
    private fun install(battle: Battle, situation: NativeBattleSituation) {
        require(battle.requestState == "move") { "A mid-battle position needs both sides at a move request" }
        battle.turn = situation.turn
        val team = battle.sides.flatMap { it.pokemon }.associateBy { it.uuid }
        for (state in situation.pokemon) {
            val pokemon = requireNotNull(team[state.uuid]) { "Situation names unknown Pokemon ${state.uuid}" }
            state.item?.let { item ->
                val id = Js.toID(item)
                if (id != pokemon.item) {
                    if (id.isEmpty()) pokemon.lastItem = pokemon.item
                    pokemon.item = id
                    pokemon.itemState = EffectState(id).also { it.target = pokemon }
                }
            }
            val hp = state.hp?.coerceAtMost(pokemon.maxhp) ?: publicHp(state.hpFraction, pokemon.maxhp)
            if (hp == 0) {
                require(!pokemon.isActive) { "A fainted Pokemon cannot lead a rebuilt position" }
                if (!pokemon.fainted) pokemon.side.pokemonLeft--
                pokemon.hp = 0
                pokemon.fainted = true
                pokemon.status = "fnt"
                pokemon.statusState = EffectState("fnt").also { it.target = pokemon }
                continue
            }
            pokemon.hp = hp
            val status = Js.toID(state.status)
            if (status.isEmpty()) {
                pokemon.status = ""
                pokemon.statusState = EffectState("").also { it.target = pokemon }
            } else if (pokemon.status != status) {
                pokemon.status = status
                pokemon.statusState = EffectState(status).also {
                    it.target = pokemon
                    // The counters are hidden: a sleeper wakes within two more tries, toxic is one step in.
                    if (status == "slp") { it["startTime"] = 3; it["time"] = 2 }
                    if (status == "tox") it["stage"] = 1
                }
            }
            pokemon.boosts = Pokemon.freshBoosts().also { boosts ->
                state.boosts.forEach { (stat, stage) -> if (stat in boosts) boosts[stat] = stage.coerceIn(-6, 6) }
            }
            state.forme?.let { forme ->
                if (pokemon.species.id != forme) pokemon.formeChange(forme, null, isPermanent = true)
            }
            state.terastallized?.let { type ->
                pokemon.terastallized = type
                if (type != "Stellar") pokemon.types = listOf(type)
                for (ally in pokemon.side.pokemon) ally.canTerastallize = null
            }
            for (slot in pokemon.moveSlots) state.movePp[slot.id]?.let { slot.pp = it.coerceIn(0, slot.maxpp) }
            state.choiceLockedMove?.let { move ->
                pokemon.volatiles["choicelock"] = EffectState("choicelock").also {
                    it["name"] = "Choice Lock"
                    it.target = pokemon
                    it.source = pokemon
                    it["move"] = Js.toID(move)
                }
            }
            if (state.confused && pokemon.isActive && "confusion" !in pokemon.volatiles) {
                // The turns left are hidden: like a sleeper, it stays confused for one more move and then snaps out.
                pokemon.volatiles["confusion"] = EffectState("confusion").also {
                    it.target = pokemon
                    it["time"] = 2
                }
            }
            if (state.movedSinceSwitchIn && pokemon.isActive) {
                pokemon.activeTurns = maxOf(pokemon.activeTurns, 1)
                pokemon.activeMoveActions = maxOf(pokemon.activeMoveActions, 1)
            }
        }
        val field = battle.field
        // Who started the weather or terrain is not public either; any active Pokemon stands in for the setter.
        val anyActive = battle.sides.firstNotNullOf { side -> side.active.firstOrNull { it != null } }
        val weather = situation.weather
        if (weather == null) field.clearWeather() else {
            if (field.weather != Js.toID(weather.id)) field.setWeather(weather.id, anyActive)
            weather.remainingTurns?.let { field.weatherState.duration = it }
        }
        val terrain = situation.terrain
        if (terrain == null) field.clearTerrain() else {
            if (field.terrain != Js.toID(terrain.id)) field.setTerrain(terrain.id, anyActive)
            terrain.remainingTurns?.let { field.terrainState.duration = it }
        }
        // Who set an effect is not public: the side's own active stands in, or the foe's for entry hazards.
        // Only durations depend on the setter (Light Clay), and the installed duration overrides them.
        field.pseudoWeather.clear()
        for (effect in situation.pseudoWeather) {
            field.addPseudoWeather(effect.id, battle.sides[0].active.firstOrNull { it != null })
            field.pseudoWeather[Js.toID(effect.id)]?.let { install(it, effect) }
        }
        for ((side, conditions) in listOf(battle.sides[0] to situation.p1SideConditions,
            battle.sides[1] to situation.p2SideConditions)) {
            side.sideConditions.clear()
            for (effect in conditions) {
                val setterSide = if (Js.toID(effect.id) in ENTRY_HAZARDS) side.foe else side
                val setter = setterSide.active.firstOrNull { it != null } ?: setterSide.pokemon.first()
                repeat(effect.layers ?: 1) { side.addSideCondition(effect.id, setter) }
                side.sideConditions[Js.toID(effect.id)]?.let { install(it, effect) }
            }
        }
        // Move restrictions and trapping were settled when the turn began; settle them again on the installed
        // position (a choice lock, an item that is gone), as Battle.nextTurn does.
        for (side in battle.sides) for (pokemon in side.active) {
            if (pokemon == null || pokemon.fainted) continue
            pokemon.maybeDisabled = false
            for (slot in pokemon.moveSlots) {
                slot.disabled = false
                slot.disabledSource = ""
            }
            battle.runEvent("DisableMove", pokemon)
            for (slot in pokemon.moveSlots.toList()) {
                battle.singleEvent("DisableMove", battle.dex.activeMove(slot.id), null, pokemon)
            }
            pokemon.trapped = false
            pokemon.maybeTrapped = false
            battle.runEvent("TrapPokemon", pokemon)
            if (!pokemon.knownType || battle.dex.notImmune("trapped", pokemon.getTypes())) {
                battle.runEvent("MaybeTrapPokemon", pokemon)
            }
        }
        battle.makeRequest("move")
    }

    /** A nature raising and one lowering each battle stat, for its legal range. */
    private val STAT_NATURES = mapOf(
        "atk" to ("Adamant" to "Modest"),
        "def" to ("Bold" to "Lonely"),
        "spa" to ("Modest" to "Adamant"),
        "spd" to ("Calm" to "Naughty"),
        "spe" to ("Timid" to "Brave"),
    )

    private val ENTRY_HAZARDS = setOf("spikes", "toxicspikes", "stealthrock", "stickyweb", "gmaxsteelsurge")

    private fun install(state: EffectState, effect: NativeEffectSituation) {
        effect.remainingTurns?.let { state.duration = it }
        effect.layers?.let { state["layers"] = it }
    }

    /** The HP whose public bar reads [fraction], from the middle of the HP values that read it. */
    private fun publicHp(fraction: Double, maxHp: Int): Int {
        if (fraction <= 0.0) return 0
        val matching = (1..maxHp).filter { abs(NativeShowdownPublicHp.fraction(it, maxHp) - fraction) < 1e-6 }
        return if (matching.isEmpty()) (fraction * maxHp).roundToInt().coerceIn(1, maxHp) else matching[matching.size / 2]
    }

    private fun set(native: NativePokemonSet, opening: NativePokemonOpeningState?): PokemonSet {
        val moveData = native.moves.map { requireNotNull(dex.move(it)) { "Unknown move $it" } }
        return PokemonSet(
            species = native.species, name = native.name, level = native.level, gender = native.gender,
            ability = native.ability, item = native.item, nature = native.nature,
            evs = native.evs.toMutableMap(), ivs = native.ivs.toMutableMap(), moves = native.moves,
            // The bridge gives every move its base PP, without PP Ups, like Cobblemon's own movesInfo.
            movesInfo = moveData.map { intArrayOf(it.pp, it.pp) }, teraType = native.teraType, uuid = native.uuid,
            currentHealth = opening?.hp, status = opening?.status?.takeIf { it.isNotEmpty() },
            statusDuration = if (opening != null) -1 else null,
        )
    }

    private fun play(snapshotJson: String, p1Choice: String, p2Choice: String, captureDamageRolls: Boolean,
                     forced: List<NativeForcedDamageRoll>): NativeBattleFrame {
        require(p1Choice.isNotBlank() && p2Choice.isNotBlank()) { "Both native choices are required" }
        val forcedByCall = forced.associate { it.damageCallIndex to it.percent }
        require(forcedByCall.size == forced.size) { "Duplicate forced native damage roll" }
        val before = resolve(snapshotJson)
        val battle = before.fork()
        val hits = step(battle, p1Choice, p2Choice, forcedByCall)
        val evidence = when {
            !captureDamageRolls || hits.isEmpty() -> emptyList()
            // A forced replay only confirms that the drawn losses match what was seen; their supports were already
            // measured on the unforced branch, and measuring them again costs sixteen replays per hit.
            forced.isNotEmpty() -> hits.map { NativeDamageRollFrame(it.turn, it.attacker, it.target, it.moveId, it.hpBefore,
                it.maxHp, it.loss, listOf(it.loss), it.callIndex, it.offense, it.defense, it.critical) }
            else -> damageEvidence(before, p1Choice, p2Choice, hits, forcedByCall)
        }
        val step = JsonArray().apply {
            add("branch"); add(p1Choice); add(p2Choice)
            add(JsonArray().also { a -> forced.forEach { f -> a.add(JsonArray().also { it.add(f.damageCallIndex); it.add(f.percent) }) } })
        }
        val token = extend(snapshotJson, step)
        remember(token, battle)
        return frame(token, battle, evidence, battle.tracer as Trace)
    }

    /** One turn of choices. Returns the attributable direct hits the trace saw. */
    private fun step(battle: Battle, p1Choice: String, p2Choice: String, forced: Map<Int, Int>): List<Trace.Hit> {
        val trace = battle.tracer as Trace
        trace.begin(battle, forced)
        // Read both wait flags first: p1's choice can finish the turn and hand p2 a new request.
        val p1Wait = battle.sides[0].activeRequest?.wait ?: false
        val p2Wait = battle.sides[1].activeRequest?.wait ?: false
        submit(battle, battle.sides[0], p1Choice, p1Wait)
        submit(battle, battle.sides[1], p2Choice, p2Wait)
        return trace.end()
    }

    private fun submit(battle: Battle, side: Side, choice: String, requestWasWait: Boolean) {
        if (requestWasWait) {
            require(choice == "pass") { "BetterAI supplied $choice for ${side.id} wait request" }
            return
        }
        require(battle.choose(side.id, choice)) { "Engine rejected ${side.id} choice: $choice" }
    }

    private fun damageEvidence(before: Battle, p1: String, p2: String, hits: List<Trace.Hit>, forced: Map<Int, Int>): List<NativeDamageRollFrame> =
        hits.map { actual ->
            val possible = (85..100).map { percent ->
                val replay = before.fork()
                val replayed = step(replay, p1, p2, forced + (actual.callIndex to percent)).firstOrNull { it.callIndex == actual.callIndex }
                check(replayed != null && replayed.attacker == actual.attacker && replayed.target == actual.target && replayed.moveId == actual.moveId) {
                    "Native damage replay lost direct hit ${actual.moveId} at call ${actual.callIndex}"
                }
                replayed.loss
            }
            check(actual.loss in possible) { "Actual native damage is outside replayed support for ${actual.moveId}" }
            NativeDamageRollFrame(actual.turn, actual.attacker, actual.target, actual.moveId, actual.hpBefore, actual.maxHp,
                actual.loss, possible, actual.callIndex, actual.offense, actual.defense, actual.critical)
        }

    private fun rename(battle: Battle, renames: Map<String, String>) {
        val team = battle.sides.flatMap { it.pokemon }
        require(renames.values.none { to -> team.any { it.uuid == to } }) { "A native rename target is already in the battle" }
        for ((from, to) in renames) {
            val matches = team.filter { it.uuid == from }
            require(matches.size == 1) { "Native rename names unknown or duplicate Pokemon $from" }
            matches[0].uuid = to
        }
    }

    private fun rebind(battle: Battle, rebindings: List<NativeMoveSetRebinding>) {
        for (rebinding in rebindings) {
            val matches = battle.sides.flatMap { it.pokemon }.filter { it.uuid == rebinding.pokemonUuid }
            require(matches.size == 1) { "Move-set rebinding names unknown or duplicate Pokemon ${rebinding.pokemonUuid}" }
            val pokemon = matches[0]
            val expected = rebinding.expectedMoveIds.map(Js::toID)
            val replacement = rebinding.replacementMoveIds.map(Js::toID)
            require(!pokemon.transformed && pokemon.set.moves.map(Js::toID) == expected && pokemon.baseMoves == expected &&
                pokemon.moves == expected && pokemon.baseMoveSlots.indices.all { pokemon.baseMoveSlots[it] === pokemon.moveSlots[it] }) {
                "Unsafe history-sensitive move-set rebinding for ${rebinding.pokemonUuid}"
            }
            val removed = expected.filter { it !in replacement }.toSet()
            val history = listOf(pokemon.lastMove?.id, pokemon.lastMoveUsed?.id, pokemon.moveThisTurn as? String) +
                pokemon.volatiles.values.map { it["move"] as? String }
            require(history.none { Js.toID(it) in removed }) {
                "Move-set rebinding would erase referenced move history for ${rebinding.pokemonUuid}"
            }
            val previous = pokemon.moveSlots.associateBy { it.id }
            val rebound = replacement.map { id ->
                previous[id] ?: requireNotNull(dex.move(id)) { "Unknown rebound move $id" }.let { MoveSlot(it.name, it.id, it.pp, it.pp, it.target) }
            }
            pokemon.baseMoveSlots = rebound.toMutableList()
            pokemon.moveSlots = rebound.toMutableList()
            // The set is what the frame reports as the source set; leaving it stale contradicted the definition.
            pokemon.set = pokemon.set.withMoves(replacement)
            if (pokemon.isActive && battle.requestState == "move") {
                pokemon.maybeDisabled = false
                for (slot in pokemon.moveSlots) {
                    slot.disabled = false
                    slot.disabledSource = ""
                }
                battle.runEvent("DisableMove", pokemon)
                for (slot in pokemon.moveSlots.toList()) {
                    val active = battle.dex.activeMove(slot.id)
                    battle.singleEvent("DisableMove", active, null, pokemon)
                    if (active.flag("cantusetwice") && pokemon.lastMove?.id == slot.id) pokemon.disableMove(slot.id)
                }
            }
        }
        val requests = battle.getRequests(battle.requestState)
        for (i in battle.sides.indices) battle.sides[i].activeRequest = requests[i]
    }

    private fun resolve(snapshotJson: String): Battle {
        synchronized(cache) { cache[snapshotJson]?.let { return it } }
        val root = JsonParser.parseString(snapshotJson).asJsonObject
        require(root.get("engine")?.asInt == 1) { "Not an AI engine snapshot" }
        val definition = gson.fromJson(root.get("definition"), NativeBattleDefinition::class.java)
        var battle = build(definition)
        for (element in root.getAsJsonArray("steps")) {
            val step = element.asJsonArray
            battle = battle.fork()
            when (step[0].asString) {
                "branch" -> step(battle, step[1].asString, step[2].asString,
                    step[3].asJsonArray.associate { it.asJsonArray[0].asInt to it.asJsonArray[1].asInt })
                "rebind" -> rebind(battle, gson.fromJson(step[1], Array<NativeMoveSetRebinding>::class.java).toList())
                "rename" -> rename(battle, step[1].asJsonObject.entrySet().associate { it.key to it.value.asString })
                "reseed" -> reseed(battle, step[1].asInt)
                "restat" -> restat(battle, gson.fromJson(step[1], Array<NativeStatChange>::class.java).toList())
                "reitem" -> reitem(battle, gson.fromJson(step[1], Array<NativeItemRebinding>::class.java).toList())
                else -> error("Unknown engine snapshot step ${step[0]}")
            }
        }
        remember(snapshotJson, battle)
        return battle
    }

    private fun extend(snapshotJson: String, step: JsonArray): String {
        val root = JsonParser.parseString(snapshotJson).asJsonObject
        root.getAsJsonArray("steps").add(step)
        return root.toString()
    }

    private fun remember(token: String, battle: Battle) {
        synchronized(cache) { cache[token] = battle }
    }

    // endregion
    // region Frames

    private fun frame(token: String, battle: Battle, damageRolls: List<NativeDamageRollFrame>, trace: Trace?): NativeBattleFrame {
        val t = battle.tracer as Trace
        return NativeBattleFrame(
            snapshotJson = token,
            turn = battle.turn,
            requestState = battle.requestState,
            ended = battle.ended,
            p1Active = activeFrames(battle.sides[0]),
            p2Active = activeFrames(battle.sides[1]),
            p1Team = teamFrames(battle.sides[0]),
            p2Team = teamFrames(battle.sides[1]),
            p1RequestJson = requestJson(battle, battle.sides[0]),
            p2RequestJson = requestJson(battle, battle.sides[1]),
            field = fieldFrame(battle),
            log = emptyList(),
            executedMoveOrder = t.moveOrder.toList(),
            executedDamageRolls = damageRolls,
            recoilLossP1 = trace?.recoil?.get(0) ?: 0.0,
            recoilLossP2 = trace?.recoil?.get(1) ?: 0.0,
        )
    }

    private fun teamFrames(side: Side): List<NativePokemonFrame> = side.pokemon.map { p ->
        pokemonFrame(p, side.active.indexOf(p).takeIf { it >= 0 })
    }

    private fun activeFrames(side: Side): List<NativePokemonFrame> =
        side.active.mapIndexedNotNull { slot, p -> p?.let { pokemonFrame(it, slot) } }

    private fun pokemonFrame(p: Pokemon, activeSlot: Int?): NativePokemonFrame = NativePokemonFrame(
        uuid = p.uuid,
        species = p.species.id,
        hp = p.hp,
        maxHp = p.maxhp,
        status = p.status,
        ability = p.ability,
        item = p.item,
        types = p.getTypes(),
        boosts = LinkedHashMap(p.boosts),
        volatiles = p.volatiles.keys.sorted(),
        moves = p.moveSlots.map { NativeMoveFrame(it.id, it.pp, it.maxpp, Js.truthy(it.disabled)) },
        activeSlot = activeSlot,
        level = p.level,
        stats = LinkedHashMap(p.storedStats),
        sourceSet = NativePokemonSourceSetFrame(
            species = p.set.species, ability = p.set.ability, item = p.set.item, moves = p.set.moves.toList(),
            nature = p.set.nature, gender = p.set.gender, evs = LinkedHashMap(p.set.evs), ivs = LinkedHashMap(p.set.ivs),
            teraType = p.set.teraType ?: "", openingHp = p.set.currentHealth, openingMaxHp = p.maxhp,
            openingStatus = p.set.status ?: "",
        ),
        baseStabTypes = p.getTypes(false, true),
        terastallizedType = p.terastallized ?: "",
        stellarBoostedTypes = p.stellarBoostedTypes.toList(),
    )

    private fun timed(id: String, state: EffectState) = NativeTimedEffectFrame(
        id, state.duration?.takeIf { it > 0 }, state["layers"]?.let { Js.int(it) }?.takeIf { it > 0 },
    )

    private fun fieldFrame(battle: Battle) = NativeBattleFieldFrame(
        weather = battle.field.weather.takeIf { it.isNotEmpty() }?.let { timed(it, battle.field.weatherState) },
        terrain = battle.field.terrain.takeIf { it.isNotEmpty() }?.let { timed(it, battle.field.terrainState) },
        pseudoWeather = battle.field.pseudoWeather.map { (id, s) -> timed(id, s) }.sortedBy { it.id },
        p1SideConditions = battle.sides[0].sideConditions.map { (id, s) -> timed(id, s) }.sortedBy { it.id },
        p2SideConditions = battle.sides[1].sideConditions.map { (id, s) -> timed(id, s) }.sortedBy { it.id },
        pendingHeals = battle.sides.flatMap { side ->
            side.slotConditions.mapIndexedNotNull { slot, conditions ->
                val wish = conditions["wish"] ?: return@mapIndexedNotNull null
                val target = side.active.getOrNull(slot)?.takeIf { !it.fainted } ?: return@mapIndexedNotNull null
                NativePendingHealFrame(target.uuid, Js.int(wish["hp"]))
            }
        },
    )

    /** The parts of Showdown's request JSON the native search reads. */
    private fun requestJson(battle: Battle, side: Side): String {
        val request = side.activeRequest ?: return "null"
        val out = JsonObject()
        when {
            request.wait -> out.addProperty("wait", true)
            request.teamPreview -> out.addProperty("teamPreview", true)
            request.forceSwitch != null -> out.add("forceSwitch", JsonArray().also { a -> request.forceSwitch.forEach { a.add(it) } })
            else -> out.add("active", JsonArray().also { active ->
                request.active!!.forEachIndexed { slot, data ->
                    if (data == null) {
                        active.add(JsonNull.INSTANCE)
                        return@forEachIndexed
                    }
                    val pokemon = side.active[slot]!!
                    val locked = pokemon.getLockedMove() != null || data.moves.singleOrNull()?.id == "struggle"
                    active.add(JsonObject().apply {
                        add("moves", JsonArray().also { moves ->
                            data.moves.forEach { m ->
                                moves.add(JsonObject().apply {
                                    addProperty("move", m.move)
                                    addProperty("id", m.id)
                                    battle.dex.move(m.id)?.category?.let { addProperty("category", it) }
                                    m.pp?.let { addProperty("pp", it) }
                                    m.maxpp?.let { addProperty("maxpp", it) }
                                    if (m.target.isNotEmpty()) addProperty("target", m.target)
                                    addProperty("disabled", Js.truthy(m.disabled))
                                })
                            }
                        })
                        if (data.trapped) addProperty("trapped", true)
                        if (data.maybeTrapped) addProperty("maybeTrapped", true)
                        // Showdown hides a trap the player cannot see yet (Magnet Pull, Shadow Tag) as maybeTrapped
                        // and rejects the switch only when it is chosen. The search plays every side with full
                        // knowledge of its world, so it must not offer a switch the engine will refuse.
                        if (!data.trapped && Js.truthy(pokemon.trapped)) addProperty("trappedHidden", true)
                        if (!locked) {
                            if (pokemon.canMegaEvo != null) addProperty("canMegaEvo", true)
                            if (data.canDynamax) addProperty("canDynamax", true)
                            if ((data.canDynamax || pokemon.volatiles.containsKey("dynamax")) && pokemon.getDynamaxRequest(true)) {
                                add("maxMoves", JsonObject().apply {
                                    add("maxMoves", JsonArray().also { maxMoves ->
                                        pokemon.moveSlots.forEach { move ->
                                            battle.actions.getMaxMove(move.id, pokemon)?.let { maxMove ->
                                                maxMoves.add(JsonObject().apply {
                                                    addProperty("move", maxMove.id)
                                                    addProperty("target", maxMove.target)
                                                    addProperty("category", maxMove.category)
                                                    if (pokemon.maxMoveDisabled(move.id)) addProperty("disabled", true)
                                                })
                                            }
                                        }
                                    })
                                })
                            }
                            (pokemon.canTerastallize as? String)?.let { addProperty("canTerastallize", it) }
                        }
                    })
                }
            })
        }
        out.add("side", JsonObject().apply {
            add("pokemon", JsonArray().also { team ->
                side.pokemon.forEach { pokemon ->
                    team.add(JsonObject().apply {
                        addProperty("uuid", pokemon.uuid)
                        if (side.slotConditions.getOrNull(pokemon.position)?.containsKey("revivalblessing") == true) {
                            addProperty("reviving", true)
                        }
                    })
                }
            })
        })
        return out.toString()
    }

    // endregion

    /** Follows the same log lines the Showdown bridge wraps: move messages, damage rolls, and HP changes. */
    private class Trace : BattleTracer {
        class Hit(val turn: Int, val attacker: String, val target: String, val moveId: String, val hpBefore: Int,
                  val maxHp: Int, val loss: Int, val callIndex: Int, val offense: NativeDamageStat? = null,
                  val defense: NativeDamageStat? = null, val critical: Boolean? = null)

        private class PendingRoll(val turn: Int, val attacker: String, val moveId: String, val callIndex: Int,
                                  val calculation: Calculation? = null)

        /** The stats and critical hit of the damage calculation whose roll comes next. */
        private class Calculation(val offense: NativeDamageStat, val defense: NativeDamageStat, val critical: Boolean)

        private var calculation: Calculation? = null

        val moveOrder: MutableList<NativeExecutedMoveFrame> = ArrayList()
        var recoil = doubleArrayOf(0.0, 0.0)
        private var current: PendingRoll? = null
        private var pending: MutableList<PendingRoll> = ArrayList()
        private var hits: MutableList<Hit> = ArrayList()
        private var calls = 0
        private var forced: Map<Int, Int> = emptyMap()
        private var hpByUuid: MutableMap<String, Int> = HashMap()
        private var active = false

        fun begin(battle: Battle, forced: Map<Int, Int>) {
            this.forced = forced
            hpByUuid = battle.sides.flatMap { it.pokemon }.associate { it.uuid to it.hp }.toMutableMap()
            current = null
            calculation = null
            pending = ArrayList()
            hits = ArrayList()
            calls = 0
            recoil = doubleArrayOf(0.0, 0.0)
            active = true
        }

        fun end(): List<Hit> {
            active = false
            return hits
        }

        override fun move(battle: Battle, pokemon: Pokemon, moveName: String) {
            if (!active) return
            val moveId = Js.toID(moveName)
            current = PendingRoll(battle.turn, pokemon.uuid, moveId, -1)
            pending = ArrayList()
            moveOrder.add(NativeExecutedMoveFrame(battle.turn, pokemon.uuid, moveId))
        }

        override fun randomizer(battle: Battle, baseDamage: Int, actual: Int): Int {
            val move = current ?: return actual
            if (!active || baseDamage <= 0) return actual
            val callIndex = calls++
            pending.add(PendingRoll(move.turn, move.attacker, move.moveId, callIndex, calculation))
            calculation = null
            val percent = forced[callIndex] ?: return actual
            require(percent in 85..100) { "Invalid forced native damage percentage $percent" }
            return Js.trunc(Js.trunc(baseDamage.toDouble() * percent).toDouble() / 100)
        }

        override fun damageStats(battle: Battle, attacker: Pokemon, attackStat: String, defender: Pokemon, defenseStat: String,
                                 crit: Boolean) {
            if (!active) return
            calculation = Calculation(NativeDamageStat(attacker.uuid, attackStat), NativeDamageStat(defender.uuid, defenseStat), crit)
        }

        override fun hpLine(battle: Battle, kind: String, pokemon: Pokemon, extras: List<String>) {
            if (!active) return
            val previous = hpByUuid[pokemon.uuid]
            hpByUuid[pokemon.uuid] = pokemon.hp
            val hasPublicSource = extras.any { it.startsWith("[from]") }
            val recoilSource = extras.any { it.equals("[from] recoil", ignoreCase = true) }
            if (kind == "-damage" && recoilSource && previous != null && previous > pokemon.hp) {
                recoil[pokemon.side.n] += (previous - pokemon.hp).toDouble() / pokemon.maxhp
            }
            val move = current
            if (kind == "-damage" && move != null && previous != null) {
                val index = pending.indexOfFirst { it.turn == move.turn && it.attacker == move.attacker && it.moveId == move.moveId }
                if (index >= 0) {
                    val roll = pending.removeAt(index)
                    val loss = previous - pokemon.hp
                    if (!hasPublicSource && loss > 0) {
                        hits.add(Hit(move.turn, move.attacker, pokemon.uuid, move.moveId, previous, pokemon.maxhp, loss, roll.callIndex,
                            roll.calculation?.offense, roll.calculation?.defense, roll.calculation?.critical))
                    }
                }
            }
        }
    }

    private fun dexFingerprint(): String {
        val stream = EngineDex::class.java.getResourceAsStream(EngineDex.RESOURCE) ?: return "missing"
        val digest = MessageDigest.getInstance("SHA-256")
        stream.use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
