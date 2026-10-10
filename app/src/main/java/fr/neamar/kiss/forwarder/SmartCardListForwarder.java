package fr.neamar.kiss.forwarder;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.pojo.NotificationPojo;
import fr.neamar.kiss.pojo.ShortcutPojo;
import fr.neamar.kiss.result.AppResult;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.ui.AutoScrollPreviewTextView;
import fr.neamar.kiss.ui.NotificationBellStyle;
import fr.neamar.kiss.ui.SmartAnimationEngine;
import fr.neamar.kiss.ui.ScrollIdleGate;
import fr.neamar.kiss.ui.SmartTextAppearance;
import fr.neamar.kiss.ui.UniversalHistoryTimestamp;
import fr.neamar.kiss.ui.TextOverflowMode;

/**
 * Vertical Smart Card renderer. The visible card has its own deliberate layout, while the real
 * adapter notification controls are re-parented so their existing listeners and behaviour remain
 * intact.
 */
final class SmartCardListForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String LEGACY_PREF_ENABLED = "smart-card-list-enabled";
    private static final int ACCENT_SAMPLE_SIZE = 10;
    private static final int MAX_ACCENT_CACHE_SIZE = 256;
    private static final long ACTIVE_QUERY_REBUILD_DEBOUNCE_MS = 120L;

    private final Map<String, String> activeQueryCardSignatures = new HashMap<>();
    private final Map<String, String> historyCardSignatures = new HashMap<>();
    private final Map<Long, Integer> accentCache =
            new LinkedHashMap<Long, Integer>(MAX_ACCENT_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
                    return size() > MAX_ACCENT_CACHE_SIZE;
                }
            };
    private FrameLayout container;
    private ScrollView scroller;
    private LinearLayout column;
    private View edgeEffect;
    private boolean pendingDataSetRefresh;
    private boolean forceNextHistoryRebuild;
    private boolean renderedActiveQuery;
    private Runnable deferredHistoryRefreshCallback;
    private Runnable userScrollStartedCallback;
    private boolean deferredRefreshIdleScheduled;
    private boolean rebuildQueued;
    private boolean rebuildQueuedAllowActiveQueryReuse;
    private final Set<String> animatedCardIds = new HashSet<>();
    private String lastAnimationScope = null;
    private int lastPresentationSignature = Integer.MIN_VALUE;
    private final Runnable rebuildAfterIdle = () -> {
        rebuildQueued = false;
        boolean allowReuse = rebuildQueuedAllowActiveQueryReuse;
        rebuildQueuedAllowActiveQueryReuse = false;
        if (isEnabled()) rebuild(allowReuse);
    };
    private final Runnable deferredRefreshAfterIdle = () -> {
        deferredRefreshIdleScheduled = false;
        if (scroller == null || !pendingDataSetRefresh || isActiveQuery()) return;

        // History order is authoritative. While a finger/fling is active we defer rebuilding so
        // content cannot jump underneath the gesture, but as soon as motion settles the pending
        // chronological dataset must replace the stale tree regardless of viewport position.
        Runnable callback = deferredHistoryRefreshCallback;
        if (callback != null) callback.run();
    };
    private final Runnable activeQueryRebuildRunnable = () -> {
        if (isEnabled() && isActiveQuery()) rebuild(true);
    };

    SmartCardListForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        if (!(mainActivity.listContainer instanceof FrameLayout)) return;
        container = (FrameLayout) mainActivity.listContainer;
        edgeEffect = mainActivity.findViewById(R.id.listEdgeEffect);

        scroller = new StableCardScrollView();
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.setScrollbarFadingEnabled(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroller.setClipToPadding(false);
        scroller.setPadding(dp(8), dp(8), dp(8), dp(18));
        scroller.setVisibility(View.GONE);

        column = new LinearLayout(mainActivity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        scroller.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM));
        migrateLegacySelection();
        applyState(true);
    }

    void onResume() {
        migrateLegacySelection();
        boolean wasVisible = scroller != null && scroller.getVisibility() == View.VISIBLE;
        applyState(false);
        // A hidden card tree is deliberately discarded when another renderer owns History.
        // Re-entering Vertical Cards must therefore rebuild from the current adapter, never reuse
        // stale cards from an earlier mode.
        if (isEnabled() && column != null && (!wasVisible || column.getChildCount() == 0)) {
            rebuild();
        } else if (isEnabled() && column != null && column.getChildCount() > 0) {
            if (lastPresentationSignature != presentationSignature()) {
                // Existing cards are retained across Home visits. A change in Settings must
                // rebind those exact cards once; ordinary resumes reuse the retained tree.
                column.removeAllViews();
                historyCardSignatures.clear();
                activeQueryCardSignatures.clear();
                rebuild();
            } else {
                replayVisibleCardsIfAnimationConfigChanged();
            }
        }
    }

    boolean onDataSetChanged() {
        if (!isEnabled()) return false;
        // Search can publish several adapter updates for one input change, so active QUERY
        // publications remain coalesced. History is different: its adapter order is the visible
        // chronological contract. Rebuild immediately when idle; while scrolling, defer only until
        // the gesture settles so the card tree can never remain stale/mixed.
        boolean activeQuery = isActiveQuery();
        if (willRebuildSynchronouslyForDataSetChange()) {
            cancelPendingActiveQueryRebuild();
            rebuild();
            return true;
        }
        if (activeQuery) {
            scheduleActiveQueryRebuild();
            return false;
        }

        cancelPendingActiveQueryRebuild();
        pendingDataSetRefresh = true;
        scheduleDeferredRefreshIdleProbe();
        return false;
    }

    void onDestroy() {
        cancelPendingActiveQueryRebuild();
        cancelDeferredRefreshIdleProbe();
        if (scroller instanceof StableCardScrollView) {
            ((StableCardScrollView) scroller).scrollIdleGate.cancel(rebuildAfterIdle);
            ((StableCardScrollView) scroller).scrollIdleGate.destroy();
        }
        deferredHistoryRefreshCallback = null;
        userScrollStartedCallback = null;
        activeQueryCardSignatures.clear();
        historyCardSignatures.clear();
        animatedCardIds.clear();
        accentCache.clear();
        container = null;
        scroller = null;
        column = null;
        edgeEffect = null;
        pendingDataSetRefresh = false;
        forceNextHistoryRebuild = false;
        renderedActiveQuery = false;
        rebuildQueued = false;
        rebuildQueuedAllowActiveQueryReuse = false;
    }

    ScrollView getScroller() {
        return scroller;
    }

    LinearLayout getColumn() {
        return column;
    }

    void setDeferredHistoryRefreshCallback(Runnable callback) {
        deferredHistoryRefreshCallback = callback;
    }

    void setUserScrollStartedCallback(Runnable callback) {
        userScrollStartedCallback = callback;
    }

    boolean willRebuildSynchronouslyForDataSetChange() {
        if (!isEnabled() || isScrollInProgress()) return false;
        boolean activeQuery = isActiveQuery();

        // Every idle History dataset publication is authoritative and must be reflected in the
        // visible card order immediately. Active QUERY still uses its debounce/reuse path.
        if (!activeQuery) return true;
        return column == null || column.getChildCount() == 0;
    }

    boolean hasPendingDataSetRefresh() {
        return pendingDataSetRefresh;
    }

    void forceHistoryRebuildOnNextDataSetChange() {
        forceNextHistoryRebuild = true;
    }

    boolean rebuildPendingDataSetRefresh() {
        if (!pendingDataSetRefresh || !isEnabled() || isActiveQuery()) return false;
        if (isScrollInProgress()) {
            scheduleDeferredRefreshIdleProbe();
            return false;
        }
        rebuild();
        return true;
    }

    boolean consumeDeferredKeepBottom() {
        // Preserve the bottom edge only when the user was actually at the bottom when deferred
        // history work becomes safe to apply. Otherwise the viewport controller restores the
        // current stable row while the chronological tree is rebuilt around it.
        return isAtBottom();
    }

    private boolean isAtBottom() {
        if (scroller == null || column == null || column.getChildCount() == 0) return true;
        View content = scroller.getChildAt(0);
        if (content == null) return true;
        int viewportHeight = Math.max(0, scroller.getHeight()
                - scroller.getPaddingTop() - scroller.getPaddingBottom());
        int maxScrollY = Math.max(0, content.getHeight() - viewportHeight);
        return maxScrollY - scroller.getScrollY() <= dp(6);
    }

    void rebuildImmediately() {
        if (isEnabled() && !isScrollInProgress()) rebuild();
    }

    private void migrateLegacySelection() {
        String mode = prefs.getString(HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL);
        if (prefs.getBoolean(LEGACY_PREF_ENABLED, false)
                && HistoryDisplayForwarder.VERTICAL.equals(mode)) {
            prefs.edit()
                    .putString(HistoryDisplayForwarder.PREF_LAYOUT, VERTICAL_CARDS)
                    .remove(LEGACY_PREF_ENABLED)
                    .apply();
        }
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(
                prefs.getString(HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private boolean isActiveQuery() {
        return mainActivity.searchEditText != null
                && !TextUtils.isEmpty(mainActivity.searchEditText.getText());
    }

    /**
     * While the IME search field owns text input, the rebuilt card tree must not enter Android's
     * focus-navigation graph. Clickable Views are automatically focusable on modern Android, and
     * this renderer recreates several clickable descendants per result. Blocking descendants only
     * for an active query keeps touch interaction intact while preventing relayout from stealing
     * EditText/IME focus.
     */
    private void scheduleActiveQueryRebuild() {
        if (scroller == null) return;
        scroller.removeCallbacks(activeQueryRebuildRunnable);
        scroller.postDelayed(activeQueryRebuildRunnable, ACTIVE_QUERY_REBUILD_DEBOUNCE_MS);
    }

    private void cancelPendingActiveQueryRebuild() {
        if (scroller != null) scroller.removeCallbacks(activeQueryRebuildRunnable);
    }

    private void scheduleDeferredRefreshIdleProbe() {
        if (scroller == null || deferredRefreshIdleScheduled || isActiveQuery()) return;
        deferredRefreshIdleScheduled = true;
        runWhenScrollIdle(deferredRefreshAfterIdle);
    }

    private void cancelDeferredRefreshIdleProbe() {
        if (scroller != null) scrollIdleGate().cancel(deferredRefreshAfterIdle);
        deferredRefreshIdleScheduled = false;
    }

    boolean isScrollInProgress() {
        return scroller instanceof StableCardScrollView
                && ((StableCardScrollView) scroller).scrollIdleGate.isScrolling();
    }

    void onScrollIdleAnimations() {
        if (!isEnabled() || scroller == null || column == null || isScrollInProgress()) {
            return;
        }

        if (!SmartAnimationEngine.isEnabled(mainActivity)) {
            int top = scroller.getScrollY();
            int bottom = top + scroller.getHeight();
            for (int i = 0; i < column.getChildCount(); i++) {
                View child = column.getChildAt(i);
                if (child.getBottom() < top || child.getTop() > bottom) continue;
                SmartAnimationEngine.reset(child);
            }
            return;
        }

        int viewportTop = scroller.getScrollY();
        int viewportBottom = viewportTop + scroller.getHeight();
        int visibleIndex = 0;
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child.getBottom() < viewportTop || child.getTop() > viewportBottom) continue;
            Object tag = child.getTag();
            String id = tag instanceof String ? (String) tag : null;
            if (id != null && animatedCardIds.add(id)) {
                SmartAnimationEngine.animateTileListItem(child, visibleIndex);
            }
            visibleIndex++;
        }
    }

    void runWhenScrollIdle(Runnable work) {
        if (scroller instanceof StableCardScrollView) {
            ((StableCardScrollView) scroller).scrollIdleGate.runWhenIdle(work);
        } else {
            work.run();
        }
    }

    void addScrollStartedListener(Runnable listener) {
        if (scroller instanceof StableCardScrollView) {
            ((StableCardScrollView) scroller).scrollIdleGate.addScrollStartedListener(listener);
        }
    }

    private ScrollIdleGate scrollIdleGate() {
        return ((StableCardScrollView) scroller).scrollIdleGate;
    }

    private void applySearchFocusIsolation(boolean activeQuery) {
        if (scroller == null) return;
        scroller.setDescendantFocusability(activeQuery
                ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroller.setFocusable(!activeQuery);
        scroller.setFocusableInTouchMode(false);
        if (column != null) {
            column.setFocusable(false);
            column.setFocusableInTouchMode(false);
        }
    }

    private void applyState(boolean force) {
        if (scroller == null) return;
        boolean enabled = isEnabled();
        if (enabled) {
            boolean wasVisible = scroller.getVisibility() == View.VISIBLE;
            mainActivity.list.setVisibility(View.GONE);
            if (edgeEffect != null) edgeEffect.setVisibility(View.GONE);
            scroller.setVisibility(View.VISIBLE);
            if (!wasVisible) {
                animatedCardIds.clear();
                lastAnimationScope = null;
                SmartAnimationEngine.animateWindowSwitch(null, scroller);
            }
            if (force) rebuild();
        } else {
            boolean wasVisible = scroller.getVisibility() == View.VISIBLE;
            scroller.setVisibility(View.GONE);
            if (wasVisible && column != null) {
                // Do not keep a second complete card hierarchy attached behind Vertical List/3D
                // Wheel. Hidden AutoMarquee/preview views retain callbacks and memory even though
                // the user cannot see them.
                column.removeAllViews();
                activeQueryCardSignatures.clear();
                historyCardSignatures.clear();
                animatedCardIds.clear();
                lastAnimationScope = null;
                pendingDataSetRefresh = false;
                forceNextHistoryRebuild = false;
                renderedActiveQuery = false;
            }
            if (HistoryDisplayForwarder.VERTICAL.equals(
                    prefs.getString(HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL))) {
                mainActivity.list.setVisibility(View.VISIBLE);
                if (edgeEffect != null) edgeEffect.setVisibility(View.VISIBLE);
            }
        }
    }

    private String animationScope(boolean activeQuery) {
        String queryScope = activeQuery && mainActivity.searchEditText != null
                ? "query:" + mainActivity.searchEditText.getText().toString()
                : "history";
        return queryScope + "|anim:" + SmartAnimationEngine.listAnimationSignature(mainActivity);
    }

    private void replayVisibleCardsIfAnimationConfigChanged() {
        boolean activeQuery = isActiveQuery();
        String currentScope = animationScope(activeQuery);
        if (TextUtils.equals(lastAnimationScope, currentScope)) return;

        animatedCardIds.clear();
        lastAnimationScope = currentScope;
        if (scroller != null) scroller.post(this::onScrollIdleAnimations);
    }

    private void rebuild() {
        rebuild(false);
    }

    private void rebuild(boolean allowActiveQueryReuse) {
        if (column == null || mainActivity.adapter == null) return;
        if (isScrollInProgress()) {
            pendingDataSetRefresh = true;
            rebuildQueuedAllowActiveQueryReuse |= allowActiveQueryReuse;
            if (!rebuildQueued) {
                rebuildQueued = true;
                runWhenScrollIdle(rebuildAfterIdle);
            }
            return;
        }
        cancelPendingActiveQueryRebuild();
        cancelDeferredRefreshIdleProbe();
        pendingDataSetRefresh = false;
        boolean activeQuery = isActiveQuery();
        String animationScope = animationScope(activeQuery);
        if (!TextUtils.equals(lastAnimationScope, animationScope)) {
            animatedCardIds.clear();
            lastAnimationScope = animationScope;
        }
        boolean previouslyRenderedActiveQuery = renderedActiveQuery;
        if (!activeQuery) forceNextHistoryRebuild = false;
        renderedActiveQuery = activeQuery;
        boolean preserveSearchFocus = activeQuery
                && mainActivity.searchEditText != null
                && mainActivity.searchEditText.hasFocus();
        applySearchFocusIsolation(activeQuery);

        if (activeQuery && allowActiveQueryReuse && previouslyRenderedActiveQuery) {
            reconcileActiveQueryCards();
        } else if (!activeQuery && !previouslyRenderedActiveQuery
                && column.getChildCount() > 0) {
            // History updates are usually a reorder/insert/remove, not a reason to destroy 50
            // complex card trees. Reconcile stable identities and keep unchanged Views/drawables.
            reconcileHistoryCards();
        } else {
            // Never perform notification-history SQLite enrichment on the UI thread while building
            // cards. Direct notification rows already contain their preview, and optional metadata
            // forwarders enrich the tree asynchronously.
            Map<String, NotificationHistoryRecord> latestNotifications = Collections.emptyMap();
            activeQueryCardSignatures.clear();
            historyCardSignatures.clear();
            column.removeAllViews();
            int count = mainActivity.adapter.getCount();
            for (int position = 0; position < count; position++) {
                Result<?> result = mainActivity.adapter.getItem(position);
                View source = mainActivity.adapter.getView(position, null, column);
                View item = createCardItem(source, result, position, latestNotifications);
                column.addView(item);
                int animationIndex = Math.max(0, count - 1 - position);
                if (SmartAnimationEngine.canAnimateTileListIndex(animationIndex)
                        && animatedCardIds.add(result.getPojoId())) {
                    SmartAnimationEngine.animateTileListItem(item, animationIndex);
                }
                if (activeQuery) {
                    activeQueryCardSignatures.put(result.getPojoId(), cardSignature(result));
                } else {
                    historyCardSignatures.put(result.getPojoId(), cardSignature(result));
                }
            }
        }

        lastPresentationSignature = presentationSignature();
        if (preserveSearchFocus && !mainActivity.searchEditText.hasFocus()) {
            // Restore only a focus state that existed before this rebuild. This is not an
            // unconditional IME reopen: it simply prevents card-tree replacement from ending
            // an active typing session.
            mainActivity.showKeyboard();
        }

    }

    /**
     * Active search used to destroy and recreate the complete Vertical Cards hierarchy after every
     * debounce. Reconcile by stable POJO identity instead: unchanged cards keep their Views, click
     * listeners and drawables; only inserted/changed results are materialized from the adapter.
     */
    private void reconcileActiveQueryCards() {
        if (column == null || mainActivity.adapter == null) return;

        Map<String, View> existingById = new HashMap<>();
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && !existingById.containsKey((String) tag)) {
                existingById.put((String) tag, child);
            }
        }

        Map<String, String> nextSignatures = new HashMap<>();
        int targetCount = mainActivity.adapter.getCount();
        for (int position = 0; position < targetCount; position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            String id = result.getPojoId();
            String signature = cardSignature(result);
            nextSignatures.put(id, signature);

            View desired = existingById.remove(id);
            if (desired != null
                    && !TextUtils.equals(activeQueryCardSignatures.get(id), signature)) {
                if (desired.getParent() == column) column.removeView(desired);
                desired = null;
            }
            boolean created = desired == null;
            if (created) {
                View source = mainActivity.adapter.getView(position, null, column);
                desired = createCardItem(
                        source, result, position, Collections.emptyMap());
            }
            placeCardChild(desired, position);
            int animationIndex = Math.max(0, targetCount - 1 - position);
            if (created && SmartAnimationEngine.canAnimateTileListIndex(animationIndex)
                    && animatedCardIds.add(id)) {
                SmartAnimationEngine.animateTileListItem(desired, animationIndex);
            }
        }

        // Entries left in the map disappeared from the new query result set. Remove those exact
        // stale Views rather than trimming arbitrary children from the end after reordering.
        for (View stale : existingById.values()) {
            if (stale.getParent() == column) column.removeView(stale);
        }
        while (column.getChildCount() > targetCount) {
            column.removeViewAt(column.getChildCount() - 1);
        }
        activeQueryCardSignatures.clear();
        activeQueryCardSignatures.putAll(nextSignatures);
        column.requestLayout();
        column.invalidate();
    }

    /**
     * Reconcile the idle History card tree in place. Unchanged cards keep their nested text,
     * drawables, click wiring and icon-accent cache; only genuinely new/changed cards are rebuilt.
     * This removes the large allocation/layout burst that used to accompany every launch,
     * notification or provider publication.
     */
    private void reconcileHistoryCards() {
        if (column == null || mainActivity.adapter == null) return;

        Map<String, View> existingById = new HashMap<>();
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && !existingById.containsKey((String) tag)) {
                existingById.put((String) tag, child);
            }
        }

        Map<String, String> nextSignatures = new HashMap<>();
        int targetCount = mainActivity.adapter.getCount();
        for (int position = 0; position < targetCount; position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            if (result == null) continue;
            String id = result.getPojoId();
            String signature = cardSignature(result);
            nextSignatures.put(id, signature);

            View desired = existingById.remove(id);
            if (desired != null
                    && !TextUtils.equals(historyCardSignatures.get(id), signature)) {
                if (desired.getParent() == column) column.removeView(desired);
                desired = null;
            }
            boolean created = desired == null;
            if (created) {
                View source = mainActivity.adapter.getView(position, null, column);
                desired = createCardItem(source, result, position, Collections.emptyMap());
            }
            placeCardChild(desired, position);
            int animationIndex = Math.max(0, targetCount - 1 - position);
            if (created && SmartAnimationEngine.canAnimateTileListIndex(animationIndex)
                    && animatedCardIds.add(id)) {
                SmartAnimationEngine.animateTileListItem(desired, animationIndex);
            }
        }

        for (View stale : existingById.values()) {
            if (stale.getParent() == column) column.removeView(stale);
        }
        while (column.getChildCount() > targetCount) {
            column.removeViewAt(column.getChildCount() - 1);
        }

        historyCardSignatures.clear();
        historyCardSignatures.putAll(nextSignatures);
        activeQueryCardSignatures.clear();
        forceNextHistoryRebuild = false;
        column.requestLayout();
    }

    private void placeCardChild(View child, int targetPosition) {
        if (child.getParent() == column) {
            int currentPosition = column.indexOfChild(child);
            if (currentPosition == targetPosition) return;
            if (currentPosition >= 0) column.removeViewAt(currentPosition);
        } else if (child.getParent() instanceof ViewGroup) {
            ((ViewGroup) child.getParent()).removeView(child);
        }
        column.addView(child, Math.min(targetPosition, column.getChildCount()));
    }

    private String cardSignature(Result<?> result) {
        if (result == null || result.getPojo() == null) return "<null>";
        fr.neamar.kiss.pojo.Pojo pojo = result.getPojo();
        StringBuilder signature = new StringBuilder(96)
                .append(result.getClass().getName()).append('|')
                .append(result.getPojoId()).append('|')
                .append(pojo.getName()).append('|')
                .append(pojo.isDisabled());

        if (pojo instanceof NotificationPojo) {
            NotificationPojo n = (NotificationPojo) pojo;
            signature.append('|').append(n.postTime)
                    .append('|').append(n.notificationCount)
                    .append('|').append(n.latestTitle)
                    .append('|').append(n.latestText);
        } else if (pojo instanceof CommunicationPojo) {
            CommunicationPojo communication = (CommunicationPojo) pojo;
            signature.append('|').append(communication.timestamp)
                    .append('|').append(communication.displayName)
                    .append('|').append(communication.body);
        }
        return signature.toString();
    }

    private final class StableCardScrollView extends ScrollView {
        private final VerticalCardUserScrollGesturePolicy userScrollGesturePolicy =
                new VerticalCardUserScrollGesturePolicy();
        final ScrollIdleGate scrollIdleGate;

        StableCardScrollView() {
            super(mainActivity);
            scrollIdleGate = new ScrollIdleGate(this);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            scrollIdleGate.onTouchEvent(event);
            if (action == MotionEvent.ACTION_DOWN) {
                cancelDeferredRefreshIdleProbe();
                userScrollGesturePolicy.onTouchDown(getScrollY());
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_MOVE
                    && userScrollGesturePolicy.onTouchMove(getScrollY())) {
                Runnable callback = userScrollStartedCallback;
                if (callback != null) callback.run();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                userScrollGesturePolicy.onTouchEnd();
                if (pendingDataSetRefresh) {
                    scheduleDeferredRefreshIdleProbe();
                }
            }
            return handled;
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            scrollIdleGate.onScrollChanged();
        }
    }

    private int presentationSignature() {
        String[] keys = {
                "smart-list-card-height-percent", "smart-list-card-icon-percent",
                "smart-list-card-name-percent", "smart-list-card-spacing-dp",
                "smart-list-icon-size-percent", "smart-list-notification-icon-size-percent",
                "smart-list-shortcut-icon-size-percent", "smart-list-contact-icon-size-percent",
                "smart-list-feature-icon-size-percent", "smart-list-resize-notification-icons",
                "smart-list-resize-shortcut-icons", "smart-list-resize-contact-icons",
                "smart-list-resize-feature-icons", "smart-list-label-size-sp",
                "smart-list-body-size-sp", "smart-history-meta-size-sp",
                "smart-history-meta-font", "smart-history-meta-color",
                "global-text-size-percent"
        };
        Map<String, ?> values = prefs.getAll();
        int hash = 17;
        for (String key : keys) {
            Object value = values.get(key);
            hash = 31 * hash + (value == null ? 0 : value.hashCode());
        }
        return hash;
    }

    private int configuredCardIconPercent(Result<?> result) {
        int normal = prefInt("smart-list-icon-size-percent", 110, 50, 240);
        String key = null;
        String enabled = null;
        if (result.getPojo() instanceof NotificationPojo) {
            key = "smart-list-notification-icon-size-percent";
            enabled = "smart-list-resize-notification-icons";
        } else if (result.getPojo() instanceof ShortcutPojo) {
            key = "smart-list-shortcut-icon-size-percent";
            enabled = "smart-list-resize-shortcut-icons";
        } else if (result.getPojo() instanceof CommunicationPojo
                || result.getPojo() instanceof fr.neamar.kiss.pojo.ContactsPojo
                || result.getPojo() instanceof fr.neamar.kiss.pojo.PhonePojo) {
            key = "smart-list-contact-icon-size-percent";
            enabled = "smart-list-resize-contact-icons";
        } else if (result.getPojo() instanceof fr.neamar.kiss.pojo.SettingPojo) {
            key = "smart-list-feature-icon-size-percent";
            enabled = "smart-list-resize-feature-icons";
        }
        if (key != null) {
            normal = prefs.getBoolean(enabled, true)
                    ? prefInt(key, normal, 50, 240) : 100;
        }
        int card = prefInt("smart-list-card-icon-percent", 100, 60, 180);
        return Math.max(50, Math.min(240, Math.round(normal * card / 100f)));
    }

    void refreshHistoryMetadata() {
        if (!isEnabled() || column == null || mainActivity.adapter == null
                || isScrollInProgress() || !UniversalHistoryTimestamp.isHistorySurface(mainActivity)) {
            return;
        }
        for (int i = 0; i < column.getChildCount(); i++) {
            View row = column.getChildAt(i);
            if (i >= mainActivity.adapter.getCount()) break;
            TextView timestamp = row.findViewById(R.id.item_history_meta);
            if (timestamp == null) continue;
            CharSequence content = UniversalHistoryTimestamp.describe(
                    mainActivity.adapter.getItem(i), mainActivity);
            if (!TextUtils.equals(timestamp.getText(), content)) timestamp.setText(content);
            SmartTextAppearance.applyHistoryMetadata(timestamp);
            mainActivity.applyGlobalTextScaleToSubtree(timestamp);
        }
    }

    private View createCardItem(View source, Result<?> result, int adapterPosition,
                                Map<String, NotificationHistoryRecord> latestNotifications) {
        int heightPercent = prefInt("smart-list-card-height-percent", 100, 70, 170);
        int iconPercent = configuredCardIconPercent(result);
        int radiusDp = prefInt("smart-list-card-radius-dp", 22, 6, 40);
        int elevationDp = prefInt("smart-list-card-elevation-dp", 9, 0, 24);
        int namePercent = prefInt("smart-list-card-name-percent", 100, 70, 170);
        int spacingDp = prefInt("smart-list-card-spacing-dp", 12, 4, 36);
        int minimumCardHeight = Math.max(dp(96), dp(122) * heightPercent / 100);

        LinearLayout wrapper = new LinearLayout(mainActivity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);
        // The viewport controller uses this stable identity across history re-ranking/rebuilds.
        // A numeric child position is not stable after a launch or notification insertion.
        wrapper.setTag(result.getPojoId());
        LinearLayout.LayoutParams wrapperLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapperLp.setMargins(dp(4), dp(spacingDp / 2), dp(4), dp(spacingDp / 2));
        wrapper.setLayoutParams(wrapperLp);

        LinearLayout card = new LinearLayout(mainActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        card.setElevation(dp(elevationDp));
        card.setMinimumHeight(minimumCardHeight);
        card.setClipToPadding(false);
        card.setClipChildren(false);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(4), 0, dp(4), 0);
        wrapper.addView(card, cardLp);

        CharSequence label = cleanDisplayLabel(extractLabel(source));
        CharSequence subtitle = extractSubtitle(source);
        CommunicationPojo call = result.getPojo() instanceof CommunicationPojo
                ? (CommunicationPojo) result.getPojo() : null;
        if (call != null && call.kind == CommunicationPojo.Kind.CALL) {
            label = callSummary(call);
        }

        ImageView liveIcon = findIconView(source);
        Drawable iconDrawable = liveIcon == null ? null : liveIcon.getDrawable();
        // Match Horizontal Icons' smooth-scroll rule: never turn a cold asynchronous icon into a
        // synchronous drawable load on the UI thread just to style the card. A neutral provisional
        // accent is deliberately not cached; bindDrawable() below supplies the real icon/accent.
        int accent = iconDrawable == null
                ? Color.rgb(64, 84, 118) : accentFor(result, iconDrawable);
        styleCard(card, radiusDp, accent);

        View notificationRow = source.findViewById(R.id.item_notification_row);
        boolean hasActiveNotification = notificationRow != null
                && notificationRow.getVisibility() == View.VISIBLE;
        String latestMessage = latestKnownNotificationMessage(
                result, source, latestNotifications);
        boolean hasMessage = !TextUtils.isEmpty(latestMessage);

        LinearLayout mainRow = new LinearLayout(mainActivity);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        mainRow.setBaselineAligned(false);
        card.addView(mainRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View iconView;
        if (liveIcon != null) {
            detachFromParent(liveIcon);
            liveIcon.setVisibility(View.VISIBLE);
            liveIcon.setAlpha(1f);
            liveIcon.setScaleX(1f);
            liveIcon.setScaleY(1f);
            liveIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            if (liveIcon.getDrawable() == null && iconDrawable != null) {
                liveIcon.setImageDrawable(iconDrawable);
            }
            iconView = liveIcon;
        } else {
            ImageView fallback = new ImageView(mainActivity);
            fallback.setImageDrawable(iconDrawable != null
                    ? iconDrawable : mainActivity.getPackageManager().getDefaultActivityIcon());
            fallback.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iconView = fallback;
        }
        // Do not cap the icon at 88dp: that silently discarded the large-icon setting.
        int iconSize = Math.max(dp(32), dp(66) * iconPercent / 100);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = dp(14);
        mainRow.addView(iconView, iconLp);
        if (iconView instanceof ImageView) {
            ImageView renderedIcon = (ImageView) iconView;
            result.bindDrawable(renderedIcon, drawable -> {
                if (drawable == null || renderedIcon.getParent() == null) return;
                int resolvedAccent = accentFor(result, drawable);
                styleCard(card, radiusDp, resolvedAccent);
            });
        }

        LinearLayout center = new LinearLayout(mainActivity);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_VERTICAL);
        mainRow.addView(center, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        AutoMarqueeTextView cardTitle = new AutoMarqueeTextView(mainActivity);
        cardTitle.setText(label);
        cardTitle.setTextColor(Color.WHITE);
        cardTitle.setTextSize(prefInt("smart-list-label-size-sp", 18, 10, 40)
                * namePercent / 100f);
        cardTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cardTitle.setShadowLayer(dp(2), 0f, dp(1), Color.argb(180, 0, 0, 0));
        NotificationBellStyle.apply(cardTitle,
                NotificationBellStyle.isNotificationItem(mainActivity, result, source));
        center.addView(cardTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(31) * Math.max(90, namePercent) / 100)));

        if (!TextUtils.isEmpty(subtitle)) {
            AutoMarqueeTextView meta = new AutoMarqueeTextView(mainActivity);
            meta.setText(subtitle);
            meta.setTextColor(Color.argb(220, 250, 250, 250));
            meta.setTextSize(prefInt("smart-list-body-size-sp", 14, 8, 32));
            meta.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            meta.setShadowLayer(dp(1), 0f, dp(1), Color.argb(160, 0, 0, 0));
            center.addView(meta, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(27))));
        }

        if (UniversalHistoryTimestamp.isHistorySurface(mainActivity)) {
            // A single dedicated timestamp slot, not a second injected overlay. Refresh
            // this in place when asynchronous History statistics become available.
            AutoMarqueeTextView timestamp = new AutoMarqueeTextView(mainActivity);
            timestamp.setId(R.id.item_history_meta);
            timestamp.setText(UniversalHistoryTimestamp.describe(result, mainActivity));
            SmartTextAppearance.applyHistoryMetadata(timestamp);
            center.addView(timestamp, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView messageView = null;
        if (hasActiveNotification) {
            TextView activeText = notificationRow.findViewById(R.id.item_notification_text);
            View read = notificationRow.findViewById(R.id.item_notification_read);
            if (activeText != null) {
                detachFromParent(activeText);
                if (!TextUtils.isEmpty(latestMessage)) activeText.setText(latestMessage);
                configureCollapsedMessage(activeText);
                center.addView(activeText, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                messageView = activeText;
            }
            if (read != null) {
                detachFromParent(read);
                LinearLayout actions = new LinearLayout(mainActivity);
                actions.setOrientation(LinearLayout.HORIZONTAL);
                actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                actionRowLp.topMargin = dp(4);
                center.addView(actions, actionRowLp);
                actions.addView(read, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            notificationRow.setVisibility(View.GONE);
        } else if (hasMessage) {
            AutoScrollPreviewTextView lastMessage = new AutoScrollPreviewTextView(mainActivity);
            lastMessage.setText(latestMessage);
            lastMessage.setTextColor(Color.WHITE);
            lastMessage.setTextSize(prefInt("smart-list-body-size-sp", 14, 8, 32));
            lastMessage.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            lastMessage.setPadding(0, dp(2), 0, dp(2));
            lastMessage.setShadowLayer(dp(1), 0f, dp(1), Color.argb(150, 0, 0, 0));
            center.addView(lastMessage, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(31))));
            messageView = lastMessage;
        } else if (TextUtils.isEmpty(subtitle)) {
            AutoMarqueeTextView context = new AutoMarqueeTextView(mainActivity);
            context.setText(describeResult(source));
            context.setTextColor(Color.argb(175, 255, 255, 255));
            context.setTextSize(prefInt("smart-list-body-size-sp", 14, 8, 32));
            context.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            center.addView(context, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(25))));
        }

        if (call != null && call.kind == CommunicationPojo.Kind.CALL
                && hasDistinctCallerName(call)) {
            AutoMarqueeTextView callerName = new AutoMarqueeTextView(mainActivity);
            callerName.setText(call.displayName);
            callerName.setTextColor(Color.WHITE);
            callerName.setTextSize(prefInt("smart-list-label-size-sp", 18, 10, 40)
                    * namePercent / 100f);
            callerName.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            callerName.setPadding(0, dp(3), 0, dp(1));
            callerName.setShadowLayer(dp(2), 0f, dp(1), Color.argb(180, 0, 0, 0));
            callerName.setContentDescription("Caller: " + call.displayName);
            center.addView(callerName, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, textRowHeight(dp(31) * Math.max(90, namePercent) / 100)));
        }

        prepareSourceForDetails(source);
        boolean hasDetails = hasMeaningfulVisibleContent(source);
        // The original adapter row is needed only while extracting the lightweight card above.
        // Keeping it hidden under every card doubles the view tree and can retain native
        // notification RemoteViews/drawables. Release that tree now and recreate details lazily.
        releaseDiscardedSource(source);

        if (hasDetails) {
            FrameLayout detailsPanel = new FrameLayout(mainActivity);
            detailsPanel.setVisibility(View.GONE);
            detailsPanel.setPadding(dp(4), dp(7), dp(4), dp(2));
            card.addView(detailsPanel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView details = new TextView(mainActivity);
            details.setText("⌄");
            details.setTextColor(Color.WHITE);
            details.setTextSize(18f);
            details.setGravity(Gravity.CENTER);
            details.setContentDescription("Show card details");
            details.setBackground(makePill(accent));
            LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(dp(38), dp(30));
            detailsLp.gravity = Gravity.END;
            detailsLp.topMargin = dp(4);
            card.addView(details, detailsLp);
            final String expectedPojoId = result.getPojoId();
            details.setOnClickListener(v -> toggleDetails(
                    detailsPanel, details, expectedPojoId));
        }

        final TextView expandableMessage = messageView;
        final boolean[] messageExpanded = {false};
        final boolean directNotification = result.getPojo() instanceof NotificationPojo;
        final boolean directShortcut = result.getPojo() instanceof ShortcutPojo;
        View.OnClickListener launchOrExpand = v -> {
            boolean needsExpansion = expandableMessage != null
                    && messageNeedsExpansion(expandableMessage);
            if (shouldExpandMessageBeforeLaunch(
                    directNotification, directShortcut, expandableMessage != null,
                    messageExpanded[0], needsExpansion)) {
                pressAnimation(card);
                expandMessage(expandableMessage);
                messageExpanded[0] = true;
                return;
            }
            pressAnimation(card);
            int currentPosition = resolveAdapterPosition(result.getPojoId());
            if (currentPosition >= 0) mainActivity.adapter.onClick(currentPosition, card);
        };
        View.OnLongClickListener longPress = v -> {
            int currentPosition = resolveAdapterPosition(result.getPojoId());
            if (currentPosition < 0) return false;
            mainActivity.adapter.onLongClick(currentPosition, v);
            return true;
        };

        // The icon itself is a first-class launch + context-menu target in every History renderer.
        // 3.30.142 added only the long-click side, which made the child consume normal taps. Bind
        // both actions to the same physical icon so one tap always launches immediately.
        iconView.setClickable(true);
        iconView.setLongClickable(true);
        iconView.setOnClickListener(v -> {
            int currentPosition = resolveAdapterPosition(result.getPojoId());
            if (currentPosition < 0) return;
            if (directNotification) {
                mainActivity.adapter.openNotificationApp(currentPosition, v);
            } else {
                mainActivity.adapter.onClick(currentPosition, v);
            }
        });
        iconView.setOnLongClickListener(longPress);

        card.setOnClickListener(launchOrExpand);
        cardTitle.setOnClickListener(launchOrExpand);
        card.setOnLongClickListener(longPress);
        cardTitle.setOnLongClickListener(longPress);
        if (expandableMessage != null) {
            // Notification/message bodies must keep their exact linked destination. AppResult and
            // ShortcutsResult attach that listener before the TextView is re-parented here, so do
            // not overwrite it with the surrounding app/shortcut launch action. Direct notification
            // cards created from lightweight text get the exact notification row action explicitly.
            if (directNotification) {
                expandableMessage.setOnClickListener(v -> {
                    int currentPosition = resolveAdapterPosition(result.getPojoId());
                    if (currentPosition >= 0) mainActivity.adapter.onClick(currentPosition, v);
                });
                expandableMessage.setClickable(true);
            }
            expandableMessage.setOnLongClickListener(longPress);
            expandableMessage.setFocusable(false);
            expandableMessage.setFocusableInTouchMode(false);
        }
        card.setClickable(true);
        cardTitle.setClickable(true);
        card.setFocusable(false);
        card.setFocusableInTouchMode(false);
        cardTitle.setFocusable(false);
        cardTitle.setFocusableInTouchMode(false);

        // Dynamic cards are the only new hierarchy that needs the global text multiplier.
        mainActivity.applyGlobalTextScaleToSubtree(wrapper);
        return wrapper;
    }

    static boolean shouldExpandMessageBeforeLaunch(boolean directNotification,
                                                   boolean directShortcut,
                                                   boolean hasMessage,
                                                   boolean alreadyExpanded,
                                                   boolean needsExpansion) {
        return !directNotification && !directShortcut && hasMessage
                && !alreadyExpanded && needsExpansion;
    }

    private CharSequence callSummary(CommunicationPojo call) {
        StringBuilder summary = new StringBuilder("Call");
        if (!TextUtils.isEmpty(call.address)) summary.append(" · ").append(call.address);
        if (!TextUtils.isEmpty(call.body)) summary.append(" · ").append(call.body);
        return summary.toString();
    }

    private boolean hasDistinctCallerName(CommunicationPojo call) {
        if (call == null || TextUtils.isEmpty(call.displayName)) return false;
        String name = call.displayName.trim();
        if (name.isEmpty()) return false;
        return TextUtils.isEmpty(call.address) || !name.equalsIgnoreCase(call.address.trim());
    }

    private String latestKnownNotificationMessage(
            Result<?> result,
            View source,
            Map<String, NotificationHistoryRecord> latestNotifications) {
        TextView active = source.findViewById(R.id.item_notification_text);
        String activeMessage = active == null || active.getVisibility() != View.VISIBLE
                ? "" : cleanText(active.getText());

        if (result instanceof AppResult) {
            String packageName = ((AppResult) result).getClassName().getPackageName();
            NotificationHistoryRecord latest = latestNotifications.get(packageName);
            if (latest != null) {
                String historical = combineNotification(latest.title, latest.text);
                if (!historical.isEmpty()) return historical;
            }
        }
        return activeMessage;
    }

    private String combineNotification(String title, String body) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanBody = body == null ? "" : body.trim();
        if (cleanTitle.isEmpty()) return cleanBody;
        if (cleanBody.isEmpty() || cleanTitle.equals(cleanBody)) return cleanTitle;
        return cleanTitle + ": " + cleanBody;
    }

    private String cleanText(CharSequence text) {
        return text == null ? "" : text.toString().trim();
    }

    private int textRowHeight(int scrollingHeight) {
        return TextOverflowMode.isAutoExpandForHistory(mainActivity)
                ? ViewGroup.LayoutParams.WRAP_CONTENT : scrollingHeight;
    }

    private void configureCollapsedMessage(TextView text) {
        if (TextOverflowMode.isAutoExpandForHistory(mainActivity)) {
            text.setSelected(false);
            text.setHorizontallyScrolling(false);
            text.setSingleLine(false);
            text.setMaxLines(Integer.MAX_VALUE);
            text.setEllipsize(null);
            text.setHorizontalFadingEdgeEnabled(false);
        } else {
            text.setSingleLine(true);
            text.setMaxLines(1);
            text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            text.setMarqueeRepeatLimit(-1);
            text.setHorizontallyScrolling(true);
            text.setSelected(true);
        }
        text.setTextColor(Color.WHITE);
        text.setTextSize(prefInt("smart-list-body-size-sp", 14, 8, 32));
        text.setGravity(Gravity.START);
        text.setPadding(0, dp(2), 0, dp(2));
    }

    private boolean messageNeedsExpansion(TextView text) {
        if (TextOverflowMode.isAutoExpandForHistory(mainActivity)) return false;
        CharSequence value = text.getText();
        if (TextUtils.isEmpty(value)) return false;
        int available = text.getWidth() - text.getPaddingLeft() - text.getPaddingRight();
        if (available <= 0) return true;
        float measured = text.getPaint().measureText(value.toString());
        return measured > available;
    }

    private void expandMessage(TextView text) {
        text.setSelected(false);
        text.setHorizontallyScrolling(false);
        text.setSingleLine(false);
        text.setMaxLines(Integer.MAX_VALUE);
        text.setEllipsize(null);
        ViewGroup.LayoutParams params = text.getLayoutParams();
        if (params != null) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            text.setLayoutParams(params);
        }
        text.requestLayout();
    }

    private void prepareSourceForDetails(View source) {
        hide(source, R.id.item_app_icon);
        hide(source, R.id.item_notification_dot);
        hide(source, R.id.item_app_name);
        hide(source, R.id.item_app_tag);
        hide(source, R.id.item_setting_icon);
        hide(source, R.id.item_setting_prefix);
        hide(source, R.id.item_setting_name);
        hide(source, R.id.item_shortcut_icon);
        hide(source, R.id.item_shortcut_tag);
        // The card already renders the canonical metadata slot; do not allow the
        // discarded native source's duplicate metadata to create a details button.
        hide(source, R.id.item_history_meta);
        View notification = source.findViewById(R.id.item_notification_row);
        if (notification != null) notification.setVisibility(View.GONE);
    }

    private void hide(View source, int id) {
        View view = source.findViewById(id);
        if (view != null) view.setVisibility(View.GONE);
    }

    private boolean hasMeaningfulVisibleContent(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (TextUtils.isEmpty(text.getText())) return false;
            String value = text.getText().toString().trim();
            return !value.isEmpty()
                    && !"Mark read".equalsIgnoreCase(value)
                    && !"Open notification".equalsIgnoreCase(value);
        }
        if (view instanceof ImageView) {
            return ((ImageView) view).getDrawable() != null;
        }
        if (!(view instanceof ViewGroup)) return view.isClickable();
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (hasMeaningfulVisibleContent(group.getChildAt(i))) return true;
        }
        return false;
    }

    private void toggleDetails(FrameLayout detailsPanel, TextView control,
                               String expectedPojoId) {
        boolean opening = detailsPanel.getVisibility() != View.VISIBLE;
        detailsPanel.animate().cancel();
        if (opening) {
            if (!populateDetails(detailsPanel, expectedPojoId)) return;
            detailsPanel.setAlpha(0f);
            detailsPanel.setVisibility(View.VISIBLE);
            control.setText("⌃");
            if (SmartAnimationEngine.isEnabled(mainActivity)) {
                detailsPanel.animate().alpha(1f)
                        .translationY(0f)
                        .setDuration(Math.max(90L, SmartAnimationEngine.duration(mainActivity) / 2))
                        .start();
            } else {
                detailsPanel.setAlpha(1f);
            }
        } else {
            control.setText("⌄");
            if (SmartAnimationEngine.isEnabled(mainActivity)) {
                detailsPanel.animate().alpha(0f)
                        .translationY(dp(6))
                        .setDuration(Math.max(80L, SmartAnimationEngine.duration(mainActivity) / 2))
                        .withEndAction(() -> clearDetailsPanel(detailsPanel)).start();
            } else {
                clearDetailsPanel(detailsPanel);
            }
        }
    }

    private boolean populateDetails(FrameLayout detailsPanel, String expectedPojoId) {
        if (detailsPanel.getChildCount() > 0) return true;
        int adapterPosition = resolveAdapterPosition(expectedPojoId);
        if (adapterPosition < 0) return false;

        Result<?> current = mainActivity.adapter.getItem(adapterPosition);
        if (current == null || !TextUtils.equals(expectedPojoId, current.getPojoId())) return false;

        View detailSource = mainActivity.adapter.getView(adapterPosition, null, detailsPanel);
        prepareSourceForDetails(detailSource);
        if (!hasMeaningfulVisibleContent(detailSource)) {
            releaseDiscardedSource(detailSource);
            return false;
        }
        detailsPanel.addView(detailSource, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return true;
    }

    private int resolveAdapterPosition(String expectedPojoId) {
        if (mainActivity.adapter == null || TextUtils.isEmpty(expectedPojoId)) return -1;
        for (int position = 0; position < mainActivity.adapter.getCount(); position++) {
            Result<?> current = mainActivity.adapter.getItem(position);
            if (current != null && TextUtils.equals(expectedPojoId, current.getPojoId())) {
                return position;
            }
        }
        return -1;
    }

    private void clearDetailsPanel(FrameLayout detailsPanel) {
        detailsPanel.removeAllViews();
        detailsPanel.setVisibility(View.GONE);
        detailsPanel.setAlpha(1f);
        detailsPanel.setTranslationY(0f);
    }

    private void releaseDiscardedSource(View source) {
        if (source == null) return;
        // AppResult's detached Mark-read control legitimately keeps its click listener. That
        // listener references the old notification row, so empty that row before discarding the
        // source to prevent it from pinning native notification content in memory.
        View notification = source.findViewById(R.id.item_notification_row);
        if (notification instanceof ViewGroup) {
            ((ViewGroup) notification).removeAllViews();
        }
        source.animate().cancel();
        source.setOnClickListener(null);
        source.setOnLongClickListener(null);
        source.setBackground(null);
        if (source instanceof ViewGroup) {
            ((ViewGroup) source).removeAllViews();
        } else if (source instanceof ImageView) {
            ((ImageView) source).setImageDrawable(null);
        } else if (source instanceof TextView) {
            ((TextView) source).setText(null);
        }
    }

    private void detachFromParent(View view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private CharSequence extractSubtitle(View source) {
        TextView tag = source.findViewById(R.id.item_app_tag);
        if (useful(tag)) return tag.getText();
        TextView shortcutTag = source.findViewById(R.id.item_shortcut_tag);
        if (useful(shortcutTag)) return shortcutTag.getText();
        return null;
    }

    private CharSequence extractLabel(View source) {
        TextView settingName = source.findViewById(R.id.item_setting_name);
        if (useful(settingName)) {
            TextView prefix = source.findViewById(R.id.item_setting_prefix);
            String prefixText = useful(prefix) ? prefix.getText().toString().trim() : "";
            String nameText = settingName.getText().toString().trim();
            if (!prefixText.isEmpty()) return prefixText + " " + nameText;
            return nameText;
        }

        TextView appName = source.findViewById(R.id.item_app_name);
        if (useful(appName)) return appName.getText();
        TextView primary = findPrimaryText(source);
        if (primary != null) return primary.getText();
        CharSequence description = source.getContentDescription();
        return TextUtils.isEmpty(description) ? "Item" : description;
    }

    private CharSequence cleanDisplayLabel(CharSequence raw) {
        if (raw == null) return "Item";
        String value = raw.toString().trim();
        if (value.regionMatches(true, 0, "Ice Box:", 0, "Ice Box:".length())) {
            value = value.substring("Ice Box:".length()).trim();
            while (value.startsWith("❄") || value.startsWith("️")) {
                value = value.substring(1).trim();
            }
        }
        return value.isEmpty() ? raw : value;
    }

    private String describeResult(View source) {
        TextView prefix = source.findViewById(R.id.item_setting_prefix);
        if (useful(prefix)) {
            String text = prefix.getText().toString().trim();
            if (text.endsWith(":")) text = text.substring(0, text.length() - 1).trim();
            if (!text.isEmpty()) return text + " shortcut";
        }
        if (source.findViewById(R.id.item_shortcut_icon) != null) return "App shortcut";
        if (source.findViewById(R.id.item_setting_icon) != null) return "System shortcut";
        return "Tap to open";
    }

    private TextView findPrimaryText(View view) {
        if (view instanceof TextView && !(view instanceof android.widget.Button)) {
            TextView text = (TextView) view;
            if (useful(text) && text.getId() != R.id.item_setting_prefix) return text;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findPrimaryText(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean useful(TextView text) {
        if (text == null || text.getVisibility() != View.VISIBLE || TextUtils.isEmpty(text.getText())) return false;
        int id = text.getId();
        if (id == R.id.item_notification_text || id == R.id.item_notification_read) return false;
        String value = text.getText().toString().trim();
        return !value.isEmpty()
                && !"Mark read".equalsIgnoreCase(value)
                && !"Open notification".equalsIgnoreCase(value)
                && !"Reply".equalsIgnoreCase(value);
    }

    private ImageView findIconView(View source) {
        ImageView setting = source.findViewById(R.id.item_setting_icon);
        if (setting != null) return setting;
        ImageView shortcut = source.findViewById(R.id.item_shortcut_icon);
        if (shortcut != null) return shortcut;
        ImageView app = source.findViewById(R.id.item_app_icon);
        if (app != null) return app;
        return findFirstVisibleImage(source);
    }

    private ImageView findFirstVisibleImage(View view) {
        if (view instanceof ImageView && view.getVisibility() == View.VISIBLE) {
            return (ImageView) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = findFirstVisibleImage(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private void pressAnimation(View card) {
        if (!SmartAnimationEngine.isEnabled(mainActivity)) return;
        card.animate().cancel();
        card.animate().scaleX(0.965f).scaleY(0.965f)
                .setDuration(Math.max(65L, SmartAnimationEngine.duration(mainActivity) / 3))
                .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f)
                        .setDuration(Math.max(70L, SmartAnimationEngine.duration(mainActivity) / 3))
                        .start()).start();
    }

    private GradientDrawable makePill(int accent) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(tone(accent, 0.68f, 205));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), tone(accent, 1.50f, 210));
        return bg;
    }

    private void styleCard(View card, int radiusDp, int accent) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        tone(accent, 1.35f, 244),
                        tone(accent, 0.96f, 238),
                        tone(accent, 0.56f, 248)
                });
        bg.setCornerRadius(dp(radiusDp));
        bg.setStroke(dp(2), tone(accent, 1.62f, 215));
        card.setBackground(bg);
        card.setClipToOutline(true);
    }

    private int accentFor(Result<?> result, Drawable drawable) {
        long key = result.getUniqueId();
        Integer cached = accentCache.get(key);
        if (cached != null) return cached;
        int accent = sampleAccent(drawable);
        accentCache.put(key, accent);
        return accent;
    }

    private int sampleAccent(Drawable drawable) {
        if (drawable == null) return Color.rgb(64, 84, 118);
        Bitmap bitmap = Bitmap.createBitmap(
                ACCENT_SAMPLE_SIZE, ACCENT_SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int l = drawable.getBounds().left;
        int t = drawable.getBounds().top;
        int r = drawable.getBounds().right;
        int b = drawable.getBounds().bottom;
        drawable.setBounds(0, 0, ACCENT_SAMPLE_SIZE, ACCENT_SAMPLE_SIZE);
        drawable.draw(canvas);
        drawable.setBounds(l, t, r, b);

        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < ACCENT_SAMPLE_SIZE; y++) {
            for (int x = 0; x < ACCENT_SAMPLE_SIZE; x++) {
                int c = bitmap.getPixel(x, y);
                if (Color.alpha(c) < 48) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[2] < 0.12f) continue;
                red += Color.red(c);
                green += Color.green(c);
                blue += Color.blue(c);
                count++;
            }
        }
        bitmap.recycle();
        if (count == 0) return Color.rgb(64, 84, 118);
        int color = Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0.34f, Math.min(0.84f, hsv[1]));
        hsv[2] = Math.max(0.42f, Math.min(0.82f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private int tone(int color, float multiplier, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * multiplier));
        return Color.HSVToColor(alpha, hsv);
    }

    private int prefInt(String key, int fallback, int min, int max) {
        // SharedPreferences.getAll() allocates/copies the complete preference map. createCardItem()
        // reads several dimensions per card, so getAll() multiplied allocations across the whole
        // History tree and triggered avoidable GC during rebuilds. Read only the requested key.
        int value = fallback;
        try {
            value = Math.round(Float.parseFloat(prefs.getString(key, Integer.toString(fallback))));
        } catch (ClassCastException | NumberFormatException ignored) {
            try { value = prefs.getInt(key, fallback); }
            catch (ClassCastException ignoredInt) {
                try { value = Math.round(prefs.getFloat(key, fallback)); }
                catch (ClassCastException ignoredFloat) {
                    try { value = (int) prefs.getLong(key, fallback); }
                    catch (ClassCastException ignoredLong) { value = fallback; }
                }
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}
