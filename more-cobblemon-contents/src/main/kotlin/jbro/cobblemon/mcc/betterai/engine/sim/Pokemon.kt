package jbro.cobblemon.mcc.betterai.engine.sim

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import jbro.cobblemon.mcc.betterai.engine.dex.Species
import kotlin.math.ceil
import kotlin.math.floor

/** Port of `sim/pokemon.js` (gen 9 paths, with the Cobblemon and Mega Showdown changes the server runs). */
class Pokemon(val set: PokemonSet, val side: Side) {
    val battle: Battle = side.battle

    var baseSpecies: Species = battle.dex.species(set.species.ifEmpty { set.name })
        ?: error("Unidentified species: ${set.species}")
    var species: Species = baseSpecies
    val speciesState: EffectState = EffectState(species.id)
    val name: String
    val fullname: String
    val level: Int
    var gender: String
    val uuid: String
    var baseMoveSlots: MutableList<MoveSlot> = ArrayList()
    var moveSlots: MutableList<MoveSlot> = ArrayList()
    var position: Int = 0
    var details: String
    var status: String = ""
    var statusState: EffectState = EffectState("")
    val volatiles: LinkedHashMap<String, EffectState> = LinkedHashMap()
    var showCure: Boolean? = null
    var hpType: String
    var hpPower: Int
    val baseHpType: String
    val baseHpPower: Int
    var baseStoredStats: LinkedHashMap<String, Int>? = null
    val storedStats: LinkedHashMap<String, Int> = linkedMapOf("atk" to 0, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 0)
    var boosts: LinkedHashMap<String, Int> = freshBoosts()
    val alphaBoosts: LinkedHashMap<String, Int> = freshBoosts()
    var baseAbility: String = Js.toID(set.ability)
    var ability: String = baseAbility
    var abilityState: EffectState = EffectState(ability)
    var item: String = Js.toID(set.item)
    var itemState: EffectState = EffectState(item)
    var lastItem: String = ""
    var usedItemThisTurn = false
    var ateBerry = false
    /** `false`, `true` or `"hidden"`. */
    var trapped: Any = false
    var maybeTrapped = false
    var maybeDisabled = false
    var illusion: Pokemon? = null
    var transformed = false
    var fainted = false
    var faintQueued = false
    var subFainted: Boolean? = null
    var types: List<String> = baseSpecies.types
    var baseTypes: List<String> = types
    var addedType: String = ""
    var knownType = true
    var apparentType: String = baseSpecies.types.joinToString("/")
    var teraType: String = set.teraType ?: types[0]
    /** `false`, `true`, or the move id that asked for the switch. */
    var switchFlag: Any = false
    var forceSwitchFlag = false
    var skipBeforeSwitchOutEventFlag = false
    var draggedIn: Int? = null
    var newlySwitched = false
    var beingCalledBack = false
    var lastMove: ActiveMove? = null
    var lastMoveUsed: ActiveMove? = null
    var lastMoveTargetLoc: Int? = null
    var moveThisTurn: Any = ""
    var statsRaisedThisTurn = false
    var statsLoweredThisTurn = false
    var hurtThisTurn: Int? = null
    var lastDamage = 0
    val attackedBy: MutableList<Attacker> = ArrayList()
    var timesAttacked = 0
    var isActive = false
    var activeTurns = 0
    var activeMoveActions = 0
    var previouslySwitchedIn = 0
    var truantTurn = false
    var swordBoost = false
    var shieldBoost = false
    var syrupTriggered = false
    val stellarBoostedTypes: MutableList<String> = ArrayList()
    var isStarted = false
    var duringMove = false
    var weighthg = 1
    var speed = 0
    var abilityOrder = 0
    var canMegaEvo: String? = null
    var canUltraBurst: String? = null
    val canGigantamax: String?
    /** A type name, or null once it can no longer terastallize. Showdown also stores `false` here. */
    var canTerastallize: Any? = null
    var terastallized: String? = null
    var maxhp = 0
    var baseMaxhp = 0
    var hp = 0
    var moveThisTurnResult: Any? = Unit
    var moveLastTurnResult: Any? = Unit
    var staleness: String? = null
    var pendingStaleness: String? = null
    var volatileStaleness: String? = null
    val dynamaxLevel: Int
    val gigantamax: Boolean
    val happiness: Int
    val m: HashMap<String, Any?> = HashMap()

    init {
        if (set.name == set.species || set.name.isEmpty()) set.name = baseSpecies.baseSpecies
        name = set.name.take(20)
        fullname = side.id + ": " + name
        set.level = (set.level).coerceIn(1, 9999)
        level = set.level
        gender = when (set.gender) {
            "M", "F", "N" -> set.gender
            else -> species.gender.ifEmpty { if (battle.randomReal() * 2 < 1) "M" else "F" }
        }
        if (gender == "N") gender = ""
        happiness = set.happiness?.coerceIn(0, 255) ?: 255
        dynamaxLevel = set.dynamaxLevel?.coerceIn(0, 10) ?: 10
        gigantamax = set.gigantamax
        uuid = set.uuid
        require(set.moves.isNotEmpty()) { "Set $name has no moves" }
        for ((i, moveName) in set.moves.withIndex()) {
            val move = battle.dex.move(moveName) ?: continue
            val info = set.movesInfo?.getOrNull(i)
            val basePp = if (move.noPPBoosts || Js.truthy(move.isZ)) move.pp else move.pp * 8 / 5
            baseMoveSlots.add(MoveSlot(move.name, move.id, info?.get(0) ?: basePp, info?.get(1) ?: basePp, move.target))
        }
        details = detailsFor(species.name)
        for (stat in listOf("hp", "atk", "def", "spe", "spa", "spd")) {
            if (set.evs[stat] == null) set.evs[stat] = 0
            if (set.ivs[stat] == null) set.ivs[stat] = 31
        }
        for (stat in set.evs.keys.toList()) set.evs[stat] = set.evs.getValue(stat).coerceIn(0, 255)
        for (stat in set.ivs.keys.toList()) set.ivs[stat] = set.ivs.getValue(stat).coerceIn(0, 31)
        val hidden = hiddenPower(set.ivs)
        hpType = set.hpType ?: hidden.first
        hpPower = hidden.second
        baseHpType = hpType
        baseHpPower = hpPower
        canMegaEvo = battle.actions.canMegaEvo(this)
        canUltraBurst = battle.actions.canUltraBurst(this)
        canGigantamax = baseSpecies.canGigantamax
        canTerastallize = battle.actions.canTerastallize(this)
        clearVolatile()
        hp = set.currentHealth ?: maxhp
        set.status?.takeIf { it.isNotEmpty() }?.let { raw ->
            val condition = battle.dex.condition(raw)
            status = condition.id
            statusState = EffectState(condition.id).also { it.target = this }
            if (set.statusDuration != -1 && set.statusDuration != null) {
                statusState.duration = set.statusDuration
                statusState["startTime"] = set.statusDuration
                statusState["time"] = set.statusDuration
            }
        }
        if (hp == 0) {
            status = "fnt"
            fainted = true
        }
    }

