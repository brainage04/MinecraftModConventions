# MinecraftModConventions

Shared Gradle plugins, GitHub workflows and client GameTest helpers for my Minecraft mods. 3.1.x and later target Minecraft 26.3; 3.0.x is the Minecraft 26.2 line.

## Release notes

**3.6.0 (unreleased) cuts a game take where each caption first appears.** The game capture records, for every caption, the first captured frame the HUD drew it into and that frame's present time (`gameCaptureCaptionMarks` in the recording's boundaries), and `scripts/trim_stages.py` opens each clip there instead of reading the picture, so the world behind the caption panel no longer decides a cut. A stage's caption is found by its id: show it with the stage's id, or with that id behind the mod's prefix and a dot, and give two stages whose captions end in the same id their full ids. A screen grab (`GTR_RECORDING_CAPTURE=x11grab`) has no marks and is still cut from the caption panel's pixels, now by the step a new caption makes in the panel, watched up to 2 s after a stage's start; where the picture cannot tell (the panel changes twice after a stage's clock start, a caption comes more than 0.5 s after its start, or the world behind the panel moves too fast to see two near-identical captions apart), the script fails naming `--clock` instead of guessing. See [Client GameTest recording](#client-gametest-recording) for what the fallback cannot see.

**3.5.0 adds the [recording rules](#shooting-a-recording).** `showStep` and `showStepAndHold` fail a caption that says what was removed or changed, so a mod whose captions do that fails its client GameTests until it rewords them. Recordings show every particle (`particles:0`), and `GTR_RECORDING_FPS=60` records the video at 60 frames a second on a card display, where the client's frame cap follows the capture rate; a client that cannot draw 60 presents fewer, and the measured 60 fps take presented 43.6 frames a second and repeated 997 of its 2816 video frames. A recording now captures the game's own picture and sound (`GTR_RECORDING_CAPTURE=game`, see [Client GameTest recording](#client-gametest-recording)): the client copies each finished frame out of its render target and plays its sound into an OpenAL loopback device, so every video frame is one whole picture, none can tear, and the machine plays no sound. `GTR_RECORDING_CAPTURE=x11grab` grabs the screen as before, and on a card display such a take replaces the frames x11grab tears with the frame before each. It also replaces a frame where the screen changed below a line one capture before it changed above it, such as a chat bar opening whose suggestion list appears above it a capture later, which repeats the previous picture for one frame. On a 25-minute TwitchPlaysMinecraft take it replaced 134 of 46,156 frames, tears and such frames together. A tear on a frame where the caption or another overlay also changes can go unrepaired and unreported. New checks can fail a recording: an x11grab card take that still has a torn frame after that repair fails unless `GTR_RECORDING_TEAR_CHECK=warn`, a `GTR_RECORDING_FPS` above 30 that gets the virtual display fails before the client starts, an encoder that cannot encode a test frame fails before the client starts, and a game take fails on a graphics backend other than OpenGL, on an OpenAL without `ALC_SOFT_loopback`, or when its capture stops mid-take; `GTR_RECORDING_CAPTURE=x11grab` is the fallback for the last three. Every recording whose mod keeps MinecraftModConventions' caption panel shows a [performance overlay](#shooting-a-recording) under it, which a test can move or hide with `ClientGameTestRecorder.placePerformanceOverlay`, and keeps its numbers as `perf*` values in the metadata. `GTR_RECORDING_VIDEO_ENCODER=h264_nvenc` encodes the capture on an NVIDIA card, and `GTR_RECORDING_TRACE=true` writes the time of every present beside the boundaries. Self-hosted runners now use SDL's EGL path too, which Minecraft 26.3 clients need there, so a self-hosted runner needs EGL installed (`libegl1` and `libegl-mesa0`).

**3.4.0 makes the strict quality policy the default.** `check` and `build` now fail on unformatted code (Spotless with google-java-format AOSP), on Checkstyle findings, on deprecation, unchecked and rawtypes warnings, and on the [deprecated GameTest API check](#deprecated-gametest-api-check). A consumer that cannot take all of that in one go passes `-PstrictQuality=false`, which turns the whole policy off and leaves the three lints as warnings, or `-PstrictUnchecked=false`, which keeps formatting, Checkstyle and deprecation as errors and turns the unchecked and rawtypes lints off, so nothing is reported for them. The reusable workflows set neither property; the route that reaches every job is the project's `gradle.properties` (`strictQuality=false` or `strictUnchecked=false`), which the plugin reads on every Gradle invocation, including the release-preparation and client GameTest jobs. The `gradle_task` input, which carries Gradle tasks and arguments, exists in `reusable-mod-build.yml`, `reusable-production-gametests.yml` and `reusable-neoforge-gametests.yml` only, so it does not cover the release-preparation or client GameTest jobs by itself (the Modrinth and CurseForge workflows take a `gradle_task` that names their publish task). A project whose `gametest` source set exists also gets the film-only [recording source set](#film-only-code-in-the-recording-source-set) (`src/recording/java` and `src/recording/resources`), which is empty until used, compiles against everything a GameTest compiles against including the mod's own main and client classes, and is packaged into the production GameTest JAR.

## Plugins

| Plugin (`io.github.brainage04.…`) | Apply to | Purpose |
| --- | --- | --- |
| `multiloader-mod-conventions` | root of a `common`/`fabric` mod, optionally with `neoforge` | **The standard entry point.** Configures the included modules with Architectury Loom and applies every plugin below except `fabric-mod-conventions` and `maven-central-publishing`. Adds the [vanilla dimension identity](#vanilla-dimension-identity-check), [tag identity](#vanilla-tag-identity-check) and [world height](#hardcoded-world-height-check) checks, and the [deprecated GameTest API](#deprecated-gametest-api-check) check, to every module. |
| `fabric-mod-conventions` | single-loader Fabric project | Fabric Loom, project identity, standard dependencies and repositories, Java settings, side-aware source layout, access widener, sources JAR, `fabric.mod.json` expansion, license in the JAR, and the [vanilla dimension identity](#vanilla-dimension-identity-check), [tag identity](#vanilla-tag-identity-check) and [world height](#hardcoded-world-height-check) checks, plus the [deprecated GameTest API](#deprecated-gametest-api-check) check. |
| `client-gametest-recorder` | Fabric project | `recordClientGameTest`: records client GameTests to MP4 in an isolated Xvfb display, from the game's own picture and sound or from a screen grab and an audio sink. Adds the runtime helpers to the `gametest` source set. |
| `production-gametests` | Fabric project | Creates the `gametest` source set, the film-only [`recording`](#film-only-code-in-the-recording-source-set) source set, and runs GameTests against the packaged mod through Loom's production client and server. |
| `integration-gametests` | Fabric or NeoForge Loom project with a `gametest` source set | Named GameTest suites that run with extra mods (Maven artifacts or pinned Git commits) loaded, in their own source set and runs. |
| `workspace-dependencies` | any project | Prefers sibling checkouts' `build/local-repo` over Maven Central, and can share one dev-client `options.txt`. |
| `java-quality-conventions` | root project | Spotless (google-java-format, AOSP), Checkstyle and javac lint as errors, under the two [strict switches](#java-quality-switches); strict is the default, `-PstrictQuality=false` opts a project out, `-PstrictUnchecked=false` leaves unchecked and rawtypes as warnings. |
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
  | `:fabric:runVanillaServerClientGameTest` | the production client GameTests against a plain vanilla server ([vanilla-server checks](#vanilla-server-checks)); not part of `runAllGameTests` |
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

## Vanilla dimension identity check

`fabric-mod-conventions` and `multiloader-mod-conventions` (in `common`, `fabric` and `neoforge`) register `checkVanillaIdentity` under `check`. It warns about the Java sources of the `main` and `client` source sets that compare with a vanilla dimension, writes each `file:line` to `build/reports/vanilla-identity.txt`, and names them in the build output:

- `Level.OVERWORLD`, `Level.NETHER` or `Level.END` (also through `ServerLevel`/`ClientLevel` or a static import) or a `BuiltinDimensionTypes` key, compared with `==`, `!=`, `equals(…)` or `is(…)`;
- such a key on the same line as `dimension()`, `dimensionType()` or `dimensionTypeRegistration()`, as in `matches(Level.NETHER, level.dimension())`.

Other uses of the keys, such as `server.getLevel(Level.END)`, and comparisons with a mod's own dimensions pass. GameTest and other source sets are not checked. The patterns are in `gradle-plugin/java-quality-conventions/src/main/resources/…/vanilla-identity-patterns.txt`.

A comparison is right when the feature is about that dimension by name. It is wrong when it stands in for a property
the game reads from data. A datapack dimension, or another mod's dimension, that behaves like this one needs the same
treatment in that case.

- **A property:** sleeping, water evaporating, fog, terrain. Read the data the game itself reads: environment attributes (`level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos)`, `RESPAWN_ANCHOR_WORKS`, `WATER_EVAPORATES`, …), `level.dimensionType()` properties, tags and game rules. A bed asks the rule its own block gives (`AbstractBedBlock#getBedRule(level, pos)`), so a straw bed reads `STRAW_BED_RULE`. On the client, confirm the attribute is synced (`EnvironmentAttribute.isSyncable()`) or decide it server-side: `BED_RULE` and `STRAW_BED_RULE` are not synced (`EnvironmentAttributes.java:124-129`) and read their defaults on the client.
- **A place:** a Nether kill score, a dungeon theme per vanilla dimension, a command that lists the players in the Nether. The identity check is right.

Two examples from the fleet:

```java
// Place: ProceduralDungeon writes one theme per vanilla dimension, and the datagen for the Nether theme
// places its structures with the Nether's tags.
if (theme.dimension.equals(Level.NETHER)) { ... }

// Property, and wrong: baritone refuses a water-bucket fall in the Nether. Vanilla asks
// level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)
// (BucketItem.java:122 in 26.3, :123 in 26.2, :124 in 1.21.11).
if (dimension() == Level.NETHER) return;
```

A mod that really targets one vanilla dimension, or a client mod with no synced rule to read, silences the line or the line above it:

```java
// vanilla-identity: the mod floods only the vanilla End
if (level.dimension() != Level.END) return;
```

The marker is optional. A marker without a reason does not silence anything and still warns. The warning prints only when the task runs: a second build with unchanged sources finds the task up to date, prints no warning and keeps the report from the last run.

## Vanilla tag identity check

`checkVanillaTagIdentities` runs over the same source sets as the dimension check and warns about a comparison or membership test of one vanilla block, item or biome that a vanilla tag also lists. It writes the hits and the tag to use to `build/reports/vanilla-tag-identities.txt`:

```java
if (block == Blocks.RED_BED) { ... }          // block tag #minecraft:beds
if (stack.is(Items.DIAMOND_SWORD)) { ... }    // item tag #minecraft:swords
Set.of(Blocks.RED_BED, Blocks.BLACK_BED)      // block tag #minecraft:beds
```

The identities come from the named Minecraft jars Loom builds for the project (`LoomGradleExtensionAPI#getNamedMinecraftJars` in the conventions plugins): the check reads their `data/minecraft/tags`, so the map always matches the version the mod compiles against instead of a list copied from one release. A tag only makes a hit when its name ends the identity id, ignoring a plural `s` and a leading `is_`: `beds` ends `red_bed`, `oak_logs` ends `oak_log`, `is_forest` ends `forest`. A list such as `beacon_base_blocks` or `bookshelf_books` mentions the identity for one use without naming it, and the check leaves the comparison alone. Tags that only classify a block for a tool, an enchantment, a spawn rule or a structure placement (`mineable/*`, `needs_*_tool`, `enchantable/*`, `has_structure/*`, …) are left out too, because replacing `block == Blocks.ICE` with the pickaxe tag would be wrong. Placing or giving a specific block is a place, not a comparison, and a list built from items (`List.of(new ItemStack(Items.ARROW))`) gives or displays them, so neither is reported. Type checks such as `instanceof BedBlock` are not covered: the tag data names block ids, and one class backs many ids.

The report names the tag id (`block tag #minecraft:beds`) rather than a Java constant, because 26.x moved many tags into `BlockItemTags` and a constant minted from the path would not compile there.

A comparison is right when the feature is about that particular block or item by name, and wrong when it stands in for
a property of the tag (`#minecraft:beds`, `#minecraft:swords`, `#minecraft:is_forest`, …) that a datapack can extend.
`// vanilla-tag: <reason>` silences the line or the line above it, and a marker without a reason still warns.

## Hardcoded world height check

`checkWorldHeight` warns about the literal build limits of the vanilla overworld: `-64`, `319` and `320` next to a `y` or a height name, and `0` or `256` next to a name that says what it holds. It writes the hits to `build/reports/world-height.txt`. It only reports a limit that is compared with, assigned to or passed as a height: `if (y < -64)`, `int maxY = 320;`, `new BlockPos(x, 320, z)`, `Math.min(320, y)`, and not `new BlockPos(x, 0, z)` or `int width = 256`.

A world's build limits come from its dimension type, so a datapack dimension can be 256 blocks tall or start at 0. `level.getMinY()`, `level.getMaxY()` and `level.getHeight()` answer for the level the code runs in, and `level.dimensionType().minY()` and `height()` when no level is at hand. `// world-height: <reason>` silences the line or the line above it, and a marker without a reason still warns.

## Deprecated GameTest API check

`fabric-mod-conventions` and `multiloader-mod-conventions` (in `common`, `fabric` and `neoforge`) register `checkDeprecatedGameTestApis` under `check` over every Java source set. It **fails** the build on `GameTestHelper#makeMockServerPlayerInLevel()`, which is `@Deprecated(forRemoval = true)` in 26.3 (`net/minecraft/gametest/framework/GameTestHelper.java:395` in the 26.3 sources) and deleted in a later release. A comment or a string literal about the method is not a call and does not fail the build. The check is part of the strict policy, so `-PstrictQuality=false` skips it along with Spotless, Checkstyle and the lints; `-PstrictUnchecked=false` does not.

A `@SuppressWarnings("removal")` or `@SuppressWarnings("deprecation")`, on a method, a class or a local declaration, hides a deprecated call from javac, so `-Werror` alone cannot fail on it; the check also reports every such annotation in the scanned sources. A suppression that is understood and still needed keeps a reason on the annotation's line or the line above it, `// deprecated-api: <reason>`; a marker without a reason still fails.

`GameTestHelper#makeMockServerPlayer(GameType)` (`GameTestHelper.java:372`) builds the same server player without joining it to the level, so it is not in the player list: chat, permissions and player-list lookups do not see it. A test that needs a joined player joins one through a supported API; BrainageLib's `testSupport` source set has `GameTestPlayer`, which joins a real player and keeps the clientbound packets.

## Java quality switches

Strict quality is on by default: `spotlessCheck`, Checkstyle, the [deprecated GameTest API check](#deprecated-gametest-api-check), and `-Werror` on the three javac lints this library enables, so a deprecated API call, an unchecked cast and a raw type each fail the build.

| Property | Default | What it controls |
| --- | --- | --- |
| `strictQuality` | `true` | The whole policy: `spotlessCheck`, Checkstyle, `checkDeprecatedGameTestApis` and `-Xlint:deprecation`, `-Xlint:unchecked` and `-Xlint:rawtypes` as errors. `false` turns all of it off and leaves the three lints as warnings. |
| `strictUnchecked` | `true` while strict quality is on | The unchecked and rawtypes lints as errors. `false` keeps formatting, Checkstyle and the deprecation group, and does not enable the unchecked and rawtypes lints at all, so nothing is reported for them. |

```shell
./gradlew build                              # strict, the default
./gradlew build -PstrictQuality=false        # nothing enforced, three lints as warnings
./gradlew build -PstrictUnchecked=false      # no unchecked/rawtypes errors
```

javac's `-Werror` promotes every enabled warning and has no per-category severity, so the switches decide which lints are enabled. With `strictQuality` on and `strictUnchecked` off, the unchecked and rawtypes lints are not enabled, so that build does not show them; the all-off build shows all three as warnings.

The defaults are the constants `DEFAULT_STRICT_QUALITY` and `DEFAULT_STRICT_UNCHECKED` in `JavaQualityConventionsPlugin.java`, and `uncheckedDefault(strictQuality)` ties the unchecked group to the master switch, so `-PstrictQuality=false` cannot leave it on. A project that cannot take unchecked or rawtypes errors, such as one whose Minecraft generics have no mechanical fix, passes `-PstrictUnchecked=false`.

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

## Client GameTest rules

A client GameTest films a player playing, so everything it does must be something a player can do with a keyboard and a mouse. These rules apply to every client GameTest in every mod, recorded or not, and a review checks them.

### Only what a player can do

- **Vanilla reach.** A break, place, use, ignite or attack happens within the reach a survival player has: 4.5 blocks to a block, 3.0 to an entity (`Player.DEFAULT_BLOCK_INTERACTION_RANGE`, `Player.DEFAULT_ENTITY_INTERACTION_RANGE`). A test never raises `BLOCK_INTERACTION_RANGE` or `ENTITY_INTERACTION_RANGE`, neither its base value nor with a modifier, and never calls `gameMode.destroyBlock` or `gameMode.useItemOn` on the server to get around a range.
- **The crosshair.** The player turns to the target first, and then breaks, places, uses, ignites or attacks the block or entity its crosshair is on. Never something beside the player, behind it, or further away than reach. Two stages of one tree are fine; a swing at the air next to the tree is not.
- **Real input.** Break and attack with the attack key, use with the use key, walk with the movement keys. Turning the player's rotation is what a mouse turn does, and the client sends it with its next tick; the keys are real presses, held for as long as a player would hold them.
- **Server-side calls are for what the camera never shows**: building the scene, spawning entities and items, game rules, time and weather, and teleports between stages before the filmed part of that stage starts.

`ClientGameTestPlayerActions` in this library does all of that, and fails a test that breaks the rule instead of recording something a player could not do. The guard behind `mine`, `useOnBlock` and `attackEntity` requires the crosshair hit to be the test's target, the target to be inside vanilla reach, and both interaction-range attributes to be untouched; it names what was wrong when it fails.

| Call | What it does |
| --- | --- |
| `lookAt(context, point)`, `lookAtBlock(context, pos)` | Turns the player to look at a point or the middle of a block |
| `lookStraightUp(context)` | Looks straight up, for a block above the head |
| `walkWithinReach(context, point)` | Walks to the point with the forward key and stops 2 blocks from it |
| `walkTo(context, point, blocks)` | The same, stopping at a chosen distance |
| `walkAwayFrom(context, point, blocks)` | Steps back with the back key, for a stage that lights something explosive |
| `mine(context, pos)` | Guard, then holds the attack key until the block is gone |
| `useOnBlock(context, pos)` | Guard, then presses the use key once |
| `attackEntity(context, entityId)` | Guard, then presses the attack key once |
| `requireLookingAtBlock(context, pos)` | Fails unless the crosshair is on the block, in reach, with untouched range attributes |
| `requireLookingAtEntity(context, entityId)` | The same for an entity |
| `requireVanillaInteractionRanges(context)` | Fails when a range attribute has a modifier or exceeds vanilla's |

The helpers act on the client thread themselves, so a test calls them in order between its own steps and keeps its server-side assertions where they are:

```java
ClientGameTestPlayerActions.walkWithinReach(context, Vec3.atCenterOf(ore));
ClientGameTestPlayerActions.lookAtBlock(context, ore);
ClientGameTestPlayerActions.mine(context, ore);
// then assert on the server that the drops reached the inventory
```

To use an unreleased build of this library, publish it into a sibling checkout's local repository and point the mod at that repository:

```shell
# in ../MinecraftModConventions, on the branch being tested
./gradlew publishAllPublicationsToLocalRepository
```

```gradle
// in the mod's root build.gradle
workspaceDependencies {
    siblingMaven('MinecraftModConventions') {
        coordinate.set("io.github.brainage04:minecraftmodconventions:${rootProject.minecraftmodconventions_version}")
        siblingDirectory.set(rootProject.layout.projectDirectory.dir('../MinecraftModConventions'))
    }
}
```

and pin `minecraftmodconventions_version` to the version that repository holds (`gradle.properties`' `mod_version` of that checkout). `publishAllPublicationsToLocalRepository` publishes the runtime library and every Gradle plugin under it, so the plugin repositories in `settings.gradle` find the same build.

### A positive case and a negative case

Every positive case has a matching negative case in the same suite, showing the difference: the feature turned off, the wrong condition, or a vanilla server that lacks the mod. Where the difference is visible on screen, the recording shows the negative case right before its positive case (rule 1 of [Shooting a recording](#shooting-a-recording)). A positive case that asserts nothing about the wrong condition does not count.

### Shooting a recording

Every mod's recording is reviewed in the same format, so every recorded client GameTest follows these six rules, and a review checks them.

1. **Negative, then positive, per scenario.** Each scenario shows about 2 seconds with the feature or rule off, then about 2 seconds with it on, and then the next scenario starts. A recording never shows all the positive cases first and the negative cases at the end.
2. **No setup and no filler on screen.** The test world is built before the recording starts: the test loads a saved NBT structure or a saved world, as BrainageMinigames does with its pre-built test world. No `/fill`, block placing or other building happens on camera, and the test carries no setup code for it. With the [dedicated-server harness](#dedicated-server-harness), the test places the structure in `beforeJoin`, which runs before the recording starts. When a scenario ends, the player teleports straight into the next one.
3. **No needless camera moves.** The player looks at the scenario's target from the scenario's first frame. A player who already faces the right way from the previous scenario keeps its rotation.
4. **Captions describe what the mod does.** Captions and every other text on screen say what the mod does in the scenario, never what was removed or changed, such as "the 10 tick wind-up is gone". They use plain prose: short sentences, with the thing acting as the subject. `showStep` and `showStepAndHold` fail a caption that contains "no longer", "anymore", "is gone", "are gone", "has changed" or "have changed", or "was", "were", "has been" or "have been" followed by "removed", "changed" or "replaced".
5. **A mod that changes hit timing hits often.** This rule applies only to a mod that changes hit timing. Its recording lands a hit at least every 5 ticks, ideally every 2, with real attack-key presses (see [Real input](#only-what-a-player-can-do)). The mod's own rules make that possible: AcceleratedDamage's damage scenarios turn on its game rules `accelerateddamage:disable_attack_cooldown` and `accelerateddamage:disable_i_frames`. A hit is damage that lands on the target, so the negative case, with the mod's rule off, lands fewer. Every other mod films vanilla attack timing, and this rule does not apply to it.
6. **A performance overlay.** Every recording shows a small panel with the client's frame rate and frame time, the server's ticks per second and milliseconds per tick, and the client's Java heap, so a review can tell a stutter in the game from one in the capture. The recording helper draws it while the recorder captures the client, so no mod adds code for it, and a plain test run does not show it. It sits in the top-right corner, right under the caption panel at its tallest (from y 46 of the 640x360 GUI under a title card). The other corners hold the mods' own HUDs (BrainageHUD films HUDs in all four), chat and the hotbar. A test whose HUD needs that place moves the overlay with `ClientGameTestRecorder.placePerformanceOverlay(context, PerformanceOverlayPlacement.BOTTOM_LEFT)` (or `TOP_LEFT`, `BOTTOM_RIGHT`), or hides it for a stage with `HIDDEN`. `startRecording` puts it back under the captions, and a bottom placement rises above the chat input while the chat screen is open. The frame time runs from the start of the frame's render to the end of its blit to the window. Client ticks, packet handling and the wait for the frame cap fall outside it, so a stall in a client tick shows only in the frame rate. The server line reads like `TPS 20.0, mspt 5.1, max 12.0`: the time of the last finished tick, the longest tick of the last second, and TPS, which says whether the server's own ticks kept up with its tick rate over the last second. Each tick counts as at least one tick slot, and TPS is at most the tick rate the server runs at, so a test that lowers the rate with `/tick rate 4` reads `TPS 4.0` without any lag. All three come from the time the server records for each tick. Time the test holds the game between ticks lowers the frame rate, not the TPS. A client GameTest runs the client and the server in lockstep, so during a long server tick the picture freezes on the frame before it. Once the tick ends, `max` shows its length and TPS stays lower for a second. The server numbers come from the singleplayer server or from the dedicated server the [harness](#dedicated-server-harness) runs. They read `TPS -` on any other server and `TPS - (paused)` while the singleplayer game is paused. The metadata keeps the frame rate's mean and minimum, the frame time's median, 95th percentile and maximum, the mean and peak milliseconds per tick, the lowest ticks per second and the peak heap, as the `perf*` values in `boundaries`. `perfTpsMinPerSecond` and `perfMsptMaxPerSecond` list, for each second from the first world tick, the lowest ticks per second and the highest `max` the overlay showed in it, with `-` for a second without a server reading. Both cover the second before each reading, so a stall shows in the second it ends and in the next. A mod that removes MMC's caption HUD element, as TwitchPlaysMinecraft does, gets no overlay and no `perf*` values, because the overlay is drawn and sampled inside that element.

### The recording standard

Every recording is 1920x1080 with GUI scale 3, a 640x360 GUI (`ClientGameTestOptions` sets it, so no mod overrides it), with the caption panel in the top-right corner 5 pixels from the edge and nothing cut off: the panel is at most 368 pixels wide and a diagnostic step's 10 lines are 116 pixels tall, both inside a 640x360 GUI. On a display that already has the card, the client's window is that same 1920x1080, which vanilla centres on the display (`Window.java:118-119` in the 26.3 sources), and the capture starts at the window's own origin and size, which the recording helper reports from inside the client, so the frame holds the window and not a band of desktop. The recording fails when the display is smaller than 1920x1080, or when the window is not that size or does not lie wholly on the display, naming the sizes involved. Game sound is on at 0.7 master volume with background music off, and the recording fails without it. The same panel position and scale in every mod is the point: a viewer switching between videos sees the same overlay.

### Stage markers, waits and captions

A recorded test prints the markers `scripts/trim_stages.py` reads and waits for the world to be ready through the library:

| Call | What it does |
| --- | --- |
| `ClientGameTestStages.markStage(id)`, `endStage(id)` | Prints `STAGE_START <epoch ms> <id>` and `STAGE_END <epoch ms> <id>`, the one format the trim scripts parse. The id is one non-blank token. |
| `ClientGameTestStages.endStage(context, id, holdAfterTicks)` | The end marker, then leaves the scene as it is for that many ticks, for the picture a cut keeps after the end. |
| `ClientGameTestWaits.waitForChunkWindow(context, radius, timeoutTicks)` | Polls the client's chunk cache until every chunk in a circle of `radius` chunks around the player is fully loaded; pass the server's view distance minus two. |
| `ClientGameTestWaits.waitForRenderSettled(context, idleTicks, timeoutTicks)` | Until the renderer has finished every chunk mesh for that many idle ticks. |
| `ClientGameTestWaits.waitForRegionLoaded(context, minChunkX, minChunkZ, maxChunkX, maxChunkZ, timeoutTicks)` | The same for a rectangle a camera looks at. |
| `ClientGameTestWaits.waitForServer(context, server, condition, timeoutTicks, description)` | Until the server satisfies a predicate, for state the client's own copy does not prove, such as the drops after a break. |
| `ClientGameTestWaits.holdFor(context, millis)` | Waits by the clock, as the film runs, not by ticks, which run slower under load. |
| `ClientGameTestRecorder.showStepAndHold(context, id, title, subtitle, holdTicks)` | The caption and its shot length in one call. |
| `ClientGameTestRecorder.placePerformanceOverlay(context, placement)` | Moves the [performance overlay](#shooting-a-recording) to another corner, or hides it with `HIDDEN`, until the next call. |

Each wait fails the test naming what was still missing, so a frame is never filmed half-built.

Show a stage's caption with the stage's id, the one `markStage` prints, or with that id behind the mod's prefix and a dot (`dimensionstructurevariants.nether` for the stage `nether`): `scripts/trim_stages.py` opens a game take's clip where the first caption of that id appears.

### Commands and chat

| Call | What it does |
| --- | --- |
| `ClientGameTestCommands.assertCommandTree(context, commands…)` | Fails unless the connected client's command tree has each of these root commands. |
| `ClientGameTestCommands.runCommand(context, command)` | Sends a command as the client player. |
| `ClientGameTestCommands.typeCommand(context, command, visibleTicks)` | Opens the chat screen, types the command whole, and submits it, so no parse error is filmed over a partial command. |
| `ClientGameTestCommands.clearChat(context)` | Clears the chat panel before a stage. |
| `ClientGameTestCommands.awaitChat(context[, timeoutTicks], texts…)` | Waits until each text appears in some chat message, then fails naming what the panel holds instead. |
| `ClientGameTestCommands.chatLines(context)` | The text of every message the panel holds. |
| `ClientGameTestCommands.clickChatCommand(context, command, settleTicks)` | Clicks a chat line's run-command link, such as `[ACCEPT]`, with the cursor resting over it first. |

### Film-only code in the `recording` source set

Scene staging, cameras, captions and film-only mixins go in `src/recording/java` and `src/recording/resources`, so the `gametest` source set holds only real tests and a test count is a count of tests. The plugin compiles that source set against everything a GameTest compiles against, the mod's own main and client classes included, except the GameTests themselves; a GameTest calls the film, and the film's classes and resources are on the GameTest run's classpath and in the production GameTest JAR. A film-only mixin config goes beside the other resources in `src/recording/resources`. The source set carries no mixin annotation processor, so a mixin that needs a refmap adds that processor in the mod's build file.

## Client GameTest recording

```shell
GTR_RECORDING_PROFILE=smoke ./gradlew --no-daemon :fabric:recordClientGameTest
```

Needs `ffmpeg`/`ffprobe` (raw video, PulseAudio input for a screen grab, H.264/AAC) and Xvfb with `xdpyinfo`, and a screen grab also needs `pactl`: a game take renders the game's own sound and needs no PulseAudio server. Set `PULSE_SERVER` for a fully isolated screen grab. The desktop's default sink is never changed.

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
- The recording HUD panel sits in the top-right corner, clear of chat in the bottom-left, with the [performance overlay](#shooting-a-recording) under it. `GTR_RECORDING_PROFILE=showcase` shows a title card there instead of the diagnostic overlay.
- `GTR_RECORDING_PRE_PADDING_SECONDS` / `GTR_RECORDING_POST_PADDING_SECONDS`: `0`–`60`, default `0.5`.
- Each recording writes metadata with tick and frame boundaries, effective padding and any capture gaps. A run fails if game audio does not reach the recording or cannot be decoded.
- A recording of 3 s or longer fails when every decoded frame is identical, which is what a client whose pictures never reach the display produces: the check compares the four boundary images and eight frames evenly spaced across the recorded window, so a stretch of film that stands still does not fail it on its own, while a capture that never changes does.
- `scripts/trim_stages.py LOG VIDEO.mp4 OUT.mp4 META.json [--clock]` cuts a recording down to its filmed stages. The test prints `STAGE_START <epoch ms> <id>` and `STAGE_END <epoch ms> <id>` around what should be on screen; the script ends each clip 0.12 s after its end, joins stages that touch, and drops the join, the dimension change and the off-camera rehearsal between them. Each clip opens on the first frame from 0.10 s before its start, its clock start, that shows the stage's caption, because a marker's time lands on the video up to 0.5 s off; it never opens earlier, so settle frames a test keeps off film stay out. Where that frame is comes from one of three sources, and the script prints which one it used:
  - A game take records, for every caption, the first captured frame the HUD drew it into (`gameCaptureCaptionMarks`); a caption is on screen from its mark to the next caption's. A stage's caption is one whose id is the stage's id, or that id behind a prefix and a dot. The clip opens at its clock start when the caption on screen there is the stage's, and otherwise on the stage's first caption drawn after the clock start; the readouts a stage shows later under the same id do not move its cut. No picture is read, so the world behind the panel cannot move the cut and the take may have any size. The run fails, naming `--clock`, when the stage's caption is neither on screen at the clock start nor drawn before the clip ends, and when the stage's id matches more than one caption id (two mods' `ice.runway` and `roller.runway` for a stage `runway`).
  - A screen grab (`GTR_RECORDING_CAPTURE=x11grab`), or a take recorded before 3.6.0, has no marks, so the script reads the caption panel of a 1920x1080 take, by its residual: its pixels with each row's mean brightness removed, so the world behind the translucent panel counts for little. A new caption shows as a step, a frame-to-frame change at least 1.5 and at least twice the panel's typical change, which is the world drifting behind it. With one step from the clock start to 0.5 s after the start, or two within 0.2 s for a caption drawn in two, the clip opens on the last of them. With no step up to 0.9 s after the start, the clip opens at the clock start on the assumption that the caption is already on screen; a later clip then needs a step since the previous clip ended. The panel is watched up to 2 s after the start: a caption that comes later than that is not seen, and the clip opens on whatever the clock start shows. Each of these windows stops at the stage's end, so in a clip of stages that touch, a short first stage is not opened on the next stage's caption. Rather than guess, the run fails naming `--clock` when the stage lasts no more than 0.5 s from its start, so that its caption may only come after it ends, when the panel never changes near the start, shows no step since the previous clip, changes more than once after the clock start, or changes from 0.5 s to 2 s after the start, and when the world moves the panel by more than 2 a frame, so that a change as small as two near-identical captions make (about 4.8, one short word apart) could hide in it. What it cannot see: a camera cut or teleport in a stage's first 0.5 s, while the stage's caption is already on screen, opens the clip at the cut; an on-film camera cut or teleport after the clock start that changes the panel about ten times as much as the new caption does hides the caption's step, so the clip opens at the cut, which may still show the previous caption; and two captions that differ by less than the drift allows, closer than about 4, can still pass for one on a moving world. After encoding, every later clip's first frame must be closer to its own caption, read 0.5 to 0.9 s after its start or last before its stage ends, than to the caption its previous clip ended on, and the first clip's first frame may be at most 4 further from its caption than the source frame was; the run fails otherwise, naming `--clock`. That check reads both captions at other moments than the first frame, so on a moving world two near-identical captions can fail a right cut there. A game take has no such check: the marks name the caption on screen at each clip's first frame, which is what it would measure. A clip's sound runs on from its first frame for exactly as long as its frames last at the take's rate, while x11grab spaces its frames by the frame time rounded to whole microseconds (ffmpeg 8.1 `libavdevice/xcbgrab.c:618`), so over a 30-minute clip the sound ends about 18 ms off the picture at 30 fps and about 36 ms at 60 fps.
  - `--clock` cuts by the log's clock instead, 0.10 s before each start, for a recording without captions.

  `LOG` is the run's log and `META.json` the recording's metadata, whose `boundaries.selectedStartEpochSeconds` is where the video starts; pass the exported video, not the raw capture. Beside `OUT.mp4` it writes `OUT.stages.json`, the first and end (exclusive) frame of each clip in the trimmed video.
- The raw capture's muxer queues up to 4096 encoded packets (over a minute of capture), so a write that waits on a busy disk does not stop the capture and leave a frame gap.
- A recording captures the game's own picture and sound (`GTR_RECORDING_CAPTURE=game`, the default). Right before each present, the client copies the finished frame out of its render target with an asynchronous GPU copy, the way vanilla's screenshots and Tracy frame capture read it, and writes it through a pipe to the recorder's ffmpeg, so every video frame is one whole game picture and none can tear. The video runs on a fixed clock at the capture rate: each video frame shows the newest picture presented before its time, and a present that would fall between the same two video frames as the one before it waits for the next video frame, so the client draws at most one picture per video frame and a late picture never waits. While the client presents nothing, such as while a test step holds it, the video repeats the last picture once a quarter of a second has passed, as a screen grab would. The sound engine plays into an OpenAL Soft loopback device (`ALC_SOFT_loopback`) instead of a sound card, and the client renders that mix on the same clock into a second pipe, so the machine plays nothing while the client records. `GTR_RECORDING_CAPTURE=x11grab` grabs the screen and the recorder's PulseAudio sink instead, as recordings did before 3.5.0. A game take needs no PulseAudio server: it loads no sink and sets no `ALSOFT` or `PULSE` variable, and a libopenal without `ALC_SOFT_loopback` fails the take at once, naming the extension and `GTR_RECORDING_CAPTURE=x11grab`. ffmpeg's input thread holds at most four raw frames of the video pipe (33 MB at 1920x1080), so an encoder that falls behind drops frames instead of growing without bound, and a capture the client stopped (a window or fullscreen change leaves the target another size) fails the take with the client's reason. A picture presented just after a video frame's time goes into the next video frame, so the picture runs up to one capture frame (33 ms at 30 frames a second) behind the sound that belongs with it: the traced showcase take put its pictures a median 30.8 ms after the present that carried them (p10 19.1, p90 32.4 over 1653 presents). A game take runs only on OpenGL: the copy flips the read-back rows assuming GL's row order, and no Vulkan run has checked what Vulkan's read-back returns, so a client on another backend fails the take before its first frame, naming the backend and `GTR_RECORDING_CAPTURE=x11grab`. The metadata names the source as `captureSource`, and the boundaries hold the capture's counts: `gameCaptureFrames`, `gameCaptureDroppedFrames`, `gameCaptureSlots`, `gameCaptureRepeatedSlots`, `gameCaptureReadbackMsMean` and `gameCaptureReadbackMsMax`, `gameSoundFramesWritten` and `gameSoundSilentFrames`. `gameCaptureCaptionMarks` lists, as JSON, every caption's id, the raw capture's frame (`slot`) of the first frame the HUD drew it into and that frame's present time (`presentUs`): each frame carries the caption extracted into it in the same `renderFrame` that presents it, and a caption counts from the first slot actually written with it, so a frame that reaches the writer late marks the slot that shows it. On thomas-server2's GTX 1070 with other jobs running (load 15 to 19), the MagicCarpet showcase recorded both ways back to back. At 30 frames a second the game capture's client presented 28.4 frames a second and the video held 20.6 new images a second while something moved, against 26.6 and 16.7 for x11grab, whose take needed 19 torn frames replaced. At 60 frames a second the figures were 43.6 and 31.3 for the game capture, against 48.0 and 29.9 for x11grab with 27 torn frames replaced. Copying a frame back took 2.1 ms on average and 25 ms at most. On the showcase branch with sound effects, a sound starts in the game capture's audio a median 0.9 ms after the game plays it, against 57 ms through PulseAudio, at the same loudness (-36.3 LUFS).
- A recording renders on an X display that already has the machine's graphics card behind its GLX (an `Xorg` running on the card) instead of the recorder's own `Xvfb`, whose `llvmpipe` presents a busy scene at a handful of frames a second and leaves every second captured frame a repeat; the client's window is visible on that display while it runs. The display is the `recordingDisplay` Gradle property (`-PrecordingDisplay=:1`), else `GTR_RECORDING_DISPLAY=:1`, else the display the host declares in `/etc/gametest-recording-display`: one line holding the display name (`:1`, or `1`; `0` is display `:0`, not an off value) and, optionally, the renderer it must name (`NVIDIA`), with blank lines and `#` comments ignored, and the file missing is the normal case on a host without a card. A recording host writes that file once (`echo ':1 NVIDIA' | sudo tee /etc/gametest-recording-display`) and every recording on it renders on the card with no environment. Any explicit value wins over the file, and a blank one (`GTR_RECORDING_DISPLAY=`, `-PrecordingDisplay=`) forces the virtual display, so a run can always opt out. `GTR_RECORDING_DISPLAY_FILE` names another file, for a host without root.
- With a display, the capture, the boundary trimming and the audio route are the same as without one. The client's frame cap is the capture rate, so the client draws about one picture per captured frame, and the metadata records the graphics device, the presented frame rate and the host. A recording that uses the display fails when the client's GL renderer is a software rasterizer, is missing, or lacks the host's renderer name: a software take is exactly the choppy video the display exists to avoid, so it is not saved as a pass, and the error names the renderer. A run that asked for the virtual display explicitly is never checked. A display serves one recording at a time: the recorder holds an exclusive lock on it (`/tmp/.gtr-recording-display-<n>.lock`, held until its capture stops), and a second recording that asks for the same display falls back to its own virtual display instead of waiting, because a waiting job would hold its heavy slot for the whole first recording. `gpu.fallbackReason` names the lock. A display that does not answer falls back to the recorder's own virtual display, and `gpu.fallbackReason` says why. VirtualGL's `vglrun` is not a way to do this: its faker advertises OpenGL 3.3, and Minecraft 26.3's OpenGL backend then draws nothing (`GL_INVALID_FRAMEBUFFER_OPERATION` throughout, a black capture).
- The metadata records the client's graphics device (`glRenderer`), its SDL video driver, the presented frame rate of the recorded world (`presentedFps`) and the host, and `gpu.active` says whether the card rendered the run.
- `GTR_RECORDING_FPS` is the capture rate, 30 frames a second by default. `GTR_RECORDING_FPS=60` records the video at 60 frames a second, for animation detail, and needs a recording display on the card. There the client's frame cap is 60, so it draws at most one picture per video frame; one that cannot draw 60 presents fewer and the video repeats the pictures in between. On the recorder's own `Xvfb` the cap stays at 30, the most `llvmpipe` presents, so a rate above 30 that gets the virtual display (the card's display busy or not answering) fails before the client starts, naming `gpu.fallbackReason`. Only a recording reads the rate: a plain client GameTest run ignores `GTR_RECORDING_FPS`, so a value that is not a positive whole number fails the recording alone. The metadata's `fps` is the capture rate, `clientFrameCap` the cap the client ran with, and `presentedFps` how often the client presented the recorded world. `scripts/trim_stages.py` cuts by time and counts frames with `ffprobe`, so it works at any capture rate. With the client capped at 120 (a cap tried during 3.5.0's development; 3.4.0 capped a card client at 60) on a GTX 1070 with a 4-core i3-12100F and no other job on the machine, it presented 114 frames a second, and a 60 fps take held 57 to 60 new images a second while something moved. The rest of its repeated frames are moments where nothing on screen moved. With two other test jobs on the same machine, its 8 threads were busy, the client presented 64 frames a second, and a take of the same showcase held 36 to 48 new images a second while something moved. A 60 fps recording therefore runs best with the node to itself, which `nomad-heavy --mem` large enough to fill the node arranges. `h264_nvenc` gave the same picture on an idle node and took the capture's CPU from 2.2 threads to 0.1.
- `GTR_RECORDING_VIDEO_ENCODER` picks the raw capture's encoder: `libx264` on the CPU by default, or `h264_nvenc` on an NVIDIA card's encoder, which takes the capture's frames as they are and leaves the CPU to the game. The metadata names it as `videoEncoder`. Before the client starts, the recorder encodes one frame with that encoder and fails with ffmpeg's reason when it cannot, such as on a host without an NVIDIA encoder.
- `GTR_RECORDING_TRACE=true` makes the client write the CPU time before and after every present of the recorded world to `<take>-boundaries-presents.csv`, beside the boundaries file, so a repeated frame can be matched against the presents around it, and the time and name of every sound it starts to `<take>-boundaries-sounds.csv`.
- With `GTR_RECORDING_CAPTURE=x11grab` on a card display, x11grab can tear a frame. It copies the screen from the top down, the NVIDIA driver does not make that copy wait for the client's present (the display's Xorg log says "Implicit sync not fully supported"), and a present that lands during the copy leaves the older image above a horizontal line and the newer one below it. On a card display the recorder finds those frames in the raw capture and replaces each with the frame before it. A take on the recorder's own Xvfb is not checked: there the check found one frame in 19 takes, and that frame was a genuine image. A torn frame holds only parts of its two neighbours, so replacing a real tear loses no image. `tornFramesReplaced` in the metadata of a card take lists them by frame number of the saved video, and the recording fails when its saved video still has a torn frame. `GTR_RECORDING_TEAR_CHECK=warn` keeps such a take with a warning and lists the frames as `tornFramesLeft`. `scripts/check_tears.py VIDEO.mp4` runs the same test on any finished video, such as a take after its captions are cut, and exits 1 when it finds one. The test marks, for each frame, the pixels that equal the frame before while differing from the frame after, those that do the opposite, and those that differ from both. A frame is torn when its upper rows show the frame before, its lower rows show the frame after, and almost none of its pixels are its own, so a fresh image between its neighbours does not count when it has content of its own. For the same reason a tear on a frame where the caption panel or another overlay also changes can go unrepaired: the overlay's change gives the frame pixels of its own, and when they are more than 2 % of its marked pixels the test takes the frame for a fresh image, so neither the repair, the recording's final check nor `scripts/check_tears.py` reports it. A real sky tear was missed this way on a frame where the caption changed. The check also replaces a fresh image that has none: one where the screen changed below a line in that capture and above the line only in the next, such as a chat bar opening whose suggestion list appears above it one capture later. Its rows below the line match the next frame and its rows above the line the previous one, as a torn frame's do, and the pixels cannot tell the two apart, so the recording loses that image and repeats the previous picture for one frame. A change below a line with nothing changing above it, such as a chat bar opening on its own, is not replaced. On a 25-minute TwitchPlaysMinecraft take at 30 frames a second, the check replaced 134 of 46,156 frames, tears and such frames together. Nobody has sorted those 134 into the two kinds.
- The client always starts with `SDL_VIDEO_DRIVER=x11`: SDL3 otherwise finds a Wayland compositor through `XDG_RUNTIME_DIR` and opens its window there, where the capture cannot see it and where it may appear on someone's screen. A run whose log names another driver fails.
- When the job runs in a cgroup of its own with a memory limit (a job runner's slot, such as a systemd scope), the recording sets that cgroup's `memory.low` to the limit for the run, so the kernel reclaims sibling jobs first instead of evicting ffmpeg's and the game's files, whose reads from a busy disk stall the capture. A cgroup without a limit is left alone.

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

### Vanilla-server checks

A client mod that registers items must still let its players join servers without it: a creative player who takes one of its items there sends an item id the server cannot decode, and the server drops them (`Failed to decode packet 'serverbound/minecraft:set_creative_mode_slot'`). `:fabric:runVanillaServerClientGameTest` runs the production client GameTests against a real vanilla server, and `VanillaServerClientGameTests` gives them the checks. To opt in, add a client GameTest and its entrypoint (`fabric/src/gametest`):

```java
public final class ExampleVanillaServerClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        VanillaServerClientGameTests.checkCreativeItems(context, VanillaServerClientGameTests.CreativeItems.of(
                "Example Mod",
                ServerSupport::serverHasExampleMod,      // the mod's own "this server has the mod" signal
                ServerSupport::isExampleModItem,         // which stacks are the mod's items
                () -> new ItemStack(ModItems.EXAMPLE))); // one of them, to take in creative
    }
}
```

```json
"fabric-client-gametest": ["com.example.ExampleClientGameTest", "com.example.ExampleVanillaServerClientGameTest"]
```

and run `./gradlew :fabric:runVanillaServerClientGameTest` (through `nomad-heavy`, like any client run).

- **The server.** The task downloads Mojang's dedicated server jar for `minecraft_version` once into `<Gradle user home>/caches/minecraftmodconventions/vanilla-server/<version>/`, checked against Mojang's published SHA-1 (later runs use it offline), and starts it with the client's Java and `-Xmx1G` in a fresh `build/run/vanillaServer` (`fabric/build/...` in a multi-loader mod): offline mode, no whitelist, a flat creative world (`force-gamemode`), `127.0.0.1` on a free port. The client gets `127.0.0.1:<port>` in `MINECRAFTMODCONVENTIONS_VANILLA_SERVER`; the server is stopped when the client exits, also when it fails. Its console is `build/run/vanillaServer/console.log`, and the task logs its join, leave and disconnect lines.
- **The run fails** when the client GameTests fail, and when no client joined the server, since then nothing was checked (a missing entrypoint, say). All of the mod's client GameTests run, as in `runProductionClientGameTest`; the vanilla checks do nothing outside this task, so the ordinary client run and `runAllGameTests` are unchanged.
- **`checkCreativeItems`** joins the server and asserts: the mod's signal says the server lacks the mod; the player is in creative; no creative tab and no search result offers one of its items; a hotbar saved with one loads it empty; taking one into the hotbar in creative leaves the client connected and the slot empty. `CreativeItems.withExtraChecks(client -> ...)` adds the mod's own checks, which run on the client thread while it is still connected. Last, it sends the held-back `set_creative_mode_slot` packet with the mod's item directly and expects the server to drop the client with that decode error, so a pass also shows the check would catch the kick.
- **Other checks against a vanilla server:** `VanillaServerClientGameTests.withVanillaServer(context, test -> ...)` joins, runs `test`, checks the client is still connected, and leaves; `assertConnected(context, message)` checks it at any point.
- The server startup counts against the client's [startup deadline](#automated-client-launches) (300 s), as it happens inside the same task.

## Production GameTests

```gradle
productionGameTests {
    runtimeModDependencies.add("me.fzzyhmstrs:fzzy_config:${fzzy_config_version}")
    runtimeLibraryDependencies.add("com.github.twitch4j:twitch4j:${twitch4j_version}")
}
```

Loom's production runs do not inherit the dev classpath, so extra mods and libraries must be listed here; Fabric API is added automatically. Tasks: `productionGameTestJar`, `runProductionClientGameTest` (Xvfb by default), `runVanillaServerClientGameTest` (the same client against a vanilla server, see [vanilla-server checks](#vanilla-server-checks)), `runProductionServerGameTest`, and in single-loader builds `runAllProductionGameTests` (multi-loader builds use the root `runAllGameTests`; neither includes the vanilla-server run). `includeClient`, `includeServer`, `clientUseXvfb`, `clientSilenceAudio`, `clientJvmArgs` and `serverProgramArgs` override the defaults. Under `recordClientGameTest`, `client-gametest-recorder` turns `clientUseXvfb` and `clientSilenceAudio` off, so the production client uses the recorder's display and audio sink.

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

The client and production GameTest workflows set `SDL_VIDEO_FORCE_EGL=1` for the steps that start a client. Minecraft 26.3 asks SDL3 for an sRGB-capable OpenGL framebuffer, and Ubuntu 24.04's Xvfb (21.1.12) offers no sRGB-capable GLX visual, so through GLX the client fails with "Couldn't find matching GLX visual" before any test runs; through EGL it finds one. This holds on self-hosted runners built from Ubuntu 24.04 too, so the workflows force EGL on every runner, and every runner needs EGL installed: `libegl1` and `libegl-mesa0`. The client workflow's package step installs them with the rest of its packages through `sudo apt-get`. The production workflow installs them through `sudo apt-get` only when `libEGL.so.1` or `libEGL_mesa.so.0` is missing, so a self-hosted runner without sudo or apt runs it as long as its image has both.

The forced EGL path is meant for the Xvfb display the workflows record and test on. A CI recording on a card display is not supported: the recorder's client would inherit `SDL_VIDEO_FORCE_EGL=1` and `LIBGL_ALWAYS_SOFTWARE=1` from the workflow step and render in software through EGL instead of on the card through its GLX.

The build and GameTest workflows take `timeout_minutes`, the time limit of each step that runs Gradle (default 20, client GameTests 40); a mod whose `runAllGameTests` takes longer passes its own. The jobs themselves may take 360 minutes, so a self-hosted runner can hold a job until it has capacity (for example in a job-started hook) without eating into the steps' limits. None of them passes `--refresh-dependencies`: it made every job re-check every dependency against the remote repositories, and a network hiccup failed the build.

A mod that depends on an owned library that is not on Maven Central (BrainageLib) passes `prepare_siblings`, a space-separated list of brainage04 repositories, to `reusable-mod-build.yml`, the three GameTest workflows and `reusable-multiloader-release.yml`. Each one is cloned into `../<name>` and published with `publishAllPublicationsToLocalRepository`, so its `siblingMaven(...)` declaration resolves from `../<name>/build/local-repo`. An entry is `Name` for the repository's default branch, or `Name@ref` to pin a branch, a tag or a full commit sha, which builds against a sibling that is not released yet:

```yaml
    with:
      prepare_siblings: BrainageLib@release/2.0.0 MinecraftModConventions@v3.5.0
```

A pinned entry is fetched at depth 1 and checked out detached, so the build sees exactly that commit; a bare `Name` clones the default branch as before. Pinning this repository's own plugin works when the consumer's `settings.gradle` resolves it from `../MinecraftModConventions/build/local-repo` (every mod generated from the templates lists it in `pluginManagement`), because the step publishes both the runtime library and the Gradle plugins into that repository.

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
./scripts/mod_fleet.py vanilla-identity [--output hits.md]
./scripts/mod_fleet.py vanilla-tags --tag-data <directory> [--output hits.md]
./scripts/mod_fleet.py world-height [--output hits.md]
```

`record` writes to a timestamped `~/Downloads/minecraft-mod-gametest-recordings-*` directory; `--output <dir> --resume` reruns only the repositories that failed. For a new Minecraft release, pass `--minecraft-version`, `--loader-version`, `--fabric-api-version`, `--java-version` and `--conventions-version` to `audit`, then update the manifest.

`vanilla-identity` lists the lines `checkVanillaIdentity` would flag in every git checkout beside this one, including mods that do not use the conventions plugins. `vanilla-tags` does the same for `checkVanillaTagIdentities` and names the tag each line should ask about; it reads the vanilla tags from `--tag-data`, the `data/minecraft/tags` directory of a decompiled Minecraft tree. `world-height` lists the literal overworld build limits `checkWorldHeight` would flag.
