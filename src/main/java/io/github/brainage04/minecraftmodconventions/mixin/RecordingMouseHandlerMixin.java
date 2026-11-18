package io.github.brainage04.minecraftmodconventions.mixin;

import io.github.brainage04.minecraftmodconventions.InputOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the recording's input overlay from the mouse buttons the game receives. Fabric's client
 * GameTest input calls this same method ({@code TestInputImpl.pressOrReleaseKey}'s mouse branch calls
 * {@code MouseHandlerAccessor.invokeOnButton}), so a test's clicks and a player's clicks both show.
 */
@Mixin(MouseHandler.class)
abstract class RecordingMouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void recordingInputOnButton(long handle, MouseButtonInfo button, int action, CallbackInfo callback) {
        // onButton ignores an event for another window (MouseHandler.java:65), so the overlay does too.
        if (handle != 0L && handle == Minecraft.getInstance().getWindow().handle()) {
            InputOverlay.onMouseButton(button.button(), action);
        }
    }
}
