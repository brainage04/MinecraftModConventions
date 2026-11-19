from __future__ import annotations

import json
import unittest
from datetime import datetime, timezone
from pathlib import Path
from tempfile import TemporaryDirectory

import review_checklist

INLINE_STEPS = """
class ExampleClientGameTest {
    void runTest(ClientGameTestContext context) {
        try {
            assertClientState(context);
            ClientGameTestRecorder.startRecording(context);
            ClientGameTestRecorder.showStep(context, "example.kit", "Kit", "The kit is rendered in the hotbar");
            context.waitTicks(40);
            ClientGameTestRecorder.showStep(context, "example.score", "Score", "sidebar");
            context.waitTicks(50);
        } finally {
        }
    }

    /** The client shows the kit. */
    private static void assertClientState(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.player == null) {
                throw new AssertionError("Expected the iron sword in the hotbar.");
            }
        });
    }
}
"""

SCENARIO_FACTORY = """
final class Suite {
    private static final List<Scenario> SCENARIOS = List.of(
            scenario("look.basic", "look", "Look", "targeting", Suite::assertLook));

    private static void assertLook(Harness harness) {
        harness.server().runCommand("setblock 3 -60 0 minecraft:gold_block"); // a target
        harness.assertYawNear(-90.0, 0.5, "/face east");
        harness.waitUntil(client -> client.crosshairPickEntity != null, 40, "/lookatentity visible pig");
    }
}

final class Catalog {
    static final List<String> IDS = List.of("look.basic");
}
"""


def write_project(root: Path, sources: dict[str, str]) -> None:
    (root / "settings.gradle").write_text("", encoding="utf-8")
    for name, text in sources.items():
        path = root / "fabric" / "src" / "gametest" / "java" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


def step_source(root: Path, step_id: str) -> review_checklist.StepSource:
    files = review_checklist.load_sources(root)
    source = review_checklist.find_step_source(step_id, files, review_checklist.index_methods(files), root)
    assert source is not None
    return source


class StepSourceTest(unittest.TestCase):
    def test_inline_show_step_uses_following_statements_and_earlier_assertions(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root, {"ExampleClientGameTest.java": INLINE_STEPS})

            source = step_source(root, "example.kit")

        self.assertEqual([fact.text for fact in source.facts], ["Waits 40 ticks (~2 s)"])
        self.assertIn("Expects the iron sword in the hotbar.", [fact.text for fact in source.earlier])
        self.assertEqual((source.title, source.subtitle), ("Kit", "The kit is rendered in the hotbar"))

    def test_scenario_factory_resolves_method_reference_over_catalog_entry(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            write_project(root, {"Suite.java": SCENARIO_FACTORY})

            source = step_source(root, "look.basic")

        texts = [fact.text for fact in source.facts]
        self.assertTrue(source.location.endswith("Suite.java:4"))
        self.assertIn("Server runs `/setblock 3 -60 0 minecraft:gold_block`", texts)
        self.assertIn("Checks yaw near (-90.0, 0.5): /face east", texts)
        self.assertIn("Waits until /lookatentity visible pig (up to 40 ticks)", texts)
        checks = review_checklist.visual_checks(source.facts, ["Now facing east."], "Look", "targeting")
        self.assertEqual(checks[0], "Chat shows “Now facing east.” *(seen in the run log)*")
        self.assertTrue(any(check.startswith("Camera/crosshair: Checks yaw near") for check in checks))


class StepTimingTest(unittest.TestCase):
    def test_log_markers_map_to_video_time(self) -> None:
        video_zero = datetime(2026, 10, 10, 9, 41, 50, tzinfo=timezone.utc).timestamp()
        metadata = {
            "startedAt": "2026-10-10T09:41:29.321989170Z",
            "boundaries": {"selectedStartEpochSeconds": video_zero},
        }
        log = "\n".join([
            "[19:41:35] [main/INFO]: Loading Minecraft",
            "[19:41:51] [Test thread/INFO]: [STDOUT]: [CLIENT_GAMETEST_RECORDER] look.basic | Look | targeting",
            "[19:41:53] [Render thread/INFO]: [System] [CHAT] Now facing east.",
            "[19:42:20] [Test thread/INFO]: [STDOUT]: [CLIENT_GAMETEST_RECORDER] done | Done | client tests",
        ])
        with TemporaryDirectory() as directory:
            path = Path(directory) / "latest.log"
            path.write_text(log, encoding="utf-8")

            steps, _ = review_checklist.parse_steps(path, metadata, 40.0)

        self.assertEqual([(s.step_id, s.start, s.end) for s in steps], [("look.basic", 1.0, 30.0), ("done", 30.0, 40.0)])
        self.assertEqual(steps[0].chat, ["Now facing east."])


class BatchTest(unittest.TestCase):
    def test_latest_batch_is_the_directory_with_the_newest_recording(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "settings.gradle").write_text("", encoding="utf-8")
            for name, started in (("old", "2026-09-01T00:00:00Z"), ("new", "2026-10-01T00:00:00Z")):
                batch = root / "build" / "recordings" / name
                batch.mkdir(parents=True)
                (batch / f"{name}.json").write_text(
                    json.dumps({"video": str(batch / f"{name}.mp4"), "captureMode": "xvfb", "startedAt": started}),
                    encoding="utf-8",
                )

            self.assertEqual(review_checklist.latest_batch(root), root / "build" / "recordings" / "new")


if __name__ == "__main__":
    unittest.main()
