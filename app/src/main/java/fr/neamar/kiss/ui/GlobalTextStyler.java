package fr.neamar.kiss.ui;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.Map;
import java.util.WeakHashMap;

import fr.neamar.kiss.SettingsActivity;
import fr.neamar.kiss.UIColors;

public final class GlobalTextStyler implements Application.ActivityLifecycleCallbacks,
        SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String PREF_GLOBAL_TEXT_COLOR = "smart-global-text-color";
    public static final String PREF_GLOBAL_TEXT_WEIGHT_ENABLED = "smart-global-text-weight-enabled";
    public static final String PREF_GLOBAL_TEXT_WEIGHT = "smart-global-text-weight";
    public static final int DEFAULT_WEIGHT = 400;
    private static final long APPLY_DEBOUNCE_MS = 24L;

    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<Activity, WindowBinding> activityBindings = new WeakHashMap<>();

    private GlobalTextStyler(Application application) {
        prefs = PreferenceManager.getDefaultSharedPreferences(application);
    }

    public static GlobalTextStyler install(Application application) {
        GlobalTextStyler styler = new GlobalTextStyler(application);
        application.registerActivityLifecycleCallbacks(styler);
        styler.prefs.registerOnSharedPreferenceChangeListener(styler);
        return styler;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (!PREF_GLOBAL_TEXT_COLOR.equals(key)
                && !PREF_GLOBAL_TEXT_WEIGHT_ENABLED.equals(key)
                && !PREF_GLOBAL_TEXT_WEIGHT.equals(key)
                && !SmartTextAppearance.PREF_TEXT_COLOR_INVERTER.equals(key)) return;
        for (Activity activity : activityBindings.keySet()) scheduleApply(activity);
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        bindActivity(activity);
        if (activity instanceof SettingsActivity) {
            activity.getWindow().getDecorView().post(() ->
                    SettingsXpStyler.styleActivity(activity.getWindow().getDecorView()));
        }
        if (activity instanceof FragmentActivity) {
            ((FragmentActivity) activity).getSupportFragmentManager()
                    .registerFragmentLifecycleCallbacks(fragmentCallbacks, true);
        }
    }

    @Override public void onActivityStarted(@NonNull Activity activity) { scheduleApply(activity); }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity instanceof SettingsActivity) {
            SettingsXpStyler.styleActivity(activity.getWindow().getDecorView());
        }
        scheduleApply(activity);
    }

    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivityStopped(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        WindowBinding binding = activityBindings.remove(activity);
        if (binding != null) binding.detach();
    }

    private final FragmentManager.FragmentLifecycleCallbacks fragmentCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment fragment) {
                    if (fragment instanceof PreferenceFragmentCompat) {
                        PreferenceFragmentCompat preferenceFragment = (PreferenceFragmentCompat) fragment;
                        GlobalTextPreferences.install(preferenceFragment);
                        String rootKey = null;
                        Bundle args = fragment.getArguments();
                        if (args != null) {
                            rootKey = args.getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT);
                        }
                        SettingsOrganizer.organize(preferenceFragment.getPreferenceScreen(), rootKey);
                        SettingsXpStyler.styleFragment(preferenceFragment);
                    }
                    if (fragment instanceof DialogFragment) {
                        applyDialog(((DialogFragment) fragment).getDialog());
                    }
                }
            };

    private void bindActivity(Activity activity) {
        if (activityBindings.containsKey(activity)) return;
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return;
        WindowBinding binding = new WindowBinding(activity, root);
        activityBindings.put(activity, binding);
        binding.attach();
    }

    private void scheduleApply(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        WindowBinding binding = activityBindings.get(activity);
        if (binding == null) {
            bindActivity(activity);
            binding = activityBindings.get(activity);
        }
        if (binding != null) binding.schedule();
    }

    public void applyDialog(@Nullable Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        View root = dialog.getWindow().getDecorView();
        if (root == null) return;
        applyToTree(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> applyToTree(root));
    }

    private void applyToTree(View view) {
        if (view instanceof TextView) applyToTextView((TextView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) applyToTree(group.getChildAt(i));
    }

    private void applyToTextView(TextView view) {
        int configuredColor = configuredColor(view.getContext());
        if (configuredColor != UIColors.COLOR_SYSTEM) {
            int rendered = SmartTextAppearance.applyTextColorInverter(view.getContext(), configuredColor);
            if (view.getCurrentTextColor() != rendered) view.setTextColor(rendered);
            if (!TextUtils.isEmpty(view.getHint())) view.setHintTextColor(rendered);
            view.setLinkTextColor(rendered);
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(view.getContext());
        if (!preferences.getBoolean(PREF_GLOBAL_TEXT_WEIGHT_ENABLED, false)) return;

        int weight = configuredWeight(view.getContext());
        Typeface current = view.getTypeface();
        boolean italic = current != null && (current.getStyle() & Typeface.ITALIC) != 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (current != null && current.getWeight() == weight && current.isItalic() == italic) return;
            view.setTypeface(Typeface.create(current == null ? Typeface.DEFAULT : current, weight, italic));
            return;
        }
        int desiredStyle = weight >= 600
                ? (italic ? Typeface.BOLD_ITALIC : Typeface.BOLD)
                : (italic ? Typeface.ITALIC : Typeface.NORMAL);
        if (current != null && current.getStyle() == desiredStyle) return;
        view.setTypeface(Typeface.create(current == null ? Typeface.DEFAULT : current, desiredStyle));
    }

    private int configuredColor(Context context) {
        String raw = PreferenceManager.getDefaultSharedPreferences(context).getString(
                PREF_GLOBAL_TEXT_COLOR, UIColors.colorToString(UIColors.COLOR_SYSTEM));
        if (TextUtils.isEmpty(raw)) return UIColors.COLOR_SYSTEM;
        try { return Color.parseColor(raw); }
        catch (IllegalArgumentException ignored) { return UIColors.COLOR_SYSTEM; }
    }

    public static int configuredWeight(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int value;
        try { value = preferences.getInt(PREF_GLOBAL_TEXT_WEIGHT, DEFAULT_WEIGHT); }
        catch (ClassCastException e) {
            try { value = Integer.parseInt(preferences.getString(
                    PREF_GLOBAL_TEXT_WEIGHT, Integer.toString(DEFAULT_WEIGHT))); }
            catch (NumberFormatException | ClassCastException ignored) { value = DEFAULT_WEIGHT; }
        }
        return clampWeight(value);
    }

    static int clampWeight(int value) { return Math.max(100, Math.min(900, value)); }

    private final class WindowBinding {
        private final Activity activity;
        private final View root;
        private boolean pending;
        private final ViewTreeObserver.OnGlobalLayoutListener listener = this::schedule;

        WindowBinding(Activity activity, View root) {
            this.activity = activity;
            this.root = root;
        }

        void attach() {
            root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            schedule();
        }

        void schedule() {
            if (pending || activity.isFinishing()) return;
            pending = true;
            mainHandler.postDelayed(() -> {
                pending = false;
                if (!activity.isFinishing()) applyToTree(root);
            }, APPLY_DEBOUNCE_MS);
        }

        void detach() {
            if (root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            }
        }
    }
}
