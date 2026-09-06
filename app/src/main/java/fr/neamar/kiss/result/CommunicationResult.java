package fr.neamar.kiss.result;

import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Telephony;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public final class CommunicationResult extends Result<CommunicationPojo> {
    private static final long ACTIVE_MESSAGE_MATCH_WINDOW_MS = 10 * 60_000L;
    private volatile Drawable icon;

    public CommunicationResult(@NonNull CommunicationPojo pojo) { super(pojo); }

    @NonNull @Override
    public View display(Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null) view = inflateFromId(context, R.layout.item_communication, parent);

        ImageView image = view.findViewById(R.id.item_search_icon);
        TextView title = view.findViewById(R.id.item_communication_title);
        TextView meta = view.findViewById(R.id.item_communication_meta);
        TextView body = view.findViewById(R.id.item_communication_body);
        View actions = view.findViewById(R.id.item_communication_actions);
        Button markRead = view.findViewById(R.id.item_communication_mark_read);
        Button open = view.findViewById(R.id.item_communication_open);

        if (isHideIcons(context)) image.setImageDrawable(null); else setAsyncDrawable(image);

        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(pojo.timestamp));
        String label = pojo.primaryLabel();
        if (TextUtils.isEmpty(label)) label = pojo.address;

        switch (pojo.kind) {
            case CALL:
                title.setText(TextUtils.isEmpty(label) ? "Call" : label);
                meta.setText("Call · " + when + (TextUtils.isEmpty(pojo.address) ? "" : " · " + pojo.address));
                body.setText(pojo.body);
                body.setVisibility(TextUtils.isEmpty(pojo.body) ? View.GONE : View.VISIBLE);
                actions.setVisibility(View.GONE);
                break;

            case SMS:
                title.setText(TextUtils.isEmpty(label) ? "Message" : label);
                meta.setText("Message · " + when + (TextUtils.isEmpty(pojo.address) ? "" : " · " + pojo.address));
                String smsNotificationId = effectiveNotificationId(context);
                String smsExpanded = !TextUtils.isEmpty(smsNotificationId)
                        ? NotificationListener.getExpandedNotificationText(context, smsNotificationId)
                        : "";
                body.setText(!TextUtils.isEmpty(smsExpanded) ? smsExpanded : cleanMessageBody(pojo.body));
                body.setVisibility(View.VISIBLE);
                actions.setVisibility(View.VISIBLE);
                configureVerifiedMarkRead(markRead, smsNotificationId);
                open.setText("Open message");
                open.setOnClickListener(v -> openMessageAndRecord(v.getContext(), v));
                break;

            case TRUECALLER_NOTIFICATION:
            default:
                title.setText(TextUtils.isEmpty(label) ? "Truecaller" : label);
                meta.setText("Message · " + when);
                String notificationId = effectiveNotificationId(context);
                String expanded = !TextUtils.isEmpty(notificationId)
                        ? NotificationListener.getExpandedNotificationText(context, notificationId)
                        : "";
                body.setText(!TextUtils.isEmpty(expanded) ? expanded : pojo.body);
                body.setVisibility(View.VISIBLE);
                actions.setVisibility(View.VISIBLE);
                configureVerifiedMarkRead(markRead, notificationId);
                open.setText("Open message");
                open.setOnClickListener(v -> openMessageAndRecord(v.getContext(), v));
                break;
        }

        title.setSelected(true);
        return view;
    }

    private void openMessageAndRecord(Context context, View view) {
        RecentLaunchTracker.remember(pojo);
        promoteVisibleHistoryItem(context);
        recordLaunch(context, null);
        openMessage(context, view, true);
    }

    private void promoteVisibleHistoryItem(Context context) {
        if (!(context instanceof MainActivity)) return;
        MainActivity activity = (MainActivity) context;
        if (activity.adapter != null) activity.adapter.promoteHistoryPojo(pojo);
    }

    private void configureVerifiedMarkRead(Button markRead, String notificationId) {
        boolean active = !TextUtils.isEmpty(notificationId);
        markRead.setVisibility(active ? View.VISIBLE : View.GONE);
        markRead.setEnabled(active);
        markRead.setOnClickListener(active
                ? v -> markMessageRead(v.getContext()) : null);
    }

    private String cleanMessageBody(String raw) {
        if (raw == null) return "";
        String body = raw.trim();
        if (body.startsWith("SMS · ")) return body.substring(6).trim();
        if (body.startsWith("Sent SMS · ")) return body.substring(11).trim();
        return body;
    }

    @Override public Drawable getDrawable(Context context) {
        if (icon != null) return icon;
        synchronized (this) {
            if (icon != null) return icon;
            if (!pojo.packageName.isEmpty()) {
                try {
                    PackageManager pm = context.getPackageManager();
                    ApplicationInfo info = pm.getApplicationInfo(pojo.packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
                    icon = info.loadIcon(pm);
                } catch (PackageManager.NameNotFoundException ignored) { }
            }
            if (icon == null) icon = context.getDrawable(android.R.drawable.sym_action_call);
            return icon;
        }
    }

    @Override boolean isDrawableCached() { return icon != null; }
    @Override void setDrawableCache(Drawable drawable) { icon = drawable; }

    @Override protected void doLaunch(Context context, View v) {
        switch (pojo.kind) {
            case CALL:
                if (!pojo.address.isEmpty() && openCallInApp(context, v)) return;
                break;
            case SMS:
            case TRUECALLER_NOTIFICATION:
                if (openMessage(context, v, false)) return;
                break;
        }
        Toast.makeText(context, "Unable to open this communication item", Toast.LENGTH_SHORT).show();
    }

    private boolean openMessage(Context context, View v, boolean showFailure) {
        String notificationId = effectiveNotificationId(context);
        if (!TextUtils.isEmpty(notificationId)
                && NotificationListener.openNotification(context, notificationId)) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        String sourceId = sourceId("sms");

        if (!TextUtils.isEmpty(sourceId) && sourceId.matches("[0-9]+")) {
            Uri messageUri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, sourceId);
            if (!TextUtils.isEmpty(pojo.packageName)
                    && tryStart(context, v, new Intent(Intent.ACTION_VIEW, messageUri)
                    .setPackage(pojo.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pm)) return true;
        }

        if (!TextUtils.isEmpty(pojo.address)) {
            Uri conversation = Uri.parse("smsto:" + Uri.encode(pojo.address));
            if (!TextUtils.isEmpty(pojo.packageName)
                    && tryStart(context, v, new Intent(Intent.ACTION_VIEW, conversation)
                    .setPackage(pojo.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pm)) return true;

            if (tryStart(context, v, new Intent(Intent.ACTION_VIEW, conversation)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pm)) return true;

            if (tryStart(context, v, new Intent(Intent.ACTION_SENDTO, conversation)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), pm)) return true;
        }

        if (!TextUtils.isEmpty(pojo.packageName)
                && AppLaunchUtils.launchPackage(context, pojo.packageName)) return true;

        if (showFailure) Toast.makeText(context, "Unable to open this exact message", Toast.LENGTH_SHORT).show();
        return false;
    }

    private boolean tryStart(Context context, View source, Intent intent, PackageManager pm) {
        if (intent.getPackage() != null && !AppLaunchUtils.ensurePackageEnabled(context, intent.getPackage())) {
            return false;
        }
        if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return false;
        setSourceBounds(intent, source);
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private void markMessageRead(Context context) {
        RecentLaunchTracker.remember(pojo);
        promoteVisibleHistoryItem(context);
        recordLaunch(context, null);
        String notificationId = effectiveNotificationId(context);
        if (!TextUtils.isEmpty(notificationId)
                && NotificationListener.markNotificationRead(context, notificationId)) {
            Toast.makeText(context, "Marked as read", Toast.LENGTH_SHORT).show();
            return;
        }

        if (pojo.kind == CommunicationPojo.Kind.SMS && markSmsProviderRead(context)) {
            Toast.makeText(context, "Marked as read", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(context, "This message cannot be marked read directly; open the message instead",
                Toast.LENGTH_SHORT).show();
    }

    private String effectiveNotificationId(Context context) {
        if (!TextUtils.isEmpty(pojo.notificationId)) {
            return NotificationListener.isNotificationActive(context, pojo.notificationId)
                    ? pojo.notificationId : "";
        }
        if (pojo.kind != CommunicationPojo.Kind.SMS) return "";

        SharedPreferences details = context.getSharedPreferences(
                NotificationListener.DETAIL_PREFERENCES_NAME, Context.MODE_PRIVATE);
        Set<String> activeIds = NotificationListener.getVerifiedActiveNotificationIds();
        if (activeIds.isEmpty()) return "";

        String expectedBody = normalizeForMatch(cleanMessageBody(pojo.body));
        String expectedAddress = normalizeForMatch(pojo.address);
        String bestId = "";
        long bestDistance = Long.MAX_VALUE;

        for (String id : activeIds) {
            if (TextUtils.isEmpty(id)) continue;
            String pkg = details.getString(id + "|package", "");
            if (!TextUtils.isEmpty(pojo.packageName) && !pojo.packageName.equals(pkg)) continue;

            long post = details.getLong(id + "|post", 0L);
            long distance = Math.abs(post - pojo.timestamp);
            if (post <= 0L || distance > ACTIVE_MESSAGE_MATCH_WINDOW_MS) continue;

            String title = normalizeForMatch(details.getString(id + "|title", ""));
            String text = normalizeForMatch(details.getString(id + "|text", ""));
            String combined = (title + " " + text).trim();

            boolean bodyMatches = !TextUtils.isEmpty(expectedBody)
                    && (combined.contains(expectedBody)
                    || (!TextUtils.isEmpty(text) && expectedBody.contains(text)));
            boolean addressMatches = !TextUtils.isEmpty(expectedAddress) && combined.contains(expectedAddress);
            if (!bodyMatches && !addressMatches) continue;

            if (distance < bestDistance) {
                bestDistance = distance;
                bestId = id;
            }
        }
        return bestId;
    }

    private String normalizeForMatch(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean markSmsProviderRead(Context context) {
        String sourceId = sourceId("sms");
        if (TextUtils.isEmpty(sourceId) || !sourceId.matches("[0-9]+")) return false;
        Uri uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, sourceId);
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.READ, 1);
        try {
            if (context.getContentResolver().update(uri, values, null, null) > 0) return true;
        } catch (RuntimeException ignored) { }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("root-mode", false)) return false;
        return markSmsReadWithRoot(sourceId);
    }

    private boolean markSmsReadWithRoot(String sourceId) {
        if (!sourceId.matches("[0-9]+")) return false;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            String command = "content update --uri content://sms/" + sourceId + " --bind read:i:1\nexit\n";
            process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            process.getOutputStream().close();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException | SecurityException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private String sourceId(String expectedSource) {
        String id = pojo.id == null ? "" : pojo.id;
        String prefix = "communication://" + expectedSource + "/";
        return id.startsWith(prefix) ? id.substring(prefix.length()) : "";
    }

    private boolean openCallInApp(Context context, View v) {
        Uri tel = Uri.parse("tel:" + Uri.encode(pojo.address));
        PackageManager pm = context.getPackageManager();

        if (!pojo.packageName.isEmpty()) {
            Intent appNumber = new Intent(Intent.ACTION_VIEW, tel)
                    .setPackage(pojo.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            setSourceBounds(appNumber, v);
            if (pm.resolveActivity(appNumber, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                try {
                    context.startActivity(appNumber);
                    return true;
                } catch (ActivityNotFoundException | SecurityException ignored) { }
            }
            if (AppLaunchUtils.launchPackage(context, pojo.packageName)) return true;
        }

        Intent dial = new Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        setSourceBounds(dial, v);
        try {
            context.startActivity(dial);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    @Override protected boolean isAllowedAsFavorite() { return false; }
    @Override protected boolean canRemoveFromHistory(Context context) {
        return pojo.kind == CommunicationPojo.Kind.CALL;
    }
    @Override protected boolean canHaveCustomIcon(Context context, IconPack iconPack) { return false; }
}
