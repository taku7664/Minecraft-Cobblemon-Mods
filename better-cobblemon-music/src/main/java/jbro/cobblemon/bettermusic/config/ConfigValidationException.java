package jbro.cobblemon.bettermusic.config;

public final class ConfigValidationException extends IllegalArgumentException {
    public ConfigValidationException(String path, String message) {
        super(path + ": " + message);
    }
}
