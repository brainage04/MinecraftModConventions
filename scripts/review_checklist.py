#!/usr/bin/env python3
"""Write a REVIEW-CHECKLIST.md for a batch of client GameTest recordings.

A batch is a directory of `recordClientGameTest` outputs (`<name>-<timestamp>.mp4` plus its
`.json` metadata and, when kept, the `-run` directory) or a `mod_fleet.py record` output
directory. Each recording is cut into the steps announced with `ClientGameTestRecorder.showStep`:
the step markers in the run log give the video timestamps, and the step id is looked up in the
mod's GameTest sources to describe what the step does and what to look for.

Nothing is built or run: the script reads metadata, logs and Java sources only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterator

SCRIPT_DIR = Path(__file__).resolve().parent
WORKSPACE = SCRIPT_DIR.parents[1]
DEFAULT_SPEED = 1.75
TICK_SECONDS_PER_ENTRY = 10  # reading "Look for" and ticking boxes, on top of the video time
TICK_SECONDS_PER_REPEAT = 3  # a step already reviewed in another recording of the batch
MARKER = "[CLIENT_GAMETEST_RECORDER] "
LOG_TIME = re.compile(r"\[(\d{2}):(\d{2}):(\d{2})\]")
CHAT = re.compile(r"\[CHAT\] (.*)$")
SKIPPED_SOURCE_SETS = {"main", "test"}  # GameTest steps live in gametest and integration suites
JAVA_KEYWORDS = {
    "if", "for", "while", "switch", "catch", "synchronized", "return", "new", "throw", "else",
    "try", "do", "super", "this", "case", "assert", "instanceof",
}
MAX_FACTS = 10
MAX_DEPTH = 2


# --------------------------------------------------------------------------------------------
# Java source scanning (heuristic, comment- and string-aware, no full parser)
# --------------------------------------------------------------------------------------------


@dataclass
class Literal:
    start: int
    end: int
    value: str


@dataclass
class JavaFile:
    path: Path
    text: str
    masked: str = ""
    literals: list[Literal] = field(default_factory=list)
    line_starts: list[int] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.masked, self.literals = mask_java(self.text)
        self.line_starts = [0] + [m.end() for m in re.finditer("\n", self.text)]

    def line(self, offset: int) -> int:
        low, high = 0, len(self.line_starts) - 1
        while low < high:
            mid = (low + high + 1) // 2
            if self.line_starts[mid] <= offset:
                low = mid
            else:
                high = mid - 1
        return low + 1

    def literals_in(self, start: int, end: int) -> list[Literal]:
        return [literal for literal in self.literals if start <= literal.start and literal.end <= end]


@dataclass
class Method:
    name: str
    file: JavaFile
    body_start: int
    body_end: int
    doc: str


def mask_java(text: str) -> tuple[str, list[Literal]]:
    """Blank out comments and literal contents (keeping offsets) and collect string literals."""
    out = list(text)
    literals: list[Literal] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            out[i:end] = " " * (end - i)
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out[i:end] = [ch if ch == "\n" else " " for ch in text[i:end]]
            i = end
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            literals.append(Literal(i, end, text[i + 3:end - 3].strip()))
            out[i + 1:end - 1] = [ch if ch == "\n" else " " for ch in text[i + 1:end - 1]]
            i = end
        elif c == '"' or c == "'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            end = min(j + 1, n)
            if c == '"':
                raw = text[i + 1:end - 1]
                literals.append(Literal(i, end, re.sub(r"\\(.)", r"\1", raw)))
            out[i + 1:end - 1] = " " * max(0, end - 1 - (i + 1))
            i = end
        else:
            i += 1
    return "".join(out), literals


def matching(masked: str, open_index: int) -> int:
    """Index of the bracket closing the one at open_index (masked text has no literals)."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    stack = [pairs[masked[open_index]]]
    for i in range(open_index + 1, len(masked)):
        ch = masked[i]
        if ch in pairs:
            stack.append(pairs[ch])
        elif ch in ")}]":
            if not stack or ch != stack[-1]:
                return i
            stack.pop()
            if not stack:
                return i
    return len(masked) - 1


def split_args(masked: str, start: int, end: int) -> list[tuple[int, int]]:
    """Top-level comma-separated argument spans inside (start, end) exclusive bounds."""
    spans, depth, begin = [], 0, start
    for i in range(start, end):
        ch = masked[i]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
        elif ch == "," and depth == 0:
            spans.append((begin, i))
            begin = i + 1
    if masked[begin:end].strip():
        spans.append((begin, end))
    return spans


def enclosing_paren(masked: str, index: int) -> int | None:
    depth = 0
    for i in range(index - 1, -1, -1):
        ch = masked[i]
        if ch in ")}]":
            depth += 1
        elif ch in "({[":
            if depth == 0:
                return i if ch == "(" else None
            depth -= 1
    return None


def identifier_before(masked: str, index: int) -> str:
    match = re.search(r"([A-Za-z_]\w*)\s*$", masked[:index])
    return match.group(1) if match else ""


# `<type> name(` where the type ends in a word, `>` or `]`; the parameters must be followed by a body.
METHOD_NAME = re.compile(r"(\w+|[>\]])\s+([a-zA-Z_]\w*)\s*\(")
METHOD_BODY = re.compile(r"\s*(?:throws\s+[\w.,\s]+?)?\s*\{")