    private fun detailsFor(speciesName: String): String {
        val displayed = if (speciesName == "Greninja-Bond") "Greninja" else speciesName
        return displayed + ", " + uuid + (if (level == 100) "" else ", L$level") +
            (if (gender == "") "" else ", $gender") + (if (set.shiny) ", shiny" else "")
    }

    val moves: List<String> get() = moveSlots.map { it.id }
    val baseMoves: List<String> get() = baseMoveSlots.map { it.id }

    fun getSlot(): String {
        val positionOffset = (side.n / 2) * side.active.size
        return side.id + "abcdef"[position + positionOffset]
    }

    override fun toString(): String = if (isActive) getSlot() + ": " + uuid else side.id + ": " + uuid

    /** `getHealth` as a log part: secret (exact) and shared (percentage) halves. */
    val getHealth: SplitPart get() = SplitPart {
        if (hp == 0) Split(side.id, "0 fnt", "0 fnt")
        else {
            var secret = "$hp/$maxhp"
            val ratio = hp.toDouble() / maxhp
            var percentage = ceil(ratio * 100).toInt()
            if (percentage == 100 && ratio < 1) percentage = 99
            var shared = "$percentage/100"
            if (status.isNotEmpty()) {
                secret += " $status"
                shared += " $status"
            }
            Split(side.id, secret, shared)
        }
    }

    val getDetails: SplitPart get() = SplitPart {
        val health = getHealth.produce()
        var d = details
        illusion?.let { ill ->
            var displayed = ill.species.name
            if (displayed == "Greninja-Bond") displayed = "Greninja"
            d = displayed + "," + ill.uuid + (if (level == 100) "" else ", L$level") +
                (if (ill.gender == "") "" else ", " + ill.gender) + (if (ill.set.shiny) ", shiny" else "")
        }
        terastallized?.let { d += ", tera:$it" }
        Split(health.side, "$d|${health.secret}", "$d|${health.shared}")
    }

    fun updateSpeed() {
        speed = getActionSpeed()
    }

    fun calculateStat(statName: String, boost: Int, modifier: Number = 1, statUser: Pokemon? = null): Int {
        var stat = storedStats.getValue(statName)
        if ("wonderroom" in battle.field.pseudoWeather) {
            if (statName == "def") stat = storedStats.getValue("spd") else if (statName == "spd") stat = storedStats.getValue("def")
        }
        val boosts = linkedMapOf(statName to boost)
        @Suppress("UNCHECKED_CAST")
        val modified = battle.runEvent("ModifyBoost", statUser ?: this, null, null, boosts) as Map<String, Int>
        var b = modified[statName] ?: 0
        if (b > 6) b = 6
        if (b < -6) b = -6
        stat = if (b >= 0) floor(stat * BOOST_TABLE[b]).toInt() else floor(stat / BOOST_TABLE[-b]).toInt()
        return battle.modify(stat, modifier)
    }

    fun getStat(statNameIn: String, unboosted: Boolean = false, unmodified: Boolean = false): Int {
        var statName = statNameIn
        var stat = storedStats.getValue(statName)
        val alpha = alphaBoosts[statName] ?: 0
        if (alpha > 0) stat = floor(stat * BOOST_TABLE[alpha]).toInt()
        if (unmodified && "wonderroom" in battle.field.pseudoWeather) {
            if (statName == "def") statName = "spd" else if (statName == "spd") statName = "def"
        }
        if (!unboosted) {
            @Suppress("UNCHECKED_CAST")
            val boosts = battle.runEvent("ModifyBoost", this, null, null, LinkedHashMap(this.boosts)) as Map<String, Int>
            var b = boosts[statName] ?: 0
            if (b > 6) b = 6
            if (b < -6) b = -6
            stat = if (b >= 0) floor(stat * BOOST_TABLE[b]).toInt() else floor(stat / BOOST_TABLE[-b]).toInt()
        }
        if (!unmodified) {
            stat = Js.int(battle.runEvent("Modify" + STAT_EVENT.getValue(statName), this, null, null, stat))
        }
        if (statName == "spe" && stat > 10000) stat = 10000
        return stat
    }

    fun getActionSpeed(): Int {
        var speed = getStat("spe", false, false)
        if (battle.field.getPseudoWeather("trickroom") != null) speed = 10000 - speed
        return Js.trunc(speed.toDouble(), 13)
    }

    fun getBestStat(unboosted: Boolean = false, unmodified: Boolean = false): String {
        var statName = "atk"
        var bestStat = 0
        for (i in listOf("atk", "def", "spa", "spd", "spe")) {
            if (getStat(i, unboosted, unmodified) > bestStat) {
                statName = i
                bestStat = getStat(i, unboosted, unmodified)
            }
        }
        return statName
    }

    fun getWeight(): Int = maxOf(1, Js.int(battle.runEvent("ModifyWeight", this, null, null, weighthg)))

    fun getMoveData(moveId: String): MoveSlot? {
        val id = battle.dex.move(moveId)?.id ?: Js.toID(moveId)
        return moveSlots.firstOrNull { it.id == id }
    }

    fun getMoveHitData(move: ActiveMove): MoveHitData {
        val table = move.moveHitData ?: HashMap<String, MoveHitData>().also { move.moveHitData = it }
        return table.getOrPut(getSlot()) { MoveHitData() }
    }

    fun alliesAndSelf(): List<Pokemon> = side.allies()
    fun allies(): List<Pokemon> = side.allies().filter { it !== this }
    fun adjacentAllies(): List<Pokemon> = side.allies().filter { isAdjacent(it) }
    fun foes(all: Boolean = false): List<Pokemon> = side.foes(all)
    fun adjacentFoes(): List<Pokemon> = if (battle.activePerHalf <= 2) side.foes() else side.foes().filter { isAdjacent(it) }

    fun isAlly(pokemon: Pokemon?): Boolean = pokemon != null && (side === pokemon.side || side.allySide === pokemon.side)

    fun isAdjacent(pokemon2: Pokemon): Boolean {
        if (fainted || pokemon2.fainted) return false
        if (battle.activePerHalf <= 2) return this !== pokemon2
        if (side === pokemon2.side) return Math.abs(position - pokemon2.position) == 1
        return Math.abs(position + pokemon2.position + 1 - side.active.size) <= 1
    }

    fun getUndynamaxedHP(amount: Int? = null): Int {
        val value = amount?.takeIf { it != 0 } ?: hp
        if (volatiles["dynamax"] != null) return ceil(value.toDouble() * baseMaxhp / maxhp).toInt()
        return value
    }

    fun getSmartTargets(target: Pokemon, move: ActiveMove): List<Pokemon> {
        val target2 = target.adjacentAllies().firstOrNull()
        if (target2 == null || target2 === this || target2.hp == 0) {
            move.smartTarget = false
            return listOf(target)
        }
        if (target.hp == 0) {
            move.smartTarget = false
            return listOf(target2)
        }
        return listOf(target, target2)
    }

