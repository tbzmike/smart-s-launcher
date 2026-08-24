package fr.neamar.kiss.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.ListIterator;

public class Permission {
    public static final int PERMISSION_READ_CONTACTS = 0;
    public static final int PERMISSION_CALL_PHONE = 1;
    public static final int PERMISSION_READ_PHONE_STATE = 2;
    public static final int PERMISSION_ACCESS_COARSE_LOCATION = 3;
    public static final int PERMISSION_ACCESS_FINE_LOCATION = 4;

    private static final String[] permissions = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
    };

    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static final ArrayList<PermissionResultListener> permissionListeners = new ArrayList<>();

    public static boolean checkPermission(Context context, int permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ContextCompat.checkSelfPermission(context, permissions[permission]) == PackageManager.PERMISSION_GRANTED;
    }

    public static void askPermission(int permission, PermissionResultListener listener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (listener != null) {
            listener.permission = permission;
            permissionListeners.add(listener);
        }
        Activity activity = Permission.currentActivity.get();
        if (activity != null) {
            if (permission == PERMISSION_ACCESS_FINE_LOCATION) {
                activity.requestPermissions(new String[]{
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, permission);
            } else {
                activity.requestPermissions(new String[]{permissions[permission]}, permission);
            }
        }
    }

    public Permission(Activity activity) { currentActivity = new WeakReference<>(activity); }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length == 0) return;
        boolean granted;
        if (requestCode == PERMISSION_ACCESS_FINE_LOCATION) {
            granted = false;
            for (int result : grantResults) granted |= result == PackageManager.PERMISSION_GRANTED;
        } else {
            granted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
        }

        ListIterator<PermissionResultListener> it = permissionListeners.listIterator();
        while (it.hasNext()) {
            PermissionResultListener listener = it.next();
            if (listener.permission == requestCode) {
                if (granted) listener.onGranted(); else listener.onDenied();
                it.remove();
            }
        }
    }

    public static class PermissionResultListener {
        public int permission = 0;
        public void onGranted() {}
        public void onDenied() {}
    }
}
