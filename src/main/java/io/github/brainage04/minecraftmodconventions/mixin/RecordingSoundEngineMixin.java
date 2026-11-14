package io.github.brainage04.minecraftmodconventions.mixin;

import io.github.brainage04.minecraftmodconventions.ClientGameTestRecordingSession;
import io.github.brainage04.minecraftmodconventions.GameCaptureAudio;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes the sounds a traced recording plays, and keeps a recorded game on its loopback device. The engine reopens
 * its device when the system's device list changes and the current device is not in it
 * ({@code SoundEngine.shouldChangeDevice}, {@code SoundEngine.java:181-213} in the 26.3 sources), and a loopback
 * device is not an enumerated output device, so any change (the recorder's own sink appearing, for one) would
 * restart the sound in the middle of a recording.
 */
@Mixin(SoundEngine.class)
public abstract class RecordingSoundEngineMixin {
    // At RETURN, not HEAD: a sound is noted only when the engine started it. NeoForge's PlaySoundEvent may cancel or
    // replace it inside play (its patch assigns the parameter, instance = ClientHooks.playSound(this, instance);
    // neoforge-26.3.0.99-userdev, patches/net/minecraft/client/sounds/SoundEngine.java.patch), and a callback is
    // handed the parameter's value at its injection point (CallbackInjector.invokeCallback loads it from its local
    // slot through Bytecode.loadArgs: sponge-mixin 0.17.3 sources :767, and the same call in 0.17.4, NeoForge's), so
    // here it names the sound that played.
    @Inject(method = "play", at = @At("RETURN"))
    private void recordingNotePlayedSound(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (cir.getReturnValue() != SoundEngine.PlayResult.NOT_STARTED) {
            ClientGameTestRecordingSession.notePlayedSound(instance.getIdentifier().toString());
        }
    }

    @Inject(method = "shouldChangeDevice", at = @At("HEAD"), cancellable = true)
    private void recordingKeepLoopback(CallbackInfoReturnable<Boolean> cir) {
        if (GameCaptureAudio.isEnabled()) {
            cir.setReturnValue(false);
        }
    }
}
