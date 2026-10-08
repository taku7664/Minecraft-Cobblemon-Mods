package jbro.cobblemon.mcc.betterai.engine.sim

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Port of `sim/battle-actions.js` as the dev server runs it (Mega Showdown's STAB and gen 9 Dynamax
 * changes included). Z-Moves are out of scope for the engine.
 */
class BattleActions(private val battle: Battle) {
    private val dex get() = battle.dex

    // region Switching

    fun switchIn(pokemon: Pokemon?, pos: Int, sourceEffect: EffectLike? = null, isDrag: Boolean = false): Any {
        if (pokemon == null || pokemon.isActive) {
            battle.hint("A switch failed because the Pokemon trying to switch in is already in.")
            return false
        }
        val side = pokemon.side
        require(pos < side.active.size) { "Invalid switch position $pos / ${side.active.size}" }
        val oldActive = side.active[pos]
        val unfaintedActive = if (oldActive != null && oldActive.hp != 0) oldActive else null
        if (unfaintedActive != null) {
            val old = unfaintedActive
            old.beingCalledBack = true
            var switchCopyFlag: Any? = null
            val selfSwitch = (sourceEffect as? ActiveMove)?.selfSwitch ?: (sourceEffect as? Effect)?.data("selfSwitch")
            if (selfSwitch is String) switchCopyFlag = selfSwitch
            if (!old.skipBeforeSwitchOutEventFlag && !isDrag) {
                battle.runEvent("BeforeSwitchOut", old)
                battle.eachEvent("Update")
            }
            old.skipBeforeSwitchOutEventFlag = false
            if (!Js.truthy(battle.runEvent("SwitchOut", old))) return false
            if (old.hp == 0) return "pursuitfaint"
            old.illusion = null
            battle.singleEvent("End", old.getAbility(), old.abilityState, old)
            battle.queue.cancelAction(old)
            if (switchCopyFlag != null) pokemon.copyVolatileFrom(old, switchCopyFlag)
            old.clearVolatile()
        }
        if (oldActive != null) {
            oldActive.isActive = false
            oldActive.isStarted = false
            oldActive.usedItemThisTurn = false
            oldActive.statsRaisedThisTurn = false
            oldActive.statsLoweredThisTurn = false
            oldActive.position = pokemon.position
            pokemon.position = pos
            side.pokemon[pokemon.position] = pokemon
            side.pokemon[oldActive.position] = oldActive
        }
        pokemon.isActive = true
        side.active[pos] = pokemon
        pokemon.activeTurns = 0
        pokemon.activeMoveActions = 0
        for (slot in pokemon.moveSlots) slot.used = false
        battle.runEvent("BeforeSwitchIn", pokemon)
        val optionals = listOfNotNull(sourceEffect?.let { "[from] ${it.name}" }, pokemon.illusion?.let { "[is] $it" })
        battle.add(if (isDrag) "drag" else "switch", pokemon, pokemon.getDetails, *optionals.toTypedArray())
        pokemon.abilityOrder = battle.abilityOrder++
        pokemon.previouslySwitchedIn++
        if (isDrag) {
            battle.singleEvent("PreStart", pokemon.getAbility(), pokemon.abilityState, pokemon)
            runSwitch(pokemon)
        } else {
            battle.queue.insertChoice(Action("runUnnerve", pokemon = pokemon))
            battle.queue.insertChoice(Action("runSwitch", pokemon = pokemon))
        }
        return true
    }

    fun dragIn(side: Side, pos: Int): Boolean {
        val pokemon = battle.getRandomSwitchable(side) ?: return false
        if (pokemon.isActive) return false
        val oldActive = side.active[pos] ?: error("nothing to drag out")
        if (oldActive.hp == 0) return false
        if (!Js.truthy(battle.runEvent("DragOut", oldActive))) return false
        return switchIn(pokemon, pos, null, true) == true
    }

    fun runSwitch(pokemon: Pokemon): Boolean {
        battle.runEvent("Swap", pokemon)
        battle.runEvent("SwitchIn", pokemon)
        battle.runEvent("EntryHazard", pokemon)
        if (pokemon.hp == 0) return false
        pokemon.isStarted = true
        if (!pokemon.fainted) {
            battle.singleEvent("Start", pokemon.getAbility(), pokemon.abilityState, pokemon)
            battle.singleEvent("Start", pokemon.getItem(), pokemon.itemState, pokemon)
        }
        pokemon.draggedIn = null
        return true
    }

    // endregion
    // region Moves

    /**
     * The "outside" move caller: PP, flinching, full paralysis and everything up to "POKEMON used MOVE".
     * [externalMove] skips LockMove and PP deduction (Dancer).
     */
    fun runMove(moveIn: ActiveMove, pokemon: Pokemon, targetLoc: Int, sourceEffectIn: EffectLike? = null, maxMove: String? = null,
                originalTarget: Pokemon? = null, externalMove: Boolean = false) {
        var sourceEffect = sourceEffectIn
        pokemon.activeMoveActions++
        val targetMove = maxMove?.let { dex.activeMove(it) } ?: moveIn
        var target = battle.getTarget(pokemon, targetMove, targetLoc, originalTarget)
        var baseMove = moveIn
        val pranksterBoosted = baseMove.pranksterBoosted
        if (baseMove.id != "struggle" && maxMove == null && !externalMove) {
            val changedMove = battle.runEvent("OverrideAction", pokemon, target, baseMove)
            if (Js.truthy(changedMove) && changedMove != true) {
                baseMove = dex.activeMove(changedMove as String)
                if (pranksterBoosted) baseMove.pranksterBoosted = true
                target = battle.getRandomTarget(pokemon, baseMove)
            }
        }
        var move = baseMove
        if (maxMove != null) move = getActiveMaxMove(baseMove, pokemon)
        move.isExternal = externalMove
        battle.setActiveMove(move, pokemon, target)
        val willTryMove = battle.runEvent("BeforeMove", pokemon, target, move)
        if (!Js.truthy(willTryMove)) {
            battle.runEvent("MoveAborted", pokemon, target, move)
            battle.clearActiveMove(true)
            pokemon.moveThisTurnResult = willTryMove
            return
        }
        if (move.flag("cantusetwice") && pokemon.lastMove?.id == move.id) pokemon.addVolatile(move.id)
        if (move.declares("beforeMoveCallback")) {
            if (Js.truthy(battle.callback(move, "beforeMoveCallback", pokemon, target, move))) {
                battle.clearActiveMove(true)
                pokemon.moveThisTurnResult = false
                return
            }
        }
        pokemon.lastDamage = 0
        if (!externalMove) {
            var lockedMove = battle.runEvent("LockMove", pokemon)
            if (lockedMove == true) lockedMove = false
            if (!Js.truthy(lockedMove)) {
                if (pokemon.deductPP(baseMove.id) == 0 && move.id != "struggle") {
                    battle.add("cant", pokemon, "nopp", move)
                    battle.clearActiveMove(true)
                    pokemon.moveThisTurnResult = false
                    return
                }
            } else {
                sourceEffect = dex.condition("lockedmove")
            }
            pokemon.moveUsed(move, targetLoc)
        }
        val noLock = externalMove && pokemon.volatiles["lockedmove"] == null
        val moveDidSomething = useMove(baseMove, pokemon, target, sourceEffect, maxMove)
        battle.lastSuccessfulMoveThisTurn = if (Js.truthy(moveDidSomething)) battle.activeMove?.id else null
        battle.activeMove?.let { move = it }
        battle.singleEvent("AfterMove", move, null, pokemon, target, move)
        battle.runEvent("AfterMove", pokemon, target, move)
        if (move.flag("cantusetwice") && pokemon.removeVolatile(move.id)) {
            battle.add("-hint", "Some effects can force a Pokemon to use ${move.name} again in a row.")
        }
        if (move.flag("dance") && Js.truthy(moveDidSomething) && !move.isExternal) {
            val dancers = battle.getAllActive().filter { it !== pokemon && it.hasAbility("dancer") && !it.isSemiInvulnerable() }
                .sortedWith { a, b ->
                    val bySpeed = -(b.storedStats.getValue("spe") - a.storedStats.getValue("spe"))
                    if (bySpeed != 0) bySpeed else b.abilityOrder - a.abilityOrder
                }
            val targetOf1stDance = battle.activeTarget
            for (dancer in dancers) {
                if (battle.faintMessages()) break
                if (dancer.fainted) continue
                battle.add("-activate", dancer, "ability: Dancer")
                val dancersTarget = if (targetOf1stDance != null && !targetOf1stDance.isAlly(dancer) && pokemon.isAlly(dancer)) targetOf1stDance else pokemon
                runMove(dex.activeMove(move.id), dancer, dancer.getLocOf(dancersTarget), dex.ability("dancer"), externalMove = true)
            }
        }
        if (noLock && pokemon.volatiles["lockedmove"] != null) pokemon.volatiles.remove("lockedmove")
        battle.faintMessages()
        battle.checkWin()
    }

