package fr.neamar.kiss.searcher;

import androidx.fragment.app.DialogFragment;

import fr.neamar.kiss.ui.ListPopup;

public interface QueryInterface {
    void temporarilyDisableTranscriptMode();

    void updateTranscriptMode(int transcriptMode);

    void beforeListChange();

    void afterListChange();

    void launchOccurred();

    /** An external target is about to start; freeze launcher-only background UI mutation first. */
    void externalResultLaunchStarting();

    /** A verified external target was started and may cover the launcher without onStop(). */
    void externalResultLaunchOccurred();

    /** The attempted external target did not start; launcher UI may accept background refreshes again. */
    void externalResultLaunchCancelled();

    void registerPopup(ListPopup popup);

    void showDialog(DialogFragment dialog);
}