def index_methods(files: list[JavaFile]) -> dict[str, list[Method]]:
    methods: dict[str, list[Method]] = {}
    for java in files:
        masked = java.masked
        for match in METHOD_NAME.finditer(masked):
            name = match.group(2)
            if name in JAVA_KEYWORDS or match.group(1) in JAVA_KEYWORDS:
                continue
            close_paren = matching(masked, match.end() - 1)
            after = METHOD_BODY.match(masked, close_paren + 1)
            if not after:
                continue
            body_start = after.end() - 1
            body_end = matching(masked, body_start)
            methods.setdefault(name, []).append(
                Method(name, java, body_start + 1, body_end, javadoc_before(java, match.start(2)))
            )
    return methods


def javadoc_before(java: JavaFile, offset: int) -> str:
    """The Javadoc right above the declaration whose name starts at offset, if any."""
    lines = java.text[:java.text.rfind("\n", 0, offset) + 1].rstrip().split("\n")
    while lines and lines[-1].strip().startswith("@"):
        lines.pop()
    head = "\n".join(lines).rstrip()
    if not head.endswith("*/"):
        return ""
    start = head.rfind("/**")
    if start < 0 or "*/" in head[start:-2]:
        return ""
    text = re.sub(r"^\s*\*\s?", "", head[start + 3:-2], flags=re.M)
    text = re.sub(r"\{@\w+\s+([^}]*)\}", r"`\1`", text)
    text = re.split(r"\n\s*@\w+", text)[0]
    return " ".join(text.split())


def load_sources(project: Path) -> list[JavaFile]:
    """Java files of every non-main, non-test source set (gametest, integration suites)."""
    files = []
    for directory, subdirectories, names in os.walk(project):
        subdirectories[:] = sorted(d for d in subdirectories if d not in {"build", "bin", "out", "run", ".gradle", ".git", "node_modules"})
        parts = Path(directory).relative_to(project).parts
        if "src" not in parts or parts.index("src") + 1 >= len(parts) or parts[parts.index("src") + 1] in SKIPPED_SOURCE_SETS:
            if "src" in parts and parts.index("src") + 1 < len(parts):
                subdirectories[:] = []
            continue
        for name in sorted(names):
            if name.endswith(".java"):
                path = Path(directory) / name
                try:
                    files.append(JavaFile(path, path.read_text(encoding="utf-8")))
                except (OSError, UnicodeDecodeError):
                    continue
    return files


# --------------------------------------------------------------------------------------------
# Turning test code into plain-words facts and visual checks
# --------------------------------------------------------------------------------------------


@dataclass
class Fact:
    kind: str  # setup, action, check, wait, fail, call
    text: str
    call: str
    location: str
    depth: int = 0
    labels: tuple[str, ...] = ()


@dataclass
class Body:
    java: JavaFile
    start: int
    end: int
    label: str
    doc: str = ""


SETUP = re.compile(r"^(prepare|create|spawn|summon|teleport|give|set|place|fill|configure|clean|reset|restore|switch|clear|equip)")
ACTION = re.compile(r"^(click|press|hold|release|open|close|select|use|type|key|attack|interact|move|look|jump|drop|run)(?!On|Command)")
CHECK = re.compile(r"^(assert|expect|verify|require|check|ensure)")
WAIT = re.compile(r"^(waitUntil|waitFor|await)")
COMMAND = re.compile(r"(?i)command$")
VISUAL_CATEGORIES = (
    ("Camera/crosshair", re.compile(r"(?i)look|yaw|pitch|\bface\b|crosshair|\baim|camera|toward")),
    ("Combat", re.compile(r"(?i)kill|damag|dead|death|attack|\bhit|shoot|melee|crystal|dragon|zombie|\bmobs?\b|arrow|fight|critical")),
    ("Movement", re.compile(r"(?i)\bmov|moved|path|walk|teleport|goto|sprint|jump|swim|\bfly|flight|elytra|\brid(e|ing)|horse|boat|mount|portal|travel")),
    ("Screen/menu", re.compile(r"(?i)screen|gui|menu|container|furnace|craft|anvil|beacon|trade|chest|brew|enchant|recipe|button")),
    ("Items", re.compile(r"(?i)hotbar|item|slot|equip|drop|eat|inventory|held|armor|tool")),
    ("HUD/chat", re.compile(r"(?i)hud|overlay|scoreboard|sidebar|chat|toast|title|feedback|message|bossbar|vote|goal")),
    ("World", re.compile(r"(?i)block|mine|mining|place|build|break|fill|water|lava")),
)


def humanize(name: str, prefix: re.Pattern[str] | None = None) -> str:
    if prefix:
        name = prefix.sub("", name, count=1)
    words = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", name).replace("_", " ").lower().strip()
    return words


def arg_value(java: JavaFile, start: int, end: int) -> str | None:
    """A readable value for a literal-ish argument: strings (joined), numbers, booleans."""
    masked = java.masked[start:end].strip()
    if not masked:
        return None
    literals = java.literals_in(start, end)
    if literals:
        outside = java.masked[start:end]
        for literal in literals:
            outside = outside.replace(java.masked[literal.start:literal.end], "", 1)
        if "->" in outside or "::" in outside:
            return None
        if re.fullmatch(r"[\s+]*", outside):
            return "".join(literal.value for literal in literals).strip()
        return "…".join(literal.value for literal in literals).strip()
    if re.fullmatch(r"-?\d+(\.\d+)?[dDfFlL]?|true|false", masked):
        return masked.rstrip("dDfFlL")
    return None


