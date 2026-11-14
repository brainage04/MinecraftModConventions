package io.github.brainage04.minecraftmodconventions;

import com.google.common.collect.HashMultimap;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordingSoundTraceTest {
    private static final Identifier SOUND = Identifier.withDefaultNamespace("test_sound");

    /**
     * A sound the engine refuses is not noted in a traced recording's sound list. This one fails
     * {@code canPlaySound}, one of vanilla's {@code NOT_STARTED} answers, and the one a NeoForge copy of the hook
     * meets when {@code PlaySoundEvent} cancels a sound.
     *
     * <p>The engine reads a sound's name only after {@code canPlaySound} (26.3 {@code SoundEngine.java:368-373}), so
     * on this path only the recorder's hook can ask for it, and it asks only to note the sound.
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
                        yield SOUND;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        assertEquals(SoundEngine.PlayResult.NOT_STARTED, engine.play(refused));
        assertEquals(0, named.get(), "the hook named a sound the engine did not start");
    }

    /**
     * A sound the engine starts is noted once. The engine reads the sound's name itself too, so the test counts only
     * the reads made from the recorder's hook.
     */
    @Test
    void aSoundTheEngineStartsIsNoted() throws ReflectiveOperationException {
        SoundEngine engine = loadedEngineWithoutDevice();
        // What play reads after its early returns (26.3 SoundEngine.java:398-463), with no device behind any of it.
        OptionInstance<?> masterVolume = allocate(OptionInstance.class);
        set(masterVolume, "value", 1.0);
        Map<SoundSource, OptionInstance<?>> volumes = new EnumMap<>(SoundSource.class);
        volumes.put(SoundSource.MASTER, masterVolume);
        Options options = allocate(Options.class);
        set(options, "soundSourceVolumes", volumes);
        set(engine, "options", options);
        Object2FloatOpenHashMap<SoundSource> gain = new Object2FloatOpenHashMap<>();
        gain.defaultReturnValue(1.0F);
        set(engine, "gainBySource", gain);
        set(engine, "listeners", new ArrayList<>());
        set(engine, "soundDeleteTime", new HashMap<>());
        set(engine, "instanceToChannel", new HashMap<>());
        set(engine, "instanceBySource", HashMultimap.create());
        Channel channel = allocate(Channel.class);
        // The channel's tasks go to an executor that drops them, so nothing calls OpenAL.
        set(engine, "channelAccess", new ChannelAccess(null, task -> { }) {
            @Override
            public CompletableFuture<ChannelHandle> createHandle(Library.Pool pool) {
                return CompletableFuture.completedFuture(new ChannelHandle(channel));
            }
        });
        Sound sound = allocate(Sound.class);
        set(sound, "location", SOUND);
        SoundBufferLibrary buffers = allocate(SoundBufferLibrary.class);
        // A buffer that never loads: play reads no file, and the channel is never told to play.
        set(buffers, "cache", new HashMap<>(Map.of(sound.getPath(), new CompletableFuture<>())));
        set(engine, "soundBuffers", buffers);
        WeighedSoundEvents event = allocate(WeighedSoundEvents.class);
        AtomicInteger namedByHook = new AtomicInteger();
        SoundInstance started = (SoundInstance) Proxy.newProxyInstance(SoundInstance.class.getClassLoader(),
                new Class<?>[]{SoundInstance.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "canPlaySound" -> true;
                    case "canStartSilent", "isLooping", "isRelative" -> false;
                    case "getOrResolve" -> event;
                    case "getSound" -> sound;
                    case "getSource" -> SoundSource.MASTER;
                    case "getVolume", "getPitch" -> 1.0F;
                    case "getDelay" -> 0;
                    case "getX", "getY", "getZ" -> 0.0;
                    case "getAttenuation" -> SoundInstance.Attenuation.NONE;
                    case "getIdentifier" -> {
                        if (StackWalker.getInstance().walk(frames -> frames.anyMatch(
                                frame -> frame.getMethodName().contains("recordingNotePlayedSound")))) {
                            namedByHook.incrementAndGet();
                        }
                        yield SOUND;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        assertEquals(SoundEngine.PlayResult.STARTED, engine.play(started));
        assertEquals(1, namedByHook.get(), "the hook notes a started sound once");
    }

    /** An engine that counts as loaded but has no device: enough for a sound that never reaches one. */
    private static SoundEngine loadedEngineWithoutDevice() throws ReflectiveOperationException {
        SoundEngine engine = allocate(SoundEngine.class);
        set(engine, "loaded", true);
        return engine;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) ((Unsafe) theUnsafe.get(null)).allocateInstance(type);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
