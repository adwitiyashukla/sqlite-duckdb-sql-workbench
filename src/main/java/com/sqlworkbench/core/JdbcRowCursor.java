package com.sqlworkbench.core;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class JdbcRowCursor implements RowCursor {

    private final ResultSet resultSet;
    private final Statement owned;
    private final List<ColumnMeta> columns;

    public JdbcRowCursor(ResultSet resultSet, Statement owned) throws SQLException {
        this.resultSet = resultSet;
        this.owned = owned;
        this.columns = readColumns(resultSet.getMetaData());
    }

    private static List<ColumnMeta> readColumns(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<ColumnMeta> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String label = meta.getColumnLabel(i);
            if (label == null || label.isBlank()) {
                label = meta.getColumnName(i);
            }
            if (label == null || label.isBlank()) {
                label = "column" + i;
            }
            list.add(new ColumnMeta(label, meta.getColumnTypeName(i), meta.getColumnType(i)));
        }
        return List.copyOf(list);
    }

    @Override
    public List<ColumnMeta> columns() {
        return columns;
    }

    @Override
    public boolean next() {
        try {
            return resultSet.next();
        } catch (SQLException e) {
            throw new QueryException(e.getMessage(), e);
        }
    }

    @Override
    public Object value(int columnIndex) {
        try {
            return resultSet.getObject(columnIndex + 1);
        } catch (SQLException e) {
            throw new QueryException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            resultSet.close();
        } catch (SQLException ignored) {
        }
        if (owned != null) {
            try {
                owned.close();
            } catch (SQLException ignored) {
            }
        }
    }
}
