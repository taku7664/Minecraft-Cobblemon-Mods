package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.api.battles.interpreter.BattleMessage
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import com.cobblemon.mod.common.battles.ShowdownPokemon
import com.cobblemon.mod.common.battles.ShowdownSide
import jbro.cobblemon.mcc.betterai.mechanics.copyState
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleMoveTargetPattern
import jbro.cobblemon.mcc.internal.ai.BattleAbilityAvailability
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleObservedEventKind
import jbro.cobblemon.mcc.internal.ai.BattleInferenceConfidence
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattleMoveOutcomeKind
import jbro.cobblemon.mcc.internal.ai.BattleMoveOutcomeView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonFormStateView
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionConstraintView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.PublicAbilityPossibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Cobblemon173PublicBattleObserverTest {
    @Test
    fun `Perish Song public countdown replaces the previous number`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        for (count in listOf(3, 2, 1)) {
            val parsed = Cobblemon173ShowdownObservationAdapter.volatileChange(
                BattleMessage("|-start|p2a: test|perish$count"))!!
            assertEquals("perishsong:$count" to true, parsed)
            observer.observe(Cobblemon173PublicObservation.VolatileChanged(4 - count, actor, parsed.first, parsed.second))
        }
        assertEquals(setOf("perishsong", "perishsong:1"), observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
    }

    @Test
    fun `observed Heal Block carries its public remaining duration and ends`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, actor, "healblock", true))
        assertTrue("healblockturns:5" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
        observer.advanceTurn(2)
        assertTrue("healblockturns:4" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(2, actor, "healblock", false))
        assertFalse(observer.publicSnapshot().pokemon.single().knownVolatileEffectIds.any { it.startsWith("healblock") })
    }

    @Test
    fun `Psychic Noise gives the public Heal Block its actual two turn duration`() {
        val source = publicPokemon(BattleSide.ALLY, 0)
        val target = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, source, "psychicnoise", listOf(target)))
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, target, "healblock", true))
        observer.advanceTurn(2)
        assertTrue("healblockturns:1" in observer.publicSnapshot().pokemon.single { it.side == BattleSide.OPPONENT }.knownVolatileEffectIds)
        observer.advanceTurn(3)
        assertFalse(observer.publicSnapshot().pokemon.single { it.side == BattleSide.OPPONENT }.knownVolatileEffectIds.any { it.startsWith("healblock") })
    }

    @Test
    fun `public Flash Fire and Charge messages preserve the active trait inputs`() {
        assertEquals("flashfire", Cobblemon173ShowdownObservationAdapter.directlyRevealedAbility(
            BattleMessage("|-start|p2a: test|ability: Flash Fire")))
        assertEquals("slowstart", Cobblemon173ShowdownObservationAdapter.directlyRevealedAbility(
            BattleMessage("|-start|p2a: test|ability: Slow Start")))
        assertEquals("flashfire" to true, Cobblemon173ShowdownObservationAdapter.volatileChange(
            BattleMessage("|-start|p2a: test|ability: Flash Fire")))
        assertEquals("charge" to true, Cobblemon173ShowdownObservationAdapter.volatileChange(
            BattleMessage("|-start|p2a: test|Charge|Thunderbolt|[from] ability: Electromorphosis")))
        assertEquals("charge" to false, Cobblemon173ShowdownObservationAdapter.volatileChange(
            BattleMessage("|-end|p2a: test|Charge")))
    }

    @Test
    fun `Laser Focus public start remains for the next turn only`() {
        val message = BattleMessage("|-start|p2a: test|move: Laser Focus")
        assertEquals("laserfocus" to true, Cobblemon173ShowdownObservationAdapter.volatileChange(message))
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, actor, "laserfocus", true))
        observer.advanceTurn(2)
        assertTrue("laserfocusturns:1" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
        observer.advanceTurn(3)
        assertFalse("laserfocus" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
    }

    @Test
    fun `Destiny Bond public single move ends when its holder attempts another move`() {
        assertEquals("destinybond" to true, Cobblemon173ShowdownObservationAdapter.volatileChange(
            BattleMessage("|-singlemove|p2a: test|Destiny Bond")))
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, actor, "destinybond", true))
        observer.advanceTurn(2)
        assertTrue("destinybond" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(2, actor, "tackle", emptyList()))
        assertFalse("destinybond" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
    }

    @Test
    fun `Endure public single turn expires when the next turn starts`() {
        assertEquals("endure" to true, Cobblemon173ShowdownObservationAdapter.volatileChange(
            BattleMessage("|-singleturn|p2a: test|move: Endure")))
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, actor, "endure", true))
        observer.advanceTurn(2)
        assertFalse("endure" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
    }

    @Test
    fun `Disable preserves the specific publicly disabled move`() {
        val parsed = Cobblemon173ShowdownObservationAdapter.volatileChange(
            BattleMessage("|-start|p2a: test|Disable|Thunderbolt"))!!
        assertEquals("disablemove:thunderbolt" to true, parsed)
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, actor, parsed.first, parsed.second))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(2, actor, "tackle", emptyList()))
        assertTrue("disablemove:thunderbolt" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
    }

    @Test
    fun `Leech Seed and binding preserve the public caster in the second opposing slot`() {
        for ((move, effect, prefix) in listOf(Triple("leechseed", "leechseed", "leechseedsource:"),
            Triple("firespin", "partiallytrapped", "partiallytrappedsource:"))) {
            val source = publicPokemon(BattleSide.OPPONENT, 1)
            val target = publicPokemon(BattleSide.ALLY, 0)
            val observer = Cobblemon173PublicBattleObserver(3)
            observer.observe(Cobblemon173PublicObservation.MoveUsed(1, source, move, listOf(target)))
            observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, target, effect, true))
            assertTrue(prefix + source.battlePokemonId in observer.publicSnapshot().pokemon
                .single { it.battlePokemonId == target.battlePokemonId }.knownVolatileEffectIds, move)
        }
    }

    @Test
    fun `Healing Wish stays on its public slot until its heal is observed`() {
        val source = publicPokemon(BattleSide.ALLY, 1)
        val incoming = publicPokemon(BattleSide.ALLY, 1)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, source, "healingwish", emptyList()))
        observer.observe(Cobblemon173PublicObservation.Fainted(1, source))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, incoming))
        assertEquals("healingwishslot:1", observer.publicSnapshot().field.sideConditions.getValue(BattleSide.ALLY).single().effectId)
        observer.observe(Cobblemon173PublicObservation.HpChanged(2, incoming, publicSourceEffectId = "healingwish"))
        assertTrue(observer.publicSnapshot().field.sideConditions.getValue(BattleSide.ALLY).isEmpty())
    }

    @Test
    fun `public rampage continuation records uses without inventing its hidden duration`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, actor, "outrage", emptyList()))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(2, actor, "outrage", emptyList(), ppLockedContinuation = true))
        assertEquals(setOf("rampagepublic:outrage:2"), observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(2, actor, "confusion", true))
        assertFalse(observer.publicSnapshot().pokemon.single().knownVolatileEffectIds.any { it.startsWith("rampagepublic:") })
    }

    @Test
    fun `consumed item is absent and its public history survives switching`() {
        val actor = publicPokemon(BattleSide.ALLY, 0)
        val replacement = publicPokemon(BattleSide.ALLY, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.HeldItemRevealed(1, actor, "sitrusberry",
            removed = true, consumed = true, ownAbilityAtRemoval = "unburden"))
        val consumed = observer.publicSnapshot().pokemon.single()
        assertNull(consumed.knownHeldItemId)
        assertTrue("unburden" in consumed.knownVolatileEffectIds)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(1, replacement))
        val benched = observer.publicSnapshot().pokemon.single { it.battlePokemonId == actor.battlePokemonId }
        assertTrue("better_ai:last_consumed_item=sitrusberry" in benched.knownVolatileEffectIds)
        assertFalse("unburden" in benched.knownVolatileEffectIds)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, actor))
        assertTrue("better_ai:last_consumed_item=sitrusberry" in observer.publicSnapshot().pokemon
            .single { it.battlePokemonId == actor.battlePokemonId }.knownVolatileEffectIds)
    }

    @Test
    fun `knocked off item can activate Unburden but cannot become a harvested berry`() {
        val actor = publicPokemon(BattleSide.ALLY, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.HeldItemRevealed(1, actor, "sitrusberry",
            removed = true, consumed = false, ownAbilityAtRemoval = "unburden"))
        val holder = observer.publicSnapshot().pokemon.single()
        assertNull(holder.knownHeldItemId)
        assertTrue("unburden" in holder.knownVolatileEffectIds)
        assertFalse(holder.knownVolatileEffectIds.any { it.startsWith("better_ai:last_consumed_item=") })
        assertFalse(Cobblemon173ShowdownObservationAdapter.itemWasConsumed(
            BattleMessage("|-enditem|p1a: test|Sitrus Berry|[from] move: Knock Off")))
        assertTrue(Cobblemon173ShowdownObservationAdapter.itemWasConsumed(
            BattleMessage("|-enditem|p1a: test|Sitrus Berry|[eat]")))
    }

    @Test
    fun `public Slow Start elapsed turns expire and entry evidence lasts only its turn`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(1, actor))
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, actor, "slowstart", true))
        assertTrue("better_ai:entered_this_turn" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
        observer.advanceTurn(2)
        assertTrue("better_ai:slow_start_turns=4" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
        assertFalse("better_ai:entered_this_turn" in observer.publicSnapshot().pokemon.single().knownVolatileEffectIds)
        observer.advanceTurn(6)
        assertFalse(observer.publicSnapshot().pokemon.single().knownVolatileEffectIds.any { it.contains("slowstart") || it.contains("slow_start_turns") })
    }

    @Test
    fun `public Wish follows its slot across a switch and expires after its due turn`() {
        val source = publicPokemon(BattleSide.ALLY, 1)
        val replacement = publicPokemon(BattleSide.ALLY, 1)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, source, "wish", emptyList()))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, replacement))
        val effect = observer.publicSnapshot().field.sideConditions.getValue(BattleSide.ALLY)
            .single { it.effectId == "wishslot1" }
        assertEquals(1, effect.remainingTurns)
        observer.advanceTurn(3)
        assertTrue(observer.publicSnapshot().field.sideConditions.getValue(BattleSide.ALLY).isEmpty())
    }

    @Test
    fun `Future Sight preserves the targeted opponent slot and a failed cast creates no effect`() {
        val source = publicPokemon(BattleSide.ALLY, 0)
        val target = publicPokemon(BattleSide.OPPONENT, 1)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, source, "futuresight", listOf(target)))
        assertEquals(3, observer.publicSnapshot().field.sideConditions.getValue(BattleSide.OPPONENT)
            .single { it.effectId == "futuresightslot1" }.remainingTurns)
        observer.observe(Cobblemon173PublicObservation.MoveOutcome(1,
            BattleMoveOutcomeView(BattleMoveOutcomeKind.FAILED, moveId = "futuresight"), source))
        assertTrue(observer.publicSnapshot().field.sideConditions.getValue(BattleSide.OPPONENT).isEmpty())
    }

    @Test
    fun `a failed repeat Wish keeps the already pending Wish`() {
        val source = publicPokemon(BattleSide.ALLY, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, source, "wish", emptyList()))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(2, source, "wish", emptyList()))
        observer.observe(Cobblemon173PublicObservation.MoveOutcome(2,
            BattleMoveOutcomeView(BattleMoveOutcomeKind.FAILED, moveId = "wish"), source))
        assertEquals(1, observer.publicSnapshot().field.sideConditions.getValue(BattleSide.ALLY)
            .single { it.effectId == "wishslot0" }.remainingTurns)
    }

    @Test
    fun `a visible Mega Trace form keeps its already publicly copied ability`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, actor, "levitate", baseAbilityId = "trace"))
        val megaStats = BattleCombatStatRangesView.exact(100, 100, 100, 200, 100, 100)
        val mega = actor.copy(formId = "mega", knownFormStates = mapOf("mega" to
            BattlePokemonFormStateView("mega", setOf("psychic"), megaStats, "trace")),
            knownTypeIds = setOf("psychic"), combatStats = megaStats)
        observer.observeActivePresence(mega)
        val current = observer.publicSnapshot().pokemon.single()
        assertEquals("mega", current.formId)
        assertEquals("levitate", current.knownAbilityId)
        assertEquals("trace", current.knownBaseAbilityId)
        assertEquals(setOf("psychic"), current.knownTypeIds)
    }

    @Test
    fun `a publicly visible Mega form replaces prior Trace without clearing active effects`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, actor, "levitate", baseAbilityId = "trace"))
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, actor, "confusion", true))
        observer.observe(Cobblemon173PublicObservation.ActionConstraintChanged(
            1, actor, BattleActionConstraintKind.TAUNT, true))
        val megaStats = BattleCombatStatRangesView.exact(100, 100, 100, 200, 100, 100)
        val mega = actor.copy(formId = "mega", knownFormStates = mapOf("mega" to
            BattlePokemonFormStateView("mega", setOf("fairy"), megaStats, "pixilate")),
            knownTypeIds = setOf("fairy"), combatStats = megaStats)
        observer.observeActivePresence(mega)
        val current = observer.publicSnapshot().pokemon.single()
        assertEquals("mega", current.formId)
        assertEquals("pixilate", current.knownAbilityId)
        assertEquals("pixilate", current.knownBaseAbilityId)
        assertTrue("confusion" in current.knownVolatileEffectIds)
        assertTrue(current.actionConstraints.taunted)
        val replacement = publicPokemon(BattleSide.OPPONENT, 0)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, replacement))
        val benched = observer.publicSnapshot().pokemon.single { it.battlePokemonId == actor.battlePokemonId }
        assertEquals("pixilate", benched.knownAbilityId)
    }

    @Test
    fun `own request resolves copied and permanent abilities by owned Pokemon identity`() {
        val ownId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        for ((currentAbility, baseAbility) in listOf("levitate" to "trace", "pixilate" to "pixilate")) {
            val request = ShowdownActionRequest().also { action ->
                action.side = ShowdownSide().also { side ->
                    side.pokemon = listOf(
                        ShowdownPokemon().also {
                            it.details = "Gardevoir, $otherId"
                            it.ability = "hiddenotherability"
                            it.baseAbility = "hiddenotherability"
                        },
                        ShowdownPokemon().also {
                            it.details = "Gardevoir, $ownId"
                            it.ability = currentAbility
                            it.baseAbility = baseAbility
                        },
                    )
                }
            }
            assertEquals(currentAbility to baseAbility,
                Cobblemon173ShowdownObservationAdapter.ownAbilityState(request, ownId, "trace"))
            assertEquals("trace" to null,
                Cobblemon173ShowdownObservationAdapter.ownAbilityState(request, UUID.randomUUID(), "trace"))
        }
        assertEquals("trace" to null,
            Cobblemon173ShowdownObservationAdapter.ownAbilityState(null, ownId, "trace"))
    }

    @Test
    fun `a Mega permanent ability overrides an older public Trace copy in the brain input`() {
        val own = ownPokemon(formId = "mega").copyState(knownAbilityId = "pixilate", knownBaseAbilityId = "pixilate")
        val actor = publicPokemon(BattleSide.ALLY, 0).copy(battlePokemonId = own.battlePokemonId)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, actor, "levitate", baseAbilityId = "trace"))
        val assembled = Cobblemon173BattleStateAssembler.assemble(
            UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(own), observer.publicSnapshot(),
            inferenceKnowledge = { _, _ -> emptyList() },
        ).pokemon.single()
        assertEquals("pixilate", assembled.knownAbilityId)
        assertEquals("pixilate", assembled.knownBaseAbilityId)
    }

    @Test
    fun `own Trace public copy reaches the final brain input and restores on observed switching`() {
        val own = ownPokemon().copyState(knownAbilityId = "trace")
        val actor = publicPokemon(BattleSide.ALLY, 0).copy(battlePokemonId = own.battlePokemonId)
        val replacementOwn = ownPokemon().copyState(activeSlot = null)
        val replacement = publicPokemon(BattleSide.ALLY, 0).copy(battlePokemonId = replacementOwn.battlePokemonId)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, actor, "levitate", baseAbilityId = "trace"))
        fun assembled(current: BattlePokemonStateView) = Cobblemon173BattleStateAssembler.assemble(
            UUID.randomUUID(), BattleFormat.SINGLE, 2,
            listOf(current, replacementOwn.copyState(activeSlot = if (current.activeSlot == null) 0 else null)),
            observer.publicSnapshot(),
            inferenceKnowledge = { _, _ -> emptyList() },
        ).pokemon.single { it.battlePokemonId == own.battlePokemonId }
        val copied = assembled(own)
        assertEquals("levitate", copied.knownAbilityId)
        assertEquals("trace", copied.knownBaseAbilityId)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, replacement))
        val benched = assembled(own.copyState(activeSlot = null))
        assertEquals("trace", benched.knownAbilityId)
        assertEquals("trace", benched.knownBaseAbilityId)
    }

    @Test
    fun `a publicly changed Trace origin replaces the earlier own ability in the brain input`() {
        val own = ownPokemon().copyState(knownAbilityId = "magicguard")
        val actor = publicPokemon(BattleSide.ALLY, 0).copy(battlePokemonId = own.battlePokemonId)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, actor, "levitate", baseAbilityId = "trace"))
        val assembled = Cobblemon173BattleStateAssembler.assemble(
            UUID.randomUUID(), BattleFormat.SINGLE, 1, listOf(own), observer.publicSnapshot(),
            inferenceKnowledge = { _, _ -> emptyList() },
        ).pokemon.single()
        assertEquals("levitate", assembled.knownAbilityId)
        assertEquals("trace", assembled.knownBaseAbilityId)
    }

    @Test
    fun `an observed switch restores publicly known Trace before the next entry`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val replacement = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(2)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, actor, "levitate", baseAbilityId = "trace"))
        val captured = observer.publicSnapshot().pokemon.single()
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, replacement))
        val benched = observer.publicSnapshot().pokemon.single { it.battlePokemonId == actor.battlePokemonId }
        assertNull(benched.activeSlot)
        assertEquals("trace", benched.knownAbilityId)
        assertEquals("trace", benched.knownBaseAbilityId)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(3, actor))
        val reentered = observer.publicSnapshot().pokemon.single { it.battlePokemonId == actor.battlePokemonId }
        assertEquals("trace", reentered.knownAbilityId)
        assertEquals("levitate", captured.knownAbilityId)
    }

    @Test
    fun `a publicly announced Trace origin survives observer state copies and snapshots`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, actor, "levitate", baseAbilityId = "trace"))
        val captured = observer.publicSnapshot().pokemon.single()
        observer.observe(Cobblemon173PublicObservation.HeldItemRevealed(1, actor, "leftovers"))
        observer.observe(Cobblemon173PublicObservation.HpChanged(1, actor.copy(hpFraction = 0.5)))
        val current = observer.publicSnapshot().pokemon.single()
        assertEquals("trace", captured.knownBaseAbilityId)
        assertEquals("trace", current.knownBaseAbilityId)
        assertEquals("levitate", current.knownAbilityId)
        assertEquals(1.0, captured.hpFraction)
        assertEquals(0.5, current.hpFraction)
    }

    @Test
    fun `a revealed current ability does not invent an unknown permanent ability`() {
        val actor = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, actor, "levitate"))
        observer.observe(Cobblemon173PublicObservation.HpChanged(1, actor.copy(hpFraction = 0.5)))
        val current = observer.publicSnapshot().pokemon.single()
        assertEquals("levitate", current.knownAbilityId)
        assertNull(current.knownBaseAbilityId)
    }

    @Test
    fun `own copied request moves survive a locked request and assembler drops them after switch`() {
        val own = ownPokemon()
        val actor = publicPokemon(BattleSide.ALLY, 0).copy(battlePokemonId = own.battlePokemonId)
        val opponent = publicPokemon(BattleSide.OPPONENT, 0)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        observer.observeTransformation(actor.battlePokemonId, opponent.battlePokemonId)
        observer.observeOwnCopiedMoves(actor.battlePokemonId, setOf("fly", "splash"))
        val captured = observer.publicSnapshot()
        observer.observeOwnCopiedMoves(actor.battlePokemonId, setOf("fly"))
        observer.observeOwnCopiedMoves(actor.battlePokemonId, setOf("recharge", "struggle"))
        observer.observeTransformation(opponent.battlePokemonId)
        observer.observeOwnCopiedMoves(opponent.battlePokemonId, setOf("hiddenmove"))
        fun assembled() = Cobblemon173BattleStateAssembler.assemble(UUID.randomUUID(), BattleFormat.SINGLE,
            3, listOf(own), observer.publicSnapshot(), inferenceKnowledge = { _, _ -> emptyList() })
            .pokemon.single { it.battlePokemonId == own.battlePokemonId }.knownMoveIds
        assertEquals(setOf("fly", "splash"), assembled())
        assertTrue(observer.publicSnapshot().pokemon.single { it.battlePokemonId == opponent.battlePokemonId }.knownMoveIds.isEmpty())
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(3, actor))
        observer.observeOwnCopiedMoves(actor.battlePokemonId, setOf("stale"))
        assertEquals(own.knownMoveIds, assembled())
        assertEquals(setOf(actor.battlePokemonId), captured.transformedPokemon)
        assertEquals(setOf(opponent.battlePokemonId), observer.publicSnapshot().transformedPokemon)
    }

    @Test
    fun `transform copies only revealed target moves and restores original moves on switch`() {
        val actor = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val bench = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 2)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, target, "splash", emptyList()))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, actor, "transform", listOf(target)))
        observer.observeTransformation(actor.battlePokemonId, target.battlePokemonId)
        fun moves(id: UUID) = observer.publicSnapshot().pokemon.single { it.battlePokemonId == id }.knownMoveIds
        assertEquals(setOf("splash"), moves(actor.battlePokemonId))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(2, actor, "tackle", emptyList()))
        assertEquals(setOf("splash", "tackle"), moves(actor.battlePokemonId))
        assertEquals(setOf("splash"), moves(target.battlePokemonId))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(3, bench))
        assertEquals(setOf("transform"), moves(actor.battlePokemonId))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(4, actor))
        assertEquals(setOf("transform"), moves(actor.battlePokemonId))
        observer.observeTransformation(actor.battlePokemonId, UUID.randomUUID())
        assertTrue(moves(actor.battlePokemonId).isEmpty())
    }

    @Test
    fun `copied PP is isolated and original expenditure returns on switching out`() {
        val actor = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val bench = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 2)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, actor))
        observer.observePpLoss(actor.battlePokemonId, "transform", 1)
        observer.observePpLoss(actor.battlePokemonId, "tackle", 7)
        observer.observeTransformation(actor.battlePokemonId)
        assertEquals(setOf(actor.battlePokemonId), observer.transformedPokemon())
        assertEquals(emptyMap<String, Int>(), observer.publicPpSpent()[actor.battlePokemonId])
        observer.observePpLoss(actor.battlePokemonId, "tackle", 2)
        observer.observePpRestore(actor.battlePokemonId, "tackle", 1, 5)
        assertEquals(mapOf("tackle" to 1), observer.publicPpSpent()[actor.battlePokemonId])
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, bench))
        assertTrue(observer.transformedPokemon().isEmpty())
        assertEquals(mapOf("transform" to 1, "tackle" to 7), observer.publicPpSpent()[actor.battlePokemonId])
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(3, actor))
        observer.observeTransformation(actor.battlePokemonId)
        assertEquals(emptyMap<String, Int>(), observer.publicPpSpent()[actor.battlePokemonId])
        observer.reset()
        assertTrue(observer.publicPpSpent().isEmpty())
        assertTrue(observer.transformedPokemon().isEmpty())
    }

    @Test
    fun `copied PP restoration caps expenditure at copied capacity not original capacity`() {
        val id = UUID.randomUUID()
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 1)
        observer.observeTransformation(id)
        observer.observePpLoss(id, "tackle", 30)
        observer.observePpRestore(id, "tackle", 10, 56)
        assertEquals(0, observer.publicPpSpent()[id]?.get("tackle"))
        observer.observePpLoss(id, "tackle", 1)
        assertEquals(1, observer.publicPpSpent()[id]?.get("tackle"))
    }

    @Test
    fun `old Kotlin default argument constructor remains callable`() {
        val constructor = BattlePokemonStateView::class.java.constructors.single {
            it.parameterCount == 19 && it.parameterTypes.last().name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        val result = constructor.newInstance(UUID.randomUUID(), BattleSide.ALLY, 0, "pikachu", null, 50,
            1.0, null, emptyMap<String, Int>(), emptySet<String>(), null, null, false,
            null, null, null, null, (1 shl 13) or (1 shl 14) or (1 shl 15) or (1 shl 16), null) as BattlePokemonStateView
        assertTrue(result.knownVolatileEffectIds.isEmpty())
        assertEquals(BattlePokemonActionConstraintView.empty(), result.actionConstraints)
    }

    @Test
    fun `only explicit substitute protocol establishes effect or transfer`() {
        fun message(line: String) = BattleMessage(line)
        assertEquals(true, Cobblemon173ShowdownObservationAdapter.substituteChange(message("|-start|p1a: test|Substitute")))
        assertEquals(false, Cobblemon173ShowdownObservationAdapter.substituteChange(message("|-end|p1a: test|Substitute")))
        assertNull(Cobblemon173ShowdownObservationAdapter.substituteChange(message("|-activate|p1a: test|move: Substitute|[damage]")))
        assertTrue(Cobblemon173ShowdownObservationAdapter.transfersSubstitute(message("|switch|p1a: next|Pikachu|100/100|[from] Baton Pass")))
        assertTrue(Cobblemon173ShowdownObservationAdapter.transfersSubstitute(message("|switch|p1a: next|Pikachu|100/100|[from] Shed Tail")))
        assertFalse(Cobblemon173ShowdownObservationAdapter.transfersSubstitute(message("|drag|p1a: next|Pikachu|100/100|[from] Baton Pass")))
        assertFalse(Cobblemon173ShowdownObservationAdapter.transfersSubstitute(message("|switch|p1a: next|Pikachu|100/100|[from] U-turn")))
    }

    @Test
    fun `substitute transfers only from matching active slot and clears on ordinary switch`() {
        val first = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val second = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val other = publicPokemon(BattleSide.OPPONENT, activeSlot = 1)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, other))
        observer.observe(Cobblemon173PublicObservation.SubstituteChanged(1, first, true))
        observer.observe(Cobblemon173PublicObservation.SubstituteChanged(1, other, true))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(1, second, transfersSubstitute = true))
        fun effects(id: UUID) = observer.publicSnapshot().pokemon.single { it.battlePokemonId == id }.knownVolatileEffectIds
            .filterNot { it.startsWith("better_ai:") }.toSet()
        assertEquals(emptySet<String>(), effects(first.battlePokemonId))
        assertEquals(setOf("substitute"), effects(second.battlePokemonId))
        assertEquals(setOf("substitute"), effects(other.battlePokemonId))
        observer.observe(Cobblemon173PublicObservation.SubstituteChanged(2, second, false))
        assertTrue(effects(second.battlePokemonId).isEmpty())
        observer.observe(Cobblemon173PublicObservation.SubstituteChanged(2, second, true))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(3, first))
        assertTrue(effects(second.battlePokemonId).isEmpty())
        assertTrue(effects(first.battlePokemonId).isEmpty())
        observer.observe(Cobblemon173PublicObservation.Fainted(3, other))
        assertTrue(effects(other.battlePokemonId).isEmpty())
    }

    @Test
    fun `Baton Pass observation copies only public boosts passable effects and their metadata`() {
        val outgoing = publicPokemon(BattleSide.ALLY, 0).copy(statStages = mapOf("atk" to 2, "spe" to 1))
        val incoming = publicPokemon(BattleSide.ALLY, 0)
        val otherSlot = publicPokemon(BattleSide.ALLY, 1).copy(statStages = mapOf("def" to 4))
        val seedCaster = publicPokemon(BattleSide.OPPONENT, 1)
        val observer = Cobblemon173PublicBattleObserver(3)
        listOf(outgoing, otherSlot, seedCaster).forEach {
            observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, it))
        }
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, seedCaster, "leechseed", listOf(outgoing)))
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, outgoing, "leechseed", true))
        for (effect in listOf("focusenergy", "laserfocus", "perishsong:2", "healblock", "endure", "destinybond", "slowstart"))
            observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, outgoing, effect, true))
        observer.observe(Cobblemon173PublicObservation.SubstituteChanged(1, outgoing, true))
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, otherSlot, "confusion", true))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, outgoing, "batonpass", emptyList()))
        // The real adapter closes the action window before dispatching the public switch event.
        observer.closeActionWindow()
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(1, incoming, transfersSubstitute = true,
            publicTransferMoveId = Cobblemon173ShowdownObservationAdapter.publicSwitchTransferMove(
                BattleMessage("|switch|p1a: next|Pikachu|100/100|[from] Baton Pass"))))
        val arrived = observer.publicSnapshot().pokemon.single { it.battlePokemonId == incoming.battlePokemonId }
        assertEquals(outgoing.statStages, arrived.statStages)
        val effects = arrived.knownVolatileEffectIds
        assertTrue(effects.containsAll(setOf("substitute", "leechseed", "leechseedsource:${seedCaster.battlePokemonId}",
            "focusenergy", "laserfocus", "laserfocusturns:2", "perishsong", "perishsong:2", "healblock", "healblockturns:5")))
        assertFalse(effects.any { it in setOf("confusion", "endure", "destinybond", "slowstart") || it.startsWith("better_ai:slow_start_turns=") })
        assertFalse(effects.any { it.startsWith("substitutehp:") }, "The public log did not reveal the substitute HP")
    }

    @Test
    fun `Shed Tail observation passes only its public substitute and no boosts or other effects`() {
        val outgoing = publicPokemon(BattleSide.ALLY, 0).copy(statStages = mapOf("atk" to 3))
        val incoming = publicPokemon(BattleSide.ALLY, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, outgoing))
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, outgoing, "focusenergy", true))
        observer.observe(Cobblemon173PublicObservation.SubstituteChanged(1, outgoing, true))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, outgoing, "shedtail", emptyList()))
        observer.closeActionWindow()
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(1, incoming, transfersSubstitute = true,
            publicTransferMoveId = Cobblemon173ShowdownObservationAdapter.publicSwitchTransferMove(
                BattleMessage("|switch|p1a: next|Pikachu|100/100|[from] Shed Tail"))))
        val arrived = observer.publicSnapshot().pokemon.single { it.battlePokemonId == incoming.battlePokemonId }
        assertEquals(emptyMap<String, Int>(), arrived.statStages)
        assertEquals(setOf("substitute"), arrived.knownVolatileEffectIds.filterNot { it.startsWith("better_ai:") }.toSet())
    }

    @Test
    fun `ordinary switch after failed Baton Pass does not inherit without public switch transfer evidence`() {
        val outgoing = publicPokemon(BattleSide.ALLY, 0).copy(statStages = mapOf("atk" to 2))
        val incoming = publicPokemon(BattleSide.ALLY, 0)
        val observer = Cobblemon173PublicBattleObserver(3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, outgoing))
        observer.observe(Cobblemon173PublicObservation.VolatileChanged(1, outgoing, "focusenergy", true))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, outgoing, "batonpass", emptyList()))
        observer.observe(Cobblemon173PublicObservation.MoveOutcome(1,
            BattleMoveOutcomeView(BattleMoveOutcomeKind.FAILED, moveId = "batonpass"), source = outgoing))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(1, incoming))
        val arrived = observer.publicSnapshot().pokemon.single { it.battlePokemonId == incoming.battlePokemonId }
        assertEquals(emptyMap<String, Int>(), arrived.statStages)
        assertFalse("focusenergy" in arrived.knownVolatileEffectIds)
    }

    @Test
    fun `events preserve the public actor slot at action time across a pivot switch`() {
        val outgoing = publicPokemon(BattleSide.OPPONENT, activeSlot = 1)
        val incoming = publicPokemon(BattleSide.OPPONENT, activeSlot = 1)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, outgoing))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, outgoing, "voltswitch", emptyList()))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(1, incoming))

        val events = observer.publicSnapshot().events
        assertEquals(
            1,
            events.last { it.kind == BattleObservedEventKind.MOVE_USED }.actorSlot,
            "The outgoing actor slot must survive after the observer marks it inactive",
        )
        assertEquals(1, events.last { it.kind == BattleObservedEventKind.SWITCHED }.actorSlot)
    }

    @Test
    fun `public action constraints persist until cleared and disappear on switch`() {
        val first = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val second = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, first, "shadowball", emptyList()))
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = 1,
                pokemon = first,
                kind = BattleActionConstraintKind.TAUNT,
                active = true,
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = 1,
                pokemon = first,
                kind = BattleActionConstraintKind.ENCORE,
                active = true,
                lockedMoveId = "shadowball",
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = 1,
                pokemon = first,
                kind = BattleActionConstraintKind.TRAPPED,
                active = true,
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.ActionConstraintChanged(
                turn = 1,
                pokemon = first,
                kind = BattleActionConstraintKind.RECHARGE,
                active = true,
            ),
        )

        val constrained = observer.publicSnapshot().pokemon.single().actionConstraints
        assertTrue(constrained.taunted)
        assertEquals("shadowball", constrained.encoreMoveId)
        assertTrue(constrained.trapped)
        assertTrue(constrained.mustRecharge)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, second))

        val afterSwitch = observer.publicSnapshot().pokemon.associateBy { it.battlePokemonId }
        assertNull(afterSwitch.getValue(first.battlePokemonId).activeSlot)
        assertEquals(BattlePokemonActionConstraintView.empty(), afterSwitch.getValue(first.battlePokemonId).actionConstraints)
        assertEquals(BattlePokemonActionConstraintView.empty(), afterSwitch.getValue(second.battlePokemonId).actionConstraints)
    }

    @Test
    fun `public snapshots cannot reveal moves abilities or held items before an event`() {
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0).copy(
            knownTypeIds = setOf("dragon", "ground"),
        )
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(turn = 0, pokemon = opponent))
        observer.observe(
            Cobblemon173PublicObservation.HpChanged(
                turn = 1,
                pokemon = opponent.copy(speciesId = "cobblemon:zoroark", hpFraction = 0.5),
            ),
        )

        val known = observer.publicSnapshot().pokemon.single()
        assertTrue(known.knownMoveIds.isEmpty())
        assertNull(known.knownAbilityId)
        assertNull(known.knownHeldItemId)
        assertEquals(0.5, known.hpFraction)
        assertEquals(opponent.speciesId, known.speciesId)
        assertEquals(setOf("dragon", "ground"), known.knownTypeIds)
    }

    @Test
    fun `revealed resources and hp delta are accumulated from public events`() {
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, opponent, "thunderbolt", listOf(target)))
        val unrelatedHiddenState = opponent.copy(
            speciesId = "cobblemon:zoroark",
            hpFraction = 0.01,
            statusId = "brn",
        )
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(1, unrelatedHiddenState, "static"))
        observer.observe(Cobblemon173PublicObservation.HeldItemRevealed(1, unrelatedHiddenState, "choicespecs"))
        observer.observe(Cobblemon173PublicObservation.HpChanged(1, target.copy(hpFraction = 0.4)))

        val snapshot = observer.publicSnapshot()
        val knownOpponent = snapshot.pokemon.single { it.battlePokemonId == opponent.battlePokemonId }
        assertEquals(setOf("thunderbolt"), knownOpponent.knownMoveIds)
        assertEquals("static", knownOpponent.knownAbilityId)
        assertEquals("choicespecs", knownOpponent.knownHeldItemId)
        assertEquals(opponent.speciesId, knownOpponent.speciesId)
        assertEquals(1.0, knownOpponent.hpFraction)
        assertNull(knownOpponent.statusId)
        assertTrue(snapshot.events.zipWithNext().all { (before, after) -> before.sequence < after.sequence })
        assertEquals(
            listOf(BattleObservedEventKind.ACTION_ORDER, BattleObservedEventKind.MOVE_USED),
            snapshot.events.filter { it.kind in setOf(BattleObservedEventKind.ACTION_ORDER, BattleObservedEventKind.MOVE_USED) }
                .map { it.kind },
        )
        assertEquals(
            -0.6,
            snapshot.events.last { it.kind == BattleObservedEventKind.HP_CHANGED }.hpFractionDelta!!,
            0.000_001,
        )
    }

    @Test
    fun `direct target hp loss links to the preceding public action window`() {
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(
            Cobblemon173PublicObservation.MoveUsed(
                turn = 1,
                actor = opponent,
                moveId = "quickattack",
                targets = listOf(target),
                baseMovePriority = 1,
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.HpChanged(
                turn = 1,
                pokemon = target.copy(hpFraction = 0.75),
                allowPrecedingActionLink = true,
            ),
        )

        val events = observer.publicSnapshot().events
        val action = events.single { it.kind == BattleObservedEventKind.ACTION_ORDER }
        val damage = events.single { it.kind == BattleObservedEventKind.HP_CHANGED }
        assertEquals("quickattack", action.publicValueId)
        assertEquals(1, action.baseMovePriority)
        assertEquals(action.sequence, damage.precedingActionSequence)
        assertEquals(opponent.battlePokemonId, damage.precedingActionActorPokemonId)
        assertEquals("quickattack", damage.precedingActionMoveId)
        assertNull(damage.publicSourceEffectId)
    }

    @Test
    fun `public residual source and closed action window cannot be mislabeled as direct move damage`() {
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, opponent, "tackle", listOf(target), 0))
        observer.observe(
            Cobblemon173PublicObservation.HpChanged(
                0,
                target.copy(hpFraction = 0.9),
                allowPrecedingActionLink = true,
                publicSourceEffectId = "brn",
            ),
        )
        observer.closeActionWindow()
        observer.observe(
            Cobblemon173PublicObservation.HpChanged(
                1,
                target.copy(hpFraction = 0.8),
                allowPrecedingActionLink = true,
            ),
        )

        val damage = observer.publicSnapshot().events.filter { it.kind == BattleObservedEventKind.HP_CHANGED }
        assertEquals("brn", damage[0].publicSourceEffectId)
        assertNull(damage[0].precedingActionSequence)
        assertNull(damage[1].publicSourceEffectId)
        assertNull(damage[1].precedingActionSequence)
    }

    @Test
    fun `public move outcomes retain explicit participants and deduplicate the two miss forms`() {
        val source = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, source))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, source, "rockblast", listOf(target), 0, missed = true))
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                1,
                BattleMoveOutcomeView(BattleMoveOutcomeKind.MISSED),
                source,
                listOf(target),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                1,
                BattleMoveOutcomeView(BattleMoveOutcomeKind.CRITICAL_HIT),
                targets = listOf(target),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                1,
                BattleMoveOutcomeView(BattleMoveOutcomeKind.HIT_COUNT, hitCount = 3),
                targets = listOf(target),
            ),
        )

        val outcomes = observer.publicSnapshot().events.filter { it.kind == BattleObservedEventKind.MOVE_OUTCOME }
        assertEquals(3, outcomes.size)
        assertEquals(BattleMoveOutcomeKind.MISSED, outcomes[0].moveOutcome?.kind)
        assertEquals("rockblast", outcomes[0].moveOutcome?.moveId)
        assertEquals(source.battlePokemonId, outcomes[0].actorPokemonId)
        assertEquals(listOf(target.battlePokemonId), outcomes[0].targetPokemonIds)
        assertNull(outcomes[0].precedingActionSequence)
        assertEquals(BattleMoveOutcomeKind.CRITICAL_HIT, outcomes[1].moveOutcome?.kind)
        assertEquals(3, outcomes[2].moveOutcome?.hitCount)
    }

    @Test
    fun `substitute damage remains a public effect without changing pokemon hp`() {
        val target = publicPokemon(BattleSide.ALLY, activeSlot = 0).copy(hpFraction = 0.75)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
        observer.observe(
            Cobblemon173PublicObservation.MoveOutcome(
                1,
                BattleMoveOutcomeView(
                    BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED,
                    publicEffectId = "substitute",
                ),
                targets = listOf(target),
            ),
        )

        val snapshot = observer.publicSnapshot()
        assertEquals(0.75, snapshot.pokemon.single().hpFraction)
        assertTrue(snapshot.events.none { it.kind == BattleObservedEventKind.HP_CHANGED })
        val outcome = snapshot.events.single { it.kind == BattleObservedEventKind.MOVE_OUTCOME }
        assertEquals(BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED, outcome.moveOutcome?.kind)
        assertEquals("substitute", outcome.moveOutcome?.publicEffectId)
        assertNull(outcome.actorPokemonId)
        assertEquals(listOf(target.battlePokemonId), outcome.targetPokemonIds)
    }

    @Test
    fun `remaining opponent count uses configured roster size and unique public faints`() {
        val first = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)

        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, first))
        observer.observe(Cobblemon173PublicObservation.Fainted(2, first))
        observer.observe(Cobblemon173PublicObservation.Fainted(2, first))

        val snapshot = observer.publicSnapshot()
        assertEquals(2, snapshot.remainingOpponentPokemon)
        assertEquals(1, snapshot.pokemon.size)
        assertTrue(snapshot.pokemon.single().fainted)
    }

    @Test
    fun `field effects expose fair duration ranges and count down without weather upkeep resets`() {
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 4)

        observer.observe(
            Cobblemon173PublicObservation.WeatherChanged(
                0,
                "raindance",
                BattleIntegerRange(5, 8),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.FieldEffectChanged(
                1,
                "electricterrain",
                FieldEffectScope.TERRAIN,
                true,
                BattleIntegerRange(5, 8),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.FieldEffectChanged(
                1,
                "trickroom",
                FieldEffectScope.ROOM,
                true,
                BattleIntegerRange(5, 7),
            ),
        )
        observer.observe(
            Cobblemon173PublicObservation.FieldEffectChanged(
                1,
                "mudsport",
                FieldEffectScope.GLOBAL,
                true,
                BattleIntegerRange(5, 5),
            ),
        )
        observer.observe(Cobblemon173PublicObservation.SideConditionChanged(1, BattleSide.OPPONENT, "spikes", true))
        observer.observe(Cobblemon173PublicObservation.SideConditionChanged(1, BattleSide.OPPONENT, "spikes", true))

        val field = observer.publicSnapshot().field
        assertEquals("raindance", field.weather?.effectId)
        assertNull(field.weather?.remainingTurns)
        assertEquals(BattleIntegerRange(5, 8), field.weather?.remainingTurnsRange)
        assertEquals("electricterrain", field.terrain?.effectId)
        assertEquals(listOf("trickroom"), field.roomEffects.map { it.effectId })
        assertEquals(BattleIntegerRange(5, 7), field.roomEffects.single().remainingTurnsRange)
        assertEquals(5, field.globalEffects.single().remainingTurns)
        assertEquals(listOf("spikes"), field.sideConditions.getValue(BattleSide.OPPONENT).map { it.effectId })
        assertEquals(2, field.sideConditions.getValue(BattleSide.OPPONENT).single().stacks)

        observer.advanceTurn(2)
        observer.observe(
            Cobblemon173PublicObservation.WeatherChanged(
                2,
                "raindance",
                BattleIntegerRange(5, 8),
                upkeep = true,
            ),
        )
        val countedDown = observer.publicSnapshot().field
        assertEquals(BattleIntegerRange(4, 7), countedDown.weather?.remainingTurnsRange)
        assertEquals(BattleIntegerRange(4, 7), countedDown.terrain?.remainingTurnsRange)
        assertEquals(4, countedDown.globalEffects.single().remainingTurns)

        observer.observe(Cobblemon173PublicObservation.FieldEffectChanged(2, "trickroom", FieldEffectScope.ROOM, false))
        observer.observe(Cobblemon173PublicObservation.SideConditionChanged(2, BattleSide.OPPONENT, "spikes", false))
        val cleared = observer.publicSnapshot().field
        assertTrue(cleared.roomEffects.isEmpty())
        assertTrue(cleared.sideConditions.getValue(BattleSide.OPPONENT).isEmpty())
    }

    @Test
    fun `installed showdown duration knowledge separates exact ranged and indefinite effects`() {
        assertEquals(BattleIntegerRange(5, 8), Cobblemon173PublicEffectDurationKnowledge.weather("raindance"))
        assertNull(Cobblemon173PublicEffectDurationKnowledge.weather("primordialsea"))
        assertEquals(
            BattleIntegerRange(5, 8),
            Cobblemon173PublicEffectDurationKnowledge.field("psychicterrain", FieldEffectScope.TERRAIN),
        )
        assertEquals(
            BattleIntegerRange(5, 7),
            Cobblemon173PublicEffectDurationKnowledge.field("trickroom", FieldEffectScope.ROOM),
        )
        assertEquals(BattleIntegerRange(4, 6), Cobblemon173PublicEffectDurationKnowledge.side("tailwind"))
        assertEquals(BattleIntegerRange(5, 5), Cobblemon173PublicEffectDurationKnowledge.side("mist"))
        assertNull(Cobblemon173PublicEffectDurationKnowledge.side("stealthrock"))
    }

    @Test
    fun `a benched ally that has Terastallized keeps its Tera types`() {
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        val togekiss = publicPokemon(BattleSide.ALLY, activeSlot = 0).copy(speciesId = "cobblemon:togekiss",
            knownTypeIds = setOf("fairy", "flying"))
        val partner = publicPokemon(BattleSide.ALLY, activeSlot = 0)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, togekiss))
        observer.observe(Cobblemon173PublicObservation.TypesChanged(1, togekiss,
            Cobblemon173PublicTypeChange(PublicTypeChangeKind.TERA, setOf("flying"))))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, partner))
        fun own(id: UUID, slot: Int?, types: Set<String>) = BattlePokemonStateView(
            battlePokemonId = id, side = BattleSide.ALLY, activeSlot = slot, speciesId = "cobblemon:togekiss", formId = "normal",
            level = 50, hpFraction = 1.0, statusId = null, statStages = emptyMap(), knownMoveIds = emptySet(),
            knownAbilityId = null, knownHeldItemId = null, fainted = false, knownTypeIds = types, knownVolatileEffectIds = emptySet(),
        )

        val state = Cobblemon173BattleStateAssembler.assemble(UUID.randomUUID(), BattleFormat.SINGLE, 2,
            listOf(own(togekiss.battlePokemonId, null, setOf("fairy", "flying")), own(partner.battlePokemonId, 0, setOf("electric", "ghost"))),
            observer.publicSnapshot())

        val benched = state.pokemon.single { it.battlePokemonId == togekiss.battlePokemonId }
        assertEquals(setOf("flying"), benched.knownTypeIds)
        assertEquals("flying", benched.knownTeraTypeId)
    }

    @Test
    fun `assembler merges full ally state with public opponent state`() {
        val ally = ownPokemon()
        val opponent = publicPokemon(BattleSide.OPPONENT, activeSlot = 0)
        val observer = Cobblemon173PublicBattleObserver(initialOpponentPokemonCount = 3)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, opponent))
        val publicAlly = publicPokemon(BattleSide.ALLY, activeSlot = 0).copy(battlePokemonId = ally.battlePokemonId)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, publicAlly))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, opponent, "tackle", listOf(publicAlly), 0))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, publicAlly, "protect", emptyList(), 0))
        observer.observe(Cobblemon173PublicObservation.SubstituteChanged(1, publicAlly, true))

        val state = Cobblemon173BattleStateAssembler.assemble(
            battleId = UUID.randomUUID(),
            format = BattleFormat.SINGLE,
            turn = 1,
            ownPokemon = listOf(ally),
            publicSnapshot = observer.publicSnapshot(),
            inferenceKnowledge = { _, _ ->
                listOf(
                    PublicAbilityPossibility("roughskin", BattleAbilityAvailability.REGULAR),
                    PublicAbilityPossibility("sandveil", BattleAbilityAvailability.HIDDEN),
                )
            },
        )

        assertEquals(2, state.pokemon.size)
        assertEquals(1, state.remainingPokemonBySide.getValue(BattleSide.ALLY))
        assertEquals(3, state.remainingPokemonBySide.getValue(BattleSide.OPPONENT))
        assertEquals(setOf("protect"), state.pokemon.single { it.side == BattleSide.ALLY }.knownMoveIds)
        assertEquals(setOf("substitute"), state.pokemon.single { it.side == BattleSide.ALLY }.knownVolatileEffectIds)
        assertEquals(setOf("tackle"), state.pokemon.single { it.side == BattleSide.OPPONENT }.knownMoveIds)
        val abilities = state.inferences.filter { it.categoryId == "ability" }
        assertEquals(setOf("roughskin", "sandveil"), abilities.mapNotNull { it.candidateId }.toSet())
        assertEquals(
            setOf(BattleAbilityAvailability.REGULAR, BattleAbilityAvailability.HIDDEN),
            abilities.mapNotNull { it.abilityAvailability }.toSet(),
        )
        assertTrue(abilities.all { it.confidence == BattleInferenceConfidence.POSSIBLE })
        val order = state.inferences.single { it.categoryId == "observed_action_order" }
        assertEquals("BEFORE_AT_SAME_BASE_PRIORITY", order.candidateId)
        assertEquals(ally.battlePokemonId, order.relatedPokemonId)
    }

    @Test
    fun `normalizes public Showdown ids and parses visible hp text`() {
        assertEquals("choicescarf", Cobblemon173ShowdownObservationAdapter.effectId("item: Choice Scarf"))
        assertEquals("sandstream", Cobblemon173ShowdownObservationAdapter.effectId("ability: Sand Stream"))
        assertEquals("uturn", Cobblemon173ShowdownObservationAdapter.effectId("U-turn"))
        assertEquals(0.5, Cobblemon173ShowdownObservationAdapter.parseHpFraction("100/200"))
        assertEquals(0.25, Cobblemon173ShowdownObservationAdapter.parseHpFraction("25/100 par"))
        assertEquals(0.0, Cobblemon173ShowdownObservationAdapter.parseHpFraction("0 fnt"))
        assertNull(Cobblemon173ShowdownObservationAdapter.parseHpFraction("75%"))
        assertNull(Cobblemon173ShowdownObservationAdapter.parseHpFraction("0/0"))
        assertNull(Cobblemon173ShowdownObservationAdapter.parseHpFraction("NaN/100"))
        assertNull(Cobblemon173ShowdownObservationAdapter.parseHpFraction("50/Infinity"))
        assertEquals(
            ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TAUNT, true),
            Cobblemon173ShowdownObservationAdapter.actionConstraintDescriptor(
                BattleMessage("|-start|p1a: Pikachu|move: Taunt"),
            ),
        )
        assertEquals(
            ShowdownActionConstraintDescriptor(BattleActionConstraintKind.ENCORE, false),
            Cobblemon173ShowdownObservationAdapter.actionConstraintDescriptor(
                BattleMessage("|-end|p1a: Pikachu|Encore"),
            ),
        )
        assertEquals(
            ShowdownActionConstraintDescriptor(BattleActionConstraintKind.RECHARGE, true),
            Cobblemon173ShowdownObservationAdapter.actionConstraintDescriptor(
                BattleMessage("|-mustrecharge|p1a: Pikachu"),
            ),
        )
        assertEquals(
            ShowdownActionConstraintDescriptor(BattleActionConstraintKind.TRAPPED, true),
            Cobblemon173ShowdownObservationAdapter.actionConstraintDescriptor(
                BattleMessage("|-activate|p1a: Pikachu|move: Fire Spin|[of] p2a: Garchomp"),
            ),
        )
        assertEquals(4, Cobblemon173ShowdownObservationAdapter.publicTurn("999", currentBattleTurn = 4, previous = 3))
        assertEquals(3, Cobblemon173ShowdownObservationAdapter.publicTurn("broken", currentBattleTurn = 4, previous = 3))
        assertEquals(
            "brn",
            Cobblemon173ShowdownObservationAdapter.publicSourceEffectId(
                BattleMessage("|-damage|p1a: Pikachu|80/100|[from] brn"),
            ),
        )
        assertNull(
            Cobblemon173ShowdownObservationAdapter.publicSourceEffectId(
                BattleMessage("|-damage|p1a: Pikachu|80/100"),
            ),
        )

        val miss = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-miss|p1a: Pikachu|p2a: Garchomp"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.MISSED, miss.outcome.kind)
        assertEquals(0, miss.sourceArgument)
        assertEquals(listOf(1), miss.targetArguments)

        val block = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-block|p2a: Garchomp|move: Protect|move: Toxic|p1a: Pikachu"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.BLOCKED, block.outcome.kind)
        assertEquals("protect", block.outcome.publicEffectId)
        assertEquals("toxic", block.outcome.moveId)
        assertEquals(3, block.sourceArgument)
        assertEquals(listOf(0), block.targetArguments)

        val substitute = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-activate|p2a: Garchomp|move: Substitute|[damage]"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.SUBSTITUTE_DAMAGED, substitute.outcome.kind)
        assertEquals("substitute", substitute.outcome.publicEffectId)
        assertNull(substitute.outcome.moveId)
        assertNull(substitute.sourceArgument)
        assertEquals(listOf(0), substitute.targetArguments)

        val protection = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-singleturn|p1a: Pikachu|move: Protect"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.PROTECTION_STARTED, protection.outcome.kind)
        assertEquals("protect", protection.outcome.publicEffectId)
        assertNull(protection.outcome.moveId)
        assertNull(protection.sourceArgument)
        assertEquals(listOf(0), protection.targetArguments)
        assertNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-activate|p2a: Garchomp|ability: Sturdy"),
            ),
        )
        assertNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-activate|p2a: Garchomp|move: Substitute"),
            ),
        )
        listOf("move: Endure", "Max Guard", "Quick Guard", "Wide Guard").forEach { effect ->
            val stallCounter = requireNotNull(
                Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                    BattleMessage("|-singleturn|p1a: Pikachu|$effect"),
                ),
            )
            assertEquals(BattleMoveOutcomeKind.PROTECTION_STARTED, stallCounter.outcome.kind, effect)
            assertEquals("protect", stallCounter.outcome.publicEffectId, effect)
            assertEquals(listOf(0), stallCounter.targetArguments, effect)
        }

        val hitCount = requireNotNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-hitcount|p2a: Garchomp|4"),
            ),
        )
        assertEquals(BattleMoveOutcomeKind.HIT_COUNT, hitCount.outcome.kind)
        assertEquals(4, hitCount.outcome.hitCount)
        assertNull(
            Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(
                BattleMessage("|-hitcount|p2a: Garchomp|broken"),
            ),
        )
        val outcomeKinds = mapOf(
            "|-fail|p2a: Garchomp|move: Toxic" to BattleMoveOutcomeKind.FAILED,
            "|-notarget|p1a: Pikachu" to BattleMoveOutcomeKind.NO_TARGET,
            "|cant|p1a: Pikachu|par|move: Thunderbolt" to BattleMoveOutcomeKind.CANNOT_ACT,
            "|-crit|p2a: Garchomp" to BattleMoveOutcomeKind.CRITICAL_HIT,
            "|-supereffective|p2a: Garchomp" to BattleMoveOutcomeKind.SUPER_EFFECTIVE,
            "|-resisted|p2a: Garchomp" to BattleMoveOutcomeKind.RESISTED,
            "|-immune|p2a: Garchomp" to BattleMoveOutcomeKind.IMMUNE,
            // Cobblemon's own lines for 4x and 1/4x hits.
            "|-extremelyeffective|p2a: Garchomp" to BattleMoveOutcomeKind.SUPER_EFFECTIVE,
            "|-mostlyineffective|p2a: Garchomp" to BattleMoveOutcomeKind.RESISTED,
        )
        outcomeKinds.forEach { (raw, expected) ->
            assertEquals(
                expected,
                requireNotNull(
                    Cobblemon173ShowdownObservationAdapter.moveOutcomeDescriptor(BattleMessage(raw)),
                ).outcome.kind,
            )
        }

        val hiddenResolverResult = publicPokemon(BattleSide.OPPONENT, activeSlot = null).copy(
            speciesId = "cobblemon:zoroark",
            formId = "hisui",
            level = 100,
        )
        val publicSwitch = Cobblemon173ShowdownObservationAdapter.publicSwitchSnapshot(
            hiddenResolverResult,
            "Pikachu, L50, M",
        )
        assertEquals("showdown:pikachu", publicSwitch.speciesId)
        assertNull(publicSwitch.formId)
        assertEquals(50, publicSwitch.level)
    }

    @Test
    fun `cumulative move uses survive event eviction and switch but reset with battle`() {
        val observer = Cobblemon173PublicBattleObserver(3, maximumRecentEvents = 2)
        val first = publicPokemon(BattleSide.OPPONENT, 0)
        val second = publicPokemon(BattleSide.OPPONENT, 0)
        repeat(10) { observer.observe(Cobblemon173PublicObservation.MoveUsed(it + 1, first, "protect", emptyList())) }
        val beforeSwitch = observer.publicSnapshot()
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(11, second))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(11, second, "protect", emptyList()))
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(12, first))
        val snapshot = observer.publicSnapshot()
        assertEquals(2, snapshot.events.size)
        assertEquals(10, snapshot.moveUses[first.battlePokemonId]?.get("protect"))
        assertEquals(1, snapshot.moveUses[second.battlePokemonId]?.get("protect"))
        assertNull(beforeSwitch.moveUses[second.battlePokemonId])
        observer.reset()
        assertTrue(observer.publicSnapshot().moveUses.isEmpty())
        assertEquals(10, snapshot.moveUses[first.battlePokemonId]?.get("protect"))
    }

    @Test
    fun `explicit Spite PP loss is separate from uses and survives event eviction`() {
        val observer = Cobblemon173PublicBattleObserver(3, maximumRecentEvents = 2)
        val target = publicPokemon(BattleSide.OPPONENT, 0)
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, target, "tackle", emptyList()))
        observer.observePpLoss(target.battlePokemonId, "tackle", 4)
        observer.observe(Cobblemon173PublicObservation.PokemonPresented(2, target))
        assertEquals(1, observer.publicSnapshot().moveUses[target.battlePokemonId]?.get("tackle"))
        assertEquals(5, observer.publicPpSpent()[target.battlePokemonId]?.get("tackle"))
        observer.reset()
        assertTrue(observer.publicPpSpent().isEmpty())
    }

    @Test
    fun `only a valid explicit Spite activation establishes extra PP loss`() {
        fun loss(line: String) = Cobblemon173ShowdownObservationAdapter.spitePpLoss(BattleMessage(line))
        assertEquals("tackle" to 4, loss("|-activate|p1a: test|move: Spite|Tackle|4"))
        assertEquals("tackle" to 1, loss("|-activate|p1a: test|move: Spite|Tackle|1"))
        assertNull(loss("|move|p2a: test|Spite|p1a: target"))
        assertNull(loss("|-fail|p1a: test|move: Spite|Tackle|4"))
        assertNull(loss("|-activate|p1a: test|item: Leppa Berry|Tackle|[consumed]"))
        for (amount in listOf("", "bad", "-1", "0", "5")) {
            assertNull(loss("|-activate|p1a: test|move: Spite|Tackle|$amount"))
        }
    }

    @Test
    fun `PP restoration caps at capacity without banking recovery against later uses`() {
        val observer = Cobblemon173PublicBattleObserver(3)
        val target = publicPokemon(BattleSide.OPPONENT, 0)
        fun use(turn: Int) = observer.observe(Cobblemon173PublicObservation.MoveUsed(turn, target, "recover", emptyList()))
        use(1)
        observer.observePpRestore(target.battlePokemonId, "recover", 10, 8)
        assertEquals(0, observer.publicPpSpent()[target.battlePokemonId]?.get("recover"))
        use(2)
        assertEquals(1, observer.publicPpSpent()[target.battlePokemonId]?.get("recover"))
        assertEquals(2, observer.publicSnapshot().moveUses[target.battlePokemonId]?.get("recover"))
        observer.observePpLoss(target.battlePokemonId, "recover", 10)
        observer.observePpRestore(target.battlePokemonId, "recover", 3, 8)
        assertEquals(5, observer.publicPpSpent()[target.battlePokemonId]?.get("recover"))
        observer.reset()
        assertTrue(observer.publicPpSpent().isEmpty())
    }

    @Test
    fun `Leppa activation names the restored move but item revelation does not`() {
        fun restored(line: String) = Cobblemon173ShowdownObservationAdapter.leppaRestoredMove(BattleMessage(line))
        assertEquals("tackle", restored("|-activate|p1a: test|item: Leppa Berry|Tackle|[consumed]"))
        assertNull(restored("|-enditem|p1a: test|Leppa Berry|[eat]"))
        assertNull(restored("|-item|p1a: test|Leppa Berry"))
        assertNull(restored("|-activate|p1a: test|move: Spite|Tackle|4"))
        assertNull(restored("|-activate|p1a: test|item: Leppa Berry||[consumed]"))
    }

    @Test
    fun `public Pressure counts opposing spread targets but respects active neutralizing gas`() {
        val observer = Cobblemon173PublicBattleObserver(3)
        val actor = publicPokemon(BattleSide.ALLY, 0)
        val first = publicPokemon(BattleSide.OPPONENT, 0)
        val second = publicPokemon(BattleSide.OPPONENT, 1)
        val gas = publicPokemon(BattleSide.ALLY, 1)
        for (target in listOf(first, second)) {
            observer.observe(Cobblemon173PublicObservation.PokemonPresented(0, target))
            observer.observe(Cobblemon173PublicObservation.AbilityRevealed(0, target, "pressure"))
        }
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, actor, "rockslide", listOf(first),
            pressureTargetPattern = BattleMoveTargetPattern.ALL_OPPONENTS))
        assertEquals(3, observer.publicPpSpent()[actor.battlePokemonId]?.get("rockslide"))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(2, actor, "tackle", listOf(first),
            pressureTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))
        assertEquals(2, observer.publicPpSpent()[actor.battlePokemonId]?.get("tackle"))
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(2, gas, "neutralizinggas"))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(3, actor, "rockslide", listOf(first),
            pressureTargetPattern = BattleMoveTargetPattern.ALL_OPPONENTS))
        assertEquals(4, observer.publicPpSpent()[actor.battlePokemonId]?.get("rockslide"))
    }

    @Test
    fun `Pressure suppression follows Gastro Acid and gas ending before the next switch snapshot`() {
        val observer = Cobblemon173PublicBattleObserver(3)
        val actor = publicPokemon(BattleSide.ALLY, 0)
        val target = publicPokemon(BattleSide.OPPONENT, 0)
        val gas = publicPokemon(BattleSide.ALLY, 1)
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(0, target, "pressure"))
        var turn = 0
        fun use() = observer.observe(Cobblemon173PublicObservation.MoveUsed(++turn, actor, "tackle", listOf(target),
            pressureTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))
        fun spent() = observer.publicPpSpent()[actor.battlePokemonId]?.get("tackle")
        observer.observeAbilityPpEffect(target.battlePokemonId, "gastroacid", true)
        use()
        assertEquals(1, spent())
        observer.observeAbilityPpEffect(target.battlePokemonId, "gastroacid", false)
        use()
        assertEquals(3, spent())
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(turn, gas, "neutralizinggas"))
        use()
        assertEquals(4, spent())
        observer.observeAbilityPpEffect(gas.battlePokemonId, "neutralizinggas", false)
        use()
        assertEquals(6, spent())
        observer.observeAbilityPpEffect(gas.battlePokemonId, "neutralizinggas", true)
        use()
        assertEquals(7, spent())
        observer.observeAbilityPpEffect(gas.battlePokemonId, "gastroacid", true)
        use()
        assertEquals(9, spent())
        observer.reset()
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(0, target, "pressure"))
        use()
        assertEquals(2, spent())
    }

    @Test
    fun `PP ability effect parser distinguishes starts ends and unrelated messages`() {
        fun effect(line: String) = Cobblemon173ShowdownObservationAdapter.abilityPpEffect(BattleMessage(line))
        assertEquals("gastroacid" to true, effect("|-start|p1a: test|Gastro Acid"))
        assertEquals("gastroacid" to false, effect("|-end|p1a: test|Gastro Acid"))
        assertEquals("neutralizinggas" to true, effect("|-ability|p1a: test|Neutralizing Gas"))
        assertEquals("neutralizinggas" to false, effect("|-end|p1a: test|ability: Neutralizing Gas"))
        assertNull(effect("|move|p1a: test|Gastro Acid|p2a: target"))
    }

    @Test
    fun `called attack keeps its use evidence but charges Pressure to the caller`() {
        val observer = Cobblemon173PublicBattleObserver(3)
        val actor = publicPokemon(BattleSide.ALLY, 0)
        val target = publicPokemon(BattleSide.OPPONENT, 0)
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(0, target, "pressure"))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, actor, "sleeptalk", listOf(actor),
            pressureTargetPattern = BattleMoveTargetPattern.SELF))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(1, actor, "tackle", listOf(target),
            pressureTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT, ppCallerMoveId = "sleeptalk"))
        assertEquals(2, observer.publicPpSpent()[actor.battlePokemonId]?.get("sleeptalk"))
        assertEquals(0, observer.publicPpSpent()[actor.battlePokemonId]?.get("tackle") ?: 0)
        assertEquals(1, observer.publicSnapshot().moveUses[actor.battlePokemonId]?.get("tackle"))
        assertFalse(observer.publicSnapshot().pokemon.single {
            it.battlePokemonId == actor.battlePokemonId
        }.knownMoveIds.contains("tackle"))
        observer.observe(Cobblemon173PublicObservation.MoveUsed(2, actor, "tackle", listOf(target),
            pressureTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))
        assertEquals(2, observer.publicPpSpent()[actor.battlePokemonId]?.get("tackle"))
        assertTrue(observer.publicSnapshot().pokemon.single {
            it.battlePokemonId == actor.battlePokemonId
        }.knownMoveIds.contains("tackle"))
    }

    @Test
    fun `caller PP link requires an explicit move source matching the same actor previous move`() {
        fun caller(line: String, previous: String?) =
            Cobblemon173ShowdownObservationAdapter.ppCallerMove(BattleMessage(line), previous)
        val called = "|move|p1a: test|Tackle|p2a: target|[from]move: Sleep Talk"
        assertEquals("sleeptalk", caller(called, "sleeptalk"))
        assertNull(caller(called, "tackle"))
        assertNull(caller(called, null))
        assertNull(caller("|move|p1a: test|Tackle|p2a: target", "sleeptalk"))
        assertNull(caller("|move|p1a: test|Tackle|p2a: target|[from]ability: Magic Bounce", "magicbounce"))
    }

    @Test
    fun `locked continuation preserves execution evidence without spending PP again`() {
        val observer = Cobblemon173PublicBattleObserver(3)
        val actor = publicPokemon(BattleSide.ALLY, 0)
        val target = publicPokemon(BattleSide.OPPONENT, 0)
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(0, target, "pressure"))
        fun use(turn: Int, locked: Boolean) = observer.observe(Cobblemon173PublicObservation.MoveUsed(
            turn, actor, "outrage", listOf(target), pressureTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT,
            ppLockedContinuation = locked))
        use(1, false)
        use(2, true)
        assertEquals(2, observer.publicPpSpent()[actor.battlePokemonId]?.get("outrage"))
        assertEquals(2, observer.publicSnapshot().moveUses[actor.battlePokemonId]?.get("outrage"))
        use(3, false)
        assertEquals(4, observer.publicPpSpent()[actor.battlePokemonId]?.get("outrage"))
    }

    @Test
    fun `only explicit lockedmove source waives continuation PP`() {
        fun locked(line: String) = Cobblemon173ShowdownObservationAdapter.ppLockedContinuation(BattleMessage(line))
        assertTrue(locked("|move|p1a: test|Fly|p2a: target|[from]lockedmove"))
        assertTrue(locked("|move|p1a: test|Outrage|p2a: target|[from] lockedmove"))
        assertFalse(locked("|move|p1a: test|Fly||[still]"))
        assertFalse(locked("|move|p1a: test|Fly|p2a: target"))
        assertFalse(locked("|move|p1a: test|Tackle|p2a: target|[from]move: Sleep Talk"))
    }

    @Test
    fun `untargeted preparation charges Pressure only when all possible foes agree`() {
        for ((abilities, expected) in listOf(listOf("pressure") to 2,
            listOf("pressure", "pressure") to 2, listOf("pressure", "illuminate") to 1)) {
            val observer = Cobblemon173PublicBattleObserver(3)
            val actor = publicPokemon(BattleSide.ALLY, 0)
            abilities.forEachIndexed { slot, ability ->
                observer.observe(Cobblemon173PublicObservation.AbilityRevealed(0,
                    publicPokemon(BattleSide.OPPONENT, slot), ability))
            }
            observer.observe(Cobblemon173PublicObservation.MoveUsed(1, actor, "fly", emptyList(),
                pressureTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT, ppPreparing = true))
            assertEquals(expected, observer.publicPpSpent()[actor.battlePokemonId]?.get("fly"), abilities.toString())
            observer.observe(Cobblemon173PublicObservation.MoveUsed(2, actor, "tackle", emptyList(),
                pressureTargetPattern = BattleMoveTargetPattern.SELECTED_OPPONENT))
            assertEquals(1, observer.publicPpSpent()[actor.battlePokemonId]?.get("tackle"))
        }
    }

    @Test
    fun `preparation requires both the public still marker and a declared charge flag`() {
        fun preparing(line: String, move: String) =
            Cobblemon173ShowdownObservationAdapter.ppPreparingMove(BattleMessage(line), move)
        assertTrue(preparing("|move|p1a: test|Fly||[still]", "fly"))
        assertFalse(preparing("|move|p1a: test|Fly|p2a: target", "fly"))
        assertFalse(preparing("|move|p1a: test|Protect||[still]", "protect"))
    }

    private fun publicPokemon(side: BattleSide, activeSlot: Int?) = Cobblemon173PublicPokemonSnapshot(
        battlePokemonId = UUID.randomUUID(),
        side = side,
        activeSlot = activeSlot,
        speciesId = if (side == BattleSide.OPPONENT) "cobblemon:garchomp" else "cobblemon:rotom",
        formId = "normal",
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        fainted = false,
    )

    private fun ownPokemon(formId: String = "normal") = BattlePokemonStateView(
        battlePokemonId = UUID.randomUUID(),
        side = BattleSide.ALLY,
        activeSlot = 0,
        speciesId = "cobblemon:metagross",
        formId = formId,
        level = 50,
        hpFraction = 1.0,
        statusId = null,
        statStages = emptyMap(),
        knownMoveIds = setOf("protect"),
        knownAbilityId = "clearbody",
        knownHeldItemId = "leftovers",
        fainted = false,
    )
}
