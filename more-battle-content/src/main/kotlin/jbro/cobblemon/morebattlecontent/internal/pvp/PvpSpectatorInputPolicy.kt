package jbro.cobblemon.morebattlecontent.internal.pvp

internal object PvpSpectatorInputPolicy {
    private val blockedNames = setOf(
        "key.forward",
        "key.left",
        "key.back",
        "key.right",
        "key.jump",
        "key.sneak",
        "key.sprint",
        "key.attack",
        "key.use",
        "key.pickItem",
        "key.drop",
        "key.swapOffhand",
        "key.inventory",
        "key.cobblemon.throwpartypokemon",
        "key.cobblemon.send_out_pokemon",
    )

    fun blocks(keyName: String): Boolean = keyName in blockedNames || keyName.startsWith("key.hotbar.")
}
