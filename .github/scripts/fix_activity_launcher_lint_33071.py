from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "app/src/main/java/fr/neamar/kiss/activitylauncher/ActivityLauncherActivity.java"

text = PATH.read_text(encoding="utf-8")
old = '''    private void handleDocumentSelection(Intent result, boolean folder) {
        Uri uri = result.getData();
        if (uri == null) return;
        int takeFlags = result.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException ignored) { }

        String label = folder ? folderLabel(uri) : documentLabel(uri);
'''
new = '''    private void handleDocumentSelection(Intent result, boolean folder) {
        Uri uri = result.getData();
        if (uri == null) return;
        int grantFlags = result.getFlags();
        boolean canRead = (grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean canWrite = (grantFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        try {
            if (canRead && canWrite) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else if (canRead) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (canWrite) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (SecurityException ignored) { }

        String label = folder ? folderLabel(uri) : documentLabel(uri);
'''

count = text.count(old)
if count != 1:
    raise SystemExit(f"expected exactly one persistable-permission block, found {count}")

PATH.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Activity Launcher persistable URI permission lint fix applied")
