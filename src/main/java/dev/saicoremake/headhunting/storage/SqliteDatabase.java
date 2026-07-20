package dev.saicoremake.headhunting.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SqliteDatabase implements AutoCloseable {
    private final Path path;
    private final Logger logger;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Connection connection;

    public SqliteDatabase(Path path, Logger logger) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "headhunting-database");
            thread.setDaemon(false);
            return thread;
        });
    }

    public CompletableFuture<Void> start() {
        return submit(connection -> null);
    }

    <T> CompletableFuture<T> submit(SqlFunction<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.apply(connection());
            } catch (SQLException exception) {
                throw new DatabaseException("Database operation failed", exception);
            }
        }, executor);
    }

    private Connection connection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return connection;
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = FULL");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA wal_autocheckpoint = 1000");
        }
        DatabaseMigrations.migrate(connection);
        return connection;
    }

    public CompletableFuture<Void> closeAsync() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            if (connection == null) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                connection.close();
            } catch (SQLException exception) {
                throw new DatabaseException("Could not close database", exception);
            }
        }, executor);
        return future.whenComplete((ignored, failure) -> executor.shutdown());
    }

    @Override
    public void close() {
        try {
            closeAsync().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while closing the database", exception);
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Could not close the database cleanly", exception);
        } finally {
            executor.shutdownNow();
        }
    }
}
