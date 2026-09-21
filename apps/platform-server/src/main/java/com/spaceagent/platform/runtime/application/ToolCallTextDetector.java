package com.spaceagent.platform.runtime.application;

import java.util.regex.Pattern;

/** Detects protocol-shaped answer text, never parses arguments or authorizes execution. */
final class ToolCallTextDetector {
    private static final Pattern CALL = Pattern.compile(
            "(?is)<(?:invoke\\b[^>]*\\bname\\s*=|function_calls\\s*>|tool_call\\s*>)");
    private ToolCallTextDetector() { }

    static boolean containsCall(String content) {
        if (content == null || content.isBlank()) return false;
        StringBuilder visible = new StringBuilder();
        char fence = 0;
        int fenceSize = 0;
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            char first = trimmed.isEmpty() ? 0 : trimmed.charAt(0);
            int run = 0;
            if (first == '`' || first == '~') {
                while (run < trimmed.length() && trimmed.charAt(run) == first) run++;
            }
            if (fence != 0) {
                if (first == fence && run >= fenceSize && trimmed.substring(run).isBlank()) fence = 0;
                continue;
            }
            if (run >= 3) { fence = first; fenceSize = run; continue; }
            if (line.startsWith("    ") || line.startsWith("\t")) continue;
            // Skip complete inline-code spans, retaining malformed/unclosed text for validation.
            for (int i = 0; i < line.length();) {
                if (line.charAt(i) != '`') { visible.append(line.charAt(i++)); continue; }
                int end = i;
                while (end < line.length() && line.charAt(end) == '`') end++;
                String delimiter = line.substring(i, end);
                int closing = line.indexOf(delimiter, end);
                if (closing < 0) { visible.append(delimiter); i = end; }
                else { visible.append(' '); i = closing + delimiter.length(); }
            }
            visible.append('\n');
        }
        return CALL.matcher(visible).find();
    }
}
