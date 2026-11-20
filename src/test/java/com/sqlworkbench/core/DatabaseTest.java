package com.sqlworkbench.core;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    private static final String LONG_QUERY =
            "with recursive c(x) as (select 1 union all select x + 1 from c where x < 500000000) select count(*) from c";

    private static Connection openWithOrders(int rows) throws Exception {
        Path dir = Files.createTempDirectory("workbench-sqlite");
        ConnectionSpec spec = ConnectionSpec.of(dir.resolve("shop.db"));
        Connection connection = DriverManager.getConnection(spec.url());
        try (Statement statement = connection.createStatement()) {
            statement.execute("create table orders (id integer primary key, customer text, total real)");
            statement.execute("create view v_large as select * from orders where total > 100");
            for (int i = 1; i <= rows; i++) {
                statement.execute("insert into orders values (" + i + ", 'customer" + i + "', " + (i * 10.0) + ")");
            }
            statement.execute("insert into orders values (" + (rows + 1) + ", null, null)");
        }
        return connection;
    }

    @Test
    void readsTablesViewsAndColumns() throws Exception {
        try (Connection connection = openWithOrders(3)) {
            List<SchemaReader.Table> tables = SchemaReader.read(connection);
            assertEquals(2, tables.size());

            SchemaReader.Table orders = tables.get(0);
            assertEquals("orders", orders.name());
            assertFalse(orders.isView());
            assertEquals(List.of("id", "customer", "total"), orders.columns().stream().map(SchemaReader.Column::name).toList());

            SchemaReader.Table view = tables.get(1);
            assertEquals("v_large", view.name());
            assertTrue(view.isView());
        }
    }

    @Test
    void runsQueryAndPagesResults() throws Exception {
        try (Connection connection = openWithOrders(25)) {
            QueryRunner runner = new QueryRunner(connection, 10, 1000);
            QueryRunner.Outcome outcome = runner.run("select id, customer from orders order by id");
            QueryRunner.Rows rows = assertInstanceOf(QueryRunner.Rows.class, outcome);

            try (PagedReader reader = rows.reader()) {
                assertEquals(List.of("id", "customer"), reader.columns().stream().map(ColumnMeta::name).toList());

                ResultPage first = reader.nextPage();
                assertEquals(10, first.size());
                assertEquals("1", first.rows().get(0).get(0));
                assertEquals("customer1", first.rows().get(0).get(1));
                assertTrue(reader.hasMore());

                reader.nextPage();
                ResultPage third = reader.nextPage();
                assertEquals(6, third.size());
                assertEquals("NULL", third.rows().get(5).get(1));
                assertEquals(26L, reader.rowsRead());
            }
        }
    }

    @Test
    void appliesRowCap() throws Exception {
        try (Connection connection = openWithOrders(50)) {
            QueryRunner runner = new QueryRunner(connection, 10, 15);
            QueryRunner.Rows rows = assertInstanceOf(QueryRunner.Rows.class,
                    runner.run("select id from orders order by id"));
            try (PagedReader reader = rows.reader()) {
                reader.nextPage();
                ResultPage second = reader.nextPage();
                assertEquals(5, second.size());
                assertTrue(reader.wasCapped());
                assertFalse(reader.hasMore());
            }
        }
    }

    @Test
    void reportsUpdateCounts() throws Exception {
        try (Connection connection = openWithOrders(5)) {
            QueryRunner runner = new QueryRunner(connection, 10, 1000);
            QueryRunner.Updated updated = assertInstanceOf(QueryRunner.Updated.class,
                    runner.run("update orders set customer = 'renamed' where id <= 3"));
            assertEquals(3, updated.count());
        }
    }

    @Test
    void reportsSqlErrors() throws Exception {
        try (Connection connection = openWithOrders(1)) {
            QueryRunner runner = new QueryRunner(connection, 10, 1000);
            assertThrows(QueryException.class, () -> runner.run("select * from missing_table"));
        }
    }

    @Test
    void exportsResultsToCsv() throws Exception {
        try (Connection connection = openWithOrders(2)) {
            QueryRunner runner = new QueryRunner(connection, 10, 1000);
            QueryRunner.Rows rows = assertInstanceOf(QueryRunner.Rows.class,
                    runner.run("select id, customer from orders order by id limit 2"));
            StringWriter out = new StringWriter();
            try (PagedReader reader = rows.reader()) {
                ResultPage page = reader.nextPage();
                CsvExporter.write(page, out);
            }
            assertEquals("id,customer\r\n1,customer1\r\n2,customer2\r\n", out.toString());
        }
    }

    @Test
    void opensExistingFilesOnly() throws Exception {
        Path dir = Files.createTempDirectory("workbench-open");
        ConnectionSpec missing = ConnectionSpec.of(dir.resolve("missing.db"));
        assertThrows(QueryException.class, () -> Database.open(missing));

        try (Database created = Database.open(missing, true)) {
            assertTrue(Files.exists(missing.file()));
            assertEquals(List.of(), created.schema());
        }
        try (Database reopened = Database.open(missing)) {
            assertEquals("missing.db (sqlite)", reopened.spec().label());
        }
    }

    @Test
    void exportStreamsEveryRowPastTheCap() throws Exception {
        try (Connection connection = openWithOrders(40)) {
            QueryRunner runner = new QueryRunner(connection, 5, 10);
            StringWriter out = new StringWriter();
            long written = runner.exportCsv("select id from orders order by id", out);
            assertEquals(41L, written);
            assertEquals(42, out.toString().split("\r\n").length);
            assertThrows(QueryException.class, () -> runner.exportCsv("update orders set total = 0", new StringWriter()));
        }
    }

    @Test
    void cancelStopsLongQuery() throws Exception {
        try (Connection connection = openWithOrders(1)) {
            QueryRunner runner = new QueryRunner(connection, 10, 1000);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    runner.run(LONG_QUERY);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            worker.start();
            Thread.sleep(300);
            long deadline = System.currentTimeMillis() + 15000;
            while (worker.isAlive() && System.currentTimeMillis() < deadline) {
                runner.cancel();
                worker.join(100);
            }
            assertFalse(worker.isAlive());
            QueryException error = assertInstanceOf(QueryException.class, failure.get());
            assertEquals("query cancelled", error.getMessage());
            assertTrue(runner.wasCancelled());
        }
    }

    @Test
    void timeoutStopsLongQuery() throws Exception {
        try (Connection connection = openWithOrders(1)) {
            QueryRunner runner = new QueryRunner(connection, 10, 1000, 1);
            long started = System.nanoTime();
            QueryException error = assertThrows(QueryException.class, () -> runner.run(LONG_QUERY));
            double seconds = (System.nanoTime() - started) / 1e9;
            assertEquals("query timed out after 1 s", error.getMessage());
            assertTrue(seconds < 5, "took " + seconds + " s");
            assertFalse(runner.wasCancelled());

            QueryRunner.Rows rows = assertInstanceOf(QueryRunner.Rows.class, runner.run("select count(*) from orders"));
            rows.reader().close();
        }
    }
}
