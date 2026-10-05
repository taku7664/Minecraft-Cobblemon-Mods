package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import java.util.UUID
import jbro.cobblemon.mcc.MoreCobblemonContents
import jbro.cobblemon.mcc.internal.ai.BattleBrain
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleLeadChoiceContext
import jbro.cobblemon.mcc.internal.ai.BattleOpponentTeamPreviewView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePokemonStateView
import jbro.cobblemon.mcc.internal.ai.BattlePublicActionCatalogView
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveKnowledge
import jbro.cobblemon.mcc.internal.ai.BattlePublicMoveOptionView
import jbro.cobblemon.mcc.internal.ai.BattleSide
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import net.minecraft.core.registries.BuiltInRegistries

/**
 * Lets the trainer's Brain pick its lead after seeing the opponent's team preview.
 *
 * The AI team and its order used to be fixed by random catalog selection, so the lead never answered
 * the player's team. Only the lead moves; the rest of the team keeps its order. Any failure, or an
 * answer that does not name exactly the lead slots from this team, keeps the original order.
 */
internal object Cobblemon173LeadChoice {
    fun order(
        team: List<BattlePokemon>,
        preview: BattleOpponentTeamPreviewView?,
        format: BattleFormat,
        trainerProfile: BattleTrainerProfile,
        trainerPersonaId: String?,
        seed: Long,
        brains: List<BattleBrain?>,
        diagnosticsLabel: String,
    ): List<BattlePokemon> {
        if (preview == null || team.size < 2) return team
        val context = try {
            context(team, preview, format, trainerProfile, trainerPersonaId, seed)
        } catch (failure: RuntimeException) {
            MoreCobblemonContents.LOGGER.warn("{} lead choice skipped: own team view failed", diagnosticsLabel, failure)
            return team
        } catch (failure: LinkageError) {
            MoreCobblemonContents.LOGGER.warn("{} lead choice skipped: own team view failed", diagnosticsLabel, failure)
            return team
        }
        for (brain in brains.filterNotNull().distinct()) {
            val leads = try {
                brain.chooseLeads(context)
            } catch (failure: RuntimeException) {
                MoreCobblemonContents.LOGGER.warn("{} lead choice failed in {}", diagnosticsLabel, brain.javaClass.simpleName, failure)
                null
            } ?: continue
            val reordered = reorder(team, BattlePokemon::uuid, leads, context.leadCount)
            if (reordered == null) {
                MoreCobblemonContents.LOGGER.warn("{} lead choice ignored: invalid leads {}", diagnosticsLabel, leads)
                return team
            }
            return reordered
        }
        return team
    }

    /** Moves [leads] to the front in order; null unless they are exactly [leadCount] distinct team members. */
    internal fun <T> reorder(team: List<T>, id: (T) -> UUID, leads: List<UUID>, leadCount: Int): List<T>? {
        if (leads.size != leadCount || leads.distinct().size != leads.size) return null
        val byId = team.associateBy(id)
        val chosen = leads.map { byId[it] ?: return null }
        return chosen + team.filterNot { it in chosen }
    }

    private fun context(
        team: List<BattlePokemon>,
        preview: BattleOpponentTeamPreviewView,
        format: BattleFormat,
        trainerProfile: BattleTrainerProfile,
        trainerPersonaId: String?,
        seed: Long,
    ) = BattleLeadChoiceContext(
        format = format,
        ownTeam = team.map(::ownState),
        ownMoves = BattlePublicActionCatalogView(team.map { battlePokemon ->
            BattlePokemonActionCatalogView(
                battlePokemonId = battlePokemon.uuid,
                moves = battlePokemon.moveSet.getMoves().mapNotNull { move ->
                    Cobblemon173ActionCandidateAdapter.publicMoveDetails(move.name)?.let { details ->
                        BattlePublicMoveOptionView(move.name, details, BattlePublicMoveKnowledge.EXACT_OWN)
                    }
                },
                moveSetComplete = true,
            )
        }),
        exactOwnTeam = Cobblemon173ExactOwnTeamView.from(team),
        opponentTeamPreview = Cobblemon173PublicTeamPreviewKnowledge.enrich(preview),
        trainerProfile = trainerProfile,
        trainerPersonaId = trainerPersonaId,
        seed = seed,
    )

    /** The same own-Pokemon view a turn decision receives, before any Pokemon is on the field. */
    private fun ownState(battlePokemon: BattlePokemon): BattlePokemonStateView {
        val pokemon = battlePokemon.effectedPokemon
        val heldItem = pokemon.heldItem()
        return BattlePokemonStateView(
            battlePokemonId = battlePokemon.uuid,
            side = BattleSide.ALLY,
            activeSlot = null,
            speciesId = pokemon.species.resourceIdentifier.toString(),
            formId = pokemon.form.name,
            level = pokemon.level,
            hpFraction = if (battlePokemon.maxHealth <= 0) 0.0 else
                (battlePokemon.health.toDouble() / battlePokemon.maxHealth).coerceIn(0.0, 1.0),
            statusId = cobblemonStatusToShowdown(pokemon.status?.status?.name?.toString()),
            statStages = emptyMap(),
            knownMoveIds = battlePokemon.moveSet.getMoves().mapTo(linkedSetOf()) { it.name },
            knownAbilityId = pokemon.ability.name,
            knownHeldItemId = if (heldItem.isEmpty) null else BuiltInRegistries.ITEM.getKey(heldItem.item).toString(),
            fainted = battlePokemon.health <= 0,
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
}
