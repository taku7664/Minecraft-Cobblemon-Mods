package jbro.cobblemon.mcc.betterai.engine.sim

import jbro.cobblemon.mcc.betterai.engine.Js
import jbro.cobblemon.mcc.betterai.engine.dex.EffectLike

/** Anything `comparePriority` sorts: queued actions and event handlers. */
interface Prioritized {
    /** `order`; `null` means Showdown's "default last". */
    val sortOrder: Int?
    val sortPriority: Double
    val sortSpeed: Double
    val sortSubOrder: Int
}

/** A queued action (`sim/battle-queue.js` Action). */
class Action(
    var choice: String,
    var pokemon: Pokemon? = null,
    var target: Pokemon? = null,
    var targetLoc: Int = 0,
    var moveid: String? = null,
    var move: ActiveMove? = null,
    /** `false`, `true` or `"done"`. */
    var mega: Any = false,
    var maxMove: String? = null,
    var dynamax: Boolean = false,
    var terastallize: String? = null,
    var event: String? = null,
    var index: Int = 0,
) : Prioritized {
    var order: Int = 0
    var priority: Double = 0.0
    var speed: Double = 0.0
    var subOrder: Int = 0
    var fractionalPriority: Double = 0.0
    var originalTarget: Pokemon? = null
    var sourceEffect: EffectLike? = null
    var side: Side? = null

    override val sortOrder: Int? get() = order.takeIf { it != 0 }
    override val sortPriority: Double get() = priority
    override val sortSpeed: Double get() = speed
    override val sortSubOrder: Int get() = subOrder

    override fun toString(): String = "$order:$priority:$speed:$subOrder - $choice${pokemon?.let { " $it" } ?: ""}${move?.let { " $it" } ?: ""}"
}

/** Port of `sim/battle-queue.js`. */
class BattleQueue(private val battle: Battle) {
    var list: MutableList<Action> = ArrayList()

    fun shift(): Action? = if (list.isEmpty()) null else list.removeAt(0)
    fun peek(end: Boolean = false): Action? = if (list.isEmpty()) null else if (end) list.last() else list[0]
    fun push(action: Action) = list.add(action)
    fun unshift(action: Action) = list.add(0, action)

    fun resolveAction(action: Action, midTurn: Boolean = false): List<Action> {
        if (action.choice == "pass") return emptyList()
        val actions = ArrayList<Action>()
        actions.add(action)
        if (action.side == null && action.pokemon != null) action.side = action.pokemon!!.side
        if (action.move == null && action.moveid != null) action.move = battle.dex.activeMove(action.moveid!!)
        if (action.order == 0) {
            action.order = ORDERS[action.choice] ?: run {
                check(action.choice == "move" || action.choice == "event") { "Unexpected orderless action ${action.choice}" }
                200
            }
        }
        if (!midTurn) {
            if (action.choice == "move") {
                val pokemon = action.pokemon!!
                val move = action.move!!
                if (action.maxMove == null && move.declares("beforeTurnCallback")) {
                    actions.addAll(0, resolveAction(Action("beforeTurnMove", pokemon = pokemon, move = move, targetLoc = action.targetLoc)))
                }
                if (Js.truthy(action.mega) && !pokemon.isSkyDropped()) {
                    actions.addAll(0, resolveAction(Action("megaEvo", pokemon = pokemon)))
                }
                if (action.terastallize != null && pokemon.terastallized == null) {
                    actions.addAll(0, resolveAction(Action("terastallize", pokemon = pokemon)))
                }
                if (action.maxMove != null && pokemon.volatiles["dynamax"] == null) {
                    actions.addAll(0, resolveAction(Action("runDynamax", pokemon = pokemon)))
                }
                if (action.maxMove == null && move.declares("priorityChargeCallback")) {
                    actions.addAll(0, resolveAction(Action("priorityChargeMove", pokemon = pokemon, move = move)))
                }
                action.fractionalPriority = Js.num(battle.runEvent("FractionalPriority", pokemon, null, move, 0))
            } else if (action.choice == "switch" || action.choice == "instaswitch") {
                val pokemon = action.pokemon!!
                val flag = pokemon.switchFlag
                if (flag is String) action.sourceEffect = battle.dex.move(flag)
                pokemon.switchFlag = false
            }
        }
        action.move?.let { move ->
            val pokemon = action.pokemon!!
            action.move = move
            if (action.targetLoc == 0) {
                val target = battle.getRandomTarget(pokemon, move)
                if (target != null) action.targetLoc = pokemon.getLocOf(target)
            }
            action.originalTarget = pokemon.getAtLoc(action.targetLoc)
        }
        battle.getActionSpeed(action)
        return actions
    }

