package io.github.brainage04.minecraftmodconventions.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.brainage04.minecraftmodconventions.ClientGameTestPlayerActions;
import net.fabricmc.fabric.impl.client.gametest.TestInputImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Fails a recorded client GameTest that presses a key or button its test input already holds. Every
 * hold, press and timed hold of Fabric's {@code TestInputImpl} ends in {@code holdKey(Key)}, which adds
 * the key to {@code KEYS_DOWN} and sends the press only when the key was not there already; a second
 * press is dropped without a word, and a {@code pressKey} on top of a hold releases it. Outside a
 * recording the call does nothing ({@link ClientGameTestPlayerActions#checkHold}).
 */
@Mixin(TestInputImpl.class)
abstract class ClientGameTestInputMixin {
    @Shadow
    @Final
    private static Set<InputConstants.Key> KEYS_DOWN;

    @Inject(method = "holdKey(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V", at = @At("HEAD"))
    private void requireKeyReleased(InputConstants.Key key, CallbackInfo callback) {
        ClientGameTestPlayerActions.checkHold(key, KEYS_DOWN.contains(key));
    }
}
