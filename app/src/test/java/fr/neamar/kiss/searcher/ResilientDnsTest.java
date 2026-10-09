package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

class ResilientDnsTest {
    @Test
    void parsesARecordWhilePreservingOriginalHostname() throws Exception {
        int id = 0x1234;
        byte[] response = responseFor("play.google.com", id, new byte[]{1, 2, 3, 4});

        List<InetAddress> addresses = ResilientDns.parseAResponseForTest(
                "play.google.com", response, response.length, id);

        assertEquals(1, addresses.size());
        assertEquals("1.2.3.4", addresses.get(0).getHostAddress());
        assertEquals("play.google.com", addresses.get(0).getHostName());
    }

    private static byte[] responseFor(String hostname, int id, byte[] address) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        out.writeShort(id);
        out.writeShort(0x8180);
        out.writeShort(1);
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(0);

        for (String label : hostname.split("\\.")) {
            byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
            out.writeByte(encoded.length);
            out.write(encoded);
        }
        out.writeByte(0);
        out.writeShort(1);
        out.writeShort(1);

        out.writeShort(0xC00C);
        out.writeShort(1);
        out.writeShort(1);
        out.writeInt(60);
        out.writeShort(4);
        out.write(address);

        out.flush();
        return bytes.toByteArray();
    }
}
