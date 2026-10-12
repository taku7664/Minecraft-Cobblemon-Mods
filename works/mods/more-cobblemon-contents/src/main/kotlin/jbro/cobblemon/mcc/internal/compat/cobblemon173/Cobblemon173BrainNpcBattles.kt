package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.BattleStartResult
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.entity.npc.NPCEntity
import java.util.UUID
import jbro.cobblemon.mcc.api.rules.BattleMechanicFlags
import jbro.cobblemon.mcc.internal.ai.BattleBrainProviderRole
import jbro.cobblemon.mcc.internal.ai.BattleBrainRegistry
import jbro.cobblemon.mcc.internal.ai.BattleBrainSelectionContext
import jbro.cobblemon.mcc.internal.ai.BattleEncounterRole
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleTrainerProfile
import jbro.cobblemon.mcc.internal.ai.BrainCapability
import net.minecraft.server.level.ServerPlayer

/**
 * A singles battle between a player and a Cobblemon NPC, set up as Cobblemon's `BattleBuilder.pvn` sets it up (the
 * player's own party, the NPC's party for the challenge, the NPC's battle theme, the battle on the NPC's list) but
 * with the trainer side played by the Better AI brain at [skill] instead of Cobblemon's NPC AI.
 */
internal object Cobblemon173BrainNpcBattles {
    fun start(player: ServerPlayer, npc: NPCEntity, leadId: UUID?, skill: Int, contentId: String): BattleStartResult {
        val playerTeam = Cobblemon.storage.getParty(player).toBattleTeam(false, false, leadId)
        val playerActor = PlayerBattleActor(player.uuid, playerTeam)
        val party = npc.getPartyForChallenge(listOf(player))
            ?: return com.cobblemon.mod.common.battles.ErroredBattleStart().apply {
                generalErrors.add(com.cobblemon.mod.common.battles.BattleStartError.noParty(npc))
            }
        val npcTeam = party.toBattleTeam(false, npc.npc.autoHealParty, null)
        val profile = BattleTrainerProfile.balanced(skill)
        val selection = BattleBrainSelectionContext(contentId, BattleEncounterRole.REGULAR, profile.difficulty.tier)
        val registry = BattleBrainRegistry.global()
        val trainerActor = Cobblemon173BrainTrainerBattleActor(
            server = player.server,
            trainerEntity = npc,
            trainerName = npc.name.string,
            actorId = npc.uuid,
            pokemonList = npcTeam,
            battleFormat = BattleFormat.SINGLE,
            opponentActorId = player.uuid,
            initialOpponentPokemonCount = playerTeam.size,
            baselineAi = Cobblemon173BaselineAiFactory.create(skill),
            mechanicPolicy = { Cobblemon173MechanicPolicy(BattleMechanicFlags.NONE) },
            trainerProfile = profile,
            primaryBrain = Cobblemon173BrainProviderResolver.create(registry, BrainCapability.SINGLE, BattleBrainProviderRole.PRIMARY, selection),
            localBrain = Cobblemon173BrainProviderResolver.create(registry, BrainCapability.SINGLE, BattleBrainProviderRole.LOCAL, selection),
            cobblemonNpc = true,
            opponentTeamPreview = Cobblemon173PublicTeamPreviewKnowledge.enrich(Cobblemon173PlayerTeamPreview.of(playerTeam)),
        )
        playerActor.battleTheme = npc.getBattleTheme()
        val result = BattleRegistry.startBattle(
            com.cobblemon.mod.common.battles.BattleFormat.GEN_9_SINGLES, BattleSide(playerActor), BattleSide(trainerActor), false)
        if (result is SuccessfulBattleStart) {
            npc.entityData.set(NPCEntity.BATTLE_IDS, npc.battleIds + result.battle.battleId)
        }
        return result
    }
}
