package fr.neamar.kiss.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.preference.PreferenceManager;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.adapter.RecordAdapter;
import fr.neamar.kiss.result.Result;

/**
 * Date navigation for the normal Home/History list.
 *
 * <p>The fixed label tells the user which date section is currently in view. The right-hand thumb
 * behaves like Google Photos fast scrolling: hold the thumb, drag vertically, and a date bubble
 * follows the target position while the ListView jumps through History.</p>
 */
public final class HistoryDateNavigator implements AbsListView.OnScrollListener {
    private static final int MIN_FAST_SCROLL_ITEMS = 8;

    private final MainActivity activity;
    private final AnimatedListView list;
    private final RecordAdapter adapter;
    private final TextView sectionLabel;
    private final DateFastScrollView fastScroll;
    private boolean historyScrollbarMode;
    private int lastRepresentativePosition = -1;
    private final int originalListTopMargin;
    private final Runnable scheduledRefresh = this::refresh;

    public HistoryDateNavigator(@NonNull MainActivity activity,
                                @NonNull AnimatedListView list,
                                @NonNull RecordAdapter adapter,
                                @NonNull ViewGroup host) {
        this.activity = activity;
        this.list = list;
        this.adapter = adapter;
        ViewGroup.LayoutParams initial = list.getLayoutParams();
        originalListTopMargin = initial instanceof FrameLayout.LayoutParams
                ? ((FrameLayout.LayoutParams) initial).topMargin : 0;

        sectionLabel = buildSectionLabel(activity);
        sectionLabel.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                reserveHeaderSpace(isHistorySurface() && adapter.getCount() > 0);
            }
        });
        fastScroll = new DateFastScrollView(activity);

        if (host instanceof FrameLayout) {
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            labelParams.topMargin = dp(activity, 8);
            ((FrameLayout) host).addView(sectionLabel, labelParams);

            FrameLayout.LayoutParams scrollParams = new FrameLayout.LayoutParams(
                    dp(activity, 180),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END);
            ((FrameLayout) host).addView(fastScroll, scrollParams);
        } else {
            host.addView(sectionLabel);
            host.addView(fastScroll);
        }

        // This ListView had no competing OnScrollListener. Keep all scroll-derived date work here;
        // the existing KeyboardScrollHider remains attached only as an OnTouchListener.
        list.setOnScrollListener(this);
        refresh();
    }

    public void onDataChanged() {
        lastRepresentativePosition = -1;
        list.removeCallbacks(scheduledRefresh);
        list.post(scheduledRefresh);
    }

    public void onSurfaceChanged() {
        refresh();
    }

    public void destroy() {
        list.removeCallbacks(scheduledRefresh);
        list.setOnScrollListener(null);
        list.setVerticalScrollBarEnabled(true);
        reserveHeaderSpace(false);
        historyScrollbarMode = false;
        lastRepresentativePosition = -1;
        ViewGroup labelParent = (ViewGroup) sectionLabel.getParent();
        if (labelParent != null) labelParent.removeView(sectionLabel);
        ViewGroup scrollParent = (ViewGroup) fastScroll.getParent();
        if (scrollParent != null) scrollParent.removeView(fastScroll);
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
        refresh();
    }

    @Override
    public void onScroll(AbsListView view,
                         int firstVisibleItem,
                         int visibleItemCount,
                         int totalItemCount) {
        refreshFromVisible(firstVisibleItem, visibleItemCount, totalItemCount);
    }

    private void refresh() {
        refreshFromVisible(
                list.getFirstVisiblePosition(),
                list.getChildCount(),
                adapter.getCount());
    }

    private void refreshFromVisible(int firstVisible,
                                    int visibleCount,
                                    int totalCount) {
        boolean history = isHistorySurface();
        reserveHeaderSpace(history && totalCount > 0);
        if (historyScrollbarMode != history) {
            historyScrollbarMode = history;
            list.setVerticalScrollBarEnabled(!history);
        }
        if (!history || totalCount <= 0) {
            lastRepresentativePosition = -1;
            sectionLabel.setVisibility(View.GONE);
            fastScroll.setVisibility(View.GONE);
            return;
        }

        int representative = representativePosition(firstVisible, totalCount);
        if (representative != lastRepresentativePosition) {
            lastRepresentativePosition = representative;
            String label = labelForPosition(representative);
            if (!label.contentEquals(sectionLabel.getText())) {
                sectionLabel.setText(label);
            }
        }
        sectionLabel.setVisibility(View.VISIBLE);

        boolean canFastScroll = totalCount >= MIN_FAST_SCROLL_ITEMS;
        fastScroll.setVisibility(canFastScroll ? View.VISIBLE : View.GONE);
        if (canFastScroll) {
            fastScroll.updatePosition(firstVisible, visibleCount, totalCount);
        }
    }

    /** Keep the Today/Yesterday header above rows, not painted over notification text. */
    private void reserveHeaderSpace(boolean history) {
        ViewGroup.LayoutParams raw = list.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        int header = Math.max(dp(activity, 44), sectionLabel.getHeight() + dp(activity, 16));
        int wanted = originalListTopMargin + (history ? header : 0);
        if (lp.topMargin != wanted) {
            lp.topMargin = wanted;
            list.setLayoutParams(lp);
        }
    }

    private boolean isHistorySurface() {
        return activity.searchEditText != null
                && activity.searchEditText.length() == 0
                && !activity.isViewingAllApps()
                && list.getVisibility() == View.VISIBLE
                && "vertical".equals(PreferenceManager.getDefaultSharedPreferences(activity)
                        .getString("smart-history-layout", "vertical"))
                && UniversalHistoryTimestamp.isHistorySurface(activity);
    }

    private int representativePosition(int firstVisible, int totalCount) {
        if (totalCount <= 0) return 0;
        int position = Math.max(0, Math.min(firstVisible, totalCount - 1));

        // If the first row is mostly scrolled away, use the next row as the visible section owner.
        View firstChild = list.getChildAt(0);
        if (firstChild != null
                && firstChild.getHeight() > 0
                && firstChild.getBottom() < firstChild.getHeight() / 2
                && position < totalCount - 1) {
            position++;
        }
        return position;
    }

    @NonNull
    private String labelForPosition(int position) {
        if (position < 0 || position >= adapter.getCount()) return "History";
        Result<?> result = adapter.getItem(position);
        long timestamp = UniversalHistoryTimestamp.resolveHistoryTimestamp(result);
        return UniversalHistoryTimestamp.formatHistorySectionLabel(activity, timestamp);
    }

    private void jumpToFraction(float fraction) {
        int count = adapter.getCount();
        if (count <= 0) return;

        float clamped = Math.max(0f, Math.min(1f, fraction));
        int position = Math.round(clamped * (count - 1));
        position = Math.max(0, Math.min(position, count - 1));

        // Keep the selected date near the top so the user immediately sees the rows represented
        // by the bubble, just like date-aware photo fast scrollers.
        // The header already has its own space outside the ListView.
        list.setSelectionFromTop(position, dp(activity, 10));
        fastScroll.setBubbleText(labelForPosition(position));
        sectionLabel.setText(labelForPosition(position));
    }

    private static TextView buildSectionLabel(Context context) {
        // This date chip owns a fixed contrast-safe palette, like the date bubble and tool screens.
        TextView label = new TextView(context.getApplicationContext());
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setGravity(Gravity.CENTER);
        label.setContentDescription("History date section");
        label.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6));
        int surface = resolveColor(context, R.attr.listBackgroundColor, 0xFF202124);
        label.setTextColor(readableColor(
                resolveColor(context, android.R.attr.textColorPrimary, Color.WHITE), surface));
        label.setElevation(dp(context, 4));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        GradientDrawable background = new GradientDrawable();
        background.setColor(withAlpha(surface, 0xE6));
        background.setCornerRadius(dp(context, 18));
        label.setBackground(background);
        label.setVisibility(View.GONE);
        return label;
    }

    private final class DateFastScrollView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF thumbRect = new RectF();
        private final RectF bubbleRect = new RectF();

        private final float thumbWidth;
        private final float thumbHeight;
        private final float edgeInset;
        private final float touchSlop;
        private final float bubbleWidth;
        private final float bubbleHeight;

        private boolean dragging;
        private float thumbTop;
        private String bubbleText = "";

        DateFastScrollView(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription("History date fast scroll");

            int surface = resolveColor(context, R.attr.listBackgroundColor, 0xFF202124);
            int primary = readableColor(
                    resolveColor(context, android.R.attr.textColorPrimary, Color.WHITE), surface);
            int accent = resolveColor(context, android.R.attr.colorAccent, primary);

            trackPaint.setColor(withAlpha(primary, 0x36));
            thumbPaint.setColor(withAlpha(primary, 0xE8));
            bubblePaint.setColor(withAlpha(surface, 0xF3));
            bubbleTextPaint.setColor(primary);
            bubbleTextPaint.setTextSize(sp(context, 14));
            bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
            bubbleTextPaint.setFakeBoldText(true);

            // Keep the active thumb visually strong without imposing a fixed accent color.
            if (accent != 0) thumbPaint.setColor(withAlpha(accent, 0xEE));

            thumbWidth = dp(context, 5);
            thumbHeight = dp(context, 58);
            edgeInset = dp(context, 5);
            touchSlop = dp(context, 20);
            bubbleWidth = dp(context, 152);
            bubbleHeight = dp(context, 40);
        }

        void updatePosition(int firstVisible, int visibleCount, int totalCount) {
            if (dragging || getHeight() <= 0 || totalCount <= 0) return;
            int scrollable = Math.max(1, totalCount - Math.max(1, visibleCount));
            float fraction = Math.max(0f, Math.min(1f, firstVisible / (float) scrollable));
            thumbTop = fraction * Math.max(0f, getHeight() - thumbHeight);
            invalidate();
        }

        void setBubbleText(String text) {
            bubbleText = text == null ? "" : text;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float thumbLeft = getWidth() - edgeInset - thumbWidth;
            float centerX = thumbLeft + thumbWidth / 2f;

            canvas.drawRoundRect(
                    centerX - dp(getContext(), 1),
                    dp(getContext(), 10),
                    centerX + dp(getContext(), 1),
                    Math.max(dp(getContext(), 10), getHeight() - dp(getContext(), 10)),
                    dp(getContext(), 1),
                    dp(getContext(), 1),
                    trackPaint);

            thumbRect.set(
                    thumbLeft,
                    thumbTop,
                    thumbLeft + thumbWidth,
                    Math.min(getHeight(), thumbTop + thumbHeight));
            canvas.drawRoundRect(
                    thumbRect,
                    thumbWidth / 2f,
                    thumbWidth / 2f,
                    thumbPaint);

            if (!dragging || bubbleText.length() == 0) return;

            float bubbleRight = thumbLeft - dp(getContext(), 12);
            float bubbleCenterY = Math.max(
                    bubbleHeight / 2f,
                    Math.min(getHeight() - bubbleHeight / 2f, thumbRect.centerY()));
            bubbleRect.set(
                    bubbleRight - bubbleWidth,
                    bubbleCenterY - bubbleHeight / 2f,
                    bubbleRight,
                    bubbleCenterY + bubbleHeight / 2f);
            canvas.drawRoundRect(
                    bubbleRect,
                    dp(getContext(), 18),
                    dp(getContext(), 18),
                    bubblePaint);

            Paint.FontMetrics fm = bubbleTextPaint.getFontMetrics();
            float baseline = bubbleRect.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(bubbleText, bubbleRect.centerX(), baseline, bubbleTextPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isHistorySurface()
                    || adapter.getCount() < MIN_FAST_SCROLL_ITEMS
                    || getHeight() <= 0) {
                dragging = false;
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    float thumbCenter = thumbTop + thumbHeight / 2f;
                    boolean nearThumbY = Math.abs(event.getY() - thumbCenter)
                            <= thumbHeight / 2f + touchSlop;
                    boolean nearRightEdge = event.getX() >= getWidth() - dp(getContext(), 34);
                    if (!nearThumbY || !nearRightEdge) return false;

                    dragging = true;
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    updateFromTouch(event.getY());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    updateFromTouch(event.getY());
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    updateFromTouch(event.getY());
                    dragging = false;
                    invalidate();
                    getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                    return true;

                default:
                    return dragging;
            }
        }

        private void updateFromTouch(float y) {
            float available = Math.max(1f, getHeight() - thumbHeight);
            thumbTop = Math.max(0f, Math.min(available, y - thumbHeight / 2f));
            float fraction = thumbTop / available;
            jumpToFraction(fraction);
            invalidate();
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private static int resolveColor(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return fallback;
        if (value.resourceId != 0) {
            try {
                return ContextCompat.getColor(context, value.resourceId);
            } catch (RuntimeException ignored) { }
        }
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? value.data : fallback;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int readableColor(int preferred, int surface) {
        int opaqueSurface = surface | 0xFF000000;
        if (ColorUtils.calculateContrast(preferred, opaqueSurface) >= 4.5) return preferred;
        return ColorUtils.calculateContrast(Color.WHITE, opaqueSurface)
                >= ColorUtils.calculateContrast(Color.BLACK, opaqueSurface) ? Color.WHITE : Color.BLACK;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static float sp(Context context, int value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                context.getResources().getDisplayMetrics());
    }
}
