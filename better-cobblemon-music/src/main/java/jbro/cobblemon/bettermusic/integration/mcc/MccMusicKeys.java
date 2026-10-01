package jbro.cobblemon.bettermusic.integration.mcc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The music keys of More Cobblemon Contents, built from plain strings so nothing here loads MCC's classes.
 *
 * <p>A battle tagged {@code more_cobblemon_contents:league_challenge}, stage {@code champion}, opponent
 * {@code ...:cynthia} looks for {@code ...:league_challenge/champion/cynthia}, then
 * {@code ...:league_challenge/champion}, then {@code ...:league_challenge}. The hub's tab
 * {@code more_cobblemon_contents:shop} looks for {@code more_cobblemon_contents:hub/shop}, then
 * {@code more_cobblemon_contents:hub}.
 */
final class MccMusicKeys {
    static final String NAMESPACE = "more_cobblemon_contents";
    static final String HUB = NAMESPACE + ":hub";
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private static final String LEAGUE = NAMESPACE + ":league_challenge";
    private static final String TOWER = NAMESPACE + ":battle_tower";
    private static final String FACTORY = NAMESPACE + ":battle_factory";
    private static final String PVP = NAMESPACE + ":pvp";

    /** The keys MCC's contents can produce, offered in the settings screen. Opponent keys are left out. */
    static final Set<String> KNOWN_BATTLE_KEYS = known(
        LEAGUE, List.of("gym", "elite_four", "champion", "hard_gym", "hard_elite_four", "hard_champion",
            "wild_trainer", "wild_trainer_ace"),
        TOWER, List.of("regular", "tier_boss", "master_ball_boss"),
        FACTORY, List.of("regular", "factory_head"),
        PVP, List.of("single", "double")
    );

    static final Set<String> KNOWN_SCREEN_KEYS = Set.copyOf(List.of(
        HUB, HUB + "/dashboard", HUB + "/shop", HUB + "/league_challenge", HUB + "/battle_tower",
        HUB + "/battle_factory", HUB + "/pvp"
    ));

    private MccMusicKeys() {
    }

    /** A battle's keys, most specific first; parts that would not form a valid key are skipped. */
    static List<String> battle(String contentId, String stage, String opponentId) {
        if (contentId == null || !KEY.matcher(contentId).matches()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        if (stage != null) {
            if (opponentId != null) {
                keys.add(contentId + "/" + stage + "/" + opponentId.substring(opponentId.lastIndexOf(':') + 1));
            }
            keys.add(contentId + "/" + stage);
        }
        keys.add(contentId);
        return keys.stream().filter(key -> KEY.matcher(key).matches()).toList();
    }

    /** The hub's keys for its open tab, most specific first. Tabs of other mods keep their namespace in the path. */
    static List<String> hub(String tabId) {
        if (tabId == null || !KEY.matcher(tabId).matches()) {
            return List.of(HUB);
        }
        String namespace = tabId.substring(0, tabId.indexOf(':'));
        String path = tabId.substring(tabId.indexOf(':') + 1);
        String tab = HUB + "/" + (namespace.equals(NAMESPACE) ? path : namespace + "/" + path);
        return List.of(tab, HUB);
    }

    private static Set<String> known(Object... contentsAndStages) {
        Set<String> keys = new LinkedHashSet<>();
        for (int index = 0; index < contentsAndStages.length; index += 2) {
            String content = (String) contentsAndStages[index];
            keys.add(content);
            for (Object stage : (List<?>) contentsAndStages[index + 1]) {
                keys.add(content + "/" + stage);
            }
        }
        return Set.copyOf(keys);
    }
}
