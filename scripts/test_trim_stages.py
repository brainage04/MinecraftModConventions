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



def drifting(cycles_per_second: float) -> str:
    """A world that moves behind the panel the way a moving scene does. At 0.25 cycles a second it moves the residual
    panel 0.8 to 1.0 a frame, so the caption read 0.5-0.9 s after a marker sits about 8 from the caption's first
    frame, which the fixed limits of the first version could not take."""
    return (f"nullsrc=size={WIDTH}x{HEIGHT}:rate={RATE}:duration=6,"
            f"geq=lum='128+60*sin(2*PI*(X/300+T*{cycles_per_second}))':cb=128:cr=128,format=yuv420p")


DRIFTING = drifting(0.25)
# A still world that turns into another picture at frame 70, between two clips, as a teleport or a camera cut off film
# does: the residual panel moves 13 there, more than two near-identical captions differ (4.9).
SWITCHING = (f"nullsrc=size={WIDTH}x{HEIGHT}:rate={RATE}:duration=6,"
             "geq=lum='128+40*sin(2*PI*(X+if(lt(N,70),0,20))/41)*sin(2*PI*Y/29)':cb=128:cr=128,format=yuv420p")
# The same world without the drift, its colours pinned: lavfi's gradients picks random colours even with a seed, so
# two runs of an unpinned fixture coded different pictures and the test could pass or fail by luck.
STILL = (f"gradients=size={WIDTH}x{HEIGHT}:rate={RATE}:duration=6:speed=0.02:seed=7:"
         f"c0=0x101010:c1=0x404040:x0=0:y0=0:x1={WIDTH}:y1={HEIGHT}")


def make_recording(directory: Path, captions: list[tuple[list, int, int]], markers: list[tuple[str, str, float]],
                   seconds: float = 6.0, world: str = STILL,
                   flash: tuple[int, int] | None = None) -> tuple[Path, Path, Path]:
    """A 1920x1080 take with the caption panel and its captions at frames [first, last], a log with the given
    STAGE_START/STAGE_END markers (kind, id, seconds into the video) and the recorder's metadata.

    `world` is the picture behind the panel (STILL, DRIFTING, SWITCHING); `flash` draws a bright off-film screen over
    the panel at frames [first, last], which is what a loading screen or a camera cut does between two stages."""
    boxes = [f"drawbox=x={x}:y={y}:w={w}:h={h}:color=white:t=fill" for x, y, w, h in HEADER]
    for caption, first, last in captions:
        boxes += [f"drawbox=x={x}:y={y}:w={w}:h={h}:color=white:t=fill:enable='between(n,{first},{last})'"
                  for x, y, w, h in caption]
    if flash:
        first, last = flash
        boxes += [f"drawbox=x={x}:y={y}:w={w}:h={h}:color=white:t=fill:enable='between(n,{first},{last})'"
                  for x, y, w, h in [(1150, 20, 400, 25), (1150, 55, 700, 25), (1150, 90, 300, 25)]]
    source = world.replace("duration=6", f"duration={seconds}")
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


def loudness(video: Path, start: float, end: float) -> float:
    """The root mean square of the video's sound from `start` to `end` seconds, from -1 to 1."""
    data = subprocess.run(["ffmpeg", "-v", "error", "-i", str(video), "-map", "0:a", "-ac", "1", "-ar", "48000",
                           "-f", "s16le", "-"], check=True, capture_output=True).stdout
    samples = [int.from_bytes(data[i:i + 2], "little", signed=True) / 32768
               for i in range(2 * round(start * 48000), 2 * round(end * 48000), 2)]
    return (sum(sample * sample for sample in samples) / len(samples)) ** 0.5


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
                         [[27, 94, 30, "a+b", 60], [147, 184, 150, "c", 180]])


class AudioPiecesTest(unittest.TestCase):
    def test_the_sound_skips_a_capture_gap_with_the_picture(self) -> None:
        # Frames 10 and on were captured 0.5 s late: once every frame lasts 1/30 s, frame 10 plays at 0.33 s, so the
        # sound under it must come from 0.83 s, where the frame was captured.
        times = [n / 30 for n in range(10)] + [0.5 + n / 30 for n in range(10, 20)]
        self.assertEqual(trim_stages.audio_pieces(2, 20, times, 30), [(2 / 30, 8 / 30), (0.5 + 10 / 30, 10 / 30)])

    def test_frames_that_keep_their_clock_take_one_piece_of_sound(self) -> None:
        # Frames 31 to 36 ms apart, as a screen grab delivers them, never drift half a frame from the 30 fps clock.
        times = [n / 30 + (0.003 if n % 2 else -0.003) for n in range(60)]
        times[0] = 0.0
        self.assertEqual(trim_stages.audio_pieces(0, 60, times, 30), [(0.0, 2.0)])


