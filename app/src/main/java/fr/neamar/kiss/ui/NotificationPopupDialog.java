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

import fr.neamar.kiss.notification.NotificationAvatarSupport;
import fr.neamar.kiss.notification.NotificationListener;

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

        String packageName = NotificationListener.getNotificationPackage(context, notifications.get(0).id);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 10);
        list.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(notifications.size() + " notifications")
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        for (NotificationListener.NotificationSnapshot snapshot : notifications) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            ImageView avatar = new ImageView(context);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setImageDrawable(identityDrawable(context, snapshot.id, packageName));
            row.addView(avatar, new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)));
            TextView rowText = new TextView(context);
            String label = snapshot.title == null ? "" : snapshot.title.trim();
            String body = snapshot.text == null ? "" : snapshot.text.trim();
            if (!body.isEmpty()) label = label.isEmpty() ? body : label + "\n" + body;
            if (label.isEmpty()) label = "Notification";
            rowText.setText(label);
            rowText.setTextSize(15f);
            rowText.setPadding(pad, 0, 0, 0);
            rowText.setMaxLines(4);
            AppNativeDialogStyle.setReadableText(rowText);
            row.addView(rowText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> {
                SmartAnimationEngine.dismissDialog(dialog);
                showNotification(context, groupKey, snapshot);
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        dialog.setView(scroll);
        showWide(dialog);
        AppNativeDialogStyle.styleDialog(dialog, packageName);
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
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImageDrawable(identityDrawable(context, snapshot.id, packageName));
        heading.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView title = new TextView(context);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextSize(17f);
        title.setPadding(pad, 0, 0, 0);
        title.setText(snapshot.title == null || snapshot.title.trim().isEmpty()
                ? "Notification" : snapshot.title);
        AppNativeDialogStyle.setReadableText(title);
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
            AppNativeDialogStyle.setReadableText(body);
            content.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout nativeContainer = new LinearLayout(context);
        nativeContainer.setOrientation(LinearLayout.VERTICAL);
        View nativeView = NotificationListener.createNativeNotificationView(
                context, snapshot.id, nativeContainer, true);
        if (nativeView != null) {
            AppNativeDialogStyle.styleNotificationContent(nativeView, packageName);
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
            AppNativeDialogStyle.setReadableText(reply);
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
            AppNativeDialogStyle.styleDialog(dialog, packageName);
            int accent = AppNativeDialogStyle.accentForPackage(context, packageName);

            Button markRead = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (markRead != null) {
                AppNativeDialogStyle.styleButton(markRead, accent);
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
                AppNativeDialogStyle.styleButton(open, accent);
                open.setOnClickListener(v -> {
                    boolean opened = NotificationListener.openNotification(context, snapshot.id);
                    if (opened) {
                        SmartAnimationEngine.dismissDialog(dialog);
                    } else {
                        Toast.makeText(context, "Unable to open this notification",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }

            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            AppNativeDialogStyle.styleButton(cancel, accent);
            AppNativeDialogStyle.styleButton(sendIfPresent(content), accent);

            if (NotificationListener.hasMarkAllReadAction(context, groupKey)) {
                Button markAll = new Button(context);
                markAll.setText("Mark all read");
                markAll.setAllCaps(false);
                AppNativeDialogStyle.styleButton(markAll, accent);
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
        AppNativeDialogStyle.styleDialog(dialog, packageName);
        SmartAnimationEngine.animateNotificationExpand(dialog);
    }

    private static android.graphics.drawable.Drawable identityDrawable(Context context, String notificationId, String packageName) {
        android.graphics.drawable.Drawable avatar = NotificationAvatarSupport.avatar(context, notificationId);
        if (avatar != null) return avatar;
        if (packageName == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            return info.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private static Button sendIfPresent(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button && "Reply".contentEquals(((Button) child).getText())) return (Button) child;
            Button nested = sendIfPresent(child);
            if (nested != null) return nested;
        }
        return null;
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
