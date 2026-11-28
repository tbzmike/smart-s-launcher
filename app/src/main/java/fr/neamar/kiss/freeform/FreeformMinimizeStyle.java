package fr.neamar.kiss.freeform;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;
import fr.neamar.kiss.R;

/** Presentation preference, independent of fullscreen switcher and bubble-folder settings. */
public final class FreeformMinimizeStyle {
    private static final String PREFS="floating-window-presentation";
    private static final String KEY="live_miniature";
    private FreeformMinimizeStyle() {}
    public static boolean live(Context c) { return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(KEY,false); }
    static void set(Context c,boolean live) { c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(KEY,live).apply(); }
    public static void choose(Activity a) {
        new AlertDialog.Builder(a).setTitle(R.string.floating_minimize_style)
                .setSingleChoiceItems(new CharSequence[]{a.getText(R.string.floating_style_icon),a.getText(R.string.floating_style_live)},
                        live(a)?1:0,(dialog,which)->{
                            set(a,which==1);
                            dialog.dismiss();
                            Toast.makeText(a,R.string.floating_style_next,Toast.LENGTH_LONG).show();
                        }).setNegativeButton(android.R.string.cancel,null).show();
    }
}
