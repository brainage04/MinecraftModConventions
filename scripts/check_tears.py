#!/usr/bin/env python3
"""Finds torn frames in a recording: frames whose upper rows show the frame before and whose lower
rows show the frame after.

usage: check_tears.py VIDEO.mp4 [--repair OUT.mp4]

x11grab copies the screen from the top down, so a frame the game presents while the copy runs leaves
the older image above the tear and the newer one below it, and nothing of its own. ffmpeg reduces every
frame to grey 480x270 and marks, in frame i, each pixel that equals frame i-1 (differs by under SAME
levels) while differing from frame i+1 by over CHANGED levels, each pixel that does the opposite, and
each pixel that differs from both by over CHANGED levels: the frame's own content. A row shows the
previous frame when at least MIN_PIXELS of its pixels are marked and RATIO times more of them match the
previous frame than the next, and the next frame the other way round. A frame is torn when those rows
split into a band above that shows the previous frame and a band below that shows the next, each holding
at least MIN_ROWS rows and agreeing in at least AGREE of them, and at most OWN_SHARE of its marked pixels
are its own. A fresh image between its neighbours has content of its own wherever something moves, so an
object that moves up the screen, which leaves the same two bands, does not count. Exits 1 when any frame
is torn, and prints each one with the row of the tear in the full-size picture.

--repair writes OUT.mp4 with each run of torn frames replaced by the frame before it (after it for a run
that opens the video), as the client GameTest recorder does: a torn frame holds only parts of its two
neighbours. The video is encoded again (libx264, CRF 18), the audio is copied, and OUT.mp4 is checked
in turn; the exit code is that check's. Any other argument exits 2 with the usage.
"""
import argparse
import json
import subprocess
import sys

WIDTH = 480
ROWS = 270
SAME = 6
CHANGED = 20
MIN_PIXELS = 24
RATIO = 4
MIN_ROWS = 8
AGREE = 0.85
OWN_SHARE = 0.02
PREVIOUS = 255
NEXT = 128
OWN = 64


def row_labels(video):
    """For every frame but the first and last: one label per row (P, N, - for mixed, None for still), the
    count of its own pixels and the count of all marked pixels."""
    shift = "trim=start_frame={0},setpts=PTS-STARTPTS"
    graph = (f"[0:v]format=gray,scale={WIDTH}:{ROWS}:flags=area,settb=1/30,setpts=N,split=3[f0][f1][f2];"
             f"[f1]{shift.format(1)},split[m1][m2];[f2]{shift.format(2)}[n];"
             f"[m1][f0]blend=all_mode=difference:shortest=1[to_previous];"
             f"[m2][n]blend=all_mode=difference:shortest=1[to_next];"
             f"[to_previous][to_next]lut2=c0='if(lt(x,{SAME})*gt(y,{CHANGED}),{PREVIOUS},"
             f"if(lt(y,{SAME})*gt(x,{CHANGED}),{NEXT},if(gt(x,{CHANGED})*gt(y,{CHANGED}),{OWN},0)))'")
    command = ["ffmpeg", "-v", "error", "-i", video, "-filter_complex", graph, "-fps_mode", "passthrough",
               "-f", "rawvideo", "-pix_fmt", "gray", "-"]
    size = WIDTH * ROWS
    frames = []
    with subprocess.Popen(command, stdout=subprocess.PIPE) as ffmpeg:
        while len(mask := ffmpeg.stdout.read(size)) == size:
            labels = [label(mask[y * WIDTH:(y + 1) * WIDTH]) for y in range(ROWS)]
            own = mask.count(OWN)
            frames.append((labels, own, own + mask.count(PREVIOUS) + mask.count(NEXT)))
    if ffmpeg.returncode != 0:
        raise SystemExit(f"ffmpeg could not read {video}")
    return frames


def label(row):
    previous = row.count(PREVIOUS)
    following = row.count(NEXT)
    if previous + following < MIN_PIXELS:
        return None
    if previous >= RATIO * following:
        return "P"
    if following >= RATIO * previous:
        return "N"
    return "-"


