package io.github.brainage04.minecraftmodconventions.mixin;

import com.mojang.blaze3d.audio.Library;
import io.github.brainage04.minecraftmodconventions.GameCaptureAudio;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;

/** While the recorder captures the game, its sound plays into a loopback device the recording renders. */
@Mixin(Library.class)
public abstract class RecordingSoundLibraryMixin {
    @Shadow
    private long currentDevice;

    @Unique
    private boolean recordingLoopback;

    @Shadow
    private static long openDeviceOrFallback(String preferredDevice, String systemDefaultDevice) {
        throw new AssertionError();
    }

    @Redirect(method = "init", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Library;openDeviceOrFallback(Ljava/lang/String;Ljava/lang/String;)J"))
    private long recordingOpenDevice(String preferredDevice, String systemDefaultDevice) {
        recordingLoopback = false;
        if (GameCaptureAudio.isEnabled()) {
            long device = GameCaptureAudio.openDevice();
            if (device == 0) {
                // No silent fallback to the output device: a take recorded that way has no sound, and the
                // recorder would only find out after the whole take. GameCaptureAudio prints the reason on the
                // recorder's failure line, which fails the take at once.
                throw GameCaptureAudio.loopbackUnavailable();
            }
            recordingLoopback = true;
            return device;
        }
        return openDeviceOrFallback(preferredDevice, systemDefaultDevice);
    }

    @ModifyArg(method = "init", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"), index = 1)
    private IntBuffer recordingLoopbackAttributes(IntBuffer attributes) {
        if (!recordingLoopback) {
            return attributes;
        }
        // The buffer lives in init's own stack frame, which is still open around this call.
        return GameCaptureAudio.loopbackAttributes(attributes,
                MemoryStack.stackGet().mallocInt(GameCaptureAudio.loopbackAttributeCapacity(attributes)));
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void recordingStartRendering(CallbackInfo ci) {
        if (recordingLoopback) {
            GameCaptureAudio.deviceOpened(currentDevice);
        }
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void recordingStopRendering(CallbackInfo ci) {
        if (recordingLoopback) {
            GameCaptureAudio.deviceClosing();
        }
    }
}
