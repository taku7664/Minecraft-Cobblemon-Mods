package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.terminal.TerminalIdentityNbtCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

internal class HoloBattleTerminalBlockEntity(
    position: BlockPos,
    state: BlockState,
) : BlockEntity(HoloBattleTerminalContent.blockEntityType, position, state) {
    var terminalId: UUID = UUID.randomUUID()
        private set

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        terminalId = TerminalIdentityNbtCodec.read(tag, terminalId)
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        TerminalIdentityNbtCodec.write(tag, terminalId)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().also { tag -> TerminalIdentityNbtCodec.write(tag, terminalId) }
}
