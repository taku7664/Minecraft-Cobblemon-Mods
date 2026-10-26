package jbro.cobblemon.policy.plaza

import java.util.function.Supplier
import jbro.cobblemon.policy.JbroPolicy

/** Simple MyRoom's optional entrypoint uses only a JDK interface; neither mod needs the other's classes. */
class RoomReturnHubProvider : Supplier<Collection<String>> {
    override fun get(): Collection<String> =
        HubReturnRule.otherHubs(JbroPolicy.config.plazaOtherHubDimensions) + HubReturnRule.PLAZA
}
