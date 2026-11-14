# Client GameTests

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

Every recording is 1920x1080 with GUI scale 3, a 640x360 GUI (`ClientGameTestOptions` sets it, so no mod overrides it), with the caption panel in the top-right corner 5 pixels from the edge and nothing cut off: the panel is at most 368 pixels wide and a diagnostic step's 10 lines are 116 pixels tall, both inside a 640x360 GUI. On a display that already has the card, the client's window is that same 1920x1080, which vanilla centres on the display (`Window.java:118-119` in the 26.3 sources), and the capture starts at the window's own origin and size, which the recording helper reports from inside the client, so the frame holds the window and not a band of desktop. The recording fails when the display is smaller than 1920x1080, or when the window is not that size or does not lie wholly on the display, naming the sizes involved. A helper older than 3.4.0 reports no window, so a screen grab on the card fails naming the helper's version and, when known, the plugin's. So does a mod that starts the recording with `ClientGameTestRecordingSession.start(Runnable)`, which reports no window however new its helper is. Game sound is on at 0.7 master volume with background music off, and the recording fails without it. The same panel position and scale in every mod is the point: a viewer switching between videos sees the same overlay.

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

## Dedicated-server harness

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

## Vanilla-server checks

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
- The server startup counts against the client's [startup deadline](MOD_BUILDS.md#automated-client-launches) (300 s), as it happens inside the same task.