    fun getAtLoc(targetLoc: Int): Pokemon? {
        var s = battle.sides[if (targetLoc < 0) side.n % 2 else (side.n + 1) % 2]
        var loc = Math.abs(targetLoc)
        if (loc > s.active.size) {
            loc -= s.active.size
            s = battle.sides[s.n + 2]
        }
        return s.active.getOrNull(loc - 1)
    }

    fun getLocOf(target: Pokemon): Int {
        val positionOffset = (target.side.n / 2) * target.side.active.size
        val pos = target.position + positionOffset + 1
        val sameHalf = side.n % 2 == target.side.n % 2
        return if (sameHalf) -pos else pos
    }

    class MoveTargets(val targets: List<Pokemon>, val pressureTargets: List<Pokemon>)

    fun getMoveTargets(move: ActiveMove, targetIn: Pokemon?): MoveTargets {
        var target = targetIn
        var targets = ArrayList<Pokemon>()
        when (move.target) {
            "all", "foeSide", "allySide", "allyTeam" -> {
                if (!move.target.startsWith("foe")) targets.addAll(alliesAndSelf())
                if (!move.target.startsWith("ally")) targets.addAll(foes(true))
                if (targets.isNotEmpty() && target !in targets) battle.retargetLastMove(targets.last())
            }
            "allAdjacent", "allAdjacentFoes" -> {
                if (move.target == "allAdjacent") targets.addAll(adjacentAllies())
                targets.addAll(adjacentFoes())
                if (targets.isNotEmpty() && target !in targets) battle.retargetLastMove(targets.last())
            }
            "allies" -> targets = ArrayList(alliesAndSelf())
            else -> {
                val selectedTarget = target
                if (target == null || (target.fainted && !target.isAlly(this) && battle.gameType != "freeforall")) {
                    val possible = battle.getRandomTarget(this, move) ?: return MoveTargets(emptyList(), emptyList())
                    target = possible
                }
                if (battle.activePerHalf > 1 && !move.tracksTarget) {
                    val weather = effectiveWeather()
                    val isCharging = move.flag("charge") && volatiles["twoturnmove"] == null &&
                        !(move.id.startsWith("solarb") && weather in listOf("sunnyday", "desolateland")) &&
                        !(move.id == "electroshot" && weather in listOf("raindance", "primordialsea")) &&
                        !(hasItem("powerherb") && move.id != "skydrop")
                    if (!isCharging) target = battle.priorityEvent("RedirectTarget", this, this, move, target) as Pokemon
                }
                if (move.smartTarget == true) {
                    targets = ArrayList(getSmartTargets(target!!, move))
                    target = targets[0]
                } else {
                    targets.add(target!!)
                }
                if (target.fainted && !move.flag("futuremove")) return MoveTargets(emptyList(), emptyList())
                if (selectedTarget !== target) battle.retargetLastMove(target)
            }
        }
        var pressureTargets: List<Pokemon> = targets
        if (move.target == "foeSide") pressureTargets = emptyList()
        if (move.flag("mustpressure")) pressureTargets = foes()
        return MoveTargets(targets, pressureTargets)
    }

    fun ignoringAbility(): Boolean {
        if (!isActive) return true
        val ability = getAbility()
        if (ability.flag("notransform") && transformed) return true
        if (ability.flag("cantsuppress")) return false
        if (volatiles["gastroacid"] != null) return true
        if (hasItem("abilityshield") || this.ability == "neutralizinggas") return false
        for (pokemon in battle.getAllActive()) {
            if (pokemon.ability == "neutralizinggas" && pokemon.volatiles["gastroacid"] == null && !pokemon.transformed &&
                !Js.truthy(pokemon.abilityState["ending"]) && volatiles["commanding"] == null) return true
        }
        return false
    }

    fun ignoringItem(): Boolean = Js.truthy(itemState["knockedOff"]) || !isActive ||
        (!getItem().bool("ignoreKlutz") && hasAbility("klutz")) || volatiles["embargo"] != null ||
        battle.field.pseudoWeather["magicroom"] != null

    fun deductPP(moveId: String, amountIn: Int? = null): Int {
        val ppData = getMoveData(moveId) ?: return 0
        ppData.used = true
        if (ppData.pp == 0) return 0
        var amount = amountIn?.takeIf { it != 0 } ?: 1
        ppData.pp -= amount
        if (ppData.pp < 0) {
            amount += ppData.pp
            ppData.pp = 0
        }
        return amount
    }

    fun moveUsed(move: ActiveMove, targetLoc: Int?) {
        lastMove = move
        lastMoveTargetLoc = targetLoc
        moveThisTurn = move.id
    }

    class Attacker(val source: Pokemon, val damage: Int, val move: String, var thisTurn: Boolean, val slot: String, val damageValue: Any?)

    fun gotAttacked(move: ActiveMove, damage: Any?, source: Pokemon) {
        val damageNumber = if (Js.isNumber(damage)) Js.int(damage) else 0
        attackedBy.add(Attacker(source, damageNumber, move.id, true, source.getSlot(), damage))
    }

    fun getLastAttackedBy(): Attacker? = attackedBy.lastOrNull()

    fun getLastDamagedBy(filterOutSameSide: Boolean? = null): Attacker? =
        attackedBy.lastOrNull { Js.isNumber(it.damageValue) && (filterOutSameSide == null || !isAlly(it.source)) }

    fun getLockedMove(): String? {
        val locked = battle.runEvent("LockMove", this)
        return if (locked == true || !Js.truthy(locked)) null else locked as String
    }

    class MoveRequest(val move: String, val id: String, val pp: Int?, val maxpp: Int?, val target: String, val disabled: Any)

    fun getMoves(lockedMove: String? = null, restrictData: Boolean = false): List<MoveRequest> {
        if (lockedMove != null) {
            val locked = Js.toID(lockedMove)
            // Showdown lists a locked move as just {move, id}: no target, so the choice takes no target either.
            trapped = true
            if (locked == "recharge") return listOf(MoveRequest("Recharge", "recharge", null, null, "", false))
            for (slot in moveSlots) {
                if (slot.id != locked) continue
                return listOf(MoveRequest(slot.move, slot.id, null, null, "", false))
            }
            val data = battle.dex.move(locked)
            return listOf(MoveRequest(data?.name ?: locked, locked, null, null, "", false))
        }
        val moves = ArrayList<MoveRequest>()
        var hasValidMove = false
        for (slot in moveSlots) {
            var moveName = slot.move
            if (slot.id == "hiddenpower") moveName = "Hidden Power $hpType"
            var target = slot.target
            if (slot.id == "curse") {
                if (!hasType("Ghost")) target = battle.dex.move("curse")!!.nonGhostTarget.ifEmpty { slot.target }
            } else if (slot.id == "pollenpuff" && volatiles["healblock"] != null) {
                target = "adjacentFoe"
            }
            var disabled: Any = slot.disabled
            if (volatiles["dynamax"] != null) {
                val canCauseStruggle = listOf("Encore", "Disable", "Taunt", "Assault Vest", "Belch", "Stuff Cheeks")
                disabled = maxMoveDisabled(slot.id) || (Js.truthy(disabled) && slot.disabledSource in canCauseStruggle)
            } else if ((slot.pp <= 0 && volatiles["partialtrappinglock"] == null) ||
                (Js.truthy(disabled) && side.active.size >= 2 && battle.actions.targetTypeChoices(target))) {
                disabled = true
            }
            if (!Js.truthy(disabled)) hasValidMove = true
            else if (disabled == "hidden" && restrictData) disabled = false
            moves.add(MoveRequest(moveName, slot.id, slot.pp, slot.maxpp, target, disabled))
        }
        return if (hasValidMove) moves else emptyList()
    }

