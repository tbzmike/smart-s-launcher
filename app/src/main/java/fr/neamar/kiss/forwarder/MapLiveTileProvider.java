package fr.neamar.kiss.forwarder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import fr.neamar.kiss.MainActivity;
import fr.neamar.kiss.utils.Log;

/** Lightweight, cached live-location source used only by the Google Maps history tile. */
final class MapLiveTileProvider {
    static final String MAPS_PACKAGE = "com.google.android.apps.maps";
    private static final String TAG = "MapLiveTileProvider";
    private static final int LOCATION_REQUEST_CODE = 4704;
    private static final String PREF_LIVE_LOCATION_DETAILS = "smart-live-location-details";
    private static final long REFRESH_MIN_MS = 60_000L;
    private static final long MAX_CURRENT_LOCATION_AGE_MS = 2L * 60_000L;
    private static final long MAX_LAST_KNOWN_AGE_MS = 10L * 60_000L;
    private static final float MIN_MOVEMENT_METERS = 8f;
    private static final AtomicBoolean REQUEST_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean PERMISSION_PROMPTED = new AtomicBoolean(false);
    private static volatile long lastRequestTime;
    private static volatile Location lastLocation;

    private MapLiveTileProvider() { }

    private static boolean liveLocationDetailsEnabled(Context context) {
        return context != null
                && PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_LIVE_LOCATION_DETAILS, false);
    }

    static boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void requestFreshLocation(MainActivity activity, Runnable onChanged) {
        if (activity == null) return;
        if (!liveLocationDetailsEnabled(activity)) return;
        if (!hasLocationPermission(activity)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
            if (PERMISSION_PROMPTED.compareAndSet(false, true)) {
                try {
                    activity.requestPermissions(new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    }, LOCATION_REQUEST_CODE);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to request location permission", e);
                }
            }
            waitForPermission(activity, onChanged, 40);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRequestTime < REFRESH_MIN_MS) {
            if (onChanged != null && lastLocation != null) onChanged.run();
            return;
        }
        if (!REQUEST_IN_FLIGHT.compareAndSet(false, true)) return;
        lastRequestTime = now;

        LocationManager manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            REQUEST_IN_FLIGHT.set(false);
            return;
        }

        List<String> providers = new ArrayList<>(2);
        boolean precise = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        try {
            if (precise && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                providers.add(LocationManager.GPS_PROVIDER);
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                providers.add(LocationManager.NETWORK_PROVIDER);
            }
            if (!providers.contains(LocationManager.GPS_PROVIDER)
                    && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                providers.add(LocationManager.GPS_PROVIDER);
            }
        } catch (RuntimeException ignored) { }

        if (providers.isEmpty()) {
            REQUEST_IN_FLIGHT.set(false);
            return;
        }
        requestFromProvider(activity, manager, providers, 0, onChanged);
    }

    private static void waitForPermission(MainActivity activity, Runnable onChanged, int attemptsLeft) {
        if (attemptsLeft <= 0 || activity.isFinishing()) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (hasLocationPermission(activity)) {
                PERMISSION_PROMPTED.set(false);
                lastRequestTime = 0L;
                requestFreshLocation(activity, onChanged);
            } else {
                waitForPermission(activity, onChanged, attemptsLeft - 1);
            }
        }, 500L);
    }

    private static void requestFromProvider(MainActivity activity, LocationManager manager,
                                            List<String> providers, int index, Runnable onChanged) {
        if (index >= providers.size()) {
            REQUEST_IN_FLIGHT.set(false);
            return;
        }
        String provider = providers.get(index);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Executor executor = activity.getMainExecutor();
                manager.getCurrentLocation(provider, new CancellationSignal(), executor, location -> {
                    if (isFresh(location, MAX_CURRENT_LOCATION_AGE_MS)) {
                        REQUEST_IN_FLIGHT.set(false);
                        boolean changed = acceptLocation(location);
                        if (onChanged != null && (changed || lastLocation != null)) onChanged.run();
                    } else {
                        requestFromProvider(activity, manager, providers, index + 1, onChanged);
                    }
                });
            } else {
                @SuppressWarnings("deprecation")
                Location candidate = manager.getLastKnownLocation(provider);
                if (isFresh(candidate, MAX_LAST_KNOWN_AGE_MS)) {
                    REQUEST_IN_FLIGHT.set(false);
                    boolean changed = acceptLocation(candidate);
                    if (onChanged != null && (changed || lastLocation != null)) onChanged.run();
                } else {
                    requestFromProvider(activity, manager, providers, index + 1, onChanged);
                }
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Unable to request current location from " + provider, e);
            requestFromProvider(activity, manager, providers, index + 1, onChanged);
        }
    }

    static LiveTileDataProvider.LiveTileData latest(Context context) {
        if (!liveLocationDetailsEnabled(context)) return null;
        if (!hasLocationPermission(context)) return null;
        Location location = lastLocation != null ? new Location(lastLocation) : bestLastKnown(context);
        if (!isFresh(location, MAX_LAST_KNOWN_AGE_MS)) return null;
        if (lastLocation == null) acceptLocation(location);

        String accuracy = location.hasAccuracy() ? " ±" + Math.round(location.getAccuracy()) + " m" : "";
        String text = String.format(Locale.US, "%.5f, %.5f%s",
                location.getLatitude(), location.getLongitude(), accuracy);
        return new LiveTileDataProvider.LiveTileData(
                null, "Current location", text, "Live · on-device location", 0, 0, false);
    }

    private static boolean acceptLocation(Location location) {
        if (location == null) return false;
        Location previous = lastLocation;
        boolean moved = previous == null || previous.distanceTo(location) >= MIN_MOVEMENT_METERS;
        boolean newer = previous == null || location.getTime() > previous.getTime();
        if (!newer && !moved) return false;
        lastLocation = new Location(location);
        return true;
    }

    private static boolean isFresh(Location location, long maxAgeMs) {
        if (location == null) return false;
        long time = location.getTime();
        if (time <= 0) return true;
        return Math.abs(System.currentTimeMillis() - time) <= maxAgeMs;
    }

    private static Location bestLastKnown(Context context) {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return null;
        Location best = null;
        try {
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (!isFresh(candidate, MAX_LAST_KNOWN_AGE_MS)) continue;
                if (best == null || candidate.getTime() > best.getTime()
                        || (candidate.hasAccuracy() && (!best.hasAccuracy()
                        || candidate.getAccuracy() < best.getAccuracy()))) {
                    best = candidate;
                }
            }
        } catch (SecurityException ignored) { }
        return best;
    }


}
