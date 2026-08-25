package fr.neamar.kiss.forwarder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryIconPolicyTest {
    @Test
    void coldTransparentAdapterPlaceholderIsNotRenderable() {
        assertFalse(HistoryIconPolicy.isRenderable(true, 255, true));
    }

    @Test
    void fullyTransparentDrawableIsNotRenderable() {
        assertFalse(HistoryIconPolicy.isRenderable(true, 0, false));
    }

    @Test
    void missingDrawableIsNotRenderable() {
        assertFalse(HistoryIconPolicy.isRenderable(false, 255, false));
    }

    @Test
    void loadedDrawableIsRenderable() {
        assertTrue(HistoryIconPolicy.isRenderable(true, 255, false));
    }
}
