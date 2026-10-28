package jbro.cobblemon.mcc.pwt.client

import java.util.UUID
import jbro.cobblemon.mcc.client.hub.MccHubContentHost
import jbro.cobblemon.mcc.client.hub.MccHubKit
import jbro.cobblemon.mcc.client.hub.MccHubPortraitCards
import jbro.cobblemon.mcc.client.hub.MccHubTabContent
import jbro.cobblemon.mcc.client.hub.MccHubTabs
import jbro.cobblemon.mcc.client.hub.MccPokemonPortraits
import jbro.cobblemon.mcc.pwt.MoreCobblemonContentsPwt as Mod
import jbro.cobblemon.mcc.pwt.network.PwtAction
import jbro.cobblemon.mcc.pwt.network.PwtRunView
import jbro.cobblemon.mcc.pwt.network.PwtView
import jbro.cobblemon.mcc.pwt.system.PwtRules
import jbro.cobblemon.uikit.UiButtonVariant
import jbro.cobblemon.uikit.UiLayout
import jbro.cobblemon.uikit.UiRect
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component

/** PWT in the MCC hub: pick three, then the bracket until the title or the first loss. */
internal class PwtHubTab : MccHubTabContent {
    override fun shown() = MccHubTabs.requestContent(Mod.CONTENT)

    override fun build(host: MccHubContentHost, bounds: UiRect) {
        val view = PwtClientState.view
        if (view == null) {
            MccHubKit.placeholder(host, bounds, pwt("waiting"), onCard = false)
            return
        }
        val run = view.run
        val layout = MccHubKit.tabFrame(
            if (run == null) UiLayout.row(gap = MccHubKit.GAP) {
                weight("party", 55, min = 1)
                weight("rules", 45, min = 1)
            } else UiLayout.leaf("bracket"),
        ).solve(bounds)
        MccHubKit.strip(host, layout["strip"], headline(view), pwt("titles", view.titles))
        if (run == null) {
            addParty(host, layout["party"], view)
            addRules(host, layout["rules"], view)
        } else {
            val body = MccHubKit.card(host, layout["bracket"], pwt("bracket"), MccHubKit.CardTone.FEATURE)
            host.add(PwtBracketWidget(body, run))
        }
        addFooter(host, layout["footer"], view)
    }

    private fun headline(view: PwtView): Component {
        view.errorKey?.let { return Component.translatable(it) }
        val run = view.run ?: return pwt("intro", view.bp)
        return when {
            run.won -> pwt("result.won", view.bp)
            // The loss settled its round, so the round lost is the one before the current.
            run.finished -> pwt("result.lost", pwt("round.${(run.round - 1).coerceIn(0, 2)}"))
            run.battling -> pwt("battling", pwt("round.${run.round}"))
            else -> pwt("next", pwt("round.${run.round}"), opponentName(run))
        }
    }

    private fun opponentName(run: PwtRunView): Component {
        val player = run.entrants.indexOfFirst { it.player }
        val alive = if (run.round == 0) (0 until 8).toList() else run.winners[run.round - 1]
        val index = alive.indexOf(player)
        return run.entrants.getOrNull(alive.getOrNull(index xor 1) ?: -1)?.let { Component.translatable(it.nameKey) } ?: Component.literal("?")
    }

    private fun addParty(host: MccHubContentHost, rect: UiRect, view: PwtView) {
        val body = MccHubKit.card(host, rect, pwt("party", PwtClientState.picked.size, PwtRules.TEAM_SIZE), MccHubKit.CardTone.FEATURE)
        if (view.party.isEmpty()) {
            MccHubKit.placeholder(host, body, pwt("party.empty"))
            return
        }
        MccHubPortraitCards.grid(body, 6).forEachIndexed { index, cell ->
            val pokemon = view.party.getOrNull(index) ?: return@forEachIndexed
            val id = UUID.fromString(pokemon.id)
            val order = PwtClientState.picked.indexOf(id).takeIf { it >= 0 }?.plus(1)
            val species = speciesName(pokemon.speciesId)
            val battleLevel = pokemon.level.coerceAtMost(PwtRules.BATTLE_LEVEL)
            val button = MccHubPortraitCards.Button(cell, MccPokemonPortraits.party(id, pokemon.speciesId, pokemon.formId),
                if (order == null) species else pwt("party.order", order, species),
                if (pokemon.legendaryClass) pwt("party.blocked") else pwt("party.level", battleLevel), order != null,
                species) {
                if (PwtClientState.toggle(id)) host.rebuild()
            }
            button.blocked = pokemon.legendaryClass
            button.active = view.unlocked && !PwtClientState.pending && (!pokemon.legendaryClass || order != null)
            val tip = pwt("party.tooltip", species, pokemon.level, battleLevel, itemName(pokemon.heldItemId))
            button.setTooltip(Tooltip.create(if (pokemon.legendaryClass) tip.copy().append("\n").append(pwt("party.blocked.tooltip")) else tip))
            host.add(button)
        }
    }

    private fun addRules(host: MccHubContentHost, rect: UiRect, view: PwtView) {
        val body = MccHubKit.card(host, rect, pwt("rules"))
        val text = Component.empty()
        val keys = if (view.unlocked) RULES else listOf("locked") + RULES
        keys.forEachIndexed { index, key ->
            if (index > 0) text.append("\n")
            text.append(pwt("rule.$key", view.bp))
        }
        MccHubKit.text(host, body, text) { theme -> MccHubKit.panelText(theme) }
    }

    private fun addFooter(host: MccHubContentHost, rect: UiRect, view: PwtView) {
        val pending = PwtClientState.pending
        val run = view.run
        fun submit(action: PwtAction) { if (PwtClientState.send(action)) host.rebuild() }
        val end = when {
            run == null -> listOf(MccHubKit.Action(pwt("enter"), UiButtonVariant.PRIMARY,
                view.unlocked && !pending && PwtClientState.picked.size == PwtRules.TEAM_SIZE, minWidth = 96) { submit(PwtAction.ENTER) })
            run.finished -> listOf(MccHubKit.Action(pwt("done"), UiButtonVariant.PRIMARY, !pending, minWidth = 96) { submit(PwtAction.LEAVE) })
            run.battling -> listOf(MccHubKit.Action(pwt("in_battle"), UiButtonVariant.PRIMARY, enabled = false, minWidth = 96) {})
            else -> listOf(
                MccHubKit.Action(pwt("forfeit"), UiButtonVariant.DANGER, !pending) {
                    MccHubKit.confirm(pwt("forfeit.title"), pwt("forfeit.message"), pwt("forfeit"), Component.translatable("gui.back")) {
                        submit(PwtAction.LEAVE)
                    }
                },
                MccHubKit.Action(pwt("fight"), UiButtonVariant.PRIMARY, !pending, minWidth = 96) { submit(PwtAction.FIGHT) },
            )
        }
        MccHubKit.footer(host, rect, emptyList(), end)
    }
}

private val RULES = listOf("team", "level", "clauses", "legendary", "gimmicks", "heal", "reward")

private fun speciesName(speciesId: String): Component =
    Component.translatable("cobblemon.species.${speciesId.substringAfter(':')}.name")

private fun itemName(itemId: String?): Component {
    if (itemId == null) return pwt("party.no_item")
    return Component.translatable("item.${itemId.substringBefore(':', "minecraft")}.${itemId.substringAfter(':')}")
}
