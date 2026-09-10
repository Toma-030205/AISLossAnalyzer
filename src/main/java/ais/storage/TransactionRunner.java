package ais.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public final class TransactionRunner {

    private final SqliteDatabase database;

    public TransactionRunner(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public <T> T execute(TransactionWork<T> work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception failure) {
                rollback(connection, failure);
                if (failure instanceof StorageException storageException) {
                    throw storageException;
                }
                throw new StorageException(
                        "SQLite transaction failed and was rolled back",
                        failure);
            }
        } catch (SQLException failure) {
            throw new StorageException("could not open SQLite transaction",
                    failure);
        }
    }

    private static void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection connection) throws Exception;
    }
}
