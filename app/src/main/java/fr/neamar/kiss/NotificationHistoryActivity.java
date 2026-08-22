package fr.neamar.kiss;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.ui.SmartAnimationEngine;
import fr.neamar.kiss.utils.AppLaunchUtils;
import fr.neamar.kiss.utils.SemanticHints;

public class NotificationHistoryActivity extends AppCompatActivity {
    private LinearLayout tabs;
    private EditText search;
    private ListView list;
    private View rootView;
    private final List<NotificationHistoryRecord> records = new ArrayList<>();
    private String selectedPackage;
    private boolean selectedPermanent;
    private int lastFirstVisible = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Notification history");
        configureWallpaperBackground();
        buildUi();
        rebuildTabs();
        refresh();
        if (rootView != null) {
            rootView.post(() -> SmartAnimationEngine.animateWindowSwitch(null, rootView));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tabs != null) rebuildTabs();
        if (list != null) refresh();
    }

    private void configureWallpaperBackground() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            attributes.setBlurBehindRadius(dp(28));
            getWindow().setAttributes(attributes);
        }
    }

    private void buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        rootView = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.argb(158, 0, 0, 0));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(pad, pad + bars.top, pad, pad + bars.bottom);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("Notification history");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Saved notifications · permanent notifications are separated · tap an app icon to open the app · tap a message to open its notification");
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
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setAdapter(new HistoryAdapter());
        list.setOnItemClickListener((parent, view, position, id) -> openNotificationTarget(records.get(position)));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) { }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                                 int totalItemCount) {
                if (visibleItemCount <= 0) return;
                if (lastFirstVisible < 0) {
                    lastFirstVisible = firstVisibleItem;
                    return;
                }
                if (firstVisibleItem == lastFirstVisible) return;

                boolean movingDown = firstVisibleItem > lastFirstVisible;
                int childIndex = movingDown ? visibleItemCount - 1 : 0;
                View child = view.getChildAt(childIndex);
                if (child != null) {
                    int animationIndex = movingDown ? childIndex : 0;
                    SmartAnimationEngine.animateTileListItem(child, animationIndex);
                }
                lastFirstVisible = firstVisibleItem;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private void rebuildTabs() {
        tabs.removeAllViews();
        addTab("All", null, false);
        addTab("Permanent", null, true);
        for (String[] app : SmartStateStore.getNotificationApps(this)) addTab(app[1], app[0], false);
    }

    private void addTab(String label, String packageName, boolean permanent) {
        Button button = new Button(this);
        button.setText(label == null || label.isEmpty() ? packageName : label);
        button.setAllCaps(false);
        boolean selected = selectedPermanent == permanent
                && ((selectedPackage == null && packageName == null)
                || (selectedPackage != null && selectedPackage.equals(packageName)));
        button.setAlpha(selected ? 1f : 0.78f);
        button.setOnClickListener(v -> {
            selectedPackage = packageName;
            selectedPermanent = permanent;
            if (permanent) search.setHint("Search permanent notifications");
            else search.setHint(packageName == null ? "Search all notifications" : "Search " + button.getText());
            rebuildTabs();
            refresh();
        });
        tabs.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void refresh() {
        String query = getSearchQuery();
        List<String> terms = null;
        if (!query.isEmpty()) {
            Set<String> smartTerms = new LinkedHashSet<>();
            smartTerms.add(query);
            smartTerms.addAll(SemanticHints.expand(query));
            terms = new ArrayList<>(smartTerms);
        }
        records.clear();
        records.addAll(SmartStateStore.queryNotifications(this, selectedPackage, terms, selectedPermanent, 2000));
        if (list != null && list.getAdapter() instanceof BaseAdapter) {
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
            lastFirstVisible = -1;
            list.post(this::animateVisibleRows);
        }
    }

    private void animateVisibleRows() {
        if (list == null || !SmartAnimationEngine.isEnabled(this)) return;
        int count = list.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = list.getChildAt(i);
            if (child != null) SmartAnimationEngine.animateTileListItem(child, i);
        }
    }

    private String getSearchQuery() {
        return search == null ? "" : search.getText().toString().trim();
    }

    private void openApp(NotificationHistoryRecord record) {
        if (!AppLaunchUtils.launchPackage(this, record.packageName)) {
            Toast.makeText(this, "Unable to open " + record.appName, Toast.LENGTH_SHORT).show();
        }
    }

    private void openNotificationTarget(NotificationHistoryRecord record) {
        if (!AppLaunchUtils.ensurePackageEnabled(this, record.packageName)) {
            Toast.makeText(this, "Unable to enable " + record.appName, Toast.LENGTH_SHORT).show();
            return;
        }
        if (NotificationListener.openNotification(this, record.notificationId)) return;
        if (!AppLaunchUtils.launchPackage(this, record.packageName)) {
            Toast.makeText(this, "Unable to open " + record.appName, Toast.LENGTH_SHORT).show();
        }
    }

    private CharSequence highlightLiteral(String value, String query) {
        String safeValue = value == null ? "" : value;
        if (query == null || query.isEmpty()) return safeValue;

        SpannableString highlighted = new SpannableString(safeValue);
        String haystack = safeValue.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < haystack.length()) {
            int start = haystack.indexOf(needle, from);
            if (start < 0) break;
            int end = start + needle.length();
            highlighted.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            highlighted.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = end;
        }
        return highlighted;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class HistoryAdapter extends BaseAdapter {
        @Override public int getCount() { return records.size(); }
        @Override public NotificationHistoryRecord getItem(int position) { return records.get(position); }
        @Override public long getItemId(int position) { return records.get(position).dbId; }

        @Override
        public android.view.View getView(int position, android.view.View convertView, ViewGroup parent) {
            NotificationHistoryRecord record = getItem(position);
            String query = getSearchQuery();
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
                if (!AppLaunchUtils.isPackageEnabled(NotificationHistoryActivity.this, record.packageName)
                        && icon.getDrawable() != null) icon.getDrawable().setAlpha(140);
            } catch (PackageManager.NameNotFoundException ignored) {}
            icon.setClickable(true);
            icon.setOnClickListener(v -> openApp(record));
            row.addView(icon, new LinearLayout.LayoutParams(size, size));

            LinearLayout text = new LinearLayout(NotificationHistoryActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(pad, 0, 0, 0);
            text.setClickable(true);
            text.setOnClickListener(v -> openNotificationTarget(record));

            TextView app = new TextView(NotificationHistoryActivity.this);
            app.setText(highlightLiteral(record.appName, query));
            app.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            app.setTextSize(15);
            text.addView(app);

            TextView message = new TextView(NotificationHistoryActivity.this);
            String combined = record.title;
            if (record.text != null && !record.text.isEmpty()) combined = combined == null || combined.isEmpty() ? record.text : combined + "\n" + record.text;
            if (combined == null || combined.isEmpty()) combined = "Notification";
            message.setText(highlightLiteral(combined, query));
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
