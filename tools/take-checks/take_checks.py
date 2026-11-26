#!/usr/bin/env python3
"""Reject a recorded take when an automatic requirement fails."""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import tomllib
from bisect import bisect_left
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable


CHECK_ORDER = (
    "P1", "P2", "P3", "P4", "P5", "P6", "I1", "I3", "I4", "C4",
    "M5", "M1", "M2", "M3", "Path", "Work size", "N3",
    "H1", "H2", "H3", "H4", "H5", "B1", "B6", "B3", "B4", "B5",
    "M4", "S1", "S2",
)
NOT_IMPLEMENTED = {
    "B1": "needs paired before/after start snapshots from instrumentation step 3",
    "B6": "needs paired before/after start positions and commands from instrumentation step 3",
    "B3": "needs paired before/after outcomes from instrumentation step 3",
    "S2": "needs per-cut audio discontinuity measurements from Baritone instrumentation step 3",
}
EVENT_STREAMS = {
    "stage_start", "stage_end", "chat", "command_typed", "command_sent", "block_change", "hud", "marker",
    "goal_reached", "failure", "inventory", "screen_step", "setting_change",
    "action_intent", "action", "damage", "retry", "landing", "death", "stall",
    "wrong_result", "movement_cause", "work_plan", "planned_path", "frame", "audio",
}
REQUIRED_TICK_COLUMNS = {
    "tick", "pos_x", "pos_y", "pos_z", "vel_x", "vel_y", "vel_z", "yaw",
    "pitch", "on_ground", "sneaking", "fall_distance", "health", "selected_slot",
    "selected_item", "selected_item_count", "forward", "back", "left", "right",
    "jump", "attack", "use", "below_block", "feet_block", "head_block",
    "feet_fluid", "hit_type", "hit_block_x", "hit_block_y", "hit_block_z",
    "hit_entity_type", "target_block",
}
HAZARD_COLUMNS = {
    "drop_distance_blocks", "lava_distance_blocks", "void_distance_blocks",
    "drop_adjacent",
}
MOVEMENT_KEYS = ("forward", "back", "left", "right", "jump")
STOP_WORDS = {"stop", "cancel", "release"}
SECONDARY_CONFIG = {
    "C4": ("follow_s",),
    "M1": ("deadband",),
    "M2": ("clearance_blocks",),
    "Work size": ("max_extra_blocks",),
    "B5": ("max_landings",),
}


class InputError(ValueError):
    pass


@dataclass(slots=True)
class Tick:
    tick: int
    seconds: float
    x: float
    y: float
    z: float
    vx: float
    vy: float
    vz: float
    yaw: float
    pitch: float
    yaw_delta: float
    pitch_delta: float
    on_ground: bool
    in_water: bool
    swimming: bool
    on_climbable: bool
    sneaking: bool
    fall_distance: float
    health: float
    selected_slot: int
    selected_item: str
    selected_item_count: int
    selected_item_damage: int
    keys: tuple[bool, ...]
    attack: bool
    use: bool
    below_block: str
    feet_block: str
    head_block: str
    feet_fluid: str
    hit_type: str
    hit_block_x: int | None
    hit_block_y: int | None
    hit_block_z: int | None
    hit_entity_type: str
    target_block: str
    drop_distance: float | None
    lava_distance: float | None
    void_distance: float | None
    drop_adjacent: bool | None

    @property
    def position(self) -> tuple[float, float, float]:
        return self.x, self.y, self.z

    @property
    def speed(self) -> float:
        return math.hypot(self.vx, self.vz)

    @property
    def rotation(self) -> tuple[float, float]:
        return self.yaw, self.pitch

    @property
    def inventory(self) -> tuple[int, str, int, int]:
        return self.selected_slot, self.selected_item, self.selected_item_count, self.selected_item_damage

    @property
    def target(self) -> tuple[Any, ...]:
        return (
            self.hit_type,
            self.hit_block_x,
            self.hit_block_y,
            self.hit_block_z,
            self.hit_entity_type,
            self.target_block,
        )


@dataclass(slots=True)
class Event:
    tick: int
    seconds: float
    type: str
    data: dict[str, Any]
    index: int

    def get(self, key: str, default: Any = None) -> Any:
        return self.data.get(key, default)


@dataclass(slots=True)
class Stage:
    stage_id: str
    start: Event
    end: Event | None
    end_seconds: float


@dataclass(slots=True)
class Take:
    ticks: list[Tick]
    events: list[Event]
    stages: list[Stage]
    streams: set[str]
    role: str | None
    take_id: str | None
    pair_id: str | None
    start_seconds: float
    end_seconds: float
    tick_hazards: bool

    @property
    def duration(self) -> float:
        return max(0.0, self.end_seconds - self.start_seconds)

    def events_of(self, event_type: str) -> list[Event]:
        return [event for event in self.events if event.type == event_type]

    def has_streams(self, *names: str) -> bool:
        return all(name in self.streams for name in names)


@dataclass(slots=True)
class Result:
    status: str
    detail: str
    reason: str = ""


@dataclass(slots=True)
class Config:
    checks: dict[str, dict[str, Any]] = field(default_factory=dict)

    def value(self, check_id: str, key: str = "value") -> float:
        return float(self.checks[check_id][key])


