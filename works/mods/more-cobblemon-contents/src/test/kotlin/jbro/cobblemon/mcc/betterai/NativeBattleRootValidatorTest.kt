package jbro.cobblemon.mcc.betterai

import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatKnowledge
import jbro.cobblemon.mcc.internal.ai.BattleCombatStatRangesView
import jbro.cobblemon.mcc.internal.ai.BattleFieldStateView
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleIntegerRange
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleStateView
import jbro.cobblemon.mcc.internal.ai.BattleTimedEffectView
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFieldFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleOpeningState
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootIssueCode
import jbro.cobblemon.mcc.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.mcc.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSet
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonOpeningState
import jbro.cobblemon.mcc.betterai.simulation.NativePokemonSourceSetFrame
import jbro.cobblemon.mcc.betterai.simulation.NativeTimedEffectFrame
import jbro.cobblemon.mcc.betterai.mechanics.LocalDynamax
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeBattleRootValidatorTest {
    @Test
    fun `rebuilt Dynamax roots preserve public markers remaining turns HP ratio and base combat stats`() {
        BattleSide.entries.forEach { side ->
            val plain = frame()
            val id = if (side == BattleSide.ALLY) ALLY else OPPONENT
            val dynamaxed = (plain.p1Team + plain.p2Team).single { it.uuid == id.toString() }.copy(
                hp = 160,
                maxHp = 200,
                baseMaxHp = 100,
                volatiles = listOf(LocalDynamax.VOLATILE),
                volatileTurns = mapOf(LocalDynamax.VOLATILE to 2),
                gigantamax = true,
            )
            val rebuilt = if (side == BattleSide.ALLY) plain.copy(
                p1Active = listOf(dynamaxed), p1Team = listOf(dynamaxed),
            ) else plain.copy(
                p2Active = listOf(dynamaxed), p2Team = listOf(dynamaxed),
            )

            val public = state(dynamaxSide = side, dynamaxTurns = 2)
            assertTrue(NativeBattleRootValidator.validate(definition(), rebuilt, public).isEmpty(), side.name)

            assertEquals(
                setOf(NativeBattleRootIssueCode.VOLATILE_MISMATCH),
                NativeBattleRootValidator.validate(
                    definition(), rebuilt, state(dynamaxSide = side, dynamaxTurns = 1),
                ).mapTo(linkedSetOf()) { it.code },
                side.name,
            )
        }
    }

    @Test
    fun `matching native root preserves exact own and bounded opponent public facts`() {
        val issues = NativeBattleRootValidator.validate(definition(), frame(), state())

        assertTrue(issues.isEmpty())
    }

    @Test
    fun `rounded opponent HP is accepted but exact own HP and wrong public percentages are rejected`() {
        val plain = frame()
        val opponent = plain.p2Team.single().copy(hp = 190, maxHp = 202)
        val rounded = plain.copy(p2Active = listOf(opponent), p2Team = listOf(opponent))
        val public = state(opponentHp = 0.95, opponentStats = null)

        assertTrue(NativeBattleRootValidator.validate(definition(), rounded, public).isEmpty())
        val wrongPercent = state(opponentHp = 0.94, opponentStats = null)
        assertEquals(
            setOf(NativeBattleRootIssueCode.HP_MISMATCH),
            NativeBattleRootValidator.validate(definition(), rounded, wrongPercent).mapTo(linkedSetOf()) { it.code },
        )
        // The game's exact ratio for an opponent whose true max HP differs: the nearest whole HP stands for it.
        val exactRatio = state(opponentHp = 0.9384, opponentStats = null)
        assertTrue(NativeBattleRootValidator.validate(definition(), rounded, exactRatio).isEmpty())
        val farRatio = state(opponentHp = 0.9284, opponentStats = null)
        assertEquals(
            setOf(NativeBattleRootIssueCode.HP_MISMATCH),
            NativeBattleRootValidator.validate(definition(), rounded, farRatio).mapTo(linkedSetOf()) { it.code },
        )
        val ally = plain.p1Team.single().copy(hp = 94)
        val wrongOwn = plain.copy(p1Active = listOf(ally), p1Team = listOf(ally))
        val ownPercent = state(ownHp = 0.95)
        assertEquals(
            setOf(NativeBattleRootIssueCode.HP_MISMATCH),
            NativeBattleRootValidator.validate(definition(), wrongOwn, ownPercent).mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `unrevealed opponent facts and ranged field duration do not reject a valid root`() {
        val issues = NativeBattleRootValidator.validate(
            definition(),
            frame(),
            state(
                opponentAbility = null,
                opponentItem = null,
                weatherTurns = null,
                weatherTurnsRange = BattleIntegerRange(2, 4),
            ),
        )

        assertTrue(issues.isEmpty())
    }

    @Test
    fun `wrong own stats revealed opponent ability and field fail together`() {
        val issues = NativeBattleRootValidator.validate(
            definition(),
            frame(allyAttack = 99),
            state(opponentAbility = "pressure", weather = "sunnyday"),
        )

        assertEquals(
            setOf(
                NativeBattleRootIssueCode.COMBAT_STATS_MISMATCH,
                NativeBattleRootIssueCode.ABILITY_MISMATCH,
                NativeBattleRootIssueCode.FIELD_MISMATCH,
            ),
            issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `definition and native frame disagreement is rejected even when fact is not public`() {
        val issues = NativeBattleRootValidator.validate(
            definition(),
            frame(opponentItem = "leftovers"),
            state(opponentAbility = null, opponentItem = null),
        )

        assertEquals(
            setOf(NativeBattleRootIssueCode.DEFINITION_FRAME_MISMATCH),
            issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `callback-mutated live identity is checked against public facts but not mistaken for source set drift`() {
        val issues = NativeBattleRootValidator.validate(
            definition(),
            frame(
                opponentAbility = "pressure",
                opponentItem = "",
                opponentSpecies = "mewtwo",
                opponentSourceItem = "choicespecs",
            ),
            state(opponentAbility = "pressure", opponentItem = null),
        )

        assertTrue(issues.isEmpty(), issues.toString())
    }

    @Test
    fun `opening seed disagreement is rejected even when live public state matches the frame`() {
        val definition = definition().copy(
            openingState = NativeBattleOpeningState(
                listOf(
                    NativePokemonOpeningState(ALLY.toString(), 80, 100, "synchronize", "leftovers"),
                    NativePokemonOpeningState(OPPONENT.toString(), 100, 100, "levitate", "choicespecs"),
                ),
            ),
        )

        val issues = NativeBattleRootValidator.validate(definition, frame(), state())

        assertEquals(
            setOf(NativeBattleRootIssueCode.OPENING_STATE_MISMATCH),
            issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `matching opening seed metadata is accepted`() {
        val definition = definition().copy(
            openingState = NativeBattleOpeningState(
                listOf(
                    NativePokemonOpeningState(ALLY.toString(), 100, 100, "synchronize", "leftovers"),
                    NativePokemonOpeningState(OPPONENT.toString(), 100, 100, "levitate", "choicespecs"),
                ),
            ),
        )
        val plain = frame()
        val ally = plain.p1Team.single().withOpeningSeed(100, 100)
        val opponent = plain.p2Team.single().withOpeningSeed(100, 100)
        val seeded = plain.copy(
            p1Active = listOf(ally),
            p2Active = listOf(opponent),
            p1Team = listOf(ally),
            p2Team = listOf(opponent),
        )

        val issues = NativeBattleRootValidator.validate(definition, seeded, state())

        assertTrue(issues.isEmpty())
    }

    @Test
    fun `active request view must match team active slots`() {
        val root = frame()
        val issues = NativeBattleRootValidator.validate(
            definition(),
            root.copy(p1Active = root.p2Active),
            state(),
        )

        assertEquals(
            setOf(NativeBattleRootIssueCode.ACTIVE_VIEW_MISMATCH),
            issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `source build metadata is checked even when final combat stats happen to match`() {
        val plain = frame()
        val opponent = plain.p2Team.single().copy(
            sourceSet = requireNotNull(plain.p2Team.single().sourceSet).copy(gender = "F"),
        )
        val altered = plain.copy(p2Active = listOf(opponent), p2Team = listOf(opponent))

        val issues = NativeBattleRootValidator.validate(definition(), altered, state())

        assertEquals(
            setOf(NativeBattleRootIssueCode.DEFINITION_FRAME_MISMATCH),
            issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `source Tera type disagreement is rejected before search`() {
        val plain = frame()
        val ally = plain.p1Team.single().copy(
            sourceSet = requireNotNull(plain.p1Team.single().sourceSet).copy(teraType = "Water"),
        )
        val altered = plain.copy(p1Active = listOf(ally), p1Team = listOf(ally))

        val issues = NativeBattleRootValidator.validate(definition(), altered, state())

        assertEquals(
            setOf(NativeBattleRootIssueCode.DEFINITION_FRAME_MISMATCH),
            issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    @Test
    fun `public battle format must match the native definition`() {
        val issues = NativeBattleRootValidator.validate(
            definition(),
            frame(),
            state(format = BattleFormat.DOUBLE),
        )

        assertEquals(
            setOf(NativeBattleRootIssueCode.FORMAT_MISMATCH),
            issues.mapTo(linkedSetOf()) { it.code },
        )
    }

    private fun definition() = NativeBattleDefinition(
        "cobblemonsingles",
        listOf(1, 2, 3, 4),
        listOf(NativePokemonSet("Ally", "Mew", listOf("tackle"), "synchronize", ALLY.toString(), "leftovers", teraType = "Psychic")),
        listOf(NativePokemonSet("Opponent", "Mew", listOf("growl"), "levitate", OPPONENT.toString(), "choicespecs", teraType = "Psychic")),
    )

    private fun frame(
        allyAttack: Int = 100,
        opponentItem: String = "choicespecs",
        opponentAbility: String = "levitate",
        opponentMove: String = "growl",
        opponentSpecies: String = "mew",
        opponentSourceItem: String = opponentItem,
    ): NativeBattleFrame {
        val ally = nativePokemon(ALLY, "synchronize", "leftovers", "tackle", allyAttack, 100)
        val opponent = nativePokemon(
            OPPONENT,
            opponentAbility,
            opponentItem,
            opponentMove,
            80,
            90,
            opponentSpecies,
            NativePokemonSourceSetFrame(
                "Mew",
                "levitate",
                opponentSourceItem,
                listOf("growl"),
                "Serious",
                "M",
                ZERO_EVS,
                PERFECT_IVS,
                "Psychic",
            ),
        )
        return NativeBattleFrame(
            snapshotJson = "root",
            turn = 1,
            requestState = "move",
            ended = false,
            p1Active = listOf(ally),
            p2Active = listOf(opponent),
            p1Team = listOf(ally),
            p2Team = listOf(opponent),
            p1RequestJson = "{}",
            p2RequestJson = "{}",
            field = NativeBattleFieldFrame(
                weather = NativeTimedEffectFrame("raindance", 3, null),
                terrain = null,
                pseudoWeather = emptyList(),
                p1SideConditions = emptyList(),
                p2SideConditions = emptyList(),
            ),
            log = emptyList(),
        )
    }

    private fun nativePokemon(
        id: UUID,
        ability: String,
        item: String,
        move: String,
        attack: Int,
        speed: Int,
        species: String = "mew",
        sourceSet: NativePokemonSourceSetFrame = NativePokemonSourceSetFrame(
            "Mew",
            ability,
            item,
            listOf(move),
            "Serious",
            "M",
            ZERO_EVS,
            PERFECT_IVS,
            "Psychic",
        ),
    ) = NativePokemonFrame(
        uuid = id.toString(),
        species = species,
        hp = 100,
        maxHp = 100,
        status = "",
        ability = ability,
        item = item,
        types = listOf("Psychic"),
        boosts = emptyMap(),
        volatiles = emptyList(),
        moves = listOf(NativeMoveFrame(move, 35, 35, false)),
        activeSlot = 0,
        level = 50,
        stats = mapOf("atk" to attack, "def" to 100, "spa" to 100, "spd" to 100, "spe" to speed),
        sourceSet = sourceSet,
    )

    private fun NativePokemonFrame.withOpeningSeed(hp: Int, maxHp: Int) = copy(
        sourceSet = requireNotNull(sourceSet).copy(
            openingHp = hp,
            openingMaxHp = maxHp,
            openingStatus = status,
        ),
    )

    private fun state(
        opponentAbility: String? = "levitate",
        opponentItem: String? = "choicespecs",
        weather: String = "raindance",
        weatherTurns: Int? = 3,
        weatherTurnsRange: BattleIntegerRange? = null,
        format: BattleFormat = BattleFormat.SINGLE,
        ownHp: Double = 1.0,
        opponentHp: Double = 1.0,
        opponentStats: BattleCombatStatRangesView? = rangedStats(),
        dynamaxSide: BattleSide? = null,
        dynamaxTurns: Int = 2,
    ) = BattleStateView(
        battleId = BATTLE,
        format = format,
        turn = 1,
        pokemon = listOf(
            publicPokemon(
                ALLY,
                BattleSide.ALLY,
                setOf("tackle"),
                "synchronize",
                "leftovers",
                exactStats(),
                if (dynamaxSide == BattleSide.ALLY) 0.8 else ownHp,
                dynamaxVolatiles(dynamaxSide == BattleSide.ALLY, dynamaxTurns),
            ),
            publicPokemon(
                OPPONENT,
                BattleSide.OPPONENT,
                setOf("growl"),
                opponentAbility,
                opponentItem,
                opponentStats,
                if (dynamaxSide == BattleSide.OPPONENT) 0.8 else opponentHp,
                dynamaxVolatiles(dynamaxSide == BattleSide.OPPONENT, dynamaxTurns),
            ),
        ),
        field = BattleFieldStateView(
            weather = BattleTimedEffectView(
                effectId = weather,
                remainingTurns = weatherTurns,
                remainingTurnsRange = weatherTurnsRange,
            ),
            terrain = null,
            roomEffects = emptyList(),
            globalEffects = emptyList(),
            sideConditions = BattleSide.entries.associateWith { emptyList() },
        ),
        remainingPokemonBySide = BattleSide.entries.associateWith { 1 },
        observedEvents = emptyList(),
        inferences = emptyList(),
    )

    private fun publicPokemon(
        id: UUID,
        side: BattleSide,
        moves: Set<String>,
        ability: String?,
        item: String?,
        stats: BattleCombatStatRangesView?,
        hp: Double,
        volatiles: Set<String> = emptySet(),
    ) = BattlePokemonStateView(
        id,
        side,
        0,
        "showdown:mew",
        null,
        50,
        hp,
        null,
        emptyMap(),
        moves,
        ability,
        item,
        false,
        setOf("psychic"),
        stats,
        emptyMap(),
        jbro.cobblemon.mcc.internal.ai.BattlePokemonActionConstraintView.empty(),
        volatiles,
    )

    private fun dynamaxVolatiles(active: Boolean, turns: Int): Set<String> = if (!active) emptySet() else setOf(
        LocalDynamax.VOLATILE,
        LocalDynamax.TURNS + turns,
        LocalDynamax.GIGANTAMAX,
    )

    private fun exactStats() = BattleCombatStatRangesView.exact(100, 100, 100, 100, 100, 100)

    private fun rangedStats() = BattleCombatStatRangesView(
        BattleIntegerRange(90, 110),
        BattleIntegerRange(70, 90),
        BattleIntegerRange(90, 110),
        BattleIntegerRange(90, 110),
        BattleIntegerRange(90, 110),
        BattleIntegerRange(80, 100),
        BattleCombatStatKnowledge.PUBLIC_SPECIES_RANGE,
    )

    private companion object {
        val BATTLE: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val ALLY: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        val OPPONENT: UUID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        val ZERO_EVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 0 }
        val PERFECT_IVS = setOf("hp", "atk", "def", "spa", "spd", "spe").associateWith { 31 }
    }
}
