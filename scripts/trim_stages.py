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

parts = []
for index, (begin, finish, stage) in enumerate(merged):
    print(f"clip {index}: {begin:.2f}s - {finish:.2f}s ({finish - begin:.2f}s) {stage}")
    part = out_path.with_name(f"{out_path.stem}-part{index}.mp4")
    subprocess.run([
        "ffmpeg", "-v", "error", "-y",
        "-ss", f"{begin:.3f}", "-to", f"{finish:.3f}", "-i", str(video_path),
        "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k", str(part),
    ], check=True)
    parts.append(part)

listing = out_path.with_name(f"{out_path.stem}-concat.txt")
listing.write_text("".join(f"file '{part.name}'\n" for part in parts))
subprocess.run([
    "ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
    "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "128k", str(out_path),
], check=True)


def frame_count(video):
    return int(subprocess.run([
        "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(video),
    ], check=True, capture_output=True, text=True).stdout.strip())


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
