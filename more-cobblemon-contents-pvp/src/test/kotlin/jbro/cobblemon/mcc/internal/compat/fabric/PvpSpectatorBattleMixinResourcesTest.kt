package jbro.cobblemon.mcc.internal.compat.fabric

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSpectatorBattleMixinResourcesTest {
    @Test
    fun `client mixin configuration owns the MCC spectator controls without changing Cobblemon`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/more_cobblemon_contents_pvp.mixins.json"))
        val clientMixins = resource.reader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject.getAsJsonArray("client").map { it.asString }.toSet()
        }

        assertTrue("client.BattleGuiPvpSpectatorMixin" in clientMixins)
        assertTrue("client.PartySendBindingPvpSpectatorMixin" in clientMixins)
    }
}
