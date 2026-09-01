package fr.neamar.kiss.broadcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.TelephonyManager;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.dataprovider.ContactsProvider;
import fr.neamar.kiss.dataprovider.simpleprovider.PhoneProvider;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.utils.CallerNameResolver;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.PackageManagerUtils;

public class IncomingCallHandler extends BroadcastReceiver {

    private static final String TAG = IncomingCallHandler.class.getSimpleName();

    @Override
    public void onReceive(final Context context, Intent intent) {
        // Only handle calls received
        if (!"android.intent.action.PHONE_STATE".equals(intent.getAction())) {
            return;
        }

        try {
            DataHandler dataHandler = KissApplication.getApplication(context).getDataHandler();
            ContactsProvider contactsProvider = dataHandler.getContactsProvider();

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE))) {
                String phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

                if (phoneNumber == null) {
                    // Skipping (private call)
                    return;
                }

                ContactsPojo contactPojo = contactsProvider == null ? null : contactsProvider.findByPhone(phoneNumber);
                CallerNameResolver.invalidateCallLogCache();
                dataHandler.addToHistory(contactPojo != null
                        ? contactPojo.getHistoryId()
                        : PhoneProvider.getHistoryId(phoneNumber));
            }
        } catch (Exception e) {
            Log.e(TAG, "Phone Receive Error", e);
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PackageManagerUtils.enableComponent(context, IncomingCallHandler.class, false);
        } else {
            PackageManagerUtils.enableComponent(context, IncomingCallHandler.class, enabled);
        }

    }
}
