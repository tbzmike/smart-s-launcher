package fr.neamar.kiss;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.SemanticHints;

public class NotificationHistoryActivity extends AppCompatActivity {
    private LinearLayout tabs;
    private EditText search;
    private ListView list;
    private final List<NotificationHistoryRecord> records = new ArrayList<>();
    private String selectedPackage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Notification history");
        buildUi();
        rebuildTabs();
        refresh();
    }

    private void buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Notification history");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("All saved notifications · tap a result to open its app");
        subtitle.setTextSize(13);
        root.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search all notifications");
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(tabs, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new ListView(this);
        list.setDividerHeight(1);
        list.setAdapter(new HistoryAdapter());
        list.setOnItemClickListener((parent, view, position, id) -> open(records.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        setContentView(root);
    }

    private void rebuildTabs() {
        tabs.removeAllViews();
        addTab("All", null);
        for (String[] app : SmartStateStore.getNotificationApps(this)) addTab(app[1], app[0]);
    }

    private void addTab(String label, String packageName) {
        Button button = new Button(this);
        button.setText(label == null || label.isEmpty() ? packageName : label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            selectedPackage = packageName;
            search.setHint(packageName == null ? "Search all notifications" : "Search " + button.getText());
            refresh();
        });
        tabs.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void refresh() {
        String query = search == null ? "" : search.getText().toString().trim();
        List<String> terms = null;
        if (!query.isEmpty()) {
            Set<String> smartTerms = new LinkedHashSet<>();
            smartTerms.add(query);
            smartTerms.addAll(SemanticHints.expand(query));
            terms = new ArrayList<>(smartTerms);
        }
        records.clear();
        // Storage is unlimited by count; only the currently rendered window is capped for UI safety.
        records.addAll(SmartStateStore.queryNotifications(this, selectedPackage, terms, 2000));
        if (list != null && list.getAdapter() instanceof BaseAdapter) ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    private void open(NotificationHistoryRecord record) {
        if (NotificationListener.openNotification(this, record.notificationId)) return;
        if (!AppLaunchUtils.launchPackage(this, record.packageName)) {
            Toast.makeText(this, "Unable to open " + record.appName, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return records.size(); }
        @Override public NotificationHistoryRecord getItem(int position) { return records.get(position); }
        @Override public long getItemId(int position) { return records.get(position).dbId; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            NotificationHistoryRecord record = getItem(position);
            LinearLayout row = new LinearLayout(NotificationHistoryActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int pad = dp(10);
            row.setPadding(0, pad, 0, pad);

            ImageView icon = new ImageView(NotificationHistoryActivity.this);
            int size = dp(42);
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(record.packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
                icon.setImageDrawable(info.loadIcon(pm));
            } catch (PackageManager.NameNotFoundException ignored) {}
            row.addView(icon, new LinearLayout.LayoutParams(size, size));

            LinearLayout text = new LinearLayout(NotificationHistoryActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(pad, 0, 0, 0);
            TextView app = new TextView(NotificationHistoryActivity.this);
            app.setText(record.appName);
            app.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            app.setTextSize(15);
            text.addView(app);

            TextView message = new TextView(NotificationHistoryActivity.this);
            String combined = record.title;
            if (record.text != null && !record.text.isEmpty()) combined = combined == null || combined.isEmpty() ? record.text : combined + "\n" + record.text;
            message.setText(combined == null || combined.isEmpty() ? "Notification" : combined);
            message.setMaxLines(4);
            message.setTextSize(14);
            text.addView(message);

            TextView time = new TextView(NotificationHistoryActivity.this);
            time.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(record.postTime));
            time.setTextSize(11);
            text.addView(time);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }
}
