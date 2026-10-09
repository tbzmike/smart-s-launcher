package fr.neamar.kiss;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
 * Local audit trail for app metadata fetching and semantic/HNSW indexing.
 *
 * <p>This screen deliberately reads the persistent event log on a worker thread so transparency
 * never blocks launcher rendering or search.</p>
 */
public final class DataActivityViewerActivity extends AppCompatActivity {
    private static final int LOAD_LIMIT = 5000;

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
        explanation.setText("Transparent local audit of semantic indexing and app-description downloads. "
                + "Tap any row for the complete event details.");
        explanation.setTextSize(13.5f);
        explanation.setPadding(0, 0, 0, dp(8));
        root.addView(explanation);

        status = new TextView(this);
        status.setTextSize(13f);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        filter = new Spinner(this);
        String[] choices = {
                "All activity",
                "HNSW builds",
                "Indexed apps & records",
                "Metadata activity",
                "Current metadata cache",
                "Errors / missing data"
        };
        filter.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, choices));
        filter.setOnItemSelectedListener(new SimpleItemSelectedListener(this::applyFilter));
        controls.addView(filter, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> reload());
        controls.addView(refresh);

        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> confirmClear());
        controls.addView(clear);
        root.addView(controls);

        search = new EditText(this);
        search.setHint("Filter app, package, source or event");
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

    private void reload() {
        status.setText("Loading activity…");
        executor.execute(() -> {
            List<SemanticActivityRecord> loaded = DBHelper.getSemanticActivity(this, LOAD_LIMIT);
            Map<String, AppSourceMetadataRecord> metadata =
                    DBHelper.getAppSourceMetadata(this);
            int metadataCount = 0;
            for (AppSourceMetadataRecord record : metadata.values()) {
                if (record == null || TextUtils.isEmpty(record.description)) continue;
                metadataCount++;
                String details = "Current cached description (" + record.description.length()
                        + " characters): " + record.description;
                if (!TextUtils.isEmpty(record.sourceUrl)) {
                    details += "\nSource URL: " + record.sourceUrl;
                }
                if (!TextUtils.isEmpty(record.installerPackage)) {
                    details += "\nInstaller package: " + record.installerPackage;
                }
                loaded.add(new SemanticActivityRecord(
                        record.fetchedAt,
                        "METADATA_CACHE_SNAPSHOT",
                        "cache",
                        record.packageName,
                        record.title,
                        record.source,
                        details));
            }
            loaded.sort((left, right) -> Long.compare(right.eventTime, left.eventTime));
            String hnsw = SemanticHnswIndex.getInstance().statusSummary();
            runOnUiThread(() -> {
                all.clear();
                all.addAll(loaded);
                status.setText("Stored app descriptions: " + metadataCount
                        + " · Activity records: " + loaded.size()
                        + "\nMetadata updater: "
                        + (AppSourceMetadataUpdater.isRunning() ? "running" : "idle")
                        + " · HNSW: " + hnsw);
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
        String type = record.eventType == null ? "" : record.eventType;
        switch (mode) {
            case 1:
                return type.startsWith("HNSW_BUILD");
            case 2:
                return type.startsWith("HNSW_") && type.endsWith("_INDEXED");
            case 3:
                return type.startsWith("METADATA_")
                        && !"METADATA_CACHE_SNAPSHOT".equals(type);
            case 4:
                return "METADATA_CACHE_SNAPSHOT".equals(type);
            case 5:
                return type.contains("FAILED")
                        || type.contains("MISSING")
                        || type.contains("ERROR");
            default:
                return true;
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
                .setTitle("Clear Data Activity history?")
                .setMessage("This only clears the transparency/audit log. "
                        + "Downloaded app descriptions and the HNSW index are not deleted.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Clear", (dialog, which) ->
                        executor.execute(() -> {
                            DBHelper.clearSemanticActivity(this);
                            runOnUiThread(this::reload);
                        }))
                .show();
    }

    private void showDetails(SemanticActivityRecord record) {
        TextView body = new TextView(this);
        int pad = dp(18);
        body.setPadding(pad, pad, pad, pad);
        body.setTextSize(14f);
        body.setTextIsSelectable(true);
        body.setText("Time: " + formatTime(record.eventTime)
                + "\nEvent: " + safe(record.eventType)
                + "\nSession: " + safe(record.sessionId)
                + "\nApp: " + emptyDash(record.appName)
                + "\nPackage: " + emptyDash(record.packageName)
                + "\nSource: " + emptyDash(record.source)
                + "\n\n" + safe(record.details));

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
            row.setPadding(dp(10), dp(9), dp(10), dp(9));

            TextView title = new TextView(parent.getContext());
            title.setTextSize(15f);
            row.addView(title);

            TextView secondary = new TextView(parent.getContext());
            secondary.setTextSize(12.5f);
            secondary.setPadding(0, dp(2), 0, 0);
            row.addView(secondary);

            TextView details = new TextView(parent.getContext());
            details.setTextSize(12f);
            details.setMaxLines(4);
            details.setPadding(0, dp(3), 0, 0);
            row.addView(details);

            return new ActivityHolder(row, title, secondary, details);
        }

        @Override
        public void onBindViewHolder(@NonNull ActivityHolder holder, int position) {
            SemanticActivityRecord record = visible.get(position);
            String app = TextUtils.isEmpty(record.appName)
                    ? prettyType(record.eventType) : record.appName;
            holder.title.setText(app + "  ·  " + prettyType(record.eventType));

            String packageText = TextUtils.isEmpty(record.packageName)
                    ? "" : record.packageName + "  ·  ";
            holder.secondary.setText(formatTime(record.eventTime)
                    + "  ·  " + packageText + emptyDash(record.source));

            String detail = safe(record.details);
            holder.details.setText(detail.length() > 420
                    ? detail.substring(0, 420) + "…" : detail);
            holder.itemView.setOnClickListener(v -> showDetails(record));
        }

        @Override
        public int getItemCount() {
            return visible.size();
        }
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
