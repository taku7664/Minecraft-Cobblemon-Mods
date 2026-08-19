package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import jbro.cobblemon.morebattlecontent.MoreBattleContent
import net.minecraft.resources.ResourceLocation

internal object HoloBattleTerminalIds {
    const val PATH = "holo_battle_terminal"
    val id: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MoreBattleContent.MOD_ID, PATH)
}
