package com.sqlworkbench.core;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class QueryRunner {

    public sealed interface Outcome {
    }

    public record Rows(PagedReader reader) implements Outcome {
    }

    public record Updated(int count) implements Outcome {
    }

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "query-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final Connection connection;
    private final int pageSize;
    private final long maxRows;
    private final int timeoutSeconds;
    private volatile Statement running;
    private volatile boolean cancelled;
    private volatile boolean timedOut;

    public QueryRunner(Connection connection, int pageSize, long maxRows) {
        this(connection, pageSize, maxRows, 0);
    }

    public QueryRunner(Connection connection, int pageSize, long maxRows, int timeoutSeconds) {
        if (connection == null) {
            throw new IllegalArgumentException("connection is required");
        }
        this.connection = connection;
        this.pageSize = pageSize;
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Outcome run(String sql) {
        cancelled = false;
        timedOut = false;
        Statement statement = null;
        ScheduledFuture<?> timer = null;
        try {
            statement = prepare();
            timer = startTimer(statement);
            if (statement.execute(sql)) {
                JdbcRowCursor cursor = new JdbcRowCursor(statement.getResultSet(), statement);
                return new Rows(new PagedReader(cursor, pageSize, maxRows));
            }
            int updated = statement.getUpdateCount();
            statement.close();
            running = null;
            return new Updated(Math.max(updated, 0));
        } catch (SQLException e) {
            closeQuietly(statement);
            running = null;
            throw failure(e);
        } finally {
            if (timer != null) {
                timer.cancel(false);
            }
        }
    }

    public long exportCsv(String sql, Writer out) throws IOException {
        Statement paging = running;
        cancelled = false;
        timedOut = false;
        Statement statement = null;
        try {
            statement = prepare();
            if (!statement.execute(sql)) {
                closeQuietly(statement);
                throw new QueryException("statement returned no rows to export");
            }
            try (JdbcRowCursor cursor = new JdbcRowCursor(statement.getResultSet(), statement)) {
                return CsvExporter.write(cursor, out);
            }
        } catch (SQLException e) {
            closeQuietly(statement);
            throw failure(e);
        } catch (QueryException e) {
            if (cancelled) {
                throw new QueryException("query cancelled", e);
            }
            throw e;
        } finally {
            running = paging;
        }
    }

    public void cancel() {
        Statement statement = running;
        if (statement == null) {
            return;
        }
        cancelled = true;
        try {
            statement.cancel();
        } catch (SQLException ignored) {
        }
    }

    public boolean wasCancelled() {
        return cancelled;
    }

    private Statement prepare() throws SQLException {
        Statement statement = connection.createStatement();
        running = statement;
        try {
            statement.setFetchSize(pageSize);
        } catch (SQLException ignored) {
        }
        return statement;
    }

    private ScheduledFuture<?> startTimer(Statement statement) {
        if (timeoutSeconds <= 0) {
            return null;
        }
        return TIMER.schedule(() -> {
            timedOut = true;
            try {
                statement.cancel();
            } catch (SQLException ignored) {
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    private QueryException failure(SQLException e) {
        if (timedOut) {
            return new QueryException("query timed out after " + timeoutSeconds + " s", e);
        }
        if (cancelled) {
            return new QueryException("query cancelled", e);
        }
        return new QueryException(e.getMessage(), e);
    }

    private static void closeQuietly(Statement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException ignored) {
        }
    }
}
