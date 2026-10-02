package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.ai.BattleBrainProviderRole
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleBrainContentIds
import jbro.cobblemon.mcc.internal.ai.BattleBrainSelectionContext
import jbro.cobblemon.mcc.internal.ai.BattleEncounterRole
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BrainCapability
import jbro.cobblemon.mcc.api.rules.MajorBattleMechanic
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewPokemonView
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.api.presentation.ManagedBattleContentIds
import jbro.cobblemon.mcc.internal.command.AiTestBattleStartResult
import jbro.cobblemon.mcc.internal.command.AiTestCommandBackend
import jbro.cobblemon.mcc.internal.command.AiTestDifficulty
import jbro.cobblemon.mcc.internal.tower.TOWER_BATTLE_LEVEL_CAP
import jbro.cobblemon.mcc.internal.tower.opponent.TowerPokemonSet
import jbro.cobblemon.mcc.internal.tower.opponent.TowerStatSpread
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.google.gson.JsonParser
import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Starts an isolated, reward-free six-on-six Cynthia test battle without Tower progression or selection rules. With
 * the League Challenge installed it is the hard Champion as the League bundles her (levels 95 to 100, Mega Evolution,
 * her League skin) against the player's party at its own levels; without it, the level 50 Tera fixture below.
 */
internal object Cobblemon173AiTestBattleRuntime : AiTestCommandBackend {
    override fun start(player: ServerPlayer, difficulty: AiTestDifficulty): AiTestBattleStartResult {
        if (BattleRegistry.getBattleByParticipatingPlayerId(player.uuid) != null) {
            return AiTestBattleStartResult.AlreadyInBattle
        }
        if (!hasBetterAiProvider()) return AiTestBattleStartResult.BetterAiUnavailable

        val party = Cobblemon.storage.getParty(player).toList()
        if (party.size != CYNTHIA_TEAM_SIZE) return AiTestBattleStartResult.WrongPartySize(party.size)

        return try {
            val hard = HardCynthiaAiTestTeam.load(player.server)
            val registrations = party.map(Pokemon::toTowerPokemonRegistration)
            val playerPreview = BattleOpponentTeamPreviewView(
                selectionSize = CYNTHIA_TEAM_SIZE,
                pokemon = registrations.mapIndexed { slot, pokemon ->
                    BattleOpponentTeamPreviewPokemonView(
                        previewSlotId = slot,
                        speciesId = pokemon.speciesId,
                        formId = pokemon.formId,
                        level = if (hard != null) party[slot].level else TOWER_BATTLE_LEVEL_CAP,
                    )
                },
            )
            val engine = Cobblemon173ManagedAiBattleEngine(
                playerResolver = { playerId -> player.server.playerList.getPlayer(playerId) },
            )
            val launch = engine.start(
                Cobblemon173ManagedAiBattle(
                    playerId = player.uuid,
                    playerTeam = party.map { pokemon -> battleCopy(player, pokemon, capLevel = hard == null) },
                    opponentTeam = hard?.members?.map(HardCynthiaAiTestTeam::toBattlePokemon)
                        ?: CynthiaAiTestFixture.team.map(Cobblemon173OpponentPokemonPropertiesFactory::toBattlePokemon),
                    trainerDisplayNameKey = if (hard != null) HardCynthiaAiTestTeam.DISPLAY_NAME_KEY else CynthiaAiTestFixture.DISPLAY_NAME_KEY,
                    trainerPersonaId = "${BattleBrainContentIds.AI_TEST_PERSONA_PREFIX}${difficulty.name.lowercase()}",
                    trainerAiSkill = difficulty.skillLevel,
                    trainerProfile = difficulty.trainerProfile(),
                    learningScopeId = UUID.randomUUID(),
                    opponentTeamPreview = playerPreview,
                    mechanic = if (hard != null) MajorBattleMechanic.MEGA else MajorBattleMechanic.TERA,
                    format = BattleFormat.SINGLE,
                    brainSelectionContext = BattleBrainSelectionContext(
                        contentId = BattleBrainContentIds.AI_TEST,
                        encounterRole = BattleEncounterRole.BOSS,
                        difficultyTier = difficulty.difficulty.tier,
                    ),
                    contentId = ManagedBattleContentIds.AI_TEST,
                    diagnosticsLabel = "Cynthia Better AI test",
                    unboundedBrainDecision = true,
                    appearance = hard?.appearance,
                ),
                onEnded = {},
            )
            when (launch) {
                is PveLaunchResult.Started -> AiTestBattleStartResult.Started(launch.battleId)
                PveLaunchResult.Unavailable -> AiTestBattleStartResult.Unavailable
            }
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.error("Cynthia Better AI test battle could not start for {}", player.uuid, failure)
            AiTestBattleStartResult.Unavailable
        } catch (failure: LinkageError) {
            MoreCobblemonContents.LOGGER.error("Cynthia Better AI test battle is incompatible for {}", player.uuid, failure)
            AiTestBattleStartResult.Unavailable
        }
    }

