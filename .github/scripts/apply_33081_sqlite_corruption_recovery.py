from pathlib import Path
import re

ROOT = Path('.')
DBHELPER = ROOT / 'app/src/main/java/fr/neamar/kiss/db/DBHelper.java'
SMART = ROOT / 'app/src/main/java/fr/neamar/kiss/db/SmartStateStore.java'
TIMELINE = ROOT / 'app/src/main/java/fr/neamar/kiss/db/NotificationTimelineStore.java'
DB = ROOT / 'app/src/main/java/fr/neamar/kiss/db/DB.java'
RECOVERY = ROOT / 'app/src/main/java/fr/neamar/kiss/db/DatabaseRecovery.java'
BUILD = ROOT / 'app/build.gradle'


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, found {count}: {old[:120]!r}')
    path.write_text(text.replace(old, new, 1))


def matching_brace(text: str, opening: int) -> int:
    depth = 0
    i = opening
    state = 'code'
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ''
        if state == 'code':
            if c == '"':
                state = 'string'
            elif c == "'":
                state = 'char'
            elif c == '/' and n == '/':
                state = 'line_comment'; i += 1
            elif c == '/' and n == '*':
                state = 'block_comment'; i += 1
            elif c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    return i
        elif state == 'string':
            if c == '\\':
                i += 1
            elif c == '"':
                state = 'code'
        elif state == 'char':
            if c == '\\':
                i += 1
            elif c == "'":
                state = 'code'
        elif state == 'line_comment':
            if c == '\n':
                state = 'code'
        elif state == 'block_comment':
            if c == '*' and n == '/':
                state = 'code'; i += 1
        i += 1
    raise SystemExit('unmatched Java method brace')


def method_ranges(text: str, name: str):
    pattern = re.compile(r'public\s+static\s+[^;{]+?\b' + re.escape(name) + r'\s*\(')
    ranges = []
    for match in pattern.finditer(text):
        opening = text.find('{', match.end())
        if opening < 0:
            raise SystemExit(f'{name}: method opening brace not found')
        closing = matching_brace(text, opening)
        ranges.append((match.start(), opening, closing))
    return ranges


def wrap_methods(path: Path, method_names, void_names, db_getter: str) -> None:
    text = path.read_text()
    targets = []
    for name in method_names:
        found = method_ranges(text, name)
        if not found:
            raise SystemExit(f'{path}: public static method {name} not found')
        for start, opening, closing in found:
            targets.append((start, opening, closing, name))

    # Work from the end so offsets stay valid. Overloads are intentionally all wrapped.
    for start, opening, closing, name in sorted(targets, reverse=True):
        body = text[opening + 1:closing]
        if 'DatabaseRecovery.run' in body:
            continue
        body = body.replace(db_getter, 'recoveryDb')
        if name in void_names:
            replacement = '{\n        DatabaseRecovery.runVoid(context, recoveryDb -> {' + body + '\n        });\n    }'
        else:
            replacement = '{\n        return DatabaseRecovery.run(context, recoveryDb -> {' + body + '\n        });\n    }'
        text = text[:opening] + replacement + text[closing + 1:]
    path.write_text(text)


# Assert the actual 3.30.80 architecture before changing it. This prevents silently missing a
# fourth independent SQLiteOpenHelper connection added elsewhere in the source tree.
initial_new_db = []
for path in (ROOT / 'app/src/main/java').rglob('*.java'):
    count = path.read_text().count('new DB(')
    if count:
        initial_new_db.extend([str(path)] * count)
expected = sorted([str(DBHELPER), str(SMART), str(TIMELINE)])
if sorted(initial_new_db) != expected:
    raise SystemExit(f'unexpected direct DB constructors: {initial_new_db}; expected {expected}')

replace_once(DB,
             '    private final static String DB_NAME = "kiss.s3db";',
             '    static final String DB_NAME = "kiss.s3db";')

recovery_source = r'''package fr.neamar.kiss.db;

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
'''
if RECOVERY.exists():
    raise SystemExit(f'{RECOVERY} already exists on 3.30.80 baseline')
RECOVERY.write_text(recovery_source)

# DBHelper is the legacy history/tags/shortcuts/custom-component facade.
replace_once(DBHELPER,
'''    private static volatile SQLiteDatabase database = null;\n\n    private DBHelper() {\n    }\n\n    private static SQLiteDatabase getDatabase(Context context) {\n        if (database == null) {\n            synchronized (DBHelper.class) {\n                if (database == null) {\n                    database = new DB(context).getReadableDatabase();\n                }\n            }\n        }\n        return database;\n    }''',
'''    private DBHelper() {\n    }\n\n    private static SQLiteDatabase getDatabase(Context context) {\n        return DatabaseRecovery.getDatabase(context);\n    }''')

