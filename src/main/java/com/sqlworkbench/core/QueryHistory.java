package com.sqlworkbench.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QueryHistory {

    public record Entry(Instant at, String sql, long millis, long rows, boolean ok) {
    }

    private final Path file;
    private final int limit;

    public QueryHistory(Path file, int limit) {
        if (file == null) {
            throw new IllegalArgumentException("file is required");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        this.file = file;
        this.limit = limit;
    }

    public void add(Entry entry) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = String.join("\t",
                entry.at().toString(),
                Long.toString(entry.millis()),
                Long.toString(entry.rows()),
                entry.ok() ? "ok" : "failed",
                encode(entry.sql())) + System.lineSeparator();
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public List<Entry> recent() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Entry entry = parse(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        if (entries.size() > limit) {
            entries = new ArrayList<>(entries.subList(entries.size() - limit, entries.size()));
        }
        Collections.reverse(entries);
        return List.copyOf(entries);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    private static Entry parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split("\t", 5);
        if (parts.length < 5) {
            return null;
        }
        try {
            return new Entry(
                    Instant.parse(parts[0]),
                    decode(parts[4]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    "ok".equals(parts[3]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String encode(String sql) {
        return sql.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    static String decode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '\\' -> out.append('\\');
                    default -> out.append(c).append(next);
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
