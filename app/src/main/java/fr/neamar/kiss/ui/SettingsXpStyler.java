package fr.neamar.kiss.ui;

import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.RecyclerView;

import fr.neamar.kiss.R;

/** Windows XP-inspired visual treatment for settings only. */
public final class SettingsXpStyler {
    private static final int XP_BLUE = Color.rgb(49, 106, 197);
    private static final int XP_BLUE_LIGHT = Color.rgb(84, 149, 255);
    private static final int XP_BLUE_DARK = Color.rgb(0, 54, 149);
    private static final int XP_PANEL = Color.rgb(236, 233, 216);
    private static final int XP_CONTROL = Color.rgb(236, 233, 216);
    private static final int XP_CONTROL_INNER = Color.rgb(255, 255, 255);
    private static final int XP_BORDER_DARK = Color.rgb(113, 111, 100);
    private static final int XP_BORDER_DEEP = Color.rgb(64, 64, 64);
    private static final int XP_TEXT = Color.rgb(18, 18, 18);
    private static final int XP_SUMMARY = Color.rgb(58, 58, 58);

    private SettingsXpStyler() { }

    public static void styleActivity(View decor) {
        if (decor == null) return;
        decor.setBackgroundColor(XP_PANEL);
        View toolbarView = decor.findViewById(R.id.main_toolbar);
        if (toolbarView instanceof Toolbar) {
            Toolbar toolbar = (Toolbar) toolbarView;
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{XP_BLUE_LIGHT, XP_BLUE, XP_BLUE_DARK});
            gradient.setStroke(dp(toolbar, 1), XP_BLUE_DARK);
            toolbar.setBackground(gradient);
            toolbar.setTitleTextColor(Color.WHITE);
            toolbar.setSubtitleTextColor(Color.WHITE);
            toolbar.setElevation(dp(toolbar, 3));
        }
        View content = decor.findViewById(R.id.content_container);
        if (content != null) {
            content.setBackground(new XpBevelDrawable(XP_PANEL, false));
        }
    }

    public static void styleFragment(@NonNull PreferenceFragmentCompat fragment) {
        RecyclerView list = fragment.getListView();
        if (list == null) return;
        list.setBackgroundColor(XP_PANEL);
        list.setPadding(dp(list, 8), dp(list, 8), dp(list, 8), dp(list, 18));
        list.setClipToPadding(false);
        list.setItemAnimator(null);
        if (list.getItemDecorationCount() == 0) {
            list.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                           @NonNull RecyclerView parent,
                                           @NonNull RecyclerView.State state) {
                    outRect.bottom = dp(view, 5);
                }
            });
        }
        list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(@NonNull View view) {
                stylePreferenceRow(view);
            }
            @Override public void onChildViewDetachedFromWindow(@NonNull View view) { }
        });
        list.post(() -> {
            for (int i = 0; i < list.getChildCount(); i++) stylePreferenceRow(list.getChildAt(i));
        });
    }

    public static void styleDialog(@Nullable Dialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        if (decor == null) return;
        window.setBackgroundDrawable(new XpBevelDrawable(XP_PANEL, false));
        decor.setBackgroundColor(Color.TRANSPARENT);

        TextView title = decor.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) {
            title.setTextColor(Color.WHITE);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            View parent = title.getParent() instanceof View ? (View) title.getParent() : null;
            if (parent != null) {
                GradientDrawable header = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{XP_BLUE_LIGHT, XP_BLUE, XP_BLUE_DARK});
                parent.setBackground(header);
                parent.setPadding(Math.max(parent.getPaddingLeft(), dp(parent, 10)),
                        Math.max(parent.getPaddingTop(), dp(parent, 7)),
                        Math.max(parent.getPaddingRight(), dp(parent, 10)),
                        Math.max(parent.getPaddingBottom(), dp(parent, 7)));
            }
        }
        styleDialogTree(decor, title);
    }

    private static void stylePreferenceRow(View row) {
        if (row == null) return;
        boolean isCategory = row.findViewById(R.id.xp_category_marker) != null;

        if (isCategory) {
            GradientDrawable background = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{XP_BLUE_LIGHT, XP_BLUE, XP_BLUE_DARK});
            background.setStroke(dp(row, 1), XP_BLUE_DARK);
            row.setBackground(background);
            row.setElevation(dp(row, 1));
        } else {
            row.setBackground(new XpBevelDrawable(XP_CONTROL, false));
            row.setElevation(0f);
            row.setPadding(Math.max(row.getPaddingLeft(), dp(row, 12)),
                    Math.max(row.getPaddingTop(), dp(row, 10)),
                    Math.max(row.getPaddingRight(), dp(row, 12)),
                    Math.max(row.getPaddingBottom(), dp(row, 10)));
        }

        styleTextTree(row, isCategory);
    }

    private static void styleTextTree(View view, boolean category) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (category) {
                text.setTextColor(Color.WHITE);
                text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                text.setTextSize(Math.max(16f,
                        text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity));
            } else {
                int id = text.getId();
                if (id == android.R.id.title) {
                    text.setTextColor(XP_TEXT);
                    text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    text.setTextSize(Math.max(16f,
                            text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity));
                } else if (id == android.R.id.summary) {
                    text.setTextColor(XP_SUMMARY);
                    text.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                }
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) styleTextTree(group.getChildAt(i), category);
    }

    private static void styleDialogTree(View view, @Nullable TextView title) {
        if (view instanceof TextView && view != title) {
            TextView text = (TextView) view;
            int id = text.getId();
            if (id == android.R.id.button1 || id == android.R.id.button2 || id == android.R.id.button3) {
                text.setTextColor(XP_TEXT);
                text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                text.setBackground(new XpBevelDrawable(XP_CONTROL, false));
                int h = dp(text, 8);
                int v = dp(text, 5);
                text.setPadding(Math.max(text.getPaddingLeft(), h), Math.max(text.getPaddingTop(), v),
                        Math.max(text.getPaddingRight(), h), Math.max(text.getPaddingBottom(), v));
            } else {
                text.setTextColor(XP_TEXT);
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) styleDialogTree(group.getChildAt(i), title);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    /** Draws the asymmetric highlight/shadow edge used by classic raised Windows controls. */
    private static final class XpBevelDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int fillColor;
        private final boolean sunken;

        XpBevelDrawable(int fillColor, boolean sunken) {
            this.fillColor = fillColor;
            this.sunken = sunken;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillColor);
            canvas.drawRect(b, paint);

            int light = sunken ? XP_BORDER_DARK : XP_CONTROL_INNER;
            int dark = sunken ? XP_CONTROL_INNER : XP_BORDER_DARK;
            int deep = sunken ? XP_CONTROL_INNER : XP_BORDER_DEEP;
            paint.setStrokeWidth(1f);

            paint.setColor(light);
            canvas.drawLine(b.left, b.top, b.right - 1, b.top, paint);
            canvas.drawLine(b.left, b.top, b.left, b.bottom - 1, paint);

            paint.setColor(dark);
            canvas.drawLine(b.left, b.bottom - 1, b.right - 1, b.bottom - 1, paint);
            canvas.drawLine(b.right - 1, b.top, b.right - 1, b.bottom - 1, paint);

            if (b.width() > 4 && b.height() > 4) {
                paint.setColor(deep);
                canvas.drawLine(b.left + 1, b.bottom - 2, b.right - 2, b.bottom - 2, paint);
                canvas.drawLine(b.right - 2, b.top + 1, b.right - 2, b.bottom - 2, paint);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
