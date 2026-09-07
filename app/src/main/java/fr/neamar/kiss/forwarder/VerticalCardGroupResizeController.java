package fr.neamar.kiss.forwarder;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.preference.UiEditLock;

/**
 * Whole-stack size editor for the Vertical Cards history style.
 *
 * The editor intentionally changes every vertical card together. Width is applied to every card
 * wrapper after SmartCardListForwarder rebuilds. Height controls the existing vertical-card size
 * preferences as a coordinated group so icon, text, minimum height and spacing shrink/grow together
 * instead of leaving large WRAP_CONTENT cards behind.
 */
final class VerticalCardGroupResizeController {
    private static final String VERTICAL_CARDS = "vertical_cards";
    private static final String PREF_WIDTH = "smart-list-card-width-percent";
    private static final String PREF_HEIGHT = "smart-list-card-height-percent";
    private static final String PREF_ICON = "smart-list-card-icon-percent";
    private static final String PREF_NAME = "smart-list-card-name-percent";
    private static final String PREF_SPACING = "smart-list-card-spacing-dp";

    private static final int MIN_WIDTH = 48;
    private static final int MAX_WIDTH = 200;
    private static final int MIN_HEIGHT = 55;
    private static final int MAX_HEIGHT = 150;

    private final MainActivity activity;
    private final SmartCardListForwarder cardForwarder;
    private final VerticalCardViewportController viewportController;
    private final SharedPreferences prefs;

    private FrameLayout host;
    private LinearLayout column;
    private TextView resizeButton;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private AlertDialog dialog;

    VerticalCardGroupResizeController(MainActivity activity,
                                      SmartCardListForwarder cardForwarder,
                                      VerticalCardViewportController viewportController) {
        this.activity = activity;
        this.cardForwarder = cardForwarder;
        this.viewportController = viewportController;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    }

    void onCreate() {
        resolveViews();
        installButton();
        attachObserver();
        sync();
    }

    void onResume() {
        resolveViews();
        installButton();
        attachObserver();
        sync();
    }

    void onDataSetChanged() {
        resolveViews();
        attachObserver();
        sync();
    }

    void onConfigurationChanged() {
        dismissDialog();
        detachObserver();
        host = null;
        column = null;
        resolveViews();
        installButton();
        attachObserver();
        sync();
    }

    void onPause() {
        dismissDialog();
    }

