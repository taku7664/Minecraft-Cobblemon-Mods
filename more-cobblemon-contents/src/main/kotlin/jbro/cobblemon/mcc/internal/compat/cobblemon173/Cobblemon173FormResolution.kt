package jbro.cobblemon.mcc.internal.compat.cobblemon173

import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Species

/** Resolves form identifiers from editable MCC catalogs without case-sensitive fallback to the base form. */
internal fun Species.findMccForm(formId: String): FormData? = forms.firstOrNull { form ->
    form.name.equals(formId, ignoreCase = true)
} ?: standardForm.takeIf { form -> form.name.equals(formId, ignoreCase = true) }
