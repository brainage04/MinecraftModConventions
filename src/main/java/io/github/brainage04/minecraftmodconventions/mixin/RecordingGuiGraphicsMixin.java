package io.github.brainage04.minecraftmodconventions.mixin;

import com.mojang.blaze3d.platform.Window;
import io.github.brainage04.minecraftmodconventions.InputOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the recording's input overlay last, on top of the HUD, an open screen and its tooltips.
 *
 * <p>{@code Gui.extractRenderState} ends with {@code graphics.applyCursor(window)}
 * ({@code Gui.java:210} in the 26.3 sources), after the HUD, the screen, the toasts and the debug
 * overlay, so drawing at the head of that call puts the overlay above all of them and its cursor above
 * the screen's widgets.
 */
@Mixin(GuiGraphicsExtractor.class)
abstract class RecordingGuiGraphicsMixin {
    @Inject(method = "applyCursor", at = @At("HEAD"))
    private void recordingInputOverlay(Window window, CallbackInfo callback) {
        InputOverlay.render((GuiGraphicsExtractor) (Object) this, Minecraft.getInstance());
    }
}
