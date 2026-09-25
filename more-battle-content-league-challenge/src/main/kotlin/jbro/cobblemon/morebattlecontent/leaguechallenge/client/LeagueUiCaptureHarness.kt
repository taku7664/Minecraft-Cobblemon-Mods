package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import jbro.cobblemon.morebattlecontent.leaguechallenge.MoreBattleContentLeagueChallenge
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeFixtureCatalog
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Screenshot
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

internal object LeagueUiCaptureHarness {
    private val logger = LoggerFactory.getLogger(MoreBattleContentLeagueChallenge.MOD_ID)

    fun installFromEnvironment() {
        val backend = LeagueUiBackend.parseOrNull(System.getenv("MBC_LEAGUE_CAPTURE_BACKEND")) ?: return
        val fixtureId = System.getenv("MBC_LEAGUE_CAPTURE_FIXTURE")?.trim().orEmpty().ifEmpty { "badges_3" }
        val fixture = runCatching { LeagueHomeFixtureCatalog.require(fixtureId) }.getOrElse { error ->
            logger.error("Ignoring League UI capture with unknown fixture {}", fixtureId, error)
            return
        }
        val completed = AtomicBoolean(false)
        var opened = false
        var requested = false
        var ticks = 0

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (!opened) {
                if (client.screen == null || client.overlay != null) return@EndTick
                client.setScreen(
                    when (backend) {
                        LeagueUiBackend.CODE -> LeagueChallengeDevelopmentScreen(fixture)
                        LeagueUiBackend.OWO -> LeagueChallengeOwoSpikeScreen(fixture)
                    }
                )
                opened = true
                logger.info("Opened League UI capture backend={} fixture={}", backend.id, fixture.id)
                return@EndTick
            }

            ticks += 1
            if (!requested && ticks >= 20) {
                requested = true
                val scaledWidth = client.window.guiScaledWidth
                val scaledHeight = client.window.guiScaledHeight
                val filename = "league-${backend.id}-${fixture.id}-${scaledWidth}x${scaledHeight}.png"
                Screenshot.grab(client.gameDirectory, filename, client.mainRenderTarget) { result ->
                    logger.info("League UI capture {}: {}", filename, result.string)
                    completed.set(true)
                }
            }

            if (requested && (completed.get() || ticks >= 100)) {
                client.stop()
            }
        })
    }
}