    fun prioritizeAction(action: Action, sourceEffect: EffectLike? = null) {
        list.remove(action)
        action.sourceEffect = sourceEffect
        action.order = 3
        list.add(0, action)
    }

    fun changeAction(pokemon: Pokemon, action: Action) {
        cancelAction(pokemon)
        if (action.pokemon == null) action.pokemon = pokemon
        insertChoice(action)
    }

    fun addChoice(choices: List<Action>) {
        for (choice in choices) {
            val resolved = resolveAction(choice)
            list.addAll(resolved)
            for (r in resolved) {
                if (r.choice == "move" && r.move!!.id != "recharge") r.pokemon!!.side.lastSelectedMove = r.move!!.id
            }
        }
    }

    fun addChoice(choice: Action) = addChoice(listOf(choice))

    fun willAct(): Action? = list.firstOrNull { it.choice in listOf("move", "switch", "instaswitch", "shift") }

    fun willMove(pokemon: Pokemon): Action? {
        if (pokemon.fainted) return null
        return list.firstOrNull { it.choice == "move" && it.pokemon === pokemon }
    }

    fun cancelAction(pokemon: Pokemon): Boolean {
        val oldLength = list.size
        list.removeAll { it.pokemon === pokemon }
        return list.size != oldLength
    }

    fun cancelMove(pokemon: Pokemon): Boolean {
        val index = list.indexOfFirst { it.choice == "move" && it.pokemon === pokemon }
        if (index < 0) return false
        list.removeAt(index)
        return true
    }

    fun willSwitch(pokemon: Pokemon): Action? =
        list.firstOrNull { (it.choice == "switch" || it.choice == "instaswitch") && it.pokemon === pokemon }

    fun insertChoice(choice: Action, midTurn: Boolean = false) {
        choice.pokemon?.updateSpeed()
        val actions = resolveAction(choice, midTurn)
        var firstIndex: Int? = null
        var lastIndex: Int? = null
        for ((i, current) in list.withIndex()) {
            val compared = Battle.comparePriority(actions[0], current)
            if (compared <= 0 && firstIndex == null) firstIndex = i
            if (compared < 0) {
                lastIndex = i
                break
            }
        }
        if (firstIndex == null) {
            list.addAll(actions)
        } else {
            val last = lastIndex ?: list.size
            val index = if (firstIndex == last) firstIndex else battle.random(firstIndex, last + 1)
            list.addAll(index, actions)
        }
    }

    fun insertChoices(choices: List<Action>) {
        for (choice in choices) insertChoice(choice)
    }

    fun clear() {
        list = ArrayList()
    }

    fun sort(): BattleQueue {
        battle.speedSort(list)
        return this
    }

    companion object {
        val ORDERS = mapOf(
            "team" to 1, "start" to 2, "instaswitch" to 3, "beforeTurn" to 4, "beforeTurnMove" to 5, "revivalblessing" to 6,
            "runUnnerve" to 100, "runSwitch" to 101, "runPrimal" to 102, "switch" to 103, "megaEvo" to 104,
            "runDynamax" to 105, "terastallize" to 106, "priorityChargeMove" to 107, "shift" to 200, "residual" to 300,
        )
    }
}
