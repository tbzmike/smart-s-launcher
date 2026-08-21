package fr.neamar.kiss.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.utils.AppLaunchUtils;

/**
 * Window-level notification viewer used by launcher timeline rows.
 * It deliberately lives above the workspace hierarchy so notification content can span panes.
 */
public final class NotificationPopupDialog {
    private NotificationPopupDialog() {}

    public static void showGroup(Context context, String groupKey) {
        List<NotificationListener.NotificationSnapshot> notifications =
                NotificationListener.getGroupNotifications(context, groupKey);
        if (notifications.isEmpty()) return;
        if (notifications.size() == 1) {
            showNotification(context, groupKey, notifications.get(0));
            return;
        }

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 10);
        list.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(notifications.size() + " notifications")
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        for (NotificationListener.NotificationSnapshot snapshot : notifications) {
            TextView row = new TextView(context);
            String label = snapshot.title == null ? "" : snapshot.title.trim();
            String body = snapshot.text == null ? "" : snapshot.text.trim();
            if (!body.isEmpty()) label = label.isEmpty() ? body : label + "\n" + body;
            if (label.isEmpty()) label = "Notification";
            row.setText(label);
            row.setTextSize(15f);
            row.setPadding(pad, pad, pad, pad);
            row.setMaxLines(4);
            row.setOnClickListener(v -> {
                SmartAnimationEngine.dismissDialog(dialog);
                showNotification(context, groupKey, snapshot);
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        dialog.setView(scroll);
        showWide(dialog);
        SmartAnimationEngine.animateDialogIn(dialog);
    }

    private static void showNotification(Context context, String groupKey,
                                         NotificationListener.NotificationSnapshot snapshot) {
        int pad = dp(context, 12);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(context);
        int iconSize = dp(context, 44);
        String packageName = NotificationListener.getNotificationPackage(context, snapshot.id);
        if (packageName != null) {
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
                icon.setImageDrawable(info.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Native notification content below remains usable without the app icon.
            }
        }
        heading.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView title = new TextView(context);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextSize(17f);
        title.setPadding(pad, 0, 0, 0);
        title.setText(snapshot.title == null || snapshot.title.trim().isEmpty()
                ? "Notification" : snapshot.title);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(heading);

        String fullText = NotificationListener.getExpandedNotificationText(context, snapshot.id);
        if (fullText != null && !fullText.trim().isEmpty()) {
            TextView body = new TextView(context);
            body.setText(fullText);
            body.setTextSize(15f);
            body.setTextIsSelectable(true);
            body.setPadding(0, pad, 0, pad);
            content.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout nativeContainer = new LinearLayout(context);
        nativeContainer.setOrientation(LinearLayout.VERTICAL);
        View nativeView = NotificationListener.createNativeNotificationView(
                context, snapshot.id, nativeContainer, true);
        if (nativeView != null) {
            nativeContainer.addView(nativeView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(nativeContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (NotificationListener.hasReplyAction(context, snapshot.id)) {
            LinearLayout replyArea = new LinearLayout(context);
            replyArea.setOrientation(LinearLayout.HORIZONTAL);
            replyArea.setPadding(0, pad, 0, 0);
            EditText reply = new EditText(context);
            reply.setSingleLine(false);
            reply.setHint("Reply");
            replyArea.addView(reply, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button send = new Button(context);
            send.setText("Reply");
            replyArea.addView(send);
            send.setOnClickListener(v -> {
                if (NotificationListener.replyToNotification(context, snapshot.id,
                        reply.getText().toString())) {
                    reply.setText("");
                } else {
                    Toast.makeText(context, "Unable to send reply", Toast.LENGTH_SHORT).show();
                }
            });
            content.addView(replyArea);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.addView(content);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(scroll)
                .setNeutralButton("Mark read", null)
                .setPositiveButton("Open notification", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        SharedPreferences detailPrefs = context.getSharedPreferences(
                NotificationListener.DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
        SharedPreferences.OnSharedPreferenceChangeListener removalListener =
                (sharedPreferences, key) -> {
                    if (NotificationListener.ACTIVE_NOTIFICATION_IDS.equals(key)
                            && !NotificationListener.isNotificationActive(context, snapshot.id)
                            && dialog.isShowing()) {
                        SmartAnimationEngine.dismissDialog(dialog);
                    }
                };

        dialog.setOnShowListener(ignored -> {
            detailPrefs.registerOnSharedPreferenceChangeListener(removalListener);

            Button markRead = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (markRead != null) {
                markRead.setOnClickListener(v -> {
                    if (NotificationListener.markNotificationRead(context, snapshot.id)) {
                        SmartAnimationEngine.dismissDialog(dialog);
                    } else {
                        Toast.makeText(context, "Unable to mark notification as read",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }

            Button open = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (open != null) {
                open.setOnClickListener(v -> {
                    // Keep the launcher visibly foreground while dispatching the user-requested
                    // PendingIntent. Android 14+ background-activity-start rules use this visible
                    // state when deciding whether a notification target may surface an Activity.
                    boolean opened = NotificationListener.openNotification(context, snapshot.id);
                    if (!opened && packageName != null) {
                        opened = AppLaunchUtils.launchPackage(context, packageName);
                    }
                    if (opened) {
                        SmartAnimationEngine.dismissDialog(dialog);
                    } else {
                        Toast.makeText(context, "Unable to open this notification",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }

            if (NotificationListener.hasMarkAllReadAction(context, groupKey)) {
                Button markAll = new Button(context);
                markAll.setText("Mark all read");
                markAll.setAllCaps(false);
                markAll.setOnClickListener(v -> {
                    if (NotificationListener.markAllRead(context, groupKey)) {
                        SmartAnimationEngine.dismissDialog(dialog);
                    } else {
                        Toast.makeText(context, "Unable to mark all as read",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.END;
                content.addView(markAll, params);
            }
        });
        dialog.setOnDismissListener(ignored ->
                detailPrefs.unregisterOnSharedPreferenceChangeListener(removalListener));
        showWide(dialog);
        SmartAnimationEngine.animateNotificationExpand(dialog);
    }

    private static void showWide(AlertDialog dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = Math.round(dialog.getContext().getResources().getDisplayMetrics().widthPixels * 0.92f);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(lp);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
