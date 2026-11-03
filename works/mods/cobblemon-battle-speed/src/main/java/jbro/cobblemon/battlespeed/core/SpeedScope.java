package jbro.cobblemon.battlespeed.core;

import java.util.ArrayDeque;

/**
 * Marks which battle the server thread is working on, so the waits Cobblemon creates in there can be shortened.
 * Battle ticks and move animation keyframes both run here; a keyframe opened inside a tick is the inner scope.
 */
public final class SpeedScope {
    public enum Kind {
        /** Inside {@code PokemonBattle.tick}. */
        BATTLE_TICK,
        /** Inside a move animation timeline that belongs to a battle. */
        MOVE_EFFECT
    }

    public record Frame(Object battle, Kind kind) {
    }

    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private SpeedScope() {
    }

    public static void push(Object battle, Kind kind) {
        FRAMES.get().push(new Frame(battle, kind));
    }

    public static void pop() {
        var frames = FRAMES.get();
        if (!frames.isEmpty()) {
            frames.pop();
        }
    }

    /** The innermost frame, or null outside any battle work. */
    public static Frame current() {
        return FRAMES.get().peek();
    }
}
