from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / "app/src/main/java/fr/neamar/kiss/activitylauncher/ActivityLauncherActivity.java"


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


text = PATH.read_text(encoding="utf-8")

text = replace_once(text,
'''import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
''',
'''import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
''', "imports-text")

text = replace_once(text,
'''import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
''',
'''import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
''', "imports-widgets")

text = replace_once(text,
'''import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
''',
'''import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;
''', "imports-insets")

text = replace_once(text,
'''import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
''',
'''import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
''', "imports-collections")

text = replace_once(text,
'''    private static final long SEARCH_DELAY_MS = 250L;

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
''',
'''    private static final long SEARCH_DELAY_MS = 60L;

    private final List<AppEntry> apps = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final AtomicInteger lifecycleGeneration = new AtomicInteger();
    private final AtomicInteger searchGeneration = new AtomicInteger();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private RecyclerView recycler;
    private RowAdapter adapter;
    private EditText search;
    private TextView status;
    private ProgressBar progress;
    private ExecutorService worker;
    private boolean lockListenerRegistered;
    private Runnable pendingSearch;
    private List<String> activeSearchTerms = Collections.emptyList();
    private int searchHighlightColor;
''', "fields")

text = replace_once(text,
'''        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        search = new EditText(this);
''',
'''        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int baseLeft = dp(12);
        final int baseTop = dp(8);
        final int baseRight = dp(12);
        final int baseBottom = dp(8);
        root.setPadding(baseLeft, baseTop, baseRight, baseBottom);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(baseLeft + bars.left, baseTop + bars.top,
                    baseRight + bars.right, baseBottom + bars.bottom);
            return windowInsets;
        });
        searchHighlightColor = resolveSearchHighlightColor();

        search = new EditText(this);
''', "root-insets")

text = replace_once(text,
'''        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
''',
'''        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        search.addTextChangedListener(new TextWatcher() {
''', "request-insets")

text = replace_once(text,
'''    private void stopForegroundWork() {
        generation.incrementAndGet();
        mainHandler.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        setBusy(false);
    }
''',
'''    private void stopForegroundWork() {
        lifecycleGeneration.incrementAndGet();
        searchGeneration.incrementAndGet();
        if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
        pendingSearch = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        setBusy(false);
    }
''', "stop-work")

text = replace_once(text,
'''    private void refreshApplications() {
        apps.clear();
        rows.clear();
        adapter.notifyDataSetChanged();
        loadApplications();
    }
''',
'''    private void refreshApplications() {
        stopForegroundWork();
        ensureWorker();
        apps.clear();
        rows.clear();
        adapter.notifyDataSetChanged();
        loadApplications();
    }
''', "refresh")

old_load_search = '''    @SuppressWarnings("deprecation")
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
'''

