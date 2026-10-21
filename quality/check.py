#!/usr/bin/env python3
"""Run pinned, read-only checks and the reviewed offline test manifest."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from descriptors import validate

HERE = Path(__file__).resolve().parent


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.output or root / "build/reports/fast-quality"
    output.mkdir(parents=True, exist_ok=True)
    files = sorted(
        set(
            subprocess.check_output(
                ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                cwd=root,
            )
            .decode()
            .split("\0")
        )
        - {""}
    )
    files = [name for name in files if (root / name).is_file()]
    python = [name for name in files if name.endswith(".py")]
    shell = []
    for name in files:
        with (root / name).open("rb") as stream:
            first = stream.readline(256)
        if name.endswith((".sh", ".bash")) or re.match(rb"^#!.*\b(?:bash|sh|dash|ksh)\b", first):
            shell.append(name)
    results = {}
    environment = dict(os.environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    environment["HYPOTHESIS_STORAGE_DIRECTORY"] = str(output.resolve() / "hypothesis")

    def run(tool: str, command: list[str], count, cwd: Path = root) -> None:
        start = time.monotonic()
        try:
            process = subprocess.run(
                command, cwd=cwd, env=environment, text=True, capture_output=True, timeout=180
            )
            text = process.stdout + process.stderr
            result = {
                "exit": process.returncode,
                "findings": count(process.stdout, process.stderr),
                "seconds": round(time.monotonic() - start, 3),
                "command": command,
            }
        except (OSError, subprocess.TimeoutExpired) as error:
            text = str(error)
            result = {"exit": 2, "findings": None, "error": text, "command": command}
        (output / f"{tool}.log").write_text(text)
        results[tool] = result
        print(f"{tool}: {json.dumps(result)}", flush=True)

    if python:
        run(
            "ruff",
            ["ruff", "check", "--config", str(HERE / "ruff.toml"), "--output-format=json", *python],
            lambda out, err: len(json.loads(out)) if out.strip().startswith("[") else None,
        )
        run(
            "ruff-format",
            ["ruff", "format", "--check", "--config", str(HERE / "ruff.toml"), *python],
            lambda out, err: len(re.findall(r"^Would reformat:", out + err, re.M)),
        )
        run(
            "mypy",
            [sys.executable, str(HERE / "mypy_check.py"), str(root), *python],
            lambda out, err: len(re.findall(r": error:", out + err)),
        )
    if shell:
        run(
            "shellcheck",
            ["shellcheck", "--format=json", *shell],
            lambda out, err: len(json.loads(out)) if out.strip().startswith("[") else None,
        )
        run(
            "shfmt",
            ["shfmt", "-l", "-i", "2", "-ci", *shell],
            lambda out, err: len(out.splitlines()),
        )
        # shfmt -l lists formatting failures but returns zero; make them a real gate.
        if results["shfmt"]["findings"]:
            results["shfmt"]["exit"] = 1
    workflows = [
        name
        for name in files
        if name.startswith(".github/workflows/") and name.endswith((".yml", ".yaml"))
    ]
    if workflows:
        run(
            "actionlint",
            ["actionlint", "-format", "{{json .}}", *workflows],
            lambda out, err: len(json.loads(out)) if out.strip().startswith("[") else None,
        )
    errors = validate(root, files)
    (output / "descriptors.log").write_text("\n".join(errors) + "\n")
    results["descriptors"] = {
        "exit": bool(errors),
        "findings": len(errors),
        "files": sum("/test_instance/" in p or "/test_environment/" in p for p in files),
        "tables": sum(Path(p).name == "gametests.txt" for p in files),
    }
    manifest = root / "quality-tests.json"
    if manifest.exists():
        for index, test in enumerate(json.loads(manifest.read_text())["offline"]):
            command = [sys.executable if item == "{python}" else item for item in test["command"]]
            run(
                f"offline-{index}",
                command,
                lambda out, err: len(
                    re.findall(
                        r"^(?:FAIL|ERROR):|^not ok |^(?:FAILED|ERROR) tests/", out + err, re.M
                    )
                ),
                root / test.get("cwd", "."),
            )
    for tool in ("ruff", "ruff-format", "mypy", "shellcheck", "shfmt", "actionlint"):
        results.setdefault(tool, {"exit": 0, "findings": 0, "files": 0})
    (output / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
    return int(any(result["exit"] for result in results.values()))


if __name__ == "__main__":
    raise SystemExit(main())
