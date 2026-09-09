package fr.neamar.kiss.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseCorruptException;

import androidx.annotation.NonNull;

import java.io.File;

import fr.neamar.kiss.utils.Log;

/**
 * Owns the single process-wide connection to kiss.s3db and performs one controlled rebuild when
 * Android reports SQLITE_CORRUPT. Normal operations do not run integrity scans or extra queries.
 */
final class DatabaseRecovery {
    private static final String TAG = DatabaseRecovery.class.getSimpleName();
    private static final Object LOCK = new Object();

    private static volatile SQLiteDatabase database;
    private static volatile long recoveryGeneration;

    private DatabaseRecovery() { }

    @FunctionalInterface
    interface Operation<T> {
        T run(SQLiteDatabase database);
    }

    @FunctionalInterface
    interface VoidOperation {
        void run(SQLiteDatabase database);
    }

    static <T> T run(@NonNull Context context, @NonNull Operation<T> operation) {
        SQLiteDatabase attemptedDatabase = null;
        long observedGeneration = recoveryGeneration;
        try {
            attemptedDatabase = getDatabase(context);
            return operation.run(attemptedDatabase);
        } catch (SQLiteDatabaseCorruptException corruption) {
            recover(context, attemptedDatabase, corruption);
            // Exactly one retry. If a fresh database also reports corruption, propagate that second
            // exception instead of looping forever or hiding an underlying storage failure.
            return operation.run(getDatabase(context));
        } catch (RuntimeException failure) {
            // A concurrent recovery may close the old handle while another DB caller is in-flight.
            // Retry that caller once only when a recovery generation actually changed underneath it.
            if (observedGeneration != recoveryGeneration) {
                return operation.run(getDatabase(context));
            }
            throw failure;
        }
    }

    static void runVoid(@NonNull Context context, @NonNull VoidOperation operation) {
        run(context, database -> {
            operation.run(database);
            return null;
        });
    }

    static SQLiteDatabase getDatabase(@NonNull Context context) {
        SQLiteDatabase local = database;
        if (local != null && local.isOpen()) return local;

        synchronized (LOCK) {
            local = database;
            if (local == null || !local.isOpen()) {
                Context appContext = context.getApplicationContext();
                database = new DB(appContext).getWritableDatabase();
            }
            return database;
        }
    }

    private static void recover(@NonNull Context context, SQLiteDatabase attemptedDatabase,
                                @NonNull SQLiteDatabaseCorruptException corruption) {
        synchronized (LOCK) {
            SQLiteDatabase current = database;
            if (attemptedDatabase != null && current != null && current != attemptedDatabase
                    && current.isOpen()) {
                // Another thread already replaced the corrupt handle while this caller unwound.
                return;
            }

            database = null;
            closeQuietly(current);
            if (attemptedDatabase != current) closeQuietly(attemptedDatabase);

            Context appContext = context.getApplicationContext();
            File main = appContext.getDatabasePath(DB.DB_NAME);
            appContext.deleteDatabase(DB.DB_NAME);
            // Context.deleteDatabase() normally removes sidecars as well. Remove leftovers explicitly
            // so a stale WAL/SHM cannot poison the new schema on vendor SQLite implementations.
            deleteIfPresent(new File(main.getPath() + "-wal"));
            deleteIfPresent(new File(main.getPath() + "-shm"));
            deleteIfPresent(new File(main.getPath() + "-journal"));

            if (main.exists()) {
                Log.e(TAG, "Unable to remove corrupt database " + main, corruption);
                throw corruption;
            }

            recoveryGeneration++;
            SmartStateStore.onDatabaseRecovered();
            Log.e(TAG, "Corrupt Smart S database removed; rebuilding a clean schema", corruption);
        }
    }

    private static void closeQuietly(SQLiteDatabase database) {
        if (database == null) return;
        try {
            if (database.isOpen()) database.close();
        } catch (RuntimeException closeFailure) {
            Log.w(TAG, "Unable to close corrupt database handle", closeFailure);
        }
    }

    private static void deleteIfPresent(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to remove SQLite sidecar " + file);
        }
    }
}
