package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.PreferenceManager;

import fr.neamar.kiss.R;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.NotificationHistorySearchPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;

/** One notification identity rule shared by native and custom launcher result renderers. */
public final class NotificationBellStyle {
    private NotificationBellStyle() {}

    public static boolean isNotificationItem(@NonNull Context context,
                                             @Nullable Result<?> result,
                                             @Nullable View renderedSource) {
        if (result == null || result.getPojo() == null) return false;
        Pojo pojo = result.getPojo();

        if (pojo instanceof NotificationPojo || pojo instanceof NotificationHistorySearchPojo) {
            return true;
        }
        if (pojo instanceof CommunicationPojo
                && ((CommunicationPojo) pojo).kind == CommunicationPojo.Kind.TRUECALLER_NOTIFICATION) {
            return true;
        }
        if (hasRenderedNotification(renderedSource)) return true;

        // Vertical Cards already present the latest persisted notification on ordinary app history
        // rows. Apply the same identity in every HISTORY renderer so those old notification items
        // do not lose their bell merely because the underlying launcher POJO is still AppPojo.
        if (pojo instanceof AppPojo
                && SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY
                && PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean("enable-notification-history", false)) {
            NotificationHistoryRecord latest = SmartStateStore.latestNotificationForPackage(
                    context, ((AppPojo) pojo).packageName);
            return latest != null
                    && (!TextUtils.isEmpty(latest.title) || !TextUtils.isEmpty(latest.text));
        }
        return false;
    }

    public static boolean hasRenderedNotification(@Nullable View root) {
        if (root == null) return false;
        View row = root.findViewById(R.id.item_notification_row);
        return row != null && row.getVisibility() == View.VISIBLE;
    }

    /** Apply to the primary name in a normal adapter row; false explicitly clears recycled rows. */
    public static void applyToResult(@NonNull View root, @Nullable Result<?> result) {
        TextView primary = primaryName(root);
        if (primary != null) apply(primary, isNotificationItem(root.getContext(), result, root));
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
        if (!visible) {
            textView.setCompoundDrawablesRelative(null, null, null, null);
            textView.setCompoundDrawablePadding(0);
            return;
        }
        Drawable source = ContextCompat.getDrawable(
                textView.getContext(), R.drawable.ic_notification_bell);
        if (source == null) return;
        Drawable bell = DrawableCompat.wrap(source.mutate());
        DrawableCompat.setTint(bell, textView.getCurrentTextColor());
        float density = textView.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.round(17f * density));
        bell.setBounds(0, 0, size, size);
        textView.setCompoundDrawablePadding(Math.round(5f * density));
        textView.setCompoundDrawablesRelative(null, null, bell, null);
    }
}
