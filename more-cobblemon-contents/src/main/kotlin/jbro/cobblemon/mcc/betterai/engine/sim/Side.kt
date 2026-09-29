package jbro.cobblemon.mcc.betterai.engine.sim

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike

/** Port of `sim/side.js`. */
class Side(val name: String, val battle: Battle, val n: Int, team: List<PokemonSet>) {
    val id: String = listOf("p1", "p2", "p3", "p4")[n]
    lateinit var foe: Side
    var allySide: Side? = null
    var lastSelectedMove: String = ""
    val team: List<PokemonSet> = team
    var pokemon: MutableList<Pokemon> = ArrayList()
    val active: MutableList<Pokemon?>
    var pokemonLeft: Int
    var faintedLastTurn: Pokemon? = null
    var faintedThisTurn: Pokemon? = null
    var totalFainted = 0
    var zMoveUsed = false
    var dynamaxUsed = false
    val sideConditions: LinkedHashMap<String, EffectState> = LinkedHashMap()
    val slotConditions: MutableList<LinkedHashMap<String, EffectState>>
    var activeRequest: Request? = null
    var choice: Choice = Choice()

    init {
        for (i in team.indices) {
            if (i >= 24) break
            pokemon.add(Pokemon(team[i], this).also { it.position = i })
        }
        active = when (battle.gameType) {
            "doubles" -> mutableListOf(null, null)
            "triples", "rotation" -> mutableListOf(null, null, null)
            else -> mutableListOf(null)
        }
        pokemonLeft = pokemon.count { !it.fainted }
        slotConditions = MutableList(active.size) { LinkedHashMap() }
    }

    class Request(
        val wait: Boolean = false,
        val teamPreview: Boolean = false,
        val forceSwitch: List<Boolean>? = null,
        val active: List<Pokemon.ActiveRequest?>? = null,
    )

    class Choice {
        var cantUndo = false
        var error = ""
        val actions: MutableList<Action> = ArrayList()
        var forcedSwitchesLeft = 0
        var forcedPassesLeft = 0
        val switchIns: MutableSet<Int> = LinkedHashSet()
        var zMove = false
        var mega = false
        var ultra = false
        var dynamax = false
        var terastallize = false
    }

    val requestState: String get() {
        val request = activeRequest ?: return ""
        if (request.wait) return ""
        if (request.teamPreview) return "teampreview"
        if (request.forceSwitch != null) return "switch"
        return "move"
    }

    fun canDynamaxNow(): Boolean {
        val first = active[0]
        if (first != null && first.terastallized != null) return false
        return !dynamaxUsed
    }

    override fun toString(): String = "$id: $name"

    fun randomFoe(): Pokemon? {
        val actives = foes()
        if (actives.isEmpty()) return null
        return battle.sample(actives)
    }

    fun foeSidesWithConditions(): List<Side> = if (battle.gameType == "freeforall") battle.sides.filter { it !== this } else listOf(foe)

    fun foePokemonLeft(): Int {
        if (battle.gameType == "freeforall") return battle.sides.filter { it !== this }.sumOf { it.pokemonLeft }
        foe.allySide?.let { return foe.pokemonLeft + it.pokemonLeft }
        return foe.pokemonLeft
    }

    fun allies(all: Boolean = false): List<Pokemon> {
        var allies = activeTeam().filterNotNull()
        if (!all) allies = allies.filter { it.hp != 0 }
        return allies
    }

    fun foes(all: Boolean = false): List<Pokemon> {
        if (battle.gameType == "freeforall") {
            return battle.sides.mapNotNull { it.active[0] }.filter { it.side !== this && (all || it.hp != 0) }
        }
        return foe.allies(all)
    }

    fun activeTeam(): List<Pokemon?> {
        if (battle.gameType != "multi") return active
        return battle.sides[n % 2].active + battle.sides[n % 2 + 2].active
    }

    fun hasAlly(pokemon: Pokemon): Boolean = pokemon.side === this || pokemon.side === allySide

