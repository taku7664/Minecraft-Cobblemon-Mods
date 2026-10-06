package jbro.cobblemon.mcc.api.access

import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrainerResourceSkinTest {
    @Test fun `local default and slim skins are accepted`() {
        assertFalse(TrainerResourceSkin("league:textures/trainers/roark.png").slim)
        assertTrue(TrainerResourceSkin("league:textures/trainers/cynthia.png", true).slim)
    }
    @Test fun `remote absolute and traversal paths cannot become skins`() {
        listOf("https://example.test/skin.png", "league:/skin.png", "league:../skin.png", "C:/skin.png", "league:skin.jpg")
            .forEach { assertThrows(IllegalArgumentException::class.java) { TrainerResourceSkin(it) } }
    }
    @Test fun `the trainer NPC aspect follows the variation generator's rule`() {
        // Must match works/tools/generate_managed_trainer_variations.py, which writes the skins under these aspects.
        assertEquals("mcc_skin_rctmod_textures_trainers_single_champion_cynthia_03a5_png",
            TrainerResourceSkin("rctmod:textures/trainers/single/champion_cynthia_03a5.png").aspect)
        assertEquals("mcc_skin_league_textures_trainers_cynthia_png_slim",
            TrainerResourceSkin("league:textures/trainers/cynthia.png", true).aspect)
    }
}
