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
        store.close();

        RemoteViews v = new RemoteViews(context.getPackageName(), detailed
                ? R.layout.widget_battery_detailed : R.layout.widget_battery_compact);
        v.setTextViewText(R.id.battery_widget_percent, s.percent() + "%");
        v.setTextViewText(R.id.battery_widget_state, s.isCharging() ? "Charging" : "Discharging");
        String current = Double.isNaN(s.currentMa()) ? "— mA"
                : String.format(Locale.US, "%.0f mA", Math.abs(s.currentMa()));
        String power = Double.isNaN(s.powerW()) ? "— W" : String.format(Locale.US, "%.2f W", s.powerW());
        v.setTextViewText(R.id.battery_widget_line1, current + " · " + power + " · " + String.format(Locale.US, "%.1f°C", s.temperatureC));
        v.setTextViewText(R.id.battery_widget_line2, s.voltageMv + " mV · " + BatteryMonitorEngine.sourceName(s.plugged));
        if (detailed) {
            String capacity = cap > 0 ? String.format(Locale.US, "Estimated full %.0f mAh", cap / 1000.0) : "Capacity learning…";
            String drain = Double.isNaN(avgDrain) ? "24h drain learning…" : String.format(Locale.US, "24h avg drain %.0f mA", avgDrain);
            String cycles = s.cycleCount >= 0 ? " · cycles " + s.cycleCount : "";
            v.setTextViewText(R.id.battery_widget_line3, capacity + cycles);
            v.setTextViewText(R.id.battery_widget_line4, drain + " · Health " + BatteryMonitorEngine.healthName(s.health));
        }
        Intent open = new Intent(context, BatteryMonitorActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 22, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.battery_widget_root, pi);
        return v;
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName compact = new ComponentName(context, BatteryWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(compact)) manager.updateAppWidget(id, build(context, false));
        ComponentName detailed = new ComponentName(context, BatteryDetailedWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(detailed)) manager.updateAppWidget(id, build(context, true));
    }
}
