package fr.neamar.kiss.searcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.db.AppCatalogRecord;
import fr.neamar.kiss.db.AppSourceMetadataRecord;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.SemanticActivityRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.utils.Log;

/**
 * Explicit user-triggered refresh of app descriptions used by semantic HNSW search.
 *
 * <p>Network work never runs while typing. Every resolved description is stored by package name in
 * the local metadata DB. After the complete refresh, one HNSW rebuild consumes that cache so the
 * description widens app-search context without adding network latency to queries.</p>
 */
public final class AppSourceMetadataUpdater {
    private static final String TAG = AppSourceMetadataUpdater.class.getSimpleName();

    public static final String PREF_USE_SOURCE_DESCRIPTIONS =
            "semantic-use-app-source-descriptions";

    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 9000;
    private static final int MAX_BODY_CHARS = 1_500_000;
    private static final int FETCH_WORKERS = 3;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final ExecutorService COORDINATOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "smart-s-app-source-update");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private static volatile int total;
    private static volatile int completed;
    private static volatile int downloaded;
    private static volatile int retained;
    private static volatile int localFallback;
    private static volatile int missing;
    private static volatile long lastFinishedAt;

    private static final Pattern META_TAG_PATTERN = Pattern.compile(
            "<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JSON_LD_PATTERN = Pattern.compile(
            "<script\\b[^>]*type=[\\\"']application/ld\\+json[\\\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PLAY_DESCRIPTION_PATTERN = Pattern.compile(
            "<div\\b[^>]*data-g-id=[\\\"']description[\\\"'][^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ITEMPROP_DESCRIPTION_PATTERN = Pattern.compile(
            "<(?:div|span|p)\\b[^>]*itemprop=[\\\"']description[\\\"'][^>]*>(.*?)</(?:div|span|p)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ITEMPROP_NAME_PATTERN = Pattern.compile(
            "<h1\\b[^>]*itemprop=[\\\"']name[\\\"'][^>]*>.*?<span[^>]*>(.*?)</span>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private AppSourceMetadataUpdater() { }

    public static boolean refreshAll(@NonNull Context context,
                                     @NonNull DataHandler dataHandler,
                                     @NonNull SharedPreferences prefs,
                                     @Nullable Runnable finishedCallback) {
        if (!RUNNING.compareAndSet(false, true)) return false;

        final Context appContext = context.getApplicationContext();
        // Pressing "Update apps metadata" means the downloaded descriptions are intended to be
        // part of semantic context. Keep this on; semantic search itself remains independently
        // controlled by its own switch.
        prefs.edit().putBoolean(PREF_USE_SOURCE_DESCRIPTIONS, true).apply();

        total = 0;
        completed = 0;
        downloaded = 0;
        retained = 0;
        localFallback = 0;
        missing = 0;

        COORDINATOR.execute(() -> {
            ExecutorService fetchPool = Executors.newFixedThreadPool(
                    FETCH_WORKERS,
                    runnable -> {
                        Thread thread = new Thread(runnable, "smart-s-app-source-fetch");
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    });

            try {
                Set<String> packages = installedPackages(appContext, dataHandler);
                total = packages.size();
                final String sessionId = "metadata-" + System.currentTimeMillis();
                try {
                    DBHelper.insertSemanticActivity(appContext, new SemanticActivityRecord(
                            System.currentTimeMillis(),
                            "METADATA_REFRESH_STARTED",
                            sessionId,
                            "",
                            "",
                            "App metadata updater",
                            "Started metadata refresh for " + total
                                    + " installed/searchable app packages."));
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to record metadata refresh start", e);
                }

                // Never erase a good cache just because Android temporarily reports no apps while
                // providers/profiles are being restored.
                if (!packages.isEmpty()) {
                    DBHelper.pruneAppSourceMetadata(appContext, packages);
                }

                Map<String, AppSourceMetadataRecord> previous =
                        DBHelper.getAppSourceMetadata(appContext);

                ExecutorCompletionService<RefreshResult> completionService =
                        new ExecutorCompletionService<>(fetchPool);
                for (String packageName : packages) {
                    AppSourceMetadataRecord old = previous.get(packageName);
                    completionService.submit(() -> refreshOne(
                            appContext, packageName, old, sessionId));
                }

                for (int i = 0; i < packages.size(); i++) {
                    try {
                        Future<RefreshResult> result = completionService.take();
                        RefreshResult refreshResult = result.get();
                        if (refreshResult == RefreshResult.DOWNLOADED) downloaded++;
                        else if (refreshResult == RefreshResult.RETAINED) retained++;
                        else if (refreshResult == RefreshResult.LOCAL_FALLBACK) localFallback++;
                        else missing++;
                    } catch (Exception e) {
                        missing++;
                        Log.w(TAG, "App source metadata refresh item failed", e);
                    } finally {
                        completed++;
                    }
                }

                // The completed DB snapshot is the only input to HNSW. Rebuild once, never once per
                // app. If semantic/HNSW is currently off, its normal enable path rebuilds later
                // from this same cache.
                if (prefs.getBoolean("semantic-search-enabled", false)
                        && prefs.getBoolean(SemanticHnswIndex.PREF_HNSW_ENABLED, true)) {
                    SemanticHnswIndex.getInstance().scheduleRebuild(dataHandler, prefs);
                }

                List<SemanticActivityRecord> finishEvents = new ArrayList<>();
                finishEvents.add(new SemanticActivityRecord(
                        System.currentTimeMillis(),
                        "METADATA_REFRESH_COMPLETED",
                        sessionId,
                        "",
                        "",
                        "App metadata updater",
                        "Completed " + total + " packages · downloaded " + downloaded
                                + " · retained " + retained
                                + " · local " + localFallback
                                + " · missing " + missing
                                + ". HNSW rebuild "
                                + (prefs.getBoolean("semantic-search-enabled", false)
                                        && prefs.getBoolean(SemanticHnswIndex.PREF_HNSW_ENABLED, true)
                                        ? "scheduled." : "deferred until semantic HNSW is enabled.")));
                try {
                    DBHelper.insertSemanticActivities(appContext, finishEvents);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to record metadata refresh completion", e);
                }
            } finally {
                fetchPool.shutdownNow();
                lastFinishedAt = System.currentTimeMillis();
                RUNNING.set(false);
                if (finishedCallback != null) {
                    new Handler(Looper.getMainLooper()).post(finishedCallback);
                }
            }
        });
        return true;
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    @NonNull
    public static String statusSummary(@NonNull Context context) {
        if (RUNNING.get()) {
            return "Updating app descriptions… " + completed + "/" + total
                    + " · downloaded " + downloaded
                    + " · retained " + retained
                    + " · local " + localFallback
                    + " · missing " + missing;
        }

        int stored = DBHelper.getAppSourceMetadataCount(context);
        int attempted = DBHelper.getAppSourceMetadataTotalCount(context);
        if (lastFinishedAt > 0L) {
            return stored + "/" + attempted + " app descriptions ready · last refresh: "
                    + downloaded + " downloaded, "
                    + retained + " retained, "
                    + localFallback + " local, "
                    + missing + " missing";
        }
        return stored + "/" + attempted + " app descriptions ready locally";
    }

    /**
     * Build a package set independent of provider timing. The DataHandler snapshot preserves
     * Smart S's cross-profile/frozen entries; PackageManager adds normal launcher activities even
     * if the provider has not finished loading yet.
     */
    private static Set<String> installedPackages(Context context, DataHandler dataHandler) {
        Set<String> packages = new HashSet<>();

        // 1) Anything already represented by Smart S providers.
        for (Pojo pojo : dataHandler.getSemanticIndexSnapshot()) {
            if (pojo instanceof AppPojo) {
                String packageName = ((AppPojo) pojo).packageName;
                if (!TextUtils.isEmpty(packageName)) packages.add(packageName);
            }
        }

        PackageManager packageManager = context.getPackageManager();

        // 2) Normal and disabled launcher activities.
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            List<ResolveInfo> launchable = packageManager.queryIntentActivities(
                    launcherIntent, PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ResolveInfo info : launchable) {
                if (info == null || info.activityInfo == null) continue;
                String packageName = info.activityInfo.packageName;
                if (!TextUtils.isEmpty(packageName)) packages.add(packageName);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to enumerate launcher activities for metadata update", e);
        }

        // 3) Installed applications themselves. This is the important frozen/disabled path:
        // LauncherApps and ACTION_MAIN queries can hide an IceBox-disabled package, but Android
        // still exposes its installed ApplicationInfo when QUERY_ALL_PACKAGES is granted.
        try {
            List<ApplicationInfo> installed = packageManager.getInstalledApplications(
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ApplicationInfo info : installed) {
                if (info == null || TextUtils.isEmpty(info.packageName)) continue;
                if (context.getPackageName().equals(info.packageName)) continue;

                boolean userInstalled = (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (userInstalled || packages.contains(info.packageName)) {
                    packages.add(info.packageName);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to enumerate installed packages for metadata update", e);
        }

        // 4) Smart S's persistent app catalog. A frozen package can be intentionally hidden by
        // PackageManager/LauncherApps on some ROMs, but the package identity remains remembered.
        UserManager userManager = ContextCompat.getSystemService(context, UserManager.class);
        if (userManager != null) {
            try {
                for (android.os.UserHandle profile : userManager.getUserProfiles()) {
                    long serial = userManager.getSerialNumberForUser(profile);
                    if (serial < 0L) continue;
                    for (AppCatalogRecord remembered
                            : SmartStateStore.getRememberedApps(context, serial)) {
                        if (remembered != null && !TextUtils.isEmpty(remembered.packageName)) {
                            packages.add(remembered.packageName);
                        }
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to enumerate remembered packages for metadata update", e);
            }
        }

        return packages;
    }

    private static RefreshResult refreshOne(Context context,
                                            String packageName,
                                            @Nullable AppSourceMetadataRecord previous,
                                            String sessionId) {
        AppSourceMetadataRecord record = new AppSourceMetadataRecord();
        record.packageName = packageName;
        record.installerPackage = installerPackage(context, packageName);

        LocalPackageText local = localPackageText(context, packageName);
        record.title = local.title;

        List<CatalogTarget> targets = targetsForInstaller(record.installerPackage, packageName);
        StringBuilder attempts = new StringBuilder();
        String lastError = "";
        for (CatalogTarget target : targets) {
            try {
                CatalogResult fetched = target.fetch();
                if (fetched == null || TextUtils.isEmpty(fetched.description)) {
                    appendAttempt(attempts, target.source + ": no usable description");
                    continue;
                }

                record.source = target.source;
                String fetchedTitle = cleanText(fetched.title);
                record.title = TextUtils.isEmpty(local.title)
                        || packageName.equals(local.title)
                        ? fetchedTitle : local.title;
                if (TextUtils.isEmpty(record.title)) record.title = packageName;
                record.description = cleanText(fetched.description);
                record.sourceUrl = target.url;
                record.fetchedAt = System.currentTimeMillis();
                record.lastError = "";
                DBHelper.upsertAppSourceMetadata(context, record);
                logMetadataEvent(context, sessionId, "METADATA_DOWNLOADED", record,
                        "Downloaded " + record.description.length() + " description characters. "
                                + "Description: " + record.description);
                return RefreshResult.DOWNLOADED;
            } catch (Exception e) {
                lastError = target.source + " · " + e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "fetch failed" : e.getMessage());
                appendAttempt(attempts, lastError);
            }
        }
        if (attempts.length() > 0) lastError = attempts.toString();

        // A transient store/network failure must never destroy previously downloaded semantic
        // context. Keep the successful description and only record the latest refresh error.
        if (previous != null && !TextUtils.isEmpty(previous.description)) {
            record.source = previous.source;
            record.title = TextUtils.isEmpty(previous.title) ? local.title : previous.title;
            record.description = previous.description;
            record.sourceUrl = previous.sourceUrl;
            record.fetchedAt = previous.fetchedAt;
            record.lastError = lastError.isEmpty()
                    ? "No newer public description found; retained cached description"
                    : lastError;
            DBHelper.upsertAppSourceMetadata(context, record);
            logMetadataEvent(context, sessionId, "METADATA_RETAINED", record,
                    "Retained previously downloaded description because this refresh failed. "
                            + "Last error: " + record.lastError
                            + ". Description: " + record.description);
            return RefreshResult.RETAINED;
        }

        // Some APK manifests contain a public application description. It is not a store download,
        // but it is useful semantic context when no supported catalog has a listing.
        if (!TextUtils.isEmpty(local.description)) {
            record.source = "Android app manifest";
            record.description = local.description;
            record.sourceUrl = "";
            record.fetchedAt = System.currentTimeMillis();
            record.lastError = lastError;
            DBHelper.upsertAppSourceMetadata(context, record);
            logMetadataEvent(context, sessionId, "METADATA_LOCAL_FALLBACK", record,
                    "No supported public catalog description was available. "
                            + "Used Android manifest description: " + record.description);
            return RefreshResult.LOCAL_FALLBACK;
        }

        record.source = sourceLabel(record.installerPackage);
        record.sourceUrl = "";
        record.fetchedAt = System.currentTimeMillis();
        record.lastError = lastError.isEmpty() ? "No public description found" : lastError;
        DBHelper.upsertAppSourceMetadata(context, record);
        logMetadataEvent(context, sessionId, "METADATA_MISSING", record,
                "No usable description found. " + record.lastError);
        return RefreshResult.MISSING;
    }

    private static void appendAttempt(StringBuilder attempts, String message) {
        if (attempts.length() > 0) attempts.append(" | ");
        attempts.append(message);
    }

    private static void logMetadataEvent(Context context,
                                         String sessionId,
                                         String eventType,
                                         AppSourceMetadataRecord record,
                                         String details) {
        String transparentDetails = details;
        if (!TextUtils.isEmpty(record.sourceUrl)) {
            transparentDetails += "\nSource URL: " + record.sourceUrl;
        }
        if (!TextUtils.isEmpty(record.installerPackage)) {
            transparentDetails += "\nInstaller package: " + record.installerPackage;
        }
        try {
            DBHelper.insertSemanticActivity(context, new SemanticActivityRecord(
                    System.currentTimeMillis(),
                    eventType,
                    sessionId,
                    record.packageName,
                    record.title,
                    record.source,
                    transparentDetails));
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to record metadata activity for " + record.packageName, e);
        }
    }

    @NonNull
    private static LocalPackageText localPackageText(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo info = packageManager.getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.GET_META_DATA);
            CharSequence title = info.loadLabel(packageManager);
            CharSequence description = info.loadDescription(packageManager);
            return new LocalPackageText(
                    title == null ? packageName : title.toString(),
                    description == null ? "" : cleanText(description.toString()));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return new LocalPackageText(packageName, "");
        }
    }

    @NonNull
    private static String installerPackage(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo sourceInfo = packageManager.getInstallSourceInfo(packageName);
                String installing = sourceInfo.getInstallingPackageName();
                if (!TextUtils.isEmpty(installing)) return installing;
                String initiating = sourceInfo.getInitiatingPackageName();
                if (!TextUtils.isEmpty(initiating)) return initiating;
            } else {
                String installer = packageManager.getInstallerPackageName(packageName);
                if (!TextUtils.isEmpty(installer)) return installer;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Sideloaded/profile apps can legitimately have no visible installer identity.
        }
        return "";
    }

    private static List<CatalogTarget> targetsForInstaller(String installer, String packageName) {
        List<CatalogTarget> targets = new ArrayList<>(4);
        String normalizedInstaller = installer == null ? "" : installer.toLowerCase(Locale.ROOT);

        if (normalizedInstaller.equals("com.android.vending")
                || normalizedInstaller.equals("com.aurora.store")) {
            addPlay(targets, packageName);
            addAptoide(targets, packageName);
            addFdroid(targets, packageName);
        } else if (normalizedInstaller.equals("org.fdroid.fdroid")
                || normalizedInstaller.equals("org.fdroid.basic")
                || normalizedInstaller.contains("droidify")
                || normalizedInstaller.contains("neo.store")) {
            addFdroid(targets, packageName);
            addPlay(targets, packageName);
            addAptoide(targets, packageName);
        } else if (normalizedInstaller.equals("cm.aptoide.pt")
                || normalizedInstaller.contains("aptoide")) {
            addAptoide(targets, packageName);
            addPlay(targets, packageName);
            addFdroid(targets, packageName);
        } else if (normalizedInstaller.equals("com.sec.android.app.samsungapps")) {
            addSamsung(targets, packageName);
            addPlay(targets, packageName);
            addAptoide(targets, packageName);
            addFdroid(targets, packageName);
        } else {
            addPlay(targets, packageName);
            addAptoide(targets, packageName);
            addFdroid(targets, packageName);
        }
        return targets;
    }

    private static void addPlay(List<CatalogTarget> targets, String packageName) {
        String zaUrl = "https://play.google.com/store/apps/details?id="
                + encode(packageName) + "&hl=en&gl=ZA";
        targets.add(new CatalogTarget(
                "Google Play", zaUrl, () -> fetchPlayMetadata(zaUrl)));

        // A package can be installed while its current ZA listing is hidden or region-restricted.
        // A second public locale lookup still uses the exact package id and gives metadata another
        // chance without changing where the installed app came from.
        String fallbackUrl = "https://play.google.com/store/apps/details?id="
                + encode(packageName) + "&hl=en&gl=US";
        targets.add(new CatalogTarget(
                "Google Play (global fallback)",
                fallbackUrl,
                () -> fetchPlayMetadata(fallbackUrl)));
    }

    private static void addFdroid(List<CatalogTarget> targets, String packageName) {
        String url = "https://f-droid.org/en/packages/" + encodePath(packageName) + "/";
        targets.add(new CatalogTarget("F-Droid", url, () -> fetchHtmlMetadata(url)));
    }

    private static void addAptoide(List<CatalogTarget> targets, String packageName) {
        String metaUrl = "https://ws2.aptoide.com/api/7/app/getMeta/package_name="
                + encodePath(packageName);
        targets.add(new CatalogTarget(
                "Aptoide", metaUrl, () -> fetchAptoideMetadata(metaUrl)));

        // Keep the older public v7 details endpoint as a second independent fallback because
        // catalogue coverage can differ between Aptoide stores/edges for the same package.
        String detailsUrl = "https://ws75.aptoide.com/api/7/app/get/package_name="
                + encodePath(packageName) + "/language=en/nodes=meta";
        targets.add(new CatalogTarget(
                "Aptoide details", detailsUrl, () -> fetchAptoideMetadata(detailsUrl)));
    }

    private static void addSamsung(List<CatalogTarget> targets, String packageName) {
        String url = "https://galaxystore.samsung.com/detail/" + encodePath(packageName);
        targets.add(new CatalogTarget("Galaxy Store", url, () -> fetchHtmlMetadata(url)));
    }

    // Package-private parser helper used by JVM regression tests without network access.
    @Nullable
    static String parsePlayDescriptionForTest(String body) {
        if (TextUtils.isEmpty(body)) return null;

        CatalogResult structured = extractJsonLdMetadata(body);
        if (structured != null && isUsefulDescription(structured.description)) {
            return structured.description;
        }

        String description = cleanText(firstGroup(PLAY_DESCRIPTION_PATTERN, body));
        if (!isUsefulDescription(description)) {
            description = cleanText(firstGroup(ITEMPROP_DESCRIPTION_PATTERN, body));
        }
        if (!isUsefulDescription(description)) {
            description = cleanText(extractMetaDescription(body));
        }
        return isUsefulDescription(description) ? description : null;
    }

    @Nullable
    private static CatalogResult fetchPlayMetadata(String url) throws Exception {
        String body = httpGet(url);
        if (TextUtils.isEmpty(body)) return null;

        CatalogResult structured = extractJsonLdMetadata(body);
        if (structured != null && isUsefulDescription(structured.description)) {
            return structured;
        }

        String title = cleanText(firstGroup(ITEMPROP_NAME_PATTERN, body));
        if (TextUtils.isEmpty(title)) title = cleanText(firstGroup(TITLE_PATTERN, body));

        String description = cleanText(firstGroup(PLAY_DESCRIPTION_PATTERN, body));
        if (!isUsefulDescription(description)) {
            description = cleanText(firstGroup(ITEMPROP_DESCRIPTION_PATTERN, body));
        }
        if (!isUsefulDescription(description)) {
            description = cleanText(extractMetaDescription(body));
        }
        return isUsefulDescription(description)
                ? new CatalogResult(title, description)
                : null;
    }

    @Nullable
    private static CatalogResult extractJsonLdMetadata(String html) {
        Matcher scripts = JSON_LD_PATTERN.matcher(html);
        while (scripts.find()) {
            String raw = scripts.group(1);
            if (TextUtils.isEmpty(raw)) continue;
            try {
                String trimmed = raw.trim();
                if (trimmed.startsWith("[")) {
                    JSONArray array = new JSONArray(trimmed);
                    for (int i = 0; i < array.length(); i++) {
                        CatalogResult result = jsonLdResult(array.opt(i));
                        if (result != null) return result;
                    }
                } else {
                    CatalogResult result = jsonLdResult(new JSONObject(trimmed));
                    if (result != null) return result;
                }
            } catch (Exception ignored) {
                // Google occasionally changes structured-data layout; continue with HTML fallbacks.
            }
        }
        return null;
    }

    @Nullable
    private static CatalogResult jsonLdResult(Object value) {
        if (!(value instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) value;

        Object graph = object.opt("@graph");
        if (graph instanceof JSONArray) {
            JSONArray array = (JSONArray) graph;
            for (int i = 0; i < array.length(); i++) {
                CatalogResult nested = jsonLdResult(array.opt(i));
                if (nested != null) return nested;
            }
        }

        String description = cleanText(object.optString("description", ""));
        if (!isUsefulDescription(description)) return null;
        String title = cleanText(object.optString("name", ""));
        return new CatalogResult(title, description);
    }

    private static boolean isUsefulDescription(@Nullable String description) {
        if (TextUtils.isEmpty(description)) return false;
        String cleaned = cleanText(description);
        if (cleaned.length() < 24) return false;
        String lower = cleaned.toLowerCase(Locale.ROOT);
        return !lower.equals("apps on google play")
                && !lower.startsWith("enjoy millions of the latest android apps")
                && !lower.startsWith("find and download");
    }

    @Nullable
    private static CatalogResult fetchHtmlMetadata(String url) throws IOException {
        String body = httpGet(url);
        if (TextUtils.isEmpty(body)) return null;

        CatalogResult structured = extractJsonLdMetadata(body);
        if (structured != null && isUsefulDescription(structured.description)) {
            return structured;
        }

        String description = cleanText(extractMetaDescription(body));
        if (!isUsefulDescription(description)) return null;

        String title = cleanText(firstGroup(TITLE_PATTERN, body));
        return new CatalogResult(title, description);
    }

    @Nullable
    private static String extractMetaDescription(String html) {
        Matcher tags = META_TAG_PATTERN.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            String name = attribute(tag, "name");
            String property = attribute(tag, "property");
            String itemprop = attribute(tag, "itemprop");
            String descriptor = !TextUtils.isEmpty(name)
                    ? name : (!TextUtils.isEmpty(property) ? property : itemprop);

            if (descriptor == null) continue;
            String normalized = descriptor.toLowerCase(Locale.ROOT);
            if (!"description".equals(normalized)
                    && !"og:description".equals(normalized)
                    && !"twitter:description".equals(normalized)) {
                continue;
            }

            String content = attribute(tag, "content");
            if (!TextUtils.isEmpty(content)) return content;
        }
        return null;
    }

    @Nullable
    private static String attribute(String tag, String name) {
        Pattern pattern = Pattern.compile(
                "(?i)\\b" + Pattern.quote(name) + "\\s*=\\s*([\\\"'])(.*?)\\1",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? matcher.group(2) : null;
    }

    @Nullable
    private static CatalogResult fetchAptoideMetadata(String url) throws Exception {
        String body = httpGet(url);
        if (TextUtils.isEmpty(body)) return null;

        JSONObject root = new JSONObject(body);
        JSONObject nodes = root.optJSONObject("nodes");
        JSONObject meta = nodes == null ? null : nodes.optJSONObject("meta");
        JSONObject data = meta == null ? null : meta.optJSONObject("data");
        if (data == null) data = root.optJSONObject("data");
        if (data == null) return null;

        String title = data.optString("name", "");
        String description = data.optString("description", "");
        if (TextUtils.isEmpty(description)) {
            JSONObject media = data.optJSONObject("media");
            if (media != null) description = media.optString("description", "");
        }
        return TextUtils.isEmpty(description) ? null : new CatalogResult(title, description);
    }

    private static String httpGet(String urlValue) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/140 Mobile Safari/537.36");
        connection.setRequestProperty("Accept-Language", "en-ZA,en;q=0.9");
        connection.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Cache-Control", "no-cache");

        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);

            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) >= 0
                        && body.length() < MAX_BODY_CHARS) {
                    int allowed = Math.min(read, MAX_BODY_CHARS - body.length());
                    body.append(buffer, 0, allowed);
                }
                return body.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    @NonNull
    private static String sourceLabel(String installerPackage) {
        if (TextUtils.isEmpty(installerPackage)) return "Unknown / sideloaded";
        String installer = installerPackage.toLowerCase(Locale.ROOT);
        if (installer.equals("com.android.vending")) return "Google Play";
        if (installer.equals("com.aurora.store")) return "Aurora Store / Google Play";
        if (installer.contains("fdroid") || installer.contains("droidify")) return "F-Droid";
        if (installer.contains("aptoide")) return "Aptoide";
        if (installer.equals("com.sec.android.app.samsungapps")) return "Galaxy Store";
        if (installer.equals("com.amazon.venezia")) return "Amazon Appstore";
        return installerPackage;
    }

    @Nullable
    private static String firstGroup(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    @NonNull
    private static String cleanText(@Nullable String value) {
        if (value == null) return "";
        String cleaned = value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#x27;", "'")
                .replace("&#x2F;", "/")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 12_000) cleaned = cleaned.substring(0, 12_000);
        return cleaned;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String encodePath(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "");
    }

    enum RefreshResult {
        DOWNLOADED,
        RETAINED,
        LOCAL_FALLBACK,
        MISSING
    }

    private interface FetchAction {
        @Nullable CatalogResult run() throws Exception;
    }

    private static final class CatalogTarget {
        final String source;
        final String url;
        final FetchAction fetchAction;

        CatalogTarget(String source, String url, FetchAction fetchAction) {
            this.source = source;
            this.url = url;
            this.fetchAction = fetchAction;
        }

        @Nullable CatalogResult fetch() throws Exception {
            return fetchAction.run();
        }
    }

    private static final class CatalogResult {
        final String title;
        final String description;

        CatalogResult(String title, String description) {
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
        }
    }

    private static final class LocalPackageText {
        final String title;
        final String description;

        LocalPackageText(String title, String description) {
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
        }
    }
}
