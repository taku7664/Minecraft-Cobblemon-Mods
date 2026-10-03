package jbro.cobblemon.mcc.betterai.simulation

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex
import jbro.cobblemon.mcc.betterai.engine.sim.Battle
import jbro.cobblemon.mcc.betterai.engine.sim.BattleOptions
import jbro.cobblemon.mcc.betterai.engine.sim.BattleTracer
import jbro.cobblemon.mcc.betterai.engine.sim.EffectState
import jbro.cobblemon.mcc.betterai.engine.sim.MoveSlot
import jbro.cobblemon.mcc.betterai.engine.sim.Pokemon
import jbro.cobblemon.mcc.betterai.engine.sim.PokemonSet
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
        return battle
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
        val evidence = if (captureDamageRolls && hits.isNotEmpty()) damageEvidence(before, p1Choice, p2Choice, hits, forcedByCall) else emptyList()
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
                actual.loss, possible, actual.callIndex)
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
                                    m.pp?.let { addProperty("pp", it) }
                                    m.maxpp?.let { addProperty("maxpp", it) }
                                    if (m.target.isNotEmpty()) addProperty("target", m.target)
                                    addProperty("disabled", Js.truthy(m.disabled))
                                })
                            }
                        })
                        if (data.trapped) addProperty("trapped", true)
                        if (data.maybeTrapped) addProperty("maybeTrapped", true)
                        if (!locked) {
                            if (pokemon.canMegaEvo != null) addProperty("canMegaEvo", true)
                            if (data.canDynamax) addProperty("canDynamax", true)
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
                  val maxHp: Int, val loss: Int, val callIndex: Int)

        private class PendingRoll(val turn: Int, val attacker: String, val moveId: String, val callIndex: Int)

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
            pending.add(PendingRoll(move.turn, move.attacker, move.moveId, callIndex))
            val percent = forced[callIndex] ?: return actual
            require(percent in 85..100) { "Invalid forced native damage percentage $percent" }
            return Js.trunc(Js.trunc(baseDamage.toDouble() * percent).toDouble() / 100)
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
                        hits.add(Hit(move.turn, move.attacker, pokemon.uuid, move.moveId, previous, pokemon.maxhp, loss, roll.callIndex))
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
