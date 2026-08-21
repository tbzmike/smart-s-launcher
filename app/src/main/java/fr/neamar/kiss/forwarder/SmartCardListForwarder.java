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
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.ui.SmartAnimationEngine;

/**
 * Vertical Smart Card renderer. The card uses a deliberate icon/content/action layout while still
 * reusing the adapter's real notification row and expanded result view so existing click handlers,
 * notification actions, contacts, shortcuts, frozen-app behaviour and accessibility stay intact.
 */
final class SmartCardListForwarder extends Forwarder {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String LEGACY_PREF_ENABLED = "smart-card-list-enabled";
    private static final int ACCENT_SAMPLE_SIZE = 10;

    private final Map<Long, Integer> accentCache = new HashMap<>();
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
        column.removeAllViews();
        accentCache.clear();

        int count = mainActivity.adapter.getCount();
        for (int position = 0; position < count; position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            View source = mainActivity.adapter.getView(position, null, column);
            View item = createCardItem(source, result, position);
            column.addView(item);
            if (position >= Math.max(0, count - 16)) {
                animateIn(item, position - Math.max(0, count - 16));
            }
        }
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private View createCardItem(View source, Result<?> result, int adapterPosition) {
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

        Drawable iconDrawable = resolveIcon(source, result);
        int accent = accentFor(result, iconDrawable);
        styleCard(card, radiusDp, accent);

        CharSequence label = extractLabel(source);
        CharSequence subtitle = extractSubtitle(source);
        View notificationRow = source.findViewById(R.id.item_notification_row);
        boolean hasNotification = notificationRow != null
                && notificationRow.getVisibility() == View.VISIBLE;

        LinearLayout mainRow = new LinearLayout(mainActivity);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        mainRow.setBaselineAligned(false);
        card.addView(mainRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView icon = new ImageView(mainActivity);
        icon.setImageDrawable(iconDrawable);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int iconSize = Math.min(dp(84), dp(62) * iconPercent / 100);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = dp(13);
        mainRow.addView(icon, iconLp);

        LinearLayout center = new LinearLayout(mainActivity);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_VERTICAL);
        mainRow.addView(center, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        AutoMarqueeTextView cardTitle = new AutoMarqueeTextView(mainActivity);
        cardTitle.setText(label);
        cardTitle.setTextColor(Color.WHITE);
        cardTitle.setTextSize(16f);
        cardTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cardTitle.setShadowLayer(dp(2), 0f, dp(1), Color.BLACK);
        center.addView(cardTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        if (!TextUtils.isEmpty(subtitle)) {
            AutoMarqueeTextView meta = new AutoMarqueeTextView(mainActivity);
            meta.setText(subtitle);
            meta.setTextColor(Color.argb(220, 245, 245, 245));
            meta.setTextSize(13f);
            meta.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            center.addView(meta, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));
        }

        if (hasNotification) {
            detachFromParent(notificationRow);
            normalizeNotificationRow(notificationRow);
            LinearLayout.LayoutParams notificationLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            notificationLp.topMargin = dp(4);
            center.addView(notificationRow, notificationLp);
        }

        // Preserve every less-common result action (contact call/message, shortcut controls,
        // feature/settings extras, etc.) in a clean collapsed details area instead of layering the
        // complete legacy row over the card's main presentation.
        prepareSourceForDetails(source, hasNotification ? notificationRow : null);
        FrameLayout detailsPanel = new FrameLayout(mainActivity);
        detailsPanel.setVisibility(View.GONE);
        detailsPanel.setPadding(dp(4), dp(7), dp(4), dp(2));
        detailsPanel.addView(source, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

        View.OnClickListener launch = v -> {
            pressAnimation(card);
            mainActivity.adapter.onClick(adapterPosition, card);
        };
        View.OnLongClickListener longPress = v -> {
            mainActivity.adapter.onLongClick(adapterPosition, card);
            return true;
        };
        card.setOnClickListener(launch);
        name.setOnClickListener(launch);
        card.setOnLongClickListener(longPress);
        name.setOnLongClickListener(longPress);
        card.setClickable(true);
        name.setClickable(true);
        return wrapper;
    }

    private void normalizeNotificationRow(View row) {
        row.setAlpha(1f);
        row.setScaleX(1f);
        row.setScaleY(1f);
        TextView text = row.findViewById(R.id.item_notification_text);
        if (text != null) {
            text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setTextColor(Color.WHITE);
        }
        View read = row.findViewById(R.id.item_notification_read);
        if (read != null) {
            ViewGroup.LayoutParams raw = read.getLayoutParams();
            if (raw != null) raw.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
    }

    private void prepareSourceForDetails(View source, View movedNotificationRow) {
        View appIcon = source.findViewById(R.id.item_app_icon);
        View appName = source.findViewById(R.id.item_app_name);
        View appTag = source.findViewById(R.id.item_app_tag);
        if (appIcon != null) appIcon.setVisibility(View.GONE);
        if (appName != null) appName.setVisibility(View.GONE);
        if (appTag != null) appTag.setVisibility(View.GONE);
        if (movedNotificationRow != null) movedNotificationRow.setVisibility(View.VISIBLE);
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
                        .setDuration(Math.max(90L, SmartAnimationEngine.duration(mainActivity) / 2))
                        .start();
            } else {
                detailsPanel.setAlpha(1f);
            }
        } else {
            control.setText("⌄");
            if (SmartAnimationEngine.isEnabled(mainActivity)) {
                detailsPanel.animate().alpha(0f)
                        .setDuration(Math.max(80L, SmartAnimationEngine.duration(mainActivity) / 2))
                        .withEndAction(() -> {
                            detailsPanel.setVisibility(View.GONE);
                            detailsPanel.setAlpha(1f);
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
        if (tag != null && tag.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(tag.getText())) {
            return tag.getText();
        }
        return null;
    }

    private void pressAnimation(View card) {
        if (!SmartAnimationEngine.isEnabled(mainActivity)) return;
        card.animate().cancel();
        card.animate().scaleX(0.975f).scaleY(0.975f)
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
        if (!SmartAnimationEngine.isEnabled(mainActivity)) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(dp(12));
        view.setScaleX(0.98f);
        view.setScaleY(0.98f);
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(Math.min(120L, index * 14L))
                .setDuration(SmartAnimationEngine.duration(mainActivity))
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private CharSequence extractLabel(View source) {
        TextView appName = source.findViewById(R.id.item_app_name);
        if (useful(appName)) return appName.getText();
        TextView primary = findPrimaryText(source);
        if (primary != null) return primary.getText();
        CharSequence description = source.getContentDescription();
        return TextUtils.isEmpty(description) ? "Item" : description;
    }

    private TextView findPrimaryText(View view) {
        if (view instanceof TextView && !(view instanceof android.widget.Button)) {
            TextView text = (TextView) view;
            if (useful(text)) return text;
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

    private Drawable resolveIcon(View source, Result<?> result) {
        ImageView app = source.findViewById(R.id.item_app_icon);
        Drawable drawable = app == null ? null : app.getDrawable();
        if (drawable == null) drawable = findImage(source);
        if (drawable == null) drawable = result.getDrawable(mainActivity);
        if (drawable == null) drawable = mainActivity.getPackageManager().getDefaultActivityIcon();
        return drawable;
    }

    private Drawable findImage(View view) {
        if (view instanceof ImageView && view.getVisibility() == View.VISIBLE) {
            Drawable d = ((ImageView) view).getDrawable();
            if (d != null) return d;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            Drawable d = findImage(group.getChildAt(i));
            if (d != null) return d;
        }
        return null;
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
