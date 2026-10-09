package fr.neamar.kiss.searcher;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * DNS strategy for app-metadata HTTP requests.
 *
 * <p>Some Android/ROM combinations can leave java.net's resolver unable to resolve any hostname
 * for this process even while the device itself is online. TLS/HTTP are still usable if the
 * hostname can be resolved another way. This resolver always tries Android's normal DNS first and,
 * only after an UnknownHostException, performs a small standard DNS A-record query directly
 * against well-known public recursive resolvers. OkHttp then connects to the returned address
 * while preserving the original hostname for TLS/SNI/certificate validation.</p>
 */
final class ResilientDns implements Dns {
    static final ResilientDns INSTANCE = new ResilientDns();

    private static final String[] FALLBACK_RESOLVERS = {
            "1.1.1.1",  // Cloudflare
            "8.8.8.8",  // Google
            "9.9.9.9"   // Quad9
    };
    private static final int DNS_PORT = 53;
    private static final int DNS_TIMEOUT_MS = 1800;
    private static final int MAX_PACKET = 2048;

    private static final Dns BOOTSTRAP_DNS = hostname -> {
        if ("cloudflare-dns.com".equalsIgnoreCase(hostname)) {
            List<InetAddress> addresses = new ArrayList<>();
            addresses.add(InetAddress.getByName("1.1.1.1"));
            addresses.add(InetAddress.getByName("1.0.0.1"));
            return addresses;
        }
        if ("dns.google".equalsIgnoreCase(hostname)) {
            List<InetAddress> addresses = new ArrayList<>();
            addresses.add(InetAddress.getByName("8.8.8.8"));
            addresses.add(InetAddress.getByName("8.8.4.4"));
            return addresses;
        }
        return Dns.SYSTEM.lookup(hostname);
    };

    private static final OkHttpClient DOH_CLIENT = new OkHttpClient.Builder()
            .dns(BOOTSTRAP_DNS)
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private final AtomicInteger systemFailures = new AtomicInteger();
    private final AtomicInteger fallbackSuccesses = new AtomicInteger();
    private final AtomicInteger fallbackFailures = new AtomicInteger();
    private final AtomicInteger transaction = new AtomicInteger(0x4A31);

    private ResilientDns() { }

    @Override
    @NonNull
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        if (hostname.isEmpty()) throw new UnknownHostException("hostname == empty");

        try {
            List<InetAddress> system = Dns.SYSTEM.lookup(hostname);
            if (system != null && !system.isEmpty()) return system;
        } catch (UnknownHostException systemError) {
            systemFailures.incrementAndGet();

            for (String resolver : FALLBACK_RESOLVERS) {
                try {
                    List<InetAddress> recovered = queryA(hostname, resolver);
                    if (!recovered.isEmpty()) {
                        fallbackSuccesses.incrementAndGet();
                        return recovered;
                    }
                } catch (IOException ignored) {
                    // Try the next resolver.
                }
            }

            // Some mobile networks block direct UDP/53 while normal HTTPS still works. Use
            // DNS-over-HTTPS as a second resolver path, bootstrapped with fixed resolver IPs so
            // it does not depend on the broken Android hostname resolver.
            try {
                List<InetAddress> recovered = queryDoh(hostname);
                if (!recovered.isEmpty()) {
                    fallbackSuccesses.incrementAndGet();
                    return recovered;
                }
            } catch (IOException ignored) {
                // Fall through to a diagnostic UnknownHostException.
            }

            fallbackFailures.incrementAndGet();
            UnknownHostException combined = new UnknownHostException(
                    "System DNS and direct DNS fallback could not resolve " + hostname);
            combined.initCause(systemError);
            throw combined;
        }

