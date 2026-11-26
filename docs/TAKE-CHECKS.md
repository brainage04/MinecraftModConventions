# Take checks

`tools/take-checks/take_checks.py` rejects a take before the recorder keeps it. The helper fixes the scenario and records another take when any check prints `FAIL`.

```shell
python3 tools/take-checks/take_checks.py \
  --ticks samples.csv \
  --events events.jsonl \
  --config tools/take-checks/take-checks.toml
```

The checker prints one `PASS`, `FAIL` or `NOT EVALUATED` line for every check, followed by the `NOT EVALUATED` list and its reasons. Exit 0 means no check failed. Exit 1 means at least one check failed. Exit 2 means the inputs or config are invalid.

A check with missing data never passes. The event file's `manifest` declares which streams exist. A declared but empty stream also produces `NOT EVALUATED` when the check needs samples, such as HUD rectangles or frame events. A declared stream with no relevant event can pass: a take with no `failure` events passes P2, and a take with no `goal_reached` events passes P3.

## Configuration

Every threshold lives in `tools/take-checks/take-checks.toml`. Each check has one entry with `value`, `unit` and `note`. Checks with two thresholds keep both in that entry, such as C4's `value` in degrees and `follow_s` in seconds. Changing a threshold is a one-line edit. Rule checks use `value = 1` and `unit = "rule"` because they have no numeric threshold.

P1, P2 and P3 use 0.5 s. Thomas approved that trial on 26 Nov 2026. The other plan values remain 5 s, 30 degrees, 1 block and a 1.5 path-length ratio.

## Event file

The event file is JSON lines. Each line has `tick`, `seconds` and `type`. `tick` matches the per-tick file's tick. `seconds` is relative to the take's start. Additional fields depend on the type.

The first event is a `manifest` with `streams`, the names of the event streams the recorder actually wrote. It may also have `role` (`before` or `after`), `take_id` and `pair_id`. Event-dependent checks return `NOT EVALUATED` when their streams are absent from the manifest.

```jsonl
{"type":"manifest","tick":0,"seconds":0.0,"streams":["stage_start","stage_end","chat","command_typed","command_sent","block_change","hud","marker","goal_reached","failure","inventory","screen_step","setting_change","action_intent","action","damage","retry","landing","death","stall","wrong_result","work_plan","frame","audio"],"take_id":"example"}
{"type":"stage_start","tick":0,"seconds":0.0,"stage_id":"portal"}
{"type":"chat","tick":0,"seconds":0.1,"text":"Building the portal"}
{"type":"command_typed","tick":1,"seconds":0.25,"command_id":"c1","text":"/"}
{"type":"command_typed","tick":2,"seconds":0.5,"command_id":"c1","text":"/x"}
{"type":"command_sent","tick":2,"seconds":0.5,"command_id":"c1","command":"/x","source":"hud"}
{"type":"hud","tick":2,"seconds":0.5,"element":"command","rect":[0,0,100,20],"screen":[0,0,640,360],"text":"/x","scale":3,"truncated":false,"values":{}}
{"type":"marker","tick":8,"seconds":2.0,"reason":"deliberate pause to show the frame","pause_s":1.0}
{"type":"goal_reached","tick":12,"seconds":3.0,"stage_id":"portal","reason":"portal lit"}
{"type":"stage_end","tick":13,"seconds":3.25,"stage_id":"portal"}
```

### Event types

| Type | Fields beyond `tick`, `seconds`, `type` | Used by |
| --- | --- | --- |
| `manifest` | `streams`, optional `role`, `take_id`, `pair_id` | Stream completeness, B4, B5 |
| `stage_start`, `stage_end` | `stage_id` | P1-P6, Path, Work size |
| `chat` | `text` | P1, P4, P5, I4, N3 |
| `command_typed` | `command_id`, growing `text` prefix | P1, I1 |
| `command_sent` | `command_id`, `command`, `source` (`chat` or `hud`), optional `target` | P1, P5, I1, I3, N3 |
| `block_change` | `stage_id`, `x`, `y`, `z`, `before`, `after`, `action` (`place` or `break`) | P4, P5, Work size |
| `hud` | `element`, `rect`, `screen`, `text`, `scale`, `truncated`, `values` | P4, P5, I4, H1-H5 |
| `marker` | `reason`, `pause_s` | P1, P4, N3 |
| `goal_reached`, `failure` | `stage_id`, `reason` | P2, P3 |
| `inventory` | `stage_id`, `slots` or changed item fields | P4, P5 |
| `screen_step` | `sequence`, `step`, `phase` (`start` or `end`) | P6 |
| `setting_change` | `stage_id`, `setting`, `value` | I4 |
| `action_intent`, `action` | `action_id`, `stage_id`; intent also has `target` | M5 |
| `damage` | `stage_id`, `amount`, `values` | H5, B4 |
| `retry` | `stage_id`, `reason` | B5 |
| `landing` | `stage_id`, `x`, `y`, `z` | B5 |
| `death`, `stall`, `wrong_result` | `stage_id`, `reason` | B4 |
| `movement_cause` | `stage_id`, `reason` | N3 |
| `work_plan` | `stage_id`, `lava` block coordinates, `required_blocks` | Work size |
| `planned_path` | `stage_id`, `length_blocks` or `points` | Path |
| `frame` | `capture_index`, `present_id` | M4 |
| `audio` | `peak_db` | S1 |

