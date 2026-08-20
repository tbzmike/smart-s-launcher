package fr.neamar.kiss.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SectionIndexer;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fr.neamar.kiss.normalizer.StringNormalizer;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.QueryInterface;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.LockedNotificationHistoryDialog;
import fr.neamar.kiss.ui.TileVisualStyle;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.fuzzy.FuzzyFactory;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class RecordAdapter extends BaseAdapter implements SectionIndexer {
    private final QueryInterface parent;
    private FuzzyScore fuzzyScore;
    private final List<Result<?>> results;
    private final HashMap<String, Integer> alphaIndexer = new HashMap<>();
    private String[] sections = new String[0];
    private static final String TAG = RecordAdapter.class.getSimpleName();

    public RecordAdapter(QueryInterface parent, List<Result<?>> results) {
        this.parent = parent;
        this.results = results;
        this.fuzzyScore = null;
    }

    @Override
    public int getViewTypeCount() { return 7; }

    @Override
    public int getItemViewType(int position) { return Result.getItemViewType(getItem(position)); }

    @Override
    public boolean hasStableIds() { return true; }

    @Override
    public int getCount() { return results.size(); }

    @Override
    public Result<?> getItem(int position) { return results.get(position); }

    @Override
    public long getItemId(int position) {
        return position < results.size() ? getItem(position).getUniqueId() : -1;
    }

    @Override
    @NonNull
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        Result<?> result = getItem(position);
        View view = result.display(parent.getContext(), convertView, parent, fuzzyScore);

        // Every result renderer ultimately comes through this adapter. Configure labels here so
        // apps, settings, shortcuts, contacts and feature results all expose their complete text
        // when the available width is too small. Custom U/horizontal cards keep their own
        // marquee labels too, so this also gives their source views a safe full-text fallback.
        configureOverflowText(view);

        if (parent instanceof AbsListView) {
            // Only style the native vertical list here. Custom Square-U/horizontal renderers
            // build their own icon/theme cards; styling them twice caused duplicate icon work
            // and made first paint noticeably slower.
            TileVisualStyle.apply(view, result, parent.getContext());
            applyVerticalHistorySizing(view, parent.getContext());
        }
        return view;
    }

    private void configureOverflowText(View view) {
        if (view instanceof TextView && !(view instanceof Button)) {
            TextView text = (TextView) view;
            if (!TextUtils.isEmpty(text.getText())) {
                text.setSingleLine(true);
                text.setMaxLines(1);
                text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                text.setMarqueeRepeatLimit(-1);
                text.setHorizontallyScrolling(true);
                text.setHorizontalFadingEdgeEnabled(true);
                text.setSelected(true);
                text.setFocusable(false);
                text.setFocusableInTouchMode(false);
                makeTextUseAvailableWidth(text);
            }
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            configureOverflowText(group.getChildAt(i));
        }
    }

    private void makeTextUseAvailableWidth(TextView text) {
        if (!(text.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) text.getParent();
        ViewGroup.LayoutParams raw = text.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;

        if (parent.getOrientation() == LinearLayout.VERTICAL) {
            if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                text.setLayoutParams(lp);
            }
            return;
        }

        // In rows such as "Setting: Wi-Fi preferences", keep the prefix at its natural width and
        // give the final label the remaining width. A width constraint is required for Android's
        // marquee to start; wrap_content alone simply measures the full string off-screen.
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT && isLastTextLabel(parent, text)) {
            lp.width = 0;
            lp.weight = Math.max(1f, lp.weight);
            text.setLayoutParams(lp);
        }
    }

    private boolean isLastTextLabel(LinearLayout parent, TextView current) {
        boolean foundCurrent = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == current) {
                foundCurrent = true;
                continue;
            }
            if (foundCurrent && child instanceof TextView && !(child instanceof Button)
                    && child.getVisibility() != View.GONE) {
                return false;
            }
        }
        return true;
    }

    private void applyVerticalHistorySizing(View row, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int rowPercent = safePercent(prefs, "smart-list-row-size-percent", 100, 70, 160);
        int iconPercent = safePercent(prefs, "smart-list-icon-size-percent", 100, 60, 170);
        row.setMinimumHeight(dp(context, 64) * rowPercent / 100);
        resizeSignificantImages(row, context, iconPercent);
    }

    private void resizeSignificantImages(View view, Context context, int percent) {
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            ViewGroup.LayoutParams lp = image.getLayoutParams();
            if (lp != null && lp.width > 0 && lp.height > 0) {
                int threshold = dp(context, 28);
                int currentMax = Math.max(lp.width, lp.height);
                if (currentMax >= threshold) {
                    // Scale width and height by the same factor. The previous code forced every
                    // significant image into a square, which distorted non-square feature and
                    // settings icons. Keeping the original ratio prevents stretching.
                    float factor = percent / 100f;
                    int targetWidth = Math.max(1, Math.round(lp.width * factor));
                    int targetHeight = Math.max(1, Math.round(lp.height * factor));
                    int targetMax = Math.max(targetWidth, targetHeight);
                    int minimumMax = dp(context, 24);
                    if (targetMax < minimumMax) {
                        float minimumFactor = minimumMax / (float) targetMax;
                        targetWidth = Math.max(1, Math.round(targetWidth * minimumFactor));
                        targetHeight = Math.max(1, Math.round(targetHeight * minimumFactor));
                    }
                    if (lp.width != targetWidth || lp.height != targetHeight) {
                        lp.width = targetWidth;
                        lp.height = targetHeight;
                        image.setLayoutParams(lp);
                    }
                    // FIT_XY is the only standard scale type that can visibly stretch the
                    // drawable. Replace it without disturbing CENTER_CROP profile photos.
                    if (image.getScaleType() == ImageView.ScaleType.FIT_XY) {
                        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    }
                }
            }
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            resizeSignificantImages(group.getChildAt(i), context, percent);
        }
    }

    private int safePercent(SharedPreferences prefs, String key, int fallback, int min, int max) {
        Object raw = prefs.getAll().get(key);
        int value = fallback;
        if (raw instanceof Number) value = Math.round(((Number) raw).floatValue());
        else if (raw instanceof String) {
            try { value = Math.round(Float.parseFloat((String) raw)); }
            catch (NumberFormatException ignored) { value = fallback; }
        }
        return Math.max(min, Math.min(max, value));
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public void onLongClick(final int pos, View v) {
        if (pos < 0 || pos >= getCount()) return;
        Result<?> result = getItem(pos);
        Context context = v.getContext();

        if (UiEditLock.isLocked(context)) {
            String packageName = getNotificationPackageName(result.getPojo());
            if (packageName != null) {
                LockedNotificationHistoryDialog.showLatest(context, packageName);
            }
            return;
        }

        ListPopup menu = result.getPopupMenu(context, this, v);
        if (menu.getAdapter().getCount() > 0) {
            parent.registerPopup(menu);
            menu.show(v);
        }
    }

    private String getNotificationPackageName(Pojo pojo) {
        if (pojo instanceof AppPojo) return ((AppPojo) pojo).packageName;
        if (pojo instanceof ShortcutPojo) return ((ShortcutPojo) pojo).packageName;
        return null;
    }

    public void onClick(final int position, View v) {
        try {
            final Result<?> result = getItem(position);
            result.launch(v.getContext(), v, parent);
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "Unable to click", e);
        }
    }

    public void removeResult(Result<?> result) {
        parent.beforeListChange();
        results.remove(result);
        notifyDataSetChanged();
        parent.temporarilyDisableTranscriptMode();
        parent.afterListChange();
    }

    public void updateWithPojos(@NonNull Context context, @NonNull List<Pojo> pojos,
                                boolean isRefresh, String query) {
        // Search runs on every query change. Avoid streams/Collectors here to reduce temporary
        // allocations and GC pressure while typing rapidly.
        Map<Pojo, Result<?>> existingResults = new HashMap<>(Math.max(16, results.size() * 2));
        for (Result<?> result : results) {
            existingResults.put(result.getPojo(), result);
        }

        List<Result<?>> updatedResults = new ArrayList<>(pojos.size());
        for (Pojo pojo : pojos) {
            if (pojo == null) continue;
            Result<?> existing = existingResults.get(pojo);
            updatedResults.add(existing != null ? existing : Result.fromPojo(parent, pojo));
        }
        updateResults(context, updatedResults, isRefresh, query);
    }

    public void updateResults(@NonNull Context context, List<Result<?>> updatedResults,
                              boolean isRefresh, String query) {
        parent.beforeListChange();
        this.results.clear();
        this.results.addAll(updatedResults);
        StringNormalizer.Result queryNormalized = StringNormalizer.normalizeWithResult(query, false);
        fuzzyScore = FuzzyFactory.createFuzzyScore(context, queryNormalized.codePoints, true);
        notifyDataSetChanged();
        if (isRefresh) parent.temporarilyDisableTranscriptMode();
        parent.afterListChange();
    }

    public void updateTranscriptMode(int transcriptMode) { parent.updateTranscriptMode(transcriptMode); }

    public void clear() {
        parent.beforeListChange();
        this.results.clear();
        notifyDataSetChanged();
        parent.afterListChange();
    }

    public void buildSections() {
        alphaIndexer.clear();
        int size = results.size();
        for (int i = 0; i < size; i++) {
            String s = getItem(i).getSection();
            if (!alphaIndexer.containsKey(s)) alphaIndexer.put(s, i);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(alphaIndexer.entrySet());
        Collections.sort(entries, Map.Entry.comparingByValue());
        sections = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) sections[i] = entries.get(i).getKey();
    }

    @Override
    public Object[] getSections() { return sections; }

    @Override
    public int getPositionForSection(int sectionIndex) {
        if (sections.length == 0) return 0;
        sectionIndex = Math.max(0, Math.min(sections.length - 1, sectionIndex));
        return alphaIndexer.getOrDefault(sections[sectionIndex], 0);
    }

    @Override
    public int getSectionForPosition(int position) {
        for (int i = 0; i < sections.length; i++) {
            if (alphaIndexer.get(sections[i]) > position) return i - 1;
        }
        return Math.max(sections.length - 2, 0);
    }

    public void showDialog(DialogFragment dialog) { parent.showDialog(dialog); }
}