new_load_search = '''    @SuppressWarnings("deprecation")
    private void loadApplications() {
        final int lifecycleToken = lifecycleGeneration.get();
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
                if (Thread.currentThread().isInterrupted()
                        || lifecycleToken != lifecycleGeneration.get()) return;
                CharSequence rawLabel = info.loadLabel(pm);
                String label = TextUtils.isEmpty(rawLabel) ? info.packageName : rawLabel.toString();
                Drawable icon;
                try { icon = info.loadIcon(pm); }
                catch (RuntimeException e) { icon = pm.getDefaultActivityIcon(); }
                loaded.add(new AppEntry(info.packageName, label, icon,
                        isApplicationEnabled(pm, info)));
            }
            loaded.sort(Comparator.comparing(app -> app.label, String.CASE_INSENSITIVE_ORDER));
            runOnUiThread(() -> {
                if (lifecycleToken != lifecycleGeneration.get() || isFinishing()) return;
                apps.clear();
                apps.addAll(loaded);
                setBusy(false);
                CharSequence current = search == null ? null : search.getText();
                if (!TextUtils.isEmpty(current)) {
                    scheduleSearch(current.toString());
                } else {
                    status.setText(apps.size() + " applications. Tap an app to inspect components.");
                    rebuildNormalRows();
                }
            });
        });
    }

    private void scheduleSearch(String rawQuery) {
        final String query = rawQuery == null ? "" : rawQuery.trim();
        final int token = searchGeneration.incrementAndGet();
        final int lifecycleToken = lifecycleGeneration.get();
        if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
        pendingSearch = null;
        activeSearchTerms = ActivityLauncherSearch.terms(query);

        if (activeSearchTerms.isEmpty()) {
            setBusy(false);
            rebuildNormalRows();
            return;
        }

        rebuildLiveSearchRows(activeSearchTerms);
        if (apps.isEmpty()) {
            status.setText("Live search waiting for installed applications...");
            return;
        }
        if (query.length() < 2) {
            status.setText(rows.size() + " matching rows - live search");
            return;
        }

        pendingSearch = () -> runSearch(query, token, lifecycleToken);
        mainHandler.postDelayed(pendingSearch, SEARCH_DELAY_MS);
    }

    private void rebuildLiveSearchRows(@NonNull List<String> terms) {
        rows.clear();
        for (ShortcutRecord saved : ActivityLauncherStore.getSaved(this)) {
            if (ActivityLauncherSearch.matchesAll(terms,
                    ActivityLauncherStore.displayLabel(this, saved), saved.intentUri)) {
                rows.add(Row.saved(saved));
            }
        }
        for (AppEntry app : apps) {
            boolean appMatch = ActivityLauncherSearch.matchesAll(terms, app.label, app.packageName);
            List<ComponentEntry> matches = new ArrayList<>();
            if (app.componentsLoaded) {
                for (ComponentEntry component : app.components) {
                    if (componentMatches(terms, component)) matches.add(component);
                }
            }
            if (appMatch || !matches.isEmpty()) {
                rows.add(Row.app(app));
                for (ComponentEntry component : matches) rows.add(Row.component(component));
            }
        }
        adapter.notifyDataSetChanged();
        status.setText(rows.size() + " matching rows - live search");
    }

    private void runSearch(String rawQuery, int token, int lifecycleToken) {
        if (apps.isEmpty()) return;
        setBusy(true);
        status.setText("Searching all components for \"" + rawQuery + "\"...");
        final List<String> terms = ActivityLauncherSearch.terms(rawQuery);
        submit(() -> {
            List<Row> found = new ArrayList<>();
            for (ShortcutRecord saved : ActivityLauncherStore.getSaved(this)) {
                if (ActivityLauncherSearch.matchesAll(terms,
                        ActivityLauncherStore.displayLabel(this, saved), saved.intentUri)) {
                    found.add(Row.saved(saved));
                }
            }
            for (AppEntry app : apps) {
                if (Thread.currentThread().isInterrupted()
                        || token != searchGeneration.get()
                        || lifecycleToken != lifecycleGeneration.get()) return;
                boolean appMatch = ActivityLauncherSearch.matchesAll(terms, app.label, app.packageName);
                List<ComponentEntry> componentMatches = new ArrayList<>();
                for (ComponentEntry component : loadComponentsSync(app)) {
                    if (componentMatches(terms, component)) componentMatches.add(component);
                }
                if (appMatch || !componentMatches.isEmpty()) {
                    found.add(Row.app(app));
                    for (ComponentEntry component : componentMatches) found.add(Row.component(component));
                }
            }
            runOnUiThread(() -> {
                if (token != searchGeneration.get()
                        || lifecycleToken != lifecycleGeneration.get()
                        || isFinishing()) return;
                rows.clear();
                rows.addAll(found);
                adapter.notifyDataSetChanged();
                setBusy(false);
                status.setText(found.size() + " matching rows - live search complete");
            });
        });
    }

    private boolean componentMatches(@NonNull List<String> terms,
                                     @NonNull ComponentEntry component) {
        return ActivityLauncherSearch.matchesAll(terms, component.label, component.className,
                component.packageName, component.status,
                component.kind.name().toLowerCase(Locale.ROOT));
    }
'''
text = replace_once(text, old_load_search, new_load_search, "load-search")

