# MinecraftModConventions icon

## What this is

`docs/icon/icon.png` — the mod's icon: 1024x1024 PNG, 8-bit RGBA, non-interlaced,
362,931 bytes, sha256
`4354aecbe1d341c23946cadfe205511a3e16907e86bb59e3678744fe460830ce`.

## How it was made

Blender render (Blender **5.1.1**, headless CLI, **Cycles on CPU**, 32 samples, 2 render
threads, 1024x1024, orthographic camera; 2.15 s).

The scene is the selected "modern conventions hub" template scene with seven real vanilla
block models placed in the chosen layout:

| slot | block |
|---|---|
| centre | crafting table |
| top-left | redstone lamp |
| top-centre | note block |
| top-right | observer |
| bottom-left | target |
| bottom-centre | jukebox |
| bottom-right | lectern |
| middle-left / middle-right | empty |

Verified by reopening the saved `.blend`
(`provenance/owner-layout-verification.json`: `exact_match: true`, 7 blocks, both middle
slots empty).

Imagery provenance — **real Minecraft 26.2 client-jar block models and textures**, extracted
byte-for-byte from the Fabric Loom merged client jar
`/home/thomas/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` into
`provenance/source/assets/modern/…`. `provenance/asset-provenance.json` lists every
extracted member with its jar path, sha256 and `byte_identical_to_jar: true`; re-extract any
of them with `unzip -p <jar> <member> > <path>`. The seven block faces actually used are the
blockstate/model JSONs of those seven blocks plus their referenced textures (crafting table,
note block, observer, lectern, jukebox, target, redstone lamp, and the shared
`cube.json`/`cube_all.json`/`cube_column.json`/`cube_top.json`/`block.json`/`bookshelf.json`
parents).

There is **no Minecraft shader pack** and no in-game capture: the block models are rendered
by Blender Cycles with the reference scene's materials, lighting and world. The template
camera is preserved exactly:

| parameter | value |
|---|---|
| camera matrix (row-major) | [[0.707107, -0.408248, 0.577350, 8.485281], [0.707107, 0.408248, -0.577350, -8.485281], [~0, 0.816497, 0.577350, 8.935281], [0, 0, 0, 1]] |
| location | 8.485281, -8.485281, 8.935281 |
| projection / scale | ORTHO, orthographic scale 6.90 |
| measured yaw / pitch | 45.000000 deg / 35.264395 deg (zero delta from the modern reference) |
| world background | linear RGB (0.0025, 0.0035, 0.006) -> decoded RGB (8, 11, 18) |

## Provenance files

`provenance/` mirrors the round-3 authoring tree `round3/blender-ab/`:

| file | what it is |
|---|---|
| `conventions2-selected-owner-layout.py` | entrypoint for this icon: `scene.render(1)` |
| `scene.py` | author script: rebuilds the hub from the reference scene, asserts the chosen layout, renders, saves `.blend`, writes metadata + verification |
| `run-blender.py` | serializing headless Blender CLI runner (cgroup/affinity/lock policy) |
| `prepare-assets.py` | extracts the byte-identical vanilla model/texture/blockstate assets and writes `asset-provenance.json` + `selected-blockstates.json` |
| `inspect-references.py` | measures the reference scenes (writes `evidence/reference-measurements.json`) |
| `verify-pngs.py` | decodes the final PNG, checks projected geometry coverage and reference-family agreement |
| `conventions2-selected-owner-layout.blend` | the rendered scene as saved by Blender |
| `…-metadata.json`, `…-verification.json` | camera/lighting/geometry record and the reopened-scene verification |
| `manifest.json`, `candidates.json`, `owner-layout-verification.json`, `camera-comparison.json`, `selected-blockstates.json`, `png-verification.json`, `render-report.json`, `reproduction.json`, `asset-provenance.json`, `source-snapshot.json`, `script-hashes.json`, `archive-verification.json`, `blockers.json`, `cleanup-report.json` | round-3 records: entry, layout, camera comparison against the legacy/modern references, per-asset jar provenance, script hashes, render and cleanup reports |
| `evidence/reference-measurements.json` | measured reference geometry used by `verify-pngs.py` |
| `source/minecraft.py`, `source/render_hubs.py`, `source/facing-table.json`, `source/modern-hub-down-left-1.blend`, `source/modern-hub-down-left-1-metadata.json`, `source/assets/**` | the frozen reference scene + asset snapshot this icon was built from (hashes in `source-snapshot.json`) |

## How to regenerate

From `docs/icon/provenance` (needs the shared `render-blender.service` cgroup with
`cpu.weight == 20` and a quota <= 3 cores; the runner asserts this, and it writes its logs
into `evidence/`):

```
python3 run-blender.py conventions2-selected-owner-layout.py
```

Then compare `conventions2-selected-owner-layout.png` with the sha256 above. Passing both
scripts, as in the original run, also re-measures the references:
`python3 run-blender.py inspect-references.py conventions2-selected-owner-layout.py`.

## Notes

- Deliberately not copied from the round-3 tree: the five retired candidate renders (moved
  to `round3/removed/blender-ab/` and listed in the removal ledger), the screenshots and
  logs in `round3/blender-ab/evidence/` (`template-family-comparison.png`, the 128 px
  thumbnail, `.log`/`-run.json`), and the `.sha256` sidecars (their hashes are aggregated in
  `script-hashes.json`).
- `verify-pngs.py` additionally reads the previous-milestone `blender-q` reference PNGs and
  writes review PNGs into `evidence/`; those inputs are outside this icon's provenance and
  are not shipped, so only its checks are usable here.
- The scene keeps a deliberately near-black background (decoded RGB 8, 11, 18); that is the
  approved reference background, not a rendering defect.

## Working-tree note

The round-3 working tree that produced this icon was cleaned up after integration. Every file needed to regenerate the icon was copied into `provenance/`; the copies live under `provenance/from-round3/` when they came from the working tree. Any remaining `round3/...` mention records where something came from, not a path that still exists.
