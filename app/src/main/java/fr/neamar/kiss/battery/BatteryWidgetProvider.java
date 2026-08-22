package fr.neamar.kiss.battery;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;

import java.util.Locale;

import fr.neamar.kiss.BatteryHistoryActivity;
import fr.neamar.kiss.BatteryMonitorActivity;
import fr.neamar.kiss.BatteryWidgetStyleActivity;
import fr.neamar.kiss.R;

public class BatteryWidgetProvider extends AppWidgetProvider {
    public static final String PREF_WIDGET_STYLE = "smart-battery-widget-style";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, build(context, false));
        BatteryMonitorStarter.ensureRunning(context);
    }

    static RemoteViews build(Context context, boolean detailed) {
        BatterySnapshot s = BatteryMonitorEngine.read(context);
        BatteryHistoryStore store = new BatteryHistoryStore(context);
        long cap = store.estimatedFullCapacityUah();
        BatteryHistoryStore.CurrentSessionStats session = store.currentSessionStats(s);
        double cycles30 = store.equivalentChargeCycles(30L * 86_400_000L);
        store.close();
        int design = BatteryCapacityEstimator.designCapacityMah(context);
        double health = BatteryCapacityEstimator.healthPercent(context, cap);

        RemoteViews v = new RemoteViews(context.getPackageName(), detailed
                ? R.layout.widget_battery_detailed : R.layout.widget_battery_compact);
        v.setImageViewResource(R.id.battery_widget_background, widgetBackground(context));
        v.setTextViewText(R.id.battery_widget_percent, s.percent() + "%");
        String sessionAge = session.durationMs >= 60_000L ? " · " + formatDuration(session.durationMs) : "";
        v.setTextViewText(R.id.battery_widget_state, s.isCharging()
                ? "Charging · " + BatteryMonitorEngine.sourceName(s.plugged) + sessionAge
                : "Discharging" + sessionAge);

        double nowMa = s.currentMa();
        if (!Double.isNaN(nowMa)) nowMa = s.isCharging() ? Math.abs(nowMa) : -Math.abs(nowMa);
        String remaining = session.estimatedRemainingMs == Long.MIN_VALUE ? ""
                : " (" + formatDuration(session.estimatedRemainingMs) + (s.isCharging() ? " to full" : " left") + ")";
        String now = Double.isNaN(nowMa) ? "Now: — mA"
                : String.format(Locale.US, "Now: %+.0f mA", nowMa);
        v.setTextViewText(R.id.battery_widget_line1, now + remaining);

        String avg = Double.isNaN(session.averageCurrentMa) ? "Avg: learning…"
                : String.format(Locale.US, "Avg: %+.0f mA", session.averageCurrentMa);
        if (!Double.isNaN(session.percentPerHour)) avg += String.format(Locale.US, " · %+.1f%%/h", session.percentPerHour);
        if (!Double.isNaN(session.totalMah)) avg += String.format(Locale.US, " · %+.0f mAh total", s.isCharging() ? session.totalMah : -session.totalMah);
        v.setTextViewText(R.id.battery_widget_line2, avg);

        if (detailed) {
            v.setTextViewText(R.id.battery_widget_line3, formatScreenLine("Screen on", session.screenOnCurrentMa, session.screenOnPercentPerHour));
            v.setTextViewText(R.id.battery_widget_line4, formatScreenLine("Screen off", session.screenOffCurrentMa, session.screenOffPercentPerHour));
            String temp = Float.isNaN(s.temperatureC) ? "—°C" : String.format(Locale.US, "%.1f°C", s.temperatureC);
            String voltage = s.voltageMv > 0 ? s.voltageMv + " mV" : "— mV";
            String power = Double.isNaN(s.powerW()) ? "— W" : String.format(Locale.US, "%.2f W", s.powerW());
            v.setTextViewText(R.id.battery_widget_line5, power + " · " + temp + " · " + voltage);
            String capacity = cap > 0 ? String.format(Locale.US, "%.0f mAh", cap / 1000.0) : "learning";
            String healthText = Double.isNaN(health) ? "health learning" : String.format(Locale.US, "health %.1f%%", health);
            String designText = design > 0 ? " / " + design + " design" : "";
            v.setTextViewText(R.id.battery_widget_line6, "Capacity " + capacity + designText + " · " + healthText
                    + String.format(Locale.US, " · 30d cycles %.2f", cycles30));
        }

        Intent open = new Intent(context, detailed ? BatteryHistoryActivity.class : BatteryMonitorActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, detailed ? 23 : 22, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.battery_widget_root, pi);

        Intent style = new Intent(context, BatteryWidgetStyleActivity.class);
        PendingIntent stylePi = PendingIntent.getActivity(context, detailed ? 25 : 24, style,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.battery_widget_style, stylePi);
        return v;
    }

    public static int widgetBackground(Context context) {
        String style = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_WIDGET_STYLE, "material_you");
        if ("google_pill".equals(style)) return R.drawable.battery_widget_google_pill;
        if ("squircle".equals(style)) return R.drawable.battery_widget_squircle;
        if ("glass".equals(style)) return R.drawable.battery_widget_glass;
        if ("soft_card".equals(style)) return R.drawable.battery_widget_soft_card;
        if ("pixel".equals(style)) return R.drawable.battery_widget_pixel;
        if ("stadium".equals(style)) return R.drawable.battery_widget_stadium;
        return R.drawable.battery_widget_material_you;
    }

    private static String formatScreenLine(String label, double ma, double percentPerHour) {
        String text = Double.isNaN(ma) ? label + ": learning…" : String.format(Locale.US, "%s: %+.0f mA", label, ma);
        if (!Double.isNaN(percentPerHour)) text += String.format(Locale.US, " · %+.1f%%/h", percentPerHour);
        return text;
    }

    private static String formatDuration(long ms) {
        long minutes = Math.max(0L, ms / 60_000L);
        long hours = minutes / 60L;
        long rem = minutes % 60L;
        return hours > 0 ? hours + "h " + rem + "m" : rem + "m";
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName compact = new ComponentName(context, BatteryWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(compact)) manager.updateAppWidget(id, build(context, false));
        ComponentName detailed = new ComponentName(context, BatteryDetailedWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(detailed)) manager.updateAppWidget(id, build(context, true));
    }
}
