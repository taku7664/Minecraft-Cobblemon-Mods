package jbro.cobblemon.mcc.internal.compat.fabric

import jbro.cobblemon.mcc.MoreCobblemonContents
import net.minecraft.resources.ResourceLocation

internal object HoloBattleTerminalIds {
    const val PATH = "holo_battle_terminal"
    val id: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MoreCobblemonContents.MOD_ID, PATH)
}
