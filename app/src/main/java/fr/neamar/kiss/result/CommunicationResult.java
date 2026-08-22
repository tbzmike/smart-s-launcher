package fr.neamar.kiss.result;

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

    CommunicationResult(@NonNull CommunicationPojo pojo) { super(pojo); }

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
                if (!pojo.address.isEmpty()) {
                    Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(pojo.address)));
                    dial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(dial);
                    return;
                }
                break;
            case SMS:
                if (!pojo.address.isEmpty()) {
                    Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(pojo.address)));
                    sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(sms);
                    return;
                }
                break;
            case TRUECALLER_NOTIFICATION:
                if (!pojo.notificationId.isEmpty() && NotificationListener.openNotification(context, pojo.notificationId)) return;
                if (!pojo.packageName.isEmpty() && AppLaunchUtils.launchPackage(context, pojo.packageName)) return;
                break;
        }
        Toast.makeText(context, "Unable to open this communication item", Toast.LENGTH_SHORT).show();
    }

    @Override protected boolean isAllowedAsFavorite() { return false; }
    @Override protected boolean canRemoveFromHistory(Context context) { return false; }
    @Override protected boolean canHaveCustomIcon(Context context, IconPack iconPack) { return false; }
}
