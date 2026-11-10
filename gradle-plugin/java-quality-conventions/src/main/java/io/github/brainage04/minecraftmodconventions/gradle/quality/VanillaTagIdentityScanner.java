package io.github.brainage04.minecraftmodconventions.gradle.quality;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds lines of Java source that compare with a specific vanilla block, item or biome identity that a vanilla tag
 * also lists, such as {@code block == Blocks.RED_BED} where {@code BlockTags.BEDS} exists. Placing a block, as in
 * {@code setBlock(pos, Blocks.RED_BED.defaultBlockState(), 3)}, is not a comparison and is not reported.
 *
 * <p>The patterns live in {@value #PATTERNS_RESOURCE}, which {@code scripts/mod_fleet.py} reads too; the tag
 * memberships come from the Minecraft jar of the project, see {@link VanillaTagIndex}.
 */
public final class VanillaTagIdentityScanner {
    public static final String PATTERNS_RESOURCE = "vanilla-tag-identity-patterns.txt";

    private static final Pattern OPT_OUT = Pattern.compile("^//\\s*vanilla-tag:(.*)$");
    /** Every registry constant in a match, so a membership list reports each of its arguments. */
    private static final Pattern IDENTITY = Pattern.compile("\\b(Blocks|Items|Biomes)\\.([A-Z][A-Z0-9_]*)");

    private final List<Pattern> hits;
    private final VanillaTagIndex tags;

    /** A hit line, 1-based, with the tags to ask about; {@code missingReason} for a reasonless marker. */
    public record Finding(int line, String source, boolean missingReason, List<String> tags) {
    }

    VanillaTagIdentityScanner(List<Pattern> hits, VanillaTagIndex tags) {
        this.hits = List.copyOf(hits);
        this.tags = tags;
    }

    public static VanillaTagIdentityScanner load(VanillaTagIndex tags) {
        try (InputStream input = VanillaTagIdentityScanner.class.getResourceAsStream(PATTERNS_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing resource " + PATTERNS_RESOURCE);
            }
            return new VanillaTagIdentityScanner(PatternFile.parse(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), PATTERNS_RESOURCE, "identity"), tags);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Scans one file's lines; a {@code // vanilla-tag: <reason>} comment clears its own line and the next. */
    public List<Finding> scan(List<String> lines) {
        JavaSourceLine lexer = new JavaSourceLine();
        List<Finding> findings = new ArrayList<>();
        Boolean previousOptOut = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            lexer.split(line);
            Boolean optOut = optOut(lexer.comment());
            List<String> tags = tags(lexer.code());
            if (!tags.isEmpty()) {
                boolean reasoned = Boolean.TRUE.equals(optOut) || Boolean.TRUE.equals(previousOptOut);
                if (!reasoned) {
                    findings.add(new Finding(index + 1, line.strip(),
                            optOut != null || previousOptOut != null, tags));
                }
            }
            previousOptOut = optOut;
        }
        return findings;
    }

    private List<String> tags(String code) {
        List<String> tags = new ArrayList<>();
        for (Pattern pattern : hits) {
            Matcher matcher = pattern.matcher(code);
            while (matcher.find()) {
                Matcher identity = IDENTITY.matcher(matcher.group());
                while (identity.find()) {
                    for (VanillaTagIndex.Member member : members(identity.group(1), identity.group(2))) {
                        String tag = VanillaTagIndex.reference(member);
                        if (!tags.contains(tag)) {
                            tags.add(tag);
                        }
                    }
                }
            }
        }
        return tags;
    }

    private List<VanillaTagIndex.Member> members(String registryToken, String name) {
        String registry = switch (registryToken) {
            case "Blocks" -> "block";
            case "Items" -> "item";
            default -> "biome";
        };
        return tags.members(registry, name.toLowerCase(Locale.ROOT));
    }

    private static Boolean optOut(String comment) {
        Matcher marker = OPT_OUT.matcher(comment);
        return marker.matches() ? !marker.group(1).isBlank() : null;
    }
}