    fun addSideCondition(statusName: String, sourceIn: Pokemon? = null, sourceEffect: EffectLike? = null): Any? {
        val source = sourceIn ?: battle.event?.target as? Pokemon ?: error("setting sidecond without a source")
        val status = battle.dex.condition(statusName)
        sideConditions[status.id]?.let { existing ->
            if (!status.declares("onSideRestart")) return false
            return battle.singleEvent("SideRestart", status, existing, this, source, sourceEffect)
        }
        val state = EffectState(status.id).also {
            it.target = this
            it.source = source
            it.sourceSlot = source.getSlot()
            it.duration = status.duration
        }
        sideConditions[status.id] = state
        if (status.declares("durationCallback")) {
            state.duration = Js.int(battle.callback(status, "durationCallback", active[0], source, sourceEffect))
        }
        if (!Js.truthy(battle.singleEvent("SideStart", status, state, this, source, sourceEffect))) {
            sideConditions.remove(status.id)
            return false
        }
        battle.runEvent("SideConditionStart", source, source, status)
        return true
    }

    fun getSideCondition(statusName: String) = battle.dex.condition(statusName).takeIf { sideConditions[it.id] != null }

    fun getSideConditionData(statusName: String): EffectState? = sideConditions[battle.dex.condition(statusName).id]

    fun removeSideCondition(statusName: String): Boolean {
        val status = battle.dex.condition(statusName)
        val state = sideConditions[status.id] ?: return false
        battle.singleEvent("SideEnd", status, state, this)
        sideConditions.remove(status.id)
        return true
    }

    fun addSlotCondition(target: Pokemon, statusName: String, sourceIn: Pokemon? = null, sourceEffect: EffectLike? = null): Any? =
        addSlotCondition(target.position, statusName, sourceIn, sourceEffect)

    fun addSlotCondition(slot: Int, statusName: String, sourceIn: Pokemon? = null, sourceEffect: EffectLike? = null): Any? {
        val source = sourceIn ?: battle.event?.target as? Pokemon ?: error("setting sidecond without a source")
        val status = battle.dex.condition(statusName)
        slotConditions[slot][status.id]?.let { existing ->
            if (!status.declares("onRestart")) return false
            return battle.singleEvent("Restart", status, existing, this, source, sourceEffect)
        }
        val state = EffectState(status.id).also {
            it.target = this
            it.source = source
            it.sourceSlot = source.getSlot()
            it.duration = status.duration
        }
        slotConditions[slot][status.id] = state
        if (status.declares("durationCallback")) {
            state.duration = Js.int(battle.callback(status, "durationCallback", active[0], source, sourceEffect))
        }
        if (!Js.truthy(battle.singleEvent("Start", status, state, active[slot], source, sourceEffect))) {
            slotConditions[slot].remove(status.id)
            return false
        }
        return true
    }

    fun getSlotCondition(target: Pokemon, statusName: String) =
        battle.dex.condition(statusName).takeIf { slotConditions[target.position][it.id] != null }

    fun removeSlotCondition(target: Pokemon, statusName: String): Boolean = removeSlotCondition(target.position, statusName)

    fun removeSlotCondition(slot: Int, statusName: String): Boolean {
        val status = battle.dex.condition(statusName)
        val state = slotConditions[slot][status.id] ?: return false
        battle.singleEvent("End", status, state, active[slot])
        slotConditions[slot].remove(status.id)
        return true
    }

    fun emitRequest(request: Request) {
        activeRequest = request
    }

    fun emitChoiceError(message: String): Boolean {
        choice.error = message
        if (battle.strictChoices) throw IllegalArgumentException("[Invalid choice] $message")
        return false
    }

    fun isChoiceDone(): Boolean {
        if (requestState.isEmpty()) return true
        if (choice.forcedSwitchesLeft != 0) return false
        if (requestState == "teampreview") return choice.actions.size >= pickedTeamSize()
        getChoiceIndex()
        return choice.actions.size >= active.size
    }

