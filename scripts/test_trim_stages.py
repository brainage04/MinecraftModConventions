from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import trim_stages

SCRIPT = Path(__file__).resolve().parent / "trim_stages.py"
RATE = 30
START_MS = 1_000_000

# Boxes standing in for caption text, inside the caption panel (x 1100-1905, y 15-132 at 1920x1080).
HEADER = [(1700, 22, 180, 14)]
CAPTION_A = [(1200, 45, 300, 20), (1550, 45, 150, 20), (1200, 80, 250, 18)]
CAPTION_B = [(1250, 45, 200, 20), (1480, 45, 320, 20), (1300, 80, 350, 18)]
CAPTION_C = [(1150, 45, 500, 20), (1700, 80, 150, 18)]
# Only caption B draws here: the test reads it to tell which caption a frame shows.
B_ONLY = (1720, 45, 70, 20)


def make_recording(directory: Path, captions: list[tuple[list, int, int]], markers: list[tuple[str, str, float]],
                   seconds: float = 6.0) -> tuple[Path, Path, Path]:
    """A 1920x1080 take with a moving world, the caption panel and its captions at frames [first, last], a log with
    the given STAGE_START/STAGE_END markers (kind, id, seconds into the video) and the recorder's metadata."""
    boxes = [f"drawbox=x={x}:y={y}:w={w}:h={h}:color=white:t=fill" for x, y, w, h in HEADER]
    for caption, first, last in captions:
        boxes += [f"drawbox=x={x}:y={y}:w={w}:h={h}:color=white:t=fill:enable='between(n,{first},{last})'"
                  for x, y, w, h in caption]
    video = directory / "take.mp4"
    subprocess.run([
        "ffmpeg", "-v", "error", "-y",
        "-f", "lavfi", "-i", f"gradients=size=1920x1080:rate={RATE}:duration={seconds}:speed=0.02:seed=7",
        "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
        "-vf", ",".join(["drawbox=x=1100:y=15:w=805:h=117:color=black@0.63:t=fill", *boxes]),
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "12", "-pix_fmt", "yuv420p", "-c:a", "aac",
        "-shortest", str(video),
    ], check=True)
    log = directory / "run.log"
    log.write_text("".join(f"[Render thread/INFO] {kind} {START_MS + round(at * 1000)} {stage}\n"
                           for kind, stage, at in markers))
    meta = directory / "take.json"
    meta.write_text(json.dumps({"fps": str(RATE), "rawCapture": str(directory / "take-raw.mkv"),
                                "boundaries": {"selectedStartEpochSeconds": START_MS / 1000.0}}))
    return log, video, meta


def brightness(video: Path, frame: int, box: tuple[int, int, int, int]) -> float:
    x, y, w, h = box
    data = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(video), "-vf", f"select='eq(n,{frame})',crop={w}:{h}:{x}:{y},format=gray",
         "-frames:v", "1", "-f", "rawvideo", "-"], check=True, capture_output=True).stdout
    return sum(data) / len(data)


def cut(log: Path, video: Path, meta: Path, *extra: str) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(SCRIPT), str(log), str(video), str(video.with_name("cut.mp4")),
                           str(meta), *extra], capture_output=True, text=True)


class StageWindowsTest(unittest.TestCase):
    def test_a_repeated_stage_pairs_with_the_first_end_after_its_start(self) -> None:
        log = ("STAGE_START 100 a\nSTAGE_END 200 a\nSTAGE_START 300 a\nSTAGE_START 250 b\n"
               "STAGE_END 400 a\nSTAGE_END 350 b\n")
        self.assertEqual(trim_stages.stage_windows(*trim_stages.markers(log)),
                         [[100, 200, "a"], [250, 350, "b"], [300, 400, "a"]])

    def test_a_start_without_an_end_fails_naming_the_stage(self) -> None:
        with self.assertRaises(SystemExit) as failure:
            trim_stages.stage_windows(*trim_stages.markers("STAGE_START 100 a\nSTAGE_END 50 a\n"))
        self.assertIn("STAGE_START without a following STAGE_END: a", str(failure.exception))

    def test_a_log_without_markers_fails(self) -> None:
        with self.assertRaises(SystemExit):
            trim_stages.stage_windows([], [])


