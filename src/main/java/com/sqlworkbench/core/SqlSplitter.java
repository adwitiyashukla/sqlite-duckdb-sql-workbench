package com.sqlworkbench.core;

import java.util.ArrayList;
import java.util.List;

public final class SqlSplitter {

    private SqlSplitter() {
    }

    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = script.length();
        while (i < n) {
            char c = script.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = closingQuote(script, i, c);
                current.append(script, i, end);
                i = end;
            } else if (c == '[') {
                int close = script.indexOf(']', i + 1);
                int end = close < 0 ? n : close + 1;
                current.append(script, i, end);
                i = end;
            } else if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                current.append(' ');
                i = lineEnd(script, i);
            } else if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int close = script.indexOf("*/", i + 2);
                current.append(' ');
                i = close < 0 ? n : close + 2;
            } else if (c == ';') {
                flush(statements, current);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        flush(statements, current);
        return statements;
    }

    static int closingQuote(String script, int start, char quote) {
        int i = start + 1;
        int n = script.length();
        while (i < n) {
            char c = script.charAt(i);
            if (c == quote) {
                if (i + 1 < n && script.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static int lineEnd(String script, int start) {
        int i = script.indexOf('\n', start);
        return i < 0 ? script.length() : i;
    }

    private static void flush(List<String> statements, StringBuilder current) {
        String text = current.toString().trim();
        if (!text.isEmpty()) {
            statements.add(text);
        }
        current.setLength(0);
    }
}
