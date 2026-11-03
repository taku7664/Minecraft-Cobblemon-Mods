package jbro.cobblemon.battlespeed.core;

import jbro.cobblemon.battlespeed.config.BattleSpeedConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToIntFunction;

/** Decides how fast a battle runs from who is in it and the levels its players picked. */
public final class BattleSpeedPolicy {
    public enum Kind { PLAYER, WILD, NPC }

    /** One battle actor: its kind and, for players, their UUIDs. */
    public record Participant(Kind kind, List<UUID> playerIds) {
        public Participant {
            Objects.requireNonNull(kind, "kind");
            playerIds = List.copyOf(playerIds);
        }

        public static Participant player(UUID id) {
            return new Participant(Kind.PLAYER, List.of(id));
        }

        public static Participant wild() {
            return new Participant(Kind.WILD, List.of());
        }

        public static Participant npc() {
            return new Participant(Kind.NPC, List.of());
        }
    }

    private BattleSpeedPolicy() {
    }

    /** Whether speed-up is allowed for a battle with these sides at all. */
    public static boolean eligible(BattleSpeedConfig config, List<List<Participant>> sides) {
        if (!config.enabled) {
            return false;
        }
        int playerSides = 0;
        boolean hasPlayer = false;
        for (List<Participant> side : sides) {
            boolean sideHasPlayer = false;
            for (Participant participant : side) {
                switch (participant.kind()) {
                    case PLAYER -> sideHasPlayer = true;
                    case WILD -> {
                        if (!config.battles.wild) {
                            return false;
                        }
                    }
                    case NPC -> {
                        if (!config.battles.npc) {
                            return false;
                        }
                    }
                }
            }
            if (sideHasPlayer) {
                playerSides++;
                hasPlayer = true;
            }
        }
        if (!hasPlayer) {
            return false;
        }
        return playerSides < 2 || config.battles.pvp;
    }

    /** The level speed of a battle, before any per-part multiplier. 1.0 when the battle is not eligible. */
    public static double levelSpeed(
        BattleSpeedConfig config,
        List<List<Participant>> sides,
        ToIntFunction<UUID> levelOf
    ) {
        if (!eligible(config, sides)) {
            return 1.0;
        }
        var players = new ArrayList<UUID>();
        int playerSides = 0;
        for (List<Participant> side : sides) {
            boolean sideHasPlayer = false;
            for (Participant participant : side) {
                if (participant.kind() == Kind.PLAYER) {
                    players.addAll(participant.playerIds());
                    sideHasPlayer = true;
                }
            }
            if (sideHasPlayer) {
                playerSides++;
            }
        }
        if (players.isEmpty()) {
            return 1.0;
        }
        // PvP always takes the slowest pick so neither side speeds up the other against their will.
        if (players.size() > 1 && playerSides < 2 && BattleSpeedConfig.NORMAL.equals(config.multiplePlayers)) {
            return 1.0;
        }
        double slowest = Double.MAX_VALUE;
        for (UUID player : players) {
            slowest = Math.min(slowest, config.speedOf(levelOf.applyAsInt(player)));
        }
        return slowest;
    }

    /** "2" for 2.0, "1.5" for 1.5: how a speed reads in chat. */
    public static String formatSpeed(double speed) {
        if (speed == Math.rint(speed)) {
            return String.valueOf((long) speed);
        }
        return String.format(Locale.ROOT, "%.2f", speed).replaceAll("0+$", "");
    }

    /** Whether move animations should be skipped outright for a battle at this level speed. */
    public static boolean skipMoveEffects(BattleSpeedConfig config, double levelSpeed) {
        int from = config.skipMoveEffectsFromLevel;
        if (from <= 0 || from > config.levelCount()) {
            return false;
        }
        return levelSpeed > 1.0 && levelSpeed >= config.speedOf(from);
    }
}
