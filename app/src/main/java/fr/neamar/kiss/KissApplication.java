package fr.neamar.kiss;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.appusage.AppUsageTracker;
import fr.neamar.kiss.forwarder.InterfaceTweaks;
import fr.neamar.kiss.notification.MediaHistoryCoordinator;
import fr.neamar.kiss.social.SocialContactIndexService;
import fr.neamar.kiss.ui.GlobalTextStyler;
import fr.neamar.kiss.utils.IconPackCache;
import fr.neamar.kiss.utils.Log;

public class KissApplication extends Application {

    private static final String TAG = KissApplication.class.getSimpleName();

    /**
     * Number of ms to wait, after a click occurred, to record a launch
     * Setting this value to 0 removes all animations
     */
    public static final int TOUCH_DELAY = 120;
    private volatile DataHandler dataHandler;
    private volatile RootHandler rootHandler;
    private volatile IconsHandler iconsPackHandler;
    private final IconPackCache mIconPackCache = new IconPackCache();
    private final MimeTypeCache mimeTypeCache = new MimeTypeCache();
    @SuppressWarnings("FieldCanBeLocal")
    private GlobalTextStyler globalTextStyler;

    public static KissApplication getApplication(Context context) {
        if (context instanceof KissApplication) {
            return (KissApplication) context;
        } else {
            return (KissApplication) context.getApplicationContext();
        }
    }

    public static IconPackCache iconPackCache(Context ctx) {
        return getApplication(ctx).mIconPackCache;
    }

    public DataHandler getDataHandler() {
        if (dataHandler == null) {
            synchronized (this) {
                if (dataHandler == null) {
                    dataHandler = new DataHandler(this);
                }
            }
        }
        return dataHandler;
    }

    public RootHandler getRootHandler() {
        if (rootHandler == null) {
            synchronized (this) {
                if (rootHandler == null) {
                    rootHandler = new RootHandler(this);
                }
            }
        }
        return rootHandler;
    }

    public void resetRootHandler(Context ctx) {
        rootHandler.resetRootHandler(ctx);
    }

    public void initDataHandler() {
        if (dataHandler != null) {
            Log.w(TAG, "dataHandler already instantiated");
        }
        getDataHandler();
    }

    public IconsHandler getIconsHandler() {
        if (iconsPackHandler == null) {
            synchronized (this) {
                if (iconsPackHandler == null) {
                    iconsPackHandler = new IconsHandler(this);
                }
            }
        }

        return iconsPackHandler;
    }

    public void resetIconsHandler() {
        iconsPackHandler = new IconsHandler(this);
    }

    public static MimeTypeCache getMimeTypeCache(Context ctx) {
        return getApplication(ctx).mimeTypeCache;
    }

    /** Release rebuildable caches only when Android is under severe memory pressure. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        // TRIM_MEMORY_UI_HIDDEN is delivered during every normal app launch from Home. Clearing
        // here made every return cold: SQLite pages and decoded icon-pack state had to be rebuilt.
        // Retain the launcher's working set while backgrounded and cooperate only when Android is
        // already reclaiming process memory aggressively.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            SQLiteDatabase.releaseMemory();
            mIconPackCache.clearCache(this);
            mimeTypeCache.clearCache();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        SQLiteDatabase.releaseMemory();
        mIconPackCache.clearCache(this);
        mimeTypeCache.clearCache();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DBHelper.initDatabase(this);
        InterfaceTweaks.setDefaultNightMode(this);
        AppUsageTracker.repairPerformanceRegression(this);
        globalTextStyler = GlobalTextStyler.install(this);
        MediaHistoryCoordinator.install(this);
        SocialContactIndexService.maybePrompt(this);
    }
}
