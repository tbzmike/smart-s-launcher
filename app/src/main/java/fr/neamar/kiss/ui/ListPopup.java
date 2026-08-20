package fr.neamar.kiss.ui;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import fr.neamar.kiss.R;
import fr.neamar.kiss.utils.SystemUiVisibilityHelper;

public class ListPopup extends PopupWindow {
    private final View.OnClickListener mClickListener;
    private final View.OnLongClickListener mLongClickListener;
    private OnItemLongClickListener mItemLongClickListener;
    private OnItemClickListener mItemClickListener;
    private DataSetObserver mObserver;
    private ListAdapter mAdapter;
    private SystemUiVisibilityHelper mSystemUiVisibilityHelper;
    private boolean dismissOnClick = true;
    private boolean animatingDismiss;

    public ListPopup(Context context) {
        super(context, null, android.R.attr.popupMenuStyle);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(layout);
        setContentView(scrollView);
        setWidth(LinearLayout.LayoutParams.WRAP_CONTENT);
        setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
        mItemClickListener = null;
        mClickListener = view -> {
            if (dismissOnClick)
                dismiss();
            if (mItemClickListener != null) {
                LinearLayout layout2 = getLinearLayout();
                int position = layout2.indexOfChild(view);
                mItemClickListener.onItemClick(mAdapter, view, position);
            }
        };
        mLongClickListener = view -> {
            if (mItemLongClickListener == null)
                return false;
            LinearLayout layout1 = getLinearLayout();
            int position = layout1.indexOfChild(view);
            return mItemLongClickListener.onItemLongClick(mAdapter, view, position);
        };
    }

    public void setOnItemClickListener(OnItemClickListener onItemClickListener) {
        mItemClickListener = onItemClickListener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener onItemLongClickListener) {
        mItemLongClickListener = onItemLongClickListener;
    }

    public void setVisibilityHelper(SystemUiVisibilityHelper systemUiVisibility) {
        mSystemUiVisibilityHelper = systemUiVisibility;
        mSystemUiVisibilityHelper.addPopup();
    }

    @Override
    public void dismiss() {
        if (!isShowing()) return;
        if (animatingDismiss) return;
        animatingDismiss = true;
        SmartAnimationEngine.animatePopupViewOut(getContentView(), this::dismissImmediately);
    }

    private void dismissImmediately() {
        super.dismiss();
        animatingDismiss = false;
        if (mSystemUiVisibilityHelper != null)
            mSystemUiVisibilityHelper.popPopup();
    }

    public ListAdapter getAdapter() {
        return mAdapter;
    }

    public void setDismissOnItemClick(boolean dismissOnClick) {
        this.dismissOnClick = dismissOnClick;
    }

