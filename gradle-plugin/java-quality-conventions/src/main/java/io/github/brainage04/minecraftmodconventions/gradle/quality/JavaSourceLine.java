package io.github.brainage04.minecraftmodconventions.gradle.quality;

/** Splits one Java source line into its code and its {@code //} comment, blanking literal contents. */
final class JavaSourceLine {
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

    String code() {
        return code;
    }

    String comment() {
        return comment;
    }
}
