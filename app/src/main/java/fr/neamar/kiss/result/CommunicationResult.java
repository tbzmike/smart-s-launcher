package fr.neamar.kiss.result;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.text.DateFormat;
import java.util.Date;

import fr.neamar.kiss.R;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public final class CommunicationResult extends Result<CommunicationPojo> {
    private volatile Drawable icon;

    public CommunicationResult(@NonNull CommunicationPojo pojo) { super(pojo); }

    @NonNull @Override
    public View display(Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null) view = inflateFromId(context, R.layout.item_search, parent);
        ImageView image = view.findViewById(R.id.item_search_icon);
        TextView text = view.findViewById(R.id.item_search_text);
        if (isHideIcons(context)) image.setImageDrawable(null); else setAsyncDrawable(image);

        String kind;
        switch (pojo.kind) {
            case CALL: kind = "Call"; break;
            case SMS: kind = "Message"; break;
            default: kind = "Truecaller"; break;
        }
        String display = pojo.getName() == null ? "" : pojo.getName();
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(pojo.timestamp));
        text.setText(kind + " · " + display + " · " + when);
        text.setMaxLines(3);
        return view;
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
                if (!pojo.address.isEmpty()) {
                    Intent sms = new Intent(Intent.ACTION_SENDTO,
                            Uri.parse("smsto:" + Uri.encode(pojo.address)));
                    sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    setSourceBounds(sms, v);
                    try {
                        context.startActivity(sms);
                        return;
                    } catch (ActivityNotFoundException | SecurityException ignored) { }
                }
                break;
            case TRUECALLER_NOTIFICATION:
                if (!pojo.notificationId.isEmpty()
                        && NotificationListener.openNotification(context, pojo.notificationId)) return;
                if (!pojo.packageName.isEmpty()
                        && AppLaunchUtils.launchPackage(context, pojo.packageName)) return;
                break;
        }
        Toast.makeText(context, "Unable to open this communication item", Toast.LENGTH_SHORT).show();
    }

    private boolean openCallInApp(Context context, View v) {
        Uri tel = Uri.parse("tel:" + Uri.encode(pojo.address));
        PackageManager pm = context.getPackageManager();

        // CommunicationIndexer stores the owning phone app package for call-log rows (currently
        // Truecaller). Try an explicit tel: deep link first. Because the package is pinned, this
        // path cannot resolve to a browser.
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

            // Some versions of Truecaller do not publicly expose a number-specific VIEW intent.
            // In that case open the app itself rather than changing this call-history item into a
            // web URL. This is still the user's requested app-first behavior.
            if (AppLaunchUtils.launchPackage(context, pojo.packageName)) return true;
        }

        // Truecaller missing/unavailable: use Android's dialer resolution. This remains tel:, never
        // http/https, so call history cannot fall through to a web browser.
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
