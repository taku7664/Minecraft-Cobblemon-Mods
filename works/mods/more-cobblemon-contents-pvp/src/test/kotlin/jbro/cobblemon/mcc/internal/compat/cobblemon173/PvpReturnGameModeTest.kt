package jbro.cobblemon.mcc.internal.compat.cobblemon173

import net.minecraft.world.level.GameType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PvpReturnGameModeTest {
    @Test
    fun `every game mode survives the serialized name a return point records`() {
        GameType.entries.forEach { mode -> assertEquals(mode, pvpReturnGameMode(mode.getName())) }
    }

    @Test
    fun `points written by older builds with the enum constant name still restore their mode`() {
        assertEquals(GameType.CREATIVE, pvpReturnGameMode("CREATIVE"))
        assertEquals(GameType.ADVENTURE, pvpReturnGameMode("ADVENTURE"))
        assertEquals(GameType.SPECTATOR, pvpReturnGameMode("Spectator"))
    }

    @Test
    fun `only an unknown name falls back to survival`() {
        assertEquals(GameType.SURVIVAL, pvpReturnGameMode("hardcore"))
        assertEquals(GameType.SURVIVAL, pvpReturnGameMode(""))
    }
}
