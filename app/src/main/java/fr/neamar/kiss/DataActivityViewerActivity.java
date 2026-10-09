package fr.neamar.kiss;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.neamar.kiss.db.AppSourceMetadataRecord;
import fr.neamar.kiss.db.DBHelper;
import fr.neamar.kiss.db.SemanticActivityRecord;
import fr.neamar.kiss.forwarder.InterfaceTweaks;
import fr.neamar.kiss.searcher.AppSourceMetadataUpdater;
import fr.neamar.kiss.searcher.SemanticHnswIndex;

/**
 * Human-readable transparency view for app descriptions and semantic/HNSW indexing.
 *
 * <p>The default view intentionally shows apps with cached descriptions rather than thousands of
 * low-level HNSW records. Technical indexing events remain available through the filter.</p>
 */
public final class DataActivityViewerActivity extends AppCompatActivity {
    private static final int LOAD_LIMIT = 5000;
    private static final String TYPE_DESCRIPTION_DOWNLOADED = "APP_DESCRIPTION_DOWNLOADED";
    private static final String TYPE_DESCRIPTION_LOCAL = "APP_DESCRIPTION_LOCAL";
    private static final String TYPE_DESCRIPTION_MISSING = "APP_DESCRIPTION_MISSING";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smart-s-data-activity-viewer");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private final List<SemanticActivityRecord> all = new ArrayList<>();
    private final List<SemanticActivityRecord> visible = new ArrayList<>();

    private SharedPreferences prefs;
    private TextView status;
    private Spinner filter;
    private EditText search;
    private Button updateDescriptions;
    private ActivityAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        InterfaceTweaks.applySettingsTheme(this, prefs);
        super.onCreate(savedInstanceState);

