from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}\n--- needle ---\n{old[:700]}")
    p.write_text(text.replace(old, new, 1))


# Exact version bump from the verified 3.30.77 source.
replace_once(
    "app/build.gradle",
    '''        // Smart S Launcher 3.30.77 - warm Home snapshots and lightweight vertical scrolling\n        versionCode 505\n        versionName "3.30.77"''',
    '''        // Smart S Launcher 3.30.78 - lightweight flashing notification attention in Vertical Cards\n        versionCode 506\n        versionName "3.30.78"''')

path = "app/src/main/java/fr/neamar/kiss/forwarder/VerticalCardNotificationHistoryForwarder.java"

# AnimationDrawable changes only its own overlay drawable on each discrete flash frame. It avoids
# the old Handler loop that walked every unread card and invalidated full card Views every 550 ms.
replace_once(
    path,
    '''import android.graphics.drawable.GradientDrawable;''',
    '''import android.graphics.drawable.AnimationDrawable;\nimport android.graphics.drawable.GradientDrawable;''')

replace_once(
    path,
    ''' * Decoration is applied only when cards are created/rebuilt. Unread notifications keep a static\n * attention border; no recurring Handler/invalidations are allowed while Home is idle or scrolling.\n * This prevents notification decoration from competing with ScrollView and marquee frame delivery.''',
    ''' * Decoration is applied only when cards are created/rebuilt. Unread notifications keep the\n * original orange/white flashing attention border, but each border now uses a tiny AnimationDrawable\n * overlay instead of a renderer-wide Handler loop. Card geometry is updated only when layout size\n * actually changes, keeping notification animation out of the scrolling hot path.''')

replace_once(
    path,
    '''    private static final float BOTTOM_SWIPE_AXIS_BIAS = 1.15f;''',
    '''    private static final float BOTTOM_SWIPE_AXIS_BIAS = 1.15f;\n    private static final int ATTENTION_PULSE_MS = 550;''')

replace_once(
    path,
    '''    private void attachAttentionBorder(View wrapper, NotificationPojo notification) {\n        if (!NotificationTimelineState.isUnread(mainActivity, notification.id)) return;\n        View card = cardView(wrapper);\n        if (card == null) return;\n\n        GradientDrawable border = new GradientDrawable();\n        border.setColor(Color.TRANSPARENT);\n        border.setCornerRadius(dp(22));\n        border.setStroke(dp(2), Color.argb(235, 255, 176, 32));\n        AttentionBorder binding = new AttentionBorder(card, border, notification.id);\n        attentionBorders.add(binding);\n        card.post(() -> {\n            if (!attentionBorders.contains(binding)\n                    || !card.isAttachedToWindow()\n                    || !NotificationTimelineState.isUnread(mainActivity, notification.id)) return;\n            border.setBounds(0, 0, Math.max(1, card.getWidth()), Math.max(1, card.getHeight()));\n            card.getOverlay().add(border);\n            card.invalidate();\n        });\n    }''',
    '''    private void attachAttentionBorder(View wrapper, NotificationPojo notification) {\n        if (!NotificationTimelineState.isUnread(mainActivity, notification.id)) return;\n        View card = cardView(wrapper);\n        if (card == null) return;\n\n        AnimationDrawable border = new AnimationDrawable();\n        border.setOneShot(false);\n        border.addFrame(createAttentionFrame(dp(2), Color.argb(235, 255, 176, 32)),\n                ATTENTION_PULSE_MS);\n        border.addFrame(createAttentionFrame(dp(4), Color.WHITE), ATTENTION_PULSE_MS);\n\n        View.OnLayoutChangeListener layoutListener = (v, left, top, right, bottom,\n                                                       oldLeft, oldTop, oldRight, oldBottom) -> {\n            int width = right - left;\n            int height = bottom - top;\n            if (width == oldRight - oldLeft && height == oldBottom - oldTop) return;\n            updateAttentionBounds(v, border);\n        };\n        AttentionBorder binding = new AttentionBorder(\n                card, border, notification.id, layoutListener);\n        attentionBorders.add(binding);\n        card.addOnLayoutChangeListener(layoutListener);\n        card.post(() -> {\n            if (!attentionBorders.contains(binding)\n                    || !card.isAttachedToWindow()\n                    || !NotificationTimelineState.isUnread(mainActivity, notification.id)) return;\n            updateAttentionBounds(card, border);\n            card.getOverlay().add(border);\n            border.start();\n        });\n    }\n\n    private GradientDrawable createAttentionFrame(int strokeWidth, int strokeColor) {\n        GradientDrawable frame = new GradientDrawable();\n        frame.setColor(Color.TRANSPARENT);\n        frame.setCornerRadius(dp(22));\n        frame.setStroke(strokeWidth, strokeColor);\n        return frame;\n    }\n\n    private void updateAttentionBounds(View card, AnimationDrawable border) {\n        border.setBounds(0, 0, Math.max(1, card.getWidth()), Math.max(1, card.getHeight()));\n    }''')

replace_once(
    path,
    '''    private void clearAttentionFor(String notificationId) {\n        for (int i = attentionBorders.size() - 1; i >= 0; i--) {\n            AttentionBorder binding = attentionBorders.get(i);\n            if (!TextUtils.equals(notificationId, binding.notificationId)) continue;\n            binding.card.getOverlay().remove(binding.border);\n            binding.card.invalidate();\n            attentionBorders.remove(i);\n        }\n    }\n\n    private void resetAttentionBorders() {\n        for (AttentionBorder binding : attentionBorders) {\n            binding.card.getOverlay().remove(binding.border);\n            binding.card.invalidate();\n        }\n        attentionBorders.clear();\n    }''',
    '''    private void clearAttentionFor(String notificationId) {\n        for (int i = attentionBorders.size() - 1; i >= 0; i--) {\n            AttentionBorder binding = attentionBorders.get(i);\n            if (!TextUtils.equals(notificationId, binding.notificationId)) continue;\n            removeAttentionBinding(binding);\n            attentionBorders.remove(i);\n        }\n    }\n\n    private void resetAttentionBorders() {\n        for (AttentionBorder binding : attentionBorders) removeAttentionBinding(binding);\n        attentionBorders.clear();\n    }\n\n    private void removeAttentionBinding(AttentionBorder binding) {\n        binding.border.stop();\n        binding.card.removeOnLayoutChangeListener(binding.layoutListener);\n        binding.card.getOverlay().remove(binding.border);\n    }''')

replace_once(
    path,
    '''    private static final class AttentionBorder {\n        final View card;\n        final GradientDrawable border;\n        final String notificationId;\n\n        AttentionBorder(View card, GradientDrawable border, String notificationId) {\n            this.card = card;\n            this.border = border;\n            this.notificationId = notificationId;\n        }\n    }''',
    '''    private static final class AttentionBorder {\n        final View card;\n        final AnimationDrawable border;\n        final String notificationId;\n        final View.OnLayoutChangeListener layoutListener;\n\n        AttentionBorder(View card, AnimationDrawable border, String notificationId,\n                        View.OnLayoutChangeListener layoutListener) {\n            this.card = card;\n            this.border = border;\n            this.notificationId = notificationId;\n            this.layoutListener = layoutListener;\n        }\n    }''')

print("3.30.78 Vertical Cards notification flash patch applied successfully")