Rectangles use `[x, y, width, height]`. A screen rectangle uses `[0, 0, width, height]`. HUD `values` is an object such as `{"health": 18}` so H5 can compare the value on the damage event's tick without parsing display text. An action target is `{"type":"block","x":1,"y":-60,"z":0}` or `{"type":"entity","entity_type":"minecraft:zombie"}`.

A marker is a deliberate pause, not a caption. Its `reason` is required, and P4 prints every marked pause in its verdict. Overlapping pause intervals count once.

### TPM mapping

TPM's `LevelTraceRecorder` already writes `samples.csv` with the columns this checker reads. Its header lists position, velocity, rotation and deltas, input keys, attack/use, sneaking, on-ground, fall distance, health, selected inventory, nearby blocks, fluid and crosshair target (`LevelTraceRecorder.java:31-91`). The recorder writes those values once per client tick (`LevelTraceRecorder.java:224-313`).

The checker accepts TPM's current event aliases:

| TPM event | Checker event |
| --- | --- |
| `event` | `type` |
| `time_millis` | `seconds`, relative to the first event |
| `start` | `stage_start`, with the trace label as `stage_id` |
| `finish` | `stage_end`, with the trace label as `stage_id` |

TPM's current events contain only those boundaries (`LevelTraceRecorder.java:316-355`). They carry no manifest, chat, typed commands, block changes, HUD samples or scenario markers, so the checks that need those streams print `NOT EVALUATED`.

### Per-tick input

The checker requires these TPM columns:

- `tick` and one clock column: `seconds`, `time_seconds` or `time_millis`.
- `pos_x`, `pos_y`, `pos_z`, `vel_x`, `vel_y`, `vel_z`, `yaw`, `pitch`.
- `on_ground`, `sneaking`, `fall_distance`, `health`.
- `selected_slot`, `selected_item`, `selected_item_count`.
- `forward`, `back`, `left`, `right`, `jump`, `attack`, `use`.
- `below_block`, `feet_block`, `head_block`, `feet_fluid`.
- `hit_type`, `hit_block_x`, `hit_block_y`, `hit_block_z`, `hit_entity_type`, `target_block`.

`yaw_delta`, `pitch_delta`, `selected_item_damage`, `in_water`, `swimming` and `on_climbable` are optional. The checker computes missing rotation deltas and defaults the other optional values.

M2 and M3 need four additional columns from TPM instrumentation step 2 or Baritone step 3:

| Column | Meaning |
| --- | --- |
| `drop_distance_blocks` | Measured distance from the feet to the nearest drop, or `inf` when no drop exists |
| `lava_distance_blocks` | Measured distance from the feet to lava, or `inf` when no lava exists |
| `void_distance_blocks` | Measured distance from the feet to the void, or `inf` when the dimension has no void |
| `drop_adjacent` | `1` or `0`, whether a drop is adjacent to the player |

A blank hazard cell means the scan is missing, so M2 or M3 returns `NOT EVALUATED`. The checker does not infer solidity or danger from block names. The client instrumentation must measure the hazard against the loaded world.

The Baritone harness currently logs chat, console output, take durations and audio peaks, but has no per-tick sample (`record.py:230-317`). Its later client sample should write the same column names. It can use `seconds` directly, or `time_millis` with the event file's clock. The event adapter should turn the harness's chat and console lines into `chat`, `command_typed`, `command_sent`, `failure` and `goal_reached` events.