def describe_call(java: JavaFile, name: str, name_start: int, open_paren: int, close_paren: int, depth: int) -> Fact | None:
    args = [arg_value(java, a, b) for a, b in split_args(java.masked, open_paren + 1, close_paren)]
    strings = tuple(a for a in args if a and not re.fullmatch(r"-?\d+(\.\d+)?|true|false", a))
    numbers = [a for a in args if a and re.fullmatch(r"-?\d+(\.\d+)?|true|false", a)]
    location = f"{java.path.name}:{java.line(name_start)}"
    qualifier = java.masked[max(0, name_start - 40):name_start]
    nums = f" ({', '.join(numbers)})" if numbers else ""

    if name in {"AssertionError", "IllegalStateException", "fail"} and strings:
        message = strings[0]
        text = f"Expects {message[9:]}" if message.startswith("Expected ") else f"Fails with “{message}”"
        return Fact("fail", text, name, location, depth, strings)
    if name == "waitTicks" and numbers:
        return Fact("wait", f"Waits {numbers[0]} ticks (~{float(numbers[0]) / 20:g} s)", name, location, depth)
    if COMMAND.search(name) and not CHECK.match(name) and strings:
        who = "Server runs" if "server" in qualifier.lower() or "Server" in name else "Runs"
        return Fact("setup", f"{who} `/{strings[0].lstrip('/')}`", name, location, depth, strings)
    if CHECK.match(name):
        detail = "; ".join(strings)
        text = f"Checks {humanize(name, CHECK)}{nums}" + (f": {detail}" if detail else "")
        return Fact("check", text, name, location, depth, strings)
    if WAIT.match(name):
        what = "; ".join(strings) or humanize(name, WAIT)
        ticks = name in {"waitUntil", "waitFor"} and numbers and re.fullmatch(r"\d+", numbers[0])
        limit = f" (up to {numbers[0]} ticks)" if ticks else nums
        return Fact("check", f"Waits until {what}{limit}", name, location, depth, strings)
    if SETUP.match(name):
        detail = ", ".join(strings + tuple(numbers))
        return Fact("setup", f"Setup: {humanize(name)}" + (f" ({detail})" if detail else ""), name, location, depth, strings)
    if ACTION.match(name):
        detail = ", ".join(strings + tuple(numbers))
        return Fact("action", f"Does: {humanize(name)}" + (f" ({detail})" if detail else ""), name, location, depth, strings)
    return None


CALL = re.compile(r"(?:(::)\s*)?\b([A-Za-z_]\w*)\s*(\()?")


def scan_body(body: Body, methods: dict[str, list[Method]], depth: int, seen: set[tuple[str, int]], facts: list[Fact]) -> None:
    java, masked = body.java, body.java.masked
    position = body.start
    while position < body.end:
        match = CALL.search(masked, position, body.end)
        if not match:
            break
        position = match.end()
        method_ref, name, paren = match.group(1), match.group(2), match.group(3)
        if not method_ref and not paren:
            continue
        if name in JAVA_KEYWORDS:
            continue
        name_start = match.start(2)
        preceding = masked[max(0, name_start - 1):name_start]
        target = resolve(name, masked[:name_start], methods)
        if method_ref:
            if target and (target.name, target.body_start) not in seen and depth < MAX_DEPTH:
                expand(target, methods, depth, seen, facts, f"`{name}`")
            continue
        open_paren = match.end() - 1
        close_paren = matching(masked, open_paren)
        fact = describe_call(java, name, name_start, open_paren, close_paren, depth)
        static_call = preceding != "." or re.search(r"\b[A-Z]\w*\.\s*$", masked[max(0, name_start - 60):name_start])
        if target and static_call and (target.name, target.body_start) not in seen and depth < MAX_DEPTH:
            args = [arg_value(java, a, b) for a, b in split_args(masked, open_paren + 1, close_paren)]
            shown = ", ".join(a for a in args if a)
            expand(target, methods, depth, seen, facts, f"`{name}({shown})`" if shown else f"`{name}`")
            position = close_paren + 1
            continue
        if fact:
            facts.append(fact)
            if fact.kind in {"check", "fail"}:
                position = close_paren + 1  # don't re-describe calls inside an assertion's arguments


def resolve(name: str, before: str, methods: dict[str, list[Method]]) -> Method | None:
    candidates = methods.get(name)
    if not candidates:
        return None
    qualifier = re.search(r"\b([A-Z]\w*)\s*(?:\.|::)\s*$", before)
    if qualifier:
        for method in candidates:
            if method.file.path.stem == qualifier.group(1):
                return method
    return candidates[0]


def expand(method: Method, methods: dict[str, list[Method]], depth: int, seen: set[tuple[str, int]], facts: list[Fact], shown: str) -> None:
    seen.add((method.name, method.body_start))
    location = f"{method.file.path.name}:{method.file.line(method.body_start)}"
    summary = f" — {method.doc}" if method.doc else ""
    facts.append(Fact("call", f"Runs {shown}{summary}", method.name, location, depth))
    scan_body(Body(method.file, method.body_start, method.body_end, method.name, method.doc), methods, depth + 1, seen, facts)


