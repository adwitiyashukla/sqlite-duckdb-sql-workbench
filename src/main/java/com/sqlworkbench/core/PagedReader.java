package com.sqlworkbench.core;

import java.util.ArrayList;
import java.util.List;

public final class PagedReader implements AutoCloseable {

    private final RowCursor cursor;
    private final int pageSize;
    private final long maxRows;
    private long rowsRead;
    private boolean finished;
    private boolean capped;

    public PagedReader(RowCursor cursor, int pageSize, long maxRows) {
        if (cursor == null) {
            throw new IllegalArgumentException("cursor is required");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1");
        }
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be at least 1");
        }
        this.cursor = cursor;
        this.pageSize = pageSize;
        this.maxRows = maxRows;
    }

    public ResultPage nextPage() {
        List<ColumnMeta> columns = cursor.columns();
        int width = columns.size();
        List<List<String>> rows = new ArrayList<>();
        long start = rowsRead;
        boolean hitCap = false;
        while (rows.size() < pageSize) {
            if (rowsRead >= maxRows) {
                hitCap = true;
                break;
            }
            if (!cursor.next()) {
                finished = true;
                break;
            }
            List<String> row = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                row.add(ValueFormatter.format(cursor.value(i)));
            }
            rows.add(List.copyOf(row));
            rowsRead++;
        }
        if (hitCap) {
            finished = true;
            capped = true;
        }
        return new ResultPage(columns, rows, start, hitCap);
    }

    public boolean hasMore() {
        return !finished;
    }

    public boolean wasCapped() {
        return capped;
    }

    public long rowsRead() {
        return rowsRead;
    }

    public List<ColumnMeta> columns() {
        return cursor.columns();
    }

    @Override
    public void close() {
        cursor.close();
    }
}
