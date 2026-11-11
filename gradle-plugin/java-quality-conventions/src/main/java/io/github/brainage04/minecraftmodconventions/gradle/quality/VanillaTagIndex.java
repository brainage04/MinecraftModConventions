package io.github.brainage04.minecraftmodconventions.gradle.quality;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The vanilla tag memberships read from the Minecraft jar a mod compiles against, so the check always matches the
 * game version of the project instead of a list copied from one release.
 *
 * <p>Two filters keep the advice usable. A tag must name the identity it lists: its last path segment, with a leading
 * {@code is_} dropped and a plural {@code s} removed, must be the end of the identity id, so {@code beds} names
 * {@code red_bed} and {@code oak_logs} names {@code oak_log}, while {@code beacon_base_blocks} and
 * {@code bookshelf_books} only classify a thing for one use. Tags that classify a block for a tool, an enchantment, a
 * spawn rule or a structure placement are left out as well, because replacing {@code block == Blocks.ICE} with the
 * pickaxe tag would be wrong.
 *
 * <p>{@code scripts/mod_fleet.py} mirrors these rules for its fleet listing, so a change here needs the same change
 * there.
 */
public final class VanillaTagIndex {
    private static final String TAG_ROOT = "data/minecraft/tags/";
    private static final String VANILLA_NAMESPACE = "minecraft:";
    private static final String VANILLA_TAG_PREFIX = "#minecraft:";
    /** The tag directories of {@code data/minecraft/tags}, including the {@code blocks}/{@code items} of 1.20 and earlier. */
    private static final Map<String, String> REGISTRY_DIRECTORIES = new LinkedHashMap<>();

    static {
        REGISTRY_DIRECTORIES.put("block", "block");
        REGISTRY_DIRECTORIES.put("blocks", "block");
        REGISTRY_DIRECTORIES.put("item", "item");
        REGISTRY_DIRECTORIES.put("items", "item");
        REGISTRY_DIRECTORIES.put("worldgen/biome", "biome");
    }

    private static final List<String> CLASSIFICATION_PREFIXES =
            List.of("mineable/", "enchantable/", "has_structure/", "spawns_");
    private static final List<String> CLASSIFICATION_PARTS = List.of("spawnable", "carver_replaceables", "_tool");
    private static final Set<String> CLASSIFICATION_TAGS = Set.of(
            "replaceable", "replaceable_by_trees", "replaceable_by_mushrooms", "geode_invalid_blocks",
            "fall_damage_resetting", "stronghold_biased_to", "enchantment_power_transmitter", "breaks_decorated_pots",
            "trimmable_armor", "snow_layer_cannot_survive_on");

    /** A vanilla tag that names the identity; {@code path} is the tag's path, such as {@code beds}. */
    public record Member(String registry, String path) {
    }

    private final Map<String, List<Member>> members;

    private VanillaTagIndex(Map<String, List<Member>> members) {
        this.members = members;
    }

