#!/usr/bin/env python3
"""Cuts a client GameTest recording down to its filmed stages.

usage: trim_stages.py LOG VIDEO.mp4 OUT.mp4 META.json

The test logs `STAGE_START <epoch ms> <id>` and `STAGE_END <epoch ms> <id>` around everything that is meant to be on
screen; the join, the dimension change, chunk loading and the off-camera rehearsal sit between the markers and are cut.
Stages that touch or overlap are kept as one continuous clip, so nothing is repeated or dropped between them.

The video starts at the recorder's selected start, which the metadata records as
`boundaries.selectedStartEpochSeconds`; a stage whose id repeats is paired with the first end that follows its start.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
video_path = Path(sys.argv[2])
out_path = Path(sys.argv[3])
meta_path = Path(sys.argv[4])

log = log_path.read_text(errors="replace")
starts = [(int(m.group(1)), m.group(2)) for m in re.finditer(r"STAGE_START (\d+) (\S+)", log)]
ends = [(int(m.group(1)), m.group(2)) for m in re.finditer(r"STAGE_END (\d+) (\S+)", log)]
if not starts:
    raise SystemExit("no STAGE_START markers in the log")

meta = json.loads(meta_path.read_text())
video_start_ms = float(meta["boundaries"]["selectedStartEpochSeconds"]) * 1000.0

LEAD = 0.10
TRAIL = 0.12
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
    begin = max(0.0, (start_ms - video_start_ms) / 1000.0 - LEAD)
    finish = (finish_ms - video_start_ms) / 1000.0 + TRAIL
    windows.append([begin, finish, stage])
if unmatched:
    raise SystemExit("STAGE_START without a following STAGE_END: " + ", ".join(unmatched))
windows.sort()
merged = []
for begin, finish, stage in windows:
    if merged and begin <= merged[-1][1]:
        merged[-1][1] = max(merged[-1][1], finish)
        merged[-1][2] += "+" + stage
    else:
        merged.append([begin, finish, stage])

def frame_count(video):
    return int(subprocess.run([
        "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(video),
    ], check=True, capture_output=True, text=True).stdout.strip())


def source_frame_times(video):
    output = subprocess.run([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "frame=best_effort_timestamp_time", "-of", "csv=p=0", str(video),
    ], check=True, capture_output=True, text=True).stdout
    return [float(line.split(",")[0]) for line in output.splitlines()
            if line and line.split(",")[0].replace(".", "", 1).isdigit()]


source_times = source_frame_times(video_path)

parts = []
for index, (begin, finish, stage) in enumerate(merged):
    expected = sum(1 for t in source_times if begin <= t < finish)
    print(f"clip {index}: {begin:.2f}s - {finish:.2f}s ({finish - begin:.2f}s) {stage}; "
          f"source frames {expected}")
    part = out_path.with_name(f"{out_path.stem}-part{index}.mp4")
    # Input seeking with -ss/-to left the first two PTS of some parts at 0.000 and 0.0667,
    # dropping the frame between them. The trim filter takes the same source frames without a gap.
    subprocess.run([
        "ffmpeg", "-v", "error", "-y",
        "-i", str(video_path),
        "-vf", f"trim=start={begin:.3f}:end={finish:.3f},setpts=PTS-STARTPTS",
        "-af", f"atrim=start={begin:.3f}:end={finish:.3f},asetpts=PTS-STARTPTS",
        "-fps_mode", "passthrough",
        "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k", str(part),
    ], check=True)
    actual = frame_count(part)
    print(f"  part {index}: {actual} frames")
    if actual != expected:
        raise SystemExit(f"clip {index} has {actual} frames but the source window has {expected}")
    parts.append(part)

listing = out_path.with_name(f"{out_path.stem}-concat.txt")
listing.write_text("".join(f"file '{part.name}'\n" for part in parts))
# A part's container duration can be one frame longer than its frame count, which leaves a 66.7 ms
# step at a join. Renumber the joined video's PTS from the frame count so every join steps 33.3 ms.
subprocess.run([
    "ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
    "-vf", "setpts=N/30/TB", "-fps_mode", "passthrough",
    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "128k", str(out_path),
], check=True)


stages = []
frame = 0
for part, (_, _, stage) in zip(parts, merged):
    frames = frame_count(part)
    stages.append({"stage": stage, "startFrame": frame, "endFrameExclusive": frame + frames})
    frame += frames
joined = frame_count(out_path)
if joined != frame:
    raise SystemExit(f"the joined video has {joined} frames but its clips add up to {frame}; stage frames would be wrong")
out_path.with_suffix(".stages.json").write_text(json.dumps(stages, indent=2) + "\n")
for part in parts:
    part.unlink()
listing.unlink()
print("wrote", out_path)
