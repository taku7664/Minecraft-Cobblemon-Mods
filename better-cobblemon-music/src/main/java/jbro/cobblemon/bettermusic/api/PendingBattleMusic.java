package jbro.cobblemon.bettermusic.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import jbro.cobblemon.bettermusic.battle.BattleMusicContext;

/**
 * A battle the server has approved but not opened yet, while the client plays its entry transition. A content mod
 * that holds battles for a transition supplies it here, and battle music starts with the transition instead of after
 * it. The supplied battle must describe the same opponent the opened battle will, so its track carries on unbroken.
 */
public final class PendingBattleMusic {
    private static volatile Supplier<Optional<Pending>> source = Optional::empty;

    private PendingBattleMusic() {
    }

    public static void supply(Supplier<Optional<Pending>> pending) {
        source = Objects.requireNonNull(pending, "pending");
    }

    public static Optional<Pending> current() {
        try {
            return Objects.requireNonNullElse(source.get(), Optional.empty());
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    public record Pending(UUID battleId, BattleMusicContext context) {
        public Pending {
            Objects.requireNonNull(battleId, "battleId");
            Objects.requireNonNull(context, "context");
        }
    }
}
