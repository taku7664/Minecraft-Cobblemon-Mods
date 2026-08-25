package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage
import com.cobblemon.mod.common.api.battles.interpreter.Effect
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleMoveOutcomeView
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import net.minecraft.core.registries.BuiltInRegistries

/** Direct Cobblemon 1.7.3 bridge. Only public Showdown messages may add opponent knowledge. */
internal class Cobblemon173ShowdownObservationAdapter(
    private val opponentActorId: UUID,
    initialOpponentPokemonCount: Int,
    maximumRecentEvents: Int = 128,
) {
    private val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount, maximumRecentEvents)
    private var battle: PokemonBattle? = null
    private var consumedMessages = 0
    private var observedTurn = 0
    private val lastMoveByPokemon = linkedMapOf<UUID, String>()

    fun attach(value: PokemonBattle) {
        check(battle == null || battle === value) { "Observation adapter cannot be moved to another battle" }
        battle = value
    }

    @Synchronized
    fun snapshot(actor: BattleActor): BattleStateView {
        val activeBattle = requireNotNull(battle) { "Observation adapter must be attached before use" }
        require(actor.battle === activeBattle) { "Actor belongs to a different battle" }
        require(actor.uuid != opponentActorId) { "The observed opponent cannot be the decision actor" }
        consumeNewMessages(activeBattle)
        observer.advanceTurn(activeBattle.turn)
        val format = when (activeBattle.format.battleType.pokemonPerSide) {
            1 -> BattleFormat.SINGLE
            2 -> BattleFormat.DOUBLE
            else -> error("Unsupported Cobblemon battle format: ${activeBattle.format.battleType.pokemonPerSide}")
        }
        return Cobblemon173BattleStateAssembler.assemble(
            battleId = activeBattle.battleId,
            format = format,
            turn = activeBattle.turn,
            ownPokemon = actor.pokemonList.map { it.toOwnState(actor) },
            publicSnapshot = observer.publicSnapshot(),
        )
    }

    private fun consumeNewMessages(activeBattle: PokemonBattle) {
        val messages = activeBattle.showdownMessages
        if (consumedMessages > messages.size) {
            consumedMessages = 0
            observedTurn = 0
            observer.reset()
            lastMoveByPokemon.clear()
        }
        messages.subList(consumedMessages, messages.size).forEach { raw -> consume(activeBattle, raw) }
        consumedMessages = messages.size
    }

    private fun consume(activeBattle: PokemonBattle, raw: String) {
        raw.lineSequence().filter { it.startsWith('|') }.forEach { line ->
            val message = runCatching { BattleMessage(line) }.getOrNull() ?: return@forEach
            try {
                if (message.id == "turn") {
                    observer.closeActionWindow()
                    observedTurn = publicTurn(message.argumentAt(0), activeBattle.turn, observedTurn)
                    observer.advanceTurn(observedTurn)
                    return@forEach
                }
                if (message.id == "upkeep") observer.closeActionWindow()
                consumeMessage(activeBattle, message)
                revealOptionalSource(activeBattle, message)
            } catch (_: RuntimeException) {
                // Unknown or malformed public protocol lines must not block the NPC turn.
            }
        }
    }

    private fun consumeMessage(activeBattle: PokemonBattle, message: BattleMessage) {
        when (message.id) {
            "switch", "drag" -> {
                observer.closeActionWindow()
                resolvePokemon(activeBattle, message, 0)?.let {
                    lastMoveByPokemon.remove(it.battlePokemonId)
                    observer.observe(
                        Cobblemon173PublicObservation.PokemonPresented(
                            observedTurn,
                            publicSwitchSnapshot(it, message.argumentAt(1)),
                        ),
                    )
                }
            }

            "move" -> {
                val actor = resolvePokemon(activeBattle, message, 0) ?: return
                val moveId = effectId(message.argumentAt(1)).takeIf(String::isNotBlank) ?: return
                lastMoveByPokemon[actor.battlePokemonId] = moveId
                val targets = listOfNotNull(resolvePokemon(activeBattle, message, 2))
                observer.observe(
                    Cobblemon173PublicObservation.MoveUsed(
                        turn = observedTurn,
                        actor = actor,
                        moveId = moveId,
                        targets = targets,
                        baseMovePriority = Moves.getByName(moveId)?.priority,
                        missed = message.hasOptionalArgument("miss"),
                    ),
                )
            }

            "-start", "-end", "-mustrecharge" -> observeActionConstraint(activeBattle, message)

            "-miss", "-fail", "-block", "-notarget", "cant", "-crit", "-supereffective",
            "-resisted", "-immune", "-hitcount", "-activate", "-singleturn" -> {
                observeActionConstraint(activeBattle, message)
                observeMoveOutcome(activeBattle, message)
            }

            "-ability" -> revealResource(activeBattle, message, ResourceKind.ABILITY)
            "-item", "-enditem" -> revealResource(activeBattle, message, ResourceKind.ITEM)
            "-damage", "-heal" -> resolvePokemon(activeBattle, message, 0)?.let { current ->
                val publicHp = parseHpFraction(message.argumentAt(1))
                val publicSourceEffectId = publicSourceEffectId(message)
                observer.observe(
                    Cobblemon173PublicObservation.HpChanged(
                        turn = observedTurn,
                        pokemon = if (publicHp == null) current else current.copy(
                            hpFraction = publicHp,
                            fainted = publicHp == 0.0,
                        ),
                        allowPrecedingActionLink = message.id == "-damage" && publicSourceEffectId == null,
                        publicSourceEffectId = publicSourceEffectId,
                    ),
                )
            }

            "-status", "-curestatus" -> resolvePokemon(activeBattle, message, 0)?.let {
                observer.observe(Cobblemon173PublicObservation.StatusChanged(observedTurn, it))
            }

            "faint" -> resolvePokemon(activeBattle, message, 0)?.let {
                observer.observe(Cobblemon173PublicObservation.Fainted(observedTurn, it))
            }

            "-weather" -> {
                val id = effectId(message.argumentAt(0)).takeUnless { it.isBlank() || it == "none" }
                observer.observe(
                    Cobblemon173PublicObservation.WeatherChanged(
                        turn = observedTurn,
                        weatherId = id,
                        durationTurns = id?.let(Cobblemon173PublicEffectDurationKnowledge::weather),
                        upkeep = message.hasOptionalArgument("upkeep"),
                    ),
                )
            }

            "-fieldstart", "-fieldend" -> {
                val id = effectId(message.argumentAt(0)).takeIf(String::isNotBlank) ?: return
                val scope = fieldScope(id)
                observer.observe(
                    Cobblemon173PublicObservation.FieldEffectChanged(
                        turn = observedTurn,
                        effectId = id,
                        scope = scope,
                        active = message.id == "-fieldstart",
                        durationTurns = Cobblemon173PublicEffectDurationKnowledge.field(id, scope),
                    ),
                )
            }

            "-sidestart", "-sideend" -> {
                val side = resolveSide(activeBattle, message.argumentAt(0)) ?: return
                val id = effectId(message.argumentAt(1)).takeIf(String::isNotBlank) ?: return
                observer.observe(
                    Cobblemon173PublicObservation.SideConditionChanged(
                        turn = observedTurn,
                        side = side,
                        effectId = id,
                        active = message.id == "-sidestart",
                        durationTurns = Cobblemon173PublicEffectDurationKnowledge.side(id),
                    ),
                )
            }
        }
    }

    private fun observeActionConstraint(activeBattle: PokemonBattle, message: BattleMessage) {
        val descriptor = actionConstraintDescriptor(message) ?: return
        val pokemon = resolvePokemon(activeBattle, message, descriptor.pokemonArgument) ?: return
        val lockedMoveId = if (descriptor.kind == BattleActionConstraintKind.ENCORE && descriptor.active) {
            lastMoveByPokemon[pokemon.battlePokemonId] ?: return
        } else {
            null
        }
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = observedTurn,
                pokemon = pokemon,
                kind = descriptor.kind,
                active = descriptor.active,
                lockedMoveId = lockedMoveId,
            ),
        )
    }

    private fun revealResource(activeBattle: PokemonBattle, message: BattleMessage, kind: ResourceKind) {
        val pokemon = resolvePokemon(activeBattle, message, 0) ?: return
        val id = effectId(message.argumentAt(1)).takeIf(String::isNotBlank) ?: return
        when (kind) {
            ResourceKind.ABILITY -> observer.observe(
                Cobblemon173PublicObservation.AbilityRevealed(observedTurn, pokemon, id),
            )

            ResourceKind.ITEM -> observer.observe(
                Cobblemon173PublicObservation.HeldItemRevealed(observedTurn, pokemon, id),
            )
        }
    }

    private fun observeMoveOutcome(activeBattle: PokemonBattle, message: BattleMessage) {
        val descriptor = moveOutcomeDescriptor(message) ?: return
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                turn = observedTurn,
                outcome = descriptor.outcome,
                source = descriptor.sourceArgument?.let { resolvePokemon(activeBattle, message, it) },
                targets = descriptor.targetArguments.mapNotNull { resolvePokemon(activeBattle, message, it) },
            ),
        )
    }

    private fun revealOptionalSource(activeBattle: PokemonBattle, message: BattleMessage) {
        val effect = message.effect("from") ?: return
        val kind = when (effect.type) {
            Effect.Type.ABILITY -> ResourceKind.ABILITY
            Effect.Type.ITEM -> ResourceKind.ITEM
            else -> return
        }
        val owner = runCatching {
            message.battlePokemonFromOptional(activeBattle, "of") ?: message.battlePokemon(0, activeBattle)
        }.getOrNull() ?: return
        val pokemon = owner.toPublicSnapshot()
        when (kind) {
            ResourceKind.ABILITY -> observer.observe(
                Cobblemon173PublicObservation.AbilityRevealed(observedTurn, pokemon, effect.id),
            )

            ResourceKind.ITEM -> observer.observe(
                Cobblemon173PublicObservation.HeldItemRevealed(observedTurn, pokemon, effect.id),
            )
        }
    }

    private fun resolvePokemon(
        activeBattle: PokemonBattle,
        message: BattleMessage,
        argument: Int,
    ): Cobblemon173PublicPokemonSnapshot? = runCatching {
        message.battlePokemon(argument, activeBattle)?.toPublicSnapshot()
    }.getOrNull()

    private fun BattlePokemon.toPublicSnapshot(): Cobblemon173PublicPokemonSnapshot {
        val activeSlot = actor.activePokemon.indexOfFirst { it.battlePokemon?.uuid == uuid }.takeIf { it >= 0 }
        val visiblePokemon = activeSlot?.let { actor.activePokemon[it].illusion } ?: this
        val visible = visiblePokemon.effectedPokemon
        val opponentIsNotVisible = sideOf(this) == BattleSide.OPPONENT && activeSlot == null
        return Cobblemon173PublicPokemonSnapshot(
            battlePokemonId = uuid,
            side = sideOf(this),
            activeSlot = activeSlot,
            speciesId = if (opponentIsNotVisible) UNKNOWN_PUBLIC_SPECIES_ID else visible.species.resourceIdentifier.toString(),
            formId = visible.form.name.takeUnless { opponentIsNotVisible },
            level = visible.level.takeUnless { opponentIsNotVisible },
            hpFraction = if (opponentIsNotVisible) 1.0 else fraction(health, maxHealth),
            statusId = effectedPokemon.status?.status?.name?.toString().takeUnless { opponentIsNotVisible },
            statStages = if (opponentIsNotVisible) emptyMap() else statChanges.mapKeys { it.key.identifier.toString() },
            fainted = if (opponentIsNotVisible) false else health <= 0,
            knownTypeIds = if (opponentIsNotVisible) emptySet() else visible.form.types.mapTo(linkedSetOf()) { it.name },
            combatStats = if (opponentIsNotVisible) null else {
                Cobblemon173PublicStatHypothesis.fromForm(visible.level, visible.form)
            },
            knownFormStates = if (opponentIsNotVisible) emptyMap() else {
                Cobblemon173KnownFormStates.publicRanges(visible.level, visible.species)
            },
        )
    }

    private fun BattlePokemon.toOwnState(owner: BattleActor): BattlePokemonStateView {
        val pokemon = effectedPokemon
        val heldItem = pokemon.heldItem()
        return BattlePokemonStateView(
            battlePokemonId = uuid,
            side = BattleSide.ALLY,
            activeSlot = owner.activePokemon.indexOfFirst { it.battlePokemon?.uuid == uuid }.takeIf { it >= 0 },
            speciesId = pokemon.species.resourceIdentifier.toString(),
            formId = pokemon.form.name,
            level = pokemon.level,
            hpFraction = fraction(health, maxHealth),
            statusId = pokemon.status?.status?.name?.toString(),
            statStages = statChanges.mapKeys { it.key.identifier.toString() },
            knownMoveIds = moveSet.getMoves().mapTo(linkedSetOf()) { it.name },
            knownAbilityId = pokemon.ability.name,
            knownHeldItemId = if (heldItem.isEmpty) null else BuiltInRegistries.ITEM.getKey(heldItem.item).toString(),
            fainted = health <= 0,
            knownTypeIds = pokemon.form.types.mapTo(linkedSetOf()) { it.name },
            combatStats = Cobblemon173PublicStatHypothesis.exactOwn(
                maxHp = pokemon.maxHealth,
                attack = pokemon.attack,
                defence = pokemon.defence,
                specialAttack = pokemon.specialAttack,
                specialDefence = pokemon.specialDefence,
                speed = pokemon.speed,
            ),
            knownFormStates = Cobblemon173KnownFormStates.exactOwn(pokemon),
        )
    }

    private fun sideOf(pokemon: BattlePokemon): BattleSide =
        if (pokemon.actor.uuid == opponentActorId) BattleSide.OPPONENT else BattleSide.ALLY

    private fun resolveSide(activeBattle: PokemonBattle, raw: String?): BattleSide? {
        val showdownSide = raw?.getOrNull(1) ?: return null
        val side = when (showdownSide) {
            '1' -> activeBattle.side1
            '2' -> activeBattle.side2
            else -> return null
        }
        return if (side.actors.any { it.uuid == opponentActorId }) BattleSide.OPPONENT else BattleSide.ALLY
    }

    private fun fraction(value: Int, maximum: Int): Double =
        if (maximum <= 0) 0.0 else (value.toDouble() / maximum).coerceIn(0.0, 1.0)

    private enum class ResourceKind { ABILITY, ITEM }

    internal companion object {
        private const val UNKNOWN_PUBLIC_SPECIES_ID = "showdown:unknown"
        private val ROOM_EFFECT_IDS = setOf("trickroom", "wonderroom", "magicroom")
        private val PARTIAL_TRAPPING_MOVE_IDS = setOf(
            "bind",
            "clamp",
            "firespin",
            "infestation",
            "magmastorm",
            "sandtomb",
            "snaptrap",
            "thundercage",
            "whirlpool",
            "wrap",
        )
        private val PUBLIC_LEVEL = Regex("(?:^|,\\s*)L(\\d+)(?:,|$)")

        fun publicSwitchSnapshot(
            resolved: Cobblemon173PublicPokemonSnapshot,
            publicDetails: String?,
        ): Cobblemon173PublicPokemonSnapshot {
            val publicName = publicDetails.orEmpty().substringBefore(',').trim()
            val showdownSpeciesId = effectId(publicName).takeIf(String::isNotBlank)
            val publicLevel = PUBLIC_LEVEL.find(publicDetails.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            return resolved.copy(
                speciesId = showdownSpeciesId?.let { "showdown:$it" } ?: UNKNOWN_PUBLIC_SPECIES_ID,
                formId = null,
                level = publicLevel,
            )
        }

        fun publicTurn(raw: String?, currentBattleTurn: Int, previous: Int): Int {
            require(currentBattleTurn >= 0)
            require(previous >= 0)
            return raw?.toIntOrNull()?.coerceIn(0, currentBattleTurn) ?: previous.coerceAtMost(currentBattleTurn)
        }

        fun effectId(raw: String?): String {
            val value = raw.orEmpty()
            if (value.isBlank()) return ""
            return runCatching { Effect.Companion.parse(value)?.id }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: value.substringAfter(": ", value)
                    .lowercase(Locale.ROOT)
                    .filter(Char::isLetterOrDigit)
        }

        fun parseHpFraction(raw: String?): Double? {
            val value = raw.orEmpty().substringBefore(' ')
            if (value == "0") return 0.0
            val parts = value.split('/')
            if (parts.size != 2) return null
            val current = parts[0].toDoubleOrNull() ?: return null
            val maximum = parts[1].toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
            return (current / maximum).coerceIn(0.0, 1.0)
        }

        fun publicSourceEffectId(message: BattleMessage): String? =
            message.effect("from")?.id?.takeIf(String::isNotBlank)

        internal fun actionConstraintDescriptor(message: BattleMessage): ShowdownActionConstraintDescriptor? {
            val effect = effectId(message.argumentAt(1))
            return when (message.id) {
                "-start" -> when (effect) {
                    "taunt" -> ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TAUNT, true)
                    "encore" -> ShowdownActionConstraintDescriptor(BattleActionConstraintKind.ENCORE, true)
                    else -> null
                }
                "-end" -> when {
                    effect == "taunt" -> ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TAUNT, false)
                    effect == "encore" -> ShowdownActionConstraintDescriptor(BattleActionConstraintKind.ENCORE, false)
                    effect in PARTIAL_TRAPPING_MOVE_IDS || message.hasOptionalArgument("partiallytrapped") ->
                        ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TRAPPED, false)
                    else -> null
                }
                "-mustrecharge" -> ShowdownActionConstraintDescriptor(BattleActionConstraintKind.RECHARGE, true)
                "cant" -> when (effect) {
                    "recharge" -> ShowdownActionConstraintDescriptor(BattleActionConstraintKind.RECHARGE, false)
                    "trapped" -> ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TRAPPED, true)
                    else -> null
                }
                "-activate" -> when {
                    effect == "trapped" -> ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TRAPPED, true)
                    effect in PARTIAL_TRAPPING_MOVE_IDS && message.hasOptionalArgument("of") ->
                        ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TRAPPED, true)
                    else -> null
                }
                else -> null
            }
        }

        fun moveOutcomeDescriptor(message: BattleMessage): ShowdownMoveOutcomeDescriptor? = when (message.id) {
            "-miss" -> ShowdownMoveOutcomeDescriptor(
                BattleMoveOutcomeView(BattleMoveOutcomeKind.MISSED),
                sourceArgument = 0,
                targetArguments = listOf(1),
            )
            "-fail" -> ShowdownMoveOutcomeDescriptor(
                BattleMoveOutcomeView(
                    BattleMoveOutcomeKind.FAILED,
                    moveId = effectId(message.argumentAt(1)).takeIf(String::isNotBlank),
                ),
                targetArguments = listOf(0),
            )
            "-block" -> ShowdownMoveOutcomeDescriptor(
                BattleMoveOutcomeView(
                    BattleMoveOutcomeKind.BLOCKED,
                    moveId = effectId(message.argumentAt(2)).takeIf(String::isNotBlank),
                    publicEffectId = effectId(message.argumentAt(1)).takeIf(String::isNotBlank),
                ),
                sourceArgument = 3,
                targetArguments = listOf(0),
            )
            "-notarget" -> ShowdownMoveOutcomeDescriptor(
                BattleMoveOutcomeView(BattleMoveOutcomeKind.NO_TARGET),
                sourceArgument = 0,
            )
            "cant" -> ShowdownMoveOutcomeDescriptor(
                BattleMoveOutcomeView(
                    BattleMoveOutcomeKind.CANNOT_ACT,
                    moveId = effectId(message.argumentAt(2)).takeIf(String::isNotBlank),
                    publicEffectId = effectId(message.argumentAt(1)).takeIf(String::isNotBlank),
                ),
                sourceArgument = 0,
            )
            "-crit" -> targetOutcome(BattleMoveOutcomeKind.CRITICAL_HIT)
            "-supereffective" -> targetOutcome(BattleMoveOutcomeKind.SUPER_EFFECTIVE)
            "-resisted" -> targetOutcome(BattleMoveOutcomeKind.RESISTED)
            "-immune" -> targetOutcome(BattleMoveOutcomeKind.IMMUNE)
            "-hitcount" -> message.argumentAt(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { count ->
                ShowdownMoveOutcomeDescriptor(
                    BattleMoveOutcomeView(BattleMoveOutcomeKind.HIT_COUNT, hitCount = count),
                    targetArguments = listOf(0),
                )
            }
            "-activate" -> if (
                effectId(message.argumentAt(1)) == "substitute" && message.hasOptionalArgument("damage")
            ) {
                publicEffectOutcome(BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED, "substitute")
            } else {
                null
            }
            "-singleturn" -> if (effectId(message.argumentAt(1)) in STALL_COUNTER_EFFECT_IDS) {
                publicEffectOutcome(BattleMoveOutcomeKind.PROTECTION_STARTED, "protect")
            } else {
                null
            }
            else -> null
        }

        private fun targetOutcome(kind: BattleMoveOutcomeKind) = ShowdownMoveOutcomeDescriptor(
            BattleMoveOutcomeView(kind),
            targetArguments = listOf(0),
        )

        private fun publicEffectOutcome(kind: BattleMoveOutcomeKind, publicEffectId: String) =
            ShowdownMoveOutcomeDescriptor(
                BattleMoveOutcomeView(kind, publicEffectId = publicEffectId),
                targetArguments = listOf(0),
            )

        private val STALL_COUNTER_EFFECT_IDS = setOf("protect", "endure", "maxguard", "quickguard", "wideguard")

        private fun fieldScope(id: String): FieldEffectScope = when {
            id.endsWith("terrain") -> FieldEffectScope.TERRAIN
            id in ROOM_EFFECT_IDS -> FieldEffectScope.ROOM
            else -> FieldEffectScope.GLOBAL
        }
    }
}

internal data class ShowdownMoveOutcomeDescriptor(
    val outcome: BattleMoveOutcomeView,
    val sourceArgument: Int? = null,
    val targetArguments: List<Int> = emptyList(),
) {
    init {
        require(sourceArgument == null || sourceArgument >= 0)
        require(targetArguments.all { it >= 0 })
        require(targetArguments.distinct().size == targetArguments.size)
    }
}

internal data class ShowdownActionConstraintDescriptor(
    val kind: BattleActionConstraintKind,
    val active: Boolean,
    val pokemonArgument: Int = 0,
) {
    init {
        require(pokemonArgument >= 0)
    }
}
