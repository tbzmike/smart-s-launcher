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
import fr.neamar.kiss.notification.NotificationIdentityIcon;

import java.util.Date;
import java.util.List;

import fr.neamar.kiss.AppUsageActivity;
import fr.neamar.kiss.NotificationHistoryActivity;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.db.NotificationTimelineStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationUnreadStore;
import fr.neamar.kiss.utils.AppReinstallSupport;
import fr.neamar.kiss.utils.SavedNotificationDestinationResolver;

/**
 * Full-fidelity History browser used by both locked and unlocked launcher UI. Launcher UI locking
 * only prevents layout/appearance editing; notification actions remain usable here.
 *
 * The popup keeps the exact selected History identity, shows complete text/native content, retained
 * imagery/media, inline reply when Android exposes RemoteInput, mark-read and exact-open actions.
 */
public final class RichNotificationHistoryDialog {
    private RichNotificationHistoryDialog() {}

    public static boolean showLatest(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        List<NotificationHistoryRecord> records = SmartStateStore.queryNotifications(
                context, packageName, null, 0);
        if (records.isEmpty()) return false;
        new Session(context, packageName, records, 0).show();
        return true;
    }

    public static boolean showSelected(Context context,
                                       String packageName,
                                       String notificationId,
                                       long postTime) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;

        // Notification History is the authority for long-press inspection. Resolve the exact
        // persisted event first, then display that immutable database row.
        NotificationHistoryRecord selected = NotificationTimelineStore.findExact(
                context, notificationId, postTime);
        if (selected != null && packageName.equals(selected.packageName)) {
            return showRecord(context, selected);
        }

