package fr.neamar.kiss.freeform;

import android.graphics.Rect;

/** A single display-space geometry contract for app content and its decoration. */
final class FreeformGeometry {
    private FreeformGeometry() {}
    static Rect contentArea(Rect screen, int captionHeight) {
        Rect result = new Rect(screen);
        result.top = Math.min(result.bottom - 1, result.top + captionHeight);
        return result;
    }
    static Rect fit(Rect source, Rect area, int minimum) {
        int width = Math.max(1, Math.min(area.width(), Math.max(minimum, source.width())));
        int height = Math.max(1, Math.min(area.height(), Math.max(minimum, source.height())));
        int x = FreeformTaskPolicy.clamp(source.left, area.left, area.right - width);
        int y = FreeformTaskPolicy.clamp(source.top, area.top, area.bottom - height);
        return new Rect(x, y, x + width, y + height);
    }
    static Rect center(Rect area) {
        int w = Math.max(1, area.width() * 3 / 4), h = Math.max(1, area.height() * 3 / 4);
        int x = area.left + (area.width() - w) / 2, y = area.top + (area.height() - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }
    static Rect caption(Rect frame, Rect usable, int height) {
        // Prefer immediately above the app. If the ROM places an app against the top edge,
        // attach inside its top edge instead of independently shifting the bar away.
        int y = frame.top - height >= usable.top ? frame.top - height : frame.top;
        return new Rect(frame.left, y, frame.right, y + Math.min(height, frame.height()));
    }
    static Rect corner(Rect frame, int size) {
        int w = Math.min(size, frame.width()), h = Math.min(size, frame.height());
        return new Rect(frame.right - w, frame.bottom - h, frame.right, frame.bottom);
    }
    static boolean near(Rect a, Rect b, int tolerance) {
        return !a.isEmpty() && !b.isEmpty() && Math.abs(a.left-b.left) <= tolerance
                && Math.abs(a.top-b.top) <= tolerance && Math.abs(a.right-b.right) <= tolerance
                && Math.abs(a.bottom-b.bottom) <= tolerance;
    }
    static boolean fullscreen(Rect frame, Rect usable, int tolerance) {
        return !frame.isEmpty() && frame.left <= usable.left + tolerance
                && frame.top <= usable.top + tolerance && frame.right >= usable.right - tolerance
                && frame.bottom >= usable.bottom - tolerance;
    }
}
