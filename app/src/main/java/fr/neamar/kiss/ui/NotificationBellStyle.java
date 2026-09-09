package fr.neamar.kiss.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.util.WeakHashMap;

import fr.neamar.kiss.R;
import fr.neamar.kiss.notification.NotificationUnreadStore;
import fr.neamar.kiss.pojo.NotificationHistorySearchPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;

/** One strict notification-event identity rule shared by native and custom result renderers. */
public final class NotificationBellStyle {
    private static final WeakHashMap<TextView, ValueAnimator> FLASHERS = new WeakHashMap<>();
    private static final WeakHashMap<TextView, Boolean> DETACH_GUARDS = new WeakHashMap<>();
    private static final WeakHashMap<TextView, BellState> STATES = new WeakHashMap<>();

    private static final class BellState {
        final boolean visible;
        final boolean flashing;
        final int color;

        BellState(boolean visible, boolean flashing, int color) {
            this.visible = visible;
            this.flashing = flashing;
            this.color = color;
        }

        boolean matches(boolean nextVisible, boolean nextFlashing, int nextColor) {
            return visible == nextVisible && flashing == nextFlashing && color == nextColor;
        }
    }

    private NotificationBellStyle() {}

    public static boolean isNotificationItem(@NonNull Context context,
                                             @Nullable Result<?> result,
                                             @Nullable View renderedSource) {
        if (result == null || result.getPojo() == null) return false;
        Pojo pojo = result.getPojo();
        // Deliberately do not inspect app notification previews, package history, or rendered
        // notification rows here. Those belong to the app-launch tile and do not change its origin.
        return NotificationBellPolicy.shouldShow(
                pojo instanceof NotificationPojo,
                pojo instanceof NotificationHistorySearchPojo);
    }

    private static boolean isUnreadNotification(@NonNull Context context,
                                                @Nullable Result<?> result) {
        if (result == null || !(result.getPojo() instanceof NotificationPojo)) return false;
        String id = result.getPojoId();
        return id != null && NotificationUnreadStore.isUnread(context, id);
    }

    /** Apply to the primary name in a normal adapter row; false explicitly clears recycled rows. */
    public static void applyToResult(@NonNull View root, @Nullable Result<?> result,
                                     boolean verticalList) {
        TextView primary = primaryName(root);
        if (primary == null) return;
        boolean show = isNotificationItem(root.getContext(), result, root);
        boolean flash = NotificationBellPolicy.shouldFlash(
                show, isUnreadNotification(root.getContext(), result), verticalList);
        apply(primary, show, flash);
    }

    @Nullable
    private static TextView primaryName(@NonNull View root) {
        int[] ids = new int[]{R.id.item_notification_app, R.id.item_app_name,
                R.id.item_setting_name, R.id.item_communication_title};
        for (int id : ids) {
            View candidate = root.findViewById(id);
            if (candidate instanceof TextView && candidate.getVisibility() != View.GONE) {
                return (TextView) candidate;
            }
        }
        return null;
    }

    public static void apply(@NonNull TextView textView, boolean visible) {
        apply(textView, visible, false);
    }

    public static void apply(@NonNull TextView textView, boolean visible, boolean flashUnread) {
        int color = flashUnread ? Color.YELLOW : textView.getCurrentTextColor();
        BellState previous = STATES.get(textView);
        if (previous != null && previous.matches(visible, flashUnread, color)) {
            if (!visible) return;
            Drawable[] current = textView.getCompoundDrawablesRelative();
            Drawable bell = current.length >= 3 ? current[2] : null;
            if (bell != null) {
                if (!flashUnread || FLASHERS.containsKey(textView)) return;
                // Detaching a row stops its animator. Rebinding the same unchanged row should only
                // restart that animator, not recreate/re-tint the bell drawable itself.
                startFlashing(textView, bell);
                return;
            }
        }

        stopFlashing(textView);
        if (!visible) {
            textView.setCompoundDrawablesRelative(null, null, null, null);
            textView.setCompoundDrawablePadding(0);
            STATES.put(textView, new BellState(false, false, color));
            return;
        }

        Drawable source = ContextCompat.getDrawable(
                textView.getContext(), R.drawable.ic_notification_bell);
        if (source == null) return;
        Drawable bell = DrawableCompat.wrap(source.mutate());
        DrawableCompat.setTint(bell, color);
        float density = textView.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.round(17f * density));
        bell.setBounds(0, 0, size, size);
        textView.setCompoundDrawablePadding(Math.round(5f * density));
        textView.setCompoundDrawablesRelative(null, null, bell, null);
        STATES.put(textView, new BellState(true, flashUnread, color));

        if (flashUnread) startFlashing(textView, bell);
    }

    private static void startFlashing(@NonNull TextView textView, @NonNull Drawable bell) {
        stopFlashing(textView);
        ensureDetachGuard(textView);
        ValueAnimator animator = ValueAnimator.ofInt(255, 55, 255);
        animator.setDuration(900L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(value -> {
            Drawable[] current = textView.getCompoundDrawablesRelative();
            if (current.length < 3 || current[2] != bell) {
                stopFlashing(textView);
                return;
            }
            bell.setAlpha((Integer) value.getAnimatedValue());
            textView.invalidate();
        });
        FLASHERS.put(textView, animator);
        animator.start();
    }

    private static void ensureDetachGuard(@NonNull TextView textView) {
        if (DETACH_GUARDS.containsKey(textView)) return;
        DETACH_GUARDS.put(textView, Boolean.TRUE);
        textView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                stopFlashing(textView);
            }
        });
    }

    private static void stopFlashing(@NonNull TextView textView) {
        ValueAnimator previous = FLASHERS.remove(textView);
        if (previous != null) previous.cancel();
    }
}
