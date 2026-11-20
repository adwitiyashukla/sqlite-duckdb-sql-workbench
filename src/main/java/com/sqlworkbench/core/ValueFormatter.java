package com.sqlworkbench.core;

import java.sql.Blob;
import java.sql.SQLException;

public final class ValueFormatter {

    public static final String NULL_TEXT = "NULL";

    private ValueFormatter() {
    }

    public static String format(Object value) {
        return switch (value) {
            case null -> NULL_TEXT;
            case String s -> s;
            case byte[] bytes -> "BLOB " + bytes.length + " bytes";
            case Blob blob -> "BLOB " + blobLength(blob) + " bytes";
            default -> String.valueOf(value);
        };
    }

    private static String blobLength(Blob blob) {
        try {
            return String.valueOf(blob.length());
        } catch (SQLException e) {
            return "unknown";
        }
    }
}
