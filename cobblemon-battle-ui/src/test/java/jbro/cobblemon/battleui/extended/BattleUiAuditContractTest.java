package jbro.cobblemon.battleui.extended;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jbro.cobblemon.battleui.extended.battle.messages.TranslationKeys;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class BattleUiAuditContractTest {
    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void teamIndicatorsAreConnectedToTheHudPass() throws Exception {
        String renderer = read("src/main/kotlin/com/cobblemonextendedbattleui/BattleInfoRenderer.kt");
        assertTrue(renderer.contains("TeamIndicatorUI.render(context)"));
    }

    @Test
    void emptyTeamsNeverCreatePanelsOrHelpIcons() throws Exception {
        String indicators = read("src/main/kotlin/com/cobblemonextendedbattleui/TeamIndicatorUI.kt");
        assertEquals(2, indicators.split("if \\(playerTeam\\.isNotEmpty\\(\\)\\)", -1).length - 1);
    }

    @Test
    void teamInteractionBoundsAreClearedBeforeEveryRenderExit() throws Exception {
        String indicators = read("src/main/kotlin/com/cobblemonextendedbattleui/TeamIndicatorUI.kt");
        int renderStart = indicators.indexOf("fun render(context: DrawContext)");
        int battleLookup = indicators.indexOf("val battle = CobblemonClient.battle ?: return", renderStart);
        int boundsClear = indicators.indexOf("clearFrameInteractionBounds()", renderStart);

        assertTrue(renderStart >= 0);
        assertTrue(boundsClear > renderStart);
        assertTrue(boundsClear < battleLookup, "Stale hit boxes must clear even when rendering exits early");
        assertTrue(indicators.contains("leftTeamPanelBounds = null"));
        assertTrue(indicators.contains("rightTeamPanelBounds = null"));
        assertTrue(indicators.contains("leftHelpIconBounds = null"));
        assertTrue(indicators.contains("rightHelpIconBounds = null"));
    }

    @Test
    void temporaryActiveSlotGapsNeverGuessThatAPokemonFainted() throws Exception {
        String indicators = read("src/main/kotlin/com/cobblemonextendedbattleui/TeamIndicatorUI.kt");
        String health = read(
            "src/main/java/com/cobblemonextendedbattleui/mixin/BattleHealthChangeHandlerMixin.java"
        );
        String updater = read(
            "src/main/kotlin/com/cobblemonextendedbattleui/battle/messages/StateUpdater.kt"
        );

        assertFalse(indicators.contains("previouslyActiveUuids"));
        assertFalse(indicators.contains("disappeared with no replacement"));
        assertTrue(health.contains("if (newPercent <= 0 && needsKOTracking)"));
        assertTrue(updater.contains("TeamIndicatorUI.markPokemonAsKO(pokemonName)"));
    }

    @Test
    void generalBattleCommandsReflowDuringRendering() throws Exception {
        String navigation = read(
            "src/main/java/com/cobblemonextendedbattleui/mixin/BattleGuiNavigationMixin.java"
        );
        int reflow = navigation.indexOf("BattleCommandLayout.place(");
        int hoverTest = navigation.indexOf(".isHovered(mouseX, mouseY)");
        assertTrue(reflow >= 0);
        assertTrue(hoverTest >= 0);
        assertTrue(reflow < hoverTest, "Commands must reflow before hover/focus ownership is evaluated");
    }

    @Test
    void spectatorSideMatchesCobblemonOverlayOnEveryPath() throws Exception {
        String switchMixin = read("src/main/java/com/cobblemonextendedbattleui/mixin/BattleSwitchHandlerMixin.java");
        assertTrue(switchMixin.contains("return !switchInSide1; // side2 is on the left for spectators"));
    }

    @Test
    void switchStateUsesActiveSlotsInsteadOfMessageArgumentOrder() throws Exception {
        String panel = read("src/main/kotlin/com/cobblemonextendedbattleui/BattleInfoPanel.kt");
        String interceptor = read("src/main/kotlin/com/cobblemonextendedbattleui/BattleMessageInterceptor.kt");
        String updater = read("src/main/kotlin/com/cobblemonextendedbattleui/battle/messages/StateUpdater.kt");
        String keys = read("src/main/kotlin/com/cobblemonextendedbattleui/battle/messages/TranslationKeys.kt");
        assertTrue(panel.contains("ActiveSlotTracker<UUID>"));
        assertFalse(interceptor.contains("StateUpdater.clearPokemonState"));
        assertFalse(updater.contains("fun clearPokemonState"));
        assertTrue(keys.contains("cobblemon.battle.formechange.default.temporary.end\""));
        assertTrue(keys.contains("cobblemon.battle.formechange.default.temporary.ended"));
        assertTrue(interceptor.contains("key in TranslationKeys.FORMECHANGE_ENDED_KEYS"));
    }

    @Test
    void opponentPpIsNotEstimatedOrTracked() throws Exception {
        String moveTracker = read("src/main/kotlin/com/cobblemonextendedbattleui/battle/state/MoveTracker.kt");
        String tooltip = read("src/main/kotlin/com/cobblemonextendedbattleui/pokemon/tooltip/TooltipDataBuilder.kt");
        String data = read("src/main/kotlin/com/cobblemonextendedbattleui/pokemon/tooltip/TooltipData.kt");
        assertFalse(moveTracker.contains("decrementPP"));
        assertFalse(moveTracker.contains("registerPressure"));
        assertFalse(tooltip.contains("estimatedMax"));
        assertFalse(data.contains("estimatedRemaining"));
    }

    @Test
    void moveTooltipNeverGuessesTheActingPokemonFromTheFirstBattleSlot() throws Exception {
        String tooltip = read("src/main/kotlin/com/cobblemonextendedbattleui/MoveTooltipRenderer.kt");
        assertFalse(tooltip.contains("getPlayerPartyPokemonFallback"));
        assertFalse(tooltip.contains("activeClientBattlePokemon.firstOrNull()"));
    }

    @Test
    void transformAbilityUsesOnlyOwnedOrRevealedInformation() throws Exception {
        String indicators = read("src/main/kotlin/com/cobblemonextendedbattleui/TeamIndicatorUI.kt");
        int staleAbilityClear = indicators.indexOf(
            "replaceAbilityForTransform(transformerUuid, null)"
        );
        int targetLookup = indicators.indexOf("BattleStateTracker.getPokemonUuid(targetName)");
        assertFalse(indicators.contains("getBattlePokemonByUuid(targetUuid, battle)"));
        assertFalse(indicators.contains("form?.abilities?.firstOrNull()"));
        assertFalse(indicators.contains("findTrackedPokemonByName"));
        assertTrue(indicators.contains("BattleStateTracker.getRevealedAbility(targetUuid)"));
        assertTrue(indicators.contains("playerActor.pokemon.find { it.uuid == targetUuid }"));
        assertTrue(indicators.contains("targetAbility = rawAbilityId"));
        assertFalse(indicators.contains("targetAbility = formatAbilityName(rawAbilityId)"));
        assertTrue(indicators.contains("replaceAbilityForTransform(transformerUuid, targetAbility)"));
        assertTrue(staleAbilityClear >= 0);
        assertTrue(targetLookup >= 0);
        assertTrue(staleAbilityClear < targetLookup, "Stale ability must clear before target lookup can fail");
    }

    @Test
    void everyRuntimeRegistrationPathPreservesActorOwnership() throws Exception {
        String initialization = read(
            "src/main/java/com/cobblemonextendedbattleui/mixin/BattleInitializeHandlerMixin.java"
        );
        String switching = read(
            "src/main/java/com/cobblemonextendedbattleui/mixin/BattleSwitchHandlerMixin.java"
        );
        String panel = read("src/main/kotlin/com/cobblemonextendedbattleui/BattleInfoPanel.kt");
        String indicators = read("src/main/kotlin/com/cobblemonextendedbattleui/TeamIndicatorUI.kt");

        assertTrue(initialization.contains("registerPokemon(uuid, name, isAlly, ownerName)"));
        assertTrue(switching.contains("registerPokemon(uuid, name, isAlly, determineOwnerName(packet))"));
        assertTrue(panel.contains("registerActivePokemon(it.second, true, it.third)"));
        assertTrue(indicators.contains("isPlayerSide, actor.displayName.string"));
    }

    @Test
    void battleLogUsesCobblemon181MoveKeys() throws Exception {
        String log = read("src/main/kotlin/com/cobblemonextendedbattleui/BattleLog.kt");
        assertTrue(log.contains("cobblemon.battle.used_move"));
        assertTrue(log.contains("cobblemon.battle.superEffective"));
        assertFalse(log.contains("cobblemon.battle.supereffective"));
    }

    @Test
    void parserConstantsOnlyReferenceCobblemon181TranslationKeys() throws Exception {
        JsonObject cobblemonEnglish;
        try (var stream = getClass().getResourceAsStream("/assets/cobblemon/lang/en_us.json")) {
            assertTrue(stream != null, "Cobblemon language resource must be on the test classpath");
            cobblemonEnglish = JsonParser.parseReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
            ).getAsJsonObject();
        }

        String constants = read(
            "src/main/kotlin/com/cobblemonextendedbattleui/battle/messages/TranslationKeys.kt"
        );
        var matcher = Pattern.compile(
            "\\\"(cobblemon\\.(?:battle|move|stat)\\.[A-Za-z0-9_.-]+|item\\.cobblemon\\.[A-Za-z0-9_.-]+)\\\""
        ).matcher(constants);
        Set<String> missing = new HashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!cobblemonEnglish.has(key) &&
                !TranslationKeys.INSTANCE.getRUNTIME_ONLY_BATTLE_KEYS().contains(key)) {
                missing.add(key);
            }
        }

        assertEquals(Set.of(), missing);

        for (String eventKey : TranslationKeys.INSTANCE.getBERRY_DAMAGE_KEYS()) {
            String berryId = eventKey.substring(eventKey.lastIndexOf('.') + 1);
            String itemId = berryId.substring(0, berryId.length() - "berry".length()) + "_berry";
            assertTrue(
                cobblemonEnglish.has("item.cobblemon." + itemId),
                "Missing item translation for " + eventKey
            );
        }

        Set<String> fixedAbilityIds = new HashSet<>(
            TranslationKeys.INSTANCE.getABILITY_SINGLE_ARG_KEYS().values()
        );
        fixedAbilityIds.addAll(TranslationKeys.INSTANCE.getABILITY_START_KEYS().values());
        for (String abilityId : fixedAbilityIds) {
            assertTrue(
                cobblemonEnglish.has("cobblemon.ability." + abilityId),
                "Missing ability translation for " + abilityId
            );
        }
    }

    @Test
    void koreanTranslationHasEveryEnglishKey() throws Exception {
        JsonObject english = JsonParser.parseString(read(
            "src/main/resources/assets/cobblemon_battle_ui/lang/en_us.json"
        )).getAsJsonObject();
        JsonObject korean = JsonParser.parseString(read(
            "src/main/resources/assets/cobblemon_battle_ui/lang/ko_kr.json"
        )).getAsJsonObject();
        Set<String> missing = english.keySet().stream()
            .filter(key -> !korean.has(key))
            .collect(Collectors.toSet());
        assertEquals(Set.of(), missing);
    }

    @Test
    void optionalClothConfigIsGuardedBeforeItsBuilderLoads() throws Exception {
        String integration = read("src/main/kotlin/com/cobblemonextendedbattleui/ModMenuIntegration.kt");
        assertTrue(integration.contains("isModLoaded(\"cloth-config\")"));
        assertFalse(integration.contains("import me.shedaniel.clothconfig2"));
    }

    @Test
    void retiredPanelConfigurationAndPartialPowerEstimationStayRemoved() throws Exception {
        String config = read("src/main/kotlin/com/cobblemonextendedbattleui/PanelConfig.kt");
        String tooltip = read("src/main/kotlin/com/cobblemonextendedbattleui/MoveTooltipRenderer.kt");
        assertFalse(config.contains("var panelX"));
        assertFalse(config.contains("var collapsedWidth"));
        assertFalse(tooltip.contains("ItemPowerBoostParser"));
        assertFalse(tooltip.contains("hasStab"));
        assertFalse(tooltip.contains("hasSheerForce"));
    }
}
