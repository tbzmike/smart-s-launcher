package fr.neamar.kiss.battery;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.Locale;

import fr.neamar.kiss.BatteryMonitorActivity;
import fr.neamar.kiss.R;

public class BatteryWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, build(context, false));
        BatteryMonitorStarter.ensureRunning(context);
    }

    static RemoteViews build(Context context, boolean detailed) {
        BatterySnapshot s = BatteryMonitorEngine.read(context);
        BatteryHistoryStore store = new BatteryHistoryStore(context);
        long cap = store.estimatedFullCapacityUah();
        double avgDrain = store.averageDrainMa24h();
        double screenOn = store.averageScreenOnDrainMa24h();
        double screenOff = store.averageScreenOffDrainMa24h();
        double cycles30 = store.equivalentChargeCycles(30L * 86_400_000L);
        store.close();
        int design = BatteryCapacityEstimator.designCapacityMah(context);
        double health = BatteryCapacityEstimator.healthPercent(context, cap);

        RemoteViews v = new RemoteViews(context.getPackageName(), detailed
                ? R.layout.widget_battery_detailed : R.layout.widget_battery_compact);
        v.setTextViewText(R.id.battery_widget_percent, s.percent() + "%");
        v.setTextViewText(R.id.battery_widget_state, s.isCharging()
                ? "Charging · " + BatteryMonitorEngine.sourceName(s.plugged) : "Discharging");
        String current = Double.isNaN(s.currentMa()) ? "— mA"
                : String.format(Locale.US, "%.0f mA", Math.abs(s.currentMa()));
        String power = Double.isNaN(s.powerW()) ? "— W" : String.format(Locale.US, "%.2f W", s.powerW());
        String temp = Float.isNaN(s.temperatureC) ? "—°C" : String.format(Locale.US, "%.1f°C", s.temperatureC);
        v.setTextViewText(R.id.battery_widget_line1, current + " · " + power + " · " + temp);
        String voltage = s.voltageMv > 0 ? s.voltageMv + " mV" : "— mV";
        String time = s.chargeTimeRemainingMs == Long.MIN_VALUE ? "" : " · full in " + formatDuration(s.chargeTimeRemainingMs);
        v.setTextViewText(R.id.battery_widget_line2, voltage + time);
        if (detailed) {
            String capacity = cap > 0 ? String.format(Locale.US, "Capacity %.0f mAh", cap / 1000.0) : "Capacity learning…";
            if (design > 0) capacity += " / " + design + " design";
            String healthText = Double.isNaN(health) ? "Health learning…" : String.format(Locale.US, "Health %.1f%%", health);
            String hardwareCycles = s.cycleCount >= 0 ? " · hw cycles " + s.cycleCount : "";
            v.setTextViewText(R.id.battery_widget_line3, capacity + " · " + healthText + hardwareCycles);
            String on = Double.isNaN(screenOn) ? "on —" : String.format(Locale.US, "on %.0f", screenOn);
            String off = Double.isNaN(screenOff) ? "off —" : String.format(Locale.US, "off %.0f", screenOff);
            String drain = Double.isNaN(avgDrain) ? "24h drain learning" : String.format(Locale.US, "24h %.0f mA", avgDrain);
            v.setTextViewText(R.id.battery_widget_line4, drain + " · screen " + on + "/" + off
                    + " · 30d cycles " + String.format(Locale.US, "%.2f", cycles30));
        }
        Intent open = new Intent(context, BatteryMonitorActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 22, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.battery_widget_root, pi);
        return v;
    }

    private static String formatDuration(long ms) {
        long minutes = Math.max(0L, ms / 60_000L);
        return (minutes / 60) + "h" + (minutes % 60) + "m";
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName compact = new ComponentName(context, BatteryWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(compact)) manager.updateAppWidget(id, build(context, false));
        ComponentName detailed = new ComponentName(context, BatteryDetailedWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(detailed)) manager.updateAppWidget(id, build(context, true));
    }
}
