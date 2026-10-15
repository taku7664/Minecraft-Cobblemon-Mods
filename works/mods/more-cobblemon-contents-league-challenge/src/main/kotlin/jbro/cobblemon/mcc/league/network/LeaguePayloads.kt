package jbro.cobblemon.mcc.league.network

import java.util.UUID
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

enum class LeagueAction { START, NEXT, CANCEL, REFRESH, SELECT_LEAGUE }
data class LeagueChallengeView(val id: String, val nameKey: String, val status: String, val unlockCap: Int,
    val badgeId: String? = null,
    /** The trainer skin the terminal draws a face from; for the League entry, the finals trainer faced next. */
    val skin: String? = null,
    val stage: Int = 0, val branch: Int = 0, val battleCap: Int = 0,
    val mechanicFlags: Int = 0, val type: String? = null, val format: String = "SINGLE",
    val bp: Long = 0, val unlockKeys: List<String> = emptyList(),
    /** The finals: on a route, the one entry standing for the whole run; among the finalists, each of its trainers. */
    val finalStage: Boolean = false)

data class LeagueRegionView(val id: String, val nameKey: String, val unlocked: Boolean)
data class LeagueFinalBranchView(val id: String, val nameKey: String)
data class LeagueView(val nonce: UUID, val revision: Long, val catalogRevision: Long, val nameKey: String,
    val badges: Int, val rank: String, val cap: Int, val champion: Boolean, val bp: Long,
    val challenges: List<LeagueChallengeView>, val runChallenge: String?, val awaitingNext: Boolean,
    val pendingRewards: Boolean, val errorKey: String? = null,
    val openScreen: Boolean = false, val runNameKey: String? = null,
    /** The hard route and its unlock state; [runHard] tells which route a run is on. */
    val hardUnlocked: Boolean = false, val hardChampion: Boolean = false,
    val hardChallenges: List<LeagueChallengeView> = emptyList(), val runHard: Boolean = false,
    val leagueId: String = "", val regions: List<LeagueRegionView> = emptyList(), val leagueUnlocked: Boolean = true,
    val finalBranches: List<LeagueFinalBranchView> = emptyList(), val hardFinalBranches: List<LeagueFinalBranchView> = emptyList(),
    val runEntry: LeagueChallengeView? = null,
    /**
     * The finals' trainers one by one, in the order they are fought, for the view that replaces the route once the
     * finals are next or running: beaten ones cleared, the one faced now open, the rest locked.
     */
    val finalists: List<LeagueChallengeView> = emptyList(), val hardFinalists: List<LeagueChallengeView> = emptyList())

data class LeagueStatePayload(val json: String) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<LeagueStatePayload>(ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "state_v2"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, LeagueStatePayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.json, 32767) }, { LeagueStatePayload(it.readUtf(32767)) })
    }
}

/** The player's League rank alone, kept current for the hub header whether or not the League tab is open. */
data class LeagueRankPayload(val rank: String) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<LeagueRankPayload>(ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "rank_v1"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, LeagueRankPayload> = StreamCodec.of(
            { buffer, payload -> buffer.writeUtf(payload.rank, 64) }, { LeagueRankPayload(it.readUtf(64)) })
    }
}

data class LeagueIntentPayload(val nonce: UUID, val requestId: UUID, val revision: Long,
    val catalogRevision: Long, val action: LeagueAction, val challengeId: String = "",
    val leagueId: String = "", val finalChoiceId: String = "") : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<LeagueIntentPayload>(ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "intent_v2"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, LeagueIntentPayload> = StreamCodec.of(
            { b, p -> b.writeUUID(p.nonce); b.writeUUID(p.requestId); b.writeLong(p.revision); b.writeLong(p.catalogRevision)
                b.writeEnum(p.action); b.writeUtf(p.challengeId, 256); b.writeUtf(p.leagueId, 256); b.writeUtf(p.finalChoiceId, 256) },
            { b -> LeagueIntentPayload(b.readUUID(), b.readUUID(), b.readLong(), b.readLong(), b.readEnum(LeagueAction::class.java), b.readUtf(256), b.readUtf(256), b.readUtf(256)) })
    }
}