    fun maxMoveDisabled(baseMoveId: String): Boolean {
        val base = battle.dex.move(baseMoveId) ?: return true
        if ((getMoveData(base.id)?.pp ?: 0) == 0) return true
        return base.category == "Status" && (hasItem("assaultvest") || volatiles["taunt"] != null)
    }

    fun getDynamaxRequest(skipChecks: Boolean = false): Boolean {
        if (!skipChecks) {
            if (!side.canDynamaxNow()) return false
            if (species.isMega || species.isPrimal || species.forme == "Ultra" || Js.truthy(getItem().data("zMove")) ||
                terastallized != null || (canMegaEvo != null && species.baseSpecies != "Rayquaza")) return false
            if (species.cannotDynamax || illusion?.species?.cannotDynamax == true) return false
        }
        var atLeastOne = false
        for (slot in moveSlots) {
            val move = battle.dex.move(slot.id) ?: continue
            if (battle.actions.getMaxMove(move.id, this) != null && !maxMoveDisabled(move.id)) atLeastOne = true
        }
        return atLeastOne
    }

    class ActiveRequest(val moves: List<MoveRequest>, val canDynamax: Boolean, val trapped: Boolean, val maybeTrapped: Boolean)

    fun getMoveRequestData(): ActiveRequest {
        var lockedMove = getLockedMove()
        val isLastActive = isLastActive()
        val canSwitchIn = battle.canSwitch(side) > 0
        var moves = getMoves(lockedMove, isLastActive)
        if (moves.isEmpty()) {
            moves = listOf(MoveRequest("Struggle", "struggle", null, null, "randomNormal", false))
            lockedMove = "struggle"
        }
        var trappedFlag = false
        var maybeTrappedFlag = false
        if (isLastActive) {
            if (canSwitchIn) {
                if (trapped == true) trappedFlag = true else if (maybeTrapped) maybeTrappedFlag = true
            }
        } else if (canSwitchIn && Js.truthy(trapped)) {
            trappedFlag = true
        }
        val canDynamax = lockedMove == null && getDynamaxRequest()
        return ActiveRequest(moves, canDynamax, trappedFlag, maybeTrappedFlag)
    }

    fun isLastActive(): Boolean {
        if (!isActive) return false
        for (i in position + 1 until side.active.size) {
            val ally = side.active[i]
            if (ally != null && !ally.fainted) return false
        }
        return true
    }

    fun positiveBoosts(): Int = boosts.values.filter { it > 0 }.sum()

    fun getCappedBoost(boosts: Map<String, Int>): LinkedHashMap<String, Int> {
        val capped = LinkedHashMap<String, Int>()
        for ((name, boost) in boosts) {
            if (boost == 0) continue
            val current = this.boosts[name] ?: 0
            capped[name] = (current + boost).coerceIn(-6, 6) - current
        }
        return capped
    }

    fun boostBy(boosts: Map<String, Int>): Int {
        var delta = 0
        for ((name, value) in getCappedBoost(boosts)) {
            delta = value
            this.boosts[name] = (this.boosts[name] ?: 0) + delta
        }
        return delta
    }

    fun clearBoosts() {
        for (name in boosts.keys) boosts[name] = 0
    }

    fun setBoost(values: Map<String, Int>) {
        for ((name, value) in values) boosts[name] = value
    }

    fun copyVolatileFrom(pokemon: Pokemon, switchCause: Any?) {
        clearVolatile()
        if (switchCause != "shedtail") boosts = pokemon.boosts
        for ((id, state) in pokemon.volatiles) {
            if (switchCause == "shedtail" && id != "substitute") continue
            if (battle.dex.conditionById(id).bool("noCopy")) continue
            val copy = EffectState(state.id).also {
                it.duration = state.duration; it.target = state.target; it.source = state.source
                it.sourceSlot = state.sourceSlot; it.sourceEffect = state.sourceEffect; it.values.putAll(state.values)
            }
            volatiles[id] = copy
            @Suppress("UNCHECKED_CAST")
            val linked = copy["linkedPokemon"] as? MutableList<Pokemon>
            if (linked != null) {
                val linkedStatus = copy["linkedStatus"]
                pokemon.volatiles[id]?.remove("linkedPokemon")
                pokemon.volatiles[id]?.remove("linkedStatus")
                for (linkedPoke in linked) {
                    @Suppress("UNCHECKED_CAST")
                    val links = linkedPoke.volatiles[Js.toID(linkedStatus)]?.get("linkedPokemon") as? MutableList<Pokemon>
                    links?.let { it[it.indexOf(pokemon)] = this }
                }
            }
        }
        pokemon.clearVolatile()
        for ((id, state) in volatiles.entries.toList()) {
            battle.singleEvent("Copy", battle.dex.conditionById(id), state, this)
        }
    }

    fun transformInto(pokemon: Pokemon, effect: EffectLike? = null): Boolean {
        val species = pokemon.species
        if (pokemon.fainted || illusion != null || pokemon.illusion != null || pokemon.volatiles["substitute"] != null ||
            pokemon.transformed || transformed || species.name == "Eternatus-Eternamax" ||
            (species.baseSpecies in listOf("Ogerpon", "Terapagos") && (terastallized != null || pokemon.terastallized != null)) ||
            terastallized == "Stellar") return false
        if (setSpecies(species, effect, true) == null) return false
        transformed = true
        weighthg = pokemon.weighthg
        val types = pokemon.getTypes(true, true)
        @Suppress("UNCHECKED_CAST")
        setType(pokemon.volatiles["roost"]?.get("typeWas") as? List<String> ?: types, true)
        addedType = pokemon.addedType
        knownType = isAlly(pokemon) && pokemon.knownType
        apparentType = pokemon.apparentType
        for (stat in storedStats.keys) storedStats[stat] = pokemon.storedStats.getValue(stat)
        moveSlots = ArrayList()
        timesAttacked = pokemon.timesAttacked
        for (slot in pokemon.moveSlots) {
            var moveName = slot.move
            if (slot.id == "hiddenpower") moveName = "Hidden Power $hpType"
            val pp = if (slot.maxpp == 1) 1 else 5
            moveSlots.add(MoveSlot(moveName, slot.id, pp, pp, slot.target, false, "", false, virtual = true))
        }
        for ((name, value) in pokemon.boosts) boosts[name] = value
        for (volatile in listOf("dragoncheer", "focusenergy", "gmaxchistrike", "laserfocus")) {
            if (pokemon.volatiles[volatile] != null) {
                addVolatile(volatile)
                if (volatile == "gmaxchistrike") volatiles[volatile]!!["layers"] = pokemon.volatiles[volatile]!!["layers"]
            } else {
                removeVolatile(volatile)
            }
        }
        if (effect != null) battle.add("-transform", this, pokemon, "[from] " + effect.fullname)
        else battle.add("-transform", this, pokemon)
        if (terastallized != null) {
            knownType = true
            apparentType = terastallized!!
        }
        setAbility(pokemon.ability, this, isFromFormeChange = true, isTransform = true)
        if (this.species.baseSpecies == "Ogerpon" && Js.truthy(canTerastallize)) canTerastallize = false
        if (this.species.baseSpecies == "Terapagos" && Js.truthy(canTerastallize)) canTerastallize = false
        return true
    }

