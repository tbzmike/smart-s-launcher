package fr.neamar.kiss.ui;

import android.app.AlertDialog;
import android.content.Context;
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

import java.util.Date;
import java.util.List;

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.SavedNotificationDestinationResolver;

/**
 * Rich read-only history browser. Media entries use an artwork-first transport layout; ordinary
 * notifications keep their expanded/native content and actions and gain persisted image previews.
 */
public final class RichNotificationHistoryDialog {
    private RichNotificationHistoryDialog() {}

    public static boolean showLatest(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        List<NotificationHistoryRecord> records = SmartStateStore.queryNotifications(
                context, packageName, null, 0);
        if (records.isEmpty()) return false;
        new Session(context, packageName, records).show();
        return true;
    }

    private static final class Session {
        private static final float SWIPE_THRESHOLD_DP = 24f;
        private static final float SWIPE_AXIS_BIAS = 1.10f;

        private final Context context;
        private final String packageName;
        private final List<NotificationHistoryRecord> records;
        private final LinearLayout content;
        private final TextView appName;
        private final TextView subtitle;
        private final TextView counter;
        private final LinearLayout previewArea;
        private final TextView body;
        private final LinearLayout nativeArea;
        private final LinearLayout actionArea;
        private final ScrollView scroll;
        private final AlertDialog dialog;
        private final int accent;

        private int index;
        private float downX;
        private float downY;

