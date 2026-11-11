package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.Objects;

/**
 * The wall-clock stage markers a recorded client GameTest prints around what belongs in the video.
 *
 * <p>{@code scripts/trim_stages.py} cuts a recording to the stages between a {@code STAGE_START} and
 * its {@code STAGE_END}, so the line is a contract with that script: the marker name, the epoch time
 * in milliseconds and one non-blank stage id, separated by single spaces.
 */
public final class ClientGameTestStages {
    static final String START_MARKER = "STAGE_START";
    static final String END_MARKER = "STAGE_END";

    private ClientGameTestStages() {
    }

    /** Starts a filmed stage. */
    public static void markStage(String id) {
        System.out.println(markerLine(START_MARKER, System.currentTimeMillis(), id));
    }

    /** Ends a filmed stage. */
    public static void endStage(String id) {
        System.out.println(markerLine(END_MARKER, System.currentTimeMillis(), id));
    }

    /**
     * Ends a filmed stage and keeps the scene as it is for a moment: a cut keeps a little picture
     * after the end marker, and that picture must still show this stage.
     */
    public static void endStage(ClientGameTestContext context, String id, int holdAfterTicks) {
        Objects.requireNonNull(context, "context");
        if (holdAfterTicks < 0) {
            throw new IllegalArgumentException("holdAfterTicks must not be negative: " + holdAfterTicks);
        }
        endStage(id);
        context.waitTicks(holdAfterTicks);
    }

    static String markerLine(String marker, long millis, String id) {
        Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(id, "id");
        String stage = id.strip();
        if (stage.isEmpty() || stage.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "A stage id is one non-blank token, because the trim scripts split the marker line on spaces: \""
                            + id + "\"");
        }
        return marker + " " + millis + " " + stage;
    }
}
