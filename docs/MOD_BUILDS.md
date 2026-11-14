# Mod builds

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
  | `:fabric:runVanillaServerClientGameTest` | the production client GameTests against a plain vanilla server ([vanilla-server checks](CLIENT_GAMETESTS.md#vanilla-server-checks)); not part of `runAllGameTests` |
  | `runAllGameTests` | every GameTest run, one at a time: `:fabric:runGameTest` and `:neoforge:runGameTest`, then each [integration GameTest suite](#integration-gametests)'s development runs, then the Fabric production server and client runs and `:neoforge:runProductionServerGameTest`, then each suite's production runs |
  | `collectReleaseArtifacts` | copies both loader JARs to `build/libs` |

  With `fabricClientGameTests = false`, `:fabric:runProductionClientGameTest` and `:fabric:recordClientGameTest` do nothing; with `neoForgeGameTests = false`, neither do `:neoforge:runGameTest` and `:neoforge:runProductionServerGameTest` (no server is installed). `runAllGameTests` leaves out `:fabric:runGameTest` and `:fabric:runProductionServerGameTest` for `mod_side=client` and with `fabricServerGameTests = false`. `common` has no loader: Loom still lists `:common:runClient`, `:common:runServer` and `:common:runClientRenderDoc`, but they do nothing useful; run the loader tasks instead.

  [docs/GRADLE_RUN_TASKS.md](GRADLE_RUN_TASKS.md) lists every `run*` and `record*` task in one place.
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
- **Extra mods from Git:** `gitMod(repository, commit)` builds a multi-loader mod that is not published: the root task `buildGitMod<Repository><short commit>` checks the repository out at the full 40-character `commit` into `.gradle/minecraftmodconventions/git-mods/<repository>/<commit>`, runs that checkout's own `./gradlew collectReleaseArtifacts` on the JVM running the build, and gives each loader its release JAR from the checkout's `build/libs` (`<archives_base_name>-<version>.jar` on Fabric, `<archives_base_name>-neoforge-<version>.jar` on NeoForge). The checkout is kept, so the task is up to date until its JARs change. The task runs plain `git`, which inherits the environment: the repository must be readable without credentials (a public repository or a local path) or through git's own environment configuration, which is how the reusable workflows clone a private repository (see [Workflows](WORKFLOWS.md#workflows)).
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
| `version` | `5023` (26.3) | same |
| `lang` | `en_us` | same |
| `fullscreen` | `false` | `false` (SDL3 cannot go fullscreen on Xvfb, which has no window manager) |
| `overrideWidth`, `overrideHeight` | `854`, `480` | the recorder display, `1920`, `1080` by default (`GTR_RECORDING_XVFB_SCREEN`) |
| `guiScale` | `2` | `3` (the overlay standard: 1920x1080 at scale 3 is a 640x360 GUI) |
| `renderDistance` | `5` | `12` |
| `simulationDistance` | `5` | same |
| `maxFps`, `enableVsync`, `inactivityFpsLimit` | `30`, `false`, `"minimized"` | same, and the capture rate (`30` by default, `60` at `GTR_RECORDING_FPS=60`) for a recording that renders on a card |
| `graphicsPreset` | `"fast"` | `"fancy"` |
| `ao`, `cutoutLeaves`, `entityShadows` | `false` | `true` |
| `improvedTransparency`, `renderClouds` | `false`, `"false"` | same |
| `particles` | `2` (minimal) | `0` (all), so effect particles such as poison and wither swirls show |
| `biomeBlendRadius`, `chunkSectionFadeInTime`, `entityDistanceScaling` | `0`, `0.0`, `0.5` | same |
| `mipmapLevels`, `maxAnisotropyBit`, `menuBackgroundBlurriness`, `prioritizeChunkUpdates`, `weatherRadius` | `0`, `1`, `0`, `0`, `5` | same |
| `soundDevice` | `""` | the recorder's private sink, or `""` for a game take, which plays into its own loopback device |
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

- `showcase` is used for a run `recordClientGameTest` launched, which it marks with the recording start signal it passes the client (`CLIENT_GAMETEST_RECORDING_START_SIGNAL`), and for a run that names the recorder's private PulseAudio sink (`GTR_RECORDING_AUDIO_DEVICE`, as an x11grab take does); `test` otherwise, including runs that only bring their own display (`GTR_RECORDING_MANAGED_XVFB=true`). A game take names no sink, and its sound comes from its own loopback device rather than from one.
- Client GameTests see the `overrideWidth` x `overrideHeight` window whatever the display size, so the GUI scale and layout are the same in every run of a profile. A `showcase` recording is always 1920x1080 at `guiScale` 3, a 640x360 GUI, whatever size its recorder display is; on a card display the capture starts at the window's own origin, so the frame holds the window wherever vanilla centres it, and a display too small for the window, or a window that does not lie wholly on it, fails the recording rather than shrinking that standard; the same test's `test` run uses an 854x480 window at `guiScale` 2 (427x240). A test that depends on screen coordinates or text wrapping can behave differently while recorded.
- On Linux, every automated client launch (production and development client GameTests, every Loom run task with `GameTest` in its name on `:fabric` and `:neoforge`, and the recorder) sets `SDL_VIDEO_DRIVER=x11` and removes `WAYLAND_DISPLAY`. Minecraft 26.3 opens its window through SDL3, which otherwise prefers a Wayland desktop over `DISPLAY` (Xvfb). Interactive runs such as `runClient` are left alone.

With `client-gametest-recorder` and `production-gametests` both applied, the runtime helper joins both client runs, and each launch has a startup deadline: the client must finish loading (the point where its GameTests start) within 300 seconds. Otherwise the task stops the client and every process it started (`xvfb-run` and its `Xvfb` included) and fails with a message naming them. Change the deadline with `clientGameTestRecorder.clientStartupTimeoutSeconds` or the Gradle property `minecraftmodconventions.clientStartupTimeoutSeconds`.

With both plugins applied, a launch that ends before the client finished loading fails as well, whatever its exit code, with a message saying so (the launch's own failure is its cause): a client that cannot open its window (no display, or no working graphics driver under Xvfb) exits without running any GameTest.

## Production GameTests

```gradle
productionGameTests {
    runtimeModDependencies.add("me.fzzyhmstrs:fzzy_config:${fzzy_config_version}")
    runtimeLibraryDependencies.add("com.github.twitch4j:twitch4j:${twitch4j_version}")
}
```

Loom's production runs do not inherit the dev classpath, so extra mods and libraries must be listed here; Fabric API is added automatically. Tasks: `productionGameTestJar`, `runProductionClientGameTest` (Xvfb by default), `runVanillaServerClientGameTest` (the same client against a vanilla server, see [vanilla-server checks](CLIENT_GAMETESTS.md#vanilla-server-checks)), `runProductionServerGameTest`, and in single-loader builds `runAllProductionGameTests` (multi-loader builds use the root `runAllGameTests`; neither includes the vanilla-server run). `includeClient`, `includeServer`, `clientUseXvfb`, `clientSilenceAudio`, `clientJvmArgs` and `serverProgramArgs` override the defaults. Under `recordClientGameTest`, `client-gametest-recorder` turns `clientUseXvfb` and `clientSilenceAudio` off, so the production client uses the recorder's display and audio sink.
