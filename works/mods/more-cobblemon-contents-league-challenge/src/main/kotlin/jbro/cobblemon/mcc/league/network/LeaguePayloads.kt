package jbro.cobblemon.mcc.league.network

import java.util.UUID
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

enum class LeagueAction { START, NEXT, CANCEL, REFRESH }
data class LeagueChallengeView(val id: String, val nameKey: String, val status: String, val unlockCap: Int,
    val badgeId: String? = null)
data class LeagueView(val nonce: UUID, val revision: Long, val catalogRevision: Long, val nameKey: String,
    val badges: Int, val rank: String, val cap: Int, val champion: Boolean, val bp: Long,
    val challenges: List<LeagueChallengeView>, val runChallenge: String?, val awaitingNext: Boolean,
    val pendingRewards: Boolean, val errorKey: String? = null,
    val openScreen: Boolean = false, val runNameKey: String? = null,
    /** The hard route, sent once the normal Champion opened it; [runHard] tells which route a run is on. */
    val hardUnlocked: Boolean = false, val hardChampion: Boolean = false,
    val hardChallenges: List<LeagueChallengeView> = emptyList(), val runHard: Boolean = false)

data class LeagueStatePayload(val json: String) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<LeagueStatePayload>(ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "state_v1"))
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
    val catalogRevision: Long, val action: LeagueAction, val challengeId: String = "") : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<LeagueIntentPayload>(ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "intent_v1"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, LeagueIntentPayload> = StreamCodec.of(
            { b, p -> b.writeUUID(p.nonce); b.writeUUID(p.requestId); b.writeLong(p.revision); b.writeLong(p.catalogRevision)
                b.writeEnum(p.action); b.writeUtf(p.challengeId, 256) },
            { b -> LeagueIntentPayload(b.readUUID(), b.readUUID(), b.readLong(), b.readLong(), b.readEnum(LeagueAction::class.java), b.readUtf(256)) })
    }
}
