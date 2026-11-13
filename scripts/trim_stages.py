#!/usr/bin/env python3
"""Cuts a client GameTest recording down to its filmed stages, each clip opening on its own caption.

usage: trim_stages.py LOG VIDEO.mp4 OUT.mp4 META.json [--clock]

The test logs `STAGE_START <epoch ms> <id>` and `STAGE_END <epoch ms> <id>` around everything that is meant to be on
screen; the join, the dimension change, chunk loading and the off-camera rehearsal sit between the markers and are cut.
Stages that touch or overlap are kept as one continuous clip, so nothing is repeated or dropped between them.

The video starts at the recorder's selected start, which the metadata records as
`boundaries.selectedStartEpochSeconds`; a stage whose id repeats is paired with the first end that follows its start.
A clip ends TRAIL_SECONDS after its last STAGE_END.

Where a clip starts comes from the picture, not from the log's clock: a marker's time lands on the video only roughly
(up to 0.5 s off on a screen-grab take), and a cut by it can open on the previous stage's caption. The caption panel
(top right, see CAPTION_CROP) is compared by its residual, its pixels with each row's mean brightness removed, so the
world behind the translucent panel counts for little. A frame shows the stage's caption when it is far closer to that
caption than the caption changes by, measured against the caption itself, the median of five panels 0.5 to 0.9 s
after the STAGE_START: the encoder's noise on the first frames of a caption then no longer decides the cut. The clip
opens on the first frame at or after the clock's start, 0.10 s before the STAGE_START, that shows its caption and
holds still for the next 0.2 s, since a caption can be drawn in two steps. It never opens earlier, so settle frames a
test keeps off film stay out, except that a clock start still showing the previous stage's caption moves the cut on to
where the new one appears. After encoding, the first frame of every clip in OUT.mp4 must match its own caption, and
match it better than the previous clip's; the run fails otherwise.

--clock cuts by the log's clock instead, 0.10 s before each STAGE_START, for a recording without captions.

Beside OUT.mp4 it writes OUT.stages.json, the first and end (exclusive) frame of each clip in the trimmed video.
"""
import argparse
import bisect
import json
import re
import subprocess
import sys
from pathlib import Path

LEAD_SECONDS = 0.10
TRAIL_SECONDS = 0.12
# The caption panel's title rows at the recording standard: ClientGameTestRecordingHud draws the panel in the top-right
# corner, 5 GUI pixels from the edges, and ClientGameTestOptions records at 1920x1080 with GUI scale 3. The region
# and the thresholds below were measured on such takes (trim_captions.py, Telekinesis, 12 Nov 2026).
VIDEO_SIZE = (1920, 1080)
CAPTION_CROP = (760, 110, 1140, 0)
# Frame counts are given at 30 frames a second and scale with the capture's rate.
SEARCH_AFTER = 15
REFERENCE_OFFSETS = (15, 18, 21, 24, 27)
SETTLE = 6
CHANGED = 1.5  # the largest frame-to-frame change near a marker when its caption is drawn
CLOSER = 0.25  # of the caption change near the marker: a frame this close to its own caption shows it
STILL = 0.6  # frame-to-frame change of a panel whose caption has settled
OWN_CAPTION = 4.0  # the encoded clip's first frame against its caption, allowing for the second encode


def arguments(argv):
    parser = argparse.ArgumentParser(description="Cut a recording down to its filmed stages.")
    parser.add_argument("log")
    parser.add_argument("video")
    parser.add_argument("out")
    parser.add_argument("meta")
    parser.add_argument("--clock", action="store_true", help="cut by the log's clock instead of the captions")
    return parser.parse_args(argv)


def markers(log):
    starts = [(int(m.group(1)), m.group(2)) for m in re.finditer(r"STAGE_START (\d+) (\S+)", log)]
    ends = [(int(m.group(1)), m.group(2)) for m in re.finditer(r"STAGE_END (\d+) (\S+)", log)]
    return starts, ends


