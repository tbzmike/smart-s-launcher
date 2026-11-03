package fr.neamar.kiss;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Route app-drawer and Settings Open launches into the single actual HOME task. */
public final class LauncherEntryActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setClass(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        } finally {
            finish();
        }
    }
}
