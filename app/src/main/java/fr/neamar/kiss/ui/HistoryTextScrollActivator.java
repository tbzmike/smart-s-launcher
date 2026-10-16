package fr.neamar.kiss.ui;

import android.view.View;
import android.view.ViewGroup;

/**
 * Re-arms only visible compact History text after a scroll settles.
 * No persistent worker, animations, polling, or extra layout pass.
 */
public final class HistoryTextScrollActivator {
    private HistoryTextScrollActivator() { }

    public static void refresh(View root) {
        if (root == null || !root.isShown()) return;
        if (root instanceof AutoMarqueeTextView) {
            ((AutoMarqueeTextView) root).refreshAutoScrollIfVisible();
        } else if (root instanceof AutoScrollPreviewTextView) {
            ((AutoScrollPreviewTextView) root).refreshAutoScrollIfVisible();
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            refresh(group.getChildAt(i));
        }
    }
}
