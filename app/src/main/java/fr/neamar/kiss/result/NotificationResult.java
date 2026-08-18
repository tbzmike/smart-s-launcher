package fr.neamar.kiss.result;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import fr.neamar.kiss.R;
import fr.neamar.kiss.icons.IconPack;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public final class NotificationResult extends Result<NotificationPojo> {
    NotificationResult(@NonNull NotificationPojo pojo) {
        super(pojo);
    }

    @NonNull
    @Override
    public View display(Context context, View view, @NonNull ViewGroup parent, FuzzyScore fuzzyScore) {
        if (view == null) view = inflateFromId(context, R.layout.item_notification_timeline, parent);

        TextView appName = view.findViewById(R.id.item_notification_app);
        TextView title = view.findViewById(R.id.item_notification_title);
        TextView text = view.findViewById(R.id.item_notification_text);
        Button dismiss = view.findViewById(R.id.item_notification_dismiss);
        ImageView icon = view.findViewById(R.id.item_notification_icon);

        appName.setText(pojo.appName);
        title.setText(pojo.getDisplayTitle());
        title.setVisibility(pojo.getDisplayTitle().isEmpty() ? View.GONE : View.VISIBLE);
        text.setText(pojo.getDisplayText());
        text.setVisibility(pojo.getDisplayText().isEmpty() ? View.GONE : View.VISIBLE);

        if (!isHideIcons(context)) setAsyncDrawable(icon);
        else icon.setImageDrawable(null);

        dismiss.setOnClickListener(v -> {
            if (NotificationListener.dismissNotification(context, pojo.id)) {
                dismiss.setEnabled(false);
                dismiss.setText(R.string.notification_dismissed);
            } else {
                Toast.makeText(context, R.string.notification_dismiss_failed, Toast.LENGTH_SHORT).show();
            }
        });
        return view;
    }

    @Override
    public Drawable getDrawable(Context context) {
        try {
            return context.getPackageManager().getApplicationIcon(pojo.packageName);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    @Override
    public void doLaunch(Context context, View v) {
        if (NotificationListener.openNotification(context, pojo.id)) return;
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(pojo.packageName);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launchIntent);
        } else {
            Toast.makeText(context, R.string.application_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected boolean isAllowedAsFavorite() {
        return false;
    }

    @Override
    protected boolean canRemoveFromHistory(Context context) {
        return false;
    }

    @Override
    protected boolean canHaveCustomIcon(Context context, IconPack iconPack) {
        return false;
    }
}