def finite_float(value: Any, label: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as error:
        raise InputError(f"{label} must be numeric") from error
    if not math.isfinite(result):
        raise InputError(f"{label} must be finite")
    return result


def event_number(event: Event, key: str, default: Any = None) -> float:
    value = event.get(key, default)
    if value is None:
        raise InputError(f"event {event.index} needs numeric {key}")
    return finite_float(value, f"event {event.index} {key}")


def number(row: dict[str, str], key: str, default: float = 0.0) -> float:
    value = row.get(key, "")
    return default if value == "" else finite_float(value, f"tick column {key}")


def integer(row: dict[str, str], key: str, default: int = 0) -> int:
    value = row.get(key, "")
    return default if value == "" else int(finite_float(value, f"tick column {key}"))


def boolean(row: dict[str, str], key: str, default: bool = False) -> bool:
    value = row.get(key, "").strip().lower()
    if value == "":
        return default
    if value in {"1", "true"}:
        return True
    if value in {"0", "false"}:
        return False
    raise InputError(f"tick column {key} has invalid boolean {value!r}")


def optional_number(row: dict[str, str], key: str) -> float | None:
    value = row.get(key, "")
    return None if value == "" else finite_float(value, f"tick column {key}")


def optional_integer(row: dict[str, str], key: str) -> int | None:
    value = row.get(key, "")
    return None if value == "" else int(finite_float(value, f"tick column {key}"))


def load_config(path: Path) -> Config:
    with path.open("rb") as source:
        document = tomllib.load(source)
    rows = document.get("checks")
    if not isinstance(rows, dict):
        raise InputError(f"{path}: missing [checks] table")
    missing = [check_id for check_id in CHECK_ORDER if check_id not in rows]
    if missing:
        raise InputError(f"{path}: missing check entries: {', '.join(missing)}")
    for check_id, row in rows.items():
        if check_id not in CHECK_ORDER:
            raise InputError(f"{path}: unknown check entry {check_id!r}")
        for key in ("value", "unit", "note"):
            if key not in row:
                raise InputError(f"{path}: [checks.{check_id}] is missing {key}")
        finite_float(row["value"], f"{path}: [checks.{check_id}].value")
        for key in SECONDARY_CONFIG.get(check_id, ()):
            if key not in row:
                raise InputError(f"{path}: [checks.{check_id}] is missing {key}")
            finite_float(row[key], f"{path}: [checks.{check_id}].{key}")
    return Config(dict(rows))


def event_origin(events: list[Event]) -> float | None:
    millis = [finite_float(event.data["time_millis"], f"event {event.index} time_millis")
              for event in events if "time_millis" in event.data]
    return min(millis) if millis else None


def load_events(path: Path) -> list[Event]:
    events: list[Event] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            data = json.loads(line)
        except json.JSONDecodeError as error:
            raise InputError(f"{path}:{line_number}: invalid JSON: {error.msg}") from error
        if not isinstance(data, dict):
            raise InputError(f"{path}:{line_number}: event must be a JSON object")
        event_type = data.get("type", data.get("event"))
        if not isinstance(event_type, str) or not event_type:
            raise InputError(f"{path}:{line_number}: event needs type")
        if event_type in {"start", "finish"}:
            stage_id = str(data.get("label", "take"))
            data["stage_id"] = stage_id
            event_type = "stage_start" if event_type == "start" else "stage_end"
        try:
            tick = int(data["tick"])
        except (KeyError, TypeError, ValueError) as error:
            raise InputError(f"{path}:{line_number}: event needs an integer tick") from error
        events.append(Event(tick, 0.0, event_type, data, len(events)))
    origin = event_origin(events)
    for event in events:
        if "seconds" in event.data:
            event.seconds = finite_float(event.data["seconds"], f"event {event.index} seconds")
        elif "time_millis" in event.data and origin is not None:
            event.seconds = (finite_float(event.data["time_millis"], f"event {event.index} time_millis") - origin) / 1000.0
        else:
            raise InputError(f"{path}: event {event.index} needs seconds or time_millis")
        if event.type == "marker" and not str(event.get("reason", "")).strip():
            raise InputError(f"{path}: marker event {event.index} needs a reason")
    return sorted(events, key=lambda event: (event.seconds, event.tick, event.index))


def load_ticks(path: Path, origin_millis: float | None) -> tuple[list[Tick], bool]:
    parsed_rows: list[tuple[Tick, bool, bool]] = []
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        columns = set(reader.fieldnames or ())
        missing = sorted(REQUIRED_TICK_COLUMNS - columns)
        if missing:
            raise InputError(f"{path}: missing tick columns: {', '.join(missing)}")
        has_seconds = "seconds" in columns or "time_seconds" in columns
        if not has_seconds and "time_millis" not in columns:
            raise InputError(f"{path}: ticks need seconds, time_seconds or time_millis")
        rows = list(reader)
        first_tick_millis = None
        if not has_seconds and rows:
            first_tick_millis = finite_float(rows[0]["time_millis"], f"{path}: first time_millis")
        clock_origin = origin_millis if origin_millis is not None else first_tick_millis
        for line_number, row in enumerate(rows, 2):
            try:
                if "seconds" in row:
                    seconds = finite_float(row["seconds"], f"{path}:{line_number}: seconds")
                elif "time_seconds" in row:
                    seconds = finite_float(row["time_seconds"], f"{path}:{line_number}: time_seconds")
                else:
                    if clock_origin is None:
                        raise InputError(f"{path}:{line_number}: cannot resolve tick seconds")
                    seconds = (finite_float(row["time_millis"], f"{path}:{line_number}: time_millis")
                               - clock_origin) / 1000.0
                yaw = number(row, "yaw")
                pitch = number(row, "pitch")
                yaw_delta = optional_number(row, "yaw_delta")
                pitch_delta = optional_number(row, "pitch_delta")
                hit_x = optional_integer(row, "hit_block_x")
                hit_y = optional_integer(row, "hit_block_y")
                hit_z = optional_integer(row, "hit_block_z")
                drop_adjacent = row.get("drop_adjacent", "")
                parsed_rows.append((Tick(
                    tick=integer(row, "tick"),
                    seconds=seconds,
                    x=number(row, "pos_x"),
                    y=number(row, "pos_y"),
                    z=number(row, "pos_z"),
                    vx=number(row, "vel_x"),
                    vy=number(row, "vel_y"),
                    vz=number(row, "vel_z"),
                    yaw=yaw,
                    pitch=pitch,
                    yaw_delta=yaw_delta if yaw_delta is not None else 0.0,
                    pitch_delta=pitch_delta if pitch_delta is not None else 0.0,
                    on_ground=boolean(row, "on_ground"),
                    in_water=boolean(row, "in_water"),
                    swimming=boolean(row, "swimming"),
                    on_climbable=boolean(row, "on_climbable"),
                    sneaking=boolean(row, "sneaking"),
                    fall_distance=number(row, "fall_distance"),
                    health=number(row, "health"),
                    selected_slot=integer(row, "selected_slot"),
                    selected_item=row.get("selected_item", ""),
                    selected_item_count=integer(row, "selected_item_count"),
                    selected_item_damage=integer(row, "selected_item_damage"),
                    keys=tuple(boolean(row, key) for key in MOVEMENT_KEYS),
                    attack=boolean(row, "attack"),
                    use=boolean(row, "use"),
                    below_block=row.get("below_block", ""),
                    feet_block=row.get("feet_block", ""),
                    head_block=row.get("head_block", ""),
                    feet_fluid=row.get("feet_fluid", ""),
                    hit_type=row.get("hit_type", ""),
                    hit_block_x=hit_x,
                    hit_block_y=hit_y,
                    hit_block_z=hit_z,
                    hit_entity_type=row.get("hit_entity_type", ""),
                    target_block=row.get("target_block", ""),
                    drop_distance=optional_number(row, "drop_distance_blocks"),
                    lava_distance=optional_number(row, "lava_distance_blocks"),
                    void_distance=optional_number(row, "void_distance_blocks"),
                    drop_adjacent=None if drop_adjacent == "" else boolean(row, "drop_adjacent"),
                ), yaw_delta is not None, pitch_delta is not None))
            except (TypeError, ValueError) as error:
                raise InputError(f"{path}:{line_number}: invalid tick value: {error}") from error
    parsed_rows.sort(key=lambda item: (item[0].seconds, item[0].tick))
    previous_yaw: float | None = None
    previous_pitch: float | None = None
    for tick, yaw_explicit, pitch_explicit in parsed_rows:
        if not yaw_explicit:
            tick.yaw_delta = wrap_degrees(tick.yaw - previous_yaw) if previous_yaw is not None else 0.0
        if not pitch_explicit:
            tick.pitch_delta = tick.pitch - previous_pitch if previous_pitch is not None else 0.0
        previous_yaw, previous_pitch = tick.yaw, tick.pitch
    return [item[0] for item in parsed_rows], HAZARD_COLUMNS <= columns


def build_stages(events: list[Event], take_end: float) -> list[Stage]:
    starts = events_of_type(events, "stage_start")
    ends = events_of_type(events, "stage_end")
    stages: list[Stage] = []
    used_ends: set[int] = set()
    for start in starts:
        stage_id = str(start.get("stage_id", ""))
        if not stage_id:
            raise InputError(f"stage_start event {start.index} needs stage_id")
        end = next((event for event in ends if event.index not in used_ends
                    and event.index > start.index and event.seconds >= start.seconds
                    and str(event.get("stage_id", "")) == stage_id), None)
        if end is not None:
            used_ends.add(end.index)
        stages.append(Stage(stage_id, start, end, end.seconds if end else take_end))
    return stages


def load_take(ticks_path: Path, events_path: Path) -> Take:
    events = load_events(events_path)
    origin = event_origin(events)
    ticks, tick_hazards = load_ticks(ticks_path, origin)
    if not ticks:
        raise InputError(f"{ticks_path}: tick stream is empty")
    if not events:
        raise InputError(f"{events_path}: event stream is empty")
    manifests = [event for event in events if event.type == "manifest"]
    if len(manifests) > 1:
        raise InputError(f"{events_path}: expected one manifest event")
    manifest = manifests[0] if manifests else None
    streams = set()
    role = take_id = pair_id = None
    if manifest:
        raw_streams = manifest.get("streams")
        if not isinstance(raw_streams, list) or not all(isinstance(item, str) for item in raw_streams):
            raise InputError(f"{events_path}: manifest streams must be a list of names")
        unknown = set(raw_streams) - EVENT_STREAMS - {"ticks"}
        if unknown:
            raise InputError(f"{events_path}: unknown manifest streams: {', '.join(sorted(unknown))}")
        streams = set(raw_streams)
        role = manifest.get("role")
        take_id = manifest.get("take_id")
        pair_id = manifest.get("pair_id")
        if role not in (None, "before", "after"):
            raise InputError(f"{events_path}: manifest role must be before or after")
    start_seconds = min([ticks[0].seconds, events[0].seconds])
    end_seconds = max([ticks[-1].seconds, events[-1].seconds])
    stages = build_stages(events, end_seconds)
    return Take(ticks, events, stages, streams, role, take_id, pair_id,
                start_seconds, end_seconds, tick_hazards)


def events_of_type(events: Iterable[Event], event_type: str) -> list[Event]:
    return [event for event in events if event.type == event_type]


def wrap_degrees(value: float) -> float:
    while value <= -180.0:
        value += 360.0
    while value > 180.0:
        value -= 360.0
    return value


def distance(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    return math.dist(a, b)


def tick_signature(tick: Tick) -> tuple[Any, ...]:
    return (
        tick.position,
        tick.rotation,
        tick.inventory,
        tick.below_block,
        tick.feet_block,
        tick.head_block,
        tick.feet_fluid,
        tick.attack,
        tick.use,
    )


def event_signature(event: Event) -> str:
    if event.type == "chat":
        visible = {"text": event.get("text", "")}
    elif event.type == "hud":
        visible = {
            "element": event.get("element", ""),
            "text": event.get("text", ""),
            "values": event.get("values", {}),
        }
    elif event.type == "block_change":
        visible = {key: event.get(key) for key in ("x", "y", "z", "before", "after", "action")}
    else:
        visible = {key: value for key, value in event.data.items()
                   if key not in {"tick", "seconds", "time_millis", "type", "event", "index"}}
    return json.dumps(visible, sort_keys=True, separators=(",", ":"))


def visible_change_times(take: Take) -> list[float]:
    changes: list[float] = []
    previous = None
    last_event_signatures: dict[tuple[str, str], str] = {}
    for tick in take.ticks:
        signature = tick_signature(tick)
        if previous is not None and signature != previous:
            changes.append(tick.seconds)
        previous = signature
    for event in take.events:
        if event.type == "chat":
            if not is_command_echo(event):
                changes.append(event.seconds)
        elif event.type == "hud":
            key = ("hud", str(event.get("element", "")))
            signature = event_signature(event)
            if last_event_signatures.get(key) != signature:
                changes.append(event.seconds)
                last_event_signatures[key] = signature
        elif event.type == "inventory":
            signature = event_signature(event)
            key = ("inventory", "")
            if last_event_signatures.get(key) != signature:
                changes.append(event.seconds)
                last_event_signatures[key] = signature
        elif event.type == "block_change" and event.get("before") != event.get("after"):
            changes.append(event.seconds)
    return sorted(set(changes))


def is_command_echo(event: Event) -> bool:
    text = str(event.get("text", "")).strip()
    return text.startswith("/")


def pause_intervals(take: Take) -> list[tuple[float, float, str]]:
    pauses = []
    for event in take.events_of("marker"):
        duration = event.get("pause_s", event.get("duration_s"))
        if duration is None:
            raise InputError(f"marker event {event.index} needs pause_s")
        duration = finite_float(duration, f"marker event {event.index} pause_s")
        if duration < 0.0:
            raise InputError(f"marker event {event.index} has a negative pause_s")
        pauses.append((event.seconds, event.seconds + duration, str(event.get("reason", ""))))
    return pauses


def overlap_seconds(start: float, end: float, intervals: Iterable[tuple[float, float, str]]) -> float:
    merged: list[list[float]] = []
    for pause_start, pause_end, _ in sorted(intervals):
        if merged and pause_start <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], pause_end)
        else:
            merged.append([pause_start, pause_end])
    return sum(max(0.0, min(end, pause_end) - max(start, pause_start))
               for pause_start, pause_end in merged)


