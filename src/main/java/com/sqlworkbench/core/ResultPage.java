package com.sqlworkbench.core;

import java.util.List;

public record ResultPage(List<ColumnMeta> columns, List<List<String>> rows, long firstRowNumber, boolean cappedHere) {

    public ResultPage {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public int size() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
