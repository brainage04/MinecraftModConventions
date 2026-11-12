package io.github.brainage04.minecraftmodconventions.gradle.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the frames x11grab tears on a display that has the graphics card behind it, and builds the filter
 * that replaces them.
 *
 * <p>x11grab copies the screen from the top down while the client keeps presenting. The NVIDIA driver does
 * not make the X server's copy wait for a present ("Implicit sync not fully supported" in the display's
 * Xorg log), so a present that lands during the copy leaves the older image above the tear and the newer one
 * below it, with nothing of its own. ffmpeg reduces every frame to grey {@value #WIDTH}x{@value #ROWS} and
 * marks, in frame i, each pixel that equals frame i-1 (under {@value #SAME} levels apart) while differing
 * from frame i+1 by over {@value #CHANGED} levels, each pixel that does the opposite, and each pixel that
 * differs from both by over {@value #CHANGED} levels: the frame's own content. A row shows the previous frame
 * when at least {@value #MIN_PIXELS} of its pixels are marked and {@value #RATIO} times more of them match
 * the previous frame than the next, and the next frame the other way round. A frame is torn when those rows
 * split into a band above that shows the previous frame and a band below that shows the next, each holding
 * at least {@value #MIN_ROWS} rows and agreeing in at least {@value #AGREE} of them, and at most
 * {@value #OWN_SHARE} of its marked pixels are its own. A fresh image between its neighbours has content of
 * its own wherever something moves, so an object moving up the screen, which leaves the same two bands,
 * does not count.
 *
 * <p>A torn frame holds nothing its neighbours do not, so the recording replaces it with the frame before it.
 * {@code scripts/check_tears.py} applies the same test to any finished video.
 */
final class TornFrames {
    static final int WIDTH = 480;
    static final int ROWS = 270;
    static final int SAME = 6;
    static final int CHANGED = 20;
    static final int MIN_PIXELS = 24;
    static final int RATIO = 4;
    static final int MIN_ROWS = 8;
    static final double AGREE = 0.85;
    static final double OWN_SHARE = 0.02;
    static final int PREVIOUS = 255;
    static final int NEXT = 128;
    static final int OWN = 64;

    private static final byte LABEL_NONE = 0;
    private static final byte LABEL_PREVIOUS = 1;
    private static final byte LABEL_NEXT = 2;
    private static final byte LABEL_MIXED = 3;

    private TornFrames() {
    }

    /**
     * The ffmpeg command that writes one grey mask per frame of {@code video} but its first. The last frame's mask
     * compares it with the frame before on both sides, so it holds no previous or next pixel and is never torn.
     */
    static List<String> maskCommand(String video) {
        String shift = "trim=start_frame=%d,setpts=PTS-STARTPTS";
        // blend pairs its two inputs' frames by timestamp, so every frame gets its number as an exact timestamp.
        // setpts=N/(30*TB) is inexact in any time base where 1/30 s is not a whole number of ticks, such as a raw
        // capture's 1/1000 or a saved video's 1/16000: there it paired 928 of carpetsound3's 3497 raw frames with
        // the wrong neighbour, and the check missed the tear in its frame 1655.
        String graph = "[0:v]format=gray,scale=" + WIDTH + ":" + ROWS + ":flags=area,settb=1/30,setpts=N,split=3[f0][f1][f2];"
                + "[f1]" + shift.formatted(1) + ",split[m1][m2];[f2]" + shift.formatted(2) + "[n];"
                + "[m1][f0]blend=all_mode=difference:shortest=1[to_previous];"
                + "[m2][n]blend=all_mode=difference:shortest=1[to_next];"
                + "[to_previous][to_next]lut2=c0='if(lt(x," + SAME + ")*gt(y," + CHANGED + ")," + PREVIOUS + ","
                + "if(lt(y," + SAME + ")*gt(x," + CHANGED + ")," + NEXT + ","
                + "if(gt(x," + CHANGED + ")*gt(y," + CHANGED + ")," + OWN + ",0)))'";
        return List.of("ffmpeg", "-v", "error", "-i", video, "-filter_complex", graph, "-fps_mode", "passthrough",
                "-f", "rawvideo", "-pix_fmt", "gray", "-");
    }

    /** The torn frames, by frame number in the video, from the masks {@link #maskCommand} writes. */
    static List<Integer> find(InputStream masks) throws IOException {
        List<Integer> torn = new ArrayList<>();
        int frame = 1;
        byte[] mask;
        while ((mask = masks.readNBytes(WIDTH * ROWS)).length == WIDTH * ROWS) {
            if (tearRow(mask) >= 0) {
                torn.add(frame);
            }
            frame++;
        }
        return torn;
    }

    /**
     * The first row of the band that shows the next frame, at the split where the most rows agree with their
     * band, or -1 when the mask's frame is not torn.
     */
    static int tearRow(byte[] mask) {
        int own = 0;
        int marked = 0;
        int[] rows = new int[ROWS];
        byte[] labels = new byte[ROWS];
        int labelled = 0;
        for (int y = 0; y < ROWS; y++) {
            int previous = 0;
            int next = 0;
            for (int x = 0; x < WIDTH; x++) {
                int value = mask[y * WIDTH + x] & 0xFF;
                if (value == PREVIOUS) {
                    previous++;
                } else if (value == NEXT) {
                    next++;
                } else if (value == OWN) {
                    own++;
                }
            }
            marked += previous + next;
            byte label = label(previous, next);
            if (label != LABEL_NONE) {
                rows[labelled] = y;
                labels[labelled] = label;
                labelled++;
            }
        }
        marked += own;
        if (own > OWN_SHARE * marked) {
            return -1;
        }
        int[] previousAbove = new int[labelled + 1];
        int[] nextAbove = new int[labelled + 1];
        for (int i = 0; i < labelled; i++) {
            previousAbove[i + 1] = previousAbove[i] + (labels[i] == LABEL_PREVIOUS ? 1 : 0);
            nextAbove[i + 1] = nextAbove[i] + (labels[i] == LABEL_NEXT ? 1 : 0);
        }
        int best = -1;
        int bestAgreeing = -1;
        for (int split = MIN_ROWS; split <= labelled - MIN_ROWS; split++) {
            int below = labelled - split;
            int nextBelow = nextAbove[labelled] - nextAbove[split];
            if (previousAbove[split] >= AGREE * split && nextBelow >= AGREE * below
                    && previousAbove[split] + nextBelow > bestAgreeing) {
                best = rows[split];
                bestAgreeing = previousAbove[split] + nextBelow;
            }
        }
        return best;
    }

    private static byte label(int previous, int next) {
        if (previous + next < MIN_PIXELS) {
            return LABEL_NONE;
        }
        if (previous >= RATIO * next) {
            return LABEL_PREVIOUS;
        }
        if (next >= RATIO * previous) {
            return LABEL_NEXT;
        }
        return LABEL_MIXED;
    }

    /**
     * The filter that follows a video stream labelled {@code input} and replaces each run of torn frames
     * (frame numbers of that stream, ascending) with the frame before the run, or after it for a run that
     * opens the stream, ending in a stream labelled {@code output}. With no torn frames it passes the stream on.
     */
    static String repairFilter(String input, String output, List<Integer> torn) {
        List<int[]> runs = new ArrayList<>();
        for (int frame : torn) {
            if (!runs.isEmpty() && runs.getLast()[1] == frame - 1) {
                runs.getLast()[1] = frame;
            } else {
                runs.add(new int[] {frame, frame});
            }
        }
        if (runs.isEmpty()) {
            return "[" + input + "]null[" + output + "]";
        }
        StringBuilder filter = new StringBuilder("[" + input + "]split=" + (runs.size() + 1) + "[repair0]");
        for (int i = 1; i <= runs.size(); i++) {
            filter.append("[source").append(i).append(']');
        }
        for (int i = 0; i < runs.size(); i++) {
            int[] run = runs.get(i);
            int replacement = run[0] > 0 ? run[0] - 1 : run[1] + 1;
            filter.append(";[repair").append(i).append("][source").append(i + 1).append("]freezeframes=first=")
                    .append(run[0]).append(":last=").append(run[1]).append(":replace=").append(replacement)
                    .append('[').append(i == runs.size() - 1 ? output : "repair" + (i + 1)).append(']');
        }
        return filter.toString();
    }
}
