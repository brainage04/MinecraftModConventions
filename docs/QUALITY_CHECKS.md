# Quality checks

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
