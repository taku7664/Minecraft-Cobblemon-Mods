package jbro.cobblemon.bettermusic.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RctTrainerRoleMapperTest {
    @Test
    void mapsPublicRctTypeIdsToUserFacingRoles() {
        assertEquals("gym", RctTrainerRoleMapper.map("leader").orElseThrow());
        assertEquals("elite", RctTrainerRoleMapper.map("e4").orElseThrow());
        assertEquals("champion", RctTrainerRoleMapper.map("champ").orElseThrow());
        assertEquals("rival", RctTrainerRoleMapper.map("rival").orElseThrow());
        assertTrue(RctTrainerRoleMapper.map("normal").isEmpty());
        assertTrue(RctTrainerRoleMapper.map(null).isEmpty());
    }
}