    private fun hasBetterAiProvider(): Boolean = BattleBrainRegistry.global().all().any { provider ->
        provider.id.value == BETTER_AI_PROVIDER_ID &&
            provider.role == BattleBrainProviderRole.LOCAL &&
            BrainCapability.SINGLE in provider.capabilities
    }

    private fun battleCopy(player: ServerPlayer, pokemon: Pokemon, capLevel: Boolean): BattlePokemon {
        val snapshot = pokemon.clone(false, player.registryAccess()).also { clone ->
            check(clone !== pokemon) { "Cobblemon returned the live party Pokemon for an AI test snapshot" }
            if (capLevel) clone.level = TOWER_BATTLE_LEVEL_CAP
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
        "more_cobblemon_contents:local_tactical"
}

/** Hard Cynthia read from the League Challenge's data pack, so the test meets the team the League bundles today. */
internal object HardCynthiaAiTestTeam {
    private const val LEAGUE = "more_cobblemon_contents_league_challenge"
    private val TEAM = ResourceLocation.fromNamespaceAndPath(LEAGUE, "league-challenge/teams/cynthia_hard.json")
    private val APPEARANCE = ResourceLocation.fromNamespaceAndPath(LEAGUE, "league-challenge/appearances/cynthia.json")
    const val DISPLAY_NAME_KEY = "trainer.$LEAGUE.cynthia"

    class Team(val members: List<String>, val appearance: TrainerResourceSkin?)

    /** The team, or null without the League Challenge or when its files cannot be read. */
    fun load(server: MinecraftServer): Team? = try {
        val resources = server.resourceManager
        resources.getResource(TEAM).orElse(null)?.openAsReader()?.use { reader ->
            val members = JsonParser.parseReader(reader).asJsonObject.getAsJsonArray("pokemon").map { it.asString }
            val appearance = resources.getResource(APPEARANCE).orElse(null)?.openAsReader()?.use { skinReader ->
                val skin = JsonParser.parseReader(skinReader).asJsonObject
                skin["skin"]?.asString?.let { TrainerResourceSkin(it, skin["model"]?.asString == "slim") }
            }
            Team(members, appearance).takeIf { members.size == 6 }
        }
    } catch (failure: RuntimeException) {
        MoreCobblemonContents.LOGGER.warn("Hard Cynthia could not be read for the AI test; using the Tower fixture", failure)
        null
    }

    fun toBattlePokemon(member: String): BattlePokemon {
        val properties = PokemonProperties.parse(member)
        return BattlePokemon.safeCopyOf(Cobblemon173CatalogPokemonCreator.create(properties, properties.form)).also { battlePokemon ->
            Cobblemon173OpponentPokemonSafety.apply(battlePokemon.originalPokemon)
            Cobblemon173OpponentPokemonSafety.apply(battlePokemon.effectedPokemon)
            battlePokemon.effectedPokemon.heal()
        }
    }
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

    const val DISPLAY_NAME_KEY = "trainer.more_cobblemon_contents.ai_test_cynthia"
}
