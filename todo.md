# MinecraftModConventions todo

Fleet-wide work for the conventions plugins and every Fabric/NeoForge mod that uses them. Work specific to one mod goes in that mod's `todo.md`. The Forge 1.8.9 projects (ToggleSprint, LegacyMinecraftModTemplate) are out of scope.

## Planned work

### Multi-loader layout

ActionAssist keeps its own multi-version layout (owner decision, 2026-10-01); the conformance checklist gives it a separate section.

### Integration GameTests

`integration-gametests` covers server GameTest suites on unobfuscated Minecraft; Modrinth and CurseMaven mods already work as Maven coordinates once the mod declares their repository. ActionAssist's hand-written NeoForge 1.21.1 `modpack` client GameTest needs these before it can move onto it:

- [ ] Client suites: a suite run based on a client GameTest run (`clientGameTest`) rather than the `gameTest` server run, with its own filtering or entry point instead of Fabric API's server filter / `--tests`.
- [ ] Remapped targets: on an obfuscated Minecraft, extra mods must go through a Loom remap configuration (`loom.addRemapConfiguration`, runtime only) instead of the plain `<name>Mods` configuration.
- [ ] Run-directory fixtures: copy a suite's config and world fixtures into its run directory before each run and clear the saved state it writes (ActionAssist's `prepareModpackClientGameTest`/`clearModpackClientGameTestWorld`).
- [ ] Jar-in-jar libraries: put the `META-INF/jarjar` libraries of an extra mod (KubeJS) on the run's runtime library classpath (`forgeRuntimeLibrary` on NeoForge).

### Platform contracts

Common holds the gameplay code; loader modules only adapt it. No mod's `common` imports Fabric, NeoForge or Architectury packages. Remaining gaps (NPCAddons is left out because it is being rewritten; TwitchPlaysMinecraft and HudRendererLib gaps are in their own `todo.md`):

- [ ] BetterVillagerTrades: `MerchantScreenMixin` and `VillagerMixin` are copied into both loader modules; move them to common.
- [ ] IceSkates: `common/.../mixin/GameRendererMixin` uses Fabric's `@Environment(EnvType.CLIENT)`; the creative-tab item lists are copied into both loaders.
- [ ] MagicCarpet: `platform/Platform` is never called, and the NeoForge service file names a class that doesn't implement it; use it or delete it. Registry ids, creative-tab and renderer registration are copied into both loaders.
- [ ] SimpleTwitchChat: the command tree and join message are copied into both loaders; NeoForge never closes the chatbot on shutdown.
- [ ] TextureAtlasGenerator: the open-screen key mapping and its tick handler are copied into both loaders; NeoForge has no config screen.
- [ ] ProceduralDungeon: NeoForge re-declares the structure and pool-element type ids instead of using the common `registerAll` hooks.
- [ ] ActionAssist: NeoForge never calls `ClientRuntime.shutdown()`.

### Loader parity

Checked on 2026-09-29 for all 26 Fabric/NeoForge mods (25 on `multiloader-mod-conventions` plus ActionAssist): both release jars have correct metadata, entrypoints, common classes and resources and nothing from the other loader, and every NeoForge jar was run on a real NeoForge 26.2 server and client. The high and medium findings are fixed; low ones remain in each mod's `todo.md`. Every mod now builds against NeoForge 26.2.0.88 and uses `iconFile` (NeoForge 26.2.0.88 warns about `logoFile`). Repeat these checks before each release:

- [ ] Before releasing a mod, run its release NeoForge jar on a real NeoForge client and compare both jars' contents. NeoForge-only regressions found so far came from mixins targeting a method NeoForge patches (BrainageServerUtils) and loader-specific ids (FortniteInMinecraft), which Fabric GameTests cannot catch. The server half is automated since 2.4.18: `:neoforge:runProductionServerGameTest` (part of `runAllGameTests`) runs the NeoForge GameTests against the release jar on a server installed with the official installer, so it catches these only where a mod's NeoForge GameTests exercise the affected code.

### Kotlin migration (deferred)

- [ ] Migrate the Gradle plugins, tests, and shipped Fabric runtime helpers to Kotlin as a separate delivery.
  - Introduce Fabric Language Kotlin as an explicit runtime dependency of the shipped helper and generated/consumer mods.
  - Preserve every published plugin ID and coordinate, extension/task DSL, Java-callable helper API, runtime-helper dependency contract, and current consumer behavior.
  - Migrate mixins only if Kotlin produces equally clear, reliable bytecode and Mixin behavior; otherwise keep mixins in Java.
  - Migrate incrementally while keeping the plugin tests, marker-resolution tests, component-isolation tests, consumer builds/GameTests, and Maven Central publication dry-run green.
  - Evaluation (2026-09-28): the plugins can move with no effect on consumers. The runtime helper is loaded into every mod's GameTests, so a Kotlin helper would force a Kotlin runtime mod into all of them; keep it in Java. Kotlin mods need Fabric Language Kotlin (supports 26.3) and Kotlin for Forge on NeoForge (supports up to 26.2 as of 2026-09-28) installed by every player.

## Guardrails, not tasks

- During development, resolve owned libraries from a module-filtered sibling `build/local-repo`, then from Maven Central.
- Mods get owned libraries (HudRendererLib, BrainageLib, the conventions runtime, the Baritone fork) from Maven Central only: no GitHub Packages, no GitHub-release or Ivy repositories.
- The conventions plugins configure only what every mod needs. Anything only some mods use (a dependency such as Cloth Config, Mod Menu, twitch4j, HudRendererLib or Baritone, its Maven repository, extra `fabric.mod.json` properties, production runtime mods) is declared in that mod's own build files, so adding a mod never changes another mod's build.
