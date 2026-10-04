package jbro.cobblemon.npc.client

import com.mojang.authlib.GameProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.world.level.block.entity.SkullBlockEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Player skins by player name, looked up once like a player head's and drawn as soon as they arrive. */
object NpcSkins {
    private val profiles = ConcurrentHashMap<String, GameProfile>()
    private val requested = ConcurrentHashMap.newKeySet<String>()

    /** [name]'s profile with its skin, or null while it is still being looked up (or no such player exists). */
    fun profile(name: String): GameProfile? {
        if (name.isBlank()) return null
        profiles[name]?.let { return it }
        if (requested.add(name)) {
            SkullBlockEntity.fetchGameProfile(name).thenAccept { found -> found.ifPresent { profiles[name] = it } }
        }
        return null
    }

    fun skin(name: String): PlayerSkin {
        val profile = profile(name) ?: return DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(name.toByteArray()))
        return Minecraft.getInstance().skinManager.getInsecureSkin(profile)
    }
}
