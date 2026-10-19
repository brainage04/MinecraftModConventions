package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftModConventions implements ClientModInitializer {
    public static final String MOD_ID = "minecraftmodconventions";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientGameTestRecordingHud.initialize();
    }
}
