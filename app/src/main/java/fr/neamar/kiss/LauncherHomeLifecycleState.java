package fr.neamar.kiss;

/**
 * Exact lifecycle state used to classify HOME redelivery without clocks or intent-identity guesses.
 */
final class LauncherHomeLifecycleState {
    private boolean stoppedSinceLastResume = true;
    private boolean externalResultLaunchedSinceResume;

    void onResumeCompleted() {
        stoppedSinceLastResume = false;
        externalResultLaunchedSinceResume = false;
    }

    void onStopped() {
        stoppedSinceLastResume = true;
    }

    void onExternalResultLaunched() {
        externalResultLaunchedSinceResume = true;
    }

    boolean launcherWasForegroundBeforeHomeIntent() {
        return !stoppedSinceLastResume && !externalResultLaunchedSinceResume;
    }
}
