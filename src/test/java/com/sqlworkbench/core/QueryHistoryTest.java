package com.sqlworkbench.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryHistoryTest {

    private static Path historyFile() throws Exception {
        return Files.createTempDirectory("workbench-history").resolve("nested").resolve("history.tsv");
    }

    @Test
    void startsEmpty() throws Exception {
        assertEquals(List.of(), new QueryHistory(historyFile(), 10).recent());
    }

    @Test
    void keepsNewestEntriesWithinLimit() throws Exception {
        QueryHistory history = new QueryHistory(historyFile(), 3);
        history.add(new QueryHistory.Entry(Instant.parse("2026-09-22T10:00:00Z"), "select 1", 5, 1, true));
        history.add(new QueryHistory.Entry(Instant.parse("2026-09-22T10:01:00Z"),
                "select *\nfrom t\twhere a = 'x'", 12, 40, true));
        history.add(new QueryHistory.Entry(Instant.parse("2026-09-22T10:02:00Z"), "bad sql", 1, 0, false));
        history.add(new QueryHistory.Entry(Instant.parse("2026-09-22T10:03:00Z"), "select 4", 2, 1, true));

        List<QueryHistory.Entry> recent = history.recent();
        assertEquals(3, recent.size());
        assertEquals("select 4", recent.get(0).sql());
        assertEquals(Instant.parse("2026-09-22T10:03:00Z"), recent.get(0).at());
        assertFalse(recent.get(1).ok());
        assertEquals("select *\nfrom t\twhere a = 'x'", recent.get(2).sql());
        assertEquals(12L, recent.get(2).millis());
        assertEquals(40L, recent.get(2).rows());
    }

    @Test
    void clearsHistory() throws Exception {
        QueryHistory history = new QueryHistory(historyFile(), 5);
        history.add(new QueryHistory.Entry(Instant.now(), "select 1", 1, 1, true));
        history.clear();
        assertEquals(List.of(), history.recent());
    }

    @Test
    void encodesControlCharacters() {
        String sql = "a\\b\nc\td";
        assertEquals(sql, QueryHistory.decode(QueryHistory.encode(sql)));
    }

    @Test
    void rejectsBadArguments() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new QueryHistory(null, 5));
        assertThrows(IllegalArgumentException.class, () -> new QueryHistory(historyFile(), 0));
    }
}
