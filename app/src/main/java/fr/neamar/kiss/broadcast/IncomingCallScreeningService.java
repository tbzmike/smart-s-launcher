package fr.neamar.kiss.broadcast;

import android.content.SharedPreferences;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.dataprovider.ContactsProvider;
import fr.neamar.kiss.dataprovider.simpleprovider.PhoneProvider;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.utils.CallerNameResolver;

@RequiresApi(api = Build.VERSION_CODES.N)
public class IncomingCallScreeningService extends CallScreeningService {

    @Override
    public void onScreenCall(@NonNull Call.Details callDetails) {
        respondToCall(callDetails, new CallResponse.Builder().build());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("enable-phone-history", false) && callDetails.getHandle() != null) {
            String phoneNumber = callDetails.getHandle().getSchemeSpecificPart();
            if (!TextUtils.isEmpty(phoneNumber)) {
                DataHandler dataHandler = KissApplication.getApplication(this).getDataHandler();
                ContactsProvider contactsProvider = dataHandler.getContactsProvider();
                ContactsPojo contactPojo = contactsProvider == null ? null : contactsProvider.findByPhone(phoneNumber);
                CallerNameResolver.invalidateCallLogCache();
                dataHandler.addToHistory(contactPojo != null
                        ? contactPojo.getHistoryId()
                        : PhoneProvider.getHistoryId(phoneNumber));
            }
        }
    }
}