def stage_windows(starts, ends):
    """Each stage's [start ms, end ms, id], in start order; a start is paired with the first unused end after it."""
    if not starts:
        raise SystemExit("no STAGE_START markers in the log")
    used = [False] * len(ends)
    unmatched = []
    windows = []
    for start_ms, stage in starts:
        finish_ms = None
        for index, (end_ms, end_stage) in enumerate(ends):
            if not used[index] and end_stage == stage and end_ms >= start_ms:
                used[index] = True
                finish_ms = end_ms
                break
        if finish_ms is None:
            unmatched.append(stage)
            continue
        windows.append([start_ms, finish_ms, stage])
    if unmatched:
        raise SystemExit("STAGE_START without a following STAGE_END: " + ", ".join(unmatched))
    return sorted(windows)


def frame_at(times, seconds):
    """The first frame shown at or after `seconds` into the video, from the frames' own timestamps."""
    return bisect.bisect_left(times, seconds - 0.0005)


def clips(windows, times, video_start_ms, rate):
    """[begin frame, end frame (exclusive), first marker frame, ids] for each clip, by the log's clock.

    A clip begins LEAD_SECONDS before its first stage starts and ends TRAIL_SECONDS after its last one ends; stages
    whose windows touch are joined into one clip."""
    trail = round(TRAIL_SECONDS * rate)
    merged = []
    for start_ms, finish_ms, stage in windows:
        start = (start_ms - video_start_ms) / 1000.0
        marker = frame_at(times, start)
        begin = frame_at(times, max(0.0, start - LEAD_SECONDS))
        finish = min(len(times), frame_at(times, (finish_ms - video_start_ms) / 1000.0) + trail)
        if merged and begin <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], finish)
            merged[-1][3] += "+" + stage
        else:
            merged.append([begin, finish, marker, stage])
    for begin, finish, _, ids in merged:
        if finish <= begin:
            raise SystemExit(f"{ids}: empty clip {begin}..{finish}")
    return merged


def at_rate(frames_at_30, rate):
    return round(frames_at_30 * rate / 30.0)


def residual(panel, width):
    """The panel with each row's mean brightness moved to 128, so the world behind the panel counts for little."""
    rows = []
    for y in range(len(panel) // width):
        row = panel[y * width:(y + 1) * width]
        shift = 128 - sum(row) / width
        rows.append(row.translate(bytes(int(max(0, min(255, value + shift))) for value in range(256))))
    return b"".join(rows)


def mean_diff(a, b):
    return sum(abs(x - y) for x, y in zip(a, b)) / len(a)


def caption_start(panels, marker, lowest, highest, finish, rate, stage, clock, previous=None):
    """The frame a clip opens on and the stage's own caption, from `panels` of the frames around it.

    The clip never opens before `clock`, the frame the log's clock puts 0.10 s before the marker; it moves forward to
    where the stage's caption appears only when `clock` does not show that caption yet, which covers a clock start
    still on the previous clip's caption (`previous`), so settle frames a test keeps off film stay out. Without a
    `previous` caption, a frame shows its own when it is within CLOSER of the caption change near the marker of it,
    which the encoder's noise on a caption's first frames fits under; with one, it must also be closer to its own
    caption than to that one.

    `panels` maps a frame to its residual panel and must hold every frame from lowest - 1 to SETTLE frames past the
    search window, and the REFERENCE_OFFSETS frames after the marker."""
    last = finish - 1
    samples = [panels[min(last, marker + at_rate(offset, rate))] for offset in REFERENCE_OFFSETS]
    reference = bytes(sorted(values)[len(values) // 2] for values in zip(*samples))
    lo = max(lowest, 1)
    hi = min(highest, marker + at_rate(SEARCH_AFTER, rate))
    change = {k: mean_diff(panels[k - 1], panels[k])
              for k in range(lo, hi + at_rate(SETTLE, rate) + 1) if k - 1 in panels and k in panels}
    biggest = max((change.get(k, 0.0) for k in range(lo, hi + 1)), default=0.0)
    if biggest < CHANGED:
        raise SystemExit(f"{stage}: the caption never changes near frame {marker} (largest change {biggest:.1f}); "
                         "a recording without captions is cut with --clock")
    limit = CLOSER * biggest
    for k in range(max(lo, clock), hi + 1):
        to_own = mean_diff(panels[k], reference)
        if to_own >= limit or (previous is not None and to_own >= mean_diff(panels[k], previous)):
            continue
        settled = all(change.get(j, 0.0) < STILL for j in range(k + 1, min(k + at_rate(SETTLE, rate), last + 1)))
        if settled:
            return k, reference
    raise SystemExit(f"{stage}: no frame near frame {marker} shows the stage's caption")


def probe(video):
    """The frame times in seconds from the first frame, the frame size, and whether the video has sound."""
    probed = json.loads(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,width,height,time_base:packet=pts",
         "-select_streams", "v:0", "-of", "json", str(video)],
        check=True, capture_output=True, text=True).stdout)
    stream = probed["streams"][0]
    numerator, denominator = (int(x) for x in stream["time_base"].split("/"))
    pts = sorted(int(packet["pts"]) for packet in probed["packets"])
    times = [(p - pts[0]) * numerator / denominator for p in pts]
    audio = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0",
         str(video)], check=True, capture_output=True, text=True).stdout.strip()
    return times, (int(stream["width"]), int(stream["height"])), bool(audio)


