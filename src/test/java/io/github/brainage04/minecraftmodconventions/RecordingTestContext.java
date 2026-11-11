package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotComparisonOptions;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A client GameTest context without a client, for the helpers whose logic runs on the calling thread:
 * it counts the ticks a helper waits, records the screens it opens and runs the client work against a
 * null client, which is enough where the helper only touches the recorder's own state.
 */
final class RecordingTestContext implements ClientGameTestContext {
    private final List<Integer> waits = new ArrayList<>();
    private final List<Supplier<Screen>> screenSuppliers = new ArrayList<>();
    private int waitedTicks;

    int waitedTicks() {
        return waitedTicks;
    }

    List<Integer> waits() {
        return waits;
    }

    List<Supplier<Screen>> screenSuppliers() {
        return screenSuppliers;
    }

    @Override
    public void waitTick() {
        waitedTicks++;
        try {
            Thread.sleep(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void waitTicks(int ticks) {
        waits.add(ticks);
        waitedTicks += ticks;
    }

    @Override
    public int waitFor(Predicate<Minecraft> predicate) {
        throw new UnsupportedOperationException("waitFor needs a client");
    }

    @Override
    public int waitFor(Predicate<Minecraft> predicate, int timeoutTicks) {
        throw new UnsupportedOperationException("waitFor needs a client");
    }

    @Override
    public int waitForScreen(Class<? extends Screen> screenClass) {
        throw new UnsupportedOperationException("waitForScreen needs a client");
    }

    @Override
    public void setScreen(Supplier<Screen> screenSupplier) {
        screenSuppliers.add(screenSupplier);
    }

    @Override
    public void clickScreenButton(String buttonText) {
        throw new UnsupportedOperationException("clickScreenButton needs a client");
    }

    @Override
    public boolean tryClickScreenButton(String buttonText) {
        throw new UnsupportedOperationException("tryClickScreenButton needs a client");
    }

    @Override
    public Path takeScreenshot(TestScreenshotOptions options) {
        throw new UnsupportedOperationException("takeScreenshot needs a client");
    }

    @Override
    public void assertScreenshotEquals(TestScreenshotComparisonOptions options) {
        throw new UnsupportedOperationException("assertScreenshotEquals needs a client");
    }

    @Override
    public org.joml.Vector2i assertScreenshotContains(TestScreenshotComparisonOptions options) {
        throw new UnsupportedOperationException("assertScreenshotContains needs a client");
    }

    @Override
    public TestInput getInput() {
        throw new UnsupportedOperationException("getInput needs a client");
    }

    @Override
    public TestWorldBuilder worldBuilder() {
        throw new UnsupportedOperationException("worldBuilder needs a client");
    }

    @Override
    public void restoreDefaultGameOptions() {
        throw new UnsupportedOperationException("restoreDefaultGameOptions needs a client");
    }

    @Override
    public <E extends Throwable> void runOnClient(FailableConsumer<Minecraft, E> action) throws E {
        action.accept(null);
    }

    @Override
    public <T, E extends Throwable> T computeOnClient(FailableFunction<Minecraft, T, E> action) throws E {
        return action.apply(null);
    }
}