        setTitle("Data Activity Viewer");
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }

        setContentView(buildUi());
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
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

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        TextView explanation = new TextView(this);
        explanation.setText("See which apps really have descriptions, where the data came from, "
                + "when it was downloaded, and whether the app was indexed into HNSW. "
                + "Tap an app to read its full description.");
        explanation.setTextSize(13.5f);
        explanation.setPadding(0, 0, 0, dp(8));
        root.addView(explanation);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        filter = new Spinner(this);
        String[] choices = {
                "Downloaded app descriptions",
                "Local fallback descriptions",
                "Apps missing descriptions",
                "Metadata refresh history",
                "Apps indexed in HNSW",
                "HNSW build history",
                "All technical activity"
        };
        filter.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, choices));
        filter.setSelection(0, false);
        filter.setOnItemSelectedListener(new SimpleItemSelectedListener(this::applyFilter));
        root.addView(filter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        updateDescriptions = new Button(this);
        updateDescriptions.setText("Update descriptions");
        updateDescriptions.setAllCaps(false);
        updateDescriptions.setOnClickListener(v -> updateDescriptionsNow());
        actions.addView(updateDescriptions, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> reload());
        actions.addView(refresh);

        Button clear = new Button(this);
        clear.setText("Clear log");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> confirmClear());
        actions.addView(clear);
        root.addView(actions);

        search = new EditText(this);
        search.setHint("Filter app, package or source");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        adapter = new ActivityAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private void updateDescriptionsNow() {
        if (AppSourceMetadataUpdater.isRunning()) {
            Toast.makeText(this, "App descriptions are already being updated",
                    Toast.LENGTH_SHORT).show();
            reload();
            return;
        }

        updateDescriptions.setEnabled(false);
        boolean started = AppSourceMetadataUpdater.refreshAll(
                this,
                KissApplication.getApplication(this).getDataHandler(),
                prefs,
                () -> {
                    Toast.makeText(
                            this,
                            AppSourceMetadataUpdater.statusSummary(this),
                            Toast.LENGTH_LONG).show();
                    reload();
                });
        if (!started) updateDescriptions.setEnabled(true);
        status.setText(started
                ? "Updating descriptions for installed, disabled and remembered apps…"
                : "App description update is already running.");
    }

    private void reload() {
        if (status == null) return;
        status.setText("Loading app-description data…");
        if (updateDescriptions != null) {
            updateDescriptions.setEnabled(!AppSourceMetadataUpdater.isRunning());
        }

        executor.execute(() -> {
            List<SemanticActivityRecord> loaded =
                    new ArrayList<>(DBHelper.getSemanticActivity(this, LOAD_LIMIT));
            Map<String, AppSourceMetadataRecord> metadata =
                    DBHelper.getAppSourceMetadata(this);

            int downloadedReady = 0;
            int localReady = 0;
            int missing = 0;
            for (AppSourceMetadataRecord record : metadata.values()) {
                if (record == null || TextUtils.isEmpty(record.packageName)) continue;
                boolean hasDescription = !TextUtils.isEmpty(record.description);
                boolean localDescription = hasDescription
                        && "Android app manifest".equals(record.source);
                if (hasDescription && localDescription) localReady++;
                else if (hasDescription) downloadedReady++;
                else missing++;

                StringBuilder details = new StringBuilder();
                if (hasDescription) {
                    details.append(record.description.trim());
                } else {
                    details.append("No usable online or manifest description has been cached yet.");
                }
                if (!TextUtils.isEmpty(record.sourceUrl)) {
                    details.append("\n\nSource URL: ").append(record.sourceUrl);
                }
                if (!TextUtils.isEmpty(record.installerPackage)) {
                    details.append("\nInstaller package: ").append(record.installerPackage);
                }
                if (!TextUtils.isEmpty(record.lastError)) {
                    details.append("\nLast fetch result: ").append(record.lastError);
                }

                String snapshotType = !hasDescription
                        ? TYPE_DESCRIPTION_MISSING
                        : (localDescription
                                ? TYPE_DESCRIPTION_LOCAL
                                : TYPE_DESCRIPTION_DOWNLOADED);
                loaded.add(new SemanticActivityRecord(
                        record.fetchedAt,
                        snapshotType,
                        "current-cache",
                        record.packageName,
                        TextUtils.isEmpty(record.title) ? record.packageName : record.title,
                        record.source,
                        details.toString()));
            }

            loaded.sort((left, right) -> Long.compare(right.eventTime, left.eventTime));
            final int downloadedCount = downloadedReady;
            final int localCount = localReady;
            final int missingCount = missing;
            final int packageCount = downloadedReady + localReady + missing;
            final int technicalCount = loaded.size() - packageCount;
            final String hnsw = SemanticHnswIndex.getInstance().statusSummary();

            runOnUiThread(() -> {
                all.clear();
                all.addAll(loaded);
                status.setText("Online descriptions: " + downloadedCount + " / " + packageCount
                        + " apps · Local fallback: " + localCount
                        + " · Missing: " + missingCount
                        + "\nMetadata updater: "
                        + (AppSourceMetadataUpdater.isRunning() ? "RUNNING" : "idle")
                        + " · Technical events: " + technicalCount
                        + "\nHNSW: " + hnsw);
                if (updateDescriptions != null) {
                    updateDescriptions.setEnabled(!AppSourceMetadataUpdater.isRunning());
                }
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        if (adapter == null || filter == null || search == null) return;
        int mode = filter.getSelectedItemPosition();
        String query = search.getText() == null
                ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);

        visible.clear();
        for (SemanticActivityRecord record : all) {
            if (!matchesMode(record, mode)) continue;
            if (!query.isEmpty() && !searchable(record).contains(query)) continue;
            visible.add(record);
        }
        adapter.notifyDataSetChanged();
    }

    private static boolean matchesMode(SemanticActivityRecord record, int mode) {
        String type = safe(record.eventType);
        switch (mode) {
            case 0:
                return TYPE_DESCRIPTION_DOWNLOADED.equals(type);
            case 1:
                return TYPE_DESCRIPTION_LOCAL.equals(type);
            case 2:
                return TYPE_DESCRIPTION_MISSING.equals(type);
            case 3:
                return type.startsWith("METADATA_");
            case 4:
                return "HNSW_APP_INDEXED".equals(type);
            case 5:
                return type.startsWith("HNSW_BUILD");
            default:
                return !TYPE_DESCRIPTION_DOWNLOADED.equals(type)
                        && !TYPE_DESCRIPTION_LOCAL.equals(type)
                        && !TYPE_DESCRIPTION_MISSING.equals(type);
        }
    }

    private static String searchable(SemanticActivityRecord record) {
        return (safe(record.eventType) + " "
                + safe(record.packageName) + " "
                + safe(record.appName) + " "
                + safe(record.source) + " "
                + safe(record.details))
                .toLowerCase(Locale.ROOT);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear technical activity history?")
                .setMessage("This only clears the audit/event history. Cached app descriptions "
                        + "remain visible here and are not deleted. The HNSW index is not deleted.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Clear", (dialog, which) ->
                        executor.execute(() -> {
                            DBHelper.clearSemanticActivity(this);
                            runOnUiThread(this::reload);
                        }))
                .show();
    }

    private void showDetails(SemanticActivityRecord record) {
        boolean descriptionDownloaded =
                TYPE_DESCRIPTION_DOWNLOADED.equals(record.eventType);
        boolean descriptionLocal = TYPE_DESCRIPTION_LOCAL.equals(record.eventType);
        boolean descriptionMissing = TYPE_DESCRIPTION_MISSING.equals(record.eventType);

        TextView body = new TextView(this);
        int pad = dp(18);
        body.setPadding(pad, pad, pad, pad);
        body.setTextSize(15f);
        body.setLineSpacing(0f, 1.16f);
        body.setTextIsSelectable(true);
        body.setAutoLinkMask(Linkify.WEB_URLS);
        body.setMovementMethod(LinkMovementMethod.getInstance());

        StringBuilder text = new StringBuilder();
        text.append("App: ").append(emptyDash(record.appName))
                .append("\nPackage: ").append(emptyDash(record.packageName))
                .append("\nSource: ").append(emptyDash(record.source))
                .append("\nDownloaded / recorded: ").append(formatTime(record.eventTime));

        if (descriptionDownloaded) {
            text.append("\n\nDOWNLOADED APP DESCRIPTION\n\n")
                    .append(safe(record.details));
        } else if (descriptionLocal) {
            text.append("\n\nLOCAL FALLBACK DESCRIPTION\n\n")
                    .append(safe(record.details));
        } else if (descriptionMissing) {
            text.append("\n\nDESCRIPTION STATUS\n\n").append(safe(record.details));
        } else {
            text.append("\nEvent: ").append(prettyType(record.eventType))
                    .append("\nSession: ").append(emptyDash(record.sessionId))
                    .append("\n\n").append(safe(record.details));
        }
        body.setText(text.toString());

        new AlertDialog.Builder(this)
                .setTitle(TextUtils.isEmpty(record.appName)
                        ? prettyType(record.eventType) : record.appName)
                .setView(body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private final class ActivityAdapter extends RecyclerView.Adapter<ActivityHolder> {
        @NonNull
        @Override
        public ActivityHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));

            TextView title = new TextView(parent.getContext());
            title.setTextSize(16f);
            row.addView(title);

            TextView secondary = new TextView(parent.getContext());
            secondary.setTextSize(12.5f);
            secondary.setPadding(0, dp(3), 0, 0);
            row.addView(secondary);

            TextView details = new TextView(parent.getContext());
            details.setTextSize(13f);
            details.setMaxLines(5);
            details.setPadding(0, dp(4), 0, dp(2));
            row.addView(details);

            return new ActivityHolder(row, title, secondary, details);
        }

        @Override
        public void onBindViewHolder(@NonNull ActivityHolder holder, int position) {
            SemanticActivityRecord record = visible.get(position);
            String app = TextUtils.isEmpty(record.appName)
                    ? prettyType(record.eventType) : record.appName;

            if (TYPE_DESCRIPTION_DOWNLOADED.equals(record.eventType)) {
                holder.title.setText(app + "  ✓ Downloaded description");
                holder.secondary.setText(emptyDash(record.source)
                        + " · " + formatTime(record.eventTime)
                        + "\n" + emptyDash(record.packageName));
                holder.details.setText(descriptionPreview(record.details));
            } else if (TYPE_DESCRIPTION_LOCAL.equals(record.eventType)) {
                holder.title.setText(app + "  ◇ Local fallback description");
                holder.secondary.setText(emptyDash(record.source)
                        + " · " + formatTime(record.eventTime)
                        + "\n" + emptyDash(record.packageName));
                holder.details.setText(descriptionPreview(record.details));
            } else if (TYPE_DESCRIPTION_MISSING.equals(record.eventType)) {
                holder.title.setText(app + "  ⚠ Description missing");
                holder.secondary.setText(emptyDash(record.packageName)
                        + " · " + formatTime(record.eventTime));
                holder.details.setText(safe(record.details));
            } else {
                holder.title.setText(app + "  ·  " + prettyType(record.eventType));
                String packageText = TextUtils.isEmpty(record.packageName)
                        ? "" : record.packageName + "  ·  ";
                holder.secondary.setText(formatTime(record.eventTime)
                        + "  ·  " + packageText + emptyDash(record.source));
                String detail = safe(record.details);
                holder.details.setText(detail.length() > 420
                        ? detail.substring(0, 420) + "…" : detail);
            }
            holder.itemView.setOnClickListener(v -> showDetails(record));
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }
    }

    private static String descriptionPreview(String details) {
        if (TextUtils.isEmpty(details)) return "";
        int sourceAt = details.indexOf("\n\nSource URL:");
        String description = sourceAt >= 0 ? details.substring(0, sourceAt) : details;
        int installerAt = description.indexOf("\nInstaller package:");
        if (installerAt >= 0) description = description.substring(0, installerAt);
        int errorAt = description.indexOf("\nLast fetch result:");
        if (errorAt >= 0) description = description.substring(0, errorAt);
        return description.length() > 420 ? description.substring(0, 420) + "…" : description;
    }

    private static final class ActivityHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView secondary;
        final TextView details;

        ActivityHolder(@NonNull View itemView,
                       TextView title,
                       TextView secondary,
                       TextView details) {
            super(itemView);
            this.title = title;
            this.secondary = secondary;
            this.details = details;
        }
    }

    private static String prettyType(String type) {
        if (TextUtils.isEmpty(type)) return "Activity";
        return type.replace('_', ' ');
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String emptyDash(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }

    private String formatTime(long time) {
        if (time <= 0L) return "Unknown time";
        return DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(time));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SimpleItemSelectedListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable callback;

        SimpleItemSelectedListener(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent,
                                   View view,
                                   int position,
                                   long id) {
            callback.run();
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    }
}
