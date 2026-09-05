package com.salesfarm.croppilot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.function.Predicate;

public final class PetOpenerController implements CropiumModule {
    private static final double EGG_STAND_X = 465.508;
    private static final double EGG_STAND_Z = 1512.819;
    private static final double EGG_STAND_TOLERANCE = 0.75;
    private static final float EGG_YAW = 168.2F;
    private static final float EGG_PITCH = 5.5F;

    private final CropPilotConfig config;
    private final HarvestController harvester;
    private Phase phase = Phase.OFF;
    private long ticks;
    private long phaseSince;
    private long nextActionTick;
    private int pageTurns;
    private int eggSelectionAttempts;
    private boolean awaitingPageChange;
    private boolean awaitingEggSelection;
    private boolean awaitingAutoEgg;
    private boolean autoEggVerified;
    private int pendingAutoDeleteSlot = -1;
    private long eggMenuDeadlineTick;
    private String eggPageFingerprint = "";
    private int interactionTicks;
    private int forgeStage;
    private int forgeClicks;
    private int retries;
    private boolean storageFull;
    private boolean holdingForward;
    private boolean holdingUse;
    private String detail = "Ready";

    public PetOpenerController(CropPilotConfig config, HarvestController harvester) {
        this.config = config;
        this.harvester = harvester;
    }

    @Override
    public String id() {
        return "egg-hatcher";
    }

    @Override
    public Component name() {
        return Component.literal("Egg Hatcher");
    }

    @Override
    public Component description() {
        return Component.literal("Auto-hatch eggs, clear low rarities, and forge when full");
    }

    @Override
    public boolean isActive() {
        return phase != Phase.OFF;
    }

    @Override
    public String status() {
        if (!isActive()) {
            return "Ready • " + config.petEgg.label() + " egg • "
                + config.petMaxForgeTier.label() + " max tier";
        }
        return detail + " • " + config.petEgg.label() + " • open " + config.petOpenAmount;
    }

