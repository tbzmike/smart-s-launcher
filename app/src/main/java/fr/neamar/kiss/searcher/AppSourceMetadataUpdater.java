package fr.neamar.kiss.searcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.neamar.kiss.DataHandler;
import fr.neamar.kiss.db.AppSourceMetadataRecord;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.utils.Log;

/**
 * Explicit, user-triggered refresh of public app catalog descriptions.
 *
 * <p>Search never calls the network. This updater runs only when requested from Settings, stores
 * one row per installed package locally, then rebuilds the semantic HNSW graph from the cache.</p>
 */
public final class AppSourceMetadataUpdater {
    private static final String TAG = AppSourceMetadataUpdater.class.getSimpleName();

    public static final String PREF_USE_SOURCE_DESCRIPTIONS =
            "semantic-use-app-source-descriptions";

    private static final int CONNECT_TIMEOUT_MS = 6500;
    private static final int READ_TIMEOUT_MS = 8000;
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
    private static volatile int updated;
    private static volatile int failed;
    private static volatile long lastFinishedAt;

    private static final Pattern META_NAME_DESCRIPTION = Pattern.compile(
            "<meta\\s+[^>]*name=[\\\"']description[\\\"'][^>]*content=[\\\"']([^\\\"']*)[\\\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_PROPERTY_DESCRIPTION = Pattern.compile(
            "<meta\\s+[^>]*property=[\\\"'](?:og:description|twitter:description)[\\\"'][^>]*content=[\\\"']([^\\\"']*)[\\\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern META_REVERSED_DESCRIPTION = Pattern.compile(
            "<meta\\s+[^>]*content=[\\\"']([^\\\"']*)[\\\"'][^>]*(?:name|property)=[\\\"'](?:description|og:description|twitter:description)[\\\"'][^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private AppSourceMetadataUpdater() { }

    public static boolean refreshAll(@NonNull Context context,
                                     @NonNull DataHandler dataHandler,
                                     @NonNull SharedPreferences prefs,
                                     @Nullable Runnable finishedCallback) {
        if (!RUNNING.compareAndSet(false, true)) return false;

        final Context appContext = context.getApplicationContext();
        total = 0;
        completed = 0;
        updated = 0;
        failed = 0;

        COORDINATOR.execute(() -> {
            ExecutorService fetchPool = Executors.newFixedThreadPool(
                    FETCH_WORKERS,
                    runnable -> {
                        Thread thread = new Thread(runnable, "smart-s-app-source-fetch");
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    });

            try {
                Set<String> packages = installedPackages(dataHandler);
                total = packages.size();
                DBHelper.pruneAppSourceMetadata(appContext, packages);

                ExecutorCompletionService<Boolean> completion =
                        new ExecutorCompletionService<>(fetchPool);
                for (String packageName : packages) {
                    completion.submit(() -> refreshOne(appContext, packageName));
                }

                for (int i = 0; i < packages.size(); i++) {
                    try {
                        Future<Boolean> result = completion.take();
                        if (Boolean.TRUE.equals(result.get())) updated++;
                        else failed++;
                    } catch (Exception e) {
                        failed++;
                        Log.w(TAG, "App source metadata refresh item failed", e);
                    } finally {
                        completed++;
                    }
                }

                if (prefs.getBoolean(PREF_USE_SOURCE_DESCRIPTIONS, true)
                        && prefs.getBoolean("semantic-search-enabled", false)
                        && prefs.getBoolean(SemanticHnswIndex.PREF_HNSW_ENABLED, true)) {
                    SemanticHnswIndex.getInstance().scheduleRebuild(dataHandler, prefs);
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
            return "Updating app source data… " + completed + "/" + total
                    + " · updated " + updated + " · failed " + failed;
        }

        int stored = DBHelper.getAppSourceMetadataCount(context);
        if (lastFinishedAt > 0L) {
            return stored + " app descriptions cached · last refresh updated "
                    + updated + ", failed " + failed;
        }
        return stored + " app descriptions cached locally";
    }

    private static Set<String> installedPackages(DataHandler dataHandler) {
        Set<String> packages = new HashSet<>();
        for (Pojo pojo : dataHandler.getSemanticIndexSnapshot()) {
            if (pojo instanceof AppPojo) {
                String packageName = ((AppPojo) pojo).packageName;
                if (!TextUtils.isEmpty(packageName)) packages.add(packageName);
            }
        }
        return packages;
    }

    private static boolean refreshOne(Context context, String packageName) {
        AppSourceMetadataRecord record = new AppSourceMetadataRecord();
        record.packageName = packageName;
        record.installerPackage = installerPackage(context, packageName);
        record.fetchedAt = System.currentTimeMillis();

        List<CatalogTarget> targets = targetsForInstaller(record.installerPackage, packageName);
        String lastError = "";
        for (CatalogTarget target : targets) {
            try {
                CatalogResult fetched = target.fetch();
                if (fetched != null && !TextUtils.isEmpty(fetched.description)) {
                    record.source = target.source;
                    record.title = cleanText(fetched.title);
                    record.description = cleanText(fetched.description);
                    record.sourceUrl = target.url;
                    record.lastError = "";
                    DBHelper.upsertAppSourceMetadata(context, record);
                    return true;
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "fetch failed" : e.getMessage());
            }
        }

        record.source = sourceLabel(record.installerPackage);
        record.lastError = lastError.isEmpty() ? "No public description found" : lastError;
        DBHelper.upsertAppSourceMetadata(context, record);
        return false;
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
        List<CatalogTarget> targets = new ArrayList<>(3);
        String normalizedInstaller = installer == null ? "" : installer.toLowerCase(Locale.ROOT);

        if (normalizedInstaller.equals("com.android.vending")
                || normalizedInstaller.equals("com.aurora.store")) {
            addPlay(targets, packageName);
            addFdroid(targets, packageName);
            addAptoide(targets, packageName);
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
        } else {
            // Unknown/sideloaded source: package-name lookup across public catalogs widens context
            // without guessing that the installer itself came from one particular store.
            addPlay(targets, packageName);
            addFdroid(targets, packageName);
            addAptoide(targets, packageName);
        }
        return targets;
    }

    private static void addPlay(List<CatalogTarget> targets, String packageName) {
        String url = "https://play.google.com/store/apps/details?id="
                + encode(packageName) + "&hl=en&gl=ZA";
        targets.add(new CatalogTarget("Google Play", url, () -> fetchHtmlMetadata(url)));
    }

    private static void addFdroid(List<CatalogTarget> targets, String packageName) {
        String url = "https://f-droid.org/en/packages/" + encodePath(packageName) + "/";
        targets.add(new CatalogTarget("F-Droid", url, () -> fetchHtmlMetadata(url)));
    }

    private static void addAptoide(List<CatalogTarget> targets, String packageName) {
        String url = "https://ws75.aptoide.com/api/7/app/get/package_name="
                + encodePath(packageName);
        targets.add(new CatalogTarget("Aptoide", url, () -> fetchAptoideMetadata(url)));
    }

    @Nullable
    private static CatalogResult fetchHtmlMetadata(String url) throws IOException {
        String body = httpGet(url);
        if (TextUtils.isEmpty(body)) return null;

        String description = firstGroup(META_PROPERTY_DESCRIPTION, body);
        if (TextUtils.isEmpty(description)) description = firstGroup(META_NAME_DESCRIPTION, body);
        if (TextUtils.isEmpty(description)) description = firstGroup(META_REVERSED_DESCRIPTION, body);
        if (TextUtils.isEmpty(description)) return null;

        String title = firstGroup(TITLE_PATTERN, body);
        return new CatalogResult(title, description);
    }

    @Nullable
    private static CatalogResult fetchAptoideMetadata(String url) throws Exception {
        String body = httpGet(url);
        if (TextUtils.isEmpty(body)) return null;

        JSONObject root = new JSONObject(body);
        JSONObject nodes = root.optJSONObject("nodes");
        JSONObject meta = nodes == null ? null : nodes.optJSONObject("meta");
        JSONObject data = meta == null ? null : meta.optJSONObject("data");
        if (data == null) {
            data = root.optJSONObject("data");
        }
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

        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
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
}
