package com.sqlworkbench.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionSpecTest {

    @Test
    void picksKindFromExtension() {
        assertEquals(DatabaseKind.SQLITE, DatabaseKind.fromFileName("shop.db"));
        assertEquals(DatabaseKind.SQLITE, DatabaseKind.fromFileName("Shop.SQLITE"));
        assertEquals(DatabaseKind.DUCKDB, DatabaseKind.fromFileName("warehouse.duckdb"));
        assertEquals(DatabaseKind.DUCKDB, DatabaseKind.fromFileName("warehouse.DDB"));
    }

    @Test
    void buildsJdbcUrls() {
        assertEquals("jdbc:sqlite:" + Path.of("/data/shop.db").toAbsolutePath(),
                ConnectionSpec.of(Path.of("/data/shop.db")).url());
        assertEquals("jdbc:duckdb:" + Path.of("/data/warehouse.duckdb").toAbsolutePath(),
                ConnectionSpec.of(Path.of("/data/warehouse.duckdb")).url());
    }

    @Test
    void labelsTheFile() {
        assertEquals("shop.db (sqlite)", ConnectionSpec.of(Path.of("/data/shop.db")).label());
    }

    @Test
    void rejectsMissingParts() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionSpec(null, Path.of("x")));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionSpec(DatabaseKind.SQLITE, null));
    }
}
