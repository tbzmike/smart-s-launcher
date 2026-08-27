package fr.neamar.kiss.ui;

import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.Locale;

import fr.neamar.kiss.notification.MediaControlClassifier;
import fr.neamar.kiss.notification.MediaNotificationSupport;
import fr.neamar.kiss.notification.NotificationListener;
import fr.neamar.kiss.notification.NotificationVisualSupport;
import fr.neamar.kiss.utils.AppLaunchUtils;

/** Builds the rich artwork/image area shared by active and historical notification dialogs. */
public final class NotificationRichPreview {
    private NotificationRichPreview() {}

    public static final class Preview {
        public final View view;
        public final boolean media;
        public final boolean activeMedia;

        Preview(View view, boolean media, boolean activeMedia) {
            this.view = view;
            this.media = media;
            this.activeMedia = activeMedia;
        }
    }

    @Nullable
    public static Preview create(Context context, String notificationId, String packageName,
                                 CharSequence fallbackTitle, CharSequence fallbackBody) {
        StatusBarNotification active = findActive(notificationId);
        Notification notification = active == null ? null : active.getNotification();
        boolean media = isMedia(notification);
        NotificationVisualSupport.Snapshot visual =
                NotificationVisualSupport.snapshot(context, notificationId);

        if (media) {
            return new Preview(createMediaPanel(context, notificationId, packageName, notification,
                    visual, fallbackTitle, fallbackBody), true, true);
        }

        // A media notification may already have left Android's panel. Persisted album art still
        // identifies it as rich content when the package has a saved media snapshot.
        MediaNotificationSupport.Snapshot mediaSnapshot = packageName == null ? null
                : MediaNotificationSupport.snapshotForPackage(context, packageName);
        if (active == null && mediaSnapshot != null && mediaSnapshot.artwork != null
                && visual != null && visual.image != null) {
            return new Preview(createMediaPanel(context, notificationId, packageName, null,
                    visual, fallbackTitle, fallbackBody), true, false);
        }

        if (visual == null || (!visual.hasImage() && !visual.hasPlayableVideo())) return null;
        return new Preview(createVisualPanel(context, visual), false, false);
    }

    private static View createMediaPanel(Context context, String notificationId, String packageName,
                                         @Nullable Notification notification,
                                         @Nullable NotificationVisualSupport.Snapshot visual,
                                         CharSequence fallbackTitle, CharSequence fallbackBody) {
        int pad = dp(context, 12);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, pad, 0, dp(context, 4));

        MediaNotificationSupport.Snapshot support = packageName == null ? null
                : MediaNotificationSupport.snapshotForPackage(context, packageName);
        MediaController controller = notification == null ? null : controller(context, notification);
        MediaMetadata metadata = null;
        PlaybackState playback = null;
        if (controller != null) {
            try {
                metadata = controller.getMetadata();
                playback = controller.getPlaybackState();
            } catch (RuntimeException ignored) {
                metadata = null;
                playback = null;
            }
        }

        Drawable artwork = support == null ? null : support.artwork;
        if (artwork == null && visual != null) artwork = visual.image;

