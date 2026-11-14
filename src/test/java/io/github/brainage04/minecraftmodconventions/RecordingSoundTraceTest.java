package io.github.brainage04.minecraftmodconventions;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingSoundTraceTest {
    /**
     * A sound the engine refuses is not noted in a traced recording's sound list. NeoForge's {@code PlaySoundEvent}
     * cancels a sound the same way: its patch to {@code SoundEngine.play} returns {@code NOT_STARTED} when the event
     * leaves no sound, before the engine reads anything else of it.
     *
     * <p>The engine reads a sound's name only after {@code canPlaySound} (26.3 {@code SoundEngine.java:368-373}), so
     * on this path only the recorder's hook can ask for it, and it asks only to note the sound. A started sound needs
     * an OpenAL device, which a unit test does not have.
     */
    @Test
    void aSoundTheEngineDoesNotStartIsNotNoted() throws ReflectiveOperationException {
        SoundEngine engine = loadedEngineWithoutDevice();
        AtomicInteger named = new AtomicInteger();
        SoundInstance refused = (SoundInstance) Proxy.newProxyInstance(SoundInstance.class.getClassLoader(),
                new Class<?>[]{SoundInstance.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "canPlaySound" -> false;
                    case "getIdentifier" -> {
                        named.incrementAndGet();
                        yield Identifier.withDefaultNamespace("test_sound");
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        assertEquals(SoundEngine.PlayResult.NOT_STARTED, engine.play(refused));
        assertEquals(0, named.get(), "the hook named a sound the engine did not start");
    }

    /** An engine that counts as loaded but has no device: enough for a sound that never reaches one. */
    private static SoundEngine loadedEngineWithoutDevice() throws ReflectiveOperationException {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        SoundEngine engine = (SoundEngine) ((Unsafe) theUnsafe.get(null)).allocateInstance(SoundEngine.class);
        Field loaded = SoundEngine.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(engine, true);
        return engine;
    }
}
