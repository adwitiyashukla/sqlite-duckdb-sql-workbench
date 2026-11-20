package com.sqlworkbench.core;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SchemaReader {

    public record Column(String name, String typeName, boolean nullable) {
    }

    public record Table(String name, String type, List<Column> columns) {

        public Table {
            columns = List.copyOf(columns);
        }

        public boolean isView() {
            return "VIEW".equalsIgnoreCase(type);
        }
    }

    private SchemaReader() {
    }

    public static List<Table> read(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<Table> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name == null || name.startsWith("sqlite_")) {
                    continue;
                }
                String type = rs.getString("TABLE_TYPE");
                tables.add(new Table(name, type == null ? "TABLE" : type, readColumns(meta, name)));
            }
        }
        tables.sort(Comparator.comparing(Table::type).thenComparing(Table::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(tables);
    }

    private static List<Column> readColumns(DatabaseMetaData meta, String table) throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                columns.add(new Column(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"))));
            }
        }
        return columns;
    }
}
