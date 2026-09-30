package jbro.cobblemon.mcc.internal.compat.fabric

import jbro.cobblemon.mcc.api.terminal.HoloTerminal
import jbro.cobblemon.mcc.internal.terminal.TerminalInteractionResult
import jbro.cobblemon.mcc.internal.terminal.TerminalInteractionService
import net.minecraft.server.level.ServerPlayer

/** Verifies uses of every hologram terminal and hands the verified ones to the hub. */
internal object HoloTerminalInteractions {
    val service = TerminalInteractionService()

    private var opener: (ServerPlayer, HoloTerminal, TerminalInteractionResult.Verified) -> Boolean = { _, _, _ -> false }

    fun install(opener: (ServerPlayer, HoloTerminal, TerminalInteractionResult.Verified) -> Boolean) {
        this.opener = opener
    }

    fun open(player: ServerPlayer, terminal: HoloTerminal, verification: TerminalInteractionResult.Verified): Boolean =
        opener(player, terminal, verification)
}
