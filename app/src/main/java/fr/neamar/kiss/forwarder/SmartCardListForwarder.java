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
 * Optional vertical card history renderer. It deliberately reuses the adapter's real result view
 * inside each card so app/contact/feature/setting behaviour, usage metadata, notification buttons,
 * frozen-app handling, shortcuts and accessibility remain wired through the existing result code.
 */
final class SmartCardListForwarder extends Forwarder {
    static final String PREF_ENABLED = "smart-card-list-enabled";

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
        applyState(true);
    }

    void onResume() {
        applyState(false);
        if (isEnabled()) rebuild();
    }

    void onDataSetChanged() {
        if (isEnabled()) rebuild();
    }

    private boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, false)
                && HistoryDisplayForwarder.VERTICAL.equals(
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

        final int count = mainActivity.adapter.getCount();
        for (int position = 0; position < count; position++) {
            Result<?> result = mainActivity.adapter.getItem(position);
            View source = mainActivity.adapter.getView(position, null, column);
            View item = createCardItem(source, result, position);
            column.addView(item);
            if (position >= Math.max(0, count - 16)) {
                animateIn(item, position - Math.max(0, count - 16));
            }
        }

        // Keep the newest/priority end visible, matching the launch-history behaviour of the
        // other custom history renderers without changing DataHandler ordering.
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private View createCardItem(View source, Result<?> result, int adapterPosition) {
        int heightPercent = prefInt("smart-list-card-height-percent", 100, 70, 170);
        int iconPercent = prefInt("smart-list-card-icon-percent", 100, 60, 180);
        int radiusDp = prefInt("smart-list-card-radius-dp", 22, 6, 40);
        int elevationDp = prefInt("smart-list-card-elevation-dp", 9, 0, 24);
        int namePercent = prefInt("smart-list-card-name-percent", 100, 70, 170);
        int spacingDp = prefInt("smart-list-card-spacing-dp", 12, 4, 36);

        LinearLayout wrapper = new LinearLayout(mainActivity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);
        LinearLayout.LayoutParams wrapperLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapperLp.setMargins(dp(4), dp(spacingDp / 2), dp(4), dp(spacingDp / 2));
        wrapper.setLayoutParams(wrapperLp);

        FrameLayout card = new FrameLayout(mainActivity);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setPadding(dp(8), dp(7), dp(8), dp(7));
        card.setElevation(dp(elevationDp));
        int cardHeight = dp(132) * heightPercent / 100;
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(92), cardHeight));
        cardLp.setMargins(dp(4), 0, dp(4), 0);
        wrapper.addView(card, cardLp);

        Drawable iconDrawable = resolveIcon(source, result);
        int accent = accentFor(result, iconDrawable);
        styleCard(card, radiusDp, accent);

        // Keep the real result view alive inside the card. This is what preserves notification
        // actions, app usage metadata, contact/shortcut semantics and feature/settings content.
        TextView primary = findPrimaryText(source);
        CharSequence label = primary != null ? primary.getText() : extractLabel(source);
        if (primary != null) primary.setVisibility(View.GONE);

        FrameLayout.LayoutParams sourceLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        sourceLp.setMargins(dp(6), dp(5), dp(6), dp(5));
        card.addView(source, sourceLp);

        // Add a stable foreground icon/profile image. If the source provides contact or app
        // artwork, resolveIcon() keeps that actual drawable instead of inventing content.
        if (iconDrawable != null) {
            ImageView icon = new ImageView(mainActivity);
            icon.setImageDrawable(iconDrawable);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int iconSize = dp(54) * iconPercent / 100;
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                    Math.min(iconSize, dp(92)), Math.min(iconSize, dp(92)),
                    Gravity.TOP | Gravity.END);
            iconLp.topMargin = dp(8);
            iconLp.rightMargin = dp(10);
            card.addView(icon, iconLp);
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

        // A small details affordance expands/collapses the actual result content without opening
        // another screen. It does not replace child notification buttons or their click wiring.
        TextView details = new TextView(mainActivity);
        details.setText("⋯");
        details.setTextColor(Color.WHITE);
        details.setTextSize(22f);
        details.setGravity(Gravity.CENTER);
        details.setContentDescription("Expand card details");
        details.setBackground(makePill(accent));
        FrameLayout.LayoutParams detailsLp = new FrameLayout.LayoutParams(
                dp(38), dp(32), Gravity.BOTTOM | Gravity.END);
        detailsLp.rightMargin = dp(8);
        detailsLp.bottomMargin = dp(7);
        card.addView(details, detailsLp);
        details.setOnClickListener(v -> toggleExpanded(card, source, cardHeight));

        return wrapper;
    }

    private void toggleExpanded(FrameLayout card, View source, int collapsedHeight) {
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        int expanded = Math.max(collapsedHeight + dp(80), dp(220));
        int target = lp.height >= expanded - dp(2) ? collapsedHeight : expanded;
        if (!SmartAnimationEngine.isEnabled(mainActivity)) {
            lp.height = target;
            card.setLayoutParams(lp);
            return;
        }
        int start = card.getHeight();
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(start, target);
        animator.setDuration(SmartAnimationEngine.duration(mainActivity));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            ViewGroup.LayoutParams params = card.getLayoutParams();
            params.height = (int) a.getAnimatedValue();
            card.setLayoutParams(params);
        });
        animator.start();
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

    private void styleCard(FrameLayout card, int radiusDp, int accent) {
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
        return !value.isEmpty() && !"Mark read".equalsIgnoreCase(value)
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

        long red = 0, green = 0, blue = 0;
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
            try { value = Math.round(Float.parseFloat((String) raw)); }
            catch (NumberFormatException ignored) { value = fallback; }
        }
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}
