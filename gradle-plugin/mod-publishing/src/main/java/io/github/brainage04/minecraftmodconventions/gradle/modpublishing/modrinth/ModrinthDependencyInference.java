package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.JsonValues;
import me.modmuss50.mpp.PlatformDependency;
import org.gradle.api.GradleException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ModrinthDependencyInference {
    private static final Set<String> BUILT_IN_DEPENDENCIES = Set.of("minecraft", "java", "fabricloader");
    private static final List<DependencyField> FIELDS = List.of(
            new DependencyField("depends", PlatformDependency.DependencyType.REQUIRED),
            new DependencyField("recommends", PlatformDependency.DependencyType.OPTIONAL),
            new DependencyField("suggests", PlatformDependency.DependencyType.OPTIONAL),
            new DependencyField("conflicts", PlatformDependency.DependencyType.INCOMPATIBLE),
            new DependencyField("breaks", PlatformDependency.DependencyType.INCOMPATIBLE)
    );

    private ModrinthDependencyInference() {
    }

    public static Configuration parse(String fabricModJson, String projectConfigJson) {
        JsonObject mod = parseObject(fabricModJson, "fabric.mod.json");
        JsonObject config = projectConfigJson == null || projectConfigJson.isBlank()
                ? new JsonObject()
                : parseObject(projectConfigJson, ".modrinth/project.json");
        JsonObject overrides = object(config, "dependency_overrides");
        LinkedHashMap<String, Dependency> dependencies = new LinkedHashMap<>();

        for (DependencyField field : FIELDS) {
            JsonObject declarations = object(mod, field.name());
            for (String modId : declarations.keySet()) {
                if (BUILT_IN_DEPENDENCIES.contains(modId)) {
                    continue;
                }
                JsonObject override = object(overrides, modId);
                if (booleanValue(override, "skip", false)) {
                    continue;
                }
                PlatformDependency.DependencyType type = dependencyType(
                        JsonValues.string(override, "dependency_type"),
                        field.type()
                );
                String projectId = JsonValues.string(override, "project_id");
                String projectSlug = JsonValues.string(override, "project_slug");
                if (projectId.isBlank() && projectSlug.isBlank()) {
                    projectSlug = normalizedSlug(modId);
                }
                Dependency dependency = new Dependency(type, projectId, projectSlug, "");
                dependencies.put(dependency.key(), dependency);
            }
        }

        JsonObject version = object(config, "version");
        JsonArray explicitDependencies = array(version, "dependencies");
        for (JsonElement value : explicitDependencies) {
            if (!value.isJsonObject()) {
                throw new GradleException("Each Modrinth version dependency must be a JSON object.");
            }
            JsonObject dependency = value.getAsJsonObject();
            if (!JsonValues.string(dependency, "file_name").isBlank()) {
                throw new GradleException("Modrinth file_name dependencies are not supported by the upstream publisher.");
            }
            PlatformDependency.DependencyType type = dependencyType(
                    JsonValues.string(dependency, "dependency_type"),
                    PlatformDependency.DependencyType.REQUIRED
            );
            Dependency parsed = new Dependency(
                    type,
                    JsonValues.string(dependency, "project_id"),
                    JsonValues.string(dependency, "project_slug"),
                    JsonValues.string(dependency, "version_id")
            );
            if (parsed.projectId().isBlank() && parsed.projectSlug().isBlank()) {
                throw new GradleException("Explicit Modrinth dependencies require project_id or project_slug.");
            }
            dependencies.put(parsed.key(), parsed);
        }

        String status = JsonValues.string(version, "status");
        if (!status.isBlank() && !"listed".equals(status)) {
            throw new GradleException("The upstream Modrinth publisher only supports listed versions; configured status: " + status);
        }
        return new Configuration(
                JsonValues.string(config, "slug"),
                JsonValues.string(config, "project_id"),
                strings(version, "game_versions"),
                strings(version, "loaders"),
                booleanValue(version, "featured", true),
                List.copyOf(dependencies.values())
        );
    }

    private static JsonObject parseObject(String json, String source) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new GradleException("Expected a JSON object in " + source);
        }
        return parsed.getAsJsonObject();
    }

    private static JsonObject object(JsonObject source, String name) {
        return source.has(name) && source.get(name).isJsonObject()
                ? source.getAsJsonObject(name)
                : new JsonObject();
    }

    private static JsonArray array(JsonObject source, String name) {
        return source.has(name) && source.get(name).isJsonArray()
                ? source.getAsJsonArray(name)
                : new JsonArray();
    }

    private static List<String> strings(JsonObject source, String name) {
        List<String> values = new ArrayList<>();
        for (JsonElement value : array(source, name)) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new GradleException(name + " must contain only strings.");
            }
            values.add(value.getAsString());
        }
        return List.copyOf(values);
    }

    private static boolean booleanValue(JsonObject source, String name, boolean defaultValue) {
        if (!source.has(name) || source.get(name).isJsonNull()) {
            return defaultValue;
        }
        if (!source.get(name).isJsonPrimitive() || !source.getAsJsonPrimitive(name).isBoolean()) {
            throw new GradleException(name + " must be a boolean.");
        }
        return source.get(name).getAsBoolean();
    }

    private static PlatformDependency.DependencyType dependencyType(
            String configured,
            PlatformDependency.DependencyType defaultType
    ) {
        if (configured == null || configured.isBlank()) {
            return defaultType;
        }
        try {
            return PlatformDependency.DependencyType.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new GradleException(
                    "Invalid dependency_type " + configured + "; expected required, optional, incompatible, or embedded.",
                    exception
            );
        }
    }

    private static String normalizedSlug(String modId) {
        return modId.replace('_', '-').replace('.', '-');
    }

    private record DependencyField(String name, PlatformDependency.DependencyType type) {
    }

    public record Configuration(
            String projectSlug,
            String projectId,
            List<String> gameVersions,
            List<String> loaders,
            boolean featured,
            List<Dependency> dependencies
    ) {
    }

    public record Dependency(
            PlatformDependency.DependencyType type,
            String projectId,
            String projectSlug,
            String version
    ) {
        String key() {
            return type + "\u0000" + projectId + "\u0000" + projectSlug + "\u0000" + version;
        }
    }
}
