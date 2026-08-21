package jbro.cobblemon.morebattlecontent.client

internal object PvpBattleCamCycleRecovery {
    fun shouldRecover(
        cycleKeyDown: Boolean,
        previousCycleKeyDown: Boolean,
        previousMode: String?,
        currentMode: String?,
    ): Boolean = cycleKeyDown && !previousCycleKeyDown && previousMode == "OFF" && currentMode == "OFF"
}

internal object OptionalBattleCamAccess {
    fun modeName(): String? = runCatching {
        val state = Class.forName("com.batmite2b.battlecam.client.BattleCamClient")
            .getField("STATE")
            .get(null)
        state.javaClass.getField("mode").get(state).toString()
    }.getOrNull()

    fun cycleMode(): Boolean = runCatching {
        val state = Class.forName("com.batmite2b.battlecam.client.BattleCamClient")
            .getField("STATE")
            .get(null)
        state.javaClass.getMethod("cycleMode").invoke(state)
        true
    }.getOrDefault(false)
}
