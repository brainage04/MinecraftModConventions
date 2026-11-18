package io.github.brainage04.minecraftmodconventions.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.brainage04.minecraftmodconventions.InputOverlay;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the recording's input overlay from the keyboard events the game receives.
 *
 * <p>Fabric's client GameTest input reaches the game through these same two methods
 * ({@code TestInputImpl.pressOrReleaseKey} calls {@code KeyboardHandlerAccessor.invokeKeyPress}, and
 * {@code typeChar} calls {@code invokeCharTyped}), so a test's presses and a player's presses both show.
 */
@Mixin(KeyboardHandler.class)
abstract class RecordingKeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void recordingInputKeyPress(long handle, int action, KeyEvent event, CallbackInfo callback) {
        InputOverlay.onKey(InputConstants.getKey(event), action);
    }

    @Inject(method = "charTyped", at = @At("HEAD"))
    private void recordingInputCharTyped(long handle, CharacterEvent event, CallbackInfo callback) {
        InputOverlay.onChar(event.codepoint());
    }
}
