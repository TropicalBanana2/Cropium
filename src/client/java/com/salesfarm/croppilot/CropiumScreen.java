package com.salesfarm.croppilot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public final class CropiumScreen extends Screen {
    private static final int BACKDROP_TOP = 0xEB080810;
    private static final int BACKDROP_BOTTOM = 0xF3050710;
    private static final int SHELL = 0xFD10101A;
    private static final int RAIL = 0xFF0B0B13;
    private static final int SURFACE = 0xFF181824;
    private static final int SURFACE_HOVER = 0xFF222233;
    private static final int SURFACE_SOFT = 0xFF13131E;
    private static final int ACCENT = 0xFF9B7BFF;
    private static final int ACCENT_BRIGHT = 0xFFB9A7FF;
    private static final int CYAN = 0xFF65D9F3;
    private static final int SUCCESS = 0xFF6EE7A8;
    private static final int DANGER = 0xFFFF667A;
    private static final int TEXT = 0xFFF4F1FF;
    private static final int MUTED = 0xFF9A99AA;
    private static final int DIM = 0xFF666576;
    private static final int BORDER = 0xFF2A2938;
    private static Page rememberedPage = Page.DASHBOARD;

    private final Screen parent;
    private final CropPilotConfig config;
    private final HarvestController controller;
    private final MineController mine;
    private final MerchantRestockController merchantRestock;
    private final PlotScannerController plotScanner;
    private final List<CropiumModule> modules;
    private Page page = rememberedPage;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int railWidth;
    private int contentX;
    private int contentWidth;
    private FrameLayout pageLayout;
    private PageCanvas pageCanvas;
    private boolean buildingPage;
    private String widgetState;
    private final MapRaster mineMapRaster = new MapRaster();
    private boolean mapMine = true;
    private ExclusionZone draftZone;
    private String mapStatus = "Pause or stop, then drag a rectangle to exclude it";
    private String thresholdDraft;
    private String thresholdStatus = "Type an amount, e.g. 750M or 1.2Q, then Apply";
    private final List<MotionMath.Vec2> traceStroke = new ArrayList<>();
    private TracedRoute.Shape traceShape = new TracedRoute.Shape(List.of(), "Draw a line on the map");
    private String traceId, traceWorld, traceMap;
    private String traceInputProblem;
    private String traceName = "Phase 1";
    private String traceStatus = "Pause or stop, then hold left mouse and draw";

    public CropiumScreen(Screen parent, CropPilotConfig config, HarvestController controller,
                         MineController mine, MerchantRestockController merchantRestock,
                         PlotScannerController plotScanner,
                         List<CropiumModule> modules) {
        super(Component.literal("Cropium"));
        this.parent = parent;
        this.config = config;
        this.controller = controller;
        this.mine = mine;
        this.merchantRestock = merchantRestock;
        this.plotScanner = plotScanner;
        this.modules = List.copyOf(modules);
    }

    @Override
    protected void init() {
        pageCanvas = null;
        buildingPage = false;
        panelWidth = Math.min(760, width - 12);
        panelHeight = Math.min(390, height - 12);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        railWidth = Math.clamp(panelWidth / 4, 108, 142);
        contentX = panelX + railWidth + 13;
        contentWidth = panelWidth - railWidth - 34;

        FrameLayout navigation = new FrameLayout(railWidth - 28, 0);
        int y = 0;
        for (Page candidate : Page.values()) {
            String group = candidate == Page.HARVESTER ? "MACROS"
                : candidate == Page.MERCHANT ? "SHARED TOOLS" : null;
            if (group != null) {
                StringWidget label = new StringWidget(Component.literal(group)
                    .withStyle(style -> style.withColor(DIM & 0xFFFFFF)), font);
                navigation.addChild(label, navigation.newChildLayoutSettings().align(0, 0).paddingTop(y + 5));
                y += 20;
            }
            Page selected = candidate;
            CropiumButton button = new CropiumButton(0, 0, railWidth - 28, 20, Component.literal(candidate.label), ignored -> {
                page = selected;
                rememberedPage = selected;
                rebuildWidgets();
            },
                ButtonStyle.NAVIGATION, page == candidate);
            navigation.addChild(button, navigation.newChildLayoutSettings().align(0, 0).paddingTop(y));
            y += 23;
        }
        ScrollableLayout navScroll = new ScrollableLayout(minecraft, navigation, panelHeight - 99,
            ScrollableLayout.ReserveStrategy.RIGHT);
        navScroll.setX(panelX + 9);
        navScroll.setY(panelY + 47);
        navScroll.arrangeElements();
        navScroll.visitWidgets(this::addRenderableWidget);

        pageLayout = new FrameLayout(contentWidth, pageHeight());
        pageCanvas = pageLayout.addChild(new PageCanvas(contentWidth, pageHeight()),
            pageLayout.newChildLayoutSettings().align(0, 0));
        buildingPage = true;
        switch (page) {
            case DASHBOARD -> addDashboardControls();
            case HARVESTER -> addHarvesterControls();
            case MINE -> addMineControls();
            case EGG_HATCHER -> addEggHatcherControls();
            case MERCHANT -> addMerchantControls();
            case PLOT_SCANNER -> addPlotScannerControls();
            case SETTINGS -> addSettingsControls();
            case CONTROLS -> addQuickControls();
            case COVERAGE -> addCoverageControls();
            case ROUTES -> addRouteControls();
            case WORKFLOW -> addWorkflowControls();
            case STATISTICS -> { }
        }
        buildingPage = false;
        ScrollableLayout contentScroll = new ScrollableLayout(minecraft, pageLayout, panelHeight - 58,
            ScrollableLayout.ReserveStrategy.RIGHT);
        contentScroll.setX(contentX);
        contentScroll.setY(panelY + 47);
        contentScroll.arrangeElements();
        contentScroll.visitWidgets(this::addRenderableWidget);
        addButton(panelX + 9, panelY + panelHeight - 27, railWidth - 18, 19,
            Component.literal("Close"), ButtonStyle.SECONDARY, false, ignored -> onClose());
        widgetState = activeWidgetState();
    }

    private int pageHeight() {
        return switch (page) {
            case DASHBOARD -> 82 + modules.size() * 51;
            case HARVESTER -> 294;
            case MINE -> 702;
            case MERCHANT -> 389;
            case CONTROLS -> 361;
            case COVERAGE -> 385;
            case ROUTES -> 574;
            case WORKFLOW -> 160 + merchantRestock.workflowSteps().size() * 20
                + workflowDetails().stream().mapToInt(line -> font.split(Component.literal(line), contentWidth).size() * 13).sum();
            case SETTINGS -> config.advancedSettings ? 390 : 245;
            case STATISTICS -> 257;
            case PLOT_SCANNER -> Math.max(220, panelHeight - 58);
            default -> 168;
        };
    }

    private String activeWidgetState() {
        return modules.stream().map(module -> module.id() + module.isActive()).reduce("", String::concat)
            + merchantRestock.isActive() + mine.canPause() + mine.isPaused() + controller.isPaused();
    }

    @Override
    public void tick() {
        if (!activeWidgetState().equals(widgetState)) {
            rebuildWidgets();
        }
    }

    private void addDashboardControls() {
        int y = contentTop() + 34;
        for (CropiumModule module : modules) {
            addModuleButton(module, y + 9);
            addButton(contentX + contentWidth - 137, y + 9, 56, 24, Component.literal("Open"),
                ButtonStyle.SECONDARY, false, ignored -> {
                    page = switch (module.id()) {
                        case "harvest" -> Page.HARVESTER;
                        case "mine" -> Page.MINE;
                        default -> Page.EGG_HATCHER;
                    };
                    rememberedPage = page;
                    rebuildWidgets();
                });
            y += 51;
        }
        int intermediaryY = contentTop() + 34 + modules.size() * 51;
        CropiumButton intermediary = addButton(contentX + Math.max(88, contentWidth - 164),
            intermediaryY + 20, Math.min(164, contentWidth - 88), 17,
            Component.literal(merchantRestock.isActive() ? "Buy + Salvage Running" : "Buy and Salvage NPCs"),
            ButtonStyle.PRIMARY, false, ignored -> merchantRestock.startManual(minecraft));
        intermediary.active = !merchantRestock.isActive();
    }

    private void addHarvesterControls() {
        CropiumModule module = module("harvest");
        if (module != null) {
            addModuleButton(module, contentTop() + 7);
        }
        int gap = 7;
        int controlWidth = (contentWidth - gap) / 2;
        addToggle(contentX, contentTop() + 46, controlWidth, "Glowing targets", () -> config.targetGlowingPlants,
            () -> config.targetGlowingPlants = !config.targetGlowingPlants);
        addToggle(contentX + controlWidth + gap, contentTop() + 46, controlWidth, "Glow inspector", () -> config.glowInspector,
            () -> config.glowInspector = !config.glowInspector);
        addToggle(contentX, contentTop() + 71, contentWidth, "Merchant restock + salvage",
            () -> config.merchantRestockHarvester,
            () -> config.merchantRestockHarvester = !config.merchantRestockHarvester);
    }

    private void addMineControls() {
        CropiumModule module = module("mine");
        if (module != null) {
            addModuleButton(module, contentTop() + 7);
        }
        int gap = 7;
        int half = (contentWidth - gap) / 2;
        addToggle(contentX, contentTop() + 46, half, "Target fossils",
            () -> config.mineTargetFossils,
            () -> config.mineTargetFossils = !config.mineTargetFossils);
        addToggle(contentX + half + gap, contentTop() + 46, half, "Bias toward ice",
            () -> config.mineTargetIce,
            () -> config.mineTargetIce = !config.mineTargetIce);
        addToggle(contentX, contentTop() + 71, contentWidth, "Merchant restock + salvage",
            () -> config.merchantRestockMine,
            () -> config.merchantRestockMine = !config.merchantRestockMine);
        addButton(contentX, contentTop() + 99, half, 21, Component.literal("Preview route"),
            ButtonStyle.SECONDARY, false, ignored -> mine.previewMine(minecraft));
        addButton(contentX + half + gap, contentTop() + 99, half, 21, Component.literal("Export diagnostics"),
            ButtonStyle.SECONDARY, false, ignored -> mine.exportDiagnostics(minecraft, true));
        addSelectorButtons(contentX, contentTop() + 126, contentWidth,
            () -> { config.mineRouteMode = config.mineRouteMode.offset(-1); mine.routeSettingsChanged(); },
            () -> { config.mineRouteMode = config.mineRouteMode.offset(1); mine.routeSettingsChanged(); });
        addSlider(contentX, contentTop() + 174, half, "Route interval", 30, 180, 5,
            () -> config.mineRouteSeconds,
            value -> { config.mineRouteSeconds = (int)value; mine.routeSettingsChanged(); }, "%.0fs ±25%%");
        addSlider(contentX + half + gap, contentTop() + 174, half, "Loop variation", 0, 100, 5,
            () -> config.mineRouteVariation,
            value -> { config.mineRouteVariation = (int)value; mine.routeSettingsChanged(); }, "%.0f%%");
        addToggle(contentX, contentTop() + 203, half, "World overlay", () -> config.showWorldOverlay,
            () -> config.showWorldOverlay = !config.showWorldOverlay);
        addToggle(contentX + half + gap, contentTop() + 203, half, "Detached camera", () -> config.thirdPersonCamera,
            () -> config.thirdPersonCamera = !config.thirdPersonCamera);
        addToggle(contentX, contentTop() + 228, half, "Natural movement", () -> config.naturalMovement,
            () -> config.naturalMovement = !config.naturalMovement);
        var pause = addButton(contentX + half + gap, contentTop() + 228, half, 21,
            Component.literal(mine.isPaused() ? "Resume route [P]" : "Pause route [P]"),
            ButtonStyle.SECONDARY, false, ignored -> { mine.pause(minecraft); rebuildWidgets(); });
        pause.active = mine.canPause() && !merchantRestock.isActive();
    }

    private void addEggHatcherControls() {
        CropiumModule module = module("egg-hatcher");
        if (module != null) {
            addModuleButton(module, contentTop() + 7);
        }
        int gap = 8;
        int selectorWidth = (contentWidth - gap) / 2;
        int selectorY = contentTop() + 45;
        addSelectorButtons(contentX, selectorY, selectorWidth,
            () -> config.petEgg = config.petEgg.offset(-1),
            () -> config.petEgg = config.petEgg.offset(1));
        addSelectorButtons(contentX + selectorWidth + gap, selectorY, selectorWidth,
            () -> config.petMaxForgeTier = config.petMaxForgeTier.offset(-1),
            () -> config.petMaxForgeTier = config.petMaxForgeTier.offset(1));

        int amountY = contentTop() + 94;
        addButton(contentX, amountY, 76, 21, Component.literal("Open 3"), ButtonStyle.SEGMENT,
            config.petOpenAmount == 3, ignored -> {
                config.petOpenAmount = 3;
                config.save();
                rebuildWidgets();
            });
        addButton(contentX + 82, amountY, 76, 21, Component.literal("Open 9"), ButtonStyle.SEGMENT,
            config.petOpenAmount == 9, ignored -> {
                config.petOpenAmount = 9;
                config.save();
                rebuildWidgets();
            });
        addToggle(contentX + 166, amountY, Math.max(90, contentWidth - 166), "Restock + salvage",
            () -> config.merchantRestockEggHatcher,
            () -> config.merchantRestockEggHatcher = !config.merchantRestockEggHatcher);
    }

    private void addSelectorButtons(int x, int y, int width, Runnable previous, Runnable next) {
        addButton(x + 5, y + 18, 21, 18, Component.literal("‹"), ButtonStyle.SECONDARY, false, ignored -> {
            previous.run();
            config.save();
            rebuildWidgets();
        });
        addButton(x + width - 26, y + 18, 21, 18, Component.literal("›"), ButtonStyle.SECONDARY, false, ignored -> {
            next.run();
            config.save();
            rebuildWidgets();
        });
    }

    private void addPlotScannerControls() {
        int gap = 6;
        int buttonWidth = (contentWidth - gap * 3) / 4;
        int y = contentTop();
        addButton(contentX, y, buttonWidth, 21, Component.literal("/plot"),
            ButtonStyle.PRIMARY, false, ignored -> {
                if (plotScanner.openPlot(minecraft)) {
                    minecraft.gui.setScreen(null);
                }
            });
        addButton(contentX + buttonWidth + gap, y, buttonWidth, 21,
            Component.literal(plotScanner.nextCornerLabel()), ButtonStyle.SECONDARY, false, ignored -> {
                if (plotScanner.selectNextCorner(minecraft)) {
                    minecraft.gui.setScreen(null);
                }
            });
        addButton(contentX + (buttonWidth + gap) * 2, y, buttonWidth, 21,
            Component.literal("Rescan"), ButtonStyle.SECONDARY, false, ignored -> plotScanner.scan(minecraft));
        addButton(contentX + (buttonWidth + gap) * 3, y, buttonWidth, 21,
            Component.literal("Clear"), ButtonStyle.DANGER, false, ignored -> {
                plotScanner.clear(minecraft);
                rebuildWidgets();
            });
    }

    private void addMerchantControls() {
        int top = contentTop();
        int gap = 7;
        int half = (contentWidth - gap) / 2;
        addToggle(contentX, top, contentWidth, "Place NPCs over threshold",
            () -> config.merchantAutoPlace,
            () -> config.merchantAutoPlace = !config.merchantAutoPlace);

        if (thresholdDraft == null) thresholdDraft = config.merchantNpcThreshold;
        EditBox threshold = new EditBox(font, contentX, top + 48, contentWidth - 69, 24,
            Component.literal("Minimum NPC generation amount"));
        threshold.setMaxLength(128);
        threshold.setValue(thresholdDraft);
        threshold.setEditable(!merchantRestock.isActive());
        threshold.setResponder(value -> {
            thresholdDraft = value;
            boolean valid = MerchantRestockLogic.parseAmount(value) != null;
            threshold.setTextColor(valid ? TEXT : DANGER);
            thresholdStatus = valid ? "Unsaved — press Apply" : "Invalid amount — try 750M or 1.2Q";
        });
        addPageWidget(threshold);
        var apply = addButton(contentX + contentWidth - 62, top + 48, 62, 24,
            Component.literal("Apply"), ButtonStyle.PRIMARY, false, ignored -> {
                if (MerchantRestockLogic.parseAmount(thresholdDraft) == null) {
                    thresholdStatus = "Invalid amount — previous threshold kept";
                    return;
                }
                String previous = config.merchantNpcThreshold;
                config.merchantNpcThreshold = thresholdDraft.strip();
                if (config.save()) thresholdStatus = "Saved: " + config.merchantNpcThreshold;
                else { config.merchantNpcThreshold = previous; thresholdStatus = "Could not save — previous threshold kept"; }
            });
        apply.active = !merchantRestock.isActive();

        CropiumButton run = addButton(contentX, top + 255, half, 26,
            Component.literal(merchantRestock.isActive() ? "Workflow running" : "Run merchant workflow"),
            ButtonStyle.PRIMARY, false, ignored -> merchantRestock.startManual(minecraft));
        run.active = !merchantRestock.isActive();
        var plot = addButton(contentX + half + gap, top + 255, half, 26, Component.literal("Open /plot"),
            ButtonStyle.SECONDARY, false, ignored -> {
                if (plotScanner.openPlot(minecraft)) {
                    minecraft.gui.setScreen(null);
                }
            });
        plot.active = !merchantRestock.isActive();
        int third = (contentWidth - gap * 2) / 3;
        addToggle(contentX, top + 222, third, "Harvester",
            () -> config.merchantRestockHarvester,
            () -> config.merchantRestockHarvester = !config.merchantRestockHarvester);
        addToggle(contentX + third + gap, top + 222, third, "Mine",
            () -> config.merchantRestockMine,
            () -> config.merchantRestockMine = !config.merchantRestockMine);
        addToggle(contentX + (third + gap) * 2, top + 222,
            contentWidth - (third + gap) * 2, "Egg Hatcher",
            () -> config.merchantRestockEggHatcher,
            () -> config.merchantRestockEggHatcher = !config.merchantRestockEggHatcher);
        addToggle(contentX, top + 108, contentWidth, "Claim verified free top-row NPCs",
            () -> config.merchantBuyRares, () -> config.merchantBuyRares = !config.merchantBuyRares);
        addToggle(contentX, top + 135, contentWidth, "Always place purchased rares",
            () -> config.merchantAlwaysPlaceRares, () -> config.merchantAlwaysPlaceRares = !config.merchantAlwaysPlaceRares);
    }

    private void addSettingsControls() {
        int gap = 7;
        int sliderWidth = Math.max(80, (contentWidth - gap) / 2);
        int right = contentX + sliderWidth + gap;
        int top = contentTop();
        addSelectorButtons(contentX, top, contentWidth,
            () -> config.movementPreset = config.movementPreset.offset(-1),
            () -> config.movementPreset = config.movementPreset.offset(1));
        addToggle(contentX, top + 47, sliderWidth, "Mining health HUD", () -> config.showMiningHealth,
            () -> config.showMiningHealth = !config.showMiningHealth);
        addToggle(right, top + 47, sliderWidth, "Mouse menu", () -> config.mouseMenuShortcut,
            () -> config.mouseMenuShortcut = !config.mouseMenuShortcut);
        addButton(contentX, top + 76, sliderWidth, 25, Component.literal("Menu: " + mouseButtonLabel()),
            ButtonStyle.SECONDARY, false, ignored -> {
                config.mouseMenuButton = config.mouseMenuButton == 4 ? 2 : config.mouseMenuButton + 1;
                config.save(); rebuildWidgets();
            });
        addButton(right, top + 76, sliderWidth, 25,
            Component.literal("GUI scale: " + minecraft.options.guiScale().get()),
            ButtonStyle.SECONDARY, false, ignored -> {
                int scale = minecraft.options.guiScale().get();
                minecraft.options.guiScale().set(scale >= 4 ? 0 : scale + 1);
                minecraft.options.save(); minecraft.resizeGui();
            });
        addButton(contentX, top + 132, contentWidth, 27,
            Component.literal(config.advancedSettings ? "Hide advanced settings" : "Show advanced settings"),
            ButtonStyle.SECONDARY, false, ignored -> {
                config.advancedSettings = !config.advancedSettings; config.save(); rebuildWidgets();
            });
        addButton(contentX, top + 172, contentWidth, 27, Component.literal("Draw custom routes"),
            ButtonStyle.SECONDARY, false, ignored -> { page = Page.ROUTES; rememberedPage = page; rebuildWidgets(); });
        if (!config.advancedSettings) return;
        top += 209;
        addSlider(contentX, top, sliderWidth, "Look pitch", 55.0, 88.0, 1.0,
            () -> config.lookDownPitch, value -> config.lookDownPitch = (float)value, "%.0f°");
        addSlider(right, top, sliderWidth, "Path variation", 0.0, 25.0, 0.5,
            () -> config.headingJitterDegrees, value -> config.headingJitterDegrees = (float)value, "%.1f°");
        addSlider(contentX, top + 29, sliderWidth, "Turn speed", 16.0, 48.0, 1.0,
            () -> config.turnDurationTicks, value -> config.turnDurationTicks = (int)Math.round(value), "%.0f ticks");
        addSlider(right, top + 29, sliderWidth, "Look-ahead", 4.0, 18.0, 0.5,
            () -> config.obstacleLookAhead, value -> config.obstacleLookAhead = (float)value, "%.1f blocks");
        addSlider(contentX, top + 58, sliderWidth, "Wall clearance", 0.10, 1.50, 0.05,
            () -> config.obstacleClearance, value -> config.obstacleClearance = (float)value, "%.2f blocks");
        addSlider(right, top + 58, sliderWidth, "Minimum BPS", 0.05, 20.0, 0.05,
            () -> config.minimumBps, value -> config.minimumBps = (float)value, "%.2f");
        addToggle(contentX, top + 88, sliderWidth, "Natural movement", () -> config.naturalMovement,
            () -> config.naturalMovement = !config.naturalMovement);
        addToggle(right, top + 88, sliderWidth, "Obstacle avoidance", () -> config.obstacleAvoidance,
            () -> config.obstacleAvoidance = !config.obstacleAvoidance);
        addToggle(contentX, top + 113, sliderWidth, "World overlay", () -> config.showWorldOverlay,
            () -> config.showWorldOverlay = !config.showWorldOverlay);
        addToggle(right, top + 113, sliderWidth, "Detached camera", () -> config.thirdPersonCamera,
            () -> config.thirdPersonCamera = !config.thirdPersonCamera);
    }

    private void addModuleButton(CropiumModule module, int y) {
        CropiumButton button = addButton(contentX + contentWidth - 75, y, 68, 24,
            Component.literal(modulePaused(module) ? "RESUME" : module.isActive() ? "STOP" : "START"),
            module.isActive() && !modulePaused(module) ? ButtonStyle.DANGER : ButtonStyle.PRIMARY, false, ignored -> {
                if (module.isActive() && !modulePaused(module)) module.stop(minecraft);
                else CropPilotClient.preparedStart(module);
                if (minecraft.gui.screen() == this) {
                    rebuildWidgets();
                }
            });
        // Merchant owns movement/inventory until its interrupted macro resumes.
        button.active = !merchantRestock.isActive();
    }

    private boolean modulePaused(CropiumModule module) {
        return module == controller && controller.isPaused() || module == mine && mine.isPaused();
    }

    private String mouseButtonLabel() {
        return config.mouseMenuButton == 2 ? "Middle click" : "Mouse " + (config.mouseMenuButton + 1);
    }

    private void addQuickControls() {
        int top = contentTop(), gap = 7, third = (contentWidth - 2 * gap) / 3;
        int i = 0;
        for (CropiumModule module : modules) {
            var start = addButton(contentX + i++ * (third + gap), top, third, 32,
                Component.literal(module.id().equals("harvest") ? "Farm" : module.id().equals("mine") ? "Mine" : "Eggs"),
                ButtonStyle.PRIMARY, false, ignored -> CropPilotClient.preparedStart(module));
            start.active = !merchantRestock.isActive() && (!module.isActive() || modulePaused(module));
        }
        var pause = addButton(contentX, top + 40, third, 30,
            Component.literal(mine.isPaused() || controller.isPaused() ? "Resume" : "Pause"),
            ButtonStyle.SECONDARY, false, ignored -> { CropPilotClient.pauseActive(); rebuildWidgets(); });
        pause.active = !merchantRestock.isActive() && (mine.canPause() || controller.isActive());
        addButton(contentX + third + gap, top + 40, third, 30, Component.literal("Stop all"),
            ButtonStyle.DANGER, false, ignored -> { CropPilotClient.stopAll(); rebuildWidgets(); });
        var recover = addButton(contentX + (third + gap) * 2, top + 40, third, 30, Component.literal("Recover"),
            ButtonStyle.SECONDARY, false, ignored -> CropPilotClient.recoverActive());
        recover.active = !merchantRestock.isActive() && (mine.isActive() || controller.isActive());
    }

    private boolean mapEditable() {
        return !merchantRestock.isActive() && !mine.isRunning() && !controller.isRunning()
            && modules.stream().noneMatch(module -> module.id().equals("egg-hatcher") && module.isActive());
    }

    private List<MotionMath.Vec2> mapBounds() { return mapMine ? mine.coverageBounds() : controller.coverageBounds(); }

    private String traceMapKey() { return mapMine ? mine.routeMapKey() : controller.routeMapKey(); }

    private List<TracedRoute> savedTraces() {
        return config.tracedRoutes.stream().filter(r -> r.matches(FieldProfileStore.worldKey(minecraft),
            mapMine ? "mine" : "harvest", traceMapKey())).toList();
    }

    private void newTrace() {
        traceStroke.clear(); traceId = null;
        traceInputProblem = null;
        traceWorld = FieldProfileStore.worldKey(minecraft); traceMap = traceMapKey();
        traceName = "Phase " + (savedTraces().size() + 1);
        traceShape = new TracedRoute.Shape(List.of(), "Draw a line on the map");
        traceStatus = "Hold left mouse to draw; release to preview smoothing";
    }

    private void updateTracePreview() {
        traceShape = TracedRoute.smooth(traceStroke);
        String problem = traceInputProblem == null ? traceShape.problem() : traceInputProblem;
        if (problem == null) problem = mapMine ? mine.tracedRouteProblem(minecraft, traceShape.points())
            : controller.tracedRouteProblem(minecraft, traceShape.points());
        if (problem != null) traceShape = new TracedRoute.Shape(traceShape.points(), problem);
        traceStatus = problem == null ? "Map check passed — live flight / join checked when running" : problem;
    }

    private void selectTrace(int direction) {
        List<TracedRoute> saved = savedTraces();
        if (saved.isEmpty()) { newTrace(); return; }
        int index = -1;
        for (int i = 0; i < saved.size(); i++) if (saved.get(i).id().equals(traceId)) index = i;
        var route = saved.get(Math.floorMod(index + direction, saved.size()));
        traceId = route.id(); traceName = route.name();
        traceInputProblem = null;
        traceWorld = route.world(); traceMap = route.map();
        traceStroke.clear(); traceStroke.addAll(route.stroke()); updateTracePreview();
    }

    private void saveTrace() {
        if (!mapEditable()) { traceStatus = "Pause or stop before saving routes"; return; }
        if (!java.util.Objects.equals(traceWorld, FieldProfileStore.worldKey(minecraft))
            || !java.util.Objects.equals(traceMap, traceMapKey())) { traceStatus = "Map changed — draw a new route here"; return; }
        updateTracePreview();
        if (!traceShape.valid()) return;
        var route = new TracedRoute(traceId == null ? java.util.UUID.randomUUID().toString() : traceId,
            traceName.strip(), traceWorld, mapMine ? "mine" : "harvest", traceMap, List.copyOf(traceStroke));
        if (!route.valid()) { traceStatus = "Enter a route name (up to 40 characters)"; return; }
        var previous = new ArrayList<>(config.tracedRoutes);
        config.tracedRoutes.removeIf(r -> r.id().equals(route.id()));
        if (config.tracedRoutes.size() >= TracedRoute.MAX_ROUTES) { config.tracedRoutes = previous; traceStatus = "Maximum 32 saved routes"; return; }
        config.tracedRoutes.add(route);
        if (!config.save()) { config.tracedRoutes = previous; traceStatus = "Could not save routes"; return; }
        traceId = route.id(); traceStatus = "Saved " + route.name() + " — enable drawn phases below to use it";
        rebuildWidgets();
    }

    private void addRouteControls() {
        int top = contentTop(), half = (contentWidth - 7) / 2, third = (contentWidth - 14) / 3;
        addButton(contentX, top, half, 26, Component.literal("Farm"), ButtonStyle.SEGMENT, !mapMine, ignored -> {
            mapMine = false; draftZone = null; newTrace(); rebuildWidgets();
        });
        addButton(contentX + half + 7, top, half, 26, Component.literal("Mine"), ButtonStyle.SEGMENT, mapMine, ignored -> {
            mapMine = true; draftZone = null; newTrace(); rebuildWidgets();
        });
        addPageWidget(new CoverageMap(contentX, top + 36, contentWidth, 225, true));
        EditBox name = new EditBox(font, contentX, top + 328, contentWidth, 24, Component.literal("Route name"));
        name.setMaxLength(40); name.setValue(traceName); name.setResponder(value -> traceName = value);
        addPageWidget(name);
        addButton(contentX, top + 360, third, 26, Component.literal(traceId == null ? "Save" : "Replace"),
            ButtonStyle.PRIMARY, false, ignored -> saveTrace()).active = mapEditable();
        addButton(contentX + third + 7, top + 360, third, 26, Component.literal("New"),
            ButtonStyle.SECONDARY, false, ignored -> { newTrace(); rebuildWidgets(); });
        addButton(contentX + 2 * (third + 7), top + 360, contentWidth - 2 * (third + 7), 26, Component.literal("Delete"),
            ButtonStyle.DANGER, false, ignored -> {
                if (!mapEditable() || traceId == null) return;
                var previous = new ArrayList<>(config.tracedRoutes);
                config.tracedRoutes.removeIf(r -> r.id().equals(traceId));
                if (!config.save()) { config.tracedRoutes = previous; traceStatus = "Could not save deletion"; return; }
                newTrace(); traceStatus = "Selected route deleted"; rebuildWidgets();
            }).active = mapEditable() && traceId != null;
        addSelectorButtons(contentX, top + 394, contentWidth,
            () -> selectTrace(-1), () -> selectTrace(1));
        addToggle(contentX, top + 442, contentWidth, "Use drawn phases for " + (mapMine ? "mine" : "farm"),
            () -> mapMine ? config.customMineRoutes : config.customFarmRoutes,
            () -> { if (mapEditable()) { if (mapMine) config.customMineRoutes = !config.customMineRoutes;
                else config.customFarmRoutes = !config.customFarmRoutes; } });
        addSlider(contentX, top + 473, contentWidth, "Phase duration", 30, 180, 5,
            () -> config.customRouteSeconds, value -> config.customRouteSeconds = (int)value, "~%.0fs");
        addButton(contentX, top + 541, contentWidth, 24, Component.literal("Refresh map / scan"),
            ButtonStyle.SECONDARY, false, ignored -> {
                if (!mapEditable()) return;
                if (mapMine) mine.previewMine(minecraft); else controller.scanField(minecraft);
                updateTracePreview();
            }).active = mapEditable();
    }

    private void drawRoutes(GuiGraphicsExtractor graphics) {
        int top = contentTop();
        graphics.text(font, font.plainSubstrByWidth("Gray: drawn line   Cyan: smoothed   Red: unsafe", contentWidth), contentX, top + 269, MUTED, false);
        graphics.text(font, "CLOSED LOOP", contentX, top + 286, ACCENT_BRIGHT, false);
        graphics.text(font, font.plainSubstrByWidth("Release to connect straight back to the start.", contentWidth), contentX, top + 299, MUTED, false);
        graphics.text(font, "ROUTE NAME", contentX, top + 316, MUTED, false);
        drawSelector(graphics, contentX, top + 394, contentWidth, "SAVED PHASES: " + savedTraces().size(),
            traceId == null ? "New drawing" : traceName);
        graphics.text(font, font.plainSubstrByWidth(traceStatus, contentWidth), contentX, top + 508, traceShape.valid() ? SUCCESS : MUTED, false);
        graphics.text(font, font.plainSubstrByWidth("Random ±25% timing; closing lines and joins must be safe.", contentWidth), contentX, top + 524, DIM, false);
    }

    private void addCoverageControls() {
        int top = contentTop(), half = (contentWidth - 7) / 2;
        addButton(contentX, top, half, 26, Component.literal("Farm"), ButtonStyle.SEGMENT, !mapMine, ignored -> {
            mapMine = false; draftZone = null; rebuildWidgets();
        });
        addButton(contentX + half + 7, top, half, 26, Component.literal("Mine"), ButtonStyle.SEGMENT, mapMine, ignored -> {
            mapMine = true; draftZone = null; mine.previewMine(minecraft); rebuildWidgets();
        });
        addPageWidget(new CoverageMap(contentX, top + 37, contentWidth, 230));
        var apply = addButton(contentX, top + 308, half, 28, Component.literal("Apply exclusion"),
            ButtonStyle.PRIMARY, false, ignored -> {
                if (!mapEditable() || draftZone == null) { mapStatus = "Pause or stop and draw an exclusion first"; return; }
                boolean valid = mapMine ? mine.validateExclusion(minecraft, draftZone) : controller.validateExclusion(minecraft, draftZone);
                if (!valid || config.exclusions.size() >= 64) {
                    mapStatus = "Rejected: preserve entry, player clearance and a safe route"; return;
                }
                if (!config.exclusions.contains(draftZone)) config.exclusions.add(draftZone);
                exclusionsChanged(); draftZone = null; mapStatus = "Saved: route will respect the excluded blocks";
                rebuildWidgets();
            });
        apply.active = mapEditable();
        var remove = addButton(contentX + half + 7, top + 308, half, 28, Component.literal("Remove last exclusion"),
            ButtonStyle.SECONDARY, false, ignored -> {
                if (!mapEditable()) return;
                String world = FieldProfileStore.worldKey(minecraft), module = mapMine ? "mine" : "harvest";
                for (int index = config.exclusions.size() - 1; index >= 0; index--) {
                    var zone = config.exclusions.get(index);
                    if (zone.worldKey().equals(world) && zone.moduleId().equals(module)) {
                        config.exclusions.remove(index); exclusionsChanged(); break;
                    }
                }
                draftZone = null; mapStatus = "Last saved exclusion removed"; rebuildWidgets();
            });
        remove.active = mapEditable();
    }

    private void exclusionsChanged() {
        config.save();
        controller.exclusionsChanged();
        mine.exclusionsChanged();
    }

    private void addWorkflowControls() {
        var run = addButton(contentX, contentTop(), (contentWidth - 7) / 2, 28,
            Component.literal("Run merchant"), ButtonStyle.PRIMARY, false, ignored -> merchantRestock.startManual(minecraft));
        run.active = !merchantRestock.isActive();
        var cancel = addButton(contentX + (contentWidth + 7) / 2, contentTop(), (contentWidth - 7) / 2, 28,
            Component.literal("Cancel safely"), ButtonStyle.DANGER, false, ignored -> {
                merchantRestock.cancelSafely(minecraft); rebuildWidgets();
            });
        cancel.active = merchantRestock.isActive();
    }

    private CropiumModule module(String id) {
        return modules.stream().filter(module -> module.id().equals(id)).findFirst().orElse(null);
    }

    private void addToggle(int x, int y, int width, String label,
                           java.util.function.BooleanSupplier value, Runnable change) {
        CropiumButton toggle = addButton(x, y, width, 21, toggleLabel(label, value.getAsBoolean()),
            ButtonStyle.TOGGLE, value.getAsBoolean(), button -> {
                change.run();
                config.save();
                button.setMessage(toggleLabel(label, value.getAsBoolean()));
                ((CropiumButton)button).selected = value.getAsBoolean();
            });
        toggle.selected = value.getAsBoolean();
    }

    private void addSlider(int x, int y, int width, String label, double minimum, double maximum,
                           double step, DoubleSupplier value, DoubleConsumer change, String format) {
        addPageWidget(new ConfigSlider(x, y, width, label, minimum, maximum, step,
            value.getAsDouble(), change, format));
    }

    private static Component toggleLabel(String label, boolean enabled) {
        return Component.literal(label + "  " + (enabled ? "ON" : "OFF"));
    }

    private CropiumButton addButton(int x, int y, int width, int height, Component label,
                                    ButtonStyle style, boolean selected, Button.OnPress action) {
        CropiumButton button = new CropiumButton(x, y, width, height, label, action, style, selected);
        return buildingPage ? addPageWidget(button) : addRenderableWidget(button);
    }

    private <T extends AbstractWidget> T addPageWidget(T widget) {
        return pageLayout.addChild(widget, pageLayout.newChildLayoutSettings().align(0, 0)
            .paddingLeft(widget.getX() - contentX).paddingTop(widget.getY() - (panelY + 47)));
    }

    private int contentTop() {
        return buildingPage || pageCanvas == null ? panelY + 47 : pageCanvas.getY();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, BACKDROP_TOP, BACKDROP_BOTTOM);
        graphics.fill(panelX - 4, panelY - 4, panelX + panelWidth + 4, panelY + panelHeight + 4, 0x52000000);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, SHELL);
        graphics.fill(panelX, panelY, panelX + railWidth, panelY + panelHeight, RAIL);
        graphics.fill(panelX + railWidth, panelY, panelX + railWidth + 1, panelY + panelHeight, BORDER);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 2, ACCENT);
        graphics.fill(panelX, panelY + 2, panelX + 2, panelY + panelHeight, 0xFF5941A4);

        graphics.fill(panelX + 12, panelY + 11, panelX + 29, panelY + 28, ACCENT);
        graphics.fill(panelX + 16, panelY + 15, panelX + 25, panelY + 24, RAIL);
        graphics.text(font, "C", panelX + 18, panelY + 15, ACCENT_BRIGHT, true);
        graphics.text(font, "CROPIUM", panelX + 36, panelY + 11, TEXT, true);
        graphics.text(font, railWidth < 132 ? "MACRO SUITE" : "AUTOMATION SUITE",
            panelX + 36, panelY + 22, DIM, false);

        graphics.text(font, page.label.toUpperCase(Locale.ROOT), contentX, panelY + 10, TEXT, true);
        String startStatus = CropPilotClient.preparationStatus();
        boolean blocked = startStatus.startsWith("Start blocked:");
        graphics.text(font, font.plainSubstrByWidth(blocked ? startStatus : page.subtitle, contentWidth),
            contentX, panelY + 24, blocked ? DANGER : MUTED, false);
        graphics.fill(contentX, panelY + 39, panelX + panelWidth - 10, panelY + 40, BORDER);
        graphics.fill(panelX + 10, panelY + 39, panelX + railWidth - 10, panelY + 40, BORDER);
        if (panelHeight > 230) {
            graphics.text(font, "FABRIC 26.2", panelX + 13, panelY + panelHeight - 40, DIM, false);
        }

    }

    private void drawPage(GuiGraphicsExtractor graphics) {
        switch (page) {
            case DASHBOARD -> drawDashboard(graphics);
            case HARVESTER -> drawHarvester(graphics);
            case MINE -> drawMine(graphics);
            case EGG_HATCHER -> drawEggHatcher(graphics);
            case MERCHANT -> drawMerchant(graphics);
            case PLOT_SCANNER -> drawPlotScanner(graphics);
            case SETTINGS -> drawSettings(graphics);
            case STATISTICS -> drawStatistics(graphics);
            case CONTROLS -> drawQuickControls(graphics);
            case COVERAGE -> drawCoverage(graphics);
            case ROUTES -> drawRoutes(graphics);
            case WORKFLOW -> drawWorkflow(graphics);
        }
    }

    private void drawDashboard(GuiGraphicsExtractor graphics) {
        int metricWidth = (contentWidth - 7) / 2;
        String active = merchantRestock.isActive() ? "Merchant workflow" : modules.stream().filter(CropiumModule::isActive)
            .map(module -> module.name().getString()).findFirst().orElse("Idle");
        drawMetric(graphics, contentX, contentTop(), metricWidth, "ACTIVE MODULE", active, ACCENT_BRIGHT);
        drawMetric(graphics, contentX + metricWidth + 7, contentTop(), metricWidth,
            mine.isActive() ? "MINE FOSSILS / HOUR" : "FARM SHINIES / HOUR",
            String.format(Locale.ROOT, "%,.1f", mine.isActive() ? mine.shiniesPerHour() : controller.shiniesPerHour()), CYAN);
        int y = contentTop() + 34;
        for (CropiumModule module : modules) {
            drawModuleCard(graphics, module, y);
            y += 51;
        }
        int intermediaryY = contentTop() + 34 + modules.size() * 51;
        graphics.fill(contentX, intermediaryY, contentX + contentWidth, intermediaryY + 1, BORDER);
        graphics.text(font, "SHARED WORKFLOW", contentX, intermediaryY + 8, MUTED, false);
        graphics.text(font, "Buy + salvage", contentX + 2, intermediaryY + 25, TEXT, false);
    }

    private void drawHarvester(GuiGraphicsExtractor graphics) {
        CropiumModule module = module("harvest");
        if (module != null) {
            drawModuleCard(graphics, module, contentTop());
        }
        int top = contentTop() + 99;
        graphics.fill(contentX, top, contentX + contentWidth, top + 34, SURFACE_SOFT);
        graphics.fill(contentX, top, contentX + 3, top + 34, CYAN);
        graphics.text(font, "LIVE GLOW ROUTING", contentX + 10, top + 6, MUTED, false);
        graphics.text(font, font.plainSubstrByWidth(controller.glowRoutingStatus(), contentWidth - 20),
            contentX + 10, top + 19, CYAN, false);
        drawSessionMetrics(graphics, contentTop() + 142, false, false);
        drawLines(graphics, CropPilotClient.preflightLines("harvest"), contentTop() + 216, MUTED);
    }

    private void drawMine(GuiGraphicsExtractor graphics) {
        CropiumModule module = module("mine");
        if (module != null) {
            drawModuleCard(graphics, module, contentTop());
        }
        drawSelector(graphics, contentX, contentTop() + 126, contentWidth, "ROUTE STYLE", config.mineRouteMode.label);
        graphics.text(font, font.plainSubstrByWidth(mine.patternStatus(), contentWidth),
            contentX, contentTop() + 254, MUTED, false);
        int top = contentTop() + 267;
        graphics.fill(contentX, top, contentX + contentWidth, top + 34, SURFACE_SOFT);
        graphics.fill(contentX, top, contentX + 3, top + 34, CYAN);
        graphics.text(font, "LIVE MINE ROUTING", contentX + 10, top + 6, MUTED, false);
        graphics.text(font, font.plainSubstrByWidth(mine.decisionStatus(), contentWidth - 20),
            contentX + 10, top + 19, CYAN, false);
        drawMineMap(graphics, contentTop() + 310);
        graphics.text(font, font.plainSubstrByWidth("Cyan: route | Green: prediction | Red: obstacle", contentWidth),
            contentX, contentTop() + 505, MUTED, false);
        drawSessionMetrics(graphics, contentTop() + 521, true, false);
        graphics.text(font, font.plainSubstrByWidth(mine.outcomeStatus(), contentWidth),
            contentX, contentTop() + 589, CYAN, false);
        graphics.text(font, font.plainSubstrByWidth(mine.responseStatus(), contentWidth),
            contentX, contentTop() + 602, MUTED, false);
        graphics.text(font, "RECENT DECISIONS / RECOVERIES", contentX, contentTop() + 623, MUTED, false);
        int row = contentTop() + 638;
        for (String event : mine.recoveryHistory().stream().limit(4).toList()) {
            graphics.text(font, font.plainSubstrByWidth(event, contentWidth), contentX, row, TEXT, false);
            row += 13;
        }
    }

    private void drawMineMap(GuiGraphicsExtractor graphics, int y) {
        int size = Math.min(190, contentWidth);
        double scale = size / 83.0;
        for (var run : mineMapRaster.update(MineLayout.MIN_X - 1, MineLayout.MAX_X + 1,
            MineLayout.MIN_Z - 1, MineLayout.MAX_Z + 1, System.nanoTime(), mine::mapColor)) {
            int left = contentX + (int)((run.x() - MineLayout.MIN_X + 1) * scale);
            int top = y + (int)((run.z() - MineLayout.MIN_Z + 1) * scale);
            graphics.fill(left, top, contentX + (int)Math.ceil((run.x() - MineLayout.MIN_X + 1 + run.length()) * scale),
                y + (int)Math.ceil((run.z() - MineLayout.MIN_Z + 2) * scale), run.color());
        }
        drawMinePath(graphics, y, size, mine.routePreview(), true, CYAN);
        drawMinePath(graphics, y, size, mine.forecastPreview(), false, SUCCESS);
        for (var block : mine.fossilPreview()) {
            int px = contentX + (int)((block.getX() - MineLayout.MIN_X + 1.5) * scale);
            int py = y + (int)((block.getZ() - MineLayout.MIN_Z + 1.5) * scale);
            graphics.fill(px - 1, py - 1, px + 2, py + 2, mine.ignored(block) ? DIM : 0xFFFFD166);
        }
        if (minecraft.player != null && MineLayout.interior(minecraft.player.getBlockX(), MineLayout.FLOOR_Y,
            minecraft.player.getBlockZ())) {
            int px = contentX + (int)((minecraft.player.getX() - MineLayout.MIN_X + 1) * scale);
            int py = y + (int)((minecraft.player.getZ() - MineLayout.MIN_Z + 1) * scale);
            graphics.fill(px - 2, py - 2, px + 3, py + 3, TEXT);
        }
        var rejected = mine.rejectedPoint();
        if (rejected != null) {
            int px = contentX + (int)Math.clamp((rejected.x() - MineLayout.MIN_X + 1) * scale, 1, size - 2);
            int py = y + (int)Math.clamp((rejected.z() - MineLayout.MIN_Z + 1) * scale, 1, size - 2);
            graphics.fill(px - 2, py, px + 3, py + 1, DANGER);
            graphics.fill(px, py - 2, px + 1, py + 3, DANGER);
        }
        if (contentWidth > size + 130) {
            graphics.text(font, "LIVE MAP", contentX + size + 12, y + 5, TEXT, true);
            graphics.text(font, "Gray: unknown", contentX + size + 12, y + 23, MUTED, false);
            graphics.text(font, "Amber: edge buffer", contentX + size + 12, y + 38, MUTED, false);
            graphics.text(font, "Gold: fossils", contentX + size + 12, y + 53, MUTED, false);
            graphics.text(font, "White: player", contentX + size + 12, y + 68, MUTED, false);
            graphics.text(font, "Red cross: rejected", contentX + size + 12, y + 83, MUTED, false);
        }
    }

    private void drawMinePath(GuiGraphicsExtractor graphics, int y, int size,
                              List<MotionMath.Vec2> points, boolean closed, int color) {
        int segments = closed ? points.size() : points.size() - 1;
        for (int i = 0; i < segments; i++) {
            var a = points.get(i);
            var b = points.get((i + 1) % points.size());
            int x0 = (int)Math.clamp((a.x() - MineLayout.MIN_X + 1) * size / 83, 0, size - 1);
            int z0 = (int)Math.clamp((a.z() - MineLayout.MIN_Z + 1) * size / 83, 0, size - 1);
            int x1 = (int)Math.clamp((b.x() - MineLayout.MIN_X + 1) * size / 83, 0, size - 1);
            int z1 = (int)Math.clamp((b.z() - MineLayout.MIN_Z + 1) * size / 83, 0, size - 1);
            int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0)));
            for (int step = 0; step <= steps; step++) {
                int px = contentX + x0 + (x1 - x0) * step / steps;
                int py = y + z0 + (z1 - z0) * step / steps;
                graphics.fill(px, py, px + 1, py + 1, color);
            }
        }
    }

    private void drawEggHatcher(GuiGraphicsExtractor graphics) {
        CropiumModule module = module("egg-hatcher");
        if (module != null) {
            drawModuleCard(graphics, module, contentTop());
        }
        int gap = 8;
        int selectorWidth = (contentWidth - gap) / 2;
        int y = contentTop() + 45;
        drawSelector(graphics, contentX, y, selectorWidth, "EGG TIER", config.petEgg.label());
        drawSelector(graphics, contentX + selectorWidth + gap, y, selectorWidth,
            "MAX FORGE TIER", config.petMaxForgeTier.label());
        String summary = config.petEgg.label() + " egg  •  " + config.petMaxForgeTier.label()
            + " max tier  •  " + config.petOpenAmount + " per open";
        graphics.text(font, summary, contentX, contentTop() + 124, ACCENT_BRIGHT, false);
    }

    private void drawPlotScanner(GuiGraphicsExtractor graphics) {
        int top = contentTop();
        graphics.text(font, plotScanner.boundsLabel(), contentX, top + 28, TEXT, false);
        String scanLine = plotScanner.bounds() == null
            ? plotScanner.status()
            : plotScanner.openSlots() + " OPEN  •  " + plotScanner.occupiedSlots() + " OCCUPIED  •  "
                + (plotScanner.totalColumns() == 0 ? 0
                    : plotScanner.loadedColumns() * 100 / plotScanner.totalColumns()) + "% LOADED";
        graphics.text(font, scanLine, contentX, top + 40, MUTED, false);
        graphics.text(font, "ENTITY  " + plotScanner.entitySummary(), contentX, top + 52, CYAN, false);

        int mapX = contentX;
        int mapY = top + 66;
        int mapWidth = contentWidth;
        int mapHeight = pageHeight() - 72;
        graphics.fill(mapX, mapY, mapX + mapWidth, mapY + mapHeight, BORDER);
        graphics.fill(mapX + 1, mapY + 1, mapX + mapWidth - 1, mapY + mapHeight - 1, SURFACE_SOFT);

        PlotScannerController.PlotBounds bounds = plotScanner.bounds();
        if (bounds == null) {
            graphics.text(font, "Look at Corner A, open Cropium, then press Set A.",
                mapX + 9, mapY + 9, DIM, false);
            graphics.text(font, "Repeat at the opposite corner with Set B.",
                mapX + 9, mapY + 21, DIM, false);
            return;
        }

        int innerX = mapX + 4;
        int innerY = mapY + 4;
        int innerWidth = Math.max(1, mapWidth - 8);
        int innerHeight = Math.max(1, mapHeight - 8);
        double scaleX = innerWidth / (double)Math.max(1, bounds.width());
        double scaleZ = innerHeight / (double)Math.max(1, bounds.depth());
        int markerSize = Math.clamp((int)Math.floor(Math.min(scaleX, scaleZ)), 2, 5);
        for (PlotScannerController.PlotSlot slot : plotScanner.slots()) {
            int x = innerX + (int)Math.floor((slot.position().getX() - bounds.minX() + 0.5) * scaleX);
            int y = innerY + (int)Math.floor((slot.position().getZ() - bounds.minZ() + 0.5) * scaleZ);
            int color = slot.occupied() ? DANGER : SUCCESS;
            graphics.fill(x - markerSize / 2, y - markerSize / 2,
                x - markerSize / 2 + markerSize, y - markerSize / 2 + markerSize, color);
        }
        graphics.text(font, "GREEN open  •  RED occupied by a visible figure entity",
            mapX + 7, mapY + mapHeight - 11, DIM, false);
    }

    private void drawMerchant(GuiGraphicsExtractor graphics) {
        int top = contentTop();
        graphics.text(font, "MINIMUM GENERATES", contentX, top + 31, TEXT, false);
        graphics.text(font, font.plainSubstrByWidth(thresholdStatus, contentWidth), contentX, top + 83, MUTED, false);
        drawLines(graphics, List.of("Rare NPC claims are free; no budget is needed.",
            "Explicit paid offers are skipped. Rares stay protected."), top + 173, MUTED);
        int statusY = top + 295;
        graphics.fill(contentX, statusY, contentX + contentWidth, statusY + 31, SURFACE_SOFT);
        graphics.fill(contentX, statusY, contentX + 3, statusY + 31,
            merchantRestock.isActive() ? SUCCESS : ACCENT);
        graphics.text(font, "MERCHANT STATUS", contentX + 10, statusY + 4, MUTED, false);
        String queue = merchantRestock.queuedPlacements() == 0 ? ""
            : "  •  " + merchantRestock.queuedPlacements() + " queued";
        graphics.text(font, font.plainSubstrByWidth(merchantRestock.status() + queue, contentWidth - 20), contentX + 10, statusY + 17,
            merchantRestock.isActive() ? SUCCESS : TEXT, false);
        drawLines(graphics, List.of(merchantRestock.rareStatus(), "Open Workflow for the live timeline and placement queue."), top + 336, MUTED);
    }

    private void drawSettings(GuiGraphicsExtractor graphics) {
        drawSelector(graphics, contentX, contentTop(), contentWidth, "MOVEMENT PRESET", config.movementPreset.label);
        drawLines(graphics, List.of("GUI scale changes Minecraft menus and text, not just Cropium.",
            "0 = automatic. Press " + mouseButtonLabel() + " in-world to open Cropium."), contentTop() + 107, MUTED);
        if (!config.advancedSettings) drawLines(graphics,
            List.of("Safe clearance remains enforced in every movement preset.", "Advanced controls are saved when you close the menu."), contentTop() + 210, MUTED);
    }

    private void drawLines(GuiGraphicsExtractor graphics, List<String> lines, int y, int color) {
        for (String line : lines) {
            graphics.text(font, font.plainSubstrByWidth(line, contentWidth), contentX, y, color, false);
            y += 14;
        }
    }

    private void drawQuickControls(GuiGraphicsExtractor graphics) {
        int top = contentTop(), half = (contentWidth - 7) / 2;
        graphics.text(font, font.plainSubstrByWidth(CropPilotClient.preparationStatus(), contentWidth), contentX, top + 80, CYAN, false);
        drawMetric(graphics, contentX, top + 102, contentWidth, "HOTBAR LOCK", "Slot 1 • farm, mine and eggs", SUCCESS);
        graphics.text(font, font.plainSubstrByWidth("Temporarily released for merchant transfers and NPC placement.", contentWidth),
            contentX, top + 137, MUTED, false);
        graphics.text(font, "MINING HEALTH", contentX, top + 155, ACCENT_BRIGHT, true);
        drawLines(graphics, CropPilotClient.healthLines(), top + 170, TEXT);
        graphics.text(font, "PREPARED START CHECKS", contentX, top + 267, ACCENT_BRIGHT, true);
        drawLines(graphics, CropPilotClient.preflightLines(mine.isActive() || mapMine ? "mine" : "harvest"), top + 282, MUTED);
    }

    private void drawCoverage(GuiGraphicsExtractor graphics) {
        drawLines(graphics, List.of("Green: recent  •  Cyan: ice / route  •  Pink: glow / fossil",
            "Orange: missed  •  Gray: ignored  •  Purple: excluded  •  White: you"), contentTop() + 275, MUTED);
        drawLines(graphics, List.of(mapStatus, "Drag to mark blocks; saved exclusions are scoped to this world + macro."),
            contentTop() + 346, CYAN);
    }

    private void drawWorkflow(GuiGraphicsExtractor graphics) {
        int top = contentTop();
        drawLines(graphics, List.of(merchantRestock.status(), merchantRestock.rareStatus()), top + 40, CYAN);
        int step = 0, y = top + 80;
        for (String label : merchantRestock.workflowSteps()) {
            boolean current = step == merchantRestock.workflowIndex();
            int color = current ? CYAN : step < merchantRestock.workflowIndex() ? SUCCESS : MUTED;
            graphics.fill(contentX + 3, y + 1, contentX + 9, y + 7, color);
            graphics.text(font, font.plainSubstrByWidth((step + 1) + ". " + label, contentWidth - 22), contentX + 18, y, color, false);
            step++; y += 20;
        }
        graphics.text(font, "PLACEMENT QUEUE", contentX, y + 7, ACCENT_BRIGHT, true);
        y += 23;
        for (String detail : workflowDetails()) {
            for (var line : font.split(Component.literal(detail), contentWidth)) {
                graphics.text(font, line, contentX, y, TEXT, false);
                y += 13;
            }
        }
    }

    private List<String> workflowDetails() {
        var details = new java.util.ArrayList<>(merchantRestock.queuePreview());
        if (details.isEmpty()) details.add("No pending NPC placements");
        details.add("");
        details.add("RARE OFFER DIAGNOSTICS");
        details.add(merchantRestock.rareStatus());
        details.add("WAIT / STOP REASON");
        details.add(merchantRestock.status());
        return details;
    }

    private final class CoverageMap extends AbstractWidget {
        private final MapRaster raster = new MapRaster();
        private final boolean tracing;
        private boolean dragging;
        private int startX, startZ;

        CoverageMap(int x, int y, int width, int height) {
            this(x, y, width, height, false);
        }

        CoverageMap(int x, int y, int width, int height, boolean tracing) {
            super(x, y, width, height, Component.literal(tracing ? "Route map. Hold left mouse to draw a line."
                : "Coverage map. Drag to mark excluded blocks."));
            this.tracing = tracing;
        }

        private TracedRoute.View view(List<MotionMath.Vec2> bounds) {
            return new TracedRoute.View(bounds.getFirst().x(), bounds.getFirst().z(),
                bounds.getLast().x() - bounds.getFirst().x() + 1, bounds.getLast().z() - bounds.getFirst().z() + 1,
                getX() + 4, getY() + 4, getWidth() - 8, getHeight() - 8);
        }

        private double scale(List<MotionMath.Vec2> bounds) {
            return view(bounds).scale();
        }

        private int mapX(double x, List<MotionMath.Vec2> bounds) {
            return (int)Math.round(view(bounds).screen(new MotionMath.Vec2(x, bounds.getFirst().z())).x());
        }

        private int mapZ(double z, List<MotionMath.Vec2> bounds) {
            return (int)Math.round(view(bounds).screen(new MotionMath.Vec2(bounds.getFirst().x(), z)).z());
        }

        private void updateDraft(MouseButtonEvent event, boolean begin) {
            List<MotionMath.Vec2> bounds = mapBounds();
            if (bounds.size() != 2 || !mapEditable()) return;
            var position = view(bounds).world(event.x(), event.y());
            if (tracing) {
                if (begin) {
                    if (!java.util.Objects.equals(traceWorld, FieldProfileStore.worldKey(minecraft))
                        || !java.util.Objects.equals(traceMap, traceMapKey())) traceId = null;
                    traceStroke.clear(); traceInputProblem = null;
                    traceWorld = FieldProfileStore.worldKey(minecraft); traceMap = traceMapKey();
                    traceShape = new TracedRoute.Shape(List.of(), "Release to preview");
                }
                if (!view(bounds).contains(event.x(), event.y())) {
                    traceInputProblem = "Stroke left the map — redraw inside the safe area";
                    dragging = false; updateTracePreview(); return;
                }
                if (traceStroke.isEmpty() || position.subtract(traceStroke.getLast()).length() >= 2) {
                    if (traceStroke.size() >= TracedRoute.MAX_POINTS) {
                        traceInputProblem = "Line too detailed — draw a shorter, simpler phase";
                        dragging = false; updateTracePreview(); return;
                    }
                    traceStroke.add(position);
                }
                traceStatus = "Drawing " + traceStroke.size() + " samples — release for the smoothed preview";
                return;
            }
            int x = Math.clamp((int)Math.floor(position.x()),
                (int)bounds.getFirst().x(), (int)bounds.getLast().x());
            int z = Math.clamp((int)Math.floor(position.z()),
                (int)bounds.getFirst().z(), (int)bounds.getLast().z());
            if (begin) { startX = x; startZ = z; }
            draftZone = new ExclusionZone(FieldProfileStore.worldKey(minecraft), mapMine ? "mine" : "harvest",
                Math.min(startX, x), Math.max(startX, x), Math.min(startZ, z), Math.max(startZ, z));
            mapStatus = "Draft: X " + draftZone.minX() + ".." + draftZone.maxX() + ", Z " + draftZone.minZ() + ".." + draftZone.maxZ();
        }

        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != 0 || !isMouseOver(event.x(), event.y()) || !mapEditable() || mapBounds().size() != 2) return false;
            if (!view(mapBounds()).contains(event.x(), event.y())) return false;
            dragging = true; updateDraft(event, true); return true;
        }

        @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            if (!dragging || event.button() != 0) return false;
            updateDraft(event, false); return true;
        }

        @Override public boolean mouseReleased(MouseButtonEvent event) {
            if (!dragging || event.button() != 0) return false;
            updateDraft(event, false); dragging = false;
            if (tracing) {
                // Drag samples are two blocks apart; the closing line must use
                // the actual release point, not the previous sampled position.
                if (traceInputProblem == null && mapEditable() && mapBounds().size() == 2 && !traceStroke.isEmpty()) {
                    var release = view(mapBounds()).world(event.x(), event.y());
                    if (traceStroke.size() == 1) traceStroke.add(release);
                    else traceStroke.set(traceStroke.size() - 1, release);
                }
                updateTracePreview();
            }
            return true;
        }

        private void zone(GuiGraphicsExtractor graphics, ExclusionZone zone, List<MotionMath.Vec2> bounds, int color) {
            int left = mapX(Math.max(zone.minX(), bounds.getFirst().x()), bounds);
            int top = mapZ(Math.max(zone.minZ(), bounds.getFirst().z()), bounds);
            int right = mapX(Math.min(zone.maxX() + 1, bounds.getLast().x() + 1), bounds);
            int bottom = mapZ(Math.min(zone.maxZ() + 1, bounds.getLast().z() + 1), bounds);
            if (left >= right || top >= bottom) return;
            graphics.fill(left, top, right, bottom, color);
            graphics.fill(left, top, right, top + 1, ACCENT_BRIGHT);
            graphics.fill(left, bottom - 1, right, bottom, ACCENT_BRIGHT);
        }

        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), SURFACE_SOFT);
            List<MotionMath.Vec2> bounds = mapBounds();
            if (bounds.size() != 2 || bounds.getLast().x() - bounds.getFirst().x() > 512
                || bounds.getLast().z() - bounds.getFirst().z() > 512) {
                graphics.text(font, "Join the saved world and preview / scan the map first.", getX() + 7, getY() + 9, MUTED, false); return;
            }
            for (var run : raster.update((int)bounds.getFirst().x(), (int)bounds.getLast().x(),
                (int)bounds.getFirst().z(), (int)bounds.getLast().z(), System.nanoTime(),
                mapMine ? mine::coverageColor : controller::coverageColor)) {
                int x = mapX(run.x(), bounds), z = mapZ(run.z(), bounds);
                graphics.fill(x, z, Math.max(x + 1, mapX(run.x() + run.length(), bounds)),
                    Math.max(z + 1, mapZ(run.z() + 1, bounds)), run.color());
            }
            if (!tracing) line(graphics, mapMine ? mine.coverageRoute() : controller.coverageRoute(), bounds, CYAN, true);
            String world = FieldProfileStore.worldKey(minecraft), module = mapMine ? "mine" : "harvest";
            for (ExclusionZone zone : config.exclusions) {
                if (zone.worldKey().equals(world) && zone.moduleId().equals(module)) zone(graphics, zone, bounds, 0x995F38A6);
            }
            if (!tracing && draftZone != null) zone(graphics, draftZone, bounds, 0xAA97702C);
            if (tracing) {
                line(graphics, traceStroke, bounds, MUTED, false);
                if (!dragging) line(graphics, traceShape.points(), bounds, traceShape.valid() ? CYAN : DANGER, true);
                if (!traceStroke.isEmpty()) {
                    int x = mapX(traceStroke.getFirst().x(), bounds), y = mapZ(traceStroke.getFirst().z(), bounds);
                    graphics.fill(x - 2, y - 2, x + 3, y + 3, ACCENT_BRIGHT);
                }
            }
            if (minecraft.player != null) {
                int x = mapX(minecraft.player.getX(), bounds), y = mapZ(minecraft.player.getZ(), bounds);
                if (x >= getX() && x < getRight() && y >= getY() && y < getBottom()) {
                    graphics.fill(x - 2, y - 2, x + 3, y + 3, TEXT);
                }
            }
        }

        private void line(GuiGraphicsExtractor graphics, List<MotionMath.Vec2> route,
                          List<MotionMath.Vec2> bounds, int color, boolean closed) {
            for (int i = 0; i < route.size() - (closed ? 0 : 1); i++) {
                var a = route.get(i); var b = route.get((i + 1) % route.size());
                int steps = Math.clamp((int)Math.ceil(a.subtract(b).length() * scale(bounds)), 1, 1024);
                for (int j = 0; j <= steps; j++) {
                    double fraction = j / (double)steps;
                    int x = mapX(a.x() + (b.x() - a.x()) * fraction, bounds);
                    int y = mapZ(a.z() + (b.z() - a.z()) * fraction, bounds);
                    if (x >= getX() && x < getRight() && y >= getY() && y < getBottom()) graphics.fill(x, y, x + 1, y + 1, color);
                }
            }
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private void drawModuleCard(GuiGraphicsExtractor graphics, CropiumModule module, int y) {
        graphics.fill(contentX, y, contentX + contentWidth, y + 43, SURFACE);
        graphics.fill(contentX, y, contentX + 3, y + 43, module.isActive() ? SUCCESS : DIM);
        int textWidth = Math.max(20, contentWidth - (page == Page.DASHBOARD ? 150 : 90));
        graphics.text(font, font.plainSubstrByWidth(module.name().getString(), textWidth), contentX + 10, y + 7, TEXT, true);
        graphics.text(font, font.plainSubstrByWidth(module.description().getString(), textWidth), contentX + 10, y + 20, MUTED, false);
        graphics.text(font, font.plainSubstrByWidth(module.status(), textWidth), contentX + 10, y + 32,
            module.isActive() ? SUCCESS : DIM, false);
    }

    private void drawSelector(GuiGraphicsExtractor graphics, int x, int y, int width, String label, String value) {
        graphics.fill(x, y, x + width, y + 40, SURFACE_SOFT);
        graphics.fill(x, y, x + width, y + 1, BORDER);
        graphics.text(font, font.plainSubstrByWidth(label, Math.max(0, width - 14)), x + 7, y + 5, MUTED, false);
        String shown = font.plainSubstrByWidth(value, Math.max(0, width - 60));
        int valueX = x + (width - font.width(shown)) / 2;
        graphics.text(font, shown, valueX, y + 24, ACCENT_BRIGHT, true);
    }

    private void drawMetric(GuiGraphicsExtractor graphics, int x, int y, int width,
                            String label, String value, int valueColor) {
        graphics.fill(x, y, x + width, y + 27, SURFACE_SOFT);
        graphics.text(font, font.plainSubstrByWidth(label, width - 16), x + 8, y + 4, MUTED, false);
        graphics.text(font, font.plainSubstrByWidth(value, width - 16), x + 8, y + 15, valueColor, true);
    }

    private void drawStatistics(GuiGraphicsExtractor graphics) {
        graphics.text(font, "HARVESTER", contentX, contentTop(), ACCENT_BRIGHT, true);
        drawSessionMetrics(graphics, contentTop() + 15, false, true);
        graphics.text(font, "MINE HARVESTER", contentX, contentTop() + 118, CYAN, true);
        drawSessionMetrics(graphics, contentTop() + 133, true, true);
        graphics.text(font, font.plainSubstrByWidth("Rates include utility pauses; new starts reset the session.", contentWidth),
            contentX, contentTop() + 233, DIM, false);
        graphics.text(font, font.plainSubstrByWidth("Shinies need a matching break; vanished glows don't count.", contentWidth),
            contentX, contentTop() + 245, DIM, false);
    }

    private void drawSessionMetrics(GuiGraphicsExtractor graphics, int y, boolean mining, boolean expanded) {
        int w = (contentWidth - 7) / 2;
        long total = mining ? mine.totalBlocksMined() : controller.totalBlocksMined();
        long shiny = mining ? mine.shinyHarvests() : controller.shinyHarvests();
        double bps = mining ? mine.blocksPerSecond() : controller.blocksPerSecond();
        double shinyRate = mining ? mine.shiniesPerHour() : controller.shiniesPerHour();
        drawMetric(graphics, contentX, y, w, "BLOCKS / SECOND", String.format(Locale.ROOT, "%.1f", bps), TEXT);
        drawMetric(graphics, contentX + w + 7, y, w, mining ? "FOSSILS / HOUR" : "SHINIES / HOUR",
            String.format(Locale.ROOT, "%,.1f", shinyRate), CYAN);
        drawMetric(graphics, contentX, y + 32, w, "TOTAL BLOCKS", String.format(Locale.ROOT, "%,d", total), TEXT);
        drawMetric(graphics, contentX + w + 7, y + 32, w, mining ? "CONFIRMED FOSSILS" : "CONFIRMED SHINIES",
            Long.toString(shiny), SUCCESS);
        if (expanded) {
            drawMetric(graphics, contentX, y + 64, w, "BLOCKS / HOUR", String.format(Locale.ROOT, "%,.0f",
                mining ? mine.blocksPerHour() : controller.blocksPerHour()), TEXT);
            drawMetric(graphics, contentX + w + 7, y + 64, w, "TRACKED TARGETS", Integer.toString(
                mining ? mine.priorityTargetCount() : controller.glowingPlantCount()), MUTED);
        }
    }

    private final class PageCanvas extends AbstractWidget {
        private PageCanvas(int width, int height) {
            super(0, 0, width, height, Component.empty());
            active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            drawPage(graphics);
        }

        @Override
        public boolean isMouseOver(double x, double y) { return false; }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) { }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean isInGameUi() {
        return true;
    }

    @Override
    public void onClose() {
        config.save();
        minecraft.gui.setScreen(parent);
    }

    private enum ButtonStyle {
        NAVIGATION,
        PRIMARY,
        SECONDARY,
        TOGGLE,
        SEGMENT,
        DANGER
    }

    private static final class CropiumButton extends Button {
        private final ButtonStyle style;
        private boolean selected;

        private CropiumButton(int x, int y, int width, int height, Component message,
                              OnPress onPress, ButtonStyle style, boolean selected) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.style = style;
            this.selected = selected;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int background = switch (style) {
                case PRIMARY -> isHoveredOrFocused() ? 0xFFB19AFF : ACCENT;
                case DANGER -> isHoveredOrFocused() ? 0xFFFF7D8E : DANGER;
                case TOGGLE, SEGMENT -> selected ? 0xFF3A2F62 : isHoveredOrFocused() ? SURFACE_HOVER : SURFACE;
                case NAVIGATION -> selected ? 0xFF27213E : isHoveredOrFocused() ? SURFACE_HOVER : 0x0013131E;
                case SECONDARY -> isHoveredOrFocused() ? SURFACE_HOVER : SURFACE;
            };
            if (!active) background = SURFACE_SOFT;
            graphics.fill(getX(), getY(), getRight(), getBottom(), background);
            if ((style == ButtonStyle.NAVIGATION || style == ButtonStyle.SEGMENT) && selected) {
                graphics.fill(getX(), getY(), getX() + 2, getBottom(), ACCENT);
            } else if (style == ButtonStyle.SECONDARY || style == ButtonStyle.TOGGLE) {
                graphics.fill(getX(), getBottom() - 1, getRight(), getBottom(), BORDER);
            }
            int color = !active ? DIM : style == ButtonStyle.PRIMARY || style == ButtonStyle.DANGER ? 0xFF0C0914
                : selected ? ACCENT_BRIGHT : active ? TEXT : DIM;
            var font = Minecraft.getInstance().font;
            String label = font.plainSubstrByWidth(getMessage().getString(), Math.max(0, getWidth() - 12));
            int textX = getX() + (getWidth() - font.width(label)) / 2;
            int textY = getY() + (getHeight() - 8) / 2;
            graphics.text(font, label, textX, textY, color, false);
        }
    }

    private enum Page {
        DASHBOARD("Overview", "Live modules and session status"),
        CONTROLS("Controls", "Prepared starts, slot-1 lock, and mining health"),
        HARVESTER("Harvester", "Crop routing and glowing targets"),
        MINE("Mine", "Mapped copper-rim mine with fossil and ice routing"),
        EGG_HATCHER("Egg Hatcher", "Egg tier, hatch amount, and forge limit"),
        MERCHANT("Merchant", "Keep valuable NPCs, salvage the rest, and place them"),
        WORKFLOW("Workflow", "Merchant progress, waiting reasons and placement queue"),
        COVERAGE("Coverage", "Harvest history, targets, and saved exclusion zones"),
        ROUTES("Routes", "Draw and save freehand paths for farm and mine"),
        PLOT_SCANNER("NPC Plot", "Saved bounds and open-slot scanner"),
        STATISTICS("Statistics", "Confirmed harvests and session rates"),
        SETTINGS("Settings", "Movement, safety, and display controls");

        private final String label;
        private final String subtitle;

        Page(String label, String subtitle) {
            this.label = label;
            this.subtitle = subtitle;
        }
    }

    private static final class ConfigSlider extends AbstractSliderButton {
        private final String label;
        private final double minimum;
        private final double maximum;
        private final double step;
        private final DoubleConsumer change;
        private final String format;

        private ConfigSlider(int x, int y, int width, String label, double minimum, double maximum,
                             double step, double current, DoubleConsumer change, String format) {
            super(x, y, width, 25, Component.empty(), (current - minimum) / (maximum - minimum));
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.change = change;
            this.format = format;
            updateMessage();
        }

        private double selectedValue() {
            double raw = minimum + value * (maximum - minimum);
            return Math.clamp(Math.round(raw / step) * step, minimum, maximum);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": "
                + String.format(Locale.ROOT, format, selectedValue())));
        }

        @Override
        protected void applyValue() {
            change.accept(selectedValue());
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            var font = Minecraft.getInstance().font;
            graphics.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? SURFACE_HOVER : SURFACE);
            String shown = String.format(Locale.ROOT, format, selectedValue());
            graphics.text(font, font.plainSubstrByWidth(label, Math.max(0, getWidth() - 23 - font.width(shown))),
                getX() + 8, getY() + 5, TEXT, false);
            graphics.text(font, shown, getRight() - 8 - font.width(shown), getY() + 5, ACCENT_BRIGHT, false);
            int left = getX() + 8;
            int right = getRight() - 8;
            int trackY = getBottom() - 5;
            int handleX = left + (int)Math.round(value * (right - left));
            graphics.fill(left, trackY, right, trackY + 2, BORDER);
            graphics.fill(left, trackY, handleX, trackY + 2, ACCENT);
            graphics.fill(handleX - 2, trackY - 2, handleX + 3, trackY + 4, ACCENT_BRIGHT);
            handleCursor(graphics);
        }
    }
}
