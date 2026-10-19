from __future__ import annotations

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import mod_fleet


class RecordingAuditTest(unittest.TestCase):
    def test_multiloader_conventions_supply_recorder_plugin(self) -> None:
        audit = mod_fleet.recording_audit(
            {"recording": {"enabled": True}},
            "id 'io.github.brainage04.multiloader-mod-conventions'",
            '"fabric-client-gametest": ["Example"]\nClientGameTestRecorder.startRecording()',
            "uses: brainage04/MinecraftModConventions/.github/workflows/reusable-client-gametests.yml@v2.4.1",
        )

        self.assertTrue(audit["ready"])
        self.assertEqual(audit["missing"], [])


class RecordingOutputLayoutTest(unittest.TestCase):
    def test_recordings_stay_inside_timestamped_output_directory(self) -> None:
        with TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            downloads = root / "Downloads"
            output = downloads / "minecraft-mod-gametest-recordings-20260727-120000"
            workspace = root / "workspace"
            (workspace / "ExampleMod").mkdir(parents=True)

            manifest = {
                "repositories": [
                    {
                        "name": "ExampleMod",
                        "recording": {"enabled": True, "profile": "showcase"},
                    }
                ]
            }
            audit = {
                "repositories": [
                    {
                        "name": "ExampleMod",
                        "recording": {"ready": True, "missing": []},
                    }
                ]
            }

            def create_recording(
                command: list[str],
                cwd: Path,
                env: dict[str, str],
                log_path: Path,
            ) -> None:
                recording_directory = Path(env["GTR_RECORDING_DIR"])
                recording_directory.mkdir(parents=True, exist_ok=True)
                (recording_directory / "examplemod-20260727-120001.mp4").write_bytes(b"video")
                (recording_directory / "examplemod-20260727-120001.json").write_text(
                    json.dumps({"video": "temporary.mp4"}),
                    encoding="utf-8",
                )

            with patch.object(mod_fleet, "run_streaming", side_effect=create_recording):
                results = mod_fleet.run_recordings(
                    manifest,
                    audit,
                    workspace,
                    output,
                    includes=set(),
                    dry_run=False,
                    fail_fast=False,
                    resume=False,
                )

            expected_video = output / "recordings" / "examplemod-20260727-120001.mp4"
            self.assertEqual(results[0]["status"], "passed")
            self.assertEqual(Path(results[0]["video"]), expected_video)
            self.assertTrue(expected_video.is_file())
            self.assertEqual(list(output.glob("*.mp4")), [])
            self.assertEqual(list(downloads.glob("*.mp4")), [])

            metadata = json.loads(
                (output / "metadata" / "examplemod-20260727-120001.json").read_text(encoding="utf-8")
            )
            self.assertEqual(Path(metadata["video"]), expected_video.resolve())
            self.assertEqual(Path(metadata["recordingDirectory"]), output.resolve())


if __name__ == "__main__":
    unittest.main()
