package jbro.cobblemon.morebattlecontent.internal.command

import java.util.UUID
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryBattleFormat
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryLevelMode
import jbro.cobblemon.morebattlecontent.internal.factory.FactoryPlayResult
import net.minecraft.server.level.ServerPlayer

internal interface FactoryCommandBackend {
    fun open(player: ServerPlayer): Boolean
    fun start(player: ServerPlayer, format: FactoryBattleFormat, levelMode: FactoryLevelMode): FactoryPlayResult
    fun select(player: ServerPlayer, setIds: List<String>): FactoryPlayResult
    fun revise(player: ServerPlayer): FactoryPlayResult
    fun battle(player: ServerPlayer, orderedSetIds: List<String>? = null): FactoryPlayResult
    fun keep(player: ServerPlayer): FactoryPlayResult
    fun swap(player: ServerPlayer, outgoingSetId: String, incomingToken: UUID): FactoryPlayResult
    fun status(player: ServerPlayer): FactoryPlayResult
    fun abandon(player: ServerPlayer): FactoryPlayResult

}