# initDatabase has no database statements of its own; opening it inside runVoid still gives the open
# itself the same corruption recovery semantics.
replace_once(DBHELPER,
'''    public static void initDatabase(Context context) {\n        getDatabase(context);\n    }''',
'''    public static void initDatabase(Context context) {\n        DatabaseRecovery.runVoid(context, recoveryDb -> { });\n    }''')

dbhelper_methods = {
    'insertHistory', 'removeFromHistory', 'clearHistory', 'getHistory', 'getHistoryLength',
    'getPreviousResultsForQuery', 'insertShortcut', 'removeShortcut', 'addCustomAppName',
    'removeCustomAppIcon', 'removeCustomAppName', 'getCustomAppData', 'getShortcuts',
    'removeShortcuts', 'removeAllShortcuts', 'insertTagsForId', 'deleteTagsForId', 'deleteTags',
    'loadTags', 'getCustomComponents', 'setCustomComponent', 'removeAllCustomComponents'
}
dbhelper_void = {
    'insertHistory', 'removeFromHistory', 'clearHistory', 'addCustomAppName', 'removeCustomAppName',
    'removeShortcuts', 'removeAllShortcuts', 'insertTagsForId', 'deleteTagsForId', 'deleteTags',
    'setCustomComponent', 'removeAllCustomComponents'
}
wrap_methods(DBHELPER, dbhelper_methods, dbhelper_void, 'getDatabase(context)')

# Smart state used to own a second connection to the same file. It now delegates to the one recovery
# owner and clears its derived notification cache when a corrupt database is rebuilt.
replace_once(SMART,
'''    private static volatile SQLiteDatabase database;\n    private static final Object LATEST_NOTIFICATION_LOCK = new Object();''',
'''    private static final Object LATEST_NOTIFICATION_LOCK = new Object();''')
replace_once(SMART,
'''    private SmartStateStore() {}\n\n    private static SQLiteDatabase db(Context context) {\n        if (database == null) {\n            synchronized (SmartStateStore.class) {\n                if (database == null) database = new DB(context.getApplicationContext()).getWritableDatabase();\n            }\n        }\n        return database;\n    }''',
'''    private SmartStateStore() {}\n\n    static void onDatabaseRecovered() {\n        latestNotificationsCache = null;\n    }\n\n    private static SQLiteDatabase db(Context context) {\n        return DatabaseRecovery.getDatabase(context);\n    }''')
smart_methods = {
    'rememberApp', 'forgetPackage', 'getRememberedApps', 'getNotificationApps',
    'queryLatestNotificationsByPackage', 'saveNotification', 'updateNotificationRoute'
}
smart_void = {'rememberApp', 'forgetPackage', 'saveNotification', 'updateNotificationRoute'}
wrap_methods(SMART, smart_methods, smart_void, 'db(context)')

# Wrap only the full queryNotifications overload; the short overload delegates to it and therefore
# must not create nested recovery scopes.
text = SMART.read_text()
ranges = method_ranges(text, 'queryNotifications')
if len(ranges) != 2:
    raise SystemExit(f'{SMART}: expected two queryNotifications overloads, found {len(ranges)}')
# The later overload is the five-argument implementation in the verified source.
start, opening, closing = ranges[-1]
body = text[opening + 1:closing].replace('db(context)', 'recoveryDb')
replacement = '{\n        return DatabaseRecovery.run(context, recoveryDb -> {' + body + '\n        });\n    }'
SMART.write_text(text[:opening] + replacement + text[closing + 1:])

# NotificationTimelineStore was a third cached SQLiteOpenHelper connection.
replace_once(TIMELINE,
'''    private static volatile SQLiteDatabase database;\n\n    private NotificationTimelineStore() { }\n\n    private static SQLiteDatabase db(Context context) {\n        if (database == null) {\n            synchronized (NotificationTimelineStore.class) {\n                if (database == null) {\n                    database = new DB(context.getApplicationContext()).getReadableDatabase();\n                }\n            }\n        }\n        return database;\n    }''',
'''    private NotificationTimelineStore() { }\n\n    private static SQLiteDatabase db(Context context) {\n        return DatabaseRecovery.getDatabase(context);\n    }''')
wrap_methods(TIMELINE, {'findLatest', 'findByDbId', 'queryAfter'}, set(), 'db(context)')

# No independent DB constructors may remain outside the single recovery owner.
remaining = []
for path in (ROOT / 'app/src/main/java').rglob('*.java'):
    count = path.read_text().count('new DB(')
    if count:
        remaining.extend([str(path)] * count)
if remaining != [str(RECOVERY)]:
    raise SystemExit(f'direct DB construction escaped consolidation: {remaining}')

replace_once(BUILD,
'''        // Smart S Launcher 3.30.80 - Home crash guards and visible-only scroll animation work\n        versionCode 508\n        versionName "3.30.80"''',
'''        // Smart S Launcher 3.30.81 - SQLite corruption recovery and launcher crash hardening\n        versionCode 509\n        versionName "3.30.81"''')

print('3.30.81 SQLite corruption recovery patch applied')
