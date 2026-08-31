package fr.neamar.kiss.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.SectionIndexer;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import fr.neamar.kiss.normalizer.StringNormalizer;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.QueryInterface;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.fuzzy.FuzzyFactory;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class RecordAdapter extends BaseAdapter implements SectionIndexer {
    private final QueryInterface parent;
    private FuzzyScore fuzzyScore;
    private final List<Result<?>> results;
    private final HashMap<String, Integer> alphaIndexer = new HashMap<>();
    private final WeakHashMap<ImageView, int[]> originalIconSizes = new WeakHashMap<>();
    private String[] sections = new String[0];
    private static final String TAG = RecordAdapter.class.getSimpleName();

    private boolean sizingConfigLoaded;
    private int rowSizePercent = 100;
    private int iconSizePercent = 100;
    private int rowBaseHeightPx;
    private int iconThresholdPx;
    private int iconMinimumPx;

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
        View view = getItem(position).display(parent.getContext(), convertView, parent, fuzzyScore);
        if (parent instanceof AbsListView) applyVerticalHistorySizing(view, parent.getContext());
        return view;
    }

    private void applyVerticalHistorySizing(View row, Context context) {
        ensureSizingConfig(context);
        int minimumHeight = rowBaseHeightPx * rowSizePercent / 100;
        if (row.getMinimumHeight() != minimumHeight) {
            row.setMinimumHeight(minimumHeight);
        }
        resizeSignificantImages(row, iconSizePercent);
    }

    private void ensureSizingConfig(Context context) {
        if (sizingConfigLoaded) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Map<String, ?> values = prefs.getAll();
        rowSizePercent = safePercent(values.get("smart-list-row-size-percent"), 100, 70, 160);
        iconSizePercent = safePercent(values.get("smart-list-icon-size-percent"), 100, 60, 170);
        rowBaseHeightPx = dp(context, 64);
        iconThresholdPx = dp(context, 28);
        iconMinimumPx = dp(context, 24);
        sizingConfigLoaded = true;
    }

    private void resizeSignificantImages(View view, int percent) {
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            ViewGroup.LayoutParams lp = image.getLayoutParams();
            if (lp != null) {
                int[] baseSize = originalIconSizes.get(image);
                if (baseSize == null) {
                    baseSize = new int[] {lp.width, lp.height};
                    originalIconSizes.put(image, baseSize);
                }

                int baseMax = Math.max(baseSize[0], baseSize[1]);
                if (baseMax >= iconThresholdPx) {
                    int targetWidth = scaledDimension(baseSize[0], percent);
                    int targetHeight = scaledDimension(baseSize[1], percent);
                    if (lp.width != targetWidth || lp.height != targetHeight) {
                        lp.width = targetWidth;
                        lp.height = targetHeight;
                        image.setLayoutParams(lp);
                    }
                }
            }
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            resizeSignificantImages(group.getChildAt(i), percent);
        }
    }

    private int scaledDimension(int base, int percent) {
        if (base <= 0) return base;
        return Math.max(iconMinimumPx, base * percent / 100);
    }

    private int safePercent(Object raw, int fallback, int min, int max) {
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
        ListPopup menu = getItem(pos).getPopupMenu(v.getContext(), this, v);
        if (menu.getAdapter().getCount() > 0) {
            parent.registerPopup(menu);
            menu.show(v);
        }
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
        Map<Pojo, Result<?>> existingResults = this.results.stream()
                .collect(Collectors.toMap(Result::getPojo, Function.identity()));
        List<Result<?>> updatedResults = pojos.stream()
                .filter(Objects::nonNull)
                .map(pojo -> existingResults.getOrDefault(pojo, Result.fromPojo(parent, pojo)))
                .collect(Collectors.toList());
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
