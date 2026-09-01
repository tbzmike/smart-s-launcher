package fr.neamar.kiss.preference;

import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import fr.neamar.kiss.BuildConfig;
import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.TagsHandler;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.utils.BackupRestoreProgress;
import fr.neamar.kiss.utils.Log;

public class ImportSettingsPreference {

    private static final String TAG = ImportSettingsPreference.class.getSimpleName();

    /**
     * Legacy clipboard import remains available for callers that still use the old dialog path.
     */
    public void onDialogClosed(Context context, boolean positiveResult) {
        if (!positiveResult) return;
        try {
            ClipboardManager clipboard = ContextCompat.getSystemService(context, ClipboardManager.class);
            if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
                    || clipboard.getPrimaryClip().getItemCount() == 0) {
                Toast.makeText(context, R.string.import_settings_error, Toast.LENGTH_SHORT).show();
                return;
            }
            String clipboardText = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context).toString();
            restoreJson(context, clipboardText, false);
        } catch (NullPointerException e) {
            Log.e(TAG, "Unable to read settings from clipboard", e);
            Toast.makeText(context, R.string.import_settings_error, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Read and restore a user-selected Smart S backup file.
     */
    public void restoreFromUri(Context context, Uri source) {
        if (source == null) {
            Toast.makeText(context, "No backup file selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        try (InputStream stream = context.getContentResolver().openInputStream(source)) {
            if (stream == null) throw new IOException("Unable to open backup file");
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) text.append(buffer, 0, count);
            }
            restoreJson(context, text.toString(), true);
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Unable to read backup file", e);
            Toast.makeText(context, R.string.import_settings_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreJson(Context context, String json, boolean fromBackupFile) {
        BackupRestoreProgress progress = null;
        try {
            // Parse and validate the complete payload before mutating any local data.
            JSONObject jsonObject = new JSONObject(json);
            int minVersion = jsonObject.optInt("__v", -1);
            if (minVersion < 0) {
                Toast.makeText(context, R.string.import_settings_version_missing, Toast.LENGTH_LONG).show();
                return;
            } else if (minVersion > BuildConfig.VERSION_CODE) {
                Toast.makeText(context, R.string.import_settings_upgrade_kiss, Toast.LENGTH_LONG).show();
                return;
            }
            validatePayload(jsonObject);

            int tagCount = jsonObject.has("__tags") ? jsonObject.getJSONObject("__tags").length() : 0;
            int componentCount = jsonObject.has("__custom_components")
                    ? jsonObject.getJSONArray("__custom_components").length() : 0;
            progress = BackupRestoreProgress.restore(
                    context, jsonObject.length() + tagCount + componentCount + 6);

            // Reset preferences to defaults before applying the validated backup.
            SharedPreferences oldPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            if (!oldPrefs.edit().clear().commit()) {
                progress.fail();
                Toast.makeText(context, R.string.import_settings_save_not_possible, Toast.LENGTH_SHORT).show();
                return;
            }
            PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
            progress.step();

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            Iterator<?> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = (String) keys.next();
                if (!key.startsWith("__")) {
                    Object newValue = jsonObject.get(key);
                    Object currentValue = prefs.getAll().get(key);
                    if (newValue instanceof Boolean) {
                        if (hasMatchingType(key, currentValue, Boolean.class)) {
                            editor.putBoolean(key, (Boolean) newValue);
                        }
                    } else if (newValue instanceof String) {
                        if (hasMatchingType(key, currentValue, String.class)) {
                            editor.putString(key, (String) newValue);
                        }
                    } else if (newValue instanceof JSONArray) {
                        if (hasMatchingType(key, currentValue, Set.class)) {
                            JSONArray newValues = (JSONArray) newValue;
                            Set<String> unwrappedValues = new HashSet<>(newValues.length());
                            for (int i = 0; i < newValues.length(); i++) {
                                unwrappedValues.add(newValues.getString(i));
                            }
                            editor.putStringSet(key, unwrappedValues);
                        }
                    } else if (newValue instanceof Number) {
                        putNumericValue(editor, key, currentValue, (Number) newValue);
                    }
                }
                progress.step();
            }

            // Commit synchronously so the rest of the restore never observes half-applied preferences.
            if (!editor.commit()) {
                progress.fail();
                Toast.makeText(context, R.string.import_settings_save_not_possible, Toast.LENGTH_SHORT).show();
                return;
            }
            progress.step();

            DataHandler dataHandler = ((KissApplication) context.getApplicationContext()).getDataHandler();

            if (jsonObject.has("__tags")) {
                TagsHandler tagHandler = dataHandler.getTagsHandler();
                tagHandler.clearTags();
                JSONObject tags = jsonObject.getJSONObject("__tags");
                Iterator<?> tagKeys = tags.keys();
                while (tagKeys.hasNext()) {
                    String id = (String) tagKeys.next();
                    tagHandler.setTags(id, tags.getString(id));
                    progress.step();
                }
            }

            if (jsonObject.has("__custom_components")) {
                DBHelper.removeAllCustomComponents(context);
                JSONArray components = jsonObject.getJSONArray("__custom_components");
                for (int i = 0; i < components.length(); i++) {
                    JSONObject component = components.getJSONObject(i);
                    String id = component.getString("id");
                    String pkg = component.getString("package");
                    String cls = component.getString("class");
                    if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(pkg) && !TextUtils.isEmpty(cls)) {
                        DBHelper.setCustomComponent(context, id, new ComponentName(pkg, cls));
                    }
                    progress.step();
                }
            }

            // Reload exactly the providers affected by restored settings.
            dataHandler.reloadApps();
            progress.step();
            dataHandler.reloadShortcuts();
            progress.step();
            dataHandler.reloadSearchProvider();
            progress.step();
            dataHandler.reloadContactsProvider();
            progress.step();

            progress.complete();
            Toast.makeText(context,
                    fromBackupFile ? "Backup restored." : context.getString(R.string.import_settings_done),
                    Toast.LENGTH_SHORT).show();
        } catch (JSONException | NullPointerException | ClassCastException e) {
            if (progress != null) progress.fail();
            Log.e(TAG, "Unable to restore preferences", e);
            Toast.makeText(context, R.string.import_settings_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void validatePayload(JSONObject jsonObject) throws JSONException {
        Iterator<?> keys = jsonObject.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            Object value = jsonObject.get(key);
            if ("__v".equals(key)) {
                if (!(value instanceof Number)) throw new JSONException("Invalid backup version");
            } else if ("__tags".equals(key)) {
                JSONObject tags = jsonObject.getJSONObject(key);
                Iterator<?> tagKeys = tags.keys();
                while (tagKeys.hasNext()) tags.getString((String) tagKeys.next());
            } else if ("__custom_components".equals(key)) {
                JSONArray components = jsonObject.getJSONArray(key);
                for (int i = 0; i < components.length(); i++) {
                    JSONObject component = components.getJSONObject(i);
                    component.getString("id");
                    component.getString("package");
                    component.getString("class");
                }
            } else if (key.startsWith("__")) {
                // Unknown metadata is allowed for forward-compatible backups.
                continue;
            } else if (value instanceof JSONArray) {
                JSONArray values = (JSONArray) value;
                for (int i = 0; i < values.length(); i++) values.getString(i);
            } else if (!(value instanceof Boolean) && !(value instanceof String) && !(value instanceof Number)) {
                throw new JSONException("Unsupported preference type for " + key);
            }
        }
    }

    private void putNumericValue(SharedPreferences.Editor editor, String key, Object currentValue, Number value) {
        if (currentValue instanceof Long || (currentValue == null && value instanceof Long)) {
            editor.putLong(key, value.longValue());
        } else if (currentValue instanceof Float || value instanceof Double) {
            editor.putFloat(key, value.floatValue());
        } else {
            editor.putInt(key, value.intValue());
        }
    }

    /**
     * @return true if the existing preference is absent or has the expected type.
     */
    private boolean hasMatchingType(String key, Object currentValue, Class<?> expectedType) {
        boolean isValid = currentValue == null || expectedType.isAssignableFrom(currentValue.getClass());
        if (!isValid) {
            Log.w(TAG, "Invalid type for " + key + ": expected " + expectedType.getSimpleName()
                    + " but was " + currentValue.getClass().getSimpleName());
        }
        return isValid;
    }
}
