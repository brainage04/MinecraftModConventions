package io.github.brainage04.minecraftmodconventions.gradle.multiloader;

import org.gradle.api.GradleException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes the NeoForge {@code versionRange} that limits a mod to the Minecraft minor version it is built for.
 *
 * <p>{@code neoforge.mods.toml} gets it as {@code ${minecraft_version_range}}. It matches Fabric's
 * {@code "minecraft": "~${minecraft_version}"}: from {@code minecraft_version} up to, not including, the next
 * minor version.
 * <ul>
 *     <li>{@code 26.2} becomes {@code [26.2,26.3)}; {@code 26.2.1} becomes {@code [26.2.1,26.3)}.</li>
 *     <li>A pre-release or release candidate keeps its suffix in the lower bound: {@code 26.3-pre1} becomes
 *     {@code [26.3-pre1,26.4)}.</li>
 *     <li>Any other shape (a weekly snapshot such as {@code 26w14a}, a bare {@code 26}, four components) has no
 *     minor version to limit to and fails the build.</li>
 * </ul>
 */
public final class MinecraftVersionRange {
    /** Placeholder name expanded in {@code neoforge.mods.toml}. */
    public static final String PROPERTY = "minecraft_version_range";

    private static final Pattern RELEASE = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.\\d+)?(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?");

    private MinecraftVersionRange() {
    }

    public static String of(String minecraftVersion) {
        Matcher matcher = RELEASE.matcher(minecraftVersion);
        if (!matcher.matches()) {
            throw new GradleException("Cannot derive " + PROPERTY + " from minecraft_version '" + minecraftVersion
                    + "': expected MAJOR.MINOR or MAJOR.MINOR.PATCH, optionally with a -pre/-rc suffix.");
        }
        int nextMinor = Math.addExact(Integer.parseInt(matcher.group(2)), 1);
        return "[" + minecraftVersion + "," + matcher.group(1) + "." + nextMinor + ")";
    }
}
