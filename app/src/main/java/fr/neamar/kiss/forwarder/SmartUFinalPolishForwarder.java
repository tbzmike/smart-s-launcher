package fr.neamar.kiss.forwarder;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.neamar.kiss.MainActivity;

/** Final U-style-only visual, ergonomics and performance controller. */
final class SmartUFinalPolishForwarder extends Forwarder {
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String PREF_STYLE = "smart-u-visual-style";
    private static final String PREF_PROFILE = "smart-u-performance-profile";
    private static final String PREF_HAND = "smart-u-hand-position";
    private static final String TAG_CONTROLS = "smart-u-final-controls";

    private static final String[] STYLES = {"glass", "acrylic", "deep", "minimal", "dynamic"};
    private static final String[] PROFILES = {"off", "efficient", "smooth", "cinematic"};
    private static final String[] HANDS = {"left", "center", "right"};

    private FrameLayout squareRoot;
    private ViewGroup squareTrack;
    private ScrollView centerScroller;
    private LinearLayout center;
    private LinearLayout controls;
    private TextView styleChip;
    private TextView profileChip;
    private TextView handChip;
    private boolean listenerInstalled;
    private boolean refreshPosted;

    SmartUFinalPolishForwarder(MainActivity mainActivity) { super(mainActivity); }

