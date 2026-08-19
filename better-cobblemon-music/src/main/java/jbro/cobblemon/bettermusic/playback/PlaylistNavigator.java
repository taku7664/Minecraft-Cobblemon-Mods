package jbro.cobblemon.bettermusic.playback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;
import jbro.cobblemon.bettermusic.config.PlaylistDefinition;

public final class PlaylistNavigator {
    private final RandomGenerator random;
    private final Map<String, State> states = new HashMap<>();

    public PlaylistNavigator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public String next(String playlistId, PlaylistDefinition playlist) {
        Objects.requireNonNull(playlistId, "playlistId");
        Objects.requireNonNull(playlist, "playlist");
        State state = states.compute(playlistId, (key, existing) ->
            existing != null && existing.matches(playlist) ? existing : new State(playlist)
        );
        return switch (playlist.selection()) {
            case SEQUENTIAL -> sequential(state);
            case RANDOM -> random(state);
            case SHUFFLE -> shuffle(state);
        };
    }

    public void reset() {
        states.clear();
    }

    private String sequential(State state) {
        int nextIndex = state.lastIndex < 0 ? 0 : (state.lastIndex + 1) % state.tracks.size();
        return select(state, nextIndex);
    }

    private String random(State state) {
        if (state.tracks.size() == 1) {
            return select(state, 0);
        }
        int candidate = random.nextInt(state.tracks.size() - 1);
        if (candidate >= state.lastIndex && state.lastIndex >= 0) {
            candidate++;
        }
        return select(state, candidate);
    }

    private String shuffle(State state) {
        if (state.shufflePosition >= state.shuffleOrder.size()) {
            rebuildShuffle(state);
        }
        int index = state.shuffleOrder.get(state.shufflePosition++);
        return select(state, index);
    }

    private void rebuildShuffle(State state) {
        state.shuffleOrder.clear();
        for (int index = 0; index < state.tracks.size(); index++) {
            state.shuffleOrder.add(index);
        }
        for (int index = state.shuffleOrder.size() - 1; index > 0; index--) {
            int swapWith = random.nextInt(index + 1);
            int value = state.shuffleOrder.get(index);
            state.shuffleOrder.set(index, state.shuffleOrder.get(swapWith));
            state.shuffleOrder.set(swapWith, value);
        }
        if (state.shuffleOrder.size() > 1 && state.shuffleOrder.getFirst() == state.lastIndex) {
            int swapWith = 1 + random.nextInt(state.shuffleOrder.size() - 1);
            int first = state.shuffleOrder.getFirst();
            state.shuffleOrder.set(0, state.shuffleOrder.get(swapWith));
            state.shuffleOrder.set(swapWith, first);
        }
        state.shufflePosition = 0;
    }

    private static String select(State state, int index) {
        state.lastIndex = index;
        return state.tracks.get(index);
    }

    private static final class State {
        private final PlaylistDefinition.Selection selection;
        private final List<String> tracks;
        private final List<Integer> shuffleOrder = new ArrayList<>();
        private int shufflePosition;
        private int lastIndex = -1;

        private State(PlaylistDefinition playlist) {
            selection = playlist.selection();
            tracks = playlist.tracks();
        }

        private boolean matches(PlaylistDefinition playlist) {
            return selection == playlist.selection() && tracks.equals(playlist.tracks());
        }
    }
}
