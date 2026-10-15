package jbro.cobblemon.mcc.client

import com.cobblemon.mod.common.client.CobblemonClient
import jbro.cobblemon.mcc.api.client.MccPendingBattle
import jbro.cobblemon.mcc.api.client.MccPendingBattles
import jbro.cobblemon.mcc.internal.battle.BattleEntryCancelPayload
import jbro.cobblemon.mcc.internal.battle.BattleEntryHoldPayload
import jbro.cobblemon.mcc.internal.battle.BattleEntryReadyPayload
import jbro.cobblemon.mcc.internal.battle.BattleEntryStyle
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import jbro.cobblemon.mcc.internal.battle.BattleVersusPayload
import jbro.cobblemon.ui.extended.UIUtils
import jbro.cobblemon.ui.extended.transition.BattleEntryKind
import jbro.cobblemon.ui.extended.transition.BattleEntryTransition
import jbro.cobblemon.ui.extended.transition.BattleVersusIntro
import jbro.cobblemon.ui.extended.transition.VersusSide
import jbro.cobblemon.ui.extended.transition.VersusTier
import jbro.cobblemon.uikit.UiIcon
import jbro.cobblemon.uikit.UiModelFraming
import jbro.cobblemon.uikit.client.CobblemonUiRenderContent
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

/**
 * Plays Cobblemon UI's battle entry transition for a battle the server holds, and tells the server to start it once
 * the screen is covered, at once when this client shows no transition.
 */
object BattleEntryClientNetworking {
    /** A pending battle that never opened is forgotten after this long, so music does not wait on it forever. */
    private const val PENDING_LIMIT_MILLIS = 10_000L
    private var pendingSince = 0L
    /** The versus intro the server announced for the battle it is about to hold. */
    private var versus: BattleVersusPayload? = null

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(BattleVersusPayload.TYPE) { payload, context ->
            context.client().execute { versus = payload }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleEntryHoldPayload.TYPE) { payload, context ->
            context.client().execute { hold(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(BattleEntryCancelPayload.TYPE) { payload, context ->
            context.client().execute {
                if (MccPendingBattles.current()?.battleId == payload.battleId) MccPendingBattles.set(null)
                if (versus?.battleId == payload.battleId) versus = null
                BattleVersusIntro.cancel()
                BattleEntryTransition.cancel()
            }
        }
        // The pending battle has opened (or this client left it behind): music reads the real battle from here on.
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            val pending = MccPendingBattles.current() ?: return@register
            val battle = CobblemonClient.battle
            val stale = net.minecraft.Util.getMillis() - pendingSince > PENDING_LIMIT_MILLIS
            if (stale || battle != null && battle.battleId == pending.battleId) MccPendingBattles.set(null)
        }
        MccClientSessionReset.onReset("battle entry transition") {
            MccPendingBattles.set(null)
            versus = null
            BattleVersusIntro.cancel()
            BattleEntryTransition.cancel()
        }
    }

    /** Plays the versus intro for [payload]'s battle when the server announced one; true when it is playing. */
    private fun playVersus(payload: BattleEntryHoldPayload, then: Runnable): Boolean {
        val intro = versus?.takeIf { it.battleId == payload.battleId } ?: return false
        versus = null
        val tier = VersusTier.fromId(if (intro.tier == "tournament") "elite_four" else intro.tier) ?: return false
        val client = Minecraft.getInstance()
        val profile = client.player?.gameProfile ?: return false
        val framing = UiModelFraming.FULL_BODY
        val me = VersusSide(Component.literal(profile.name), Component.translatable("cobblemon_ui.versus.challenger"),
            CobblemonUiRenderContent.PlayerProfile(profile, framing))
        val skin = intro.skin?.let { CobblemonUiRenderContent.PlayerSkin(UiIcon(it.namespace, it.path), intro.slim, framing) }
            ?: CobblemonUiRenderContent.Empty
        val title = if (intro.tier == "tournament") "screen.more_cobblemon_contents.versus.tournament" else "cobblemon_ui.versus.${tier.id}"
        val opponent = VersusSide(Component.translatable(intro.nameKey), Component.translatable(title), skin)
        // The opponent's colour is their lead's type, as the trainer transition colours itself.
        val accent = payload.species?.let(PokemonSpecies::getByIdentifier)?.let { UIUtils.getTypeColor(it.primaryType) }
        return BattleVersusIntro.play(tier, me, opponent, accent, intro.hard, then)
    }

    private fun hold(payload: BattleEntryHoldPayload) {
        pendingSince = net.minecraft.Util.getMillis()
        MccPendingBattles.set(MccPendingBattle(payload.battleId, payload.style.id, payload.species?.toString(),
            payload.form, payload.labels))
        val kind = when (payload.style) {
            BattleEntryStyle.WILD -> BattleEntryKind.WILD
            BattleEntryStyle.LEGENDARY -> BattleEntryKind.LEGENDARY
            BattleEntryStyle.TRAINER -> BattleEntryKind.TRAINER
        }
        val ready = Runnable { ready(payload) }
        val transition = Runnable {
            val playing = try {
                BattleEntryTransition.play(kind, payload.species, ready)
            } catch (failure: RuntimeException) {
                jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.warn("Battle entry transition failed to play", failure)
                false
            }
            if (!playing) ready.run()
        }
        // A versus intro replaces the transition: it ends white, and the battle starts behind the white.
        val versusPlaying = try {
            playVersus(payload, ready)
        } catch (failure: RuntimeException) {
            jbro.cobblemon.mcc.MoreCobblemonContents.LOGGER.warn("Versus intro failed to play", failure)
            false
        }
        if (!versusPlaying) transition.run()
    }

    private fun ready(payload: BattleEntryHoldPayload) {
        if (ClientPlayNetworking.canSend(BattleEntryReadyPayload.TYPE)) {
            ClientPlayNetworking.send(BattleEntryReadyPayload(payload.battleId))
        }
    }
}
