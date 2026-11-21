package io.github.brainage04.minecraftmodconventions.mixin;

import io.github.brainage04.minecraftmodconventions.InputOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the recording's input overlay when a screen opens, so a key held at that moment goes dark.
 *
 * <p>{@code Gui.setScreen} releases every key mapping when a screen opens ({@code Gui.java:263} in
 * 26.3) and no release event reaches the overlay, so without this a key held as chat opens would
 * stay lit for the rest of the recording.
 */
@Mixin(Gui.class)
abstract class RecordingGuiMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void recordingInputScreenOpened(Screen screen, CallbackInfo callback) {
        if (screen != null) {
            InputOverlay.releaseAll();
        }
    }
}