def result(check_id: str, status: str, detail: str, take: Take, reason: str = "") -> Result:
    return Result(status, f"{detail}; analyzed {take.duration:.2f}s", reason or (detail if status == "NOT EVALUATED" else ""))


def missing(take: Take, reason: str) -> Result:
    return result("", "NOT EVALUATED", reason, take, reason)


def check_p1(take: Take, config: Config) -> Result:
    if not take.stages:
        return missing(take, "missing stream stage_start")
    if not take.has_streams("chat", "command_typed", "command_sent"):
        return missing(take, "missing event manifest streams chat, command_typed, command_sent")
    threshold = config.value("P1")
    worst_delay = -1.0
    worst_stage = ""
    action_events = [event for event in take.events
                     if event.type in {"chat", "command_typed", "command_sent"}]
    for stage in take.stages:
        first_action: float | None = None
        for event in action_events:
            if stage.start.seconds <= event.seconds <= stage.end_seconds:
                first_action = event.seconds if first_action is None else min(first_action, event.seconds)
        previous = None
        for tick in take.ticks:
            if not (stage.start.seconds <= tick.seconds <= stage.end_seconds):
                previous = None
                continue
            moved = previous is not None and tick.position != previous.position
            if moved or tick.attack or tick.use:
                first_action = tick.seconds if first_action is None else min(first_action, tick.seconds)
            previous = tick
        if first_action is None:
            return result("P1", "FAIL", f"stage {stage.stage_id} has no action before {stage.end_seconds:.2f}s", take)
        delay = first_action - stage.start.seconds
        if delay > worst_delay:
            worst_delay, worst_stage = delay, stage.stage_id
    if worst_delay > threshold:
        return result("P1", "FAIL", f"stage {worst_stage} first action at {worst_delay:.2f}s, limit {threshold:.2f}s", take)
    return result("P1", "PASS", f"slowest first action {worst_delay:.2f}s in stage {worst_stage}, limit {threshold:.2f}s", take)


