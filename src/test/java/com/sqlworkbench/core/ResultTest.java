package com.sqlworkbench.core;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    static final class ListCursor implements RowCursor {

        private final List<ColumnMeta> columns;
        private final List<Object[]> data;
        private int position = -1;
        boolean closed;

        ListCursor(List<String> names, List<Object[]> data) {
            List<ColumnMeta> metas = new ArrayList<>();
            for (String name : names) {
                metas.add(new ColumnMeta(name, "TEXT", 12));
            }
            this.columns = List.copyOf(metas);
            this.data = data;
        }

        @Override
        public List<ColumnMeta> columns() {
            return columns;
        }

        @Override
        public boolean next() {
            position++;
            return position < data.size();
        }

        @Override
        public Object value(int columnIndex) {
            return data.get(position)[columnIndex];
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static ListCursor counting(int rows) {
        List<Object[]> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Object[]{i});
        }
        return new ListCursor(List.of("n"), data);
    }

    @Test
    void formatsValues() {
        assertEquals("NULL", ValueFormatter.format(null));
        assertEquals("hello", ValueFormatter.format("hello"));
        assertEquals("", ValueFormatter.format(""));
        assertEquals("42", ValueFormatter.format(42));
        assertEquals("true", ValueFormatter.format(Boolean.TRUE));
        assertEquals("BLOB 3 bytes", ValueFormatter.format(new byte[]{1, 2, 3}));
    }

    @Test
    void escapesCsvFields() {
        assertEquals("abc", CsvExporter.escape("abc"));
        assertEquals("\"a,b\"", CsvExporter.escape("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\""));
        assertEquals("\"a\nb\"", CsvExporter.escape("a\nb"));
        assertEquals("", CsvExporter.escape(null));
    }

    @Test
    void writesCsvWithHeader() throws Exception {
        ListCursor cursor = new ListCursor(List.of("id", "note"),
                new ArrayList<>(List.of(
                        new Object[]{1, "plain"},
                        new Object[]{2, "with,comma"},
                        new Object[]{3, null})));
        StringWriter out = new StringWriter();
        assertEquals(3L, CsvExporter.write(cursor, out));
        assertEquals("id,note\r\n1,plain\r\n2,\"with,comma\"\r\n3,NULL\r\n", out.toString());
    }

    @Test
    void readsPagesUntilExhausted() {
        ListCursor cursor = counting(7);
        PagedReader reader = new PagedReader(cursor, 3, 1000);

        ResultPage first = reader.nextPage();
        assertEquals(3, first.size());
        assertEquals(0L, first.firstRowNumber());
        assertTrue(reader.hasMore());

        reader.nextPage();
        ResultPage third = reader.nextPage();
        assertEquals(1, third.size());
        assertEquals(6L, third.firstRowNumber());
        assertFalse(reader.hasMore());
        assertEquals(7L, reader.rowsRead());

        reader.close();
        assertTrue(cursor.closed);
    }

    @Test
    void stopsAtRowCap() {
        PagedReader reader = new PagedReader(counting(20), 4, 6);
        reader.nextPage();
        ResultPage second = reader.nextPage();

        assertEquals(2, second.size());
        assertTrue(second.cappedHere());
        assertTrue(reader.wasCapped());
        assertFalse(reader.hasMore());
        assertEquals(6L, reader.rowsRead());
    }

    @Test
    void rejectsBadPageSettings() {
        assertThrows(IllegalArgumentException.class, () -> new PagedReader(counting(1), 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new PagedReader(counting(1), 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new PagedReader(null, 10, 10));
    }
}
