package jbro.cobblemon.mcc.internal.tower

import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentBattleTeamMaterialization
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentBattleTeamMaterializer
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentCatalog
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentProfile
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentRandom
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentSelectionResult
import jbro.cobblemon.mcc.internal.tower.opponent.TowerOpponentSelector
import jbro.cobblemon.mcc.internal.tower.opponent.TowerPokemonSet
import jbro.cobblemon.mcc.internal.selection.RecentSelectionHistory
import java.util.Collections
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.internal.ai.BattleBrainSelectionContext
import jbro.cobblemon.mcc.internal.ai.BattleEncounterRole
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile

internal class TowerPreparedPveBattle<P, O>(
    val request: TowerBattleLaunchRequest,
    playerTeam: Collection<P>,
    opponentTeam: Collection<O>,
    val profile: TowerOpponentProfile,
    val trainerProfile: BattleTrainerProfile,
    val opponentKind: TowerOpponentKind,
) {
    val playerTeam: List<P> = Collections.unmodifiableList(ArrayList(playerTeam))
    val opponentTeam: List<O> = Collections.unmodifiableList(ArrayList(opponentTeam))
    val mechanic: MajorBattleMechanic
        get() = request.mechanic
    val brainSelectionContext: BattleBrainSelectionContext = BattleBrainSelectionContext(
        contentId = BattleBrainContentIds.BATTLE_TOWER,
        encounterRole = when (opponentKind) {
            TowerOpponentKind.REGULAR -> BattleEncounterRole.REGULAR
            TowerOpponentKind.TIER_BOSS,
            TowerOpponentKind.MASTER_BALL_BOSS,
            -> BattleEncounterRole.BOSS
        },
        difficultyTier = trainerProfile.difficulty.tier,
    )
}

internal fun interface TowerPveBattleRuntime<P, O> {
    fun start(prepared: TowerPreparedPveBattle<P, O>): TowerBattleLaunchResult
}

internal class TowerPveBattleLauncher<P, O>(
    private val registeredTeamMaterializer: (UUID, TowerSelectedTeam) -> TowerRegisteredBattleTeamResult<P>,
    private val catalogSource: () -> TowerOpponentCatalog?,
    opponentMemberFactory: (TowerPokemonSet) -> O,
    private val runtime: TowerPveBattleRuntime<P, O>,
    private val random: TowerOpponentRandom,
    private val diagnostics: (String) -> Unit = {},
) : TowerBattleLauncher {
    private val opponentMaterializer = TowerOpponentBattleTeamMaterializer(opponentMemberFactory)
    private val recentProfiles = RecentSelectionHistory<UUID, String>(RECENT_PROFILE_LIMIT)
    private val recentSpecies = RecentSelectionHistory<UUID, String>(RECENT_SPECIES_LIMIT)

    override fun launch(request: TowerBattleLaunchRequest): TowerBattleLaunchResult {
        val playerTeam = registeredTeamMaterializer(request.playerId, request.selection)
        if (playerTeam !is TowerRegisteredBattleTeamResult.Created) {
            reportSafely("registered team could not be materialized for ${request.playerId}: ${playerTeam.describe()}")
            return TowerBattleLaunchResult.Unavailable
        }
        val catalog = catalogSource() ?: run {
            reportSafely("the Battle Tower opponent catalog is not loaded; check the earlier catalog reload errors")
            return TowerBattleLaunchResult.Unavailable
        }
        val opponentKind = TowerProgression.nextOpponent(request.progress)
        val opponent = TowerOpponentSelector(catalog, random).select(
            request.progress.nextStage,
            request.progress.format,
            opponentKind,
            request.mechanic,
            recentProfiles.recent(request.playerId),
            recentSpecies.recent(request.playerId),
            request.legendaryClassAllowed,
            // Every boss, so every 5th win, is a Champion.
            championBoss = opponentKind != TowerOpponentKind.REGULAR,
            challengerLegendaries = TowerLegendaryClassPolicy.count(request.selection.members.filter { it.legendaryClass }.map { it.speciesId }),
        )
        if (opponent !is TowerOpponentSelectionResult.Selected) {
            reportSafely(
                "no opponent could be selected for stage ${request.progress.nextStage}, format " +
                    "${request.progress.format}, kind $opponentKind, mechanic ${request.mechanic}, " +
                    "legendaryClassAllowed=${request.legendaryClassAllowed}: ${opponent.describe()}",
            )
            return TowerBattleLaunchResult.Unavailable
        }
        val level = TowerProgression.opponentLevel(Math.addExact(request.progress.currentWinStreak, 1))
        val team = opponent.team.map { set -> set.withBattleLevel(level) }
        val opponentTeam = opponentMaterializer.materialize(team)
        if (opponentTeam !is TowerOpponentBattleTeamMaterialization.Created) {
            reportSafely("opponent team ${opponent.profile.profileId} could not be materialized")
            return TowerBattleLaunchResult.Unavailable
        }
        val result = runtime.start(
            TowerPreparedPveBattle(
                request = request,
                playerTeam = playerTeam.members,
                opponentTeam = opponentTeam.members,
                profile = opponent.profile,
                trainerProfile = TowerBattleDifficultyPolicy.resolve(
                    request.progress.nextStage,
                    opponentKind,
                    opponent.profile.aiSkill,
                ),
                opponentKind = opponentKind,
            ),
        )
        if (result is TowerBattleLaunchResult.Started) {
            recentProfiles.record(request.playerId, opponent.profile.profileId)
            opponent.team.forEach { pokemon -> recentSpecies.record(request.playerId, pokemon.speciesId) }
        }
        return result
    }

    fun forget(playerId: UUID) {
        recentProfiles.forget(playerId)
        recentSpecies.forget(playerId)
    }

    fun clear() {
        recentProfiles.clear()
        recentSpecies.clear()
    }

    private fun reportSafely(message: String) {
        try {
            diagnostics(message)
        } catch (_: RuntimeException) {
            // Diagnostics cannot replace a contained launch failure.
        } catch (_: LinkageError) {
            // Compatibility diagnostics are best-effort at this boundary.
        }
    }

    private companion object {
        const val RECENT_PROFILE_LIMIT = 3
        const val RECENT_SPECIES_LIMIT = 24

        fun TowerRegisteredBattleTeamResult<*>.describe(): String = when (this) {
            is TowerRegisteredBattleTeamResult.Created -> "created"
            TowerRegisteredBattleTeamResult.NoSnapshot -> "no registered team snapshot is stored"
            is TowerRegisteredBattleTeamResult.MaterializationFailed ->
                "the registered team could not be read: ${cause.message}"
            is TowerRegisteredBattleTeamResult.SnapshotMismatch ->
                "the snapshot no longer matches Pokemon $pokemonId"
            is TowerRegisteredBattleTeamResult.CopyFailed ->
                "the battle copy of Pokemon $pokemonId failed: ${cause.message}"
        }

        fun TowerOpponentSelectionResult.describe(): String = when (this) {
            is TowerOpponentSelectionResult.Selected -> "selected"
            TowerOpponentSelectionResult.NoEligibleProfile ->
                "no trainer profile matches this stage, format, opponent kind, and mechanic"
            is TowerOpponentSelectionResult.NoLegalTeam ->
                "trainer $profileId has no legal team in its resolved set pool"
        }
    }
}
