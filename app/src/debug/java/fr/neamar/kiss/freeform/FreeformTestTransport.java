package fr.neamar.kiss.freeform;

/** Instrumentation-only transport override; this class is never included in a production APK. */
final class FreeformTestTransport {
    private FreeformTestTransport() {}
    private static volatile NativeTaskClient.Transport transport;
    static NativeTaskClient.Transport get() { return transport; }
    static void install(NativeTaskClient.Transport value) { transport = value; }
}
