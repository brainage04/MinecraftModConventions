"""Type-check independent script roots without inventing package-qualified imports."""

from __future__ import annotations

from collections import defaultdict
import os
from pathlib import Path
import re
import subprocess
import sys


def main() -> int:
    root = Path(sys.argv[1]).resolve()
    groups: dict[Path, list[str]] = defaultdict(list)
    for name in sys.argv[2:]:
        path = root / name
        base = path.parent
        while base != root and (base / "__init__.py").exists():
            base = base.parent
        groups[base].append(str(path.relative_to(base)))
    diagnostics: set[str] = set()
    failed = False
    for base, names in sorted(groups.items()):
        # Script entrypoints deliberately import siblings, not tools.foo.module.
        search = [base]
        if base.parent != root.parent:
            search.append(base.parent)
        process = subprocess.run(
            ["mypy", "--config-file", str(Path(__file__).with_name("mypy.ini")), *names],
            cwd=base,
            env={**os.environ, "MYPYPATH": os.pathsep.join(map(str, search))},
            capture_output=True,
            text=True,
            check=False,
        )
        failed |= process.returncode != 0
        for line in (process.stdout + process.stderr).splitlines():
            match = re.match(r"(.+?):(\d+): (error|note): (.*)", line)
            if match:
                path = (base / match[1]).resolve()
                display = str(path.relative_to(root)) if path.is_relative_to(root) else str(path)
                diagnostics.add(f"{display}:{match[2]}: {match[3]}: {match[4]}")
            elif not line.startswith(("Success:", "Found ")):
                diagnostics.add(f"{base.relative_to(root)}: {line}")
    print("\n".join(sorted(diagnostics)))
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