    fun chooseMove(moveTextIn: Any? = null, targetLocIn: Int = 0, eventIn: String = ""): Boolean {
        var event = eventIn
        var targetLoc = targetLocIn
        if (requestState != "move") return emitChoiceError("Can't move: You need a $requestState response")
        val index = getChoiceIndex()
        if (index >= active.size) return emitChoiceError("Can't move: You sent more choices than unfainted Pokemon.")
        val autoChoose = moveTextIn == null || moveTextIn == ""
        val pokemon = active[index]!!
        val request = pokemon.getMoveRequestData()
        var moveid: String
        var targetType: String
        val moveText: Any = if (autoChoose) 1 else moveTextIn!!
        if (moveText is Int || (moveText is String && moveText.matches(Regex("^[0-9]+$")))) {
            val moveIndex = Js.int(moveText) - 1
            if (moveIndex < 0 || moveIndex >= request.moves.size) {
                return emitChoiceError("Can't move: Your ${pokemon.name} doesn't have a move ${moveIndex + 1}")
            }
            moveid = request.moves[moveIndex].id
            targetType = request.moves[moveIndex].target
        } else {
            moveid = Js.toID(moveText)
            if (moveid.startsWith("hiddenpower")) moveid = "hiddenpower"
            targetType = ""
            for (m in request.moves) {
                if (m.id != moveid) continue
                targetType = m.target.ifEmpty { "normal" }
                break
            }
            if (targetType.isEmpty() && (event == "" || event == "dynamax") && (request.canDynamax || pokemon.volatiles["dynamax"] != null)) {
                for ((i, m) in request.moves.withIndex()) {
                    val max = battle.actions.getMaxMove(m.id, pokemon) ?: continue
                    if (moveid == max.id) {
                        moveid = request.moves[i].id
                        targetType = max.target
                        event = "dynamax"
                        break
                    }
                }
            }
            if (targetType.isEmpty()) return emitChoiceError("Can't move: Your ${pokemon.name} doesn't have a move matching $moveid")
        }
        val moves = pokemon.getMoves()
        if (autoChoose) {
            for ((i, m) in request.moves.withIndex()) {
                if (Js.truthy(m.disabled)) continue
                if (i < moves.size && m.id == moves[i].id && Js.truthy(moves[i].disabled)) continue
                moveid = m.id
                targetType = m.target
                break
            }
        }
        val move = battle.dex.move(moveid)
        val maxMove = if (event == "dynamax" || pokemon.volatiles["dynamax"] != null) battle.actions.getMaxMove(moveid, pokemon) else null
        if (event == "dynamax" && maxMove == null) return emitChoiceError("Can't move: ${pokemon.name} can't use ${move?.name} as a Max Move")
        if (maxMove != null) targetType = maxMove.target
        if (autoChoose) {
            targetLoc = 0
        } else if (battle.actions.targetTypeChoices(targetType)) {
            if (targetLoc == 0 && active.size >= 2) return emitChoiceError("Can't move: ${move?.name} needs a target")
            if (!battle.validTargetLoc(targetLoc, pokemon, targetType)) return emitChoiceError("Can't move: Invalid target for ${move?.name}")
        } else if (targetLoc != 0) {
            return emitChoiceError("Can't move: You can't choose a target for ${move?.name}")
        }
        val lockedMove = pokemon.getLockedMove()
        if (lockedMove != null) {
            var lockedTargetLoc = pokemon.lastMoveTargetLoc ?: 0
            val lockedId = Js.toID(lockedMove)
            pokemon.volatiles[lockedId]?.get("targetLoc")?.let { if (Js.truthy(it)) lockedTargetLoc = Js.int(it) }
            choice.actions.add(Action("move", pokemon = pokemon, targetLoc = lockedTargetLoc, moveid = lockedId))
            return true
        } else if (moves.isEmpty()) {
            moveid = "struggle"
        } else if (maxMove != null) {
            if (pokemon.maxMoveDisabled(moveid)) return emitChoiceError("Can't move: ${pokemon.name}'s ${maxMove.name} is disabled")
        } else {
            var isEnabled = false
            for (m in moves) {
                if (m.id != moveid) continue
                if (!Js.truthy(m.disabled)) {
                    isEnabled = true
                    break
                }
            }
            if (!isEnabled) {
                check(!autoChoose) { "autoChoose chose a disabled move" }
                return emitChoiceError("Can't move: ${pokemon.name}'s ${move?.name} is disabled")
            }
        }
        val mega = event == "mega"
        if (mega && pokemon.canMegaEvo == null) return emitChoiceError("Can't move: ${pokemon.name} can't mega evolve")
        if (mega && choice.mega) return emitChoiceError("Can't move: You can only mega-evolve once per battle")
        val ultra = event == "ultra"
        if (ultra && pokemon.canUltraBurst == null) return emitChoiceError("Can't move: ${pokemon.name} can't ultra burst")
        if (ultra && choice.ultra) return emitChoiceError("Can't move: You can only ultra burst once per battle")
        var dynamax = event == "dynamax"
        val canDynamax = activeRequest?.active?.getOrNull(active.indexOf(pokemon))?.canDynamax == true
        if (dynamax && (choice.dynamax || !canDynamax)) {
            if (pokemon.volatiles["dynamax"] != null) dynamax = false
            else return emitChoiceError("Can't move: You can only Dynamax once per battle.")
        }
        val terastallize = event == "terastallize"
        if (terastallize && !Js.truthy(pokemon.canTerastallize)) return emitChoiceError("Can't move: ${pokemon.name} can't Terastallize.")
        if (terastallize && choice.terastallize) return emitChoiceError("Can't move: You can only Terastallize once per battle.")
        choice.actions.add(Action("move", pokemon = pokemon, targetLoc = targetLoc, moveid = moveid,
            mega = mega || ultra, maxMove = maxMove?.id, dynamax = dynamax,
            terastallize = if (terastallize) pokemon.teraType else null))
        if (pokemon.maybeDisabled) choice.cantUndo = choice.cantUndo || pokemon.isLastActive()
        if (mega) choice.mega = true
        if (ultra) choice.ultra = true
        if (dynamax) choice.dynamax = true
        if (terastallize) choice.terastallize = true
        return true
    }