    /** The "inside" move caller: the move's effects, not the act of choosing it (Sleep Talk, Magic Bounce ...). */
    /** [targetUndefined]: the caller gave no target (JS `undefined`), so one is picked at random after ModifyTarget. */
    fun useMove(move: ActiveMove, pokemon: Pokemon, target: Pokemon?, sourceEffect: EffectLike? = null, maxMove: String? = null,
                targetUndefined: Boolean = false): Any? {
        pokemon.moveThisTurnResult = Unit
        val oldMoveResult = pokemon.moveThisTurnResult
        val moveResult = useMoveInner(move, pokemon, target, sourceEffect, maxMove, targetUndefined)
        if (oldMoveResult === pokemon.moveThisTurnResult) pokemon.moveThisTurnResult = moveResult
        return moveResult
    }

    fun useMoveInner(moveIn: ActiveMove, pokemon: Pokemon, targetIn: Pokemon?, sourceEffectIn: EffectLike? = null, maxMove: String? = null,
                     targetUndefined: Boolean = false): Any? {
        var target = targetIn
        var sourceEffect = sourceEffectIn
        if (sourceEffect == null && battle.effect?.id?.isNotEmpty() == true) sourceEffect = battle.effect
        if (sourceEffect != null && sourceEffect.id in listOf("instruct", "custapberry")) sourceEffect = null
        var move = moveIn
        pokemon.lastMoveUsed = move
        if (maxMove != null && move.category != "Status") {
            battle.singleEvent("ModifyType", move, null, pokemon, target, move, move)
            battle.runEvent("ModifyType", pokemon, target, move, move)
        }
        if (maxMove != null || (move.category != "Status" && sourceEffect != null && Js.truthy(sourceEffect.data("isMax")))) {
            move = getActiveMaxMove(move, pokemon)
        }
        battle.activeMove?.let { active ->
            move.priority = active.priority
            if (!move.hasBounced) move.pranksterBoosted = active.pranksterBoosted
        }
        val baseTarget = move.target
        val targetRelay = linkedMapOf<String, Any?>("target" to if (targetUndefined && target == null) Unit else target)
        @Suppress("UNCHECKED_CAST")
        val modifiedTarget = battle.runEvent("ModifyTarget", pokemon, target, move, targetRelay, true) as? Map<String, Any?>
        if (modifiedTarget != null && modifiedTarget.containsKey("target") && modifiedTarget["target"] !== Unit) {
            target = modifiedTarget["target"] as? Pokemon
        }
        if (target == null && modifiedTarget?.get("target") === Unit) target = battle.getRandomTarget(pokemon, move)
        if (move.target == "self" || move.target == "allies") target = pokemon
        if (sourceEffect != null) {
            move.sourceEffect = sourceEffect.id
            move.ignoreAbility = Js.truthy(sourceEffect.data("ignoreAbility"))
        }
        var moveResult: Any? = false
        battle.setActiveMove(move, pokemon, target)
        battle.singleEvent("ModifyType", move, null, pokemon, target, move, move)
        battle.singleEvent("ModifyMove", move, null, pokemon, target, move, move)
        if (baseTarget != move.target) target = battle.getRandomTarget(pokemon, move)
        move = battle.runEvent("ModifyType", pokemon, target, move, move) as? ActiveMove ?: move
        val modified = battle.runEvent("ModifyMove", pokemon, target, move, move)
        if (modified == false) {
            // Throat Chop and Gravity cancel the move this way. Showdown still compares `false.target` with the
            // old target and asks for a random target for the empty move `false` resolves to (a roll in doubles).
            battle.getRandomTarget(pokemon, dex.activeMove(""))
            return false
        }
        move = modified as? ActiveMove ?: move
        if (baseTarget != move.target) target = battle.getRandomTarget(pokemon, move)
        if (pokemon.fainted) return false
        var attrs = ""
        val movename = if (move.id == "hiddenpower") "Hidden Power" else move.name
        if (sourceEffect != null) attrs += "|[from]${sourceEffect.fullname}"
        battle.addMove("move", pokemon, movename, Js.str(target?.toString() ?: "null") + attrs)
        if (target == null) {
            battle.attrLastMove("[notarget]")
            battle.add("-fail", pokemon)
            return false
        }
        val moveTargets = pokemon.getMoveTargets(move, target)
        val targets = moveTargets.targets
        if (targets.isNotEmpty()) target = targets.last()
        val callerMoveForPressure = if (sourceEffect != null && Js.truthy(sourceEffect.data("pp"))) sourceEffect else null
        if (sourceEffect == null || callerMoveForPressure != null || sourceEffect.id == "pursuit") {
            var extraPP = 0
            for (source in moveTargets.pressureTargets) {
                val ppDrop = battle.runEvent("DeductPP", source, pokemon, move)
                if (ppDrop != true) extraPP += Js.int(ppDrop)
            }
            if (extraPP > 0) pokemon.deductPP(callerMoveForPressure?.id ?: moveIn.id, extraPP)
        }
        if (!Js.truthy(battle.singleEvent("TryMove", move, null, pokemon, target, move)) ||
            !Js.truthy(battle.runEvent("TryMove", pokemon, target, move))) {
            move.mindBlownRecoil = false
            return false
        }
        battle.singleEvent("UseMoveMessage", move, null, pokemon, target, move)
        if (move.ignoreImmunity == null) move.ignoreImmunity = move.category == "Status"
        if (move.selfdestruct == "always") battle.faint(pokemon, pokemon, move)
        val damage: Any?
        if (move.target in listOf("all", "foeSide", "allySide", "allyTeam")) {
            damage = tryMoveHit(targets, pokemon, move)
            if (damage == NOT_FAIL) pokemon.moveThisTurnResult = null
            if (Js.truthy(damage) || damage == 0 || damage === Unit) moveResult = true
        } else {
            if (targets.isEmpty()) {
                battle.attrLastMove("[notarget]")
                battle.add("-fail", pokemon)
                return false
            }
            moveResult = trySpreadMoveHit(targets.toMutableList(), pokemon, move)
        }
        if (move.selfBoost != null && Js.truthy(moveResult)) {
            moveHit(listOf(pokemon), pokemon, move, SelfBoostHit(move), false, true)
        }
        if (pokemon.hp == 0) battle.faint(pokemon, pokemon, move)
        if (!Js.truthy(moveResult)) {
            battle.singleEvent("MoveFail", move, null, target, pokemon, move)
            return false
        }
        if (!move.negateSecondary && !(move.hasSheerForce && pokemon.hasAbility("sheerforce")) && !move.flag("futuremove")) {
            val originalHp = pokemon.hp
            battle.singleEvent("AfterMoveSecondarySelf", move, null, pokemon, target, move)
            battle.runEvent("AfterMoveSecondarySelf", pokemon, target, move)
            if (pokemon !== target && move.category != "Status") {
                if (pokemon.hp <= pokemon.maxhp / 2.0 && originalHp > pokemon.maxhp / 2.0) battle.runEvent("EmergencyExit", pokemon, pokemon)
            }
        }
        return true
    }

    /** Wraps `move.selfBoost` so `moveHit` can apply it like a `self` block. */
    private class SelfBoostHit(private val move: ActiveMove) : HitData by move {
        override val hitBoosts: Map<String, Int>? get() = move.selfBoost
        override val hitSelf: HitData? get() = null
        override val hitSecondaries: List<HitData>? get() = null
        override val hitStatus: String? get() = null
        override val hitVolatileStatus: String? get() = null
        override val hitHeal: IntArray? get() = null
        override val hitSideCondition: String? get() = null
        override val hitSlotCondition: String? get() = null
        override val hitWeather: String? get() = null
        override val hitTerrain: String? get() = null
        override val hitPseudoWeather: String? get() = null
        override val hitForceSwitch: Boolean get() = false
        override val hitSelfdestruct: String? get() = null
        override val hitSelfSwitch: Any? get() = null
        override fun handler(callbackName: String): Any? = null
        override fun declares(callbackName: String): Boolean = false
    }

