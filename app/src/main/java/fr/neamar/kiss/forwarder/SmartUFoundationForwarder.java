package fr.neamar.kiss.forwarder;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.ui.AutoMarqueeTextView;
import fr.neamar.kiss.utils.Log;

/**
 * Smart-U foundation layer for the 3.28.97 baseline.
 *
 * This controller deliberately enhances only square_u. It observes the existing SquareTrackLayout
 * instead of replacing the KISS result pipeline, so normal click/long-click routing and every other
 * history layout remain owned by HistoryDisplayForwarder.
 */
final class SmartUFoundationForwarder {
    private static final String TAG = SmartUFoundationForwarder.class.getSimpleName();
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String TAG_HEADER = "smart-u-foundation-header";

    private final MainActivity activity;
    private final HistoryDisplayForwarder historyDisplayForwarder;
    private final android.content.SharedPreferences prefs;
    private final Map<View, Float> baseAlpha = new HashMap<>();
    private final Map<View, Float> baseZ = new HashMap<>();

    private FrameLayout squareRoot;
    private ViewGroup squareTrack;
    private ScrollView notificationScroller;
    private LinearLayout notificationCenter;
    private LinearLayout header;
    private TextView title;
    private TextView subtitle;
    private View selectedCard;
    private int selectedIndex = -1;
    private boolean layoutListenerInstalled;
    private boolean refreshPosted;

    private VelocityTracker velocityTracker;
    private float trackDownX;
    private float trackDownY;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;

    private float cardDownX;
    private float cardDownY;
    private boolean cardGestureConsumed;

    SmartUFoundationForwarder(MainActivity activity,
                              HistoryDisplayForwarder historyDisplayForwarder) {
        this.activity = activity;
        this.historyDisplayForwarder = historyDisplayForwarder;
        this.prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity);
        ViewConfiguration configuration = ViewConfiguration.get(activity);
        minFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    void onCreate() {
        resolveViews();
        installObservers();
        refreshSoon();
    }

    void onResume() {
        resolveViews();
        installObservers();
        refreshSoon();
    }

    void onPause() {
        recycleVelocityTracker();
    }

    void onDataSetChanged() {
        if (!isUStyle()) {
            removeEnhancements();
            return;
        }
        resolveViews();
        installObservers();
        refreshSoon();
    }

    void onDestroy() {
        recycleVelocityTracker();
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
        squareRoot = readField("squareRoot", FrameLayout.class);
        squareTrack = readField("squareTrack", ViewGroup.class);
        notificationScroller = readField("notificationScroller", ScrollView.class);
        notificationCenter = readField("notificationCenter", LinearLayout.class);
    }

    private <T> T readField(String name, Class<T> expected) {
        try {
            Field field = HistoryDisplayForwarder.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(historyDisplayForwarder);
            return expected.isInstance(value) ? expected.cast(value) : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to resolve Smart-U field: " + name, e);
            return null;
        }
    }

