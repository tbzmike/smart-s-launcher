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

/** Live visual feedback for settings that alter launcher UI or UX. */
public final class UiLivePreviewPreference extends Preference
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String TYPE_HISTORY = "history";
    public static final String TYPE_UI = "ui";
    public static final String TYPE_UX = "ux";
    public static final String TYPE_ANIMATIONS = "animations";
    public static final String TYPE_WALLPAPER = "wallpaper";
    public static final String TYPE_WORKSPACE = "workspace";

    private final SharedPreferences prefs;
    private final String type;
    private PreviewView preview;

    public UiLivePreviewPreference(@NonNull Context context, @NonNull String type) {
        super(context);
        this.type = type;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        setKey("live-preview-" + type);
        setLayoutResource(R.layout.preference_ui_live_preview);
        setSelectable(false);
        setPersistent(false);
        setOrder(-10000);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDetached() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (preview != null) preview.stop();
        preview = null;
        super.onDetached();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        TextView title = (TextView) holder.findViewById(R.id.ui_preview_title);
        TextView summary = (TextView) holder.findViewById(R.id.ui_preview_summary);
        FrameLayout host = (FrameLayout) holder.findViewById(R.id.ui_preview_canvas);
        if (title != null) title.setText(title());
        if (summary != null) summary.setText(summary());
        if (host == null) return;
        host.removeAllViews();
        if (preview != null) preview.stop();
        preview = new PreviewView(getContext(), type, prefs);
        host.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        preview.refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null || !relevant(key)) return;
        if ("primary-color".equals(key)) UIColors.clearColorCache();
        if (preview != null) preview.refresh();
    }

    private boolean relevant(String key) {
        switch (type) {
            case TYPE_HISTORY:
                return key.startsWith("smart-u-") || key.startsWith("smart-horizontal-")
                        || key.startsWith("smart-list-") || "smart-history-layout".equals(key);
            case TYPE_ANIMATIONS:
                return key.startsWith("smart-animation-") || "smart-animations-enabled".equals(key);
            case TYPE_WORKSPACE:
                return key.startsWith("smart-workspace-");
            case TYPE_WALLPAPER:
                return key.startsWith("smart-focus-") || key.startsWith("smart-blur-")
                        || key.contains("wallpaper");
            case TYPE_UI:
                return key.contains("theme") || key.contains("color") || key.contains("icon")
                        || key.contains("result") || key.contains("rounded") || key.contains("margin")
                        || key.contains("transparent") || key.contains("favorite") || key.contains("bar");
            case TYPE_UX:
                return key.startsWith("gesture-") || key.contains("keyboard") || key.contains("hide")
                        || key.startsWith("smart-animation-") || "smart-animations-enabled".equals(key);
            default:
                return true;
        }
    }

    private String title() {
        switch (type) {
            case TYPE_HISTORY: return "Live history preview";
            case TYPE_UI: return "Live interface preview";
            case TYPE_UX: return "Live experience preview";
            case TYPE_ANIMATIONS: return "Live animation preview";
            case TYPE_WALLPAPER: return "Live wallpaper preview";
            case TYPE_WORKSPACE: return "Live workspace preview";
            default: return "Live preview";
        }
    }

    private String summary() {
        switch (type) {
            case TYPE_HISTORY: return "Drag the controls below — size, icons, notification box and spacing update here immediately.";
            case TYPE_ANIMATIONS: return "Animation style and speed replay here immediately.";
            case TYPE_WORKSPACE: return "Pane direction and split size update immediately.";
            case TYPE_WALLPAPER: return "Wallpaper, blur and focus changes update immediately.";
            case TYPE_UI: return "Theme, colors, icons, result shape and layout changes update here as they are saved.";
            case TYPE_UX: return "Visibility, keyboard and motion-related changes are shown here immediately.";
            default: return "Changes appear here immediately.";
        }
    }

    private static final class PreviewView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SharedPreferences prefs;
        private final String type;
        private ValueAnimator animator;
        private float phase;

        PreviewView(Context context, String type, SharedPreferences prefs) {
            super(context);
            this.type = type;
            this.prefs = prefs;
        }

        void refresh() {
            stop();
            phase = 0f;
            invalidate();
            if ((TYPE_ANIMATIONS.equals(type) || TYPE_UX.equals(type))
                    && prefs.getBoolean("smart-animations-enabled", true)
                    && !"none".equals(prefs.getString("smart-animation-scroll", "classic"))) {
                int speed = number("smart-animation-speed-percent", 100, 5, 300);
                animator = ValueAnimator.ofFloat(0f, 1f);
                animator.setDuration(Math.max(260L, Math.round(1100f * 100f / speed)));
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.setRepeatMode(ValueAnimator.REVERSE);
                animator.setInterpolator(new DecelerateInterpolator());
                animator.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
                animator.start();
            }
        }

        void stop() {
            if (animator != null) animator.cancel();
            animator = null;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(17, 19, 24));
            c.drawRoundRect(new RectF(0, 0, w, h), dp(18), dp(18), p);
            switch (type) {
                case TYPE_HISTORY: history(c, w, h); break;
                case TYPE_WORKSPACE: workspace(c, w, h); break;
                case TYPE_WALLPAPER: wallpaper(c, w, h); break;
                case TYPE_ANIMATIONS: animation(c, w, h); break;
                case TYPE_UX: ui(c, w, h); moving(c, w, h); break;
                default: ui(c, w, h); break;
            }
        }

        private void history(Canvas c, float w, float h) {
            int tilePct = number("smart-u-tile-size-percent", 100, 70, 150);
            int iconPct = number("smart-u-icon-size-percent", 100, 60, 160);
            int panelPct = number("smart-u-notification-panel-size-percent", 100, 55, 150);
            int contentPct = number("smart-u-notification-content-size-percent", 100, 65, 140);
            int gap = number("smart-u-notification-gap-dp", 28, 8, 96);
            float tw = dp(50) * tilePct / 100f;
            float th = dp(64) * tilePct / 100f;
            float icon = Math.min(tw * .52f, dp(28) * iconPct / 100f);
            float bottom = h - dp(10) - th;
            for (int i = 0; i < 3; i++) {
                float y = bottom - i * th * .55f;
                tile(c, dp(7), y, tw, th, icon, i);
                tile(c, w - dp(7) - tw, y, tw, th, icon, i + 3);
            }
            tile(c, w / 2f - tw / 2f, bottom, tw, th, icon, 6);

            float pw = Math.min(w * .62f, w * .42f * panelPct / 100f + dp(34));
            float ph = Math.min(h * .50f, h * .30f * panelPct / 100f + dp(16));
            float pb = bottom - dp(Math.min(70, gap));
            RectF box = new RectF(w / 2f - pw / 2f, Math.max(dp(8), pb - ph),
                    w / 2f + pw / 2f, pb);
            p.setColor(Color.rgb(29, 33, 41));
            c.drawRoundRect(box, dp(12), dp(12), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(Color.rgb(92, 112, 220));
            c.drawRoundRect(box, dp(12), dp(12), p);
            p.setStyle(Paint.Style.FILL);
            float row = dp(16) * contentPct / 100f;
            p.setColor(Color.rgb(52, 57, 68));
            c.drawRoundRect(new RectF(box.left + dp(8), box.top + dp(18), box.right - dp(8),
                    box.top + dp(18) + row), dp(6), dp(6), p);
        }

        private void tile(Canvas c, float x, float y, float w, float h, float icon, int seed) {
            RectF r = new RectF(x, y, x + w, y + h);
            p.setColor(Color.rgb(43, 47, 57));
            c.drawRoundRect(r, dp(10), dp(10), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(Color.rgb(205, 215, 232));
            c.drawRoundRect(r, dp(10), dp(10), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(seed % 2 == 0 ? Color.rgb(82, 140, 255) : Color.rgb(83, 205, 144));
            c.drawCircle(r.centerX(), r.top + h * .35f, icon / 2f, p);
            p.setColor(Color.argb(175, 0, 0, 0));
            c.drawRoundRect(new RectF(r.left + dp(4), r.bottom - dp(16), r.right - dp(4),
                    r.bottom - dp(4)), dp(4), dp(4), p);
        }

        private void ui(Canvas c, float w, float h) {
            int primary = UIColors.getPrimaryColor(getContext());
            boolean roundedBars = prefs.getBoolean("pref-rounded-bars", true);
            boolean roundedRows = prefs.getBoolean("pref-rounded-list", false);
            boolean hideIcons = prefs.getBoolean("icons-hide", false);
            float margin = prefs.getBoolean("large-result-list-margins", false) ? dp(27) : dp(11);
            p.setColor(Color.argb(80, Color.red(primary), Color.green(primary), Color.blue(primary)));
            c.drawRect(0, 0, w, h, p);
            for (int i = 0; i < 3; i++) {
                float top = dp(12) + i * dp(42);
                RectF row = new RectF(margin, top, w - margin, top + dp(34));
                p.setColor(Color.rgb(37, 40, 48));
                c.drawRoundRect(row, roundedRows ? dp(14) : dp(4), roundedRows ? dp(14) : dp(4), p);
                if (!hideIcons) {
                    p.setColor(i == 0 ? primary : Color.rgb(125, 134, 150));
                    c.drawCircle(row.left + dp(18), row.centerY(), dp(10), p);
                }
                p.setColor(Color.rgb(225, 229, 237));
                float start = row.left + (hideIcons ? dp(12) : dp(36));
                c.drawRoundRect(new RectF(start, row.centerY() - dp(3), row.right - dp(15),
                        row.centerY() + dp(3)), dp(3), dp(3), p);
            }
            p.setColor(Color.rgb(29, 31, 37));
            c.drawRoundRect(new RectF(margin, h - dp(42), w - margin, h - dp(12)),
                    roundedBars ? dp(17) : 0, roundedBars ? dp(17) : 0, p);
            p.setColor(primary);
            c.drawCircle(w - margin - dp(17), h - dp(27), dp(8), p);
        }

        private void animation(Canvas c, float w, float h) {
            p.setColor(Color.LTGRAY);
            p.setTextSize(dp(12));
            String style = prefs.getString("smart-animation-scroll", "classic");
            int speed = number("smart-animation-speed-percent", 100, 5, 300);
            c.drawText("Scroll: " + style + "   Speed: " + speed + "%", dp(12), dp(22), p);
            moving(c, w, h);
        }

        private void moving(Canvas c, float w, float h) {
            String style = prefs.getString("smart-animation-scroll", "classic");
            float x = w / 2f;
            float y = h / 2f;
            float scale = 1f;
            float rot = 0f;
            float alpha = 1f;
            if (prefs.getBoolean("smart-animations-enabled", true)) {
                switch (style == null ? "classic" : style) {
                    case "wave": y += (phase - .5f) * dp(34); break;
                    case "slide": x += (phase - .5f) * dp(88); break;
                    case "zoom": scale = .72f + phase * .38f; break;
                    case "tilt": rot = -14f + phase * 28f; break;
                    case "stack": x += (phase - .5f) * dp(34); scale = .88f + phase * .12f; break;
                    case "cascade": y += (1f - phase) * dp(32); alpha = .55f + phase * .45f; break;
                    case "focus":
                    case "depth": scale = .88f + phase * .14f; alpha = .72f + phase * .28f; break;
                    default: y += (1f - phase) * dp(14); break;
                }
            }
            c.save();
            c.translate(x, y);
            c.rotate(rot);
            c.scale(scale, scale);
            p.setColor(Color.argb(Math.round(alpha * 255f), 70, 135, 255));
            c.drawRoundRect(new RectF(-dp(38), -dp(28), dp(38), dp(28)), dp(12), dp(12), p);
            p.setColor(Color.WHITE);
            c.drawCircle(0, -dp(4), dp(10), p);
            c.restore();
        }

        private void wallpaper(Canvas c, float w, float h) {
            p.setColor(Color.rgb(24, 39, 65));
            c.drawRect(0, 0, w, h, p);
            for (int i = 0; i < 12; i++) {
                p.setColor(Color.argb(150, 220, 230, 255));
                c.drawCircle((i * 47) % Math.max(1, (int) w),
                        (i * 31) % Math.max(1, (int) h), dp(1 + i % 2), p);
            }
            if (prefs.getBoolean("smart-focus-blur-enabled", false)) {
                String strength = prefs.getString("smart-blur-strength", "balanced");
                int alpha = "strong".equals(strength) ? 180 : ("light".equals(strength) ? 80 : 125);
                p.setColor(Color.argb(alpha, 12, 14, 22));
                c.drawRect(0, 0, w, h, p);
                p.setColor(Color.argb(110, 105, 155, 255));
                c.drawCircle(w / 2f, h / 2f, dp(44), p);
            }
            p.setColor(Color.WHITE);
            c.drawCircle(w / 2f, h / 2f, dp(13), p);
        }

        private void workspace(Canvas c, float w, float h) {
            boolean enabled = prefs.getBoolean("smart-workspace-enabled", false);
            String orientation = prefs.getString("smart-workspace-orientation", "horizontal");
            int split = number("smart-workspace-split-percent", 50, 15, 85);
            if (!enabled) {
                p.setColor(Color.LTGRAY);
                p.setTextSize(dp(13));
                c.drawText("Enable workspace to preview pane sizing", dp(17), h / 2f, p);
                return;
            }
            p.setColor(Color.rgb(45, 91, 168));
            if ("vertical".equals(orientation)) {
                float y = dp(10) + (h - dp(20)) * split / 100f;
                c.drawRoundRect(new RectF(dp(12), dp(12), w - dp(12), y - dp(5)), dp(9), dp(9), p);
                p.setColor(Color.rgb(95, 104, 122));
                c.drawRect(dp(10), y - dp(2), w - dp(10), y + dp(2), p);
            } else {
                float x = dp(10) + (w - dp(20)) * split / 100f;
                c.drawRoundRect(new RectF(dp(12), dp(12), x - dp(5), h - dp(12)), dp(9), dp(9), p);
                p.setColor(Color.rgb(95, 104, 122));
                c.drawRect(x - dp(2), dp(10), x + dp(2), h - dp(10), p);
            }
        }

        private int number(String key, int fallback, int min, int max) {
            Object raw = prefs.getAll().get(key);
            int value = fallback;
            if (raw instanceof Number) value = Math.round(((Number) raw).floatValue());
            else if (raw instanceof String) {
                try { value = Math.round(Float.parseFloat((String) raw)); }
                catch (NumberFormatException ignored) { value = fallback; }
            }
            return Math.max(min, Math.min(max, value));
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
