package com.sqlworkbench.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record AppConfig(int pageSize, long maxRows, int historyLimit, int queryTimeoutSeconds, Path historyFile) {

    public static final String PAGE_SIZE = "page.size";
    public static final String MAX_ROWS = "result.max.rows";
    public static final String HISTORY_LIMIT = "history.limit";
    public static final String QUERY_TIMEOUT = "query.timeout.seconds";
    public static final String HISTORY_FILE = "history.file";

    public AppConfig {
        require(pageSize >= 1 && pageSize <= 100000, PAGE_SIZE + " must be between 1 and 100000");
        require(maxRows >= 1, MAX_ROWS + " must be at least 1");
        require(maxRows >= pageSize, MAX_ROWS + " must not be smaller than " + PAGE_SIZE);
        require(historyLimit >= 1 && historyLimit <= 100000, HISTORY_LIMIT + " must be between 1 and 100000");
        require(queryTimeoutSeconds >= 0, QUERY_TIMEOUT + " must not be negative");
        require(historyFile != null, HISTORY_FILE + " must be set");
    }

    public static AppConfig defaults() {
        return new AppConfig(200, 200000L, 200, 0, defaultHistoryFile());
    }

    public static AppConfig load(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return defaults();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return from(properties);
    }

    static AppConfig from(Properties properties) {
        AppConfig base = defaults();
        String history = properties.getProperty(HISTORY_FILE);
        return new AppConfig(
                intValue(properties, PAGE_SIZE, base.pageSize()),
                longValue(properties, MAX_ROWS, base.maxRows()),
                intValue(properties, HISTORY_LIMIT, base.historyLimit()),
                intValue(properties, QUERY_TIMEOUT, base.queryTimeoutSeconds()),
                history == null || history.isBlank() ? base.historyFile() : Path.of(history.trim()));
    }

    static Path defaultHistoryFile() {
        return Path.of(System.getProperty("user.home"), ".sqlworkbench", "history.tsv");
    }

    private static int intValue(Properties properties, String key, int fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, found " + raw.trim());
        }
    }

    private static long longValue(Properties properties, String key, long fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, found " + raw.trim());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
