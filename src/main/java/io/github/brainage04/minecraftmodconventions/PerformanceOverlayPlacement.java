package io.github.brainage04.minecraftmodconventions;

/**
 * Where the recording's performance overlay sits; a test picks one with
 * {@link ClientGameTestRecorder#placePerformanceOverlay}.
 */
public enum PerformanceOverlayPlacement {
    /** In the top-right corner, right under the caption panel. The default. */
    TOP_RIGHT,
    TOP_LEFT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    /** Not drawn. The recording still keeps the overlay's numbers in its metadata. */
    HIDDEN
}
