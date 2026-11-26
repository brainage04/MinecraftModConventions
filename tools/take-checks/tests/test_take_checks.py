from __future__ import annotations

import csv
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


TOOLS = Path(__file__).resolve().parents[1]
SCRIPT = TOOLS / "take_checks.py"
CONFIG = TOOLS / "take-checks.toml"
CHECK_IDS = (
    "P1", "P2", "P3", "P4", "P5", "P6", "I1", "I3", "I4", "C4", "M5", "M1", "M2", "M3",
    "Path", "Work size", "N3", "H1", "H2", "H3", "H4", "H5", "B1", "B6", "B3", "B4", "B5",
    "M4", "S1", "S2",
)
BASE_STREAMS = {
    "stage_start", "stage_end", "chat", "command_typed", "command_sent", "block_change",
    "hud", "marker", "goal_reached", "failure", "inventory", "screen_step",
    "setting_change", "action_intent", "action", "damage", "retry", "landing",
    "death", "stall", "wrong_result", "movement_cause", "work_plan", "planned_path",
    "frame", "audio",
}
REQUIRED_FIELDS = {
    "tick", "pos_x", "pos_y", "pos_z", "vel_x", "vel_y", "vel_z", "yaw", "pitch",
    "on_ground", "sneaking", "fall_distance", "health", "selected_slot", "selected_item",
    "selected_item_count", "forward", "back", "left", "right", "jump", "attack", "use",
    "below_block", "feet_block", "head_block", "feet_fluid", "hit_type", "hit_block_x",
    "hit_block_y", "hit_block_z", "hit_entity_type", "target_block",
}
BASE_FIELDS = ["tick", "seconds", *sorted(REQUIRED_FIELDS - {"tick"})]


def tick(number: int, seconds: float, **changes) -> dict[str, object]:
    row: dict[str, object] = {
        "tick": number,
        "seconds": seconds,
        "pos_x": 0.0,
        "pos_y": -60.0,
        "pos_z": 0.0,
        "vel_x": 0.0,
        "vel_y": 0.0,
        "vel_z": 0.0,
        "yaw": 0.0,
        "pitch": 0.0,
        "on_ground": 1,
        "in_water": 0,
        "swimming": 0,
        "on_climbable": 0,
        "sneaking": 0,
        "fall_distance": 0.0,
        "health": 20.0,
        "selected_slot": 0,
        "selected_item": "minecraft:air",
        "selected_item_count": 0,
        "selected_item_damage": 0,
        "forward": 0,
        "back": 0,
        "left": 0,
        "right": 0,
        "jump": 0,
        "attack": 0,
        "use": 0,
        "below_block": "minecraft:stone",
        "feet_block": "minecraft:air",
        "head_block": "minecraft:air",
        "feet_fluid": "minecraft:empty",
        "hit_type": "MISS",
        "hit_block_x": "",
        "hit_block_y": "",
        "hit_block_z": "",
        "hit_entity_type": "",
        "target_block": "minecraft:air",
    }
    row.update(changes)
    return row


def steady_rows(end: float = 2.0, step: float = 0.25) -> list[dict[str, object]]:
    count = round(end / step)
    return [tick(index, index * step) for index in range(count + 1)]


def event(event_type: str, number: int, seconds: float, **fields) -> dict[str, object]:
    return {"type": event_type, "tick": number, "seconds": seconds, **fields}


def boundaries(end: float = 2.0, stage_id: str = "stage") -> list[dict[str, object]]:
    return [
        event("stage_start", 0, 0.0, stage_id=stage_id),
        event("stage_end", round(end * 4), end, stage_id=stage_id),
    ]


def hud(number: int = 0, seconds: float = 0.0, **changes) -> dict[str, object]:
    fields = {
        "element": "panel",
        "rect": [0, 0, 100, 20],
        "screen": [0, 0, 640, 360],
        "text": "ready",
        "scale": 3,
        "truncated": False,
        "values": {},
    }
    fields.update(changes)
    return event("hud", number, seconds, **fields)