def matching_stage(take: Take, event: Event) -> Stage | None:
    stage_id = event.get("stage_id")
    candidates = ([stage for stage in take.stages if stage_id is not None
                   and stage.stage_id == str(stage_id)]
                  or [stage for stage in take.stages
                      if stage.start.seconds <= event.seconds <= stage.end_seconds])
    return max(candidates, key=lambda stage: (stage.start.seconds, stage.start.index), default=None)


def stop_delay(take: Take, event: Event, threshold: float) -> tuple[str, float, float]:
    stage = matching_stage(take, event)
    stop = stage.end if stage and stage.end else None
    stop_time = stop.seconds if stop else take.end_seconds
    delay = stop_time - event.seconds
    status = "PASS" if delay <= threshold else "FAIL"
    return status, delay, stop_time


def check_stop(take: Take, config: Config, check_id: str, event_type: str, stream_name: str) -> Result:
    if not take.has_streams(stream_name, "stage_end"):
        return missing(take, f"missing stream {stream_name} or stage_end")
    if not take.stages or any(stage.end is None for stage in take.stages):
        return missing(take, "stage_end is missing for a stage")
    threshold = config.value(check_id)
    events = take.events_of(event_type)
    if not events:
        return result(check_id, "PASS", f"no {event_type} events", take)
    worst = max(((stop_delay(take, event, threshold), event) for event in events),
                key=lambda item: item[0][1])
    (status, delay, stop_time), event = worst
    detail = (f"slowest stop {delay:.2f}s at {stop_time:.2f}s after {event_type} "
              f"at {event.seconds:.2f}s, limit {threshold:.2f}s")
    return result(check_id, status, detail, take)


def check_p4(take: Take, config: Config) -> Result:
    required = ("chat", "hud", "block_change", "inventory", "marker")
    if not take.has_streams(*required):
        absent = ", ".join(name for name in required if name not in take.streams)
        return missing(take, f"missing streams {absent}")
    if not take.stages:
        return missing(take, "missing stream stage_start")
    threshold = config.value("P4")
    pauses = pause_intervals(take)
    changes = visible_change_times(take)
    worst = 0.0
    worst_start = take.start_seconds
    for stage in take.stages:
        boundaries = [stage.start.seconds]
        boundaries.extend(time for time in changes if stage.start.seconds < time < stage.end_seconds)
        boundaries.append(stage.end_seconds)
        for start, end in zip(boundaries, boundaries[1:]):
            active = end - start - overlap_seconds(start, end, pauses)
            if active > worst:
                worst, worst_start = active, start
    pause_text = ", ".join(f"{start:.2f}-{end:.2f}s {reason}" for start, end, reason in pauses) or "none"
    detail = f"longest unmarked gap {worst:.2f}s at {worst_start:.2f}s, limit {threshold:.2f}s; marked pauses: {pause_text}"
    return result("P4", "FAIL" if worst > threshold else "PASS", detail, take)


def check_p5(take: Take, config: Config) -> Result:
    if not take.has_streams("command_sent", "chat", "hud", "block_change", "inventory"):
        return missing(take, "missing command_sent, chat, hud, block_change or inventory stream")
    threshold = config.value("P5")
    commands = take.events_of("command_sent")
    if not commands:
        return result("P5", "PASS", "no command_sent events", take)
    changes = visible_change_times(take)
    worst_delay = -1.0
    worst_command = ""
    incomplete: tuple[str, str] | None = None
    for command in commands:
        stage = matching_stage(take, command)
        available_end = stage.end_seconds if stage else take.end_seconds
        effect = next((time for time in changes if command.seconds <= time <= available_end), None)
        if effect is None:
            if available_end - command.seconds < threshold:
                if incomplete is None:
                    incomplete = (f"take ends before the {threshold:.2f}s effect window at {available_end:.2f}s",
                                  f"command at {command.seconds:.2f}s has no complete effect window")
                continue
            return result("P5", "FAIL", f"command at {command.seconds:.2f}s has no visible effect by {available_end:.2f}s", take)
        delay = effect - command.seconds
        if delay > worst_delay:
            worst_delay, worst_command = delay, str(command.get("command", ""))
    if worst_delay > threshold:
        return result("P5", "FAIL", f"command {worst_command!r} first effect at {worst_delay:.2f}s, limit {threshold:.2f}s", take)
    if incomplete is not None:
        detail, reason = incomplete
        return result("P5", "NOT EVALUATED", detail, take, reason)
    return result("P5", "PASS", f"slowest command effect {worst_delay:.2f}s, limit {threshold:.2f}s", take)


def check_p6(take: Take, config: Config) -> Result:
    if not take.has_streams("screen_step"):
        return missing(take, "missing stream screen_step")
    threshold = config.value("P6")
    open_steps: dict[tuple[str, str], Event] = {}
    durations: list[float] = []
    for event in take.events_of("screen_step"):
        sequence, step = event.get("sequence"), event.get("step")
        if not all(isinstance(value, str) and value.strip() for value in (sequence, step)):
            return result("P6", "NOT EVALUATED", f"screen step at {event.seconds:.2f}s has no sequence and step", take,
                          "screen_step needs sequence and step")
        phase = event.get("phase")
        if phase not in {"start", "end"}:
            return result("P6", "NOT EVALUATED", f"screen step at {event.seconds:.2f}s has phase {phase!r}", take,
                          "screen_step phase must be start or end")
        key = (sequence, step)
        if phase == "start":
            if key in open_steps:
                return result("P6", "NOT EVALUATED", f"screen step {key} starts twice", take,
                              "screen_step has a duplicate open start")
            open_steps[key] = event
        elif key in open_steps:
            durations.append(event.seconds - open_steps.pop(key).seconds)
        else:
            return result("P6", "FAIL", f"screen step {key} ends at {event.seconds:.2f}s without a start", take)
    shortest = min(durations, default=math.inf)
    if shortest < threshold:
        return result("P6", "FAIL", f"shortest screen step {shortest:.2f}s, minimum {threshold:.2f}s", take)
    if open_steps:
        key, event = next(iter(open_steps.items()))
        if take.end_seconds - event.seconds < threshold:
            return result("P6", "NOT EVALUATED", f"screen step {key} has no complete {threshold:.2f}s window", take,
                          f"screen step {key} is still open at take end")
        return result("P6", "FAIL", f"screen step {key} never ends after {event.seconds:.2f}s", take)
    if not durations:
        return result("P6", "PASS", "no screen_step events", take)
    return result("P6", "PASS", f"shortest screen step {shortest:.2f}s, minimum {threshold:.2f}s", take)


def check_i1(take: Take, config: Config) -> Result:
    if not take.has_streams("command_typed", "command_sent"):
        return missing(take, "missing command_typed or command_sent stream")
    typed: dict[str, list[Event]] = {}
    for event in take.events_of("command_typed"):
        typed.setdefault(str(event.get("command_id", "")), []).append(event)
    commands = take.events_of("command_sent")
    for command in commands:
        command_id = str(command.get("command_id", ""))
        text = command.get("command")
        if not isinstance(text, str) or not text:
            return result("I1", "NOT EVALUATED", f"command {command_id} at {command.seconds:.2f}s has no command text", take,
                          "command_sent.command is missing")
        events = typed.get(command_id, [])
        expected = [text[:index] for index in range(1, len(text) + 1)]
        actual = [str(event.get("text", "")) for event in events]
        ticks = [event.tick for event in events]
        if (actual != expected or any(right <= left for left, right in zip(ticks, ticks[1:]))
                or (ticks and ticks[-1] > command.tick)):
            return result("I1", "FAIL", f"command {command_id} at {command.seconds:.2f}s is not one visible character per tick", take)
    return result("I1", "PASS", f"checked {len(commands)} typed commands", take)


