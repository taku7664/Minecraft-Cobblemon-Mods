package jbro.cobblemon.morebattlecontent.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.morebattlecontent.MoreBattleContent
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainProviderRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainRegistry
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainContentIds
import jbro.cobblemon.morebattlecontent.api.ai.BattleBrainSelectionContext
import jbro.cobblemon.morebattlecontent.api.ai.BattleEncounterRole
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BrainCapability
import jbro.cobblemon.morebattlecontent.api.rules.MajorBattleMechanic
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.morebattlecontent.api.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.morebattlecontent.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.morebattlecontent.internal.command.AiTestBattleStartResult
import jbro.cobblemon.morebattlecontent.internal.command.AiTestCommandBackend
import jbro.cobblemon.morebattlecontent.internal.command.AiTestDifficulty
import jbro.cobblemon.morebattlecontent.internal.tower.TOWER_BATTLE_LEVEL_CAP
import jbro.cobblemon.morebattlecontent.internal.tower.TowerBattleLaunchResult
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerPokemonSet
import jbro.cobblemon.morebattlecontent.internal.tower.opponent.TowerStatSpread
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Starts an isolated, reward-free six-on-six Cynthia test battle without Tower progression or selection rules. */
internal object Cobblemon173AiTestBattleRuntime : AiTestCommandBackend {
    override fun start(player: ServerPlayer, difficulty: AiTestDifficulty): AiTestBattleStartResult {
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) {
            return AiTestBattleStartResult.AlreadyInBattle
        }
        if (!hasBetterAiProvider()) return AiTestBattleStartResult.BetterAiUnavailable

        val party = Cobblemon.storage.getParty(player).toList()
        if (party.size != CYNTHIA_TEAM_SIZE) return AiTestBattleStartResult.WrongPartySize(party.size)

        return try {
            val registrations = party.map(Pokemon::toTowerPokemonRegistration)
            val playerPreview = BattleOpponentTeamPreviewView(
                selectionSize = CYNTHIA_TEAM_SIZE,
                pokemon = registrations.mapIndexed { slot, pokemon ->
                    BattleOpponentTeamPreviewPokemonView(
                        previewSlotId = slot,
                        speciesId = pokemon.speciesId,
                        formId = pokemon.formId,
                        level = TOWER_BATTLE_LEVEL_CAP,
                    )
                },
            )
            val runtime = Cobblemon173TowerPveBattleRuntime(
                playerResolver = { playerId -> player.server.playerList.getPlayer(playerId) },
                sessionCompletion = { _, _, _, _ -> },
                sessionCancellation = { _, _, _ -> },
            )
            val launch = runtime.startManaged(
                Cobblemon173ManagedAiBattle(
                    playerId = player.uuid,
                    playerTeam = party.map { pokemon -> battleCopy(player, pokemon) },
                    opponentTeam = CynthiaAiTestFixture.team.map(
                        Cobblemon173OpponentPokemonPropertiesFactory::toBattlePokemon,
                    ),
                    trainerDisplayNameKey = CynthiaAiTestFixture.DISPLAY_NAME_KEY,
                    trainerPersonaId = "ai_test_cynthia_${difficulty.name.lowercase()}",
                    trainerAiSkill = difficulty.skillLevel,
                    trainerProfile = difficulty.trainerProfile(),
                    learningScopeId = UUID.randomUUID(),
                    opponentTeamPreview = playerPreview,
                    mechanic = MajorBattleMechanic.TERA,
                    format = BattleFormat.SINGLE,
                    brainSelectionContext = BattleBrainSelectionContext(
                        contentId = BattleBrainContentIds.AI_TEST,
                        encounterRole = BattleEncounterRole.BOSS,
                        difficultyTier = difficulty.difficulty.tier,
                    ),
                    contentId = ManagedBattleContentIds.AI_TEST,
                    diagnosticsLabel = "Cynthia Better AI test",
                ),
            )
            when (launch) {
                is TowerBattleLaunchResult.Started -> AiTestBattleStartResult.Started(launch.battleId)
                TowerBattleLaunchResult.Unavailable -> AiTestBattleStartResult.Unavailable
            }
        } catch (failure: RuntimeException) {
            MoreBattleContent.LOGGER.error("Cynthia Better AI test battle could not start for {}", player.uuid, failure)
            AiTestBattleStartResult.Unavailable
        } catch (failure: LinkageError) {
            MoreBattleContent.LOGGER.error("Cynthia Better AI test battle is incompatible for {}", player.uuid, failure)
            AiTestBattleStartResult.Unavailable
        }
    }

    private fun hasBetterAiProvider(): Boolean = BattleBrainRegistry.global().all().any { provider ->
        provider.id.value == BETTER_AI_PROVIDER_ID &&
            provider.role == BattleBrainProviderRole.LOCAL &&
            BrainCapability.SINGLE in provider.capabilities
    }

    private fun battleCopy(player: ServerPlayer, pokemon: Pokemon): BattlePokemon {
        val snapshot = pokemon.clone(false, player.registryAccess()).also { clone ->
            check(clone !== pokemon) { "Cobblemon returned the live party Pokemon for an AI test snapshot" }
            clone.level = TOWER_BATTLE_LEVEL_CAP
            clone.heal()
        }
        return BattlePokemon.safeCopyOf(snapshot).also { copy ->
            check(copy.effectedPokemon !== snapshot) { "Cobblemon returned the AI test snapshot as its battle copy" }
            check(copy.effectedPokemon.isBattleClone()) { "Cobblemon AI test battle clone marker is missing" }
            copy.effectedPokemon.heal()
        }
    }

    private const val CYNTHIA_TEAM_SIZE = 6
    private const val BETTER_AI_PROVIDER_ID =
        "cobblemon_more_battle_content_better_ai:local_tactical"
}

