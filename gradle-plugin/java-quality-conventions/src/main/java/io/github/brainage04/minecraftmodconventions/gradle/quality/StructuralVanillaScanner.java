package io.github.brainage04.minecraftmodconventions.gradle.quality;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prototype of the three quality checks over the compiler's own syntax trees instead of regular expressions.
 *
 * <p>The checks are the same as {@link VanillaIdentityScanner}, {@link VanillaTagIdentityScanner} and
 * {@link WorldHeightScanner}: a comparison with a vanilla dimension identity, with one block, item or biome that a
 * vanilla tag names, and with a literal overworld build limit. A syntax tree answers them structurally, so a shape that
 * spans several lines is seen as one expression and the contents of comments and string literals can never match.
 *
 * <p>{@link #scan(List, VanillaTagIndex)} parses every source in one javac task, which is the cheapest way to ask the
 * compiler for many files at once: {@code ToolProvider.getSystemJavaCompiler()} is part of the JDK Gradle already runs
 * on, so the check stays offline and adds no dependency.
 */
public final class StructuralVanillaScanner {
    private static final Pattern LEVEL_KEY = Pattern.compile(
            "(?:(?:[a-z_]\\w*\\.)*(?:(?:Server|Client)?Level|BuiltinDimensionTypes)\\.(?:OVERWORLD|NETHER|END|OVERWORLD_CAVES)"
                    + "|(?:OVERWORLD|NETHER|END))");
    private static final Pattern IDENTITY_CONSTANT = Pattern.compile(
            "(?:[a-z_]\\w*\\.)*(?:Blocks|Items|Biomes)\\.[A-Z][A-Z0-9_]*");
    private static final Pattern ANY_CONSTANT = Pattern.compile("\\b(Blocks|Items|Biomes)\\.([A-Z][A-Z0-9_]*)");
    private static final Pattern DIMENSION_CALL = Pattern.compile("\\.?dimension(?:Type|TypeRegistration)?\\(\\s*\\)$");
    private static final Pattern NUMBER = Pattern.compile("-64|319|320|0|256");
    private static final Pattern MODERN = Pattern.compile("-64|319|320");
    private static final Pattern HEIGHT_NAME = Pattern.compile(
            "(?:[a-z_]\\w*\\.)*(?:minY|maxY|minBuildHeight|maxBuildHeight|worldHeight|buildHeight|heightLimit|yMin|yMax"
                    + "|bottomY|topY|MIN_Y|MAX_Y|MIN_BUILD_HEIGHT|MAX_BUILD_HEIGHT|WORLD_HEIGHT)");
    private static final Pattern WEAK_Y = Pattern.compile(
            "(?:[A-Za-z_$][\\w$]*\\.)*(?:getY|getMinY|getMaxY|getHeight|getMinBuildHeight|getMaxBuildHeight)\\(\\)"
                    + "|(?:y|Y|blockY|posY|yPos|sectionY|chunkY|yLevel|yMin|yMax|minY|maxY|bottomY|topY)");

    /** Which check a finding belongs to. */
    public enum Check {
        IDENTITY("vanilla-identity"),
        TAG("vanilla-tag"),
        HEIGHT("world-height");

        private final String marker;

        Check(String marker) {
            this.marker = marker;
        }

        /** The opt-out marker name, {@code // <marker>: <reason>}. */
        public String marker() {
            return marker;
        }
    }

    /** One parsed source: its name for reports, its text and the directory the report path is relative to. */
    public record Source(String name, String content) {
    }

    /** A hit line, 1-based, with the tags to ask about for the tag check. */
    public record Finding(Check check, String path, int line, String source, List<String> tags) {
    }

    private StructuralVanillaScanner() {
    }

    /** Scans the sources with all three checks; {@code tags} may be null, which skips the tag membership filter. */
    public static List<Finding> scan(List<Source> sources, VanillaTagIndex tags) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler; the structural check needs a JDK");
        }
        List<JavaFileObject> files = new ArrayList<>();
        for (Source source : sources) {
            files.add(new SourceFile(source.name(), source.content()));
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) compiler.getTask(
                null, null, diagnostics, List.of("-proc:none"), null, files);
        SourcePositions positions = Trees.instance(task).getSourcePositions();
        List<Finding> findings = new ArrayList<>();
        for (CompilationUnitTree unit : task.parse()) {
            String content = sources.get(indexOf(sources, unit)).content();
            new Visitor(unit, positions, content, tags, findings).scan(unit, null);
        }
        return findings;
    }

    /** A {@code // <check>: <reason>} comment on the line or the line above silences a finding. */
    public static boolean suppressed(Check check, List<String> lines, int line) {
        Pattern marker = Pattern.compile("//\\s*" + check.marker() + ":(.*)$");
        for (int index : new int[] {line - 1, line - 2}) {
            if (index >= 0 && index < lines.size()) {
                Matcher found = marker.matcher(lines.get(index));
                if (found.find() && !found.group(1).isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int indexOf(List<Source> sources, CompilationUnitTree unit) {
        String name = unit.getSourceFile().getName();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).name().equals(name)) {
                return i;
            }
        }
        return 0;
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String name;
        private final String content;

        SourceFile(String name, String content) {
            super(URI.create("string:///" + name.replace('\\', '/')), Kind.SOURCE);
            this.name = name;
            this.content = content;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }

    private static final class Visitor extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String content;
        private final VanillaTagIndex tags;
        private final List<Finding> findings;

        Visitor(CompilationUnitTree unit, SourcePositions positions, String content, VanillaTagIndex tags,
                List<Finding> findings) {
            this.unit = unit;
            this.positions = positions;
            this.content = content;
            this.tags = tags;
            this.findings = findings;
        }

        @Override
        public Void visitBinary(BinaryTree tree, Void unused) {
            Tree.Kind kind = tree.getKind();
            if (kind == Tree.Kind.EQUAL_TO || kind == Tree.Kind.NOT_EQUAL_TO) {
                String left = text(tree.getLeftOperand());
                String right = text(tree.getRightOperand());
                if (matchesKey(left) || matchesKey(right)) {
                    add(Check.IDENTITY, tree, List.of());
                } else if (matchesIdentity(left) || matchesIdentity(right)) {
                    add(Check.TAG, tree, tagsIn(text(tree)));
                } else if (isHeightComparison(left, right)) {
                    add(Check.HEIGHT, tree, List.of());
                }
            } else if (kind == Tree.Kind.LESS_THAN || kind == Tree.Kind.LESS_THAN_EQUAL
                    || kind == Tree.Kind.GREATER_THAN || kind == Tree.Kind.GREATER_THAN_EQUAL) {
                if (isHeightComparison(text(tree.getLeftOperand()), text(tree.getRightOperand()))) {
                    add(Check.HEIGHT, tree, List.of());
                }
            }
            return super.visitBinary(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
            String name = selectorName(tree);
            String receiver = receiver(tree);
            List<String> arguments = arguments(tree);
            boolean keyArgument = arguments.stream().anyMatch(StructuralVanillaScanner::matchesKey);
            if (("equals".equals(name) || "is".equals(name)) && (matchesKey(receiver) || keyArgument)) {
                add(Check.IDENTITY, tree, List.of());
            }
            if (keyArgument && arguments.stream().anyMatch(argument -> DIMENSION_CALL.matcher(argument).find())) {
                add(Check.IDENTITY, tree, List.of());
            }
            if (("equals".equals(name) || "is".equals(name))
                    && (matchesIdentity(receiver) || arguments.stream().anyMatch(StructuralVanillaScanner::matchesIdentity))) {
                add(Check.TAG, tree, tagsIn(text(tree)));
            }
            if ("of".equals(name) && Set.of("Set", "List", "Stream").contains(receiver)
                    && arguments.stream().anyMatch(StructuralVanillaScanner::matchesIdentity)) {
                add(Check.TAG, tree, tagsIn(text(tree)));
            }
            if (("min".equals(name) || "max".equals(name) || "clamp".equals(name))
                    && arguments.stream().anyMatch(argument -> MODERN.matcher(argument).matches())
                    && arguments.stream().anyMatch(argument -> WEAK_Y.matcher(argument).matches())) {
                add(Check.HEIGHT, tree, List.of());
            }
            return super.visitMethodInvocation(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            if ("BlockPos".equals(text(tree.getIdentifier()))) {
                List<String> arguments = new ArrayList<>();
                tree.getArguments().forEach(argument -> arguments.add(text(argument)));
                if (arguments.size() == 3 && MODERN.matcher(arguments.get(1)).matches()) {
                    add(Check.HEIGHT, tree, List.of());
                }
            }
            return super.visitNewClass(tree, unused);
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            ExpressionTree initializer = tree.getInitializer();
            if (initializer != null && HEIGHT_NAME.matcher(tree.getName().toString()).matches()
                    && NUMBER.matcher(text(initializer)).matches()) {
                add(Check.HEIGHT, tree, List.of());
            }
            return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitAssignment(AssignmentTree tree, Void unused) {
            if (HEIGHT_NAME.matcher(text(tree.getVariable())).matches()
                    && NUMBER.matcher(text(tree.getExpression())).matches()) {
                add(Check.HEIGHT, tree, List.of());
            }
            return super.visitAssignment(tree, unused);
        }

        private boolean isHeightComparison(String left, String right) {
            boolean leftY = WEAK_Y.matcher(left).matches();
            boolean rightY = WEAK_Y.matcher(right).matches();
            boolean leftHeight = HEIGHT_NAME.matcher(left).matches();
            boolean rightHeight = HEIGHT_NAME.matcher(right).matches();
            boolean leftModern = MODERN.matcher(left).matches();
            boolean rightModern = MODERN.matcher(right).matches();
            boolean leftNumber = NUMBER.matcher(left).matches();
            boolean rightNumber = NUMBER.matcher(right).matches();
            return (leftY && rightModern) || (rightY && leftModern)
                    || (leftHeight && rightNumber) || (rightHeight && leftNumber);
        }

        private List<String> tagsIn(String expression) {
            if (tags == null) {
                return List.of();
            }
            Set<String> references = new LinkedHashSet<>();
            Matcher constant = ANY_CONSTANT.matcher(expression);
            while (constant.find()) {
                String registry = switch (constant.group(1)) {
                    case "Blocks" -> "block";
                    case "Items" -> "item";
                    default -> "biome";
                };
                for (VanillaTagIndex.Member member
                        : tags.members(registry, constant.group(2).toLowerCase(Locale.ROOT))) {
                    references.add(VanillaTagIndex.reference(member));
                }
            }
            return List.copyOf(references);
        }

        private final Set<String> seen = new LinkedHashSet<>();

        private void add(Check check, Tree tree, List<String> tagsForFinding) {
            if (check == Check.TAG && tagsForFinding.isEmpty()) {
                return;
            }
            long start = positions.getStartPosition(unit, tree);
            if (start < 0) {
                return;
            }
            int line = (int) unit.getLineMap().getLineNumber(start);
            List<String> lines = content.lines().toList();
            if (line >= 1 && line <= lines.size() && seen.add(check + ":" + line)) {
                findings.add(new Finding(check, unit.getSourceFile().getName(), line, lines.get(line - 1).strip(),
                        tagsForFinding));
            }
        }

        private String text(Tree tree) {
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start < 0 || end < 0 || end > content.length() || start > end) {
                return tree.toString();
            }
            return content.substring((int) start, (int) end);
        }

        private List<String> arguments(MethodInvocationTree tree) {
            List<String> arguments = new ArrayList<>();
            tree.getArguments().forEach(argument -> arguments.add(text(argument)));
            return arguments;
        }

        private static String selectorName(MethodInvocationTree tree) {
            ExpressionTree select = tree.getMethodSelect();
            if (select instanceof MemberSelectTree member) {
                return member.getIdentifier().toString();
            }
            return select instanceof IdentifierTree identifier ? identifier.getName().toString() : "";
        }

        private static String receiver(MethodInvocationTree tree) {
            ExpressionTree select = tree.getMethodSelect();
            return select instanceof MemberSelectTree member ? member.getExpression().toString() : "";
        }
    }

    static boolean matchesKey(String text) {
        return LEVEL_KEY.matcher(text).matches();
    }

    static boolean matchesIdentity(String text) {
        return IDENTITY_CONSTANT.matcher(text).matches();
    }
}
