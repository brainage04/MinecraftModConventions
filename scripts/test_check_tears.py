from __future__ import annotations

import contextlib
import io
import unittest

import check_tears


class FrameRateTest(unittest.TestCase):
    def test_the_average_rate_is_used_when_ffprobe_reports_one(self) -> None:
        self.assertEqual(check_tears.frame_rate("30/1", "60/1"), (30, 1))

    def test_a_zero_average_falls_back_to_the_real_rate(self) -> None:
        self.assertEqual(check_tears.frame_rate("0/0", "30000/1001"), (30000, 1001))

    def test_no_rate_at_all_is_an_error(self) -> None:
        with self.assertRaises(ValueError):
            check_tears.frame_rate("0/0", "0/0")


class ArgumentsTest(unittest.TestCase):
    def test_a_video_alone_or_with_repair(self) -> None:
        self.assertIsNone(check_tears.arguments(["take.mp4"]).repair)
        self.assertEqual(check_tears.arguments(["take.mp4", "--repair", "out.mp4"]).repair, "out.mp4")

    def test_anything_else_is_a_usage_error(self) -> None:
        for argv in (["take.mp4", "out.mp4"], ["take.mp4", "--fix", "out.mp4"], ["take.mp4", "--repair"], []):
            with self.subTest(argv=argv), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as exit_:
                    check_tears.arguments(argv)
                self.assertEqual(exit_.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
