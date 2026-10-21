import json
from pathlib import Path
import tempfile
import unittest

from descriptors import unique_object, validate


class DescriptorContractTest(unittest.TestCase):
    def test_duplicate_keys_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
            json.loads('{"max_ticks":1,"max_ticks":2}', object_pairs_hook=unique_object)

    def validate_instance(self, **changes):
        descriptor = {
            "type": "minecraft:function",
            "function": "example:test",
            "environment": {"type": "minecraft:all_of", "definitions": []},
            "structure": "minecraftmodconventions:empty",
            "max_ticks": 1,
        }
        descriptor.update(changes)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = "common/src/gametest/resources/data/example/test_instance/test.json"
            path = root / name
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(descriptor))
            return validate(root, [name])

    def test_actual_all_of_codec_is_accepted(self):
        self.assertEqual([], self.validate_instance())

    def test_tick_bounds_types_and_unknown_fields_are_rejected(self):
        for ticks in (0, -1, 2147483648, True, "20"):
            with self.subTest(ticks=ticks):
                self.assertTrue(self.validate_instance(max_ticks=ticks))
        self.assertTrue(self.validate_instance(dimensions=[1, 2, 3]))

    def test_missing_environment_is_not_assumed_vanilla(self):
        self.assertTrue(self.validate_instance(environment="minecraft:not_registered"))

    def test_wrong_all_of_field_is_rejected(self):
        self.assertTrue(
            self.validate_instance(environment={"type": "minecraft:all_of", "environments": []})
        )

    def validate_table(self, structure, module="common", resource_module="common"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text("mod_id=example\n")
            table = f"{module}/src/gametest/gametests.txt"
            template = f"{resource_module}/src/main/resources/data/example/structure/present.nbt"
            for name in (table, template):
                (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / template).write_bytes(b"")
            option = "" if structure is None else " structure=" + structure
            (root / table).write_text(
                "# generated default or explicit template\ncase 20" + option + "\n"
            )
            return validate(root, [table, template, "gradle.properties"])

    def test_table_accepts_project_templates_and_published_empty(self):
        for structure in (None, "present", "example:present", "minecraftmodconventions:empty"):
            with self.subTest(structure=structure):
                self.assertEqual([], self.validate_table(structure))

    def test_table_rejects_unknown_external_structures(self):
        for structure in (
            "unknownmod:present",
            "fabricmoddingconventions:empty",
            "minecraftmodconventions:not_published",
        ):
            with self.subTest(structure=structure):
                errors = self.validate_table(structure)
                self.assertTrue(
                    any("gametests.txt:2: unresolved structure" in error for error in errors)
                )

    def test_table_rejects_unknown_project_paths(self):
        for structure in ("missing", "example:missing"):
            with self.subTest(structure=structure):
                self.assertTrue(self.validate_table(structure))

    def test_table_does_not_borrow_other_loader_resources(self):
        self.assertTrue(
            self.validate_table("example:present", module="fabric", resource_module="neoforge")
        )

    def test_retained_json_rejects_unknown_structure_namespaces(self):
        self.assertTrue(self.validate_instance(structure="unknownmod:present"))


if __name__ == "__main__":
    unittest.main()
