package fr.neamar.kiss.preference;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

/**
 * Headless fragment that owns the Storage Access Framework result lifecycle for
 * Smart S settings backup and restore. Keeping this isolated avoids changing
 * SettingsActivity/SettingsFragment result routing.
 */
public final class BackupRestorePickerFragment extends Fragment {
    private static final String TAG = "smart_s_backup_restore_picker";
    private static final String ARG_RESTORE = "restore";
    private static final String STATE_STARTED = "started";
    private static final String BACKUP_FILE_NAME = "smart-s-launcher-backup.json";

    private ActivityResultLauncher<String> createDocumentLauncher;
    private ActivityResultLauncher<String[]> openDocumentLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean started;

    public static void show(@NonNull FragmentActivity activity, boolean restore) {
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;

        BackupRestorePickerFragment fragment = new BackupRestorePickerFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_RESTORE, restore);
        fragment.setArguments(args);
        activity.getSupportFragmentManager().beginTransaction()
                .add(fragment, TAG)
                .commit();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        started = savedInstanceState != null && savedInstanceState.getBoolean(STATE_STARTED, false);

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                this::onBackupDestinationSelected);

        openDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onRestoreSourceSelected);

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (!granted) {
                        Toast.makeText(requireContext(),
                                "Backup/restore will continue, but notification progress cannot be shown.",
                                Toast.LENGTH_LONG).show();
                    }
                    launchStoragePicker();
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (started) return;
        started = true;
        requestNotificationPermissionThenLaunch();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(STATE_STARTED, started);
        super.onSaveInstanceState(outState);
    }

    private void requestNotificationPermissionThenLaunch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        launchStoragePicker();
    }

    private void launchStoragePicker() {
        if (isRestore()) {
            openDocumentLauncher.launch(new String[]{"application/json", "text/json", "text/plain"});
        } else {
            createDocumentLauncher.launch(BACKUP_FILE_NAME);
        }
    }

    private void onBackupDestinationSelected(@Nullable Uri uri) {
        if (uri != null && isAdded()) {
            new ExportSettingsPreference().backupToUri(requireContext(), uri);
        }
        removeSelf();
    }

    private void onRestoreSourceSelected(@Nullable Uri uri) {
        if (uri == null || !isAdded()) {
            removeSelf();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Restore Smart S backup?")
                .setMessage("This will replace the current Smart S settings, tags, and custom components with the selected backup.")
                .setPositiveButton("Restore", (dialog, which) -> {
                    if (isAdded()) {
                        new ImportSettingsPreference().restoreFromUri(requireContext(), uri);
                    }
                    removeSelf();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> removeSelf())
                .setOnCancelListener(dialog -> removeSelf())
                .show();
    }

    private boolean isRestore() {
        Bundle args = getArguments();
        return args != null && args.getBoolean(ARG_RESTORE, false);
    }

    private void removeSelf() {
        if (!isAdded()) return;
        getParentFragmentManager().beginTransaction()
                .remove(this)
                .commitAllowingStateLoss();
    }
}
