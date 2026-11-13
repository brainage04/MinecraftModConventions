from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import trim_stages

SCRIPT = Path(__file__).resolve().parent / "trim_stages.py"
RATE = 30
START_MS = 1_000_000
WIDTH, HEIGHT = 1920, 1080

# Boxes standing in for caption text, inside the caption panel (x 1100-1905, y 15-132 at 1920x1080).
HEADER = [(1700, 22, 180, 14)]
CAPTION_A = [(1200, 45, 300, 20), (1550, 45, 150, 20), (1200, 80, 250, 18)]
CAPTION_B = [(1250, 45, 200, 20), (1480, 45, 320, 20), (1300, 80, 350, 18)]
CAPTION_C = [(1150, 45, 500, 20), (1700, 80, 150, 18)]
# A caption that shares all but one small box with CAPTION_B, as two takes of one mod's stages do: their panels are
# 2.6 apart, where CAPTION_B and CAPTION_C are 30 apart.
CAPTION_C_LIKE = [*CAPTION_B, (1700, 80, 60, 18)]
# Only caption B draws here: the test reads it to tell which caption a frame shows. CAPTION_C_LIKE draws in C_ONLY.
B_ONLY = (1720, 45, 70, 20)
C_ONLY = (1710, 80, 40, 18)


# The world behind the panel, drifting one pixel's worth a frame the way a moving scene does, and drifting the
# residual panel about 0.5 a frame with it: the reference taken 0.5-0.9 s after a marker then sits about 8 from a
# caption's first frame, which the fixed limits of the first version could not take.
DRIFTING = (f"nullsrc=size={WIDTH}x{HEIGHT}:rate={RATE}:duration=6,"
            "geq=lum='128+60*sin(2*PI*(X/300+T*0.25))':cb=128:cr=128,format=yuv420p")
# The same world without the drift, its colours pinned: lavfi's gradients picks random colours even with a seed, so
# two runs of an unpinned fixture coded different pictures and the test could pass or fail by luck.
STILL = (f"gradients=size={WIDTH}x{HEIGHT}:rate={RATE}:duration=6:speed=0.02:seed=7:"
         f"c0=0x101010:c1=0x404040:x0=0:y0=0:x1={WIDTH}:y1={HEIGHT}")


