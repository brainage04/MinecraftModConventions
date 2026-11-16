package io.github.brainage04.minecraftmodconventions.gradle.modpublishing.repository;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Carries this build's answer from the visibility check to the store tasks.
 *
 * <p>A store task's {@code onlyIf} must not read the answer from a file: a file left by an earlier build says nothing
 * about this one, and holding a {@link org.gradle.api.Task} reference in {@code onlyIf} breaks the configuration cache.
 * A shared build service is the supported place for one task to hand execution-time state to another, and it starts
 * each build with no answer, so a build that did not run the check refuses the stores.
 */
public abstract class StoreVisibility implements BuildService<BuildServiceParameters.None> {
    private final AtomicReference<Boolean> allowed = new AtomicReference<>();

    public void record(boolean storePublicationAllowed) {
        allowed.set(storePublicationAllowed);
    }

    public boolean wasChecked() {
        return allowed.get() != null;
    }

    /** Whether this build's check allowed store publication; false until the check records an answer. */
    public boolean allowsStorePublication() {
        return Boolean.TRUE.equals(allowed.get());
    }
}
