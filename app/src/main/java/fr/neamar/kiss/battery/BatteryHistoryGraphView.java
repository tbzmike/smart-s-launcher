package fr.neamar.kiss.battery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class BatteryHistoryGraphView extends View {
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tempPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<BatteryHistoryStore.SamplePoint> points = new ArrayList<>();

    public BatteryHistoryGraphView(Context context) { this(context, null); }

    public BatteryHistoryGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        gridPaint.setColor(0x44FFFFFF);
        gridPaint.setStrokeWidth(d);
        levelPaint.setColor(0xFF64B5F6);
        levelPaint.setStyle(Paint.Style.STROKE);
        levelPaint.setStrokeWidth(2.2f * d);
        tempPaint.setColor(0xFFFFB74D);
        tempPaint.setStyle(Paint.Style.STROKE);
        tempPaint.setStrokeWidth(1.7f * d);
        labelPaint.setColor(0xCCFFFFFF);
        labelPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setPoints(List<BatteryHistoryStore.SamplePoint> newPoints) {
        points = newPoints == null ? new ArrayList<>() : new ArrayList<>(newPoints);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float left = getPaddingLeft() + 6f;
        float right = w - getPaddingRight() - 6f;
        float top = getPaddingTop() + 10f;
        float bottom = h - getPaddingBottom() - 20f;
        for (int p = 0; p <= 100; p += 25) {
            float y = bottom - (bottom - top) * p / 100f;
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        canvas.drawText("100%", left, top + labelPaint.getTextSize(), labelPaint);
        canvas.drawText("0%", left, bottom, labelPaint);
        canvas.drawText("Battery level", left, h - 3f, labelPaint);
        if (points.size() < 2) {
            canvas.drawText("Collecting battery history…", left + 30f, (top + bottom) / 2f, labelPaint);
            return;
        }
        long start = points.get(0).ts;
        long end = points.get(points.size() - 1).ts;
        long span = Math.max(1L, end - start);
        Path level = new Path();
        Path temp = new Path();
        boolean haveLevel = false;
        boolean haveTemp = false;
        for (BatteryHistoryStore.SamplePoint p : points) {
            float x = left + (right - left) * (p.ts - start) / (float) span;
            float ly = bottom - (bottom - top) * Math.max(0, Math.min(100, p.level)) / 100f;
            if (!haveLevel) { level.moveTo(x, ly); haveLevel = true; } else level.lineTo(x, ly);
            if (!Float.isNaN(p.temp)) {
                float normalized = Math.max(20f, Math.min(55f, p.temp));
                float ty = bottom - (bottom - top) * (normalized - 20f) / 35f;
                if (!haveTemp) { temp.moveTo(x, ty); haveTemp = true; } else temp.lineTo(x, ty);
            }
        }
        if (haveLevel) canvas.drawPath(level, levelPaint);
        if (haveTemp) canvas.drawPath(temp, tempPaint);
    }
}