        LinearLayout main = new LinearLayout(context);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(main, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (artwork != null) {
            FrameLayout artworkFrame = new FrameLayout(context);
            ImageView art = new ImageView(context);
            art.setImageDrawable(artwork);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable artBackground = rounded(Color.argb(36, 255, 255, 255), dp(context, 18));
            art.setBackground(artBackground);
            art.setClipToOutline(true);
            artworkFrame.addView(art, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (visual != null && visual.hasPlayableVideo()) {
                TextView play = videoPlayBadge(context);
                FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(
                        dp(context, 56), dp(context, 56), Gravity.CENTER);
                artworkFrame.addView(play, playLp);
                artworkFrame.setContentDescription("Play video");
                artworkFrame.setOnClickListener(v -> openVideo(context, visual, packageName));
            }
            LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(context, 136), dp(context, 136));
            artLp.rightMargin = pad;
            main.addView(artworkFrame, artLp);
        }

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        main.addView(details, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String title = metadataText(metadata, MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (TextUtils.isEmpty(title)) title = clean(fallbackTitle);
        String artist = metadataText(metadata, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (TextUtils.isEmpty(artist)) artist = clean(fallbackBody);
        String album = metadataText(metadata, MediaMetadata.METADATA_KEY_ALBUM,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);

        TextView titleView = text(context, title, 20f, true);
        titleView.setMaxLines(2);
        details.addView(titleView);
        if (!TextUtils.isEmpty(artist)) {
            TextView artistView = text(context, artist, 17f, false);
            artistView.setMaxLines(2);
            artistView.setAlpha(0.90f);
            details.addView(artistView);
        }
        if (!TextUtils.isEmpty(album) && !album.equalsIgnoreCase(artist)) {
            TextView albumView = text(context, album, 14f, false);
            albumView.setMaxLines(2);
            albumView.setAlpha(0.72f);
            details.addView(albumView);
        }

        long duration = metadata == null ? 0L : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = estimatedPosition(playback, duration);
        if (duration > 0L) {
            SeekBar seek = new SeekBar(context);
            seek.setMax(1000);
            seek.setProgress((int) Math.max(0, Math.min(1000, position * 1000L / duration)));
            seek.setPadding(0, dp(context, 10), 0, 0);
            if (controller != null && playback != null
                    && (playback.getActions() & PlaybackState.ACTION_SEEK_TO) != 0L) {
                MediaController finalController = controller;
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
                    @Override public void onStartTrackingTouch(SeekBar bar) { }
                    @Override public void onStopTrackingTouch(SeekBar bar) {
                        try {
                            finalController.getTransportControls().seekTo(duration * bar.getProgress() / 1000L);
                        } catch (RuntimeException ignored) { }
                    }
                });
            } else {
                seek.setEnabled(false);
            }
            root.addView(seek, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout times = new LinearLayout(context);
            TextView elapsed = text(context, formatTime(position), 13f, false);
            TextView total = text(context, formatTime(duration), 13f, false);
            times.addView(elapsed, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            total.setGravity(Gravity.END);
            times.addView(total, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(times);
        }

        if (support != null && support.active) {
            LinearLayout controls = new LinearLayout(context);
            controls.setGravity(Gravity.CENTER);
            controls.setPadding(0, dp(context, 6), 0, 0);
            if (support.previous) controls.addView(controlButton(context, "◀|", packageName,
                    MediaControlClassifier.Kind.PREVIOUS));
            if (support.playPause) controls.addView(controlButton(context,
                    support.playing ? "Ⅱ" : "▶", packageName, MediaControlClassifier.Kind.PLAY_PAUSE));
            if (support.next) controls.addView(controlButton(context, "|▶", packageName,
                    MediaControlClassifier.Kind.NEXT));
            root.addView(controls, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 64)));
        }

        return root;
    }

    private static View createVisualPanel(Context context, NotificationVisualSupport.Snapshot visual) {
        int top = dp(context, 10);
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(0, top, 0, dp(context, 4));
        int height = dp(context, 230);
        if (visual.image != null) {
            ImageView image = new ImageView(context);
            image.setImageDrawable(visual.image);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setBackground(rounded(Color.argb(30, 255, 255, 255), dp(context, 18)));
            frame.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
        }
        if (visual.hasPlayableVideo()) {
            TextView play = videoPlayBadge(context);
            FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(
                    dp(context, 64), dp(context, 64), Gravity.CENTER);
            frame.addView(play, playLp);
            frame.setContentDescription("Play video");
            frame.setOnClickListener(v -> openVideo(context, visual, null));
            if (visual.image == null) {
                frame.setBackground(rounded(Color.argb(80, 0, 0, 0), dp(context, 18)));
                frame.setMinimumHeight(height);
            }
        }
        return frame;
    }

    private static Button controlButton(Context context, String label, String packageName,
                                        MediaControlClassifier.Kind kind) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(kind == MediaControlClassifier.Kind.PLAY_PAUSE ? 24f : 19f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        int size = kind == MediaControlClassifier.Kind.PLAY_PAUSE ? dp(context, 60) : dp(context, 52);
        GradientDrawable bg = rounded(Color.argb(26, 255, 255, 255), size / 2f);
        bg.setStroke(dp(context, 1), Color.argb(120, 255, 255, 255));
        button.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = dp(context, 9);
        lp.rightMargin = dp(context, 9);
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> MediaNotificationSupport.perform(context, packageName, kind));
        return button;
    }

    private static void openVideo(Context context, NotificationVisualSupport.Snapshot visual,
                                  @Nullable String packageName) {
        if (NotificationVisualSupport.openMedia(context, visual)) return;
        if (packageName != null && AppLaunchUtils.launchPackage(context, packageName)) return;
        Toast.makeText(context, "Video is no longer available from this notification",
                Toast.LENGTH_SHORT).show();
    }

    public static boolean isActiveMedia(Context context, String notificationId) {
        StatusBarNotification active = findActive(notificationId);
        return active != null && isMedia(active.getNotification());
    }

    @Nullable
    private static StatusBarNotification findActive(String notificationId) {
        if (TextUtils.isEmpty(notificationId)) return null;
        NotificationListener listener = listener();
        if (listener == null) return null;
        StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (RuntimeException e) {
            return null;
        }
        if (active == null) return null;
        for (StatusBarNotification sbn : active) {
            if (sbn != null && notificationId.equals(NotificationListener.getTimelineId(sbn))) return sbn;
        }
        return null;
    }

    private static boolean isMedia(@Nullable Notification notification) {
        if (notification == null) return false;
        if (Notification.CATEGORY_TRANSPORT.equals(notification.category)) return true;
        Bundle extras = notification.extras;
        return extras != null && extras.get(Notification.EXTRA_MEDIA_SESSION) instanceof MediaSession.Token;
    }

    @Nullable
    private static NotificationListener listener() {
        try {
            Field field = NotificationListener.class.getDeclaredField("instance");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof NotificationListener ? (NotificationListener) value : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static MediaController controller(Context context, Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return null;
        Object token = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        if (!(token instanceof MediaSession.Token)) return null;
        try {
            return new MediaController(context, (MediaSession.Token) token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long estimatedPosition(@Nullable PlaybackState state, long duration) {
        if (state == null) return 0L;
        long position = Math.max(0L, state.getPosition());
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0L) {
            long delta = Math.max(0L, android.os.SystemClock.elapsedRealtime()
                    - state.getLastPositionUpdateTime());
            position += Math.round(delta * state.getPlaybackSpeed());
        }
        if (duration > 0L) position = Math.min(duration, position);
        return Math.max(0L, position);
    }

    private static String metadataText(@Nullable MediaMetadata metadata, String... keys) {
        if (metadata == null) return "";
        for (String key : keys) {
            CharSequence value = metadata.getText(key);
            if (value != null && !value.toString().trim().isEmpty()) return value.toString().trim();
        }
        return "";
    }

    private static TextView text(Context context, String value, float size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        AppNativeDialogStyle.setReadableText(view);
        return view;
    }

    private static TextView videoPlayBadge(Context context) {
        TextView play = new TextView(context);
        play.setText("▶");
        play.setTextSize(28f);
        play.setTextColor(Color.WHITE);
        play.setGravity(Gravity.CENTER);
        GradientDrawable bg = rounded(Color.argb(170, 0, 0, 0), dp(context, 32));
        bg.setStroke(dp(context, 1), Color.argb(150, 255, 255, 255));
        play.setBackground(bg);
        return play;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static String clean(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
