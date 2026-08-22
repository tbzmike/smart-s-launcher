package fr.neamar.kiss.battery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class BatteryPeriodBarView extends View {
    public static final class Bar {
        public final String label;
        public final float chargedPercent;
        public final float usedPercent;

        public Bar(String label, float chargedPercent, float usedPercent) {
            this.label = label;
            this.chargedPercent = Math.max(0f, chargedPercent);
            this.usedPercent = Math.max(0f, usedPercent);
        }
    }

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint charge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint used = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Bar> bars = new ArrayList<>();

    public BatteryPeriodBarView(Context context) { this(context, null); }

    public BatteryPeriodBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        grid.setColor(0x44FFFFFF);
        grid.setStrokeWidth(d);
        charge.setColor(0xFF42A5F5);
        used.setColor(0xFF7CB342);
        text.setColor(0xD9FFFFFF);
        text.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setBars(List<Bar> newBars) {
        bars = newBars == null ? new ArrayList<>() : new ArrayList<>(newBars);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float left = getPaddingLeft() + 36f;
        float right = w - getPaddingRight() - 8f;
        float top = getPaddingTop() + 8f;
        float bottom = h - getPaddingBottom() - 28f;
        if (bars.isEmpty()) {
            canvas.drawText("Collecting period history…", left, (top + bottom) / 2f, text);
            return;
        }
        float max = 100f;
        for (Bar b : bars) max = Math.max(max, Math.max(b.chargedPercent, b.usedPercent));
        max = (float) (Math.ceil(max / 100f) * 100f);
        for (int i = 0; i <= 4; i++) {
            float y = bottom - (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, grid);
            canvas.drawText(Integer.toString(Math.round(max * i / 4f)), 2f, y + text.getTextSize() / 2f, text);
        }
        float slot = (right - left) / Math.max(1, bars.size());
        float bw = Math.max(4f, slot * 0.28f);
        for (int i = 0; i < bars.size(); i++) {
            Bar b = bars.get(i);
            float cx = left + slot * (i + 0.5f);
            float ch = (bottom - top) * Math.min(max, b.chargedPercent) / max;
            float uh = (bottom - top) * Math.min(max, b.usedPercent) / max;
            canvas.drawRect(cx - bw - 1f, bottom - ch, cx - 1f, bottom, charge);
            canvas.drawRect(cx + 1f, bottom - uh, cx + bw + 1f, bottom, used);
            float tw = text.measureText(b.label);
            canvas.drawText(b.label, cx - tw / 2f, h - 5f, text);
        }
    }
}