@dataclass
class StepSource:
    location: str
    facts: list[Fact]
    description: str
    title: str = ""
    subtitle: str = ""
    earlier: list[Fact] = field(default_factory=list)


def step_bodies(java: JavaFile, literal: Literal) -> tuple[int, list[Body], tuple[str, str]]:
    """Score one occurrence of a step id and return the code it stands for."""
    masked = java.masked
    open_paren = enclosing_paren(masked, literal.start)
    if open_paren is None:
        return 0, [], ("", "")
    close_paren = matching(masked, open_paren)
    call_name = identifier_before(masked, open_paren)
    spans = split_args(masked, open_paren + 1, close_paren)
    texts = [arg_value(java, a, b) for a, b in spans]
    after_id = [t for (a, _), t in zip(spans, texts) if a > literal.start and t and not re.fullmatch(r"-?\d+(\.\d+)?|true|false", t)]
    bodies: list[Body] = []
    for a, b in spans:
        segment = masked[a:b]
        arrow = segment.find("->")
        if arrow >= 0:
            start = a + arrow + 2
            brace = masked.find("{", start, b)
            if brace >= 0 and not masked[start:brace].strip():
                bodies.append(Body(java, brace + 1, matching(masked, brace), "lambda"))
            else:
                bodies.append(Body(java, start, b, "lambda"))
        elif "::" in segment:
            bodies.append(Body(java, a, b, "method reference"))
    if bodies:
        return 3, bodies, title_pair(after_id)
    if "step" in call_name.lower():
        statement_end = masked.find(";", close_paren)
        block_start, block_end = enclosing_block(masked, open_paren)
        step_call = re.compile(r"\b\w*[sS]tep\s*\(")
        next_step = step_call.search(masked, statement_end + 1, block_end)
        first_step = step_call.search(masked, block_start, block_end)
        end = next_step.start() if next_step else block_end
        bodies = [Body(java, statement_end + 1, end, "following statements")]
        if first_step and first_step.start() > block_start + 1:
            bodies.append(Body(java, block_start + 1, first_step.start(), "earlier"))
        return 2, bodies, ((after_id[0] if after_id else ""), (after_id[1] if len(after_id) > 1 else ""))
    return 1, [], title_pair(after_id)


def title_pair(strings: list[str]) -> tuple[str, str]:
    if len(strings) >= 2:
        return strings[-2], strings[-1]
    return (strings[0] if strings else ""), ""


def enclosing_block(masked: str, index: int) -> tuple[int, int]:
    """Offsets of the `{` and `}` of the innermost block around index."""
    depth = 0
    for i in range(index - 1, -1, -1):
        ch = masked[i]
        if ch in ")}]":
            depth += 1
        elif ch in "({[":
            if depth == 0 and ch == "{":
                return i, matching(masked, i)
            depth = max(0, depth - 1)
    return 0, len(masked)


def find_step_source(step_id: str, files: list[JavaFile], methods: dict[str, list[Method]], project: Path) -> StepSource | None:
    best: tuple[int, JavaFile, Literal, list[Body], tuple[str, str]] | None = None
    for java in files:
        for literal in java.literals:
            if literal.value != step_id:
                continue
            score, bodies, titles = step_bodies(java, literal)
            if best is None or score > best[0]:
                best = (score, java, literal, bodies, titles)
    if best is None:
        return None
    _, java, literal, bodies, titles = best
    facts: list[Fact] = []
    earlier: list[Fact] = []
    seen: set[tuple[str, int]] = set()
    for body in bodies:
        if body.label == "earlier":
            collected: list[Fact] = []
            scan_body(body, methods, 0, seen, collected)
            earlier += [fact for fact in collected if fact.kind in {"check", "fail"}]
        else:
            scan_body(body, methods, 0, seen, facts)
    docs = [fact.text.split(" — ", 1)[1] for fact in facts if fact.kind == "call" and " — " in fact.text]
    location = f"{java.path.relative_to(project)}:{java.line(literal.start)}"
    return StepSource(location, facts, docs[0] if docs else "", *titles, earlier=earlier)


def visual_checks(facts: list[Fact], chat: list[str], title: str, subtitle: str) -> list[str]:
    """2-5 things to watch: chat lines the run printed, the step's own description, then assertions."""
    checks = [f"Chat shows “{shorten(line)}” *(seen in the run log)*" for line in distinct(chat)[:2]]
    if len(subtitle.split()) >= 4:
        checks.append(f"On screen: {subtitle} *(the step's description)*")
    ranked: list[tuple[int, int, str, Fact]] = []
    for order, fact in enumerate(facts):
        if fact.kind not in {"check", "fail"}:
            continue
        # Rank 0: the assertion itself names something visible; rank 1: only its label does.
        for rank, haystack in enumerate((humanize(fact.call), " ".join(fact.labels))):
            category = next((c for c, pattern in VISUAL_CATEGORIES if pattern.search(haystack)), None)
            if category:
                ranked.append((rank, order, category, fact))
                break
    ranked.sort(key=lambda item: item[:2])
    picked: list[tuple[int, str, Fact]] = []
    categories = {category for _, _, category, _ in ranked}
    for _, order, category, fact in ranked:
        if len(checks) + len(picked) >= 5:
            break
        if any(c == category for _, c, _ in picked) and len({c for _, c, _ in picked}) < len(categories):
            continue
        picked.append((order, category, fact))
    for _, order, category, fact in ranked:
        if len(checks) + len(picked) >= 4:
            break
        if all(f is not fact for _, _, f in picked):
            picked.append((order, category, fact))
    for _, category, fact in sorted(picked, key=lambda item: item[0]):
        checks.append(f"{category}: {fact.text} *(from `{fact.call}`, {fact.location})*")
    if len(checks) < 2:
        subject = " — ".join(part for part in (title, subtitle) if part)
        checks.append(f"What happens on screen matches “{subject}” *(guessed from the step title)*")
    if len(checks) < 2:
        checks.append("No visual glitches: frozen frames, stuck movement, flicker, overlapping HUDs *(guessed)*")
    return checks


