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


if __name__ == "__main__":
    unittest.main()
