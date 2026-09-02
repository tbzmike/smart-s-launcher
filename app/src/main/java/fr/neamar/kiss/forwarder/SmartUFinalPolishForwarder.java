package fr.neamar.kiss.forwarder;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.utils.Log;

/** Final Square-U-only visuals, accessibility and performance tuning for 3.28.99+. */
final class SmartUFinalPolishForwarder {
    private static final String TAG = SmartUFinalPolishForwarder.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String PREF_STYLE = "smart-u-visual-style";
    private static final String PREF_PROFILE = "smart-u-motion-profile";
    private static final String TAG_CONTROLS = "smart-u-final-polish-controls";

    private static final String[] STYLES = {"glass", "acrylic", "deep", "minimal", "dynamic"};
    private static final String[] PROFILES = {"off", "efficient", "smooth", "cinematic"};

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final android.content.SharedPreferences prefs;

    private FrameLayout squareRoot;
    private ViewGroup squareTrack;
    private ViewGroup observedTrack;
    private ScrollView notificationScroller;
    private LinearLayout notificationCenter;
    private LinearLayout controls;
    private TextView styleChip;
    private TextView motionChip;
    private boolean refreshPosted;
    private String appliedStyle;
    private String appliedProfile;
    private int styledChildCount = -1;
    private int accessibleChildCount = -1;

