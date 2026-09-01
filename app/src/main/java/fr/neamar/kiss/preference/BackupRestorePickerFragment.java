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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import fr.neamar.kiss.backup.FullBackupManager;

/** Owns the Storage Access Framework lifecycle for portable full backup and restore. */
public final class BackupRestorePickerFragment extends Fragment {
    private static final String TAG = "smart_s_backup_restore_picker";
    private static final String ARG_RESTORE = "restore";
    private static final String STATE_STARTED = "started";

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
        activity.getSupportFragmentManager().beginTransaction().add(fragment, TAG).commit();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        started = savedInstanceState != null && savedInstanceState.getBoolean(STATE_STARTED, false);

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
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
            openDocumentLauncher.launch(new String[]{
                    "application/zip", "application/octet-stream", "application/x-zip-compressed"});
        } else {
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
            createDocumentLauncher.launch("SmartS-full-backup-" + stamp + ".ssb");
        }
    }

    private void onBackupDestinationSelected(@Nullable Uri uri) {
        if (uri != null && isAdded()) FullBackupManager.backup(requireContext(), uri);
        removeSelf();
    }

    private void onRestoreSourceSelected(@Nullable Uri uri) {
        if (uri == null || !isAdded()) {
            removeSelf();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Restore full Smart S backup?")
                .setMessage("This replaces Smart S persistent launcher data from the selected .ssb backup: settings, history, shortcuts, tags, launcher databases, custom state and app-owned files. The archive is validated and staged first. Smart S restarts after a successful restore.")
                .setPositiveButton("Restore full backup", (dialog, which) -> {
                    if (isAdded()) FullBackupManager.restore(requireContext(), uri);
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
        getParentFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
    }
}
