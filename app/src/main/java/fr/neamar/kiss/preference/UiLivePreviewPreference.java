package fr.neamar.kiss.preference;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

import fr.neamar.kiss.R;
import fr.neamar.kiss.UIColors;

/**
 * Reusable live preview placed at the top of settings screens that change visible launcher UI/UX.
 * It listens to the same SharedPreferences as the real launcher and redraws immediately, so the
 * preview cannot drift away from the values that are actually persisted and consumed by runtime.
 */
public final class UiLivePreviewPreference extends Preference
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String TYPE_HISTORY = "history";
    public static final String TYPE_UI = "ui";
    public static final String TYPE_UX = "ux";
    public static final String TYPE_ANIMATIONS = "animations";
    public static final String TYPE_WALLPAPER = "wallpaper";
    public static final String TYPE_WORKSPACE = "workspace";

    private final SharedPreferences prefs;
    private final String previewType;
    private PreviewCanvas boundCanvas;

    public UiLivePreviewPreference(@NonNull Context context, @NonNull String previewType) {
        super(context);
        this.previewType = previewType;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
        setKey("live-preview-" + previewType);
        setLayoutResource(R.layout.preference_ui_live_preview);
        setSelectable(false);
        setPersistent(false);
        setOrder(-10000);
    }

    @Override
    protected void onAttached() {
        super.onAttached();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetached() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (boundCanvas != null) boundCanvas.stopAnimation();
        boundCanvas = null;
        super.onDetached();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView title = (TextView) holder.findViewById(R.id.ui_preview_title);
        TextView summary = (TextView) holder.findViewById(R.id.ui_preview_summary);
        FrameLayout host = (FrameLayout) holder.findViewById(R.id.ui_preview_canvas);
        if (title != null) title.setText(titleForType());
        if (summary != null) summary.setText(summaryForType());
        if (host == null) return;

        host.removeAllViews();
        if (boundCanvas != null) boundCanvas.stopAnimation();
        boundCanvas = new PreviewCanvas(getContext(), previewType, prefs);
        host.addView(boundCanvas, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        boundCanvas.refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null || !isRelevantKey(key)) return;
        if (boundCanvas != null) boundCanvas.refresh();
        notifyChanged();
    }

    private boolean isRelevantKey(String key) {
        switch (previewType) {
            case TYPE_HISTORY:
                return key.startsWith("smart-u-")
                        || key.startsWith("smart-horizontal-")
                        || key.startsWith("smart-list-")
                        || "smart-history-layout".equals(key);
            case TYPE_ANIMATIONS:
                return key.startsWith("smart-animation-") || "smart-animations-enabled".equals(key);
            case TYPE_WORKSPACE:
                return key.startsWith("smart-workspace-");
            case TYPE_WALLPAPER:
                return key.startsWith("smart-focus-") || key.startsWith("smart-blur-")
                        || key.contains("wallpaper");
            case TYPE_UI:
                return key.contains("theme") || key.contains("color") || key.contains("icon")
                        || key.contains("result") || key.contains("rounded")
                        || key.contains("margin") || key.contains("transparent")
                        || key.contains("favorite") || key.contains("bar");
            case TYPE_UX:
                return key.startsWith("gesture-") || key.contains("keyboard")
                        || key.contains("history-hide") || key.contains("favorites-hide")
                        || key.startsWith("pref-hide-") || key.startsWith("smart-animation-")
                        || "smart-animations-enabled".equals(key);
            default:
                return true;
        }
    }

    private String titleForType() {
        switch (previewType) {
            case TYPE_HISTORY: return "Live history preview";
            case TYPE_UI: return "Live interface preview";
            case TYPE_UX: return "Live experience preview";
            case TYPE_ANIMATIONS: return "Live animation preview";
            case TYPE_WALLPAPER: return "Live wallpaper preview";
            case TYPE_WORKSPACE: return "Live workspace preview";
            default: return "Live preview";
        }
    }

    private String summaryForType() {
        switch (previewType) {
            case TYPE_HISTORY:
                return "Drag the sizing controls below — tiles, icons, notification box and spacing update here immediately.";
            case TYPE_ANIMATIONS:
                return "Animation style and speed replay here whenever you change them.";
            case TYPE_WORKSPACE:
                return "Pane direction and split size update immediately.";
            case TYPE_WALLPAPER:
                return "Blur/focus choices update immediately without leaving Settings.";
            case TYPE_UI:
                return "Theme, color, icon, result and layout changes are reflected here as soon as they are saved.";
            case TYPE_UX:
                return "Visibility and motion-related experience changes are shown here as soon as they change.";
            default:
                return "Changes appear here immediately.";
        }
    }

    private static final class PreviewCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SharedPreferences prefs;
        private final String type;
        private ValueAnimator animator;
        private float phase;

        PreviewCanvas(Context context, String type, SharedPreferences prefs) {
            super(context);
            this.type = type;
            this.prefs = prefs;
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        void refresh() {
            stopAnimation();
            phase = 0f;
            invalidate();
            if (TYPE_ANIMATIONS.equals(type) || TYPE_UX.equals(type)) startAnimation();
        }

        void stopAnimation() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        private void startAnimation() {
            if (!prefs.getBoolean("smart-animations-enabled", true)) return;
            String style = prefs.getString("smart-animation-scroll", "depth");
            if ("none".equals(style)) return;
            int speed = intPref("smart-animation-speed-percent", 100, 5, 300);
            long duration = Math.max(260L, Math.round(1100f * 100f / speed));
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(17, 19, 24));
            canvas.drawRoundRect(new RectF(0, 0, w, h), dp(18), dp(18), paint);

            switch (type) {
                case TYPE_HISTORY: drawHistory(canvas, w, h); break;
                case TYPE_WORKSPACE: drawWorkspace(canvas, w, h); break;
                case TYPE_WALLPAPER: drawWallpaper(canvas, w, h); break;
                case TYPE_ANIMATIONS: drawAnimation(canvas, w, h); break;
                case TYPE_UX: drawUx(canvas, w, h); break;
                case TYPE_UI:
                default: drawUi(canvas, w, h); break;
            }
        }

        private void drawHistory(Canvas canvas, float w, float h) {
            int tilePercent = intPref("smart-u-tile-size-percent", 100, 70, 150);
            int iconPercent = intPref("smart-u-icon-size-percent", 100, 60, 160);
            int panelPercent = intPref("smart-u-notification-panel-size-percent", 100, 55, 150);
            int contentPercent = intPref("smart-u-notification-content-size-percent", 100, 65, 140);
            int gap = intPref("smart-u-notification-gap-dp", 28, 8, 96);

            float tileW = dp(54) * tilePercent / 100f;
            float tileH = dp(70) * tilePercent / 100f;
            float icon = Math.min(tileW * 0.48f, dp(28) * iconPercent / 100f);
            float bottom = h - dp(14) - tileH;
            float sideX = dp(10);
            float rightX = w - dp(10) - tileW;

            for (int i = 0; i < 3; i++) {
                float y = bottom - i * (tileH * 0.58f);
                drawTile(canvas, sideX, y, tileW, tileH, icon, i);
                drawTile(canvas, rightX, y, tileW, tileH, icon, i + 3);
            }
            drawTile(canvas, w / 2f - tileW / 2f, bottom, tileW, tileH, icon, 6);

            float panelW = Math.min(w * 0.56f, w * 0.42f * panelPercent / 100f + dp(40));
            float panelH = Math.min(h * 0.58f, h * 0.34f * panelPercent / 100f + dp(18));
            float panelBottom = bottom - dp(Math.min(72, gap));
            float panelTop = Math.max(dp(10), panelBottom - panelH);
            RectF panel = new RectF(w / 2f - panelW / 2f, panelTop,
                    w / 2f + panelW / 2f, panelTop + panelH);
            paint.setColor(Color.rgb(27, 31, 39));
            canvas.drawRoundRect(panel, dp(14), dp(14), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(180, 115, 137, 255));
            canvas.drawRoundRect(panel, dp(14), dp(14), paint);
            paint.setStyle(Paint.Style.FILL);

            float rowH = Math.max(dp(12), dp(18) * contentPercent / 100f);
            paint.setColor(Color.rgb(45, 49, 60));
            canvas.drawRoundRect(new RectF(panel.left + dp(8), panel.top + dp(18),
                    panel.right - dp(8), panel.top + dp(18) + rowH), dp(7), dp(7), paint);
        }

        private void drawTile(Canvas canvas, float x, float y, float width, float height,
                              float icon, int seed) {
            RectF rect = new RectF(x, y, x + width, y + height);
            paint.setColor(Color.rgb(39, 43, 52));
            canvas.drawRoundRect(rect, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(170, 215, 224, 240));
            canvas.drawRoundRect(rect, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(seed % 2 == 0 ? Color.rgb(92, 144, 255) : Color.rgb(96, 210, 150));
            canvas.drawCircle(rect.centerX(), rect.top + height * 0.36f, icon / 2f, paint);
            paint.setColor(Color.argb(170, 0, 0, 0));
            canvas.drawRoundRect(new RectF(rect.left + dp(4), rect.bottom - dp(18),
                    rect.right - dp(4), rect.bottom - dp(4)), dp(5), dp(5), paint);
        }

        private void drawUi(Canvas canvas, float w, float h) {
            int primary = UIColors.getPrimaryColor(getContext());
            boolean roundedBars = prefs.getBoolean("pref-rounded-bars", true);
            boolean roundedList = prefs.getBoolean("pref-rounded-list", false);
            boolean hideIcons = prefs.getBoolean("icons-hide", false);
            boolean largeMargins = prefs.getBoolean("large-result-list-margins", false);
            float margin = largeMargins ? dp(28) : dp(12);

            paint.setColor(Color.argb(90, Color.red(primary), Color.green(primary), Color.blue(primary)));
            canvas.drawRect(0, 0, w, h, paint);

            float barRadius = roundedBars ? dp(18) : 0f;
            paint.setColor(Color.argb(220, 22, 24, 30));
            canvas.drawRoundRect(new RectF(margin, h - dp(42), w - margin, h - dp(12)),
                    barRadius, barRadius, paint);
            paint.setColor(primary);
            canvas.drawCircle(w - margin - dp(18), h - dp(27), dp(8), paint);

            for (int i = 0; i < 3; i++) {
                float top = dp(14) + i * dp(42);
                RectF row = new RectF(margin, top, w - margin, top + dp(34));
                paint.setColor(Color.rgb(35, 38, 46));
                canvas.drawRoundRect(row, roundedList ? dp(14) : dp(4), roundedList ? dp(14) : dp(4), paint);
                if (!hideIcons) {
                    paint.setColor(i == 0 ? primary : Color.rgb(120, 130, 150));
                    canvas.drawCircle(row.left + dp(18), row.centerY(), dp(10), paint);
                }
                paint.setColor(Color.argb(210, 235, 238, 244));
                float start = row.left + (hideIcons ? dp(12) : dp(36));
                canvas.drawRoundRect(new RectF(start, row.centerY() - dp(3), row.right - dp(18),
                        row.centerY() + dp(3)), dp(3), dp(3), paint);
            }
        }

        private void drawUx(Canvas canvas, float w, float h) {
            drawUi(canvas, w, h);
            if (prefs.getBoolean("pref-hide-navbar", false)) {
                paint.setColor(Color.argb(190, 245, 245, 245));
                canvas.drawRoundRect(new RectF(w / 2f - dp(34), h - dp(7),
                        w / 2f + dp(34), h - dp(4)), dp(2), dp(2), paint);
            }
            drawMovingSample(canvas, w, h);
        }

        private void drawAnimation(Canvas canvas, float w, float h) {
            paint.setColor(Color.argb(120, 255, 255, 255));
            paint.setTextSize(dp(12));
            String style = prefs.getString("smart-animation-scroll", "depth");
            int speed = intPref("smart-animation-speed-percent", 100, 5, 300);
            canvas.drawText("Scroll: " + style + "   Speed: " + speed + "%", dp(14), dp(24), paint);
            drawMovingSample(canvas, w, h);
        }

        private void drawMovingSample(Canvas canvas, float w, float h) {
            String style = prefs.getString("smart-animation-scroll", "depth");
            float x = w / 2f - dp(38);
            float y = h / 2f - dp(28);
            float scale = 1f;
            float rotation = 0f;
            float alpha = 1f;
            if (prefs.getBoolean("smart-animations-enabled", true)) {
                switch (style == null ? "depth" : style) {
                    case "wave": y += (phase - 0.5f) * dp(32); break;
                    case "slide": x += (phase - 0.5f) * dp(80); break;
                    case "zoom": scale = 0.72f + phase * 0.38f; break;
                    case "tilt": rotation = -14f + phase * 28f; break;
                    case "stack": x += (phase - 0.5f) * dp(34); scale = 0.88f + phase * 0.12f; break;
                    case "cascade": y += (1f - phase) * dp(30); alpha = 0.55f + phase * 0.45f; break;
                    case "focus":
                    case "depth": scale = 0.88f + phase * 0.14f; alpha = 0.72f + phase * 0.28f; break;
                    case "classic": y += (1f - phase) * dp(16); break;
                    default: break;
                }
            }
            canvas.save();
            canvas.translate(x + dp(38), y + dp(28));
            canvas.rotate(rotation);
            canvas.scale(scale, scale);
            paint.setColor(Color.argb(Math.round(alpha * 255f), 70, 135, 255));
            canvas.drawRoundRect(new RectF(-dp(38), -dp(28), dp(38), dp(28)), dp(12), dp(12), paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(0, -dp(4), dp(10), paint);
            paint.setColor(Color.argb(190, 0, 0, 0));
            canvas.drawRoundRect(new RectF(-dp(27), dp(12), dp(27), dp(18)), dp(3), dp(3), paint);
            canvas.restore();
        }

        private void drawWallpaper(Canvas canvas, float w, float h) {
            paint.setColor(Color.rgb(24, 38, 62));
            canvas.drawRect(0, 0, w, h, paint);
            for (int i = 0; i < 11; i++) {
                paint.setColor(Color.argb(130 + (i % 3) * 30, 210, 225, 255));
                float x = ((i * 47) % Math.max(1, (int) w));
                float y = ((i * 31) % Math.max(1, (int) h));
                canvas.drawCircle(x, y, dp(1 + (i % 2)), paint);
            }
            boolean blur = prefs.getBoolean("smart-focus-blur-enabled", false);
            if (blur) {
                String strength = prefs.getString("smart-blur-strength", "balanced");
                int alpha = "strong".equals(strength) ? 180 : ("light".equals(strength) ? 80 : 125);
                paint.setColor(Color.argb(alpha, 12, 14, 22));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setColor(Color.argb(110, 115, 165, 255));
                canvas.drawCircle(w / 2f, h / 2f, dp(44), paint);
            }
            paint.setColor(Color.WHITE);
            canvas.drawCircle(w / 2f, h / 2f, dp(13), paint);
        }

        private void drawWorkspace(Canvas canvas, float w, float h) {
            boolean enabled = prefs.getBoolean("smart-workspace-enabled", false);
            String orientation = prefs.getString("smart-workspace-orientation", "horizontal");
            int split = intPref("smart-workspace-split-percent", 50, 15, 85);
            paint.setColor(Color.rgb(31, 35, 43));
            canvas.drawRoundRect(new RectF(dp(10), dp(10), w - dp(10), h - dp(10)), dp(12), dp(12), paint);
            if (!enabled) {
                paint.setColor(Color.argb(180, 255, 255, 255));
                paint.setTextSize(dp(13));
                canvas.drawText("Enable workspace to preview pane sizing", dp(20), h / 2f, paint);
                return;
            }
            paint.setColor(Color.rgb(59, 67, 82));
            if ("vertical".equals(orientation)) {
                float y = dp(10) + (h - dp(20)) * split / 100f;
                canvas.drawRect(dp(10), y - dp(2), w - dp(10), y + dp(2), paint);
                paint.setColor(Color.rgb(45, 91, 168));
                canvas.drawRoundRect(new RectF(dp(16), dp(16), w - dp(16), y - dp(8)), dp(8), dp(8), paint);
            } else {
                float x = dp(10) + (w - dp(20)) * split / 100f;
                canvas.drawRect(x - dp(2), dp(10), x + dp(2), h - dp(10), paint);
                paint.setColor(Color.rgb(45, 91, 168));
                canvas.drawRoundRect(new RectF(dp(16), dp(16), x - dp(8), h - dp(16)), dp(8), dp(8), paint);
            }
        }

        private int intPref(String key, int fallback, int min, int max) {
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

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