    fun setSpecies(rawSpecies: Species, source: EffectLike? = battle.effect, isTransform: Boolean = false): Species? {
        val result = battle.runEvent("ModifySpecies", this, null, source, rawSpecies)
        val species = result as? Species ?: return null
        this.species = species
        setType(species.types, true)
        apparentType = rawSpecies.types.joinToString("/")
        addedType = species.addedType ?: ""
        knownType = true
        weighthg = species.weighthg
        val stats = battle.spreadModify(species.baseStats, set)
        species.maxHP?.let { stats["hp"] = it }
        if (maxhp == 0) {
            baseMaxhp = stats.getValue("hp")
            maxhp = stats.getValue("hp")
            hp = stats.getValue("hp")
        }
        if (!isTransform) baseStoredStats = stats
        for (stat in storedStats.keys) storedStats[stat] = stats.getValue(stat)
        speed = storedStats.getValue("spe")
        return species
    }

    fun formeChange(speciesId: String, source: EffectLike? = battle.effect, isPermanent: Boolean = false, message: String? = null): Boolean {
        val rawSpecies = battle.dex.species(speciesId) ?: return false
        val species = setSpecies(rawSpecies, source) ?: return false
        val apparentSpecies = illusion?.species?.name ?: species.baseSpecies
        if (isPermanent) {
            baseSpecies = rawSpecies
            details = detailsFor(species.name)
            var d = (illusion ?: this).details
            terastallized?.let { d += ", tera:$it" }
            battle.add("detailschange", this, d)
            if (source == null) {
                // no message
            } else if (source.effectType == "Item") {
                canTerastallize = null
                if (Js.truthy(source.data("zMove"))) {
                    battle.add("-burst", this, apparentSpecies, species.requiredItem)
                    moveThisTurnResult = true
                } else if (source.declares("onPrimal")) {
                    if (illusion != null) {
                        ability = ""
                        battle.add("-primal", illusion, species.requiredItem)
                    } else {
                        battle.add("-primal", this, species.requiredItem)
                    }
                } else {
                    battle.add("-mega", this, apparentSpecies, species.requiredItem)
                    moveThisTurnResult = true
                }
            } else if (source.effectType == "Status") {
                battle.add("-formechange", this, species.name, message)
            }
        } else {
            if (source?.effectType == "Ability") {
                battle.add("-formechange", this, species.name, message, "[from] ability: " + source.name)
            } else {
                battle.add("-formechange", this, illusion?.species?.name ?: species.name, message)
            }
        }
        if (isPermanent && (source == null || source.id !in listOf("disguise", "iceface"))) {
            if (illusion != null) ability = ""
            if (source != null || !getAbility().flag("cantsuppress")) setAbility(species.abilities["0"] ?: "", null, true)
            baseAbility = Js.toID(species.abilities["0"])
        }
        if (terastallized != null) {
            knownType = true
            apparentType = terastallized!!
        }
        return true
    }

    fun clearVolatile(includeSwitchFlags: Boolean = true) {
        boosts = freshBoosts()
        moveSlots = ArrayList(baseMoveSlots)
        transformed = false
        ability = baseAbility
        hpType = baseHpType
        hpPower = baseHpPower
        if (canTerastallize == false) canTerastallize = teraType
        for ((_, state) in volatiles.entries.toList()) {
            val linkedStatus = state["linkedStatus"]
            if (linkedStatus != null) {
                @Suppress("UNCHECKED_CAST")
                removeLinkedVolatiles(linkedStatus, state["linkedPokemon"] as MutableList<Pokemon>)
            }
        }
        if (species.name == "Eternatus-Eternamax" && volatiles["dynamax"] != null) {
            val dynamax = volatiles.getValue("dynamax")
            volatiles.clear()
            volatiles["dynamax"] = dynamax
        } else {
            volatiles.clear()
        }
        if (includeSwitchFlags) {
            switchFlag = false
            forceSwitchFlag = false
        }
        lastMove = null
        lastMoveUsed = null
        moveThisTurn = ""
        moveLastTurnResult = Unit
        moveThisTurnResult = Unit
        lastDamage = 0
        attackedBy.clear()
        hurtThisTurn = null
        newlySwitched = true
        beingCalledBack = false
        volatileStaleness = null
        setSpecies(baseSpecies)
    }

    fun hasType(type: String): Boolean = type in getTypes()

    fun hasType(types: List<String>): Boolean {
        val mine = getTypes()
        return types.any { it in mine }
    }

    fun faint(source: Pokemon? = null, effect: EffectLike? = null): Int {
        if (fainted || faintQueued) return 0
        val d = hp
        hp = 0
        switchFlag = false
        faintQueued = true
        battle.faintQueue.add(Battle.FaintData(this, source, effect))
        if (baseSpecies.forme.startsWith("Mega")) {
            val baseForm = battle.dex.species(baseSpecies.baseSpecies)!!
            formeChange(baseForm.name, null, true)
            baseSpecies = baseForm
            canMegaEvo = null
        }
        return d
    }

    fun damage(amount: Double, source: Pokemon? = null, effect: EffectLike? = null): Int {
        if (hp == 0 || amount.isNaN() || amount <= 0) return 0
        var d = if (amount < 1) 1.0 else amount
        var dealt = Js.trunc(d)
        hp -= dealt
        if (hp <= 0) {
            dealt += hp
            faint(source, effect)
        }
        return dealt
    }

    fun tryTrap(isHidden: Boolean = false): Boolean {
        if (!runStatusImmunity("trapped")) return false
        if (Js.truthy(trapped) && isHidden) return true
        trapped = if (isHidden) "hidden" else true
        return true
    }

