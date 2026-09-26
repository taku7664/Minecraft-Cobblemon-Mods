package jbro.cobblemon.morebattlecontent.api.access

import jbro.cobblemon.morebattlecontent.api.presentation.TrainerResourceSkin
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
}
