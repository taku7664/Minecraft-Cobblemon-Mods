package jbro.cobblemon.policy.client

import com.cobblemon.mod.common.client.CobblemonClient
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Tab opens the Pokenav and Tab again closes it. Cobblemon UI's battle information also sits on Tab, and Minecraft
 * hands a key to only one mapping, so both read the key's state themselves; the Pokenav stays shut during a battle.
 * Opening goes through the server's `/pokenav`, the same as typing it.
 */
object PokenavKey {
    private const val POKENAV_SCREEN = "com.metacontent.cobblenav.client.gui.pokenav.PokenavScreen"

    private val key = KeyMapping("key.jbro_policy.pokenav", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_TAB, "key.categories.jbro_policy")
    private val screenClass: Class<*>? by lazy { runCatching { Class.forName(POKENAV_SCREEN) }.getOrNull() }
    private var wasDown = false

    fun register() {
        if (!FabricLoader.getInstance().isModLoaded("cobblenav")) return
        KeyBindingHelper.registerKeyBinding(key)
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
    }

    private fun tick(client: Minecraft) {
        val down = isDown(client)
        if (down && !wasDown) press(client)
        wasDown = down
    }

    private fun press(client: Minecraft) {
        val player = client.player ?: return
        val screen = client.screen
        when {
            screen != null && screenClass?.isInstance(screen) == true -> screen.onClose()
            screen == null && CobblemonClient.battle == null -> player.connection.sendCommand("pokenav")
        }
    }

    private fun isDown(client: Minecraft): Boolean {
        val bound = InputConstants.getKey(key.saveString())
        if (bound == InputConstants.UNKNOWN) return false
        val window = client.window.window
        return if (bound.type == InputConstants.Type.MOUSE) GLFW.glfwGetMouseButton(window, bound.value) == GLFW.GLFW_PRESS
        else InputConstants.isKeyDown(window, bound.value)
    }
}
