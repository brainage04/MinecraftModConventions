package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.modrinth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.brainage04.minecraftmodconventions.gradle.modpublishing.JsonValues;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Produces deterministic Modrinth project create/update payloads from Fabric metadata. */
@CacheableTask
public abstract class PrepareModrinthProjectMetadataTask extends DefaultTask {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getFabricModJson();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    public abstract RegularFileProperty getProjectConfig();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getProjectBody();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    public abstract RegularFileProperty getLicenseFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    public abstract RegularFileProperty getIconFile();

    @Input
    public abstract Property<String> getRepository();

    @Input
    @Optional
    public abstract Property<String> getRepositoryDescription();

    @Input
    public abstract Property<String> getDiscordUrl();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    void prepareMetadata() throws IOException {
        JsonObject mod = readObject(getFabricModJson().get().getAsFile().toPath());
        JsonObject config = getProjectConfig().isPresent()
                ? readObject(getProjectConfig().get().getAsFile().toPath())
                : new JsonObject();
        String body = Files.readString(getProjectBody().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        String repository = required(getRepository().getOrElse(""), "GitHub repository");
        String repositoryUrl = "https://github.com/" + repository;
        String modId = required(JsonValues.string(mod, "id"), "fabric.mod.json id");
        String slug = firstNonBlank(JsonValues.string(config, "slug"), modId);
        String description = firstNonBlank(
                getRepositoryDescription().getOrElse(""),
                JsonValues.string(config, "description"),
                JsonValues.string(mod, "description")
        );
        JsonObject contact = object(mod, "contact");
        String issuesUrl = firstNonBlank(JsonValues.string(config, "issues_url"), JsonValues.string(contact, "issues"), repositoryUrl + "/issues");
        String sourceUrl = firstNonBlank(
                JsonValues.string(config, "source_url"),
                JsonValues.string(contact, "sources"),
                JsonValues.string(contact, "homepage"),
                repositoryUrl
        );
        String wikiUrl = firstNonBlank(JsonValues.string(config, "wiki_url"), JsonValues.string(contact, "wiki"), repositoryUrl + "/wiki");
        String licenseFileName = getLicenseFile().isPresent()
                ? getLicenseFile().get().getAsFile().getName()
                : "LICENSE";
        String licenseUrl = firstNonBlank(
                JsonValues.string(config, "license_url"),
                repositoryUrl + "/blob/HEAD/" + licenseFileName
        );
        SideSupport support = inferSupport(mod);
        String clientSide = firstNonBlank(JsonValues.string(config, "client_side"), support.client());
        String serverSide = firstNonBlank(JsonValues.string(config, "server_side"), support.server());

        JsonObject update = new JsonObject();
        addString(update, "description", description);
        update.addProperty("body", body);
        addString(update, "issues_url", issuesUrl);
        addString(update, "source_url", sourceUrl);
        addString(update, "wiki_url", wikiUrl);
        addString(update, "license_url", licenseUrl);
        addString(update, "client_side", clientSide);
        addString(update, "server_side", serverSide);

        JsonObject create = new JsonObject();
        create.addProperty("slug", slug);
        create.addProperty("title", firstNonBlank(JsonValues.string(config, "title"), JsonValues.string(mod, "name"), modId));
        addString(create, "description", description);
        create.addProperty("body", body);
        create.add("categories", nonEmptyArray(config, "categories", "utility"));
        addString(create, "client_side", clientSide);
        addString(create, "server_side", serverSide);
        create.addProperty("status", firstNonBlank(JsonValues.string(config, "status"), "draft"));
        copyIfPresent(config, create, "requested_status");
        create.add("additional_categories", array(config, "additional_categories"));
        addString(create, "issues_url", issuesUrl);
        addString(create, "source_url", sourceUrl);
        addString(create, "wiki_url", wikiUrl);
        addString(create, "discord_url", getDiscordUrl().getOrElse(""));
        create.add("donation_urls", array(config, "donation_urls"));
        JsonElement license = config.has("license_id") ? config.get("license_id") : mod.get("license");
        if (license != null && !license.isJsonNull()) {
            create.add("license_id", license.deepCopy());
        }
        addString(create, "license_url", licenseUrl);
        create.addProperty("project_type", firstNonBlank(JsonValues.string(config, "project_type"), "mod"));
        create.add("initial_versions", new JsonArray());
        create.addProperty("is_draft", true);

        JsonObject output = new JsonObject();
        output.addProperty("slug", slug);
        if (config.has("project_id") && !config.get("project_id").isJsonNull()) {
            output.add("project_id", config.get("project_id").deepCopy());
        }
        output.add("create", create);
        output.add("update", update);
        output.addProperty("has_icon", getIconFile().isPresent());

        Path outputPath = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, GSON.toJson(output) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    static JsonObject readObject(Path path) throws IOException {
        JsonElement value = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!value.isJsonObject()) {
            throw new GradleException("Expected a JSON object in " + path);
        }
        return value.getAsJsonObject();
    }


    private static JsonObject object(JsonObject source, String name) {
        return source.has(name) && source.get(name).isJsonObject()
                ? source.getAsJsonObject(name)
                : new JsonObject();
    }

    private static JsonArray array(JsonObject source, String name) {
        return source.has(name) && source.get(name).isJsonArray()
                ? source.getAsJsonArray(name).deepCopy()
                : new JsonArray();
    }

    private static JsonArray nonEmptyArray(JsonObject source, String name, String defaultValue) {
        JsonArray values = array(source, name);
        if (values.isEmpty()) {
            values.add(defaultValue);
        }
        return values;
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String name) {
        if (source.has(name) && !source.get(name).isJsonNull()) {
            target.add(name, source.get(name).deepCopy());
        }
    }

    private static void addString(JsonObject target, String name, String value) {
        if (value != null && !value.isBlank()) {
            target.addProperty(name, value);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new GradleException("Missing " + name);
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static SideSupport inferSupport(JsonObject mod) {
        String environment = JsonValues.string(mod, "environment");
        if ("client".equals(environment)) {
            return new SideSupport("required", "unsupported");
        }
        if ("server".equals(environment)) {
            return new SideSupport("unsupported", "required");
        }
        JsonObject entrypoints = object(mod, "entrypoints");
        JsonArray clientEntrypoints = array(entrypoints, "client");
        if (!clientEntrypoints.isEmpty()) {
            return new SideSupport("required", "required");
        }
        return new SideSupport("unsupported", "required");
    }

    private record SideSupport(String client, String server) {
    }
}
