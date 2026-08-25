package jbro.cobblemon.morebattlecontent.internal.compat.fabric

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PvpSpectatorBattleMixinResourcesTest {
    @Test
    fun `client mixin configuration owns the MBC spectator controls without changing Cobblemon`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/cobblemon_more_battle_content.mixins.json"))
        val clientMixins = resource.reader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject.getAsJsonArray("client").map { it.asString }.toSet()
        }

        assertTrue("client.BattleGuiPvpSpectatorMixin" in clientMixins)
        assertTrue("client.PartySendBindingPvpSpectatorMixin" in clientMixins)
    }
}
