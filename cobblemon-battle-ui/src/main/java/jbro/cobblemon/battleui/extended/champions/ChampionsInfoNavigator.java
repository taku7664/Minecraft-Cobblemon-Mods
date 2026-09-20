package jbro.cobblemon.battleui.extended.champions;

/**
 * Spatial focus model for the Champions-style battle information overlay.
 * It deliberately knows nothing about Minecraft rendering or input APIs.
 */
public final class ChampionsInfoNavigator {
    public enum Section { POKEMON, EFFECTS }

    private Section section = Section.POKEMON;
    private int pokemonCount;
    private int effectCount;
    private int focusedPokemon;
    private int focusedEffect;

    public void updateCounts(int pokemonCount, int effectCount) {
        this.pokemonCount = Math.max(0, pokemonCount);
        this.effectCount = Math.max(0, effectCount);
        focusedPokemon = clamp(focusedPokemon, this.pokemonCount);
        focusedEffect = clamp(focusedEffect, this.effectCount);
        if (section == Section.EFFECTS && this.effectCount == 0) {
            section = Section.POKEMON;
        }
    }

    public void reset() {
        section = Section.POKEMON;
        focusedPokemon = 0;
        focusedEffect = 0;
    }

    public void moveLeft() {
        if (section == Section.POKEMON) focusedPokemon = wrap(focusedPokemon - 1, pokemonCount);
    }

    public void moveRight() {
        if (section == Section.POKEMON) focusedPokemon = wrap(focusedPokemon + 1, pokemonCount);
    }

    public void moveUp() {
        if (section == Section.EFFECTS) {
            if (focusedEffect == 0) section = Section.POKEMON;
            else focusedEffect--;
        }
    }

    public void moveDown() {
        if (section == Section.POKEMON) {
            if (effectCount > 0) {
                section = Section.EFFECTS;
                focusedEffect = 0;
            }
        } else if (focusedEffect + 1 < effectCount) {
            focusedEffect++;
        }
    }

    public void hoverPokemon(int index) {
        if (index >= 0 && index < pokemonCount) {
            section = Section.POKEMON;
            focusedPokemon = index;
        }
    }

    public void hoverEffect(int index) {
        if (index >= 0 && index < effectCount) {
            section = Section.EFFECTS;
            focusedEffect = index;
        }
    }

    public Section section() { return section; }
    public int focusedPokemon() { return focusedPokemon; }
    public int focusedEffect() { return focusedEffect; }

    private static int wrap(int value, int count) {
        if (count <= 0) return 0;
        return Math.floorMod(value, count);
    }

    private static int clamp(int value, int count) {
        if (count <= 0) return 0;
        return Math.max(0, Math.min(value, count - 1));
    }
}
