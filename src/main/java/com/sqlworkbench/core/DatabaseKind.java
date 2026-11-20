package com.sqlworkbench.core;

import java.nio.file.Path;

public enum DatabaseKind {
    SQLITE("jdbc:sqlite:"),
    DUCKDB("jdbc:duckdb:");

    private final String urlPrefix;

    DatabaseKind(String urlPrefix) {
        this.urlPrefix = urlPrefix;
    }

    public String urlFor(Path file) {
        return urlPrefix + file.toAbsolutePath();
    }

    public static DatabaseKind fromFileName(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".duckdb") || lower.endsWith(".ddb")) {
            return DUCKDB;
        }
        return SQLITE;
    }
}
