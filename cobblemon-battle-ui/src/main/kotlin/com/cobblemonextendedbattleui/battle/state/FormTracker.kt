package jbro.cobblemon.battleui.extended.battle.state

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.FormData
import jbro.cobblemon.battleui.extended.BattleStateTracker.FormState
import jbro.cobblemon.battleui.extended.CobblemonExtendedBattleUI
import net.minecraft.util.Identifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks form changes (Mega Evolution, Castform, Darmanitan, etc.)
 * and species ID registration for form lookups.
 */
object FormTracker {

    private val pokemonForms = ConcurrentHashMap<UUID, FormState>()
    private val pokemonSpeciesIds = ConcurrentHashMap<UUID, Identifier>()

    fun clear() {
        pokemonForms.clear()
        pokemonSpeciesIds.clear()
    }

    // ── Species ID Registration ──────────────────────────────────────────────

    fun registerSpeciesId(uuid: UUID, speciesId: Identifier) {
        if (!pokemonSpeciesIds.containsKey(uuid)) {
            pokemonSpeciesIds[uuid] = speciesId
            CobblemonExtendedBattleUI.LOGGER.debug("FormTracker: Registered species ID $speciesId for $uuid")
        }
    }

    fun getSpeciesId(uuid: UUID): Identifier? = pokemonSpeciesIds[uuid]

    fun getSpeciesIdByName(pokemonName: String, preferAlly: Boolean? = null): Identifier? {
        val uuid = PokemonRegistry.resolvePokemonUuid(pokemonName, preferAlly) ?: return null
        return pokemonSpeciesIds[uuid]
    }

    // ── Form CRUD ────────────────────────────────────────────────────────────

    fun setCurrentForm(pokemonName: String, formName: String, isMega: Boolean = false, isTemporary: Boolean = false, preferAlly: Boolean? = null) {
        val uuid = PokemonRegistry.resolvePokemonUuid(pokemonName, preferAlly) ?: run {
            CobblemonExtendedBattleUI.LOGGER.debug("FormTracker: Unknown Pokemon '$pokemonName' for form change")
            return
        }

        val existing = pokemonForms[uuid]
        pokemonForms[uuid] = FormState(
            currentForm = formName,
            originalForm = existing?.originalForm,
            isMega = isMega,
            isTemporary = isTemporary
        )
        CobblemonExtendedBattleUI.LOGGER.debug(
            "FormTracker: $pokemonName form changed to '$formName' (mega=$isMega, temporary=$isTemporary)"
        )
    }

    fun clearCurrentForm(pokemonName: String, preferAlly: Boolean? = null) {
        val uuid = PokemonRegistry.resolvePokemonUuid(pokemonName, preferAlly) ?: return
        if (clearCurrentForm(uuid)) {
            CobblemonExtendedBattleUI.LOGGER.debug("FormTracker: $pokemonName reverted to base form")
        }
    }

    fun clearCurrentForm(uuid: UUID): Boolean = pokemonForms.remove(uuid) != null

    /** Clears switch-reset forms while preserving permanent forms such as Mega Evolution. */
    fun handleSwitchOut(uuid: UUID) {
        val state = pokemonForms[uuid]
        if (state == null) {
            TypeTracker.restoreOriginalTypes(uuid)
            return
        }
        if (state.isTemporary) {
            pokemonForms.remove(uuid)
            TypeTracker.restoreOriginalTypes(uuid)
            return
        }

        val species = pokemonSpeciesIds[uuid]?.let { PokemonSpecies.getByIdentifier(it) }
        if (species == null) {
            TypeTracker.restoreOriginalTypes(uuid)
            return
        }

        val standardForm = species.standardForm
        val persistentForm = formNameToAspects(state.currentForm)
            .asSequence()
            .map { aspect -> species.getForm(setOf(aspect)) }
            .firstOrNull { it != standardForm }
            ?: standardForm
        TypeTracker.initializeDynamicTypes(
            uuid,
            persistentForm.primaryType.name,
            persistentForm.secondaryType?.name
        )
    }

    fun getCurrentForm(uuid: UUID): FormState? = pokemonForms[uuid]

    fun getCurrentFormByName(pokemonName: String, preferAlly: Boolean? = null): FormState? {
        val uuid = PokemonRegistry.resolvePokemonUuid(pokemonName, preferAlly) ?: return null
        return pokemonForms[uuid]
    }

    // ── Aspect Conversion ────────────────────────────────────────────────────

    fun formNameToAspects(formName: String): List<String> {
        val baseName = formName
            .lowercase()
            .replace(" ", "")
            .replace("form", "")
            .replace("mode", "")
            .trim()

        return listOf(
            "$baseName-mode",
            "$baseName-form",
            baseName,
            formName.lowercase().replace(" ", "-"),
            formName.lowercase().replace(" ", "")
        )
    }

    fun formNameToAspect(formName: String): String {
        return formNameToAspects(formName).first()
    }

    // ── Form Change Type Updates ─────────────────────────────────────────────

    fun updateTypesForFormChange(
        pokemonName: String,
        speciesId: Identifier?,
        formName: String,
        preferAlly: Boolean? = null
    ): FormData? {
        if (speciesId == null) return null

        val species = PokemonSpecies.getByIdentifier(speciesId) ?: return null
        val standardForm = species.standardForm

        val aspectsToTry = formNameToAspects(formName)
        var form: FormData = standardForm
        var foundForm = false

        for (aspect in aspectsToTry) {
            val candidateForm = species.getForm(setOf(aspect))
            if (candidateForm != standardForm || aspect == standardForm.aspects.firstOrNull()) {
                form = candidateForm
                foundForm = true
                CobblemonExtendedBattleUI.LOGGER.debug("FormTracker: Found form using aspect '$aspect'")
                break
            }
        }

        if (!foundForm) {
            CobblemonExtendedBattleUI.LOGGER.debug("FormTracker: Could not find form for '$formName', tried aspects: $aspectsToTry")
        }

        val primaryType = form.primaryType?.name
        val secondaryType = form.secondaryType?.name

        TypeTracker.setTypeReplacement(pokemonName, primaryType, secondaryType, preferAlly)

        CobblemonExtendedBattleUI.LOGGER.debug("FormTracker: Form change type update for $pokemonName to $formName - types: $primaryType/$secondaryType")

        return form
    }
}
