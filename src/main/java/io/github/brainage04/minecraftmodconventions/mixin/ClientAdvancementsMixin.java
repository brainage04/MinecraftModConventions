package io.github.brainage04.minecraftmodconventions.mixin;

import io.github.brainage04.minecraftmodconventions.GameTestRecorderEnvironment;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.multiplayer.ClientAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientAdvancements.class)
abstract class ClientAdvancementsMixin {
    @Redirect(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V"
            )
    )
    private void minecraftModConventions$suppressAdvancementToast(ToastManager toastManager, Toast toast) {
        if (!GameTestRecorderEnvironment.disableAdvancementToasts()) {
            toastManager.addToast(toast);
        }
    }
}
