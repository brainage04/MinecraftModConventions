#!/usr/bin/env python3
"""Cuts a client GameTest recording down to its filmed stages, each clip opening on its own caption.

usage: trim_stages.py LOG VIDEO.mp4 OUT.mp4 META.json [--clock]

The test logs `STAGE_START <epoch ms> <id>` and `STAGE_END <epoch ms> <id>` around everything that is meant to be on
screen; the join, the dimension change, chunk loading and the off-camera rehearsal sit between the markers and are cut.
Stages that touch or overlap are kept as one continuous clip, so nothing is repeated or dropped between them.

The video starts at the recorder's selected start, which the metadata records as
`boundaries.selectedStartEpochSeconds`; a stage whose id repeats is paired with the first end that follows its start.
A clip ends TRAIL_SECONDS after its last STAGE_END.

Where a clip starts depends on what the take records, and the run prints which source it used:

- A game capture (the default since 3.5.0) records, for every caption, the first captured frame the HUD drew it into
  (`boundaries.gameCaptureCaptionMarks`, read with `boundaries.rawStartFrameIndex`); a caption is on screen from its
  mark to the next one. A stage's caption has the stage's id, or ends in "." and the stage's id. A clip opens at its
  clock start, 0.10 s before its STAGE_START, when the caption on screen there is the stage's, else on the stage's
  first caption drawn after the clock start. The run fails, naming --clock, when there is no such caption before the
  clip ends, or when the stage's id matches more than one caption id.
- A screen grab (x11grab) has no marks, so the caption panel (top right, see CAPTION_CROP) is read from the picture.
  A frame's panel is compared with the frame before it by its residual, its pixels with each row's mean brightness
  removed, so the world behind the translucent panel counts for little. A caption shows up as a step: a change of at
  least CHANGED and STEP times the panel's typical change from frame to frame (its drift). With one step between the
  clock start and 0.5 s after the STAGE_START (a caption drawn in two steps counts as one), the clip opens where it
  ends. With none up to 0.9 s, the caption is taken to be on screen already and the clip opens at the clock start; a
  later clip then needs a step since the previous clip ended, or the clock start may still show that clip's caption.
  A clip whose first stage is the stage its previous clip ended on, a stage repeated with its caption, needs neither
  that step nor any change near its start: the caption still on screen is its own, and after encoding its first frame
  is held to the first clip's rule below. Its panel is watched to the stage's end instead of for 2 s, and a change
  after 0.9 s, which may be a new text under its id, fails naming --clock.
  The panel is watched up to LATE (2 s) after the STAGE_START; a caption that comes later still is not seen. Every
  one of these windows stops at the stage's end, where the next stage of a merged clip may draw its own caption. The
  run fails, naming --clock, when the stage lasts no more than 0.5 s from its STAGE_START, so that its caption may
  only come after it ends, when the panel changes more than once after the clock start, or changes more than 0.5 s
  after the STAGE_START, or drifts so fast that a change as small as NEAREST could hide in it. A camera cut in a
  stage's first 0.5 s, while its caption is already on screen, still opens the clip at the cut, and so does an on-film
  cut about ten times the caption's own change before the caption comes.
  After encoding, every later clip's first frame in OUT.mp4 must be closer to its own caption, read 0.5 to 0.9 s
  after its STAGE_START or last before its stage ends, than to the caption its previous clip ended on, and the first
  clip's may sit at most OWN_CAPTION further from its caption than the source frame did.

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
SEARCH_AFTER = 15  # a caption may still arrive up to here after its marker; the frames after it show the caption
REFERENCE_OFFSETS = (15, 18, 21, 24, 27)
LATE = 60  # the panel is watched up to here after a marker, or to its stage's end: a later caption is not seen
SETTLE = 6  # steps closer together than this are one caption being drawn
CHANGED = 1.5  # the smallest change that counts as a step: encoder noise on a still panel stays below it
STEP = 2.0  # a step is a change this many times the panel's typical change from frame to frame
STILL = 0.1  # of the largest change from the clock start on: smaller changes are a drawn caption's encoder noise
# The smallest caption change the cut must see: two captions that differ by one short word change the panel by about
# 4.8 (CAPTION_B and CAPTION_C_LIKE in the tests). A world drifting so fast that a step must be larger is refused.
NEAREST = 4.0
OWN_CAPTION = 4.0  # how much worse the second encode may leave a clip's first frame than the source frame


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
    """[begin frame, end frame (exclusive), first marker frame, ids, first stage's end frame] for each clip, by the log's
    clock.

    A clip begins LEAD_SECONDS before its first stage starts and ends TRAIL_SECONDS after its last one ends; stages
    whose windows touch are joined into one clip."""
    trail = round(TRAIL_SECONDS * rate)
    merged = []
    for start_ms, finish_ms, stage in windows:
        start = (start_ms - video_start_ms) / 1000.0
        marker = frame_at(times, start)
        begin = frame_at(times, max(0.0, start - LEAD_SECONDS))
        ends = frame_at(times, (finish_ms - video_start_ms) / 1000.0)
        finish = min(len(times), ends + trail)
        if merged and begin <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], finish)
            merged[-1][3] += "+" + stage
        else:
            merged.append([begin, finish, marker, stage, ends])
    for begin, finish, _, ids, _ in merged:
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


def median_panel(samples):
    """The pixel-wise median of panels: it keeps what they show and drops the frames' noise."""
    return bytes(sorted(values)[len(values) // 2] for values in zip(*samples))


def caption_start(panels, marker, lowest, finish, rate, stage, clock, first, until, continues=False):
    """The frame a clip opens on and the stage's own caption, from `panels` of the frames around it.

    `clock` is the frame the log's clock puts 0.10 s before the marker, `lowest` the frame after the previous clip,
    `first` whether this is the first clip, `until` the frame the clip's first stage ends at and `continues` whether
    the previous clip ended on this stage (see `repeats`), whose caption the clock start may then still show. The
    stage's own caption is the median of five panels 0.5 to 0.9 s after the marker, or the last ones before `until`;
    it is only used to check the encode. The cut comes from the steps in the panel (see the module docstring): the
    world behind the panel drifts and can change off film, so neither the distance to a caption read at another moment
    nor a fixed limit decides where a caption appears.

    `panels` maps a frame to its residual panel and must hold every frame from lowest - 1 to LATE after the marker,
    or to the clip's end when it continues."""
    if until <= marker + at_rate(SEARCH_AFTER, rate):
        raise SystemExit(f"{stage}: the stage lasts {(until - marker) / rate:.2f} s from its marker (frame {marker}), "
                         "less than the 0.5 s its caption may come after it, so the picture cannot show its caption "
                         "arriving; cut with --clock")
    # A merged clip's next stage may draw its caption before this one's 0.9 s are up: the frames from its first
    # stage's end on show that caption, not this one's.
    own_last = min(finish, until) - 1
    reference = median_panel([panels[min(own_last, marker + at_rate(offset, rate))] for offset in REFERENCE_OFFSETS])
    lo = max(lowest, 1)
    read = min(own_last, marker + at_rate(SEARCH_AFTER, rate))
    end = min(own_last, marker + at_rate(REFERENCE_OFFSETS[-1], rate))
    # Nothing shows a repeated stage's caption arriving, so a new text under its id must be seen wherever it comes in
    # the stage, not only in the first 2 s.
    late = own_last if continues else min(own_last, marker + at_rate(LATE, rate))
    change = {k: mean_diff(panels[k - 1], panels[k]) for k in range(lo, max(end, late) + 1)}
    biggest = max((change[k] for k in range(lo, read + 1)), default=0.0)
    if biggest < CHANGED and not continues:
        raise SystemExit(f"{stage}: the caption never changes near frame {marker} (largest change {biggest:.1f}); "
                         "a recording without captions is cut with --clock")
    after = sorted(change[k] for k in range(max(lo, clock + 1), end + 1)) or [0.0]
    drift = after[len(after) // 2]
    if STEP * drift > NEAREST:
        raise SystemExit(f"{stage}: the world behind the caption panel moves {drift:.1f} a frame near frame {marker}, "
                         "too fast to see a caption change in it; cut with --clock")
    bar = max(CHANGED, STEP * drift, STILL * after[-1])
    steps = [k for k in range(max(lo, clock + 1), end + 1) if change[k] >= bar]
    events = []
    for k in steps:
        if events and k - events[-1][-1] < at_rate(SETTLE, rate):
            events[-1].append(k)
        else:
            events.append([k])
    if len(events) > 1:
        raise SystemExit(f"{stage}: the caption panel changes at frames "
                         f"{', '.join(str(event[-1]) for event in events[:-1])} and {events[-1][-1]}, after the "
                         f"clock start (frame {clock}), so the picture cannot tell which brings the stage's caption; "
                         "cut with --clock")
    late_steps = [k for k in range(end + 1, late + 1) if change[k] >= bar]
    if late_steps:
        raise SystemExit(f"{stage}: the caption panel changes at frame {late_steps[0]}, "
                         f"{(late_steps[0] - marker) / rate:.1f} s after the marker (frame {marker}), after its "
                         "caption is read, so the clip may open on the previous caption; cut with --clock")
    if events:
        opens = events[0][-1]
        if opens > read:
            raise SystemExit(f"{stage}: the caption panel changes at frame {opens}, 0.5 to 0.9 s after the marker "
                             f"(frame {marker}), while its caption is read; cut with --clock")
        return opens, reference
    if not first and not continues and not any(change[k] >= bar for k in range(lo, clock + 1)):
        raise SystemExit(f"{stage}: the caption panel does not change between the previous clip and frame {read}, "
                         "so the clock start may still show the previous caption; cut with --clock")
    return clock, reference


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


def repeats(previous_ids, ids):
    """Whether a clip opens on the stage its previous clip ended on: the stage repeats, and the caption on screen
    between the two clips is its own, so the picture cannot show it arriving or tell it from the previous clip's."""
    return previous_ids is not None and previous_ids.split("+")[-1] == ids.split("+")[0]


def caption_cuts(video, merged, rate):
    """Moves each clip's start to where the caption panel shows its stage's caption; returns each clip's own caption,
    the caption it ends on and how far its first frame then sits from its own caption."""
    settle = at_rate(SETTLE, rate)
    wanted = set()
    previous_end = 0
    previous_ids = None
    for _, finish, marker, ids, _ in merged:
        watched = finish - 1 if repeats(previous_ids, ids) else min(finish - 1, marker + at_rate(LATE, rate))
        wanted.update(range(max(0, previous_end - 1), watched + 1))
        # The caption the clip ends on, which is the one the next clip must not open on: a merged clip shows the
        # caption of the last stage it holds there, not the one its first stage's marker introduced.
        wanted.update(range(max(0, finish - 1 - settle), finish))
        previous_end = finish
        previous_ids = ids
    panels = read_panels(video, wanted)
    captions = []
    previous_end = 0
    previous_ids = None
    for clip in merged:
        begin, finish, marker, ids, until = clip
        stage = ids.split("+")[0]
        clip[0], reference = caption_start(panels, marker, previous_end, finish, rate, stage, begin, not captions,
                                           until, repeats(previous_ids, ids))
        print(f"{stage}: marker frame {marker}, caption from frame {clip[0]} ({(marker - clip[0]) / rate:+.2f} s)")
        captions.append({"own": reference,
                         "ending": median_panel([panels[j] for j in range(max(0, finish - 1 - settle), finish)]),
                         "measured": mean_diff(panels[clip[0]], reference)})
        previous_end = finish
        previous_ids = ids
    return captions


def caption_marks(meta):
    """The game capture's caption marks as (caption id, frame of the exported video), in the order they were first
    drawn, or None for a take without them."""
    boundaries = meta["boundaries"]
    marks = boundaries.get("gameCaptureCaptionMarks")
    if marks is None:
        return None
    first = int(boundaries["rawStartFrameIndex"])
    return [(mark["id"], int(mark["slot"]) - first) for mark in json.loads(marks)]


def marked_cuts(merged, marks):
    """Moves each clip's start to the first frame from its clock start that shows its stage's caption, by the marks.

    A stage's caption is one whose id is the stage's id, or ends in "." and the stage's id, as a mod that prefixes its
    captions names them; a stage id that more than one caption id matches fails. A caption is on screen from its mark
    to the next caption's mark. The clip opens at its clock start when the caption on screen there is the stage's,
    else on the stage's first caption drawn after the clock start; a stage with neither before its clip ends fails."""
    for clip in merged:
        begin, finish, marker, ids, _ = clip
        stage = ids.split("+")[0]
        names = sorted({caption for caption, _ in marks if caption == stage or caption.endswith("." + stage)})
        if len(names) > 1:
            raise SystemExit(f"{stage}: the stage's id names more than one caption ({', '.join(names)}); give the "
                             "stage its caption's full id, or cut with --clock")
        on_screen = [(caption, frame) for caption, frame in marks if frame <= begin]
        if on_screen and on_screen[-1][0] in names:
            caption, drawn = on_screen[-1]
            opens = begin
        else:
            later = [(caption, frame) for caption, frame in marks if caption in names and frame > begin]
            if not later:
                raise SystemExit(f"{stage}: no caption with the stage's id is on screen at the clock start (frame "
                                 f"{begin}) or drawn after it; show it with the stage's id, or cut with --clock")
            caption, drawn = later[0]
            if drawn >= finish:
                raise SystemExit(f"{stage}: its caption {caption} is first drawn at frame {drawn}, after the clip ends "
                                 f"at frame {finish}; cut with --clock")
            opens = drawn
        clip[0] = opens
        print(f"{stage}: marker frame {marker}, caption {caption} drawn from frame {drawn}, clip from frame {opens}")


def check_first_frames(out_path, stages, captions):
    """Fails, naming --clock, unless every clip of the cut opens on its own caption rather than the previous clip's.

    The first clip has no previous caption to compare with, so the second encode may only leave its first frame
    OWN_CAPTION worse than the source frame it was cut from: this checks the encode, not the cut. So may a clip that
    repeats the stage its previous clip ended on (see `repeats`), whose own caption is the previous one. Every other
    later clip must be closer to its own caption than to the caption the previous clip ended on, because how far the
    encoded first frame lands from its caption depends on the host's encoder: the same take measured 0.1 to 4.2 while
    the caption change stayed near 27. Both captions are read at other moments than the first frame, so on a world
    that drifts behind the panel two near-identical captions can fail a right cut here, and a world that changed
    between the clips can pass a wrong one: the cut must not lean on this check."""
    panels = read_panels(out_path, {stage["startFrame"] for stage in stages})
    previous = None
    previous_ids = None
    for stage, caption in zip(stages, captions):
        first = panels[stage["startFrame"]]
        own = mean_diff(first, caption["own"])
        if previous is None or repeats(previous_ids, stage["stage"]):
            if own >= caption["measured"] + OWN_CAPTION:
                raise SystemExit(f"{stage['stage']}: the clip's first frame does not show its own caption ({own:.1f}); "
                                 "cut with --clock")
        elif own >= mean_diff(first, previous):
            raise SystemExit(f"{stage['stage']}: the clip's first frame still shows the previous caption "
                             f"({own:.1f} to its own, {mean_diff(first, previous):.1f} to the previous); "
                             "cut with --clock")
        previous = caption["ending"]
        previous_ids = stage["stage"]


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
    captions = None
    marks = None if args.clock else caption_marks(meta)
    if args.clock:
        print("cut by the log's clock (--clock)")
    elif marks is not None:
        print(f"cut by the game capture's caption marks ({len(marks)} captions)")
        marked_cuts(merged, marks)
    else:
        if size != VIDEO_SIZE:
            raise SystemExit(f"the video is {size[0]}x{size[1]}, but the caption panel is only measured at the "
                             f"{VIDEO_SIZE[0]}x{VIDEO_SIZE[1]} recording standard; cut it with --clock")
        print("cut by the caption panel's pixels (no caption marks: a screen grab or a take before 3.6.0)")
        captions = caption_cuts(video, merged, rate)
    for index, (begin, finish, _, ids, _) in enumerate(merged):
        print(f"clip {index}: frames {begin} - {finish} ({(finish - begin) / rate:.2f}s) {ids}")
    encode(video, out_path, merged, times, rate, sound)

    stages = []
    frame = 0
    for begin, finish, _, ids, _ in merged:
        stages.append({"stage": ids, "startFrame": frame, "endFrameExclusive": frame + finish - begin})
        frame += finish - begin
    joined = frame_count(out_path)
    if joined != frame:
        raise SystemExit(f"the joined video has {joined} frames but its clips add up to {frame}; stage frames would be wrong")
    if captions is not None:
        check_first_frames(out_path, stages, captions)
    out_path.with_suffix(".stages.json").write_text(json.dumps(stages, indent=2) + "\n")
    print("wrote", out_path)


if __name__ == "__main__":
    main(sys.argv[1:])
