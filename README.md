# MinecraftModConventions

Shared Gradle plugins, GitHub workflows and client GameTest helpers for my Minecraft mods.

## Plugins

| Plugin (`io.github.brainage04.…`) | Apply to | Purpose |
| --- | --- | --- |
| `multiloader-mod-conventions` | root of a `common`/`fabric` mod, optionally with `neoforge` | **The standard entry point.** Configures the included modules with Architectury Loom and applies every plugin below except `fabric-mod-conventions` and `maven-central-publishing`. |
| `fabric-mod-conventions` | single-loader Fabric project | Fabric Loom, project identity, standard dependencies and repositories, Java settings, side-aware source layout, access widener, sources JAR, `fabric.mod.json` expansion, license in the JAR. |
| `client-gametest-recorder` | Fabric project | `recordClientGameTest`: records client GameTests to MP4 in an isolated Xvfb display and audio sink. Adds the runtime helpers to the `gametest` source set. |
| `production-gametests` | Fabric project | Creates the `gametest` source set and runs GameTests against the packaged mod through Loom's production client and server. |
| `integration-gametests` | Fabric or NeoForge Loom project with a `gametest` source set | Named GameTest suites that run with extra mods (Maven artifacts or pinned Git commits) loaded, in their own source set and runs. |
| `workspace-dependencies` | any project | Prefers sibling checkouts' `build/local-repo` over Maven Central, and can share one dev-client `options.txt`. |
| `java-quality-conventions` | root project | Spotless (google-java-format, AOSP), Checkstyle and javac lint; enforced only with `-PstrictQuality=true`. |
| `mod-publishing` | Fabric/NeoForge project | Opt-in GitHub, Modrinth and CurseForge release tasks around the Mod Publish Plugin. |
| `maven-central-publishing` | libraries only | POM metadata, signing and Central Portal upload. |

Plugins are resolved from `../MinecraftModConventions/build/local-repo` when present, otherwise from Maven Central. Consumers map each plugin ID to its module in `settings.gradle`; copy `pluginManagement` from ModernMinecraftModTemplate.

## Multi-loader mods

```gradle
plugins {
    id "io.github.brainage04.multiloader-mod-conventions" version "${minecraftmodconventions_version}"
}
```

Requires subprojects `common` and `fabric`; `neoforge` is optional. Required Gradle properties are `mod_id`, `mod_name`, `mod_version`, `maven_group`, `archives_base_name`, `java_version`, `minecraft_version`, `loader_version`, `fabric_api_version` and `minecraftmodconventions_version`. `neoforge_version` is required only when `:neoforge` exists. `mod_side` (`both`, `client` or `server`; default `both`) splits Fabric client sources for `both`, turns off Fabric production server GameTests for `client`, and sets the CurseForge environment.

For a Fabric-only shared-source project use `include 'common', 'fabric'` in settings. Root
`build`, `collectReleaseArtifacts` and `runAllGameTests` discover only the included loaders.
Fabric development/production integration suites and optional production-client tests remain
available; removing `:neoforge` does not remove those suites. A Fabric-only release omits
`additional_artifact_pattern` from `reusable-multiloader-release.yml`, which skips both
NeoForge publication jobs while retaining Fabric and GitHub publication.