    /**
     * Sets the adapter that provides the data and the views to represent the data
     * in this popup window.
     *
     * @param adapter The adapter to use to create this window's content.
     */
    public void setAdapter(ListAdapter adapter) {
        if (mObserver == null) {
            mObserver = new PopupDataSetObserver();
        } else if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(mObserver);
        }
        mAdapter = adapter;
        if (mAdapter != null) {
            adapter.registerDataSetObserver(mObserver);
        }
    }

    private LinearLayout getLinearLayout() {
        return (LinearLayout) ((ScrollView) getContentView()).getChildAt(0);
    }

    protected void updateItems() {
        LinearLayout layout = getLinearLayout();
        layout.removeAllViews();
        if (mAdapter == null) return;
        int adapterCount = mAdapter.getCount();
        for (int i = 0; i < adapterCount; i += 1) {
            View view = mAdapter.getView(i, null, layout);
            layout.addView(view);
            if (mAdapter.isEnabled(i)) {
                view.setOnClickListener(mClickListener);
                if (mItemLongClickListener == null) {
                    view.setLongClickable(false);
                } else {
                    view.setOnLongClickListener(mLongClickListener);
                }
            }
        }
    }

    private void appendResizeItem(View anchor) {
        Object tag = anchor == null ? null : anchor.getTag();
        if (!(tag instanceof ResizeTarget)) return;

        LinearLayout layout = getLinearLayout();
        TextView resize = (TextView) LayoutInflater.from(anchor.getContext())
                .inflate(R.layout.popup_list_item, layout, false);
        resize.setText("Resize");
        resize.setContentDescription("Resize");
        resize.setOnClickListener(v -> {
            dismiss();
            ((ResizeTarget) tag).beginResize();
        });
        layout.addView(resize);
    }

    public void show(View anchor) {
        show(anchor, .5f);
    }

    public void show(View anchor, float anchorOverlap) {
        updateItems();
        appendResizeItem(anchor);

        if (mSystemUiVisibilityHelper != null)
            mSystemUiVisibilityHelper.copyVisibility(getContentView());

        // don't steal the focus, this will prevent the keyboard from changing
        setFocusable(false);
        // draw over stuff if needed
        setClippingEnabled(false);

        final Rect displayFrame = new Rect();
        anchor.getWindowVisibleDisplayFrame(displayFrame);

        final int[] anchorPos = new int[2];
        anchor.getLocationOnScreen(anchorPos);

        // calculate absolute position of anchor
        int overlapAmount = (int) (anchor.getHeight() * anchorOverlap);
        int absoluteAnchorPos = (anchorPos[1] + anchor.getHeight()) - overlapAmount;
        // anchor should be on visible screen
        if (absoluteAnchorPos > displayFrame.bottom) {
            absoluteAnchorPos = displayFrame.bottom;
        }
        if (absoluteAnchorPos < displayFrame.top) {
            absoluteAnchorPos = displayFrame.top;
        }
        final int distanceToBottom = displayFrame.bottom - absoluteAnchorPos;
        final int distanceToTop = absoluteAnchorPos - displayFrame.top;
        // calculate new relative position of anchor
        final int relativeAnchorPos = absoluteAnchorPos - (anchorPos[1] + anchor.getHeight());

        LinearLayout linearLayout = getLinearLayout();

        linearLayout.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        setWidth(Math.min(linearLayout.getMeasuredWidth(), (displayFrame.right - displayFrame.left) * 2 / 3));

        int xOffset = anchor.getPaddingStart();
        boolean smartAnimations = SmartAnimationEngine.isEnabled(anchor.getContext());

        int yOffset;
        if (distanceToBottom > linearLayout.getMeasuredHeight()) {
            // show below anchor
            yOffset = relativeAnchorPos;
            setAnimationStyle(smartAnimations ? 0 : R.style.PopupAnimationTop);
        } else if (distanceToBottom >= distanceToTop) {
            // show below anchor with scroll depending on menu height
            yOffset = relativeAnchorPos;
            int menuHeight = Math.min(distanceToBottom, linearLayout.getMeasuredHeight());
            setHeight(menuHeight);
            setAnimationStyle(smartAnimations ? 0 : R.style.PopupAnimationTop);
        } else {
            // show above anchor with scroll depending on menu height
            int menuHeight = Math.min(distanceToTop, linearLayout.getMeasuredHeight());
            yOffset = relativeAnchorPos - menuHeight;
            setHeight(menuHeight);
            setAnimationStyle(smartAnimations ? 0 : R.style.PopupAnimationBottom);
        }

        showAsDropDown(anchor, xOffset, yOffset);
        if (smartAnimations) SmartAnimationEngine.animatePopupViewIn(getContentView());
    }

    public interface OnItemClickListener {
        void onItemClick(ListAdapter adapter, View view, int position);
    }

    public interface OnItemLongClickListener {
        boolean onItemLongClick(ListAdapter adapter, View view, int position);
    }

    public static class Item {
        @StringRes
        public final int stringId;
        final String string;

        public Item(Context context, @StringRes int stringId) {
            super();
            this.stringId = stringId;
            this.string = context.getResources()
                    .getString(stringId);
        }

        public Item(@NonNull String string) {
            super();
            this.stringId = 0;
            this.string = string;
        }

        @NonNull
        @Override
        public String toString() {
            return this.string;
        }
    }

    protected class ScrollView extends android.widget.ScrollView {
        public ScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            // act as a modal, if we click outside dismiss the popup
            final int x = (int) event.getX();
            final int y = (int) event.getY();
            if ((event.getAction() == MotionEvent.ACTION_DOWN)
                    && ((x < 0) || (x >= getWidth()) || (y < 0) || (y >= getHeight()))) {
                dismiss();
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return super.dispatchTouchEvent(event);
        }
    }

    protected class PopupDataSetObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            if (isShowing()) {
                // Resize the popup to fit new content
                updateItems();
                update();
            }
        }

        @Override
        public void onInvalidated() {
            dismiss();
        }
    }
}