    fun hasMove(moveId: String): String? {
        var id = Js.toID(moveId)
        if (id.startsWith("hiddenpower")) id = "hiddenpower"
        return moveSlots.firstOrNull { it.id == id }?.id
    }

    fun disableMove(moveId: String, isHidden: Boolean = false, sourceEffect: EffectLike? = null) {
        val effect = sourceEffect ?: battle.effect
        val id = Js.toID(moveId)
        for (slot in moveSlots) {
            if (slot.id == id && slot.disabled != true) {
                slot.disabled = if (isHidden) "hidden" else true
                slot.disabledSource = effect?.name?.takeIf { it.isNotEmpty() } ?: slot.move
            }
        }
    }

    /** Returns the amount healed, or `false`. */
    fun heal(amount: Double, @Suppress("UNUSED_PARAMETER") source: Pokemon? = null, @Suppress("UNUSED_PARAMETER") effect: EffectLike? = null): Any {
        if (hp == 0) return false
        var d = Js.trunc(amount)
        if (amount.isNaN()) return false
        if (d <= 0) return false
        if (hp >= maxhp) return false
        hp += d
        if (hp > maxhp) {
            d -= hp - maxhp
            hp = maxhp
        }
        return d
    }

    fun sethp(amount: Double): Int {
        if (hp == 0) return 0
        var d = Js.trunc(amount)
        if (d < 1) d = 1
        d -= hp
        hp += d
        if (hp > maxhp) {
            d -= hp - maxhp
            hp = maxhp
        }
        return d
    }

    fun trySetStatus(status: String, source: Pokemon? = null, sourceEffect: EffectLike? = null): Any? =
        setStatus(this.status.ifEmpty { status }, source, sourceEffect)

    fun cureStatus(silent: Boolean = false): Boolean {
        if (hp == 0 || status.isEmpty()) return false
        battle.add("-curestatus", this, status, if (silent) "[silent]" else "[msg]")
        if (status == "slp" && removeVolatile("nightmare")) battle.add("-end", this, "Nightmare", "[silent]")
        setStatus("")
        return true
    }

    fun setStatus(statusName: String, sourceIn: Pokemon? = null, sourceEffectIn: EffectLike? = null, ignoreImmunities: Boolean = false): Any? {
        if (hp == 0) return false
        val status = battle.dex.condition(statusName)
        var source = sourceIn
        var sourceEffect = sourceEffectIn
        if (battle.event != null) {
            if (source == null) source = battle.event!!.source as? Pokemon
            if (sourceEffect == null) sourceEffect = battle.effect
        }
        if (source == null) source = this
        if (this.status == status.id) {
            val effectStatus = sourceEffect?.let { statusOf(it) }
            if (effectStatus == this.status) {
                battle.add("-fail", this, this.status)
            } else if (effectStatus != null) {
                battle.add("-fail", source)
                battle.attrLastMove("[still]")
            }
            return false
        }
        if (!ignoreImmunities && status.id.isNotEmpty() && !(source.hasAbility("corrosion") && status.id in listOf("tox", "psn"))) {
            if (!runStatusImmunity(if (status.id == "tox") "psn" else status.id)) {
                if (sourceEffect?.let { statusOf(it) } != null) battle.add("-immune", this)
                return false
            }
        }
        val prevStatus = this.status
        val prevStatusState = statusState
        if (status.id.isNotEmpty()) {
            val result = battle.runEvent("SetStatus", this, source, sourceEffect, status)
            if (!Js.truthy(result)) return result
        }
        this.status = status.id
        statusState = EffectState(status.id).also { it.target = this }
        statusState.source = source
        status.duration?.takeIf { it != 0 }?.let { statusState.duration = it }
        if (status.declares("durationCallback")) {
            statusState.duration = Js.int(battle.callback(status, "durationCallback", this, source, sourceEffect))
        }
        if (status.id.isNotEmpty() && !Js.truthy(battle.singleEvent("Start", status, statusState, this, source, sourceEffect))) {
            this.status = prevStatus
            statusState = prevStatusState
            return false
        }
        if (status.id.isNotEmpty() && !Js.truthy(battle.runEvent("AfterSetStatus", this, source, sourceEffect, status))) return false
        return true
    }

    private fun statusOf(effect: EffectLike): String? = when (effect) {
        is ActiveMove -> effect.status
        is Effect -> effect.string("status")
        else -> effect.data("status") as? String
    }

    fun updatePP() {
        battle.add("pp_update", "${side.id}: $uuid", moveSlots.joinToString(", ") { "${it.id}: ${it.pp}" })
    }

    fun clearStatus(): Boolean {
        if (hp == 0 || status.isEmpty()) return false
        if (status == "slp" && removeVolatile("nightmare")) battle.add("-end", this, "Nightmare", "[silent]")
        setStatus("")
        return true
    }

    fun getStatus(): Effect = battle.dex.conditionById(status)

    fun eatItem(force: Boolean = false, sourceIn: Pokemon? = null, sourceEffectIn: EffectLike? = null): Boolean {
        if (item.isEmpty() || Js.truthy(itemState["knockedOff"])) return false
        if ((hp == 0 && item != "jabocaberry" && item != "rowapberry") || !isActive) return false
        val sourceEffect = sourceEffectIn ?: battle.effect
        val source = sourceIn ?: battle.event?.target as? Pokemon
        val item = getItem()
        if (Js.truthy(battle.runEvent("UseItem", this, null, null, item)) &&
            (force || Js.truthy(battle.runEvent("TryEatItem", this, null, null, item)))) {
            battle.add("-enditem", this, item, "[eat]")
            battle.singleEvent("Eat", item, itemState, this, source, sourceEffect)
            battle.runEvent("EatItem", this, null, null, item)
            if (item.id in RESTORATIVE_BERRIES) {
                when (pendingStaleness) {
                    "internal" -> if (staleness != "external") staleness = "internal"
                    "external" -> staleness = "external"
                }
                pendingStaleness = null
            }
            lastItem = this.item
            this.item = ""
            itemState = EffectState("").also { it.target = this }
            usedItemThisTurn = true
            ateBerry = true
            battle.runEvent("AfterUseItem", this, null, null, item)
            return true
        }
        return false
    }

    fun useItem(sourceIn: Pokemon? = null, sourceEffectIn: EffectLike? = null): Boolean {
        if ((hp == 0 && !getItem().bool("isGem")) || !isActive) return false
        if (item.isEmpty() || Js.truthy(itemState["knockedOff"])) return false
        val sourceEffect = sourceEffectIn ?: battle.effect
        val source = sourceIn ?: battle.event?.target as? Pokemon
        val item = getItem()
        if (Js.truthy(battle.runEvent("UseItem", this, null, null, item))) {
            when {
                item.id == "redcard" -> battle.add("-enditem", this, item, "[of] " + Js.str(source))
                item.bool("isGem") -> battle.add("-enditem", this, item, "[from] gem")
                else -> battle.add("-enditem", this, item)
            }
            @Suppress("UNCHECKED_CAST")
            (item.map("boosts") as? Map<String, Any?>)?.let { b -> battle.boost(b.mapValues { Js.int(it.value) }, this, source, item) }
            battle.singleEvent("Use", item, itemState, this, source, sourceEffect)
            lastItem = this.item
            this.item = ""
            itemState = EffectState("").also { it.target = this }
            usedItemThisTurn = true
            battle.runEvent("AfterUseItem", this, null, null, item)
            return true
        }
        return false
    }