old_toggle = '''    private void toggleApp(AppEntry app) {
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
'''
new_toggle = '''    private void toggleApp(AppEntry app) {
        if (app.componentsLoaded) {
            if (!TextUtils.isEmpty(search.getText())) {
                app.expanded = true;
                scheduleSearch(search.getText().toString());
            } else {
                app.expanded = !app.expanded;
                rebuildNormalRows();
            }
            return;
        }
        app.expanded = true;
        app.loading = true;
        if (TextUtils.isEmpty(search.getText())) rebuildNormalRows();
        setBusy(true);
        status.setText("Reading " + app.label + " components...");
        int lifecycleToken = lifecycleGeneration.get();
        submit(() -> {
            loadComponentsSync(app);
            runOnUiThread(() -> {
                if (lifecycleToken != lifecycleGeneration.get() || isFinishing()) return;
                app.loading = false;
                setBusy(false);
                status.setText("Loaded " + app.componentCount() + " components for " + app.label);
                if (TextUtils.isEmpty(search.getText())) rebuildNormalRows();
                else scheduleSearch(search.getText().toString());
            });
        });
    }
'''
text = replace_once(text, old_toggle, new_toggle, "toggle")

old_component = '''    private ComponentEntry componentEntry(ComponentInfo info, Kind kind) {
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
'''
new_component = '''    private ComponentEntry componentEntry(ComponentInfo info, Kind kind) {
        PackageManager pm = getPackageManager();
        CharSequence labelText;
        try { labelText = info.loadLabel(pm); }
        catch (RuntimeException e) { labelText = null; }
        String label = TextUtils.isEmpty(labelText) ? shortClassName(info.name) : labelText.toString();
        boolean ownPackage = getPackageName().equals(info.packageName);
        boolean exported = info.exported || ownPackage;
        boolean applicationEnabled = info.applicationInfo != null
                && isApplicationEnabled(pm, info.applicationInfo);
        boolean componentEnabled = isComponentEnabled(pm, info);
        boolean enabled = applicationEnabled && componentEnabled;
        String permission = componentPermission(info);
        boolean permissionAllowed = TextUtils.isEmpty(permission)
                || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
        boolean invokable = kind != Kind.PROVIDER && exported && enabled && permissionAllowed;
        StringBuilder state = new StringBuilder();
        state.append(info.exported ? "Exported" : ownPackage ? "Internal (own app)" : "Private");
        if (!applicationEnabled) state.append(" - Application disabled");
        else if (!componentEnabled) state.append(" - Component disabled");
        else state.append(" - Enabled");
        if (!TextUtils.isEmpty(permission) && !permissionAllowed) state.append(" - Requires ").append(permission);
        if (kind == Kind.PROVIDER) {
            state.append(" - Information only");
        } else if (!enabled) {
            state.append(" - Tap to enable; root may be requested");
        } else if (!exported || !permissionAllowed) {
            state.append(" - Privileged/root launch required");
        } else if (kind == Kind.SERVICE || kind == Kind.RECEIVER) {
            state.append(" - May require app-specific action/extras");
        }
        Intent target = kind == Kind.PROVIDER ? null
                : new Intent().setComponent(new ComponentName(info.packageName, info.name));
        return new ComponentEntry(kind, info.packageName, info.name, label,
                state.toString(), invokable, target, exported, applicationEnabled,
                componentEnabled, permissionAllowed);
    }

    private boolean isApplicationEnabled(@NonNull PackageManager pm,
                                         @NonNull ApplicationInfo info) {
        try {
            int state = pm.getApplicationEnabledSetting(info.packageName);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) return false;
        } catch (IllegalArgumentException ignored) { }
        return info.enabled;
    }

    private boolean isComponentEnabled(@NonNull PackageManager pm, @NonNull ComponentInfo info) {
        try {
            int state = pm.getComponentEnabledSetting(new ComponentName(info.packageName, info.name));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) return false;
        } catch (IllegalArgumentException ignored) { }
        return info.enabled;
    }
'''
text = replace_once(text, old_component, new_component, "component-state")

