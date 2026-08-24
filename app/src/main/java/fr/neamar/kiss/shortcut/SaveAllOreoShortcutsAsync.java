package fr.neamar.kiss.shortcut;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import java.lang.ref.WeakReference;
import java.util.List;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.loader.LoadAppPojos;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.ShortcutUtil;

@RequiresApi(Build.VERSION_CODES.O)
public class SaveAllOreoShortcutsAsync extends AsyncTask<Void, Integer, Boolean> {

    private static final String TAG = SaveAllOreoShortcutsAsync.class.getSimpleName();
    private final WeakReference<Context> context;

    public SaveAllOreoShortcutsAsync(@NonNull Context context) {
        this.context = new WeakReference<>(context);
    }

    @Override
    protected Boolean doInBackground(Void... voids) {

        Context context = this.context.get();
        if (context == null) {
            cancel(true);
            return null;
        }

        List<ShortcutInfo> shortcuts;
        try {
            shortcuts = ShortcutUtil.getAllShortcuts(context);
        } catch (SecurityException e) {
            Log.e(TAG, "Unable to get all shortcuts", e);
            publishProgress(-1);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().putBoolean("first-run-shortcuts", true).apply();
            cancel(true);
            return null;
        }

        final DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean retainAll = prefs.getBoolean(LoadAppPojos.PREF_INDEX_DISABLED_APPS, true);

        boolean shortcutsUpdated = false;
        for (ShortcutInfo shortcutInfo : shortcuts) {
            if (isCancelled()) break;

            if (retainAll) {
                // Permanent shortcut catalog: remember every shortcut Android exposes while the
                // publisher is available. Do not route disabled shortcuts through
                // DataHandler.updateShortcut(), because its legacy behaviour removes disabled
                // shortcuts from the database. Frozen apps must retain their known shortcuts.
                ShortcutRecord record = ShortcutUtil.createShortcutRecord(
                        context, shortcutInfo, !shortcutInfo.isPinned());
                if (record != null) {
                    shortcutsUpdated |= DBHelper.insertShortcut(context, record);
                }
            } else if (shortcutInfo.isPinned() || !shortcutInfo.isEnabled()) {
                // Preserve the original KISS behaviour when permanent disabled/frozen indexing is
                // explicitly turned off in Settings.
                shortcutsUpdated |= dataHandler.updateShortcut(shortcutInfo, !shortcutInfo.isPinned());
            }
        }

        return shortcutsUpdated;
    }

    @Override
    protected void onProgressUpdate(Integer... progress) {
        if (progress[0] == -1) {
            Context context = this.context.get();
            if (context != null) {
                Toast.makeText(context, R.string.cant_pin_shortcut, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onPostExecute(@NonNull Boolean success) {
        if (success) {
            Log.i(TAG, "Shortcuts added to KISS");

            Context context = this.context.get();
            if (context != null) {
                KissApplication.getApplication(context).getDataHandler().reloadShortcuts();
            }
        }
    }
}
