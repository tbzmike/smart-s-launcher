package fr.neamar.kiss.forwarder;

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

import androidx.preference.PreferenceManager;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import fr.neamar.kiss.KissApplication;
import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.battery.BatteryMonitorEngine;
import fr.neamar.kiss.battery.BatterySnapshot;
import fr.neamar.kiss.pojo.Pojo;
import fr.neamar.kiss.result.Result;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.utils.Log;

/**
 * Smart-U contextual dashboard and intelligence layer for the 3.28.97+ baseline.
 * Everything in this controller is active only while square_u is selected.
 */
final class SmartUIntelligenceForwarder {
    private static final String TAG = SmartUIntelligenceForwarder.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String PREF_HAND = "smart-u-hand-position";
    private static final String TAG_DASHBOARD = "smart-u-intelligence-dashboard";
    private static final String TAG_COUNT_BADGE = "smart-u-notification-count";
    private static final String TAG_LIVE = "smart-u-live-card";

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final android.content.SharedPreferences prefs;
    private final Set<String> favoriteIds = new HashSet<>();

    private FrameLayout squareRoot;
    private ViewGroup squareTrack;
    private ScrollView notificationScroller;
    private LinearLayout notificationCenter;
    private LinearLayout dashboard;
    private ImageView heroIcon;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView liveStrip;
    private TextView preview;
    private TextView handChip;
    private View selectedCard;
    private int selectedIndex = -1;
    private boolean layoutListenerInstalled;
    private boolean refreshPosted;