old_show = '''    private void showComponent(ComponentEntry entry) {
        if (entry.kind == Kind.PROVIDER || !entry.invokable || entry.targetIntent == null) {
            new AlertDialog.Builder(this)
                    .setTitle(entry.label)
                    .setMessage(entry.packageName + "/" + entry.className + "\\n\\n" + entry.status)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String kind = entry.kind == Kind.SERVICE ? ActivityLauncherStore.KIND_SERVICE
                : entry.kind == Kind.RECEIVER ? ActivityLauncherStore.KIND_BROADCAST
                : ActivityLauncherStore.KIND_ACTIVITY;
        showTargetEditor(entry.label, entry.targetIntent, kind,
                entry.packageName + "/" + entry.className + "\\n" + entry.status);
    }
'''
new_show = '''    private void showComponent(ComponentEntry entry) {
        if (entry.kind == Kind.PROVIDER || entry.targetIntent == null) {
            new AlertDialog.Builder(this)
                    .setTitle(entry.label)
                    .setMessage(entry.packageName + "/" + entry.className + "\\n\\n" + entry.status)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (!entry.applicationEnabled || !entry.componentEnabled) {
            enableComponentAndOpen(entry);
            return;
        }
        boolean privileged = !entry.exported || !entry.permissionAllowed;
        String kind = targetKind(entry.kind, privileged);
        showTargetEditor(entry.label, entry.targetIntent, kind,
                entry.packageName + "/" + entry.className + "\\n" + entry.status
                        + (privileged ? "\\nThis target uses an on-demand root launch." : ""));
    }

    private String targetKind(@NonNull Kind kind, boolean privileged) {
        if (kind == Kind.SERVICE) return privileged
                ? ActivityLauncherStore.KIND_PRIVILEGED_SERVICE
                : ActivityLauncherStore.KIND_SERVICE;
        if (kind == Kind.RECEIVER) return privileged
                ? ActivityLauncherStore.KIND_PRIVILEGED_BROADCAST
                : ActivityLauncherStore.KIND_BROADCAST;
        return privileged ? ActivityLauncherStore.KIND_PRIVILEGED_ACTIVITY
                : ActivityLauncherStore.KIND_ACTIVITY;
    }

    private void enableApplicationAndInspect(@NonNull AppEntry app) {
        setBusy(true);
        status.setText("Enabling " + app.label + " on demand...");
        final int lifecycleToken = lifecycleGeneration.get();
        submit(() -> {
            ActivityLauncherPrivilegeBridge.Result result =
                    ActivityLauncherPrivilegeBridge.enableApplication(this, app.packageName);
            runOnUiThread(() -> {
                if (lifecycleToken != lifecycleGeneration.get() || isFinishing()) return;
                setBusy(false);
                if (result != ActivityLauncherPrivilegeBridge.Result.SUCCESS) {
                    showEnableFailure(app.packageName, app.label, result);
                    return;
                }
                app.appEnabled = true;
                app.componentsLoaded = false;
                app.components.clear();
                app.expanded = false;
                Toast.makeText(this, app.label + " enabled", Toast.LENGTH_SHORT).show();
                toggleApp(app);
            });
        });
    }

    private void enableComponentAndOpen(@NonNull ComponentEntry entry) {
        setBusy(true);
        status.setText("Enabling " + entry.label + " on demand...");
        final int lifecycleToken = lifecycleGeneration.get();
        submit(() -> {
            ComponentName component = new ComponentName(entry.packageName, entry.className);
            ActivityLauncherPrivilegeBridge.Result result =
                    ActivityLauncherPrivilegeBridge.enableComponent(this, component);
            ComponentEntry refreshed = result == ActivityLauncherPrivilegeBridge.Result.SUCCESS
                    ? reloadComponent(entry) : null;
            runOnUiThread(() -> {
                if (lifecycleToken != lifecycleGeneration.get() || isFinishing()) return;
                setBusy(false);
                if (result != ActivityLauncherPrivilegeBridge.Result.SUCCESS || refreshed == null) {
                    showEnableFailure(entry.packageName, entry.label, result);
                    return;
                }
                Toast.makeText(this, entry.label + " enabled", Toast.LENGTH_SHORT).show();
                showComponent(refreshed);
                if (!TextUtils.isEmpty(search.getText())) scheduleSearch(search.getText().toString());
                else rebuildNormalRows();
            });
        });
    }

    @Nullable
    private ComponentEntry reloadComponent(@NonNull ComponentEntry original) {
        for (AppEntry app : apps) {
            if (!app.packageName.equals(original.packageName)) continue;
            app.appEnabled = ActivityLauncherPrivilegeBridge.isApplicationEffectivelyEnabled(
                    this, app.packageName);
            app.componentsLoaded = false;
            app.components.clear();
            for (ComponentEntry refreshed : loadComponentsSync(app)) {
                if (refreshed.kind == original.kind
                        && refreshed.className.equals(original.className)) return refreshed;
            }
            return null;
        }
        return null;
    }

    private void showEnableFailure(@NonNull String packageName, @NonNull String label,
                                   @NonNull ActivityLauncherPrivilegeBridge.Result result) {
        String reason = result == ActivityLauncherPrivilegeBridge.Result.ROOT_UNAVAILABLE_OR_DENIED
                ? "Android blocked the change and root access was unavailable or denied."
                : "Android still reports the target as disabled after the enable attempt.";
        new AlertDialog.Builder(this)
                .setTitle("Could not enable " + label)
                .setMessage(reason + "\\n\\nSmart S only requests root when you tap a target; "
                        + "nothing remains running in the background.")
                .setPositiveButton("App info", (dialog, which) -> {
                    Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + packageName));
                    try { startActivity(details); }
                    catch (RuntimeException ignored) { }
                })
                .setNegativeButton("Close", null)
                .show();
    }
'''
text = replace_once(text, old_show, new_show, "show-component")

