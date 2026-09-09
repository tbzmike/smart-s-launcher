from pathlib import Path

base_path = Path('.github/scripts/apply_33081_sqlite_corruption_recovery.py')
source = base_path.read_text()


def replace_once(old: str, new: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(f'v2 source guard expected exactly one match, found {count}: {old[:120]!r}')
    source = source.replace(old, new, 1)


# Correct the exact verified 3.30.80 version comment.
replace_once(
    'Smart S Launcher 3.30.80 - Home crash guards and visible-only scroll animation work',
    'Smart S Launcher 3.30.80 - Home crash guard and scroll stability')

# The first guarded discovery run proved that six classes, not three, directly open kiss.s3db.
replace_once(
"""TIMELINE = ROOT / 'app/src/main/java/fr/neamar/kiss/db/NotificationTimelineStore.java'\nDB = ROOT / 'app/src/main/java/fr/neamar/kiss/db/DB.java'""",
"""TIMELINE = ROOT / 'app/src/main/java/fr/neamar/kiss/db/NotificationTimelineStore.java'\nLAUNCH_HISTORY = ROOT / 'app/src/main/java/fr/neamar/kiss/db/LaunchHistoryStatsStore.java'\nLAUNCH_STATS = ROOT / 'app/src/main/java/fr/neamar/kiss/db/LaunchStatsProvider.java'\nHISTORY_USAGE = ROOT / 'app/src/main/java/fr/neamar/kiss/db/HistoryItemUsageTodayStore.java'\nDB = ROOT / 'app/src/main/java/fr/neamar/kiss/db/DB.java'""")
replace_once(
"""expected = sorted([str(DBHELPER), str(SMART), str(TIMELINE)])""",
"""expected = sorted([str(DBHELPER), str(SMART), str(TIMELINE), str(LAUNCH_HISTORY), str(LAUNCH_STATS), str(HISTORY_USAGE)])""")

extra_patch = r"""
# Three additional direct openers were discovered by the guarded baseline scan. They are read-only
# statistics/usage helpers, but an independent handle could still retain the corrupt file after the
# main stores recover it, so they must use the same owner and retry boundary.
replace_once(LAUNCH_HISTORY,
'''        SQLiteDatabase db = new DB(context.getApplicationContext()).getReadableDatabase();''',
'''        SQLiteDatabase db = recoveryDb;''')
replace_once(LAUNCH_HISTORY,
'''        db.close();\n        return result;''',
'''        return result;''')
wrap_methods(LAUNCH_HISTORY, {'getAll'}, set(), '__no_direct_getter__')

replace_once(LAUNCH_STATS,
'''        DB helper = new DB(context.getApplicationContext());\n        try {\n            SQLiteDatabase db = helper.getReadableDatabase();\n            String sql = "SELECT record, MAX(timeStamp), "\n                    + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), COUNT(*) "\n                    + "FROM history GROUP BY record";\n            try (Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(startOfToday)})) {\n                while (cursor.moveToNext()) {\n                    stats.put(cursor.getString(0), new LaunchStats(\n                            cursor.getLong(1), cursor.getInt(2), cursor.getInt(3)));\n                }\n            }\n        } finally {\n            helper.close();\n        }\n        return stats;''',
'''        SQLiteDatabase db = recoveryDb;\n        String sql = "SELECT record, MAX(timeStamp), "\n                + "SUM(CASE WHEN timeStamp >= ? THEN 1 ELSE 0 END), COUNT(*) "\n                + "FROM history GROUP BY record";\n        try (Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(startOfToday)})) {\n            while (cursor.moveToNext()) {\n                stats.put(cursor.getString(0), new LaunchStats(\n                        cursor.getLong(1), cursor.getInt(2), cursor.getInt(3)));\n            }\n        }\n        return stats;''')
wrap_methods(LAUNCH_STATS, {'loadAll'}, set(), '__no_direct_getter__')

replace_once(HISTORY_USAGE,
'''        android.database.sqlite.SQLiteDatabase db =\n                new DB(context.getApplicationContext()).getReadableDatabase();''',
'''        android.database.sqlite.SQLiteDatabase db = DatabaseRecovery.getDatabase(context);''')
# getToday is the recovery boundary for its private history query. The Android UsageStats work is
# repeated only after a real DB recovery, which is an exceptional path rather than normal runtime.
wrap_methods(HISTORY_USAGE, {'getToday'}, set(), '__no_direct_getter__')

"""
replace_once(
    '# No independent DB constructors may remain outside the single recovery owner.\n',
    extra_patch + '# No independent DB constructors may remain outside the single recovery owner.\n')

# Execute the fully guarded combined patch in this checkout without rewriting the retained v1 file.
namespace = {'__name__': '__main__', '__file__': str(base_path)}
exec(compile(source, str(base_path), 'exec'), namespace)

# Method-body wrapping preserves original indentation verbatim, including whitespace-only blank
# lines. Normalize trailing whitespace after the guarded transformation so the generated Java is
# diff-clean without changing any executable source text.
for path in [
    Path('app/src/main/java/fr/neamar/kiss/db/DBHelper.java'),
    Path('app/src/main/java/fr/neamar/kiss/db/SmartStateStore.java'),
    Path('app/src/main/java/fr/neamar/kiss/db/NotificationTimelineStore.java'),
    Path('app/src/main/java/fr/neamar/kiss/db/LaunchHistoryStatsStore.java'),
    Path('app/src/main/java/fr/neamar/kiss/db/LaunchStatsProvider.java'),
    Path('app/src/main/java/fr/neamar/kiss/db/HistoryItemUsageTodayStore.java'),
]:
    lines = path.read_text().splitlines()
    path.write_text('\n'.join(line.rstrip() for line in lines) + '\n')
