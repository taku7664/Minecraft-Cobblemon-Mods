package jbro.cobblemon.morebattlecontent.internal.command

import jbro.cobblemon.morebattlecontent.internal.spectate.RemoteSpectateResult
import net.minecraft.server.level.ServerPlayer

internal fun interface SpectateCommandBackend {
    fun spectate(viewer: ServerPlayer, target: ServerPlayer): RemoteSpectateResult
}