text = replace_once(text,
'''        } catch (SecurityException ignored) { }

        String label = folder ? folderLabel(uri) : documentLabel(uri);
''',
'''        } catch (SecurityException | IllegalArgumentException ignored) { }

        String label = folder ? folderLabel(uri) : documentLabel(uri);
''', "persistable-uri")

old_custom = '''    private void showCustomIntentDialog() {
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
                                "Custom target\\n" + target.toUri(0));
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid intent or URI", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
'''
new_custom = '''    private void showCustomIntentDialog() {
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

        RadioGroup type = new RadioGroup(this);
        type.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton activity = new RadioButton(this);
        activity.setId(View.generateViewId());
        activity.setText("Activity");
        activity.setChecked(true);
        RadioButton service = new RadioButton(this);
        service.setId(View.generateViewId());
        service.setText("Service");
        RadioButton broadcast = new RadioButton(this);
        broadcast.setId(View.generateViewId());
        broadcast.setText("Broadcast");
        type.addView(activity);
        type.addView(service);
        type.addView(broadcast);
        panel.addView(type);

        CheckBox privileged = new CheckBox(this);
        privileged.setText("Use root/privileged launch for private or protected explicit components");
        panel.addView(privileged);

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
                        boolean root = privileged.isChecked();
                        String kind;
                        if (type.getCheckedRadioButtonId() == service.getId()) {
                            kind = root ? ActivityLauncherStore.KIND_PRIVILEGED_SERVICE
                                    : ActivityLauncherStore.KIND_SERVICE;
                        } else if (type.getCheckedRadioButtonId() == broadcast.getId()) {
                            kind = root ? ActivityLauncherStore.KIND_PRIVILEGED_BROADCAST
                                    : ActivityLauncherStore.KIND_BROADCAST;
                        } else {
                            kind = root ? ActivityLauncherStore.KIND_PRIVILEGED_ACTIVITY
                                    : ActivityLauncherStore.KIND_ACTIVITY;
                        }
                        showTargetEditor(fallback, target, kind,
                                "Custom target\\n" + target.toUri(0)
                                        + (root ? "\\nOn-demand root/privileged launch" : ""));
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid intent or URI", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
'''
text = replace_once(text, old_custom, new_custom, "custom-intent")

