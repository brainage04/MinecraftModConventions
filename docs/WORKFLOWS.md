# Workflows

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

## First release on the stores

A store job that is skipped for missing configuration writes a line to its step summary as well as a notice: `reusable-publish-modrinth.yml` without the `modrinth_token` secret, `reusable-publish-curseforge.yml` without the `curseforge_token` secret. The CurseForge line names what is missing, the `CURSEFORGE_PROJECT_ID` variable, the `CURSEFORGE_TOKEN` secret or both. `require_stores`, a space-separated list of `modrinth` and `curseforge`, makes such a skip fail the job instead; both publish workflows and `reusable-multiloader-release.yml` take it. A CurseForge job with a token but no project id fails either way.

Before a mod's first store release:

1. **Modrinth:** add the secret `MODRINTH_TOKEN`, a personal access token with the `PROJECT_CREATE`, `PROJECT_WRITE` and `VERSION_CREATE` scopes. The first release creates the project as a draft from `.modrinth/project.json`, `README.md` and the icon. Pass `submit_for_review: true` (`modrinth_submit_for_review: true` to `reusable-multiloader-release.yml`, which submits from the NeoForge job when there is one, after both versions are up) to submit it for review after the upload; otherwise submit it on modrinth.com. Either way it becomes public only once a moderator approves it.
2. **CurseForge:** the [upload API](https://support.curseforge.com/en/support/solutions/articles/9000197321-curseforge-upload-api) uploads files to an existing project and cannot create one, so create the project by hand in the [author console](https://authors.curseforge.com/). Then set the repository variable `CURSEFORGE_PROJECT_ID` to its numeric id (shown on its overview page) and the secret `CURSEFORGE_TOKEN` to a token from the console's [API tokens](https://authors.curseforge.com/#/settings/api-tokens) page. The mods' release workflows pass `secrets.CURSEFORGE_TOKEN` as the `curseforge_token` secret, so a repository that holds its key only as `CURSEFORGE_API_KEY` must add a `CURSEFORGE_TOKEN` secret holding a token from that page.
3. **Dependencies:** release BrainageLib, or any other library the mod depends on, to each store first. Each upload declares the dependencies in `fabric.mod.json`'s `depends` (required), `recommends` and `suggests` (optional), and `conflicts` and `breaks` (incompatible), except `minecraft`, `java`, `fabricloader` and those whose `dependency_overrides` entry in `.modrinth/project.json` sets `skip: true`. A multi-loader mod's NeoForge uploads read `.modrinth/neoforge-dependencies.json` (same shape) instead when it exists; its Fabric uploads always read the Fabric `fabric.mod.json`. Modrinth names them by slug and the upload fails when Modrinth knows no project with that slug, so `brainagelib` has to exist on Modrinth, and be approved before players can install it. CurseForge names them by the entry's `curseforge_slug`, sent as the upload's `relations.projects`. A dependency without one (`fabric-api` needs one too) fails `checkCurseforgeDependencies`, which `reusable-release-prepare.yml` runs before anything is published when `check_curseforge_dependencies` is true (`reusable-multiloader-release.yml` sets it when `publish_curseforge` is on and `curseforge_project_id` is set), and fails CurseForge validation otherwise. `curseforge_skip: true` leaves a dependency off CurseForge only:

   ```json
   "dependency_overrides": {
     "brainagelib": { "curseforge_slug": "brainagelib" },
     "modmenu": { "curseforge_skip": true }
   }
   ```
