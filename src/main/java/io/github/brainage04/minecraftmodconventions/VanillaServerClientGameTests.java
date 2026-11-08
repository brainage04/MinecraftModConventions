package io.github.brainage04.minecraftmodconventions;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.inventory.Hotbar;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Client GameTests against a plain vanilla server, which has none of the mod's content. They run only under
 * {@code runVanillaServerClientGameTest}, which starts that server and names it in {@value #SERVER_ENV}; in every other
 * client GameTest run they do nothing.
 */
public final class VanillaServerClientGameTests {
    /** {@code host:port} of the vanilla server, set by {@code runVanillaServerClientGameTest}. */
    public static final String SERVER_ENV = "MINECRAFTMODCONVENTIONS_VANILLA_SERVER";
    /** The first hotbar slot in the player's inventory menu. */
    public static final int HOTBAR_MENU_SLOT = 36;
    private static final int TIMEOUT_TICKS = 1200;
    /** Ticks the server gets to answer an undecodable packet; it drops the connection at once. */
    private static final int KICK_TICKS = 60;
    private static final String CREATIVE_SLOT_PACKET = "serverbound/minecraft:set_creative_mode_slot";

    private VanillaServerClientGameTests() {
    }

    /** The vanilla server's address, or empty outside {@code runVanillaServerClientGameTest}. */
    public static Optional<String> serverAddress() {
        return serverAddress(System.getenv(SERVER_ENV));
    }

    static Optional<String> serverAddress(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    /**
     * Joins the vanilla server, runs {@code test} in its world and checks that the client is still connected
     * afterwards, then leaves. Does nothing (and returns false) without a vanilla server.
     */
    public static boolean withVanillaServer(ClientGameTestContext context, Consumer<ClientGameTestContext> test) {
        return onVanillaServer(context, test.andThen(after -> assertConnected(
                after, "The client must still be connected to the vanilla server after the test")));
    }

    private static boolean onVanillaServer(ClientGameTestContext context, Consumer<ClientGameTestContext> test) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(test, "test");
        Optional<String> address = serverAddress();
        if (address.isEmpty()) {
            System.out.println("[MMC] " + SERVER_ENV + " is not set; vanilla server check skipped");
            return false;
        }
        join(context, address.get());
        try {
            test.accept(context);
        } finally {
            leave(context);
        }
        return true;
    }

    /**
     * The creative-menu check for a mod that registers items, on a vanilla server: {@code items.serverHasMod()} is
     * false, the player is in creative, no creative tab or the search offers one of the mod's items, a hotbar saved
     * with one loads empty, and taking one in creative keeps the client connected with the slot empty. Then
     * {@code items.extraChecks()} run. Last, it sends the packet the mod must hold back past the mod
     * ({@code set_creative_mode_slot} with the mod's item) and expects the server to drop the client for it, so a
     * pass also shows that the check would catch the kick.
     */
    public static boolean checkCreativeItems(ClientGameTestContext context, CreativeItems items) {
        Objects.requireNonNull(items, "items");
        return onVanillaServer(context, test -> {
            test.runOnClient(client -> {
                if (items.serverHasMod().getAsBoolean()) {
                    throw new AssertionError("A vanilla server must not count as having " + items.modName());
                }
                if (!client.player.hasInfiniteMaterials()) {
                    throw new AssertionError("The vanilla server must put the player in creative");
                }
                CreativeModeTabs.tryRebuildTabContents(
                        client.player.connection.enabledFeatures(), false, client.level.registryAccess());
                for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
                    for (ItemStack stack : tab.getDisplayItems()) {
                        if (items.isModItem().test(stack)) {
                            throw new AssertionError(stack + " is offered in " + tab.getDisplayName().getString());
                        }
                    }
                    for (ItemStack stack : tab.getSearchTabDisplayItems()) {
                        if (items.isModItem().test(stack)) {
                            throw new AssertionError(stack + " is offered in the search");
                        }
                    }
                }

                // A hotbar saved with the item in it, as one saved in singleplayer would be.
                client.player.getInventory().setItem(0, items.sampleItem().get());
                Hotbar hotbar = new Hotbar();
                hotbar.storeFrom(client.player.getInventory(), client.level.registryAccess());
                client.player.getInventory().setItem(0, ItemStack.EMPTY);
                ItemStack restored = hotbar.load(client.level.registryAccess()).getFirst();
                if (!restored.isEmpty()) {
                    throw new AssertionError("A saved hotbar must load " + items.modName()
                            + "'s item empty here, got " + restored);
                }

                // What taking the item from a creative tab does.
                client.gameMode.handleCreativeModeItemAdd(items.sampleItem().get(), HOTBAR_MENU_SLOT);
            });
            test.waitTicks(KICK_TICKS);
            assertConnected(test, "Taking " + items.modName() + "'s item in creative must not disconnect the client");
            test.runOnClient(client -> {
                if (!client.player.inventoryMenu.getSlot(HOTBAR_MENU_SLOT).getItem().isEmpty()) {
                    throw new AssertionError("The slot must stay empty, since the server never got the item");
                }
                items.extraChecks().accept(client);
            });
            assertConnected(test, items.modName() + "'s extra checks must not disconnect the client");
            System.out.println("[MMC] Vanilla server: no " + items.modName() + " item offered, still connected");

            // The packet the mod holds back, sent directly: the kick the check guards against.
            test.runOnClient(client -> client.getConnection().send(
                    new ServerboundSetCreativeModeSlotPacket(HOTBAR_MENU_SLOT, items.sampleItem().get())));
            test.waitFor(client -> client.level == null && screen(client) instanceof DisconnectedScreen, TIMEOUT_TICKS);
            String reason = test.computeOnClient(client -> screen(client).getNarrationMessage().getString());
            if (!reason.contains("Failed to decode packet") || !reason.contains(CREATIVE_SLOT_PACKET)) {
                throw new AssertionError("Expected the vanilla server to drop the client for an undecodable "
                        + CREATIVE_SLOT_PACKET + ", got: " + reason);
            }
            System.out.println("[MMC] Vanilla server: the unguarded packet disconnected the client: " + reason);
        });
    }

    private static void join(ClientGameTestContext context, String address) {
        context.runOnClient(client -> ConnectScreen.startConnecting(
                screen(client),
                client,
                ServerAddress.parseString(address),
                new ServerData("Vanilla server", address, ServerData.Type.OTHER),
                false,
                null));
        context.waitFor(client -> client.level != null && client.player != null && screen(client) == null,
                TIMEOUT_TICKS);
        context.waitTicks(20);
    }

    /** Fails with {@code message} (and the disconnect reason, if any) unless the client is in the server's world. */
    public static void assertConnected(ClientGameTestContext context, String message) {
        context.runOnClient(client -> {
            if (screen(client) instanceof DisconnectedScreen disconnected) {
                throw new AssertionError(message + ": " + disconnected.getNarrationMessage().getString());
            }
            if (client.level == null || client.getConnection() == null
                    || !client.getConnection().isAcceptingMessages()) {
                throw new AssertionError(message);
            }
        });
    }

    private static void leave(ClientGameTestContext context) {
        context.runOnClient(client -> {
            if (client.level != null) {
                client.level.disconnect(Component.literal("Disconnecting"));
                client.disconnectWithSavingScreen();
            }
        });
        context.waitFor(client -> client.level == null, TIMEOUT_TICKS);
        context.waitTicks(2);
        context.setScreen(TitleScreen::new);
    }

    private static Screen screen(Minecraft client) {
        return client.gui.screen();
    }

    /**
     * What {@link #checkCreativeItems} needs from a mod: its name for messages, its own signal for "this server has
     * the mod", which stacks are its items, one of its items to take, and checks of its own to run while connected.
     */
    public record CreativeItems(
            String modName,
            BooleanSupplier serverHasMod,
            Predicate<ItemStack> isModItem,
            Supplier<ItemStack> sampleItem,
            Consumer<Minecraft> extraChecks
    ) {
        public CreativeItems {
            Objects.requireNonNull(modName, "modName");
            Objects.requireNonNull(serverHasMod, "serverHasMod");
            Objects.requireNonNull(isModItem, "isModItem");
            Objects.requireNonNull(sampleItem, "sampleItem");
            Objects.requireNonNull(extraChecks, "extraChecks");
        }

        public static CreativeItems of(
                String modName,
                BooleanSupplier serverHasMod,
                Predicate<ItemStack> isModItem,
                Supplier<ItemStack> sampleItem
        ) {
            return new CreativeItems(modName, serverHasMod, isModItem, sampleItem, _ -> {
            });
        }

        /** Adds checks run on the client thread while connected, after the shared ones and before the kick. */
        public CreativeItems withExtraChecks(Consumer<Minecraft> checks) {
            return new CreativeItems(modName, serverHasMod, isModItem, sampleItem, extraChecks.andThen(checks));
        }
    }
}
