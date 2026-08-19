package jbro.cobblemon.morebattlecontent.internal.terminal

import java.util.UUID
import net.minecraft.nbt.CompoundTag

internal object TerminalIdentityNbtCodec {
    fun write(tag: CompoundTag, terminalId: UUID) {
        tag.putUUID(TERMINAL_ID_TAG, terminalId)
    }

    fun read(tag: CompoundTag, fallbackId: UUID): UUID =
        if (tag.hasUUID(TERMINAL_ID_TAG)) tag.getUUID(TERMINAL_ID_TAG) else fallbackId

    private const val TERMINAL_ID_TAG = "terminal_id"
}
