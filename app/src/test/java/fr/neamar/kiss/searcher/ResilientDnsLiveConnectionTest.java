package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Live connection regression test for the exact failure seen on-device:
 * Android/system DNS is bypassed and Smart S must resolve Google Play using only its fallback
 * resolver, then complete a TLS/HTTPS request using the original hostname.
 */
class ResilientDnsLiveConnectionTest {
    @Test
    void forcedFallbackDnsCanReachGooglePlayOverHttps() throws Exception {
        Dns forcedFallback = hostname -> {
            List<InetAddress> resolved = ResilientDns.lookupFallbackForTest(hostname);
            assertTrue(!resolved.isEmpty(), "Fallback resolver returned no addresses for " + hostname);
            return resolved;
        };

        OkHttpClient client = new OkHttpClient.Builder()
                .dns(forcedFallback)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        Request request = new Request.Builder()
                .url("https://play.google.com/store/apps/details?id=com.whatsapp&hl=en&gl=ZA")
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/140 Mobile Safari/537.36")
                .build();

        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.code() >= 100,
                    "No valid HTTP response received from Google Play");
            assertNotNull(response.handshake(),
                    "Google Play HTTPS connection did not complete a TLS handshake");
        }
    }
}
