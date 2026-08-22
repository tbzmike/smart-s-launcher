package fr.neamar.kiss.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BatteryBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BatteryMonitorStarter.ensureRunning(context);
        BatteryWidgetProvider.updateAll(context);
    }
}