def check_i3(take: Take, config: Config) -> Result:
    if not take.has_streams("command_sent"):
        return missing(take, "missing stream command_sent")
    commands = take.events_of("command_sent")
    for command in commands:
        command_text = command.get("command")
        if not isinstance(command_text, str) or not command_text.strip():
            return result("I3", "NOT EVALUATED", f"command at {command.seconds:.2f}s has no command text", take,
                          "command_sent.command is missing")
        text = command_text.lstrip("/#.").lower().split()
        if not STOP_WORDS.intersection(text):
            continue
        source = command.get("source")
        if source not in {"hud", "chat"}:
            return result("I3", "NOT EVALUATED", f"command at {command.seconds:.2f}s has no source", take,
                          "command_sent source is missing")
        if source != "hud":
            return result("I3", "FAIL", f"stop command {command.get('command')!r} at {command.seconds:.2f}s came from chat", take)
    return result("I3", "PASS", f"checked {len(commands)} commands", take)


def check_i4(take: Take, config: Config) -> Result:
    if not take.has_streams("setting_change"):
        return missing(take, "missing stream setting_change")
    if not take.has_streams("hud") and not take.has_streams("chat"):
        return missing(take, "missing on-screen hud or chat stream")
    changes = take.events_of("setting_change")
    for change in changes:
        setting = change.get("setting")
        if not isinstance(setting, str) or not setting.strip():
            return result("I4", "NOT EVALUATED", f"setting change at {change.seconds:.2f}s has no setting name", take,
                          "setting_change.setting is missing")
        shown = False
        wrong_value = False
        for event in take.events:
            if event.tick != change.tick:
                continue
            if event.type == "hud":
                values = event.get("values", {})
                if isinstance(values, dict) and setting in values:
                    shown = True
                    if "value" in change.data and values[setting] != change.get("value"):
                        wrong_value = True
                shown = shown or setting in str(event.get("text", ""))
            elif event.type == "chat":
                shown = shown or setting in str(event.get("text", ""))
        if wrong_value:
            return result("I4", "FAIL", f"HUD value for {setting!r} at {change.seconds:.2f}s does not match the setting change", take)
        if not shown:
            return result("I4", "FAIL", f"setting {setting!r} at {change.seconds:.2f}s has no same-tick on-screen line", take)
    return result("I4", "PASS", f"checked {len(changes)} setting changes", take)


def check_c4(take: Take, config: Config) -> Result:
    angle_limit = config.value("C4")
    follow = config.value("C4", "follow_s")
    runs: list[list[Tick]] = []
    current: list[Tick] = []
    for tick in take.ticks:
        if math.hypot(tick.yaw_delta, tick.pitch_delta) != 0.0:
            current.append(tick)
        elif current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    qualifying = [run for run in runs if sum(math.hypot(tick.yaw_delta, tick.pitch_delta) for tick in run) > angle_limit]
    if not qualifying:
        return result("C4", "PASS", f"no head turn over {angle_limit:.2f} deg", take)
    for run in qualifying:
        last = run[-1]
        window_end = last.seconds + follow
        containing = next((group for group in stage_tick_groups(take)
                           if group[0].seconds <= last.seconds <= group[-1].seconds), None)
        available_end = containing[-1].seconds if containing else take.ticks[-1].seconds
        if available_end < window_end:
            return result("C4", "NOT EVALUATED", f"turn ending at {last.seconds:.2f}s has no complete {follow:.2f}s follow window", take,
                          f"incomplete follow window after turn at {last.seconds:.2f}s")
        active = False
        previous = last.position
        previous_target = last.target
        for tick in take.ticks:
            if not (last.seconds < tick.seconds <= window_end):
                continue
            if tick.position != previous or tick.attack or tick.use or tick.target != previous_target:
                active = True
                break
            previous = tick.position
            previous_target = tick.target
        if not active:
            return result("C4", "FAIL", f"{sum(math.hypot(tick.yaw_delta, tick.pitch_delta) for tick in run):.2f} deg turn ending at {last.seconds:.2f}s has no activity for {follow:.2f}s", take)
    return result("C4", "PASS", f"checked {len(qualifying)} turns over {angle_limit:.2f} deg", take)


def target_matches(tick: Tick, target: dict[str, Any]) -> bool:
    kind = target.get("type")
    if kind == "block":
        return (tick.hit_type == "BLOCK"
                and (tick.hit_block_x, tick.hit_block_y, tick.hit_block_z)
                == (target.get("x"), target.get("y"), target.get("z")))
    if kind == "entity":
        return tick.hit_type == "ENTITY" and tick.hit_entity_type == target.get("entity_type")
    return False


def target_complete(target: dict[str, Any]) -> bool:
    kind = target.get("type")
    if kind == "block":
        return all(isinstance(target.get(key), (int, float)) and not isinstance(target.get(key), bool)
                   for key in ("x", "y", "z"))
    if kind == "entity":
        return isinstance(target.get("entity_type"), str) and bool(target["entity_type"])
    return True


def check_m5(take: Take, config: Config) -> Result:
    if not take.has_streams("action_intent", "action"):
        return missing(take, "missing action_intent or action stream")
    intents = take.events_of("action_intent")
    action_events = take.events_of("action")
    actions: dict[str, Event] = {}
    for event in action_events:
        action_id = event.get("action_id")
        if not isinstance(action_id, str) or not action_id or action_id in actions:
            return result("M5", "NOT EVALUATED", f"action at {event.seconds:.2f}s has a missing or duplicate action_id", take,
                          "action.action_id is missing or duplicated")
        actions[action_id] = event
    for action in action_events:
        if not any(str(intent.get("action_id", "")) == str(action.get("action_id", "")) for intent in intents):
            return result("M5", "NOT EVALUATED", f"action at {action.seconds:.2f}s has no action_intent", take,
                          "action has no matching action_intent")
    by_tick = {tick.tick: tick for tick in take.ticks}
    for intent in intents:
        action = actions.get(str(intent.get("action_id", "")))
        if action is None:
            return result("M5", "NOT EVALUATED", f"intent at {intent.seconds:.2f}s has no matching action event", take,
                          "action_intent has no matching action")
        if intent.tick not in by_tick or action.tick not in by_tick:
            return result("M5", "NOT EVALUATED", f"action {intent.get('action_id')!r} has no tick samples at intent and action", take,
                          "action tick sample is missing")
        target = intent.get("target")
        if not isinstance(target, dict):
            return result("M5", "NOT EVALUATED", f"intent at {intent.seconds:.2f}s has no target", take,
                          "action_intent target is missing")
        if not target_complete(target):
            return result("M5", "NOT EVALUATED", f"intent at {intent.seconds:.2f}s has an incomplete target", take,
                          "action_intent target fields are incomplete")
        if not target_matches(by_tick[intent.tick], target):
            return result("M5", "FAIL", f"target not under the crosshair at intent {intent.seconds:.2f}s", take)
        if not target_matches(by_tick[action.tick], target):
            return result("M5", "FAIL", f"target not under the crosshair at action {action.seconds:.2f}s", take)
    return result("M5", "PASS", f"checked {len(intents)} action intents", take)