internal object CynthiaAiTestFixture {
    val team: List<TowerPokemonSet> = listOf(
        set(
            id = "ai_test_cynthia_spiritomb",
            species = "spiritomb",
            ability = "pressure",
            nature = "calm",
            item = "leftovers",
            moves = listOf("shadowball", "darkpulse", "willowisp", "painsplit"),
            evs = spread(hp = 252, defence = 128, specialDefence = 128),
            teraType = "ghost",
        ),
        set(
            id = "ai_test_cynthia_roserade",
            species = "roserade",
            ability = "naturalcure",
            nature = "timid",
            item = "focus_sash",
            moves = listOf("energyball", "sludgebomb", "shadowball", "toxicspikes"),
            evs = spread(specialAttack = 252, speed = 252),
            teraType = "grass",
        ),
        set(
            id = "ai_test_cynthia_togekiss",
            species = "togekiss",
            ability = "serenegrace",
            nature = "timid",
            item = "leftovers",
            moves = listOf("airslash", "aurasphere", "thunderwave", "roost"),
            evs = spread(hp = 252, speed = 252),
            teraType = "flying",
        ),
        set(
            id = "ai_test_cynthia_lucario",
            species = "lucario",
            ability = "innerfocus",
            nature = "jolly",
            item = "life_orb",
            moves = listOf("closecombat", "meteormash", "extremespeed", "swordsdance"),
            evs = spread(attack = 252, speed = 252),
            teraType = "steel",
        ),
        set(
            id = "ai_test_cynthia_milotic",
            species = "milotic",
            ability = "marvelscale",
            nature = "bold",
            item = "leftovers",
            moves = listOf("scald", "icebeam", "recover", "mirrorcoat"),
            evs = spread(hp = 252, defence = 252),
            teraType = "water",
        ),
        set(
            id = "ai_test_cynthia_garchomp",
            species = "garchomp",
            ability = "roughskin",
            nature = "jolly",
            item = "rocky_helmet",
            moves = listOf("earthquake", "dragonclaw", "stoneedge", "swordsdance"),
            evs = spread(attack = 252, speed = 252),
            teraType = "ground",
        ),
    )
    private fun set(
        id: String,
        species: String,
        ability: String,
        nature: String,
        item: String,
        moves: List<String>,
        evs: TowerStatSpread,
        teraType: String,
    ) = TowerPokemonSet(
        setId = id,
        setTier = 3,
        mechanic = MajorBattleMechanic.TERA,
        speciesId = "cobblemon:$species",
        formId = null,
        abilityId = "cobblemon:$ability",
        natureId = "cobblemon:$nature",
        heldItemId = "cobblemon:$item",
        moves = moves.map { move -> "cobblemon:$move" },
        ivs = spread(hp = 31, attack = 31, defence = 31, specialAttack = 31, specialDefence = 31, speed = 31),
        evs = evs,
        teraType = teraType,
    )

    private fun spread(
        hp: Int = 0,
        attack: Int = 0,
        defence: Int = 0,
        specialAttack: Int = 0,
        specialDefence: Int = 0,
        speed: Int = 0,
    ) = TowerStatSpread(hp, attack, defence, specialAttack, specialDefence, speed)

    const val DISPLAY_NAME_KEY = "trainer.cobblemon_more_battle_content.ai_test_cynthia"
}
