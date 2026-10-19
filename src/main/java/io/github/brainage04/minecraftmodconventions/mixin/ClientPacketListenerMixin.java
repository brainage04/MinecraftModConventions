package io.github.brainage04.minecraftmodconventions.mixin;

import io.github.brainage04.minecraftmodconventions.GameTestRecorderEnvironment;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Redirect(
            method = "handleLogin",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/ToastManager;addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V"
            )
    )
    private void minecraftModConventions$suppressUnsecureChatToast(ToastManager toastManager, Toast toast) {
        if (!GameTestRecorderEnvironment.disableUnsecureChatToast()) {
            toastManager.addToast(toast);
        }
    }

    @Redirect(
            method = "handleRecipeBookAdd",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/toasts/RecipeToast;addOrUpdate(Lnet/minecraft/client/gui/components/toasts/ToastManager;Lnet/minecraft/world/item/crafting/display/RecipeDisplay;)V"
            )
    )
    private void minecraftModConventions$suppressRecipeToast(ToastManager toastManager, RecipeDisplay display) {
        if (!GameTestRecorderEnvironment.disableRecipeToasts()) {
            RecipeToast.addOrUpdate(toastManager, display);
        }
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void minecraftModConventions$suppressAdvancementChat(
            ClientboundSystemChatPacket packet,
            CallbackInfo callback
    ) {
        if (GameTestRecorderEnvironment.disableAdvancementChatMessages()
                && isAdvancementAnnouncement(packet.content())) {
            callback.cancel();
        }
    }

    private static boolean isAdvancementAnnouncement(Component component) {
        return component.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().startsWith("chat.type.advancement.");
    }
}
