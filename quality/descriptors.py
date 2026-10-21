"""MC 26.2 codec validation before server startup, without loading Minecraft."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

HERE = Path(__file__).resolve().parent


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load(path: Path) -> Any:
    return json.loads(path.read_text(), object_pairs_hook=unique_object)


def validate(root: Path, files: list[str]) -> list[str]:
    validators = {
        kind: Draft202012Validator(load(HERE / f"{schema}-26.2.schema.json"))
        for kind, schema in [("test_instance", "instance"), ("test_environment", "environment")]
    }
    errors: list[str] = []
    resources: dict[tuple[str, str, str], str] = {}
    documents: list[tuple[str, str, str, Any]] = []
    for name in files:
        parts = Path(name).parts
        if "resources" not in parts or "data" not in parts:
            continue
        at = parts.index("data")
        if len(parts) <= at + 3:
            continue
        namespace, kind = parts[at + 1 : at + 3]
        if kind not in {*validators, "structure"}:
            continue
        lane = "/".join(parts[:at])
        identifier = namespace + ":" + str(Path(*parts[at + 3 :]).with_suffix(""))
        key = (lane, kind, identifier)
        if key in resources:
            errors.append(f"{name}: duplicate resource {identifier}: {resources[key]}")
        resources[key] = name
        if kind in validators and name.endswith(".json"):
            try:
                document = load(root / name)
                failures = sorted(validators[kind].iter_errors(document), key=lambda e: str(e.path))
                errors.extend(f"{name}:{'/'.join(map(str, e.path))}: {e.message}" for e in failures)
                if not failures:
                    documents.append((name, lane, kind, document))
            except (ValueError, OSError) as error:
                errors.append(f"{name}: {error}")

    def reference(name: str, lane: str, kind: str, identifier: str) -> None:
        identifier = identifier if ":" in identifier else "minecraft:" + identifier
        if (kind, identifier) in {
            ("test_environment", "minecraft:default"),
            ("structure", "minecraftmodconventions:empty"),
        }:
            return
        # A loader may use its own lane and common main/gametest resources, never the other loader.
        module = "" if lane.startswith("src/") else lane.split("/")[0]
        candidates = {
            lane,
            "common/src/main/resources",
            "common/src/gametest/resources",
            str(Path(module) / "src/main/resources"),
        }
        if not any((candidate, kind, identifier) in resources for candidate in candidates):
            errors.append(f"{name}: unresolved {kind} reference {identifier} in {lane}")

    def environment_refs(name: str, lane: str, value: Any) -> None:
        if isinstance(value, str):
            reference(name, lane, "test_environment", value)
        elif value.get("type") == "minecraft:all_of":
            for definition in value["definitions"]:
                environment_refs(name, lane, definition)

    for name, lane, kind, document in documents:
        if kind == "test_instance":
            environment_refs(name, lane, document["environment"])
            reference(name, lane, "structure", document["structure"])
            if document.get("required_successes", 1) > document.get("max_attempts", 1):
                errors.append(f"{name}: required_successes exceeds max_attempts")
        else:
            environment_refs(name, lane, document)
    tables = [name for name in files if Path(name).name == "gametests.txt"]
    if tables:
        try:
            properties = (root / "gradle.properties").read_text()
        except OSError as error:
            errors.append(f"gradle.properties: cannot resolve table namespace: {error}")
            return errors
        namespace_match = re.search(
            r"(?m)^[ \t]*mod_id[ \t]*=[ \t]*([a-z0-9_.-]+)[ \t]*$", properties
        )
        if namespace_match is None:
            errors.append("gradle.properties: missing valid mod_id for gametests.txt references")
            return errors
        project_namespace = namespace_match.group(1)
        for name in tables:
            lane = str(Path(name).parent / "resources")
            for line_number, line in enumerate((root / name).read_text().splitlines(), 1):
                words = line.partition("#")[0].split()
                if not words:
                    continue
                structures = [
                    word.removeprefix("structure=")
                    for word in words[2:]
                    if word.startswith("structure=")
                ] or ["minecraftmodconventions:empty"]
                for structure in structures:
                    if ":" not in structure:
                        structure = project_namespace + ":" + structure
                    reference(f"{name}:{line_number}", lane, "structure", structure)
    return errors
