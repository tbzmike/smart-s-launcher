package fr.neamar.kiss.forwarder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
    private static final int ZOOM = 16;
    private static final long REFRESH_MIN_MS = 60_000L;
    private static final long MAX_CURRENT_LOCATION_AGE_MS = 2L * 60_000L;
    private static final long MAX_LAST_KNOWN_AGE_MS = 10L * 60_000L;
    private static final float MIN_MOVEMENT_METERS = 8f;
    private static final AtomicBoolean REQUEST_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean PERMISSION_PROMPTED = new AtomicBoolean(false);
    private static volatile long lastRequestTime;
    private static volatile Location lastLocation;
    private static volatile String lastStreet = "";

    private MapLiveTileProvider() { }

    static boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void requestFreshLocation(MainActivity activity, Runnable onChanged) {
        if (activity == null) return;
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
        if (!hasLocationPermission(context)) return null;
        Location location = lastLocation != null ? new Location(lastLocation) : bestLastKnown(context);
        if (!isFresh(location, MAX_LAST_KNOWN_AGE_MS)) return null;
        if (lastLocation == null) acceptLocation(location);

        if (TextUtils.isEmpty(lastStreet)) {
            String street = reverseGeocode(context, location);
            if (!TextUtils.isEmpty(street)) lastStreet = street;
        }
        String title = TextUtils.isEmpty(lastStreet) ? "Current location" : lastStreet;
        String accuracy = location.hasAccuracy() ? " ±" + Math.round(location.getAccuracy()) + " m" : "";
        String text = String.format(Locale.US, "%.5f, %.5f%s", location.getLatitude(), location.getLongitude(), accuracy);
        Bitmap map = loadMapPreview(context, location);
        return new LiveTileDataProvider.LiveTileData(
                map == null ? null : new BitmapDrawable(context.getResources(), map),
                title, text, "Live · © OpenStreetMap contributors", 0, 0, false);
    }

    private static boolean acceptLocation(Location location) {
        if (location == null) return false;
        Location previous = lastLocation;
        boolean moved = previous == null || previous.distanceTo(location) >= MIN_MOVEMENT_METERS;
        boolean newer = previous == null || location.getTime() > previous.getTime();
        if (!newer && !moved) return false;
        lastLocation = new Location(location);
        if (moved) lastStreet = "";
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

    private static String reverseGeocode(Context context, Location location) {
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            if (!Geocoder.isPresent()) return "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                final String[] result = new String[]{""};
                final Object lock = new Object();
                geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1, addresses -> {
                    synchronized (lock) {
                        result[0] = bestAddress(addresses);
                        lock.notifyAll();
                    }
                });
                synchronized (lock) { if (TextUtils.isEmpty(result[0])) lock.wait(1500L); }
                return result[0];
            }
            @SuppressWarnings("deprecation")
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            return bestAddress(addresses);
        } catch (Exception e) {
            return "";
        }
    }

    private static String bestAddress(List<Address> addresses) {
        if (addresses == null || addresses.isEmpty()) return "";
        Address a = addresses.get(0);
        if (!TextUtils.isEmpty(a.getThoroughfare())) {
            StringBuilder s = new StringBuilder(a.getThoroughfare());
            if (!TextUtils.isEmpty(a.getSubThoroughfare())) s.insert(0, a.getSubThoroughfare() + " ");
            return s.toString();
        }
        if (!TextUtils.isEmpty(a.getFeatureName())) return a.getFeatureName();
        return a.getAddressLine(0) == null ? "" : a.getAddressLine(0);
    }

    private static Bitmap loadMapPreview(Context context, Location location) {
        int x = lonToTileX(location.getLongitude(), ZOOM);
        int y = latToTileY(location.getLatitude(), ZOOM);
        File dir = new File(context.getCacheDir(), "map_tiles");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File file = new File(dir, ZOOM + "_" + x + "_" + y + ".png");
        Bitmap bitmap = readBitmap(file);
        if (bitmap == null) bitmap = downloadTile(file, x, y);
        if (bitmap == null) return null;
        return drawPositionMarker(bitmap, location, x, y);
    }

    private static Bitmap downloadTile(File target, int x, int y) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://tile.openstreetmap.org/" + ZOOM + "/" + x + "/" + y + ".png");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent", "SmartSLauncher/3.29 (+https://github.com/tbzmike/smart-s-launcher)");
            connection.setRequestProperty("Accept", "image/png,image/*;q=0.8");
            connection.setUseCaches(true);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap != null) {
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                }
                return bitmap;
            }
        } catch (Exception e) {
            Log.w(TAG, "Map preview unavailable", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Bitmap readBitmap(File file) {
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) { return BitmapFactory.decodeStream(in); }
        catch (Exception ignored) { return null; }
    }

    private static Bitmap drawPositionMarker(Bitmap source, Location location, int tileX, int tileY) {
        Bitmap out = source.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return source;
        double worldX = lonToWorldX(location.getLongitude(), ZOOM);
        double worldY = latToWorldY(location.getLatitude(), ZOOM);
        float px = (float) ((worldX - tileX) * out.getWidth());
        float py = (float) ((worldY - tileY) * out.getHeight());
        Canvas canvas = new Canvas(out);
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG); halo.setColor(Color.WHITE);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG); dot.setColor(Color.rgb(33, 150, 243));
        canvas.drawCircle(px, py, 13f, halo);
        canvas.drawCircle(px, py, 8f, dot);
        return out;
    }

    private static int lonToTileX(double lon, int zoom) { return (int) Math.floor(lonToWorldX(lon, zoom)); }
    private static int latToTileY(double lat, int zoom) { return (int) Math.floor(latToWorldY(lat, zoom)); }
    private static double lonToWorldX(double lon, int zoom) { return (lon + 180.0) / 360.0 * (1 << zoom); }
    private static double latToWorldY(double lat, int zoom) {
        double clipped = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double rad = Math.toRadians(clipped);
        return (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * (1 << zoom);
    }
}