def make_recording(directory: Path, captions: list[tuple[list, int, int]], markers: list[tuple[str, str, float]],
                   seconds: float = 6.0, drifting: bool = False,
                   flash: tuple[int, int] | None = None) -> tuple[Path, Path, Path]:
    """A 1920x1080 take with the caption panel and its captions at frames [first, last], a log with the given
    STAGE_START/STAGE_END markers (kind, id, seconds into the video) and the recorder's metadata.

    `drifting` models a world that moves behind the panel; `flash` draws a bright off-film screen over the panel at
    frames [first, last], which is what a loading screen or a camera cut does between two stages."""
    boxes = [f"drawbox=x={x}:y={y}:w={w}:h={h}:color=white:t=fill" for x, y, w, h in HEADER]
    for caption, first, last in captions:
        boxes += [f"drawbox=x={x}:y={y}:w={w}:h={h}:color=white:t=fill:enable='between(n,{first},{last})'"
                  for x, y, w, h in caption]
    if flash:
        first, last = flash
        boxes += [f"drawbox=x={x}:y={y}:w={w}:h={h}:color=white:t=fill:enable='between(n,{first},{last})'"
                  for x, y, w, h in [(1150, 20, 400, 25), (1150, 55, 700, 25), (1150, 90, 300, 25)]]
    source = (DRIFTING if drifting else STILL).replace("duration=6", f"duration={seconds}")
    video = directory / "take.mp4"
    subprocess.run([
        "ffmpeg", "-v", "error", "-y",
        "-f", "lavfi", "-i", source,
        "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
        "-vf", ",".join(["drawbox=x=1100:y=15:w=805:h=117:color=black@0.63:t=fill", *boxes]),
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "12", "-pix_fmt", "yuv420p", "-c:a", "aac",
        # The fixture encodes with one thread so that x264 codes a caption's first frames the same way on every run;
        # 20 threads put caption B's first frame 4.27 off its caption against 0.24 on one.
        "-threads", "1",
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
        # The caption is drawn in two steps, at frames 40 and 41; the marker says 35, so the clock starts at 32.
        panels = {k: old if k < 40 else half if k == 40 else new for k in range(0, 80)}
        begin, reference = trim_stages.caption_start(panels, 35, 1, 79, 80, 30, "b", 32)
        self.assertEqual(begin, 41)
        self.assertEqual(reference, new)

    def test_a_caption_that_never_changes_near_the_marker_fails(self) -> None:
        panels = {k: bytes([10] * 100) for k in range(0, 80)}
        with self.assertRaises(SystemExit) as failure:
            trim_stages.caption_start(panels, 35, 1, 79, 80, 30, "b", 32)
        self.assertIn("b: the caption never changes near frame 35", str(failure.exception))

    def test_a_noisy_first_frame_of_a_caption_still_shows_it(self) -> None:
        # An encoder codes a scene change's first frames worse, so caption B's first seven frames sit 6 off the
        # median caption while the caption change near the marker is 106: a fixed distance of 3.5 would skip them.
        old, new = bytes([10] * 100), bytes([110] * 100)
        noisy = bytes([125] * 40 + [110] * 60)
        panels = {k: old if k < 40 else noisy if k < 47 else new for k in range(0, 80)}
        begin, reference = trim_stages.caption_start(panels, 41, 1, 79, 80, 30, "b", 38)
        self.assertEqual(begin, 40)
        self.assertEqual(reference, new)

    def test_a_clock_frame_on_a_similar_previous_caption_stays_out_despite_an_off_film_change(self) -> None:
        # Two captions that share most of their text, and an off-film screen between them whose change is far
        # larger than the caption's own: the frame the clock lands on still shows the previous caption, which only
        # the comparison with that caption can tell, since against the caption change alone it passes.
        previous = bytes([10] * 60 + [200] * 40)
        own = bytes([10] * 60 + [200] * 30 + [120] * 10)
        panels = {k: previous for k in range(0, 40)}
        panels.update({k: own for k in range(40, 80)})
        panels[30] = bytes([255] * 100)
        begin, reference = trim_stages.caption_start(panels, 35, 1, 79, 80, 30, "b", 32, previous=previous)
        self.assertEqual(begin, 40)
        self.assertEqual(reference, own)

    def test_a_caption_that_is_still_being_drawn_is_not_taken_from_its_first_step(self) -> None:
        old, drawn, new = bytes([10] * 100), bytes([170] * 100), bytes([200] * 100)
        # Caption B is drawn in two steps at frames 40 and 42; its first step is within the caption change of the
        # settled caption, so only the panel still moving gives it away.
        panels = {k: old if k < 40 else drawn if k < 42 else new for k in range(0, 80)}
        begin, reference = trim_stages.caption_start(panels, 35, 1, 79, 80, 30, "b", 32)
        self.assertEqual(begin, 42)
        self.assertEqual(reference, new)


class FirstFramesTest(unittest.TestCase):
    def test_a_clip_whose_first_frame_is_not_its_caption_fails(self) -> None:
        stages = [{"stage": "a", "startFrame": 0, "endFrameExclusive": 10}]
        panels = {0: bytes([200] * 100)}
        captions = [{"own": bytes([10] * 100), "ending": bytes([10] * 100), "measured": 0.0}]
        with mock.patch.object(trim_stages, "read_panels", return_value=panels):
            with self.assertRaises(SystemExit) as failure:
                trim_stages.check_first_frames(Path("cut.mp4"), stages, captions)
        self.assertIn("a: the clip's first frame does not show its own caption", str(failure.exception))

    def test_the_first_clip_may_open_as_far_from_its_caption_as_the_source_frame_did(self) -> None:
        # A world that drifts behind the panel leaves a good first frame 6 off its caption, where the fixed
        # allowance was 4; the second encode may only add OWN_CAPTION to what the cut measured.
        stages = [{"stage": "a", "startFrame": 0, "endFrameExclusive": 10}]
        panels = {0: bytes([116] * 100)}
        captions = [{"own": bytes([110] * 100), "ending": bytes([110] * 100), "measured": 4.0}]
        with mock.patch.object(trim_stages, "read_panels", return_value=panels):
            trim_stages.check_first_frames(Path("cut.mp4"), stages, captions)
            with self.assertRaises(SystemExit):
                trim_stages.check_first_frames(Path("cut.mp4"), stages,
                                               [{"own": bytes([110] * 100), "ending": bytes([110] * 100),
                                                 "measured": 0.0}])

    def test_a_clip_that_still_shows_the_previous_caption_fails(self) -> None:
        stages = [{"stage": "a", "startFrame": 0, "endFrameExclusive": 10},
                  {"stage": "b", "startFrame": 10, "endFrameExclusive": 20}]
        panels = {0: bytes([10] * 100), 10: bytes([12] * 100)}
        captions = [{"own": bytes([10] * 100), "ending": bytes([10] * 100), "measured": 0.0},
                    {"own": bytes([14] * 100), "ending": bytes([14] * 100), "measured": 0.0}]
        with mock.patch.object(trim_stages, "read_panels", return_value=panels):
            with self.assertRaises(SystemExit) as failure:
                trim_stages.check_first_frames(Path("cut.mp4"), stages, captions)
        self.assertIn("b: the clip's first frame still shows the previous caption", str(failure.exception))

    def test_a_clip_after_a_merged_one_is_checked_against_the_caption_it_ended_with(self) -> None:
        # Stages a and b are one clip that ends on caption B; stage c's own caption shares most of its text with B.
        # Comparing c's first frame with a's caption would let a frame holding B pass, so the comparison uses the
        # caption the merged clip ended on.
        stages = [{"stage": "a+b", "startFrame": 0, "endFrameExclusive": 10},
                  {"stage": "c", "startFrame": 10, "endFrameExclusive": 20}]
        b_text, c_text = bytes([10] * 60 + [200] * 40), bytes([10] * 60 + [200] * 30 + [120] * 10)
        panels = {0: bytes([10] * 100), 10: b_text}
        captions = [{"own": bytes([10] * 100), "ending": b_text, "measured": 0.0},
                    {"own": c_text, "ending": c_text, "measured": 0.0}]
        with mock.patch.object(trim_stages, "read_panels", return_value=panels):
            with self.assertRaises(SystemExit) as failure:
                trim_stages.check_first_frames(Path("cut.mp4"), stages, captions)
        self.assertIn("c: the clip's first frame still shows the previous caption", str(failure.exception))


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

    def test_a_caption_that_leads_its_marker_opens_at_the_marker(self) -> None:
        # IceSkates draws a caption, waits for the scene to settle off film and only then marks the stage, so the
        # caption can show 1 s before the marker. The clip must open at the clock's start, not on settle frames.
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_A, 2, 40), (CAPTION_B, 41, 110)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 1.2),
             ("STAGE_START", "b", 2.4), ("STAGE_END", "b", 3.5)])

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        out = video.with_name("cut.mp4")
        stages = json.loads(out.with_suffix(".stages.json").read_text())
        self.assertEqual(stages, [{"stage": "a", "startFrame": 0, "endFrameExclusive": 28},
                                  {"stage": "b", "startFrame": 28, "endFrameExclusive": 68}])
        self.assertGreater(brightness(out, 28, B_ONLY), 200, "clip b must open on caption B")

    def test_a_noisy_take_still_opens_on_its_own_caption(self) -> None:
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_A, 12, 89), (CAPTION_B, 90, 179)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0), ("STAGE_START", "b", 2.7),
             ("STAGE_END", "b", 5.0)])
        noisy = self.directory / "noisy.mp4"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(video), "-c:v", "libx264", "-preset",
                        "veryfast", "-crf", "30", "-threads", "4", "-c:a", "copy", str(noisy)], check=True)

        result = cut(log, noisy, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        out = noisy.with_name("cut.mp4")
        stages = json.loads(out.with_suffix(".stages.json").read_text())
        self.assertEqual(stages[1]["startFrame"], 52, "clip b must open where caption B appears")
        self.assertGreater(brightness(out, 52, B_ONLY), 200, "clip b must open on caption B")

    def test_a_take_whose_world_drifts_behind_the_panel_still_opens_on_its_caption(self) -> None:
        # The world moves behind the translucent panel, so caption B's first frame sits about 8 from the caption
        # read 0.5-0.9 s later while the previous caption is 25 away: the cut must still open on B's first frame.
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_A, 12, 89), (CAPTION_B, 90, 121), (CAPTION_C, 122, 179)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0), ("STAGE_START", "b", 2.7),
             ("STAGE_END", "b", 4.0), ("STAGE_START", "c", 4.05), ("STAGE_END", "c", 5.5)],
            drifting=True)

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("b: marker frame 81, caption from frame 90", result.stdout)
        stages = json.loads(video.with_name("cut.stages.json").read_text())
        self.assertGreater(brightness(video.with_name("cut.mp4"), stages[1]["startFrame"], B_ONLY), 200,
                           "clip b+c must open on caption B")

    def test_a_take_whose_world_drifts_behind_the_panel_keeps_a_lead_caption_at_its_clock(self) -> None:
        # Caption B shows from frame 52, 1.9 s before its marker, so the clock start already shows it and the clip
        # opens there; a drifting world must not push the cut later into the take.
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_A, 2, 51), (CAPTION_B, 52, 179)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 1.2),
             ("STAGE_START", "b", 2.4), ("STAGE_END", "b", 3.5)],
            drifting=True)

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("b: marker frame 72, caption from frame 69", result.stdout)
        stages = json.loads(video.with_name("cut.stages.json").read_text())
        self.assertGreater(brightness(video.with_name("cut.mp4"), stages[1]["startFrame"], B_ONLY), 200,
                           "clip b must open on caption B")

    def test_a_clip_after_a_merged_one_does_not_open_on_the_caption_it_ended_with(self) -> None:
        # Stages a and b touch, so they are one clip that ends on caption B. Stage c's caption shares most of its
        # text with B, and an off-film screen covers the panel between them, which must not let the cut open c on
        # the caption the merged clip ended on.
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_A, 12, 59), (CAPTION_B, 60, 119), (CAPTION_C_LIKE, 120, 179)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0), ("STAGE_START", "b", 2.0),
             ("STAGE_END", "b", 2.6), ("STAGE_START", "c", 3.6), ("STAGE_END", "c", 5.0)],
            flash=(86, 92))

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("c: marker frame 108, caption from frame 120", result.stdout)
        stages = json.loads(video.with_name("cut.stages.json").read_text())
        self.assertEqual([stage["stage"] for stage in stages], ["a+b", "c"])
        self.assertGreater(brightness(video.with_name("cut.mp4"), stages[1]["startFrame"], C_ONLY), 200,
                           "clip c must open on caption C")

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