    /** Reads every {@code data/minecraft/tags} entry of the given jars; later jars only add what earlier ones lack. */
    public static VanillaTagIndex load(Iterable<File> jars) {
        Map<String, Map<String, List<String>>> raw = new TreeMap<>();
        for (File file : jars) {
            if (!file.isFile()) {
                continue;
            }
            try (JarFile jar = new JarFile(file)) {
                for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                    JarEntry entry = entries.nextElement();
                    readEntry(jar, entry, raw);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot read the Minecraft jar " + file, exception);
            }
        }
        Map<String, Map<String, Set<String>>> resolved = new HashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> registry : raw.entrySet()) {
            Map<String, Set<String>> registryTags =
                    resolved.computeIfAbsent(registry.getKey(), _ -> new TreeMap<>());
            for (String tag : registry.getValue().keySet()) {
                resolve(registry.getKey(), tag, raw, registryTags, new HashSet<>());
            }
        }
        Map<String, List<Member>> members = new HashMap<>();
        for (Map.Entry<String, Map<String, Set<String>>> registry : resolved.entrySet()) {
            for (Map.Entry<String, Set<String>> tag : registry.getValue().entrySet()) {
                if (isClassificationTag(tag.getKey())) {
                    continue;
                }
                for (String id : tag.getValue()) {
                    if (!namesTheIdentity(tag.getKey(), id)) {
                        continue;
                    }
                    Member member = new Member(registry.getKey(), tag.getKey());
                    members.computeIfAbsent(registry.getKey() + "/" + id, _ -> new ArrayList<>()).add(member);
                }
            }
        }
        members.values().forEach(tagMembers -> tagMembers.sort((left, right) ->
                left.path().equals(right.path()) ? 0 : left.path().compareTo(right.path())));
        return new VanillaTagIndex(members);
    }

    public List<Member> members(String registry, String id) {
        return members.getOrDefault(registry + "/" + id, List.of());
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public int size() {
        return members.size();
    }

    /**
     * The tag reference to report, such as {@code block tag #minecraft:beds}. The Java constant depends on the game
     * version (26.x moved many tags to {@code BlockItemTags}), so the id is the one thing that is always right.
     */
    public static String reference(Member member) {
        String registry = switch (member.registry()) {
            case "block" -> "block tag";
            case "item" -> "item tag";
            default -> "biome tag";
        };
        return registry + " " + VANILLA_TAG_PREFIX + member.path();
    }

    private static void readEntry(JarFile jar, JarEntry entry, Map<String, Map<String, List<String>>> raw)
            throws IOException {
        String name = entry.getName();
        if (!name.startsWith(TAG_ROOT) || !name.endsWith(".json")) {
            return;
        }
        String path = name.substring(TAG_ROOT.length(), name.length() - ".json".length());
        for (Map.Entry<String, String> directory : REGISTRY_DIRECTORIES.entrySet()) {
            if (!path.startsWith(directory.getKey() + "/")) {
                continue;
            }
            String tag = path.substring(directory.getKey().length() + 1);
            try (InputStream input = jar.getInputStream(entry)) {
                JsonObject json = JsonParser.parseString(
                        new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                raw.computeIfAbsent(directory.getValue(), _ -> new TreeMap<>())
                        .putIfAbsent(tag, values(json.getAsJsonArray("values")));
            }
            return;
        }
    }

    private static List<String> values(JsonArray values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (JsonElement value : values) {
            String text = value.isJsonObject() && value.getAsJsonObject().has("id")
                    ? value.getAsJsonObject().get("id").getAsString()
                    : value.isJsonPrimitive() ? value.getAsString() : "";
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    private static Set<String> resolve(String registry, String tag, Map<String, Map<String, List<String>>> raw,
            Map<String, Set<String>> resolved, Set<String> visiting) {
        Set<String> done = resolved.get(tag);
        if (done != null) {
            return done;
        }
        if (!visiting.add(tag)) {
            return Set.of();
        }
        Set<String> ids = new TreeSet<>();
        for (String value : raw.getOrDefault(registry, Map.of()).getOrDefault(tag, List.of())) {
            if (value.startsWith("#")) {
                String nested = withoutNamespace(value.substring(1));
                if (nested != null) {
                    ids.addAll(resolve(registry, nested, raw, resolved, visiting));
                }
            } else {
                String id = withoutNamespace(value);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        visiting.remove(tag);
        resolved.put(tag, ids);
        return ids;
    }

    /** The id inside the vanilla namespace, or null for another namespace, whose tags only that namespace knows. */
    private static String withoutNamespace(String value) {
        return value.startsWith(VANILLA_NAMESPACE) ? value.substring(VANILLA_NAMESPACE.length()) : null;
    }

    private static boolean namesTheIdentity(String tag, String id) {
        String path = tag.substring(tag.lastIndexOf('/') + 1);
        if (path.startsWith("is_")) {
            path = path.substring("is_".length());
        }
        return singular(id).endsWith(singular(path));
    }

    private static String singular(String word) {
        return word.endsWith("s") && word.length() > 3 ? word.substring(0, word.length() - 1) : word;
    }

    private static boolean isClassificationTag(String tag) {
        if (CLASSIFICATION_TAGS.contains(tag)) {
            return true;
        }
        if (CLASSIFICATION_PREFIXES.stream().anyMatch(tag::startsWith)) {
            return true;
        }
        return CLASSIFICATION_PARTS.stream().anyMatch(tag::contains);
    }
}