def shorten(value: str, limit: int = 120) -> str:
    return value if len(value) <= limit else value[:limit - 1].rstrip() + "…"


def distinct(values: list[str]) -> list[str]:
    seen: list[str] = []
    for value in values:
        if value not in seen:
            seen.append(value)
    return seen


# --------------------------------------------------------------------------------------------
# Recordings, step markers and video time
# --------------------------------------------------------------------------------------------


@dataclass
class Step:
    step_id: str
    title: str
    subtitle: str
    start: float | None = None
    end: float | None = None
    chat: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


@dataclass
class Recording:
    metadata_path: Path
    metadata: dict[str, Any]
    video: Path
    duration: float | None
    project: Path | None
    log: Path | None
    steps: list[Step]
    intro_end: float | None

    @property
    def showcase(self) -> bool:
        return self.metadata.get("profile") == "showcase"


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    return value if isinstance(value, dict) else {}


def is_recording_metadata(path: Path) -> bool:
    try:
        data = load_json(path)
    except (OSError, ValueError):
        return False
    return "video" in data and "captureMode" in data


def metadata_files(directory: Path) -> list[Path]:
    found = [p for p in directory.glob("*.json") if is_recording_metadata(p)]
    found += [p for p in (directory / "metadata").glob("*.json") if is_recording_metadata(p)]
    return sorted(found, key=lambda p: str(load_json(p).get("startedAt", "")))


def latest_batch(project: Path) -> Path | None:
    root = project / "build" / "recordings"
    if not root.is_dir():
        return None
    candidates = [root] + [p for p in root.iterdir() if p.is_dir() and not p.name.endswith("-run")]
    dated = []
    for directory in candidates:
        files = metadata_files(directory)
        if files:
            dated.append((max(str(load_json(f).get("startedAt", "")) for f in files), directory))
    return max(dated)[1] if dated else None


def slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-").lower()


def infer_project(metadata: dict[str, Any], explicit: Path | None) -> Path | None:
    if explicit:
        return explicit.resolve()
    for key in ("runDirectory", "recordingDirectory", "video"):
        value = metadata.get(key)
        if not value or "/build/" not in value:
            continue
        root = Path(value.split("/build/")[0])
        if root.name in {"fabric", "neoforge", "common"} and (root.parent / "settings.gradle").exists():
            root = root.parent
        if (root / "settings.gradle").exists() or (root / "settings.gradle.kts").exists():
            return root
    name = str(metadata.get("name", ""))
    for sibling in WORKSPACE.iterdir() if WORKSPACE.is_dir() else []:
        if sibling.is_dir() and slug(sibling.name) == name:
            return sibling
    return None


def find_log(metadata_path: Path, metadata: dict[str, Any]) -> Path | None:
    kept = metadata.get("keptRunDirectory")
    candidates = []
    if kept:
        candidates.append(Path(kept) / "logs" / "latest.log")
    stem = metadata_path.stem
    candidates.append(metadata_path.parent / f"{stem}-run" / "logs" / "latest.log")
    candidates.append(metadata_path.parent.parent / "logs" / f"{metadata.get('name', '')}.log")
    return next((c for c in candidates if c.is_file()), None)


def parse_instant(value: str) -> datetime:
    value = value.rstrip("Z")
    if "." in value:
        head, fraction = value.split(".", 1)
        value = f"{head}.{fraction[:6]}"
    return datetime.fromisoformat(value).replace(tzinfo=timezone.utc)


def video_start_epoch(boundaries: dict[str, Any]) -> float | None:
    if "selectedStartEpochSeconds" in boundaries:
        return float(boundaries["selectedStartEpochSeconds"])
    if "firstWorldTickStartUs" in boundaries and "firstWorldTickVideoSeconds" in boundaries:
        return int(boundaries["firstWorldTickStartUs"]) / 1e6 - float(boundaries["firstWorldTickVideoSeconds"])
    return None


