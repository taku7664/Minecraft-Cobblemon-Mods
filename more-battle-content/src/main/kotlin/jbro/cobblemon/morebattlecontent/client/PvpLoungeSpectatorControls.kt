package jbro.cobblemon.morebattlecontent.client

import jbro.cobblemon.morebattlecontent.internal.pvp.PvpSpectatorInputPolicy
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft

internal object PvpLoungeSpectatorControls {
    private var active = false

    fun register() {
        ClientTickEvents.START_CLIENT_TICK.register(::enforce)
        ClientTickEvents.END_CLIENT_TICK.register(::enforce)
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> active = false }
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) enforce(Minecraft.getInstance())
    }

    private fun enforce(client: Minecraft) {
        if (!active) return
        client.options.keyMappings
            .asSequence()
            .filter { PvpSpectatorInputPolicy.blocks(it.name) }
            .forEach { mapping ->
                mapping.isDown = false
                while (mapping.consumeClick()) Unit
            }
    }
}
