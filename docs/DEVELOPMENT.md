# Development

## Fleet audit

`scripts/mod_fleet.py` checks every sibling mod against the versions, structure, workflows and recording policy in `scripts/mod-fleet.json`, and can record them all.

```shell
./scripts/mod_fleet.py audit --github --strict
./scripts/mod_fleet.py record [--include <repository>] [--dry-run]
./scripts/mod_fleet.py vanilla-identity [--output hits.md]
./scripts/mod_fleet.py vanilla-tags --tag-data <directory> [--output hits.md]
./scripts/mod_fleet.py world-height [--output hits.md]
```

`record` writes to a timestamped `~/Downloads/minecraft-mod-gametest-recordings-*` directory; `--output <dir> --resume` reruns only the repositories that failed. For a new Minecraft release, pass `--minecraft-version`, `--loader-version`, `--fabric-api-version`, `--java-version` and `--conventions-version` to `audit`, then update the manifest.

`vanilla-identity` lists the lines `checkVanillaIdentity` would flag in every git checkout beside this one, including mods that do not use the conventions plugins. `vanilla-tags` does the same for `checkVanillaTagIdentities` and names the tag each line should ask about; it reads the vanilla tags from `--tag-data`, the `data/minecraft/tags` directory of a decompiled Minecraft tree. `world-height` lists the literal overworld build limits `checkWorldHeight` would flag.
