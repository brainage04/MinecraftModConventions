package io.github.brainage04.minecraftmodconventions.gradle.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds calls to GameTest APIs that the game marks for removal, and the {@code @SuppressWarnings}
 * annotations that would hide them from javac.
 *
 * <p>A suppression is reported because javac cannot warn about a call it was told to ignore: a test
 * can compile clean under {@code -Werror} and still break when the API disappears. A reasoned
 * {@code // deprecated-api: <reason>} comment on the annotation's line, or the line above it, is the
 * way to keep one. Comments and string literals do not count as calls.
 */
public final class DeprecatedGameTestApiScanner {
    /** {@code GameTestHelper#makeMockServerPlayerInLevel()}, {@code @Deprecated(forRemoval = true)} in 26.3. */
    static final String MOCK_PLAYER_IN_LEVEL = "makeMockServerPlayerInLevel";
    static final String SUPPRESS_WARNINGS = "SuppressWarnings";
    static final String MARKER = "// deprecated-api: <reason>";

    private static final Pattern HIDDEN_LINT = Pattern.compile("\"(removal|deprecation)\"");
    private static final Pattern REASON = Pattern.compile("^//\\s*deprecated-api:(.*)$");
    private static final int MAX_ANNOTATION_LINES = 8;

    private DeprecatedGameTestApiScanner() {
    }

    /** A hit line, 1-based, and its source text. */
    public record Finding(int line, String source) {
    }

    /** A suppression that hides a deprecation warning, 1-based line of its annotation. */
    public record Suppression(int line, String source, boolean missingReason) {
    }

    public static List<Finding> scan(List<String> lines) {
        JavaSourceLine lexer = new JavaSourceLine();
        List<Finding> findings = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            lexer.split(line);
            if (lexer.code().contains(MOCK_PLAYER_IN_LEVEL)) {
                findings.add(new Finding(index + 1, line.strip()));
            }
        }
        return findings;
    }

    public static List<Suppression> scanSuppressions(List<String> lines) {
        JavaSourceLine lexer = new JavaSourceLine();
        List<Suppression> suppressions = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            lexer.split(lines.get(index));
            if (!lexer.code().contains(SUPPRESS_WARNINGS)) {
                continue;
            }
            StringBuilder annotation = new StringBuilder(lines.get(index));
            int depth = parens(lexer.code());
            boolean sawParen = lexer.code().indexOf('(') >= 0;
            int end = index;
            while ((!sawParen || depth > 0)
                    && end + 1 < lines.size()
                    && end - index < MAX_ANNOTATION_LINES) {
                end++;
                lexer.split(lines.get(end));
                annotation.append('\n').append(lines.get(end));
                depth += parens(lexer.code());
                sawParen = sawParen || lexer.code().indexOf('(') >= 0;
            }
            if (HIDDEN_LINT.matcher(annotation).find()) {
                suppressions.add(new Suppression(index + 1, lines.get(index).strip(), !reasoned(lines, index)));
            }
            index = end;
        }
        return suppressions;
    }

    private static int parens(String code) {
        int depth = 0;
        for (int index = 0; index < code.length(); index++) {
            char character = code.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            }
        }
        return depth;
    }

    private static boolean reasoned(List<String> lines, int index) {
        return hasReason(lines.get(index)) || (index > 0 && hasReason(lines.get(index - 1)));
    }

    private static boolean hasReason(String line) {
        int at = line.indexOf("//");
        if (at < 0) {
            return false;
        }
        Matcher matcher = REASON.matcher(line.substring(at).strip());
        return matcher.matches() && !matcher.group(1).strip().isEmpty();
    }
}
