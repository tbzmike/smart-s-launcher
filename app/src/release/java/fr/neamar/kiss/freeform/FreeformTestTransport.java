package fr.neamar.kiss.freeform;

/** No instrumentation transport injection exists in the production source set. */
final class FreeformTestTransport {
    private FreeformTestTransport() {}
    static NativeTaskClient.Transport get() { return null; }
}