MMC's stage helper prints `STAGE_START <epoch ms> <id>` and `STAGE_END <epoch ms> <id>` (`ClientGameTestStages.java:21-53`). Convert those lines to `stage_start` and `stage_end` events on the same clock as the samples. MMC's trace writes `beforePresentUs,afterPresentUs` rows (`ClientGameTestRecordingSession.java:206-220`). A later adapter can pair those presents with captured frame indices and emit the `frame` events M4 reads.

## Automatic requirements (a take that breaks one is rejected)

| Id | Requirement | Implementation and data |
| --- | --- | --- |
| P1 | First movement, command or chat line within 0.5 s of stage start | Implemented. Needs ticks, stage boundaries and the manifest's action streams. |
| P2 | Stop within 0.5 s of a failure line | Implemented. Needs `failure` and stage end. |
| P3 | Stop within 0.5 s of goal reached | Implemented. Needs `goal_reached` and stage end. |
| P4 | No unmarked stretch over 5 s without visible change | Implemented. Needs ticks plus chat, HUD text, inventory and block-change streams. Prints marked pauses. |
| P5 | First visible command effect within 1 s | Implemented. Needs `command_sent` and visible-change streams. |
| P6 | Each craft, anvil or place step stays visible for at least 0.5 s | Implemented. Needs paired `screen_step` events. |
| I1 | Every command grows one visible character per tick | Implemented. Needs `command_typed` and `command_sent`. |
| I3 | Stop, release and cancel commands use the HUD | Implemented. Needs `command_sent.source`. |
| I4 | Every setting change has a same-tick on-screen line | Implemented. Needs `setting_change` plus HUD or chat. |
| C4 | No turn over 30 degrees followed by 1 s without activity | Implemented from tick rotation, movement, actions and crosshair target. |
| M5 | Crosshair holds the intended target at intent and action | Implemented. Needs `action_intent`, `action` and crosshair samples. |
| M1 | No rotation or speed sign flip within 3 ticks | Implemented from tick deltas. The config deadband ignores small deltas, each stage is checked separately, and speed loss while airborne is not a reversal. |
| M2 | Moving feet keep more than 1 block clearance from nearby hazards | Implemented. Needs the three measured hazard-distance columns from step 2/3. |
| M3 | Sneaking matches an adjacent drop on every tick | Implemented. Needs `drop_adjacent` from step 2/3. |
| Path | Path length stays within 1.5 times the optimum | Implemented. Uses `planned_path` when present, otherwise the straight-line distance. |
| Work size | Scaffold stays within 2 blocks of lava and adds no more blocks than needed | Implemented. Needs `work_plan` and `block_change`. |
| N3 | Movement has input or an explanation within 1 tick | Implemented. Needs ticks and explanation streams. |
| H1 | Every HUD rectangle stays inside the screen | Implemented. Needs `hud` rectangles. |
| H2 | HUD text wraps instead of truncating | Implemented. Needs `hud.truncated`. |
| H3 | Same-tick HUD rectangles do not overlap | Implemented. Needs `hud` rectangles. |
| H4 | All HUD samples use one GUI scale | Implemented. Needs `hud.scale`. |
| H5 | HUD values change on the damage event's tick | Implemented. Needs `damage.values` and `hud.values`. |
| B1 | Before and after takes start from the same blocks | Not implemented. Needs paired start snapshots and a pair runner from step 3. |
| B6 | Before and after takes use the same position and command | Not implemented. Needs paired start positions and commands from step 3. |
| B3 | Before and after takes have different visible outcomes | Not implemented. Needs paired outcomes from step 3. |
| B4 | Before take shows harm | Implemented for one take. Needs `role = "before"` and harm events or a health drop. |
| B5 | After take has no retries, repeated landings or final fall | Implemented for one take. Needs `role = "after"`, retry/landing streams and ticks. |
| M4 | No dropped or repeated frames | Implemented. Needs contiguous `frame.capture_index` and unique consecutive `present_id` values. |
| S1 | Film has game audio | Implemented. Needs `audio.peak_db`; below -50 dB fails. |
| S2 | Audio has no level jump at a cut | Not implemented. Needs per-cut discontinuity measurements from Baritone step 3. |

## Tests

```shell
python3 -m unittest discover -s tools/take-checks/tests -v
```

Every implemented check has a synthetic failing trace and a passing trace. The suite also covers missing streams, a config edit that changes a verdict, TPM's legacy clock and boundary aliases, marked pauses, the rec4 dead time, the Path M look and the far portal scaffold.
