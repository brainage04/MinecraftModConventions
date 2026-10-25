package io.github.brainage04.minecraftmodconventions.gradle.production;

import org.gradle.process.ProcessForkOptions;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The display environment of every automated client launch (GameTests and recordings). Since 26.3 the client opens
 * its window through SDL3, which prefers Wayland whenever {@code WAYLAND_DISPLAY} is set: launched from a desktop
 * session, a client meant for Xvfb (or the recorder's display) would open on the desktop instead. On Linux such a
 * launch therefore uses SDL's X11 driver on {@code DISPLAY}, which Xvfb, the recorder and XWayland all provide. Other
 * systems have no X11 driver and are left alone.
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
        useX11(environment, System.getProperty("os.name", ""));
    }

    static void useX11(Map<String, ? super String> environment, String osName) {
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) {
            return;
        }
        environment.remove("WAYLAND_DISPLAY");
        environment.put("SDL_VIDEO_DRIVER", "x11");
    }
}
