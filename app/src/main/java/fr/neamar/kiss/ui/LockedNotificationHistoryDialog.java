package fr.neamar.kiss.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
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

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;

/**
 * Read-only notification browser used when the launcher UI is locked.
 * Swipe up for older notifications from the same app; swipe down for newer ones.
 */
public final class LockedNotificationHistoryDialog {
    private LockedNotificationHistoryDialog() {}

    public static boolean showLatest(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        List<NotificationHistoryRecord> records = SmartStateStore.queryNotifications(
                context, packageName, null, 0);
        if (records.isEmpty()) return false;
        new Session(context, packageName, records).show();
        return true;
    }

    private static final class Session {
        private static final float SWIPE_THRESHOLD_DP = 44f;

        private final Context context;
        private final String packageName;
        private final List<NotificationHistoryRecord> records;
        private final LinearLayout content;
        private final TextView counter;
        private final TextView title;
        private final TextView time;
        private final TextView body;
        private final LinearLayout nativeArea;
        private final LinearLayout actionArea;
        private final ScrollView scroll;
        private final AlertDialog dialog;

        private int index;
        private float downY;

        Session(Context context, String packageName, List<NotificationHistoryRecord> records) {
            this.context = context;
            this.packageName = packageName;
            this.records = records;

            int pad = dp(14);
            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(pad, pad, pad, pad);

            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);

            ImageView appIcon = new ImageView(context);
            int iconSize = dp(44);
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName,
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                appIcon.setImageDrawable(info.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException ignored) {
                // The stored notification history is still readable without an app icon.
            }
            header.addView(appIcon, new LinearLayout.LayoutParams(iconSize, iconSize));

            LinearLayout headingText = new LinearLayout(context);
            headingText.setOrientation(LinearLayout.VERTICAL);
            headingText.setPadding(pad, 0, 0, 0);

            title = new TextView(context);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setTextSize(17f);
            headingText.addView(title);

            time = new TextView(context);
            time.setTextSize(12f);
            headingText.addView(time);

            header.addView(headingText, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            counter = new TextView(context);
            counter.setTextSize(12f);
            counter.setGravity(Gravity.END);
            header.addView(counter);
            content.addView(header);

            body = new TextView(context);
            body.setTextSize(15f);
            body.setTextIsSelectable(true);
            body.setPadding(0, pad, 0, pad);
            content.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            nativeArea = new LinearLayout(context);
            nativeArea.setOrientation(LinearLayout.VERTICAL);
            content.addView(nativeArea, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            actionArea = new LinearLayout(context);
            actionArea.setOrientation(LinearLayout.VERTICAL);
            content.addView(actionArea, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView hint = new TextView(context);
            hint.setText("Swipe up: older   •   Swipe down: newer");
            hint.setGravity(Gravity.CENTER);
            hint.setTextSize(12f);
            hint.setPadding(0, pad, 0, 0);
            content.addView(hint);

            scroll = new ScrollView(context);
            scroll.setFillViewport(false);
            scroll.addView(content);
            scroll.setOnTouchListener(this::onTouch);

            dialog = new AlertDialog.Builder(context)
                    .setView(scroll)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
        }

        void show() {
            render();
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = Math.round(context.getResources().getDisplayMetrics().widthPixels * 0.94f);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(lp);
            }
            SmartAnimationEngine.animateNotificationExpand(dialog);
        }

        private boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = event.getRawY();
                    return false;
                case MotionEvent.ACTION_UP:
                    float delta = event.getRawY() - downY;
                    float threshold = dpFloat(SWIPE_THRESHOLD_DP);
                    if (Math.abs(delta) < threshold) return false;
                    if (delta < 0f && index < records.size() - 1) {
                        index++;
                        render();
                        scroll.scrollTo(0, 0);
                        return true;
                    }
                    if (delta > 0f && index > 0) {
                        index--;
                        render();
                        scroll.scrollTo(0, 0);
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        private void render() {
            NotificationHistoryRecord record = records.get(index);
            String recordTitle = record.title == null ? "" : record.title.trim();
            title.setText(recordTitle.isEmpty() ? safeAppName(record) : recordTitle);
            time.setText(DateFormat.getMediumDateFormat(context).format(record.postTime)
                    + "  " + DateFormat.getTimeFormat(context).format(record.postTime));
            counter.setText((index + 1) + " / " + records.size());

            boolean active = record.notificationId != null
                    && NotificationListener.isNotificationActive(context, record.notificationId);
            String expanded = active
                    ? NotificationListener.getExpandedNotificationText(context, record.notificationId)
                    : record.text;
            if (expanded == null || expanded.trim().isEmpty()) expanded = record.text;
            body.setText(expanded == null ? "" : expanded);

            nativeArea.removeAllViews();
            actionArea.removeAllViews();

            if (active) {
                View nativeView = NotificationListener.createNativeNotificationView(
                        context, record.notificationId, nativeArea, true);
                if (nativeView != null) {
                    nativeArea.addView(nativeView, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                addActiveActions(record);
            } else {
                addOpenAppAction();
            }
        }

        private void addActiveActions(NotificationHistoryRecord record) {
            int pad = dp(8);
            if (NotificationListener.hasReplyAction(context, record.notificationId)) {
                LinearLayout replyRow = new LinearLayout(context);
                replyRow.setOrientation(LinearLayout.HORIZONTAL);
                replyRow.setPadding(0, pad, 0, 0);

                EditText reply = new EditText(context);
                reply.setHint("Reply");
                reply.setSingleLine(false);
                replyRow.addView(reply, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button send = new Button(context);
                send.setText("Reply");
                send.setOnClickListener(v -> {
                    String message = reply.getText().toString();
                    if (message.trim().isEmpty()) return;
                    if (NotificationListener.replyToNotification(
                            context, record.notificationId, message)) {
                        reply.setText("");
                    } else {
                        Toast.makeText(context, "Unable to send reply",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                replyRow.addView(send);
                actionArea.addView(replyRow);
            }

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END);
            buttons.setPadding(0, pad, 0, 0);

            Button markRead = new Button(context);
            markRead.setText("Mark read");
            markRead.setOnClickListener(v -> {
                if (NotificationListener.markNotificationRead(context, record.notificationId)) {
                    render();
                } else {
                    Toast.makeText(context, "Unable to mark notification as read",
                            Toast.LENGTH_SHORT).show();
                }
            });
            buttons.addView(markRead);

            Button open = new Button(context);
            open.setText("Open notification");
            open.setOnClickListener(v -> {
                if (NotificationListener.openNotification(context, record.notificationId)) {
                    SmartAnimationEngine.dismissDialog(dialog);
                } else {
                    Toast.makeText(context, "Unable to open this notification",
                            Toast.LENGTH_SHORT).show();
                }
            });
            buttons.addView(open);
            actionArea.addView(buttons);
        }

        private void addOpenAppAction() {
            LinearLayout buttons = new LinearLayout(context);
            buttons.setGravity(Gravity.END);
            Button open = new Button(context);
            open.setText("Open app");
            open.setOnClickListener(v -> {
                Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (intent == null) {
                    Toast.makeText(context, "App cannot be opened",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                SmartAnimationEngine.dismissDialog(dialog);
            });
            buttons.addView(open);
            actionArea.addView(buttons);
        }

        private String safeAppName(NotificationHistoryRecord record) {
            return record.appName == null || record.appName.trim().isEmpty()
                    ? packageName : record.appName;
        }

        private int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        private float dpFloat(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }
    }
}
