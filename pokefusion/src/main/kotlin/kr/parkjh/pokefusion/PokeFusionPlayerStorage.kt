package kr.parkjh.pokefusion

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

object PokeFusionPlayerStorage {
    sealed interface LoadResult {
        data class Success(val state: PokeFusionPlayerState) : LoadResult
        data object Corrupt : LoadResult
    }

    private val attachment: AttachmentType<CompoundTag> = AttachmentRegistry.create(
        ResourceLocation.fromNamespaceAndPath("pokefusion", "player_state")
    ) { builder: AttachmentRegistry.Builder<CompoundTag> ->
        builder.persistent(CompoundTag.CODEC).copyOnDeath()
    }

    fun initialize() = Unit

    fun load(player: ServerPlayer): LoadResult {
        val root = player.getAttached(attachment) ?: return LoadResult.Success(PokeFusionPlayerState())
        return try {
            LoadResult.Success(PokeFusionPlayerStateCodec.decode(root, player.registryAccess()))
        } catch (exception: RuntimeException) {
            LOGGER.error(
                "Pokefusion 플레이어 저장 데이터를 읽지 못했습니다. 원본을 보존하고 합성을 차단합니다: {}",
                player.scoreboardName,
                exception
            )
            LoadResult.Corrupt
        }
    }

    fun store(player: ServerPlayer, state: PokeFusionPlayerState) {
        player.setAttached(attachment, PokeFusionPlayerStateCodec.encode(state, player.registryAccess()))
    }
    private val LOGGER = LoggerFactory.getLogger(PokeFusionPlayerStorage::class.java)
}
