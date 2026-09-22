package fr.neamar.kiss.update;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/**
 * Opens updater connections on Android's current active network when possible.
 *
 * Smart S Launcher is a long-lived HOME process. Unlike a normal app that is relaunched often,
 * it can survive Wi-Fi/mobile handovers for days. Explicitly using the active Network prevents an
 * updater check from being stranded on a stale process/default network after such a handover.
 */
final class UpdateNetwork {
    private UpdateNetwork() {
    }

    static HttpURLConnection open(Context context, String rawUrl,
                                  int connectTimeoutMs, int readTimeoutMs) throws IOException {
        URL url = new URL(rawUrl);
        URLConnection raw = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
                if (network != null && caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    raw = network.openConnection(url);
                }
            }
        }

        if (raw == null) raw = url.openConnection();
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("Updater URL did not create an HTTP connection");
        }

        HttpURLConnection connection = (HttpURLConnection) raw;
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        return connection;
    }

    static String describe(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "legacy Android network";
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "ConnectivityManager unavailable";
        Network network = cm.getActiveNetwork();
        if (network == null) return "no active Android network";
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return "active network has no capabilities";
        boolean internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return "active network: internet=" + internet + ", validated=" + validated;
    }
}