def read_panels(video, wanted):
    """The residual caption panel of each frame in `wanted`, read in one pass over the video."""
    width, height, x, y = CAPTION_CROP
    size = width * height
    panels = {}
    last = max(wanted)
    command = ["ffmpeg", "-v", "error", "-i", str(video), "-vf", f"crop={width}:{height}:{x}:{y},format=gray",
               "-fps_mode", "passthrough", "-frames:v", str(last + 1), "-f", "rawvideo", "-"]
    with subprocess.Popen(command, stdout=subprocess.PIPE) as ffmpeg:
        frame = 0
        while len(data := ffmpeg.stdout.read(size)) == size:
            if frame in wanted:
                panels[frame] = residual(data, width)
            frame += 1
    if ffmpeg.returncode != 0:
        raise SystemExit(f"ffmpeg could not read {video}")
    missing = sorted(set(wanted) - set(panels))
    if missing:
        raise SystemExit(f"could not read the caption panel of frames {missing[:5]} of {video}")
    return panels


def caption_cuts(video, merged, rate):
    """Moves each clip's start to the first frame from its clock start that shows its own caption; returns each
    clip's caption."""
    wanted = set()
    previous_end = 0
    for _, finish, marker, _ in merged:
        hi = min(finish - 1, marker + at_rate(SEARCH_AFTER, rate) + at_rate(SETTLE, rate))
        wanted.update(range(max(0, previous_end - 1), hi + 1))
        wanted.update(min(finish - 1, marker + at_rate(offset, rate)) for offset in REFERENCE_OFFSETS)
        previous_end = finish
    panels = read_panels(video, wanted)
    references = []
    previous_end = 0
    for clip in merged:
        begin, finish, marker, ids = clip
        stage = ids.split("+")[0]
        clip[0], reference = caption_start(panels, marker, previous_end, finish - 1, finish, rate, stage, begin,
                                           references[-1] if references else None)
        print(f"{stage}: marker frame {marker}, caption from frame {clip[0]} ({(marker - clip[0]) / rate:+.2f} s)")
        references.append(reference)
        previous_end = finish
    return references


