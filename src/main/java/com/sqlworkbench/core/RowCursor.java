package com.sqlworkbench.core;

import java.util.List;

public interface RowCursor extends AutoCloseable {

    List<ColumnMeta> columns();

    boolean next();

    Object value(int columnIndex);

    @Override
    void close();
}
