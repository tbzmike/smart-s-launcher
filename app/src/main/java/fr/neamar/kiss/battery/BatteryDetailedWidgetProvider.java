package fr.neamar.kiss.battery;

import android.appwidget.AppWidgetManager;
import android.content.Context;

public final class BatteryDetailedWidgetProvider extends BatteryWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, build(context, true));
        BatteryMonitorStarter.ensureRunning(context);
    }
}