def sign_flip(series: list[tuple[int, float]], window_ticks: int, deadband: float) -> tuple[int, float] | None:
    previous: tuple[int, float] | None = None
    for item in series:
        if abs(item[1]) <= deadband:
            continue
        if previous and (previous[1] > 0.0) != (item[1] > 0.0) and item[0] - previous[0] <= window_ticks:
            return item[0], item[1]
        previous = item
    return None


def stage_tick_groups(take: Take) -> list[list[Tick]]:
    if not take.stages:
        return [take.ticks]
    groups: list[list[Tick]] = [[] for _ in take.stages]
    for tick in take.ticks:
        index = next((position for position, stage in enumerate(take.stages)
                      if stage.start.seconds <= tick.seconds <= stage.end_seconds), None)
        if index is not None:
            groups[index].append(tick)
    return groups


def check_m1(take: Take, config: Config) -> Result:
    window = int(config.value("M1"))
    deadband = config.value("M1", "deadband")
    flips: list[tuple[int, float]] = []
    for ticks in stage_tick_groups(take):
        yaw = [(tick.tick, tick.yaw_delta) for tick in ticks]
        pitch = [(tick.tick, tick.pitch_delta) for tick in ticks]
        speed = [(current.tick, current.speed - previous.speed)
                 for previous, current in zip(ticks, ticks[1:])
                 if not (not previous.on_ground and not current.on_ground and current.speed < previous.speed)]
        flips.extend(flip for flip in (sign_flip(yaw, window, deadband),
                                       sign_flip(pitch, window, deadband),
                                       sign_flip(speed, window, deadband)) if flip)
    if flips:
        tick_number, value = min(flips, key=lambda item: item[0])
        return result("M1", "FAIL", f"rotation or speed sign flip at tick {tick_number} ({value:.6f}) within {window} ticks", take)
    return result("M1", "PASS", f"no rotation or speed sign flip within {window} ticks", take)


def check_m2(take: Take, config: Config) -> Result:
    if not take.tick_hazards:
        return missing(take, "missing tick hazard distances from instrumentation step 2/3")
    hazard_range = config.value("M2")
    clearance = config.value("M2", "clearance_blocks")
    worst = math.inf
    worst_time = take.start_seconds
    previous = None
    for tick in take.ticks:
        if any(value is None for value in (tick.drop_distance, tick.lava_distance, tick.void_distance)):
            return missing(take, "a tick hazard distance is blank")
        moving = previous is not None and tick.position != previous.position
        previous = tick
        if not moving:
            continue
        distances = [value for value in (tick.drop_distance, tick.lava_distance, tick.void_distance)
                     if value <= hazard_range]
        if distances and min(distances) <= clearance:
            if min(distances) < worst:
                worst, worst_time = min(distances), tick.seconds
    if worst != math.inf:
        return result("M2", "FAIL", f"moving feet {worst:.2f} blocks from a hazard at {worst_time:.2f}s, clearance {clearance:.2f}", take)
    return result("M2", "PASS", f"moving clearance stays above {clearance:.2f} blocks for hazards within {hazard_range:.2f}", take)


def check_m3(take: Take, config: Config) -> Result:
    if not take.tick_hazards:
        return missing(take, "missing adjacent-drop scan from instrumentation step 2/3")
    for tick in take.ticks:
        if tick.drop_adjacent is None:
            return missing(take, "drop_adjacent is blank in the tick hazard stream")
        if tick.sneaking != tick.drop_adjacent:
            return result("M3", "FAIL", f"sneaking={tick.sneaking} at {tick.seconds:.2f}s but adjacent drop={tick.drop_adjacent}", take)
    return result("M3", "PASS", "sneaking matches adjacent drops on every tick", take)


def planned_length(event: Event) -> float | None:
    if "length_blocks" in event.data:
        return event_number(event, "length_blocks")
    points = event.data.get("points")
    if not isinstance(points, list) or len(points) < 2:
        return None
    return sum(distance(tuple(finite_float(value, f"planned_path event {event.index} point") for value in points[index - 1]),
                        tuple(finite_float(value, f"planned_path event {event.index} point") for value in points[index]))
               for index in range(1, len(points)))


def check_path(take: Take, config: Config) -> Result:
    if not take.stages:
        return missing(take, "missing stream stage_start")
    limit = config.value("Path")
    plans = {str(event.get("stage_id", "")): event for event in take.events_of("planned_path")}
    worst_ratio = 0.0
    worst_stage = ""
    worst_kind = "straight line"
    incomplete: tuple[str, str] | None = None
    for stage in take.stages:
        samples = [tick for tick in take.ticks if stage.start.seconds <= tick.seconds <= stage.end_seconds]
        if len(samples) < 2:
            if incomplete is None:
                incomplete = (f"stage {stage.stage_id} has fewer than two tick samples",
                              f"stage {stage.stage_id} has insufficient tick samples")
            continue
        actual = sum(distance(left.position, right.position) for left, right in zip(samples, samples[1:]))
        plan = plans.get(stage.stage_id)
        optimum = planned_length(plan) if plan else distance(samples[0].position, samples[-1].position)
        stage_kind = "planned path" if plan else "straight line"
        if optimum is None or optimum <= 0.0:
            if actual == 0.0:
                continue
            if incomplete is None:
                incomplete = (f"stage {stage.stage_id} has no positive path optimum",
                              f"stage {stage.stage_id} optimum is missing or zero")
            continue
        ratio = actual / optimum
        if ratio > worst_ratio:
            worst_ratio, worst_stage, worst_kind = ratio, stage.stage_id, stage_kind
    if worst_ratio > limit:
        return result("Path", "FAIL", f"stage {worst_stage} length ratio {worst_ratio:.2f} uses {worst_kind}, limit {limit:.2f}", take)
    if incomplete is not None:
        detail, reason = incomplete
        return result("Path", "NOT EVALUATED", detail, take, reason)
    return result("Path", "PASS", f"worst length ratio {worst_ratio:.2f}, limit {limit:.2f}", take)


def block_position(event: Event) -> tuple[float, float, float]:
    try:
        return event_number(event, "x"), event_number(event, "y"), event_number(event, "z")
    except (KeyError, TypeError, ValueError) as error:
        raise InputError(f"block_change event {event.index} needs numeric x, y and z") from error


