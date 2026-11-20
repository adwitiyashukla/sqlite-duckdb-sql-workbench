package com.sqlworkbench.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SqlTokens {

    public enum Kind {
        KEYWORD, STRING, NUMBER, COMMENT
    }

    public record Token(Kind kind, int start, int end) {
    }

    private static final Set<String> KEYWORDS = Set.of(
            "add", "all", "alter", "and", "as", "asc", "attach", "autoincrement", "avg", "begin", "between",
            "bigint", "blob", "boolean", "by", "case", "cast", "check", "collate", "column", "commit",
            "constraint", "copy", "count", "create", "cross", "current", "date", "default", "delete", "desc",
            "describe", "detach", "distinct", "double", "drop", "else", "end", "escape", "except", "exists",
            "explain", "false", "filter", "following", "foreign", "from", "full", "glob", "group", "having", "if",
            "in", "index", "inner", "insert", "int", "integer", "intersect", "into", "is", "join", "key", "left",
            "like", "limit", "max", "min", "natural", "not", "null", "offset", "on", "or", "order", "outer",
            "over", "partition", "pragma", "preceding", "primary", "range", "real", "recursive", "references",
            "returning", "right", "rollback", "row", "rows", "select", "set", "show", "sum", "summarize", "table",
            "text", "then", "timestamp", "transaction", "true", "unbounded", "union", "unique", "update", "using",
            "vacuum", "values", "varchar", "view", "when", "where", "window", "with");

    private static final Set<String> READ_STARTS = Set.of(
            "select", "with", "values", "pragma", "table", "show", "describe", "explain", "summarize", "from");

    private static final Set<String> WRITES = Set.of(
            "insert", "update", "delete", "create", "drop", "alter", "attach", "detach", "vacuum", "copy");

    private SqlTokens() {
    }

    public static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                int newline = text.indexOf('\n', i);
                int end = newline < 0 ? n : newline;
                tokens.add(new Token(Kind.COMMENT, i, end));
                i = end;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                tokens.add(new Token(Kind.COMMENT, i, end));
                i = end;
            } else if (c == '\'') {
                int end = SqlSplitter.closingQuote(text, i, '\'');
                tokens.add(new Token(Kind.STRING, i, end));
                i = end;
            } else if (c == '"' || c == '`') {
                i = SqlSplitter.closingQuote(text, i, c);
            } else if (c == '[') {
                int close = text.indexOf(']', i + 1);
                i = close < 0 ? n : close + 1;
            } else if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < n && (Character.isDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                    end++;
                }
                tokens.add(new Token(Kind.NUMBER, i, end));
                i = end;
            } else if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && isWordPart(text.charAt(end))) {
                    end++;
                }
                if (KEYWORDS.contains(text.substring(i, end).toLowerCase(Locale.ROOT))) {
                    tokens.add(new Token(Kind.KEYWORD, i, end));
                }
                i = end;
            } else {
                i++;
            }
        }
        return tokens;
    }

    public static boolean isReadOnly(String sql) {
        List<String> words = new ArrayList<>();
        for (Token token : tokenize(sql)) {
            if (token.kind() == Kind.KEYWORD) {
                words.add(sql.substring(token.start(), token.end()).toLowerCase(Locale.ROOT));
            }
        }
        if (words.isEmpty() || !READ_STARTS.contains(words.get(0))) {
            return false;
        }
        for (String word : words) {
            if (WRITES.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
