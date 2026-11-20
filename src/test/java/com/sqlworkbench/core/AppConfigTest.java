package com.sqlworkbench.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    private static Path write(String contents) throws Exception {
        Path file = Files.createTempDirectory("workbench-config").resolve("workbench.properties");
        Files.writeString(file, contents);
        return file;
    }

    @Test
    void suppliesDefaults() {
        AppConfig config = AppConfig.defaults();
        assertEquals(200, config.pageSize());
        assertEquals(200000L, config.maxRows());
        assertEquals(200, config.historyLimit());
        assertEquals(0, config.queryTimeoutSeconds());
        assertTrue(config.historyFile().endsWith(Path.of(".sqlworkbench", "history.tsv")));
    }

    @Test
    void fallsBackWhenFileIsMissing() throws Exception {
        assertEquals(AppConfig.defaults(), AppConfig.load(null));
        assertEquals(AppConfig.defaults(), AppConfig.load(Path.of("no", "such", "file.properties")));
    }

    @Test
    void readsEveryTunable() throws Exception {
        Path file = write("""
                page.size=50
                result.max.rows=1000
                history.limit=10
                query.timeout.seconds=30
                history.file=/tmp/workbench-test-history.tsv
                """);
        AppConfig config = AppConfig.load(file);
        assertEquals(50, config.pageSize());
        assertEquals(1000L, config.maxRows());
        assertEquals(10, config.historyLimit());
        assertEquals(30, config.queryTimeoutSeconds());
        assertEquals(Path.of("/tmp/workbench-test-history.tsv"), config.historyFile());
    }

    @Test
    void keepsDefaultsForMissingKeys() throws Exception {
        AppConfig config = AppConfig.load(write("page.size=25\n"));
        assertEquals(25, config.pageSize());
        assertEquals(200000L, config.maxRows());
    }

    @Test
    void rejectsOutOfRangeValues() {
        Path anywhere = Path.of("history.tsv");
        assertThrows(IllegalArgumentException.class, () -> new AppConfig(0, 100, 10, 0, anywhere));
        assertThrows(IllegalArgumentException.class, () -> new AppConfig(500, 100, 10, 0, anywhere));
        assertThrows(IllegalArgumentException.class, () -> new AppConfig(10, 100, 0, 0, anywhere));
        assertThrows(IllegalArgumentException.class, () -> new AppConfig(10, 100, 10, -1, anywhere));
        assertThrows(IllegalArgumentException.class, () -> new AppConfig(10, 100, 10, 0, null));
    }

    @Test
    void rejectsNonNumericValues() throws Exception {
        Path file = write("page.size=lots\n");
        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file));
    }
}
