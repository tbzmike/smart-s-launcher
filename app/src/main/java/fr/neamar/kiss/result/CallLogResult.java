package fr.neamar.kiss.result;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.CallLog;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.Locale;

import fr.neamar.kiss.R;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.pojo.CallLogPojo;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

/** A real Android call-log row, distinct from a typed phone-number action or contact result. */
public final class CallLogResult extends Result<CallLogPojo> {
    private static final String TRUECALLER_PACKAGE = "com.truecaller";

    CallLogResult(@NonNull CallLogPojo pojo) {
        super(pojo);
    }

    @NonNull
    @Override
    public View display(Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null) view = inflateFromId(context, R.layout.item_phone, parent);

        TextView text = view.findViewById(R.id.item_phone_text);
        String line = buildDisplayLine(context);
        if (fuzzyScore != null && pojo.normalizedName != null) {
            displayHighlighted(pojo.normalizedName, line, fuzzyScore, text, context);
        } else {
            text.setText(line);
        }
        setAsyncDrawable(view.findViewById(R.id.item_phone_icon), 0);
        return view;
    }

    private String buildDisplayLine(Context context) {
        String time = DateFormat.getTimeFormat(context).format(pojo.callTimestamp);
        String type = callTypeLabel(pojo.callType);
        String duration = formatDuration(pojo.durationSeconds);
        String name = pojo.getName();
        String number = pojo.phoneNumber;

        StringBuilder line = new StringBuilder();
        if (name != null && !name.isEmpty()) line.append(name);
        if (number != null && !number.isEmpty() && !number.equals(name)) {
            if (line.length() > 0) line.append(" · ");
            line.append(number);
        }
        if (!type.isEmpty()) line.append(" · ").append(type);
        line.append(" · ").append(time);
        if (!duration.isEmpty()) line.append(" · ").append(duration);
        return line.toString();
    }

    private String callTypeLabel(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "Missed";
            case CallLog.Calls.REJECTED_TYPE:
                return "Rejected";
            case CallLog.Calls.BLOCKED_TYPE:
                return "Blocked";
            case CallLog.Calls.VOICEMAIL_TYPE:
                return "Voicemail";
            default:
                return "Call";
        }
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) return "";
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes <= 0) return seconds + "s";
        if (seconds == 0) return minutes + "m";
        return String.format(Locale.getDefault(), "%dm %02ds", minutes, seconds);
    }

    @Override
    public Drawable getDrawable(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationIcon(TRUECALLER_PACKAGE);
        } catch (PackageManager.NameNotFoundException ignored) {
            return getThemedDrawable(context, pojo, R.drawable.ic_phone);
        }
    }

    @Override
    protected void doLaunch(Context context, View v) {
        Uri telUri = Uri.parse("tel:" + Uri.encode(pojo.phoneNumber));
        PackageManager pm = context.getPackageManager();

        // First preference: let Truecaller handle the exact number if it explicitly advertises
        // support for this tel: VIEW intent. This cannot resolve to a browser because the package
        // is pinned to Truecaller.
        Intent truecallerNumber = new Intent(Intent.ACTION_VIEW, telUri)
                .setPackage(TRUECALLER_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        setSourceBounds(truecallerNumber, v);
        if (pm.resolveActivity(truecallerNumber, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            try {
                context.startActivity(truecallerNumber);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Fall through to Truecaller's main launcher activity.
            }
        }

        // If Truecaller is installed but does not expose a public deep link for this number, open
        // Truecaller itself rather than sending the result to a web search/browser.
        Intent truecallerMain = pm.getLaunchIntentForPackage(TRUECALLER_PACKAGE);
        if (truecallerMain != null) {
            truecallerMain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            setSourceBounds(truecallerMain, v);
            try {
                context.startActivity(truecallerMain);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Fall through to the system/default dialer.
            }
        }

        Intent dial = new Intent(Intent.ACTION_DIAL, telUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        setSourceBounds(dial, v);
        try {
            context.startActivity(dial);
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(context, "No phone app can open this call", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected boolean canRemoveFromHistory(Context context) {
        return true;
    }

    @Override
    protected boolean canHaveCustomIcon(Context context, IconPack iconPack) {
        return true;
    }
}