    fun chooseSwitch(slotText: String? = null): Boolean {
        if (requestState != "move" && requestState != "switch") return emitChoiceError("Can't switch: You need a $requestState response")
        val index = getChoiceIndex()
        if (index >= active.size) {
            if (requestState == "switch") return emitChoiceError("Can't switch: You sent more switches than Pokemon that need to switch")
            return emitChoiceError("Can't switch: You sent more choices than unfainted Pokemon")
        }
        val pokemon = active[index]!!
        var slot: Int
        if (slotText.isNullOrEmpty()) {
            if (requestState != "switch") return emitChoiceError("Can't switch: You need to select a Pokemon to switch in")
            if (slotConditions[pokemon.position]["revivalblessing"] != null) {
                slot = 0
                while (!this.pokemon[slot].fainted) slot++
            } else {
                if (choice.forcedSwitchesLeft == 0) return choosePass()
                slot = active.size
                while (slot in choice.switchIns || this.pokemon[slot].fainted) slot++
            }
        } else {
            slot = (slotText.toIntOrNull() ?: 0) - 1
        }
        if (slot < 0 || (slotText != null && slotText.length > 2)) {
            slot = -1
            for ((i, mon) in this.pokemon.withIndex()) {
                if (slotText!!.lowercase() == mon.name.lowercase() || Js.toID(slotText) == mon.species.id || slotText == mon.uuid) {
                    slot = i
                    break
                }
            }
            if (slot < 0) return emitChoiceError("Can't switch: You do not have a Pokemon named \"$slotText\" to switch to")
        }
        if (slot >= this.pokemon.size) return emitChoiceError("Can't switch: You do not have a Pokemon in slot ${slot + 1} to switch to")
        if (slot < active.size && slotConditions[pokemon.position]["revivalblessing"] == null) {
            return emitChoiceError("Can't switch: You can't switch to an active Pokemon")
        }
        if (slot in choice.switchIns) return emitChoiceError("Can't switch: The Pokemon in slot ${slot + 1} can only switch in once")
        val targetPokemon = this.pokemon[slot]
        if (slotConditions[pokemon.position]["revivalblessing"] != null) {
            if (!targetPokemon.fainted) return emitChoiceError("Can't switch: You have to pass to a fainted Pokemon")
            choice.forcedSwitchesLeft = maxOf(choice.forcedSwitchesLeft - 1, 0)
            pokemon.switchFlag = false
            choice.actions.add(Action("revivalblessing", pokemon = pokemon, target = targetPokemon))
            return true
        }
        if (targetPokemon.fainted) return emitChoiceError("Can't switch: You can't switch to a fainted Pokemon")
        if (requestState == "move") {
            if (Js.truthy(pokemon.trapped)) return emitChoiceError("Can't switch: The active Pokemon is trapped")
            else if (pokemon.maybeTrapped) choice.cantUndo = choice.cantUndo || pokemon.isLastActive()
        } else if (requestState == "switch") {
            check(choice.forcedSwitchesLeft != 0) { "Player somehow switched too many Pokemon" }
            choice.forcedSwitchesLeft--
        }
        choice.switchIns.add(slot)
        choice.actions.add(Action(if (requestState == "switch") "instaswitch" else "switch", pokemon = pokemon, target = targetPokemon))
        return true
    }

    fun pickedTeamSize(): Int = pokemon.size

    fun clearChoice() {
        var forcedSwitches = 0
        var forcedPasses = 0
        if (battle.requestState == "switch") {
            val canSwitchOut = active.count { it != null && Js.truthy(it.switchFlag) }
            val canSwitchIn = pokemon.drop(active.size).count { !it.fainted }
            forcedSwitches = minOf(canSwitchOut, canSwitchIn)
            forcedPasses = canSwitchOut - forcedSwitches
        }
        choice = Choice().also {
            it.forcedSwitchesLeft = forcedSwitches
            it.forcedPassesLeft = forcedPasses
        }
    }