    /** Includes single-target moves. */
    fun trySpreadMoveHit(targetsIn: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove, notActive: Boolean = false): Boolean {
        var targets: MutableList<Pokemon> = targetsIn
        if (targets.size > 1 && move.smartTarget != true) move.spreadHit = true
        if (notActive) battle.setActiveMove(move, pokemon, targets[0])
        val hitResult = battle.singleEvent("Try", move, null, pokemon, targets[0], move).let { first ->
            if (!Js.truthy(first)) first else battle.singleEvent("PrepareHit", move, EffectState(""), targets[0], pokemon, move).let { second ->
                if (!Js.truthy(second)) second else battle.runEvent("PrepareHit", pokemon, targets[0], move)
            }
        }
        if (!Js.truthy(hitResult)) {
            if (hitResult == false) {
                battle.add("-fail", pokemon)
                battle.attrLastMove("[still]")
            }
            return hitResult == NOT_FAIL
        }
        var atLeastOneFailure = false
        val steps: List<(MutableList<Pokemon>, Pokemon, ActiveMove) -> List<Any?>?> = listOf(
            ::hitStepInvulnerabilityEvent, ::hitStepTryHitEvent, ::hitStepTypeImmunity, ::hitStepTryImmunity,
            ::hitStepAccuracy, ::hitStepBreakProtect, ::hitStepStealBoosts, ::hitStepMoveHitLoop,
        )
        for (step in steps) {
            val hitResults = step(targets, pokemon, move) ?: continue
            targets = targets.filterIndexed { i, _ -> Js.truthy(hitResults.getOrNull(i)) || hitResults.getOrNull(i) == 0 }.toMutableList()
            atLeastOneFailure = atLeastOneFailure || hitResults.any { it == false }
            if (move.smartTarget == true && atLeastOneFailure) move.smartTarget = false
            if (targets.isEmpty()) break
        }
        val moveResult = targets.isNotEmpty()
        if (!moveResult && !atLeastOneFailure) pokemon.moveThisTurnResult = null
        val hitSlot = targets.map { it.getSlot() }
        if (move.spreadHit) battle.attrLastMove("[spread] " + hitSlot.joinToString(","))
        return moveResult
    }

    fun hitStepInvulnerabilityEvent(targets: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove): List<Any?> {
        if (move.id == "helpinghand") return List(targets.size) { true }
        val hitResults = ArrayList<Any?>()
        for (target in targets) {
            val result: Any? = when {
                target.volatiles["commanding"] != null -> false
                move.id == "toxic" && pokemon.hasType("Poison") -> true
                else -> battle.runEvent("Invulnerability", target, pokemon, move)
            }
            hitResults.add(result)
            if (result == false) {
                if (move.smartTarget == true) {
                    move.smartTarget = false
                } else {
                    if (!move.spreadHit) battle.attrLastMove("[miss]")
                    battle.add("-miss", pokemon, target)
                }
            }
        }
        return hitResults
    }

    fun hitStepTryHitEvent(targets: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        val hitResults = (battle.runEvent("TryHit", targets.toList(), pokemon, move) as List<Any?>).toMutableList()
        if (true !in hitResults && false in hitResults) {
            battle.add("-fail", pokemon)
            battle.attrLastMove("[still]")
        }
        for (i in targets.indices) {
            if (hitResults[i] != NOT_FAIL) hitResults[i] = if (Js.truthy(hitResults[i])) hitResults[i] else false
        }
        return hitResults
    }

    fun hitStepTypeImmunity(targets: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove): List<Any?> {
        if (move.ignoreImmunity == null) move.ignoreImmunity = move.category == "Status"
        return targets.map { target ->
            val ignore = move.ignoreImmunity
            (Js.truthy(ignore) && (ignore == true || (ignore as? Map<*, *>)?.get(move.type) == true)) ||
                target.runImmunity(move.type, move.smartTarget != true)
        }
    }

    fun hitStepTryImmunity(targets: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove): List<Any?> {
        val hitResults = ArrayList<Any?>()
        for (target in targets) {
            if (move.flag("powder") && target !== pokemon && !dex.notImmune("powder", target.getTypes())) {
                battle.add("-immune", target)
                hitResults.add(false)
            } else if (!Js.truthy(battle.singleEvent("TryImmunity", move, EffectState(""), target, pokemon, move))) {
                battle.add("-immune", target)
                hitResults.add(false)
            } else if (move.pranksterBoosted && pokemon.hasAbility("prankster") && !target.isAlly(pokemon) &&
                !dex.notImmune("prankster", target.getTypes())) {
                if (target.illusion != null || !(move.status != null && !dex.notImmune(move.status!!, target.getTypes()))) {
                    battle.hint("Since gen 7, Dark is immune to Prankster moves.")
                }
                battle.add("-immune", target)
                hitResults.add(false)
            } else {
                hitResults.add(true)
            }
        }
        return hitResults
    }

    fun hitStepAccuracy(targets: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove): List<Any?> {
        val hitResults = ArrayList<Any?>()
        for (target in targets) {
            battle.activeTarget = target
            var accuracy: Any? = move.accuracy
            val ohko = move.ohko
            if (Js.truthy(ohko)) {
                if (!target.isSemiInvulnerable()) {
                    var acc = 30
                    if (ohko == "Ice" && !pokemon.hasType("Ice")) acc = 20
                    if (target.volatiles["dynamax"] == null && pokemon.level >= target.level &&
                        (ohko == true || !target.hasType(ohko as String))) {
                        acc += pokemon.level - target.level
                        accuracy = acc
                    } else {
                        battle.add("-immune", target, "[ohko]")
                        hitResults.add(false)
                        continue
                    }
                }
            } else {
                accuracy = battle.runEvent("ModifyAccuracy", target, pokemon, move, accuracy)
                if (accuracy != true) {
                    var boost = 0
                    if (!move.ignoreAccuracy) {
                        @Suppress("UNCHECKED_CAST")
                        val boosts = battle.runEvent("ModifyBoost", pokemon, null, null, LinkedHashMap(pokemon.boosts)) as Map<String, Int>
                        boost = (boosts["accuracy"] ?: 0).coerceIn(-6, 6)
                    }
                    if (!move.ignoreEvasion) {
                        @Suppress("UNCHECKED_CAST")
                        val boosts = battle.runEvent("ModifyBoost", target, null, null, LinkedHashMap(target.boosts)) as Map<String, Int>
                        boost = (boost - (boosts["evasion"] ?: 0)).coerceIn(-6, 6)
                    }
                    val acc = Js.num(accuracy)
                    if (boost > 0) accuracy = Js.trunc(acc * (3 + boost) / 3)
                    else if (boost < 0) accuracy = Js.trunc(acc * 3 / (3 - boost))
                }
            }
            accuracy = if (move.alwaysHit || (move.id == "toxic" && pokemon.hasType("Poison")) ||
                (move.target == "self" && move.category == "Status" && !target.isSemiInvulnerable())) {
                true
            } else {
                battle.runEvent("Accuracy", target, pokemon, move, accuracy)
            }
            if (accuracy != true && !battle.randomChance(Js.num(accuracy), 100)) {
                if (move.smartTarget == true) {
                    move.smartTarget = false
                } else {
                    if (!move.spreadHit) battle.attrLastMove("[miss]")
                    battle.add("-miss", pokemon, target)
                }
                if (!Js.truthy(move.ohko) && pokemon.hasItem("blunderpolicy") && pokemon.useItem()) {
                    battle.boost(mapOf("spe" to 2), pokemon)
                }
                hitResults.add(false)
                continue
            }
            hitResults.add(true)
        }
        return hitResults
    }

    fun hitStepBreakProtect(targets: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove): List<Any?>? {
        if (move.breaksProtect) {
            for (target in targets) {
                var broke = false
                for (effectid in listOf("banefulbunker", "burningbulwark", "kingsshield", "obstruct", "protect", "silktrap", "spikyshield")) {
                    if (target.removeVolatile(effectid)) broke = true
                }
                for (effectid in listOf("craftyshield", "matblock", "quickguard", "wideguard")) {
                    if (target.side.removeSideCondition(effectid)) broke = true
                }
                if (broke) {
                    if (move.id == "feint") battle.add("-activate", target, "move: Feint")
                    else battle.add("-activate", target, "move: " + move.name, "[broken]")
                    target.volatiles.remove("stall")
                }
            }
        }
        return null
    }

