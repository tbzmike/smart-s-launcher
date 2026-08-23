package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.neamar.kiss.MainActivity;

/**
 * U-style-only experience layer.
 *
 * This class deliberately sits on top of HistoryDisplayForwarder's Square-U renderer instead of
 * changing the normal vertical or horizontal history paths. It adds selected-card state, richer
 * depth/lighting, notification indicators, haptics and a contextual smart-center header while
 * preserving the existing adapter click/long-click and search pipeline.
 */
final class SmartUExperienceForwarder extends Forwarder {
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String TAG_HEADER = "smart-u-center-header";
    private static final String TAG_BADGE = "smart-u-notification-badge";
    private static final String TAG_GLOW = "smart-u-ambient-glow";

    private FrameLayout squareRoot;
    private ViewGroup squareTrack;
    private ScrollView notificationScroller;
    private LinearLayout notificationCenter;
    private LinearLayout smartHeader;
    private TextView smartTitle;
    private TextView smartSubtitle;
    private View ambientGlow;
    private View selectedCard;
    private int selectedIndex = -1;
    private boolean trackListenerInstalled;
    private boolean selectionApplyPosted;

    SmartUExperienceForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        locateSquareViews();
        installTrackListener();
        refresh();
    }

    void onResume() {
        locateSquareViews();
        installTrackListener();
        refresh();
    }

    void onDataSetChanged() {
        if (!isUStyle()) return;
        locateSquareViews();
        installTrackListener();
        postApplySelection();
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void refresh() {
        if (!isUStyle()) {
            removeEnhancements();
            return;
        }
        ensureAmbientGlow();
        ensureSmartHeader();
        if (notificationScroller != null) notificationScroller.setVisibility(View.VISIBLE);
        postApplySelection();
    }

    private void locateSquareViews() {
        if (!(mainActivity.listContainer instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) mainActivity.listContainer;
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View candidate = container.getChildAt(i);
            if (!(candidate instanceof FrameLayout)) continue;
            FrameLayout frame = (FrameLayout) candidate;
            ScrollView verticalScroller = null;
            ViewGroup track = null;
            for (int j = 0; j < frame.getChildCount(); j++) {
                View child = frame.getChildAt(j);
                if (child instanceof ScrollView && !(child instanceof HorizontalScrollView)) {
                    verticalScroller = (ScrollView) child;
                } else if (child instanceof ViewGroup && !TAG_HEADER.equals(child.getTag())) {
                    track = (ViewGroup) child;
                }
            }
            if (verticalScroller == null || track == null) continue;
            squareRoot = frame;
            squareTrack = track;
            notificationScroller = verticalScroller;
            View content = verticalScroller.getChildCount() > 0
                    ? verticalScroller.getChildAt(0) : null;
            notificationCenter = content instanceof LinearLayout ? (LinearLayout) content : null;
            return;
        }
    }

    private void installTrackListener() {
        if (squareTrack == null || trackListenerInstalled) return;
        squareTrack.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (isUStyle()) postApplySelection();
        });
        trackListenerInstalled = true;
    }

    private void postApplySelection() {
        if (squareTrack == null || selectionApplyPosted) return;
        selectionApplyPosted = true;
        squareTrack.post(() -> {
            selectionApplyPosted = false;
            if (isUStyle()) applySelectionState();
        });
    }

    private void applySelectionState() {
        if (squareTrack == null || squareTrack.getChildCount() == 0) {
            updateSmartCenter(null, -1);
            return;
        }

        View best = null;
        int bestIndex = -1;
        float bestScore = Float.MAX_VALUE;
        float targetX = squareTrack.getWidth() / 2f;
        float targetY = squareTrack.getHeight();

        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            removeBadge(card);
            if (card.getVisibility() != View.VISIBLE) continue;
            float score = selectionScore(card, targetX, targetY);
            if (score < bestScore) {
                bestScore = score;
                best = card;
                bestIndex = i;
            }
        }

        applyDepthLighting(targetX, targetY, best);
        addNotificationBadges();
        if (best == null) {
            updateSmartCenter(null, -1);
            return;
        }

        if (selectedCard != best) {
            clearSelectedOverlay();
            selectedCard = best;
            selectedIndex = bestIndex;
            selectedCard.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            animateSelectedCard(selectedCard);
            pulseSmartCenter();
        }
        applySelectedOverlay(selectedCard);
        selectedCard.setAlpha(1f);
        selectedCard.setTranslationZ(Math.max(selectedCard.getTranslationZ(), dp(32)));
        updateSmartCenter(selectedCard, selectedIndex);
    }

    private float selectionScore(View card, float targetX, float targetY) {
        float centerX = card.getX() + card.getWidth() / 2f;
        float centerY = card.getY() + card.getHeight() / 2f;
        return Math.abs(centerX - targetX) + Math.abs(centerY - targetY) * 0.38f;
    }

    private void applyDepthLighting(float targetX, float targetY, View best) {
        if (squareTrack == null) return;
        float widthScale = Math.max(1f, squareTrack.getWidth() * 0.60f);
        float heightScale = Math.max(1f, squareTrack.getHeight() * 0.72f);

        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (card.getVisibility() != View.VISIBLE) continue;

            float centerX = card.getX() + card.getWidth() / 2f;
            float centerY = card.getY() + card.getHeight() / 2f;
            float dx = Math.abs(centerX - targetX) / widthScale;
            float dy = Math.abs(centerY - targetY) / heightScale;
            float distance = Math.min(1f, dx * 0.72f + dy * 0.58f);
            float focus = 1f - distance;

            if (card != best) {
                float scale = 0.79f + 0.19f * focus;
                card.setScaleX(scale);
                card.setScaleY(scale);
                card.setAlpha(0.60f + 0.36f * focus);
                card.setTranslationZ(dp(2) + dp(17) * focus);
            }
        }

        updateAmbientGlow(best != null ? 1f : 0.55f);
    }

    private void animateSelectedCard(View card) {
        card.animate().cancel();
        card.animate()
                .scaleX(1.12f)
                .scaleY(1.12f)
                .alpha(1f)
                .translationZ(dp(32))
                .setDuration(165L)
                .start();
    }

    private void applySelectedOverlay(View card) {
        card.getOverlay().clear();
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(Color.TRANSPARENT);
        focus.setCornerRadius(dp(20));
        focus.setStroke(dp(3), Color.argb(245, 120, 176, 255));
        focus.setBounds(0, 0, card.getWidth(), card.getHeight());
        card.getOverlay().add(focus);

        GradientDrawable inner = new GradientDrawable();
        inner.setColor(Color.TRANSPARENT);
        inner.setCornerRadius(dp(17));
        inner.setStroke(dp(1), Color.argb(220, 245, 250, 255));
        inner.setBounds(dp(4), dp(4), Math.max(dp(4), card.getWidth() - dp(4)),
                Math.max(dp(4), card.getHeight() - dp(4)));
        card.getOverlay().add(inner);
    }

    private void clearSelectedOverlay() {
        if (selectedCard != null) selectedCard.getOverlay().clear();
    }

    private void ensureAmbientGlow() {
        if (squareRoot == null) return;
        View existing = squareRoot.findViewWithTag(TAG_GLOW);
        if (existing != null) {
            ambientGlow = existing;
            return;
        }

        ambientGlow = new View(mainActivity);
        ambientGlow.setTag(TAG_GLOW);
        ambientGlow.setClickable(false);
        ambientGlow.setFocusable(false);
        ambientGlow.setAlpha(0.78f);
        updateAmbientGlow(0.75f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        squareRoot.addView(ambientGlow, 0, lp);
    }

    private void updateAmbientGlow(float intensity) {
        if (ambientGlow == null) return;
        int strongAlpha = Math.round(88f * intensity);
        int softAlpha = Math.round(38f * intensity);
        GradientDrawable glow = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {
                        Color.argb(strongAlpha, 86, 139, 255),
                        Color.argb(softAlpha, 65, 92, 180),
                        Color.TRANSPARENT
                });
        glow.setCornerRadius(dp(28));
        ambientGlow.setBackground(glow);
    }

    private void addNotificationBadges() {
        if (squareTrack == null || notificationCenter == null) return;
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            String label = findCardLabel(card);
            if (TextUtils.isEmpty(label) || !hasNotificationForLabel(label)) continue;
            if (!(card instanceof FrameLayout)) continue;

            TextView badge = new TextView(mainActivity);
            badge.setTag(TAG_BADGE);
            badge.setText("•");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(18f);
            badge.setGravity(Gravity.CENTER);
            badge.setContentDescription("Has notification");
            badge.setElevation(dp(8));
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(Color.rgb(230, 55, 70));
            background.setStroke(dp(1), Color.WHITE);
            badge.setBackground(background);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(22), dp(22),
                    Gravity.TOP | Gravity.END);
            lp.topMargin = dp(6);
            lp.rightMargin = dp(6);
            ((FrameLayout) card).addView(badge, lp);
        }
    }

    private void removeBadge(View card) {
        if (!(card instanceof ViewGroup)) return;
        View badge = card.findViewWithTag(TAG_BADGE);
        if (badge != null && badge.getParent() instanceof ViewGroup) {
            ((ViewGroup) badge.getParent()).removeView(badge);
        }
    }

    private boolean hasNotificationForLabel(String label) {
        if (notificationCenter == null) return false;
        for (int i = 0; i < notificationCenter.getChildCount(); i++) {
            View child = notificationCenter.getChildAt(i);
            if (child == smartHeader || TAG_HEADER.equals(child.getTag())) continue;
            if (containsExactText(child, label)) return true;
        }
        return false;
    }

    private boolean containsExactText(View view, String label) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (!TextUtils.isEmpty(text) && label.equalsIgnoreCase(text.toString().trim())) return true;
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsExactText(group.getChildAt(i), label)) return true;
        }
        return false;
    }

    private void ensureSmartHeader() {
        if (notificationCenter == null) return;
        if (smartHeader != null && smartHeader.getParent() == notificationCenter) return;

        View existing = notificationCenter.findViewWithTag(TAG_HEADER);
        if (existing instanceof LinearLayout) {
            smartHeader = (LinearLayout) existing;
            smartTitle = smartHeader.getChildCount() > 0 && smartHeader.getChildAt(0) instanceof TextView
                    ? (TextView) smartHeader.getChildAt(0) : null;
            smartSubtitle = smartHeader.getChildCount() > 1 && smartHeader.getChildAt(1) instanceof TextView
                    ? (TextView) smartHeader.getChildAt(1) : null;
            return;
        }

        smartHeader = new LinearLayout(mainActivity);
        smartHeader.setTag(TAG_HEADER);
        smartHeader.setOrientation(LinearLayout.VERTICAL);
        smartHeader.setGravity(Gravity.CENTER);
        smartHeader.setPadding(dp(10), dp(9), dp(10), dp(9));
        smartHeader.setElevation(dp(7));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(232, 15, 21, 34));
        background.setCornerRadius(dp(17));
        background.setStroke(dp(2), Color.argb(225, 105, 158, 255));
        smartHeader.setBackground(background);

        smartTitle = new TextView(mainActivity);
        smartTitle.setTextColor(Color.WHITE);
        smartTitle.setTextSize(18f);
        smartTitle.setGravity(Gravity.CENTER);
        smartTitle.setSingleLine(true);
        smartTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        smartTitle.setMarqueeRepeatLimit(-1);
        smartTitle.setSelected(true);

        smartSubtitle = new TextView(mainActivity);
        smartSubtitle.setTextColor(Color.argb(225, 205, 222, 250));
        smartSubtitle.setTextSize(12.5f);
        smartSubtitle.setGravity(Gravity.CENTER);
        smartSubtitle.setPadding(0, dp(3), 0, 0);

        smartHeader.addView(smartTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        smartHeader.addView(smartSubtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(3), dp(3), dp(7));
        notificationCenter.addView(smartHeader, 0, lp);
    }

    private void pulseSmartCenter() {
        if (smartHeader == null) return;
        smartHeader.animate().cancel();
        smartHeader.setScaleX(0.985f);
        smartHeader.setScaleY(0.985f);
        smartHeader.setAlpha(0.88f);
        smartHeader.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(160L)
                .start();
    }

    private void updateSmartCenter(View card, int index) {
        ensureSmartHeader();
        if (smartTitle == null || smartSubtitle == null) return;
        if (card == null) {
            smartTitle.setText("Smart U");
            smartSubtitle.setText("Swipe the U to browse • Tap to open • Hold for actions");
            return;
        }

        String label = findCardLabel(card);
        smartTitle.setText(TextUtils.isEmpty(label) ? "Selected item" : label);
        boolean hasNotification = !TextUtils.isEmpty(label) && hasNotificationForLabel(label);
        String query = mainActivity.searchEditText == null
                ? "" : mainActivity.searchEditText.getText().toString().trim();
        String context = TextUtils.isEmpty(query) ? "Selected" : "Best match for “" + query + "”";
        String notice = hasNotification ? " • notification available" : "";
        String position = squareTrack == null || squareTrack.getChildCount() <= 1
                ? "" : " • " + (index + 1) + "/" + squareTrack.getChildCount();
        smartSubtitle.setText(context + notice + position + " • swipe • tap to open");
        if (notificationScroller != null) notificationScroller.setVisibility(View.VISIBLE);
        updateAmbientGlow(hasNotification ? 1f : 0.86f);
    }

    private String findCardLabel(View card) {
        TextView text = findUsefulText(card);
        return text == null || TextUtils.isEmpty(text.getText())
                ? "" : text.getText().toString().trim();
    }

    private TextView findUsefulText(View view) {
        if (view instanceof TextView && !TAG_BADGE.equals(view.getTag())) {
            TextView text = (TextView) view;
            if (text.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(text.getText())) {
                String value = text.getText().toString().trim();
                if (!value.isEmpty() && !"•".equals(value)) return text;
            }
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView candidate = findUsefulText(group.getChildAt(i));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private void removeEnhancements() {
        clearSelectedOverlay();
        selectedCard = null;
        selectedIndex = -1;
        if (squareTrack != null) {
            for (int i = 0; i < squareTrack.getChildCount(); i++) removeBadge(squareTrack.getChildAt(i));
        }
        if (smartHeader != null && smartHeader.getParent() instanceof ViewGroup) {
            ((ViewGroup) smartHeader.getParent()).removeView(smartHeader);
        }
        if (ambientGlow != null && ambientGlow.getParent() instanceof ViewGroup) {
            ((ViewGroup) ambientGlow.getParent()).removeView(ambientGlow);
        }
        smartHeader = null;
        smartTitle = null;
        smartSubtitle = null;
        ambientGlow = null;
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}
