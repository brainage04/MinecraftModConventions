package io.github.brainage04.minecraftmodconventions.gradle.modpublishing;

import com.google.gson.JsonObject;

/** Shared tolerant accessors for remote and project JSON payloads. */
public final class JsonValues {
    private JsonValues() {
    }

    public static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        return object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }
}