    void onCreate() { locate(); installListener(); refresh(false); }
    void onResume() { locate(); installListener(); refresh(false); }
    void onDataSetChanged() {
        if (!isUStyle()) { removePolish(); return; }
        locate(); installListener(); postRefresh();
    }
    void onConfigurationChanged() {
        locate();
        if (isUStyle()) refresh(false);
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void locate() {
        if (!(mainActivity.listContainer instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) mainActivity.listContainer;
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View candidate = container.getChildAt(i);
            if (!(candidate instanceof FrameLayout)) continue;
            FrameLayout root = (FrameLayout) candidate;
            ScrollView scroller = null;
            ViewGroup track = null;
            for (int j = 0; j < root.getChildCount(); j++) {
                View child = root.getChildAt(j);
                if (child instanceof ScrollView && !(child instanceof HorizontalScrollView)) scroller = (ScrollView) child;
                else if (child instanceof ViewGroup) track = (ViewGroup) child;
            }
            if (scroller == null || track == null || scroller.getChildCount() == 0) continue;
            View content = scroller.getChildAt(0);
            if (!(content instanceof LinearLayout)) continue;
            squareRoot = root;
            squareTrack = track;
            centerScroller = scroller;
            center = (LinearLayout) content;
            return;
        }
    }

    private void installListener() {
        if (squareTrack == null || listenerInstalled) return;
        squareTrack.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (isUStyle()) postRefresh();
        });
        listenerInstalled = true;
    }

    private void postRefresh() {
        if (squareTrack == null || refreshPosted) return;
        refreshPosted = true;
        squareTrack.post(() -> { refreshPosted = false; refresh(false); });
    }

    private void refresh(boolean userInitiated) {
        if (!isUStyle()) { removePolish(); return; }
        locate();
        if (squareRoot == null || squareTrack == null || center == null) return;
        ensureControls();
        applyResponsiveSizing();
        applyHandPosition(userInitiated);
        applyProfile();
        applyCardStyle();
        updateControlLabels();
        applyAccessibility();
    }

    private void ensureControls() {
        View existing = center.findViewWithTag(TAG_CONTROLS);
        if (existing instanceof LinearLayout) {
            controls = (LinearLayout) existing;
            styleChip = controls.getChildCount() > 0 ? (TextView) controls.getChildAt(0) : null;
            profileChip = controls.getChildCount() > 1 ? (TextView) controls.getChildAt(1) : null;
            handChip = controls.getChildCount() > 2 ? (TextView) controls.getChildAt(2) : null;
            return;
        }
        controls = new LinearLayout(mainActivity);
        controls.setTag(TAG_CONTROLS);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(3), dp(4), dp(3), dp(5));
        styleChip = chip(); profileChip = chip(); handChip = chip();
        styleChip.setOnClickListener(v -> { cycle(PREF_STYLE, STYLES, "glass"); refresh(true); });
        profileChip.setOnClickListener(v -> { cycle(PREF_PROFILE, PROFILES, "smooth"); refresh(true); });
        handChip.setOnClickListener(v -> { cycle(PREF_HAND, HANDS, "center"); refresh(true); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        controls.addView(styleChip, lp); controls.addView(profileChip, lp); controls.addView(handChip, lp);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setMargins(dp(3), 0, dp(3), dp(6));
        center.addView(controls, row);
    }

    private TextView chip() {
        TextView v = new TextView(mainActivity);
        v.setTextColor(Color.WHITE); v.setTextSize(11.5f); v.setGravity(Gravity.CENTER);
        v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END); v.setClickable(true); v.setFocusable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.argb(220, 29, 43, 70));
        bg.setCornerRadius(dp(17)); bg.setStroke(dp(1), Color.argb(205, 118, 173, 255)); v.setBackground(bg);
        v.setMinHeight(dp(38)); return v;
    }

    private void applyResponsiveSizing() {
        if (centerScroller == null) return;
        int width = mainActivity.getResources().getDisplayMetrics().widthPixels;
        int height = mainActivity.getResources().getDisplayMetrics().heightPixels;
        boolean landscape = mainActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean compact = width / mainActivity.getResources().getDisplayMetrics().density < 360f;
        ViewGroup.LayoutParams raw = centerScroller.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (landscape) {
                lp.width = Math.min(dp(500), Math.max(dp(230), Math.round(width * 0.46f)));
                lp.height = Math.min(dp(330), Math.max(dp(170), Math.round(height * 0.62f)));
            } else if (compact) {
                lp.width = Math.max(dp(210), width - dp(26));
                lp.height = Math.min(dp(300), Math.max(dp(170), Math.round(height * 0.38f)));
            }
            lp.gravity = Gravity.CENTER;
            centerScroller.setLayoutParams(lp);
        }
        if (controls != null) {
            float size = compact ? 10.5f : 11.5f;
            for (int i = 0; i < controls.getChildCount(); i++) ((TextView) controls.getChildAt(i)).setTextSize(size);
        }
    }

    private void applyHandPosition(boolean animate) {
        if (squareRoot == null) return;
        String hand = prefs.getString(PREF_HAND, "center");
        float shift = mainActivity.getResources().getDisplayMetrics().widthPixels * 0.065f;
        float target = "left".equals(hand) ? -shift : ("right".equals(hand) ? shift : 0f);
        if (animate && !"off".equals(prefs.getString(PREF_PROFILE, "smooth"))) {
            squareRoot.animate().cancel();
            squareRoot.animate().translationX(target).setDuration(profileDuration()).start();
        } else squareRoot.setTranslationX(target);
    }

    private void applyProfile() {
        String profile = prefs.getString(PREF_PROFILE, "smooth");
        if (squareRoot == null) return;
        if ("cinematic".equals(profile)) {
            squareRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            squareTrack.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            squareRoot.setLayerType(View.LAYER_TYPE_NONE, null);
            squareTrack.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        if (controls != null) controls.setAlpha("off".equals(profile) ? 0.88f : 1f);
    }

    private long profileDuration() {
        String profile = prefs.getString(PREF_PROFILE, "smooth");
        if ("efficient".equals(profile)) return 110L;
        if ("cinematic".equals(profile)) return 280L;
        return 180L;
    }

    private void applyCardStyle() {
        String style = prefs.getString(PREF_STYLE, "glass");
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (!(card instanceof FrameLayout)) continue;
            String label = label(card);
            GradientDrawable bg = new GradientDrawable();
            int stroke = Color.argb(205, 220, 232, 255);
            if ("acrylic".equals(style)) {
                bg.setColor(Color.argb(228, 31, 37, 49)); stroke = Color.argb(220, 180, 202, 236);
            } else if ("deep".equals(style)) {
                bg.setColor(Color.argb(248, 10, 13, 20)); stroke = Color.argb(230, 82, 126, 210);
            } else if ("minimal".equals(style)) {
                bg.setColor(Color.argb(72, 8, 10, 15)); stroke = Color.argb(96, 255, 255, 255);
            } else if ("dynamic".equals(style)) {
                int accent = accentFor(label); bg.setColor(withAlpha(accent, 155)); stroke = withAlpha(lighten(accent), 235);
            } else {
                bg.setColor(Color.argb(150, 24, 31, 45)); stroke = Color.argb(205, 205, 225, 255);
            }
            bg.setCornerRadius(dp("minimal".equals(style) ? 22 : 18));
            bg.setStroke(dp("deep".equals(style) ? 2 : 1), stroke);
            card.setBackground(bg);
            card.setElevation(dp("deep".equals(style) ? 9 : ("minimal".equals(style) ? 2 : 5)));
        }
    }

    private int accentFor(String label) {
        int hash = TextUtils.isEmpty(label) ? 0 : label.hashCode();
        float hue = Math.abs(hash % 360);
        return Color.HSVToColor(new float[]{hue, 0.48f, 0.70f});
    }
    private int lighten(int color) {
        float[] hsv = new float[3]; Color.colorToHSV(color, hsv); hsv[1] *= 0.72f; hsv[2] = Math.min(1f, hsv[2] * 1.28f); return Color.HSVToColor(hsv);
    }
    private int withAlpha(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }

    private void updateControlLabels() {
        if (styleChip == null) return;
        styleChip.setText("Style · " + title(prefs.getString(PREF_STYLE, "glass")));
        profileChip.setText("Motion · " + title(prefs.getString(PREF_PROFILE, "smooth")));
        handChip.setText("Hand · " + title(prefs.getString(PREF_HAND, "center")));
    }

    private void applyAccessibility() {
        if (styleChip != null) styleChip.setContentDescription("U style visual theme. Tap to change. Current " + prefs.getString(PREF_STYLE, "glass"));
        if (profileChip != null) profileChip.setContentDescription("U style animation profile. Tap to change. Current " + prefs.getString(PREF_PROFILE, "smooth"));
        if (handChip != null) handChip.setContentDescription("U style one handed position. Tap to change. Current " + prefs.getString(PREF_HAND, "center"));
        if (squareTrack != null) for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i); String label = label(card);
            if (!TextUtils.isEmpty(label)) card.setContentDescription(label + ". Tap to open, hold or swipe up for actions.");
            card.setMinimumWidth(dp(48)); card.setMinimumHeight(dp(48));
        }
    }

    private String label(View v) {
        TextView text = findText(v); return text == null ? "" : text.getText().toString().trim();
    }
    private TextView findText(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(t.getText()) && !"•".contentEquals(t.getText())) return t;
        }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            TextView t = findText(((ViewGroup) v).getChildAt(i)); if (t != null) return t;
        }
        return null;
    }

    private void cycle(String key, String[] values, String fallback) {
        String current = prefs.getString(key, fallback); int index = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) { index = i; break; }
        prefs.edit().putString(key, values[(index + 1) % values.length]).apply();
    }
    private String title(String value) {
        if (TextUtils.isEmpty(value)) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private void removePolish() {
        if (controls != null && controls.getParent() instanceof ViewGroup) ((ViewGroup) controls.getParent()).removeView(controls);
        controls = null; styleChip = null; profileChip = null; handChip = null;
        if (squareRoot != null) { squareRoot.animate().cancel(); squareRoot.setTranslationX(0f); squareRoot.setLayerType(View.LAYER_TYPE_NONE, null); }
        if (squareTrack != null) squareTrack.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    private int dp(int value) { return Math.round(value * mainActivity.getResources().getDisplayMetrics().density); }
}
