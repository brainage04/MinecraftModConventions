package io.github.brainage04.minecraftmodconventions.gradle.quality;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds lines of Java source that use a literal build limit of the vanilla overworld, such as
 * {@code if (y < -64)} or {@code int maxY = 320;}, where {@code level.getMinY()}, {@code getMaxY()} and
 * {@code getHeight()} answer the same question for the dimension the code runs in. The patterns live in
 * {@value #PATTERNS_RESOURCE}, which {@code scripts/mod_fleet.py} reads too.
 */
public final class WorldHeightScanner {
    public static final String PATTERNS_RESOURCE = "world-height-patterns.txt";

    private static final Pattern OPT_OUT = Pattern.compile("^//\\s*world-height:(.*)$");

    private final List<Pattern> hits;

    /** A hit line, 1-based; {@code missingReason} when an opt-out marker covers it without a reason. */
    public record Finding(int line, String source, boolean missingReason) {
    }

    WorldHeightScanner(List<Pattern> hits) {
        this.hits = List.copyOf(hits);
    }

    public static WorldHeightScanner load() {
        try (InputStream input = WorldHeightScanner.class.getResourceAsStream(PATTERNS_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing resource " + PATTERNS_RESOURCE);
            }
            return new WorldHeightScanner(PatternFile.parse(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), PATTERNS_RESOURCE, null));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Scans one file's lines; a {@code // world-height: <reason>} comment clears its own line and the next. */
    public List<Finding> scan(List<String> lines) {
        JavaSourceLine lexer = new JavaSourceLine();
        List<Finding> findings = new ArrayList<>();
        Boolean previousOptOut = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            lexer.split(line);
            Boolean optOut = optOut(lexer.comment());
            boolean hit = hits.stream().anyMatch(pattern -> pattern.matcher(lexer.code()).find());
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

    private static Boolean optOut(String comment) {
        Matcher marker = OPT_OUT.matcher(comment);
        return marker.matches() ? !marker.group(1).isBlank() : null;
    }
}
