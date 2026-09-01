package fr.neamar.kiss.preference;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.utils.BackupRestoreProgress;
import fr.neamar.kiss.utils.ClipboardUtils;
import fr.neamar.kiss.utils.Log;

public class ExportSettingsPreference {

    private static final String TAG = ExportSettingsPreference.class.getSimpleName();

    /**
     * Legacy clipboard export remains available for callers that still use the old dialog path.
     */
    public void onDialogClosed(Context context, boolean positiveResult) {
        if (positiveResult) exportSettings(context, null);
    }

    /**
     * Save the same verified settings payload to a user-selected Storage Access Framework Uri.
     */
    public void backupToUri(Context context, Uri destination) {
        if (destination == null) {
            Toast.makeText(context, "No backup destination selected.", Toast.LENGTH_SHORT).show();
            return;
        }
        exportSettings(context, destination);
    }

    private void exportSettings(Context context, @Nullable Uri destination) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Get default values from XML, to only write changed data.
        SharedPreferences defaultValues = context.getSharedPreferences("__default__", Context.MODE_PRIVATE);
        PreferenceManager.setDefaultValues(context, "__default__", Context.MODE_PRIVATE, R.xml.preferences, true);
        JSONObject out = new JSONObject();
        BackupRestoreProgress progress = null;
        try {
            // Min version required to read those settings.
            out.put("__v", 223);

            Set<String> keys = new HashSet<>();
            keys.addAll(defaultValues.getAll().keySet());
            keys.addAll(prefs.getAll().keySet());

            Map<String, String> tags = ((KissApplication) context.getApplicationContext())
                    .getDataHandler().getTagsHandler().getTags();
            Map<String, ComponentName> components = DBHelper.getCustomComponents(context);
            progress = BackupRestoreProgress.backup(
                    context, keys.size() + tags.size() + components.size() + 3);

            // Export settings.
            Map<String, ?> allPrefs = prefs.getAll();
            for (String key : keys) {
                Object value = allPrefs.get(key);
                if (value instanceof Boolean) {
                    if (defaultValues.contains(key)) {
                        boolean defaultValue = defaultValues.getBoolean(key, true);
                        boolean currentValue = prefs.getBoolean(key, defaultValue);
                        if (currentValue != defaultValue) out.put(key, currentValue);
                    } else {
                        out.put(key, value);
                    }
                } else if (value instanceof String) {
                    if (defaultValues.contains(key)) {
                        String defaultValue = defaultValues.getString(key, "");
                        String currentValue = prefs.getString(key, defaultValue);
                        if (!currentValue.equals(defaultValue)) out.put(key, currentValue);
                    } else {
                        out.put(key, value);
                    }
                } else if (value instanceof Set) {
                    if (defaultValues.contains(key)) {
                        Set<String> defaultValue = defaultValues.getStringSet(key, new HashSet<>());
                        Set<String> currentValue = prefs.getStringSet(key, new HashSet<>());
                        if (!currentValue.equals(defaultValue)) out.put(key, new JSONArray(currentValue));
                    } else {
                        out.put(key, new JSONArray((Set<?>) value));
                    }
                } else if (value instanceof Integer || value instanceof Long
                        || value instanceof Float || value instanceof Double) {
                    // Preserve numeric custom preferences too; the previous clipboard exporter skipped them.
                    out.put(key, value);
                } else if (value != null) {
                    Log.w(TAG, "Unknown type: " + key + ":" + value);
                }
                progress.step();
            }

            // Export tags.
            JSONObject jsonTags = new JSONObject();
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                jsonTags.put(entry.getKey(), entry.getValue());
                progress.step();
            }
            out.put("__tags", jsonTags);
            progress.step();

            // Export custom components.
            JSONArray jsonComponents = new JSONArray();
            for (Map.Entry<String, ComponentName> entry : components.entrySet()) {
                JSONObject jsonComponent = new JSONObject();
                jsonComponent.put("id", entry.getKey());
                jsonComponent.put("package", entry.getValue().getPackageName());
                jsonComponent.put("class", entry.getValue().getClassName());
                jsonComponents.put(jsonComponent);
                progress.step();
            }
            out.put("__custom_components", jsonComponents);
            progress.step();

            if (destination == null) {
                ClipboardUtils.setClipboard(context, "kiss", out.toString());
            } else {
                try (OutputStream stream = context.getContentResolver().openOutputStream(destination, "wt")) {
                    if (stream == null) throw new IOException("Unable to open backup destination");
                    stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
                    stream.flush();
                }
            }
            progress.step();
            progress.complete();

            if (destination == null) {
                Toast.makeText(context, R.string.export_settings_done, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Backup saved.", Toast.LENGTH_SHORT).show();
            }
        } catch (JSONException | IOException | SecurityException e) {
            if (progress != null) progress.fail();
            Log.e(TAG, "Unable to back up settings", e);
            Toast.makeText(context, R.string.export_settings_error, Toast.LENGTH_SHORT).show();
        } finally {
            defaultValues.edit().clear().apply();
        }
    }
}