def tear_row(labels):
    """The first labelled row of the lower band, at the split where the most rows agree with their band,
    when the labelled rows split into two bands, else None."""
    rows = [(y, value) for y, value in enumerate(labels) if value is not None]
    previous_above, next_above = [0], [0]
    for _, value in rows:
        previous_above.append(previous_above[-1] + (value == "P"))
        next_above.append(next_above[-1] + (value == "N"))
    best, best_agreeing = None, -1
    for split in range(MIN_ROWS, len(rows) - MIN_ROWS + 1):
        next_below = next_above[-1] - next_above[split]
        agreeing = previous_above[split] + next_below
        if (previous_above[split] >= AGREE * split and next_below >= AGREE * (len(rows) - split)
                and agreeing > best_agreeing):
            best, best_agreeing = rows[split][0], agreeing
    return best


def torn_frames(video):
    """(frame, row of the tear out of ROWS) for every torn frame."""
    found = []
    for index, (labels, own, marked) in enumerate(row_labels(video)):
        row = None if own > OWN_SHARE * marked else tear_row(labels)
        if row is not None:
            found.append((index + 1, row))
    return found


def repair_filter(torn):
    """The filter that replaces each run of torn frames with the frame before it (after it at frame 0)."""
    runs = []
    for frame in torn:
        if runs and runs[-1][1] == frame - 1:
            runs[-1][1] = frame
        else:
            runs.append([frame, frame])
    if not runs:
        return "[0:v]null[video]"
    parts = ["[0:v]split=" + str(len(runs) + 1) + "[repair0]" + "".join(f"[source{i}]" for i in range(1, len(runs) + 1))]
    for i, (first, last) in enumerate(runs):
        replacement = first - 1 if first > 0 else last + 1
        target = "video" if i == len(runs) - 1 else f"repair{i + 1}"
        parts.append(f"[repair{i}][source{i + 1}]freezeframes=first={first}:last={last}:replace={replacement}[{target}]")
    return ";".join(parts)


def frame_rate(average, real):
    """The stream's frame rate as (numerator, denominator): ffprobe's avg_frame_rate, or r_frame_rate when the
    average is 0/0, as ffprobe reports it for some streams."""
    for rate in (average, real):
        num, den = (int(x) for x in rate.split("/"))
        if num > 0 and den > 0:
            return num, den
    raise ValueError(f"no frame rate: avg_frame_rate {average}, r_frame_rate {real}")


def report(video):
    stream = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=height,avg_frame_rate,r_frame_rate", "-of", "json", video],
        check=True, capture_output=True, text=True).stdout
    info = json.loads(stream)["streams"][0]
    height = info["height"]
    num, den = frame_rate(info["avg_frame_rate"], info["r_frame_rate"])
    tears = torn_frames(video)
    for frame, row in tears:
        print(f"torn frame {frame} ({frame * den / num:.3f} s), tear near row {row * int(height) // ROWS}")
    print(f"{video}: {len(tears)} torn frame(s)")
    return [frame for frame, _ in tears]


def arguments(argv):
    """VIDEO and an optional --repair OUT; anything else exits with a usage error."""
    parser = argparse.ArgumentParser(description="Finds and repairs torn frames in a recording.")
    parser.add_argument("video")
    parser.add_argument("--repair", metavar="OUT")
    return parser.parse_args(argv)


if __name__ == "__main__":
    args = arguments(sys.argv[1:])
    torn = report(args.video)
    if args.repair:
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", args.video, "-filter_complex", repair_filter(torn),
                        "-map", "[video]", "-map", "0:a?", "-c:v", "libx264", "-preset", "medium", "-crf", "18",
                        "-pix_fmt", "yuv420p", "-fps_mode", "passthrough", "-c:a", "copy", "-movflags", "+faststart",
                        args.repair], check=True)
        torn = report(args.repair)
    sys.exit(1 if torn else 0)
