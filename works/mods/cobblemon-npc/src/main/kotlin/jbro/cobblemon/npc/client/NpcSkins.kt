package jbro.cobblemon.npc.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.platform.NativeImage
import jbro.cobblemon.npc.CobblemonNpc
import jbro.cobblemon.npc.dialogue.NpcSkinRef
import jbro.cobblemon.uikit.UiIcon
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.level.block.entity.SkullBlockEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The skins NPCs wear. A player's skin is looked up once like a player head's and drawn as soon as it arrives. A
 * texture skin (an RCT Trainers+ trainer) is read from the resource packs: its model, slim or wide, comes from the
 * image itself, and a texture no enabled pack has is drawn as a default skin rather than the missing-texture check.
 */
object NpcSkins : SimpleSynchronousResourceReloadListener {
    private val profiles = ConcurrentHashMap<String, GameProfile>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val textures = HashMap<ResourceLocation, PlayerSkin.Model?>()

    fun register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(this)
    }

    override fun getFabricId(): ResourceLocation = CobblemonNpc.id("npc_skins")

    // Packs come and go with a reload; texture skins are checked again on their next use.
    override fun onResourceManagerReload(manager: ResourceManager) = textures.clear()

    /** [name]'s profile with its skin, or null while it is still being looked up (or no such player exists). */
    fun profile(name: String): GameProfile? {
        if (name.isBlank()) return null
        profiles[name]?.let { return it }
        if (requested.add(name)) {
            SkullBlockEntity.fetchGameProfile(name).thenAccept { found -> found.ifPresent { profiles[name] = it } }
        }
        return null
    }

    fun skin(value: String): PlayerSkin = when (val ref = NpcSkinRef.parse(value)) {
        NpcSkinRef.Default -> fallback(value)
        is NpcSkinRef.Player -> profile(ref.name)?.let(Minecraft.getInstance().skinManager::getInsecureSkin) ?: fallback(value)
        is NpcSkinRef.Texture -> {
            val texture = ResourceLocation.fromNamespaceAndPath(ref.namespace, ref.path)
            model(texture)?.let { PlayerSkin(texture, null, null, null, it, false) } ?: fallback(value)
        }
    }

    /** The NPC drawn in a UI kit render slot, the way its skin field names it. */
    fun content(value: String, framing: UiModelFraming = UiModelFraming.PORTRAIT): CobblemonUiRenderContent {
        val ref = NpcSkinRef.parse(value)
        if (ref is NpcSkinRef.Player) profile(ref.name)?.let { return CobblemonUiRenderContent.PlayerProfile(it, framing) }
        val skin = skin(value)
        return CobblemonUiRenderContent.PlayerSkin(UiIcon(skin.texture().namespace, skin.texture().path),
            skin.model() == PlayerSkin.Model.SLIM, framing)
    }

    /** The model [texture] is drawn for, or null when no enabled pack has it. */
    fun model(texture: ResourceLocation): PlayerSkin.Model? = textures.getOrPut(texture) {
        val resource = Minecraft.getInstance().resourceManager.getResource(texture).orElse(null) ?: return@getOrPut null
        try {
            resource.open().use { stream -> NativeImage.read(stream).use(::modelOf) }
        } catch (failure: Exception) {
            CobblemonNpc.LOGGER.warn("NPC skin {} could not be read", texture, failure)
            null
        }
    }

    /** A slim skin leaves the last two columns of the right arm's back empty; a wide one fills them. */
    private fun modelOf(image: NativeImage): PlayerSkin.Model {
        if (image.width != 64 || image.height != 64) return PlayerSkin.Model.WIDE
        val empty = (20..31).all { y -> (54..55).all { x -> image.getPixelRGBA(x, y) ushr 24 == 0 } }
        return if (empty) PlayerSkin.Model.SLIM else PlayerSkin.Model.WIDE
    }

    private fun fallback(value: String) = DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(value.toByteArray()))
}