    private void installObservers() {
        if (squareTrack == null) return;
        if (!layoutListenerInstalled) {
            squareTrack.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (isUStyle()) refreshSoon();
            });
            layoutListenerInstalled = true;
        }
        squareTrack.setOnTouchListener(this::observeTrackGesture);
    }

    private boolean observeTrackGesture(View view, MotionEvent event) {
        if (!isUStyle()) return false;
        trackVelocity(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                trackDownX = event.getX();
                trackDownY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                final float dx = event.getX() - trackDownX;
                final float dy = event.getY() - trackDownY;
                final float velocityX = computeVelocityX();
                recycleVelocityTracker();
                if (Math.abs(dx) > Math.abs(dy) * 1.15f
                        && Math.abs(velocityX) >= minFlingVelocity) {
                    // Run after SquareTrackLayout processes ACTION_UP and starts its normal nearest
                    // slot settle. We then replace that settle with a velocity-projected target.
                    view.post(() -> applyProjectedSettle(velocityX));
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocityTracker();
                break;
            default:
                break;
        }
        return false;
    }

    private void trackVelocity(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || velocityTracker == null) {
            recycleVelocityTracker();
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
    }

    private float computeVelocityX() {
        if (velocityTracker == null) return 0f;
        velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
        return velocityTracker.getXVelocity();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void applyProjectedSettle(float velocityX) {
        if (!isUStyle() || squareTrack == null || squareTrack.getChildCount() < 2) return;
        try {
            Field offsetField = squareTrack.getClass().getDeclaredField("rotationOffset");
            Field animatorField = squareTrack.getClass().getDeclaredField("settleAnimator");
            offsetField.setAccessible(true);
            animatorField.setAccessible(true);

            float current = offsetField.getFloat(squareTrack);
            Object running = animatorField.get(squareTrack);
            if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();

            float normalized = Math.max(-1f, Math.min(1f, velocityX / Math.max(1f, maxFlingVelocity)));
            float projectedSlots = normalized * 4.0f;
            if (Math.abs(projectedSlots) < 1f) projectedSlots = Math.signum(velocityX);
            float target = Math.round(current + projectedSlots);
            if (Math.abs(target - current) < 0.5f) target = Math.round(current + Math.signum(velocityX));

            ValueAnimator animator = ValueAnimator.ofFloat(current, target);
            long duration = Math.max(150L, Math.min(420L,
                    170L + Math.round(Math.abs(target - current) * 58L)));
            animator.setDuration(duration);
            animator.setInterpolator(new DecelerateInterpolator(1.65f));
            animator.addUpdateListener(animation -> {
                try {
                    offsetField.setFloat(squareTrack, (float) animation.getAnimatedValue());
                    squareTrack.requestLayout();
                } catch (IllegalAccessException e) {
                    Log.e(TAG, "Unable to animate Smart-U rotation", e);
                }
            });
            animatorField.set(squareTrack, animator);
            animator.start();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Unable to apply Smart-U projected settle", e);
        }
    }

    private void refreshSoon() {
        if (squareTrack == null || refreshPosted) return;
        refreshPosted = true;
        squareTrack.post(() -> {
            refreshPosted = false;
            refreshNow();
        });
    }

    private void refreshNow() {
        if (!isUStyle()) {
            removeEnhancements();
            return;
        }
        resolveViews();
        if (squareTrack == null) return;
        ensureHeader();
        applyFocusState();
    }

    private void applyFocusState() {
        if (squareTrack.getChildCount() == 0) {
            setSelectedCard(null, -1);
            updateHeader();
            return;
        }

        float targetX = squareTrack.getWidth() / 2f;
        float targetY = squareTrack.getHeight();
        float bestScore = Float.MAX_VALUE;
        View best = null;
        int bestIndex = -1;

        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (card.getVisibility() != View.VISIBLE) continue;
            baseAlpha.putIfAbsent(card, card.getAlpha());
            baseZ.putIfAbsent(card, card.getTranslationZ());

            float cx = card.getX() + card.getWidth() / 2f;
            float cy = card.getY() + card.getHeight() / 2f;
            float dx = Math.abs(cx - targetX) / Math.max(1f, squareTrack.getWidth() * 0.58f);
            float dy = Math.abs(cy - targetY) / Math.max(1f, squareTrack.getHeight() * 0.72f);
            float distance = Math.min(1f, dx * 0.72f + dy * 0.55f);
            float focus = 1f - distance;

            float originalAlpha = baseAlpha.get(card);
            float originalZ = baseZ.get(card);
            card.setAlpha(Math.max(0.48f, originalAlpha * (0.62f + 0.38f * focus)));
            card.setTranslationZ(Math.max(originalZ, dp(2) + dp(19) * focus));

            float score = Math.abs(cx - targetX) + Math.abs(cy - targetY) * 0.38f;
            if (score < bestScore) {
                bestScore = score;
                best = card;
                bestIndex = i;
            }
        }

        setSelectedCard(best, bestIndex);
        if (selectedCard != null) {
            selectedCard.setAlpha(1f);
            selectedCard.setTranslationZ(Math.max(selectedCard.getTranslationZ(), dp(34)));
            applySelectedOverlay(selectedCard);
            installSelectedCardGesture(selectedCard);
        }
        updateHeader();
    }

    private void setSelectedCard(View next, int index) {
        if (selectedCard == next) {
            selectedIndex = index;
            return;
        }
        if (selectedCard != null) {
            selectedCard.getOverlay().clear();
            selectedCard.setOnTouchListener(null);
        }
        selectedCard = next;
        selectedIndex = index;
        if (selectedCard != null) {
            selectedCard.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            selectedCard.animate().cancel();
            selectedCard.setScaleX(selectedCard.getScaleX() * 0.985f);
            selectedCard.setScaleY(selectedCard.getScaleY() * 0.985f);
            selectedCard.animate().scaleX(selectedCard.getScaleX() / 0.985f)
                    .scaleY(selectedCard.getScaleY() / 0.985f)
                    .setDuration(135L).start();
        }
    }

    private void applySelectedOverlay(View card) {
        card.getOverlay().clear();
        GradientDrawable outer = new GradientDrawable();
        outer.setColor(Color.TRANSPARENT);
        outer.setCornerRadius(dp(20));
        outer.setStroke(dp(3), Color.argb(245, 120, 178, 255));
        outer.setBounds(0, 0, card.getWidth(), card.getHeight());
        card.getOverlay().add(outer);

        GradientDrawable inner = new GradientDrawable();
        inner.setColor(Color.TRANSPARENT);
        inner.setCornerRadius(dp(17));
        inner.setStroke(dp(1), Color.argb(220, 245, 250, 255));
        inner.setBounds(dp(4), dp(4), Math.max(dp(4), card.getWidth() - dp(4)),
                Math.max(dp(4), card.getHeight() - dp(4)));
        card.getOverlay().add(inner);
    }

    private void installSelectedCardGesture(View card) {
        card.setOnTouchListener((v, event) -> {
            if (!isUStyle() || v != selectedCard) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cardDownX = event.getX();
                    cardDownY = event.getY();
                    cardGestureConsumed = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (cardGestureConsumed) return true;
                    float dx = event.getX() - cardDownX;
                    float dy = event.getY() - cardDownY;
                    if (Math.abs(dy) >= dp(42) && Math.abs(dy) > Math.abs(dx) * 1.25f) {
                        cardGestureConsumed = true;
                        v.setPressed(false);
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                        if (dy < 0f) showActions();
                        else showDetails();
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean consumed = cardGestureConsumed;
                    cardGestureConsumed = false;
                    return consumed;
                default:
                    return cardGestureConsumed;
            }
        });
    }

    private void showActions() {
        if (!validSelection()) return;
        activity.adapter.onLongClick(selectedIndex, selectedCard);
    }

    private void showDetails() {
        if (notificationScroller == null) return;
        notificationScroller.setVisibility(View.VISIBLE);
        notificationScroller.animate().cancel();
        notificationScroller.setAlpha(0.86f);
        notificationScroller.animate().alpha(1f).setDuration(140L).start();
        notificationScroller.post(() -> notificationScroller.smoothScrollTo(0,
                notificationCenter == null ? 0 : notificationCenter.getHeight()));
    }

    private boolean validSelection() {
        return selectedIndex >= 0 && activity.adapter != null
                && selectedIndex < activity.adapter.getCount() && selectedCard != null;
    }

    private void ensureHeader() {
        if (notificationCenter == null) return;
        View existing = notificationCenter.findViewWithTag(TAG_HEADER);
        if (existing instanceof LinearLayout) {
            header = (LinearLayout) existing;
            if (header.getChildCount() > 0 && header.getChildAt(0) instanceof TextView) title = (TextView) header.getChildAt(0);
            if (header.getChildCount() > 1 && header.getChildAt(1) instanceof TextView) subtitle = (TextView) header.getChildAt(1);
            return;
        }
        header = new LinearLayout(activity);
        header.setTag(TAG_HEADER);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(dp(10), dp(8), dp(10), dp(8));
        header.setElevation(dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(228, 14, 20, 34));
        background.setCornerRadius(dp(17));
        background.setStroke(dp(2), Color.argb(220, 104, 161, 255));
        header.setBackground(background);

        title = new AutoMarqueeTextView(activity);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17.5f);
        title.setGravity(Gravity.CENTER);

        subtitle = new AutoMarqueeTextView(activity);
        subtitle.setTextColor(Color.argb(225, 205, 222, 250));
        subtitle.setTextSize(12f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(3), 0, 0);

        header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(3), dp(3), dp(7));
        notificationCenter.addView(header, 0, lp);
    }

    private void updateHeader() {
        if (title == null || subtitle == null) return;
        if (selectedCard == null) {
            title.setText("Smart U");
            subtitle.setText("Swipe to browse • start typing to search");
            return;
        }
        String label = findCardLabel(selectedCard);
        title.setText(TextUtils.isEmpty(label) ? "Selected item" : label);
        String query = activity.searchEditText == null ? "" : activity.searchEditText.getText().toString().trim();
        String context = TextUtils.isEmpty(query) ? "Focused" : "Top search focus for “" + query + "”";
        subtitle.setText(context + " • swipe up actions • swipe down details");
        if (notificationScroller != null) notificationScroller.setVisibility(View.VISIBLE);
    }

    private String findCardLabel(View view) {
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (!TextUtils.isEmpty(value)) return value.toString().trim();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                String found = findCardLabel(group.getChildAt(i));
                if (!TextUtils.isEmpty(found)) return found;
            }
        }
        return "";
    }

    private void removeEnhancements() {
        if (selectedCard != null) {
            selectedCard.getOverlay().clear();
            selectedCard.setOnTouchListener(null);
        }
        for (Map.Entry<View, Float> entry : baseAlpha.entrySet()) {
            entry.getKey().setAlpha(entry.getValue());
        }
        for (Map.Entry<View, Float> entry : baseZ.entrySet()) {
            entry.getKey().setTranslationZ(entry.getValue());
        }
        baseAlpha.clear();
        baseZ.clear();
        if (header != null && header.getParent() instanceof ViewGroup) {
            ((ViewGroup) header.getParent()).removeView(header);
        }
        header = null;
        title = null;
        subtitle = null;
        selectedCard = null;
        selectedIndex = -1;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
