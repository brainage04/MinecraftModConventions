package io.github.brainage04.minecraftmodconventions.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.brainage04.minecraftmodconventions.InputOverlay;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the recording's input overlay from the keyboard events the game receives.
 *
 * <p>Fabric's client GameTest input reaches the game through this same method
 * ({@code TestInputImpl.pressOrReleaseKey} calls {@code KeyboardHandlerAccessor.invokeKeyPress}), so a
 * test's presses and a player's presses both show.
 */
@Mixin(KeyboardHandler.class)
abstract class RecordingKeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void recordingInputKeyPress(long handle, int action, KeyEvent event, CallbackInfo callback) {
        if (isThisWindow(handle)) {
            InputOverlay.onKey(InputConstants.getKey(event), action);
        }
    }

    /** The same window check {@code keyPress} makes before it acts on an event ({@code KeyboardHandler.java:437}). */
    private static boolean isThisWindow(long handle) {
        return handle != 0L && handle == Minecraft.getInstance().getWindow().handle();
    }
}
