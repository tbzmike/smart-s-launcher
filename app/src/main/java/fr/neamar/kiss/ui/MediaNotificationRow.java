package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import fr.neamar.kiss.R;
import fr.neamar.kiss.notification.MediaControlClassifier;
import fr.neamar.kiss.notification.MediaNotificationSupport;

/** Notification history row that augments media notifications with art and live transport controls. */
public final class MediaNotificationRow extends LinearLayout {
    private LinearLayout mediaPanel;
    private String boundPackage;

    public MediaNotificationRow(Context context) {
        super(context);
    }

    public MediaNotificationRow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::refreshMedia);
    }

    public void refreshMedia() {
        TextView app = findViewById(R.id.item_notification_app);
        CharSequence label = app == null ? null : app.getText();
        MediaNotificationSupport.Snapshot snapshot =
                MediaNotificationSupport.snapshotForLabel(getContext(), label);
        if (snapshot == null || (snapshot.artwork == null && !snapshot.active)) {
            removeMediaPanel();
            return;
        }
        boundPackage = snapshot.packageName;
        ensureMediaPanel(snapshot);
    }

    private void ensureMediaPanel(MediaNotificationSupport.Snapshot snapshot) {
        View appView = findViewById(R.id.item_notification_app);
        if (appView == null || !(appView.getParent() instanceof LinearLayout)) return;
        LinearLayout content = (LinearLayout) appView.getParent();

        if (mediaPanel == null) {
            mediaPanel = new LinearLayout(getContext());
            mediaPanel.setOrientation(HORIZONTAL);
            mediaPanel.setGravity(Gravity.CENTER_VERTICAL);
            mediaPanel.setPadding(dp(6), dp(5), dp(6), dp(5));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(112, 0, 0, 0));
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), Color.argb(90, 255, 255, 255));
            mediaPanel.setBackground(bg);

            int insertAt = Math.min(2, content.getChildCount());
            LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            panelLp.topMargin = dp(4);
            panelLp.bottomMargin = dp(3);
            content.addView(mediaPanel, insertAt, panelLp);
        }

        mediaPanel.removeAllViews();
        if (snapshot.artwork != null) {
            ImageView art = new ImageView(getContext());
            art.setImageDrawable(snapshot.artwork);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setClipToOutline(true);
            GradientDrawable artShape = new GradientDrawable();
            artShape.setColor(Color.TRANSPARENT);
            artShape.setCornerRadius(dp(10));
            art.setBackground(artShape);
            LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(72), dp(72));
            artLp.rightMargin = dp(8);
            mediaPanel.addView(art, artLp);
        }

        LinearLayout controls = new LinearLayout(getContext());
        controls.setOrientation(HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        mediaPanel.addView(controls, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (snapshot.active) {
            if (snapshot.previous) addControl(controls, "⏮", MediaControlClassifier.Kind.PREVIOUS);
            if (snapshot.playPause) addControl(controls, snapshot.playing ? "⏸" : "▶",
                    MediaControlClassifier.Kind.PLAY_PAUSE);
            if (snapshot.next) addControl(controls, "⏭", MediaControlClassifier.Kind.NEXT);
        } else {
            TextView history = new TextView(getContext());
            history.setText("Album art · history");
            history.setTextColor(Color.WHITE);
            history.setTextSize(12f);
            history.setGravity(Gravity.CENTER);
            controls.addView(history, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mediaPanel.setVisibility(VISIBLE);
    }

    private void addControl(LinearLayout controls, String label, MediaControlClassifier.Kind kind) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextSize(18f);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(v -> {
            if (MediaNotificationSupport.perform(getContext(), boundPackage, kind)) {
                postDelayed(this::refreshMedia, 180L);
            }
        });
        controls.addView(button, new LinearLayout.LayoutParams(
                0, dp(48), 1f));
    }

    private void removeMediaPanel() {
        if (mediaPanel == null) return;
        ViewGroup parent = (ViewGroup) mediaPanel.getParent();
        if (parent != null) parent.removeView(mediaPanel);
        mediaPanel = null;
        boundPackage = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
