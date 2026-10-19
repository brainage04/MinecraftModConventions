#!/usr/bin/env python3
"""Audit and record the owned Minecraft mod fleet.

The policy manifest is deliberately explicit about active projects, intentional
exclusions, and recording eligibility. Repository discovery still reports any
new sibling Minecraft checkout so a newly created mod cannot silently escape
the fleet policy.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Iterable

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_MANIFEST = SCRIPT_DIR / "mod-fleet.json"
RECORDER_CAPABLE_PLUGINS = (
    "io.github.brainage04.client-gametest-recorder",
    "io.github.brainage04.multiloader-mod-conventions",
)
REUSABLE_WORKFLOW_PATTERN = re.compile(
    r"brainage04/MinecraftModConventions/\.github/workflows/[^@\s]+@v([0-9]+(?:\.[0-9]+)*)"
)


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    return parse_properties(path.read_text(encoding="utf-8"))


def parse_properties(content: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def text_files(root: Path, patterns: Iterable[str]) -> list[Path]:
    files: list[Path] = []
    for pattern in patterns:
        files.extend(path for path in root.glob(pattern) if path.is_file())
    return sorted(set(files))


def combined_text(paths: Iterable[Path]) -> str:
    chunks: list[str] = []
    for path in paths:
        try:
            chunks.append(path.read_text(encoding="utf-8"))
        except UnicodeDecodeError:
            continue
    return "\n".join(chunks)


def discover_minecraft_repositories(workspace: Path) -> set[str]:
    discovered: set[str] = set()
    for child in workspace.iterdir():
        if not child.is_dir() or not (child / ".git").exists():
            continue
        values = properties(child / "gradle.properties")
        if "minecraft_version" in values:
            discovered.add(child.name)
    return discovered

def discover_github_minecraft_repositories(
    owner: str,
    policy_names: set[str],
    ignored: set[str],
) -> dict[str, Any]:
    if shutil.which("gh") is None:
        raise ValueError("GitHub inventory requested, but the gh CLI is not installed")

    list_result = subprocess.run(
        [
            "gh",
            "repo",
            "list",
            owner,
            "--limit",
            "1000",
            "--json",
            "name,isPrivate,isFork,isArchived,defaultBranchRef",
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    if list_result.returncode != 0:
        raise ValueError("Unable to read authenticated GitHub inventory: " + list_result.stderr.strip())

    repositories = json.loads(list_result.stdout)
    owned_names = {repository["name"] for repository in repositories}
    minecraft_candidates: set[str] = set()
    for name in sorted(owned_names - policy_names - ignored):
        file_result = subprocess.run(
            ["gh", "api", f"repos/{owner}/{name}/contents/gradle.properties"],
            check=False,
            capture_output=True,
            text=True,
        )
        if file_result.returncode != 0:
            if "HTTP 404" in file_result.stderr:
                continue
            raise ValueError(f"Unable to inspect {owner}/{name}: {file_result.stderr.strip()}")
        payload = json.loads(file_result.stdout)
        encoded = str(payload.get("content", "")).replace("\n", "")
        try:
            content = base64.b64decode(encoded, validate=True).decode("utf-8")
        except (ValueError, UnicodeDecodeError) as exception:
            raise ValueError(f"Unable to decode {owner}/{name}/gradle.properties") from exception
        if "minecraft_version" in parse_properties(content):
            minecraft_candidates.add(name)

    return {
        "repositoryCount": len(repositories),
        "ownedNames": sorted(owned_names),
        "minecraftCandidates": sorted(minecraft_candidates),
        "missingPolicyRepositories": sorted(policy_names - owned_names),
    }


def audit_repository(entry: dict[str, Any], workspace: Path, baseline: dict[str, str]) -> dict[str, Any]:
    name = required_text(entry, "name")
    root = workspace / entry.get("path", name)
    managed = bool(entry.get("managed", True))
    deviations: list[str] = []
    values = properties(root / "gradle.properties")

    if not root.is_dir():
        return {
            "name": name,
            "path": str(root),
            "visibility": entry.get("visibility", "unknown"),
            "platform": entry.get("platform", "unknown"),
            "status": entry.get("status", "unknown"),
            "managed": managed,
            "compliance": "missing",
            "deviations": ["Local checkout is missing"],
            "recording": recording_audit(entry, "", "", ""),
        }

    build_files = text_files(root, ["build.gradle", "build.gradle.kts", "*/build.gradle", "*/build.gradle.kts"])
    workflow_files = text_files(root, [".github/workflows/*.yml", ".github/workflows/*.yaml"])
    settings_files = text_files(root, ["settings.gradle", "settings.gradle.kts"])
    gametest_files = text_files(
        root,
        [
            "src/gametest/**/*.java",
            "src/gametest/**/*.kt",
            "src/gametest/**/*.json",
            "fabric/src/gametest/**/*.java",
            "fabric/src/gametest/**/*.kt",
            "fabric/src/gametest/**/*.json",
        ],
    )
    build_text = combined_text(build_files)
    workflow_text = combined_text(workflow_files)
    settings_text = combined_text(settings_files)
    gametest_text = combined_text(gametest_files)

    actual = {
        "minecraftVersion": values.get("minecraft_version"),
        "loaderVersion": values.get("loader_version", values.get("fabric_version")),
        "fabricApiVersion": values.get("fabric_api_version"),
        "javaVersion": values.get("java_version"),
        "conventionsVersion": values.get("minecraftmodconventions_version"),
    }
    workflow_versions = sorted(set(REUSABLE_WORKFLOW_PATTERN.findall(workflow_text)))

    if managed:
        expected_properties = {
            "minecraftVersion": "minecraftVersion",
            "loaderVersion": "loaderVersion",
            "fabricApiVersion": "fabricApiVersion",
            "javaVersion": "javaVersion",
            "conventionsVersion": "conventionsVersion",
        }
        for actual_key, baseline_key in expected_properties.items():
            expected = baseline[baseline_key]
            found = actual[actual_key]
            if found != expected:
                deviations.append(f"{actual_key} expected {expected}, found {found or '<missing>'}")

        if "io.github.brainage04." not in build_text:
            deviations.append("No MinecraftModConventions plugin is applied")
        if "MinecraftModConventionsGitHubReleases" in settings_text or "ivy {" in settings_text:
            deviations.append("Settings still declare a GitHub-release Ivy repository (plugins are on Maven Central)")
        if "mavenCentral()" not in settings_text:
            deviations.append("Settings omit Maven Central from pluginManagement")
        if "includeBuild(\"../MinecraftModConventions\")" not in settings_text and "includeBuild('../MinecraftModConventions')" not in settings_text:
            deviations.append("Settings omit the local MinecraftModConventions composite build")
        if not workflow_versions:
            deviations.append("No reusable MinecraftModConventions workflow is referenced")
        elif workflow_versions != [baseline["conventionsVersion"]]:
            deviations.append(
                "Reusable workflow versions expected v%s, found %s"
                % (baseline["conventionsVersion"], ", ".join("v" + version for version in workflow_versions))
            )

    compliance = "intentional-exclusion" if not managed else ("compliant" if not deviations else "noncompliant")
    return {
        "name": name,
        "path": str(root),
        "visibility": entry.get("visibility", "unknown"),
        "platform": entry.get("platform", "unknown"),
        "status": entry.get("status", "unknown"),
        "managed": managed,
        "compliance": compliance,
        "versions": actual,
        "workflowVersions": workflow_versions,
        "deviations": deviations,
        "recording": recording_audit(entry, build_text, gametest_text, workflow_text),
    }


def recording_audit(
    entry: dict[str, Any],
    build_text: str,
    gametest_text: str,
    workflow_text: str,
) -> dict[str, Any]:
    policy = entry.get("recording", {})
    enabled = bool(policy.get("enabled", False))
    plugin_applied = any(plugin_id in build_text for plugin_id in RECORDER_CAPABLE_PLUGINS)
    client_entrypoint = "fabric-client-gametest" in gametest_text
    handshake = "ClientGameTestRecorder.startRecording" in gametest_text
    workflow_wired = "reusable-client-gametests.yml" in workflow_text
    ready = enabled and plugin_applied and client_entrypoint and handshake and workflow_wired
    missing: list[str] = []
    if enabled:
        if not plugin_applied:
            missing.append("recorder plugin")
        if not client_entrypoint:
            missing.append("fabric-client-gametest entrypoint")
        if not handshake:
            missing.append("recording handshake")
        if not workflow_wired:
            missing.append("reusable client GameTest workflow")
    return {
        "enabled": enabled,
        "ready": ready,
        "profile": policy.get("profile", "showcase"),
        "trace": bool(policy.get("trace", False)),
        "reason": policy.get("reason"),
        "missing": missing,
    }


def audit_fleet(
    manifest: dict[str, Any],
    workspace: Path,
    overrides: dict[str, str],
    include_github: bool = False,
) -> dict[str, Any]:
    baseline = dict(manifest["baseline"])
    baseline.update({key: value for key, value in overrides.items() if value is not None})
    entries = manifest.get("repositories", [])
    audits = [audit_repository(entry, workspace, baseline) for entry in entries]

    policy_names = {required_text(entry, "name") for entry in entries}
    ignored = set(manifest.get("ignoredRepositories", {}))
    discovered = discover_minecraft_repositories(workspace)
    github_inventory = None
    github_candidates: set[str] = set()
    if include_github:
        github_inventory = discover_github_minecraft_repositories(
            required_text(manifest, "owner"),
            policy_names,
            ignored,
        )
        github_candidates.update(github_inventory["minecraftCandidates"])
    unmanaged = sorted((discovered | github_candidates) - policy_names - ignored)
    missing = sorted(policy_names - discovered)

    return {
        "generatedAt": datetime.now(UTC).isoformat(),
        "workspace": str(workspace),
        "owner": manifest.get("owner"),
        "baseline": baseline,
        "repositoryCount": len(audits),
        "repositories": audits,
        "unmanagedCandidates": unmanaged,
        "missingCheckouts": missing,
        "githubInventory": github_inventory,
    }


def markdown_report(audit: dict[str, Any], recording_results: list[dict[str, Any]] | None = None) -> str:
    lines = [
        "# Minecraft mod fleet report",
        "",
        f"Generated: {audit['generatedAt']}",
        "",
        "| Repository | Visibility | Platform | Status | Compliance | Recording | Deviations |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for repo in audit["repositories"]:
        recording = repo["recording"]
        if recording["enabled"]:
            recording_label = "ready" if recording["ready"] else "missing " + ", ".join(recording["missing"])
        else:
            recording_label = "excluded: " + str(recording.get("reason") or "policy")
        deviations = "; ".join(repo["deviations"]) or "none"
        lines.append(
            "| {name} | {visibility} | {platform} | {status} | {compliance} | {recording} | {deviations} |".format(
                name=escape_table(repo["name"]),
                visibility=escape_table(repo["visibility"]),
                platform=escape_table(repo["platform"]),
                status=escape_table(repo["status"]),
                compliance=escape_table(repo["compliance"]),
                recording=escape_table(recording_label),
                deviations=escape_table(deviations),
            )
        )

    github_inventory = audit.get("githubInventory")
    if github_inventory:
        lines.extend(
            [
                "",
                f"Authenticated GitHub inventory: {github_inventory['repositoryCount']} repositories; "
                f"{len(github_inventory['minecraftCandidates'])} unclassified Minecraft candidates.",
            ]
        )
        if github_inventory["missingPolicyRepositories"]:
            lines.extend(["", "## Policy repositories missing from GitHub", ""])
            lines.extend(f"- {name}" for name in github_inventory["missingPolicyRepositories"])

    if audit["unmanagedCandidates"]:
        lines.extend(["", "## Unmanaged Minecraft repository candidates", ""])
        lines.extend(f"- {name}" for name in audit["unmanagedCandidates"])
    if audit["missingCheckouts"]:
        lines.extend(["", "## Missing local checkouts", ""])
        lines.extend(f"- {name}" for name in audit["missingCheckouts"])

    if recording_results is not None:
        lines.extend(["", "## Recording execution", "", "| Repository | Result | Video | Log |", "| --- | --- | --- | --- |"])
        for result in recording_results:
            lines.append(
                "| {name} | {status} | {video} | {log} |".format(
                    name=escape_table(result["name"]),
                    status=escape_table(result["status"]),
                    video=escape_table(result.get("video") or ""),
                    log=escape_table(result.get("log") or ""),
                )
            )
    lines.append("")
    return "\n".join(lines)


def run_recordings(
    manifest: dict[str, Any],
    audit: dict[str, Any],
    workspace: Path,
    output: Path,
    includes: set[str],
    dry_run: bool,
    fail_fast: bool,
    resume: bool,
) -> list[dict[str, Any]]:
    output.mkdir(parents=True, exist_ok=True)
    (output / "logs").mkdir(exist_ok=True)
    (output / "metadata").mkdir(exist_ok=True)
    (output / "recordings").mkdir(exist_ok=True)
    (output / ".work").mkdir(exist_ok=True)

    previous_results: dict[str, dict[str, Any]] = {}
    previous_report = output / "fleet-report.json"
    if resume and previous_report.is_file():
        previous_payload = load_json(previous_report)
        for previous in previous_payload.get("recordingResults", []):
            video = Path(str(previous.get("video", "")))
            metadata = Path(str(previous.get("metadata", "")))
            if previous.get("status") == "passed" and video.is_file() and metadata.is_file():
                previous_results[str(previous.get("name"))] = previous

    entries = {required_text(entry, "name"): entry for entry in manifest["repositories"]}
    audits = {repo["name"]: repo for repo in audit["repositories"]}
    results: list[dict[str, Any]] = []

    for name, entry in entries.items():
        policy = entry.get("recording", {})
        if not policy.get("enabled", False):
            continue
        if name in previous_results:
            results.append(previous_results[name])
            print(f"[{name}] reusing completed recording {previous_results[name]['video']}")
            continue
        if includes and name not in includes:
            continue
        repo_audit = audits[name]
        if not repo_audit["recording"]["ready"]:
            result = {
                "name": name,
                "status": "not-ready",
                "error": "Missing " + ", ".join(repo_audit["recording"]["missing"]),
            }
            results.append(result)
            print(f"[{name}] {result['error']}", file=sys.stderr)
            if fail_fast:
                break
            continue

        work_dir = output / ".work" / slug(name)
        if work_dir.exists():
            shutil.rmtree(work_dir)
        work_dir.mkdir(parents=True)
        log_path = output / "logs" / f"{slug(name)}.log"
        root = workspace / entry.get("path", name)
        env = os.environ.copy()
        env.update(
            {
                "GTR_RECORDING_DIR": str(work_dir.resolve()),
                "GTR_RECORDING_RUN_DIR": str((work_dir / "run").resolve()),
                "GTR_RECORDING_NAME": slug(name),
                "GTR_RECORDING_PROFILE": str(policy.get("profile", "showcase")),
                "GTR_RECORDING_TRACE": "true" if policy.get("trace", False) else "false",
                "CLIENT_GAMETEST_PROFILE": str(policy.get("profile", "showcase")),
            }
        )

        try:
            for preparation in policy.get("prepare", []):
                command = [str(part) for part in preparation["command"]]
                cwd = workspace / preparation.get("cwd", entry.get("path", name))
                if dry_run:
                    print(f"[{name}] prepare: {command} (cwd={cwd})")
                else:
                    run_streaming(command, cwd, env, log_path)

            command = ["./gradlew", "--no-daemon", str(policy.get("task", ":fabric:recordClientGameTest"))]
            if dry_run:
                print(f"[{name}] record: {command} (cwd={root})")
                results.append({"name": name, "status": "dry-run", "log": str(log_path)})
                continue

            run_streaming(command, root, env, log_path)
            videos = sorted(work_dir.glob("*.mp4"))
            metadata_files = sorted(work_dir.glob("*.json"))
            if len(videos) != 1:
                raise RuntimeError(f"Expected exactly one recorded video, found {len(videos)} in {work_dir}")
            if len(metadata_files) != 1:
                raise RuntimeError(f"Expected exactly one metadata file, found {len(metadata_files)} in {work_dir}")

            destination_video = output / "recordings" / videos[0].name
            destination_metadata = output / "metadata" / metadata_files[0].name
            shutil.copy2(videos[0], destination_video)
            copy_metadata(metadata_files[0], destination_metadata, destination_video, output)
            shutil.rmtree(work_dir)
            result = {
                "name": name,
                "status": "passed",
                "video": str(destination_video),
                "metadata": str(destination_metadata),
                "log": str(log_path),
            }
            results.append(result)
            print(f"[{name}] recording saved to {destination_video}")
        except Exception as exception:  # keep the remaining fleet runnable after one scenario fails
            result = {
                "name": name,
                "status": "failed",
                "error": str(exception),
                "workDirectory": str(work_dir),
                "log": str(log_path),
            }
            results.append(result)
            print(f"[{name}] {exception}", file=sys.stderr)
            if fail_fast:
                break
    return results


def copy_metadata(source: Path, destination: Path, video: Path, output: Path) -> None:
    data = load_json(source)
    data["video"] = str(video.resolve())
    data["recordingDirectory"] = str(output.resolve())
    data["keptRunDirectory"] = None
    data["fleetCollectedAt"] = datetime.now(UTC).isoformat()
    destination.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def run_streaming(command: list[str], cwd: Path, env: dict[str, str], log_path: Path) -> None:
    print(f"[{cwd.name}] running: {' '.join(command)}")
    with log_path.open("a", encoding="utf-8") as log:
        log.write(f"$ {' '.join(command)}\n")
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            print(line, end="")
            log.write(line)
        status = process.wait()
    if status != 0:
        raise RuntimeError(f"Command failed with status {status}: {' '.join(command)}")


def write_report(output: Path, audit: dict[str, Any], recording_results: list[dict[str, Any]] | None = None) -> None:
    output.mkdir(parents=True, exist_ok=True)
    payload = dict(audit)
    if recording_results is not None:
        payload["recordingResults"] = recording_results
    (output / "fleet-report.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    (output / "fleet-report.md").write_text(markdown_report(audit, recording_results), encoding="utf-8")


def required_text(mapping: dict[str, Any], key: str) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"Expected non-empty text field {key}")
    return value.strip()


def escape_table(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", value).strip("-").lower()


def default_output() -> Path:
    timestamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
    return Path.home() / "Downloads" / f"minecraft-mod-gametest-recordings-{timestamp}"


def baseline_overrides(args: argparse.Namespace) -> dict[str, str]:
    return {
        "minecraftVersion": args.minecraft_version,
        "loaderVersion": args.loader_version,
        "fabricApiVersion": args.fabric_api_version,
        "javaVersion": args.java_version,
        "conventionsVersion": args.conventions_version,
    }


def add_shared_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--workspace", type=Path, default=SCRIPT_DIR.parents[1])
    parser.add_argument("--minecraft-version")
    parser.add_argument("--loader-version")
    parser.add_argument("--fabric-api-version")
    parser.add_argument("--java-version")
    parser.add_argument("--conventions-version")
    parser.add_argument(
        "--github",
        action="store_true",
        help="Reconcile the manifest with every authenticated public/private repository using gh",
    )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    audit_parser = subparsers.add_parser("audit", help="Audit every manifest repository")
    add_shared_arguments(audit_parser)
    audit_parser.add_argument("--output", type=Path)
    audit_parser.add_argument("--strict", action="store_true", help="Fail for managed deviations or unmanaged candidates")

    list_parser = subparsers.add_parser("list", help="List recording policy")
    add_shared_arguments(list_parser)

    record_parser = subparsers.add_parser("record", help="Audit and record every eligible repository")
    add_shared_arguments(record_parser)
    record_parser.add_argument("--output", type=Path, default=None)
    record_parser.add_argument("--include", action="append", default=[], metavar="REPOSITORY")
    record_parser.add_argument("--dry-run", action="store_true")
    record_parser.add_argument("--fail-fast", action="store_true")
    record_parser.add_argument(
        "--resume",
        action="store_true",
        help="Reuse valid passed artifacts in an existing --output report and rerun the remainder",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    manifest = load_json(args.manifest.resolve())
    workspace = args.workspace.expanduser().resolve()
    audit = audit_fleet(manifest, workspace, baseline_overrides(args), args.github)

    if args.command == "list":
        for repo in audit["repositories"]:
            recording = repo["recording"]
            detail = "record" if recording["enabled"] else "skip: " + str(recording.get("reason") or "policy")
            print(f"{repo['name']}: {detail}")
        return 0

    if args.command == "audit":
        print(markdown_report(audit))
        if args.output:
            write_report(args.output.expanduser().resolve(), audit)
        failures = any(
            repo["managed"] and repo["compliance"] in {"noncompliant", "missing"}
            for repo in audit["repositories"]
        )
        failures = failures or bool(audit["unmanagedCandidates"])
        github_inventory = audit.get("githubInventory")
        if github_inventory:
            failures = failures or bool(github_inventory["missingPolicyRepositories"])
        return 1 if args.strict and failures else 0

    output = (args.output or default_output()).expanduser().resolve()
    includes = set(args.include)
    known = {repo["name"] for repo in audit["repositories"]}
    unknown = sorted(includes - known)
    if unknown:
        raise ValueError("Unknown --include repositories: " + ", ".join(unknown))
    results = run_recordings(
        manifest,
        audit,
        workspace,
        output,
        includes,
        args.dry_run,
        args.fail_fast,
        args.resume,
    )
    write_report(output, audit, results)
    print(f"Fleet report: {output / 'fleet-report.md'}")
    return 1 if any(result["status"] in {"failed", "not-ready"} for result in results) else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as exception:
        print(f"error: {exception}", file=sys.stderr)
        raise SystemExit(2)