class ClipsTest(unittest.TestCase):
    def test_markers_land_on_the_frames_own_timestamps_not_on_a_fixed_rate(self) -> None:
        # Frames 31 to 36 ms apart, as a screen grab delivers them.
        times = [0.0, 0.031, 0.067, 0.100, 0.136, 0.167, 0.203, 0.234]
        self.assertEqual(trim_stages.frame_at(times, 0.136), 4)
        self.assertEqual(trim_stages.frame_at(times, 0.140), 5)

    def test_touching_stages_are_one_clip_and_separate_ones_are_not(self) -> None:
        times = [n / 30 for n in range(300)]
        windows = [[1_000, 2_000, "a"], [2_050, 3_000, "b"], [5_000, 6_000, "c"]]
        self.assertEqual(trim_stages.clips(windows, times, 0, 30),
                         [[27, 94, 30, "a+b"], [147, 184, 150, "c"]])


class CaptionStartTest(unittest.TestCase):
    def test_the_clip_opens_where_its_caption_has_settled_not_at_the_marker(self) -> None:
        old, half, new = bytes([10] * 100), bytes([60] * 100), bytes([200] * 100)
        # The caption is drawn in two steps, at frames 40 and 41; the marker says 35.
        panels = {k: old if k < 40 else half if k == 40 else new for k in range(0, 80)}
        begin, reference = trim_stages.caption_start(panels, 35, 1, 79, 80, 30, "b")
        self.assertEqual(begin, 41)
        self.assertEqual(reference, new)

    def test_a_caption_that_never_changes_near_the_marker_fails(self) -> None:
        panels = {k: bytes([10] * 100) for k in range(0, 80)}
        with self.assertRaises(SystemExit) as failure:
            trim_stages.caption_start(panels, 35, 1, 79, 80, 30, "b")
        self.assertIn("b: the caption never changes near frame 35", str(failure.exception))


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "ffmpeg is not installed")
class CaptionCutTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(tempfile.mkdtemp(prefix="trim-stages-"))
        self.addCleanup(shutil.rmtree, self.directory)

    def test_each_clip_opens_on_its_own_caption_although_the_log_clock_is_early(self) -> None:
        # Caption B shows from frame 90 (3.0 s), but the log puts b's start at 2.7 s: a cut by the clock opens on
        # caption A. Stage c touches b, so b and c are one clip.
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_A, 12, 89), (CAPTION_B, 90, 121), (CAPTION_C, 122, 179)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0), ("STAGE_START", "b", 2.7),
             ("STAGE_END", "b", 4.0), ("STAGE_START", "c", 4.05), ("STAGE_END", "c", 5.5)])

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        out = video.with_name("cut.mp4")
        stages = json.loads(out.with_suffix(".stages.json").read_text())
        self.assertEqual(stages, [{"stage": "a", "startFrame": 0, "endFrameExclusive": 52},
                                  {"stage": "b+c", "startFrame": 52, "endFrameExclusive": 131}])
        self.assertGreater(brightness(out, 52, B_ONLY), 200, "clip b+c must open on caption B")
        self.assertLess(brightness(out, 51, B_ONLY), 120, "clip a must end on caption A")

    def test_a_take_without_captions_fails_unless_cut_by_the_clock(self) -> None:
        log, video, meta = make_recording(
            self.directory, [], [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0)], seconds=3.0)

        by_caption = cut(log, video, meta)
        self.assertNotEqual(by_caption.returncode, 0)
        self.assertIn("a: the caption never changes near frame 15", by_caption.stderr)

        by_clock = cut(log, video, meta, "--clock")
        self.assertEqual(by_clock.returncode, 0, by_clock.stdout + by_clock.stderr)
        stages = json.loads(video.with_name("cut.stages.json").read_text())
        self.assertEqual(stages, [{"stage": "a", "startFrame": 0, "endFrameExclusive": 52}])

    def test_the_raw_capture_is_refused(self) -> None:
        log, video, meta = make_recording(
            self.directory, [], [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0)], seconds=1.0)
        meta.write_text(json.dumps({"rawCapture": str(video), "boundaries": {"selectedStartEpochSeconds": 1000.0}}))

        result = cut(log, video, meta)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not the raw capture", result.stderr)


if __name__ == "__main__":
    unittest.main()
