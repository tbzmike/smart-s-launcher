package fr.neamar.kiss.forwarder;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.R;

/** U-style-only intelligence dashboard layered onto the existing Smart Center. */
final class SmartUIntelligenceForwarder extends Forwarder {
    private static final String PREF_LAYOUT = "smart-history-layout";
    private static final String SQUARE_U = "square_u";
    private static final String TAG_DASH = "smart-u-intelligence-dashboard";
    private static final String TAG_BADGE = "smart-u-count-badge";

    private ViewGroup squareTrack;
    private ScrollView centerScroller;
    private LinearLayout center;
    private LinearLayout dashboard;
    private ImageView heroIcon;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView notificationPreview;
    private TextView expandChip;
    private View selectedCard;
    private int selectedIndex = -1;
    private boolean expanded;
    private boolean listenerInstalled;
    private boolean refreshPosted;

    SmartUIntelligenceForwarder(MainActivity mainActivity) { super(mainActivity); }

    void onCreate() { locate(); installListener(); refresh(); }
    void onResume() { locate(); installListener(); refresh(); }
    void onDataSetChanged() {
        if (!isUStyle()) { removeDashboard(); return; }
        locate(); installListener(); postRefresh();
    }

    private boolean isUStyle() {
        return SQUARE_U.equals(prefs.getString(PREF_LAYOUT, "vertical"));
    }

    private void locate() {
        if (!(mainActivity.listContainer instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) mainActivity.listContainer;
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View candidate = container.getChildAt(i);
            if (!(candidate instanceof FrameLayout)) continue;
            FrameLayout root = (FrameLayout) candidate;
            ScrollView scroller = null;
            ViewGroup track = null;
            for (int j = 0; j < root.getChildCount(); j++) {
                View child = root.getChildAt(j);
                if (child instanceof ScrollView && !(child instanceof HorizontalScrollView)) scroller = (ScrollView) child;
                else if (child instanceof ViewGroup) track = (ViewGroup) child;
            }
            if (scroller == null || track == null || scroller.getChildCount() == 0) continue;
            View content = scroller.getChildAt(0);
            if (!(content instanceof LinearLayout)) continue;
            squareTrack = track;
            centerScroller = scroller;
            center = (LinearLayout) content;
            return;
        }
    }

