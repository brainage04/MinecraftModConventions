package io.github.brainage04.minecraftmodconventions.gradle.fabric;

import org.gradle.api.GradleException;
import org.gradle.api.Project;

/** Supported Fabric mod environment layouts. */
public enum ModSide {
    BOTH,
    CLIENT,
    SERVER;

    public static ModSide from(Project project) {
        Object value = project.findProperty(FabricModConventionsPlugin.MOD_SIDE_PROPERTY);
        if (value == null || value.toString().isBlank()) {
            throw new GradleException(
                    FabricModConventionsPlugin.PLUGIN_ID + " requires project property '"
                            + FabricModConventionsPlugin.MOD_SIDE_PROPERTY + "'."
            );
        }
        return parse(value.toString());
    }

    public static ModSide parse(String value) {
        return switch (value.strip()) {
            case "both" -> BOTH;
            case "client" -> CLIENT;
            case "server" -> SERVER;
            default -> throw new GradleException(
                    "Project property '" + FabricModConventionsPlugin.MOD_SIDE_PROPERTY
                            + "' must be one of both, client, or server, but was '" + value + "'."
            );
        };
    }
}
