package jbro.cobblemon.policy.support

import jbro.cobblemon.policy.support.DiscordGatewaySession.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordGatewaySessionTest {
    private val session = DiscordGatewaySession("TOKEN")

    private fun sent(action: Action) = (action as Action.Send).payload

    @Test
    fun `hello starts the heartbeat and logs in, online with no status text`() {
        val actions = session.onMessage("""{"op":10,"d":{"heartbeat_interval":41250},"s":null,"t":null}""")
        assertEquals(Action.Heartbeat(41250), actions[0])
        val identify = sent(actions[1])
        assertEquals(2, identify.get("op").asInt)
        val d = identify.getAsJsonObject("d")
        assertEquals("TOKEN", d.get("token").asString)
        assertEquals(0, d.get("intents").asInt)
        val presence = d.getAsJsonObject("presence")
        assertEquals("online", presence.get("status").asString)
        assertEquals(0, presence.getAsJsonArray("activities").size())
    }

    @Test
    fun `heartbeats carry the last sequence, and a missed acknowledgement reconnects`() {
        session.onMessage("""{"op":10,"d":{"heartbeat_interval":1000}}""")
        session.onMessage("""{"op":0,"t":"READY","s":1,"d":{"user":{"username":"빡켓몬봇"},"application":{"id":"77"},"guilds":[{"id":"5","unavailable":true}]}}""").let {
            assertEquals(listOf(Action.Ready("빡켓몬봇", "77", listOf("5"))), it)
        }
        val first = sent(session.heartbeat())
        assertEquals(1, first.get("op").asInt)
        assertEquals(1L, first.get("d").asLong)
        assertEquals(Action.Reconnect, session.heartbeat())
        session.onMessage("""{"op":11}""")
        session.onMessage("""{"op":0,"t":"GUILD_CREATE","s":2,"d":{}}""")
        assertEquals(2L, sent(session.heartbeat()).get("d").asLong)
    }

    @Test
    fun `Discord asking for a heartbeat gets one at once`() {
        val heartbeat = sent(session.onMessage("""{"op":1,"d":null}""").single())
        assertEquals(1, heartbeat.get("op").asInt)
        assertTrue(heartbeat.get("d").isJsonNull)
    }

    @Test
    fun `reconnect requests start over, a refused token stops for good`() {
        assertEquals(listOf(Action.Reconnect), session.onMessage("""{"op":7,"d":null}"""))
        assertEquals(listOf(Action.Reconnect), session.onMessage("""{"op":9,"d":false}"""))
        assertTrue(session.onClose(4004) is Action.Stop)
        assertTrue(session.onClose(4014) is Action.Stop)
        assertEquals(Action.Reconnect, session.onClose(1006))
        assertEquals(Action.Reconnect, session.onClose(4000))
    }

    @Test
    fun `slash commands arrive as interactions`() {
        val actions = session.onMessage("""{"op":0,"t":"INTERACTION_CREATE","s":3,"d":{"id":"9","type":2,"data":{"name":"접속자"}}}""")
        val interaction = actions.single() as Action.Interaction
        assertEquals("접속자", interaction.data.getAsJsonObject("data").get("name").asString)
    }
}
