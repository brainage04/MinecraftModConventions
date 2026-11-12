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
    @Inject(method = "play", at = @At("HEAD"))
    private void recordingNotePlayedSound(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        ClientGameTestRecordingSession.notePlayedSound(instance.getIdentifier().toString());
    }

    @Inject(method = "shouldChangeDevice", at = @At("HEAD"), cancellable = true)
    private void recordingKeepLoopback(CallbackInfoReturnable<Boolean> cir) {
        if (GameCaptureAudio.isEnabled()) {
            cir.setReturnValue(false);
        }
    }
}
