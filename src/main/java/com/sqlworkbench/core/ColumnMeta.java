package com.sqlworkbench.core;

public record ColumnMeta(String name, String typeName, int jdbcType) {

    public ColumnMeta {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column name is required");
        }
    }
}
