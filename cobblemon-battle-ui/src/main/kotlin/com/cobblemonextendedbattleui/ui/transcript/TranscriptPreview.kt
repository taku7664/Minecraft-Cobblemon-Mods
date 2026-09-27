package jbro.cobblemon.battleui.extended.ui.transcript

import jbro.cobblemon.battleui.extended.BattleLog
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.util.UUID

/** Development-only caller lives in BattleThemeCapture; never touches real battle history. */
internal object TranscriptPreview {
    fun render(context: DrawContext, mode: String) {
        val self = TranscriptSpeaker(UUID(0, 1001), true, "Player", Text.translatable("cobblemon.species.charizard.name"), Identifier.of("cobblemon", "charizard"), emptySet())
        val enemy = TranscriptSpeaker(UUID(0, 1002), false, "Opponent", Text.translatable("cobblemon.species.pikachu.name"), Identifier.of("cobblemon", "pikachu"), emptySet(), true)
        val entries = mutableListOf<BattleLog.LogEntry>()
        fun add(turn: Int, key: String, speaker: TranscriptSpeaker? = null, vararg args: Any) {
            val fullKey = if (key.startsWith("cobblemon.")) key else "cobblemon.battle.$key"
            entries.add(BattleLog.LogEntry(turn, BattleLog.EntryType.OTHER, Text.translatable(fullKey, *args), fullKey, speaker = speaker))
        }
        if (mode != "log-empty") {
            for (turn in if (mode == "log-long") 1..18 else 6..6) {
                add(turn, "turn", null, turn)
                add(turn, "used_move", enemy, enemy.pokemonName, Text.translatable("cobblemon.move.thunderbolt"))
                add(turn, "superEffective", null, self.pokemonName)
                add(turn, "crit", null, self.pokemonName)
                add(turn, "used_move", self, self.pokemonName, Text.translatable("cobblemon.move.flamethrower"))
                add(turn, "cobblemon.status.burn.apply", null, enemy.pokemonName)
                add(turn, "cobblemon.status.burn.hurt", null, enemy.pokemonName)
            }
            add(if (mode == "log-long") 19 else 7, "turn", null, if (mode == "log-long") 19 else 7)
            add(if (mode == "log-long") 19 else 7, "used_move", enemy, enemy.pokemonName, Text.translatable("cobblemon.move.quickattack"))
        }
        BattleTranscriptOverlay.renderEntries(context, entries, "preview-$mode")
    }
}