    SmartUIntelligenceForwarder(MainActivity activity,
                                HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() { resolveViews(); installObserver(); refreshSoon(); }
    void onResume() { resolveViews(); installObserver(); refreshSoon(); }
    void onDataSetChanged() {
        if (!isUStyle()) { removeEnhancements(); return; }
        resolveViews(); installObserver(); refreshSoon();
    }
    void onFavoriteChange() { if (isUStyle()) refreshSoon(); }
    void onConfigurationChanged() { resolveViews(); if (isUStyle()) refreshSoon(); }
    void onDestroy() { removeEnhancements(); squareRoot = null; squareTrack = null; notificationScroller = null; notificationCenter = null; }

    private boolean isUStyle() { return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical")); }

    private void resolveViews() {
        squareRoot = readField("squareRoot", FrameLayout.class);
        squareTrack = readField("squareTrack", ViewGroup.class);
        notificationScroller = readField("notificationScroller", ScrollView.class);
        notificationCenter = readField("notificationCenter", LinearLayout.class);
    }

    private <T> T readField(String name, Class<T> type) {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve Smart-U field: " + name, e);
            return null;
        }
    }

    private void installObserver() {
        if (squareTrack == null || layoutListenerInstalled) return;
        squareTrack.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (isUStyle()) refreshSoon();
        });
        layoutListenerInstalled = true;
    }

    private void refreshSoon() {
        if (squareTrack == null || refreshPosted) return;
        refreshPosted = true;
        squareTrack.post(() -> { refreshPosted = false; refreshNow(); });
    }

    private void refreshNow() {
        if (!isUStyle()) { removeEnhancements(); return; }
        resolveViews();
        if (squareTrack == null || notificationCenter == null) return;
        rebuildFavoriteIds();
        findSelectedCard();
        ensureDashboard();
        applyResponsiveSizing();
        applyHandPosition();
        updateDashboard();
        updateNotificationBadges();
        updateLiveCards();
        applySearchAndPriorityEmphasis();
        if (notificationScroller != null) notificationScroller.setVisibility(View.VISIBLE);
    }

    private void rebuildFavoriteIds() {
        favoriteIds.clear();
        List<Pojo> favorites = KissApplication.getApplication(activity).getDataHandler().getFavorites();
        if (favorites == null) return;
        for (Pojo pojo : favorites) {
            if (pojo != null && !TextUtils.isEmpty(pojo.getFavoriteId())) favoriteIds.add(pojo.getFavoriteId());
        }
    }

    private void findSelectedCard() {
        selectedCard = null;
        selectedIndex = -1;
        float tx = squareTrack.getWidth() / 2f;
        float ty = squareTrack.getHeight();
        float best = Float.MAX_VALUE;
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (card.getVisibility() != View.VISIBLE) continue;
            float cx = card.getX() + card.getWidth() / 2f;
            float cy = card.getY() + card.getHeight() / 2f;
            float score = Math.abs(cx - tx) + Math.abs(cy - ty) * 0.38f;
            if (score < best) { best = score; selectedCard = card; selectedIndex = i; }
        }
    }

    private void ensureDashboard() {
        View existing = notificationCenter.findViewWithTag(TAG_DASHBOARD);
        if (existing instanceof LinearLayout) {
            dashboard = (LinearLayout) existing;
            return;
        }
        dashboard = new LinearLayout(activity);
        dashboard.setTag(TAG_DASHBOARD);
        dashboard.setOrientation(LinearLayout.VERTICAL);
        dashboard.setGravity(Gravity.CENTER);
        dashboard.setPadding(dp(10), dp(9), dp(10), dp(9));
        dashboard.setElevation(dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(230, 12, 18, 31));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2), Color.argb(220, 104, 162, 255));
        dashboard.setBackground(bg);

        LinearLayout hero = new LinearLayout(activity);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        heroIcon = new ImageView(activity);
        heroIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hero.addView(heroIcon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        heroTitle = text(18f, Color.WHITE);
        heroMeta = text(12.5f, Color.argb(230, 205, 223, 250));
        texts.addView(heroTitle);
        texts.addView(heroMeta);
        hero.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dashboard.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        liveStrip = text(13f, Color.WHITE);
        liveStrip.setGravity(Gravity.CENTER);
        liveStrip.setPadding(dp(4), dp(8), dp(4), dp(6));
        dashboard.addView(liveStrip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        preview = text(12.5f, Color.argb(235, 236, 242, 255));
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(4), dp(4), dp(4), dp(7));
        dashboard.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        handChip = text(12f, Color.WHITE);
        handChip.setGravity(Gravity.CENTER);
        handChip.setMinHeight(dp(38));
        handChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(Color.argb(220, 30, 46, 76));
        chip.setCornerRadius(dp(17));
        chip.setStroke(dp(1), Color.argb(200, 115, 172, 255));
        handChip.setBackground(chip);
        handChip.setClickable(true);
        handChip.setFocusable(true);
        handChip.setOnClickListener(v -> { cycleHand(); applyHandPosition(); updateHandChip(); });
        dashboard.addView(handChip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(3), dp(3), dp(8));
        notificationCenter.addView(dashboard, 0, lp);
    }

    private TextView text(float size, int color) {
        AutoMarqueeTextView v = new AutoMarqueeTextView(activity);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private void updateDashboard() {
        if (dashboard == null) return;
        BatterySnapshot battery = BatteryMonitorEngine.read(activity);
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String date = new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(new Date());
        String charge = battery.isCharging() ? " ⚡ charging" : "";
        liveStrip.setText("🕘 " + time + "   📅 " + date + "   🔋 " + battery.percent() + "%" + charge);
        updateHandChip();

        if (selectedCard == null || activity.adapter == null || activity.adapter.getCount() == 0) {
            heroIcon.setImageDrawable(null);
            heroTitle.setText("Smart S");
            heroMeta.setText("Smart U ready");
            preview.setText("Start typing to search • or swipe through recent apps");
            return;
        }

        Result<?> result = activity.adapter.getItem(selectedIndex);
        String label = result.getPojo().getName();
        heroTitle.setText(TextUtils.isEmpty(label) ? "Selected item" : label);
        heroIcon.setImageDrawable(findIcon(selectedCard));
        int count = notificationCount(label);
        String query = activity.searchEditText == null ? "" : activity.searchEditText.getText().toString().trim();
        boolean favorite = favoriteIds.contains(result.getFavoriteId());
        String zone = priorityZone(selectedIndex, squareTrack.getChildCount(), favorite, !TextUtils.isEmpty(query));
        String meta = zone + " • " + (selectedIndex + 1) + "/" + squareTrack.getChildCount();
        if (count > 0) meta += " • " + count + " notification" + (count == 1 ? "" : "s");
        heroMeta.setText(meta);
        preview.setText(count > 0 ? notificationPreview(label)
                : (TextUtils.isEmpty(query) ? "Tap to open • hold or swipe up for actions • swipe down for details"
                : "Best visible match for “" + query + "” • continue typing to refine"));
    }

    private String priorityZone(int index, int count, boolean favorite, boolean searching) {
        if (searching) return "Search priority";
        if (favorite) return "Favourite zone";
        int recency = (count - 1) - index;
        if (recency <= 2) return "Recent zone";
        if (recency <= Math.max(4, count / 2)) return "History zone";
        return "Older history";
    }

    private void updateNotificationBadges() {
        if (activity.adapter == null) return;
        int count = Math.min(squareTrack.getChildCount(), activity.adapter.getCount());
        for (int i = 0; i < count; i++) {
            View card = squareTrack.getChildAt(i);
            if (!(card instanceof FrameLayout)) continue;
            View old = card.findViewWithTag(TAG_COUNT_BADGE);
            if (old != null) ((FrameLayout) card).removeView(old);
            String label = activity.adapter.getItem(i).getPojo().getName();
            int notifications = notificationCount(label);
            if (notifications <= 0) continue;
            TextView badge = text(11f, Color.WHITE);
            badge.setTag(TAG_COUNT_BADGE);
            badge.setText(notifications > 99 ? "99+" : Integer.toString(notifications));
            badge.setGravity(Gravity.CENTER);
            badge.setElevation(dp(12));
            badge.setContentDescription(notifications + " notifications");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(222, 48, 68));
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), Color.WHITE);
            badge.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(notifications > 9 ? dp(30) : dp(24), dp(24), Gravity.TOP | Gravity.END);
            lp.topMargin = dp(5); lp.rightMargin = dp(5);
            ((FrameLayout) card).addView(badge, lp);
        }
    }

    private int notificationCount(String label) {
        if (notificationCenter == null || TextUtils.isEmpty(label)) return 0;
        int count = 0;
        for (int i = 0; i < notificationCenter.getChildCount(); i++) {
            View child = notificationCenter.getChildAt(i);
            if (child == dashboard || TAG_DASHBOARD.equals(child.getTag())) continue;
            if (containsExactText(child, label)) count++;
        }
        return count;
    }

    private String notificationPreview(String label) {
        for (int i = 0; i < notificationCenter.getChildCount(); i++) {
            View child = notificationCenter.getChildAt(i);
            if (child == dashboard || TAG_DASHBOARD.equals(child.getTag())) continue;
            if (containsExactText(child, label)) {
                StringBuilder out = new StringBuilder();
                collectText(child, out);
                if (out.length() > 0) return out.toString();
            }
        }
        return "Notification available";
    }

    private boolean containsExactText(View view, String label) {
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (!TextUtils.isEmpty(t) && label.equalsIgnoreCase(t.toString().trim())) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsExactText(group.getChildAt(i), label)) return true;
        }
        return false;
    }

    private void collectText(View view, StringBuilder out) {
        if (view instanceof TextView) {
            String s = ((TextView) view).getText().toString().trim();
            if (!s.isEmpty() && !"Notifications".equalsIgnoreCase(s) && !s.startsWith("Smart U")) {
                if (out.length() > 0) out.append(" • ");
                out.append(s);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
        }
    }

    private void updateLiveCards() {
        if (activity.adapter == null) return;
        BatterySnapshot battery = BatteryMonitorEngine.read(activity);
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String date = new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date());
        int count = Math.min(squareTrack.getChildCount(), activity.adapter.getCount());
        for (int i = 0; i < count; i++) {
            View card = squareTrack.getChildAt(i);
            if (!(card instanceof FrameLayout)) continue;
            View old = card.findViewWithTag(TAG_LIVE);
            if (old != null) ((FrameLayout) card).removeView(old);
            String name = activity.adapter.getItem(i).getPojo().getName();
            if (TextUtils.isEmpty(name)) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            String value = null;
            if (lower.contains("clock")) value = time;
            else if (lower.contains("calendar")) value = date;
            else if (lower.contains("battery")) value = battery.percent() + "%" + (battery.isCharging() ? " ⚡" : "");
            if (value == null) continue;
            TextView live = text(13f, Color.WHITE);
            live.setTag(TAG_LIVE);
            live.setText(value);
            live.setGravity(Gravity.CENTER);
            live.setElevation(dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(175, 0, 0, 0));
            bg.setCornerRadius(dp(10));
            live.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(88);
            ((FrameLayout) card).addView(live, lp);
        }
    }

    private void applySearchAndPriorityEmphasis() {
        if (activity.adapter == null) return;
        String query = activity.searchEditText == null ? "" : activity.searchEditText.getText().toString().trim();
        int count = Math.min(squareTrack.getChildCount(), activity.adapter.getCount());
        for (int i = 0; i < count; i++) {
            View card = squareTrack.getChildAt(i);
            Result<?> result = activity.adapter.getItem(i);
            boolean favorite = favoriteIds.contains(result.getFavoriteId());
            if (!TextUtils.isEmpty(query)) {
                int recency = (count - 1) - i;
                if (recency == 0) {
                    card.setAlpha(1f);
                    card.setTranslationZ(Math.max(card.getTranslationZ(), dp(38)));
                } else if (recency <= 2) {
                    card.setTranslationZ(Math.max(card.getTranslationZ(), dp(22)));
                }
            } else if (favorite && card != selectedCard) {
                card.setTranslationZ(Math.max(card.getTranslationZ(), dp(14)));
            }
        }
    }

    private void applyResponsiveSizing() {
        if (notificationScroller == null) return;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        float density = activity.getResources().getDisplayMetrics().density;
        boolean landscape = width > height;
        boolean compact = width / density < 360f;
        ViewGroup.LayoutParams raw = notificationScroller.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        int cardCount = squareTrack == null ? 0 : squareTrack.getChildCount();
        if (landscape) {
            lp.width = Math.min(dp(520), Math.max(dp(240), Math.round(width * 0.46f)));
            lp.height = Math.min(dp(340), Math.max(dp(170), Math.round(height * 0.64f)));
        } else if (compact) {
            lp.width = Math.max(dp(210), width - dp(24));
            lp.height = Math.min(dp(310), Math.max(dp(175), Math.round(height * 0.40f)));
        } else if (cardCount > 10) {
            lp.height = Math.min(dp(330), Math.max(lp.height, Math.round(height * 0.34f)));
        }
        lp.gravity = Gravity.CENTER;
        notificationScroller.setLayoutParams(lp);
    }

    private void applyHandPosition() {
        if (squareRoot == null) return;
        String hand = prefs.getString(PREF_HAND, "center");
        float width = activity.getResources().getDisplayMetrics().widthPixels;
        float shift = width * 0.065f;
        float target = "left".equals(hand) ? -shift : ("right".equals(hand) ? shift : 0f);
        squareRoot.animate().cancel();
        squareRoot.animate().translationX(target).setDuration(170L).start();
    }

    private void cycleHand() {
        String hand = prefs.getString(PREF_HAND, "center");
        String next = "center".equals(hand) ? "right" : ("right".equals(hand) ? "left" : "center");
        prefs.edit().putString(PREF_HAND, next).apply();
    }

    private void updateHandChip() {
        if (handChip == null) return;
        String hand = prefs.getString(PREF_HAND, "center");
        handChip.setText("One-handed · " + Character.toUpperCase(hand.charAt(0)) + hand.substring(1));
        handChip.setContentDescription("Smart U one handed position. Current " + hand + ". Tap to change.");
    }

    private Drawable findIcon(View view) {
        if (view instanceof ImageView && ((ImageView) view).getDrawable() != null) return ((ImageView) view).getDrawable();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Drawable icon = findIcon(group.getChildAt(i));
                if (icon != null) return icon;
            }
        }
        return null;
    }

    private void removeEnhancements() {
        if (dashboard != null && dashboard.getParent() instanceof ViewGroup) ((ViewGroup) dashboard.getParent()).removeView(dashboard);
        if (squareTrack != null) {
            for (int i = 0; i < squareTrack.getChildCount(); i++) {
                View card = squareTrack.getChildAt(i);
                if (card instanceof FrameLayout) {
                    View badge = card.findViewWithTag(TAG_COUNT_BADGE);
                    if (badge != null) ((FrameLayout) card).removeView(badge);
                    View live = card.findViewWithTag(TAG_LIVE);
                    if (live != null) ((FrameLayout) card).removeView(live);
                }
            }
        }
        if (squareRoot != null) { squareRoot.animate().cancel(); squareRoot.setTranslationX(0f); }
        dashboard = null; heroIcon = null; heroTitle = null; heroMeta = null; liveStrip = null; preview = null; handChip = null;
        selectedCard = null; selectedIndex = -1; favoriteIds.clear();
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
