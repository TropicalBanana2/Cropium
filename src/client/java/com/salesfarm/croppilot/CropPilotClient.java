package com.salesfarm.croppilot;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;

public final class CropPilotClient implements ClientModInitializer {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("crop-pilot", "status");
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HUD_ID);
    private static HarvestController activeController;
    private static MineController activeMine;
    private static CropPilotClient instance;

    private CropPilotConfig config;
    private HarvestController controller;
    private MineController mine;
    private PetOpenerController petOpener;
    private MerchantRestockController merchantRestock;
    private PlotScannerController plotScanner;
    private FieldProfileStore profiles;
    private KeyMapping boundsKey;
    private KeyMapping toggleKey;
    private KeyMapping pauseKey;
    private KeyMapping clearKey;
    private KeyMapping scanKey;
    private KeyMapping configKey;
    private final AttackResumeGate attackResumeGate = new AttackResumeGate();
    private final MiningHealth miningHealth = new MiningHealth();
    private boolean attackAtTickStart;
    private boolean menuRequested;
    private String preparationStatus = "Choose a module and press Start";
    private String targetSnapshot = "No active mining target observed yet";
    private boolean toolSnapshot;

    @Override
    public void onInitializeClient() {
        instance = this;
        config = CropPilotConfig.load();
        profiles = FieldProfileStore.load();
        controller = new HarvestController(config, profiles);
        mine = new MineController(config);
        petOpener = new PetOpenerController(config, controller);
        plotScanner = new PlotScannerController(config);
        merchantRestock = new MerchantRestockController(config, controller, mine, petOpener, plotScanner);
        activeController = controller;
        activeMine = mine;
        boundsKey = key("key.crop-pilot.bounds", GLFW.GLFW_KEY_B);
        toggleKey = key("key.crop-pilot.toggle", GLFW.GLFW_KEY_H);
        pauseKey = key("key.crop-pilot.pause", GLFW.GLFW_KEY_P);
        clearKey = key("key.crop-pilot.clear", GLFW.GLFW_KEY_R);
        scanKey = key("key.crop-pilot.scan", GLFW.GLFW_KEY_J);
        configKey = key("key.crop-pilot.config", GLFW.GLFW_KEY_O);
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?>) || minecraft.player == null) return;
            if (!merchantRestock.isActive() && !petOpener.isActive() && !controller.isActive() && !mine.isActive()
                && !(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) return;
            // Native buttons stay outside the container slots and never issue slot clicks.
            Screens.getWidgets(screen).add(Button.builder(Component.literal("Stop mod"), button -> {
                stopAll(); preparationStatus = "Stopped; inspect the open inventory before continuing";
            }).bounds(4, 4, 64, 20).build());
            Screens.getWidgets(screen).add(Button.builder(Component.literal("Controls"), button -> {
                if (minecraft.player == null || !minecraft.player.containerMenu.getCarried().isEmpty()) {
                    preparationStatus = "Put the carried item back before closing"; return;
                }
                stopAll();
                minecraft.player.closeContainer();
                menuRequested = true;
            }).bounds(4, 28, 64, 20).build());
        });

        ClientTickEvents.START_CLIENT_TICK.register(minecraft -> {
            attackAtTickStart = minecraft.options.keyAttack.isDown();
            restoreFirstSlot(minecraft);
            attackResumeGate.beforeTick(minecraft.gui.screen() != null);
            controller.snapshotCrops(minecraft);
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        AttackBlockCallback.EVENT.register((player, level, hand, position, direction) ->
            player == Minecraft.getInstance().player && (!mine.mayAttack(Minecraft.getInstance(), position)
                || !controller.mayAttack(Minecraft.getInstance(), position)
                || mine.isMining() && !correctTool(Minecraft.getInstance(), 2)
                || controller.isHarvesting() && !correctTool(Minecraft.getInstance(), 1))
                ? InteractionResult.FAIL : InteractionResult.PASS);
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, position, state) -> {
            controller.onBlockBroken(position);
            mine.onBlockBroken(position);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receivedAt) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && !sender.id().equals(minecraft.player.getUUID())) {
                minecraft.execute(() -> controller.onChatMessage(minecraft, message));
                minecraft.execute(() -> mine.onChatMessage(minecraft, message));
                minecraft.execute(() -> petOpener.onChatMessage(minecraft, message));
                minecraft.execute(() -> merchantRestock.onChatMessage(minecraft, message));
            }
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> merchantRestock.onChatMessage(minecraft, message));
            if (!overlay) {
                minecraft.execute(() -> controller.onChatMessage(minecraft, message));
                minecraft.execute(() -> mine.onChatMessage(minecraft, message));
                minecraft.execute(() -> petOpener.onChatMessage(minecraft, message));
            }
        });
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            if (Minecraft.getInstance().gui.screen() != null) return;
            controller.collectGizmos(Minecraft.getInstance());
            mine.collectGizmos(Minecraft.getInstance());
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, HUD_ID, (graphics, deltaTracker) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || (!config.showHud && !config.glowInspector)) {
                return;
            }
            if (config.showHud) {
                String first = merchantRestock.isActive() ? "Cropium • Merchant " + (merchantRestock.workflowIndex() + 1)
                    + "/" + merchantRestock.workflowSteps().size() : mine.isActive() ? mine.hudLineOne() : controller.hudLineOne();
                String second = merchantRestock.isActive() ? merchantRestock.workflowSteps().get(Math.max(0, merchantRestock.workflowIndex()))
                    : mine.isActive() ? mine.hudLineTwo() : controller.hudLineTwo();
                String third = mine.isActive() ? mine.hudLineThree() : controller.hudLineThree();
                String fourth = mine.isActive() ? mine.hudLineFour() : controller.hudLineFour();
                int width = Math.max(
                    Math.max(minecraft.font.width(first), minecraft.font.width(second)),
                    Math.max(minecraft.font.width(third), minecraft.font.width(fourth))) + 18;
                int accent = mine.isActive() ? mine.hudAccentColor() : controller.hudAccentColor();
                graphics.fill(6, 6, 7 + width, 56, 0x55000000);
                graphics.fill(5, 5, 5 + width, 54, 0xC0121822);
                graphics.fill(5, 5, 8, 54, accent);
                graphics.fill(8, 5, 5 + width, 7, accent);
                graphics.text(minecraft.font, first, 13, 10, 0xFFFFFFFF, true);
                graphics.text(minecraft.font, second, 13, 21, 0xFFB8E994, true);
                graphics.text(minecraft.font, third, 13, 32, 0xFFFFD166, true);
                graphics.text(minecraft.font, fourth, 13, 43, 0xFFAEC6CF, true);
                String health = merchantRestock.isActive() ? merchantRestock.status()
                    : config.showMiningHealth ? miningHealth.status() : "";
                if (menuRequested) health = "Menu queued: waiting for inventory workflow to finish";
                if (!health.isEmpty()) {
                    int healthWidth = Math.min(minecraft.font.width(health), 430);
                    graphics.fill(5, 55, 21 + healthWidth, 70, 0xD0121822);
                    graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(health, 430),
                        13, 58, 0xFFD8E2EE, false);
                }
            }
            if (config.glowInspector) {
                java.util.List<String> lines = controller.glowInspectorLines();
                int y = config.showHud ? 76 : 6;
                int width = lines.stream().mapToInt(minecraft.font::width).max().orElse(160) + 16;
                graphics.fill(5, y, 5 + width, y + 38, 0xD010151F);
                graphics.fill(5, y, 8, y + 38, 0xFF78E6A8);
                for (int index = 0; index < lines.size(); index++) {
                    graphics.text(minecraft.font, lines.get(index), 13, y + 5 + index * 11,
                        index == 0 ? 0xFF78E6A8 : 0xFFD8E2EE, true);
                }
            }
        });
        registerProfileCommands();
    }

    private void registerProfileCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommands.literal("cropium")
                .then(ClientCommands.literal("scan")
                    .executes(context -> controller.scanField(context.getSource().getClient()) ? 1 : 0))
                .then(ClientCommands.literal("anchor")
                    .executes(context -> controller.anchorImportedField(context.getSource().getClient()) ? 1 : 0))
                .then(ClientCommands.literal("profile")
                    .then(ClientCommands.literal("save")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                            .executes(context -> saveProfile(context.getSource(), StringArgumentType.getString(context, "name")))))
                    .then(ClientCommands.literal("load")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                            .executes(context -> loadProfile(context.getSource(), StringArgumentType.getString(context, "name")))))
                    .then(ClientCommands.literal("delete")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                            .executes(context -> deleteProfile(context.getSource(), StringArgumentType.getString(context, "name")))))
                    .then(ClientCommands.literal("list").executes(context -> listProfiles(context.getSource())))
                )
        ));
    }

    private int saveProfile(FabricClientCommandSource source, String name) {
        Minecraft minecraft = source.getClient();
        String world = FieldProfileStore.worldKey(minecraft);
        FieldProfileStore.Profile profile = controller.currentProfile();
        if (world == null || profile == null) {
            source.sendError(net.minecraft.network.chat.Component.literal("Set two field corners in a world first"));
            return 0;
        }
        try {
            profiles.put(world, name, profile);
            source.sendFeedback(net.minecraft.network.chat.Component.literal("Saved Cropium field '" + name.trim() + "'"));
            return 1;
        } catch (IllegalArgumentException | IOException exception) {
            source.sendError(net.minecraft.network.chat.Component.literal("Could not save profile: " + exception.getMessage()));
            return 0;
        }
    }

    private int loadProfile(FabricClientCommandSource source, String name) {
        String world = FieldProfileStore.worldKey(source.getClient());
        FieldProfileStore.Profile profile = world == null ? null : profiles.get(world, name);
        if (profile == null) {
            source.sendError(net.minecraft.network.chat.Component.literal("No field named '" + name.trim() + "' in this world and dimension"));
            return 0;
        }
        if (!controller.applyProfile(source.getClient(), profile)) {
            source.sendError(net.minecraft.network.chat.Component.literal("That saved field is invalid"));
            return 0;
        }
        source.sendFeedback(net.minecraft.network.chat.Component.literal("Loaded Cropium field '" + profile.name + "'"));
        return 1;
    }

    private int deleteProfile(FabricClientCommandSource source, String name) {
        String world = FieldProfileStore.worldKey(source.getClient());
        try {
            if (world != null && profiles.delete(world, name)) {
                source.sendFeedback(net.minecraft.network.chat.Component.literal("Deleted Cropium field '" + name.trim() + "'"));
                return 1;
            }
            source.sendError(net.minecraft.network.chat.Component.literal("No field named '" + name.trim() + "' in this world and dimension"));
        } catch (IOException exception) {
            source.sendError(net.minecraft.network.chat.Component.literal("Could not delete profile: " + exception.getMessage()));
        }
        return 0;
    }

    private int listProfiles(FabricClientCommandSource source) {
        String world = FieldProfileStore.worldKey(source.getClient());
        java.util.List<String> names = world == null ? java.util.List.of() : profiles.names(world);
        source.sendFeedback(net.minecraft.network.chat.Component.literal(names.isEmpty()
            ? "No Cropium fields saved in this world and dimension"
            : "Cropium fields: " + String.join(", ", names)));
        return names.size();
    }

    private void tick(Minecraft minecraft) {
        controller.prepareWorld(minecraft);
        plotScanner.tick(minecraft);
        if (configKey.consumeClick()) menuRequested = true;
        if (menuRequested && openMenu(minecraft)) {
            menuRequested = false;
            return;
        }
        if (minecraft.gui.screen() == null && !merchantRestock.isActive()) {
            while (boundsKey.consumeClick()) {
                controller.selectNextCorner(minecraft);
            }
            while (toggleKey.consumeClick()) {
                if (mine.isActive()) {
                    if (mine.isPaused()) preparedStart(mine); else mine.stop(minecraft);
                    continue;
                }
                if (controller.isActive() && !controller.isPaused()) controller.stop(minecraft);
                else preparedStart(controller);
            }
            while (pauseKey.consumeClick()) {
                if (!merchantRestock.isActive()) {
                    if (mine.isActive()) mine.pause(minecraft);
                    else controller.pause(minecraft);
                }
            }
            while (clearKey.consumeClick()) {
                controller.clear(minecraft);
            }
            while (scanKey.consumeClick()) {
                controller.scanField(minecraft);
            }
        }
        boolean merchantWasActive = merchantRestock.isActive();
        merchantRestock.tick(minecraft);
        restoreFirstSlot(minecraft);
        petOpener.tick(minecraft);
        if (!merchantWasActive && !merchantRestock.isActive()) {
            controller.tick(minecraft);
            mine.tick(minecraft);
        }
        // A GUI sets vanilla's missTime to 10000. Holding attack immediately on
        // close never clears it; one released game tick does, with no extra packets.
        boolean inputAvailable = minecraft.player != null && minecraft.level != null
            && minecraft.gui.screen() == null && !merchantWasActive && !merchantRestock.isActive();
        int attackOwner = !inputAvailable ? 0 : mine.isMining() ? 2 : controller.isHarvesting() ? 1 : 0;
        boolean safeAttack = attackOwner == 2 ? mine.safeAttack(minecraft)
            : attackOwner == 1 && controller.safeAttack(minecraft);
        if (attackResumeGate.watchdog(attackOwner, safeAttack, attackAtTickStart,
            attackOwner == 2 ? mine.totalBlocksMined() : controller.totalBlocksMined(), System.nanoTime())
            && attackOwner == 2) {
            mine.noteAttackRearm();
        }
        int healthOwner = attackOwner == 2 && mine.recoveryReady() ? 2
            : attackOwner == 1 && controller.recoveryReady() ? 1 : 0;
        if (healthOwner != 0) {
            targetSnapshot = healthOwner == 2 ? mine.miningTargetStatus(minecraft) : controller.miningTargetStatus(minecraft);
            toolSnapshot = correctTool(minecraft, healthOwner);
        }
        MiningHealth.Action action = miningHealth.update(healthOwner, attackAtTickStart, safeAttack,
            correctTool(minecraft, healthOwner), healthOwner == 2 ? mine.totalBlocksMined() : controller.totalBlocksMined(),
            System.nanoTime());
        if (action != MiningHealth.Action.NONE && healthOwner != 0) {
            if (action == MiningHealth.Action.STOP || !restoreTool(minecraft, healthOwner)) {
                preparationStatus = (action == MiningHealth.Action.STOP
                    ? miningHealth.status() : "Stopped: slot-1 mining tool unavailable")
                    + " — " + targetSnapshot + "; attack " + (attackAtTickStart ? "held" : "released")
                    + "; mouse " + (minecraft.mouseHandler.isMouseGrabbed() ? "captured" : "uncaptured");
                if (healthOwner == 2) mine.stop(minecraft, preparationStatus);
                else controller.stop(minecraft, preparationStatus);
            } else {
                attackResumeGate.rearm();
                if (healthOwner == 2) mine.recoverMiningInput(minecraft, action == MiningHealth.Action.RECOVER);
                else controller.recoverMiningInput(minecraft, action == MiningHealth.Action.RECOVER);
            }
        }
        if (attackResumeGate.afterTick(minecraft.gui.screen() != null,
            controller.isRunning() || mine.isRunning())) {
            minecraft.options.keyAttack.setDown(false);
        }
    }

    private static KeyMapping key(String name, int code) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.Type.KEYSYM, code, CATEGORY));
    }

    private void restoreFirstSlot(Minecraft minecraft) {
        if (minecraft.player != null && HotbarGuard.shouldRestore(
            controller.isRunning() || mine.isRunning() || petOpener.isActive(), merchantRestock.isActive(),
            minecraft.gui.screen() != null, minecraft.player.containerMenu.getCarried().isEmpty(),
            minecraft.player.containerMenu == minecraft.player.inventoryMenu, minecraft.player.getInventory().getSelectedSlot())) {
            minecraft.player.getInventory().setSelectedSlot(HotbarGuard.FIRST_SLOT);
        }
    }

    private boolean openMenu(Minecraft minecraft) {
        if (minecraft.player == null || merchantRestock.isActive()) return false;
        if (!minecraft.player.containerMenu.getCarried().isEmpty()
            || minecraft.player.containerMenu != minecraft.player.inventoryMenu) return false;
        var screen = minecraft.gui.screen();
        if (screen instanceof CropiumScreen) return true;
        if (screen != null) return false;
        if (mine.isRunning()) {
            if (mine.canPause()) mine.pause(minecraft);
            else mine.stop(minecraft);
        }
        if (controller.isRunning()) controller.pause(minecraft);
        // Egg hatching is resumed with its normal verified menu sequence, not by
        // retaining stale container state underneath a custom screen.
        if (petOpener.isActive()) petOpener.stop(minecraft);
        minecraft.setScreenAndShow(new CropiumScreen(null, config, controller,
            mine, merchantRestock, plotScanner, java.util.List.of(controller, mine, petOpener)));
        return true;
    }

    public static boolean handleMenuMouse(int button, int action) {
        if (instance == null || !instance.config.mouseMenuShortcut || button != instance.config.mouseMenuButton) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return false;
        if (action == GLFW.GLFW_PRESS) {
            if (minecraft.gui.screen() instanceof CropiumScreen screen) screen.onClose();
            else instance.menuRequested = true;
        }
        return true;
    }

    private static boolean usableTool(net.minecraft.world.item.ItemStack item) {
        return HotbarGuard.usableItem(item.isEmpty(), item.isDamageableItem(), item.getDamageValue(), item.getMaxDamage());
    }

    private boolean correctTool(Minecraft minecraft, int owner) {
        if (minecraft.player == null || owner == 0) return false;
        int slot = HotbarGuard.FIRST_SLOT;
        return slot >= 0 && minecraft.player.getInventory().getSelectedSlot() == slot
            && usableTool(minecraft.player.getInventory().getItem(slot));
    }

    private boolean restoreTool(Minecraft minecraft, int owner) {
        if (minecraft.player == null || minecraft.gui.screen() != null || merchantRestock.isActive()) return false;
        int slot = HotbarGuard.FIRST_SLOT;
        if (slot < 0 || !usableTool(minecraft.player.getInventory().getItem(slot))) return false;
        minecraft.player.getInventory().setSelectedSlot(slot);
        return true;
    }

    public static java.util.List<String> preflightLines(String module) {
        if (instance == null) return java.util.List.of("Client not ready");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return java.util.List.of("Join a world first");
        var inventory = minecraft.player.getInventory();
        int slot = HotbarGuard.FIRST_SLOT;
        String tool = "Tool slot " + (slot + 1) + ": " + (usableTool(inventory.getItem(slot))
            ? inventory.getItem(slot).getHoverName().getString() : "select a usable mining tool");
        return java.util.List.of(module.equals("mine") ? instance.mine.preflightStatus(minecraft)
                : instance.controller.preflightStatus(minecraft), tool,
            inventory.getFreeSlot() >= 0 ? "Inventory space available" : "Inventory full (warning only; virtual drops may still work)",
            "Entry and low-flight setup are automatic; flight is checked at the destination");
    }

    public static boolean preparedStart(CropiumModule module) {
        if (instance == null) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null)
            return startBlocked(minecraft, "Join a world before starting " + module.name().getString());
        if (instance.merchantRestock.isActive()) return startBlocked(minecraft, "Merchant owns the inventory; finish or cancel it first");
        if (minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof CropiumScreen))
            return startBlocked(minecraft, "Close the open screen before starting " + module.name().getString());
        if (!minecraft.player.containerMenu.getCarried().isEmpty()
            || minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
            return startBlocked(minecraft, "Put the carried item back and close the container before starting");
        }
        int owner = module.id().equals("mine") ? 2 : module.id().equals("harvest") ? 1 : 0;
        if (owner != 0) {
            int slot = HotbarGuard.FIRST_SLOT;
            if (!usableTool(minecraft.player.getInventory().getItem(slot))) {
                return startBlocked(minecraft, "Hotbar slot 1 is empty or broken; put your farming/mining item there");
            }
            if (minecraft.player.getInventory().getFreeSlot() < 0) {
                minecraft.player.sendSystemMessage(Component.literal("[Cropium] Inventory is full; starting anyway. Physical drops may not fit."));
            }
            minecraft.player.getInventory().setSelectedSlot(slot);
        }
        // Resolve a queued mouse-menu request before closing the screen, otherwise
        // the very next tick can reopen Cropium and immediately pause the new run.
        instance.menuRequested = false;
        boolean wasPaused = owner == 2 ? instance.mine.isPaused() : owner == 1 && instance.controller.isPaused();
        if (wasPaused) {
            minecraft.gui.setScreen(null);
            if (owner == 2) instance.mine.pause(minecraft); else instance.controller.pause(minecraft);
            boolean resumed = owner == 2 ? instance.mine.isRunning() : instance.controller.isRunning();
            if (resumed) {
                instance.preparationStatus = "Resumed " + module.name().getString();
                instance.attackResumeGate.rearm();
                return true;
            }
            module.stop(minecraft); // A paused entry may need the normal preparation sequence again.
        }
        String preflight = owner == 2 ? instance.mine.preflightStatus(minecraft)
            : owner == 1 ? instance.controller.preflightStatus(minecraft) : "Ready";
        if (!preflight.equals("Ready")) return startBlocked(minecraft, preflight);
        for (CropiumModule other : java.util.List.of(instance.controller, instance.mine, instance.petOpener)) {
            if (other != module && other.isActive()) other.stop(minecraft);
        }
        boolean started = owner == 2 ? instance.mine.preparedStart(minecraft)
            : owner == 1 ? instance.controller.preparedStart(minecraft) : module.start(minecraft);
        instance.preparationStatus = started ? "Preparing " + module.name().getString() : "Start blocked: check preflight details";
        if (started) {
            instance.miningHealth.reset();
            minecraft.player.getInventory().setSelectedSlot(HotbarGuard.FIRST_SLOT);
        } else {
            return startBlocked(minecraft, module.name().getString() + " could not start; see its preceding safety message");
        }
        return started;
    }

    private static boolean startBlocked(Minecraft minecraft, String reason) {
        instance.preparationStatus = "Start blocked: " + reason;
        if (minecraft.player != null) minecraft.player.sendSystemMessage(Component.literal("[Cropium] " + instance.preparationStatus));
        return false;
    }

    public static String preparationStatus() { return instance == null ? "Not initialized" : instance.preparationStatus; }

    /** Only native block continuation gets this exception; never grab the OS cursor. */
    public static boolean allowUncapturedMiningAttack() {
        if (instance == null || instance.merchantRestock == null) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return false;
        int owner = instance.mine.isMining() ? 2 : instance.controller.isHarvesting() ? 1 : 0;
        boolean safeTarget = owner == 2 ? instance.mine.safeAttack(minecraft)
            : owner == 1 && instance.controller.safeAttack(minecraft);
        return AttackResumeGate.allowUncapturedAttack(owner != 0,
            minecraft.gui.screen() != null || minecraft.gui.overlay() != null,
            instance.merchantRestock.isActive() || instance.petOpener.isActive(),
            minecraft.player.containerMenu == minecraft.player.inventoryMenu
                && minecraft.player.inventoryMenu.getCarried().isEmpty(),
            minecraft.options.keyAttack.isDown(), safeTarget && instance.correctTool(minecraft, owner));
    }

    public static void onMouseGrabbed() {
        if (instance != null && instance.merchantRestock != null
            && (instance.controller.isRunning() || instance.mine.isRunning())) {
            // Vanilla grabMouse sets missTime=10000 even when no menu was open.
            instance.attackResumeGate.rearm();
        }
    }

    public static java.util.List<String> healthLines() {
        if (instance == null) return java.util.List.of("Not initialized");
        String mode = instance.merchantRestock.isActive() ? "Utility owns input" : instance.mine.isPaused() || instance.controller.isPaused()
            ? "Paused" : !instance.mine.isActive() && !instance.controller.isActive() ? "Idle" : "Active";
        return java.util.List.of(mode + " • " + instance.miningHealth.status(),
            "Attack at game-tick start: " + (instance.attackAtTickStart ? "HELD" : "RELEASED"),
            "Last live target: " + instance.targetSnapshot,
            instance.toolSnapshot ? "Last live tool: slot 1 equipped" : "Last live tool: slot 1 tool unavailable",
            instance.miningHealth.breakAge(System.nanoTime()),
            "Checked every 5s; GUI and utility phases suspend recovery");
    }

    public static void pauseActive() {
        if (instance == null || instance.merchantRestock.isActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (instance.mine.isPaused()) { preparedStart(instance.mine); return; }
        if (instance.controller.isPaused()) { preparedStart(instance.controller); return; }
        if (instance.mine.canPause()) instance.mine.pause(minecraft);
        else if (instance.controller.isActive()) instance.controller.pause(minecraft);
        else if (instance.petOpener.isActive()) instance.petOpener.stop(minecraft);
        if (instance.mine.isRunning() || instance.controller.isRunning()) minecraft.gui.setScreen(null);
    }

    public static void stopAll() {
        if (instance == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (instance.merchantRestock.isActive()) instance.merchantRestock.cancelSafely(minecraft);
        instance.controller.stop(minecraft);
        instance.mine.stop(minecraft);
        instance.petOpener.stop(minecraft);
        instance.menuRequested = false;
    }

    public static void recoverActive() {
        if (instance == null || instance.merchantRestock.isActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.containerMenu != minecraft.player.inventoryMenu
            || !minecraft.player.containerMenu.getCarried().isEmpty()) return;
        minecraft.gui.setScreen(null);
        if (instance.mine.isPaused() || instance.controller.isPaused()) pauseActive();
        int owner = instance.mine.isActive() ? 2 : instance.controller.isActive() ? 1 : 0;
        if (owner == 0 || !instance.restoreTool(minecraft, owner)) return;
        instance.attackResumeGate.rearm();
        if (owner == 2) instance.mine.recoverMiningInput(minecraft, true);
        else instance.controller.recoverMiningInput(minecraft, true);
    }

    public static HarvestController controller() {
        return activeController;
    }

    public static boolean captureFreeLook(double yawDelta, double pitchDelta) {
        FreeLookCamera camera = freeLookCamera();
        if (camera == null) return false;
        camera.capture(yawDelta, pitchDelta);
        return true;
    }

    public static FreeLookCamera freeLookCamera() {
        FreeLookCamera camera = activeMine == null ? null : activeMine.freeLookCamera();
        return camera != null ? camera : activeController == null ? null : activeController.freeLookCamera();
    }
}
