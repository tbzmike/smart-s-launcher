package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.neamar.kiss.MainActivity;

/** U-style-only contextual actions and vertical gestures for the focused Square-U result. */
final class SmartUActionForwarder extends Forwarder {
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String TAG_ACTIONS = "smart-u-action-row";

    private ViewGroup squareTrack;
    private ScrollView notificationScroller;
    private LinearLayout notificationCenter;
    private LinearLayout actionRow;
    private View selectedCard;
    private int selectedIndex = -1;
    private boolean listenerInstalled;
    private boolean refreshPosted;
    private float gestureDownX;
    private float gestureDownY;
    private boolean verticalGestureConsumed;

    SmartUActionForwarder(MainActivity mainActivity) {
        super(mainActivity);
    }

    void onCreate() {
        locateViews();
        installListener();
        refresh();
    }

    void onResume() {
        locateViews();
        installListener();
        refresh();
    }

    void onDataSetChanged() {
        if (!isUStyle()) return;
        locateViews();
        installListener();
        postRefresh();
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void locateViews() {
        if (!(mainActivity.listContainer instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) mainActivity.listContainer;
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View candidate = container.getChildAt(i);
            if (!(candidate instanceof FrameLayout)) continue;
            FrameLayout frame = (FrameLayout) candidate;
            ScrollView scroller = null;
            ViewGroup track = null;
            for (int j = 0; j < frame.getChildCount(); j++) {
                View child = frame.getChildAt(j);
                if (child instanceof ScrollView && !(child instanceof HorizontalScrollView)) {
                    scroller = (ScrollView) child;
                } else if (child instanceof ViewGroup) {
                    track = (ViewGroup) child;
                }
            }
            if (scroller == null || track == null) continue;
            View content = scroller.getChildCount() > 0 ? scroller.getChildAt(0) : null;
            if (!(content instanceof LinearLayout)) continue;
            squareTrack = track;
            notificationScroller = scroller;
            notificationCenter = (LinearLayout) content;
            return;
        }
    }

    private void installListener() {
        if (squareTrack == null || listenerInstalled) return;
        squareTrack.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (isUStyle()) postRefresh();
        });
        listenerInstalled = true;
    }

    private void refresh() {
        if (!isUStyle()) {
            removeActionRow();
            return;
        }
        locateViews();
        ensureActionRow();
        updateSelection();
    }

    private void postRefresh() {
        if (squareTrack == null || refreshPosted) return;
        refreshPosted = true;
        squareTrack.post(() -> {
            refreshPosted = false;
            refresh();
        });
    }

    private void updateSelection() {
        if (squareTrack == null || squareTrack.getChildCount() == 0) {
            clearSelectedCardGesture();
            selectedIndex = -1;
            setActionEnabled(false);
            return;
        }
        float targetX = squareTrack.getWidth() / 2f;
        float targetY = squareTrack.getHeight();
        float bestScore = Float.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (card.getVisibility() != View.VISIBLE) continue;
            float cx = card.getX() + card.getWidth() / 2f;
            float cy = card.getY() + card.getHeight() / 2f;
            float score = Math.abs(cx - targetX) + Math.abs(cy - targetY) * 0.38f;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        selectedIndex = best;
        View nextSelected = best >= 0 && best < squareTrack.getChildCount()
                ? squareTrack.getChildAt(best) : null;
        if (selectedCard != nextSelected) {
            clearSelectedCardGesture();
            selectedCard = nextSelected;
            installSelectedCardGesture();
        }
        setActionEnabled(best >= 0);
    }

    private void installSelectedCardGesture() {
        if (selectedCard == null) return;
        selectedCard.setOnTouchListener((v, event) -> {
            if (!isUStyle() || v != selectedCard) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    gestureDownX = event.getX();
                    gestureDownY = event.getY();
                    verticalGestureConsumed = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (verticalGestureConsumed) return true;
                    float dx = event.getX() - gestureDownX;
                    float dy = event.getY() - gestureDownY;
                    float threshold = dp(42);
                    if (Math.abs(dy) >= threshold && Math.abs(dy) > Math.abs(dx) * 1.25f) {
                        verticalGestureConsumed = true;
                        v.setPressed(false);
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                        if (dy < 0f) showSelectedActions();
                        else showSelectedDetails();
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean consumed = verticalGestureConsumed;
                    verticalGestureConsumed = false;
                    return consumed;
                default:
                    return verticalGestureConsumed;
            }
        });
    }

    private void clearSelectedCardGesture() {
        if (selectedCard != null) selectedCard.setOnTouchListener(null);
        selectedCard = null;
        verticalGestureConsumed = false;
    }

    private void ensureActionRow() {
        if (notificationCenter == null) return;
        View existing = notificationCenter.findViewWithTag(TAG_ACTIONS);
        if (existing instanceof LinearLayout) {
            actionRow = (LinearLayout) existing;
            return;
        }

        actionRow = new LinearLayout(mainActivity);
        actionRow.setTag(TAG_ACTIONS);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setPadding(dp(4), dp(2), dp(4), dp(8));

        TextView open = createChip("Open");
        open.setOnClickListener(v -> openSelected());
        open.setTag("smart-u-open");

        TextView actions = createChip("Actions");
        actions.setOnClickListener(v -> showSelectedActions());
        actions.setTag("smart-u-actions");

        TextView details = createChip("Details");
        details.setOnClickListener(v -> showSelectedDetails());
        details.setTag("smart-u-details");

        LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(0, dp(38), 1f);
        chip.setMargins(dp(3), 0, dp(3), 0);
        actionRow.addView(open, chip);
        actionRow.addView(actions, chip);
        actionRow.addView(details, chip);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(dp(3), 0, dp(3), dp(4));
        int insertAt = Math.min(1, notificationCenter.getChildCount());
        notificationCenter.addView(actionRow, insertAt, rowParams);
    }

    private TextView createChip(String text) {
        TextView chip = new TextView(mainActivity);
        chip.setText(text);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(13f);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setElevation(dp(5));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(225, 28, 40, 66));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.argb(220, 112, 168, 255));
        chip.setBackground(background);
        return chip;
    }

    private void openSelected() {
        if (!hasValidSelection()) return;
        View card = selectedCard != null ? selectedCard : actionRow;
        mainActivity.adapter.onClick(selectedIndex, card);
    }

    private void showSelectedActions() {
        if (!hasValidSelection()) return;
        View card = selectedCard != null ? selectedCard : actionRow;
        mainActivity.adapter.onLongClick(selectedIndex, card);
    }

    private void showSelectedDetails() {
        if (!isUStyle() || notificationScroller == null || notificationCenter == null) return;
        int targetY = Math.max(0, notificationCenter.getMeasuredHeight() - notificationScroller.getHeight());
        notificationScroller.smoothScrollTo(0, targetY);
        if (selectedCard != null) {
            selectedCard.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        if (actionRow != null) {
            actionRow.animate().cancel();
            actionRow.setAlpha(0.82f);
            actionRow.animate().alpha(1f).setDuration(140L).start();
        }
    }

    private boolean hasValidSelection() {
        return isUStyle() && selectedIndex >= 0 && mainActivity.adapter != null
                && selectedIndex < mainActivity.adapter.getCount();
    }

    private void setActionEnabled(boolean enabled) {
        if (actionRow == null) return;
        actionRow.setAlpha(enabled ? 1f : 0.45f);
        for (int i = 0; i < actionRow.getChildCount(); i++) {
            actionRow.getChildAt(i).setEnabled(enabled);
        }
    }

    private void removeActionRow() {
        clearSelectedCardGesture();
        selectedIndex = -1;
        if (actionRow != null && actionRow.getParent() instanceof ViewGroup) {
            ((ViewGroup) actionRow.getParent()).removeView(actionRow);
        }
        actionRow = null;
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}