        Session(Context context, String packageName, List<NotificationHistoryRecord> records) {
            this.context = context;
            this.packageName = packageName;
            this.records = records;
            this.accent = AppNativeDialogStyle.accentForPackage(context, packageName);

            int pad = dp(16);
            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(pad, pad, pad, pad);

            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(context);
            int iconSize = dp(48);
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(packageName,
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                icon.setImageDrawable(info.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException ignored) { }
            header.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

            LinearLayout heading = new LinearLayout(context);
            heading.setOrientation(LinearLayout.VERTICAL);
            heading.setPadding(dp(12), 0, 0, 0);

            appName = new TextView(context);
            appName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            appName.setTextSize(19f);
            AppNativeDialogStyle.setReadableText(appName);
            heading.addView(appName);

            subtitle = new TextView(context);
            subtitle.setTextSize(13f);
            AppNativeDialogStyle.setReadableText(subtitle);
            heading.addView(subtitle);
            header.addView(heading, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            counter = new TextView(context);
            counter.setTextSize(13f);
            counter.setGravity(Gravity.END);
            AppNativeDialogStyle.setReadableText(counter);
            header.addView(counter);
            content.addView(header);

            previewArea = new LinearLayout(context);
            previewArea.setOrientation(LinearLayout.VERTICAL);
            content.addView(previewArea, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            body = new TextView(context);
            body.setTextSize(16f);
            body.setTextIsSelectable(true);
            body.setPadding(0, dp(12), 0, dp(8));
            AppNativeDialogStyle.setReadableText(body);
            content.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            nativeArea = new LinearLayout(context);
            nativeArea.setOrientation(LinearLayout.VERTICAL);
            content.addView(nativeArea, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView hint = new TextView(context);
            hint.setText("Swipe up: older   •   Swipe down: newer");
            hint.setGravity(Gravity.CENTER);
            hint.setTextSize(13f);
            hint.setPadding(0, dp(12), 0, dp(6));
            AppNativeDialogStyle.setReadableText(hint);
            content.addView(hint);

            actionArea = new LinearLayout(context);
            actionArea.setOrientation(LinearLayout.VERTICAL);
            content.addView(actionArea, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            scroll = new HistorySwipeScrollView(context);
            scroll.setFillViewport(false);
            scroll.addView(content);

            dialog = new AlertDialog.Builder(context)
                    .setView(scroll)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
        }

        void show() {
            render();
            dialog.setOnShowListener(ignored -> {
                AppNativeDialogStyle.styleDialog(dialog, packageName);
                AppNativeDialogStyle.styleButton(
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE), accent);
            });
            dialog.show();
            AppNativeDialogStyle.styleDialog(dialog, packageName);
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.width = Math.round(context.getResources().getDisplayMetrics().widthPixels * 0.94f);
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                window.setAttributes(lp);
            }
            SmartAnimationEngine.animateNotificationExpand(dialog);
        }

        private void render() {
            NotificationHistoryRecord record = records.get(index);
            boolean active = record.notificationId != null
                    && NotificationListener.isNotificationActive(context, record.notificationId);
            String expanded = active
                    ? NotificationListener.getExpandedNotificationText(context, record.notificationId)
                    : record.text;
            if (expanded == null || expanded.trim().isEmpty()) expanded = record.text;

            NotificationRichPreview.Preview rich = NotificationRichPreview.create(
                    context, record.notificationId, packageName, record.title, expanded);
            boolean media = rich != null && rich.media;

            appName.setText(safeAppName(record));
            Date posted = new Date(record.postTime);
            if (media && rich.activeMedia) {
                subtitle.setText("Playing on this phone");
            } else {
                subtitle.setText(DateFormat.getMediumDateFormat(context).format(posted)
                        + "  " + DateFormat.getTimeFormat(context).format(posted));
            }
            counter.setText((index + 1) + " / " + records.size());

            previewArea.removeAllViews();
            if (rich != null) {
                previewArea.addView(rich.view, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            if (media) {
                body.setVisibility(View.GONE);
                body.setText("");
            } else {
                body.setVisibility(View.VISIBLE);
                String title = record.title == null ? "" : record.title.trim();
                String text = expanded == null ? "" : expanded.trim();
                if (!title.isEmpty() && !text.startsWith(title)) {
                    body.setText(text.isEmpty() ? title : title + "\n" + text);
                } else {
                    body.setText(text.isEmpty() ? title : text);
                }
            }

            nativeArea.removeAllViews();
            actionArea.removeAllViews();
            if (active && !media) {
                View nativeView = NotificationListener.createNativeNotificationView(
                        context, record.notificationId, nativeArea, true);
                if (nativeView != null) {
                    AppNativeDialogStyle.styleNotificationContent(nativeView, packageName);
                    nativeArea.addView(nativeView, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                addActiveActions(record);
            } else {
                addSavedOpenAction(record);
            }

            if (dialog.isShowing()) AppNativeDialogStyle.styleDialog(dialog, packageName);
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
                AppNativeDialogStyle.setReadableText(reply);
                replyRow.addView(reply, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button send = new Button(context);
                send.setText("Reply");
                AppNativeDialogStyle.styleButton(send, accent);
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
            buttons.setGravity(Gravity.END);
            buttons.setPadding(0, pad, 0, 0);

            Button markRead = new Button(context);
            markRead.setText("Mark read");
            AppNativeDialogStyle.styleButton(markRead, accent);
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
            AppNativeDialogStyle.styleButton(open, accent);
            open.setOnClickListener(v -> {
                boolean opened = SavedNotificationDestinationResolver.openExact(context, record);
                if (opened) SmartAnimationEngine.dismissDialog(dialog);
                else Toast.makeText(context, "Unable to open this exact notification",
                        Toast.LENGTH_SHORT).show();
            });
            buttons.addView(open);
            actionArea.addView(buttons);
        }

        private void addSavedOpenAction(NotificationHistoryRecord record) {
            LinearLayout buttons = new LinearLayout(context);
            buttons.setGravity(Gravity.END);
            Button open = new Button(context);
            boolean exactTarget = SavedNotificationDestinationResolver.hasExactTarget(context, record);
            open.setText(exactTarget ? "Open notification" : "Open app");
            AppNativeDialogStyle.styleButton(open, accent);
            open.setOnClickListener(v -> {
                boolean opened = exactTarget
                        ? SavedNotificationDestinationResolver.openExact(context, record)
                        : AppLaunchUtils.launchPackage(context, packageName);
                if (!opened) {
                    Toast.makeText(context, exactTarget
                                    ? "Unable to open this exact notification" : "App cannot be opened",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                SmartAnimationEngine.dismissDialog(dialog);
            });
            buttons.addView(open);
            actionArea.addView(buttons);
        }

        private boolean handleSwipeEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    return false;
                case MotionEvent.ACTION_UP:
                    float deltaX = event.getRawX() - downX;
                    float deltaY = event.getRawY() - downY;
                    float absX = Math.abs(deltaX);
                    float absY = Math.abs(deltaY);
                    float threshold = dpFloat(SWIPE_THRESHOLD_DP);
                    if (absY < threshold || absY <= absX * SWIPE_AXIS_BIAS) return false;
                    if (deltaY < 0f && index < records.size() - 1) {
                        index++;
                        render();
                        scroll.scrollTo(0, 0);
                        return true;
                    }
                    if (deltaY > 0f && index > 0) {
                        index--;
                        render();
                        scroll.scrollTo(0, 0);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_CANCEL:
                    downX = 0f;
                    downY = 0f;
                    return false;
                default:
                    return false;
            }
        }

        private final class HistorySwipeScrollView extends ScrollView {
            HistorySwipeScrollView(Context context) { super(context); }
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (handleSwipeEvent(event)) return true;
                return super.dispatchTouchEvent(event);
            }
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
