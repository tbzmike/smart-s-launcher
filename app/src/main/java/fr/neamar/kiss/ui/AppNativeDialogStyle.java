package fr.neamar.kiss.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
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
        Context context = dialog.getContext();
        int accent = accentForPackage(context, packageName);
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{tone(accent, 0.78f), tone(accent, 0.46f)});
            bg.setCornerRadius(dp(context, 26));
            bg.setStroke(dp(context, 1), toneAlpha(accent, 1.22f, 210));
            window.setBackgroundDrawable(bg);
        }

        TextView title = dialog.findViewById(context.getResources().getIdentifier("alertTitle", "id", "android"));
        if (title != null) title.setTextColor(Color.WHITE);
        styleButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), accent);
        styleButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), accent);
        styleButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), accent);
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
