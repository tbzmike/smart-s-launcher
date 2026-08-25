package fr.neamar.kiss.forwarder;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.db.NotificationHistoryRecord;
import fr.neamar.kiss.db.SmartStateStore;
import fr.neamar.kiss.pojo.CommunicationPojo;
import fr.neamar.kiss.result.AppResult;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.ui.SmartAnimationEngine;

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

    SmartCardListForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        if (!(mainActivity.listContainer instanceof FrameLayout)) return;
        container = (FrameLayout) mainActivity.listContainer;
        edgeEffect = mainActivity.findViewById(R.id.listEdgeEffect);

        scroller = new ScrollView(mainActivity);
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
        applyState(false);
        if (isEnabled()) rebuild();
    }

    void onDataSetChanged() {
        if (isEnabled()) rebuild();
    }

    void onDestroy() {
        accentCache.clear();
        container = null;
        scroller = null;
        column = null;
        edgeEffect = null;
    }

    ScrollView getScroller() {
        return scroller;
    }

    LinearLayout getColumn() {
        return column;
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

    private void applyState(boolean force) {
        if (scroller == null) return;
        boolean enabled = isEnabled();
        if (enabled) {
            mainActivity.list.setVisibility(View.GONE);
            if (edgeEffect != null) edgeEffect.setVisibility(View.GONE);
            scroller.setVisibility(View.VISIBLE);
            if (force) rebuild();
        } else {
            scroller.setVisibility(View.GONE);
            if (HistoryDisplayForwarder.VERTICAL.equals(
                    prefs.getString(HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL))) {
                mainActivity.list.setVisibility(View.VISIBLE);
                if (edgeEffect != null) edgeEffect.setVisibility(View.VISIBLE);
            }
        }
    }

    private void rebuild() {
        if (column == null || mainActivity.adapter == null) return;
        Map<String, NotificationHistoryRecord> latestNotifications =
                prefs.getBoolean("enable-notification-history", false)
                        ? SmartStateStore.queryLatestNotificationsByPackage(mainActivity)
                        : Collections.emptyMap();
        column.removeAllViews();

        int count = mainActivity.adapter.getCount();
        for (int position = 0; position < count; position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            View source = mainActivity.adapter.getView(position, null, column);
            View item = createCardItem(source, result, position, latestNotifications);
            column.addView(item);
        }

        scroller.post(() -> {
            int childCount = column.getChildCount();
            int first = Math.max(0, childCount - 16);
            int visualIndex = 0;
            for (int i = first; i < childCount; i++) {
                View child = column.getChildAt(i);
                animateIn(child, visualIndex++);
            }
        });
    }

    private View createCardItem(View source, Result<?> result, int adapterPosition,
                                Map<String, NotificationHistoryRecord> latestNotifications) {
        int heightPercent = prefInt("smart-list-card-height-percent", 100, 70, 170);
        int iconPercent = prefInt("smart-list-card-icon-percent", 100, 60, 180);
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
        if (iconDrawable == null) iconDrawable = result.getDrawable(mainActivity);
        int accent = accentFor(result, iconDrawable);
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
        int iconSize = Math.min(dp(88), dp(66) * iconPercent / 100);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = dp(14);
        mainRow.addView(iconView, iconLp);

        LinearLayout center = new LinearLayout(mainActivity);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_VERTICAL);
        mainRow.addView(center, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        AutoMarqueeTextView cardTitle = new AutoMarqueeTextView(mainActivity);
        cardTitle.setText(label);
        cardTitle.setTextColor(Color.WHITE);
        cardTitle.setTextSize(16f * namePercent / 100f);
        cardTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cardTitle.setShadowLayer(dp(2), 0f, dp(1), Color.argb(180, 0, 0, 0));
        center.addView(cardTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(31) * Math.max(90, namePercent) / 100));

        if (!TextUtils.isEmpty(subtitle)) {
            AutoMarqueeTextView meta = new AutoMarqueeTextView(mainActivity);
            meta.setText(subtitle);
            meta.setTextColor(Color.argb(220, 250, 250, 250));
            meta.setTextSize(13f);
            meta.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            meta.setShadowLayer(dp(1), 0f, dp(1), Color.argb(160, 0, 0, 0));
            center.addView(meta, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));
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
            AutoMarqueeTextView lastMessage = new AutoMarqueeTextView(mainActivity);
            lastMessage.setText(latestMessage);
            lastMessage.setTextColor(Color.WHITE);
            lastMessage.setTextSize(13f);
            lastMessage.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            lastMessage.setPadding(0, dp(2), 0, dp(2));
            lastMessage.setShadowLayer(dp(1), 0f, dp(1), Color.argb(150, 0, 0, 0));
            center.addView(lastMessage, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(31)));
            messageView = lastMessage;
        } else if (TextUtils.isEmpty(subtitle)) {
            AutoMarqueeTextView context = new AutoMarqueeTextView(mainActivity);
            context.setText(describeResult(source));
            context.setTextColor(Color.argb(175, 255, 255, 255));
            context.setTextSize(12f);
            context.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            center.addView(context, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));
        }

        if (call != null && call.kind == CommunicationPojo.Kind.CALL
                && hasDistinctCallerName(call)) {
            AutoMarqueeTextView callerName = new AutoMarqueeTextView(mainActivity);
            callerName.setText(call.displayName);
            callerName.setTextColor(Color.WHITE);
            callerName.setTextSize(15f * namePercent / 100f);
            callerName.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            callerName.setPadding(0, dp(3), 0, dp(1));
            callerName.setShadowLayer(dp(2), 0f, dp(1), Color.argb(180, 0, 0, 0));
            callerName.setContentDescription("Caller: " + call.displayName);
            center.addView(callerName, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(31) * Math.max(90, namePercent) / 100));
        }

        prepareSourceForDetails(source);
        FrameLayout detailsPanel = new FrameLayout(mainActivity);
        detailsPanel.setVisibility(View.GONE);
        detailsPanel.setPadding(dp(4), dp(7), dp(4), dp(2));
        detailsPanel.addView(source, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean hasDetails = hasMeaningfulVisibleContent(source);
        if (hasDetails) {
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
            details.setOnClickListener(v -> toggleDetails(detailsPanel, details));
        }

        AutoMarqueeTextView name = new AutoMarqueeTextView(mainActivity);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15f * namePercent / 100f);
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(8), dp(5), dp(8), dp(3));
        name.setShadowLayer(dp(2), 0f, dp(1), Color.BLACK);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34) * Math.max(90, namePercent) / 100);
        nameLp.setMargins(dp(10), dp(3), dp(10), 0);
        wrapper.addView(name, nameLp);

        final TextView expandableMessage = messageView;
        final boolean[] messageExpanded = {false};
        View.OnClickListener launchOrExpand = v -> {
            if (expandableMessage != null && !messageExpanded[0] && messageNeedsExpansion(expandableMessage)) {
                pressAnimation(card);
                expandMessage(expandableMessage);
                messageExpanded[0] = true;
                return;
            }
            pressAnimation(card);
            mainActivity.adapter.onClick(adapterPosition, card);
        };
        View.OnLongClickListener longPress = v -> {
            mainActivity.adapter.onLongClick(adapterPosition, card);
            return true;
        };
        card.setOnClickListener(launchOrExpand);
        cardTitle.setOnClickListener(launchOrExpand);
        name.setOnClickListener(launchOrExpand);
        card.setOnLongClickListener(longPress);
        cardTitle.setOnLongClickListener(longPress);
        name.setOnLongClickListener(longPress);
        card.setClickable(true);
        cardTitle.setClickable(true);
        name.setClickable(true);
        return wrapper;
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

    private void configureCollapsedMessage(TextView text) {
        text.setSingleLine(true);
        text.setMaxLines(1);
        text.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        text.setMarqueeRepeatLimit(-1);
        text.setHorizontallyScrolling(true);
        text.setSelected(true);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13f);
        text.setGravity(Gravity.START);
        text.setPadding(0, dp(2), 0, dp(2));
    }

    private boolean messageNeedsExpansion(TextView text) {
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

    private void toggleDetails(View detailsPanel, TextView control) {
        boolean opening = detailsPanel.getVisibility() != View.VISIBLE;
        detailsPanel.animate().cancel();
        if (opening) {
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
                        .withEndAction(() -> {
                            detailsPanel.setVisibility(View.GONE);
                            detailsPanel.setAlpha(1f);
                            detailsPanel.setTranslationY(0f);
                        }).start();
            } else {
                detailsPanel.setVisibility(View.GONE);
            }
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

    private void animateIn(View view, int index) {
        SmartAnimationEngine.animateTileListItem(view, index);
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
        Object raw = prefs.getAll().get(key);
        int value = fallback;
        if (raw instanceof Number) value = Math.round(((Number) raw).floatValue());
        else if (raw instanceof String) {
            try {
                value = Math.round(Float.parseFloat((String) raw));
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}
