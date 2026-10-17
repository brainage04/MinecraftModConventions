# Gradle run tasks

The `run*` and `record*` tasks a multi-loader mod gets from `io.github.brainage04.multiloader-mod-conventions`, run from the mod's root. Loader-specific runs live in that loader's project. Root tasks aggregate the full gate or provide the `runQuickGameTests` iteration entry point.

## Development (dev environment, sources on the classpath)

| Task | What it runs |
|---|---|
| `:fabric:runClient` / `:neoforge:runClient` | Dev client with the mod and DevAuth (DevAuth signs in only if enabled in `~/.config/devauth/config.toml`) |
| `:fabric:runServer` / `:neoforge:runServer` | Dev dedicated server |
| `:fabric:runGameTest` | Fabric server GameTests in the dev environment |
| `:neoforge:runGameTest` | NeoForge server GameTests in the dev environment |
| `:fabric:run<Name>GameTest` / `:neoforge:run<Name>GameTest` | One [integration GameTest suite](../README.md#integration-gametests) in the dev environment, with its extra mods; only its filtered GameTests run |

## Release jar (production environment)

| Task | What it runs |
|---|---|
| `:fabric:runProductionServerGameTest` | Fabric server GameTests on a production Fabric server, against the release jar |
| `:fabric:runProductionClientGameTest` | Fabric client GameTests in a production Fabric client under Xvfb; does nothing when `fabricClientGameTests = false` |
| `:fabric:recordClientGameTest` | The same client GameTests, recorded to video (Xvfb, PipeWire, ffmpeg); skipped when client GameTests are off |
| `:neoforge:runProductionServerGameTest` | NeoForge server GameTests on a real NeoForge server installed with the official installer, against the release jar; skipped when `neoForgeGameTests = false` |
| `:fabric:runProduction<Name>GameTest` / `:neoforge:runProduction<Name>GameTest` | One integration GameTest suite on the same production servers as `runProductionServerGameTest`, with the suite's JAR and its extra mods added |

There is no NeoForge client GameTest task: NeoForge has no client GameTest framework.

Production GameTest runs and integration GameTest suite runs are never up to date: every invocation starts the game, so a changed environment (such as a test filter in `JAVA_TOOL_OPTIONS`) or a rerun after a flaky failure always runs the tests.

Fabric server GameTests start with a fresh world on every invocation: `runGameTest` and `runProductionServerGameTest` delete only `<runDir>/world` before launching, including with a custom run directory. This applies to both single-loader Fabric mods and multi-loader mods; logs, options, EULA files and other run-directory contents are preserved. NeoForge's development and production GameTest entrypoint already replaces its test world through vanilla's GameTest main, so it needs no additional reset. Integration GameTest suite runs delete `<runDir>/world` on both loaders.

## Root

| Task | What it runs |
|---|---|
| `runAllGameTests` | Every ordinary and integration GameTest in Fabric development, NeoForge development, Fabric production and NeoForge production, plus the optional Fabric production client. Sequential by default. |
| `runQuickGameTests` | Only `:fabric:runQuickGameTest`, in development, selected with `quickGameTestFilter` and optionally `quickGameTestSuite`. Not a merge gate. |

Each run has its own run directory: `fabric/build/run/gameTest`, `neoforge/run/gametest`, `fabric/build/run/productionServerGameTest`, `fabric/build/run/clientGameTest` and `neoforge/build/run/productionServerGameTest`; a suite's runs use `<loader>/build/run/<name>GameTest` and `<loader>/build/run/production<Name>GameTest`.

Use `runQuickGameTests '-PquickGameTestFilter=examplemod:movement_*'` while iterating.
`-PquickGameTestSuite=compat` loads a named integration suite instead of the ordinary source set;
its filter is the default unless overridden. Quick runs use `fabric/build/run/quickGameTest`
and reset their own world without altering the ordinary gate's filter.

For merge gates, `--parallel -PparallelGameTests=true` permits two concurrent loader lanes,
each retaining development-before-production order. It changes no task dependencies that select
coverage and drops no environments or integration suites. Reserve memory for two game JVMs,
Gradle and native overhead first. Without this explicit opt-in, `--parallel` alone keeps game
runs sequential. All GameTest JVMs use `multiLoaderModConventions.gameTestHeap`, default `4G`;
`-Pgametest_heap=6G` overrides the configured heap for one invocation.

Fabric server runs are left out for `mod_side=client` and with
`fabricServerGameTests = false`; NeoForge runs are skipped with `neoForgeGameTests = false`.

## Tasks that do nothing

Loom also creates `:common:runClient`, `:common:runServer` and `:common:runClientRenderDoc`. `common` has no loader and the conventions clear its run configurations, so these tasks are skipped. Use the `:fabric:` or `:neoforge:` task instead.
