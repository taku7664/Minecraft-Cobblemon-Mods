package jbro.cobblemon.morebattlecontent.api.access

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** Server-thread API. Addons register on SERVER_STARTING and close their handles on stop. */
object BattleContentAccess {
    private val servers = java.util.IdentityHashMap<MinecraftServer, ContentAccessPolicies>()

    internal fun registerLifecycle() {
        ServerLifecycleEvents.SERVER_STOPPED.register { servers.remove(it) }
    }

    fun register(server: MinecraftServer, contentIds: Set<String>, policy: ContentAccessPolicy): AutoCloseable =
        servers.getOrPut(server) {
            ContentAccessPolicies { MoreBattleContent.LOGGER.error("Content access provider failed", it) }
        }.register(contentIds, policy)

    fun check(player: ServerPlayer, contentId: String, action: ContentAccessAction): ContentAccessDecision =
        servers[player.server]?.check(player.uuid, contentId, action) ?: ContentAccessDecision.Allowed

    fun allow(player: ServerPlayer, contentId: String, action: ContentAccessAction): Boolean {
        val result = check(player, contentId, action)
        if (result is ContentAccessDecision.Denied) {
            player.sendSystemMessage(Component.translatable(result.reasonKey, *result.arguments.toTypedArray()))
            return false
        }
        return true
    }
}
