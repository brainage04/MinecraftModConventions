package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.process.ProcessForkOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * The display environment of every automated client launch. Since 26.3 the client opens its window through SDL3,
 * which prefers Wayland whenever {@code WAYLAND_DISPLAY} is set: launched from a desktop session, a client meant for
 * Xvfb (or the recorder's display) would open on the desktop instead. Every launch therefore uses SDL's X11 driver
 * on {@code DISPLAY}, which Xvfb, the recorder and XWayland all provide.
 */
public final class ClientLaunchEnvironment {
    private ClientLaunchEnvironment() {
    }

    public static void useX11(ProcessForkOptions launch) {
        Map<String, Object> environment = new HashMap<>(launch.getEnvironment());
        useX11(environment);
        launch.setEnvironment(environment);
    }

    public static void useX11(Map<String, ? super String> environment) {
        environment.remove("WAYLAND_DISPLAY");
        environment.put("SDL_VIDEO_DRIVER", "x11");
    }
}