old_system = '''    private void showSystemFeatures() {
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
                            "Android system action\\n" + chosen.action);
                })
                .setNegativeButton("Close", null)
                .show();
    }
'''
new_system = '''    private void showSystemFeatures() {
        List<SystemTarget> targets = systemTargets();
        String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            SystemTarget target = targets.get(i);
            Intent intent = new Intent(target.action);
            labels[i] = target.label + (intent.resolveActivity(getPackageManager()) == null
                    ? " (no current handler)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Android system features")
                .setItems(labels, (dialog, which) -> {
                    SystemTarget chosen = targets.get(which);
                    Intent intent = new Intent(chosen.action);
                    boolean resolved = intent.resolveActivity(getPackageManager()) != null;
                    showTargetEditor(chosen.label, intent, ActivityLauncherStore.KIND_ACTIVITY,
                            "Android system action\\n" + chosen.action
                                    + (resolved ? "" : "\\nNo current handler; target can still be saved"));
                })
                .setNegativeButton("Close", null)
                .show();
    }
'''
text = replace_once(text, old_system, new_system, "system-features")

text = replace_once(text,
'''    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
''',
'''    private int resolveSearchHighlightColor() {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) {
            int color = value.resourceId != 0
                    ? ContextCompat.getColor(this, value.resourceId) : value.data;
            return (color & 0x00ffffff) | 0x66000000;
        }
        return 0x66ffeb3b;
    }

    @NonNull
    private CharSequence highlightSearch(@Nullable String value) {
        String source = value == null ? "" : value;
        if (activeSearchTerms.isEmpty() || source.isEmpty()) return source;
        SpannableString highlighted = new SpannableString(source);
        String lower = source.toLowerCase(Locale.ROOT);
        for (String term : activeSearchTerms) {
            int start = lower.indexOf(term);
            while (start >= 0) {
                int end = start + term.length();
                highlighted.setSpan(new BackgroundColorSpan(searchHighlightColor), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                highlighted.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                start = lower.indexOf(term, end);
            }
        }
        return highlighted;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
''', "highlight-methods")

text = replace_once(text,
'''        final boolean appEnabled;
''',
'''        boolean appEnabled;
''', "app-enabled-mutable")

old_entry = '''        final boolean invokable;
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
'''
new_entry = '''        final boolean invokable;
        final Intent targetIntent;
        final boolean exported;
        final boolean applicationEnabled;
        final boolean componentEnabled;
        final boolean permissionAllowed;

        ComponentEntry(Kind kind, String packageName, String className, String label,
                       String status, boolean invokable, @Nullable Intent targetIntent,
                       boolean exported, boolean applicationEnabled, boolean componentEnabled,
                       boolean permissionAllowed) {
            this.kind = kind;
            this.packageName = packageName;
            this.className = className;
            this.label = label;
            this.status = status;
            this.invokable = invokable;
            this.targetIntent = targetIntent;
            this.exported = exported;
            this.applicationEnabled = applicationEnabled;
            this.componentEnabled = componentEnabled;
            this.permissionAllowed = permissionAllowed;
        }
'''
text = replace_once(text, old_entry, new_entry, "component-entry")

