package fr.neamar.kiss.forwarder;

import android.view.ContextMenu;
import android.view.MenuItem;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.SmartWorkspaceController;

/**
 * Extends the proven KISS widget forwarder so widgets can coexist with the normal history/results
 * pane when the optional Smart S flexible workspace is enabled.
 */
class WorkspaceWidgets extends Widgets {
    WorkspaceWidgets(MainActivity mainActivity) {
        super(mainActivity);
    }

    @Override
    void onCreate() {
        SmartWorkspaceController.install(mainActivity);
        if (!SmartWorkspaceController.isEnabled(prefs) || prefs.getBoolean("history-hide", false)) {
            super.onCreate();
            return;
        }

        // Widgets historically restore only in KISS minimal mode. Temporarily expose that state
        // only while the existing widget host restores; immediately restore the user's real choice.
        boolean originalHistoryHide = prefs.getBoolean("history-hide", false);
        prefs.edit().putBoolean("history-hide", true).commit();
        try {
            super.onCreate();
        } finally {
            prefs.edit().putBoolean("history-hide", originalHistoryHide).commit();
        }
    }

    @Override
    void onCreateContextMenu(ContextMenu menu) {
        super.onCreateContextMenu(menu);
        if (SmartWorkspaceController.isEnabled(prefs)) {
            MenuItem addWidget = menu.findItem(R.id.add_widget);
            if (addWidget != null) addWidget.setVisible(true);
        }
    }

    @Override
    void onDataSetChanged() {
        if (!SmartWorkspaceController.isEnabled(prefs)) {
            super.onDataSetChanged();
        }
        // In workspace mode the empty/history view lives in its own pane and cannot cover widgets,
        // so the legacy minimal-mode touch workaround must not hide it.
    }
}