    @Override
    public boolean start(Minecraft minecraft) {
        if (isActive()) {
            return true;
        }
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return false;
        }
        if (harvester.isActive()) {
            harvester.stop(minecraft);
        }
        phase = Phase.APPROACHING_EGG;
        ticks = 0;
        phaseSince = 0;
        storageFull = false;
        forgeStage = 0;
        forgeClicks = 0;
        retries = 0;
        resetEggMenuSynchronization();
        closeScreen(minecraft);
        sendCommand(minecraft, "egg");
        nextActionTick = 15;
        detail = "Opening egg stand";
        message(minecraft, "Egg Hatcher started — " + config.petEgg.label() + " egg, "
            + config.petMaxForgeTier.label() + " max tier");
        return true;
    }

    @Override
    public void stop(Minecraft minecraft) {
        stop(minecraft, "Egg Hatcher stopped");
    }

    public boolean suspendForUtility(Minecraft minecraft) {
        if (!isActive()) {
            return false;
        }
        stopSilently(minecraft);
        return true;
    }

    public void tick(Minecraft minecraft) {
        if (!isActive()) {
            return;
        }
        ticks++;
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            stopSilently(minecraft);
            return;
        }

        if (storageFull && phase != Phase.OPENING_PETS && phase != Phase.OPENING_FORGE
            && phase != Phase.FORGING) {
            beginForge(minecraft);
            return;
        }

        if (minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            releaseInput(minecraft);
            handleContainer(minecraft, screen);
            return;
        }

        releaseUse(minecraft);
        switch (phase) {
            case APPROACHING_EGG -> approachEgg(minecraft);
            case INTERACTING_EGG -> interactWithEgg(minecraft);
            case EGG_SETUP -> {
                releaseForward(minecraft);
                if (phaseAge() > 80) {
                    retryEggStand(minecraft);
                }
            }
            case STARTING_HATCH -> {
                releaseForward(minecraft);
                if (phaseAge() > 100) {
                    retryEggStand(minecraft, "open-eggs confirmation did not arrive");
                }
            }
            case HATCHING -> releaseForward(minecraft);
            case OPENING_PETS -> retryCommand(minecraft, "pets", "Opening Pets");
            case OPENING_FORGE -> retryForgeMenu(minecraft);
            case FORGING, OFF -> releaseInput(minecraft);
        }
    }

    public void onChatMessage(Minecraft minecraft, Component message) {
        if (!isActive() || message == null) {
            return;
        }
        if (PetOpenerLogic.eggsOpened(message.getString(), config.petOpenAmount)) {
            retries = 0;
            setPhase(Phase.HATCHING, 0);
            detail = "Auto-hatching confirmed by server";
        } else if (PetOpenerLogic.storageFull(message.getString())) {
            storageFull = true;
            detail = "Pet storage full — opening forge";
        }
    }

    private void handleContainer(Minecraft minecraft, AbstractContainerScreen<?> screen) {
        String title = PetOpenerLogic.normalize(screen.getTitle().getString());
        if (title.startsWith("egg ") || title.equals("egg")) {
            handleEggMenu(minecraft, screen.getMenu(), title);
        } else if (PetOpenerLogic.containsWords(title, "forge machines")) {
            handleForgeMachines(minecraft, screen.getMenu());
        } else if (PetOpenerLogic.containsWords(title, "pet forging")) {
            handlePetForging(minecraft, screen.getMenu());
        } else if (phase == Phase.OPENING_PETS) {
            handlePetsMenu(minecraft, screen.getMenu());
        }
    }

    private void handleEggMenu(Minecraft minecraft, AbstractContainerMenu menu, String title) {
        if (phase == Phase.STARTING_HATCH) {
            if (phaseAge() > 100) {
                retryEggStand(minecraft, "open-eggs confirmation did not arrive");
            } else {
                detail = "Waiting for the server's opened x" + config.petOpenAmount + " message";
            }
            return;
        }
        if (phase == Phase.HATCHING) {
            detail = "Auto-hatching";
            return;
        }
        if (phase != Phase.EGG_SETUP) {
            setPhase(Phase.EGG_SETUP, 5);
            resetEggMenuSynchronization();
            detail = "Configuring " + config.petEgg.label() + " egg";
            return;
        }
        if (!ready()) {
            return;
        }

        if ((awaitingPageChange || awaitingEggSelection) && !menu.getCarried().isEmpty()) {
            retryEggStand(minecraft, "GUI desynced while changing egg pages");
            return;
        }
        if (!PetOpenerLogic.containsWords(title, config.petEgg.label())) {
            if (awaitingEggSelection) {
                if (ticks >= eggMenuDeadlineTick) {
                    retryEggStand(minecraft, "Egg selection was not acknowledged");
                } else {
                    delay(3);
                }
                return;
            }
            int targetPage = PetOpenerLogic.eggPage(config.petEgg.ordinal());
            if (awaitingPageChange) {
                String fingerprint = eggPageFingerprint(menu);
                if (!fingerprint.equals(eggPageFingerprint)) {
                    awaitingPageChange = false;
                    pageTurns++;
                    phaseSince = ticks;
                    detail = "Page " + (pageTurns + 1) + " loaded";
                    delay(8);
                } else if (ticks >= eggMenuDeadlineTick) {
                    retryEggStand(minecraft, "Egg page change was not acknowledged");
                } else {
                    delay(3);
                }
                return;
            }
            if (pageTurns < targetPage) {
                if (!validContainerSlot(menu, 8)) {
                    retryEggStand(minecraft);
                    return;
                }
                eggPageFingerprint = eggPageFingerprint(menu);
                click(minecraft, menu, 8, 0, ContainerInput.PICKUP);
                awaitingPageChange = true;
                eggMenuDeadlineTick = ticks + 60;
                detail = "Waiting for egg page " + (pageTurns + 2);
                delay(4);
                return;
            }
            int eggSlot = PetOpenerLogic.eggSlot(config.petEgg.ordinal());
            if (!validContainerSlot(menu, eggSlot) || menu.getSlot(eggSlot).getItem().isEmpty()) {
                retryEggStand(minecraft);
                return;
            }
            if (eggSelectionAttempts++ >= 2) {
                retryEggStand(minecraft);
                return;
            }
            click(minecraft, menu, eggSlot, 0, ContainerInput.PICKUP);
            awaitingEggSelection = true;
            eggMenuDeadlineTick = ticks + 60;
            detail = "Waiting for " + config.petEgg.label() + " egg selection";
            delay(4);
            return;
        }

        awaitingPageChange = false;
        awaitingEggSelection = false;

        if (pendingAutoDeleteSlot >= 0) {
            if (!menu.getCarried().isEmpty()) {
                retryEggStand(minecraft, "auto-delete click desynced");
            } else if (!validContainerSlot(menu, pendingAutoDeleteSlot)
                || !PetOpenerLogic.needsAutoDelete(slotText(minecraft, menu, pendingAutoDeleteSlot))) {
                pendingAutoDeleteSlot = -1;
                phaseSince = ticks;
                detail = "Low-rarity auto-delete confirmed";
                delay(6);
            } else if (ticks >= eggMenuDeadlineTick) {
                retryEggStand(minecraft, "auto-delete was not acknowledged");
            } else {
                detail = "Waiting for auto-delete confirmation";
                delay(4);
            }
            return;
        }

        int deleteSlot = findSlot(minecraft, menu, 9, Math.max(9, containerSlots(menu) - 9),
            PetOpenerLogic::needsAutoDelete);
        if (deleteSlot >= 0) {
            click(minecraft, menu, deleteSlot, 0, ContainerInput.PICKUP);
            pendingAutoDeleteSlot = deleteSlot;
            eggMenuDeadlineTick = ticks + 60;
            detail = "Enabling low-rarity auto-delete — waiting for server";
            delay(4);
            return;
        }

        int autoEggSlot = findSlot(minecraft, menu, 0, containerSlots(menu),
            text -> PetOpenerLogic.containsWords(text, "auto egg"));
        if (autoEggSlot < 0) {
            if (awaitingAutoEgg && !menu.getCarried().isEmpty()) {
                retryEggStand(minecraft, "Auto Egg click desynced");
            } else if (phaseAge() > 240 || (awaitingAutoEgg && ticks >= eggMenuDeadlineTick)) {
                retryEggStand(minecraft, "Auto Egg option did not synchronize");
            } else {
                delay(5);
            }
            return;
        }
        if (awaitingAutoEgg && !menu.getCarried().isEmpty()) {
            retryEggStand(minecraft, "Auto Egg click desynced");
            return;
        }
        String autoEgg = slotText(minecraft, menu, autoEggSlot);
        if (PetOpenerLogic.containsWords(autoEgg, "status off")) {
            if (awaitingAutoEgg) {
                if (!menu.getCarried().isEmpty() || ticks >= eggMenuDeadlineTick) {
                    retryEggStand(minecraft, "Auto Egg toggle was not acknowledged");
                } else {
                    detail = "Waiting for Auto Egg confirmation";
                    delay(4);
                }
            } else {
                click(minecraft, menu, autoEggSlot, 0, ContainerInput.PICKUP);
                awaitingAutoEgg = true;
                autoEggVerified = false;
                eggMenuDeadlineTick = ticks + 60;
                detail = "Enabling Auto Egg — waiting for server";
                delay(4);
            }
            return;
        }
        if (!PetOpenerLogic.containsWords(autoEgg, "status on")) {
            if (phaseAge() > 240) {
                retryEggStand(minecraft, "Auto Egg status was unreadable");
            } else {
                delay(5);
            }
            return;
        }
        awaitingAutoEgg = false;
        if (!autoEggVerified) {
            autoEggVerified = true;
            phaseSince = ticks;
            detail = "Auto Egg verified ON";
            delay(8);
            return;
        }

        String amount = "x" + config.petOpenAmount;
        int openSlot = findSlot(minecraft, menu, Math.max(0, containerSlots(menu) - 9),
            containerSlots(menu), text -> PetOpenerLogic.containsWords(text, "open")
                && PetOpenerLogic.containsWords(text, amount));
        if (openSlot < 0) {
            openSlot = config.petOpenAmount == 3 ? 49 : 50;
        }
        if (!validContainerSlot(menu, openSlot)) {
            stop(minecraft, "Egg Hatcher stopped: the open-eggs button was not found");
            return;
        }
        click(minecraft, menu, openSlot, 1, ContainerInput.PICKUP);
        setPhase(Phase.STARTING_HATCH, 0);
        detail = "Waiting for opened x" + config.petOpenAmount + " confirmation";
    }

    private void handlePetsMenu(Minecraft minecraft, AbstractContainerMenu menu) {
        if (!ready()) {
            return;
        }
        int slot = -1;
        for (int index = 0; index < containerSlots(menu); index++) {
            if (menu.getSlot(index).getItem().getItem() == Items.BLAST_FURNACE) {
                slot = index;
                break;
            }
        }
        if (slot < 0 && validContainerSlot(menu, 46) && !menu.getSlot(46).getItem().isEmpty()) {
            slot = 46;
        }
        if (slot < 0) {
            if (phaseAge() > 160) {
                stop(minecraft, "Egg Hatcher stopped: Forge Pets was not found");
            }
            delay(5);
            return;
        }
        click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
        setPhase(Phase.OPENING_FORGE, 6);
        detail = "Opening forge machines";
    }

    private void handleForgeMachines(Minecraft minecraft, AbstractContainerMenu menu) {
        if (forgeStage > config.petMaxForgeTier.ordinal()) {
            resumeHatching(minecraft);
            return;
        }
        if (!ready()) {
            return;
        }
        CropPilotConfig.ForgeTier tier = CropPilotConfig.ForgeTier.values()[forgeStage];
        int slot = findSlot(minecraft, menu, 0, containerSlots(menu),
            text -> PetOpenerLogic.containsWords(text, tier.label()));
        if (slot < 0) {
            int fallback = 10 + forgeStage;
            slot = validContainerSlot(menu, fallback) && !menu.getSlot(fallback).getItem().isEmpty() ? fallback : -1;
        }
        if (slot < 0) {
            stop(minecraft, "Egg Hatcher stopped: " + tier.label() + " forge was not found");
            return;
        }
        forgeClicks = 0;
        click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
        setPhase(Phase.FORGING, 6);
        detail = "Forging through " + tier.label();
    }

    private void handlePetForging(Minecraft minecraft, AbstractContainerMenu menu) {
        if (phase != Phase.FORGING || !ready()) {
            return;
        }
        int petSlot = 10;
        if (!validContainerSlot(menu, petSlot) || menu.getSlot(petSlot).getItem().isEmpty()) {
            if (forgeClicks == 0) {
                resumeHatching(minecraft);
            } else {
                advanceForge(minecraft);
            }
            return;
        }
        click(minecraft, menu, petSlot, 1, ContainerInput.QUICK_MOVE);
        forgeClicks++;
        if (forgeClicks >= 4) {
            advanceForge(minecraft);
        } else {
            delay(4);
        }
    }

    private void advanceForge(Minecraft minecraft) {
        forgeStage++;
        if (forgeStage > config.petMaxForgeTier.ordinal()) {
            resumeHatching(minecraft);
            return;
        }
        closeScreen(minecraft);
        sendCommand(minecraft, "pets");
        retries = 0;
        setPhase(Phase.OPENING_PETS, 10);
        detail = "Opening next forge tier";
    }

    private void beginForge(Minecraft minecraft) {
        storageFull = false;
        forgeStage = 0;
        forgeClicks = 0;
        retries = 0;
        closeScreen(minecraft);
        sendCommand(minecraft, "pets");
        setPhase(Phase.OPENING_PETS, 10);
        detail = "Storage full — opening Pets";
    }

    private void resumeHatching(Minecraft minecraft) {
        closeScreen(minecraft);
        sendCommand(minecraft, "egg");
        retries = 0;
        interactionTicks = 0;
        resetEggMenuSynchronization();
        setPhase(Phase.APPROACHING_EGG, 15);
        detail = "Returning to " + config.petEgg.label() + " egg";
    }

    private void approachEgg(Minecraft minecraft) {
        if (!ready()) {
            return;
        }
        double dx = EGG_STAND_X - minecraft.player.getX();
        double dz = EGG_STAND_Z - minecraft.player.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance > 32.0) {
            releaseForward(minecraft);
            if (phaseAge() > 120) {
                stop(minecraft, "Egg Hatcher stopped: /egg did not reach the configured egg stand");
            }
            return;
        }
        if (distance > EGG_STAND_TOLERANCE) {
            float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
            turnToward(minecraft, yaw, minecraft.player.getXRot(), 10.0F);
            holdForward(minecraft);
            detail = "Walking to egg stand • " + String.format(java.util.Locale.ROOT, "%.1fm", distance);
            return;
        }
        releaseForward(minecraft);
        interactionTicks = 0;
        setPhase(Phase.INTERACTING_EGG, 0);
        detail = "Opening egg menu";
    }

    private void interactWithEgg(Minecraft minecraft) {
        float yawError = Math.abs(Mth.wrapDegrees(EGG_YAW - minecraft.player.getYRot()));
        float pitchError = Math.abs(EGG_PITCH - minecraft.player.getXRot());
        if (yawError > 1.0F || pitchError > 0.8F) {
            turnToward(minecraft, EGG_YAW, EGG_PITCH, 9.0F);
            return;
        }
        if (interactionTicks == 0) {
            holdForward(minecraft);
            holdUse(minecraft);
        } else {
            releaseUse(minecraft);
        }
        interactionTicks++;
        if (interactionTicks >= 4) {
            releaseInput(minecraft);
            setPhase(Phase.EGG_SETUP, 0);
        }
    }

    private void retryEggStand(Minecraft minecraft) {
        retryEggStand(minecraft, "egg menu did not open");
    }

    private void retryEggStand(Minecraft minecraft, String reason) {
        if (++retries > 6) {
            stop(minecraft, "Egg Hatcher stopped: " + reason + " after 6 retries");
            return;
        }
        closeScreen(minecraft);
        sendCommand(minecraft, "egg");
        interactionTicks = 0;
        resetEggMenuSynchronization();
        setPhase(Phase.APPROACHING_EGG, 15);
        detail = "Refreshing /egg after " + reason;
    }

    private void resetEggMenuSynchronization() {
        pageTurns = 0;
        eggSelectionAttempts = 0;
        awaitingPageChange = false;
        awaitingEggSelection = false;
        awaitingAutoEgg = false;
        autoEggVerified = false;
        pendingAutoDeleteSlot = -1;
        eggMenuDeadlineTick = 0;
        eggPageFingerprint = "";
    }

    private static String eggPageFingerprint(AbstractContainerMenu menu) {
        StringBuilder fingerprint = new StringBuilder();
        for (int slot = 1; slot <= 8 && slot < containerSlots(menu); slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            fingerprint.append(ItemStack.hashItemAndComponents(stack)).append(':')
                .append(stack.getCount()).append(';');
        }
        return fingerprint.toString();
    }

    private void retryCommand(Minecraft minecraft, String command, String status) {
        if (phaseAge() <= 100) {
            return;
        }
        if (++retries > 3) {
            stop(minecraft, "Egg Hatcher stopped: " + status + " timed out");
            return;
        }
        sendCommand(minecraft, command);
        setPhase(phase, 10);
    }

    private void retryForgeMenu(Minecraft minecraft) {
        if (phaseAge() <= 100) {
            return;
        }
        closeScreen(minecraft);
        sendCommand(minecraft, "pets");
        setPhase(Phase.OPENING_PETS, 10);
        detail = "Retrying forge menu";
    }

    private int findSlot(Minecraft minecraft, AbstractContainerMenu menu, int start, int end,
                         Predicate<String> predicate) {
        for (int index = Math.max(0, start); index < Math.min(end, menu.slots.size()); index++) {
            if (!menu.getSlot(index).getItem().isEmpty()
                && predicate.test(slotText(minecraft, menu, index))) {
                return index;
            }
        }
        return -1;
    }

    private String slotText(Minecraft minecraft, AbstractContainerMenu menu, int slot) {
        if (!validSlot(menu, slot)) {
            return "";
        }
        ItemStack stack = menu.getSlot(slot).getItem();
        if (stack.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(stack.getHoverName().getString());
        try {
            List<Component> lines = stack.getTooltipLines(Item.TooltipContext.of(minecraft.level),
                minecraft.player, TooltipFlag.NORMAL);
            for (Component line : lines) {
                text.append('\n').append(line.getString());
            }
        } catch (RuntimeException ignored) {
            // The item name still gives semantic matching a useful fallback.
        }
        return text.toString();
    }

    private void click(Minecraft minecraft, AbstractContainerMenu menu, int slot, int button,
                       ContainerInput input) {
        if (minecraft.gameMode != null && minecraft.player != null && validContainerSlot(menu, slot)) {
            minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, input, minecraft.player);
        }
    }

    private static int containerSlots(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - 36);
    }

    private static boolean validSlot(AbstractContainerMenu menu, int slot) {
        return slot >= 0 && slot < menu.slots.size();
    }

    private static boolean validContainerSlot(AbstractContainerMenu menu, int slot) {
        return slot >= 0 && slot < containerSlots(menu);
    }

    private void turnToward(Minecraft minecraft, float yaw, float pitch, float maximumYawStep) {
        float yawStep = Math.clamp(Mth.wrapDegrees(yaw - minecraft.player.getYRot()),
            -maximumYawStep, maximumYawStep);
        float pitchStep = Math.clamp(pitch - minecraft.player.getXRot(), -5.0F, 5.0F);
        minecraft.player.setYRot(minecraft.player.getYRot() + yawStep);
        minecraft.player.setXRot(minecraft.player.getXRot() + pitchStep);
    }

    private void holdForward(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(true);
        holdingForward = true;
    }

    private void holdUse(Minecraft minecraft) {
        minecraft.options.keyUse.setDown(true);
        holdingUse = true;
    }

    private void releaseForward(Minecraft minecraft) {
        if (holdingForward) {
            minecraft.options.keyUp.setDown(false);
            holdingForward = false;
        }
    }

    private void releaseUse(Minecraft minecraft) {
        if (holdingUse) {
            minecraft.options.keyUse.setDown(false);
            holdingUse = false;
        }
    }

    private void releaseInput(Minecraft minecraft) {
        releaseForward(minecraft);
        releaseUse(minecraft);
    }

    private void closeScreen(Minecraft minecraft) {
        releaseInput(minecraft);
        if (minecraft.gui.screen() instanceof AbstractContainerScreen<?> && minecraft.player != null) {
            minecraft.player.closeContainer();
        } else if (minecraft.gui.screen() != null) {
            minecraft.gui.setScreen(null);
        }
    }

    private static void sendCommand(Minecraft minecraft, String command) {
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand(command);
        }
    }

    private void stop(Minecraft minecraft, String reason) {
        stopSilently(minecraft);
        message(minecraft, reason);
    }

    private void stopSilently(Minecraft minecraft) {
        releaseInput(minecraft);
        phase = Phase.OFF;
        storageFull = false;
        detail = "Ready";
    }

    private static void message(Minecraft minecraft, String text) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("[Cropium] " + text));
        }
    }

    private void setPhase(Phase next, int delay) {
        phase = next;
        phaseSince = ticks;
        nextActionTick = ticks + delay;
    }

    private void delay(int ticks) {
        nextActionTick = this.ticks + ticks;
    }

    private boolean ready() {
        return ticks >= nextActionTick;
    }

    private long phaseAge() {
        return ticks - phaseSince;
    }

    private enum Phase {
        OFF,
        APPROACHING_EGG,
        INTERACTING_EGG,
        EGG_SETUP,
        STARTING_HATCH,
        HATCHING,
        OPENING_PETS,
        OPENING_FORGE,
        FORGING
    }
}