    fun takeItem(sourceIn: Pokemon? = null): Any {
        if (!isActive) return false
        if (item.isEmpty() || Js.truthy(itemState["knockedOff"])) return false
        val source = sourceIn ?: this
        val item = getItem()
        if (Js.truthy(battle.runEvent("TakeItem", this, source, null, item))) {
            this.item = ""
            val oldItemState = itemState
            itemState = EffectState("").also { it.target = this }
            pendingStaleness = null
            battle.singleEvent("End", item, oldItemState, this)
            battle.runEvent("AfterTakeItem", this, null, null, item)
            return item
        }
        return false
    }

    fun setItem(itemName: String, source: Pokemon? = null, effect: EffectLike? = null): Boolean {
        if (hp == 0 || !isActive) return false
        if (Js.truthy(itemState["knockedOff"])) return false
        val item = battle.dex.item(itemName)
        val effectId = battle.effect?.id ?: ""
        val inflicted = effectId in listOf("trick", "switcheroo")
        val external = inflicted && source != null && !source.isAlly(this)
        pendingStaleness = if (external) "external" else "internal"
        val oldItem = getItem()
        val oldItemState = itemState
        this.item = item.id
        itemState = EffectState(item.id).also { it.target = this }
        if (oldItem.exists) battle.singleEvent("End", oldItem, oldItemState, this)
        if (item.id.isNotEmpty()) battle.singleEvent("Start", item, itemState, this, source, effect)
        return true
    }

    fun getItem(): Effect = battle.dex.item(item)

    fun hasItem(item: String): Boolean {
        if (Js.toID(item) != this.item) return false
        return !ignoringItem()
    }

    fun hasItem(items: List<String>): Boolean {
        if (items.none { Js.toID(it) == this.item }) return false
        return !ignoringItem()
    }

    fun clearItem(): Boolean = setItem("")

    fun setAbility(abilityName: String, source: Pokemon? = null, isFromFormeChange: Boolean = false, isTransform: Boolean = false): Any? {
        if (hp == 0) return false
        val ability = battle.dex.ability(abilityName)
        val oldAbility = this.ability
        if (!isFromFormeChange) {
            if (ability.flag("cantsuppress") || getAbility().flag("cantsuppress")) return false
        }
        if (!isFromFormeChange && !isTransform) {
            val result = battle.runEvent("SetAbility", this, source, battle.effect, ability)
            if (!Js.truthy(result)) return result
        }
        battle.singleEvent("End", battle.dex.ability(oldAbility), abilityState, this, source)
        val current = battle.effect
        if (current != null && current.effectType == "Move" && !isFromFormeChange) {
            battle.add("-endability", this, battle.dex.ability(oldAbility), "[from] move: " + (battle.dex.move(current.id)?.name ?: current.name))
        }
        this.ability = ability.id
        abilityState = EffectState(ability.id).also { it.target = this }
        if (ability.id.isNotEmpty() && (!isTransform || oldAbility != ability.id)) {
            battle.singleEvent("Start", ability, abilityState, this, source)
        }
        abilityOrder = battle.abilityOrder++
        return oldAbility
    }

    fun getAbility(): Effect = battle.dex.ability(ability)

    fun hasAbility(ability: String): Boolean {
        if (Js.toID(ability) != this.ability) return false
        return !ignoringAbility()
    }

    fun hasAbility(abilities: List<String>): Boolean {
        if (abilities.none { Js.toID(it) == this.ability }) return false
        return !ignoringAbility()
    }

    fun clearAbility(): Any? = setAbility("")

    fun getNature() = battle.dex.nature(set.nature)

    fun addVolatile(statusName: String, sourceIn: Pokemon? = null, sourceEffectIn: EffectLike? = null, linkedStatus: String? = null): Any? {
        val status = battle.dex.condition(statusName)
        if (hp == 0 && !status.bool("affectsFainted")) return false
        if (linkedStatus != null && sourceIn != null && sourceIn.hp == 0) return false
        var source = sourceIn
        var sourceEffect = sourceEffectIn
        if (battle.event != null) {
            if (source == null) source = battle.event!!.source as? Pokemon
            if (sourceEffect == null) sourceEffect = battle.effect
        }
        if (source == null) source = this
        volatiles[status.id]?.let { existing ->
            if (!status.declares("onRestart")) return false
            return battle.singleEvent("Restart", status, existing, this, source, sourceEffect)
        }
        if (!runStatusImmunity(status.id)) {
            if (sourceEffect?.let { statusOf(it) } != null) battle.add("-immune", this)
            return false
        }
        val result = battle.runEvent("TryAddVolatile", this, source, sourceEffect, status)
        if (!Js.truthy(result)) return result
        val state = EffectState(status.id).also {
            it["name"] = status.name
            it.target = this
            it.source = source
            it.sourceSlot = source.getSlot()
            if (sourceEffect != null) it.sourceEffect = sourceEffect
            status.duration?.takeIf { d -> d != 0 }?.let { d -> it.duration = d }
        }
        volatiles[status.id] = state
        if (status.declares("durationCallback")) {
            state.duration = Js.int(battle.callback(status, "durationCallback", this, source, sourceEffect))
        }
        val started = battle.singleEvent("Start", status, state, this, source, sourceEffect)
        if (!Js.truthy(started)) {
            volatiles.remove(status.id)
            return started
        }
        if (linkedStatus != null) {
            val linkedId = Js.toID(linkedStatus)
            if (source.volatiles[linkedId] == null) {
                source.addVolatile(linkedStatus, this, sourceEffect)
                source.volatiles[linkedId]?.let {
                    it["linkedPokemon"] = mutableListOf(this)
                    it["linkedStatus"] = status.id
                }
            } else {
                @Suppress("UNCHECKED_CAST")
                (source.volatiles[linkedId]!!["linkedPokemon"] as MutableList<Pokemon>).add(this)
            }
            volatiles[status.id]?.let {
                it["linkedPokemon"] = mutableListOf(source)
                it["linkedStatus"] = linkedId
            }
        }
        return true
    }

    fun getVolatile(statusName: String): Effect? {
        val status = battle.dex.condition(statusName)
        return if (volatiles[status.id] != null) status else null
    }

    fun removeVolatile(statusName: String): Boolean {
        if (hp == 0) return false
        val status = battle.dex.condition(statusName)
        val state = volatiles[status.id] ?: return false
        battle.singleEvent("End", status, state, this)
        val linkedPokemon = state["linkedPokemon"]
        val linkedStatus = state["linkedStatus"]
        volatiles.remove(status.id)
        if (linkedPokemon != null) {
            @Suppress("UNCHECKED_CAST")
            removeLinkedVolatiles(linkedStatus, linkedPokemon as MutableList<Pokemon>)
        }
        return true
    }

