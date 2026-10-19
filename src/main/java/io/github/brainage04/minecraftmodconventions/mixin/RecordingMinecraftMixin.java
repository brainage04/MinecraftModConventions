package io.github.brainage04.minecraftmodconventions.mixin;

import io.github.brainage04.minecraftmodconventions.ClientGameTestRecordingSession;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes real simulation and presentation, including loading screens and world teardown. */
@Mixin(Minecraft.class)
public abstract class RecordingMinecraftMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void recordingBeforeWorldTick(CallbackInfo ci) {
        ClientGameTestRecordingSession.beforeWorldTick();
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V", shift = At.Shift.AFTER))
    private void recordingAfterWorldTick(CallbackInfo ci) {
        ClientGameTestRecordingSession.afterWorldTick();
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"))
    private void recordingBeforePresent(CallbackInfo ci) {
        ClientGameTestRecordingSession.beforePresent(((Minecraft) (Object) this).level != null);
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V", shift = At.Shift.AFTER))
    private void recordingAfterPresent(CallbackInfo ci) {
        ClientGameTestRecordingSession.afterPresent(((Minecraft) (Object) this).level != null);
    }

    @Inject(method = {"clearClientLevel", "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V"},
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;",
                    opcode = 181, shift = At.Shift.AFTER))
    private void recordingWorldCleared(CallbackInfo ci) {
        if (((Minecraft) (Object) this).level == null) {
            ClientGameTestRecordingSession.worldCleared();
        }
    }
}
