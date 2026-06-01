package nl.kmc.storage.sqlite;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Shared async helper for all SQLite repositories. */
abstract class AsyncBase {

    protected final SQLiteDataSource dataSource;
    protected final Executor executor;

    protected AsyncBase(SQLiteDataSource dataSource, Executor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    protected <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    protected CompletableFuture<Void> asyncVoid(ThrowingRunnable task) {
        return CompletableFuture.runAsync(() -> {
            try { task.run(); }
            catch (Exception e) { throw new RuntimeException(e); }
        }, executor);
    }

    @FunctionalInterface
    protected interface ThrowingRunnable {
        void run() throws Exception;
    }
}