    private void installListener() {
        if (squareTrack == null || listenerInstalled) return;
        squareTrack.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if (isUStyle()) postRefresh(); });
        listenerInstalled = true;
    }

    private void postRefresh() {
        if (squareTrack == null || refreshPosted) return;
        refreshPosted = true;
        squareTrack.post(() -> { refreshPosted = false; refresh(); });
    }

    private void refresh() {
        if (!isUStyle()) { removeDashboard(); return; }
        locate();
        if (center == null || squareTrack == null) return;
        ensureDashboard();
        updateSelection();
        updateDashboard();
        updateCountBadges();
    }

    private void updateSelection() {
        selectedCard = null; selectedIndex = -1;
        float tx = squareTrack.getWidth() / 2f;
        float ty = squareTrack.getHeight();
        float best = Float.MAX_VALUE;
        for (int i = 0; i < squareTrack.getChildCount(); i++) {
            View card = squareTrack.getChildAt(i);
            if (card.getVisibility() != View.VISIBLE) continue;
            float cx = card.getX() + card.getWidth()/2f;
            float cy = card.getY() + card.getHeight()/2f;
            float score = Math.abs(cx-tx) + Math.abs(cy-ty)*0.38f;
            if (score < best) { best = score; selectedCard = card; selectedIndex = i; }
        }
    }

    private void ensureDashboard() {
        View existing = center.findViewWithTag(TAG_DASH);
        if (existing instanceof LinearLayout) { dashboard = (LinearLayout) existing; return; }
        dashboard = new LinearLayout(mainActivity);
        dashboard.setTag(TAG_DASH);
        dashboard.setOrientation(LinearLayout.VERTICAL);
        dashboard.setGravity(Gravity.CENTER);
        dashboard.setPadding(dp(10), dp(8), dp(10), dp(8));
        dashboard.setElevation(dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(220, 12, 18, 31));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.argb(190, 104, 162, 255));
        dashboard.setBackground(bg);

        LinearLayout hero = new LinearLayout(mainActivity);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        heroIcon = new ImageView(mainActivity);
        heroIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hero.addView(heroIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout text = new LinearLayout(mainActivity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(10),0,0,0);
        heroTitle = textView(17f, Color.WHITE);
        heroTitle.setSingleLine(true); heroTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE); heroTitle.setSelected(true);
        heroMeta = textView(12f, Color.argb(225,205,222,250));
        text.addView(heroTitle); text.addView(heroMeta);
        hero.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dashboard.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        notificationPreview = textView(12.5f, Color.argb(235,235,241,255));
        notificationPreview.setPadding(dp(4),dp(7),dp(4),dp(5));
        notificationPreview.setGravity(Gravity.CENTER);
        dashboard.addView(notificationPreview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        expandChip = textView(12.5f, Color.WHITE);
        expandChip.setGravity(Gravity.CENTER); expandChip.setPadding(dp(10),dp(6),dp(10),dp(6));
        GradientDrawable chipBg = new GradientDrawable(); chipBg.setColor(Color.argb(220,31,48,79)); chipBg.setCornerRadius(dp(15));
        expandChip.setBackground(chipBg); expandChip.setClickable(true);
        expandChip.setOnClickListener(v -> { expanded = !expanded; updateDashboard(); if (centerScroller != null && expanded) centerScroller.post(() -> centerScroller.smoothScrollTo(0, center.getHeight())); });
        dashboard.addView(expandChip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3),dp(3),dp(3),dp(7));
        center.addView(dashboard, Math.min(1, center.getChildCount()), lp);
    }

    private TextView textView(float size, int color) {
        TextView v = new TextView(mainActivity); v.setTextSize(size); v.setTextColor(color); return v;
    }

    private void updateDashboard() {
        if (dashboard == null || heroTitle == null) return;
        if (selectedCard == null) {
            heroIcon.setImageDrawable(null); heroTitle.setText("Smart U");
            heroMeta.setText("Ready • swipe to browse"); notificationPreview.setText("Start typing to search or browse recent results");
            expandChip.setText(expanded ? "Compact ▲" : "Expand ▼"); return;
        }
        String label = cardLabel(selectedCard);
        heroTitle.setText(TextUtils.isEmpty(label) ? "Selected item" : label);
        Drawable icon = cardIcon(selectedCard); heroIcon.setImageDrawable(icon);
        int count = notificationCount(label);
        String query = mainActivity.searchEditText == null ? "" : mainActivity.searchEditText.getText().toString().trim();
        String priority = TextUtils.isEmpty(query) ? "Recent priority" : "Search priority";
        heroMeta.setText(priority + " • " + (selectedIndex+1) + "/" + squareTrack.getChildCount() + (count > 0 ? " • " + count + " notification" + (count == 1 ? "" : "s") : ""));
        notificationPreview.setText(count > 0 ? notificationPreview(label) : (expanded ? "No unread notification for this item • swipe up for actions • swipe down for details" : "No unread notifications"));
        notificationPreview.setMaxLines(expanded ? 5 : 2);
        expandChip.setText(expanded ? "Compact ▲" : "Expand details ▼");
    }

    private void updateCountBadges() {
        for (int i=0;i<squareTrack.getChildCount();i++) {
            View card=squareTrack.getChildAt(i);
            if (!(card instanceof FrameLayout)) continue;
            View old=card.findViewWithTag(TAG_BADGE); if(old!=null) ((FrameLayout)card).removeView(old);
            String label=cardLabel(card); int count=notificationCount(label); if(count<=0) continue;
            TextView badge=textView(11f,Color.WHITE); badge.setTag(TAG_BADGE); badge.setText(count>99?"99+":String.valueOf(count)); badge.setGravity(Gravity.CENTER); badge.setElevation(dp(10));
            GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.rgb(220,48,67)); bg.setCornerRadius(dp(12)); bg.setStroke(dp(1),Color.WHITE); badge.setBackground(bg);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(count>9?dp(30):dp(24),dp(24),Gravity.TOP|Gravity.END); lp.topMargin=dp(5); lp.rightMargin=dp(5); ((FrameLayout)card).addView(badge,lp);
        }
    }

    private int notificationCount(String label) {
        if (center == null || TextUtils.isEmpty(label)) return 0;
        int count=0;
        for(int i=0;i<center.getChildCount();i++) { View child=center.getChildAt(i); if(child==dashboard) continue; if(containsText(child,label)) count++; }
        return count;
    }

    private String notificationPreview(String label) {
        if(center==null) return "Notification available";
        for(int i=0;i<center.getChildCount();i++) { View child=center.getChildAt(i); if(child==dashboard) continue; if(containsText(child,label)) { String text=collectText(child); if(!TextUtils.isEmpty(text)) return text; } }
        return "Notification available";
    }

    private boolean containsText(View v,String label) {
        if(v instanceof TextView) { CharSequence t=((TextView)v).getText(); if(!TextUtils.isEmpty(t)&&label.equalsIgnoreCase(t.toString().trim())) return true; }
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) if(containsText(((ViewGroup)v).getChildAt(i),label)) return true;
        return false;
    }

    private String collectText(View v) {
        StringBuilder out=new StringBuilder(); collectText(v,out); return out.toString().trim();
    }
    private void collectText(View v,StringBuilder out) {
        if(v instanceof TextView) { String s=((TextView)v).getText().toString().trim(); if(!s.isEmpty()&&!"Notifications".equalsIgnoreCase(s)&&!"Open".equalsIgnoreCase(s)&&!"Actions".equalsIgnoreCase(s)&&!"Details".equalsIgnoreCase(s)) { if(out.length()>0) out.append(" • "); out.append(s); } }
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) collectText(((ViewGroup)v).getChildAt(i),out);
    }

    private String cardLabel(View v) {
        TextView t=findText(v); return t==null?"":t.getText().toString().trim();
    }
    private TextView findText(View v) {
        if(v instanceof TextView && !TAG_BADGE.equals(v.getTag())) { TextView t=(TextView)v; if(t.getVisibility()==View.VISIBLE&&!TextUtils.isEmpty(t.getText())) return t; }
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) { TextView t=findText(((ViewGroup)v).getChildAt(i)); if(t!=null) return t; }
        return null;
    }
    private Drawable cardIcon(View v) {
        ImageView image=findImage(v); return image==null?null:image.getDrawable();
    }
    private ImageView findImage(View v) {
        if(v instanceof ImageView && v.getVisibility()==View.VISIBLE) return (ImageView)v;
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) { ImageView image=findImage(((ViewGroup)v).getChildAt(i)); if(image!=null) return image; }
        return null;
    }

    private void removeDashboard() {
        if(dashboard!=null && dashboard.getParent() instanceof ViewGroup) ((ViewGroup)dashboard.getParent()).removeView(dashboard);
        if(squareTrack!=null) for(int i=0;i<squareTrack.getChildCount();i++) { View card=squareTrack.getChildAt(i); if(card instanceof FrameLayout) { View badge=card.findViewWithTag(TAG_BADGE); if(badge!=null) ((FrameLayout)card).removeView(badge); } }
        dashboard=null; heroIcon=null; heroTitle=null; heroMeta=null; notificationPreview=null; expandChip=null; selectedCard=null; selectedIndex=-1; expanded=false;
    }

    private int dp(int value) { return Math.round(value*mainActivity.getResources().getDisplayMetrics().density); }
}
