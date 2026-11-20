package com.sqlworkbench.core;

import java.nio.file.Path;

public record ConnectionSpec(DatabaseKind kind, Path file) {

    public ConnectionSpec {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (file == null) {
            throw new IllegalArgumentException("file is required");
        }
    }

    public static ConnectionSpec of(Path file) {
        return new ConnectionSpec(DatabaseKind.fromFileName(file.getFileName().toString()), file);
    }

    public String url() {
        return kind.urlFor(file);
    }

    public String label() {
        return file.getFileName() + " (" + kind.name().toLowerCase() + ")";
    }
}