    fun hitStepStealBoosts(targets: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove): List<Any?>? {
        val target = targets[0]
        if (move.stealsBoosts) {
            val boosts = LinkedHashMap<String, Int>()
            var stolen = false
            for ((statName, stage) in target.boosts) {
                if (stage > 0) {
                    boosts[statName] = stage
                    stolen = true
                }
            }
            if (stolen) {
                battle.attrLastMove("[still]")
                battle.add("-clearpositiveboost", target, pokemon, "move: " + move.name)
                battle.boost(boosts, pokemon, pokemon)
                for (statName in boosts.keys) boosts[statName] = 0
                target.setBoost(boosts)
                battle.addMove("-anim", pokemon, "Spectral Thief", target)
            }
        }
        return null
    }

    fun afterMoveSecondaryEvent(targets: List<Pokemon>, pokemon: Pokemon, move: ActiveMove) {
        if (!move.negateSecondary && !(move.hasSheerForce && pokemon.hasAbility("sheerforce"))) {
            battle.singleEvent("AfterMoveSecondary", move, null, targets.firstOrNull(), pokemon, move)
            battle.runEvent("AfterMoveSecondary", targets, pokemon, move)
        }
    }

    /** For moves that target sides or the field rather than Pokemon. */
    fun tryMoveHit(targets: List<Pokemon>, pokemon: Pokemon, move: ActiveMove): Any? {
        val target = targets[0]
        battle.setActiveMove(move, pokemon, target)
        var hitResult = battle.singleEvent("Try", move, null, pokemon, target, move).let { first ->
            if (!Js.truthy(first)) first else battle.singleEvent("PrepareHit", move, EffectState(""), target, pokemon, move).let { second ->
                if (!Js.truthy(second)) second else battle.runEvent("PrepareHit", pokemon, target, move)
            }
        }
        if (!Js.truthy(hitResult)) {
            if (hitResult == false) {
                battle.add("-fail", pokemon)
                battle.attrLastMove("[still]")
            }
            return false
        }
        hitResult = if (move.target == "all") battle.runEvent("TryHitField", target, pokemon, move)
        else battle.runEvent("TryHitSide", target, pokemon, move)
        if (!Js.truthy(hitResult)) {
            if (hitResult == false) {
                battle.add("-fail", pokemon)
                battle.attrLastMove("[still]")
            }
            return false
        }
        return moveHit(listOf(target), pokemon, move)
    }

    fun hitStepMoveHitLoop(targets: MutableList<Pokemon>, pokemon: Pokemon, move: ActiveMove): List<Any?> {
        var damage: MutableList<Any?> = MutableList(targets.size) { 0 }
        move.totalDamage = 0
        pokemon.lastDamage = 0
        var targetHits: Int
        val multihit = move.multihit
        targetHits = when (multihit) {
            is List<*> -> {
                val lo = Js.int(multihit[0])
                val hi = Js.int(multihit[1])
                if (lo == 2 && hi == 5) {
                    var hits = battle.sample(listOf(2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 5, 5, 5))
                    if (hits < 4 && pokemon.hasItem("loadeddice")) hits = 5 - battle.random(2)
                    hits
                } else {
                    battle.random(lo, hi + 1)
                }
            }
            null -> 1
            else -> Js.int(multihit).takeIf { it != 0 } ?: 1
        }
        if (targetHits == 10 && pokemon.hasItem("loadeddice")) targetHits -= battle.random(7)
        var nullDamage = true
        var moveDamage: MutableList<Any?> = ArrayList()
        val isSleepUsable = move.sleepUsable || (dex.move(move.sourceEffect)?.sleepUsable == true)
        var targetsCopy: MutableList<Pokemon?> = ArrayList(targets)
        var hit = 1
        while (hit <= targetHits) {
            if (damage.any { it == false }) break
            if (hit > 1 && pokemon.status == "slp" && !isSleepUsable) break
            if (targets.all { it.hp == 0 }) break
            move.hit = hit
            if (move.smartTarget == true && targets.size > 1) {
                targetsCopy = mutableListOf(targets[hit - 1])
                damage = mutableListOf(damage[hit - 1])
            } else {
                targetsCopy = ArrayList(targets)
            }
            val target = targetsCopy[0]
            if (target != null && move.smartTarget != null) {
                if (hit > 1) battle.addMove("-anim", pokemon, move.name, target) else battle.retargetLastMove(target)
            }
            if (target != null && move.multiaccuracy && hit > 1) {
                var accuracy: Any? = move.accuracy
                val boostTable = doubleArrayOf(1.0, 4.0 / 3, 5.0 / 3, 2.0, 7.0 / 3, 8.0 / 3, 3.0)
                if (accuracy != true) {
                    var acc = Js.num(accuracy)
                    if (!move.ignoreAccuracy) {
                        @Suppress("UNCHECKED_CAST")
                        val boosts = battle.runEvent("ModifyBoost", pokemon, null, null, LinkedHashMap(pokemon.boosts)) as Map<String, Int>
                        val boost = (boosts["accuracy"] ?: 0).coerceIn(-6, 6)
                        acc = if (boost > 0) acc * boostTable[boost] else acc / boostTable[-boost]
                    }
                    if (!move.ignoreEvasion) {
                        @Suppress("UNCHECKED_CAST")
                        val boosts = battle.runEvent("ModifyBoost", target, null, null, LinkedHashMap(target.boosts)) as Map<String, Int>
                        val boost = (boosts["evasion"] ?: 0).coerceIn(-6, 6)
                        if (boost > 0) acc /= boostTable[boost] else if (boost < 0) acc *= boostTable[-boost]
                    }
                    accuracy = Js.number(acc)
                }
                accuracy = battle.runEvent("ModifyAccuracy", target, pokemon, move, accuracy)
                if (!move.alwaysHit) {
                    accuracy = battle.runEvent("Accuracy", target, pokemon, move, accuracy)
                    if (accuracy != true && !battle.randomChance(Js.num(accuracy), 100)) break
                }
            }
            val (moveDamageThisHit, newTargets) = spreadMoveHit(targetsCopy, pokemon, move, move)
            targetsCopy = newTargets
            if (move.smartTarget == true) moveDamage.addAll(moveDamageThisHit) else moveDamage = moveDamageThisHit
            if (moveDamage.none { it != false }) break
            nullDamage = false
            for ((i, md) in moveDamage.withIndex()) {
                if (move.smartTarget == true && i != hit - 1) continue
                val value = if (md == true || !Js.truthy(md)) 0 else Js.int(md)
                if (i < damage.size) damage[i] = value else damage.add(value)
                move.totalDamage += value
            }
            if (move.mindBlownRecoil) {
                val hpBeforeRecoil = pokemon.hp
                battle.damage(Math.round(pokemon.maxhp / 2.0), pokemon, pokemon, dex.condition(move.id), true)
                move.mindBlownRecoil = false
                if (pokemon.hp <= pokemon.maxhp / 2.0 && hpBeforeRecoil > pokemon.maxhp / 2.0) battle.runEvent("EmergencyExit", pokemon, pokemon)
            }
            battle.eachEvent("Update")
            if (pokemon.hp == 0 && targets.size == 1) {
                hit++
                break
            }
            hit++
        }
        if (hit == 1) return MutableList(damage.size) { false }
        if (nullDamage) damage = MutableList(damage.size) { false }
        battle.faintMessages(false, false, pokemon.hp == 0)
        if (move.multihit != null && move.smartTarget == null) battle.add("-hitcount", targets[0], hit - 1)
        if ((move.recoil != null || move.id == "chloroblast") && move.totalDamage != 0) {
            val hpBeforeRecoil = pokemon.hp
            battle.damage(calcRecoilDamage(move.totalDamage, move, pokemon), pokemon, pokemon, dex.conditionById("recoil"))
            if (pokemon.hp <= pokemon.maxhp / 2.0 && hpBeforeRecoil > pokemon.maxhp / 2.0) battle.runEvent("EmergencyExit", pokemon, pokemon)
        }
        if (move.struggleRecoil) {
            val hpBeforeRecoil = pokemon.hp
            val recoilDamage = maxOf(Math.round(pokemon.baseMaxhp / 4.0).toInt(), 1)
            battle.directDamage(recoilDamage, pokemon, pokemon, STRUGGLE_RECOIL)
            if (pokemon.hp <= pokemon.maxhp / 2.0 && hpBeforeRecoil > pokemon.maxhp / 2.0) battle.runEvent("EmergencyExit", pokemon, pokemon)
        }
        if (move.smartTarget == true) targetsCopy = ArrayList(targets)
        for ((i, target) in targetsCopy.withIndex()) {
            if (target != null && pokemon !== target) {
                target.gotAttacked(move, moveDamage.getOrNull(i), pokemon)
                if (Js.isNumber(moveDamage.getOrNull(i))) target.timesAttacked += if (move.smartTarget == true) 1 else hit - 1
            }
        }
        if (Js.truthy(move.ohko) && targets[0].hp == 0) battle.add("-ohko")
        if (damage.none { Js.truthy(it) || it == 0 }) return damage
        battle.eachEvent("Update")
        afterMoveSecondaryEvent(targetsCopy.filterNotNull(), pokemon, move)
        if (!move.negateSecondary && !(move.hasSheerForce && pokemon.hasAbility("sheerforce"))) {
            for ((i, d) in damage.withIndex()) {
                val curDamage = if (targets.size == 1) move.totalDamage else d
                if (Js.isNumber(curDamage) && targets[i].hp != 0) {
                    val targetHPBeforeDamage = (targets[i].hurtThisTurn ?: 0) + Js.int(curDamage)
                    if (targets[i].hp <= targets[i].maxhp / 2.0 && targetHPBeforeDamage > targets[i].maxhp / 2.0) {
                        battle.runEvent("EmergencyExit", targets[i], pokemon)
                    }
                }
            }
        }
        return damage
    }

