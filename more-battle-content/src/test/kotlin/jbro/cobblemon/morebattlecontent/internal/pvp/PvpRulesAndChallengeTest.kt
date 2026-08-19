package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PvpRulesAndChallengeTest {
    private val challenger = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val opponent = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val outsider = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    @Test
    fun `champions preset is configurable without weakening fixed safety rules`() {
        val rules = PvpRulesPreset.champions()

        assertEquals(50, rules.battleLevel)
        assertEquals(90, rules.entrySelectionSeconds)
        assertEquals(45, rules.turnSelectionSeconds)
        assertEquals(7 * 60, rules.totalBattleSecondsPerPlayer)
        assertEquals(PvpBattleMechanic.entries.toSet(), rules.allowedMajorMechanics)
        assertFalse(rules.bagItemsAllowed)
        assertFalse(rules.experienceAllowed)
        assertFalse(rules.prizeMoneyAllowed)

        val customized = PvpRulesPreset.champions(entrySelectionSeconds = 120, turnSelectionSeconds = 60)
        assertEquals(120, customized.entrySelectionSeconds)
        assertEquals(60, customized.turnSelectionSeconds)
        assertThrows<IllegalArgumentException> { PvpRulesPreset.champions(turnSelectionSeconds = 0) }
    }

    @Test
    fun `single and double registration ranges and private selections are server validated`() {
        val single = acceptedTeam((1..3).map(::pokemon), PvpBattleFormat.SINGLE)
        val double = acceptedTeam((1..6).map(::pokemon), PvpBattleFormat.DOUBLE)

        assertEquals(3, acceptedSelection(single, (1..3).map(::pokemonId)).members.size)
        assertEquals(4, acceptedSelection(double, (1..4).map(::pokemonId)).members.size)
        assertTrue(PvpTeamRules.register((1..2).map(::pokemon), PvpBattleFormat.SINGLE) is PvpTeamRegistrationResult.Rejected)
        assertTrue(PvpTeamRules.register((1..3).map(::pokemon), PvpBattleFormat.DOUBLE) is PvpTeamRegistrationResult.Rejected)
        assertTrue(PvpTeamRules.select(double, listOf(pokemonId(1)), PvpBattleFormat.DOUBLE) is PvpTeamSelectionResult.Rejected)
        assertEquals(50, pokemon(1, level = 1).battleLevel)
        assertEquals(50, pokemon(1, level = 100).battleLevel)
    }

    @Test
    fun `registration rejects duplicate pokemon species and held items without a species allow-list`() {
        assertTrue(
            PvpTeamRules.register(listOf(pokemon(1), pokemon(1), pokemon(2)), PvpBattleFormat.SINGLE) is
                PvpTeamRegistrationResult.Rejected,
        )
        assertTrue(
            PvpTeamRules.register(
                listOf(pokemon(1), pokemon(2, species = "cobblemon:species1"), pokemon(3)),
                PvpBattleFormat.SINGLE,
            ) is PvpTeamRegistrationResult.Rejected,
        )
        assertTrue(
            PvpTeamRules.register(
                listOf(pokemon(1), pokemon(2, item = "cobblemon:item1"), pokemon(3)),
                PvpBattleFormat.SINGLE,
            ) is PvpTeamRegistrationResult.Rejected,
        )
        assertTrue(
            PvpTeamRules.register(
                listOf(pokemon(1, species = "addon:any_registered_species"), pokemon(2), pokemon(3)),
                PvpBattleFormat.SINGLE,
            ) is PvpTeamRegistrationResult.Accepted,
        )
    }

    @Test
    fun `challenge target can accept or reject and either participant can cancel before battle`() {
        val service = PvpChallengeService()
        val acceptedId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val accepted = service.invite(PvpChallengeRequest(acceptedId, challenger, opponent, PvpBattleFormat.SINGLE))

        assertTrue(accepted is PvpChallengeMutationResult.Applied)
        assertEquals(PvpChallengePhase.PENDING, service.get(acceptedId)?.phase)
        assertEquals(PvpChallengeMutationError.NOT_TARGET, service.accept(acceptedId, outsider).errorOrNull())
        assertEquals(PvpChallengePhase.TEAM_REGISTRATION, service.accept(acceptedId, opponent).challengeOrNull()?.phase)
        assertEquals(PvpChallengePhase.CANCELLED, service.cancel(acceptedId, challenger).challengeOrNull()?.phase)

        val rejectedId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        service.invite(PvpChallengeRequest(rejectedId, challenger, opponent, PvpBattleFormat.DOUBLE))
        assertEquals(PvpChallengePhase.REJECTED, service.reject(rejectedId, opponent).challengeOrNull()?.phase)
    }

    @Test
    fun `challenge ids are idempotent and conflicting payloads or busy players are rejected`() {
        val service = PvpChallengeService()
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val request = PvpChallengeRequest(id, challenger, opponent, PvpBattleFormat.SINGLE)

        assertTrue(service.invite(request) is PvpChallengeMutationResult.Applied)
        assertTrue(service.invite(request) is PvpChallengeMutationResult.Unchanged)
        assertEquals(
            PvpChallengeMutationError.REQUEST_CONFLICT,
            service.invite(request.copy(format = PvpBattleFormat.DOUBLE)).errorOrNull(),
        )
        assertEquals(
            PvpChallengeMutationError.PARTICIPANT_BUSY,
            service.invite(PvpChallengeRequest(UUID.randomUUID(), challenger, outsider, PvpBattleFormat.SINGLE)).errorOrNull(),
        )
    }

    @Test
    fun `completed challenges release both participants for later matches`() {
        val service = PvpChallengeService()
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        service.invite(PvpChallengeRequest(id, challenger, opponent, PvpBattleFormat.SINGLE))
        service.accept(id, opponent)

        assertEquals(PvpChallengePhase.ACTIVE, service.startMatch(id).challengeOrNull()?.phase)
        assertEquals(PvpChallengePhase.COMPLETED, service.complete(id).challengeOrNull()?.phase)
        assertTrue(
            service.invite(PvpChallengeRequest(UUID.randomUUID(), challenger, outsider, PvpBattleFormat.SINGLE)) is
                PvpChallengeMutationResult.Applied,
        )
    }

    @Test
    fun `accepted match reveals species only and requires explicit ready from both players`() {
        val match = PvpMatchSession(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            challenger,
            opponent,
            PvpBattleFormat.SINGLE,
        )
        match.register(challenger, acceptedTeam((1..3).map(::pokemon), PvpBattleFormat.SINGLE))
        match.register(opponent, acceptedTeam((4..6).map(::pokemon), PvpBattleFormat.SINGLE))

        assertEquals(PvpMatchPhase.TEAM_PREVIEW, match.phase)
        assertEquals(listOf("cobblemon:species4", "cobblemon:species5", "cobblemon:species6"), match.previewFor(challenger).speciesIds)
        match.select(challenger, (1..3).map(::pokemonId))
        assertEquals(PvpMatchPhase.TEAM_PREVIEW, match.phase)
        assertEquals(null, match.selectionFor(opponent))
        match.select(opponent, (4..6).map(::pokemonId))
        assertEquals(PvpMatchPhase.TEAM_PREVIEW, match.phase)
        assertFalse(match.isReady(challenger))
        assertFalse(match.isReady(opponent))
        match.ready(challenger)
        assertEquals(PvpMatchPhase.TEAM_PREVIEW, match.phase)
        assertTrue(match.isReady(challenger))
        assertTrue(match.unready(challenger))
        assertFalse(match.isReady(challenger))
        match.ready(challenger)
        match.ready(opponent)
        assertEquals(PvpMatchPhase.READY, match.phase)
        assertEquals(3, match.selectionFor(challenger)?.members?.size)
        assertEquals(3, match.selectionFor(opponent)?.members?.size)
    }

    private fun acceptedTeam(members: List<PvpPokemonRegistration>, format: PvpBattleFormat): PvpRegisteredTeam =
        (PvpTeamRules.register(members, format) as PvpTeamRegistrationResult.Accepted).team

    private fun acceptedSelection(
        team: PvpRegisteredTeam,
        ids: List<UUID>,
    ): PvpSelectedTeam = (PvpTeamRules.select(team, ids, team.format) as PvpTeamSelectionResult.Accepted).team

    private fun pokemon(
        index: Int,
        species: String = "cobblemon:species$index",
        item: String? = "cobblemon:item$index",
        level: Int = 50,
    ) = PvpPokemonRegistration(pokemonId(index), species, item, level)

    private fun pokemonId(index: Int): UUID = UUID(0, index.toLong())
}