    void onDestroy() {
        dismissDialog();
        detachObserver();
        if (resizeButton != null && resizeButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) resizeButton.getParent()).removeView(resizeButton);
        }
        resizeButton = null;
        host = null;
        column = null;
    }

    private boolean isEnabled() {
        return VERTICAL_CARDS.equals(prefs.getString(
                HistoryDisplayForwarder.PREF_LAYOUT, HistoryDisplayForwarder.VERTICAL));
    }

    private void resolveViews() {
        FrameLayout nextHost = activity.listContainer instanceof FrameLayout
                ? (FrameLayout) activity.listContainer : null;
        LinearLayout nextColumn = cardForwarder.getColumn();
        if (nextColumn != column) {
            detachObserver();
            column = nextColumn;
        }
        host = nextHost;
    }

    private void installButton() {
        if (host == null) return;
        if (resizeButton != null && resizeButton.getParent() == host) return;
        if (resizeButton != null && resizeButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) resizeButton.getParent()).removeView(resizeButton);
        }

        resizeButton = new TextView(activity);
        resizeButton.setText("↔↕");
        resizeButton.setTextColor(Color.WHITE);
        resizeButton.setTextSize(20f);
        resizeButton.setGravity(Gravity.CENTER);
        resizeButton.setContentDescription("Resize all Vertical Cards");
        resizeButton.setElevation(dp(28));
        resizeButton.setBackground(makeButtonBackground());
        resizeButton.setOnClickListener(v -> showResizeDialog());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(52), dp(52),
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(12);
        lp.rightMargin = dp(12);
        host.addView(resizeButton, lp);
    }

    private GradientDrawable makeButtonBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(225, 31, 38, 52));
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(2), Color.argb(230, 138, 180, 255));
        return bg;
    }

    private void attachObserver() {
        if (column == null || layoutListener != null) return;
        layoutListener = this::applyWidthToAllCards;
        column.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    private void detachObserver() {
        if (column != null && layoutListener != null) {
            ViewTreeObserver observer = column.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
    }

    private void sync() {
        boolean editable = isEnabled() && !UiEditLock.isLocked(activity);
        if (resizeButton != null) {
            resizeButton.setVisibility(editable ? View.VISIBLE : View.GONE);
            if (editable) resizeButton.bringToFront();
        }
        if (!isEnabled()) {
            dismissDialog();
            return;
        }
        applyWidthSoon();
    }

    private void applyWidthSoon() {
        if (column != null) column.post(this::applyWidthToAllCards);
    }

    private void applyWidthToAllCards() {
        if (!isEnabled() || column == null || column.getWidth() <= 0) return;
        int widthPercent = prefInt(PREF_WIDTH, 100, MIN_WIDTH, MAX_WIDTH);
        int available = Math.max(dp(120), column.getWidth() - column.getPaddingLeft()
                - column.getPaddingRight());
        int targetWidth = Math.round(available * widthPercent / 100f);

        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (!(raw instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            // Keep 100% as the exact historical full-width behaviour. Values above 100% are
            // deliberate oversized card widths rather than being collapsed back to MATCH_PARENT.
            int desired = widthPercent == 100
                    ? ViewGroup.LayoutParams.MATCH_PARENT : targetWidth;
            if (lp.width == desired && lp.gravity == Gravity.CENTER_HORIZONTAL) continue;
            lp.width = desired;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            child.setLayoutParams(lp);
        }
    }

    private void showResizeDialog() {
        if (!isEnabled() || UiEditLock.isLocked(activity)) return;
        dismissDialog();

        int width = prefInt(PREF_WIDTH, 100, MIN_WIDTH, MAX_WIDTH);
        int height = prefInt(PREF_HEIGHT, 100, MIN_HEIGHT, MAX_HEIGHT);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(12), dp(24), dp(10));

        TextView explanation = new TextView(activity);
        explanation.setText("Resize every Vertical Card together");
        explanation.setTextSize(15f);
        explanation.setPadding(0, 0, 0, dp(10));
        panel.addView(explanation);

        TextView widthLabel = new TextView(activity);
        widthLabel.setText("Width · " + width + "%");
        widthLabel.setTextSize(16f);
        panel.addView(widthLabel);

        SeekBar widthBar = new SeekBar(activity);
        widthBar.setMax(MAX_WIDTH - MIN_WIDTH);
        widthBar.setProgress(width - MIN_WIDTH);
        panel.addView(widthBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heightLabel = new TextView(activity);
        heightLabel.setText("Height · " + height + "%");
        heightLabel.setTextSize(16f);
        LinearLayout.LayoutParams heightLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heightLabelLp.topMargin = dp(14);
        panel.addView(heightLabel, heightLabelLp);

        SeekBar heightBar = new SeekBar(activity);
        heightBar.setMax(MAX_HEIGHT - MIN_HEIGHT);
        heightBar.setProgress(height - MIN_HEIGHT);
        panel.addView(heightBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        widthBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = MIN_WIDTH + progress;
                widthLabel.setText("Width · " + value + "%");
                if (fromUser) {
                    prefs.edit().putInt(PREF_WIDTH, value).apply();
                    applyWidthSoon();
                }
            }
        });

        heightBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = MIN_HEIGHT + progress;
                heightLabel.setText("Height · " + value + "%");
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = MIN_HEIGHT + seekBar.getProgress();
                applyHeightGroup(value);
            }
        });

        dialog = new AlertDialog.Builder(activity)
                .setTitle("Vertical Cards size")
                .setView(panel)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset", (d, which) -> {
                    prefs.edit()
                            .putInt(PREF_WIDTH, 100)
                            .putInt(PREF_HEIGHT, 100)
                            .putInt(PREF_ICON, 100)
                            .putInt(PREF_NAME, 100)
                            .putInt(PREF_SPACING, 12)
                            .apply();
                    rebuildCards();
                    applyWidthSoon();
                })
                .create();
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.show();
    }

    private void applyHeightGroup(int heightPercent) {
        int clamped = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, heightPercent));
        int iconPercent = Math.max(60, Math.min(180, clamped));
        int namePercent = Math.max(70, Math.min(170, clamped));
        int spacing = Math.max(4, Math.min(36, Math.round(12f * clamped / 100f)));

        prefs.edit()
                .putInt(PREF_HEIGHT, clamped)
                .putInt(PREF_ICON, iconPercent)
                .putInt(PREF_NAME, namePercent)
                .putInt(PREF_SPACING, spacing)
                .apply();
        rebuildCards();
        applyWidthSoon();
    }

    private void rebuildCards() {
        viewportController.beforeDataSetChanged();
        cardForwarder.onDataSetChanged();
        viewportController.afterDataSetChanged();
    }

    private void dismissDialog() {
        AlertDialog current = dialog;
        dialog = null;
        if (current != null && current.isShowing()) current.dismiss();
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
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // No-op.
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // Optional override.
        }
    }
}