class TakeChecksTest(unittest.TestCase):
    def run_case(self, rows, events, *, streams=None, role=None, config_text=None):
        stream_names = set(BASE_STREAMS if streams is None else streams)
        manifest = event("manifest", 0, 0.0, streams=sorted(stream_names), take_id="take")
        if role:
            manifest["role"] = role
        all_events = [manifest, *events]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ticks_path = root / "ticks.csv"
            events_path = root / "events.jsonl"
            config_path = root / "take-checks.toml"
            extras = sorted({key for row in rows for key in row} - set(BASE_FIELDS))
            base_fields = [field for field in BASE_FIELDS
                           if field == "tick" or any(field in row for row in rows)]
            with ticks_path.open("w", newline="", encoding="utf-8") as output:
                writer = csv.DictWriter(output, fieldnames=[*base_fields, *extras])
                writer.writeheader()
                writer.writerows(rows)
            events_path.write_text("".join(json.dumps(item) + "\n" for item in all_events), encoding="utf-8")
            config_path.write_text(config_text if config_text is not None else CONFIG.read_text(encoding="utf-8"),
                                   encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--ticks", str(ticks_path), "--events", str(events_path),
                 "--config", str(config_path)],
                text=True, capture_output=True, check=False,
            )
        verdicts = {}
        for check_id in CHECK_IDS:
            for line in completed.stdout.splitlines():
                for status in ("PASS", "FAIL", "NOT EVALUATED"):
                    prefix = f"{status} {check_id} "
                    if line.startswith(prefix):
                        verdicts[check_id] = (status, line[len(prefix):])
        return completed, verdicts

    def assert_verdict(self, verdicts, check_id, expected):
        self.assertIn(check_id, verdicts)
        self.assertEqual(expected, verdicts[check_id][0], verdicts[check_id][1])

    def test_p1_late_action_fails_and_early_action_passes(self):
        rows = steady_rows()
        late = self.run_case(rows, [*boundaries(), event("chat", 4, 1.0, text="hello")])[1]
        early = self.run_case(rows, [*boundaries(), event("chat", 1, 0.25, text="hello")])[1]
        self.assert_verdict(late, "P1", "FAIL")
        self.assert_verdict(early, "P1", "PASS")

    def test_p2_late_stop_fails_and_prompt_stop_passes(self):
        rows = steady_rows()
        failure = event("failure", 1, 0.25, reason="error")
        late = self.run_case(rows, [*boundaries(), failure])[1]
        early = self.run_case(rows, [event("stage_start", 0, 0.0, stage_id="stage"),
                                     event("stage_end", 2, 0.5, stage_id="stage"), failure])[1]
        self.assert_verdict(late, "P2", "FAIL")
        self.assert_verdict(early, "P2", "PASS")

    def test_p3_late_stop_fails_and_prompt_stop_passes(self):
        rows = steady_rows()
        goal = event("goal_reached", 1, 0.25, reason="lit")
        late = self.run_case(rows, [*boundaries(), goal])[1]
        early = self.run_case(rows, [event("stage_start", 0, 0.0, stage_id="stage"),
                                     event("stage_end", 2, 0.5, stage_id="stage"), goal])[1]
        self.assert_verdict(late, "P3", "FAIL")
        self.assert_verdict(early, "P3", "PASS")

    def test_p4_dead_time_fails_and_visible_change_passes(self):
        rows = steady_rows(6.0)
        late = self.run_case(rows, [*boundaries(6.0), hud(0, 0.0), hud(24, 6.0, text="done")])[1]
        paced = self.run_case(rows, [*boundaries(6.0), hud(0, 0.0), hud(12, 3.0, text="step"),
                                     hud(24, 6.0, text="done")])[1]
        self.assert_verdict(late, "P4", "FAIL")
        self.assert_verdict(paced, "P4", "PASS")

    def test_p4_reports_a_marked_pause_instead_of_silently_excusing_it(self):
        rows = steady_rows(8.0)
        verdicts = self.run_case(rows, [*boundaries(8.0), hud(0, 0.0), hud(32, 8.0, text="done"),
                                        event("marker", 4, 1.0, reason="deliberate pause", pause_s=6.0)])[1]
        self.assert_verdict(verdicts, "P4", "PASS")
        self.assertIn("deliberate pause", verdicts["P4"][1])

    def test_p4_hud_layout_change_does_not_hide_dead_time(self):
        rows = steady_rows(6.0)
        verdicts = self.run_case(rows, [*boundaries(6.0), hud(0, 0.0),
                                        hud(12, 3.0, rect=[200, 0, 100, 20])])[1]
        self.assert_verdict(verdicts, "P4", "FAIL")

    def test_p4_overlapping_pauses_are_subtracted_once(self):
        rows = steady_rows(12.0)
        events = [*boundaries(12.0), hud(0, 0.0), hud(48, 12.0, text="done"),
                  event("marker", 0, 0.0, reason="first", pause_s=3.0),
                  event("marker", 8, 2.0, reason="second", pause_s=4.0)]
        verdicts = self.run_case(rows, events)[1]
        self.assert_verdict(verdicts, "P4", "FAIL")

    def test_p4_two_static_hud_elements_do_not_hide_dead_time(self):
        rows = steady_rows(6.0)
        events = [*boundaries(6.0),
                  hud(0, 0.0, element="panel"),
                  hud(0, 0.0, element="status", rect=[200, 0, 100, 20]),
                  hud(12, 3.0, element="panel"),
                  hud(12, 3.0, element="status", rect=[200, 0, 100, 20]),
                  hud(24, 6.0, element="panel", text="done"),
                  hud(24, 6.0, element="status", rect=[200, 0, 100, 20], text="done")]
        self.assert_verdict(self.run_case(rows, events)[1], "P4", "FAIL")

    def test_p5_command_at_zero_without_effect_fails(self):
        command = event("command_sent", 0, 0.0, command_id="c", command="/x", source="hud")
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), command])[1], "P5", "FAIL")

    def test_p5_repeated_chat_line_counts_as_a_visible_change(self):
        command = event("command_sent", 8, 2.0, command_id="c", command="/x", source="hud")
        events = [*boundaries(4.0), event("chat", 1, 0.25, text="go"),
                  event("chat", 10, 2.5, text="go"), command]
        self.assert_verdict(self.run_case(steady_rows(4.0), events)[1], "P5", "PASS")

    def test_p5_no_op_block_change_is_not_a_visible_effect(self):
        command = event("command_sent", 8, 2.0, command_id="c", command="/x", source="hud")
        real = event("block_change", 1, 0.25, action="place", x=1, y=-60, z=0,
                     before="minecraft:air", after="minecraft:obsidian")
        no_op = event("block_change", 10, 2.5, action="place", x=2, y=-60, z=0,
                      before="minecraft:air", after="minecraft:air")
        verdicts = self.run_case(steady_rows(4.0), [*boundaries(4.0), real, no_op, command])[1]
        self.assert_verdict(verdicts, "P5", "FAIL")

    def test_p5_ignores_an_effect_after_the_stage_ends(self):
        rows = steady_rows()
        rows[4]["pos_x"] = 1.0
        command = event("command_sent", 1, 0.25, command_id="c", command="/path", source="hud")
        events = [event("stage_start", 0, 0.0, stage_id="stage"),
                  event("stage_end", 3, 0.75, stage_id="stage"), command]
        self.assert_verdict(self.run_case(rows, events)[1], "P5", "NOT EVALUATED")

    def test_rec4_dead_time_is_rejected(self):
        rows = steady_rows(70.0, 1.0)
        verdicts = self.run_case(rows, [*boundaries(70.0), hud(0, 0.0), hud(70, 70.0, text="done")])[1]
        self.assert_verdict(verdicts, "P4", "FAIL")
        self.assertIn("70.00s", verdicts["P4"][1])

    def test_p5_late_effect_fails_and_prompt_effect_passes(self):
        command = event("command_sent", 1, 0.25, command_id="c", command="/path", source="hud")
        slow_rows = steady_rows()
        slow_rows[6]["pos_x"] = 1.0
        fast_rows = steady_rows()
        fast_rows[2]["pos_x"] = 1.0
        slow = self.run_case(slow_rows, [*boundaries(), command])[1]
        fast = self.run_case(fast_rows, [*boundaries(), command])[1]
        self.assert_verdict(slow, "P5", "FAIL")
        self.assert_verdict(fast, "P5", "PASS")

    def test_p6_short_step_fails_and_half_second_step_passes(self):
        rows = steady_rows()
        short = [event("screen_step", 0, 0.0, sequence="craft", step="1", phase="start"),
                 event("screen_step", 1, 0.25, sequence="craft", step="1", phase="end")]
        exact = [event("screen_step", 0, 0.0, sequence="craft", step="1", phase="start"),
                 event("screen_step", 2, 0.5, sequence="craft", step="1", phase="end")]
        self.assert_verdict(self.run_case(rows, [*boundaries(), *short])[1], "P6", "FAIL")
        self.assert_verdict(self.run_case(rows, [*boundaries(), *exact])[1], "P6", "PASS")

    def test_i1_instant_command_fails_and_character_typing_passes(self):
        rows = steady_rows()
        sent = event("command_sent", 2, 0.5, command_id="c", command="/x", source="chat")
        typed = [event("command_typed", 1, 0.25, command_id="c", text="/"),
                 event("command_typed", 2, 0.5, command_id="c", text="/x")]
        self.assert_verdict(self.run_case(rows, [*boundaries(), sent])[1], "I1", "FAIL")
        self.assert_verdict(self.run_case(rows, [*boundaries(), sent, *typed])[1], "I1", "PASS")

    def test_i3_chat_stop_fails_and_hud_stop_passes(self):
        rows = steady_rows()
        chat = event("command_sent", 1, 0.25, command_id="c", command="path stop", source="chat")
        hud_command = event("command_sent", 1, 0.25, command_id="c", command="path stop", source="hud")
        self.assert_verdict(self.run_case(rows, [*boundaries(), chat])[1], "I3", "FAIL")
        self.assert_verdict(self.run_case(rows, [*boundaries(), hud_command])[1], "I3", "PASS")

    def test_i4_hidden_setting_fails_and_same_tick_hud_line_passes(self):
        rows = steady_rows()
        change = event("setting_change", 1, 0.25, setting="sprint", value=True)
        hidden = self.run_case(rows, [*boundaries(), change, hud(2, 0.5, text="sprint")])[1]
        shown = self.run_case(rows, [*boundaries(), change, hud(1, 0.25, text="sprint")])[1]
        self.assert_verdict(hidden, "I4", "FAIL")
        self.assert_verdict(shown, "I4", "PASS")

    def test_c4_path_m_look_fails_and_activity_during_follow_window_passes(self):
        rows = steady_rows()
        rows[1]["yaw"] = 45.0
        rows[1]["yaw_delta"] = 45.0
        still = self.run_case(rows, [*boundaries()])[1]
        active_rows = [dict(row) for row in rows]
        active_rows[2]["pos_x"] = 1.0
        active = self.run_case(active_rows, [*boundaries()])[1]
        self.assert_verdict(still, "C4", "FAIL")
        self.assert_verdict(active, "C4", "PASS")

    def test_m5_late_facing_fails_and_preaimed_target_passes(self):
        rows = [tick(0, 0.0), tick(1, 0.25, hit_type="BLOCK", hit_block_x=1, hit_block_y=-60,
                                   hit_block_z=0, hit_block="minecraft:stone", target_block="minecraft:stone"),
                tick(2, 0.5, hit_type="BLOCK", hit_block_x=1, hit_block_y=-60,
                     hit_block_z=0, hit_block="minecraft:stone", target_block="minecraft:stone")]
        intent = event("action_intent", 1, 0.25, action_id="a", target={"type": "block", "x": 1, "y": -60, "z": 0})
        action = event("action", 2, 0.5, action_id="a")
        self.assert_verdict(self.run_case(rows, [*boundaries(), intent, action])[1], "M5", "PASS")
        rows[1]["hit_type"] = "MISS"
        rows[1]["hit_block_x"] = ""
        rows[1]["hit_block_y"] = ""
        rows[1]["hit_block_z"] = ""
        self.assert_verdict(self.run_case(rows, [*boundaries(), intent, action])[1], "M5", "FAIL")

    def test_m1_sign_flip_fails_and_separated_flip_passes(self):
        close = [tick(index, index * 0.25, yaw_delta=(1.0 if index == 1 else -1.0 if index == 2 else 0.0))
                 for index in range(9)]
        separated = [tick(index, index * 0.25, yaw_delta=(1.0 if index == 1 else -1.0 if index == 5 else 0.0))
                     for index in range(9)]
        self.assert_verdict(self.run_case(close, boundaries())[1], "M1", "FAIL")
        self.assert_verdict(self.run_case(separated, boundaries())[1], "M1", "PASS")

    def hazard_rows(self, distance, adjacent=False, sneaking=False):
        rows = [tick(0, 0.0), tick(1, 0.25, pos_x=1.0)]
        for row in rows:
            row.update(drop_distance_blocks=distance, lava_distance_blocks=99.0,
                       void_distance_blocks=99.0, drop_adjacent=int(adjacent), sneaking=int(sneaking))
        return rows

    def test_m2_close_pass_fails_and_safe_pass_passes(self):
        close = self.run_case(self.hazard_rows(1.0), boundaries())[1]
        safe = self.run_case(self.hazard_rows(2.0), boundaries())[1]
        self.assert_verdict(close, "M2", "FAIL")
        self.assert_verdict(safe, "M2", "PASS")

    def test_m3_wrong_sneak_fails_and_matching_sneak_passes(self):
        wrong = self.run_case(self.hazard_rows(5.0, adjacent=True, sneaking=False), boundaries())[1]
        right = self.run_case(self.hazard_rows(5.0, adjacent=True, sneaking=True), boundaries())[1]
        self.assert_verdict(wrong, "M3", "FAIL")
        self.assert_verdict(right, "M3", "PASS")

    def test_path_detour_fails_and_direct_path_passes(self):
        detour = [tick(0, 0.0), tick(1, 0.5, pos_x=1.0), tick(2, 1.0), tick(3, 1.5, pos_x=2.0)]
        direct = [tick(0, 0.0), tick(1, 0.5, pos_x=1.0), tick(2, 1.0, pos_x=2.0)]
        self.assert_verdict(self.run_case(detour, boundaries(2.0))[1], "Path", "FAIL")
        self.assert_verdict(self.run_case(direct, boundaries(2.0))[1], "Path", "PASS")

    def test_rec4_far_portal_scaffold_fails_and_near_scaffold_passes(self):
        rows = steady_rows()
        plan = event("work_plan", 0, 0.0, stage_id="stage", lava=[0, -60, 0], required_blocks=1)
        far = event("block_change", 1, 0.25, stage_id="stage", action="place", x=5, y=-60, z=0,
                    before="minecraft:air", after="minecraft:obsidian")
        near = event("block_change", 1, 0.25, stage_id="stage", action="place", x=1, y=-60, z=0,
                     before="minecraft:air", after="minecraft:obsidian")
        self.assert_verdict(self.run_case(rows, [*boundaries(), plan, far])[1], "Work size", "FAIL")
        self.assert_verdict(self.run_case(rows, [*boundaries(), plan, near])[1], "Work size", "PASS")

    def test_work_size_rejects_more_blocks_than_the_job_needs(self):
        plan = event("work_plan", 0, 0.0, stage_id="stage", lava=[0, -60, 0], required_blocks=1)
        changes = [event("block_change", index, index * 0.25, stage_id="stage", action="place",
                         x=index, y=-60, z=0, before="minecraft:air", after="minecraft:obsidian")
                   for index in (1, 2)]
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), plan, *changes])[1],
                            "Work size", "FAIL")

    def test_n3_unexplained_movement_fails_and_input_movement_passes(self):
        unexplained = [tick(0, 0.0), tick(1, 0.25, pos_x=1.0)]
        explained = [tick(0, 0.0), tick(1, 0.25, pos_x=1.0, forward=1)]
        cause = event("movement_cause", 1, 0.25, reason="scripted movement")
        self.assert_verdict(self.run_case(unexplained, boundaries())[1], "N3", "FAIL")
        self.assert_verdict(self.run_case(explained, boundaries())[1], "N3", "PASS")
        self.assert_verdict(self.run_case(unexplained, [*boundaries(), cause])[1], "N3", "PASS")

    def test_n3_movement_one_tick_after_input_passes(self):
        rows = [tick(0, 0.0, forward=1), tick(1, 0.25, pos_x=1.0)]
        self.assert_verdict(self.run_case(rows, boundaries())[1], "N3", "PASS")

    def test_n3_attack_use_marker_and_chat_do_not_explain_movement(self):
        moved = [tick(0, 0.0), tick(1, 0.25, pos_x=1.0)]
        marker = event("marker", 1, 0.25, reason="paused", pause_s=0.0)
        chat = event("chat", 1, 0.25, text="unrelated")
        self.assert_verdict(self.run_case([tick(0, 0.0), tick(1, 0.25, pos_x=1.0, attack=1)],
                                          boundaries())[1], "N3", "FAIL")
        self.assert_verdict(self.run_case([tick(0, 0.0), tick(1, 0.25, pos_x=1.0, use=1)],
                                          boundaries())[1], "N3", "FAIL")
        self.assert_verdict(self.run_case(moved, [*boundaries(), marker])[1], "N3", "FAIL")
        self.assert_verdict(self.run_case(moved, [*boundaries(), chat])[1], "N3", "FAIL")

    def test_h1_offscreen_element_fails_and_inside_element_passes(self):
        bad = self.run_case(steady_rows(), [*boundaries(), hud(rect=[-1, 0, 100, 20])])[1]
        good = self.run_case(steady_rows(), [*boundaries(), hud()])[1]
        self.assert_verdict(bad, "H1", "FAIL")
        self.assert_verdict(good, "H1", "PASS")

    def test_h2_truncated_text_fails_and_wrapped_text_passes(self):
        bad = self.run_case(steady_rows(), [*boundaries(), hud(truncated=True)])[1]
        good = self.run_case(steady_rows(), [*boundaries(), hud(truncated=False)])[1]
        self.assert_verdict(bad, "H2", "FAIL")
        self.assert_verdict(good, "H2", "PASS")

    def test_h3_overlapping_elements_fail_and_separate_elements_pass(self):
        overlap = hud(element="left"), hud(element="right", rect=[50, 0, 100, 20])
        separate = hud(element="left"), hud(element="right", rect=[200, 0, 100, 20])
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), *overlap])[1], "H3", "FAIL")
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), *separate])[1], "H3", "PASS")

    def test_h4_mixed_scales_fail_and_one_scale_passes(self):
        mixed = hud(element="left", scale=3), hud(element="right", scale=2)
        same = hud(element="left", scale=3), hud(element="right", scale=3)
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), *mixed])[1], "H4", "FAIL")
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), *same])[1], "H4", "PASS")

    def test_h5_late_health_update_fails_and_same_tick_update_passes(self):
        damage = event("damage", 1, 0.25, amount=2.0, values={"health": 18})
        late = self.run_case(steady_rows(), [*boundaries(), damage, hud(2, 0.5, values={"health": 18})])[1]
        same = self.run_case(steady_rows(), [*boundaries(), damage, hud(1, 0.25, values={"health": 18})])[1]
        self.assert_verdict(late, "H5", "FAIL")
        self.assert_verdict(same, "H5", "PASS")

    def test_b4_clean_before_fails_and_harmed_before_passes(self):
        rows = steady_rows()
        damage = event("damage", 1, 0.25, amount=2.0, values={"health": 18})
        self.assert_verdict(self.run_case(rows, boundaries(), role="before")[1], "B4", "FAIL")
        self.assert_verdict(self.run_case(rows, [*boundaries(), damage], role="before")[1], "B4", "PASS")

    def test_b5_final_fall_fails_and_grounded_after_passes(self):
        rows = steady_rows()
        falling = [dict(row) for row in rows]
        falling[-1].update(on_ground=0, vel_y=-1.0, fall_distance=2.0)
        self.assert_verdict(self.run_case(falling, boundaries(), role="after")[1], "B5", "FAIL")
        self.assert_verdict(self.run_case(rows, boundaries(), role="after")[1], "B5", "PASS")

    def test_b5_retry_and_repeated_landing_fail(self):
        retry = event("retry", 1, 0.25, reason="again")
        landings = [event("landing", 1, 0.25, x=0, y=-60, z=0),
                    event("landing", 2, 0.5, x=0, y=-60, z=0)]
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), retry], role="after")[1], "B5", "FAIL")
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), *landings], role="after")[1], "B5", "FAIL")

    def test_m4_repeated_present_fails_and_unique_frames_pass(self):
        good = [event("frame", 0, 0.0, capture_index=0, present_id="a"),
                event("frame", 1, 0.25, capture_index=1, present_id="b")]
        repeated = [event("frame", 0, 0.0, capture_index=0, present_id="a"),
                    event("frame", 1, 0.25, capture_index=1, present_id="a")]
        gap = [event("frame", 0, 0.0, capture_index=0, present_id="a"),
               event("frame", 2, 0.5, capture_index=2, present_id="b")]
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), *good])[1], "M4", "PASS")
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), *repeated])[1], "M4", "FAIL")
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), *gap])[1], "M4", "FAIL")

    def test_s1_silent_audio_fails_and_audible_audio_passes(self):
        silent = event("audio", 0, 0.0, peak_db=-60.0)
        audible = event("audio", 0, 0.0, peak_db=-10.0)
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), silent])[1], "S1", "FAIL")
        self.assert_verdict(self.run_case(steady_rows(), [*boundaries(), audible])[1], "S1", "PASS")

    def test_missing_streams_are_not_evaluated_never_pass(self):
        streams = {"stage_start", "stage_end"}
        completed, verdicts = self.run_case(steady_rows(), boundaries(), streams=streams)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        for check_id in ("P2", "P3", "P4", "P5", "P6", "I1", "I3", "I4", "M5", "M2", "M3",
                         "Work size", "N3", "H1", "H2", "H3", "H4", "H5", "M4", "S1"):
            self.assert_verdict(verdicts, check_id, "NOT EVALUATED")
        self.assertIn("NOT EVALUATED:", completed.stdout)

    def test_config_change_changes_the_verdict(self):
        rows = steady_rows()
        events = [*boundaries(), event("chat", 4, 1.0, text="hello")]
        original = CONFIG.read_text(encoding="utf-8")
        changed = original.replace("[checks.P1]\nvalue = 0.5", "[checks.P1]\nvalue = 2.0", 1)
        self.assertNotEqual(original, changed)
        default = self.run_case(rows, events)[1]
        relaxed = self.run_case(rows, events, config_text=changed)[1]
        self.assert_verdict(default, "P1", "FAIL")
        self.assert_verdict(relaxed, "P1", "PASS")

    def test_legacy_tpm_boundaries_and_millis_clock_are_accepted(self):
        rows = steady_rows()
        for row in rows:
            row["time_millis"] = 1_000_000 + round(float(row.pop("seconds")) * 1000)
        events = [
            {"tick": 0, "time_millis": 1_000_000, "event": "start", "label": "legacy"},
            {"tick": 8, "time_millis": 1_002_000, "event": "finish", "label": "legacy"},
        ]
        completed, verdicts = self.run_case(rows, events, streams={"stage_start", "stage_end"})
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assert_verdict(verdicts, "Path", "PASS")

    def test_failing_take_exits_nonzero(self):
        completed, verdicts = self.run_case(steady_rows(), [*boundaries(), hud(rect=[-1, 0, 10, 10])])
        self.assertEqual(1, completed.returncode, completed.stdout + completed.stderr)
        self.assert_verdict(verdicts, "H1", "FAIL")


if __name__ == "__main__":
    unittest.main()