        // Keep compatibility with legacy/group notification rows that predate exact DB identity,
        // but never fall back to "latest from the app" for a normal notification.
        List<NotificationHistoryRecord> records = SmartStateStore.queryNotifications(
                context, packageName, null, 0);
        if (records.isEmpty()) return false;
        int startIndex = NotificationHistoryStartIndex.resolve(
                records, notificationId, postTime);
        if (startIndex < 0) return false;
        new Session(context, packageName, records, startIndex).show();
        return true;
    }

    /**
     * Display one exact row already resolved from Smart S Notification History. The surrounding
     * package timeline is loaded only to preserve older/newer swipe navigation; the selected row
     * itself is anchored by its database id, never by list position or "latest" heuristics.
     */
    public static boolean showRecord(Context context, NotificationHistoryRecord selected) {
        if (context == null || selected == null || selected.dbId <= 0L
                || selected.packageName == null || selected.packageName.isEmpty()) {
            return false;
        }

        List<NotificationHistoryRecord> records = SmartStateStore.queryNotifications(
                context, selected.packageName, null, 0);
        int startIndex = -1;
        for (int i = 0; i < records.size(); i++) {
            NotificationHistoryRecord candidate = records.get(i);
            if (candidate != null && candidate.dbId == selected.dbId) {
                startIndex = i;
                break;
            }
        }

        // A retention/cleanup pass can race the package query after the exact row was read. Show the
        // already-resolved record rather than silently switching to another notification.
        if (startIndex < 0) {
            records = new java.util.ArrayList<>();
            records.add(selected);
            startIndex = 0;
        }

        new Session(context, selected.packageName, records, startIndex).show();
        return true;
    }

    private static final class Session {
        private static final float SWIPE_THRESHOLD_DP = 24f;
        private static final float SWIPE_AXIS_BIAS = 1.10f;

        private final Context context;
        private final String packageName;
        private final List<NotificationHistoryRecord> records;
        private final LinearLayout content;
        private final ImageView headerIcon;
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

        Session(Context context, String packageName, List<NotificationHistoryRecord> records,
                int startIndex) {
            this.context = context;
            this.packageName = packageName;
            this.records = records;
            this.index = startIndex;
            this.accent = AppNativeDialogStyle.accentForPackage(context, packageName);

            int pad = dp(16);
            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(pad, pad, pad, pad);

            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);

            headerIcon = new ImageView(context);
            headerIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int iconSize = dp(48);
            setHeaderIdentity(null);
            header.addView(headerIcon, new LinearLayout.LayoutParams(iconSize, iconSize));

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
                    && NotificationListener.isNotificationActive(
                    context, record.notificationId, record.postTime);
            String expanded = active
                    ? NotificationListener.getExpandedNotificationText(context, record.notificationId)
                    : record.text;
            if (expanded == null || expanded.trim().isEmpty()) expanded = record.text;

            NotificationRichPreview.Preview rich = NotificationRichPreview.create(
                    context, record.notificationId, packageName, record.postTime,
                    record.title, expanded);
            boolean media = rich != null && rich.media;

            setHeaderIdentity(record);
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

            String title = record.title == null ? "" : record.title.trim();
            String text = expanded == null ? "" : expanded.trim();
            String fullText;
            if (!title.isEmpty() && !text.startsWith(title)) {
                fullText = text.isEmpty() ? title : title + "\n" + text;
            } else {
                fullText = text.isEmpty() ? title : text;
            }
            body.setText(fullText);
            body.setVisibility(fullText.isEmpty() ? View.GONE : View.VISIBLE);

            nativeArea.removeAllViews();
            actionArea.removeAllViews();
            if (active && !media) {
                View nativeView = NotificationListener.createNativeNotificationView(
                        context, record.notificationId, record.postTime, nativeArea, true);
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

            addOpenInHistoryAction(record);

            if (dialog.isShowing()) AppNativeDialogStyle.styleDialog(dialog, packageName);
        }

        private void addActiveActions(NotificationHistoryRecord record) {
            int pad = dp(8);
            if (NotificationListener.hasReplyAction(
                    context, record.notificationId, record.postTime)) {
                LinearLayout replyRow = new LinearLayout(context);
                replyRow.setOrientation(LinearLayout.HORIZONTAL);
                replyRow.setGravity(Gravity.CENTER_VERTICAL);
                replyRow.setPadding(0, pad, 0, 0);

                EditText reply = new EditText(context);
                reply.setHint("Reply");
                reply.setSingleLine(false);
                AppNativeDialogStyle.setReadableText(reply);
                replyRow.addView(reply, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button send = new Button(context);
                send.setText("Reply");
                configureActionButton(send);
                AppNativeDialogStyle.styleButton(send, accent);
                send.setOnClickListener(v -> {
                    String message = reply.getText().toString();
                    if (message.trim().isEmpty()) return;
                    if (NotificationListener.replyToNotification(
                            context, record.notificationId, record.postTime, message)) {
                        reply.setText("");
                    } else {
                        Toast.makeText(context, "Unable to send reply",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sendParams.setMargins(dp(8), 0, 0, 0);
                replyRow.addView(send, sendParams);
                actionArea.addView(replyRow);
            }

            LinearLayout buttons = newActionRow(pad);
            addActionButton(buttons, markReadButton(record, true), false);

            Button open = new Button(context);
            open.setText("Open notification");
            AppNativeDialogStyle.styleButton(open, accent);
            open.setOnClickListener(v -> {
                SavedNotificationDestinationResolver.OpenResult result =
                        SavedNotificationDestinationResolver.openExactOrAppResult(context, record);
                if (result.accepted()) {
                    SmartAnimationEngine.dismissDialog(dialog);
                } else if (result == SavedNotificationDestinationResolver.OpenResult.APP_NOT_INSTALLED) {
                    AppReinstallSupport.showUninstalledDialog(
                            context, record.packageName, record.appName);
                } else if (result == SavedNotificationDestinationResolver.OpenResult.APP_DISABLED_CANNOT_ENABLE) {
                    Toast.makeText(context, "The app could not be re-enabled.",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "Direct notification/message link is unavailable.",
                            Toast.LENGTH_SHORT).show();
                }
            });
            addActionButton(buttons, open, true);
            actionArea.addView(buttons);
        }

        private void addSavedOpenAction(NotificationHistoryRecord record) {
            LinearLayout buttons = newActionRow(dp(8));
            addActionButton(buttons, markReadButton(record, false), false);

            Button open = new Button(context);
            open.setText("Open notification");
            AppNativeDialogStyle.styleButton(open, accent);
            open.setOnClickListener(v -> {
                SavedNotificationDestinationResolver.OpenResult result =
                        SavedNotificationDestinationResolver.openExactOrAppResult(context, record);
                if (result == SavedNotificationDestinationResolver.OpenResult.APP_NOT_INSTALLED) {
                    AppReinstallSupport.showUninstalledDialog(
                            context, packageName, record.appName);
                    return;
                }
                if (result == SavedNotificationDestinationResolver.OpenResult.APP_DISABLED_CANNOT_ENABLE) {
                    Toast.makeText(context, "The app could not be re-enabled.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!result.accepted()) {
                    Toast.makeText(context, "Direct notification/message link is unavailable.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                SmartAnimationEngine.dismissDialog(dialog);
            });
            addActionButton(buttons, open, true);
            actionArea.addView(buttons);

            if (!SavedNotificationDestinationResolver.hasExactTarget(context, record)) {
                LinearLayout fixRow = newActionRow(dp(6));
                Button fix = new Button(context);
                fix.setText("Fix notification link");
                AppNativeDialogStyle.styleButton(fix, accent);
                fix.setOnClickListener(v -> {
                    if (NotificationListener.rebindStaleNotification(context, record)) {
                        Toast.makeText(context, "Notification link restored",
                                Toast.LENGTH_SHORT).show();
                        render();
                    } else {
                        Toast.makeText(context,
                                "No matching notification found to fix this link",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                addActionButton(fixRow, fix, false);
                actionArea.addView(fixRow);
            }
        }

        private void addOpenInHistoryAction(NotificationHistoryRecord record) {
            if (record == null) return;

            LinearLayout row = newActionRow(dp(8));

            Button usage = new Button(context);
            usage.setText("App usage history");
            AppNativeDialogStyle.styleButton(usage, accent);
            usage.setOnClickListener(v -> {
                SmartAnimationEngine.dismissDialog(dialog);
                AppUsageActivity.openForPackage(
                        context, record.packageName, record.appName);
            });
            addActionButton(row, usage, false);

            if (record.dbId > 0L) {
                Button openHistory = new Button(context);
                openHistory.setText("Notification history");
                AppNativeDialogStyle.styleButton(openHistory, accent);
                openHistory.setOnClickListener(v -> {
                    SmartAnimationEngine.dismissDialog(dialog);
                    NotificationHistoryActivity.openExactHistoryRecord(context, record);
                });
                addActionButton(row, openHistory, true);
            }
            actionArea.addView(row);
        }

        private LinearLayout newActionRow(int topPadding) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, topPadding, 0, 0);
            return row;
        }

        private void addActionButton(LinearLayout row, Button button, boolean addLeadingGap) {
            configureActionButton(button);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (addLeadingGap) params.setMargins(dp(8), 0, 0, 0);
            row.addView(button, params);
        }

        private void configureActionButton(Button button) {
            if (button == null) return;
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMaxLines(2);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(8), dp(6), dp(8), dp(6));
            button.setMinimumHeight(dp(40));
        }

        private Button markReadButton(NotificationHistoryRecord record, boolean active) {
            Button markRead = new Button(context);
            markRead.setText("Mark read");
            AppNativeDialogStyle.styleButton(markRead, accent);
            markRead.setOnClickListener(v -> {
                boolean systemMarked = active && NotificationListener.markNotificationRead(
                        context, record.notificationId, record.postTime);
                if (!systemMarked && record.notificationId != null
                        && !record.notificationId.isEmpty()) {
                    // Saved notifications no longer have an Android panel action to invoke, but
                    // Smart S still owns unread state for their History identity.
                    NotificationUnreadStore.markRead(context, record.notificationId);
                }
                render();
            });
            return markRead;
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

        private void setHeaderIdentity(NotificationHistoryRecord record) {
            android.graphics.drawable.Drawable identity = NotificationIdentityIcon.resolve(
                    context, record == null ? null : record.notificationId, packageName);
            headerIcon.setImageDrawable(identity);
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
