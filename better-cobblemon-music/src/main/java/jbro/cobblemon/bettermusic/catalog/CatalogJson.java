package jbro.cobblemon.bettermusic.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class CatalogJson {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern RESOURCE_PATH = Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");
    private static final Pattern SPECIES = Pattern.compile("[a-z0-9_.-]+(?::[a-z0-9_.-]+)?(?:#[a-z0-9_.-]+)?");
    private static final Pattern LEGACY_OGG_PATH = Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*\\.ogg");

    private CatalogJson() {
    }

    static JsonObject object(JsonObject parent, String key, String path) {
        JsonElement element = required(parent, key, path);
        if (!element.isJsonObject()) {
            throw error(path + "." + key, "must be an object");
        }
        return element.getAsJsonObject();
    }

    static JsonObject optionalObject(JsonObject parent, String key, String path) {
        if (!parent.has(key)) {
            return new JsonObject();
        }
        return object(parent, key, path);
    }

    static JsonArray array(JsonObject parent, String key, String path) {
        JsonElement element = required(parent, key, path);
        if (!element.isJsonArray()) {
            throw error(path + "." + key, "must be an array");
        }
        return element.getAsJsonArray();
    }

    static JsonArray optionalArray(JsonObject parent, String key, String path) {
        if (!parent.has(key)) {
            return new JsonArray();
        }
        return array(parent, key, path);
    }

    static String string(JsonObject parent, String key, String path) {
        JsonElement element = required(parent, key, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw error(path + "." + key, "must be a string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw error(path + "." + key, "must not be blank");
        }
        return value;
    }

    static String optionalString(JsonObject parent, String key, String path, String fallback) {
        return parent.has(key) ? string(parent, key, path) : fallback;
    }

    static double number(JsonObject parent, String key, String path) {
        JsonElement element = required(parent, key, path);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw error(path + "." + key, "must be a number");
        }
        try {
            double value = element.getAsDouble();
            if (!Double.isFinite(value)) {
                throw error(path + "." + key, "must be finite");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw error(path + "." + key, "must be a number");
        }
    }

    static int integer(JsonObject parent, String key, String path) {
        double value = number(parent, key, path);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw error(path + "." + key, "must be an integer");
        }
        return (int) value;
    }

    static String resourceId(String value, String path) {
        if (!RESOURCE_ID.matcher(value).matches()) {
            throw error(path, "must be a lowercase namespaced resource ID");
        }
        return value;
    }

    static String resourcePath(String value, String path) {
        if (!RESOURCE_PATH.matcher(value).matches()) {
            throw error(path, "must be a lowercase resource path");
        }
        return value;
    }

    static String species(String value, String path) {
        if (!SPECIES.matcher(value).matches()) {
            throw error(path, "must be a lowercase species or species#form ID");
        }
        return value;
    }

    static String legacyOggPath(String value, String path) {
        if (!LEGACY_OGG_PATH.matcher(value).matches()) {
            throw error(path, "must be a lowercase relative .ogg path");
        }
        return value;
    }

    static void only(JsonObject object, String path, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (String key : object.keySet()) {
            if (!names.contains(key)) {
                throw error(path + "." + key, "unknown property");
            }
        }
    }

    static Set<String> uniqueStrings(JsonArray array, String path, ValueValidator validator) {
        Set<String> values = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String itemPath = path + "[" + index + "]";
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw error(itemPath, "must be a string");
            }
            String value = validator.validate(element.getAsString(), itemPath);
            if (!values.add(value)) {
                throw error(itemPath, "duplicate value '" + value + "'");
            }
        }
        return values;
    }

    static <E extends Enum<E>> E enumValue(String value, Class<E> type, String path) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw error(path, "must be one of " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    static CatalogValidationException error(String path, String message) {
        return new CatalogValidationException(path + ": " + message);
    }

    private static JsonElement required(JsonObject parent, String key, String path) {
        if (!parent.has(key) || parent.get(key).isJsonNull()) {
            throw error(path + "." + key, "is required");
        }
        return parent.get(key);
    }

    @FunctionalInterface
    interface ValueValidator {
        String validate(String value, String path);
    }
}