def check_first_frames(out_path, stages, references):
    """Fails unless every clip of the cut opens on its own caption rather than the previous clip's.

    The first clip has no previous caption to compare with, so it keeps the fixed OWN_CAPTION allowance for the
    second encode; every later clip must be closer to its own caption than to the previous one, because how far the
    encoded first frame lands from its caption depends on the host's encoder: the same take measured 0.1 to 4.2
    while the caption change stayed near 27."""
    panels = read_panels(out_path, {stage["startFrame"] for stage in stages})
    previous = None
    for stage, reference in zip(stages, references):
        first = panels[stage["startFrame"]]
        own = mean_diff(first, reference)
        if previous is None:
            if own >= OWN_CAPTION:
                raise SystemExit(f"{stage['stage']}: the clip's first frame does not show its own caption ({own:.1f})")
        elif own >= mean_diff(first, previous):
            raise SystemExit(f"{stage['stage']}: the clip's first frame still shows the previous caption "
                             f"({own:.1f} to its own, {mean_diff(first, previous):.1f} to the previous)")
        previous = reference


def frame_count(video):
    return int(subprocess.run([
        "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(video),
    ], check=True, capture_output=True, text=True).stdout.strip())


def encode(video, out_path, merged, times, rate, sound):
    """Joins the clips by frame index and gives every kept frame exactly 1/rate, the audio cut to match."""
    select = "+".join(f"between(n,{begin},{finish - 1})" for begin, finish, *_ in merged)
    graph = [f"[0:v]select='{select}',settb=1/{rate:g},setpts=N[vout]"]
    maps = ["-map", "[vout]"]
    if sound:
        pieces = []
        for index, (begin, finish, *_) in enumerate(merged):
            start = times[begin]
            graph.append(f"[0:a]atrim=start={start:.6f}:end={start + (finish - begin) / rate:.6f},"
                         f"asetpts=PTS-STARTPTS[a{index}]")
            pieces.append(f"[a{index}]")
        graph.append("".join(pieces) + f"concat=n={len(merged)}:v=0:a=1[aout]")
        maps += ["-map", "[aout]", "-c:a", "aac", "-b:a", "128k"]
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-i", str(video), "-filter_complex", ";".join(graph), *maps,
        "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
        "-fps_mode", "passthrough", str(out_path),
    ], check=True)


def main(argv):
    args = arguments(argv)
    video, out_path = Path(args.video), Path(args.out)
    meta = json.loads(Path(args.meta).read_text())
    raw_capture = meta.get("rawCapture")
    if raw_capture and Path(raw_capture).name == video.name:
        raise SystemExit("cut the recorder's exported video, not the raw capture, which starts before the selected start")
    windows = stage_windows(*markers(Path(args.log).read_text(errors="replace")))
    video_start_ms = float(meta["boundaries"]["selectedStartEpochSeconds"]) * 1000.0
    rate = float(meta.get("fps") or 30)
    times, size, sound = probe(video)
    merged = clips(windows, times, video_start_ms, rate)
    references = None
    if not args.clock:
        if size != VIDEO_SIZE:
            raise SystemExit(f"the video is {size[0]}x{size[1]}, but the caption panel is only measured at the "
                             f"{VIDEO_SIZE[0]}x{VIDEO_SIZE[1]} recording standard; cut it with --clock")
        references = caption_cuts(video, merged, rate)
    for index, (begin, finish, _, ids) in enumerate(merged):
        print(f"clip {index}: frames {begin} - {finish} ({(finish - begin) / rate:.2f}s) {ids}")
    encode(video, out_path, merged, times, rate, sound)

    stages = []
    frame = 0
    for begin, finish, _, ids in merged:
        stages.append({"stage": ids, "startFrame": frame, "endFrameExclusive": frame + finish - begin})
        frame += finish - begin
    joined = frame_count(out_path)
    if joined != frame:
        raise SystemExit(f"the joined video has {joined} frames but its clips add up to {frame}; stage frames would be wrong")
    if references is not None:
        check_first_frames(out_path, stages, references)
    out_path.with_suffix(".stages.json").write_text(json.dumps(stages, indent=2) + "\n")
    print("wrote", out_path)


if __name__ == "__main__":
    main(sys.argv[1:])
