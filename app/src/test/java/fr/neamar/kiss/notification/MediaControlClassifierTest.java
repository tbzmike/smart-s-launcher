package fr.neamar.kiss.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MediaControlClassifierTest {
    @Test void recognizesPreviousVariants() {
        assertEquals(MediaControlClassifier.Kind.PREVIOUS, MediaControlClassifier.classify("Previous"));
        assertEquals(MediaControlClassifier.Kind.PREVIOUS, MediaControlClassifier.classify("Rewind 15 seconds"));
    }

    @Test void recognizesPlayPauseVariants() {
        assertEquals(MediaControlClassifier.Kind.PLAY_PAUSE, MediaControlClassifier.classify("Play"));
        assertEquals(MediaControlClassifier.Kind.PLAY_PAUSE, MediaControlClassifier.classify("Pause"));
        assertEquals(MediaControlClassifier.Kind.PLAY_PAUSE, MediaControlClassifier.classify("Resume playback"));
    }

    @Test void recognizesNextAndRejectsUnrelatedActions() {
        assertEquals(MediaControlClassifier.Kind.NEXT, MediaControlClassifier.classify("Next"));
        assertEquals(MediaControlClassifier.Kind.NEXT, MediaControlClassifier.classify("Skip forward"));
        assertEquals(MediaControlClassifier.Kind.OTHER, MediaControlClassifier.classify("Like"));
        assertEquals(MediaControlClassifier.Kind.OTHER, MediaControlClassifier.classify(null));
    }
}