    private final View.OnLayoutChangeListener layoutChangeListener = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (isUStyle() && ((r - l) != (or - ol) || (b - t) != (ob - ot))) refreshSoon();
    };

    SmartUFinalPolishForwarder(MainActivity activity,
                               HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() { resolveViews(); installObserver(); refreshSoon(); }
    void onResume() { resolveViews(); installObserver(); refreshSoon(); }
    void onPause() { releaseTransientLayers(); }
    void onDataSetChanged() {
        if (!isUStyle()) { removeEnhancements(); return; }
        resolveViews(); installObserver(); refreshSoon();
    }
    void onConfigurationChanged() {
        resolveViews();
        installObserver();
        appliedStyle = null;
        appliedProfile = null;
        styledChildCount = -1;
        accessibleChildCount = -1;
        if (isUStyle()) refreshSoon();
    }
    void onDestroy() {
        if (observedTrack != null) observedTrack.removeOnLayoutChangeListener(layoutChangeListener);
        observedTrack = null;
        removeEnhancements();
        squareRoot = null;
        squareTrack = null;
        notificationScroller = null;
        notificationCenter = null;
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void resolveViews() {
        ViewGroup previousTrack = squareTrack;
        squareRoot = readField("squareRoot", FrameLayout.class);
        squareTrack = readField("squareTrack", ViewGroup.class);
        notificationScroller = readField("notificationScroller", ScrollView.class);
        notificationCenter = readField("notificationCenter", LinearLayout.class);
        if (previousTrack != squareTrack) {
            appliedStyle = null;
            appliedProfile = null;
            styledChildCount = -1;
            accessibleChildCount = -1;
        }
    }

    private <T> T readField(String name, Class<T> type) {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve Smart-U polish field: " + name, e);
            return null;
        }
    }

    private void installObserver() {
        if (squareTrack == null || observedTrack == squareTrack) return;
        if (observedTrack != null) observedTrack.removeOnLayoutChangeListener(layoutChangeListener);
        squareTrack.addOnLayoutChangeListener(layoutChangeListener);
        observedTrack = squareTrack;
    }

    private void refreshSoon() {
        if (squareTrack == null || refreshPosted) return;
        refreshPosted = true;
        squareTrack.post(() -> {
            refreshPosted = false;
            if (isUStyle()) refreshNow();
        });
    }

    private void refreshNow() {
        if (!isUStyle()) { removeEnhancements(); return; }
        resolveViews();
        installObserver();
        if (squareTrack == null) return;
        ensureControls();
        applyResponsiveTuning();
        applyMotionProfile();
        applyMaterialStyle();
        applyLightingPolish();
        applyAccessibility();
        updateLabels();
    }

    private void ensureControls() {
        if (notificationCenter == null) return;
        View existing = notificationCenter.findViewWithTag(TAG_CONTROLS);
        if (existing instanceof LinearLayout) {
            controls = (LinearLayout) existing;
            if (controls.getChildCount() > 0 && controls.getChildAt(0) instanceof TextView) styleChip = (TextView) controls.getChildAt(0);
            if (controls.getChildCount() > 1 && controls.getChildAt(1) instanceof TextView) motionChip = (TextView) controls.getChildAt(1);
            return;
        }
        controls = new LinearLayout(activity);
        controls.setTag(TAG_CONTROLS);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(3), dp(3), dp(3), dp(6));
        styleChip = createChip();
        motionChip = createChip();
        styleChip.setOnClickListener(v -> { cycle(PREF_STYLE, STYLES, "glass"); appliedStyle = null; refreshNow(); });
        motionChip.setOnClickListener(v -> { cycle(PREF_PROFILE, PROFILES, "smooth"); appliedProfile = null; refreshNow(); });
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        chipParams.setMargins(dp(3), 0, dp(3), 0);
        controls.addView(styleChip, chipParams);
        controls.addView(motionChip, chipParams);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setMargins(dp(3), 0, dp(3), dp(7));
        notificationCenter.addView(controls, row);
    }

    private TextView createChip() {
        AutoMarqueeTextView v = new AutoMarqueeTextView(activity);
        v.setTextColor(Color.WHITE);
        v.setTextSize(12f);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        v.setFocusable(true);
        v.setMinWidth(dp(48));
        v.setMinHeight(dp(48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(218, 28, 43, 70));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(210, 116, 174, 255));
        v.setBackground(bg);
        return v;
    }

    private void applyResponsiveTuning() {
        if (notificationScroller == null) return;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        float density = activity.getResources().getDisplayMetrics().density;
        boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean compact = width / density < 360f;
        ViewGroup.LayoutParams raw = notificationScroller.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            int targetWidth = lp.width;
            int targetHeight = lp.height;
            if (landscape) {
                targetWidth = Math.min(dp(540), Math.max(dp(250), Math.round(width * 0.47f)));
                targetHeight = Math.min(dp(350), Math.max(dp(180), Math.round(height * 0.66f)));
            } else if (compact) {
                targetWidth = Math.max(dp(214), width - dp(22));
                targetHeight = Math.min(dp(320), Math.max(dp(180), Math.round(height * 0.41f)));
            }
            if (lp.width != targetWidth || lp.height != targetHeight || lp.gravity != Gravity.CENTER) {
                lp.width = targetWidth;
                lp.height = targetHeight;
                lp.gravity = Gravity.CENTER;
                notificationScroller.setLayoutParams(lp);
            }
        }
        float targetTextSp = compact ? 10.5f : 12f;
        if (styleChip != null && Math.abs(styleChip.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity - targetTextSp) > 0.1f) styleChip.setTextSize(targetTextSp);
        if (motionChip != null && Math.abs(motionChip.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity - targetTextSp) > 0.1f) motionChip.setTextSize(targetTextSp);
    }

    private void applyMotionProfile() {
        String profile = prefs.getString(PREF_PROFILE, "smooth");
        if (squareRoot == null || squareTrack == null) return;
        if (profile.equals(appliedProfile)) return;
        appliedProfile = profile;
        int layer = "cinematic".equals(profile) ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (squareRoot.getLayerType() != layer) squareRoot.setLayerType(layer, null);
        if (squareTrack.getLayerType() != layer) squareTrack.setLayerType(layer, null);
        if ("off".equals(profile)) {
            squareRoot.animate().cancel();
            squareTrack.animate().cancel();
        }
        if (controls != null) {
            float alpha = "off".equals(profile) ? 0.86f : 1f;
            if (controls.getAlpha() != alpha) controls.setAlpha(alpha);
        }
    }

    private void releaseTransientLayers() {
        if (squareRoot != null && squareRoot.getLayerType() != View.LAYER_TYPE_NONE) squareRoot.setLayerType(View.LAYER_TYPE_NONE, null);
        if (squareTrack != null && squareTrack.getLayerType() != View.LAYER_TYPE_NONE) squareTrack.setLayerType(View.LAYER_TYPE_NONE, null);
        appliedProfile = null;
    }

    private void applyMaterialStyle() {
        if (squareTrack == null) return;
        String style = prefs.getString(PREF_STYLE, "glass");
        int childCount = squareTrack.getChildCount();
        if (style.equals(appliedStyle) && childCount == styledChildCount) return;
        appliedStyle = style;
        styledChildCount = childCount;
        for (int i = 0; i < childCount; i++) {
            View child = squareTrack.getChildAt(i);
            if (!(child instanceof FrameLayout)) continue;
            FrameLayout card = (FrameLayout) child;
            int accent = "dynamic".equals(style) ? accentFor(label(card)) : 0;
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colorsFor(style, accent));
            bg.setCornerRadius(dp("minimal".equals(style) ? 22 : 18));
            int strokeColor = "dynamic".equals(style) ? lighten(accent, 0.70f, 1.25f, 235)
                    : ("deep".equals(style) ? Color.argb(230, 88, 130, 218) : Color.argb(205, 210, 228, 255));
            bg.setStroke(dp("deep".equals(style) ? 2 : 1), strokeColor);
            card.setBackground(bg);
            float targetElevation = dp("deep".equals(style) ? 10 : ("minimal".equals(style) ? 2 : 6));
            if (card.getElevation() != targetElevation) card.setElevation(targetElevation);
        }
        if (notificationScroller != null) {
            GradientDrawable centerBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.argb(228, 12, 18, 31), Color.argb(218, 24, 34, 58), Color.argb(228, 8, 11, 19)});
            centerBg.setCornerRadius(dp(22));
            centerBg.setStroke(dp(2), Color.argb(205, 105, 160, 255));
            notificationScroller.setBackground(centerBg);
        }
    }

    private int[] colorsFor(String style, int accent) {
        if ("acrylic".equals(style)) return new int[]{Color.argb(235, 43, 50, 66), Color.argb(225, 27, 34, 48), Color.argb(235, 18, 22, 31)};
        if ("deep".equals(style)) return new int[]{Color.argb(252, 18, 22, 31), Color.argb(250, 8, 11, 18), Color.argb(255, 2, 4, 8)};
        if ("minimal".equals(style)) return new int[]{Color.argb(80, 18, 23, 34), Color.argb(56, 8, 11, 18), Color.argb(36, 0, 0, 0)};
        if ("dynamic".equals(style)) return new int[]{lighten(accent, 0.70f, 1.28f, 205), withAlpha(accent, 185), darken(accent, 0.64f, 220)};
        return new int[]{Color.argb(170, 58, 74, 104), Color.argb(150, 28, 38, 57), Color.argb(180, 10, 15, 25)};
    }

    private void applyLightingPolish() {
        if (squareTrack == null) return;
        float tx = squareTrack.getWidth() / 2f;
        float ty = squareTrack.getHeight();
        String profile = prefs.getString(PREF_PROFILE, "smooth");
        String style = prefs.getString(PREF_STYLE, "glass");
        float alphaFloor = "minimal".equals(style) ? 0.70f : 0.58f;
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (card.getVisibility() != View.VISIBLE) continue;
            float cx = card.getX() + card.getWidth() / 2f;
            float cy = card.getY() + card.getHeight() / 2f;
            float dx = Math.abs(cx - tx) / Math.max(1f, squareTrack.getWidth() * 0.60f);
            float dy = Math.abs(cy - ty) / Math.max(1f, squareTrack.getHeight() * 0.75f);
            float distance = Math.min(1f, dx * 0.70f + dy * 0.52f);
            float focus = 1f - distance;
            float targetAlpha = Math.max(alphaFloor, Math.min(1f, alphaFloor + 0.42f * focus));
            if (Math.abs(card.getAlpha() - targetAlpha) > 0.002f) card.setAlpha(targetAlpha);
            float targetZ = Math.max(card.getTranslationZ(), dp(3) + dp(21) * focus);
            if (Math.abs(card.getTranslationZ() - targetZ) > 0.25f) card.setTranslationZ(targetZ);
            if (!"off".equals(profile)) {
                float targetRotation = card.getRotationY() * (0.88f + 0.12f * focus);
                if (Math.abs(card.getRotationY() - targetRotation) > 0.02f) card.setRotationY(targetRotation);
            }
        }
    }

    private void applyAccessibility() {
        if (styleChip != null) {
            styleChip.setContentDescription("Smart U visual style. Current " + prefs.getString(PREF_STYLE, "glass") + ". Tap to change.");
            motionChip.setContentDescription("Smart U motion profile. Current " + prefs.getString(PREF_PROFILE, "smooth") + ". Tap to change.");
        }
        if (squareTrack == null) return;
        int childCount = squareTrack.getChildCount();
        if (childCount == accessibleChildCount) return;
        accessibleChildCount = childCount;
        int min = dp(48);
        for (int i = 0; i < childCount; i++) {
            View card = squareTrack.getChildAt(i);
            String name = label(card);
            if (!TextUtils.isEmpty(name)) card.setContentDescription(name + ". Tap to open. Hold or swipe up for actions. Swipe down for details.");
            if (card.getMinimumWidth() != min) card.setMinimumWidth(min);
            if (card.getMinimumHeight() != min) card.setMinimumHeight(min);
        }
    }

    private void updateLabels() {
        if (styleChip == null || motionChip == null) return;
        String style = "Style · " + title(prefs.getString(PREF_STYLE, "glass"));
        String motion = "Motion · " + title(prefs.getString(PREF_PROFILE, "smooth"));
        if (!style.contentEquals(styleChip.getText())) styleChip.setText(style);
        if (!motion.contentEquals(motionChip.getText())) motionChip.setText(motion);
    }

    private String label(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (!TextUtils.isEmpty(text) && !"•".contentEquals(text)) return text.toString().trim();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                String found = label(group.getChildAt(i));
                if (!TextUtils.isEmpty(found)) return found;
            }
        }
        return "";
    }

    private int accentFor(String label) {
        int hash = TextUtils.isEmpty(label) ? 0 : label.hashCode();
        float hue = Math.abs(hash % 360);
        return Color.HSVToColor(new float[]{hue, 0.50f, 0.72f});
    }

    private int lighten(int color, float saturationMultiplier, float valueMultiplier, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0f, Math.min(1f, hsv[1] * saturationMultiplier));
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * valueMultiplier));
        return Color.HSVToColor(alpha, hsv);
    }

    private int darken(int color, float valueMultiplier, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * valueMultiplier));
        return Color.HSVToColor(alpha, hsv);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void cycle(String key, String[] values, String fallback) {
        String current = prefs.getString(key, fallback);
        int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) { index = i; break; }
        prefs.edit().putString(key, values[(index + 1) % values.length]).apply();
    }

    private String title(String value) {
        if (TextUtils.isEmpty(value)) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void removeEnhancements() {
        releaseTransientLayers();
        if (controls != null && controls.getParent() instanceof ViewGroup) ((ViewGroup) controls.getParent()).removeView(controls);
        controls = null;
        styleChip = null;
        motionChip = null;
        appliedStyle = null;
        styledChildCount = -1;
        accessibleChildCount = -1;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
