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


class VanillaIdentityScanTest(unittest.TestCase):
    patterns = mod_fleet.vanilla_identity_patterns()

    def hit_lines(self, source: str) -> list[int]:
        return [finding["line"] for finding in mod_fleet.scan_vanilla_identity(source.splitlines(), self.patterns)]

    def test_autosleep_overworld_gate_is_a_hit(self) -> None:
        self.assertEqual(
            self.hit_lines(
                "// Beds explode in the Nether and the End.\n"
                "if (level.dimension() != Level.OVERWORLD) return;\n"
            ),
            [2],
        )

    def test_data_driven_code_comments_and_strings_pass(self) -> None:
        self.assertEqual(
            self.hit_lines(
                "if (level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos).explodes()) return;\n"
                "ServerLevel end = server.getLevel(Level.END);\n"
                "boolean hub = level.dimension().equals(ModDimensions.HUB);\n"
                "// if (level.dimension() != Level.OVERWORLD) return;\n"
                'String message = "level.dimension() == Level.NETHER";\n'
            ),
            [],
        )

    def test_opt_out_needs_a_reason(self) -> None:
        self.assertEqual(
            self.hit_lines(
                "// vanilla-identity: the mod only floods the vanilla End\n"
                "if (level.dimension() != Level.END) return;\n"
                "boolean a = level.dimension() == Level.NETHER; // vanilla-identity: nether-only feature\n"
                "// vanilla-identity:\n"
                "if (level.dimension() != Level.OVERWORLD) return;\n"
            ),
            [5],
        )


class VanillaTagScanTest(unittest.TestCase):
    patterns = mod_fleet.vanilla_tag_patterns()

    def setUp(self) -> None:
        self.temporary = TemporaryDirectory()
        tags = Path(self.temporary.name) / "tags"
        for name, values in {
            "block/beds.json": ["minecraft:red_bed", "minecraft:black_bed"],
            "block/oak_logs.json": ["minecraft:oak_log"],
            "block/logs.json": ["#minecraft:oak_logs"],
            "block/mineable/pickaxe.json": ["minecraft:ice"],
            "block/beacon_base_blocks.json": ["minecraft:red_bed"],
            "item/swords.json": ["minecraft:diamond_sword"],
            "worldgen/biome/is_forest.json": ["minecraft:forest"],
        }.items():
            path = tags / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"values": values}), encoding="utf-8")
        self.index = mod_fleet.vanilla_tag_index(tags)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def scan(self, source: str) -> list[dict[str, object]]:
        return mod_fleet.scan_vanilla_tags(source.splitlines(), self.patterns, self.index)

    def test_comparisons_of_tag_listed_identities_are_hits(self) -> None:
        findings = self.scan(
            "boolean bed = block == Blocks.RED_BED;\n"
            "if (stack.is(Items.DIAMOND_SWORD)) return;\n"
            "Set<Block> logs = Set.of(Blocks.OAK_LOG);\n"
            "Set<Block> tail = Set.of(Blocks.STONE, Blocks.RED_BED);\n"
            "Set<Block> head = Set.of(Blocks.RED_BED, Blocks.STONE);\n"
            "boolean forest = key == Biomes.FOREST;\n"
            "boolean qualified = block == net.minecraft.world.level.block.Blocks.RED_BED;\n"
        )
        self.assertEqual([finding["line"] for finding in findings], [1, 2, 3, 4, 5, 6, 7])
        self.assertEqual(findings[0]["constants"], ["block tag #minecraft:beds"])
        self.assertEqual(findings[1]["constants"], ["item tag #minecraft:swords"])
        self.assertEqual(findings[2]["constants"], ["block tag #minecraft:logs", "block tag #minecraft:oak_logs"])
        self.assertEqual(findings[3]["constants"], ["block tag #minecraft:beds"])
        self.assertEqual(findings[4]["constants"], ["block tag #minecraft:beds"])
        self.assertEqual(findings[5]["constants"], ["biome tag #minecraft:is_forest"])
        self.assertEqual(findings[6]["constants"], ["block tag #minecraft:beds"])

    def test_placing_untagged_identities_and_comments_pass(self) -> None:
        self.assertEqual(
            self.scan(
                "level.setBlock(pos, Blocks.RED_BED.defaultBlockState(), 3);\n"
                "if (block == Blocks.ICE) return;\n"
                "if (block == ModBlocks.BED) return;\n"
                "Stream.of(new ItemStack(Items.ARROW, 64)).forEach(menu::add);\n"
                "// block == Blocks.RED_BED\n"
                'String message = "block == Blocks.RED_BED";\n'
            ),
            [],
        )

    def test_opt_out_needs_a_reason(self) -> None:
        findings = self.scan(
            "// vanilla-tag: the mod replaces the bed block only\n"
            "if (block == Blocks.RED_BED) return;\n"
            "// vanilla-tag:\n"
            "if (block == Blocks.BLACK_BED) return;\n"
        )
        self.assertEqual([finding["line"] for finding in findings], [4])
        self.assertTrue(findings[0]["missingReason"])


class WorldHeightScanTest(unittest.TestCase):
    patterns = mod_fleet.world_height_patterns()

    def hit_lines(self, source: str) -> list[int]:
        return [
            finding["line"]
            for finding in mod_fleet.scan_patterns(
                source.splitlines(), self.patterns, mod_fleet.WORLD_HEIGHT_OPT_OUT
            )
        ]

    def test_overworld_limits_in_height_code_are_hits(self) -> None:
        self.assertEqual(
            self.hit_lines(
                "if (y < -64) return;\n"
                "int maxY = 320;\n"
                "BlockPos top = new BlockPos(x, 320, z);\n"
                "int worldHeight = 256;\n"
                "if (sprite.getY() == 0) return;\n"
                "int width = 256;\n"
                "int height = 256;\n"
            ),
            [1, 2, 3, 4],
        )

    def test_opt_out_needs_a_reason(self) -> None:
        self.assertEqual(
            self.hit_lines(
                "if (y < -64) return; // world-height: the 1.16.1 overworld only\n"
                "// world-height: the form is 256 pixels tall\n"
                "int maxBuildHeight = 256;\n"
                "// world-height:\n"
                "if (y > 320) return;\n"
            ),
            [5],
        )

if __name__ == "__main__":
    unittest.main()