class LogClock:
    """Maps the log's local HH:MM:SS to epoch seconds, calibrated against the recording start."""

    def __init__(self, started: datetime, first_log_seconds: int) -> None:
        start_seconds = started.hour * 3600 + started.minute * 60 + started.second
        delta = (first_log_seconds - start_seconds) % 86400
        offset = round(delta / 900) * 900
        if offset > 14 * 3600:
            offset -= 86400
        self.offset = offset
        local = started + timedelta(seconds=offset)
        self.day = datetime(local.year, local.month, local.day, tzinfo=timezone.utc)
        self.previous = -1

    def epoch(self, seconds_of_day: int) -> float:
        if seconds_of_day < self.previous - 3600:
            self.day += timedelta(days=1)
        self.previous = seconds_of_day
        return (self.day + timedelta(seconds=seconds_of_day - self.offset)).timestamp()


def parse_steps(log: Path, metadata: dict[str, Any], video_end: float | None) -> tuple[list[Step], float | None]:
    steps: list[Step] = []
    started = metadata.get("startedAt")
    zero = video_start_epoch(metadata.get("boundaries") or {})
    clock: LogClock | None = None
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        time_match = LOG_TIME.search(line)
        seconds = None
        if time_match:
            h, m, s = (int(g) for g in time_match.groups())
            seconds = h * 3600 + m * 60 + s
            if clock is None and started:
                clock = LogClock(parse_instant(started), seconds)
        if MARKER in line:
            parts = [p.strip() for p in line.split(MARKER, 1)[1].split(" | ")]
            parts += [""] * (3 - len(parts))
            step = Step(parts[0], parts[1], " | ".join(p for p in parts[2:] if p))
            if seconds is not None and clock and zero is not None:
                step.start = max(0.0, clock.epoch(seconds) - zero)
            if steps and steps[-1].end is None:
                steps[-1].end = step.start
            steps.append(step)
        elif steps:
            if seconds is not None and clock:
                clock.epoch(seconds)
            chat = CHAT.search(line)
            if chat and chat.group(1).strip():
                steps[-1].chat.append(chat.group(1).strip())
            elif "/ERROR]" in line:
                steps[-1].errors.append(line.split("]: ", 1)[-1].strip())
    if steps and steps[-1].end is None:
        steps[-1].end = video_end
    # Chat that most steps print (world reset feedback such as game mode changes) is not a check.
    counts: dict[str, int] = {}
    for step in steps:
        for line in set(step.chat):
            counts[line] = counts.get(line, 0) + 1
    for step in steps:
        step.chat = [line for line in step.chat if len(steps) < 4 or counts[line] <= len(steps) * 0.3]
    intro_end = steps[0].start if steps else None
    return steps, intro_end


def probe_duration(video: Path, metadata: dict[str, Any]) -> float | None:
    if video.is_file() and shutil.which("ffprobe"):
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(video)],
            capture_output=True, text=True, check=False,
        )
        try:
            return float(result.stdout.strip())
        except ValueError:
            pass
    boundaries = metadata.get("boundaries") or {}
    if "worldExitVideoSeconds" in boundaries:
        return float(boundaries["worldExitVideoSeconds"]) + float(boundaries.get("effectivePostWorldExitPaddingSeconds", 0))
    return None


def load_recording(path: Path, explicit_project: Path | None) -> Recording:
    metadata = load_json(path)
    video = Path(metadata["video"])
    if not video.is_file():
        sibling = path.with_suffix(".mp4")
        fleet = path.parent.parent / "recordings" / video.name
        video = next((c for c in (sibling, fleet) if c.is_file()), video)
    duration = probe_duration(video, metadata)
    log = find_log(path, metadata)
    steps, intro_end = parse_steps(log, metadata, duration) if log else ([], None)
    if not steps:
        # No showStep markers (or no kept log): review the recording as one entry.
        steps = [Step("(whole recording)", str(metadata.get("name", video.stem)), "no step markers", 0.0, duration)]
    return Recording(path, metadata, video, duration, infer_project(metadata, explicit_project), log, steps, intro_end)


def source_only_steps(files: list[JavaFile]) -> list[Step]:
    """Step ids for a project without recordings: ids passed to showStep or to a scenario factory."""
    steps: list[Step] = []
    seen: set[str] = set()
    for java in files:
        for literal in java.literals:
            if not re.fullmatch(r"[a-z0-9_-]+(\.[a-z0-9_-]+)+", literal.value) or literal.value in seen:
                continue
            score, _, titles = step_bodies(java, literal)
            open_paren = enclosing_paren(java.masked, literal.start)
            first_string = open_paren is not None and next(
                (lit for lit in java.literals if lit.start > open_paren), None) is literal
            if score >= 2 and first_string:
                seen.add(literal.value)
                steps.append(Step(literal.value, *titles))
    return steps


# --------------------------------------------------------------------------------------------
# Markdown
# --------------------------------------------------------------------------------------------


def clock(seconds: float | None) -> str:
    if seconds is None:
        return "?"
    seconds = int(seconds)
    hours, rest = divmod(seconds, 3600)
    return f"{hours}:{rest // 60:02d}:{rest % 60:02d}" if hours else f"{rest // 60}:{rest % 60:02d}"


