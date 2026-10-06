package jbro.cobblemon.mcc.api.presentation

/** Cosmetic local resource reference only; never a URL or filesystem path. */
data class TrainerResourceSkin(val texture: String, val slim: Boolean = false) {
    init {
        require(ManagedBattleContentIds.isValid(texture) && texture.endsWith(".png") && !texture.contains(".."))
        require(texture.length <= 256)
        require(!texture.contains("://") && !texture.substringAfter(':').startsWith('/'))
    }

    /**
     * The aspect that dresses MCC's battle trainer NPC (`more_cobblemon_contents:managed_trainer`) in this skin. Each
     * module's `managed_trainer` variation file maps it to the texture; `works/tools/generate_managed_trainer_variations.py`
     * writes those files from the content data with this same rule.
     */
    val aspect: String
        get() = "mcc_skin_" + texture.lowercase().replace(NOT_ASPECT, "_").trim('_') + if (slim) "_slim" else ""

    private companion object {
        val NOT_ASPECT = Regex("[^a-z0-9]+")
    }
}