def check_work_size(take: Take, config: Config) -> Result:
    if not take.has_streams("work_plan", "block_change"):
        return missing(take, "missing work_plan or block_change stream")
    maximum = config.value("Work size")
    extra = int(config.value("Work size", "max_extra_blocks"))
    plans = {str(event.get("stage_id", "")): event for event in take.events_of("work_plan")}
    placed: dict[str, list[Event]] = {}
    for event in take.events_of("block_change"):
        action = event.get("action")
        if action not in {"place", "break"}:
            return result("Work size", "NOT EVALUATED", f"block_change at {event.seconds:.2f}s has action {action!r}", take,
                          "block_change.action must be place or break")
        if action != "place":
            continue
        stage_id = str(event.get("stage_id", ""))
        if not stage_id:
            stage = next((stage for stage in take.stages if stage.start.seconds <= event.seconds <= stage.end_seconds), None)
            stage_id = stage.stage_id if stage else ""
        placed.setdefault(stage_id, []).append(event)
    for stage_id, events in placed.items():
        plan = plans.get(stage_id)
        if plan is None:
            return result("Work size", "NOT EVALUATED", f"stage {stage_id} has placed blocks but no work_plan", take,
                          f"stage {stage_id} work_plan is missing")
        lava = plan.get("lava")
        if not isinstance(lava, list) or len(lava) != 3:
            return result("Work size", "NOT EVALUATED", f"stage {stage_id} work_plan has no lava position", take,
                          f"stage {stage_id} lava position is missing")
        lava_position = tuple(finite_float(value, f"stage {stage_id} lava coordinate") for value in lava)
        if "required_blocks" not in plan.data:
            return result("Work size", "NOT EVALUATED", f"stage {stage_id} work_plan has no required_blocks", take,
                          f"stage {stage_id} required_blocks is missing")
        required = int(event_number(plan, "required_blocks"))
        if len(events) > required + extra:
            return result("Work size", "FAIL", f"stage {stage_id} placed {len(events)} blocks, job needs {required}", take)
        for event in events:
            separation = distance(block_position(event), lava_position)
            if separation > maximum:
                return result("Work size", "FAIL", f"stage {stage_id} scaffold block at {event.seconds:.2f}s is {separation:.2f} blocks from lava, limit {maximum:.2f}", take)
    return result("Work size", "PASS", f"checked {sum(len(events) for events in placed.values())} placed blocks", take)


def has_recent_tick(tick_number: int, samples: list[int], window: int) -> bool:
    index = bisect_left(samples, tick_number - window)
    return index < len(samples) and samples[index] <= tick_number


def check_n3(take: Take, config: Config) -> Result:
    if not take.has_streams("command_sent", "chat", "marker"):
        return missing(take, "missing command_sent, chat or marker stream")
    window = int(config.value("N3"))
    input_ticks = sorted({tick.tick for tick in take.ticks if any(tick.keys)})
    explanation_ticks = sorted({event.tick for event in take.events
                                if event.type in {"command_sent", "action_intent", "movement_cause"}})
    previous = None
    for tick in take.ticks:
        moving = previous is not None and tick.position != previous.position
        previous = tick
        if not moving:
            continue
        if any(has_recent_tick(tick.tick, samples, window)
               for samples in (input_ticks, explanation_ticks)):
            continue
        return result("N3", "FAIL", f"movement at {tick.seconds:.2f}s has no input or marked explanation within {window} ticks", take)
    return result("N3", "PASS", f"all movement has input or an explanation within {window} ticks", take)


def hud_events(take: Take) -> list[Event]:
    return take.events_of("hud")


def rectangle(event: Event, key: str) -> tuple[float, float, float, float] | None:
    value = event.get(key)
    if not isinstance(value, list) or len(value) != 4:
        return None
    try:
        return tuple(map(float, value))
    except (TypeError, ValueError):
        return None


def check_h1(take: Take, config: Config) -> Result:
    if not take.has_streams("hud"):
        return missing(take, "missing stream hud")
    events = hud_events(take)
    if not events:
        return missing(take, "hud stream declared but contains no HUD samples")
    for event in events:
        rect = rectangle(event, "rect")
        screen = rectangle(event, "screen")
        if rect is None or screen is None:
            return result("H1", "NOT EVALUATED", f"HUD event at {event.seconds:.2f}s has no rect and screen", take,
                          "HUD rectangle or screen dimensions are missing")
        x, y, width, height = rect
        _, _, screen_width, screen_height = screen
        if x < 0.0 or y < 0.0 or x + width > screen_width or y + height > screen_height:
            return result("H1", "FAIL", f"HUD element {event.get('element')!r} crosses a screen edge at {event.seconds:.2f}s", take)
    return result("H1", "PASS", f"checked {len(hud_events(take))} HUD rectangles", take)


def check_h2(take: Take, config: Config) -> Result:
    if not take.has_streams("hud"):
        return missing(take, "missing stream hud")
    events = hud_events(take)
    if not events:
        return missing(take, "hud stream declared but contains no HUD samples")
    for event in events:
        truncated = event.get("truncated")
        if not isinstance(truncated, bool):
            return result("H2", "NOT EVALUATED", f"HUD event at {event.seconds:.2f}s has no boolean truncated flag", take,
                          "hud.truncated is missing or is not a boolean")
        if truncated:
            return result("H2", "FAIL", f"HUD element {event.get('element')!r} is truncated at {event.seconds:.2f}s", take)
    return result("H2", "PASS", f"checked {len(hud_events(take))} HUD text samples", take)


def rectangles_overlap(left: tuple[float, float, float, float], right: tuple[float, float, float, float]) -> bool:
    lx, ly, lw, lh = left
    rx, ry, rw, rh = right
    return min(lx + lw, rx + rw) > max(lx, rx) and min(ly + lh, ry + rh) > max(ly, ry)


def check_h3(take: Take, config: Config) -> Result:
    if not take.has_streams("hud"):
        return missing(take, "missing stream hud")
    events = hud_events(take)
    if not events:
        return missing(take, "hud stream declared but contains no HUD samples")
    grouped: dict[int, list[Event]] = {}
    for event in events:
        grouped.setdefault(event.tick, []).append(event)
    for tick, events in grouped.items():
        for index, left in enumerate(events):
            left_rect = rectangle(left, "rect")
            if left_rect is None:
                return result("H3", "NOT EVALUATED", f"HUD rectangle missing at tick {tick}", take,
                              "HUD rectangle is missing")
            for right in events[index + 1:]:
                right_rect = rectangle(right, "rect")
                if right_rect is None:
                    return result("H3", "NOT EVALUATED", f"HUD rectangle missing at tick {tick}", take,
                                  "HUD rectangle is missing")
                if rectangles_overlap(left_rect, right_rect):
                    return result("H3", "FAIL", f"HUD elements {left.get('element')!r} and {right.get('element')!r} overlap at tick {tick}", take)
    return result("H3", "PASS", "no same-tick HUD rectangles overlap", take)


def check_h4(take: Take, config: Config) -> Result:
    if not take.has_streams("hud"):
        return missing(take, "missing stream hud")
    events = hud_events(take)
    if not events:
        return missing(take, "hud stream declared but contains no HUD samples")
    if any("scale" not in event.data for event in events):
        return missing(take, "GUI scale is missing from a HUD sample")
    scales = {event_number(event, "scale") for event in events}
    if len(scales) > 1:
        return result("H4", "FAIL", f"HUD uses GUI scales {sorted(scales)}", take)
    return result("H4", "PASS", f"one GUI scale observed: {next(iter(scales))}", take)


