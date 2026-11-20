package com.sqlworkbench.core;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

public final class Database implements AutoCloseable {

    private final ConnectionSpec spec;
    private final Connection connection;

    private Database(ConnectionSpec spec, Connection connection) {
        this.spec = spec;
        this.connection = connection;
    }

    public static Database open(ConnectionSpec spec) {
        return open(spec, false);
    }

    public static Database open(ConnectionSpec spec, boolean createIfMissing) {
        if (!createIfMissing && !Files.isRegularFile(spec.file())) {
            throw new QueryException(spec.file() + " does not exist");
        }
        try {
            return new Database(spec, DriverManager.getConnection(spec.url()));
        } catch (SQLException e) {
            throw new QueryException("could not open " + spec.file().getFileName() + ": " + e.getMessage(), e);
        }
    }

    public ConnectionSpec spec() {
        return spec;
    }

    public Connection connection() {
        return connection;
    }

    public List<SchemaReader.Table> schema() {
        try {
            return SchemaReader.read(connection);
        } catch (SQLException e) {
            throw new QueryException(e.getMessage(), e);
        }
    }

    public QueryRunner runner(AppConfig config) {
        return new QueryRunner(connection, config.pageSize(), config.maxRows(), config.queryTimeoutSeconds());
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
