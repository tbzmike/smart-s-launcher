package fr.neamar.kiss.activitylauncher;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import fr.neamar.kiss.R;
import fr.neamar.kiss.db.ShortcutRecord;
import fr.neamar.kiss.preference.UiEditLock;

/**
 * Foreground-only component and shortcut explorer. There are deliberately no Services,
 * WorkManager jobs, persistent receivers or package callbacks associated with this screen.
 */
public final class ActivityLauncherActivity extends AppCompatActivity {
    private static final int ROW_APP = 1;
    private static final int ROW_SECTION = 2;
    private static final int ROW_COMPONENT = 3;
    private static final int ROW_SAVED = 4;
    private static final long SEARCH_DELAY_MS = 250L;

    private final List<AppEntry> apps = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private RecyclerView recycler;
    private RowAdapter adapter;
    private EditText search;
    private TextView status;
    private ProgressBar progress;
    private ExecutorService worker;
    private boolean lockListenerRegistered;

    private ActivityResultLauncher<Intent> documentPicker;
    private ActivityResultLauncher<Intent> folderPicker;

    private final SharedPreferences.OnSharedPreferenceChangeListener lockListener = (prefs, key) -> {
        if (UiEditLock.isLocked(this)) runOnUiThread(this::finish);
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UiEditLock.isLocked(this)) {
            finish();
            return;
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Activity Launcher");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        documentPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        handleDocumentSelection(result.getData(), false);
                    }
                });
        folderPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        handleDocumentSelection(result.getData(), true);
                    }
                });

        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (UiEditLock.isLocked(this)) {
            finish();
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!lockListenerRegistered) {
            prefs.registerOnSharedPreferenceChangeListener(lockListener);
            lockListenerRegistered = true;
        }
        ensureWorker();
        if (apps.isEmpty()) loadApplications();
        else rebuildNormalRows();
    }

    @Override
    protected void onPause() {
        if (lockListenerRegistered) {
            PreferenceManager.getDefaultSharedPreferences(this)
                    .unregisterOnSharedPreferenceChangeListener(lockListener);
            lockListenerRegistered = false;
        }
        stopForegroundWork();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopForegroundWork();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search apps, packages and components");
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, dp(6));
        actions.addView(makeButton("Saved", v -> showSavedTargets()));
        actions.addView(makeButton("File", v -> pickDocument()));
        actions.addView(makeButton("Folder", v -> pickFolder()));
        actions.addView(makeButton("Intent", v -> showCustomIntentDialog()));
        actions.addView(makeButton("System", v -> showSystemFeatures()));
        actions.addView(makeButton("Refresh", v -> refreshApplications()));
        scroller.addView(actions);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setVisibility(View.GONE);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(30), dp(30)));
        status = new TextView(this);
        status.setText("Loading installed applications...");
        status.setPadding(dp(8), 0, 0, 0);
        statusRow.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(statusRow);

        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setHasFixedSize(false);
        adapter = new RowAdapter();
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleSearch(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        button.setLayoutParams(lp);
        return button;
    }

    private void ensureWorker() {
        if (worker == null || worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
    }

    private void stopForegroundWork() {
        generation.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        setBusy(false);
    }

    private void submit(Runnable runnable) {
        ensureWorker();
        try {
            worker.execute(runnable);
        } catch (RejectedExecutionException ignored) { }
    }

    private void setBusy(boolean busy) {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void refreshApplications() {
        apps.clear();
        rows.clear();
        adapter.notifyDataSetChanged();
        loadApplications();
    }

    @SuppressWarnings("deprecation")
    private void loadApplications() {
        int token = generation.incrementAndGet();
        setBusy(true);
        status.setText("Loading installed applications...");
        submit(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installed;
            int flags = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.GET_META_DATA;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags));
            } else {
                installed = pm.getInstalledApplications(flags);
            }
            List<AppEntry> loaded = new ArrayList<>(installed.size());
            for (ApplicationInfo info : installed) {
                if (Thread.currentThread().isInterrupted()) return;
                CharSequence rawLabel = info.loadLabel(pm);
                String label = TextUtils.isEmpty(rawLabel) ? info.packageName : rawLabel.toString();
                Drawable icon;
                try { icon = info.loadIcon(pm); }
                catch (RuntimeException e) { icon = pm.getDefaultActivityIcon(); }
                loaded.add(new AppEntry(info.packageName, label, icon, info.enabled));
            }
            loaded.sort(Comparator.comparing(app -> app.label, String.CASE_INSENSITIVE_ORDER));
            runOnUiThread(() -> {
                if (token != generation.get() || isFinishing()) return;
                apps.clear();
                apps.addAll(loaded);
                setBusy(false);
                status.setText(apps.size() + " applications. Tap an app to inspect components.");
                rebuildNormalRows();
            });
        });
    }

    private void scheduleSearch(String rawQuery) {
        mainHandler.removeCallbacksAndMessages(null);
        final String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            generation.incrementAndGet();
            setBusy(false);
            rebuildNormalRows();
            return;
        }
        mainHandler.postDelayed(() -> runSearch(query), SEARCH_DELAY_MS);
    }

    private void runSearch(String rawQuery) {
        if (apps.isEmpty()) return;
        int token = generation.incrementAndGet();
        setBusy(true);
        status.setText("Searching components on demand...");
        final String query = rawQuery.toLowerCase(Locale.ROOT);
        submit(() -> {
            List<Row> found = new ArrayList<>();
            for (ShortcutRecord saved : ActivityLauncherStore.getSaved(this)) {
                if (contains(ActivityLauncherStore.displayLabel(this, saved), query)) {
                    found.add(Row.saved(saved));
                }
            }
            for (AppEntry app : apps) {
                if (Thread.currentThread().isInterrupted() || token != generation.get()) return;
                boolean appMatch = contains(app.label, query) || contains(app.packageName, query);
                List<ComponentEntry> componentMatches = new ArrayList<>();
                if (query.length() >= 2) {
                    List<ComponentEntry> components = loadComponentsSync(app);
                    for (ComponentEntry component : components) {
                        if (contains(component.label, query)
                                || contains(component.className, query)
                                || contains(component.packageName, query)) {
                            componentMatches.add(component);
                        }
                    }
                }
                if (appMatch || !componentMatches.isEmpty()) {
                    found.add(Row.app(app));
                    for (ComponentEntry component : componentMatches) found.add(Row.component(component));
                }
            }
            runOnUiThread(() -> {
                if (token != generation.get() || isFinishing()) return;
                rows.clear();
                rows.addAll(found);
                adapter.notifyDataSetChanged();
                setBusy(false);
                status.setText(found.size() + " matching rows");
            });
        });
    }

    private boolean contains(@Nullable String value, @NonNull String lowerQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private void rebuildNormalRows() {
        if (adapter == null) return;
        rows.clear();
        List<ShortcutRecord> saved = ActivityLauncherStore.getSaved(this);
        if (!saved.isEmpty()) {
            rows.add(Row.section("Saved Smart S targets (" + saved.size() + ")"));
            for (ShortcutRecord record : saved) rows.add(Row.saved(record));
        }
        rows.add(Row.section("Installed applications (" + apps.size() + ")"));
        for (AppEntry app : apps) {
            rows.add(Row.app(app));
            if (app.expanded && app.componentsLoaded) appendComponentRows(app);
        }
        adapter.notifyDataSetChanged();
    }

    private void appendComponentRows(AppEntry app) {
        appendKind(app, Kind.ACTIVITY, "Activities", app.activityCount);
        appendKind(app, Kind.SERVICE, "Services", app.serviceCount);
        appendKind(app, Kind.RECEIVER, "Receivers", app.receiverCount);
        appendKind(app, Kind.PROVIDER, "Providers", app.providerCount);
    }

    private void appendKind(AppEntry app, Kind kind, String title, int count) {
        if (count <= 0) return;
        rows.add(Row.section("    " + title + " (" + count + ")"));
        for (ComponentEntry entry : app.components) {
            if (entry.kind == kind) rows.add(Row.component(entry));
        }
    }

    private void toggleApp(AppEntry app) {
        if (app.componentsLoaded) {
            app.expanded = !app.expanded;
            rebuildNormalRows();
            return;
        }
        app.expanded = true;
        app.loading = true;
        rebuildNormalRows();
        setBusy(true);
        status.setText("Reading " + app.label + " components...");
        int token = generation.get();
        submit(() -> {
            loadComponentsSync(app);
            runOnUiThread(() -> {
                if (token != generation.get() || isFinishing()) return;
                app.loading = false;
                setBusy(false);
                status.setText("Loaded " + app.componentCount() + " components for " + app.label);
                if (TextUtils.isEmpty(search.getText())) rebuildNormalRows();
            });
        });
    }

    @SuppressWarnings("deprecation")
    @NonNull
    private List<ComponentEntry> loadComponentsSync(AppEntry app) {
        if (app.componentsLoaded) return app.components;
        PackageManager pm = getPackageManager();
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                | PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS;
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = pm.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags));
            } else {
                info = pm.getPackageInfo(app.packageName, flags);
            }
            List<ComponentEntry> loaded = new ArrayList<>();
            addActivities(loaded, info.activities, Kind.ACTIVITY);
            addServices(loaded, info.services);
            addActivities(loaded, info.receivers, Kind.RECEIVER);
            addProviders(loaded, info.providers);
            loaded.sort((left, right) -> {
                int kindCompare = Integer.compare(left.kind.order, right.kind.order);
                if (kindCompare != 0) return kindCompare;
                return left.label.compareToIgnoreCase(right.label);
            });
            app.components.clear();
            app.components.addAll(loaded);
            app.activityCount = countKind(loaded, Kind.ACTIVITY);
            app.serviceCount = countKind(loaded, Kind.SERVICE);
            app.receiverCount = countKind(loaded, Kind.RECEIVER);
            app.providerCount = countKind(loaded, Kind.PROVIDER);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            app.components.clear();
        }
        app.componentsLoaded = true;
        return app.components;
    }

    private int countKind(List<ComponentEntry> entries, Kind kind) {
        int count = 0;
        for (ComponentEntry entry : entries) if (entry.kind == kind) count++;
        return count;
    }

    private void addActivities(List<ComponentEntry> out, @Nullable ActivityInfo[] infos, Kind kind) {
        if (infos == null) return;
        for (ActivityInfo info : infos) out.add(componentEntry(info, kind));
    }

    private void addServices(List<ComponentEntry> out, @Nullable ServiceInfo[] infos) {
        if (infos == null) return;
        for (ServiceInfo info : infos) out.add(componentEntry(info, Kind.SERVICE));
    }

    private void addProviders(List<ComponentEntry> out, @Nullable ProviderInfo[] infos) {
        if (infos == null) return;
        for (ProviderInfo info : infos) out.add(componentEntry(info, Kind.PROVIDER));
    }

    private ComponentEntry componentEntry(ComponentInfo info, Kind kind) {
        PackageManager pm = getPackageManager();
        CharSequence labelText;
        try { labelText = info.loadLabel(pm); }
        catch (RuntimeException e) { labelText = null; }
        String label = TextUtils.isEmpty(labelText) ? shortClassName(info.name) : labelText.toString();
        boolean ownPackage = getPackageName().equals(info.packageName);
        boolean exported = info.exported || ownPackage;
        boolean enabled = info.enabled && info.applicationInfo != null && info.applicationInfo.enabled;
        String permission = componentPermission(info);
        boolean permissionAllowed = TextUtils.isEmpty(permission)
                || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
        boolean invokable = kind != Kind.PROVIDER && exported && enabled && permissionAllowed;
        StringBuilder state = new StringBuilder();
        state.append(info.exported ? "Exported" : ownPackage ? "Internal (own app)" : "Private");
        state.append(enabled ? " - Enabled" : " - Disabled");
        if (!TextUtils.isEmpty(permission) && !permissionAllowed) state.append(" - Requires ").append(permission);
        if (kind == Kind.PROVIDER) state.append(" - Information only");
        else if (invokable && (kind == Kind.SERVICE || kind == Kind.RECEIVER)) {
            state.append(" - May require app-specific action/extras");
        }
        Intent target = kind == Kind.PROVIDER ? null
                : new Intent().setComponent(new ComponentName(info.packageName, info.name));
        return new ComponentEntry(kind, info.packageName, info.name, label,
                state.toString(), invokable, target);
    }

    @Nullable
    private String componentPermission(ComponentInfo info) {
        if (info instanceof ActivityInfo) return ((ActivityInfo) info).permission;
        if (info instanceof ServiceInfo) return ((ServiceInfo) info).permission;
        if (info instanceof ProviderInfo) {
            ProviderInfo provider = (ProviderInfo) info;
            return !TextUtils.isEmpty(provider.readPermission)
                    ? provider.readPermission : provider.writePermission;
        }
        return null;
    }

    private String shortClassName(String className) {
        if (className == null) return "Unnamed component";
        int dot = className.lastIndexOf('.');
        return dot >= 0 && dot + 1 < className.length() ? className.substring(dot + 1) : className;
    }

    private void showComponent(ComponentEntry entry) {
        if (entry.kind == Kind.PROVIDER || !entry.invokable || entry.targetIntent == null) {
            new AlertDialog.Builder(this)
                    .setTitle(entry.label)
                    .setMessage(entry.packageName + "/" + entry.className + "\n\n" + entry.status)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String kind = entry.kind == Kind.SERVICE ? ActivityLauncherStore.KIND_SERVICE
                : entry.kind == Kind.RECEIVER ? ActivityLauncherStore.KIND_BROADCAST
                : ActivityLauncherStore.KIND_ACTIVITY;
        showTargetEditor(entry.label, entry.targetIntent, kind,
                entry.packageName + "/" + entry.className + "\n" + entry.status);
    }

    private void showTargetEditor(@NonNull String defaultLabel, @NonNull Intent target,
                                  @NonNull String kind, @NonNull String details) {
        ShortcutRecord existing = ActivityLauncherStore.find(this, target, kind);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView info = new TextView(this);
        info.setText(details);
        info.setTextIsSelectable(true);
        panel.addView(info);

        EditText label = new EditText(this);
        label.setHint("Smart S shortcut name");
        label.setSingleLine(true);
        label.setText(existing == null ? defaultLabel
                : ActivityLauncherStore.displayLabel(this, existing));
        panel.addView(label);

        Button test = makePanelButton("Test / launch now");
        test.setOnClickListener(v -> {
            if (!ActivityLauncherDispatchActivity.executeTarget(this, target, kind)) {
                Toast.makeText(this, "Target could not be invoked", Toast.LENGTH_LONG).show();
            }
        });
        panel.addView(test);

        Button save = makePanelButton(existing == null ? "Save in Smart S" : "Save / rename");
        save.setOnClickListener(v -> {
            ShortcutRecord record = ActivityLauncherStore.save(this,
                    cleanLabel(label, defaultLabel), target, kind);
            Toast.makeText(this, "Saved " + ActivityLauncherStore.displayLabel(this, record),
                    Toast.LENGTH_SHORT).show();
            rebuildNormalRowsIfIdle();
        });
        panel.addView(save);

        Button home = makePanelButton("Add to Smart S Home / history");
        home.setOnClickListener(v -> {
            ShortcutRecord record = ActivityLauncherStore.save(this,
                    cleanLabel(label, defaultLabel), target, kind);
            ActivityLauncherStore.addToHome(this, record);
            Toast.makeText(this, "Added to Smart S Home / history", Toast.LENGTH_SHORT).show();
            rebuildNormalRowsIfIdle();
        });
        panel.addView(home);

        Button favorite = makePanelButton("Add to Favorites");
        favorite.setOnClickListener(v -> {
            ShortcutRecord record = ActivityLauncherStore.save(this,
                    cleanLabel(label, defaultLabel), target, kind);
            ActivityLauncherStore.addToFavorites(this, record);
            Toast.makeText(this, "Added to Favorites", Toast.LENGTH_SHORT).show();
            rebuildNormalRowsIfIdle();
        });
        panel.addView(favorite);

        if (existing != null) {
            Button remove = makePanelButton("Delete saved Smart S target");
            remove.setOnClickListener(v -> {
                ActivityLauncherStore.remove(this, existing);
                Toast.makeText(this, "Saved target removed", Toast.LENGTH_SHORT).show();
                rebuildNormalRowsIfIdle();
            });
            panel.addView(remove);
        }

        new AlertDialog.Builder(this)
                .setTitle("Activity Launcher target")
                .setView(panel)
                .setNegativeButton("Close", null)
                .show();
    }

    private Button makePanelButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        return button;
    }

    private String cleanLabel(EditText input, String fallback) {
        String value = input.getText() == null ? "" : input.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    private void rebuildNormalRowsIfIdle() {
        if (search == null || TextUtils.isEmpty(search.getText())) rebuildNormalRows();
    }

    private void showSavedTargets() {
        List<ShortcutRecord> saved = ActivityLauncherStore.getSaved(this);
        if (saved.isEmpty()) {
            Toast.makeText(this, "No Activity Launcher targets saved yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[saved.size()];
        for (int i = 0; i < saved.size(); i++) {
            labels[i] = ActivityLauncherStore.displayLabel(this, saved.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("Saved Smart S targets")
                .setItems(labels, (dialog, which) -> openSaved(saved.get(which)))
                .setNegativeButton("Close", null)
                .show();
    }

    private void openSaved(ShortcutRecord record) {
        try {
            ActivityLauncherStore.DecodedTarget decoded = ActivityLauncherStore.decode(this, record);
            showTargetEditor(ActivityLauncherStore.displayLabel(this, record), decoded.targetIntent,
                    decoded.kind, "Saved Smart S target\n" + decoded.targetIntent.toUri(0));
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle(ActivityLauncherStore.displayLabel(this, record))
                    .setMessage("The stored target can no longer be decoded.\n\n" + record.intentUri)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void pickDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        documentPicker.launch(intent);
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPicker.launch(intent);
    }

    private void handleDocumentSelection(Intent result, boolean folder) {
        Uri uri = result.getData();
        if (uri == null) return;
        int grantFlags = result.getFlags();
        boolean canRead = (grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean canWrite = (grantFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        try {
            if (canRead && canWrite) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else if (canRead) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (canWrite) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (SecurityException ignored) { }

        String label = folder ? folderLabel(uri) : documentLabel(uri);
        Intent target = new Intent(Intent.ACTION_VIEW).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!folder) {
            String type = getContentResolver().getType(uri);
            if (!TextUtils.isEmpty(type)) target.setDataAndType(uri, type);
        }
        boolean resolvable = target.resolveActivity(getPackageManager()) != null;
        String details = (folder ? "Folder" : "Document") + " URI\n" + uri
                + (resolvable ? "\nLaunch handler available" : "\nNo current ACTION_VIEW handler; it can still be saved");
        showTargetEditor(label, target, ActivityLauncherStore.KIND_ACTIVITY, details);
    }

    private String documentLabel(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (!TextUtils.isEmpty(value)) return value;
                }
            }
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return TextUtils.isEmpty(last) ? "Document" : last;
    }

    private String folderLabel(Uri uri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            if (!TextUtils.isEmpty(id)) return id;
        } catch (RuntimeException ignored) { }
        return "Folder";
    }

    private void showCustomIntentDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText label = new EditText(this);
        label.setHint("Shortcut name (optional)");
        panel.addView(label);
        EditText value = new EditText(this);
        value.setHint("Deep link, Android action, or intent:#Intent... URI");
        value.setMinLines(3);
        value.setGravity(Gravity.TOP);
        panel.addView(value);
        new AlertDialog.Builder(this)
                .setTitle("Custom intent / deep link")
                .setView(panel)
                .setPositiveButton("Continue", (dialog, which) -> {
                    String raw = value.getText() == null ? "" : value.getText().toString().trim();
                    if (raw.isEmpty()) return;
                    try {
                        Intent target = parseCustomIntent(raw);
                        String fallback = !TextUtils.isEmpty(label.getText())
                                ? label.getText().toString().trim() : raw;
                        showTargetEditor(fallback, target, ActivityLauncherStore.KIND_ACTIVITY,
                                "Custom target\n" + target.toUri(0));
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid intent or URI", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Intent parseCustomIntent(String raw) throws java.net.URISyntaxException {
        if (raw.contains("#Intent;") || raw.startsWith("intent:")) {
            return Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
        }
        if (raw.contains("://")) return new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
        return new Intent(raw);
    }

    private void showSystemFeatures() {
        List<SystemTarget> available = new ArrayList<>();
        for (SystemTarget target : systemTargets()) {
            Intent intent = new Intent(target.action);
            if (intent.resolveActivity(getPackageManager()) != null) available.add(target);
        }
        String[] labels = new String[available.size()];
        for (int i = 0; i < available.size(); i++) labels[i] = available.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Android system features")
                .setItems(labels, (dialog, which) -> {
                    SystemTarget chosen = available.get(which);
                    Intent intent = new Intent(chosen.action);
                    showTargetEditor(chosen.label, intent, ActivityLauncherStore.KIND_ACTIVITY,
                            "Android system action\n" + chosen.action);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private List<SystemTarget> systemTargets() {
        List<SystemTarget> targets = new ArrayList<>();
        targets.add(new SystemTarget("Wi-Fi settings", "android.settings.WIFI_SETTINGS"));
        targets.add(new SystemTarget("Internet connectivity panel", "android.settings.panel.action.INTERNET_CONNECTIVITY"));
        targets.add(new SystemTarget("Bluetooth settings", "android.settings.BLUETOOTH_SETTINGS"));
        targets.add(new SystemTarget("NFC settings", "android.settings.NFC_SETTINGS"));
        targets.add(new SystemTarget("Mobile network settings", "android.settings.DATA_ROAMING_SETTINGS"));
        targets.add(new SystemTarget("VPN settings", "android.settings.VPN_SETTINGS"));
        targets.add(new SystemTarget("Location settings", "android.settings.LOCATION_SOURCE_SETTINGS"));
        targets.add(new SystemTarget("Display settings", "android.settings.DISPLAY_SETTINGS"));
        targets.add(new SystemTarget("Sound settings", "android.settings.SOUND_SETTINGS"));
        targets.add(new SystemTarget("Volume panel", "android.settings.panel.action.VOLUME"));
        targets.add(new SystemTarget("Accessibility settings", "android.settings.ACCESSIBILITY_SETTINGS"));
        targets.add(new SystemTarget("Developer options", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
        targets.add(new SystemTarget("Battery saver", "android.settings.BATTERY_SAVER_SETTINGS"));
        targets.add(new SystemTarget("Battery optimization", "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"));
        targets.add(new SystemTarget("Notification access", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        targets.add(new SystemTarget("Default apps", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"));
        targets.add(new SystemTarget("Display over other apps", "android.settings.action.MANAGE_OVERLAY_PERMISSION"));
        targets.add(new SystemTarget("Modify system settings", "android.settings.action.MANAGE_WRITE_SETTINGS"));
        targets.add(new SystemTarget("Storage settings", "android.settings.INTERNAL_STORAGE_SETTINGS"));
        targets.add(new SystemTarget("Security settings", "android.settings.SECURITY_SETTINGS"));
        targets.add(new SystemTarget("Date and time", "android.settings.DATE_SETTINGS"));
        targets.add(new SystemTarget("Keyboard and input", "android.settings.INPUT_METHOD_SETTINGS"));
        return targets;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum Kind {
        ACTIVITY(0), SERVICE(1), RECEIVER(2), PROVIDER(3);
        final int order;
        Kind(int order) { this.order = order; }
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;
        final boolean appEnabled;
        final List<ComponentEntry> components = new ArrayList<>();
        boolean expanded;
        boolean loading;
        boolean componentsLoaded;
        int activityCount;
        int serviceCount;
        int receiverCount;
        int providerCount;

        AppEntry(String packageName, String label, Drawable icon, boolean appEnabled) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.appEnabled = appEnabled;
        }

        int componentCount() {
            return activityCount + serviceCount + receiverCount + providerCount;
        }
    }

    private static final class ComponentEntry {
        final Kind kind;
        final String packageName;
        final String className;
        final String label;
        final String status;
        final boolean invokable;
        final Intent targetIntent;

        ComponentEntry(Kind kind, String packageName, String className, String label,
                       String status, boolean invokable, @Nullable Intent targetIntent) {
            this.kind = kind;
            this.packageName = packageName;
            this.className = className;
            this.label = label;
            this.status = status;
            this.invokable = invokable;
            this.targetIntent = targetIntent;
        }
    }

    private static final class SystemTarget {
        final String label;
        final String action;
        SystemTarget(String label, String action) {
            this.label = label;
            this.action = action;
        }
    }

    private static final class Row {
        final int type;
        final AppEntry app;
        final ComponentEntry component;
        final ShortcutRecord saved;
        final String title;

        private Row(int type, AppEntry app, ComponentEntry component,
                    ShortcutRecord saved, String title) {
            this.type = type;
            this.app = app;
            this.component = component;
            this.saved = saved;
            this.title = title;
        }

        static Row app(AppEntry app) { return new Row(ROW_APP, app, null, null, null); }
        static Row section(String title) { return new Row(ROW_SECTION, null, null, null, title); }
        static Row component(ComponentEntry component) { return new Row(ROW_COMPONENT, null, component, null, null); }
        static Row saved(ShortcutRecord saved) { return new Row(ROW_SAVED, null, null, saved, null); }
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowHolder> {
        @Override public int getItemViewType(int position) { return rows.get(position).type; }

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout root = new LinearLayout(ActivityLauncherActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(viewType == ROW_COMPONENT ? dp(38) : dp(10), dp(8), dp(10), dp(8));
            root.setMinimumHeight(dp(viewType == ROW_SECTION ? 42 : 64));

            ImageView icon = new ImageView(ActivityLauncherActivity.this);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            root.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout text = new LinearLayout(ActivityLauncherActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(10), 0, dp(8), 0);
            TextView title = new TextView(ActivityLauncherActivity.this);
            title.setTextSize(viewType == ROW_SECTION ? 17f : 16f);
            TextView subtitle = new TextView(ActivityLauncherActivity.this);
            subtitle.setTextSize(12f);
            subtitle.setMaxLines(3);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(title);
            text.addView(subtitle);
            root.addView(text, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView trailing = new TextView(ActivityLauncherActivity.this);
            trailing.setTextSize(18f);
            trailing.setGravity(Gravity.CENTER);
            root.addView(trailing, new LinearLayout.LayoutParams(dp(42), dp(42)));
            return new RowHolder(root, icon, title, subtitle, trailing);
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            Row row = rows.get(position);
            holder.itemView.setOnClickListener(null);
            holder.itemView.setOnLongClickListener(null);
            holder.icon.setImageDrawable(null);
            holder.icon.setVisibility(View.GONE);
            holder.subtitle.setVisibility(View.VISIBLE);
            holder.trailing.setVisibility(View.VISIBLE);
            holder.title.setTypeface(Typeface.DEFAULT);

            if (row.type == ROW_SECTION) {
                holder.title.setText(row.title);
                holder.title.setTypeface(Typeface.DEFAULT_BOLD);
                holder.subtitle.setVisibility(View.GONE);
                holder.trailing.setVisibility(View.GONE);
                return;
            }

            if (row.type == ROW_APP) {
                AppEntry app = row.app;
                holder.icon.setVisibility(View.VISIBLE);
                holder.icon.setImageDrawable(app.icon);
                holder.title.setText(app.label);
                String details = app.packageName;
                if (app.componentsLoaded) {
                    details += "\nActivities " + app.activityCount + "  Services " + app.serviceCount
                            + "  Receivers " + app.receiverCount + "  Providers " + app.providerCount;
                } else if (app.loading) {
                    details += "\nReading components...";
                } else if (!app.appEnabled) {
                    details += "\nApplication disabled";
                } else {
                    details += "\nTap to inspect components";
                }
                holder.subtitle.setText(details);
                holder.trailing.setText(app.expanded ? "^" : "v");
                holder.itemView.setOnClickListener(v -> toggleApp(app));
                Intent main = getPackageManager().getLaunchIntentForPackage(app.packageName);
                if (main != null) {
                    holder.itemView.setOnLongClickListener(v -> {
                        showTargetEditor(app.label, main, ActivityLauncherStore.KIND_ACTIVITY,
                                "Main application launch\n" + app.packageName);
                        return true;
                    });
                }
                return;
            }

            if (row.type == ROW_COMPONENT) {
                ComponentEntry entry = row.component;
                holder.title.setText(entry.label + "  [" + entry.kind.name().toLowerCase(Locale.ROOT) + "]");
                holder.subtitle.setText(entry.className + "\n" + entry.status);
                holder.trailing.setText(entry.invokable ? ">" : "i");
                holder.itemView.setOnClickListener(v -> showComponent(entry));
                return;
            }

            ShortcutRecord record = row.saved;
            holder.title.setText(ActivityLauncherStore.displayLabel(ActivityLauncherActivity.this, record));
            holder.subtitle.setText("Saved Activity Launcher target\n" + record.intentUri);
            holder.trailing.setText(">");
            holder.itemView.setOnClickListener(v -> openSaved(record));
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        final TextView trailing;

        RowHolder(@NonNull View itemView, ImageView icon, TextView title,
                  TextView subtitle, TextView trailing) {
            super(itemView);
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.trailing = trailing;
        }
    }
}