        fallbackFailures.incrementAndGet();
        throw new UnknownHostException("No addresses returned for " + hostname);
    }

    int systemFailureCount() {
        return systemFailures.get();
    }

    int fallbackSuccessCount() {
        return fallbackSuccesses.get();
    }

    int fallbackFailureCount() {
        return fallbackFailures.get();
    }

    @NonNull
    String statusSummary() {
        return "system DNS failures " + systemFailureCount()
                + " · fallback recoveries " + fallbackSuccessCount()
                + " · fallback failures " + fallbackFailureCount();
    }

    @NonNull
    private List<InetAddress> queryA(String hostname, String resolver) throws IOException {
        int id = transaction.incrementAndGet() & 0xFFFF;
        byte[] query = buildQuery(hostname, id);

        DatagramPacket request = new DatagramPacket(
                query,
                query.length,
                new InetSocketAddress(InetAddress.getByName(resolver), DNS_PORT));

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(DNS_TIMEOUT_MS);
            socket.send(request);

            byte[] buffer = new byte[MAX_PACKET];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            return parseAResponse(hostname, buffer, response.getLength(), id);
        } catch (SocketTimeoutException timeout) {
            throw new IOException("DNS timeout via " + resolver, timeout);
        }
    }

    @NonNull
    private static List<InetAddress> queryDoh(String hostname) throws IOException {
        IOException last = null;
        String encoded = java.net.URLEncoder.encode(
                hostname, java.nio.charset.StandardCharsets.UTF_8.name());
        String[] urls = {
                "https://cloudflare-dns.com/dns-query?name=" + encoded + "&type=A",
                "https://dns.google/resolve?name=" + encoded + "&type=A"
        };

        for (String url : urls) {
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build();
            try (Response response = DOH_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    last = new IOException("DoH HTTP " + response.code());
                    continue;
                }
                ResponseBody body = response.body();
                if (body == null) continue;

                JSONObject root = new JSONObject(body.string());
                JSONArray answers = root.optJSONArray("Answer");
                if (answers == null) continue;

                List<InetAddress> result = new ArrayList<>();
                for (int i = 0; i < answers.length(); i++) {
                    JSONObject answer = answers.optJSONObject(i);
                    if (answer == null || answer.optInt("type", -1) != 1) continue;
                    String address = answer.optString("data", "");
                    if (address.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
                        result.add(InetAddress.getByAddress(
                                hostname, ipv4Bytes(address)));
                    }
                }
                if (!result.isEmpty()) return result;
            } catch (Exception e) {
                last = e instanceof IOException
                        ? (IOException) e : new IOException("DoH parse failure", e);
            }
        }

        if (last != null) throw last;
        return Collections.emptyList();
    }

    private static byte[] ipv4Bytes(String address) throws IOException {
        String[] parts = address.split("\\.");
        if (parts.length != 4) throw new IOException("Invalid IPv4 address");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value;
            try {
                value = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid IPv4 address", e);
            }
            if (value < 0 || value > 255) throw new IOException("Invalid IPv4 address");
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] buildQuery(String hostname, int id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(bytes);

        out.writeShort(id);
        out.writeShort(0x0100); // recursion desired
        out.writeShort(1);      // QDCOUNT
        out.writeShort(0);      // ANCOUNT
        out.writeShort(0);      // NSCOUNT
        out.writeShort(0);      // ARCOUNT

        String normalized = hostname.toLowerCase(Locale.ROOT);
        for (String label : normalized.split("\\.")) {
            byte[] encoded = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (encoded.length == 0 || encoded.length > 63) {
                throw new IOException("Invalid DNS label");
            }
            out.writeByte(encoded.length);
            out.write(encoded);
        }
        out.writeByte(0);
        out.writeShort(1); // A
        out.writeShort(1); // IN
        out.flush();
        return bytes.toByteArray();
    }

    @NonNull
    static List<InetAddress> parseAResponseForTest(
            String hostname, byte[] packet, int length, int expectedId) throws IOException {
        return parseAResponse(hostname, packet, length, expectedId);
    }

    @NonNull
    private static List<InetAddress> parseAResponse(
            String hostname, byte[] packet, int length, int expectedId) throws IOException {
        if (packet == null || length < 12) throw new IOException("Short DNS response");

        int id = u16(packet, 0);
        if (id != expectedId) throw new IOException("DNS transaction mismatch");

        int flags = u16(packet, 2);
        if ((flags & 0x8000) == 0) throw new IOException("Not a DNS response");
        int rcode = flags & 0x000F;
        if (rcode != 0) throw new IOException("DNS rcode " + rcode);

        int questions = u16(packet, 4);
        int answers = u16(packet, 6);
        int offset = 12;

        for (int i = 0; i < questions; i++) {
            offset = skipName(packet, length, offset);
            if (offset + 4 > length) throw new IOException("Malformed DNS question");
            offset += 4;
        }

        List<InetAddress> addresses = new ArrayList<>();
        for (int i = 0; i < answers; i++) {
            offset = skipName(packet, length, offset);
            if (offset + 10 > length) throw new IOException("Malformed DNS answer");

            int type = u16(packet, offset);
            int clazz = u16(packet, offset + 2);
            int rdLength = u16(packet, offset + 8);
            offset += 10;
            if (offset + rdLength > length) throw new IOException("Malformed DNS RDATA");

            if (type == 1 && clazz == 1 && rdLength == 4) {
                byte[] address = new byte[4];
                System.arraycopy(packet, offset, address, 0, 4);
                addresses.add(InetAddress.getByAddress(hostname, address));
            }
            offset += rdLength;
        }

        return addresses.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(addresses);
    }

    private static int skipName(byte[] packet, int length, int offset) throws IOException {
        int guard = 0;
        while (offset < length && guard++ < 128) {
            int value = packet[offset] & 0xFF;
            if (value == 0) return offset + 1;
            if ((value & 0xC0) == 0xC0) {
                if (offset + 1 >= length) throw new IOException("Broken DNS pointer");
                return offset + 2;
            }
            if (value > 63 || offset + 1 + value > length) {
                throw new IOException("Broken DNS name");
            }
            offset += 1 + value;
        }
        throw new IOException("Unterminated DNS name");
    }

    private static int u16(byte[] packet, int offset) {
        return ((packet[offset] & 0xFF) << 8) | (packet[offset + 1] & 0xFF);
    }
}
