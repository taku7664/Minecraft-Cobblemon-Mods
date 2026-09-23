package jbro.cobblemon.morebattlecontent.internal.pvp

import java.util.Collections

internal fun Collection<PvpBattleMechanic>.immutableMechanicSet(): Set<PvpBattleMechanic> =
    Collections.unmodifiableSet(LinkedHashSet(this))
