# MinecraftModConventions

MinecraftModConventions is a set of Gradle plugins, reusable GitHub workflows and client GameTest helpers for my Fabric and NeoForge Minecraft mods. 3.1.x and later target Minecraft 26.3; 3.0.x is the Minecraft 26.2 line.

A mod applies one plugin, `multiloader-mod-conventions` (or `fabric-mod-conventions` for a single-loader Fabric mod), and gets the build, the GameTest runs, the quality checks and the release tasks every one of my mods shares. Its client GameTests use the runtime helpers to play and film the mod the way a player would, and its GitHub workflows call the reusable ones here. What each release changed is in [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md).

## Features

| Plugin (`io.github.brainage04.…`) | Apply to | Purpose |
| --- | --- | --- |
| `multiloader-mod-conventions` | root of a `common`/`fabric` mod, optionally with `neoforge` | **The standard entry point.** Configures the included modules with Architectury Loom and applies every plugin below except `fabric-mod-conventions` and `maven-central-publishing`. Adds the [quality checks](docs/QUALITY_CHECKS.md) to every module. |
| `fabric-mod-conventions` | single-loader Fabric project | Fabric Loom, project identity, standard dependencies and repositories, Java settings, side-aware source layout, access widener, sources JAR, `fabric.mod.json` expansion, license in the JAR, and the [quality checks](docs/QUALITY_CHECKS.md). |
| `client-gametest-recorder` | Fabric project | `recordClientGameTest`: records client GameTests to MP4 in an isolated Xvfb display, from the game's own picture and sound or from a screen grab and an audio sink. Adds the runtime helpers to the `gametest` source set. |
| `production-gametests` | Fabric project | Creates the `gametest` source set, the film-only [`recording`](docs/CLIENT_GAMETESTS.md#film-only-code-in-the-recording-source-set) source set, and runs GameTests against the packaged mod through Loom's production client and server. |
| `integration-gametests` | Fabric or NeoForge Loom project with a `gametest` source set | Named GameTest suites that run with extra mods (Maven artifacts or pinned Git commits) loaded, in their own source set and runs. |
| `workspace-dependencies` | any project | Prefers sibling checkouts' `build/local-repo` over Maven Central, and can share one dev-client `options.txt`. |
| `java-quality-conventions` | root project | Spotless (google-java-format, AOSP), Checkstyle and javac lint as errors, under the two [strict switches](docs/QUALITY_CHECKS.md#java-quality-switches); strict is the default, `-PstrictQuality=false` opts a project out, `-PstrictUnchecked=false` leaves unchecked and rawtypes as warnings. |
| `mod-publishing` | Fabric/NeoForge project | Opt-in GitHub, Modrinth and CurseForge release tasks around the Mod Publish Plugin. |
| `maven-central-publishing` | libraries only | POM metadata, signing and Central Portal upload. |

### Mod builds

A multi-loader mod has `common` and `fabric` subprojects, and optionally `neoforge`. Shared GameTests go in `common/src/gametest`, and each loader has its own run tasks. [docs/MOD_BUILDS.md](docs/MOD_BUILDS.md) covers the layout, the single-loader layout, sibling checkouts as dependencies, the `options.txt` every automated client launch starts from, and production GameTests. [docs/GRADLE_RUN_TASKS.md](docs/GRADLE_RUN_TASKS.md) lists every `run*` and `record*` task.

### Integration GameTests

Named GameTest suites run with extra mods loaded, from Maven or from a pinned Git commit: [docs/MOD_BUILDS.md](docs/MOD_BUILDS.md#integration-gametests).

### Quality checks

`check` fails on unformatted code, Checkstyle findings, javac lint and deprecated GameTest APIs unless a project turns the strict policy off, and warns about code that compares with a vanilla dimension, a block, item or biome a vanilla tag lists, or a literal overworld build limit: [docs/QUALITY_CHECKS.md](docs/QUALITY_CHECKS.md).

### Client GameTests

A client GameTest does only what a player can do with a keyboard and a mouse, pairs every positive case with a negative one, and follows the recording rules and the recording standard. The helpers for stage markers, waits, captions, commands, chat, a dedicated server and a vanilla server are listed with those rules in [docs/CLIENT_GAMETESTS.md](docs/CLIENT_GAMETESTS.md).

### Client GameTest recording

```shell
GTR_RECORDING_PROFILE=smoke ./gradlew --no-daemon :fabric:recordClientGameTest
```

Needs `ffmpeg`/`ffprobe` and Xvfb with `xdpyinfo`; a screen grab also needs `pactl`. The recorder, its `GTR_RECORDING_*` settings, the card display, the metadata and `scripts/trim_stages.py` are in [docs/RECORDING.md](docs/RECORDING.md).

### Publishing

`mod-publishing` enables a destination only when its block is configured:

```gradle
modPublishing {
    github { repository.set("brainage04/example-mod") }
    modrinth { projectId.set("example-project") }
    curseforge { projectId.set("123456") }
}
```

`publishGithub`, `publishModrinth` and `publishCurseforge` can be retried independently; `publishMods` runs all enabled ones. The Modrinth description is synced from `README.md` and the icon from `assets/<mod_id>/icon.png`. `build` and `check` never contact these services.

A Modrinth project that the sync creates stays a draft, and neither it nor its versions are public until a moderator approves it. `modrinth { submitForReview.set(true) }` (or `MODRINTH_SUBMIT_FOR_REVIEW=true`, or `-PmodrinthSubmitForReview=true`; a non-empty environment variable wins over the property) makes `publishModrinth` finish with `submitModrinthProjectForReview`, which submits a draft project for review by setting its status to `processing` ([Modrinth API](https://docs.modrinth.com/api/operations/modifyproject/)). A project in any other status is left alone. When Modrinth refuses the submission, for example while the project still has a required check open on modrinth.com, the task logs Modrinth's reason as a warning and writes it to `$GITHUB_STEP_SUMMARY`, and the release carries on; with `MODRINTH_REVIEW_REQUIRED=true` (the workflows set it when `require_stores` lists `modrinth`) it fails instead. The fleet's `publishing = false` skips the submission too.

`maven-central-publishing` is for libraries. See [docs/PUBLISHING.md](docs/PUBLISHING.md) for credentials and the release command.

### Workflows

Consumer workflows call the reusable ones (build, client GameTests, production GameTests, NeoForge GameTests, multi-loader release) and only supply triggers, profiles, artifact patterns and project IDs: [docs/WORKFLOWS.md](docs/WORKFLOWS.md).

## Getting started

- Java 25 and Minecraft 26.3 (MinecraftModConventions 3.1.x or later), with Fabric Loader and Fabric API, and NeoForge for a multi-loader mod with `neoforge`.
- Apply the plugin in the root `build.gradle`:

  ```gradle
  plugins {
      id "io.github.brainage04.multiloader-mod-conventions" version "${minecraftmodconventions_version}"
  }
  ```

- Set `mod_id`, `mod_name`, `mod_version`, `maven_group`, `archives_base_name`, `java_version`, `minecraft_version`, `loader_version`, `fabric_api_version` and `minecraftmodconventions_version` in `gradle.properties`, plus `neoforge_version` when `:neoforge` exists.
- Plugins are resolved from `../MinecraftModConventions/build/local-repo` when present, otherwise from Maven Central. Consumers map each plugin ID to its module in `settings.gradle`; copy `pluginManagement` from ModernMinecraftModTemplate.

## Commands

No in-game commands. The Gradle tasks are in [docs/GRADLE_RUN_TASKS.md](docs/GRADLE_RUN_TASKS.md).

## Configuration

- `gradle.properties`: `mod_side` (`both`, `client` or `server`), `strictQuality=false` and `strictUnchecked=false` ([quality switches](docs/QUALITY_CHECKS.md#java-quality-switches)), `minecraftmodconventions.clientStartupTimeoutSeconds` ([automated client launches](docs/MOD_BUILDS.md#automated-client-launches)).
- `~/.gradle/gradle.properties`: `minecraftmodconventions.devClientOptions`, one `options.txt` for every dev client ([workspace dependencies](docs/MOD_BUILDS.md#workspace-dependencies)).
- Build blocks: `multiLoaderModConventions`, `fabricModConventions`, `productionGameTests`, `clientGameTestRecorder`, `workspaceDependencies` and `modPublishing`, each described with its feature.
- Recordings: the `GTR_RECORDING_*` environment variables and the `recordingDisplay` Gradle property ([docs/RECORDING.md](docs/RECORDING.md)).

## Compatibility

3.1.x and later build mods for Minecraft 26.3, 3.0.x for 26.2. A CI recording on a card display is not supported ([docs/WORKFLOWS.md](docs/WORKFLOWS.md)). No other known incompatibilities.

## Documentation

- [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md) - what each release changed and what can newly fail.
- [docs/MOD_BUILDS.md](docs/MOD_BUILDS.md) - multi-loader and single-loader builds, integration GameTests, sibling dependencies, automated client launches, production GameTests.
- [docs/GRADLE_RUN_TASKS.md](docs/GRADLE_RUN_TASKS.md) - every `run*` and `record*` task.
- [docs/QUALITY_CHECKS.md](docs/QUALITY_CHECKS.md) - the vanilla identity, tag identity, world height and deprecated GameTest API checks, and the strict switches.
- [docs/CLIENT_GAMETESTS.md](docs/CLIENT_GAMETESTS.md) - the client GameTest rules, the recording rules and standard, and the test helpers.
- [docs/RECORDING.md](docs/RECORDING.md) - the client GameTest recorder.
- [docs/WORKFLOWS.md](docs/WORKFLOWS.md) - the reusable GitHub workflows, and what a mod needs before its first store release.
- [docs/PUBLISHING.md](docs/PUBLISHING.md) - releasing MinecraftModConventions to Maven Central.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) - the fleet audit script.
