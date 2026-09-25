package jbro.cobblemon.uikit.client

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.narration.NarratableEntry
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

internal enum class GalleryHarnessMode {
    OFF,
    MANUAL,
    CAPTURE;

    companion object {
        fun fromEnvironment(environment: Map<String, String>): GalleryHarnessMode = when {
            environment["COBBLEMON_UI_KIT_CAPTURE_WORLD"] == "1" -> CAPTURE
            environment["COBBLEMON_UI_KIT_MANUAL_GALLERY"] == "1" -> MANUAL
            else -> OFF
        }
    }
}

internal object GalleryWorldCaptureHarness {
    private val logger = LoggerFactory.getLogger("cobblemon_ui_kit")

    fun installFromEnvironment() {
        val mode = GalleryHarnessMode.fromEnvironment(System.getenv())
        if (mode == GalleryHarnessMode.OFF) return

        val topCaptured = AtomicBoolean(false)
        val scrolledCaptured = AtomicBoolean(false)
        var scaleApplied = false
        var opened = false
        var verified = false
        var topCaptureRequested = false
        var scrollVerified = false
        var scrolledCaptureRequested = false
        var ticks = 0
        var waitingScreenClass: String? = null

        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (!scaleApplied) {
                client.options.guiScale().set(2)
                client.resizeDisplay()
                scaleApplied = true
                return@EndTick
            }

            if (!opened) {
                if (client.level == null || client.player == null || client.screen != null || client.overlay != null) {
                    val currentScreenClass = client.screen?.javaClass?.name
                    if (currentScreenClass != null && currentScreenClass != waitingScreenClass) {
                        waitingScreenClass = currentScreenClass
                        logger.info(
                            "Waiting for UI Kit world load screen={} title={} overlay={}",
                            currentScreenClass,
                            client.screen?.title?.string,
                            client.overlay?.javaClass?.name
                        )
                    }
                    return@EndTick
                }
                client.setScreen(ComponentGalleryScreen())
                opened = true
                logger.info(
                    "Opened UI Kit gallery in world dimension={} player={} mode={}",
                    client.level!!.dimension().location(),
                    client.player!!.scoreboardName,
                    mode
                )
                if (mode == GalleryHarnessMode.MANUAL) {
                    logger.info("Manual UI Kit gallery is ready; automation and automatic shutdown are disabled")
                }
                return@EndTick
            }

            if (mode == GalleryHarnessMode.MANUAL) return@EndTick

            ticks += 1
            val screen = client.screen as? ComponentGalleryScreen
                ?: error("UI Kit gallery closed before capture")
            check(client.level != null && client.player != null) { "UI Kit gallery is not attached to a loaded world" }

            if (!verified && ticks >= 5) {
                check(screen.narrationMessage.string.isNotBlank()) { "UI Kit gallery narration is blank" }
                screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0)
                val focused = checkNotNull(screen.focused) { "UI Kit gallery did not focus a widget after TAB" }
                check(focused is NarratableEntry) { "Focused gallery widget is not narratable" }
                verified = true
                logger.info(
                    "Verified UI Kit gallery world={} focused={} logical={}x{}",
                    client.level!!.dimension().location(),
                    focused.javaClass.simpleName,
                    client.window.guiScaledWidth,
                    client.window.guiScaledHeight
                )
            }

            if (!topCaptureRequested && ticks >= 15) {
                topCaptureRequested = true
                val filename = "ui-kit-world-top-${client.window.guiScaledWidth}x${client.window.guiScaledHeight}.png"
                Screenshot.grab(client.gameDirectory, filename, client.mainRenderTarget) { result ->
                    logger.info("UI Kit world capture {}: {}", filename, result.string)
                    topCaptured.set(true)
                }
            }

            if (topCaptured.get() && !scrollVerified) {
                val consumed = (1..6).map {
                    screen.mouseScrolled(
                        (client.window.guiScaledWidth / 2).toDouble(),
                        (client.window.guiScaledHeight / 2).toDouble(),
                        0.0,
                        -1.0
                    )
                }.any { it }
                check(consumed) { "UI Kit gallery did not consume a viewport scroll" }
                scrollVerified = true
                logger.info("Verified UI Kit gallery scroll path")
            }

            if (scrollVerified && !scrolledCaptureRequested && ticks >= 25) {
                scrolledCaptureRequested = true
                val filename = "ui-kit-world-scrolled-${client.window.guiScaledWidth}x${client.window.guiScaledHeight}.png"
                Screenshot.grab(client.gameDirectory, filename, client.mainRenderTarget) { result ->
                    logger.info("UI Kit world capture {}: {}", filename, result.string)
                    scrolledCaptured.set(true)
                }
            }

            if (topCaptured.get() && scrolledCaptured.get()) {
                screen.onClose()
                check(client.screen !== screen) { "UI Kit gallery did not close through onClose" }
                logger.info("Verified UI Kit gallery close path with world still loaded={}", client.level != null)
                client.stop()
            } else if (ticks >= 240) {
                error("UI Kit world capture timed out")
            }
        })
    }
}
