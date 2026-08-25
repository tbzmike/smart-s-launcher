package fr.neamar.kiss.searcher;

import androidx.fragment.app.DialogFragment;

import fr.neamar.kiss.ui.ListPopup;

public interface QueryInterface {
    void temporarilyDisableTranscriptMode();

    void updateTranscriptMode(int transcriptMode);

    void beforeListChange();

    void afterListChange();

    void launchOccurred();

    /** A verified app/shortcut target was started and may cover the launcher without onStop(). */
    void externalResultLaunchOccurred();

    void registerPopup(ListPopup popup);

    void showDialog(DialogFragment dialog);
}
