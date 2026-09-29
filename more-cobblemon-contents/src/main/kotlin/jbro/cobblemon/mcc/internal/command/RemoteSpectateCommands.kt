package jbro.cobblemon.mcc.internal.command

import jbro.cobblemon.mcc.internal.spectate.RemoteSpectateResult
import net.minecraft.server.level.ServerPlayer

internal fun interface SpectateCommandBackend {
    fun spectate(viewer: ServerPlayer, target: ServerPlayer): RemoteSpectateResult
}
