package fr.neamar.kiss.adapter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
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
import java.util.WeakHashMap;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.R;
import fr.neamar.kiss.normalizer.StringNormalizer;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.pojo.AppPojo;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.DisabledAppPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.preference.UiEditLock;
import fr.neamar.kiss.result.CommunicationResult;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.searcher.QueryInterface;
import fr.neamar.kiss.searcher.SearchHandler;
import fr.neamar.kiss.searcher.Searcher;
import fr.neamar.kiss.ui.LaunchMorphTransition;
import fr.neamar.kiss.ui.ListPopup;
import fr.neamar.kiss.ui.TileVisualStyle;
import fr.neamar.kiss.utils.Log;
import fr.neamar.kiss.utils.NotificationHistoryResolver;
import fr.neamar.kiss.utils.RecentLaunchTracker;
import fr.neamar.kiss.utils.SocialMessagePresentation;
import fr.neamar.kiss.utils.fuzzy.FuzzyFactory;
import fr.neamar.kiss.utils.fuzzy.FuzzyScore;

public class RecordAdapter extends BaseAdapter implements SectionIndexer {
    private final QueryInterface parent;
    private FuzzyScore fuzzyScore;
    private final List<Result<?>> results;
    private final HashMap<String, Integer> alphaIndexer = new HashMap<>();
    private final HashMap<String, String> notificationPreviewCache = new HashMap<>();
    private final WeakHashMap<View, int[]> baseRowPadding = new WeakHashMap<>();
    private String[] sections = new String[0];
    private String lastRenderedQuery = null;
    private static final String TAG = RecordAdapter.class.getSimpleName();

    public RecordAdapter(QueryInterface parent, List<Result<?>> results) { this.parent = parent; this.results = results; this.fuzzyScore = null; }
    @Override public int getViewTypeCount() { return 8; }
    @Override public int getItemViewType(int position) { Result<?> result = getItem(position); return result instanceof CommunicationResult ? 7 : Result.getItemViewType(result); }
    @Override public boolean hasStableIds() { return true; }
    @Override public int getCount() { return results.size(); }
    @Override public Result<?> getItem(int position) { return results.get(position); }
    @Override public long getItemId(int position) { return position < results.size() ? getItem(position).getUniqueId() : -1; }

