package com.sqlworkbench.bench;

import com.sqlworkbench.core.ConnectionSpec;
import com.sqlworkbench.core.Database;
import com.sqlworkbench.core.PagedReader;
import com.sqlworkbench.core.QueryException;
import com.sqlworkbench.core.QueryRunner;
import com.sqlworkbench.core.ResultPage;

import java.io.IOException;
import java.io.Writer;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class Benchmark {

    private static final int PAGE_SIZE = 200;
    private static final long ROW_CAP = 200_000;
    private static final int REPEATS = 5;

    private Benchmark() {
    }

    public static void main(String[] args) throws Exception {
        long rows = args.length > 0 ? Long.parseLong(args[0]) : 2_000_000L;
        Path dir = Files.createTempDirectory("workbench-bench");
        Runtime runtime = Runtime.getRuntime();
        System.out.printf(Locale.ROOT, "sql workbench benchmark: %d rows, page size %d, row cap %d%n", rows, PAGE_SIZE, ROW_CAP);
        System.out.printf(Locale.ROOT, "java %s on %s, %d cores, max heap %d MB%n%n",
                System.getProperty("java.version"), System.getProperty("os.name"),
                runtime.availableProcessors(), runtime.maxMemory() / (1024 * 1024));
        try {
            engine("sqlite", dir.resolve("bench.db"), rows, sqliteBuild(rows),
                    "with recursive c(x) as (select 1 union all select x + 1 from c where x < 2000000000) select count(*) from c");
            engine("duckdb", dir.resolve("bench.duckdb"), rows, duckdbBuild(rows),
                    "select sum(a.range * b.range) from range(300000) a, range(300000) b");
            gridMemory(dir.resolve("bench.db"), rows);
        } finally {
            deleteTree(dir);
        }
    }

    private static List<String> sqliteBuild(long rows) {
        return List.of(
                "create table orders (id integer primary key, customer text, region text, amount real, created text)",
                "insert into orders with recursive r(i) as (select 1 union all select i + 1 from r where i < " + rows + ") "
                        + "select i, 'customer' || (i % 50000), "
                        + "case i % 4 when 0 then 'north' when 1 then 'south' when 2 then 'east' else 'west' end, "
                        + "((i * 7919) % 100000) / 100.0, date('2024-01-01', '+' || (i % 730) || ' days') from r");
    }

    private static List<String> duckdbBuild(long rows) {
        return List.of(
                "create table orders as select i as id, concat('customer', i % 50000) as customer, "
                        + "case i % 4 when 0 then 'north' when 1 then 'south' when 2 then 'east' else 'west' end as region, "
                        + "((i * 7919) % 100000) / 100.0 as amount, date '2024-01-01' + cast(i % 730 as integer) as created "
                        + "from range(1, " + (rows + 1) + ") t(i)");
    }

    private static void engine(String name, Path file, long rows, List<String> build, String longQuery) throws Exception {
        try (Database database = Database.open(ConnectionSpec.of(file), true)) {
            Connection connection = database.connection();
            long started = System.nanoTime();
            try (Statement statement = connection.createStatement()) {
                for (String sql : build) {
                    statement.execute(sql);
                }
            }
            report(name, "build " + rows + " row table", seconds(System.nanoTime() - started));

            QueryRunner runner = new QueryRunner(connection, PAGE_SIZE, ROW_CAP);
            report(name, "first page, select *", medianFirstPage(runner, "select * from orders"));
            report(name, "first page, order by amount desc", medianFirstPage(runner, "select * from orders order by amount desc"));
            report(name, "first page, group by region", medianFirstPage(runner,
                    "select region, count(*), avg(amount) from orders group by region"));

            Path csv = file.resolveSibling(name + ".csv");
            long exportStarted = System.nanoTime();
            long written;
            try (Writer out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                written = runner.exportCsv("select * from orders", out);
            }
            long exportNanos = System.nanoTime() - exportStarted;
            long bytes = Files.size(csv);
            Files.delete(csv);
            report(name, "export every row to csv", String.format(Locale.ROOT, "%d rows in %s, %,.0f rows/s, %d MB",
                    written, seconds(exportNanos), written / (exportNanos / 1e9), bytes / (1024 * 1024)));

            report(name, "cancel latency on a long query", medianCancel(runner, longQuery));
        }
    }

    private static String medianFirstPage(QueryRunner runner, String sql) {
        firstPageMillis(runner, sql);
        double[] samples = new double[REPEATS];
        for (int i = 0; i < REPEATS; i++) {
            samples[i] = firstPageMillis(runner, sql);
        }
        return median(samples);
    }

    private static double firstPageMillis(QueryRunner runner, String sql) {
        long started = System.nanoTime();
        QueryRunner.Rows rows = (QueryRunner.Rows) runner.run(sql);
        try (PagedReader reader = rows.reader()) {
            ResultPage page = reader.nextPage();
            double millis = (System.nanoTime() - started) / 1e6;
            if (page.isEmpty()) {
                throw new IllegalStateException("no rows came back for " + sql);
            }
            return millis;
        }
    }

    private static String medianCancel(QueryRunner runner, String sql) throws InterruptedException {
        double[] samples = new double[REPEATS];
        for (int i = 0; i < REPEATS; i++) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread query = new Thread(() -> {
                try {
                    runner.run(sql);
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            query.setDaemon(true);
            query.start();
            Thread.sleep(500);
            long cancelled = System.nanoTime();
            runner.cancel();
            query.join(30000);
            if (query.isAlive()) {
                return "query was still running 30 s after cancel";
            }
            if (!(failure.get() instanceof QueryException)) {
                return "query ended without being cancelled";
            }
            samples[i] = (System.nanoTime() - cancelled) / 1e6;
        }
        return median(samples);
    }

    private static void gridMemory(Path sqliteFile, long rows) {
        try (Database database = Database.open(ConnectionSpec.of(sqliteFile))) {
            report("grid", "heap held at the " + ROW_CAP + " row cap", megabytes(heldByGrid(database, ROW_CAP)));
            report("grid", "heap held with all " + rows + " rows", megabytes(heldByGrid(database, rows)));
        }
    }

    private static long heldByGrid(Database database, long cap) {
        QueryRunner runner = new QueryRunner(database.connection(), PAGE_SIZE, cap);
        long before = usedHeap();
        List<List<String>> grid = new ArrayList<>();
        QueryRunner.Rows rows = (QueryRunner.Rows) runner.run("select * from orders");
        try (PagedReader reader = rows.reader()) {
            while (reader.hasMore()) {
                grid.addAll(reader.nextPage().rows());
            }
        }
        long held = usedHeap() - before;
        Reference.reachabilityFence(grid);
        return held;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String median(double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return String.format(Locale.ROOT, "%.1f ms (median of %d)", sorted[sorted.length / 2], sorted.length);
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.2f s", nanos / 1e9);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void report(String engine, String step, String result) {
        System.out.printf(Locale.ROOT, "%-7s %-38s %s%n", engine, step, result);
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