    fun removeLinkedVolatiles(linkedStatus: Any?, linkedPokemon: MutableList<Pokemon>) {
        val status = Js.toID(linkedStatus)
        for (linkedPoke in linkedPokemon.toList()) {
            val data = linkedPoke.volatiles[status] ?: continue
            @Suppress("UNCHECKED_CAST")
            val list = data["linkedPokemon"] as MutableList<Pokemon>
            list.remove(this)
            if (list.isEmpty()) linkedPoke.removeVolatile(status)
        }
    }

    fun setType(newType: List<String>, enforce: Boolean = false): Boolean {
        if (!enforce) {
            if ("Stellar" in newType) return false
            if (species.num == 493 || species.num == 773) return false
            if (terastallized != null) return false
        }
        types = newType
        addedType = ""
        knownType = true
        apparentType = types.joinToString("/")
        return true
    }

    fun setType(newType: String, enforce: Boolean = false): Boolean = setType(listOf(newType), enforce)

    fun addType(newType: String): Boolean {
        if (terastallized != null) return false
        addedType = newType
        return true
    }

    fun getTypes(excludeAdded: Boolean = false, preterastallized: Boolean = false): List<String> {
        if (!preterastallized && terastallized != null && terastallized != "Stellar") return listOf(terastallized!!)
        @Suppress("UNCHECKED_CAST")
        val types = battle.runEvent("Type", this, null, null, this.types) as List<String>
        if (!excludeAdded && addedType.isNotEmpty()) return types + addedType
        if (types.isNotEmpty()) return types
        return listOf("Normal")
    }

    /** `true` grounded, `false` airborne, `null` airborne through Levitate. */
    fun isGrounded(negateImmunity: Boolean = false): Boolean? {
        if ("gravity" in battle.field.pseudoWeather) return true
        if ("ingrain" in volatiles) return true
        if ("smackdown" in volatiles) return true
        val item = if (ignoringItem()) "" else this.item
        if (item == "ironball") return true
        if (!negateImmunity && hasType("Flying") && !(hasType("???") && "roost" in volatiles)) return false
        if (hasAbility(listOf("levitate", "eelevate")) && !battle.suppressingAbility(this)) return null
        if ("magnetrise" in volatiles) return false
        if ("telekinesis" in volatiles) return false
        return item != "airballoon"
    }

    fun isSemiInvulnerable(): Boolean = volatiles["fly"] != null || volatiles["bounce"] != null || volatiles["dive"] != null ||
        volatiles["dig"] != null || volatiles["phantomforce"] != null || volatiles["shadowforce"] != null || isSkyDropped()

    fun isSkyDropped(): Boolean {
        if (volatiles["skydrop"] != null) return true
        for (foe in side.foe.active) {
            if (foe != null && foe.volatiles["skydrop"]?.source === this) return true
        }
        return false
    }

    fun isProtected(): Boolean = listOf("protect", "detect", "maxguard", "kingsshield", "spikyshield", "banefulbunker",
        "obstruct", "silktrap", "burningbulwark").any { volatiles[it] != null }

    /** Like `Field.effectiveWeather()`, but Utility Umbrella hides sun and rain, and Mega Sol forces sun. */
    fun effectiveWeather(sourceEffectIn: EffectLike? = null, message: Boolean = false): String {
        val sourceEffect = sourceEffectIn ?: battle.effect
        val weather = battle.field.effectiveWeather()
        if (battle.activePokemon?.hasAbility("megasol") == true && sourceEffect != null &&
            (sourceEffect.id == "megasol" || sourceEffect.effectType == "Move" || sourceEffect.effectType == "Weather") &&
            sourceEffect.id != "electroshot") {
            if (weather != "sunnyday" && message) battle.add("-activate", this, "ability: Mega Sol")
            return "sunnyday"
        }
        return when (weather) {
            "sunnyday", "raindance", "desolateland", "primordialsea" -> if (hasItem("utilityumbrella")) "" else weather
            else -> weather
        }
    }

    fun runEffectiveness(move: ActiveMove): Int {
        if (terastallized != null && move.type == "Stellar") return 1
        var total = 0
        for (type in getTypes()) {
            var typeMod: Any? = battle.dex.effectiveness(move.type, type)
            typeMod = battle.singleEvent("Effectiveness", move, null, this, type, move, typeMod)
            total += Js.int(battle.runEvent("Effectiveness", this, type, move, typeMod))
        }
        return total
    }

    /** `false` immune, `true` not immune. */
    fun runImmunity(type: String?, message: Boolean = false): Boolean {
        if (type == null || type.isEmpty() || type == "???") return true
        require(battle.dex.isTypeName(type)) { "Use runStatusImmunity for $type" }
        if (fainted) return false
        val negateImmunity = !Js.truthy(battle.runEvent("NegateImmunity", this, type))
        val notImmune: Boolean? = if (type == "Ground") isGrounded(negateImmunity)
            else negateImmunity || battle.dex.notImmune(type, getTypes())
        if (notImmune == true) return true
        if (!message) return false
        if (notImmune == null) battle.add("-immune", this, "[from] ability: Levitate") else battle.add("-immune", this)
        return false
    }

    fun runStatusImmunity(type: String?, message: Boolean = false): Boolean {
        if (fainted) return false
        if (type == null || type.isEmpty()) return true
        if (!battle.dex.notImmune(type, getTypes())) {
            if (message) battle.add("-immune", this)
            return false
        }
        val immunity = battle.runEvent("Immunity", this, null, null, type)
        if (!Js.truthy(immunity)) {
            if (message && immunity != null) battle.add("-immune", this)
            return false
        }
        return true
    }

    private fun hiddenPower(ivs: Map<String, Int>): Pair<String, Int> {
        val hpTypes = listOf("Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "Fire", "Water",
            "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark")
        var typeX = 0
        var i = 1
        for (s in listOf("hp", "atk", "def", "spe", "spa", "spd")) {
            typeX += i * ((ivs[s] ?: 31) % 2)
            i *= 2
        }
        return hpTypes[typeX * 15 / 63] to 60
    }

    companion object {
        val BOOST_TABLE = doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0)
        val STAT_EVENT = mapOf("atk" to "Atk", "def" to "Def", "spa" to "SpA", "spd" to "SpD", "spe" to "Spe")
        val RESTORATIVE_BERRIES = setOf("leppaberry", "aguavberry", "enigmaberry", "figyberry", "iapapaberry", "magoberry",
            "sitrusberry", "wikiberry", "oranberry")

        fun freshBoosts(): LinkedHashMap<String, Int> =
            linkedMapOf("atk" to 0, "def" to 0, "spa" to 0, "spd" to 0, "spe" to 0, "accuracy" to 0, "evasion" to 0)
    }
}
