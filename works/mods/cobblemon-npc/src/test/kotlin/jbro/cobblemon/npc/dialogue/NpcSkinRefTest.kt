package jbro.cobblemon.npc.dialogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class NpcSkinRefTest {
    @Test
    fun `skin discovery includes MCC NPCs and excludes unrelated textures`() {
        assertTrue(NpcSkinRef.isSkinFolder("textures/npcs/wild/nurse_joy.png"))
        assertTrue(NpcSkinRef.isSkinFolder("textures/trainers/single/clerk.png"))
        assertTrue(NpcSkinRef.isSkinFolder("textures/entity/player/wide/steve.png"))
        assertFalse(NpcSkinRef.isSkinFolder("textures/item/nurse_joy.png"))
        assertFalse(NpcSkinRef.isSkinFolder("textures/pokemon/pikachu.png"))
        assertEquals(NpcSkinRef.Texture("more_cobblemon_contents_league_challenge", "textures/npcs/wild/nurse_joy.png"),
            NpcSkinRef.parse("more_cobblemon_contents_league_challenge:textures/npcs/wild/nurse_joy.png"))
    }

    @Test
    fun `players, RCT trainers and pack textures are told apart`() {
        assertEquals(NpcSkinRef.Player("Steve"), NpcSkinRef.parse(" Steve "))
        assertEquals(NpcSkinRef.Texture("rctmod", "textures/trainers/single/clerk.png"), NpcSkinRef.parse("rct:clerk"))
        assertEquals(NpcSkinRef.Texture("rctmod", "textures/trainers/single/clerk.png"), NpcSkinRef.parse("rct:clerk.png"))
        assertEquals(NpcSkinRef.Texture("league", "textures/trainers/roark.png"), NpcSkinRef.parse("league:textures/trainers/roark.png"))
        assertEquals("rct:hiker_alan_00b9", NpcSkinRef.rct("hiker_alan_00b9"))
    }

    @Test
    fun `anything else falls back to the default skin`() {
        listOf("", "   ", "rct:", "rct:../x", "rct:Big Name", "a:textures/../skin.png", "a:textures/skin", "A:b.png",
            "name with spaces", "way_too_long_player_name").forEach {
            assertEquals(NpcSkinRef.Default, NpcSkinRef.parse(it), it)
        }
    }
}
