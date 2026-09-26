package jbro.cobblemon.morebattlecontent.api.presentation

/** Cosmetic local resource reference only; never a URL or filesystem path. */
data class TrainerResourceSkin(val texture: String, val slim: Boolean = false) {
    init {
        require(ManagedBattleContentIds.isValid(texture) && texture.endsWith(".png") && !texture.contains(".."))
        require(texture.length <= 256)
        require(!texture.contains("://") && !texture.substringAfter(':').startsWith('/'))
    }
}
