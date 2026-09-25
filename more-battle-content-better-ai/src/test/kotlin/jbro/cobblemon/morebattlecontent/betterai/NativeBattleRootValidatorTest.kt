package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatKnowledge
import jbro.cobblemon.morebattlecontent.api.ai.BattleCombatStatRangesView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFieldStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleIntegerRange
import jbro.cobblemon.morebattlecontent.api.ai.BattlePokemonStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleStateView
import jbro.cobblemon.morebattlecontent.api.ai.BattleTimedEffectView
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleDefinition
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFieldFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleOpeningState
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootIssueCode
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeBattleRootValidator
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeMoveFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSet
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonOpeningState
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativePokemonSourceSetFrame
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeTimedEffectFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeBattleRootValidatorTest {
    @Test
    fun `matching native root preserves exact own and bounded opponent public facts`() {
        val issues = NativeBattleRootValidator.validate(definition(), frame(), state())

        assertTrue(issues.isEmpty())
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
            ),
            publicPokemon(
                OPPONENT,
                BattleSide.OPPONENT,
                setOf("growl"),
                opponentAbility,
                opponentItem,
                rangedStats(),
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
        stats: BattleCombatStatRangesView,
    ) = BattlePokemonStateView(
        id,
        side,
        0,
        "showdown:mew",
        null,
        50,
        1.0,
        null,
        emptyMap(),
        moves,
        ability,
        item,
        false,
        setOf("psychic"),
        stats,
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