old_app_bind = '''                holder.icon.setVisibility(View.VISIBLE);
                holder.icon.setImageDrawable(app.icon);
                holder.title.setText(app.label);
                String details = app.packageName;
                if (app.componentsLoaded) {
                    details += "\\nActivities " + app.activityCount + "  Services " + app.serviceCount
                            + "  Receivers " + app.receiverCount + "  Providers " + app.providerCount;
                } else if (app.loading) {
                    details += "\\nReading components...";
                } else if (!app.appEnabled) {
                    details += "\\nApplication disabled";
                } else {
                    details += "\\nTap to inspect components";
                }
                holder.subtitle.setText(details);
                holder.trailing.setText(app.expanded ? "^" : "v");
                holder.itemView.setOnClickListener(v -> toggleApp(app));
'''
new_app_bind = '''                holder.icon.setVisibility(View.VISIBLE);
                holder.icon.setImageDrawable(app.icon);
                holder.title.setText(highlightSearch(app.label));
                String details = app.packageName;
                if (app.componentsLoaded) {
                    details += "\\nActivities " + app.activityCount + "  Services " + app.serviceCount
                            + "  Receivers " + app.receiverCount + "  Providers " + app.providerCount;
                } else if (app.loading) {
                    details += "\\nReading components...";
                } else if (!app.appEnabled) {
                    details += "\\nApplication disabled - tap to enable";
                } else {
                    details += "\\nTap to inspect components";
                }
                holder.subtitle.setText(highlightSearch(details));
                holder.trailing.setText(!app.appEnabled ? "!" : app.expanded ? "^" : "v");
                holder.itemView.setOnClickListener(v -> {
                    if (app.appEnabled) toggleApp(app);
                    else enableApplicationAndInspect(app);
                });
'''
text = replace_once(text, old_app_bind, new_app_bind, "adapter-app")

old_component_bind = '''            if (row.type == ROW_COMPONENT) {
                ComponentEntry entry = row.component;
                holder.title.setText(entry.label + "  [" + entry.kind.name().toLowerCase(Locale.ROOT) + "]");
                holder.subtitle.setText(entry.className + "\\n" + entry.status);
                holder.trailing.setText(entry.invokable ? ">" : "i");
                holder.itemView.setOnClickListener(v -> showComponent(entry));
                return;
            }

            ShortcutRecord record = row.saved;
            holder.title.setText(ActivityLauncherStore.displayLabel(ActivityLauncherActivity.this, record));
            holder.subtitle.setText("Saved Activity Launcher target\\n" + record.intentUri);
'''
new_component_bind = '''            if (row.type == ROW_COMPONENT) {
                ComponentEntry entry = row.component;
                holder.title.setText(highlightSearch(entry.label + "  ["
                        + entry.kind.name().toLowerCase(Locale.ROOT) + "]"));
                holder.subtitle.setText(highlightSearch(entry.className + "\\n" + entry.status));
                if (entry.kind == Kind.PROVIDER) holder.trailing.setText("i");
                else if (!entry.applicationEnabled || !entry.componentEnabled) holder.trailing.setText("!");
                else if (!entry.exported || !entry.permissionAllowed) holder.trailing.setText("R");
                else holder.trailing.setText(">");
                holder.itemView.setOnClickListener(v -> showComponent(entry));
                return;
            }

            ShortcutRecord record = row.saved;
            holder.title.setText(highlightSearch(ActivityLauncherStore.displayLabel(
                    ActivityLauncherActivity.this, record)));
            holder.subtitle.setText(highlightSearch("Saved Activity Launcher target\\n" + record.intentUri));
'''
text = replace_once(text, old_component_bind, new_component_bind, "adapter-component-saved")

# Fail-closed assertions for the architectural requirements.
required = [
    "ViewCompat.setOnApplyWindowInsetsListener",
    "WindowInsetsCompat.Type.systemBars()",
    "lifecycleGeneration",
    "searchGeneration",
    "ActivityLauncherSearch.matchesAll",
    "highlightSearch",
    "enableApplicationAndInspect",
    "enableComponentAndOpen",
    "KIND_PRIVILEGED_ACTIVITY",
    "Application disabled - tap to enable",
]
for marker in required:
    if marker not in text:
        raise SystemExit(f"missing required marker after patch: {marker}")

for forbidden in ("WorkManager.", "JobScheduler.", "AlarmManager.", "WakeLock", "registerReceiver("):
    if forbidden in text:
        raise SystemExit(f"foreground-only violation in Activity Launcher UI: {forbidden}")

PATH.write_text(text, encoding="utf-8")
print("Activity Launcher 3.30.72 UI patch applied with exact-source assertions")
