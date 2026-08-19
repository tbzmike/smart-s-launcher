package fr.neamar.kiss.forwarder;

import android.content.Intent;
import android.view.ContextMenu;
import android.view.MenuItem;

import androidx.annotation.Nullable;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.SmartWorkspaceController;

/**
 * Keeps the legacy KISS widget engine for normal/minimal mode, but gives Flexible Workspace its
 * own freeform widget host. The two persistence/sizing models are intentionally isolated.
 */
class WorkspaceWidgets extends Widgets {
    private boolean workspaceMode;
    private FreeformWorkspaceWidgetManager freeformWidgets;

    WorkspaceWidgets(MainActivity mainActivity) {
        super(mainActivity);
    }

    @Override
    void onCreate() {
        SmartWorkspaceController controller = SmartWorkspaceController.install(mainActivity);
        workspaceMode = SmartWorkspaceController.isEnabled(prefs) && controller != null;
        if (!workspaceMode) {
            super.onCreate();
            return;
        }

        freeformWidgets = new FreeformWorkspaceWidgetManager(mainActivity, prefs);
        freeformWidgets.onCreate();
    }

    @Override
    public void onStart() {
        if (workspaceMode && freeformWidgets != null) {
            freeformWidgets.onStart();
        } else {
            super.onStart();
        }
    }

    @Override
    public void onDestroy() {
        if (workspaceMode && freeformWidgets != null) {
            freeformWidgets.onDestroy();
        } else {
            super.onDestroy();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!workspaceMode) {
            super.onActivityResult(requestCode, resultCode, data);
        }
        // Flexible Workspace uses ActivityResultLaunchers owned by the freeform manager.
    }

    @Override
    boolean onOptionsItemSelected(MenuItem item) {
        if (workspaceMode && item.getItemId() == R.id.add_widget && freeformWidgets != null) {
            freeformWidgets.startAddWidget();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    void onCreateContextMenu(ContextMenu menu) {
        if (!workspaceMode) {
            super.onCreateContextMenu(menu);
            return;
        }
        MenuItem addWidget = menu.findItem(R.id.add_widget);
        if (addWidget != null) addWidget.setVisible(true);
    }

    @Override
    void onDataSetChanged() {
        if (!workspaceMode) {
            super.onDataSetChanged();
        }
        // Freeform widgets live in their own workspace pane and do not need the legacy empty-list
        // touch workaround.
    }
}
