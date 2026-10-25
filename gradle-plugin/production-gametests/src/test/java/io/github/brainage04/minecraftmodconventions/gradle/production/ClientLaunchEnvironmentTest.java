package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientLaunchEnvironmentTest {
    @Test
    void onLinuxALaunchFromAWaylandSessionUsesX11OnItsDisplay() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "DISPLAY", ":90", "WAYLAND_DISPLAY", "wayland-0", "SDL_VIDEO_DRIVER", "wayland"));

        ClientLaunchEnvironment.useX11(environment, "Linux");

        assertEquals(Map.of("DISPLAY", ":90", "SDL_VIDEO_DRIVER", "x11"), environment);
    }

    @Test
    void otherSystemsHaveNoX11DriverAndKeepTheirEnvironment() {
        for (String os : new String[] {"Windows 11", "Mac OS X"}) {
            Map<String, String> environment = new HashMap<>(Map.of("WAYLAND_DISPLAY", "wayland-0"));

            ClientLaunchEnvironment.useX11(environment, os);

            assertEquals(Map.of("WAYLAND_DISPLAY", "wayland-0"), environment, os);
        }
    }
}
