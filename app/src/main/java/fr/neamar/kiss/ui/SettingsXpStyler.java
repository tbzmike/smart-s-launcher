package fr.neamar.kiss.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.RecyclerView;

import fr.neamar.kiss.R;

/** Windows XP-inspired visual treatment for settings only. */
public final class SettingsXpStyler {
    private static final int XP_BLUE = Color.rgb(49, 106, 197);
    private static final int XP_BLUE_DARK = Color.rgb(0, 54, 149);
    private static final int XP_PANEL = Color.rgb(236, 233, 216);
    private static final int XP_ROW = Color.rgb(255, 255, 255);
    private static final int XP_BORDER = Color.rgb(122, 150, 223);
    private static final int XP_TEXT = Color.rgb(20, 20, 20);
    private static final int XP_SUMMARY = Color.rgb(65, 65, 65);

    private SettingsXpStyler() { }

    public static void styleActivity(View decor) {
        if (decor == null) return;
        View toolbarView = decor.findViewById(R.id.main_toolbar);
        if (toolbarView instanceof Toolbar) {
            Toolbar toolbar = (Toolbar) toolbarView;
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{XP_BLUE, XP_BLUE_DARK});
            gradient.setCornerRadius(0f);
            toolbar.setBackground(gradient);
            toolbar.setTitleTextColor(Color.WHITE);
            toolbar.setSubtitleTextColor(Color.WHITE);
            toolbar.setElevation(dp(toolbar, 3));
        }
        View content = decor.findViewById(R.id.content_container);
        if (content != null) content.setBackgroundColor(XP_PANEL);
    }

    public static void styleFragment(@NonNull PreferenceFragmentCompat fragment) {
        RecyclerView list = fragment.getListView();
        if (list == null) return;
        list.setBackgroundColor(XP_PANEL);
        list.setPadding(dp(list, 8), dp(list, 8), dp(list, 8), dp(list, 18));
        list.setClipToPadding(false);
        list.setItemAnimator(null);
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

    private static void stylePreferenceRow(View row) {
        if (row == null) return;
        boolean isCategory = row.getTag() instanceof PreferenceCategory;

        GradientDrawable background = new GradientDrawable();
        background.setColor(isCategory ? XP_BLUE : XP_ROW);
        background.setCornerRadius(dp(row, isCategory ? 4 : 6));
        background.setStroke(dp(row, isCategory ? 0 : 1), isCategory ? XP_BLUE : XP_BORDER);
        row.setBackground(background);
        row.setElevation(dp(row, isCategory ? 1 : 2));
        row.setPadding(Math.max(row.getPaddingLeft(), dp(row, 12)),
                Math.max(row.getPaddingTop(), dp(row, 10)),
                Math.max(row.getPaddingRight(), dp(row, 12)),
                Math.max(row.getPaddingBottom(), dp(row, 10)));

        styleTextTree(row, isCategory);
    }

    private static void styleTextTree(View view, boolean category) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (category) {
                text.setTextColor(Color.WHITE);
                text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                text.setTextSize(Math.max(16f, text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity));
            } else {
                int id = text.getId();
                if (id == android.R.id.title) {
                    text.setTextColor(XP_TEXT);
                    text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    text.setTextSize(Math.max(16f, text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity));
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

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
