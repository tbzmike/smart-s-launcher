package fr.neamar.kiss.preference;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.DialogPreference;
import androidx.preference.PreferenceGroup;

public class DialogShowingPreference extends DialogPreference {
    private static final String KEY_BACKUP_SETTINGS = "export-settings";
    private static final String KEY_RESTORE_SETTINGS = "import-settings";

    public DialogShowingPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public DialogShowingPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DialogShowingPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DialogShowingPreference(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        String key = getKey();
        if (KEY_BACKUP_SETTINGS.equals(key)) {
            setTitle("Full Smart S backup");
            setSummary("One portable .ssb file: settings, history, shortcuts, tags, databases, launcher state and app-owned files");
            renameParentSection();
        } else if (KEY_RESTORE_SETTINGS.equals(key)) {
            setTitle("Restore full Smart S backup");
            setSummary("Validate and restore a portable .ssb backup, then restart Smart S");
            renameParentSection();
        }
    }

    @Override
    protected void onClick() {
        String key = getKey();
        if (KEY_BACKUP_SETTINGS.equals(key) || KEY_RESTORE_SETTINGS.equals(key)) {
            FragmentActivity activity = findFragmentActivity(getContext());
            if (activity != null) {
                BackupRestorePickerFragment.show(activity, KEY_RESTORE_SETTINGS.equals(key));
                return;
            }
        }
        super.onClick();
    }

    @Nullable
    private FragmentActivity findFragmentActivity(Context context) {
        Context current = context;
        while (current != null) {
            if (current instanceof FragmentActivity) return (FragmentActivity) current;
            if (!(current instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return null;
    }

    private void renameParentSection() {
        PreferenceGroup parent = getParent();
        if (parent != null && "importexport".equals(parent.getKey())) {
            parent.setTitle("Backup & restore");
            parent.setSummary("Portable full-state backup and restore for Smart S Launcher");
        }
    }
}
