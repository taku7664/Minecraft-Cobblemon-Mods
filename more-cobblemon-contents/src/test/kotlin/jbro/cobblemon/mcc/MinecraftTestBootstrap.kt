package jbro.cobblemon.mcc

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

/**
 * Bootstraps Minecraft once before any test runs. A test that touches a vanilla registry before the bootstrap
 * would otherwise poison that registry's class for every later test in the JVM, so the outcome would depend on
 * the order the classes happen to run in.
 */
class MinecraftTestBootstrap : LauncherSessionListener {
    override fun launcherSessionOpened(session: LauncherSession) {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }
}
