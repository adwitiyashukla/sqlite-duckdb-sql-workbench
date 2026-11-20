package com.sqlworkbench.core;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

public final class CsvExporter {

    private CsvExporter() {
    }

    public static long write(RowCursor cursor, Writer out) throws IOException {
        List<ColumnMeta> columns = cursor.columns();
        writeRow(out, columns.stream().map(ColumnMeta::name).toList());
        long written = 0;
        while (cursor.next()) {
            List<String> row = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                row.add(ValueFormatter.format(cursor.value(i)));
            }
            writeRow(out, row);
            written++;
        }
        out.flush();
        return written;
    }

    public static long write(ResultPage page, Writer out) throws IOException {
        writeRow(out, page.columns().stream().map(ColumnMeta::name).toList());
        for (List<String> row : page.rows()) {
            writeRow(out, row);
        }
        out.flush();
        return page.size();
    }

    static void writeRow(Writer out, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(values.get(i)));
        }
        out.write("\r\n");
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
