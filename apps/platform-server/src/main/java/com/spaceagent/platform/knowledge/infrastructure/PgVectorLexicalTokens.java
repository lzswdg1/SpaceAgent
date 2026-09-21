package com.spaceagent.platform.knowledge.infrastructure;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;

/** Bounded local lexical encoding. CJK bigrams/Latin words, not a claim of Milvus BM25 parity. */
final class PgVectorLexicalTokens {
    private PgVectorLexicalTokens() {}

    static List<String> tokens(String text, int limit) {
        var tokens = new LinkedHashSet<String>();
        var word = new StringBuilder();
        int previousHan = -1;
        for (int point : text.toLowerCase(Locale.ROOT).codePoints().toArray()) {
            var script = Character.UnicodeScript.of(point);
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                flush(word, tokens);
                tokens.add("h" + Integer.toHexString(point));
                if (previousHan >= 0) tokens.add("b" + Integer.toHexString(previousHan) + "x" + Integer.toHexString(point));
                previousHan = point;
            } else {
                previousHan = -1;
                if (point < 128 && Character.isLetterOrDigit(point)) {
                    if (word.length() < 80) word.appendCodePoint(point);
                } else flush(word, tokens);
            }
            if (tokens.size() >= limit) break;
        }
        if (tokens.size() < limit) flush(word, tokens);
        return tokens.stream().limit(limit).toList();
    }

    private static void flush(StringBuilder word, LinkedHashSet<String> tokens) {
        if (!word.isEmpty()) { tokens.add("w" + word); word.setLength(0); }
    }
}