    /** `side.choose(input)`, e.g. `move 1`, `move 2 terastallize`, `switch 3`, `move 1 +2, move 3 -1`. */
    fun choose(input: String): Boolean {
        if (requestState.isEmpty()) return emitChoiceError(if (battle.ended) "Can't do anything: The game is over" else "Can't do anything: It's not your turn")
        if (choice.cantUndo) return emitChoiceError("Can't undo: A trapping/disabling effect would cause undo to leak information")
        clearChoice()
        val choiceStrings = if (input.startsWith("team ")) listOf(input) else input.split(",")
        if (choiceStrings.size > active.size) return emitChoiceError("Can't make choices: too many choices for a ${battle.gameType} game")
        for (choiceString in choiceStrings) {
            val trimmed = choiceString.trim()
            val space = trimmed.indexOf(' ')
            val choiceType = if (space >= 0) trimmed.substring(0, space) else trimmed
            var data = if (space >= 0) trimmed.substring(space + 1).trim() else ""
            when (choiceType) {
                "move" -> {
                    var targetLoc: Int? = null
                    var event = ""
                    while (true) {
                        if (Regex("\\s(?:-|\\+)?[1-3]$").containsMatchIn(data) && Js.toID(data) != "conversion2") {
                            if (targetLoc != null) return emitChoiceError("Conflicting arguments for \"move\": $data")
                            targetLoc = data.takeLast(2).trim().toInt()
                            data = data.dropLast(2).trim()
                        } else if (data.endsWith(" mega")) {
                            event = "mega"; data = data.dropLast(5)
                        } else if (data.endsWith(" ultra")) {
                            event = "ultra"; data = data.dropLast(6)
                        } else if (data.endsWith(" dynamax")) {
                            event = "dynamax"; data = data.dropLast(8)
                        } else if (data.endsWith(" gigantamax")) {
                            event = "dynamax"; data = data.dropLast(11)
                        } else if (data.endsWith(" max")) {
                            event = "dynamax"; data = data.dropLast(4)
                        } else if (data.endsWith(" terastal")) {
                            event = "terastallize"; data = data.dropLast(9)
                        } else if (data.endsWith(" terastallize")) {
                            event = "terastallize"; data = data.dropLast(13)
                        } else {
                            break
                        }
                    }
                    if (!chooseMove(data, targetLoc ?: 0, event)) return false
                }
                "switch" -> chooseSwitch(data)
                "pass", "skip" -> if (!choosePass()) return false
                "auto", "default" -> autoChoose()
                else -> emitChoiceError("Unrecognized choice: $choiceString")
            }
        }
        return choice.error.isEmpty()
    }

    fun getChoiceIndex(isPass: Boolean = false): Int {
        var index = choice.actions.size
        if (!isPass) {
            when (requestState) {
                "move" -> while (index < active.size && (active[index]!!.fainted || active[index]!!.volatiles["commanding"] != null)) {
                    choosePass()
                    index++
                }
                "switch" -> while (index < active.size && !Js.truthy(active[index]!!.switchFlag)) {
                    choosePass()
                    index++
                }
            }
        }
        return index
    }

    fun choosePass(): Boolean {
        val index = getChoiceIndex(true)
        if (index >= active.size) return false
        val pokemon = active[index]!!
        when (requestState) {
            "switch" -> if (Js.truthy(pokemon.switchFlag)) {
                if (choice.forcedPassesLeft == 0) return emitChoiceError("Can't pass: You need to switch in a Pokemon to replace ${pokemon.name}")
                choice.forcedPassesLeft--
            }
            "move" -> Unit
            else -> return emitChoiceError("Can't pass: Not a move or switch request")
        }
        choice.actions.add(Action("pass"))
        return true
    }

    fun autoChoose(): Boolean {
        if (requestState == "switch") {
            var i = 0
            while (!isChoiceDone()) {
                check(chooseSwitch()) { "autoChoose switch crashed: ${choice.error}" }
                check(++i <= 10) { "autoChoose failed: infinite looping" }
            }
        } else if (requestState == "move") {
            var i = 0
            while (!isChoiceDone()) {
                check(chooseMove()) { "autoChoose crashed: ${choice.error}" }
                check(++i <= 10) { "autoChoose failed: infinite looping" }
            }
        }
        return true
    }
}
