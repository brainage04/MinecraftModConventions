package io.github.brainage04.minecraftmodconventions;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record GameTestScenarioSelection(
        Set<String> onlyIds,
        Set<String> suites,
        Set<String> profiles,
        boolean allProfiles
) {
    public static final String PROFILE_ALL = "all";

    public GameTestScenarioSelection {
        onlyIds = copyTextSet(onlyIds, "onlyIds");
        suites = copyTextSet(suites, "suites");
        profiles = copyTextSet(profiles, "profiles");
    }

    public static GameTestScenarioSelection fromEnvironment() {
        String profileValue = System.getenv(GameTestRecorderEnvironment.TEST_PROFILE_ENV);
        return new GameTestScenarioSelection(
                envNamedSet(GameTestRecorderEnvironment.TEST_ONLY_ENV),
                envNamedSet(GameTestRecorderEnvironment.TEST_SUITE_ENV),
                profileSet(profileValue),
                profileMatchesAll(profileValue)
        );
    }

    public boolean matches(String id, String suite, Collection<String> scenarioProfiles) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(scenarioProfiles, "scenarioProfiles");
        return matchesIdOrSuite(id, suite) && matchesProfile(scenarioProfiles);
    }

    private boolean matchesIdOrSuite(String id, String suite) {
        return onlyIds.isEmpty() && suites.isEmpty()
                || onlyIds.contains(normalize(id))
                || suites.contains(normalize(suite));
    }

    private boolean matchesProfile(Collection<String> scenarioProfiles) {
        if (allProfiles || profiles.isEmpty()) {
            return true;
        }

        for (String scenarioProfile : scenarioProfiles) {
            if (profiles.contains(normalize(scenarioProfile))) {
                return true;
            }
        }
        return false;
    }

    private static boolean profileMatchesAll(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return splitSet(value).stream().anyMatch(PROFILE_ALL::equalsIgnoreCase);
    }

    private static Set<String> profileSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> profiles = new LinkedHashSet<>(splitSet(value));
        profiles.removeIf(PROFILE_ALL::equalsIgnoreCase);
        return Collections.unmodifiableSet(profiles);
    }

    private static Set<String> envNamedSet(String name) {
        return splitSet(System.getenv(name));
    }

    private static Set<String> splitSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        LinkedHashSet<String> values = new LinkedHashSet<>();
        Arrays.stream(value.split(","))
                .map(GameTestScenarioSelection::normalize)
                .filter(part -> !part.isBlank())
                .forEach(values::add);
        return Collections.unmodifiableSet(values);
    }

    private static Set<String> copyTextSet(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                copy.add(normalized);
            }
        }
        return Collections.unmodifiableSet(copy);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
