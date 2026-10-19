package io.github.brainage04.minecraftmodconventions;

public final class GameTestRecorderEnvironment {
    public static final String ENABLED_PROPERTY = "minecraftmodconventions.clientGameTest";
    public static final String DISABLE_UNSECURE_CHAT_TOAST_PROPERTY =
            "minecraftmodconventions.clientGameTestRecorder.disableUnsecureChatToast";
    public static final String DISABLE_RECIPE_TOASTS_PROPERTY =
            "minecraftmodconventions.clientGameTestRecorder.disableRecipeToasts";
    public static final String DISABLE_ADVANCEMENT_TOASTS_PROPERTY =
            "minecraftmodconventions.clientGameTestRecorder.disableAdvancementToasts";
    public static final String DISABLE_ADVANCEMENT_CHAT_MESSAGES_PROPERTY =
            "minecraftmodconventions.clientGameTestRecorder.disableAdvancementChatMessages";

    public static final String RECORDING_NAME_ENV = "CLIENT_GAMETEST_RECORDING_NAME";
    public static final String RECORDING_PROFILE_ENV = "CLIENT_GAMETEST_RECORDING_PROFILE";
    public static final String RECORDING_TRACE_ENV = "CLIENT_GAMETEST_RECORDING_TRACE";
    public static final String START_SIGNAL_ENV = "CLIENT_GAMETEST_RECORDING_START_SIGNAL";
    public static final String READY_SIGNAL_ENV = "CLIENT_GAMETEST_RECORDING_READY_SIGNAL";
    public static final String STOP_SIGNAL_ENV = "CLIENT_GAMETEST_RECORDING_STOP_SIGNAL";
    public static final String COMPLETE_SIGNAL_ENV = "CLIENT_GAMETEST_RECORDING_COMPLETE_SIGNAL";
    public static final String BOUNDARIES_ENV = "CLIENT_GAMETEST_RECORDING_BOUNDARIES";

    public static final String TEST_PROFILE_ENV = "CLIENT_GAMETEST_PROFILE";
    public static final String TEST_ONLY_ENV = "CLIENT_GAMETEST_ONLY";
    public static final String TEST_SUITE_ENV = "CLIENT_GAMETEST_SUITE";

    private GameTestRecorderEnvironment() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    public static boolean disableUnsecureChatToast() {
        return disabledDuringRecording(DISABLE_UNSECURE_CHAT_TOAST_PROPERTY);
    }

    public static boolean disableRecipeToasts() {
        return disabledDuringRecording(DISABLE_RECIPE_TOASTS_PROPERTY);
    }

    public static boolean disableAdvancementToasts() {
        return disabledDuringRecording(DISABLE_ADVANCEMENT_TOASTS_PROPERTY);
    }

    public static boolean disableAdvancementChatMessages() {
        return disabledDuringRecording(DISABLE_ADVANCEMENT_CHAT_MESSAGES_PROPERTY);
    }

    private static boolean disabledDuringRecording(String property) {
        return isEnabled() && Boolean.parseBoolean(System.getProperty(property, "true"));
    }
}