def chapters_file(recording: Recording, output_dir: Path) -> Path | None:
    timed = [s for s in recording.steps if s.start is not None]
    if not timed:
        return None
    path = output_dir / f"{recording.video.stem}.chapters.ffmetadata"
    lines = [";FFMETADATA1"]
    if recording.intro_end and recording.intro_end > 1:
        lines += ["[CHAPTER]", "TIMEBASE=1/1000", "START=0", f"END={int(recording.intro_end * 1000)}", "title=(setup)"]
    for index, step in enumerate(timed):
        start = max(0.0, step.start - 1)
        end = step.end if step.end is not None else (recording.duration or step.start + 1)
        title = escape_ffmetadata(f"{step.step_id} — {step.title} {step.subtitle}".strip())
        lines += ["[CHAPTER]", "TIMEBASE=1/1000", f"START={int(start * 1000)}", f"END={int(max(end, start + 1) * 1000)}", f"title={title}"]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def escape_ffmetadata(value: str) -> str:
    return re.sub(r"([=;#\\\n])", r"\\\1", value)


def shell_quote(value: str) -> str:
    return value if re.fullmatch(r"[\w@%+=:,./-]+", value) else "'" + value.replace("'", "'\\''") + "'"


def render(recordings: list[Recording], sources: dict[Path, tuple[list[JavaFile], dict[str, list[Method]]]],
           output: Path, speed: float, source_only: tuple[Path, list[Step]] | None) -> str:
    out: list[str] = []
    entries = 0
    full_entries: dict[str, str] = {}
    body: list[str] = []
    chapter_paths: list[tuple[Recording, Path | None]] = []

    def entry(step: Step, recording: Recording | None, project: Path | None) -> None:
        nonlocal entries
        entries += 1
        number = f"{entries}"
        span = ""
        if step.start is not None:
            length = (step.end - step.start) if step.end is not None else None
            span = f" · {clock(step.start)}–{clock(step.end)}"
            if length is not None:
                span += f" ({clock(length)}, {clock(length / speed)} at {speed:g}x)"
        heading = f"### {number}. `{step.step_id}` — {step.title}" + (f" · {step.subtitle}" if step.subtitle else "")
        body.append(heading)
        body.append("")
        if recording:
            body.append(f"- **Recording:** `{recording.video.name}`{span}")
        if step.step_id in full_entries:
            body.append(f"- Same test as entry {full_entries[step.step_id]}; check it looks the same here (and presentable, for a showcase cut).")
            body.append("- [ ] pass  [ ] fail  · notes: ")
            body.append("")
            return
        full_entries[step.step_id] = number
        found = None
        if project and project in sources:
            files, methods = sources[project]
            found = find_step_source(step.step_id, files, methods, project)
        if recording:
            landmark = f"`{step.title}` / `{step.subtitle}`" if recording.showcase else f"`Scenario: {step.step_id}`"
            body.append(f"- **Starts when:** the bottom-right recorder panel switches to {landmark}")
        if found:
            body.append(f"- **Source:** `{found.location}`")
            if found.description:
                body.append(f"- **Described as:** {found.description}")
            body.append("- **What it does:**")
            shown = [f for f in found.facts if f.kind != "wait" or f.depth == 0]
            for fact in shown[:MAX_FACTS]:
                body.append(f"  {'  ' * fact.depth}- {fact.text}")
            if len(shown) > MAX_FACTS:
                body.append(f"  - … {len(shown) - MAX_FACTS} more (see source)")
            if not shown:
                body.append("  - (no assertions or setup calls recognised; read the source)")
            if found.earlier:
                body.append("- **Asserted earlier in the test** (state the step shows):")
                for fact in found.earlier[:MAX_FACTS // 2]:
                    body.append(f"  - {fact.text}")
                if len(found.earlier) > MAX_FACTS // 2:
                    body.append(f"  - … {len(found.earlier) - MAX_FACTS // 2} more (see source)")
        else:
            body.append("- **Source:** not found in the GameTest sources; checks below are guessed from the title")
        checks = visual_checks((found.facts + found.earlier) if found else [], step.chat, step.title, step.subtitle)
        body.append("- **Look for:**")
        for check in checks:
            body.append(f"  - [ ] {check}")
        if step.errors:
            body.append(f"- **Run log:** {len(step.errors)} ERROR line(s) in this step, first: `{step.errors[0][:160]}`")
        body.append("- **Result:** [ ] pass  [ ] fail")
        body.append("- **Notes:** ")
        body.append("")

    ordered = sorted(recordings, key=lambda r: (r.showcase, str(r.metadata.get("startedAt", ""))))
    for recording in ordered:
        meta = recording.metadata
        body.append(f"## {recording.video.name}")
        body.append("")
        body.append(f"- **File:** `{recording.video}`")
        body.append(f"- **Recorded:** {meta.get('startedAt', '?')} → {meta.get('finishedAt', '?')} · profile `{meta.get('profile')}` · "
                    f"selectors {json.dumps({k: v for k, v in (meta.get('selectors') or {}).items() if v})}")
        body.append(f"- **Duration:** {clock(recording.duration)} ({clock((recording.duration or 0) / speed)} at {speed:g}x) · "
                    f"{len(recording.steps)} steps · Gradle status {meta.get('gradleStatus')} · "
                    f"recorder failures: {len(meta.get('recordingFailures') or [])}")
        if recording.intro_end and recording.intro_end >= 2:
            body.append(f"- **Skip:** 0:00–{clock(recording.intro_end)} is world setup before the first step")
        if not recording.log:
            body.append("- **No run log found** (no kept `-run` directory): steps and timestamps are unknown for this recording.")
        body.append("")
        chapter_paths.append((recording, chapters_file(recording, output.parent)))
        for step in recording.steps:
            entry(step, recording, recording.project)

    if source_only:
        project, steps = source_only
        body.append("## From test sources only (no recordings found)")
        body.append("")
        if not steps:
            body.append("No step ids found: no `ClientGameTestRecorder.showStep` call (or scenario factory with a dotted id "
                        "and a lambda or method reference) in the GameTest sources.")
            body.append("")
        for step in steps:
            entry(step, None, project)

    total = sum(r.duration or 0 for r in recordings)
    watch = total / speed
    tick = len(full_entries) * TICK_SECONDS_PER_ENTRY + (entries - len(full_entries)) * TICK_SECONDS_PER_REPEAT
    out.append("# Client GameTest review checklist")
    out.append("")
    out.append(f"Generated {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} by `MinecraftModConventions/scripts/review_checklist.py`. "
               "Facts come from the GameTest sources, step times and chat lines from the run log. "
               "Checks marked *guessed* are not backed by an assertion or log line.")
    out.append("")
    out.append("## Workflow")
    out.append("")
    if recordings:
        out.append(f"1. Open everything as one playlist at {speed:g}x, in this file's order "
                   f"({'non-showcase first, then showcase cuts' if any(r.showcase for r in recordings) else 'oldest first'}):")
        out.append("")
        out.append("   ```shell")
        mpv = "mpv" if shutil.which("mpv") else "nix shell nixpkgs#mpv --command mpv"
        parts = [mpv, f"--speed={speed:g}", "--save-position-on-quit", "--osd-level=3"]
        lines = [" ".join(parts) + " \\"]
        for recording, chapters in chapter_paths:
            if chapters:
                lines.append(f"     --{{ --chapters-file={shell_quote(str(chapters))} {shell_quote(str(recording.video))} --}} \\")
            else:
                lines.append(f"     {shell_quote(str(recording.video))} \\")
        lines[-1] = lines[-1].rstrip(" \\")
        out.extend("   " + line for line in lines)
        out.append("   ```")
        out.append("")
        out.append("2. Each step is a chapter: `PgDn`/`PgUp` jump to the next/previous step, `Enter`/`Shift+Enter` to the next/previous recording, "
                   "`[`/`]` change speed, `Space` pauses. The step id is also in the bottom-right recorder panel.")
        out.append("3. Per entry: read **Look for** before the chapter starts, watch, tick the boxes, mark pass/fail, "
                   "write a note only when something is off. Entries marked *Same test as* only need a glance.")
        out.append(f"4. Time: about {clock(watch)} of video at {speed:g}x ({clock(total)} at 1x), plus ~{TICK_SECONDS_PER_ENTRY} s per entry "
                   f"and ~{TICK_SECONDS_PER_REPEAT} s per repeat for reading and ticking ({entries} entries, ~{clock(tick)}) "
                   f"≈ **{round((watch + tick) / 60)} min**. "
                   "Split it across sittings: `--save-position-on-quit` resumes where you stopped.")
    else:
        out.append("No recordings were found, so there are no timestamps or playlist. Record with "
                   "`GTR_RECORDING_PROFILE=<profile> ./gradlew --no-daemon :fabric:recordClientGameTest` (through `heavy`), "
                   "then rerun this script to get timestamps, chapters and an mpv command.")
    out.append("")
    return "\n".join(out + body)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("path", type=Path, help="Mod project (uses its newest build/recordings batch) or a batch directory")
    parser.add_argument("--project", type=Path, help="Mod project whose GameTest sources describe the steps (default: inferred)")
    parser.add_argument("--output", type=Path, help="Checklist path (default: REVIEW-CHECKLIST.md in the batch directory)")
    parser.add_argument("--speed", type=float, default=DEFAULT_SPEED)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    path = args.path.resolve()
    project = args.project.resolve() if args.project else None
    is_project = (path / "settings.gradle").exists() or (path / "settings.gradle.kts").exists()
    if is_project:
        project = project or path
        batch = latest_batch(path)
    else:
        batch = path
    recordings = [load_recording(p, project) for p in metadata_files(batch)] if batch else []

    source_only = None
    if not recordings:
        if not project:
            print(f"No recording metadata in {path} and no --project to read sources from.", file=sys.stderr)
            return 1
        batch = project / "build" / "recordings"
        batch.mkdir(parents=True, exist_ok=True)

    sources: dict[Path, tuple[list[JavaFile], dict[str, list[Method]]]] = {}
    for root in {r.project for r in recordings if r.project} | ({project} if project else set()):
        files = load_sources(root)
        sources[root] = (files, index_methods(files))
    if not recordings and project:
        source_only = (project, source_only_steps(sources[project][0]))

    output = (args.output or batch / "REVIEW-CHECKLIST.md").resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(render(recordings, sources, output, args.speed, source_only), encoding="utf-8")
    steps = sum(len(r.steps) for r in recordings) + (len(source_only[1]) if source_only else 0)
    print(f"Review checklist: {output} ({len(recordings)} recordings, {steps} steps)")
    for recording in recordings:
        if recording.project is None:
            print(f"warning: no project found for {recording.metadata_path.name}; pass --project", file=sys.stderr)
        if recording.log is None:
            print(f"warning: no run log for {recording.metadata_path.name}; its steps are unknown", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
