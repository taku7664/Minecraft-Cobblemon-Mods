package jbro.cobblemon.dimensions.wormhole

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import jbro.cobblemon.dimensions.CobblemonDimensions

/**
 * How often Ultra Wormholes open, from `config/cobblemon-dimensions.json`. Every [checkSeconds] each player gets a
 * roll; the averages below are in minutes, so a personal hole opens about once per [personalEveryMinutes] for a
 * player standing in the overworld.
 */
data class WormholeSettings(
    val checkSeconds: Int = 60,
    /** A small hole near one player in the overworld. */
    val personalEveryMinutes: Double = 30.0,
    val personalSeconds: Int = 60,
    val personalRadius: Float = 2.5f,
    /** A great hole near a random overworld player, announced to the whole server without coordinates. */
    val greatEveryMinutes: Double = 120.0,
    val greatSeconds: Int = 300,
    val greatRadius: Float = 6f,
    /** A way home near a player in Ultra Space. */
    val returnEveryMinutes: Double = 8.0,
    val returnSeconds: Int = 60,
    val returnRadius: Float = 2.5f,
) {
    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        /** Reads the file, writing the defaults first when it is missing; a broken file falls back to the defaults. */
        fun load(path: Path): WormholeSettings {
            try {
                if (Files.notExists(path)) {
                    Files.createDirectories(path.parent)
                    Files.writeString(path, gson.toJson(WormholeSettings()))
                }
                return gson.fromJson(Files.readString(path), WormholeSettings::class.java) ?: WormholeSettings()
            } catch (failure: Exception) {
                CobblemonDimensions.LOGGER.warn("Could not read {}; using the default wormhole settings", path, failure)
                return WormholeSettings()
            }
        }
    }
}
