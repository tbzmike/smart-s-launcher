package fr.neamar.kiss.forwarder;

import fr.neamar.kiss.MainActivity;

/** Routes exact keyboard visibility into the layout-driven Vertical Cards viewport controller. */
final class VerticalCardKeyboardAnchor {
    private VerticalCardKeyboardAnchor() {
    }

    static void onKeyboardVisibilityChanged(MainActivity activity, boolean keyboardVisible) {
        VerticalCardViewportController.noteKeyboardVisibility(activity, keyboardVisible);
    }
}
