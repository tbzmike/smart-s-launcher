from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    p.write_text(text.replace(old, new, 1))

# Permission.java: preserve existing request codes and append READ_CALL_LOG as a new permission.
replace_once(
    "app/src/main/java/fr/neamar/kiss/utils/Permission.java",
'''    public static final int PERMISSION_READ_CONTACTS = 0;
    public static final int PERMISSION_CALL_PHONE = 1;
    public static final int PERMISSION_READ_PHONE_STATE = 2;

    private static final String[] permissions = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
    };''',
'''    public static final int PERMISSION_READ_CONTACTS = 0;
    public static final int PERMISSION_CALL_PHONE = 1;
    public static final int PERMISSION_READ_PHONE_STATE = 2;
    public static final int PERMISSION_READ_CALL_LOG = 3;

    private static final String[] permissions = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
    };''')

# PhoneProvider: history reconstruction now resolves caller/contact names, while live number search stays unchanged.
replace_once(
    "app/src/main/java/fr/neamar/kiss/dataprovider/simpleprovider/PhoneProvider.java",
'''import fr.neamar.kiss.pojo.PhonePojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.PhoneUtils;''',
'''import fr.neamar.kiss.pojo.PhonePojo;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.utils.CallerNameResolver;
import fr.neamar.kiss.utils.PhoneUtils;''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/dataprovider/simpleprovider/PhoneProvider.java",
'''    @Override
    public boolean mayFindById(String id) {
        return id.startsWith(PHONE_SCHEME);
    }

    public PhonePojo findById(String id) {''',
'''    @Override
    public boolean mayFindById(String id) {
        return id.startsWith(PHONE_SCHEME);
    }

    public static String getHistoryId(String phoneNumber) {
        return PHONE_SCHEME + phoneNumber;
    }

    public PhonePojo findById(String id) {''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/dataprovider/simpleprovider/PhoneProvider.java",
'''        String historyId = PHONE_SCHEME + phoneNumber;''',
'''        String historyId = getHistoryId(phoneNumber);''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/dataprovider/simpleprovider/PhoneProvider.java",
'''        pojo.setName(phoneNumber, false);
        return pojo;''',
'''        String displayName = phoneNumber;
        if (!fromSearch) {
            String resolvedName = CallerNameResolver.resolve(context, phoneNumber);
            if (resolvedName != null && !resolvedName.isEmpty()) displayName = resolvedName;
        }
        pojo.setName(displayName, false);
        return pojo;''')

# PhoneResult: the old renderer ignored pojo.name and always printed only the number.
replace_once(
    "app/src/main/java/fr/neamar/kiss/result/PhoneResult.java",
'''        TextView phoneText = view.findViewById(R.id.item_phone_text);
        String text = context.getString(R.string.ui_item_phone, pojo.phone);
        int pos = text.indexOf(pojo.phone);
        int len = pojo.phone.length();
        displayHighlighted(text, Collections.singletonList(new Pair<>(pos, pos + len)), phoneText, context);''',
'''        TextView phoneText = view.findViewById(R.id.item_phone_text);
        String callerName = pojo.getName();
        boolean hasResolvedCallerName = callerName != null && !callerName.equals(pojo.phone);
        String text = hasResolvedCallerName
                ? callerName + " · " + pojo.phone
                : context.getString(R.string.ui_item_phone, pojo.phone);
        int pos = text.indexOf(pojo.phone);
        int len = pojo.phone.length();
        displayHighlighted(text, Collections.singletonList(new Pair<>(pos, pos + len)), phoneText, context);''')

# Legacy incoming-call receiver: unknown callers must not be silently discarded.
replace_once(
    "app/src/main/java/fr/neamar/kiss/broadcast/IncomingCallHandler.java",
'''import fr.neamar.kiss.dataprovider.ContactsProvider;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.utils.Log;''',
'''import fr.neamar.kiss.dataprovider.ContactsProvider;
import fr.neamar.kiss.dataprovider.simpleprovider.PhoneProvider;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.utils.CallerNameResolver;
import fr.neamar.kiss.utils.Log;''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/broadcast/IncomingCallHandler.java",
'''            ContactsProvider contactsProvider = dataHandler.getContactsProvider();

            // Stop if contacts are not enabled
            if (contactsProvider == null) {
                return;
            }

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE))) {
                String phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

                if (phoneNumber == null) {
                    // Skipping (private call)
                    return;
                }

                ContactsPojo contactPojo = contactsProvider.findByPhone(phoneNumber);
                if (contactPojo != null) {
                    dataHandler.addToHistory(contactPojo.getHistoryId());
                }
            }''',
'''            ContactsProvider contactsProvider = dataHandler.getContactsProvider();

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
            }''')

# Android Q+ call-screening path: mirror the same contact-or-phone-history behavior.
replace_once(
    "app/src/main/java/fr/neamar/kiss/broadcast/IncomingCallScreeningService.java",
'''import fr.neamar.kiss.dataprovider.ContactsProvider;
import fr.neamar.kiss.pojo.ContactsPojo;''',
'''import fr.neamar.kiss.dataprovider.ContactsProvider;
import fr.neamar.kiss.dataprovider.simpleprovider.PhoneProvider;
import fr.neamar.kiss.pojo.ContactsPojo;
import fr.neamar.kiss.utils.CallerNameResolver;''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/broadcast/IncomingCallScreeningService.java",
'''                DataHandler dataHandler = KissApplication.getApplication(this).getDataHandler();
                ContactsProvider contactsProvider = dataHandler.getContactsProvider();
                if (contactsProvider != null) {
                    ContactsPojo contactPojo = contactsProvider.findByPhone(phoneNumber);
                    if (contactPojo != null) {
                        dataHandler.addToHistory(contactPojo.getHistoryId());
                    }
                }''',
'''                DataHandler dataHandler = KissApplication.getApplication(this).getDataHandler();
                ContactsProvider contactsProvider = dataHandler.getContactsProvider();
                ContactsPojo contactPojo = contactsProvider == null ? null : contactsProvider.findByPhone(phoneNumber);
                CallerNameResolver.invalidateCallLogCache();
                dataHandler.addToHistory(contactPojo != null
                        ? contactPojo.getHistoryId()
                        : PhoneProvider.getHistoryId(phoneNumber));''')

# Settings: request READ_CALL_LOG for caller-name enrichment without disabling phone history if the
# restricted permission is unavailable. READ_PHONE_STATE remains mandatory exactly as before.
replace_once(
    "app/src/main/java/fr/neamar/kiss/SettingsFragment.java",
'''            } else if (key.equalsIgnoreCase("enable-phone-history")) {
                boolean enabled = sharedPreferences.getBoolean(key, false);
                if (enabled && !Permission.checkPermission(getContext(), Permission.PERMISSION_READ_PHONE_STATE)) {
                    Permission.askPermission(Permission.PERMISSION_READ_PHONE_STATE, new Permission.PermissionResultListener() {
                        @Override
                        public void onGranted() {
                            setPhoneHistoryEnabled(true);
                        }

                        @Override
                        public void onDenied() {
                            // You don't want to give us permission, that's fine. Revert the toggle.
                            SwitchPreference p = findPreference(key);
                            if (p != null) {
                                p.setChecked(false);
                            }
                            Toast.makeText(getContext(), R.string.permission_denied, Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    setPhoneHistoryEnabled(enabled);
                }
            } else if (key.equalsIgnoreCase("primary-color")) {''',
'''            } else if (key.equalsIgnoreCase("enable-phone-history")) {
                boolean enabled = sharedPreferences.getBoolean(key, false);
                if (enabled) ensurePhoneHistoryPermissions(key);
                else setPhoneHistoryEnabled(false);
            } else if (key.equalsIgnoreCase("primary-color")) {''')
replace_once(
    "app/src/main/java/fr/neamar/kiss/SettingsFragment.java",
'''    protected void setPhoneHistoryEnabled(boolean enabled) {
        IncomingCallHandler.setEnabled(getContext(), enabled);''',
'''    private void ensurePhoneHistoryPermissions(String preferenceKey) {
        if (!Permission.checkPermission(requireContext(), Permission.PERMISSION_READ_PHONE_STATE)) {
            Permission.askPermission(Permission.PERMISSION_READ_PHONE_STATE, new Permission.PermissionResultListener() {
                @Override
                public void onGranted() {
                    ensurePhoneHistoryPermissions(preferenceKey);
                }

                @Override
                public void onDenied() {
                    SwitchPreference p = findPreference(preferenceKey);
                    if (p != null) p.setChecked(false);
                    Toast.makeText(getContext(), R.string.permission_denied, Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        if (!Permission.checkPermission(requireContext(), Permission.PERMISSION_READ_CALL_LOG)) {
            Permission.askPermission(Permission.PERMISSION_READ_CALL_LOG, new Permission.PermissionResultListener() {
                @Override
                public void onGranted() {
                    setPhoneHistoryEnabled(true);
                }

                @Override
                public void onDenied() {
                    // Call screening still works without READ_CALL_LOG; only caller-name enrichment
                    // falls back to Contacts/number when Android does not grant the restricted permission.
                    setPhoneHistoryEnabled(true);
                    Toast.makeText(getContext(),
                            "Call log permission is needed for caller-ID names in phone history.",
                            Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        setPhoneHistoryEnabled(true);
    }

    protected void setPhoneHistoryEnabled(boolean enabled) {
        IncomingCallHandler.setEnabled(getContext(), enabled);''')

# Version bump.
replace_once(
    "app/build.gradle",
'''        // Smart S Launcher 3.30.10
        versionCode 438
        versionName "3.30.10"''',
'''        // Smart S Launcher 3.30.11
        versionCode 439
        versionName "3.30.11"''')
