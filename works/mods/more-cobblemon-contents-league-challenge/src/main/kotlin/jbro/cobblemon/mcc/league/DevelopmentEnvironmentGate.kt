package jbro.cobblemon.mcc.league

internal object DevelopmentEnvironmentGate {
    fun shouldRegister(isDevelopmentEnvironment: Boolean): Boolean = isDevelopmentEnvironment
}