    /**
     * Applies one hit of [moveData] to [targetsIn]. Returns the per-target results and the targets that are
     * still in play (entries become null or false as they drop out, like Showdown's array).
     */
    fun spreadMoveHit(targetsIn: List<Pokemon?>, pokemon: Pokemon, move: ActiveMove, hitEffect: HitData? = null,
                      isSecondary: Boolean = false, isSelf: Boolean = false): Pair<MutableList<Any?>, MutableList<Pokemon?>> {
        val targets: MutableList<Any?> = ArrayList(targetsIn)
        val target = targetsIn.firstOrNull()
        var damage: MutableList<Any?> = MutableList(targets.size) { true }
        var hitResult: Any? = true
        val moveData: HitData = hitEffect ?: move
        if (move.target == "all" && !isSelf) {
            hitResult = battle.singleEvent("TryHitField", moveData, EffectState(""), target, pokemon, move)
        } else if ((move.target == "foeSide" || move.target == "allySide" || move.target == "allyTeam") && !isSelf) {
            hitResult = battle.singleEvent("TryHitSide", moveData, EffectState(""), target, pokemon, move)
        } else if (target != null) {
            hitResult = battle.singleEvent("TryHit", moveData, EffectState(""), target, pokemon, move)
        }
        if (!Js.truthy(hitResult)) {
            if (hitResult == false) {
                battle.add("-fail", pokemon)
                battle.attrLastMove("[still]")
            }
            return mutableListOf<Any?>(false) to targetsIn.toMutableList()
        }
        if (!isSecondary && !isSelf) {
            if (move.target !in listOf("all", "allyTeam", "allySide", "foeSide")) {
                damage = tryPrimaryHitEvent(damage, targets, pokemon, move, moveData)
            }
        }
        for (i in targets.indices) {
            if (damage[i] == HIT_SUBSTITUTE) {
                damage[i] = true
                targets[i] = null
            }
            if (targets[i] != null && targets[i] != false && isSecondary && moveData.hitSelf == null) damage[i] = true
            if (!Js.truthy(damage[i])) targets[i] = false
        }
        damage = getSpreadDamage(damage, targets, pokemon, move, moveData, isSecondary, isSelf)
        for (i in targets.indices) if (damage[i] == false) targets[i] = false
        damage = battle.spreadDamage(damage, targets, pokemon, move)
        for (i in targets.indices) if (damage[i] == false) targets[i] = false
        damage = runMoveEffects(damage, targets, pokemon, move, moveData, isSecondary, isSelf)
        for (i in targets.indices) if (!Js.truthy(damage[i]) && damage[i] != 0) targets[i] = false
        val activeTarget = battle.activeTarget
        if (moveData.hitSelf != null && !move.selfDropped) selfDrops(targets, pokemon, move, moveData, isSecondary)
        if (moveData.hitSecondaries != null) secondaries(targets, pokemon, move, moveData, isSelf)
        battle.activeTarget = activeTarget
        if (moveData.hitForceSwitch) damage = forceSwitch(damage, targets, pokemon, move)
        for (i in targets.indices) if (!Js.truthy(damage[i]) && damage[i] != 0) targets[i] = false
        val damagedTargets = ArrayList<Pokemon>()
        val damagedDamage = ArrayList<Any?>()
        for ((i, t) in targets.withIndex()) {
            if (Js.isNumber(damage[i]) && t is Pokemon) {
                damagedTargets.add(t)
                damagedDamage.add(damage[i])
            }
        }
        val pokemonOriginalHP = pokemon.hp
        if (damagedDamage.isNotEmpty() && !isSecondary && !isSelf) {
            battle.runEvent("DamagingHit", damagedTargets, pokemon, move, damagedDamage)
            if (moveData.declares("onAfterHit")) {
                for (t in damagedTargets) battle.singleEvent("AfterHit", moveData, EffectState(""), t, pokemon, move)
            }
            if (pokemon.hp != 0 && pokemon.hp <= pokemon.maxhp / 2.0 && pokemonOriginalHP > pokemon.maxhp / 2.0) {
                battle.runEvent("EmergencyExit", pokemon)
            }
        }
        return damage to targets.map { it as? Pokemon }.toMutableList()
    }

    fun tryPrimaryHitEvent(damage: MutableList<Any?>, targets: List<Any?>, pokemon: Pokemon, move: ActiveMove, moveData: HitData): MutableList<Any?> {
        for ((i, target) in targets.withIndex()) {
            if (target !is Pokemon) continue
            damage[i] = battle.runEvent("TryPrimaryHit", target, pokemon, moveData)
        }
        return damage
    }

    fun getSpreadDamage(damage: MutableList<Any?>, targets: List<Any?>, source: Pokemon, move: ActiveMove, moveData: HitData,
                        isSecondary: Boolean, isSelf: Boolean): MutableList<Any?> {
        for ((i, target) in targets.withIndex()) {
            if (target !is Pokemon) continue
            battle.activeTarget = target
            damage[i] = Unit
            // Secondary and self blocks have no base power: Showdown's getDamage returns undefined for them.
            val curDamage = if (moveData is ActiveMove) getDamage(source, target, moveData) else Unit
            if (curDamage == false || curDamage == null) {
                if (damage[i] == false && !isSecondary && !isSelf) {
                    battle.add("-fail", source)
                    battle.attrLastMove("[still]")
                }
                damage[i] = false
                continue
            }
            damage[i] = curDamage
        }
        return damage
    }

