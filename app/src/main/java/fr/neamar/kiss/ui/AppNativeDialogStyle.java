package fr.neamar.kiss.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

/** Reuses the same icon-derived color idea as launcher tiles for notification dialogs. */
public final class AppNativeDialogStyle {
    private static final int SAMPLE = 12;
    private static final int FALLBACK = Color.rgb(64, 84, 118);

    private AppNativeDialogStyle() {}

    public static int accentForPackage(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return FALLBACK;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            Drawable icon = info.loadIcon(pm);
            return icon == null ? FALLBACK : sampleAccent(icon);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return FALLBACK;
        }
    }

    public static void styleDialog(AlertDialog dialog, String packageName) {
        if (dialog == null) return;
        applyDialogStyle(dialog, packageName);

        // Android 14-16 / vendor AlertDialog implementations can re-apply their Material/system
        // surface after show(). Re-apply once on the next UI pass so Smart S' app colour remains
        // the final visible surface instead of the platform grey shell.
        Window window = dialog.getWindow();
        if (window != null) {
            View decor = window.getDecorView();
            if (decor != null) decor.post(() -> applyDialogStyle(dialog, packageName));
        }
    }

    private static void applyDialogStyle(AlertDialog dialog, String packageName) {
        Context context = dialog.getContext();
        int accent = accentForPackage(context, packageName);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            View decor = window.getDecorView();
            View panel = findDialogPanel(decor, context);

            // Clear every system/theme background in the ancestry around AlertDialog's panel,
            // then paint only the actual panel. This avoids the grey Material parent surface that
            // appears specifically on the notification expand-arrow route.
            if (panel != null) {
                clearAncestorChrome(panel, decor);
                panel.setBackground(makeDialogBackground(context, accent));
                clearChildPanelBackgrounds(panel, context);
            } else if (decor != null) {
                decor.setBackground(makeDialogBackground(context, accent));
            }
        }

        TextView title = dialog.findViewById(context.getResources().getIdentifier("alertTitle", "id", "android"));
        if (title != null) title.setTextColor(Color.WHITE);
        styleButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), accent);
        styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), accent);
        styleButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), accent);
    }

    /**
     * Removes only AlertDialog wrapper backgrounds between the resolved panel and decor. Custom
     * notification content is below the panel and is therefore left intact.
     */
    private static void clearAncestorChrome(View panel, View decor) {
        View current = panel;
        while (current != null) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            View parentView = (View) parent;
            if (parentView != decor) parentView.setBackgroundColor(Color.TRANSPARENT);
            if (parentView == decor) break;
            current = parentView;
        }
        if (decor != null) decor.setBackgroundColor(Color.TRANSPARENT);
    }

    /**
     * Styles app-supplied notification RemoteViews inside Smart S. Android notifications may
     * contain their own opaque grey root/background, so changing only the AlertDialog window is
     * not enough. Tint container backgrounds while leaving image content intact.
     */
    public static void styleNotificationContent(View root, String packageName) {
        if (root == null) return;
        int accent = accentForPackage(root.getContext(), packageName);
        GradientDrawable surface = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{toneAlpha(accent, 0.86f, 238), toneAlpha(accent, 0.54f, 238)});
        surface.setCornerRadius(dp(root.getContext(), 18));
        surface.setStroke(dp(root.getContext(), 1), toneAlpha(accent, 1.25f, 205));
        root.setBackground(surface);
        tintNotificationChildren(root, accent, true);
    }

    private static void tintNotificationChildren(View view, int accent, boolean root) {
        if (view == null) return;

        if (view instanceof TextView) {
            setReadableText((TextView) view);
        }

        // Do not tint images: album art, sender avatars and app icons must remain faithful.
        if (!root && view instanceof ViewGroup && !(view instanceof ImageView)) {
            Drawable background = view.getBackground();
            if (background != null) {
                try {
                    view.setBackgroundTintList(ColorStateList.valueOf(toneAlpha(accent, 0.70f, 224)));
                } catch (RuntimeException ignored) {
                    // A vendor RemoteViews background can reject tinting; the styled parent surface
                    // still guarantees that uncovered areas use the app-derived colour.
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintNotificationChildren(group.getChildAt(i), accent, false);
            }
        }
    }

    private static Drawable makeDialogBackground(Context context, int accent) {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{tone(accent, 0.92f), tone(accent, 0.50f)});
        bg.setCornerRadius(dp(context, 26));
        bg.setStroke(dp(context, 1), toneAlpha(accent, 1.30f, 225));
        return bg;
    }

    private static View findDialogPanel(View root, Context context) {
        if (root == null) return null;
        int parentPanelId = context.getResources().getIdentifier("parentPanel", "id", "android");
        if (parentPanelId != 0) {
            View panel = root.findViewById(parentPanelId);
            if (panel != null) return panel;
        }
        int customPanelId = context.getResources().getIdentifier("customPanel", "id", "android");
        if (customPanelId != 0) {
            View panel = root.findViewById(customPanelId);
            if (panel != null && panel.getParent() instanceof View) return (View) panel.getParent();
        }
        int contentId = android.R.id.content;
        View content = root.findViewById(contentId);
        if (content != null && content.getParent() instanceof View) return (View) content.getParent();
        return root;
    }

    private static void clearChildPanelBackgrounds(View root, Context context) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        int topPanel = context.getResources().getIdentifier("topPanel", "id", "android");
        int contentPanel = context.getResources().getIdentifier("contentPanel", "id", "android");
        int buttonPanel = context.getResources().getIdentifier("buttonPanel", "id", "android");
        int customPanel = context.getResources().getIdentifier("customPanel", "id", "android");
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            int id = child.getId();
            if (id == topPanel || id == contentPanel || id == buttonPanel || id == customPanel) {
                child.setBackgroundColor(Color.TRANSPARENT);
            }
            clearChildPanelBackgrounds(child, context);
        }
    }

    public static void setReadableText(TextView view) {
        if (view == null) return;
        view.setTextColor(Color.WHITE);
        view.setHintTextColor(Color.argb(190, 255, 255, 255));
    }

    public static void styleButton(Button button, int accent) {
        if (button == null) return;
        button.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{toneAlpha(accent, 1.12f, 180), toneAlpha(accent, 0.82f, 160)});
        bg.setCornerRadius(dp(button.getContext(), 14));
        bg.setStroke(dp(button.getContext(), 1), toneAlpha(accent, 1.35f, 190));
        button.setBackground(bg);
    }

    private static int sampleAccent(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int l = drawable.getBounds().left;
        int t = drawable.getBounds().top;
        int r = drawable.getBounds().right;
        int b = drawable.getBounds().bottom;
        drawable.setBounds(0, 0, SAMPLE, SAMPLE);
        drawable.draw(canvas);
        drawable.setBounds(l, t, r, b);

        long red = 0, green = 0, blue = 0;
        int count = 0;
        for (int y = 0; y < SAMPLE; y++) {
            for (int x = 0; x < SAMPLE; x++) {
                int c = bitmap.getPixel(x, y);
                if (Color.alpha(c) < 48) continue;
                float[] hsv = new float[3];
                Color.colorToHSV(c, hsv);
                if (hsv[2] < 0.12f) continue;
                red += Color.red(c);
                green += Color.green(c);
                blue += Color.blue(c);
                count++;
            }
        }
        bitmap.recycle();
        if (count == 0) return FALLBACK;
        int result = Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
        float[] hsv = new float[3];
        Color.colorToHSV(result, hsv);
        hsv[1] = Math.max(0.30f, Math.min(0.82f, hsv[1]));
        hsv[2] = Math.max(0.38f, Math.min(0.82f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private static int tone(int color, float multiplier) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * multiplier));
        return Color.HSVToColor(hsv);
    }

    private static int toneAlpha(int color, float multiplier, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * multiplier));
        return Color.HSVToColor(alpha, hsv);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
