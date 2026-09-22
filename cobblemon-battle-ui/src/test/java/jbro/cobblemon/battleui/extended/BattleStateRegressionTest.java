package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import jbro.cobblemon.battleui.extended.BattleStateTracker.ItemStatus;
import jbro.cobblemon.battleui.extended.battle.messages.MessageParser;
import jbro.cobblemon.battleui.extended.battle.messages.StateUpdater;
import jbro.cobblemon.battleui.extended.battle.state.AbilityItemTracker;
import jbro.cobblemon.battleui.extended.battle.state.FormTracker;
import jbro.cobblemon.battleui.extended.battle.state.PokemonRegistry;
import jbro.cobblemon.battleui.extended.battle.state.VolatileStatusTracker;
import net.minecraft.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class BattleStateRegressionTest {
    @AfterEach
    void clearState() {
        BattleStateTracker.INSTANCE.clear();
    }

    @Test
    void restoredItemReplacesConsumedState() {
        UUID uuid = UUID.randomUUID();
        PokemonRegistry.INSTANCE.registerPokemon(uuid, "Tropius", false);
        AbilityItemTracker.INSTANCE.setItem("Tropius", "Sitrus Berry", ItemStatus.CONSUMED, 2, false);

        AbilityItemTracker.INSTANCE.setItem("Tropius", "Sitrus Berry", ItemStatus.HELD, 3, false);

        assertEquals(ItemStatus.HELD, AbilityItemTracker.INSTANCE.getItem(uuid).getStatus());
    }

    @Test
    void transformClearsTheOldAbilityWhenTheCopiedAbilityIsUnknown() {
        UUID transformer = UUID.randomUUID();
        AbilityItemTracker.INSTANCE.setRevealedAbility(transformer, "Imposter");

        AbilityItemTracker.INSTANCE.replaceAbilityForTransform(transformer, null);

        assertEquals(null, AbilityItemTracker.INSTANCE.getRevealedAbility(transformer));
    }

    @Test
    void transformReplacesTheOldAbilityWhenTheCopiedAbilityIsKnown() {
        UUID transformer = UUID.randomUUID();
        AbilityItemTracker.INSTANCE.setRevealedAbility(transformer, "Imposter");

        AbilityItemTracker.INSTANCE.replaceAbilityForTransform(transformer, "Flash Fire");

        assertEquals("Flash Fire", AbilityItemTracker.INSTANCE.getRevealedAbility(transformer));
    }

    @Test
    void perishSongOnlyMarksTheProvidedActivePokemon() {
        UUID active = UUID.randomUUID();
        UUID benched = UUID.randomUUID();
        PokemonRegistry.INSTANCE.registerPokemon(active, "Active", true);
        PokemonRegistry.INSTANCE.registerPokemon(benched, "Benched", true);

        VolatileStatusTracker.INSTANCE.applyPerishSongTo(Set.of(active), 4);

        assertTrue(VolatileStatusTracker.INSTANCE.getVolatileStatuses(active).stream()
            .anyMatch(state -> state.getType() == BattleStateTracker.VolatileStatus.PERISH_SONG));
        assertFalse(VolatileStatusTracker.INSTANCE.getVolatileStatuses(benched).stream()
            .anyMatch(state -> state.getType() == BattleStateTracker.VolatileStatus.PERISH_SONG));
    }

    @Test
    void typeTranslationKeyProducesStableInternalId() {
        assertEquals("fire", MessageParser.INSTANCE.extractTypeId(Text.translatable("cobblemon.type.fire")));
    }

    @Test
    void ownedPokemonArgumentKeepsItsOwnerForMirrorMatchResolution() {
        UUID ally = UUID.randomUUID();
        UUID opponent = UUID.randomUUID();
        PokemonRegistry.INSTANCE.setPlayerNames("Alice", "Bob");
        PokemonRegistry.INSTANCE.registerPokemon(ally, "Eevee", true, "Alice");
        PokemonRegistry.INSTANCE.registerPokemon(opponent, "Eevee", false, "Bob");

        String opponentReference = MessageParser.INSTANCE.extractPokemonName(
            Text.translatable("cobblemon.battle.owned_pokemon", "Bob", "Eevee")
        );
        String allyReference = MessageParser.INSTANCE.extractPokemonName(
            Text.translatable("cobblemon.battle.owned_pokemon", "Alice", "Eevee")
        );

        assertEquals(opponent, PokemonRegistry.INSTANCE.resolvePokemonUuid(opponentReference, null));
        assertEquals(ally, PokemonRegistry.INSTANCE.resolvePokemonUuid(allyReference, null));
    }

    @Test
    void ownerResolutionDoesNotUseSubstringMatches() {
        UUID ally = UUID.randomUUID();
        UUID opponent = UUID.randomUUID();
        PokemonRegistry.INSTANCE.setPlayerNames("Al", "Alice");
        PokemonRegistry.INSTANCE.registerPokemon(ally, "Eevee", true, "Al");
        PokemonRegistry.INSTANCE.registerPokemon(opponent, "Eevee", false, "Alice");

        String opponentReference = MessageParser.INSTANCE.extractPokemonName(
            Text.translatable("cobblemon.battle.owned_pokemon", "Alice", "Eevee")
        );

        assertEquals(opponent, PokemonRegistry.INSTANCE.resolvePokemonUuid(opponentReference, null));
    }

    @Test
    void ownerResolutionDistinguishesSameNamedPokemonOnTheSameSide() {
        UUID aliceEevee = UUID.randomUUID();
        UUID carolEevee = UUID.randomUUID();
        PokemonRegistry.INSTANCE.setPlayerNames(
            List.of("Alice", "Carol"),
            List.of("Bob", "Dave")
        );
        PokemonRegistry.INSTANCE.registerPokemon(aliceEevee, "Eevee", true, "Alice");
        PokemonRegistry.INSTANCE.registerPokemon(carolEevee, "Eevee", true, "Carol");

        assertEquals(
            carolEevee,
            PokemonRegistry.INSTANCE.resolvePokemonUuid("Carol's Eevee", null)
        );
        assertEquals(
            aliceEevee,
            PokemonRegistry.INSTANCE.resolvePokemonUuid("Alice's Eevee", null)
        );
    }

    @Test
    void sameSideDuplicateWithoutOwnerIsNotGuessed() {
        PokemonRegistry.INSTANCE.registerPokemon(UUID.randomUUID(), "Eevee", true);
        PokemonRegistry.INSTANCE.registerPokemon(UUID.randomUUID(), "Eevee", true);

        assertEquals(null, PokemonRegistry.INSTANCE.resolvePokemonUuid("Eevee", true));
    }

    @Test
    void ownerQualifiedReferenceNeverFallsBackToAnotherOwnersSingleCandidate() {
        UUID aliceEevee = UUID.randomUUID();
        PokemonRegistry.INSTANCE.setPlayerNames(
            List.of("Alice", "Carol"),
            List.of("Bob")
        );
        PokemonRegistry.INSTANCE.registerPokemon(aliceEevee, "Eevee", true, "Alice");

        assertEquals(null, PokemonRegistry.INSTANCE.resolvePokemonUuid("Carol's Eevee", null));
    }

    @Test
    void stealEatRemovesTheVictimsItemNotTheUsers() {
        UUID victim = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        PokemonRegistry.INSTANCE.registerPokemon(victim, "Victim", false);
        PokemonRegistry.INSTANCE.registerPokemon(user, "User", true);

        StateUpdater.INSTANCE.extractStealEat(new Object[] {"Victim", "Sitrus Berry", "User"});

        assertEquals(ItemStatus.STOLEN, AbilityItemTracker.INSTANCE.getItem(victim).getStatus());
        assertEquals(null, AbilityItemTracker.INSTANCE.getItem(user));
    }

    @Test
    void corrosiveGasUsesDestroyedStatus() {
        UUID victim = UUID.randomUUID();
        PokemonRegistry.INSTANCE.registerPokemon(victim, "Victim", false);

        StateUpdater.INSTANCE.extractCorrosiveGas(new Object[] {"Victim", "Leftovers", "User"});

        assertEquals(ItemStatus.DESTROYED, AbilityItemTracker.INSTANCE.getItem(victim).getStatus());
    }

    @Test
    void batonPassDataIsAppliedOnlyToTheChosenReceiver() {
        UUID passer = UUID.randomUUID();
        UUID unchangedPartner = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        PokemonRegistry.INSTANCE.registerPokemon(passer, "Passer", true);
        PokemonRegistry.INSTANCE.registerPokemon(unchangedPartner, "Partner", true);
        PokemonRegistry.INSTANCE.registerPokemon(receiver, "Receiver", true);
        BattleStateTracker.INSTANCE.applyStatChange(
            "Passer", BattleStateTracker.BattleStat.ATTACK, 2, true
        );
        BattleStateTracker.INSTANCE.markBatonPassUsed("Passer", true);

        var batonData = BattleStateTracker.INSTANCE.clearPokemonAfterSwitch(passer);
        assertNotNull(batonData);
        BattleStateTracker.INSTANCE.applyBatonPass(receiver, batonData);

        assertEquals(2, BattleStateTracker.INSTANCE.getStatChanges(receiver)
            .get(BattleStateTracker.BattleStat.ATTACK));
        assertTrue(BattleStateTracker.INSTANCE.getStatChanges(unchangedPartner).isEmpty());
    }

    @Test
    void batonPassPreservesRemainingVolatileDuration() {
        UUID passer = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        PokemonRegistry.INSTANCE.registerPokemon(passer, "Passer", true);
        PokemonRegistry.INSTANCE.registerPokemon(receiver, "Receiver", true);
        BattleStateTracker.INSTANCE.setTurn(2);
        BattleStateTracker.INSTANCE.setVolatileStatus(
            "Passer", BattleStateTracker.VolatileStatus.CONFUSION, true
        );
        BattleStateTracker.INSTANCE.markBatonPassUsed("Passer", true);

        BattleStateTracker.INSTANCE.setTurn(4);
        var batonData = BattleStateTracker.INSTANCE.clearPokemonAfterSwitch(passer);
        assertNotNull(batonData);
        BattleStateTracker.INSTANCE.applyBatonPass(receiver, batonData);

        var confusion = BattleStateTracker.INSTANCE.getVolatileStatuses(receiver).stream()
            .filter(state -> state.getType() == BattleStateTracker.VolatileStatus.CONFUSION)
            .findFirst()
            .orElseThrow();
        assertEquals("2", BattleStateTracker.INSTANCE.getVolatileTurnsRemaining(confusion));
    }

    @Test
    void switchClearsTemporaryFormsButKeepsPermanentForms() {
        UUID temporary = UUID.randomUUID();
        UUID permanent = UUID.randomUUID();
        PokemonRegistry.INSTANCE.registerPokemon(temporary, "Temporary", true);
        PokemonRegistry.INSTANCE.registerPokemon(permanent, "Permanent", true);
        FormTracker.INSTANCE.setCurrentForm("Temporary", "Dynamax", false, true, true);
        FormTracker.INSTANCE.setCurrentForm("Permanent", "Mega", true, false, true);

        BattleStateTracker.INSTANCE.clearPokemonAfterSwitch(temporary);
        BattleStateTracker.INSTANCE.clearPokemonAfterSwitch(permanent);

        assertEquals(null, FormTracker.INSTANCE.getCurrentForm(temporary));
        assertNotNull(FormTracker.INSTANCE.getCurrentForm(permanent));
    }
}
