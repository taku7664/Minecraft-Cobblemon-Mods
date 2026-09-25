package jbro.cobblemon.morebattlecontent.leaguechallenge.client

import jbro.cobblemon.morebattlecontent.leaguechallenge.MoreBattleContentLeagueChallenge
import jbro.cobblemon.morebattlecontent.leaguechallenge.ui.LeagueHomeFixtureCatalog
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.narration.NarrationThunk
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object LeagueUiCaptureHarness {
    private val logger = LoggerFactory.getLogger(MoreBattleContentLeagueChallenge.MOD_ID)

    fun installFromEnvironment() {
        val backend = LeagueUiBackend.parseOrNull(System.getenv("MBC_LEAGUE_CAPTURE_BACKEND")) ?: return
        val localeValue = System.getenv("MBC_LEAGUE_CAPTURE_LOCALE")?.trim().orEmpty()
        val requestedLocale = if (localeValue.isEmpty()) {
            null
        } else {
            LeagueUiLocale.parseOrNull(localeValue) ?: run {
                logger.error("Ignoring League UI capture with unsupported locale {}", localeValue)
                return
            }
        }
        val guiScaleValue = System.getenv("MBC_LEAGUE_CAPTURE_GUI_SCALE")?.trim().orEmpty()
        val guiScale = if (guiScaleValue.isEmpty()) {
            null
        } else {
            guiScaleValue.toIntOrNull()?.takeIf { it in 1..4 } ?: run {
                logger.error("Ignoring League UI capture with invalid GUI scale {}", guiScaleValue)
                return
            }
        }
        val fixtureId = System.getenv("MBC_LEAGUE_CAPTURE_FIXTURE")?.trim().orEmpty().ifEmpty { "badges_3" }
        val fixture = runCatching { LeagueHomeFixtureCatalog.require(fixtureId) }.getOrElse { error ->
            logger.error("Ignoring League UI capture with unknown fixture {}", fixtureId, error)
            return
        }
        val completed = AtomicBoolean(false)
        val languageReady = AtomicBoolean(requestedLocale == null)
        val languageFailure = AtomicReference<Throwable?>()
        var guiScaleApplied = guiScale == null
        var languageReloadRequested = false
        var opened = false
        var inputVerified = false
        var requested = false
        var closeVerified = false
        var ticks = 0

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (!guiScaleApplied) {
                client.options.guiScale().set(checkNotNull(guiScale))
                client.resizeDisplay()
                guiScaleApplied = true
                logger.info("Applied League UI capture GUI scale={}", guiScale)
                return@EndTick
            }
            languageFailure.get()?.let { throw IllegalStateException("League UI language reload failed", it) }
            if (!languageReady.get()) {
                if (!languageReloadRequested) {
                    if (client.screen == null || client.overlay != null) return@EndTick
                    val locale = checkNotNull(requestedLocale)
                    client.options.languageCode = locale.id
                    client.languageManager.setSelected(locale.id)
                    languageReloadRequested = true
                    client.reloadResourcePacks().whenComplete { _, error ->
                        if (error == null) languageReady.set(true) else languageFailure.set(error)
                    }
                    logger.info("Reloading resources for League UI capture locale={}", locale.id)
                }
                return@EndTick
            }

            if (!opened) {
                if (client.screen == null || client.overlay != null) return@EndTick
                requestedLocale?.let { locale ->
                    check(client.languageManager.selected == locale.id) {
                        "Expected League UI locale ${locale.id}, got ${client.languageManager.selected}"
                    }
                }
                client.setScreen(
                    when (backend) {
                        LeagueUiBackend.CODE -> LeagueChallengeDevelopmentScreen(fixture)
                        LeagueUiBackend.OWO -> LeagueChallengeOwoSpikeScreen(fixture)
                    }
                )
                opened = true
                logger.info(
                    "Opened League UI capture backend={} fixture={} locale={}",
                    backend.id,
                    fixture.id,
                    client.languageManager.selected
                )
                return@EndTick
            }

            ticks += 1
            if (!inputVerified && ticks >= 5) {
                verifyInputAndNarration(checkNotNull(client.screen))
                inputVerified = true
            }
            if (!requested && ticks >= 20) {
                requested = true
                val scaledWidth = client.window.guiScaledWidth
                val scaledHeight = client.window.guiScaledHeight
                val locale = client.languageManager.selected
                val filename = "league-${backend.id}-${fixture.id}-${locale}-${scaledWidth}x${scaledHeight}.png"
                Screenshot.grab(client.gameDirectory, filename, client.mainRenderTarget) { result ->
                    logger.info("League UI capture {}: {}", filename, result.string)
                    completed.set(true)
                }
            }

            if (requested && completed.get() && !closeVerified) {
                val capturedScreen = checkNotNull(client.screen)
                capturedScreen.onClose()
                check(client.screen !== capturedScreen) { "League UI screen did not close through onClose" }
                closeVerified = true
                logger.info("Verified League UI ESC close path")
            }

            if (closeVerified) {
                client.stop()
            } else if (ticks >= 200) {
                error("League UI capture timed out before completion")
            }
        })
    }

    private fun verifyInputAndNarration(screen: Screen) {
        check(screen.narrationMessage.string.isNotBlank()) { "League UI screen narration is blank" }
        screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0)
        val focused = checkNotNull(screen.focused) { "League UI did not focus a component after TAB" }
        val narratable = focused as? NarratableEntry
            ?: error("Focused League UI component is not narratable: ${focused.javaClass.name}")
        val narration = RecordingNarrationOutput()
        narratable.updateNarration(narration)
        check(narration.entries.isNotEmpty()) { "Focused League UI component produced no narration" }
        check(screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0)) { "League UI did not handle ENTER activation" }
        val probe = screen as? LeagueUiVerificationProbe
            ?: error("League UI screen does not expose the development verification probe")
        check(probe.actionDispatched) { "League UI focused action was not dispatched by ENTER" }
        logger.info(
            "Verified League UI input backend={} focused={} narration={}",
            screen.javaClass.simpleName,
            focused.javaClass.simpleName,
            narration.entries.joinToString(" | ")
        )
    }

    private class RecordingNarrationOutput : NarrationElementOutput {
        val entries = mutableListOf<String>()

        override fun add(type: net.minecraft.client.gui.narration.NarratedElementType, narration: NarrationThunk<*>) {
            narration.getText(entries::add)
        }

        override fun nest(): NarrationElementOutput = this
    }
}
