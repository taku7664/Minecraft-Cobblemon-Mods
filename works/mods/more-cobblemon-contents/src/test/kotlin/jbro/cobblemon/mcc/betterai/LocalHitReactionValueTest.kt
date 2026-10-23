package jbro.cobblemon.mcc.betterai

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.google.gson.*
import jbro.cobblemon.mcc.betterai.engine.EngineReferee
import jbro.cobblemon.mcc.betterai.calculation.PublicBattleTacticalCalculator
import jbro.cobblemon.mcc.betterai.calculation.LocalDecisionCalculationScope
import jbro.cobblemon.mcc.betterai.evaluation.*
import jbro.cobblemon.mcc.betterai.matchup.*
import jbro.cobblemon.mcc.betterai.mechanics.*
import jbro.cobblemon.mcc.betterai.state.LocalSwitchStateProjector
import jbro.cobblemon.mcc.internal.ai.*
import jbro.cobblemon.mcc.internal.compat.cobblemon173.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalHitReactionValueTest {
    @Test fun `isolated orb fast path preserves full projection across hit chances and HP`() {
        for (accuracy in listOf(40.0, 70.0, 100.0)) for (hp in listOf(1.0, 83.0 / 146, 15.0 / 146)) {
            val move = BattleActionCandidate("orb", BattleActionKind.USE_MOVE, 0, 0, "tackle",
                targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)), moveDetails = BattleMoveCandidateView("normal",
                    BattleMoveDamageCategory.PHYSICAL, 40.0, accuracy, 0, 20))
            val c = PublicBattleTacticalCalculator.calculate(context(listOf(mon(TARGET, BattleSide.ALLY, hp, item = "lifeorb"),
                mon(ACTOR, BattleSide.OPPONENT)), move))
            val a = c.candidates.single()
            assertNotNull(LocalFixedHpReactionValue.read(a, c, LocalDecisionTuning.CURRENT))
            val full = LocalRootHitReactionEvaluator.score(a, c, LocalDecisionTuning.CURRENT.copy(simpleFixedHpReactions = false))!!
            val fast = LocalRootHitReactionEvaluator.score(a, c, LocalDecisionTuning.CURRENT.copy(simpleFixedHpReactions = true))!!
            assertEquals(full.total, fast.total, 1e-10, "$accuracy/$hp")
        }
    }

    @Test fun `orb shortcut declines recoil death target berry and substitute interactions`() {
        val move = tackle()
        for ((hp, item, volatile) in listOf(Triple(14.0 / 146, null, emptySet()),
            Triple(1.0, "sitrusberry", emptySet()), Triple(1.0, null, setOf("substitute")))) {
            val c = PublicBattleTacticalCalculator.calculate(context(listOf(mon(TARGET, BattleSide.ALLY, hp, item = "lifeorb"),
                mon(ACTOR, BattleSide.OPPONENT, item = item, volatile = volatile)), move))
            assertNull(LocalFixedHpReactionValue.read(c.candidates.single(), c, LocalDecisionTuning.CURRENT))
        }
    }

    @Test fun `native regenerator dynamax HP and alternating truant phase reach their scoring readers`() {
        EngineReferee.assumeAvailable()
        val process = ProcessBuilder("node", Path.of(System.getProperty("aiengine.tools")).resolve("hit-reaction-audit.cjs").toString(),
            System.getProperty("aiengine.showdown"), "passive").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), text)
        val native = JsonParser.parseString(text).asJsonObject
        val snapshot = Cobblemon173PublicPokemonSnapshot(TARGET, BattleSide.ALLY, 0, "mew", null, 50,
            1.0, null, emptyMap(), false)
        val observer = Cobblemon173PublicBattleObserver(1)
        observer.observe(Cobblemon173PublicObservation.AbilityRevealed(0, snapshot, "truant"))
        fun holder(raw: JsonObject, ability: String?, item: String? = null) = mon(TARGET, BattleSide.ALLY,
            hp = raw["hp"].asDouble / raw["maxhp"].asDouble, ability = ability, item = item,
            volatile = raw.getAsJsonArray("volatiles").map { it.asString }.toSet())
        for (row in native.getAsJsonArray("regeneration").map { it.asJsonObject }) {
            val own = holder(row.getAsJsonObject("before"), "regenerator")
            val state = context(listOf(own, mon(ACTOR, BattleSide.OPPONENT)), tackle()).state
            val expected = row.getAsJsonObject("after")
            assertEquals(expected["hp"].asDouble / expected["maxhp"].asDouble,
                LocalSwitchStateProjector.projectedSwitchOutHp(state, own), 1e-12)
        }
        for (row in native.getAsJsonArray("truant").map { it.asJsonObject }) {
            val own = holder(row.getAsJsonObject("before"), "truant")
            val c = context(listOf(own, mon(ACTOR, BattleSide.OPPONENT)), tackle())
            assertEquals(if (row["loafed"].asBoolean) 0.0 else 1.0, LocalSetupGate.actingChance(own, c.state))
            observer.advanceTurn(row["turn"].asInt)
            val observed = observer.publicSnapshot().pokemon.single()
            assertEquals(row["loafed"].asBoolean, "truant" in observed.knownVolatileEffectIds)
            if (row["moved"].asBoolean) observer.observe(Cobblemon173PublicObservation.MoveUsed(row["turn"].asInt,
                snapshot, "tackle", emptyList()))
            val projected = LocalMoveImpactProjector.project(tackle(), c, BattleSide.ALLY, LocalProjectedActionCalculationCache())
            val owesTurn = row.getAsJsonObject("after").getAsJsonArray("volatiles").any { it.asString == "truant" }
            assertTrue(projected.all { ("truant" in it.state.pokemon.first { p -> p.battlePokemonId == TARGET }.knownVolatileEffectIds) == owesTurn })
        }
        val orb = native.getAsJsonObject("orb")
        val own = holder(orb.getAsJsonObject("before"), null, "lifeorb")
        val c = context(listOf(own, mon(ACTOR, BattleSide.OPPONENT)), tackle())
        val after = LocalAfterHitReactions.afterMove(c.state, TARGET, tackle(), true).pokemon.first()
        val expected = orb.getAsJsonObject("after")
        assertEquals(expected["hp"].asDouble / expected["maxhp"].asDouble, after.hpFraction, 1e-12)
    }

    @Test fun `arena public move history reaches the same truant phase for both sides`() {
        val input = javaClass.getResourceAsStream("/oracle/low-hp-belly-drum-input.json")!!.bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject }
        val own = input.getAsJsonObject("request").getAsJsonObject("side").getAsJsonArray("pokemon")
            .map { it.asJsonObject }.first { it["active"].asBoolean }["ident"].asString
        val foe = "p2a: 00000000-0000-0000-0000-000000000203"
        input.getAsJsonObject("ownAbilities").addProperty(own, "truant")
        input.getAsJsonArray("publicLog").apply {
            add("|turn|5"); add("|-ability|$foe|Truant")
            add("|move|$own|Tackle|$foe"); add("|move|$foe|Tackle|$own")
        }
        for (turn in 6..7) {
            val c = EmbeddedTeamInput.context(input, UUID(0, 10), turn, 0)
            val active = c.state.pokemon.filter { it.activeSlot != null }
            assertEquals(2, active.size)
            assertTrue(active.all { ("truant" in it.knownVolatileEffectIds) == (turn == 6) })
        }
    }

    @Test fun `native damage rolls criticals multi hits item use and reactive stages agree`() {
        EngineReferee.assumeAvailable()
        val process = ProcessBuilder("node", Path.of(System.getProperty("aiengine.tools")).resolve("hit-reaction-audit.cjs").toString(),
            System.getProperty("aiengine.showdown")).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), text)
        val cases = JsonParser.parseString(text).asJsonArray.map { it.asJsonObject }.groupBy { it["id"].asString }
        assertEquals(21, cases.size)
        for ((id, examples) in cases) {
            val first = examples.first()
            val before = first.getAsJsonObject("before")
            fun pokemon(raw: JsonObject, side: BattleSide, uuid: UUID): BattlePokemonStateView = mon(uuid, side,
                hp = raw["hp"].asDouble / raw["maxhp"].asDouble, ability = raw["ability"].asString,
                item = raw["item"].asString, types = raw.getAsJsonArray("types").map { it.asString }.toSet(),
                volatile = raw.getAsJsonArray("volatiles").map { it.asString }.toSet() +
                    raw["substituteHp"]?.let { setOf("substitutehp:${it.asDouble / 146}") }.orEmpty())
            val target = pokemon(before.getAsJsonObject("target"), BattleSide.ALLY, TARGET)
            val actor = pokemon(before.getAsJsonObject("actor"), BattleSide.OPPONENT, ACTOR)
            val info = first.getAsJsonObject("move")
            val effects = (info["multihit"]?.takeIf { it.isJsonArray }?.asJsonArray?.let { listOf(BattleMoveEffectView(BattleMoveEffectKind.MULTI_HIT,
                BattleMoveEffectTarget.SELECTED_TARGET, amountRange = BattleIntegerRange(it[0].asInt, it[1].asInt))) }
                ?: info["multihit"]?.takeUnless { it.isJsonNull }?.let { listOf(BattleMoveEffectView(BattleMoveEffectKind.MULTI_HIT,
                    BattleMoveEffectTarget.SELECTED_TARGET, amountRange = BattleIntegerRange(it.asInt, it.asInt))) }.orEmpty()) +
                if (info["damage"]?.takeUnless { it.isJsonNull }?.asString == "level") listOf(BattleMoveEffectView(
                    BattleMoveEffectKind.FIXED_DAMAGE_LEVEL, BattleMoveEffectTarget.SELECTED_TARGET)) else emptyList()
            val move = BattleActionCandidate("native", BattleActionKind.USE_MOVE, 0, 0, info["id"].asString,
                targets = listOf(BattleTargetSlot(BattleSide.ALLY, 0)), moveDetails = BattleMoveCandidateView(info["type"].asString,
                    if (info["category"].asString == "Physical") BattleMoveDamageCategory.PHYSICAL else BattleMoveDamageCategory.SPECIAL,
                    info["power"].asDouble, 100.0, 0, 20, effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL,
                        effects, false, mechanicFlags = info.getAsJsonArray("flags").map { it.asString }.toSet())))
            val context = context(listOf(target, actor), move)
            val paths = LocalMoveImpactProjector.project(move, context, BattleSide.OPPONENT, LocalProjectedActionCalculationCache())
            fun key(hp: Int, item: String, stages: Map<String, Int>, sourceHp: Int, sourceStages: Map<String, Int>): String =
                listOf(hp, item, stages.filterValues { it != 0 }.toSortedMap(), sourceHp, sourceStages.filterValues { it != 0 }.toSortedMap()).toString()
            val stat = mapOf("atk" to "attack", "def" to "defence", "spa" to "special_attack", "spd" to "special_defence",
                "spe" to "speed", "accuracy" to "accuracy", "evasion" to "evasion")
            fun boosts(raw: JsonObject) = raw.getAsJsonObject("boosts").entrySet().associate { stat.getValue(it.key) to it.value.asInt }
            val expected = examples.groupBy { row ->
                val after = row.getAsJsonObject("after"); val t = after.getAsJsonObject("target"); val a = after.getAsJsonObject("actor")
                key(t["hp"].asInt, t["item"].asString, boosts(t), a["hp"].asInt, boosts(a))
            }.mapValues { (_, rows) -> rows.sumOf { it["probability"].asDouble } }
            val actual = paths.groupBy { path ->
                val t = path.state.pokemon.first { it.battlePokemonId == TARGET }; val a = path.state.pokemon.first { it.battlePokemonId == ACTOR }
                key(kotlin.math.round(t.hpFraction * 146).toInt(), t.knownHeldItemId.orEmpty(), t.statStages,
                    kotlin.math.round(a.hpFraction * 146).toInt(), a.statStages)
            }.mapValues { (_, rows) -> rows.sumOf { it.probability } }
            assertEquals(expected.keys, actual.keys, id)
            for (key in expected.keys) assertEquals(expected.getValue(key), actual.getValue(key), 1e-10, "$id/$key")
            assertEquals(1.0, paths.sumOf { it.probability }, 1e-10, id)
        }
    }

    @Test fun `life orb reaction pays the actual integer HP once in its own move score`() {
        val move = tackle()
        val base = context(listOf(mon(TARGET, BattleSide.ALLY, item = "lifeorb"), mon(ACTOR, BattleSide.OPPONENT)), move)
        val calculated = PublicBattleTacticalCalculator.calculate(base)
        val score = LocalRootHitReactionEvaluator.score(calculated.candidates.single(), calculated, LocalDecisionTuning.CURRENT)!!
        assertEquals(-14.0 / 146 * 100, score.total, 1e-9)
        assertEquals(0.0, score.statStageUtility)
        val protected = base.copy(state = base.state.copyState(pokemon = base.state.pokemon.map {
            if (it.side == BattleSide.ALLY) it.copyState(knownAbilityId = "magicguard") else it }))
        val safe = PublicBattleTacticalCalculator.calculate(protected)
        assertEquals(0.0, LocalRootHitReactionEvaluator.score(safe.candidates.single(), safe, LocalDecisionTuning.CURRENT)?.total ?: 0.0, 1e-9)
    }

    @Test fun `decision reuse preserves reaction values and separates changed HP contexts`() {
        val move = tackle()
        val c = PublicBattleTacticalCalculator.calculate(context(listOf(mon(TARGET, BattleSide.ALLY, item = "lifeorb"),
            mon(ACTOR, BattleSide.OPPONENT)), move))
        val expected = LocalRootHitReactionEvaluator.score(c.candidates.single(), c, LocalDecisionTuning.CURRENT)!!
        LocalDecisionCalculationScope.with { scope ->
            repeat(2) {
                val actual = LocalRootHitReactionEvaluator.score(c.candidates.single(), c, LocalDecisionTuning.CURRENT)!!
                assertEquals(expected.total.toBits(), actual.total.toBits())
            }
            assertTrue(scope.hits > 0)
            val hurt = c.copy(state = c.state.copyState(pokemon = c.state.pokemon.map {
                if (it.side == BattleSide.ALLY) it.copyState(hpFraction = 1.0 / 146) else it }))
            val changed = LocalRootHitReactionEvaluator.score(c.candidates.single(), hurt, LocalDecisionTuning.CURRENT)!!
            assertTrue(changed.total < expected.total, "recoil KO must not reuse the healthy holder's value")
        }
    }

    @Test fun `regenerator heals integer base HP and truant keeps its alternating attempts`() {
        val holder = mon(TARGET, BattleSide.ALLY, hp = 45.0 / 146, ability = "regenerator")
        val state = context(listOf(holder, mon(ACTOR, BattleSide.OPPONENT)), tackle()).state
        assertEquals(93.0 / 146, LocalSwitchStateProjector.projectedSwitchOutHp(state, holder), 1e-12)
        val loafing = holder.copyState(knownAbilityId = "truant", knownVolatileEffectIds = setOf("truant"))
        val loaf = state.copyState(pokemon = listOf(loafing, state.pokemon.last()))
        assertEquals(0.0, LocalSetupGate.actingChance(loafing, loaf))
        val profile = LocalKnockoutProfile.of(List(16) { 1.0 }, 1.0, 1.0, 6).withGapBetweenUses(false)
        assertEquals(listOf(1.0, 1.0, 0.0), profile.survival.take(3))
        assertEquals(2.0, profile.expectedUses)
        assertEquals(listOf(1.0, 0.0), LocalKnockoutProfile.of(List(16) { 1.0 }, 1.0, 1.0, 6)
            .withGapBetweenUses(true).survival.take(2))
    }

    @Test fun `KO boost is priced against the remaining opponent and is worthless after the last KO`() {
        val move = tackle()
        val actor = mon(TARGET, BattleSide.ALLY, ability = "moxie")
        val victim = mon(ACTOR, BattleSide.OPPONENT, hp = 1.0 / 146)
        val next = mon(UUID(0, 3), BattleSide.OPPONENT).copyState(activeSlot = null)
        val c = PublicBattleTacticalCalculator.calculate(context(listOf(actor, victim, next), move))
        val gain = LocalRootHitReactionEvaluator.score(c.candidates.single(), c, LocalDecisionTuning.CURRENT)!!
        assertTrue(gain.statStageUtility > 0.0, gain.toString())
        assertEquals(gain.statStageUtility, gain.total, 1e-9, "the existing KO price must not be added again")
        val last = PublicBattleTacticalCalculator.calculate(context(listOf(actor, victim), move))
        assertEquals(0.0, LocalRootHitReactionEvaluator.score(last.candidates.single(), last,
            LocalDecisionTuning.CURRENT)?.total ?: 0.0, 1e-9)
    }

    @Test fun `switch continuation reads reactive stages and consumed berry HP`() {
        val incomingId = UUID(0, 4)
        fun entry(ability: String?, item: String?, hp: Double): SwitchInScore {
            val incoming = mon(incomingId, BattleSide.ALLY, hp = hp, ability = ability, item = item).copyState(activeSlot = null)
            val c = context(listOf(mon(TARGET, BattleSide.ALLY), mon(ACTOR, BattleSide.OPPONENT), incoming), tackle())
            return LocalMatchupScoreCalculator.calculate(c).switchIn(incomingId, ACTOR, TARGET)!!
        }
        val weak = entry("weakarmor", null, 1.0).outcomes.getValue("tackle")
        assertTrue(weak.reactionBranches.all { it.stages["speed"] == 2 && it.stages["defence"] == -1 })
        assertEquals(1.0, weak.afterEntry!!.subjectMovesFirstProbability)
        val berry = entry(null, "sitrusberry", 81.0 / 146).outcomes.getValue("tackle")
        assertTrue(berry.hpAfterEntry > 81.0 / 146)
        assertTrue(berry.reactionBranches.all { it.itemChanged && it.healing > 0.0 })
        assertEquals(berry.score, berry.survival * berry.continuationScore - (1.0 - berry.survival), 1e-12)
        val inactive = entry(null, "sitrusberry", 1.0).outcomes.getValue("tackle")
        val plain = entry(null, null, 1.0).outcomes.getValue("tackle")
        assertTrue(inactive.reactionBranches.isEmpty())
        assertEquals(plain.hpAfterEntry.toBits(), inactive.hpAfterEntry.toBits())
        assertEquals(plain.score.toBits(), inactive.score.toBits())
    }

    @Test fun `spread life orb is paid once after all targets even when the first target is immune`() {
        val move = BattleActionCandidate("spread", BattleActionKind.USE_MOVE, 0, 0, "hypervoice",
            moveDetails = BattleMoveCandidateView("normal", BattleMoveDamageCategory.SPECIAL, 90.0, 100.0, 0, 20,
                targetPattern = BattleMoveTargetPattern.ALL_OPPONENTS))
        val actor = mon(TARGET, BattleSide.ALLY, hp = 10.0 / 146, item = "lifeorb")
        val immune = mon(ACTOR, BattleSide.OPPONENT, types = setOf("ghost"))
        val struck = mon(UUID(0, 3), BattleSide.OPPONENT).copyState(activeSlot = 1)
        val c = context(listOf(actor, immune, struck), move)
        val paths = LocalMoveImpactProjector.project(move, c, BattleSide.ALLY, LocalProjectedActionCalculationCache())
        assertEquals(1.0, paths.sumOf { it.probability }, 1e-10)
        assertTrue(paths.all { p -> p.state.pokemon.first { it.battlePokemonId == TARGET }.fainted })
        assertTrue(paths.all { p -> p.state.pokemon.first { it.battlePokemonId == struck.battlePokemonId }.hpFraction < 1.0 })
        assertTrue(paths.all { p -> p.state.pokemon.first { it.battlePokemonId == ACTOR }.hpFraction == 1.0 })
        val full = c.copy(state = c.state.copyState(pokemon = listOf(actor.copyState(hpFraction = 1.0),
            immune.copyState(knownTypeIds = setOf("psychic")), struck)))
        val scored = PublicBattleTacticalCalculator.calculate(full)
        assertEquals(-14.0 / 146 * 100, LocalRootHitReactionEvaluator.score(scored.candidates.single(), scored,
            LocalDecisionTuning.CURRENT)!!.total, 1e-9)
    }

    @Test fun `joint attacks consume one berry and price its actual healing once`() {
        val first = tackle()
        val second = BattleActionCandidate("partner", BattleActionKind.USE_MOVE, 1, 0, "tackle",
            targets = first.targets, moveDetails = first.moveDetails)
        val partner = mon(UUID(0, 3), BattleSide.ALLY).copyState(activeSlot = 1)
        val joint = BattleActionCandidate("joint", BattleActionKind.COMPOSITE,
            componentActionIds = listOf(first.actionId, second.actionId), componentActions = listOf(first, second))
        for (hp in listOf(81.0, 100.0)) {
            val c = context(listOf(mon(TARGET, BattleSide.ALLY), partner,
                mon(ACTOR, BattleSide.OPPONENT, hp = hp / 146, item = "sitrusberry")), first)
            val independent = listOf(first, second).sumOf { LocalRootHitReactionEvaluator.score(it, c, LocalDecisionTuning.CURRENT)?.total ?: 0.0 }
            val correction = LocalRootHitReactionEvaluator.compositeCorrection(joint, c, LocalDecisionTuning.CURRENT)
            assertEquals(-36.0 / 146 * 100, independent + correction.total, 1e-9)
        }
    }

    @Test fun `inactive try hit absorption skips joint reactions but later absorption remains eligible`() {
        val move = tackle()
        val c = context(listOf(mon(TARGET, BattleSide.ALLY), mon(ACTOR, BattleSide.OPPONENT, ability = "flashfire")), move)
        assertTrue(LocalMoveImpactProjector.mayReact(move, c, BattleSide.ALLY))
        assertFalse(LocalMoveImpactProjector.mayReact(move, c, BattleSide.ALLY, includeGuards = false))
        val heldByTarget = context(listOf(mon(TARGET, BattleSide.ALLY), mon(ACTOR, BattleSide.OPPONENT, item = "lifeorb")), move)
        assertFalse(LocalMoveImpactProjector.mayReact(move, heldByTarget, BattleSide.ALLY))
        val heldByUser = context(listOf(mon(TARGET, BattleSide.ALLY, item = "lifeorb"), mon(ACTOR, BattleSide.OPPONENT)), move)
        assertTrue(LocalMoveImpactProjector.mayReact(move, heldByUser, BattleSide.ALLY, includeGuards = false))
        val water = BattleActionCandidate("water", BattleActionKind.USE_MOVE, 0, 0, "watergun", targets = move.targets,
            moveDetails = BattleMoveCandidateView("water", BattleMoveDamageCategory.SPECIAL, 40.0, 100.0, 0, 20))
        val absorb = context(listOf(mon(TARGET, BattleSide.ALLY), mon(ACTOR, BattleSide.OPPONENT, ability = "waterabsorb")), water)
        assertTrue(LocalMoveImpactProjector.mayReact(water, absorb, BattleSide.ALLY, includeGuards = false))
    }

    private fun mon(id: UUID, side: BattleSide, hp: Double = 1.0, ability: String? = null, item: String? = null,
        types: Set<String> = setOf("psychic"), volatile: Set<String> = emptySet()) = BattlePokemonStateView(id, side, 0,
        "mew", null, 50, hp, null, emptyMap(), emptySet(), ability, item, false, knownTypeIds = types,
        knownVolatileEffectIds = volatile, combatStats = BattleCombatStatRangesView(BattleIntegerRange(146, 146),
            BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100), BattleIntegerRange(100, 100),
            BattleIntegerRange(100, 100), if (side == BattleSide.ALLY) BattleCombatStatKnowledge.EXACT_OWN else BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE))

    private fun context(pokemon: List<BattlePokemonStateView>, move: BattleActionCandidate) = BattleDecisionContext(UUID(0, 9),
        BattleStateView(UUID(0, 8), if (pokemon.any { it.activeSlot == 1 }) BattleFormat.DOUBLE else BattleFormat.SINGLE, 1, pokemon, BattleFieldStateView.empty(), BattleSide.entries.associateWith { side ->
            pokemon.count { it.side == side && !it.fainted }.coerceAtLeast(1) },
            emptyList(), emptyList()), listOf(move), Long.MAX_VALUE, publicActionCatalog = BattlePublicActionCatalogView(
            pokemon.map { BattlePokemonActionCatalogView(it.battlePokemonId,
                listOf(BattlePublicMoveOptionView("tackle", tackle().moveDetails!!, BattlePublicMoveKnowledge.PUBLICLY_REVEALED)), true) }))

    private fun tackle() = BattleActionCandidate("tackle", BattleActionKind.USE_MOVE, 0, 0, "tackle",
        targets = listOf(BattleTargetSlot(BattleSide.OPPONENT, 0)), moveDetails = BattleMoveCandidateView("normal",
            BattleMoveDamageCategory.PHYSICAL, 40.0, 100.0, 0, 20,
            effects = BattleMoveEffectsView(BattleMoveEffectCoverage.DECLARATIVE_PARTIAL, emptyList(), false, mechanicFlags = setOf("contact"))))

    private companion object { val TARGET = UUID(0, 1); val ACTOR = UUID(0, 2) }
}
