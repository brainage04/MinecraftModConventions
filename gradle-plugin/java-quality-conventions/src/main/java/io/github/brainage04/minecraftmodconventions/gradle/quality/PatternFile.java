package io.github.brainage04.minecraftmodconventions.gradle.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a pattern file of {@code name = regex} fragments and {@code hit = regex} lines. The same file format is read
 * by {@code scripts/mod_fleet.py}, so every regex must stay inside the syntax Java and Python share.
 */
final class PatternFile {
    private static final Pattern DEFINITION = Pattern.compile("^([a-z]\\w*)\\s*=\\s*(.+)$");
    private static final Pattern REFERENCE = Pattern.compile("\\{([a-z]\\w*)}");

    private PatternFile() {
    }

    /**
     * The hit patterns of the file.
     *
     * @param resource     the file name, for error messages
     * @param requiredGroup a named group every hit pattern must carry, or null
     */
    static List<Pattern> parse(String content, String resource, String requiredGroup) {
        Map<String, String> fragments = new LinkedHashMap<>();
        List<Pattern> hits = new ArrayList<>();
        for (String raw : content.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher definition = DEFINITION.matcher(line);
            if (!definition.matches()) {
                throw new IllegalArgumentException("Malformed line in " + resource + ": " + line);
            }
            Matcher reference = REFERENCE.matcher(definition.group(2));
            StringBuilder regex = new StringBuilder();
            while (reference.find()) {
                String fragment = fragments.get(reference.group(1));
                if (fragment == null) {
                    throw new IllegalArgumentException("Undefined {" + reference.group(1) + "} in " + resource);
                }
                reference.appendReplacement(regex, Matcher.quoteReplacement("(?:" + fragment + ")"));
            }
            reference.appendTail(regex);
            if (definition.group(1).equals("hit")) {
                Pattern pattern = Pattern.compile(regex.toString());
                if (requiredGroup != null && !pattern.namedGroups().containsKey(requiredGroup)) {
                    throw new IllegalArgumentException(
                            "Hit pattern of " + resource + " has no (?<" + requiredGroup + ">...) group: " + line);
                }
                hits.add(pattern);
            } else {
                fragments.put(definition.group(1), regex.toString());
            }
        }
        return hits;
    }
}