    fun runMoveEffects(damage: MutableList<Any?>, targets: MutableList<Any?>, source: Pokemon, move: ActiveMove, moveData: HitData,
                       isSecondary: Boolean, isSelf: Boolean): MutableList<Any?> {
        var didAnything: Any? = damage.reduce { a, b -> combineResults(a, b) }
        for ((i, t) in targets.withIndex()) {
            if (t == false) continue
            var hitResult: Any?
            var didSomething: Any? = Unit
            val target = t as? Pokemon
            if (target != null) {
                moveData.hitBoosts?.let { boosts ->
                    if (!target.fainted) {
                        hitResult = battle.boost(boosts, target, source, move, isSecondary, isSelf)
                        didSomething = combineResults(didSomething, hitResult)
                    }
                }
                val heal = moveData.hitHeal
                if (heal != null && !target.fainted) {
                    if (target.hp >= target.maxhp) {
                        battle.add("-fail", target, "heal")
                        battle.attrLastMove("[still]")
                        damage[i] = combineResults(damage[i], false)
                        didAnything = combineResults(didAnything, null)
                        continue
                    }
                    val amount = target.baseMaxhp.toDouble() * heal[0] / heal[1]
                    val d = target.heal(Math.round(amount).toDouble())
                    if (!Js.truthy(d) && d != 0) {
                        battle.add("-fail", source)
                        battle.attrLastMove("[still]")
                        damage[i] = combineResults(damage[i], false)
                        didAnything = combineResults(didAnything, null)
                        continue
                    }
                    battle.add("-heal", target, target.getHealth)
                    didSomething = true
                }
                val status = moveData.hitStatus
                if (status != null) {
                    hitResult = target.trySetStatus(status, source, moveData.hitAbility ?: move)
                    if (!Js.truthy(hitResult) && move.status != null) {
                        damage[i] = combineResults(damage[i], false)
                        didAnything = combineResults(didAnything, null)
                        continue
                    }
                    didSomething = combineResults(didSomething, hitResult)
                }
                moveData.hitForceStatus?.let { hitResult = target.setStatus(it, source, move); didSomething = combineResults(didSomething, hitResult) }
                moveData.hitVolatileStatus?.let { hitResult = target.addVolatile(it, source, move); didSomething = combineResults(didSomething, hitResult) }
                moveData.hitSideCondition?.let { hitResult = target.side.addSideCondition(it, source, move); didSomething = combineResults(didSomething, hitResult) }
                moveData.hitSlotCondition?.let { hitResult = target.side.addSlotCondition(target, it, source, move); didSomething = combineResults(didSomething, hitResult) }
                moveData.hitWeather?.let { hitResult = battle.field.setWeather(it, source, move); didSomething = combineResults(didSomething, hitResult) }
                moveData.hitTerrain?.let { hitResult = battle.field.setTerrain(it, source, move); didSomething = combineResults(didSomething, hitResult) }
                moveData.hitPseudoWeather?.let { hitResult = battle.field.addPseudoWeather(it, source, move); didSomething = combineResults(didSomething, hitResult) }
                if (moveData.hitForceSwitch) {
                    hitResult = battle.canSwitch(target.side) != 0
                    didSomething = combineResults(didSomething, hitResult)
                }
                if (move.target == "all" && !isSelf) {
                    if (moveData.declares("onHitField")) {
                        hitResult = battle.singleEvent("HitField", moveData, EffectState(""), target, source, move)
                        didSomething = combineResults(didSomething, hitResult)
                    }
                } else if ((move.target == "foeSide" || move.target == "allySide") && !isSelf) {
                    if (moveData.declares("onHitSide")) {
                        hitResult = battle.singleEvent("HitSide", moveData, EffectState(""), target.side, source, move)
                        didSomething = combineResults(didSomething, hitResult)
                    }
                } else {
                    if (moveData.declares("onHit")) {
                        hitResult = battle.singleEvent("Hit", moveData, EffectState(""), target, source, move)
                        didSomething = combineResults(didSomething, hitResult)
                    }
                    if (!isSelf && !isSecondary) battle.runEvent("Hit", target, source, move)
                }
            }
            if (moveData.hitSelfdestruct == "ifHit" && damage[i] != false) battle.faint(source, source, move)
            if (moveData.hitSelfSwitch != null && Js.truthy(moveData.hitSelfSwitch)) {
                didSomething = if (battle.canSwitch(source.side) != 0 && source.volatiles["commanded"] == null) true
                else combineResults(didSomething, false)
            }
            if (didSomething === Unit) didSomething = true
            damage[i] = combineResults(damage[i], if (didSomething == null) false else didSomething)
            didAnything = combineResults(didAnything, didSomething)
        }
        if (!Js.truthy(didAnything) && didAnything != 0 && moveData.hitSelf == null && moveData.hitSelfdestruct == null) {
            if (!isSelf && !isSecondary) {
                if (didAnything == false) {
                    battle.add("-fail", source)
                    battle.attrLastMove("[still]")
                }
            }
        } else if (Js.truthy(move.selfSwitch) && source.hp != 0 && source.volatiles["commanded"] == null) {
            source.switchFlag = move.id
        }
        return damage
    }

    fun selfDrops(targets: List<Any?>, source: Pokemon, move: ActiveMove, moveData: HitData, isSecondary: Boolean) {
        for (target in targets) {
            if (target == false) continue
            val self = moveData.hitSelf ?: continue
            if (move.selfDropped) continue
            if (!isSecondary && self.hitBoosts != null) {
                val secondaryRoll = battle.random(100)
                if (self.chance == null || secondaryRoll < self.chance!!) moveHit(listOf(source), source, move, self, isSecondary, true)
                if (move.multihit == null) move.selfDropped = true
            } else {
                moveHit(listOf(source), source, move, self, isSecondary, true)
            }
        }
    }

    fun secondaries(targets: List<Any?>, source: Pokemon, move: ActiveMove, moveData: HitData, isSelf: Boolean) {
        val list = moveData.hitSecondaries ?: return
        for (target in targets) {
            // A target behind a substitute is null here, not false: its secondaries still roll.
            if (target == false) continue
            val mon = target as? Pokemon
            @Suppress("UNCHECKED_CAST")
            val secondaries = battle.runEvent("ModifySecondaries", mon, source, moveData, ArrayList(list)) as List<HitData>
            for (secondary in secondaries) {
                val secondaryRoll = battle.random(100)
                if (secondary.chance == null || secondaryRoll < secondary.chance!!) {
                    moveHit(listOf(mon), source, move, secondary, true, isSelf)
                }
            }
        }
    }

    fun forceSwitch(damage: MutableList<Any?>, targets: List<Any?>, source: Pokemon, move: ActiveMove): MutableList<Any?> {
        for ((i, t) in targets.withIndex()) {
            val target = t as? Pokemon ?: continue
            if (target.hp > 0 && source.hp > 0 && battle.canSwitch(target.side) != 0) {
                val hitResult = battle.runEvent("DragOut", target, source, move)
                if (Js.truthy(hitResult)) {
                    target.forceSwitchFlag = true
                } else if (hitResult == false && move.category == "Status") {
                    battle.add("-fail", source)
                    battle.attrLastMove("[still]")
                    damage[i] = false
                }
            }
        }
        return damage
    }

    fun moveHit(targets: List<Pokemon?>, pokemon: Pokemon, move: ActiveMove, moveData: HitData? = null,
                isSecondary: Boolean = false, isSelf: Boolean = false): Any? {
        val retVal = spreadMoveHit(targets, pokemon, move, moveData, isSecondary, isSelf).first[0]
        return if (retVal == true) Unit else retVal
    }

    fun calcRecoilDamage(damageDealt: Int, move: ActiveMove, pokemon: Pokemon): Int {
        if (move.id == "chloroblast") return Math.round(pokemon.maxhp / 2.0).toInt()
        val recoil = move.recoil!!
        return maxOf(Math.round(damageDealt.toDouble() * recoil[0] / recoil[1]).toInt(), 1)
    }

    fun getMaxMove(moveId: String, pokemon: Pokemon): jbro.cobblemon.mcc.betterai.engine.dex.MoveData? {
        val move = dex.move(moveId) ?: return null
        if (move.name == "Struggle") return move
        val speciesName = Js.toID(pokemon.species.name)
        val gmax = if (pokemon.gigantamax) GMAX_MAP[speciesName] else null
        if (gmax != null && move.category != "Status") {
            val gMaxMove = dex.move(gmax)
            if (gMaxMove != null && gMaxMove.type == move.type) return gMaxMove
        }
        return dex.move(MAX_MOVES[if (move.category == "Status") "Status" else move.type] ?: return null)
    }

    fun getActiveMaxMove(move: ActiveMove, pokemon: Pokemon): ActiveMove {
        if (move.name == "Struggle") return dex.activeMove(move.id)
        val speciesName = Js.toID(pokemon.species.name)
        val gmax = if (pokemon.gigantamax) GMAX_MAP[speciesName] else null
        var maxMove = dex.activeMove(MAX_MOVES.getValue(if (move.category == "Status") "Status" else move.type))
        if (move.category != "Status") {
            if (gmax != null) {
                val gMaxMove = dex.activeMove(gmax)
                if (gMaxMove.type == move.type) maxMove = gMaxMove
            }
            val basePower = Js.int(move.maxMoveData?.get("basePower"))
            require(basePower != 0) { "${move.name} doesn't have a maxMove basePower" }
            if (maxMove.id !in listOf("gmaxdrumsolo", "gmaxfireball", "gmaxhydrosnipe")) maxMove.basePower = basePower
            maxMove.category = move.category
        }
        maxMove.baseMove = move.id
        maxMove.priority = move.priority
        maxMove.isZOrMaxPowered = true
        return maxMove
    }