    @Override @NonNull
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        Result<?> result = getItem(position);
        View view = result.display(parent.getContext(), convertView, parent, fuzzyScore);
        if (result.getPojo() instanceof NotificationPojo) {
            configureSocialMessageCard(view, (NotificationPojo) result.getPojo());
            applyBestNotificationPreview(view, (NotificationPojo) result.getPojo());
        }
        configureOverflowText(view);
        if (result.getPojo() instanceof NotificationPojo) configureNotificationTileClick(view, result);
        if (parent instanceof AbsListView) {
            TileVisualStyle.apply(view, result, parent.getContext());
            applyVerticalHistorySizing(view, parent.getContext());
            applyVerticalHistoryPolish(view, parent.getContext());
        }
        return view;
    }

    private void configureSocialMessageCard(View view, NotificationPojo notification) {
        SocialMessagePresentation presentation = SocialMessagePresentation.resolve(view.getContext(), notification);
        if (!presentation.message) return;

        TextView app = view.findViewById(R.id.item_notification_app);
        TextView title = view.findViewById(R.id.item_notification_title);
        TextView text = view.findViewById(R.id.item_notification_text);
        View nativeContainer = view.findViewById(R.id.item_notification_native_container);

        if (app != null) app.setText(presentation.headline);
        if (title != null) {
            title.setText(presentation.preview);
            title.setVisibility(TextUtils.isEmpty(presentation.preview) ? View.GONE : View.VISIBLE);
            configureMarquee(title);
        }
        if (text != null) text.setVisibility(View.GONE);
        if (nativeContainer != null) nativeContainer.setVisibility(View.GONE);
    }

    /**
     * Some apps expose useful text only through the live notification extras while their compact
     * cached title/body are empty. Resolve that richer text lazily for visible rows only and cache it
     * per notification id; this avoids scanning Android's active-notification array for every search
     * candidate while still replacing generic "1 notification" labels when real text exists.
     */
    private void applyBestNotificationPreview(View view, NotificationPojo notification) {
        TextView title = view.findViewById(R.id.item_notification_title);
        if (title == null) return;

        CharSequence current = title.getText();
        String currentText = current == null ? "" : current.toString().trim();
        if (!currentText.isEmpty() && !isGenericNotificationCount(currentText)) return;

        String preview = notification.getPreview();
        if (TextUtils.isEmpty(preview)
                && notification.id.startsWith(NotificationListener.NOTIFICATION_SCHEME)
                && NotificationListener.isNotificationActive(view.getContext(), notification.id)) {
            if (notificationPreviewCache.containsKey(notification.id)) {
                preview = notificationPreviewCache.get(notification.id);
            } else {
                String expanded = NotificationListener.getExpandedNotificationText(
                        view.getContext(), notification.id);
                preview = expanded == null ? "" : expanded.trim().replace('\n', ' ');
                notificationPreviewCache.put(notification.id, preview);
            }
        }

        if (!TextUtils.isEmpty(preview)) {
            title.setText(preview);
            title.setVisibility(View.VISIBLE);
            configureMarquee(title);
        }
    }

    private boolean isGenericNotificationCount(String text) {
        String value = text == null ? "" : text.trim().toLowerCase(java.util.Locale.ROOT);
        return value.matches("\\d+ notifications?");
    }

    private void configureNotificationTileClick(View view, Result<?> result) {
        View.OnClickListener openHistory = v -> {
            RecentLaunchTracker.remember(result.getPojo());
            if (NotificationHistoryResolver.showForPojo(v.getContext(), result.getPojo())) {
                recordExplicitSelection(v.getContext(), result.getPojo());
                return;
            }
            result.launch(v.getContext(), v, parent);
        };
        view.setOnClickListener(openHistory);
        int[] ids = new int[]{R.id.item_notification_native_container, R.id.item_notification_app,
                R.id.item_notification_title, R.id.item_notification_text};
        for (int id : ids) {
            View child = view.findViewById(id);
            if (child != null) child.setOnClickListener(openHistory);
        }
    }

    private void recordExplicitSelection(Context context, Pojo pojo) {
        if (context == null || pojo == null) return;
        KissApplication.getApplication(context).getDataHandler().addToHistory(pojo.getHistoryId());
    }

    private void configureOverflowText(View view) {
        if (view instanceof TextView && !(view instanceof Button)) {
            TextView text = (TextView) view;
            if (text.getId() == R.id.item_communication_body) {
                text.setSingleLine(false);
                text.setMaxLines(Integer.MAX_VALUE);
                text.setHorizontallyScrolling(false);
                text.setEllipsize(null);
                text.setHorizontalFadingEdgeEnabled(false);
                return;
            }
            if (!TextUtils.isEmpty(text.getText())) configureMarquee(text);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) configureOverflowText(group.getChildAt(i));
    }

    private void configureMarquee(TextView text) {
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

    private void makeTextUseAvailableWidth(TextView text) {
        if (!(text.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) text.getParent();
        ViewGroup.LayoutParams raw = text.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
        if (parent.getOrientation() == LinearLayout.VERTICAL) {
            if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) { lp.width = ViewGroup.LayoutParams.MATCH_PARENT; text.setLayoutParams(lp); }
            return;
        }
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT && isLastTextLabel(parent, text)) { lp.width = 0; lp.weight = Math.max(1f, lp.weight); text.setLayoutParams(lp); }
    }

    private boolean isLastTextLabel(LinearLayout parent, TextView current) {
        boolean foundCurrent = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == current) { foundCurrent = true; continue; }
            if (foundCurrent && child instanceof TextView && !(child instanceof Button) && child.getVisibility() != View.GONE) return false;
        }
        return true;
    }

    private void applyVerticalHistorySizing(View row, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int rowPercent = safePercent(prefs, "smart-list-row-size-percent", 100, 70, 160);
        int iconPercent = safePercent(prefs, "smart-list-icon-size-percent", 100, 60, 170);
        row.setMinimumHeight(dp(context, 64) * rowPercent / 100);
        applyPrimaryIconScale(row, iconPercent);
    }

    /** Apply the typography and spacing controls only to the actual Vertical List history mode. */
    private void applyVerticalHistoryPolish(View row, Context context) {
        if (!isVerticalHistory(context)) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        int labelSp = safePercent(prefs, "smart-list-label-size-sp", 18, 12, 28);
        int bodySp = safePercent(prefs, "smart-list-body-size-sp", 14, 10, 22);
        Typeface labelTypeface = typefaceFor(prefs.getString("smart-list-label-font", "sans_bold"));
        Typeface bodyTypeface = typefaceFor(prefs.getString("smart-list-body-font", "sans_normal"));

        int[] labelIds = new int[]{
                R.id.item_app_name, R.id.item_contact_name, R.id.item_setting_name,
                R.id.item_notification_app, R.id.item_communication_title, R.id.item_phone_text
        };
        int[] bodyIds = new int[]{
                R.id.item_app_tag, R.id.item_shortcut_tag, R.id.item_contact_phone,
                R.id.item_contact_nickname, R.id.item_notification_title, R.id.item_notification_text,
                R.id.item_communication_meta, R.id.item_communication_body
        };
        applyTextStyle(row, labelIds, labelSp, labelTypeface);
        applyTextStyle(row, bodyIds, bodySp, bodyTypeface);

        int spacing = safePercent(prefs, "smart-list-row-spacing-dp", 4, 0, 24);
        int[] base = baseRowPadding.get(row);
        if (base == null) {
            base = new int[]{row.getPaddingLeft(), row.getPaddingTop(), row.getPaddingRight(), row.getPaddingBottom()};
            baseRowPadding.put(row, base);
        }
        int half = dp(context, spacing) / 2;
        row.setPadding(base[0], base[1] + half, base[2], base[3] + half);
    }

    private boolean isVerticalHistory(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return "vertical".equals(prefs.getString("smart-history-layout", "vertical"))
                && SearchHandler.getInstance().getLastSearchType() == Searcher.Type.HISTORY;
    }

    private void applyTextStyle(View row, int[] ids, int sizeSp, Typeface typeface) {
        for (int id : ids) {
            View candidate = row.findViewById(id);
            if (!(candidate instanceof TextView) || candidate.getVisibility() == View.GONE) continue;
            TextView text = (TextView) candidate;
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            text.setTypeface(typeface);
        }
    }

    private Typeface typefaceFor(String value) {
        if (value == null) value = "sans_normal";
        String family = "sans-serif";
        int style = Typeface.NORMAL;
        switch (value) {
            case "sans_bold": style = Typeface.BOLD; break;
            case "sans_italic": style = Typeface.ITALIC; break;
            case "sans_bold_italic": style = Typeface.BOLD_ITALIC; break;
            case "condensed_normal": family = "sans-serif-condensed"; break;
            case "condensed_bold": family = "sans-serif-condensed"; style = Typeface.BOLD; break;
            case "serif_normal": family = "serif"; break;
            case "serif_bold": family = "serif"; style = Typeface.BOLD; break;
            case "monospace_normal": family = "monospace"; break;
            case "monospace_bold": family = "monospace"; style = Typeface.BOLD; break;
            case "sans_normal":
            default: break;
        }
        return Typeface.create(family, style);
    }

    private void applyPrimaryIconScale(View row, int percent) {
        ImageView icon = findPrimaryIcon(row); if (icon == null) return;
        float scale = percent / 100f; icon.setScaleX(scale); icon.setScaleY(scale);
        if (icon.getId() == R.id.item_setting_icon) icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        else if (icon.getScaleType() == ImageView.ScaleType.FIT_XY) icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    private ImageView findPrimaryIcon(View row) {
        int[] ids = new int[]{R.id.item_setting_icon, R.id.item_shortcut_icon, R.id.item_contact_icon, R.id.item_app_icon, R.id.item_phone_icon, R.id.item_search_icon, R.id.item_notification_icon};
        for (int id : ids) { View candidate = row.findViewById(id); if (candidate instanceof ImageView && candidate.getVisibility() != View.GONE) return (ImageView) candidate; }
        return null;
    }

    private int safePercent(SharedPreferences prefs, String key, int fallback, int min, int max) {
        Object raw = prefs.getAll().get(key); int value = fallback;
        if (raw instanceof Number) value = Math.round(((Number) raw).floatValue());
        else if (raw instanceof String) { try { value = Math.round(Float.parseFloat((String) raw)); } catch (NumberFormatException ignored) { value = fallback; } }
        return Math.max(min, Math.min(max, value));
    }

    private int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    public boolean showNotificationHistoryIfAvailable(final int pos, View v) {
        if (pos < 0 || pos >= getCount() || v == null) return false;
        return NotificationHistoryResolver.showForPojo(v.getContext(), getItem(pos).getPojo());
    }

    public void onLongClick(final int pos, View v) {
        if (pos < 0 || pos >= getCount()) return;
        Result<?> result = getItem(pos); Context context = v.getContext();
        if (showNotificationHistoryIfAvailable(pos, v)) return;
        if (UiEditLock.isLocked(context)) return;
        ListPopup menu = result.getPopupMenu(context, this, v);
        if (menu.getAdapter().getCount() > 0) { parent.registerPopup(menu); menu.show(v); }
    }

    public void onClick(final int position, View v) {
        try {
            final Result<?> result = getItem(position);
            RecentLaunchTracker.remember(result.getPojo());
            if (result.getPojo() instanceof NotificationPojo
                    && NotificationHistoryResolver.showForPojo(v.getContext(), result.getPojo())) {
                recordExplicitSelection(v.getContext(), result.getPojo());
                return;
            }

            Pojo pojo = result.getPojo();
            boolean morphLaunch = pojo instanceof AppPojo
                    || pojo instanceof ShortcutPojo
                    || pojo instanceof DisabledAppPojo;
            if (morphLaunch) {
                boolean started = LaunchMorphTransition.start(
                        v.getContext(), v, () -> result.launch(v.getContext(), v, parent));
                if (started) return;
            }
            result.launch(v.getContext(), v, parent);
        } catch (IndexOutOfBoundsException e) { Log.w(TAG, "Unable to click", e); }
    }

    public void removeResult(Result<?> result) { parent.beforeListChange(); results.remove(result); notifyDataSetChanged(); parent.temporarilyDisableTranscriptMode(); parent.afterListChange(); }

    public void updateWithPojos(@NonNull Context context, @NonNull List<Pojo> pojos, boolean isRefresh, String query) {
        Map<Pojo, Result<?>> existingResults = new HashMap<>(Math.max(16, results.size() * 2));
        for (Result<?> result : results) existingResults.put(result.getPojo(), result);
        List<Result<?>> updatedResults = new ArrayList<>(pojos.size());
        for (Pojo pojo : pojos) {
            if (pojo == null) continue;
            Result<?> existing = existingResults.get(pojo);
            if (existing != null) updatedResults.add(existing);
            else if (pojo instanceof CommunicationPojo) updatedResults.add(new CommunicationResult((CommunicationPojo) pojo));
            else updatedResults.add(Result.fromPojo(parent, pojo));
        }
        updateResults(context, updatedResults, isRefresh, query);
    }

    public void updateResults(@NonNull Context context, List<Result<?>> updatedResults, boolean isRefresh, String query) {
        String normalizedQuery = query == null ? "" : query;
        if (sameVisibleState(updatedResults, normalizedQuery)) return;

        parent.beforeListChange();
        this.results.clear();
        this.results.addAll(updatedResults);
        notificationPreviewCache.clear();
        lastRenderedQuery = normalizedQuery;
        StringNormalizer.Result queryNormalized = StringNormalizer.normalizeWithResult(normalizedQuery, false);
        fuzzyScore = FuzzyFactory.createFuzzyScore(context, queryNormalized.codePoints, true);
        notifyDataSetChanged();
        if (isRefresh) parent.temporarilyDisableTranscriptMode();
        parent.afterListChange();
    }

    private boolean sameVisibleState(List<Result<?>> updatedResults, String query) {
        if (!TextUtils.equals(lastRenderedQuery, query)) return false;
        if (updatedResults == null || updatedResults.size() != results.size()) return false;

        for (int i = 0; i < results.size(); i++) {
            Result<?> oldResult = results.get(i);
            Result<?> newResult = updatedResults.get(i);
            if (oldResult == null || newResult == null) {
                if (oldResult != newResult) return false;
                continue;
            }
            if (!samePojoState(oldResult.getPojo(), newResult.getPojo())) return false;
        }
        return true;
    }

    private boolean samePojoState(Pojo oldPojo, Pojo newPojo) {
        if (oldPojo == null || newPojo == null) return oldPojo == newPojo;
        if (oldPojo.getClass() != newPojo.getClass()) return false;
        if (!TextUtils.equals(oldPojo.id, newPojo.id)) return false;
        if (!TextUtils.equals(oldPojo.getName(), newPojo.getName())) return false;

        if (oldPojo instanceof AppPojo && newPojo instanceof AppPojo) {
            if (((AppPojo) oldPojo).isDisabled() != ((AppPojo) newPojo).isDisabled()) return false;
        }

        if (oldPojo instanceof NotificationPojo && newPojo instanceof NotificationPojo) {
            NotificationPojo oldNotification = (NotificationPojo) oldPojo;
            NotificationPojo newNotification = (NotificationPojo) newPojo;
            if (oldNotification.notificationCount != newNotification.notificationCount) return false;
            if (oldNotification.postTime != newNotification.postTime) return false;
            if (!TextUtils.equals(oldNotification.latestTitle, newNotification.latestTitle)) return false;
            if (!TextUtils.equals(oldNotification.latestText, newNotification.latestText)) return false;
        }

        if (oldPojo instanceof CommunicationPojo && newPojo instanceof CommunicationPojo) {
            CommunicationPojo oldCommunication = (CommunicationPojo) oldPojo;
            CommunicationPojo newCommunication = (CommunicationPojo) newPojo;
            if (oldCommunication.kind != newCommunication.kind) return false;
            if (oldCommunication.timestamp != newCommunication.timestamp) return false;
            if (!TextUtils.equals(oldCommunication.displayName, newCommunication.displayName)) return false;
            if (!TextUtils.equals(oldCommunication.address, newCommunication.address)) return false;
            if (!TextUtils.equals(oldCommunication.body, newCommunication.body)) return false;
            if (!TextUtils.equals(oldCommunication.notificationId, newCommunication.notificationId)) return false;
        }

        return true;
    }

    public void updateTranscriptMode(int transcriptMode) { parent.updateTranscriptMode(transcriptMode); }
    public void clear() { parent.beforeListChange(); this.results.clear(); notificationPreviewCache.clear(); lastRenderedQuery = null; notifyDataSetChanged(); parent.afterListChange(); }

    public void buildSections() {
        alphaIndexer.clear(); int size = results.size();
        for (int i = 0; i < size; i++) { String s = getItem(i).getSection(); if (!alphaIndexer.containsKey(s)) alphaIndexer.put(s, i); }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(alphaIndexer.entrySet()); Collections.sort(entries, Map.Entry.comparingByValue());
        sections = new String[entries.size()]; for (int i = 0; i < entries.size(); i++) sections[i] = entries.get(i).getKey();
    }

    @Override public Object[] getSections() { return sections; }
    @Override public int getPositionForSection(int sectionIndex) { if (sections.length == 0) return 0; sectionIndex = Math.max(0, Math.min(sections.length - 1, sectionIndex)); return alphaIndexer.getOrDefault(sections[sectionIndex], 0); }
    @Override public int getSectionForPosition(int position) { for (int i = 0; i < sections.length; i++) if (alphaIndexer.get(sections[i]) > position) return i - 1; return Math.max(sections.length - 2, 0); }
    public void showDialog(DialogFragment dialog) { parent.showDialog(dialog); }
}
