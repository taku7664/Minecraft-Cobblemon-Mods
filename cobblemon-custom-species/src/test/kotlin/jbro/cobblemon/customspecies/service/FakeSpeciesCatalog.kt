package jbro.cobblemon.customspecies.service

import jbro.cobblemon.customspecies.config.FormSelector

class FakeSpeciesCatalog private constructor(private val targets: MutableMap<SpeciesTargetKey, SpeciesTargetState>) : SpeciesCatalog {
    override fun resolve(species: String, selector: FormSelector): List<SpeciesTargetKey> = when (selector) {
        FormSelector.Base -> listOfNotNull(targets.keys.firstOrNull { it.species == species && it.form == "base" })
        FormSelector.All -> targets.keys.filter { it.species == species }
        is FormSelector.Named -> listOfNotNull(targets.keys.firstOrNull { it.species == species && it.form == selector.id })
    }

    override fun read(key: SpeciesTargetKey): SpeciesTargetState = targets.getValue(key)

    override fun write(key: SpeciesTargetKey, state: SpeciesTargetState) {
        targets[key] = state
    }

    fun requireTarget(species: String, form: String): SpeciesTargetState = targets.getValue(SpeciesTargetKey(species, form))

    companion object {
        fun rotomWithInheritedWashForm(): FakeSpeciesCatalog {
            val stats = mutableMapOf("hp" to 50, "attack" to 50, "defence" to 77, "special_attack" to 95, "special_defence" to 77, "speed" to 91)
            val moves = mutableSetOf("1:thundershock")
            val abilities = mutableSetOf("n:levitate")
            return FakeSpeciesCatalog(mutableMapOf(
                SpeciesTargetKey("cobblemon:rotom", "base") to SpeciesTargetState(stats, abilities, moves),
                SpeciesTargetKey("cobblemon:rotom", "wash") to SpeciesTargetState(stats, abilities, moves)
            ))
        }

        fun charizard(): FakeSpeciesCatalog = FakeSpeciesCatalog(mutableMapOf(
            SpeciesTargetKey("cobblemon:charizard", "base") to SpeciesTargetState(
                mutableMapOf("hp" to 78, "attack" to 84, "defence" to 78, "special_attack" to 109, "special_defence" to 85, "speed" to 100),
                mutableSetOf("n:blaze", "h:solarpower"),
                mutableSetOf("1:growl")
            )
        ))
    }
}