class CaptionStartTest(unittest.TestCase):
    def test_the_clip_opens_where_its_caption_has_settled_not_at_the_marker(self) -> None:
        old, half, new = bytes([10] * 100), bytes([60] * 100), bytes([200] * 100)
        # The caption is drawn in two steps, at frames 40 and 41; the marker says 35, so the clock starts at 32.
        panels = {k: old if k < 40 else half if k == 40 else new for k in range(0, 80)}
        begin, reference = trim_stages.caption_start(panels, 35, 1, 80, 30, "b", 32, True, 80)
        self.assertEqual(begin, 41)
        self.assertEqual(reference, new)

    def test_a_caption_that_never_changes_near_the_marker_fails(self) -> None:
        panels = {k: bytes([10] * 100) for k in range(0, 80)}
        with self.assertRaises(SystemExit) as failure:
            trim_stages.caption_start(panels, 35, 1, 80, 30, "b", 32, True, 80)
        self.assertIn("b: the caption never changes near frame 35", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_noisy_first_frame_of_a_caption_still_shows_it(self) -> None:
        # An encoder codes a scene change's first frames worse, so caption B's first seven frames sit 6 off the
        # median caption while the caption change near the marker is 106: a fixed distance of 3.5 would skip them.
        old, new = bytes([10] * 100), bytes([110] * 100)
        noisy = bytes([125] * 40 + [110] * 60)
        panels = {k: old if k < 40 else noisy if k < 47 else new for k in range(0, 80)}
        begin, reference = trim_stages.caption_start(panels, 41, 1, 80, 30, "b", 38, True, 80)
        self.assertEqual(begin, 40)
        self.assertEqual(reference, new)

    def test_a_similar_caption_after_an_off_film_change_opens_where_it_appears(self) -> None:
        # Two captions that share most of their text, and an off-film screen between them whose change is far
        # larger than the caption's own: the frame the clock lands on still shows the previous caption, and only the
        # step where the new caption appears, after the clock, may open the clip.
        previous = bytes([10] * 60 + [200] * 40)
        own = bytes([10] * 60 + [200] * 30 + [120] * 10)
        panels = {k: previous for k in range(0, 40)}
        panels.update({k: own for k in range(40, 80)})
        panels[30] = bytes([255] * 100)
        begin, reference = trim_stages.caption_start(panels, 35, 1, 80, 30, "b", 32, False, 80)
        self.assertEqual(begin, 40)
        self.assertEqual(reference, own)

    def test_a_caption_that_is_still_being_drawn_is_not_taken_from_its_first_step(self) -> None:
        old, drawn, new = bytes([10] * 100), bytes([170] * 100), bytes([200] * 100)
        # Caption B is drawn in two steps at frames 40 and 42; its first step is within the caption change of the
        # settled caption, so only the panel still moving gives it away.
        panels = {k: old if k < 40 else drawn if k < 42 else new for k in range(0, 80)}
        begin, reference = trim_stages.caption_start(panels, 35, 1, 80, 30, "b", 32, True, 80)
        self.assertEqual(begin, 42)
        self.assertEqual(reference, new)

    def test_a_panel_that_changes_twice_after_the_clock_fails_naming_the_clock(self) -> None:
        # A caption change and a second one 10 frames later, both after the clock: which of them brings the stage's
        # caption is not something the picture can tell, so the cut stops instead of guessing.
        panels = {k: bytes([10 if k < 40 else 60 if k < 50 else 110] * 100) for k in range(0, 80)}
        with self.assertRaises(SystemExit) as failure:
            trim_stages.caption_start(panels, 35, 1, 80, 30, "b", 32, False, 80)
        self.assertIn("b: the caption panel changes at frames 40 and 50", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_caption_that_changes_while_it_is_read_fails_naming_the_clock(self) -> None:
        # The panel changes 0.6 s after the marker, inside the frames the stage's caption is read from, so the clock
        # start may still show the caption before it.
        panels = {k: bytes([10 if k < 53 else 110] * 100) for k in range(0, 80)}
        panels[20] = bytes([60] * 100)
        with self.assertRaises(SystemExit) as failure:
            trim_stages.caption_start(panels, 35, 1, 80, 30, "b", 32, False, 80)
        self.assertIn("b: the caption panel changes at frame 53, 0.5 to 0.9 s after the marker", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_world_that_moves_too_fast_to_see_a_caption_change_fails_naming_the_clock(self) -> None:
        # The panel moves 3 a frame, so a step must exceed 6 to stand out from it, and two near-identical captions
        # differ by less than that: the cut cannot tell whether the clock start shows the stage's caption.
        panels = {k: bytes([(3 * k) % 256] * 100) for k in range(0, 80)}
        with self.assertRaises(SystemExit) as failure:
            trim_stages.caption_start(panels, 35, 1, 80, 30, "b", 32, True, 80)
        self.assertIn("b: the world behind the caption panel moves 3.0 a frame", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_later_clip_whose_panel_never_changed_since_the_previous_clip_fails_naming_the_clock(self) -> None:
        # The world drifts 1.6 a frame, which passes the bar for a caption that changes somewhere near the marker,
        # but no step stands out from the drift: the clock start may still show the previous clip's caption.
        panels = {k: bytes([(16 * k // 10) % 256] * 100) for k in range(0, 80)}
        with self.assertRaises(SystemExit) as failure:
            trim_stages.caption_start(panels, 35, 20, 80, 30, "b", 32, False, 80)
        self.assertIn("b: the caption panel does not change between the previous clip and frame 50", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_later_clip_whose_caption_came_before_the_clock_opens_at_the_clock(self) -> None:
        # The caption changed off film, after the previous clip ended at frame 20 and before the clock at 32.
        panels = {k: bytes([10 if k < 25 else 110] * 100) for k in range(0, 80)}
        begin, reference = trim_stages.caption_start(panels, 35, 20, 80, 30, "b", 32, False, 80)
        self.assertEqual(begin, 32)
        self.assertEqual(reference, bytes([110] * 100))

    def test_a_caption_that_comes_after_its_caption_is_read_fails_naming_the_clock(self) -> None:
        # The panel changed off film before the clock (frame 25), but the stage's caption comes only at frame 70,
        # 1.2 s after the marker: the clock start shows the previous caption, and so does the reading window.
        panels = {k: bytes([10 if k < 25 else 110 if k < 70 else 210] * 100) for k in range(0, 80)}
        with self.assertRaises(SystemExit) as failure:
            trim_stages.caption_start(panels, 35, 20, 80, 30, "b", 32, False, 80)
        self.assertIn("b: the caption panel changes at frame 70, 1.2 s after the marker", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_change_after_the_first_stage_of_a_clip_ends_belongs_to_the_next_stage(self) -> None:
        # The same panels in a clip whose first stage ends at frame 70: the change there is the next stage's caption.
        panels = {k: bytes([10 if k < 25 else 110 if k < 70 else 210] * 100) for k in range(0, 80)}
        begin, _ = trim_stages.caption_start(panels, 35, 20, 80, 30, "b", 32, False, 70)
        self.assertEqual(begin, 32)


class FirstFramesTest(unittest.TestCase):
    def test_a_clip_whose_first_frame_is_not_its_caption_fails(self) -> None:
        stages = [{"stage": "a", "startFrame": 0, "endFrameExclusive": 10}]
        panels = {0: bytes([200] * 100)}
        captions = [{"own": bytes([10] * 100), "ending": bytes([10] * 100), "measured": 0.0}]
        with mock.patch.object(trim_stages, "read_panels", return_value=panels):
            with self.assertRaises(SystemExit) as failure:
                trim_stages.check_first_frames(Path("cut.mp4"), stages, captions)
        self.assertIn("a: the clip's first frame does not show its own caption", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

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
        self.assertIn("--clock", str(failure.exception))

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


class MarkedCutsTest(unittest.TestCase):
    def test_the_marks_are_read_as_frames_of_the_exported_video(self) -> None:
        meta = {"boundaries": {"rawStartFrameIndex": 100, "gameCaptureCaptionMarks": json.dumps(
            [{"id": "a", "slot": 90, "presentUs": 1}, {"id": "b", "slot": 130, "presentUs": 2}])}}
        self.assertEqual(trim_stages.caption_marks(meta), [("a", -10), ("b", 30)])
        self.assertIsNone(trim_stages.caption_marks({"boundaries": {"rawStartFrameIndex": 100}}))

    def test_a_caption_drawn_before_the_clock_opens_the_clip_at_the_clock(self) -> None:
        clips = [[27, 94, 30, "a", 90]]
        trim_stages.marked_cuts(clips, [("a", 10)])
        self.assertEqual(clips[0][0], 27)

    def test_a_caption_drawn_after_the_clock_opens_the_clip_where_it_is_drawn(self) -> None:
        # The stage is marked before its caption is shown; the title card before it has another id, and the
        # readouts that follow with the stage's id come later than its first caption.
        clips = [[27, 94, 30, "a", 90]]
        trim_stages.marked_cuts(clips, [("intro", -40), ("a", 33), ("a", 40)])
        self.assertEqual(clips[0][0], 33)

    def test_a_repeated_stage_opens_on_the_caption_it_shows_after_the_previous_clip(self) -> None:
        # Stage a's first caption went off screen when b's came, before b's clip ended: the second clip of a opens
        # on a's new caption, not at its clock start, which still shows b.
        clips = [[10, 40, 13, "a", 36], [60, 100, 63, "b", 96], [120, 150, 123, "a", 146]]
        trim_stages.marked_cuts(clips, [("a", 5), ("b", 58), ("a", 125)])
        self.assertEqual([clip[0] for clip in clips], [10, 60, 125])

    def test_a_stage_keeps_a_caption_still_on_screen_from_before_the_previous_clip_ended(self) -> None:
        clips = [[10, 40, 13, "a", 36], [60, 100, 63, "a", 96]]
        trim_stages.marked_cuts(clips, [("a", 5)])
        self.assertEqual([clip[0] for clip in clips], [10, 60])

    def test_a_caption_id_with_the_mods_prefix_belongs_to_the_stage(self) -> None:
        clips = [[57, 100, 60, "nether", 96]]
        trim_stages.marked_cuts(clips, [("dimensionstructurevariants.nether", 70)])
        self.assertEqual(clips[0][0], 70)

    def test_a_stage_without_a_caption_of_its_id_fails_naming_the_clock(self) -> None:
        with self.assertRaises(SystemExit) as failure:
            trim_stages.marked_cuts([[57, 100, 60, "nether", 96]], [("overworld", 10), ("netherite", 58)])
        self.assertIn("nether: no caption with the stage's id", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_caption_first_drawn_after_its_clip_ends_fails_naming_the_clock(self) -> None:
        with self.assertRaises(SystemExit) as failure:
            trim_stages.marked_cuts([[57, 100, 60, "a", 96]], [("a", 100)])
        self.assertIn("a: its caption a is first drawn at frame 100, after the clip ends", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_caption_replaced_before_the_clock_start_opens_the_clip_where_it_comes_back(self) -> None:
        # Stage b's caption is shown off film at 50, a rehearsal caption replaces it at 60, and b's comes back at 80:
        # the clock start (65) shows the rehearsal.
        clips = [[10, 40, 13, "a", 36], [65, 120, 68, "b", 116]]
        trim_stages.marked_cuts(clips, [("a", 5), ("b", 50), ("rehearsal", 60), ("b", 80)])
        self.assertEqual([clip[0] for clip in clips], [10, 80])

    def test_a_caption_replaced_before_the_clock_start_and_never_shown_again_fails_naming_the_clock(self) -> None:
        with self.assertRaises(SystemExit) as failure:
            trim_stages.marked_cuts([[30, 90, 33, "b", 86]], [("b", 5), ("rehearsal", 20)])
        self.assertIn("b: no caption with the stage's id", str(failure.exception))
        self.assertIn("--clock", str(failure.exception))

    def test_a_stage_id_that_two_captions_end_in_fails_naming_the_clock(self) -> None:
        # Two mods' captions end in the same short stage id; neither may stand in for the other.
        clips = [[10, 40, 13, "first", 36], [60, 120, 63, "runway", 116], [130, 200, 133, "runway", 196]]
        with self.assertRaises(SystemExit) as failure:
            trim_stages.marked_cuts(clips, [("x.first", 5), ("ice.runway", 50), ("roller.runway", 140)])
        self.assertIn("runway: the stage's id names more than one caption (ice.runway, roller.runway)",
                      str(failure.exception))
        self.assertIn("--clock", str(failure.exception))


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
        self.assertIn("cut by the caption panel's pixels", result.stdout)
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
            world=DRIFTING)

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("a: marker frame 15, caption from frame 12", result.stdout)
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
            world=DRIFTING)

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("a: marker frame 15, caption from frame 12", result.stdout)
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

    def test_a_caption_that_comes_after_its_caption_is_read_on_a_switched_world_fails_naming_the_clock(self) -> None:
        # The world switches off film at frame 70 and caption b comes at frame 112, 1.0 s after the marker (81):
        # the clock start and the reading window both show the previous caption, similar or not.
        for name, second in [("similar", CAPTION_C_LIKE), ("unrelated", CAPTION_C)]:
            with self.subTest(name):
                directory = self.directory / name
                directory.mkdir()
                log, video, meta = make_recording(
                    directory,
                    [(CAPTION_B, 12, 111), (second, 112, 179)],
                    [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0), ("STAGE_START", "b", 2.7),
                     ("STAGE_END", "b", 5.0)],
                    world=SWITCHING)

                result = cut(log, video, meta)

                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("b: the caption panel changes at frame 112", result.stderr)
                self.assertIn("--clock", result.stderr)

    def test_a_similar_caption_after_an_off_film_scene_change_opens_where_it_appears(self) -> None:
        # Captions B and C_LIKE differ by one small box, the log clock is 0.3 s early as on a screen grab, and the
        # world behind the panel turns into another picture off film at frame 70: the clock start (78) still shows B
        # in the new world and must not open clip b.
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_B, 12, 89), (CAPTION_C_LIKE, 90, 179)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0), ("STAGE_START", "b", 2.7),
             ("STAGE_END", "b", 5.0)],
            world=SWITCHING)

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("b: marker frame 81, caption from frame 90", result.stdout)
        stages = json.loads(video.with_name("cut.stages.json").read_text())
        self.assertGreater(brightness(video.with_name("cut.mp4"), stages[1]["startFrame"], C_ONLY), 200,
                           "clip b must open on caption C")

    def test_near_identical_captions_on_a_drifting_world_open_where_the_new_one_appears(self) -> None:
        # The drift (0.8 to 1.0 a frame) is a fifth of the change between B and C_LIKE: the step still stands out.
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_B, 12, 89), (CAPTION_C_LIKE, 90, 179)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0), ("STAGE_START", "b", 2.7),
             ("STAGE_END", "b", 5.0)],
            world=DRIFTING)

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("a: marker frame 15, caption from frame 12", result.stdout)
        self.assertIn("b: marker frame 81, caption from frame 90", result.stdout)
        stages = json.loads(video.with_name("cut.stages.json").read_text())
        self.assertGreater(brightness(video.with_name("cut.mp4"), stages[1]["startFrame"], C_ONLY), 200,
                           "clip b must open on caption C")

    def test_a_world_too_fast_to_tell_near_identical_captions_apart_fails_naming_the_clock(self) -> None:
        # At 1 cycle a second the world moves the panel about 3.4 a frame, as much as B and C_LIKE differ.
        log, video, meta = make_recording(
            self.directory,
            [(CAPTION_B, 12, 89), (CAPTION_C_LIKE, 90, 179)],
            [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0), ("STAGE_START", "b", 2.7),
             ("STAGE_END", "b", 5.0)],
            world=drifting(1.0))

        result = cut(log, video, meta)

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("the world behind the caption panel moves", result.stderr)
        self.assertIn("--clock", result.stderr)

    def test_a_game_capture_is_cut_at_its_caption_marks_without_reading_the_picture(self) -> None:
        # A 640x360 take, which the caption panel's pixels cannot be read from: the marks alone place the cut.
        # Each frame's brightness is twice its index, so a clip's first frame tells which source frame it is.
        video = self.directory / "take.mp4"
        subprocess.run([
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", f"nullsrc=size=640x360:rate={RATE}:duration=4,geq=lum='2*N':cb=128:cr=128",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
            "-c:v", "libx264", "-preset", "ultrafast", "-qp", "0", "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-shortest", str(video),
        ], check=True)
        log = self.directory / "run.log"
        log.write_text("".join(f"{kind} {START_MS + round(at * 1000)} {stage}\n" for kind, stage, at in [
            ("STAGE_START", "a", 0.5), ("STAGE_END", "a", 1.5), ("STAGE_START", "b", 2.0), ("STAGE_END", "b", 3.5)]))
        # The export starts at the raw capture's frame 100. Caption a was drawn before its clock (raw 110, frame
        # 10), b only after its marker (raw 165, frame 65), as a test that marks the stage before it shows the
        # caption draws it; the readout b2 that follows has another id.
        marks = [{"id": "intro", "slot": 90, "presentUs": 0}, {"id": "a", "slot": 110, "presentUs": 0},
                 {"id": "b", "slot": 165, "presentUs": 0}, {"id": "b2", "slot": 170, "presentUs": 0}]
        meta = self.directory / "take.json"
        meta.write_text(json.dumps({"fps": str(RATE), "boundaries": {
            "selectedStartEpochSeconds": START_MS / 1000.0, "rawStartFrameIndex": 100,
            "gameCaptureCaptionMarks": json.dumps(marks)}}))

        result = cut(log, video, meta)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("cut by the game capture's caption marks", result.stdout)
        self.assertIn("b: marker frame 60, caption b drawn from frame 65, clip from frame 65", result.stdout)
        stages = json.loads(video.with_name("cut.stages.json").read_text())
        self.assertEqual(stages, [{"stage": "a", "startFrame": 0, "endFrameExclusive": 37},
                                  {"stage": "b", "startFrame": 37, "endFrameExclusive": 81}])
        box = (0, 0, 64, 64)
        # One source frame is 2.3 brighter than the one before it, as the gray read sees it.
        self.assertAlmostEqual(brightness(video.with_name("cut.mp4"), 0, box), brightness(video, 12, box), delta=1)
        self.assertAlmostEqual(brightness(video.with_name("cut.mp4"), 37, box), brightness(video, 65, box), delta=1)

        by_clock = cut(log, video, meta, "--clock")
        self.assertEqual(by_clock.returncode, 0, by_clock.stdout + by_clock.stderr)
        self.assertIn("cut by the log's clock", by_clock.stdout)
        self.assertEqual(json.loads(video.with_name("cut.stages.json").read_text())[1]["startFrame"], 37)

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

    def test_the_sound_stays_under_its_frame_across_a_capture_gap(self) -> None:
        # A screen grab that missed 1 s of frames: frame 60 was captured at 3.0 s, when a tone starts. In the cut
        # every frame lasts 1/30 s, so frame 60, the clip's 49th frame, plays at 1.6 s, and the tone must start there,
        # not at 2.6 s, where the take's own clock would put it.
        video = self.directory / "take.mp4"
        subprocess.run([
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", f"testsrc2=size=320x240:rate={RATE}:duration=5",
            "-f", "lavfi", "-i", "aevalsrc='if(gte(t,3),0.5*sin(2*PI*440*t),0)':d=6:s=48000",
            "-vf", "setpts='(N+if(gte(N,60),30,0))/30/TB'", "-fps_mode", "passthrough",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", str(video),
        ], check=True)
        log = self.directory / "run.log"
        log.write_text(f"STAGE_START {START_MS + 500} a\nSTAGE_END {START_MS + 4000} a\n")
        meta = self.directory / "take.json"
        meta.write_text(json.dumps({"fps": str(RATE), "boundaries": {"selectedStartEpochSeconds": START_MS / 1000.0}}))

        result = cut(log, video, meta, "--clock")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("clip 0: frames 12 - 94", result.stdout)
        out = video.with_name("cut.mp4")
        self.assertLess(loudness(out, 1.40, 1.55), 0.01, "the tone must not start before frame 60")
        self.assertGreater(loudness(out, 1.65, 1.80), 0.2, "the tone must start under frame 60")

    def test_the_raw_capture_is_refused(self) -> None:
        log, video, meta = make_recording(
            self.directory, [], [("STAGE_START", "a", 0.5), ("STAGE_END", "a", 2.0)], seconds=1.0)
        meta.write_text(json.dumps({"rawCapture": str(video), "boundaries": {"selectedStartEpochSeconds": 1000.0}}))

        result = cut(log, video, meta)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not the raw capture", result.stderr)


if __name__ == "__main__":
    unittest.main()