    fun targetTypeChoices(targetType: String): Boolean = targetType in CHOOSABLE_TARGETS

    /** Merges hit results by type priority: undefined < "" < null < boolean < number (numbers add). */
    fun combineResults(left: Any?, right: Any?): Any? {
        val priorities = listOf("undefined", "string", "object", "boolean", "number")
        val l = priorities.indexOf(Js.typeOf(left))
        val r = priorities.indexOf(Js.typeOf(right))
        return when {
            l > r -> left
            Js.truthy(left) && !Js.truthy(right) && right != 0 -> left
            Js.isNumber(left) && Js.isNumber(right) -> Js.number(Js.num(left) + Js.num(right))
            else -> right
        }
    }

    /** `0` is a success dealing no damage; `Unit` success, `null` silent failure, `false` loud failure. */
    fun getDamage(source: Pokemon, target: Pokemon, move: ActiveMove, suppressMessages: Boolean = false): Any? {
        val ignore = move.ignoreImmunity
        if (!Js.truthy(ignore) || (ignore != true && (ignore as? Map<*, *>)?.get(move.type) != true)) {
            if (!target.runImmunity(move.type, !suppressMessages)) return false
        }
        if (Js.truthy(move.ohko)) return target.maxhp
        if (move.declares("damageCallback")) return battle.callback(move, "damageCallback", source, target, null)
        val fixed = move.damage
        if (fixed == "level") return source.level
        if (Js.truthy(fixed)) return fixed
        var basePower: Any? = move.basePower
        if (move.declares("basePowerCallback")) basePower = battle.callback(move, "basePowerCallback", source, target, move)
        if (!Js.truthy(basePower)) return if (basePower == 0) Unit else basePower
        var bp = Js.clampIntRange(basePower, 1)
        var critRatio = Js.int(battle.runEvent("ModifyCritRatio", source, target, move, move.critRatio))
        critRatio = critRatio.coerceIn(0, 4)
        val critMult = intArrayOf(0, 24, 8, 2, 1)
        val moveHit = target.getMoveHitData(move)
        moveHit.crit = move.willCrit ?: false
        if (move.willCrit == null) {
            if (critRatio != 0) moveHit.crit = battle.randomChance(1, critMult[critRatio])
        }
        if (moveHit.crit) moveHit.crit = Js.truthy(battle.runEvent("CriticalHit", target, null, move))
        val modifiedBp = battle.runEvent("BasePower", source, target, move, bp, true)
        if (!Js.truthy(modifiedBp)) return 0
        bp = Js.clampIntRange(modifiedBp, 1)
        if ((source.volatiles["dynamax"] == null && Js.truthy(move.isMax)) ||
            (Js.truthy(move.isMax) && Js.truthy(dex.move(move.baseMove ?: "")?.isMax))) bp = 0
        if (bp < 60 && source.getTypes(true).contains(move.type) && source.terastallized != null && move.priority <= 0 &&
            move.multihit == null && !((move.basePower == 0 || move.basePower == 150) && move.declares("basePowerCallback"))) {
            bp = 60
        }
        val level = source.level
        val attacker = if (move.overrideOffensivePokemon == "target") target else source
        val defender = if (move.overrideDefensivePokemon == "source") source else target
        val isPhysical = move.category == "Physical"
        var attackStat = move.overrideOffensiveStat ?: if (isPhysical) "atk" else "spa"
        val defenseStat = move.overrideDefensiveStat ?: if (isPhysical) "def" else "spd"
        var atkBoosts = attacker.boosts[attackStat] ?: 0
        var defBoosts = defender.boosts[defenseStat] ?: 0
        var ignoreNegativeOffensive = move.ignoreNegativeOffensive
        var ignorePositiveDefensive = move.ignorePositiveDefensive
        if (moveHit.crit) {
            ignoreNegativeOffensive = true
            ignorePositiveDefensive = true
        }
        val ignoreOffensive = move.ignoreOffensive || (ignoreNegativeOffensive && atkBoosts < 0)
        val ignoreDefensive = move.ignoreDefensive || (ignorePositiveDefensive && defBoosts > 0)
        if (ignoreOffensive) atkBoosts = 0
        if (ignoreDefensive) defBoosts = 0
        battle.tracer?.let { tracer ->
            // Wonder Room makes calculateStat read the other defense stat.
            fun stored(stat: String) = if ("wonderroom" in battle.field.pseudoWeather && stat in setOf("def", "spd")) {
                if (stat == "def") "spd" else "def"
            } else stat
            tracer.damageStats(battle, attacker, stored(attackStat), defender, stored(defenseStat), moveHit.crit)
        }
        var attack = attacker.calculateStat(attackStat, atkBoosts, 1, source)
        var defense = defender.calculateStat(defenseStat, defBoosts, 1, target)
        attackStat = if (move.category == "Physical") "atk" else "spa"
        attack = Js.int(battle.runEvent("Modify" + Pokemon.STAT_EVENT.getValue(attackStat), source, target, move, attack))
        defense = Js.int(battle.runEvent("Modify" + Pokemon.STAT_EVENT.getValue(defenseStat), target, source, move, defense))
        val baseDamage = Js.trunc(Js.trunc(Js.trunc(Js.trunc(2.0 * level / 5 + 2).toDouble() * bp * attack).toDouble() / defense).toDouble() / 50)
        return modifyDamage(baseDamage, source, target, move, suppressMessages)
    }

    fun modifyDamage(baseDamageIn: Int, pokemon: Pokemon, target: Pokemon, move: ActiveMove, suppressMessages: Boolean = false): Int {
        val type = move.type
        var baseDamage = baseDamageIn + 2
        if (move.spreadHit) {
            val spreadModifier = move.spreadModifier ?: if (battle.gameType == "freeforall") 0.5 else 0.75
            baseDamage = battle.modify(baseDamage, spreadModifier)
        } else if (move.multihitType == "parentalbond" && move.hit > 1) {
            baseDamage = battle.modify(baseDamage, 0.25)
        }
        baseDamage = Js.int(battle.runEvent("WeatherModifyDamage", pokemon, target, move, baseDamage))
        val isCrit = target.getMoveHitData(move).crit
        if (isCrit) baseDamage = Js.trunc(baseDamage * (move.critModifier ?: 1.5))
        baseDamage = battle.randomizer(baseDamage)
        if (type != "???") {
            var stab: Double
            val tera = pokemon.teraType.replaceFirstChar { it.uppercaseChar() }.let { it.take(1) + it.drop(1).lowercase() }
            val isSTAB = move.forceSTAB || pokemon.hasType(type) || pokemon.getTypes(false, true).contains(type) ||
                (pokemon.terastallized != null && tera == type)
            stab = if (isSTAB) 1.5 else 1.0
            if (pokemon.terastallized != null && tera == "Stellar" && pokemon.species.name == "Terapagos-Stellar") {
                stab = if (isSTAB && move.name != "Tera Starstorm") 2.0 else 1.2
            } else if (pokemon.terastallized != null && tera == "Stellar") {
                if (type !in pokemon.stellarBoostedTypes) {
                    stab = if (isSTAB) 2.0 else 1.2
                    pokemon.stellarBoostedTypes.add(type)
                }
            }
            if (pokemon.terastallized != null && tera == type && pokemon.getTypes(false, true).contains(type)) stab = 2.0
            val modifiedStab = battle.runEvent("ModifySTAB", pokemon, target, move, Js.number(stab))
            baseDamage = battle.modify(baseDamage, Js.num(modifiedStab))
        }
        var typeMod = target.runEffectiveness(move)
        typeMod = typeMod.coerceIn(-6, 6)
        target.getMoveHitData(move).typeMod = typeMod
        if (!suppressMessages) {
            if (typeMod >= 2) battle.add("-extremelyeffective", target)
            else if (typeMod <= -2) battle.add("-mostlyineffective", target)
        }
        if (typeMod > 0) {
            if (!suppressMessages && typeMod < 2) battle.add("-supereffective", target)
            repeat(typeMod) { baseDamage *= 2 }
        }
        if (typeMod < 0) {
            if (!suppressMessages && typeMod > -2) battle.add("-resisted", target)
            repeat(-typeMod) { baseDamage = Js.trunc(baseDamage / 2.0) }
        }
        if (isCrit && !suppressMessages) battle.add("-crit", target)
        if (pokemon.status == "brn" && move.category == "Physical" && !pokemon.hasAbility("guts")) {
            if (move.id != "facade") baseDamage = battle.modify(baseDamage, 0.5)
        }
        baseDamage = Js.int(battle.runEvent("ModifyDamage", pokemon, target, move, baseDamage))
        if (move.isZOrMaxPowered && target.getMoveHitData(move).zBrokeProtect) {
            baseDamage = battle.modify(baseDamage, 0.25)
            battle.add("-zbroken", target)
        }
        if (baseDamage == 0) return 1
        return Js.trunc(baseDamage.toDouble(), 16)
    }

