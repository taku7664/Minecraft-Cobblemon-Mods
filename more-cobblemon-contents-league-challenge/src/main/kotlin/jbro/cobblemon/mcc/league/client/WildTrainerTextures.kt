package jbro.cobblemon.mcc.league.client

import com.google.gson.JsonParser
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import net.fabricmc.fabric.api.resource.ResourceReloadListenerKeys
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager

/**
 * Wild trainers wear RCT Trainers+ skins that live only in that resource pack. Without the pack each skin path is
 * pointed at the default player skin instead of the missing-texture checkerboard; with it the real skin loads.
 */
internal object WildTrainerTextures : SimpleSynchronousResourceReloadListener {
    private val list = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "wild_trainer_skins.json")
    private val fallback = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png")
    private val aliased = HashSet<ResourceLocation>()

    override fun getFabricId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "wild_trainer_textures")

    override fun getFabricDependencies(): Collection<ResourceLocation> = listOf(ResourceReloadListenerKeys.TEXTURES)

    override fun onResourceManagerReload(manager: ResourceManager) {
        val skins = try {
            manager.getResource(list).orElse(null)?.openAsReader()?.use { reader ->
                JsonParser.parseReader(reader).asJsonObject.getAsJsonArray("skins").map {
                    ResourceLocation.fromNamespaceAndPath("rctmod", "textures/trainers/single/${it.asJsonObject.get("file").asString}.png")
                }
            }.orEmpty()
        } catch (failure: RuntimeException) {
            Mod.LOGGER.warn("Wild trainer skin list could not be read", failure)
            return
        }
        val textures = Minecraft.getInstance().textureManager
        skins.forEach { skin ->
            val present = manager.getResource(skin).isPresent
            if (!present && aliased.add(skin)) {
                textures.register(skin, Alias(fallback))
            } else if (present && aliased.remove(skin)) {
                // The pack arrived; drop the alias so the real skin loads on its next use.
                textures.release(skin)
            }
        }
    }

    /** Draws another registered texture under a second name, without owning it. */
    private class Alias(private val target: ResourceLocation) : AbstractTexture() {
        override fun load(manager: ResourceManager) = Unit

        override fun getId(): Int = Minecraft.getInstance().textureManager.getTexture(target).id

        override fun releaseId() = Unit

        override fun close() = Unit
    }
}
