package io.github.brainage04.minecraftmodconventions.gradle.quality;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds lines of Java source that compare with a hardcoded vanilla dimension identity, such as
 * {@code level.dimension() != Level.OVERWORLD}. The patterns live in
 * {@value #PATTERNS_RESOURCE}, which {@code scripts/mod_fleet.py} reads too.
 */
public final class VanillaIdentityScanner {
    public static final String PATTERNS_RESOURCE = "vanilla-identity-patterns.txt";

    private static final Pattern DEFINITION = Pattern.compile("^([a-z]\\w*)\\s*=\\s*(.+)$");
    private static final Pattern REFERENCE = Pattern.compile("\\{([a-z]\\w*)}");
    private static final Pattern OPT_OUT = Pattern.compile("^//\\s*vanilla-identity:(.*)$");

    private final List<Pattern> hits;

    /** A hit line, 1-based; {@code missingReason} when an opt-out marker covers it without a reason. */
    public record Finding(int line, String source, boolean missingReason) {
    }

    VanillaIdentityScanner(List<Pattern> hits) {
        this.hits = List.copyOf(hits);
    }

    public static VanillaIdentityScanner load() {
        try (InputStream input = VanillaIdentityScanner.class.getResourceAsStream(PATTERNS_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing resource " + PATTERNS_RESOURCE);
            }
            return new VanillaIdentityScanner(parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<Pattern> parse(String patterns) {
        Map<String, String> fragments = new LinkedHashMap<>();
        List<Pattern> hits = new ArrayList<>();
        for (String raw : patterns.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher definition = DEFINITION.matcher(line);
            if (!definition.matches()) {
                throw new IllegalArgumentException("Malformed line in " + PATTERNS_RESOURCE + ": " + line);
            }
            Matcher reference = REFERENCE.matcher(definition.group(2));
            StringBuilder regex = new StringBuilder();
            while (reference.find()) {
                String fragment = fragments.get(reference.group(1));
                if (fragment == null) {
                    throw new IllegalArgumentException("Undefined {" + reference.group(1) + "} in " + PATTERNS_RESOURCE);
                }
                reference.appendReplacement(regex, Matcher.quoteReplacement("(?:" + fragment + ")"));
            }
            reference.appendTail(regex);
            if (definition.group(1).equals("hit")) {
                hits.add(Pattern.compile(regex.toString()));
            } else {
                fragments.put(definition.group(1), regex.toString());
            }
        }
        return hits;
    }

    /** Scans one file's lines; a {@code // vanilla-identity: <reason>} comment clears its own line and the next. */
    public List<Finding> scan(List<String> lines) {
        Lexer lexer = new Lexer();
        List<Finding> findings = new ArrayList<>();
        Boolean previousOptOut = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            lexer.split(line);
            Boolean optOut = optOut(lexer.comment);
            boolean hit = hits.stream().anyMatch(pattern -> pattern.matcher(lexer.code).find());
            if (hit) {
                boolean reasoned = Boolean.TRUE.equals(optOut) || Boolean.TRUE.equals(previousOptOut);
                if (!reasoned) {
                    findings.add(new Finding(index + 1, line.strip(), optOut != null || previousOptOut != null));
                }
            }
            previousOptOut = optOut;
        }
        return findings;
    }

    /** {@code null} without a marker, otherwise whether the marker gives a reason. */
    private static Boolean optOut(String comment) {
        Matcher marker = OPT_OUT.matcher(comment);
        return marker.matches() ? !marker.group(1).isBlank() : null;
    }

    /** Splits a line into code (comments removed, literal contents blanked) and its {@code //} comment. */
    private static final class Lexer {
        private boolean inBlockComment;
        private boolean inTextBlock;
        private String code = "";
        private String comment = "";

        void split(String line) {
            StringBuilder out = new StringBuilder();
            comment = "";
            int index = 0;
            while (index < line.length()) {
                if (inBlockComment) {
                    if (line.startsWith("*/", index)) {
                        inBlockComment = false;
                        out.append(' ');
                        index += 2;
                    } else {
                        index++;
                    }
                } else if (inTextBlock) {
                    if (line.startsWith("\"\"\"", index)) {
                        inTextBlock = false;
                        out.append("\"\"\"");
                        index += 3;
                    } else {
                        index += line.charAt(index) == '\\' ? 2 : 1;
                    }
                } else if (line.startsWith("\"\"\"", index)) {
                    inTextBlock = true;
                    out.append("\"\"\"");
                    index += 3;
                } else if (line.charAt(index) == '"' || line.charAt(index) == '\'') {
                    char quote = line.charAt(index);
                    index++;
                    while (index < line.length() && line.charAt(index) != quote) {
                        index += line.charAt(index) == '\\' ? 2 : 1;
                    }
                    out.append(quote).append(quote);
                    index++;
                } else if (line.startsWith("//", index)) {
                    comment = line.substring(index).strip();
                    break;
                } else if (line.startsWith("/*", index)) {
                    inBlockComment = true;
                    index += 2;
                } else {
                    out.append(line.charAt(index));
                    index++;
                }
            }
            code = out.toString();
        }
    }
}
