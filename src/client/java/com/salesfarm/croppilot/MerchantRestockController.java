package com.salesfarm.croppilot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class MerchantRestockController {
    private static final int BUY_ALL_SLOT = 4;
    private static final int MINION_FORGE_SLOT = 46;
    private static final int SALVAGE_MENU_SLOT = 15;
    private static final int SALVAGE_CONFIRM_SLOT = 50;
    private static final long TRIGGER_COOLDOWN_NANOS = 15_000_000_000L;
    private static final int FARM_RETURN_TIMEOUT_TICKS = 120;
    private static final int FARM_WALK_TIMEOUT_TICKS = 200;
    private static final int FLIGHT_CONFIRM_TIMEOUT_TICKS = 30;
    private static final int HOVER_TIMEOUT_TICKS = 100;
    private static final int MAX_FARM_RETURN_ATTEMPTS = 2;
    private static final int MAX_FLIGHT_ATTEMPTS = 3;
    private static final double FARM_RETURN_TELEPORT_DISTANCE = 2.0;
    private static final double FARM_RETURN_WALK_DISTANCE = 5.0;
    private static final double FARM_HOVER_HEIGHT = 0.45;
    private static final int RARE_PURCHASE_TIMEOUT_TICKS = 100;
    private static final List<String> WORKFLOW_STEPS = List.of("Open Merchant", "Inspect and buy rares",
        "Buy normal NPCs", "Open Salvage", "Salvage normal NPCs", "Open NPC plot",
        "Equip and place NPCs", "Return to interrupted macro", "Complete");

    private final CropPilotConfig config;
    private final HarvestController harvester;
    private final MineController mine;
    private final PetOpenerController eggHatcher;
    private final PlotScannerController plotScanner;
    private Phase phase = Phase.OFF;
    private ResumeTarget resumeTarget = ResumeTarget.NONE;
    private List<ItemStack> inventoryBefore = List.of();
    private List<NewStack> purchased = List.of();
    private int workflowIndex = -1;
    private int rareSlot;
    private int rarePurchased;
    private String rareResult = "Not inspected";
    private final List<String> rareDiagnostics = new ArrayList<>();
    private final Set<String> attemptedRareOffers = new HashSet<>();
    private final List<ItemStack> confirmedRares = new ArrayList<>();
    private List<ItemStack> beforeRarePurchase = List.of();
    private ItemStack pendingRareOffer = ItemStack.EMPTY;
    private String pendingRareLore = "";
    private ItemStack observedRare = ItemStack.EMPTY;
    private long rareStableSince;
    private boolean automaticTriggersBlocked;
    private long ticks;
    private long phaseSince;
    private long nextActionTick;
    private AbstractContainerMenu pendingMenuClick;
    private ItemStack pendingMenuIcon = ItemStack.EMPTY;
    private long menuClickedAt;
    private long lastTriggerNanos;
    private int retries;
    private int attemptedStacks;
    private final Set<Integer> attemptedInventorySlots = new HashSet<>();
    private final Set<BlockPos> reservedPlacementSlots = new HashSet<>();
    private NewStack activePlacement;
    private BlockPos placementTarget;
    private int placementCountBefore;
    private int placementAttempts;
    private int hotbarMoveAttempts;
    private int pendingHotbarSlot = -1;
    private int originalSelectedSlot;
    private int sourceInventorySlot = -1;
    private InventoryScreen placementInventoryScreen;
    private HotbarTransfer<ItemStack> hotbarTransfer;
    private int placedCount;
    private int stalledTicks;
    private double previousTargetDistance = Double.POSITIVE_INFINITY;
    private String status = "Idle";
    private boolean teleportedForPlacement;
    private Boolean previousPauseOnLostFocus;
    private String pendingResult;
    private Vec3 farmReturnOrigin;
    private double farmWalkStartX;
    private double farmWalkStartZ;
    private double farmGroundY;
    private float farmReturnYaw;
    private int farmReturnAttempts;
    private int flightAttempts;
    private int hoverStableTicks;

    public MerchantRestockController(CropPilotConfig config, HarvestController harvester,
                                     MineController mine, PetOpenerController eggHatcher,
                                     PlotScannerController plotScanner) {
        this.config = config;
        this.harvester = harvester;
        this.mine = mine;
        this.eggHatcher = eggHatcher;
        this.plotScanner = plotScanner;
    }

    public boolean isActive() {
        return phase != Phase.OFF;
    }

    public String status() {
        return status;
    }

    public List<String> workflowSteps() {
        return WORKFLOW_STEPS;
    }

    /** Zero based; -1 before the first run. Stops retain the last reached step. */
    public int workflowIndex() {
        return workflowIndex;
    }

    public List<String> queuePreview() {
        return purchased.stream().filter(stack -> stack.remaining > 0)
            .map(stack -> stack.remaining + " × " + stack.sample.getHoverName().getString()
                + (stack.rare ? " [rare]" : "") + " — "
                + (!isActive() ? "kept / inspect inventory" : stack.placeAtPlot ? "place at plot"
                    : stack.rare ? "keep protected" : "salvage if safely separable"))
            .toList();
    }

    public String rareStatus() {
        String result = rareResult + " • " + rarePurchased + " confirmed • free NPCs";
        return rareDiagnostics.isEmpty() ? result : result + " • " + String.join("; ", rareDiagnostics);
    }

    public void cancelSafely(Minecraft minecraft) {
        if (!isActive()) return;
        stopForInventory(minecraft, "Merchant cancelled; check any pending purchase or Salvage contents");
    }

    public int queuedPlacements() {
        return purchased.stream().filter(stack -> stack.placeAtPlot)
            .mapToInt(stack -> stack.remaining).sum();
    }

    public boolean startManual(Minecraft minecraft) {
        if (isActive() || minecraft.player == null || minecraft.level == null
            || minecraft.getConnection() == null || !cursorEmpty(minecraft)) {
            return false;
        }
        automaticTriggersBlocked = false;
        ResumeTarget target = ResumeTarget.NONE;
        if (harvester.isRunning() && harvester.suspendForUtility(minecraft)) {
            target = ResumeTarget.HARVESTER;
        } else if (mine.isRunning() && mine.suspendForUtility(minecraft)) {
            target = ResumeTarget.MINE;
        } else if (eggHatcher.isActive() && eggHatcher.suspendForUtility(minecraft)) {
            target = ResumeTarget.EGG_HATCHER;
        }
        begin(minecraft, target, "Buy and Salvage NPCs started for testing");
        return true;
    }

    public void onChatMessage(Minecraft minecraft, Component message) {
        if (message == null || isActive() || automaticTriggersBlocked
            || !MerchantRestockLogic.isRestockMessage(message.getString())) {
            return;
        }
        long now = System.nanoTime();
        if ((lastTriggerNanos != 0L && now - lastTriggerNanos < TRIGGER_COOLDOWN_NANOS) || minecraft.player == null
            || minecraft.level == null || minecraft.getConnection() == null || !cursorEmpty(minecraft)) {
            return;
        }

        ResumeTarget target;
        if (config.merchantRestockHarvester && harvester.isRunning()
            && harvester.suspendForUtility(minecraft)) {
            target = ResumeTarget.HARVESTER;
        } else if (config.merchantRestockMine && mine.isRunning()
            && mine.suspendForUtility(minecraft)) {
            target = ResumeTarget.MINE;
        } else if (config.merchantRestockEggHatcher && eggHatcher.isActive()
            && eggHatcher.suspendForUtility(minecraft)) {
            target = ResumeTarget.EGG_HATCHER;
        } else {
            return;
        }

        lastTriggerNanos = now;
        begin(minecraft, target, "Merchant restocked — paused " + target.label + " to buy and salvage");
    }

    private void begin(Minecraft minecraft, ResumeTarget target, String startMessage) {
        clearMenuClick();
        resumeTarget = target;
        inventoryBefore = snapshotInventory(minecraft);
        originalSelectedSlot = HotbarGuard.FIRST_SLOT; // Reserve slot 1 for the tool; return here after placement.
        purchased = new ArrayList<>();
        rareSlot = 0;
        rarePurchased = 0;
        rareResult = "Waiting to inspect top row";
        rareDiagnostics.clear();
        attemptedRareOffers.clear();
        // Retain rare identities across workflows so later merged purchases also stay protected.
        beforeRarePurchase = List.of();
        pendingRareOffer = ItemStack.EMPTY;
        observedRare = ItemStack.EMPTY;
        ticks = 0;
        retries = 0;
        attemptedStacks = 0;
        placedCount = 0;
        teleportedForPlacement = false;
        activePlacement = null;
        placementTarget = null;
        placementAttempts = 0;
        hotbarMoveAttempts = 0;
        pendingHotbarSlot = -1;
        placementInventoryScreen = null;
        hotbarTransfer = null;
        stalledTicks = 0;
        previousTargetDistance = Double.POSITIVE_INFINITY;
        pendingResult = null;
        farmReturnOrigin = null;
        farmReturnAttempts = 0;
        flightAttempts = 0;
        hoverStableTicks = 0;
        attemptedInventorySlots.clear();
        reservedPlacementSlots.clear();
        closeScreen(minecraft);
        keepRunningWhenUnfocused(minecraft);
        sendCommand(minecraft, "merchant");
        setPhase(Phase.OPENING_MERCHANT, 12);
        status = "Opening Merchant";
        message(minecraft, startMessage);
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
        if (resumeTarget == ResumeTarget.HARVESTER && !harvester.isPaused()) {
            stopForInventory(minecraft, "Merchant routine cancelled because the harvester stopped");
            return;
        }
        if (resumeTarget == ResumeTarget.MINE && !mine.isPaused()) {
            stopForInventory(minecraft, "Merchant routine cancelled because Mine Harvester stopped");
            return;
        }
        keepRunningWhenUnfocused(minecraft);

        if (phase != Phase.WAITING_FOR_HOTBAR && !menuClickSettled(minecraft)) return;
        if (phase == Phase.WAITING_FOR_RARE_PURCHASE) {
            verifyRarePurchase(minecraft);
            return;
        }
        if (phase == Phase.WAITING_FOR_CONFIRM && ready()) {
            continueAfterSalvage(minecraft);
            return;
        }
        if (phase.isPlacementPhase()) {
            handlePlacement(minecraft);
            return;
        }
        if (minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            handleContainer(minecraft, screen);
            return;
        }
        if (phase == Phase.OPENING_MERCHANT && phaseAge() > 100) {
            if (++retries > 2) {
                abort(minecraft, "Merchant menu did not open");
            } else {
                sendCommand(minecraft, "merchant");
                setPhase(Phase.OPENING_MERCHANT, 12);
            }
        } else if (phaseAge() > 140) {
            abort(minecraft, "Merchant workflow timed out at " + phase.label);
        }
    }

    private void handleContainer(Minecraft minecraft, AbstractContainerScreen<?> screen) {
        String title = PetOpenerLogic.normalize(screen.getTitle().getString());
        boolean expected = switch (phase) {
            case OPENING_MERCHANT, SCANNING_RARES, BUYING_NORMAL, WAITING_FOR_PURCHASE ->
                PetOpenerLogic.containsWords(title, "npc merchant");
            case OPENING_FORGE -> PetOpenerLogic.containsWords(title, "minion forge");
            case OPENING_SALVAGE, SALVAGING, CONFIRMING -> PetOpenerLogic.containsWords(title, "merchant salvage");
            default -> false;
        };
        if (!expected) {
            failAfter(minecraft, "Unexpected menu while " + phase.label + ": " + screen.getTitle().getString(), 100);
            return;
        }
        if (PetOpenerLogic.containsWords(title, "npc merchant")) {
            handleMerchant(minecraft, screen.getMenu());
        } else if (PetOpenerLogic.containsWords(title, "minion forge")) {
            handleForge(minecraft, screen.getMenu());
        } else if (PetOpenerLogic.containsWords(title, "merchant salvage")) {
            handleSalvage(minecraft, screen.getMenu());
        } else if (phaseAge() > 100) {
            abort(minecraft, "Unexpected menu while " + phase.label + ": " + screen.getTitle().getString());
        }
    }

    private void handleMerchant(Minecraft minecraft, AbstractContainerMenu menu) {
        if (!ready()) {
            return;
        }
        if (phase == Phase.OPENING_MERCHANT) {
            status = "Inspecting Merchant top row for NPC offers";
            setPhase(Phase.SCANNING_RARES, 5);
            return;
        }
        if (phase == Phase.SCANNING_RARES) {
            inspectNextRare(minecraft, menu);
            return;
        }
        if (phase == Phase.BUYING_NORMAL) {
            int slot = preferredSlot(minecraft, menu, BUY_ALL_SLOT, Items.NETHER_STAR,
                text -> PetOpenerLogic.containsWords(text, "buy all"));
            if (slot < 0) {
                failAfter(minecraft, "Could not find Buy All in the merchant menu", 80);
                return;
            }
            click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
            status = "Buy All sent once • waiting for normal NPC inventory";
            setPhase(Phase.WAITING_FOR_PURCHASE, 15);
            return;
        }
        if (phase == Phase.WAITING_FOR_PURCHASE) {
            int slot = preferredSlot(minecraft, menu, MINION_FORGE_SLOT, Items.CAULDRON,
                text -> PetOpenerLogic.containsWords(text, "minion forge"));
            if (slot < 0) {
                failAfter(minecraft, "Could not find Minion Forge after buying", 100);
                return;
            }
            click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
            status = "Opening Minion Forge";
            setPhase(Phase.OPENING_FORGE, 6);
        }
    }

    private void inspectNextRare(Minecraft minecraft, AbstractContainerMenu menu) {
        if (!config.merchantBuyRares || rareSlot >= Math.min(9, containerSlots(menu))) {
            rareResult = !config.merchantBuyRares ? "Rare buying disabled" : "Top row inspected";
            status = "Rare inspection complete • buying normal NPCs";
            setPhase(Phase.BUYING_NORMAL, 10);
            return;
        }
        int slot = rareSlot++;
        ItemStack offer = menu.getSlot(slot).getItem();
        delay(5);
        if (offer.isEmpty() || !MerchantRestockLogic.inspectableRareSlot(slot,
            BuiltInRegistries.ITEM.getKey(offer.getItem()).getPath())) return;
        String lore = stackText(minecraft, offer);
        String problem = MerchantRestockLogic.rareOfferProblem(lore);
        if (offer.getCount() != 1) problem = "unsupported offer quantity";
        String identity = MerchantRestockLogic.cleanText(offer.getHoverName().getString()) + "|"
            + MerchantRestockLogic.generatedAmount(lore);
        if (problem == null && attemptedRareOffers.contains(identity)) problem = "already attempted this NPC offer";
        if (problem != null) {
            rareDiagnostics.add("Slot " + (slot + 1) + ": " + problem);
            rareResult = "Inspecting top row";
            status = "Skipping rare slot " + (slot + 1) + " • " + problem;
            return;
        }
        boolean room = false;
        for (int i = 0; i < 36; i++) {
            if (minecraft.player.getInventory().getItem(i).isEmpty()) {
                room = true;
                break;
            }
        }
        if (!room || minecraft.gameMode == null || minecraft.player.containerMenu != menu) {
            rareDiagnostics.add("Slot " + (slot + 1) + ": inventory space or active menu unavailable");
            return;
        }
        // One attempt per free offer per workflow, even if the server response later times out.
        attemptedRareOffers.add(identity);
        beforeRarePurchase = snapshotInventory(minecraft);
        pendingRareOffer = offer.copy();
        pendingRareLore = lore;
        observedRare = ItemStack.EMPTY;
        click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
        rareResult = "Verifying " + offer.getHoverName().getString();
        status = "Rare purchase sent once • verifying matching NPC inventory increase";
        setPhase(Phase.WAITING_FOR_RARE_PURCHASE, 15);
    }

    private void verifyRarePurchase(Minecraft minecraft) {
        if (!ready()) return;
        List<NewStack> additions = purchasedStacks(beforeRarePurchase, snapshotInventory(minecraft));
        NewStack addition = additions.size() == 1 ? additions.getFirst() : null;
        boolean matches = addition != null && addition.sample.getItem() == pendingRareOffer.getItem()
            && MerchantRestockLogic.sameNpcOffer(pendingRareOffer.getHoverName().getString(), pendingRareLore,
                addition.sample.getHoverName().getString(), stackText(minecraft, addition.sample));
        if (matches && addition.remaining == 1) {
            if (!ItemStack.isSameItemSameComponents(observedRare, addition.sample)) {
                observedRare = addition.sample.copy();
                rareStableSince = ticks;
            }
            if (MerchantRestockLogic.confirmedSinglePurchase(addition.remaining, matches, ticks - rareStableSince)) {
                if (!isProtectedRare(addition.sample)) confirmedRares.add(addition.sample.copy());
                addition.rare = true;
                addition.placeAtPlot = MerchantRestockLogic.shouldPlace(true, config.merchantAlwaysPlaceRares,
                    config.merchantAutoPlace, stackText(minecraft, addition.sample), config.merchantNpcThreshold);
                NewStack existing = matchingStack(addition.sample, purchased);
                if (existing == null) purchased.add(addition);
                else {
                    existing.remaining++;
                    existing.rare = true;
                    existing.placeAtPlot = addition.placeAtPlot;
                }
                rarePurchased++;
                rareResult = "Confirmed " + addition.sample.getHoverName().getString();
                pendingRareOffer = ItemStack.EMPTY;
                beforeRarePurchase = List.of();
                status = "Rare inventory increase confirmed • inspecting remaining top-row offers";
                setPhase(Phase.SCANNING_RARES, 15);
                return;
            }
        } else observedRare = ItemStack.EMPTY;
        if (phaseAge() >= RARE_PURCHASE_TIMEOUT_TICKS) {
            rareResult = "Purchase unverified; no retry or Salvage";
            stopForInventory(minecraft, "Rare purchase did not produce one identifiable, stable NPC; inspect inventory");
        }
    }

    private void handleForge(Minecraft minecraft, AbstractContainerMenu menu) {
        if (phase != Phase.OPENING_FORGE || !ready()) {
            return;
        }
        int slot = preferredSlot(minecraft, menu, SALVAGE_MENU_SLOT, null,
            text -> PetOpenerLogic.containsWords(text, "salvage"));
        if (slot < 0) {
            failAfter(minecraft, "Could not find Salvage in Minion Forge", 100);
            return;
        }
        click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
        status = "Opening Merchant Salvage";
        setPhase(Phase.OPENING_SALVAGE, 8);
    }

    private void handleSalvage(Minecraft minecraft, AbstractContainerMenu menu) {
        if (!ready()) {
            return;
        }
        if (phase == Phase.OPENING_SALVAGE) {
            purchased = purchasedStacks(inventoryBefore, snapshotInventory(minecraft));
            for (NewStack stack : purchased) {
                stack.rare = isProtectedRare(stack.sample);
            }
            if (purchased.isEmpty()) {
                if (phaseAge() <= 35) {
                    delay(5);
                } else {
                    finish(minecraft, "Buy All added no new inventory stacks");
                }
                return;
            }
            classifyPurchases(minecraft);
            status = "Classified purchases • " + queuedPlacements() + " queued for placement";
            setPhase(Phase.SALVAGING, 3);
            return;
        }
        if (phase == Phase.SALVAGING) {
            Transfer transfer = nextTransfer(menu);
            if (transfer != null) {
                transfer.stack.remaining -= transfer.count;
                attemptedStacks++;
                attemptedInventorySlots.add(transfer.inventorySlot);
                click(minecraft, menu, transfer.menuSlot, 0, ContainerInput.QUICK_MOVE);
                status = "Moving normal NPC stack " + attemptedStacks + " to Salvage";
                delay(4);
            } else if (attemptedStacks == 0) {
                if (queuedPlacements() > 0) {
                    beginPlacement(minecraft);
                } else {
                    finish(minecraft, "Purchases kept; no safely separable normal NPC stacks to salvage");
                }
            } else {
                setPhase(Phase.CONFIRMING, 10);
            }
            return;
        }
        if (phase == Phase.CONFIRMING) {
            for (int menuSlot = 0; menuSlot < containerSlots(menu); menuSlot++) {
                if (isProtectedRare(menu.getSlot(menuSlot).getItem())) {
                    stopForInventory(minecraft, "A protected rare is in Salvage; retrieve it before continuing");
                    return;
                }
            }
            int slot = preferredSlot(minecraft, menu, SALVAGE_CONFIRM_SLOT, Items.BLAST_FURNACE,
                text -> PetOpenerLogic.containsWords(text, "salvage"));
            if (slot < 0) {
                failAfter(minecraft, "Could not find the Salvage confirmation", 100);
                return;
            }
            click(minecraft, menu, slot, 0, ContainerInput.PICKUP);
            status = "Salvage confirmation sent • waiting for completion";
            setPhase(Phase.WAITING_FOR_CONFIRM, 18);
        }
    }

    private boolean isProtectedRare(ItemStack sample) {
        if (sample.isEmpty()) return false;
        for (ItemStack rare : confirmedRares) {
            // GUI lore may change; protecting a matching item/name is safer than salvaging it.
            if (rare.getItem() == sample.getItem() && MerchantRestockLogic.cleanText(rare.getHoverName().getString())
                .equalsIgnoreCase(MerchantRestockLogic.cleanText(sample.getHoverName().getString()))) return true;
        }
        return false;
    }

    private Transfer nextTransfer(AbstractContainerMenu menu) {
        int firstPlayerSlot = containerSlots(menu);
        for (int menuSlot = firstPlayerSlot; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.getSlot(menuSlot);
            ItemStack current = slot.getItem();
            int inventorySlot = slot.getContainerSlot();
            if (current.isEmpty() || inventorySlot < 0 || inventorySlot >= inventoryBefore.size()
                || attemptedInventorySlots.contains(inventorySlot)
                || !inventoryBefore.get(inventorySlot).isEmpty()) {
                continue;
            }
            NewStack stack = matchingStack(current, purchased);
            if (stack != null && MerchantRestockLogic.maySalvage(stack.rare, stack.placeAtPlot)
                && current.getCount() <= stack.remaining) {
                return new Transfer(menuSlot, inventorySlot, current.getCount(), stack);
            }
        }
        return null;
    }

    private void classifyPurchases(Minecraft minecraft) {
        for (NewStack stack : purchased) {
            stack.placeAtPlot = MerchantRestockLogic.shouldPlace(stack.rare, config.merchantAlwaysPlaceRares,
                config.merchantAutoPlace, stackText(minecraft, stack.sample), config.merchantNpcThreshold);
        }
        int qualifying = queuedPlacements();
        if (qualifying > 0) {
            message(minecraft, "Keeping " + qualifying + " NPC" + (qualifying == 1 ? "" : "s")
                + " for plot placement (generation threshold or always-place rare setting)");
        }
    }

    private void continueAfterSalvage(Minecraft minecraft) {
        if (queuedPlacements() > 0) {
            beginPlacement(minecraft);
        } else {
            finish(minecraft, "Merchant purchase and salvage complete");
        }
    }

    private void beginPlacement(Minecraft minecraft) {
        int queued = queuedPlacements();
        closeScreen(minecraft);
        releaseMovement(minecraft);
        if (plotScanner.bounds() == null) {
            finish(minecraft, "Kept " + queued + " valuable NPC" + (queued == 1 ? "" : "s")
                + "; set NPC Plot bounds before auto-placement");
            return;
        }
        status = "Closing Merchant GUI before preparing " + queued + " valuable NPC"
            + (queued == 1 ? "" : "s");
        setPhase(Phase.CLOSING_FOR_PLACEMENT, 3);
    }

    private void openPlotAfterContainerClose(Minecraft minecraft) {
        int queued = queuedPlacements();
        if (!plotScanner.openPlot(minecraft)) {
            finish(minecraft, "Kept " + queued + " valuable NPC" + (queued == 1 ? "" : "s")
                + "; /plot could not be opened");
            return;
        }
        teleportedForPlacement = true;
        status = "Teleporting to plot with " + queued + " valuable NPC" + (queued == 1 ? "" : "s");
        setPhase(Phase.OPENING_PLOT, 45);
    }

    private void handlePlacement(Minecraft minecraft) {
        if (minecraft.player == null) {
            return;
        }
        if (phase == Phase.PREPARING_ITEM || phase == Phase.WAITING_FOR_HOTBAR) {
            releaseMovement(minecraft);
            minecraft.options.keyAttack.setDown(false);
            if (minecraft.gui.screen() != placementInventoryScreen || placementInventoryScreen == null
                || minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
                if (phase == Phase.WAITING_FOR_HOTBAR) {
                    stopForInventory(minecraft, "Inventory transfer interrupted; check the item on your cursor");
                    return;
                }
                if (!minecraft.player.containerMenu.getCarried().isEmpty()) {
                    stopForInventory(minecraft, "Clear the cursor before moving NPCs; inventory left open");
                } else if (minecraft.gui.screen() != null || minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
                    closeScreen(minecraft);
                    if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) minecraft.player.closeContainer();
                    if (phaseAge() > 100) stopForInventory(minecraft, "Merchant GUI did not close; NPCs kept");
                    else delay(6);
                } else if (ready()) {
                    placementInventoryScreen = new InventoryScreen(minecraft.player);
                    minecraft.setScreenAndShow(placementInventoryScreen);
                    delay(10);
                }
                return;
            }
            if (phase == Phase.PREPARING_ITEM) preparePlacementItem(minecraft);
            else verifyHotbarItem(minecraft);
            return;
        }
        boolean inventoryReady = MerchantRestockLogic.inventoryReady(
            minecraft.gui.screen() == null,
            minecraft.player.containerMenu == minecraft.player.inventoryMenu);
        if (!inventoryReady) {
            if (!minecraft.player.containerMenu.getCarried().isEmpty()) {
                stopForInventory(minecraft, "Inventory cursor is occupied; automation paused without closing it");
                return;
            }
            closeScreen(minecraft);
            if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
                minecraft.player.closeContainer();
            }
            status = "Closing the active GUI before inventory setup";
            if (phase == Phase.CLOSING_FOR_PLACEMENT && phaseAge() > 100) {
                finish(minecraft, "Valuable NPCs were kept because the Merchant GUI did not close");
            } else {
                delay(3);
            }
            return;
        }
        switch (phase) {
            case CLOSING_FOR_PLACEMENT -> {
                if (ready()) {
                    openPlotAfterContainerClose(minecraft);
                }
            }
            case OPENING_PLOT -> handlePlotArrival(minecraft);
            case VERIFYING_HELD_ITEM -> verifyEquippedItem(minecraft);
            case SELECTING_SLOT -> selectNextPlotSlot(minecraft);
            case WALKING_TO_SLOT -> walkToPlotSlot(minecraft);
            case AIMING_AT_SLOT -> aimAndPlace(minecraft);
            case WAITING_FOR_PLACEMENT -> verifyPlacement(minecraft);
            case RETURNING_TO_FARM -> waitForFarmReturn(minecraft);
            case WALKING_INTO_FARM -> walkIntoFarm(minecraft);
            case STARTING_FLIGHT -> doubleTapFlight(minecraft);
            case WAITING_FOR_FLIGHT -> waitForFlight(minecraft);
            case ADJUSTING_HOVER -> adjustFarmHover(minecraft);
            default -> {
            }
        }
    }

    private void handlePlotArrival(Minecraft minecraft) {
        if (!ready() || minecraft.player == null) {
            return;
        }
        PlotScannerController.PlotBounds plot = plotScanner.bounds();
        if (plot == null) {
            finish(minecraft, "Valuable NPCs were kept, but the saved plot bounds are unavailable");
            return;
        }
        boolean insidePlot = minecraft.player.getX() >= plot.minX() - 4.0
            && minecraft.player.getX() <= plot.maxX() + 5.0
            && minecraft.player.getZ() >= plot.minZ() - 4.0
            && minecraft.player.getZ() <= plot.maxZ() + 5.0;
        if (!insidePlot) {
            if (phaseAge() > 200) {
                finish(minecraft, "Valuable NPCs were kept because /plot did not reach the saved plot");
            } else {
                delay(10);
            }
            return;
        }
        if (!minecraft.player.onGround()) {
            releaseMovement(minecraft);
            minecraft.options.keyShift.setDown(true);
            status = "Landing before walking to an open plot slot";
            if (phaseAge() > 260) {
                finish(minecraft, "Valuable NPCs were kept because the player could not land at the plot");
            }
            return;
        }
        minecraft.options.keyShift.setDown(false);
        setPhase(Phase.PREPARING_ITEM, 2);
    }

    private void selectNextPlotSlot(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!ready()) {
            return;
        }
        if (activePlacement == null || !heldPlacementItem(minecraft)) {
            setPhase(Phase.PREPARING_ITEM, 2);
            return;
        }
        PlotScannerController.PlotSlot selected = plotScanner.bestPlacementSlot(minecraft,
            reservedPlacementSlots);
        if (selected == null) {
            finish(minecraft, "Placed " + placedCount + "; kept " + queuedPlacements()
                + " because no open plot slot was available");
            return;
        }
        placementTarget = selected.position();
        stalledTicks = 0;
        previousTargetDistance = Double.POSITIVE_INFINITY;
        status = "Walking to open slot " + shortPos(placementTarget) + " • " + queuedPlacements() + " queued";
        setPhase(Phase.WALKING_TO_SLOT, 0);
    }

    private void preparePlacementItem(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!ready() || minecraft.player == null) {
            return;
        }
        if (!minecraft.player.inventoryMenu.getCarried().isEmpty()) {
            stopForInventory(minecraft, "Clear the cursor before preparing the next NPC; inventory left open");
            return;
        }
        if (activePlacement == null) {
            activePlacement = purchased.stream()
                .filter(stack -> stack.placeAtPlot && stack.remaining > 0)
                .findFirst().orElse(null);
            if (activePlacement == null) {
                finish(minecraft, "Merchant complete — placed " + placedCount + " valuable NPC"
                    + (placedCount == 1 ? "" : "s"));
                return;
            }
            placementAttempts = 0;
            hotbarMoveAttempts = 0;
            pendingHotbarSlot = -1;
        }
        Inventory inventory = minecraft.player.getInventory();
        int inventorySlot = findInventorySlot(inventory, activePlacement.sample);
        if (inventorySlot < 0) {
            if (phaseAge() <= 80) {
                delay(5);
                return;
            }
            int missing = activePlacement.remaining;
            activePlacement.remaining = 0;
            message(minecraft, "Could not find " + missing + " queued NPC item"
                + (missing == 1 ? "" : "s") + "; continuing with the remaining queue");
            activePlacement = null;
            setPhase(Phase.PREPARING_ITEM, 2);
            return;
        }
        if (hotbarMoveAttempts++ >= 3) {
            stopForInventory(minecraft, "NPC hotbar transfer repeatedly rejected; items kept for manual inspection");
            return;
        }
        if (Inventory.isHotbarSlot(inventorySlot)) {
            pendingHotbarSlot = inventorySlot;
            hotbarTransfer = null;
            selectAndCloseInventory(minecraft);
            return;
        }
        if (!minecraft.player.inventoryMenu.getCarried().isEmpty() || minecraft.gameMode == null) {
            stopForInventory(minecraft, "Cannot move the NPC with an occupied cursor; inventory left open");
            return;
        }
        sourceInventorySlot = inventorySlot;
        pendingHotbarSlot = placementHotbarSlot(inventory, originalSelectedSlot);
        hotbarTransfer = new HotbarTransfer<>(inventory.getItem(inventorySlot).copy(),
            inventory.getItem(pendingHotbarSlot).copy(), ItemStack::matches, ItemStack::isEmpty);
        status = "Preparing inventory pickup into hotbar slot " + (pendingHotbarSlot + 1);
        setPhase(Phase.WAITING_FOR_HOTBAR, 0);
    }

    private void verifyHotbarItem(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!ready() || minecraft.player == null || activePlacement == null) {
            return;
        }
        Inventory inventory = minecraft.player.getInventory();
        var menu = minecraft.player.inventoryMenu;
        var step = hotbarTransfer.nextStep(ticks, inventory.getItem(sourceInventorySlot),
            inventory.getItem(pendingHotbarSlot), menu.getCarried());
        if (step == HotbarTransfer.Step.ABORT) {
            stopForInventory(minecraft, "Inventory did not settle; NPC/tool left available for manual inspection");
            return;
        }
        if (step == HotbarTransfer.Step.WAIT) return;
        if (step == HotbarTransfer.Step.COMPLETE) {
            selectAndCloseInventory(minecraft);
            return;
        }
        int inventorySlot = step == HotbarTransfer.Step.PLACE_HOTBAR ? pendingHotbarSlot : sourceInventorySlot;
        int menuSlot = menuSlotForInventory(menu, inventory, inventorySlot);
        if (menuSlot < 0 || minecraft.gameMode == null) {
            stopForInventory(minecraft, "Could not map the player inventory slot; no further clicks sent");
            return;
        }
        click(minecraft, menu, menuSlot, 0, ContainerInput.PICKUP);
        status = switch (step) {
            case PICK_SOURCE -> "Picking up the NPC • waiting for inventory to settle";
            case PLACE_HOTBAR -> "Placing the NPC into the hotbar • waiting";
            default -> "Returning the displaced hotbar item to its safe inventory slot";
        };
    }

    private void selectAndCloseInventory(Minecraft minecraft) {
        minecraft.player.getInventory().setSelectedSlot(pendingHotbarSlot);
        closeScreen(minecraft);
        placementInventoryScreen = null;
        status = "Hotbar equipped • checking again with the inventory closed";
        setPhase(Phase.VERIFYING_HELD_ITEM, 20);
    }

    private void verifyEquippedItem(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!ready()) return;
        var inventory = minecraft.player.getInventory();
        var step = hotbarTransfer == null ? HotbarTransfer.Step.COMPLETE
            : hotbarTransfer.step(inventory.getItem(sourceInventorySlot), inventory.getItem(pendingHotbarSlot),
                minecraft.player.inventoryMenu.getCarried());
        boolean transferComplete = step == HotbarTransfer.Step.COMPLETE;
        if (transferComplete && inventory.getSelectedSlot() == pendingHotbarSlot && heldPlacementItem(minecraft)) {
            status = "NPC secured in hand • selecting an open plot slot";
            setPhase(Phase.SELECTING_SLOT, 3);
        } else {
            if (step != HotbarTransfer.Step.COMPLETE && step != HotbarTransfer.Step.PICK_SOURCE) {
                stopForInventory(minecraft, "Partial inventory rollback; check the NPC and displaced item before continuing");
                return;
            }
            hotbarTransfer = null;
            status = "Inventory changed after closing • rechecking the NPC before walking";
            setPhase(Phase.PREPARING_ITEM, 10);
        }
    }

    private void stopForInventory(Minecraft minecraft, String reason) {
        // Do not close an uncertain cursor into a full inventory or resume movement.
        releaseMovement(minecraft);
        restoreFocusPause(minecraft);
        phase = Phase.OFF;
        clearMenuClick();
        automaticTriggersBlocked = true;
        if (!pendingRareOffer.isEmpty()) rareResult = "Purchase interrupted/unverified; no retry or Salvage";
        resumeTarget = ResumeTarget.NONE;
        activePlacement = null;
        placementTarget = null;
        hotbarTransfer = null;
        placementInventoryScreen = null;
        status = reason;
        message(minecraft, reason + " — interrupted macro remains paused");
    }

    private void walkToPlotSlot(Minecraft minecraft) {
        if (minecraft.player == null || placementTarget == null || activePlacement == null) {
            return;
        }
        if (!heldPlacementItem(minecraft)) {
            releaseMovement(minecraft);
            setPhase(Phase.PREPARING_ITEM, 6);
            return;
        }
        if (phaseAge() % 20 == 0) {
            plotScanner.refresh(minecraft);
            if (plotScanner.isOccupied(placementTarget)) {
                status = "Destination filled; selecting another open slot";
                setPhase(Phase.SELECTING_SLOT, 2);
                return;
            }
        }

        double targetX = placementTarget.getX() + 0.5;
        double targetZ = placementTarget.getZ() + 0.5;
        double dx = targetX - minecraft.player.getX();
        double dz = targetZ - minecraft.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.32) {
            releaseMovement(minecraft);
            status = "On plot slot " + shortPos(placementTarget) + " • looking down";
            setPhase(Phase.AIMING_AT_SLOT, 6);
            return;
        }

        float targetYaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
        float yawDelta = Mth.wrapDegrees(targetYaw - minecraft.player.getYRot());
        minecraft.player.setYRot(minecraft.player.getYRot() + Math.clamp(yawDelta, -10.0F, 10.0F));
        minecraft.player.setXRot(minecraft.player.getXRot()
            + Math.clamp(12.0F - minecraft.player.getXRot(), -4.0F, 4.0F));
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keyShift.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.player.setSprinting(false);
        minecraft.options.keyUp.setDown(Math.abs(yawDelta) < 35.0F);

        if (distance < previousTargetDistance - 0.015) {
            stalledTicks = 0;
        } else {
            stalledTicks++;
        }
        previousTargetDistance = distance;
        if (stalledTicks > 80 || phaseAge() > 600) {
            releaseMovement(minecraft);
            finish(minecraft, "Placed " + placedCount + "; kept " + queuedPlacements()
                + " because the selected plot slot could not be reached");
        }
    }

    private void aimAndPlace(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (minecraft.player == null || placementTarget == null || activePlacement == null) {
            return;
        }
        float pitchDelta = 90.0F - minecraft.player.getXRot();
        minecraft.player.setXRot(minecraft.player.getXRot() + Math.clamp(pitchDelta, -12.0F, 12.0F));
        if (!ready() || Math.abs(pitchDelta) > 2.0F) {
            return;
        }
        plotScanner.refresh(minecraft);
        if (plotScanner.isOccupied(placementTarget)) {
            status = "Destination filled; selecting another open slot";
            setPhase(Phase.SELECTING_SLOT, 2);
            return;
        }
        if (!ItemStack.isSameItemSameComponents(minecraft.player.getInventory().getSelectedItem(),
            activePlacement.sample)) {
            setPhase(Phase.PREPARING_ITEM, 2);
            return;
        }
        placementCountBefore = totalMatching(snapshotInventory(minecraft), activePlacement.sample);
        useNpcItemOnTarget(minecraft);
        placementAttempts++;
        status = "Placing NPC " + (placedCount + 1) + " • verifying server response";
        setPhase(Phase.WAITING_FOR_PLACEMENT, 12);
    }

    private void verifyPlacement(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!ready() || minecraft.player == null || activePlacement == null || placementTarget == null) {
            return;
        }
        plotScanner.refresh(minecraft);
        int currentCount = totalMatching(snapshotInventory(minecraft), activePlacement.sample);
        if (activePlacement.rare ? placementCountBefore - currentCount == 1
            : currentCount < placementCountBefore || plotScanner.isOccupied(placementTarget)) {
            reservedPlacementSlots.add(placementTarget);
            activePlacement.remaining--;
            placedCount++;
            status = "Placed " + placedCount + " • " + queuedPlacements() + " remaining";
            activePlacement = null;
            placementTarget = null;
            pendingHotbarSlot = -1;
            setPhase(Phase.PREPARING_ITEM, 10);
        } else if (activePlacement.rare && currentCount != placementCountBefore) {
            stopForInventory(minecraft, "Rare inventory changed unexpectedly during placement; inspect remaining NPCs");
        } else if (phaseAge() < 45) {
            delay(5);
        } else if (placementAttempts < 3) {
            reservedPlacementSlots.add(placementTarget);
            status = "Placement rejected • trying a different open slot";
            setPhase(Phase.SELECTING_SLOT, 6);
        } else {
            finish(minecraft, "Placed " + placedCount + "; kept " + queuedPlacements()
                + " after the server did not confirm placement");
        }
    }

    private void useNpcItemOnTarget(Minecraft minecraft) {
        if (minecraft.gameMode == null || minecraft.player == null || placementTarget == null) {
            return;
        }
        Vec3 hitLocation = new Vec3(placementTarget.getX() + 0.5,
            placementTarget.getY() + 1.0, placementTarget.getZ() + 0.5);
        minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(hitLocation, Direction.UP, placementTarget, false));
        minecraft.player.swing(InteractionHand.MAIN_HAND);
    }

    private static int findInventorySlot(Inventory inventory, ItemStack sample) {
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            if (ItemStack.isSameItemSameComponents(inventory.getItem(slot), sample)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean heldPlacementItem(Minecraft minecraft) {
        return minecraft.player != null && activePlacement != null
            && Inventory.isHotbarSlot(minecraft.player.getInventory().getSelectedSlot())
            && ItemStack.isSameItemSameComponents(minecraft.player.getInventory().getSelectedItem(),
                activePlacement.sample);
    }

    private static int placementHotbarSlot(Inventory inventory, int protectedSlot) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        // Keep the original mining tool in place when every hotbar slot is full.
        return (protectedSlot + 1) % Inventory.getSelectionSize();
    }

    private static int menuSlotForInventory(AbstractContainerMenu menu, Inventory inventory,
                                            int inventorySlot) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.getSlot(menuSlot);
            if (slot.container == inventory && slot.getContainerSlot() == inventorySlot) {
                return menuSlot;
            }
        }
        return -1;
    }

    private static List<NewStack> purchasedStacks(List<ItemStack> before, List<ItemStack> after) {
        List<NewStack> result = new ArrayList<>();
        for (ItemStack current : after) {
            if (current.isEmpty() || matchingStack(current, result) != null) {
                continue;
            }
            int count = MerchantRestockLogic.purchasedCount(totalMatching(before, current),
                totalMatching(after, current));
            if (count > 0) {
                result.add(new NewStack(current.copyWithCount(1), count));
            }
        }
        return result;
    }

    private static int totalMatching(List<ItemStack> inventory, ItemStack sample) {
        int total = 0;
        for (ItemStack stack : inventory) {
            if (ItemStack.isSameItemSameComponents(stack, sample)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static NewStack matchingStack(ItemStack sample, List<NewStack> stacks) {
        for (NewStack stack : stacks) {
            if (stack.remaining > 0 && ItemStack.isSameItemSameComponents(stack.sample, sample)) {
                return stack;
            }
        }
        return null;
    }

    private static List<ItemStack> snapshotInventory(Minecraft minecraft) {
        Inventory inventory = minecraft.player.getInventory();
        List<ItemStack> snapshot = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            snapshot.add(inventory.getItem(slot).copy());
        }
        return snapshot;
    }

    private int preferredSlot(Minecraft minecraft, AbstractContainerMenu menu, int fallback, Item item,
                              Predicate<String> semanticMatch) {
        int containerSlots = containerSlots(menu);
        if (validContainerSlot(menu, fallback) && !menu.getSlot(fallback).getItem().isEmpty()
            && (item == null || menu.getSlot(fallback).getItem().is(item))
            && semanticMatch.test(slotText(minecraft, menu, fallback))) {
            return fallback;
        }
        return findSlot(minecraft, menu, 0, containerSlots, semanticMatch);
    }

    private int findSlot(Minecraft minecraft, AbstractContainerMenu menu, int start, int end,
                         Predicate<String> predicate) {
        for (int slot = Math.max(0, start); slot < Math.min(end, menu.slots.size()); slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()
                && predicate.test(slotText(minecraft, menu, slot))) {
                return slot;
            }
        }
        return -1;
    }

    private String slotText(Minecraft minecraft, AbstractContainerMenu menu, int slot) {
        return stackText(minecraft, menu.getSlot(slot).getItem());
    }

    private static String stackText(Minecraft minecraft, ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(stack.getHoverName().getString());
        try {
            for (Component line : stack.getTooltipLines(Item.TooltipContext.of(minecraft.level),
                minecraft.player, TooltipFlag.NORMAL)) {
                text.append('\n').append(line.getString());
            }
        } catch (RuntimeException ignored) {
            // The item name and fixed slot remain available as fallbacks.
        }
        return text.toString();
    }

    private void click(Minecraft minecraft, AbstractContainerMenu menu, int slot, int button,
                       ContainerInput input) {
        if (minecraft.gameMode != null && minecraft.player != null && minecraft.player.containerMenu == menu
            && slot >= 0 && slot < menu.slots.size()) {
            // Player-inventory pickups belong to HotbarTransfer, not GUI buttons.
            if (input == ContainerInput.PICKUP && button == 0 && menu != minecraft.player.inventoryMenu
                && validContainerSlot(menu, slot) && menu.getCarried().isEmpty()) {
                pendingMenuClick = menu;
                pendingMenuIcon = menu.getSlot(slot).getItem().copy();
                menuClickedAt = ticks;
            }
            minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, input, minecraft.player);
        }
    }

    private boolean menuClickSettled(Minecraft minecraft) {
        var menu = minecraft.player.containerMenu;
        boolean predictedIcon = pendingMenuClick == menu && !pendingMenuIcon.isEmpty()
            && minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen && screen.getMenu() == menu
            && minecraft.player.inventoryMenu.getCarried().isEmpty()
            && ItemStack.matches(menu.getCarried(), pendingMenuIcon);
        var action = MerchantRestockLogic.menuCursorAction(cursorEmpty(minecraft), predictedIcon, ticks - menuClickedAt);
        if (action == MerchantRestockLogic.MenuCursorAction.WAIT) {
            status = "Waiting for the server to acknowledge the Merchant menu click";
            return false;
        }
        if (action == MerchantRestockLogic.MenuCursorAction.STOP) {
            stopForInventory(minecraft, predictedIcon ? "Merchant menu click did not settle; no retry sent"
                : "Cursor occupied by an unexpected item; no further merchant actions sent");
            return false;
        }
        if (pendingMenuClick != null) {
            clearMenuClick();
            // Cursor and slot updates can arrive separately. Leave a short gap
            // before the next action, in addition to the phase's existing delay.
            nextActionTick = Math.max(nextActionTick, ticks + 4);
        }
        return true;
    }

    private void clearMenuClick() {
        pendingMenuClick = null;
        pendingMenuIcon = ItemStack.EMPTY;
    }

    private void failAfter(Minecraft minecraft, String reason, int timeoutTicks) {
        if (phaseAge() > timeoutTicks) {
            abort(minecraft, reason);
        }
    }

    private void finish(Minecraft minecraft, String result) {
        if (!cursorEmpty(minecraft)) {
            stopForInventory(minecraft, result + " — cursor occupied; inventory left open");
            return;
        }
        if (resumeTarget == ResumeTarget.HARVESTER && teleportedForPlacement
            && !phase.isReturnPhase()) {
            beginFarmReturn(minecraft, result);
            return;
        }
        complete(minecraft, result, true);
    }

    private void beginFarmReturn(Minecraft minecraft, String result) {
        pendingResult = result;
        farmReturnAttempts = 0;
        flightAttempts = 0;
        hoverStableTicks = 0;
        closeScreen(minecraft);
        releaseMovement(minecraft);
        startFarmReturn(minecraft);
    }

    private void startFarmReturn(Minecraft minecraft) {
        if (++farmReturnAttempts > MAX_FARM_RETURN_ATTEMPTS) {
            complete(minecraft, pendingResult + " — could not return through /farm", false);
            return;
        }
        releaseMovement(minecraft);
        farmReturnOrigin = minecraft.player.position();
        sendCommand(minecraft, "farm");
        status = "Returning to farm • /farm attempt " + farmReturnAttempts + "/" + MAX_FARM_RETURN_ATTEMPTS;
        setPhase(Phase.RETURNING_TO_FARM, 2);
    }

    private void waitForFarmReturn(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!ready()) {
            return;
        }
        if (farmReturnOrigin != null && minecraft.player.position().distanceToSqr(farmReturnOrigin)
            >= FARM_RETURN_TELEPORT_DISTANCE * FARM_RETURN_TELEPORT_DISTANCE) {
            farmReturnYaw = minecraft.player.getYRot();
            farmWalkStartX = minecraft.player.getX();
            farmWalkStartZ = minecraft.player.getZ();
            status = "/farm arrived • walking five blocks inward";
            setPhase(Phase.WALKING_INTO_FARM, 3);
            return;
        }
        if (phaseAge() >= FARM_RETURN_TIMEOUT_TICKS) {
            startFarmReturn(minecraft);
        }
    }

    private void walkIntoFarm(Minecraft minecraft) {
        if (!ready()) {
            return;
        }
        double dx = minecraft.player.getX() - farmWalkStartX;
        double dz = minecraft.player.getZ() - farmWalkStartZ;
        if (Math.hypot(dx, dz) >= FARM_RETURN_WALK_DISTANCE) {
            releaseMovement(minecraft);
            farmGroundY = minecraft.player.getY();
            if (minecraft.player.getAbilities().flying) {
                beginHoverAdjustment();
            } else {
                beginFlightAttempt(minecraft);
            }
            return;
        }
        if (minecraft.player.horizontalCollision && phaseAge() >= 10
            || phaseAge() >= FARM_WALK_TIMEOUT_TICKS) {
            startFarmReturn(minecraft);
            return;
        }
        float yawDelta = Mth.wrapDegrees(farmReturnYaw - minecraft.player.getYRot());
        minecraft.player.setYRot(minecraft.player.getYRot() + Math.clamp(yawDelta, -8.0F, 8.0F));
        minecraft.player.setXRot(minecraft.player.getXRot()
            + Math.clamp(12.0F - minecraft.player.getXRot(), -4.0F, 4.0F));
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyShift.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.player.setSprinting(false);
        minecraft.options.keyUp.setDown(Math.abs(yawDelta) < 25.0F);
    }

    private void beginFlightAttempt(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!minecraft.player.getAbilities().mayfly) {
            complete(minecraft, pendingResult + " — /farm did not permit flight; Harvester remains paused", false);
            return;
        }
        if (++flightAttempts > MAX_FLIGHT_ATTEMPTS) {
            complete(minecraft, pendingResult + " — double-tap flight was not accepted; Harvester remains paused", false);
            return;
        }
        status = "Starting flight • double-tap attempt " + flightAttempts + "/" + MAX_FLIGHT_ATTEMPTS;
        setPhase(Phase.STARTING_FLIGHT, 0);
    }

    private void doubleTapFlight(Minecraft minecraft) {
        releaseMovement(minecraft);
        minecraft.options.keyJump.setDown(MerchantRestockLogic.flightTapDown((int)phaseAge()));
        if (phaseAge() >= 5) {
            minecraft.options.keyJump.setDown(false);
            status = "Verifying flight before Harvester resumes";
            setPhase(Phase.WAITING_FOR_FLIGHT, 1);
        }
    }

    private void waitForFlight(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!ready()) {
            return;
        }
        if (minecraft.player.getAbilities().flying) {
            beginHoverAdjustment();
        } else if (phaseAge() >= FLIGHT_CONFIRM_TIMEOUT_TICKS) {
            beginFlightAttempt(minecraft);
        }
    }

    private void beginHoverAdjustment() {
        hoverStableTicks = 0;
        status = "Flight confirmed • settling just above the crops";
        setPhase(Phase.ADJUSTING_HOVER, 0);
    }

    private void adjustFarmHover(Minecraft minecraft) {
        releaseMovement(minecraft);
        if (!minecraft.player.getAbilities().flying) {
            beginFlightAttempt(minecraft);
            return;
        }
        double error = farmGroundY + FARM_HOVER_HEIGHT - minecraft.player.getY();
        if (error > 0.12) {
            minecraft.options.keyJump.setDown(true);
            hoverStableTicks = 0;
        } else if (error < -0.16) {
            minecraft.options.keyShift.setDown(true);
            hoverStableTicks = 0;
        } else {
            hoverStableTicks++;
        }
        if (hoverStableTicks >= 4) {
            releaseMovement(minecraft);
            complete(minecraft, pendingResult, true);
        } else if (phaseAge() >= HOVER_TIMEOUT_TICKS) {
            complete(minecraft, pendingResult + " — could not settle at farming height; Harvester remains paused", false);
        }
    }

    private void complete(Minecraft minecraft, String result, boolean allowResume) {
        if (!cursorEmpty(minecraft)) {
            stopForInventory(minecraft, result + " — cursor occupied; inventory left open");
            return;
        }
        ResumeTarget target = resumeTarget;
        closeScreen(minecraft);
        if (minecraft.player != null) minecraft.player.getInventory().setSelectedSlot(originalSelectedSlot);
        releaseMovement(minecraft);
        restoreFocusPause(minecraft);
        phase = Phase.OFF;
        if (allowResume) workflowIndex = WORKFLOW_STEPS.size() - 1;
        clearMenuClick();
        status = result;
        resumeTarget = ResumeTarget.NONE;
        inventoryBefore = List.of();
        activePlacement = null;
        placementTarget = null;
        attemptedInventorySlots.clear();
        reservedPlacementSlots.clear();

        boolean resumed = allowResume && switch (target) {
            case HARVESTER -> harvester.resumeAfterUtility(minecraft);
            case MINE -> mine.resumeAfterUtility(minecraft);
            case EGG_HATCHER -> eggHatcher.start(minecraft);
            case NONE -> false;
        };
        String suffix = resumed ? " — " + target.label + " resumed"
            : target != ResumeTarget.NONE ? " — " + target.label + " remains paused" : "";
        teleportedForPlacement = false;
        pendingResult = null;
        farmReturnOrigin = null;
        farmReturnAttempts = 0;
        flightAttempts = 0;
        hoverStableTicks = 0;
        message(minecraft, result + suffix);
    }

    private void abort(Minecraft minecraft, String reason) {
        stopForInventory(minecraft, reason);
    }

    private void stopSilently(Minecraft minecraft) {
        releaseMovement(minecraft);
        restoreFocusPause(minecraft);
        phase = Phase.OFF;
        clearMenuClick();
        automaticTriggersBlocked = true;
        status = "Merchant stopped after disconnect; check inventory before restarting";
        resumeTarget = ResumeTarget.NONE;
        inventoryBefore = List.of();
        activePlacement = null;
        placementTarget = null;
        hotbarTransfer = null;
        placementInventoryScreen = null;
        attemptedInventorySlots.clear();
        reservedPlacementSlots.clear();
        pendingResult = null;
        farmReturnOrigin = null;
        farmReturnAttempts = 0;
        flightAttempts = 0;
        hoverStableTicks = 0;
    }

    private void keepRunningWhenUnfocused(Minecraft minecraft) {
        if (previousPauseOnLostFocus == null) {
            previousPauseOnLostFocus = minecraft.options.pauseOnLostFocus;
        }
        minecraft.options.pauseOnLostFocus = false;
    }

    private void restoreFocusPause(Minecraft minecraft) {
        if (previousPauseOnLostFocus != null) {
            minecraft.options.pauseOnLostFocus = previousPauseOnLostFocus;
            previousPauseOnLostFocus = null;
        }
    }

    private static int containerSlots(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - 36);
    }

    private static boolean validContainerSlot(AbstractContainerMenu menu, int slot) {
        return slot >= 0 && slot < containerSlots(menu);
    }

    private static void closeScreen(Minecraft minecraft) {
        if (!cursorEmpty(minecraft)) return;
        if (minecraft.gui.screen() instanceof AbstractContainerScreen<?> && minecraft.player != null) {
            minecraft.player.closeContainer();
        } else if (minecraft.gui.screen() != null) {
            minecraft.gui.setScreen(null);
        }
    }

    private static boolean cursorEmpty(Minecraft minecraft) {
        return minecraft.player == null || minecraft.player.containerMenu.getCarried().isEmpty()
            && minecraft.player.inventoryMenu.getCarried().isEmpty();
    }

    private static void releaseMovement(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyShift.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.options.keyAttack.setDown(false);
        minecraft.options.keyUse.setDown(false);
        if (minecraft.player != null) {
            minecraft.player.setSprinting(false);
        }
    }

    private static void sendCommand(Minecraft minecraft, String command) {
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand(command);
        }
    }

    private static void message(Minecraft minecraft, String text) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("[Cropium] " + text));
        }
    }

    private void setPhase(Phase next, int delay) {
        phase = next;
        workflowIndex = switch (next) {
            case OFF -> workflowIndex;
            case OPENING_MERCHANT -> 0;
            case SCANNING_RARES, WAITING_FOR_RARE_PURCHASE -> 1;
            case BUYING_NORMAL, WAITING_FOR_PURCHASE -> 2;
            case OPENING_FORGE, OPENING_SALVAGE -> 3;
            case SALVAGING, CONFIRMING, WAITING_FOR_CONFIRM -> 4;
            case CLOSING_FOR_PLACEMENT, OPENING_PLOT -> 5;
            case PREPARING_ITEM, WAITING_FOR_HOTBAR, VERIFYING_HELD_ITEM, SELECTING_SLOT,
                WALKING_TO_SLOT, AIMING_AT_SLOT, WAITING_FOR_PLACEMENT -> 6;
            case RETURNING_TO_FARM, WALKING_INTO_FARM, STARTING_FLIGHT, WAITING_FOR_FLIGHT,
                ADJUSTING_HOVER -> 7;
        };
        phaseSince = ticks;
        nextActionTick = ticks + delay;
    }

    private void delay(int delay) {
        nextActionTick = ticks + delay;
    }

    private boolean ready() {
        return ticks >= nextActionTick;
    }

    private long phaseAge() {
        return ticks - phaseSince;
    }

    private static String shortPos(BlockPos position) {
        return position.getX() + ", " + position.getY() + ", " + position.getZ();
    }

    private enum Phase {
        OFF("idle"),
        OPENING_MERCHANT("opening Merchant"),
        SCANNING_RARES("inspecting rare NPC offers"),
        WAITING_FOR_RARE_PURCHASE("verifying rare NPC purchase"),
        BUYING_NORMAL("buying normal NPCs"),
        WAITING_FOR_PURCHASE("waiting for Buy All"),
        OPENING_FORGE("opening Minion Forge"),
        OPENING_SALVAGE("opening Salvage"),
        SALVAGING("moving new purchases"),
        CONFIRMING("confirming Salvage"),
        WAITING_FOR_CONFIRM("waiting for Salvage"),
        CLOSING_FOR_PLACEMENT("closing Merchant before inventory setup"),
        OPENING_PLOT("opening the NPC plot"),
        PREPARING_ITEM("selecting a valuable NPC"),
        WAITING_FOR_HOTBAR("verifying the hotbar item"),
        VERIFYING_HELD_ITEM("checking the equipped NPC after closing inventory"),
        SELECTING_SLOT("selecting an open plot slot"),
        WALKING_TO_SLOT("walking to an open plot slot"),
        AIMING_AT_SLOT("looking down at the plot slot"),
        WAITING_FOR_PLACEMENT("verifying NPC placement"),
        RETURNING_TO_FARM("returning through /farm"),
        WALKING_INTO_FARM("walking into the farm"),
        STARTING_FLIGHT("double-tapping flight"),
        WAITING_FOR_FLIGHT("verifying flight"),
        ADJUSTING_HOVER("settling at farming height");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        private boolean isPlacementPhase() {
            return ordinal() >= CLOSING_FOR_PLACEMENT.ordinal();
        }

        private boolean isReturnPhase() {
            return ordinal() >= RETURNING_TO_FARM.ordinal();
        }
    }

    private enum ResumeTarget {
        NONE("module"),
        HARVESTER("Harvester"),
        MINE("Mine Harvester"),
        EGG_HATCHER("Egg Hatcher");

        private final String label;

        ResumeTarget(String label) {
            this.label = label;
        }
    }

    private static final class NewStack {
        private final ItemStack sample;
        private int remaining;
        private boolean placeAtPlot;
        private boolean rare;

        private NewStack(ItemStack sample, int remaining) {
            this.sample = sample;
            this.remaining = remaining;
        }
    }

    private record Transfer(int menuSlot, int inventorySlot, int count, NewStack stack) {
    }
}
