package fr.neamar.kiss.searcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AppSourceMetadataUpdaterTest {
    @Test
    void parsesCurrentPlayDs5LocalizedDescription() {
        String description =
                "Scan QR codes, barcodes, EAN and UPC product codes quickly and securely.";

        StringBuilder detail = new StringBuilder("[");
        for (int i = 0; i <= 12; i++) {
            if (i > 0) detail.append(',');
            if (i == 0) {
                detail.append("[\"Barcode Utility\"]");
            } else if (i == 12) {
                detail.append("[[[null,\"").append(description).append("\"]]]");
            } else {
                detail.append("null");
            }
        }
        detail.append(']');

        String data = "[null,[null,null," + detail + "]]";
        String html = "<html><script nonce=\"abc\">"
                + "AF_initDataCallback({key:'ds:5', data:" + data + ", sideChannel:{}});"
                + "</script></html>";

        assertEquals(description, AppSourceMetadataUpdater.parsePlayDescriptionForTest(html));
    }

    @Test
    void ignoresUnrelatedPlayCallbacks() {
        String html = "<script>AF_initDataCallback({key:'ds:3',data:[1,2,3]});</script>";
        assertNull(AppSourceMetadataUpdater.parsePlayDescriptionForTest(html));
    }
}