- Shared GameTests go in `common/src/gametest/java` and `common/src/gametest/resources`; both loaders compile and package them.
- Both loaders' `gametest` source sets also get the structure `minecraftmodconventions:empty`, 8x8x8 blocks of air (`minecraft:empty` is 1x1x1). Use it in test instances with `"structure": "minecraftmodconventions:empty"`; it is never packaged into the release JARs.
- The access widener is `<mod_id>.accesswidener` in `common` (or `fabric`); the NeoForge access transformer is `neoforge/src/main/resources/META-INF/accesstransformer.cfg`.
- Tasks that run one loader live in that loader's project under the same name on both; the root only has tasks spanning both loaders. Loom's development `clientGameTest` run is not created (there is no `:fabric:runClientGameTest`); client GameTests run in Loom's production client.

  | Task | Runs |
  | --- | --- |
  | `:fabric:runClient`, `:neoforge:runClient` | development client |
  | `:fabric:runServer`, `:neoforge:runServer` | development dedicated server |
  | `:fabric:runGameTest`, `:neoforge:runGameTest` | development server GameTests |
  | `:fabric:runProductionServerGameTest` | Fabric server GameTests against the packaged mod |
  | `:neoforge:runProductionServerGameTest` | NeoForge server GameTests against the packaged mod, on a NeoForge server installed with the official installer |
  | `:fabric:runProductionClientGameTest` | Fabric client GameTests against the packaged mod, in Xvfb |
  | `:fabric:recordClientGameTest` | the production client GameTests, recorded to MP4 |
  | `runAllGameTests` | every GameTest run, one at a time: `:fabric:runGameTest` and `:neoforge:runGameTest`, then each [integration GameTest suite](#integration-gametests)'s development runs, then the Fabric production server and client runs and `:neoforge:runProductionServerGameTest`, then each suite's production runs |
  | `collectReleaseArtifacts` | copies both loader JARs to `build/libs` |

  With `fabricClientGameTests = false`, `:fabric:runProductionClientGameTest` and `:fabric:recordClientGameTest` do nothing; with `neoForgeGameTests = false`, neither do `:neoforge:runGameTest` and `:neoforge:runProductionServerGameTest` (no server is installed). `runAllGameTests` leaves out `:fabric:runGameTest` and `:fabric:runProductionServerGameTest` for `mod_side=client` and with `fabricServerGameTests = false`. `common` has no loader: Loom still lists `:common:runClient`, `:common:runServer` and `:common:runClientRenderDoc`, but they do nothing useful; run the loader tasks instead.

  [docs/GRADLE_RUN_TASKS.md](docs/GRADLE_RUN_TASKS.md) lists every `run*` and `record*` task in one place.
- `:neoforge:runProductionServerGameTest` is the NeoForge counterpart of Loom's Fabric production server run (Loom's production run tasks only launch Fabric). `:neoforge:installProductionServer` runs the official installer for `neoforge_version` (resolved and cached by Gradle as `net.neoforged:neoforge:<version>:installer`) with `--install-server` into `neoforge/build/minecraftmodconventions/neoforge-server/<version>`, once per version. The run then starts that installation's own launcher arguments with FML's `GameTestServer` entrypoint, which runs every GameTest and exits with the number of failed required tests; any failure or crash fails the task.
  - The mod is the release JAR (`:neoforge:jar`, what `collectReleaseArtifacts` ships) plus `:neoforge:productionGameTestJar` (classifier `production-gametest`: the `gametest` source set, including `common/src/gametest` and `minecraftmodconventions:empty`), loaded as one mod through FML's `fml.modFolders`, so `@EventBusSubscriber(modid = <mod_id>)` classes in the GameTest source set register as they do in development. Both sit in `mod-under-test/`, not `mods/`.
  - Other mods the server needs (BrainageLib, Cloth Config, ...) are not taken from the development classpath: declare them in `:neoforge`'s `productionRuntimeMods`, as on Fabric. They go into `mods/`.
  - NeoForge only ticks GameTests outside production, so the plugin adds a generated `ProductionGameTestTicker` to the `gametest` source set that ticks them on a production GameTest server (it does nothing in development). `RegisterGameTestsEvent` is not posted in production; register test functions with `RegisterEvent` and `test_instance` data, as the template does.
  - Run directory `neoforge/build/run/productionServerGameTest` (log in `logs/latest.log`), JUnit-like report `neoforge/build/test-results/runProductionServerGameTest/TEST-gametest.xml`. The task's `jvmArgs` and `programArgs` (for example `--tests <mod_id>:some_test*`) add to the launch.

  ```gradle
  // neoforge/build.gradle
  dependencies {
      productionRuntimeMods "io.github.brainage04:brainagelib-neoforge:${rootProject.brainagelib_version}"
  }
  ```
