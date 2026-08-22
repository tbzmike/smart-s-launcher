package fr.neamar.kiss.forwarder;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.HapticFeedbackConstantsCompat;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;
import fr.neamar.kiss.ui.SmartWorkspaceController;

/**
 * Keeps the legacy KISS widget engine for normal/minimal mode, but gives Flexible Workspace its
 * own freeform widget host. The two persistence/sizing models are intentionally isolated.
 */
class WorkspaceWidgets extends Widgets {
    private static final String DONE_TAG = "smart-freeform-widget-done";

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
        scheduleDoneControlInstall();
    }

    @Override
    public void onStart() {
        if (workspaceMode && freeformWidgets != null) {
            freeformWidgets.onStart();
            scheduleDoneControlInstall();
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
            scheduleDoneControlInstall();
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

    private void scheduleDoneControlInstall() {
        View widgetArea = mainActivity.findViewById(R.id.widgetLayout);
        if (widgetArea == null) return;
        widgetArea.post(this::installDoneControls);
        widgetArea.postDelayed(this::installDoneControls, 500L);
    }

    private void installDoneControls() {
        View areaView = mainActivity.findViewById(R.id.widgetLayout);
        if (!(areaView instanceof ViewGroup)) return;
        ViewGroup area = (ViewGroup) areaView;
        if (area.getChildCount() == 0 || !(area.getChildAt(0) instanceof ViewGroup)) return;

        ViewGroup surface = (ViewGroup) area.getChildAt(0);
        for (int i = 0; i < surface.getChildCount(); i++) {
            View child = surface.getChildAt(i);
            if (child instanceof FrameLayout) attachDoneControl((FrameLayout) child);
        }
    }

    private void attachDoneControl(FrameLayout frame) {
        for (int i = 0; i < frame.getChildCount(); i++) {
            if (DONE_TAG.equals(frame.getChildAt(i).getTag())) return;
        }

        TextView done = new TextView(mainActivity);
        done.setTag(DONE_TAG);
        done.setText("✓");
        done.setTextColor(Color.WHITE);
        done.setTextSize(20f);
        done.setGravity(Gravity.CENTER);
        done.setContentDescription("Confirm widget placement");
        done.setVisibility(View.GONE);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(38, 166, 91));
        background.setStroke(dp(1), Color.WHITE);
        done.setBackground(background);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(36), dp(36), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(26);
        frame.addView(done, params);

        done.setOnClickListener(v -> finishWidgetEdit(frame, done));
        frame.getViewTreeObserver().addOnGlobalLayoutListener(() -> syncDoneVisibility(frame, done));
        syncDoneVisibility(frame, done);
    }

    private void syncDoneVisibility(FrameLayout frame, View done) {
        // Child 0 is the live widget stack layer and child 1 is persistent stack-position chrome.
        // Edit controls begin at child 2. Ignoring the first two keeps a stack's page indicator
        // from being mistaken for an active resize/edit control.
        boolean editing = false;
        for (int i = 2; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (child != done && child.getVisibility() == View.VISIBLE) {
                editing = true;
                break;
            }
        }
        int wanted = editing ? View.VISIBLE : View.GONE;
        if (done.getVisibility() != wanted) done.setVisibility(wanted);
    }

    private void finishWidgetEdit(FrameLayout frame, View done) {
        // Preserve child 1, the stack position indicator. Only actual edit controls are hidden.
        for (int i = 2; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (child != done) child.setVisibility(View.GONE);
        }
        frame.setBackground(null);
        done.setVisibility(View.GONE);
        frame.requestLayout();
        frame.performHapticFeedback(HapticFeedbackConstantsCompat.CONFIRM);
    }

    private int dp(int value) {
        return Math.round(value * mainActivity.getResources().getDisplayMetrics().density);
    }
}