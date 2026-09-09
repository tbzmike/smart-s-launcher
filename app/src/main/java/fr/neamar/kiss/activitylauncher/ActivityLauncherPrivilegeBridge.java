package fr.neamar.kiss.activitylauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * On-demand privilege bridge. No probe, process or command is started until the user taps
 * a disabled/private component or launches a saved privileged target.
 */
final class ActivityLauncherPrivilegeBridge {
    enum Result {
        SUCCESS,
        ROOT_UNAVAILABLE_OR_DENIED,
        FAILED
    }

    private static final long ROOT_TIMEOUT_MS = 12000L;

    private ActivityLauncherPrivilegeBridge() { }

    static boolean isSafePackageName(@NonNull String value) {
        return value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*");
    }

    static boolean isSafeClassName(@NonNull String value) {
        return value.matches("\\.?[A-Za-z0-9_.$]+");
    }

    @NonNull
    private static String componentShellArg(@NonNull ComponentName component) {
        String packageName = component.getPackageName();
        String className = component.getClassName();
        if (!isSafePackageName(packageName) || !isSafeClassName(className)) {
            throw new IllegalArgumentException("Unsafe component name");
        }
        return "'" + packageName + "/" + className + "'";
    }

    @NonNull
    private static String packageShellArg(@NonNull String packageName) {
        if (!isSafePackageName(packageName)) throw new IllegalArgumentException("Unsafe package name");
        return "'" + packageName + "'";
    }

    static boolean isApplicationEffectivelyEnabled(@NonNull Context context,
                                                   @NonNull String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            int state = pm.getApplicationEnabledSetting(packageName);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            return info.enabled;
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    static boolean isComponentEffectivelyEnabled(@NonNull Context context,
                                                 @NonNull ComponentName component) {
        if (!isApplicationEffectivelyEnabled(context, component.getPackageName())) return false;
        PackageManager pm = context.getPackageManager();
        try {
            int state = pm.getComponentEnabledSetting(component);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return false;
            }
            ComponentInfo info = findComponent(pm, component);
            return info != null && info.enabled;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @NonNull
    static Result enableApplication(@NonNull Context context, @NonNull String packageName) {
        if (!isSafePackageName(packageName)) return Result.FAILED;
        if (isApplicationEffectivelyEnabled(context, packageName)) return Result.SUCCESS;

        PackageManager pm = context.getPackageManager();
        try {
            pm.setApplicationEnabledSetting(packageName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            if (isApplicationEffectivelyEnabled(context, packageName)) return Result.SUCCESS;
        } catch (SecurityException | IllegalArgumentException ignored) { }

        String command = "pm enable --user " + UserHandle.myUserId() + " "
                + packageShellArg(packageName);
        if (!runRootCommand(command)) return Result.ROOT_UNAVAILABLE_OR_DENIED;
        return isApplicationEffectivelyEnabled(context, packageName)
                ? Result.SUCCESS : Result.FAILED;
    }

    @NonNull
    static Result enableComponent(@NonNull Context context, @NonNull ComponentName component) {
        Result app = enableApplication(context, component.getPackageName());
        if (app != Result.SUCCESS) return app;
        if (isComponentEffectivelyEnabled(context, component)) return Result.SUCCESS;

        PackageManager pm = context.getPackageManager();
        try {
            pm.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            if (isComponentEffectivelyEnabled(context, component)) return Result.SUCCESS;
        } catch (SecurityException | IllegalArgumentException ignored) { }

        String command;
        try {
            command = "pm enable --user " + UserHandle.myUserId() + " "
                    + componentShellArg(component);
        } catch (IllegalArgumentException e) {
            return Result.FAILED;
        }
        if (!runRootCommand(command)) return Result.ROOT_UNAVAILABLE_OR_DENIED;
        return isComponentEffectivelyEnabled(context, component)
                ? Result.SUCCESS : Result.FAILED;
    }

    static boolean executePrivilegedTarget(@NonNull Context context, @NonNull Intent target,
                                           @NonNull String kind) {
        ComponentName component = target.getComponent();
        if (component == null) return false;
        if (enableComponent(context, component) != Result.SUCCESS) return false;

        String verb;
        if (ActivityLauncherStore.KIND_PRIVILEGED_ACTIVITY.equals(kind)) {
            verb = "am start";
        } else if (ActivityLauncherStore.KIND_PRIVILEGED_SERVICE.equals(kind)) {
            verb = "am startservice";
        } else if (ActivityLauncherStore.KIND_PRIVILEGED_BROADCAST.equals(kind)) {
            verb = "am broadcast";
        } else {
            return false;
        }

        try {
            return runRootCommand(verb + " --user " + UserHandle.myUserId()
                    + " -n " + componentShellArg(component));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ComponentInfo findComponent(@NonNull PackageManager pm,
                                               @NonNull ComponentName component) {
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                | PackageManager.MATCH_DISABLED_COMPONENTS;
        try {
            PackageInfo info = pm.getPackageInfo(component.getPackageName(), flags);
            ComponentInfo match = find(component.getClassName(), info.activities);
            if (match != null) return match;
            match = find(component.getClassName(), info.services);
            if (match != null) return match;
            match = find(component.getClassName(), info.receivers);
            if (match != null) return match;
            return find(component.getClassName(), info.providers);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static ComponentInfo find(@NonNull String className, ActivityInfo[] infos) {
        if (infos == null) return null;
        for (ActivityInfo info : infos) if (className.equals(info.name)) return info;
        return null;
    }

    private static ComponentInfo find(@NonNull String className, ServiceInfo[] infos) {
        if (infos == null) return null;
        for (ServiceInfo info : infos) if (className.equals(info.name)) return info;
        return null;
    }

    private static ComponentInfo find(@NonNull String className, ProviderInfo[] infos) {
        if (infos == null) return null;
        for (ProviderInfo info : infos) if (className.equals(info.name)) return info;
        return null;
    }

    private static boolean runRootCommand(@NonNull String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            long deadline = System.currentTimeMillis() + ROOT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    return process.exitValue() == 0;
                } catch (IllegalThreadStateException running) {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        process.destroy();
                        return false;
                    }
                }
            }
            process.destroy();
            return false;
        } catch (IOException | SecurityException e) {
            if (process != null) process.destroy();
            return false;
        }
    }
}
