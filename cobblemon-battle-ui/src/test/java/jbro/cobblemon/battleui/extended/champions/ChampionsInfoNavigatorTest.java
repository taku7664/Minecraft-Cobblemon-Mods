package jbro.cobblemon.battleui.extended.champions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChampionsInfoNavigatorTest {
    @Test
    void wrapsAcrossPokemonTabs() {
        ChampionsInfoNavigator navigator = new ChampionsInfoNavigator();
        navigator.updateCounts(4, 3);

        navigator.moveLeft();
        assertEquals(3, navigator.focusedPokemon());

        navigator.moveRight();
        assertEquals(0, navigator.focusedPokemon());
    }

    @Test
    void movesBetweenRosterAndEffectList() {
        ChampionsInfoNavigator navigator = new ChampionsInfoNavigator();
        navigator.updateCounts(4, 3);
        navigator.hoverPokemon(2);

        navigator.moveDown();
        assertEquals(ChampionsInfoNavigator.Section.EFFECTS, navigator.section());
        assertEquals(0, navigator.focusedEffect());

        navigator.moveUp();
        assertEquals(ChampionsInfoNavigator.Section.POKEMON, navigator.section());
        assertEquals(2, navigator.focusedPokemon());
    }

    @Test
    void ignoresMissingEffectSectionAndClampsWhenDataShrinks() {
        ChampionsInfoNavigator navigator = new ChampionsInfoNavigator();
        navigator.updateCounts(6, 2);
        navigator.hoverPokemon(5);
        navigator.hoverEffect(1);

        navigator.updateCounts(2, 0);

        assertEquals(ChampionsInfoNavigator.Section.POKEMON, navigator.section());
        assertEquals(1, navigator.focusedPokemon());
        navigator.moveDown();
        assertEquals(ChampionsInfoNavigator.Section.POKEMON, navigator.section());
    }
}
