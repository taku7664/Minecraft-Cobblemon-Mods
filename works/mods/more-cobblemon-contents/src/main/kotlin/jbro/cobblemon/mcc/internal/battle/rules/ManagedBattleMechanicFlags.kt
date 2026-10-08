package jbro.cobblemon.mcc.internal.battle.rules

import jbro.cobblemon.mcc.api.rules.BattleMechanicFlags

internal val ManagedSubmittedMechanic.flag: Int get() = when (this) {
    ManagedSubmittedMechanic.MEGA -> BattleMechanicFlags.MEGA
    ManagedSubmittedMechanic.DYNAMAX -> BattleMechanicFlags.DYNAMAX
    ManagedSubmittedMechanic.TERA -> BattleMechanicFlags.TERA
    ManagedSubmittedMechanic.Z_MOVE -> BattleMechanicFlags.Z_MOVE
    ManagedSubmittedMechanic.UNSUPPORTED -> BattleMechanicFlags.NONE
}

internal fun submittedMechanics(flags: Int): Set<ManagedSubmittedMechanic> {
    BattleMechanicFlags.requireValid(flags)
    return ManagedSubmittedMechanic.entries.filterTo(LinkedHashSet()) { BattleMechanicFlags.contains(flags, it.flag) }
}
