package jbro.cobblemon.bettermusic.client;

final class PlaylistChoiceCodec {
    private PlaylistChoiceCodec() {
    }

    static String parseInput(String input, String packDefaultDisplay) {
        return input.equals(packDefaultDisplay) ? "" : input;
    }
}