    /** Confusion ignores most modifiers and uses a 16-bit base damage. */
    fun getConfusionDamage(pokemon: Pokemon, basePower: Int): Int {
        val attack = pokemon.calculateStat("atk", pokemon.boosts.getValue("atk"))
        val defense = pokemon.calculateStat("def", pokemon.boosts.getValue("def"))
        val level = pokemon.level
        val baseDamage = Js.trunc(Js.trunc(Js.trunc(Js.trunc(2.0 * level / 5 + 2).toDouble() * basePower * attack).toDouble() / defense).toDouble() / 50) + 2
        var damage = Js.trunc(baseDamage.toDouble(), 16)
        damage = battle.randomizer(damage)
        return maxOf(1, damage)
    }

    // endregion
    // region Mega Evolution and Terastallization

    fun canMegaEvo(pokemon: Pokemon): String? {
        val species = pokemon.species
        for (move in pokemon.baseMoves) {
            if (move == "dragonascent" && species.name == "Rayquaza") {
                if (pokemon.volatiles["dynamax"] != null || pokemon.terastallized != null) return null
                return "Rayquaza-Mega"
            }
        }
        val stone = pokemon.getItem().data("megaStone") ?: return null
        return when (stone) {
            is Map<*, *> -> stone[species.name] as? String
            else -> null
        }
    }

    fun canUltraBurst(pokemon: Pokemon): String? =
        if (pokemon.baseSpecies.name in listOf("Necrozma-Dawn-Wings", "Necrozma-Dusk-Mane") && pokemon.getItem().id == "ultranecroziumz") {
            "Necrozma-Ultra"
        } else null

    fun runMegaEvo(pokemon: Pokemon): Boolean {
        val speciesId = pokemon.canMegaEvo ?: pokemon.canUltraBurst ?: return false
        pokemon.formeChange(speciesId, pokemon.getItem(), true)
        val wasMega = pokemon.canMegaEvo != null
        for (ally in pokemon.side.pokemon) {
            if (wasMega) ally.canMegaEvo = null else ally.canUltraBurst = null
        }
        battle.runEvent("AfterMega", pokemon)
        return true
    }

    fun canTerastallize(pokemon: Pokemon): String? {
        if (pokemon.species.baseSpecies == "Rayquaza" && !Js.truthy(pokemon.getItem().data("zMove"))) return pokemon.teraType
        if (pokemon.species.name == "Groudon-Primal" || pokemon.species.name == "Kyogre-Primal") return null
        if (Js.truthy(pokemon.getItem().data("zMove")) || pokemon.canMegaEvo != null || pokemon.volatiles["dynamax"] != null) return null
        return pokemon.teraType
    }

    fun terastallize(pokemon: Pokemon) {
        if (pokemon.illusion != null && pokemon.illusion!!.species.baseSpecies in listOf("Ogerpon", "Terapagos")) {
            battle.singleEvent("End", dex.ability("Illusion"), pokemon.abilityState, pokemon)
        }
        val type = pokemon.teraType.take(1).uppercase() + pokemon.teraType.drop(1).lowercase()
        battle.add("-terastallize", pokemon, type)
        pokemon.canMegaEvo = null
        pokemon.terastallized = type
        for (ally in pokemon.side.pokemon) ally.canTerastallize = null
        pokemon.addedType = ""
        pokemon.knownType = true
        pokemon.apparentType = type
        if (pokemon.species.baseSpecies == "Ogerpon") {
            val tera = if (pokemon.species.id == "ogerpon") "tealtera" else "tera"
            pokemon.formeChange(pokemon.species.id + tera, null, true)
        }
        if (pokemon.species.name == "Terapagos-Terastal" && type == "Stellar") {
            pokemon.formeChange("Terapagos-Stellar", null, true)
            pokemon.baseMaxhp = floor((floor(2.0 * pokemon.species.baseStats.getValue("hp") + (pokemon.set.ivs["hp"] ?: 31) +
                floor((pokemon.set.evs["hp"] ?: 0) / 4.0) + 100) * pokemon.level) / 100 + 10).toInt()
            val newMaxHP = pokemon.baseMaxhp
            pokemon.hp = newMaxHP - (pokemon.maxhp - pokemon.hp)
            pokemon.maxhp = newMaxHP
            battle.add("-heal", pokemon, pokemon.getHealth, "[silent]")
        }
        battle.runEvent("AfterTerastallization", pokemon)
    }

    // endregion

    companion object {
        /** Showdown's `NOT_FAIL` (""), `HIT_SUBSTITUTE` (0), `FAIL` (false), `SILENT_FAIL` (null). */
        const val NOT_FAIL = ""
        const val HIT_SUBSTITUTE = 0

        val STRUGGLE_RECOIL = Effect("strugglerecoil", "strugglerecoil", "Condition", com.google.gson.JsonObject(), "", emptySet())

        val CHOOSABLE_TARGETS = setOf("normal", "any", "adjacentAlly", "adjacentAllyOrSelf", "adjacentFoe")

        val MAX_MOVES = mapOf(
            "Flying" to "Max Airstream", "Dark" to "Max Darkness", "Fire" to "Max Flare", "Bug" to "Max Flutterby",
            "Water" to "Max Geyser", "Status" to "Max Guard", "Ice" to "Max Hailstorm", "Fighting" to "Max Knuckle",
            "Electric" to "Max Lightning", "Psychic" to "Max Mindstorm", "Poison" to "Max Ooze", "Grass" to "Max Overgrowth",
            "Ghost" to "Max Phantasm", "Ground" to "Max Quake", "Rock" to "Max Rockfall", "Fairy" to "Max Starfall",
            "Steel" to "Max Steelspike", "Normal" to "Max Strike", "Dragon" to "Max Wyrmwind",
        )

        val GMAX_MAP = mapOf(
            "venusaur" to "gmaxvinelash", "charizard" to "gmaxwildfire", "blastoise" to "gmaxcannonade",
            "butterfree" to "gmaxbefuddle", "pikachu" to "gmaxvoltcrash", "meowth" to "gmaxgoldrush",
            "machamp" to "gmaxchistrike", "gengar" to "gmaxterror", "kingler" to "gmaxfoamburst", "lapras" to "gmaxresonance",
            "eevee" to "gmaxcuddle", "snorlax" to "gmaxreplenish", "garbodor" to "gmaxmalodor", "melmetal" to "gmaxmeltdown",
            "copperajah" to "gmaxsteelsurge", "duraludon" to "gmaxdepletion", "corviknight" to "gmaxwindrage",
            "orbeetle" to "gmaxgravitas", "toxtricity" to "gmaxstunshock", "toxtricitylowkey" to "gmaxstunshock",
            "centiskorch" to "gmaxcentiferno", "hatterene" to "gmaxsmite", "grimmsnarl" to "gmaxsnooze",
            "alcremie" to "gmaxfinale", "drednaw" to "gmaxstonesurge", "coalossal" to "gmaxvolcalith", "flapple" to "gmaxtartness",
            "appletun" to "gmaxsweetness", "sandaconda" to "gmaxsandblast", "inteleon" to "gmaxhydrosnipe",
            "cinderace" to "gmaxfireball", "rillaboom" to "gmaxdrumsolo", "urshifu" to "gmaxoneblow",
            "urshifurapidstrike" to "gmaxrapidflow",
        )
    }
}

@Suppress("unused")
private fun Double.roundHalfUp(): Long = this.roundToLong()