def check_h5(take: Take, config: Config) -> Result:
    if not take.has_streams("damage", "hud"):
        return missing(take, "missing damage or hud stream")
    damage = take.events_of("damage")
    if not hud_events(take):
        return missing(take, "hud stream declared but contains no HUD samples")
    hud_by_tick: dict[int, list[dict[str, Any]]] = {}
    for event in hud_events(take):
        values = event.get("values", {})
        if isinstance(values, dict):
            hud_by_tick.setdefault(event.tick, []).append(values)
    for event in damage:
        expected = event.get("values")
        if not isinstance(expected, dict) or not expected:
            return result("H5", "NOT EVALUATED", f"damage at {event.seconds:.2f}s has no expected HUD values", take,
                          "damage event values are missing")
        actual: dict[str, Any] = {}
        for values in hud_by_tick.get(event.tick, []):
            actual.update(values)
        for key, value in expected.items():
            if key not in actual or actual[key] != value:
                return result("H5", "FAIL", f"HUD value {key!r} did not update on damage tick {event.tick} at {event.seconds:.2f}s", take)
    return result("H5", "PASS", f"checked {len(damage)} damage events", take)


def check_b4(take: Take, config: Config) -> Result:
    if take.role != "before":
        return missing(take, "manifest role is not before")
    if not take.has_streams("damage", "death", "stall", "wrong_result", "failure"):
        return missing(take, "missing harm-event streams")
    harm_types = {"death", "stall", "wrong_result", "failure"}
    for event in take.events:
        if event.type in harm_types or (event.type == "damage" and event_number(event, "amount", 0.0) > 0.0):
            return result("B4", "PASS", f"before take shows {event.type} at {event.seconds:.2f}s", take)
    for previous, current in zip(take.ticks, take.ticks[1:]):
        if current.health < previous.health:
            return result("B4", "PASS", f"before take loses health at {current.seconds:.2f}s", take)
    return result("B4", "FAIL", "before take shows no damage, death, stall, failure or wrong result", take)


def landing_block(event: Event) -> tuple[int, int, int]:
    values: list[int] = []
    for key in ("x", "y", "z"):
        value = event.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            raise InputError(f"landing event {event.index} needs numeric {key}")
        values.append(math.floor(float(value)))
    return values[0], values[1], values[2]


def check_b5(take: Take, config: Config) -> Result:
    if take.role != "after":
        return missing(take, "manifest role is not after")
    if not take.has_streams("retry", "landing"):
        return missing(take, "missing retry or landing stream")
    retries = take.events_of("retry")
    if retries:
        return result("B5", "FAIL", f"after take retries at {retries[0].seconds:.2f}s", take)
    landings: dict[tuple[int, int, int], int] = {}
    for event in take.events_of("landing"):
        position = landing_block(event)
        landings[position] = landings.get(position, 0) + 1
    maximum = int(config.value("B5", "max_landings"))
    for position, count in landings.items():
        if count > maximum:
            return result("B5", "FAIL", f"after take lands {count} times at {position}, maximum {maximum}", take)
    for last in [group[-1] for group in stage_tick_groups(take) if group] or [take.ticks[-1]]:
        if (not last.on_ground and not last.in_water
                and (last.fall_distance > 0.0 or (last.vy < 0.0 and not last.swimming and not last.on_climbable))):
            return result("B5", "FAIL", f"after take ends in a fall at {last.seconds:.2f}s", take)
    return result("B5", "PASS", "after take has no retries, repeated landings or final fall", take)


def check_m4(take: Take, config: Config) -> Result:
    if not take.has_streams("frame"):
        return missing(take, "missing frame-present stream")
    frames = take.events_of("frame")
    if not frames:
        return missing(take, "frame stream declared but contains no frame events")
    previous_index: int | None = None
    previous_present: str | None = None
    for event in frames:
        try:
            index = int(event.data["capture_index"])
        except (KeyError, TypeError, ValueError) as error:
            raise InputError(f"frame event {event.index} needs capture_index") from error
        present = event.get("present_id")
        if not isinstance(present, str) or not present:
            raise InputError(f"frame event {event.index} needs a non-empty present_id")
        if previous_index is not None and index != previous_index + 1:
            return result("M4", "FAIL", f"capture frame index jumps from {previous_index} to {index} at {event.seconds:.2f}s", take)
        if previous_present is not None and present == previous_present:
            return result("M4", "FAIL", f"present {present} repeats at {event.seconds:.2f}s", take)
        previous_index, previous_present = index, present
    return result("M4", "PASS", f"checked {len(frames)} captured frames", take)


def check_s1(take: Take, config: Config) -> Result:
    if not take.has_streams("audio"):
        return missing(take, "missing audio summary stream")
    samples = take.events_of("audio")
    if not samples:
        return missing(take, "audio stream declared but contains no peak measurements")
    threshold = config.value("S1")
    peaks: list[float] = []
    for event in samples:
        if "peak_db" not in event.data:
            return missing(take, "audio peak_db is missing")
        value = event.data["peak_db"]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            raise InputError(f"audio event {event.index} needs a finite numeric peak_db")
        peaks.append(float(value))
    peak = max(peaks)
    if peak < threshold:
        return result("S1", "FAIL", f"audio peak {peak:.2f} dB is below {threshold:.2f} dB", take)
    return result("S1", "PASS", f"loudest of {len(peaks)} audio peaks is {peak:.2f} dB, at least {threshold:.2f} dB", take)


CHECKS = {
    "P1": check_p1,
    "P2": lambda take, config: check_stop(take, config, "P2", "failure", "failure"),
    "P3": lambda take, config: check_stop(take, config, "P3", "goal_reached", "goal_reached"),
    "P4": check_p4,
    "P5": check_p5,
    "P6": check_p6,
    "I1": check_i1,
    "I3": check_i3,
    "I4": check_i4,
    "C4": check_c4,
    "M5": check_m5,
    "M1": check_m1,
    "M2": check_m2,
    "M3": check_m3,
    "Path": check_path,
    "Work size": check_work_size,
    "N3": check_n3,
    "H1": check_h1,
    "H2": check_h2,
    "H3": check_h3,
    "H4": check_h4,
    "H5": check_h5,
    "B1": lambda take, config: missing(take, NOT_IMPLEMENTED["B1"]),
    "B6": lambda take, config: missing(take, NOT_IMPLEMENTED["B6"]),
    "B3": lambda take, config: missing(take, NOT_IMPLEMENTED["B3"]),
    "B4": check_b4,
    "B5": check_b5,
    "M4": check_m4,
    "S1": check_s1,
    "S2": lambda take, config: missing(take, NOT_IMPLEMENTED["S2"]),
}


def evaluate(take: Take, config: Config) -> list[Result]:
    return [CHECKS[check_id](take, config) for check_id in CHECK_ORDER]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ticks", required=True, type=Path, help="per-tick CSV")
    parser.add_argument("--events", required=True, type=Path, help="event JSON lines")
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("take-checks.toml"))
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        config = load_config(args.config)
        take = load_take(args.ticks, args.events)
        results = evaluate(take, config)
    except (OSError, InputError, tomllib.TOMLDecodeError) as error:
        print(f"ERROR {error}", file=sys.stderr)
        return 2
    for check_id, verdict in zip(CHECK_ORDER, results):
        print(f"{verdict.status} {check_id} {verdict.detail}")
    not_evaluated = [(check_id, verdict) for check_id, verdict in zip(CHECK_ORDER, results)
                     if verdict.status == "NOT EVALUATED"]
    if not_evaluated:
        print("NOT EVALUATED:")
        for check_id, verdict in not_evaluated:
            print(f"  {check_id}: {verdict.reason}")
    return 1 if any(verdict.status == "FAIL" for verdict in results) else 0


if __name__ == "__main__":
    sys.exit(main())