- For `mod_side` `client` or `both`, [DevAuth](https://github.com/DJtheRedstoner/DevAuth) (`DevAuth-fabric`/`DevAuth-neoforge`, version `1.2.2` or the `devauth_version` property) is on the development runtime of both loaders (Loom's `localRuntime`). It is not in the JARs, publications or production runs, and stays inactive until you enable it (`-Ddevauth.enabled=true` or its config file). Turn it off with `devAuth = false`.
- `neoforge.mods.toml` can use `${minecraft_version_range}`, which the plugin computes from `minecraft_version` to match Fabric's `"minecraft": "~${minecraft_version}"`: from that version up to, not including, the next minor version (`26.2` → `[26.2,26.3)`, `26.2.1` → `[26.2.1,26.3)`, `26.3-pre1` → `[26.3-pre1,26.4)`). Any other shape, such as a weekly snapshot (`26w14a`), fails the build. Minecraft is the only bounded dependency: on both loaders, the loader and libraries are open-ended (`>=x` in `fabric.mod.json`, `[x,)` in `neoforge.mods.toml`).

Turn off parts that don't apply:

```gradle
multiLoaderModConventions {
    fabricClientGameTests = true
    fabricServerGameTests = true
    neoForgeGameTests = true
    publishing = true
    devAuth = true                  // default: true unless mod_side=server
}
```

### Integration GameTests

GameTests that need other mods loaded (a minigame the mod plugs into, a modpack it must work beside) go in a named integration GameTest suite instead of the ordinary GameTests, so those mods, their mixins and their game rules never reach the ordinary runs:

```gradle
multiLoaderModConventions {
    integrationGameTests {
        compat {
            filter = 'examplemod:compat_*'
            fabricMods.add('maven.modrinth:other-mod:1.2.0+fabric')
            neoForgeMods.add('maven.modrinth:other-mod:1.2.0+neoforge')
            gitMod('https://github.com/brainage04/OtherMod.git', '0123456789abcdef0123456789abcdef01234567')
        }
    }
}
```

- **Sources:** the source set named after the suite, on both loaders: `common/src/<name>/java` and `common/src/<name>/resources`, plus `fabric/src/<name>` and `neoforge/src/<name>` for loader-specific files. It compiles against the `gametest` source set (and so the mod and its shared GameTest helpers) and the suite's extra mods. Its `test_instance` data goes in `common/src/<name>/resources/data/<mod_id>/test_instance/`.
- **Its own mod:** the suite's output is packaged by `:<loader>:<name>GameTestJar` and loaded as a separate mod, so it carries its own `fabric.mod.json` and `META-INF/neoforge.mods.toml` (a mod id such as `<mod_id>_<name>_gametest`) declaring the extra mods it depends on. Its test functions are registered the way the ordinary GameTests register theirs (`RegisterEvent` on NeoForge, an entrypoint on Fabric); its classes are on the classpath of its own runs only.
- **`filter`** (required) selects the suite's GameTests: Fabric API's `fabric-api.gametest.filter` on Fabric and `--tests` on NeoForge. The suite runs with the ordinary GameTests on the classpath too; the filter keeps them out of its runs.
- **Extra mods from Maven:** `fabricMods` and `neoForgeMods` take Maven coordinates, resolved without transitive dependencies (list every mod the suite needs). Declare their repository in the loader module, as for any other dependency; for Modrinth's Maven:

  ```gradle
  // fabric/build.gradle and neoforge/build.gradle
  repositories {
      exclusiveContent {
          forRepository { maven { url = 'https://api.modrinth.com/maven' } }
          filter { includeGroup 'maven.modrinth' }
      }
  }
  ```
- **Extra mods from Git:** `gitMod(repository, commit)` builds a multi-loader mod that is not published: the root task `buildGitMod<Repository><short commit>` checks the repository out at the full 40-character `commit` into `.gradle/minecraftmodconventions/git-mods/<repository>/<commit>`, runs that checkout's own `./gradlew collectReleaseArtifacts` on the JVM running the build, and gives each loader its release JAR from the checkout's `build/libs` (`<archives_base_name>-<version>.jar` on Fabric, `<archives_base_name>-neoforge-<version>.jar` on NeoForge). The checkout is kept, so the task is up to date until its JARs change. The task runs plain `git`, which inherits the environment: the repository must be readable without credentials (a public repository or a local path) or through git's own environment configuration, which is how the reusable workflows clone a private repository (see [Workflows](#workflows)).
- **Tasks:** `:<loader>:run<Name>GameTest` (development, run directory `<loader>/build/run/<name>GameTest`) and `:<loader>:runProduction<Name>GameTest` (production server, run directory `<loader>/build/run/production<Name>GameTest`, report under `build/test-results/` on NeoForge). The production runs load the release JAR, the GameTest JAR, the suite's JAR and the extra mods; Fabric's also loads `productionRuntimeMods`, NeoForge's puts the suite and extra mods into `mods/` beside `productionRuntimeMods`. Every run starts with a fresh world. `runAllGameTests` runs them all; the Fabric runs follow `fabricServerGameTests` and the NeoForge runs `neoForgeGameTests`.
- **Ordinary builds:** `build` checks the suite's sources with Checkstyle without compiling them, so it never fetches or builds the extra mods; only the suite's own tasks do. The ordinary `gametest` source set, its runs and the release JARs never contain the suite or its extra mods.
- **CI:** `reusable-production-gametests.yml` runs `runAllGameTests`, which fetches and builds the extra mods itself; consumers add no job or input.

Outside the multi-loader conventions, apply `io.github.brainage04.integration-gametests` to a Fabric project with `production-gametests` (or an Architectury Loom project with a `gametest` source set and a `gameTest` server run) and declare the suites there with `mods` in place of `fabricMods`/`neoForgeMods` and sources in `src/<name>`. With `production-gametests`, each suite also gets `runProduction<Name>GameTest`, which `runAllProductionGameTests` includes. Suites run server GameTests in an unobfuscated (no-remap) Minecraft, without remapping the extra mods.

### Repositories

Every module gets only Maven Central, `https://maven.fabricmc.net/` (`net.fabricmc` groups), `https://maven.neoforged.net/releases/` (`net.neoforged` groups) and `https://maven.architectury.dev/` (`dev.architectury` groups); owned libraries (the conventions runtime, HudRendererLib, BrainageLib) come from Maven Central or a sibling `build/local-repo` (see [Workspace dependencies](#workspace-dependencies)). There is no `mavenLocal()` and no GitHub-release repository. A mod that needs another repository declares it in the module that declares the dependency, restricted to that dependency's group:

```gradle
repositories {
    exclusiveContent {
        forRepository { maven { url = "https://maven.shedaniel.me/" } }
        filter { includeGroup "me.shedaniel.cloth" }
    }
}
```

## Single-loader Fabric mods

Apply `fabric-mod-conventions` plus the leaf plugins you need. It requires the multi-loader properties minus `neoforge_version`, and uses this layout:

| `mod_side` | Main source layout | Client GameTests | Server GameTests |
| --- | --- | --- | --- |
| `both` | `src/main` plus `src/client` | yes | yes |
| `client` | `src/main` | yes | no |
| `server` | `src/main` | no | yes |

```gradle
fabricModConventions {
    repositoriesEnabled = true
    javaEnabled = true
    sourcesJarEnabled = true
    resourceExpansionEnabled = true
    licenseJarEnabled = true
    additionalFabricModJsonProperties.add("dependency_version")
}
```

Every option defaults to `true`. `fabric.mod.json` expansion fails on a missing or blank property.

## Workspace dependencies

```gradle
workspaceDependencies {
    siblingMaven("HudRendererLib") {
        coordinate.set("io.github.brainage04:hudrendererlib:${hudrendererlib_version}")
    }
}
```

The sibling repository (`../<name>/build/local-repo` by default; override with `siblingDirectory` or `localRepository`) serves only that module and is checked before Maven Central. Publish a sibling with `./gradlew publishAllPublicationsToLocalRepository`.

To give every dev client the same keybinds and video settings, set this in `~/.gradle/gradle.properties`:

```properties
minecraftmodconventions.devClientOptions=/path/to/options.txt
```

The file is copied into the client run directory before every `runClient` launch, overwriting changes made in-game. Automated client launches never use it.

## Automated client launches

Client GameTests (`runClientGameTest`), production client GameTests (`runProductionClientGameTest`) and recordings start from an `options.txt` written by the plugins before every launch (`prepareClientGameTestRun`, `prepareProductionGameTestRuns`), from one of two profiles that are the same for every mod. Settings the client saves during a run never reach the next one.

| Option | `test` | `showcase` |
| --- | --- | --- |
| `version` | `4903` (26.2) | same |
| `lang` | `en_us` | same |
| `fullscreen` | `false` | `true` |
| `overrideWidth`, `overrideHeight` | `854`, `480` | same |
| `guiScale` | `2` | same |
| `renderDistance` | `5` | `12` |
| `simulationDistance` | `5` | same |
| `maxFps`, `enableVsync`, `inactivityFpsLimit` | `30`, `false`, `"minimized"` | same |
| `graphicsPreset` | `"fast"` | `"fancy"` |
| `ao`, `cutoutLeaves`, `entityShadows` | `false` | `true` |
| `improvedTransparency`, `renderClouds` | `false`, `"false"` | same |
| `particles` | `2` (minimal) | same |
| `biomeBlendRadius`, `chunkSectionFadeInTime`, `entityDistanceScaling` | `0`, `0.0`, `0.5` | same |
| `mipmapLevels`, `maxAnisotropyBit`, `menuBackgroundBlurriness`, `prioritizeChunkUpdates`, `weatherRadius` | `0`, `0`, `0`, `0`, `5` | same |
| `soundDevice` | `""` | the recorder's private sink |
| `soundCategory_master` | `0.0` | `0.7` |
| `soundCategory_music` | `0.0` | `0.0` |
| other `soundCategory_*` (`record`, `weather`, `block`, `hostile`, `neutral`, `player`, `ambient`, `voice`, `ui`) | `0.0` | `1.0` |
| `narrator`, `narratorHotkey` | `0`, `false` | same |
| `tutorialStep`, `onboardAccessibility` | `none`, `false` | same |
| `joinedFirstServer` | `true` (`clientGameTestRecorder.disableSocialInteractionsToast`) | same |
| `skipMultiplayerWarning`, `realmsNotifications`, `telemetryOptInExtra` | `true`, `false`, `false` | same |
| `musicToast`, `hideSplashTexts`, `showAutosaveIndicator` | `"never"`, `true`, `false` | same |
| `pauseOnLostFocus` | `false` | same |
| `autoJump`, `toggleSprint`, `toggleCrouch` | `false` | same |

- `showcase` is used when the recorder gives the run its private PulseAudio sink (`recordClientGameTest` passes it as `GTR_RECORDING_AUDIO_DEVICE`), `test` otherwise, including runs that only bring their own display (`GTR_RECORDING_MANAGED_XVFB=true`). Its sound only reaches that sink.
- Client GameTests see the `overrideWidth` x `overrideHeight` window whatever the display size, so the GUI scale and layout are the same in every run.

With `client-gametest-recorder` and `production-gametests` both applied, the runtime helper joins both client runs, and each launch has a startup deadline: the client must finish loading (the point where its GameTests start) within 300 seconds. Otherwise the task stops the client and every process it started (`xvfb-run` and its `Xvfb` included) and fails with a message naming them. Change the deadline with `clientGameTestRecorder.clientStartupTimeoutSeconds` or the Gradle property `minecraftmodconventions.clientStartupTimeoutSeconds`.

## Client GameTest recording

```shell
GTR_RECORDING_PROFILE=smoke ./gradlew --no-daemon :fabric:recordClientGameTest
```

Needs `ffmpeg`/`ffprobe` (X11 capture, PulseAudio input, H.264/AAC), Xvfb with `xdpyinfo`, and `pactl`. Set `PULSE_SERVER` for a fully isolated session. The desktop's default sink is never changed.

```gradle
clientGameTestRecorder {
    disableUnsecureChatToast = true
    disableSocialInteractionsToast = true
    disableRecipeToasts = true
    disableAdvancementToasts = true
    disableAdvancementChatMessages = true
    clientStartupTimeoutSeconds = 300
}
```

- The five `disable…` options default to `true`; the toasts and chat messages they name stay hidden in every client GameTest run.
- The recording HUD panel sits in the top-right corner, clear of chat in the bottom-left. `GTR_RECORDING_PROFILE=showcase` shows a title card there instead of the diagnostic overlay.
- `GTR_RECORDING_PRE_PADDING_SECONDS` / `GTR_RECORDING_POST_PADDING_SECONDS`: `0`–`60`, default `0.5`.
- Each recording writes metadata with tick and frame boundaries, effective padding and any capture gaps. A run fails if game audio is not routed to the recorder's sink or cannot be decoded.

### Dedicated-server harness

```java
ClientGameTestServers.withDedicatedServer(context, "Example GameTest", server -> {
    server.runOnServer(ExampleClientGameTest::prepare);
    // Exercise and assert the connected client.
});

ClientGameTestServers.withDedicatedServer(context, "Example GameTest",
        server -> server.runOnServer(ExampleClientGameTest::buildStage), // before the client joins
        server -> {
            // The client is in the world, already on the stage.
        });
```

The harness starts the server, connects the client, and always disconnects, finishes the recording and stops the server, even when the callback fails. The optional `beforeJoin` callback (also with a `Properties` argument) runs after the server has started and before the client connects; a recording is trimmed to start 0.5 s (`GTR_RECORDING_PRE_PADDING_SECONDS`) before the first world tick, so setup done there (blocks, entities, game rules, teams) never shows. To place the client player, set the world spawn there: `minecraftServer.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, pos, yaw, pitch))` plus `GameRules.RESPAWN_RADIUS` set to `0` puts a new player on that block, facing that way. A player that joins in `beforeJoin` (a Carpet fake player) takes a slot, so raise `max-players` in the properties for it. Java helpers live in `io.github.brainage04.minecraftmodconventions`.

## Production GameTests

```gradle
productionGameTests {
    runtimeModDependencies.add("me.fzzyhmstrs:fzzy_config:${fzzy_config_version}")
    runtimeLibraryDependencies.add("com.github.twitch4j:twitch4j:${twitch4j_version}")
}
```

Loom's production runs do not inherit the dev classpath, so extra mods and libraries must be listed here; Fabric API is added automatically. Tasks: `productionGameTestJar`, `runProductionClientGameTest` (Xvfb by default), `runProductionServerGameTest`, and in single-loader builds `runAllProductionGameTests` (multi-loader builds use the root `runAllGameTests`). `includeClient`, `includeServer`, `clientUseXvfb`, `clientJvmArgs` and `serverProgramArgs` override the defaults.

## Publishing

`mod-publishing` enables a destination only when its block is configured:

```gradle
modPublishing {
    github { repository.set("brainage04/example-mod") }
    modrinth { projectId.set("example-project") }
    curseforge { projectId.set("123456") }
}
```

`publishGithub`, `publishModrinth` and `publishCurseforge` can be retried independently; `publishMods` runs all enabled ones. The Modrinth description is synced from `README.md` and the icon from `assets/<mod_id>/icon.png`. `build` and `check` never contact these services.

`maven-central-publishing` is for libraries. See [docs/PUBLISHING.md](docs/PUBLISHING.md) for credentials and the release command.

## Workflows

Consumer workflows call these and only supply triggers, profiles, artifact patterns and project IDs:

- `reusable-mod-build.yml` – `build` (`gradle_task` overrides it; a multi-loader mod that also calls `reusable-production-gametests.yml` passes `build -x :fabric:runGameTest`, since Loom's `check` runs that suite and `runAllGameTests` runs it again)
- `reusable-client-gametests.yml` – Fabric client GameTests and recordings (`:fabric:runProductionClientGameTest`, `:fabric:recordClientGameTest`)
- `reusable-production-gametests.yml` – `runAllGameTests`, every development and production GameTest run, including integration GameTest suites, which fetch and build their extra mods in Gradle (`gradle_task` overrides it)
  A consumer with its own exact-coverage sharding runner may set `test_runner: tools/run_production_shards.py`.
  The workflow invokes that repository-local Python script with `build/test-lanes/ci` followed by
  `--no-daemon` and `gradle_task` arguments, instead of invoking Gradle directly. The CI job's
  existing memory admission remains authoritative; the script must not acquire a second heavy slot.
- `reusable-neoforge-gametests.yml` – `:neoforge:runProductionServerGameTest` alone (`gradle_task` overrides it, for example with `:neoforge:runGameTest`)
- `reusable-multiloader-release.yml` – GitHub, Modrinth and CurseForge release of both loader JARs

Every reusable workflow takes a `runner` input, a JSON `runs-on` value defaulting to `"ubuntu-24.04"`; private mods pass their self-hosted runner labels:

```yaml
    with:
      runner: '["self-hosted","minecraft"]'
```

The build and GameTest workflows take `timeout_minutes`, the time limit of each step that runs Gradle (default 20, client GameTests 40); a mod whose `runAllGameTests` takes longer passes its own. The jobs themselves may take 360 minutes, so a self-hosted runner can hold a job until it has capacity (for example in a job-started hook) without eating into the steps' limits. None of them passes `--refresh-dependencies`: it made every job re-check every dependency against the remote repositories, and a network hiccup failed the build.

A mod that depends on an owned library that is not on Maven Central (BrainageLib) passes `prepare_siblings`, a space-separated list of brainage04 repositories, to `reusable-mod-build.yml`, the three GameTest workflows and `reusable-multiloader-release.yml`. Each one is cloned (default branch) into `../<name>` and published with `publishAllPublicationsToLocalRepository`, so its `siblingMaven(...)` declaration resolves from `../<name>/build/local-repo`:

```yaml
    with:
      prepare_siblings: BrainageLib
```

A mod that jar-in-jars the Baritone fork (TwitchPlaysMinecraft) passes `prepare_baritone: true` to the same workflows. Each Gradle build then first clones the fork's `minecraft-<minecraft_version>` branch into `../baritone` and runs its `publishAllPublicationsToLocalBaritoneRepository`.

A build that clones a private brainage04 repository (SparringBots' `gitMod` of the private BrainageMinigames, or a private `prepare_siblings` library) passes `git_ssh_repositories`, the space-separated repository names, and the secret `git_ssh_key`, the private half of a read-only deploy key for them, to `reusable-mod-build.yml`, the three GameTest workflows and `reusable-multiloader-release.yml` (which forwards both to its prepare, Modrinth and CurseForge jobs); `reusable-release-prepare.yml`, `reusable-publish-modrinth.yml` and `reusable-publish-curseforge.yml` take them directly. `reusable-publish-github.yml` clones nothing but the public Baritone fork and takes neither. Right after the stale-sibling cleanup the job writes the key with mode 600 into `${RUNNER_TEMP}/git-ssh` and exports `GIT_SSH_COMMAND` (that key only, host keys recorded in a `known_hosts` file in the same directory) plus `GIT_CONFIG_COUNT`/`GIT_CONFIG_KEY_n`/`GIT_CONFIG_VALUE_n` through `$GITHUB_ENV`, before any `prepare_*` clone and the Gradle builds. They rewrite `https://github.com/brainage04/<name>.git` to `git@github.com:brainage04/<name>.git` for the later steps of that job only; nothing is written to the global git config or `~/.ssh`, and a final `if: always()` step deletes the directory. Without the secret the step does nothing.

```yaml
    with:
      git_ssh_repositories: BrainageMinigames
    secrets:
      git_ssh_key: ${{ secrets.BM_DEPLOY_KEY }}
```

Self-hosted runners keep their work tree between jobs, so every reusable workflow job deletes everything beside its checkout (`..`) right after checking out, before any `prepare_*` step; a stale `../MinecraftModConventions` would otherwise be picked up by `settings.gradle`'s `includeBuild` in place of the released plugin.

## Fleet audit

`scripts/mod_fleet.py` checks every sibling mod against the versions, structure, workflows and recording policy in `scripts/mod-fleet.json`, and can record them all.

```shell
./scripts/mod_fleet.py audit --github --strict
./scripts/mod_fleet.py record [--include <repository>] [--dry-run]
```

`record` writes to a timestamped `~/Downloads/minecraft-mod-gametest-recordings-*` directory; `--output <dir> --resume` reruns only the repositories that failed. For a new Minecraft release, pass `--minecraft-version`, `--loader-version`, `--fabric-api-version`, `--java-version` and `--conventions-version` to `audit`, then update the manifest.
