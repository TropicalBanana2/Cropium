package com.salesfarm.croppilot;

import com.mojang.blaze3d.platform.InputConstants;
import com.salesfarm.croppilot.MineLayout.MinePattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** A buffered route inside the verified mine, independent of its changing floor material. */
public final class MineController implements CropiumModule {
    private static final int EDGE_CLEARANCE = 5;
    private static final int MIN_ROUTE_SPAN = 12;
    private static final int TELEPORT_TIMEOUT_TICKS = 120;
    private static final int ENTRY_TIMEOUT_TICKS = 500;
    private static final int FLIGHT_CONFIRM_TICKS = 35;
    private static final int MAX_TRANSIT_ATTEMPTS = 3;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final int TARGET_SCAN_INTERVAL = 10;
    private static final int TARGET_COOLDOWN_TICKS = 240;
    private static final int TARGET_TIMEOUT_TICKS = 140;
    private static final double TARGET_PASS_DISTANCE = 6.0;

    private final CropPilotConfig config;
    private final Random random = new Random();
    private final FlightMotion motion = new FlightMotion(random);
    private final CoverageMemory coverage = new CoverageMemory();
    private final Set<Long> iceCells = new HashSet<>();
    private String coverageWorldKey;
    private float recoveryPitch;
    private long recoveryAimUntil;
    private final FreeLookCamera camera = new FreeLookCamera();
    private boolean manualPause;
    private final MineNavigation.Response response = new MineNavigation.Response();
    private final MineTrace trace = new MineTrace();
    private final Set<Long> blockedCells = new HashSet<>();
    private final Set<Long> unknownCells = new HashSet<>();
    private final Map<BlockPos, Integer> targetMisses = new HashMap<>();
    private final Set<BlockPos> ignoredTargets = new HashSet<>();
    private final Map<BlockPos, PendingTarget> pendingTargets = new HashMap<>();
    private final List<MotionMath.Vec2> predictedPath = new ArrayList<>();
    private MotionMath.Vec2 joinPoint;
    private MotionMath.Vec2 lastPosition;
    private MotionMath.Vec2 measuredVelocity = new MotionMath.Vec2(0, 0);
    private MotionMath.Vec2 rejectedPoint;
    private String clearanceReason = "Not scanned";
    private String lastDecision = "Idle";
    private int missedPasses, targetAttempts, localRecoveries, localAttempts, riskSkips;
    private long localRecoveryDeadline;
    private boolean previewOnly;
    private final Set<Long> surface = new HashSet<>();
    private final Set<Long> safeSurface = new HashSet<>();
    private final Map<BlockPos, Integer> fossilTargets = new HashMap<>();
    private final Set<BlockPos> iceTargets = new HashSet<>();
    private final Map<BlockPos, Integer> targetCooldowns = new HashMap<>();
    private final Map<BlockPos, Integer> shinyCountCooldowns = new HashMap<>();
    private final Deque<Long> recentBreaks = new ArrayDeque<>();
    private State state = State.OFF;
    private MotionMath.Vec2[] route = new MotionMath.Vec2[0];
    private MotionMath.Vec2[] perimeter = new MotionMath.Vec2[0];
    private MinePattern activePattern = MinePattern.PERIMETER;
    private TracedRoute activeTrace;
    private int nextPatternTick;
    private MotionMath.Vec2 legStart;
    private MotionMath.Vec2 entryLegStart;
    private MotionMath.Vec2 heading = new MotionMath.Vec2(0.0, 1.0);
    private TargetIntercept target;
    private GridRect routeArea;
    private String activeWorldKey;
    private long ticks;
    private long stateSince;
    private long sessionStartedNanos;
    private long sessionStoppedNanos;
    private long blocksMined;
    private int routeIndex;
    private int routeStep = 1;
    private int floorY;
    private int transitAttempts;
    private int flightAttempts;
    private int scanAttempts;
    private int recoveryAttempts;
    private int stableMiningTicks;
    private int nextTargetScanTick;
    private int iceTargetCount;
    private int fossilsMined;
    private int iceMined;
    private int progressCheckTick;
    private int arrivalStableTicks;
    private int entryIndex;
    private double progressCheckX;
    private double progressCheckZ;
    private boolean joiningRoute;
    private boolean modHoldingAttack;
    private boolean modHoldingForward;
    private boolean modHoldingSprint;
    private boolean modHoldingLeft;
    private boolean modHoldingRight;
    private boolean modHoldingSneak;
    private Boolean previousPauseOnLostFocus;
    private String status = "Idle";

    public MineController(CropPilotConfig config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "mine";
    }

    @Override
    public Component name() {
        return Component.literal("Mine Harvester");
    }

    @Override
    public Component description() {
        return Component.literal("Mapped mine loop with fossil and ice interception");
    }

    @Override
    public boolean isActive() {
        return state != State.OFF;
    }

    public boolean isRunning() {
        return state != State.OFF && state != State.PAUSED;
    }

    public boolean isPaused() {
        return state == State.PAUSED;
    }

    public boolean isMining() { return state == State.MINING; }

    private MovementPreset movementPreset() {
        return config.movementPreset == null ? MovementPreset.BALANCED : config.movementPreset;
    }

    public String preflightStatus(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return "Join the server first";
        if (isPaused()) return "Paused; resume or stop the current workflow";
        if (minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof CropiumScreen)) return "Close the open container or screen";
        // /mine may enable flight at the destination. Entry still verifies it
        // before any flight input; the lobby's ability flag must not block travel.
        return "Ready";
    }

    public boolean preparedStart(Minecraft minecraft) {
        String status = preflightStatus(minecraft);
        if (!status.equals("Ready")) { message(minecraft, status); return false; }
        return start(minecraft);
    }

    public boolean recoveryReady() { return state == State.MINING && !joiningRoute; }

    public void exclusionsChanged() {
        if (isRunning()) return;
        target = null;
        targetCooldowns.clear();
        pendingTargets.clear();
        predictedPath.clear();
        joinPoint = null;
        route = new MotionMath.Vec2[0];
        routeArea = null;
        motion.routeOffset = motion.targetRouteOffset = 0;
        recoveryAimUntil = 0;
    }

    public String miningTargetStatus(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) return "World unavailable";
        if (isPaused()) return "Paused";
        if (minecraft.gui.screen() != null) return "GUI open";
        if (state != State.MINING) return state.label;
        if (activeWorldKey == null || !activeWorldKey.equals(FieldProfileStore.worldKey(minecraft))) return "World changed";
        if (!minecraft.player.getAbilities().flying) return "Flight inactive";
        if (!MineLayout.lowFlightHeight(minecraft.player.getY())) return "Outside low flight height";
        if (minecraft.player.horizontalCollision || !bodyClear(minecraft, point(minecraft.player))) return "Obstacle or exclusion ahead";
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return "Aiming at air";
        if (minecraft.player.getEyePosition().distanceToSqr(hit.getLocation()) > Math.pow(minecraft.player.blockInteractionRange(), 2)) return "Target out of reach";
        if (minecraft.level.getBlockState(hit.getBlockPos()).isAir()) return "Air; waiting for regrowth";
        return mayAttack(minecraft, hit.getBlockPos()) ? "Mineable floor in reach" : "Aim misses safe mine floor";
    }

    public void recoverMiningInput(Minecraft minecraft, boolean escalate) {
        if (!recoveryReady() || minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null
            || minecraft.gui.screen() != null || activeWorldKey == null
            || !activeWorldKey.equals(FieldProfileStore.worldKey(minecraft))) return;
        releaseAttack(minecraft);
        recoveryAimUntil = 0;
        if (escalate) { recover(minecraft, "mining input recovery requested"); return; }
        if (!minecraft.player.getAbilities().flying || !MineLayout.lowFlightHeight(minecraft.player.getY())
            || !bodyClear(minecraft, point(minecraft.player))) return;
        // Pitch-only correction leaves the forecast's horizontal input exactly intact.
        for (float change : new float[]{0, 2, -2, 4, -4, 6, -6, 8, -8}) {
            float pitch = FlightMotion.boundedMiningPitch(minecraft.player.getXRot(), change);
            if (!Float.isFinite(pitch)) continue;
            if (validRecoveryPitch(minecraft, pitch)) {
                recoveryPitch = pitch;
                recoveryAimUntil = ticks + 30;
                minecraft.player.setXRot(FlightMotion.easePitch(minecraft.player.getXRot(), pitch));
                trace.event(ticks, "Bounded forward floor re-aim");
                return;
            }
        }
        lastDecision = "No reachable forward floor for bounded re-aim";
    }

    private boolean validRecoveryPitch(Minecraft minecraft, float pitch) {
        var eye = minecraft.player.getEyePosition();
        var travel = yawHeading(minecraft.player.getYRot());
        double radians = Math.toRadians(pitch), range = minecraft.player.blockInteractionRange();
        var end = eye.add(travel.x() * Math.cos(radians) * range, -Math.sin(radians) * range,
            travel.z() * Math.cos(radians) * range);
        var hit = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.BLOCK && mayAttack(minecraft, hit.getBlockPos());
    }

    FreeLookCamera freeLookCamera() {
        return isRunning() && config.thirdPersonCamera && camera.enabled() ? camera : null;
    }

    public boolean canPause() { return state == State.MINING || state == State.PAUSED && manualPause; }

    public void pause(Minecraft minecraft) {
        if (state == State.MINING) {
            if (target != null) {
                targetCooldowns.put(target.block, (int)ticks + TARGET_COOLDOWN_TICKS);
                target = null;
            }
            releaseAll(minecraft);
            camera.restore(minecraft);
            restoreFocusPause(minecraft);
            predictedPath.clear();
            manualPause = true;
            setState(State.PAUSED);
            status = "Paused • P to rejoin the current route";
            lastDecision = status;
            trace.event(ticks, "Manual pause");
        } else if (state == State.PAUSED && manualPause && minecraft.player != null && minecraft.level != null) {
            manualPause = false;
            keepRunningWhenUnfocused(minecraft);
            heading = yawHeading(minecraft.player.getYRot());
            response.reset();
            lastPosition = null;
            measuredVelocity = new MotionMath.Vec2(0, 0);
            if (activeWorldKey != null && activeWorldKey.equals(FieldProfileStore.worldKey(minecraft))
                && minecraft.player.getAbilities().flying && MineLayout.lowFlightHeight(minecraft.player.getY())
                && route.length >= 3) {
                refreshLocalMap(minecraft);
                if (joinRoute(minecraft, route, true, 0, 0)) {
                    progressCheckX = minecraft.player.getX();
                    progressCheckZ = minecraft.player.getZ();
                    setState(State.ALIGNING);
                    trace.event(ticks, "Resuming current route without teleport");
                    return;
                }
            }
            beginTransit(minecraft, "Safe mine return after pause");
        }
    }

    public void routeSettingsChanged() { nextPatternTick = (int)ticks; }

    private String patternLabel() {
        return activeTrace == null ? activePattern.label : activeTrace.name();
    }

    public String patternStatus() {
        if (!isRunning()) return isPaused() ? "Route retained • paused" : "Preset applies after the safe entry join";
        return patternLabel() + " • " + (joiningRoute ? "joining" : target != null ? "target pass"
            : "change in " + Math.max(0, (nextPatternTick - ticks + 19) / 20) + "s");
    }

    public boolean safeAttack(Minecraft minecraft) {
        return state == State.MINING && minecraft.hitResult instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK && mayAttack(minecraft, hit.getBlockPos());
    }

    public void noteAttackRearm() {
        trace.event(ticks, "5s input watchdog: rearmed break input after missing input/progress");
    }

    @Override
    public String status() {
        return state == State.OFF ? status : state.label + " • "
            + String.format(Locale.ROOT, "%.1f BPS", blocksPerSecond());
    }

    @Override
    public boolean start(Minecraft minecraft) {
        if (isActive()) {
            return true;
        }
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            message(minecraft, "Join the server before starting Mine Harvester");
            return false;
        }
        resetSession();
        keepRunningWhenUnfocused(minecraft);
        if (minecraft.gui.screen() != null) {
            minecraft.gui.setScreen(null);
        }
        beginTransit(minecraft, "Starting Mine Harvester");
        return true;
    }

    @Override
    public void stop(Minecraft minecraft) {
        stop(minecraft, "Mine Harvester stopped");
    }

    public boolean suspendForUtility(Minecraft minecraft) {
        if (!isRunning()) {
            return false;
        }
        releaseAll(minecraft);
        camera.restore(minecraft);
        manualPause = false;
        setState(State.PAUSED);
        status = "Paused for merchant workflow";
        restoreFocusPause(minecraft);
        return true;
    }

    public boolean resumeAfterUtility(Minecraft minecraft) {
        if (state != State.PAUSED || minecraft.player == null || minecraft.level == null
            || minecraft.getConnection() == null) {
            return false;
        }
        transitAttempts = 0;
        recoveryAttempts = 0;
        keepRunningWhenUnfocused(minecraft);
        beginTransit(minecraft, "Returning to the mine after merchant workflow");
        return true;
    }

    public void tick(Minecraft minecraft) {
        if (state == State.OFF) {
            return;
        }
        ticks++;
        pruneBreaks();
        var screen = minecraft.gui.screen();
        if (MineNavigation.loadingAllowed(state == State.TELEPORTING, screen instanceof LevelLoadingScreen,
            stateAge(), TELEPORT_TIMEOUT_TICKS)) {
            releaseAll(minecraft);
            status = "Waiting for expected mine loading screen";
            return;
        }
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            stop(minecraft, "Mine Harvester stopped: connection/world unavailable during " + state.label);
            return;
        }
        if (minecraft.options.keyShift.isDown() && !modHoldingSneak
            || InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            stop(minecraft, "Mine Harvester stopped by Shift");
            return;
        }
        if (minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof CropiumScreen)
            && state != State.PAUSED) {
            stop(minecraft, "Mine Harvester stopped: " + screen.getClass().getSimpleName()
                + " opened during " + state.label);
            return;
        }
        if (state != State.PAUSED && activeWorldKey != null
            && !activeWorldKey.equals(FieldProfileStore.worldKey(minecraft))) {
            recover(minecraft, "the world changed during the mine workflow");
            return;
        }
        if (state != State.PAUSED) keepRunningWhenUnfocused(minecraft);
        camera.enable(minecraft, isRunning() && config.thirdPersonCamera);
        switch (state) {
            case TELEPORTING -> tickTeleport(minecraft);
            case ENTERING -> tickEntry(minecraft);
            case STARTING_FLIGHT -> tickFlightTap(minecraft);
            case WAITING_FOR_FLIGHT -> tickFlightConfirmation(minecraft);
            case ADJUSTING_HEIGHT -> tickHeight(minecraft);
            case CALIBRATING -> tickCalibration(minecraft);
            case ALIGNING -> tickAlignment(minecraft);
            case MINING -> tickMining(minecraft);
            case LOCAL_RECOVERY -> tickLocalRecovery(minecraft);
            case PAUSED -> releaseAll(minecraft);
            case OFF -> { }
        }
    }

    public void onChatMessage(Minecraft minecraft, Component message) {
        if (!isActive() || !config.stopOnNameMention || minecraft.player == null || message == null) {
            return;
        }
        if (MotionMath.containsNameToken(message.getString(), minecraft.player.getGameProfile().name())) {
            stop(minecraft, "Mine Harvester stopped: your name was mentioned in chat");
        }
    }

    public void onBlockBroken(BlockPos position) {
        if (state != State.MINING) {
            return;
        }
        long now = System.nanoTime();
        coverage.record(position.getX(), position.getZ(), ticks);
        blocksMined++;
        recentBreaks.addLast(now);
        shinyCountCooldowns.entrySet().removeIf(entry -> entry.getValue() <= ticks);
        boolean fossil = fossilTargets.containsKey(position)
            || target != null && !target.harvested && target.kind == TargetKind.FOSSIL && position.equals(target.block)
            || pendingTargets.containsKey(position) && pendingTargets.get(position).kind == TargetKind.FOSSIL;
        if (fossil && !shinyCountCooldowns.containsKey(position)) {
            fossilsMined++;
            shinyCountCooldowns.put(position.immutable(), (int)ticks + 40);
        }
        if (target != null && !target.harvested && position.equals(target.block)) {
            target.harvested = true;
        }
        if (iceTargets.remove(position)) iceMined++;
        if (fossil || pendingTargets.containsKey(position)) {
            pendingTargets.remove(position);
            targetMisses.remove(position);
            ignoredTargets.remove(position);
        }
    }

    public int priorityTargetCount() {
        return fossilTargets.size() + iceTargetCount;
    }

    public String targetStatus() {
        if (target != null) {
            return "Direct " + target.kind.label.toLowerCase(Locale.ROOT) + " intercept at " + shortPos(target.block);
        }
        return fossilTargets.size() + " fossils • " + iceTargetCount + " ice blocks visible";
    }

    public String hudLineOne() {
        return "MINE  " + state.label.toUpperCase(Locale.ROOT) + "  •  "
            + String.format(Locale.ROOT, "%.1f BPS", blocksPerSecond());
    }

    public String hudLineTwo() {
        return String.format(Locale.ROOT, "%d mined • %.0f blocks/h • %.1f fossils/h",
            blocksMined, blocksPerHour(), shiniesPerHour());
    }

    public String hudLineThree() {
        return routeArea == null ? "ROUTE  waiting for mapped mine validation"
            : "ROUTE  " + patternLabel() + " • " + routeArea.width() + "×" + routeArea.depth() + " safe core";
    }

    public String hudLineFour() {
        return target == null
            ? "TARGET  " + fossilTargets.size() + " fossils • " + iceTargetCount + " ice • "
                + fossilsMined + "/" + iceMined + " collected"
            : "TARGET  " + target.kind.label + " • straight pass, then rejoin";
    }

    public int hudAccentColor() {
        return switch (state) {
            case MINING -> target == null ? 0xFF65D9F3 : 0xFFFF5EDB;
            case PAUSED -> 0xFFFFD166;
            case OFF -> 0xFF7F8C8D;
            default -> 0xFFFF9F43;
        };
    }

    public void collectGizmos(Minecraft minecraft) {
        if (!config.showWorldOverlay || minecraft.player == null || route.length < 3
            || activeWorldKey == null || !activeWorldKey.equals(FieldProfileStore.worldKey(minecraft))) {
            return;
        }
        double y = floorY + 1.15;
        Gizmos.cuboid(new AABB(MineLayout.MIN_X, floorY, MineLayout.MIN_Z,
            MineLayout.MAX_X + 1, floorY + 3, MineLayout.MAX_Z + 1),
            GizmoStyle.stroke(0xFF65D9F3, 2.0F)).setAlwaysOnTop();
        Gizmos.cuboid(new AABB(MineLayout.MIN_X + EDGE_CLEARANCE, floorY + 0.05,
            MineLayout.MIN_Z + EDGE_CLEARANCE, MineLayout.MAX_X + 1 - EDGE_CLEARANCE,
            floorY + 2.5, MineLayout.MAX_Z + 1 - EDGE_CLEARANCE),
            GizmoStyle.stroke(0xFFFFD166, 2.0F)).setAlwaysOnTop();
        for (int index = 0; index < route.length; index++) {
            MotionMath.Vec2 start = route[index];
            MotionMath.Vec2 end = route[(index + 1) % route.length];
            Gizmos.line(new Vec3(start.x(), y, start.z()), new Vec3(end.x(), y, end.z()),
                0xFF65D9F3, 2.0F).setAlwaysOnTop();
        }
        for (BlockPos fossil : fossilTargets.keySet()) {
            int color = ignoredTargets.contains(fossil) ? 0xFF777784
                : target != null && target.block.equals(fossil) ? 0xFFFF5EDB : 0xFFFFD166;
            Gizmos.cuboid(fossil, 0.06F, GizmoStyle.strokeAndFill(
                color, 2.0F, color & 0x25FFFFFF)).setAlwaysOnTop();
        }
        // Only draw nearby live obstacles, with a hard cap on per-frame geometry.
        int drawn = 0;
        for (long cell : blockedCells) {
            int x = cellX(cell), z = cellZ(cell);
            if (Math.hypot(x + 0.5 - minecraft.player.getX(), z + 0.5 - minecraft.player.getZ()) > 24) continue;
            Gizmos.cuboid(new AABB(x, floorY + 1, z, x + 1, floorY + 3, z + 1),
                GizmoStyle.strokeAndFill(0xFFFF7043, 1.5F, 0x20FF7043)).setAlwaysOnTop();
            if (++drawn >= 96) break;
        }
        if (isRunning()) {
            drawPathGizmos(predictedPath, y + 0.15, 0xFF6EE7A8, 3.0F);
            if (joinPoint != null) {
                drawPathGizmos(List.of(point(minecraft.player), joinPoint, route[routeIndex]),
                    y + 0.1, 0xFFFF9F43, 2.5F);
            }
            if (minecraft.options.keyAttack.isDown() && safeAttack(minecraft)
                && minecraft.hitResult instanceof BlockHitResult hit) {
                Gizmos.cuboid(hit.getBlockPos(), 0.025F,
                    GizmoStyle.strokeAndFill(0xFFFF5C5C, 2.5F, 0x28FF5C5C)).setAlwaysOnTop();
            }
        }
        if (target != null) {
            Gizmos.cuboid(target.block, 0.06F,
                GizmoStyle.strokeAndFill(0xFFFF5EDB, 2.5F, 0x25FF5EDB)).setAlwaysOnTop();
            drawPathGizmos(List.of(point(minecraft.player), target.point, target.pass,
                route[target.rejoinIndex]), y, 0xFFFF5EDB, 2.5F);
        }
    }

    private static void drawPathGizmos(List<MotionMath.Vec2> points, double y, int color, float width) {
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            Gizmos.line(new Vec3(a.x(), y, a.z()), new Vec3(b.x(), y, b.z()), color, width).setAlwaysOnTop();
        }
    }

    private void beginTransit(Minecraft minecraft, String reason) {
        releaseAll(minecraft);
        trace.event(ticks, reason);
        lastDecision = reason;
        previewOnly = false;
        manualPause = false;
        lastPosition = null;
        response.reset();
        joinPoint = null;
        predictedPath.clear();
        surface.clear();
        safeSurface.clear();
        blockedCells.clear();
        unknownCells.clear();
        iceCells.clear();
        fossilTargets.clear();
        iceTargets.clear();
        iceTargetCount = 0;
        route = new MotionMath.Vec2[0];
        perimeter = new MotionMath.Vec2[0];
        activePattern = MinePattern.PERIMETER;
        activeTrace = null;
        routeArea = null;
        target = null;
        scanAttempts = 0;
        flightAttempts = 0;
        arrivalStableTicks = 0;
        activeWorldKey = null;
        sendCommand(minecraft, "mine");
        setState(State.TELEPORTING);
        status = reason + " • waiting for /mine";
        message(minecraft, reason + " — running /mine");
    }

    private void tickTeleport(Minecraft minecraft) {
        releaseAll(minecraft);
        String world = FieldProfileStore.worldKey(minecraft);
        LocalPlayer player = minecraft.player;
        boolean arrived = world != null && world.endsWith("|minecraft:overworld")
            && MineLayout.nearArrival(player.getX(), player.getY(), player.getZ());
        arrivalStableTicks = arrived && stateAge() >= 10 ? arrivalStableTicks + 1 : 0;
        if (arrivalStableTicks >= 6 && borderAt(minecraft, MineLayout.MAX_X + 1, 1530)) {
            entryIndex = 0;
            entryLegStart = point(player);
            activeWorldKey = world;
            if (!world.equals(coverageWorldKey)) coverage.clear();
            coverageWorldKey = world;
            setState(State.ENTERING);
            status = "/mine confirmed • following the mapped ramp";
            return;
        }
        if (stateAge() >= TELEPORT_TIMEOUT_TICKS) {
            if (++transitAttempts >= MAX_TRANSIT_ATTEMPTS) {
                stop(minecraft, "Mine Harvester stopped: expected /mine arrival or copper rim was not verified");
            } else {
                arrivalStableTicks = 0;
                sendCommand(minecraft, "mine");
                setState(State.TELEPORTING);
                status = "Retrying /mine • " + (transitAttempts + 1) + "/" + MAX_TRANSIT_ATTEMPTS;
            }
        }
    }

    private void tickEntry(Minecraft minecraft) {
        releaseAttack(minecraft);
        releaseVertical(minecraft);
        LocalPlayer player = minecraft.player;
        if (!activeWorldKey.equals(FieldProfileStore.worldKey(minecraft))
            || stateAge() >= ENTRY_TIMEOUT_TICKS) {
            recover(minecraft, "the mapped mine entry was not completed");
            return;
        }
        // /mine can preserve flight. Land before taking the rising pedestrian ramp.
        if (player.getAbilities().flying) {
            releaseMovement(minecraft);
            minecraft.options.keyShift.setDown(true);
            modHoldingSneak = true;
            status = "Landing at /mine before walking the ramp";
            return;
        }
        MotionMath.Vec2 position = point(player);
        MotionMath.Vec2 destination = MineLayout.ENTRY.get(entryIndex);
        MotionMath.Vec2 next = entryIndex + 1 < MineLayout.ENTRY.size()
            ? MineLayout.ENTRY.get(entryIndex + 1) : null;
        if (position.subtract(destination).length() < 0.75
            || MotionMath.shouldAdvanceSegment(position, entryLegStart, destination, next)
                && MotionMath.routeProgress(position, entryLegStart, destination, 0.0).crossTrack() < 1.0) {
            entryIndex++;
            entryLegStart = destination;
        }
        if (entryIndex == MineLayout.ENTRY.size()) {
            beginFlight(minecraft);
            return;
        }
        destination = MineLayout.ENTRY.get(entryIndex);
        MotionMath.RouteProgress progress = MotionMath.routeProgress(position, entryLegStart, destination, 0.0);
        if (progress.crossTrack() > 2.25) {
            recover(minecraft, "walking drifted off the mapped ramp");
            return;
        }
        MotionMath.Vec2 aim = MotionMath.polylineLookPoint(position, entryLegStart, destination,
            MineLayout.ENTRY.subList(entryIndex + 1, MineLayout.ENTRY.size()), 2.5);
        MotionMath.Vec2 desired = aim.subtract(position);
        float entryYaw = (float)Math.toDegrees(Math.atan2(-desired.x(), desired.z()));
        float yawDelta = Mth.wrapDegrees(entryYaw - player.getYRot());
        player.setYRot(player.getYRot() + Math.clamp(yawDelta, -6.0F, 6.0F));
        player.setXRot(player.getXRot() + Math.clamp(8.0F - player.getXRot(), -3.0F, 3.0F));
        // Keep walking through the shallow bend; only pause to correct a genuinely
        // wrong initial heading. No stop-and-pivot at each entry waypoint.
        if (Math.abs(yawDelta) > 35.0F) {
            releaseMovement(minecraft);
            return;
        }
        if (!entryStepClear(minecraft, position.add(yawHeading(player.getYRot()).scale(0.28)))) {
            recover(minecraft, "the mapped ramp is blocked or not loaded");
            return;
        }
        minecraft.options.keyUp.setDown(true);
        minecraft.options.keySprint.setDown(false);
        player.setSprinting(false);
        modHoldingForward = true;
        modHoldingSprint = false;
        status = "Walking mapped ramp • leg " + (entryIndex + 1) + "/" + MineLayout.ENTRY.size();
    }

    private void beginFlight(Minecraft minecraft) {
        releaseAll(minecraft);
        if (minecraft.player.getAbilities().flying) {
            setState(State.ADJUSTING_HEIGHT);
            status = "Flight active • brief descent taps";
            return;
        }
        if (!minecraft.player.getAbilities().mayfly) {
            recover(minecraft, "/mine did not permit flight");
            return;
        }
        if (++flightAttempts > 3) {
            recover(minecraft, "double-tap flight was not accepted");
            return;
        }
        setState(State.STARTING_FLIGHT);
        status = "Starting flight • attempt " + flightAttempts + "/3";
    }

    private void tickFlightTap(Minecraft minecraft) {
        releaseMovement(minecraft);
        minecraft.options.keyJump.setDown(MerchantRestockLogic.flightTapDown((int)stateAge()));
        if (stateAge() >= 5) {
            minecraft.options.keyJump.setDown(false);
            setState(State.WAITING_FOR_FLIGHT);
            status = "Verifying flight";
        }
    }

    private void tickFlightConfirmation(Minecraft minecraft) {
        releaseAll(minecraft);
        if (minecraft.player.getAbilities().flying) {
            setState(State.ADJUSTING_HEIGHT);
            status = "Flight active • brief descent taps";
        } else if (stateAge() >= FLIGHT_CONFIRM_TICKS) {
            beginFlight(minecraft);
        }
    }

    private void tickHeight(Minecraft minecraft) {
        releaseAll(minecraft);
        if (!minecraft.player.getAbilities().flying) {
            beginFlight(minecraft);
            return;
        }
        double floor = mineFloorTop(minecraft);
        double clearance = minecraft.player.getY() - floor;
        double velocity = minecraft.player.getDeltaMovement().y;
        if (MineLayout.readyToMine((int)stateAge(), clearance, velocity)) {
            scanAttempts = 0;
            setState(State.CALIBRATING);
            status = "Low flight ready • validating mine clearance";
            return;
        }
        if (MineLayout.descentTapForClearance((int)stateAge(), clearance,
            velocity, minecraft.player.getAbilities().getFlyingSpeed())) {
            minecraft.options.keyShift.setDown(true);
            modHoldingSneak = true;
        }
        if (stateAge() < MineLayout.FLIGHT_SETTLE_TICKS) {
            return;
        }
        stop(minecraft, "Mine Harvester stopped: low flight could not be confirmed; descent released");
    }

    private double mineFloorTop(Minecraft minecraft) {
        // Read actual collision tops, not the player's rounded Y or a glow entity.
        // Regenerating air retains the known map floor instead of selecting bedrock.
        double top = MineLayout.FLOOR_Y + 1.0;
        int x = Mth.floor(minecraft.player.getX());
        int z = Mth.floor(minecraft.player.getZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = new BlockPos(x + dx, MineLayout.FLOOR_Y, z + dz);
                if (!minecraft.level.hasChunkAt(pos)) {
                    return Double.NaN;
                }
                var shape = minecraft.level.getBlockState(pos).getCollisionShape(minecraft.level, pos);
                if (!shape.isEmpty()) {
                    top = Math.max(top, pos.getY() + shape.bounds().maxY);
                }
            }
        }
        return top;
    }

    private void tickCalibration(Minecraft minecraft) {
        releaseAll(minecraft);
        if (stateAge() < 10 || stateAge() % 20 != 10) {
            return;
        }
        scanAttempts++;
        if (scanMine(minecraft)) {
            if (!startRoute(minecraft)) {
                recover(minecraft, "the mine entry point could not reach the safe core");
            }
        } else if (scanAttempts >= 6) {
            recover(minecraft, "the mapped mine/rim was incomplete or obstructed; check loaded chunks");
        } else {
            status = "Waiting for mine chunks • scan " + scanAttempts + "/6";
        }
    }

    private boolean scanMine(Minecraft minecraft) {
        surface.clear();
        safeSurface.clear();
        blockedCells.clear();
        unknownCells.clear();
        floorY = MineLayout.FLOOR_Y;
        for (int x = MineLayout.MIN_X - 1; x <= MineLayout.MAX_X + 1; x++) {
            for (int z = MineLayout.MIN_Z - 1; z <= MineLayout.MAX_Z + 1; z++) {
                BlockPos block = new BlockPos(x, floorY, z);
                if (!minecraft.level.hasChunkAt(block)) {
                    return false;
                }
                if (MineLayout.rim(x, z)) {
                    if (!borderAt(minecraft, x, z)) {
                        return false;
                    }
                } else {
                    // Geometry is permanent; air during regrowth and ore replacements must
                    // not erase lanes. Stairs and live body obstacles are never part of a lane.
                    // The downloaded footprint is permanent. Live collisions are
                    // a separate layer, so regrowing ore cannot delete the route.
                    surface.add(key(x, z));
                    refreshCell(minecraft, x, z);
                }
            }
        }
        for (long cell : surface) {
            if (hasSurfaceClearance(cellX(cell), cellZ(cell), EDGE_CLEARANCE)) {
                safeSurface.add(cell);
            }
        }
        GridRect largest = largestRectangle(safeSurface);
        if (largest == null || largest.width() < MIN_ROUTE_SPAN || largest.depth() < MIN_ROUTE_SPAN) {
            return false;
        }

        for (int inset = 2; inset <= Math.min(largest.width(), largest.depth()) / 3; inset++) {
            GridRect candidateArea = largest.inset(inset);
            if (candidateArea.width() < MIN_ROUTE_SPAN || candidateArea.depth() < MIN_ROUTE_SPAN) {
                break;
            }
            double radius = Math.clamp(Math.min(candidateArea.width(), candidateArea.depth()) / 3.0,
                6.0, 24.0);
            MotionMath.Vec2[] candidate = MotionMath.roundedRectangle(
                candidateArea.minX + 0.5, candidateArea.maxX + 0.5,
                candidateArea.minZ + 0.5, candidateArea.maxZ + 0.5, radius, 12);
            if (routeClear(minecraft, candidate)) {
                route = candidate;
                perimeter = candidate.clone();
                routeArea = candidateArea;
                return true;
            }
        }
        return false;
    }

    private boolean startRoute(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        MotionMath.Vec2 position = point(player);
        if (!safeSurface.contains(key(Mth.floor(position.x()), Mth.floor(position.z())))) {
            return false;
        }
        heading = yawHeading(player.getYRot());
        if (!joinRoute(minecraft, route, true, 0, 0)) return false;
        activePattern = MinePattern.PERIMETER;
        activeTrace = null;
        legStart = position;
        joiningRoute = true;
        activeWorldKey = FieldProfileStore.worldKey(minecraft);
        stableMiningTicks = 0;
        progressCheckTick = (int)ticks;
        progressCheckX = player.getX();
        progressCheckZ = player.getZ();
        nextTargetScanTick = (int)ticks;
        setState(State.ALIGNING);
        motion.routeOffset = motion.targetRouteOffset = 0.0;
        motion.scheduleWander((int)ticks);
        motion.schedulePitchChange((int)ticks);
        // Join the proven entry loop first; apply the chosen style after the join.
        nextPatternTick = (int)ticks;
        status = "Aligning with the safe loop before moving";
        message(minecraft, "Mine calibrated: " + routeArea.width() + "×" + routeArea.depth()
            + " safe core with " + EDGE_CLEARANCE + "-block edge clearance");
        return true;
    }

    private void tickAlignment(Minecraft minecraft) {
        releaseAll(minecraft);
        MotionMath.Vec2 desired = (joinPoint == null ? route[routeIndex] : joinPoint).subtract(point(minecraft.player));
        float yaw = (float)Math.toDegrees(Math.atan2(-desired.x(), desired.z()));
        float error = Mth.wrapDegrees(yaw - minecraft.player.getYRot());
        minecraft.player.setYRot(minecraft.player.getYRot() + Math.clamp(error, -6.0F, 6.0F));
        if (Math.abs(error) <= 5.0F
            && Math.hypot(minecraft.player.getDeltaMovement().x, minecraft.player.getDeltaMovement().z) < 0.12) {
            heading = yawHeading(minecraft.player.getYRot());
            progressCheckTick = (int)ticks;
            lastPosition = point(minecraft.player);
            measuredVelocity = new MotionMath.Vec2(0, 0);
            response.reset();
            setState(State.MINING);
        } else if (stateAge() > 100) {
            recover(minecraft, "could not align with the mine route");
        }
    }

    private void tickMining(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        MotionMath.Vec2 position = point(player);
        measuredVelocity = lastPosition == null ? new MotionMath.Vec2(player.getDeltaMovement().x, player.getDeltaMovement().z)
            : position.subtract(lastPosition);
        lastPosition = position;
        if (measuredVelocity.length() > 3.0) {
            response.reset();
            recover(minecraft, "position correction exceeded three blocks");
            return;
        }
        response.observe(measuredVelocity);
        if (ticks % 10 == 0) refreshLocalMap(minecraft);
        if (!player.getAbilities().flying) {
            recover(minecraft, "flight ended");
            return;
        }
        String world = FieldProfileStore.worldKey(minecraft);
        if (activeWorldKey != null && world != null && !activeWorldKey.equals(world)) {
            recover(minecraft, "the world changed while mining");
            return;
        }
        if (!MineLayout.lowFlightHeight(player.getY())) {
            recover(minecraft, "mining height left the mapped floor");
            return;
        }
        if (player.horizontalCollision || !safeSurface.contains(key(Mth.floor(player.getX()), Mth.floor(player.getZ())))
            || !bodyClear(minecraft, point(player))) {
            recover(minecraft, "the safe corridor was lost");
            return;
        }
        if (++stableMiningTicks >= 600) {
            recoveryAttempts = 0;
            localAttempts = 0;
            stableMiningTicks = 0;
        }
        if (ticks - progressCheckTick >= 40) {
            double travelled = Math.hypot(player.getX() - progressCheckX, player.getZ() - progressCheckZ);
            progressCheckTick = (int)ticks;
            progressCheckX = player.getX();
            progressCheckZ = player.getZ();
            if (travelled < 1.0) {
                recover(minecraft, "movement stalled");
                return;
            }
        }

        holdMiningInputs(minecraft);
        scanTargets(minecraft);
        observePassedTargets(minecraft);
        if (target != null && tickTarget(minecraft)) {
            return;
        }
        followRoute(minecraft);
    }

    private void followRoute(Minecraft minecraft) {
        MotionMath.Vec2 position = point(minecraft.player);
        if (!joiningRoute && target == null && ticks >= nextPatternTick) {
            switchPattern(minecraft);
        }
        if (joinPoint != null && (position.subtract(joinPoint).length() < 0.7
            || MotionMath.shouldAdvanceSegment(position, legStart, joinPoint, route[routeIndex]))) {
            legStart = joinPoint;
            joinPoint = null;
            joiningRoute = false;
        }
        if (joinPoint != null) {
            driveCourse(minecraft, currentCourse(), null);
            return;
        }
        for (int advances = 0; advances < 8; advances++) {
            MotionMath.Vec2 destination = route[routeIndex];
            int next = nextIndex(routeIndex, routeStep);
            if (position.subtract(destination).length() > 0.85
                && !MotionMath.shouldAdvanceSegment(position, legStart, destination, route[next])) {
                break;
            }
            legStart = destination;
            routeIndex = next;
            joiningRoute = false;
        }
        MotionMath.Vec2 destination = route[routeIndex];
        MotionMath.RouteProgress progress = MotionMath.routeProgress(position, legStart, destination, 0.0);
        if (!joiningRoute && progress.crossTrack() > 6.0) {
            recover(minecraft, "route drift exceeded the safe corridor");
            return;
        }
        double room = joiningRoute || ticks < recoveryAimUntil || ticks - stateSince < 20 ? 0 : 1;
        if (activeTrace != null) room = 0; // Keep variation out of the drawn line's turns and edge buffer.
        var upcoming = position.add(yawHeading(minecraft.player.getYRot()).scale(4));
        if (!bodyClear(minecraft, upcoming, minecraft.player.getY(), 1.0 + movementPreset().driftLimit())) room = 0;
        motion.wander((int)ticks, config.naturalMovement ? config.headingJitterDegrees : 0.0, movementPreset(), room);
        if (driveCourse(minecraft, currentCourse(), null)) {
            status = "Mining " + patternLabel() + " • " + targetStatus();
        }
    }

    private void scanTargets(Minecraft minecraft) {
        if (ticks < nextTargetScanTick) {
            return;
        }
        nextTargetScanTick = (int)ticks + TARGET_SCAN_INTERVAL;
        targetCooldowns.entrySet().removeIf(entry -> entry.getValue() <= ticks);
        fossilTargets.clear();
        if (config.mineTargetFossils) {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity == minecraft.player || entity instanceof Player || entity.isRemoved()
                    || !entity.isCurrentlyGlowing() || entity.distanceToSqr(minecraft.player) > 64.0 * 64.0
                    || entity.getY() < floorY - 0.25 || entity.getY() > floorY + 2.0) {
                    continue;
                }
                BlockPos mapped = new BlockPos(Mth.floor(entity.getX()), floorY, Mth.floor(entity.getZ()));
                if (safeSurface.contains(key(mapped.getX(), mapped.getZ()))) {
                    fossilTargets.put(mapped, (int)ticks);
                }
            }
        }

        iceTargetCount = 0;
        iceTargets.clear();
        if (config.mineTargetIce) {
            int centerX = Mth.floor(minecraft.player.getX());
            int centerZ = Mth.floor(minecraft.player.getZ());
            for (int x = centerX - 40; x <= centerX + 40; x++) {
                for (int z = centerZ - 40; z <= centerZ + 40; z++) {
                    if (safeSurface.contains(key(x, z))
                        && isIce(minecraft.level.getBlockState(new BlockPos(x, floorY, z)))) {
                        iceTargetCount++;
                        iceTargets.add(new BlockPos(x, floorY, z));
                    }
                }
            }
        }
        if (target == null && !joiningRoute) {
            TargetChoice fossil = bestFossil(minecraft);
            TargetChoice ice = fossil == null ? bestIce(minecraft) : null;
            TargetChoice choice = MineNavigation.preferIce(ice == null ? Double.POSITIVE_INFINITY : ice.score,
                fossil == null ? Double.POSITIVE_INFINITY : fossil.score) ? ice : fossil;
            if (choice != null) {
                target = new TargetIntercept(choice.block, choice.kind, choice.point, choice.pass,
                    choice.rejoinIndex, choice.approach, (int)ticks, choice.distance);
                target.start = point(minecraft.player);
                targetAttempts++;
                trace.event(ticks, "Target pass: " + choice.kind.label + " at " + shortPos(choice.block));
                status = "Taking a straight path through " + choice.kind.label.toLowerCase(Locale.ROOT);
            }
        }
    }

    private void switchPattern(Minecraft minecraft) {
        if (config.customMineRoutes) {
            var choices = TracedRoute.choices(config.tracedRoutes, FieldProfileStore.worldKey(minecraft), id(),
                routeMapKey(), activeTrace == null ? null : activeTrace.id(), random);
            // Validate at most one full forecast per attempt, not 32 laps in a client tick.
            if (!choices.isEmpty()) {
                var candidate = choices.getFirst();
                var shape = candidate.compile();
                var path = shape.points().toArray(MotionMath.Vec2[]::new);
                if (shape.valid() && tracedRouteProblem(minecraft, shape.points()) == null
                    && routeClear(minecraft, path) && joinRoute(minecraft, path, false, 0, 0)) {
                    activeTrace = candidate;
                    motion.routeOffset = motion.targetRouteOffset = 0;
                    nextPatternTick = MineLayout.nextChangeTick((int)ticks, config.customRouteSeconds, random);
                    lastDecision = "Drawn phase: " + candidate.name();
                    trace.event(ticks, lastDecision);
                    return;
                }
            }
            nextPatternTick = (int)ticks + 100;
            lastDecision = "Keeping safe route — no clear drawn phase / join yet";
            return;
        }
        record Candidate(MinePattern pattern, MotionMath.Vec2[] path, double fossilCoverage, double iceCoverage) { }
        List<Candidate> candidates = new ArrayList<>();
        List<MotionMath.Vec2> fossils = config.mineTargetFossils ? fossilTargets.keySet().stream()
            .filter(block -> !ignoredTargets.contains(block))
            .map(block -> new MotionMath.Vec2(block.getX() + 0.5, block.getZ() + 0.5)).toList() : List.of();
        List<MotionMath.Vec2> ice = config.mineTargetIce ? iceTargets.stream()
            .filter(block -> !ignoredTargets.contains(block))
            .map(block -> new MotionMath.Vec2(block.getX() + 0.5, block.getZ() + 0.5)).toList() : List.of();
        for (MinePattern choice : MineLayout.patternChoices(config.mineRouteMode, activePattern, random)) {
            for (int sample = 0; sample < (MineLayout.longPass(choice) ? 3 : 1); sample++) {
                var path = MineLayout.variedPath(choice, perimeter, random,
                    config.mineRouteVariation / 100.0 * movementPreset().variationScale());
                candidates.add(new Candidate(choice, path, MineNavigation.iceCoverage(path, fossils), MineNavigation.iceCoverage(path, ice)
                    - 30 * movementPreset().coverageWeight() * coverage.recentFraction(List.of(path), ticks)));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::fossilCoverage).reversed()
            .thenComparingInt(candidate -> MineLayout.longPass(candidate.pattern) ? 0 : 1)
            .thenComparing(Comparator.comparingDouble(Candidate::iceCoverage).reversed()));
        for (Candidate candidate : candidates) {
            MinePattern choice = candidate.pattern;
            if (!routeClear(minecraft, candidate.path)) {
                continue;
            }
            if (joinRoute(minecraft, candidate.path, false, choice == MinePattern.CROSS_CUT ? 28 : 0,
                choice == MinePattern.REVERSAL ? -routeStep : 0)) {
                activePattern = choice;
                activeTrace = null;
                motion.targetRouteOffset = 0.0;
                nextPatternTick = MineLayout.nextChangeTick((int)ticks, config.mineRouteSeconds, random);
                lastDecision = "Safe pattern switch: " + choice.label
                    + (candidate.fossilCoverage > 0 ? " • fossil-rich pass" : candidate.iceCoverage > 0 ? " • ice-rich pass" : "");
                trace.event(ticks, lastDecision);
                return;
            }
        }
        // Unsafe variety is optional. Keep the current clear route and retry later.
        nextPatternTick = (int)ticks + 200;
        lastDecision = "Keeping safe route • no clear pattern join yet";
    }

    private TargetChoice bestFossil(Minecraft minecraft) {
        if (!config.mineTargetFossils) {
            return null;
        }
        TargetChoice best = null;
        for (BlockPos block : fossilTargets.keySet()) {
            TargetChoice choice = evaluateTarget(minecraft, block, TargetKind.FOSSIL, 52.0);
            if (choice != null && (best == null || choice.score < best.score)) {
                best = choice;
            }
        }
        return best;
    }

    private TargetChoice bestIce(Minecraft minecraft) {
        if (!config.mineTargetIce) {
            return null;
        }
        MotionMath.Vec2 position = point(minecraft.player);
        MotionMath.Vec2 travel = yawHeading(minecraft.player.getYRot());
        List<BlockPos> candidates = new ArrayList<>();
        TargetChoice best = null;
        for (BlockPos block : iceTargets) {
            MotionMath.Vec2 delta = new MotionMath.Vec2(block.getX() + 0.5, block.getZ() + 0.5).subtract(position);
            double distance = delta.length();
            if (distance >= 5.0 && distance <= 44.0 && !targetCooldowns.containsKey(block)
                && !ignoredTargets.contains(block) && !pendingTargets.containsKey(block)
                && Math.abs(Math.toDegrees(MotionMath.signedAngle(travel, delta.normalized()))) <= 52.0) {
                candidates.add(block.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(block -> Mth.square(block.getX() + 0.5 - position.x())
            + Mth.square(block.getZ() + 0.5 - position.z())));
        for (int index = 0; index < Math.min(24, candidates.size()); index++) {
            TargetChoice choice = evaluateTarget(minecraft, candidates.get(index), TargetKind.ICE, 52.0);
            if (choice != null && (best == null || choice.score < best.score)) {
                best = choice;
            }
        }
        return best;
    }

    private TargetChoice evaluateTarget(Minecraft minecraft, BlockPos block, TargetKind kind,
                                        double maximumTurnDegrees) {
        if (targetCooldowns.containsKey(block) || ignoredTargets.contains(block) || pendingTargets.containsKey(block)) {
            return null;
        }
        MotionMath.Vec2 position = point(minecraft.player);
        MotionMath.Vec2 point = new MotionMath.Vec2(block.getX() + 0.5, block.getZ() + 0.5);
        MotionMath.Vec2 delta = point.subtract(position);
        double distance = delta.length();
        if (distance < 5.0 || distance > 44.0) {
            return null;
        }
        MotionMath.Vec2 approach = delta.normalized();
        double angle = Math.abs(Math.toDegrees(MotionMath.signedAngle(yawHeading(
            minecraft.player.getYRot()), approach)));
        if (angle > maximumTurnDegrees) {
            return null;
        }
        MotionMath.Vec2 pass = point.add(approach.scale(TARGET_PASS_DISTANCE));
        if (!segmentClear(minecraft, position, pass)) {
            // A live obstacle can move; risky attempts get a cooldown, not a miss.
            targetCooldowns.put(block, (int)ticks + TARGET_COOLDOWN_TICKS);
            return null;
        }
        int rejoin = safeRejoinIndex(minecraft, pass, approach);
        if (rejoin < 0) {
            return null;
        }
        List<MotionMath.Vec2> corridor = targetCourse(position, point, pass, rejoin);
        int horizon = Math.clamp((int)((distance + TARGET_PASS_DISTANCE) / planningSpeed()) + 28, 30, 140);
        var forecast = MineNavigation.forecast(pose(minecraft), corridor, planningSpeed(), response.inertia,
            null, point, horizon, (a, b) -> segmentClear(minecraft, a, b));
        if (!forecast.clear() || forecast.closestTarget() > 0.75) {
            targetCooldowns.put(block, (int)ticks + TARGET_COOLDOWN_TICKS);
            riskSkips++;
            return null;
        }
        double score = distance + angle * (kind == TargetKind.FOSSIL ? 0.08 : 0.18);
        return new TargetChoice(block.immutable(), kind, point, pass, rejoin, approach, distance, score);
    }

    private int safeRejoinIndex(Minecraft minecraft, MotionMath.Vec2 from, MotionMath.Vec2 approach) {
        int maximum = Math.min(route.length - 1, 14);
        int best = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int offset = 2; offset <= maximum; offset++) {
            int candidate = nextIndex(routeIndex, routeStep * offset);
            MotionMath.Vec2 rejoin = route[candidate];
            MotionMath.Vec2 exit = rejoin.subtract(from);
            MotionMath.Vec2 tangent = route[nextIndex(candidate, routeStep)].subtract(rejoin);
            if (exit.length() < 6.0 || Math.abs(Math.toDegrees(
                MotionMath.signedAngle(approach, exit.normalized()))) > 55.0
                || Math.abs(Math.toDegrees(MotionMath.signedAngle(exit, tangent))) > 65.0
                || !segmentClear(minecraft, from, rejoin)) {
                continue;
            }
            double score = exit.length() + offset * 0.15;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean tickTarget(Minecraft minecraft) {
        MotionMath.Vec2 position = point(minecraft.player);
        double distance = position.subtract(target.point).length();
        target.closestDistance = Math.min(target.closestDistance, distance);
        boolean passed = MotionMath.passedPoint(position, target.point, target.approach, 0.65);
        boolean movingAway = target.closestDistance < 2.4 && distance > target.closestDistance + 0.55;
        if (target.harvested || passed || movingAway || ticks - target.startedTick > TARGET_TIMEOUT_TICKS) {
            finishTarget(position);
            return false;
        }
        if (!segmentClear(minecraft, position, target.pass)) {
            finishTarget(position);
            return false;
        }
        if (driveCourse(minecraft, targetCourse(target.start, target.point, target.pass, target.rejoinIndex), target.point)) {
            status = "Direct " + target.kind.label.toLowerCase(Locale.ROOT) + " intercept • "
                + String.format(Locale.ROOT, "%.1f blocks", distance);
        }
        return true;
    }

    private void finishTarget(MotionMath.Vec2 position) {
        if (!target.harvested) {
            pendingTargets.put(target.block, new PendingTarget(target.kind, (int)ticks + 40));
        }
        targetCooldowns.put(target.block, (int)ticks + TARGET_COOLDOWN_TICKS);
        routeIndex = target.rejoinIndex;
        legStart = target.point;
        joinPoint = target.pass;
        joiningRoute = true;
        target = null;
    }

    private List<MotionMath.Vec2> targetCourse(MotionMath.Vec2 start, MotionMath.Vec2 crop,
                                             MotionMath.Vec2 pass, int rejoin) {
        List<MotionMath.Vec2> course = new ArrayList<>();
        course.add(start);
        course.add(crop);
        course.add(pass);
        course.addAll(courseFrom(route[rejoin], null, route, nextIndex(rejoin, routeStep), routeStep));
        return course;
    }

    private void observePassedTargets(Minecraft minecraft) {
        var iterator = pendingTargets.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (ticks < entry.getValue().dueTick || !minecraft.level.hasChunkAt(entry.getKey())) continue;
            boolean stillPresent = entry.getValue().kind == TargetKind.FOSSIL
                ? fossilTargets.containsKey(entry.getKey()) : isIce(minecraft.level.getBlockState(entry.getKey()));
            if (stillPresent) {
                missedPasses++;
                int misses = targetMisses.merge(entry.getKey(), 1, Integer::sum);
                if (misses >= 2) ignoredTargets.add(entry.getKey());
                trace.event(ticks, "Miss " + misses + " at " + shortPos(entry.getKey())
                    + (misses >= 2 ? " — ignored for this session" : " — next route pass only"));
            }
            // Missing render entities are not proof of a successful block break.
            iterator.remove();
        }
    }

    private void refreshCell(Minecraft minecraft, int x, int z) {
        long cell = key(x, z);
        BlockPos pos = new BlockPos(x, MineLayout.FLOOR_Y, z);
        if (!minecraft.level.hasChunkAt(pos)) {
            unknownCells.add(cell);
            return;
        }
        unknownCells.remove(cell);
        if (isIce(minecraft.level.getBlockState(pos))) iceCells.add(cell);
        else iceCells.remove(cell);
        if (minecraft.level.getBlockState(pos).is(BlockTags.STAIRS)
            || !bodyClear(minecraft, new MotionMath.Vec2(x + 0.5, z + 0.5),
                previewOnly ? MineLayout.FLOOR_Y + 1.8 : minecraft.player.getY(), 1.0, false)) blockedCells.add(cell);
        else blockedCells.remove(cell);
    }

    private void refreshLocalMap(Minecraft minecraft) {
        int x0 = Mth.floor(minecraft.player.getX()), z0 = Mth.floor(minecraft.player.getZ());
        for (int x = Math.max(MineLayout.MIN_X, x0 - 22); x <= Math.min(MineLayout.MAX_X, x0 + 22); x++) {
            for (int z = Math.max(MineLayout.MIN_Z, z0 - 22); z <= Math.min(MineLayout.MAX_Z, z0 + 22); z++) {
                refreshCell(minecraft, x, z);
            }
        }
    }

    private boolean driveCourse(Minecraft minecraft, List<MotionMath.Vec2> course, MotionMath.Vec2 focus) {
        LocalPlayer player = minecraft.player;
        var pose = pose(minecraft);
        MineNavigation.Forecast best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Integer strafe : new Integer[]{null, 0, -1, 1}) {
            var forecast = MineNavigation.forecast(pose, course, planningSpeed(), response.inertia,
                strafe, focus, 24, (a, b) -> segmentClear(minecraft, a, b));
            if (!forecast.clear()) {
                rejectedPoint = forecast.obstruction();
                continue;
            }
            // Follow the same policy that the forecast assumes on future ticks.
            // Otherwise repeated zero-strafe overrides can postpone a turn forever.
            if (strafe == null) { best = forecast; break; }
            double score = forecast.error() + Math.abs(strafe) * 0.35
                + (focus == null ? 0 : forecast.closestTarget() * 0.6);
            if (score < bestScore) { best = forecast; bestScore = score; }
        }
        if (best == null) {
            recover(minecraft, "no safe eased input: " + clearanceReason);
            return false;
        }
        predictedPath.clear();
        predictedPath.addAll(best.points());
        var input = best.input();
        trace.sample(ticks, pose, input, planningSpeed(), response.inertia);
        response.applied(input);
        heading = input.heading();
        player.setYRot(input.yaw());
        setStrafe(minecraft, input.strafe() < 0, input.strafe() > 0);
        double room = focus != null || joiningRoute || ticks < recoveryAimUntil
            || !bodyClear(minecraft, point(player).add(yawHeading(player.getYRot()).scale(4)),
                player.getY(), 1.0 + movementPreset().driftLimit()) ? 0 : 1;
        float desiredPitch = config.lookDownPitch + motion.pitchOffset((int)ticks, config.naturalMovement, movementPreset(), room);
        if (focus != null && target != null) {
            double horizontal = Math.hypot(target.point.x() - player.getX(), target.point.z() - player.getZ());
            if (horizontal < 9.0) {
                // Anticipate the pitch easing delay while crossing at full speed.
                double leadDistance = target.point.subtract(point(player).add(measuredVelocity.scale(1.2))).length();
                float exact = (float)Math.toDegrees(Math.atan2(
                    player.getEyeY() - (floorY + 0.95), Math.max(0.10, leadDistance)));
                desiredPitch += (exact - desiredPitch) * (float)Math.clamp((9.0 - horizontal) / 4.0, 0.0, 1.0);
            }
        }
        if (focus == null) desiredPitch += FlightMotion.pitchJitter((int)ticks, config.naturalMovement)
            * (float)room * movementPreset().pitchLimit() / 2.5F;
        if (ticks < recoveryAimUntil && validRecoveryPitch(minecraft, recoveryPitch)) desiredPitch = recoveryPitch;
        player.setXRot(FlightMotion.easePitch(player.getXRot(), desiredPitch));
        lastDecision = "Following " + (focus == null ? patternLabel() : "validated target pass");
        return true;
    }

    private MineNavigation.Pose pose(Minecraft minecraft) {
        return new MineNavigation.Pose(point(minecraft.player), measuredVelocity, heading, minecraft.player.getYRot());
    }

    private double planningSpeed() { return response.planningSpeed(measuredVelocity); }

    private List<MotionMath.Vec2> courseFrom(MotionMath.Vec2 start, MotionMath.Vec2 join,
                                           MotionMath.Vec2[] path, int index, int direction) {
        List<MotionMath.Vec2> points = new ArrayList<>();
        points.add(start);
        if (join != null && join.subtract(start).length() > 0.01) points.add(join);
        for (int n = 0; n <= path.length; n++) {
            MotionMath.Vec2 p = path[Math.floorMod(index + direction * n, path.length)];
            if (p.subtract(points.getLast()).length() > 0.01) points.add(p);
        }
        return points;
    }

    private List<MotionMath.Vec2> currentCourse() {
        List<MotionMath.Vec2> points = courseFrom(legStart, joinPoint, route, routeIndex, routeStep);
        if (!joiningRoute && target == null && Math.abs(motion.routeOffset) > 0.01) {
            for (int i = 1; i < points.size() - 1; i++) {
                MotionMath.Vec2 tangent = points.get(i + 1).subtract(points.get(i - 1)).normalized();
                points.set(i, points.get(i).add(new MotionMath.Vec2(-tangent.z(), tangent.x()).scale(motion.routeOffset)));
            }
        }
        return points;
    }

    private boolean joinRoute(Minecraft minecraft, MotionMath.Vec2[] candidate, boolean stationary,
                              double minimumDistance, int requiredDirection) {
        MotionMath.Vec2 position = point(minecraft.player);
        record Join(int index, int direction, MotionMath.Vec2 point, double score) { }
        List<Join> joins = new ArrayList<>();
        for (int direction : new int[]{1, -1}) {
            if (requiredDirection != 0 && direction != requiredDirection) continue;
            for (int i = 0; i < candidate.length; i++) {
                int next = Math.floorMod(i + direction, candidate.length);
                MotionMath.Vec2 tangent = candidate[next].subtract(candidate[i]);
                double length = tangent.length();
                if (length < 0.01) continue;
                MotionMath.Vec2 join = MineNavigation.forwardJoin(position, candidate[i], candidate[next], planningSpeed());
                MotionMath.Vec2 approach = join.subtract(position);
                double distance = approach.length();
                double entry = Math.abs(Math.toDegrees(MotionMath.signedAngle(heading, approach)));
                double exit = Math.abs(Math.toDegrees(MotionMath.signedAngle(approach, tangent)));
                if (distance < Math.max(5, minimumDistance) || distance > 65 || exit > 55
                    || !stationary && entry > 70) continue;
                joins.add(new Join(next, direction, join, distance + entry * 0.2 + exit * 0.4));
            }
        }
        joins.sort(Comparator.comparingDouble(Join::score));
        for (Join join : joins.stream().limit(12).toList()) {
            if (!segmentClear(minecraft, position, join.point)) continue;
            List<MotionMath.Vec2> course = courseFrom(position, join.point, candidate, join.index, join.direction);
            var initial = pose(minecraft);
            if (stationary) {
                MotionMath.Vec2 h = join.point.subtract(position).normalized();
                initial = new MineNavigation.Pose(position, new MotionMath.Vec2(0, 0), h,
                    (float)Math.toDegrees(Math.atan2(-h.x(), h.z())));
            }
            int horizon = Math.clamp((int)(position.subtract(join.point).length() / planningSpeed()) + 30, 30, 100);
            var prediction = MineNavigation.forecast(initial, course, planningSpeed(), response.inertia,
                null, null, horizon, (a, b) -> segmentClear(minecraft, a, b));
            if (!prediction.clear()) continue;
            route = candidate;
            routeIndex = join.index;
            routeStep = join.direction;
            legStart = position;
            joinPoint = join.point;
            joiningRoute = true;
            motion.routeOffset = motion.targetRouteOffset = 0;
            predictedPath.clear();
            predictedPath.addAll(prediction.points());
            trace.event(ticks, "Forward route join at " + join.point.x() + ", " + join.point.z());
            return true;
        }
        return false;
    }

    private boolean routeClear(Minecraft minecraft, MotionMath.Vec2[] candidate) {
        if (candidate.length < 8) {
            return false;
        }
        for (int index = 0; index < candidate.length; index++) {
            if (!segmentClear(minecraft, candidate[index], candidate[(index + 1) % candidate.length])) {
                return false;
            }
        }
        double length = 0;
        for (int i = 0; i < candidate.length; i++) length += candidate[i].subtract(candidate[(i + 1) % candidate.length]).length();
        // Joins can select either travel direction. Validate both complete laps,
        // not just the clockwise lap followed by a short reverse entry forecast.
        for (int direction : new int[]{1, -1}) {
            List<MotionMath.Vec2> loop = new ArrayList<>();
            for (int i = 0; i < candidate.length * 2; i++) {
                loop.add(candidate[Math.floorMod(i * direction, candidate.length)]);
            }
            MotionMath.Vec2 h = loop.get(1).subtract(loop.getFirst()).normalized();
            var initial = new MineNavigation.Pose(loop.getFirst(), h.scale(planningSpeed()), h,
                (float)Math.toDegrees(Math.atan2(-h.x(), h.z())));
            if (!MineNavigation.forecast(initial, loop, planningSpeed(), response.inertia, null, null,
                Math.clamp((int)(length / planningSpeed()) + 30, 60, 1200),
                (a, b) -> segmentClear(minecraft, a, b)).clear()) return false;
        }
        return true;
    }

    private boolean segmentClear(Minecraft minecraft, MotionMath.Vec2 start, MotionMath.Vec2 end) {
        if (!config.exclusions.isEmpty() && CoverageMemory.segmentExcluded(config.exclusions, FieldProfileStore.worldKey(minecraft), id(),
            start, end, minecraft.player.getBbWidth() / 2.0 + 1.0)) {
            clearanceReason = "user exclusion";
            return false;
        }
        MotionMath.Vec2 delta = end.subtract(start);
        double distance = delta.length();
        int steps = Math.max(1, (int)Math.ceil(distance / 0.5));
        for (int step = 0; step <= steps; step++) {
            MotionMath.Vec2 point = start.add(delta.scale((double)step / steps));
            int x = Mth.floor(point.x()), z = Mth.floor(point.z());
            if (!safeSurface.contains(key(x, z))) {
                clearanceReason = "edge buffer at " + x + ", " + z;
                return false;
            }
            if (!minecraft.level.hasChunkAt(new BlockPos(x, floorY, z))) {
                unknownCells.add(key(x, z));
                clearanceReason = "unloaded cell at " + x + ", " + z;
                return false;
            }
            if (blockedCells.contains(key(x, z)) || !bodyClear(minecraft, point)) {
                clearanceReason = "live obstacle at " + x + ", " + z;
                return false;
            }
        }
        return true;
    }

    private boolean bodyClear(Minecraft minecraft, MotionMath.Vec2 point) {
        return bodyClear(minecraft, point, previewOnly ? MineLayout.FLOOR_Y + 1.8 : minecraft.player.getY(), 1.0);
    }

    private boolean bodyClear(Minecraft minecraft, MotionMath.Vec2 point, double feetY, double padding) {
        return bodyClear(minecraft, point, feetY, padding, true);
    }

    private boolean bodyClear(Minecraft minecraft, MotionMath.Vec2 point, double feetY, double padding, boolean exclusions) {
        if (exclusions && !config.exclusions.isEmpty() && CoverageMemory.excluded(config.exclusions, FieldProfileStore.worldKey(minecraft), id(),
            point.x(), point.z(), minecraft.player.getBbWidth() / 2.0 + Math.max(1.0, padding))) return false;
        AABB current = minecraft.player.getBoundingBox();
        AABB probe = current.inflate(padding, 0.0, padding)
            .move(point.x() - minecraft.player.getX(), feetY - minecraft.player.getY(),
                point.z() - minecraft.player.getZ());
        int minX = Mth.floor(probe.minX + 1.0E-6);
        int maxX = Mth.floor(probe.maxX - 1.0E-6);
        int minY = Mth.floor(probe.minY + 1.0E-6);
        int maxY = Mth.floor(probe.maxY - 1.0E-6);
        int minZ = Mth.floor(probe.minZ + 1.0E-6);
        int maxZ = Mth.floor(probe.maxZ - 1.0E-6);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos block = new BlockPos(x, y, z);
                    if (!minecraft.level.hasChunkAt(block)) {
                        return false;
                    }
                    BlockState state = minecraft.level.getBlockState(block);
                    var shape = state.getCollisionShape(minecraft.level, block);
                    if (!shape.isEmpty() && shape.bounds().move(x, y, z).intersects(probe)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean entryStepClear(Minecraft minecraft, MotionMath.Vec2 point) {
        if (!config.exclusions.isEmpty() && CoverageMemory.segmentExcluded(config.exclusions, FieldProfileStore.worldKey(minecraft), id(),
            point(minecraft.player), point, minecraft.player.getBbWidth() / 2.0 + 1.0)) return false;
        double feet = minecraft.player.getY();
        double support = Double.NEGATIVE_INFINITY;
        double radius = minecraft.player.getBbWidth() / 2.0;
        for (int x = Mth.floor(point.x() - radius); x <= Mth.floor(point.x() + radius); x++) {
            for (int z = Mth.floor(point.z() - radius); z <= Mth.floor(point.z() + radius); z++) {
                for (int y = Mth.floor(feet + 0.6); y >= Mth.floor(feet) - 2; y--) {
                    BlockPos block = new BlockPos(x, y, z);
                    if (!minecraft.level.hasChunkAt(block)) {
                        return false;
                    }
                    var shape = minecraft.level.getBlockState(block).getCollisionShape(minecraft.level, block);
                    if (!shape.isEmpty()) {
                        support = Math.max(support, y + shape.bounds().maxY);
                        break;
                    }
                }
            }
        }
        return support <= feet + 0.61 && support >= feet - 0.61
            && bodyClear(minecraft, point, support + 0.01, 0.0);
    }

    private boolean borderAt(Minecraft minecraft, int x, int z) {
        BlockPos block = new BlockPos(x, MineLayout.FLOOR_Y, z);
        if (!minecraft.level.hasChunkAt(block)) {
            return false;
        }
        String name = BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(block).getBlock()).getPath();
        return name.startsWith("waxed_") && name.endsWith("cut_copper_stairs");
    }

    private boolean hasSurfaceClearance(int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!surface.contains(key(x + dx, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static GridRect largestRectangle(Set<Long> cells) {
        if (cells.isEmpty()) {
            return null;
        }
        int minX = cells.stream().mapToInt(MineController::cellX).min().orElse(0);
        int maxX = cells.stream().mapToInt(MineController::cellX).max().orElse(0);
        int minZ = cells.stream().mapToInt(MineController::cellZ).min().orElse(0);
        int maxZ = cells.stream().mapToInt(MineController::cellZ).max().orElse(0);
        int width = maxX - minX + 1;
        int[] heights = new int[width];
        GridRect best = null;
        int bestArea = 0;
        for (int z = minZ; z <= maxZ; z++) {
            for (int column = 0; column < width; column++) {
                heights[column] = cells.contains(key(minX + column, z)) ? heights[column] + 1 : 0;
            }
            ArrayDeque<Integer> stack = new ArrayDeque<>();
            for (int column = 0; column <= width; column++) {
                int height = column == width ? 0 : heights[column];
                while (!stack.isEmpty() && heights[stack.peek()] > height) {
                    int top = stack.pop();
                    int left = stack.isEmpty() ? 0 : stack.peek() + 1;
                    int rectangleWidth = column - left;
                    int rectangleHeight = heights[top];
                    int area = rectangleWidth * rectangleHeight;
                    if (rectangleWidth >= MIN_ROUTE_SPAN && rectangleHeight >= MIN_ROUTE_SPAN
                        && area > bestArea) {
                        bestArea = area;
                        best = new GridRect(minX + left, minX + column - 1,
                            z - rectangleHeight + 1, z);
                    }
                }
                if (column < width) {
                    stack.push(column);
                }
            }
        }
        return best;
    }

    private void recover(Minecraft minecraft, String reason) {
        if (state == State.MINING && minecraft.player != null && minecraft.level != null
            && minecraft.player.getAbilities().flying && MineLayout.lowFlightHeight(minecraft.player.getY())
            && route.length > 2 && activeWorldKey != null && activeWorldKey.equals(FieldProfileStore.worldKey(minecraft))
            && localAttempts++ < 3) {
            if (target != null) {
                targetCooldowns.put(target.block, (int)ticks + TARGET_COOLDOWN_TICKS);
                target = null;
            }
            trace.event(ticks, "Local recovery: " + reason);
            lastDecision = reason;
            localRecoveryDeadline = ticks + 60;
            setState(State.LOCAL_RECOVERY);
            tickLocalRecovery(minecraft);
            return;
        }
        teleportRecovery(minecraft, reason);
    }

    private void teleportRecovery(Minecraft minecraft, String reason) {
        if (++recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            stop(minecraft, "Mine Harvester stopped after repeated recoveries: " + reason);
            return;
        }
        transitAttempts = 0;
        beginTransit(minecraft, "Safety recovery " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS
            + " (" + reason + ")");
    }

    private void tickLocalRecovery(Minecraft minecraft) {
        releaseAll(minecraft);
        if (minecraft.player == null || !minecraft.player.getAbilities().flying
            || !MineLayout.lowFlightHeight(minecraft.player.getY())) {
            teleportRecovery(minecraft, "local recovery lost flight");
            return;
        }
        measuredVelocity = new MotionMath.Vec2(minecraft.player.getDeltaMovement().x, minecraft.player.getDeltaMovement().z);
        if (stateAge() % 5 == 0) {
            refreshLocalMap(minecraft);
            boolean stationary = measuredVelocity.length() < 0.12 && stateAge() >= 10;
            if (joinRoute(minecraft, route, stationary, 0, 0)) {
                localRecoveries++;
                progressCheckTick = (int)ticks;
                progressCheckX = minecraft.player.getX();
                progressCheckZ = minecraft.player.getZ();
                lastPosition = point(minecraft.player);
                response.reset();
                setState(stationary ? State.ALIGNING : State.MINING);
                status = "Local route rejoin ready";
                if (!stationary) {
                    holdMiningInputs(minecraft);
                    driveCourse(minecraft, currentCourse(), null);
                }
                return;
            }
        }
        status = "Local recovery • waiting for a clear forward join";
        if (ticks >= localRecoveryDeadline) teleportRecovery(minecraft, "no safe local rejoin: " + clearanceReason);
    }

    private void resetSession() {
        coverage.clear();
        coverageWorldKey = null;
        recoveryAimUntil = 0;
        ticks = 0;
        blocksMined = 0;
        fossilsMined = 0;
        iceMined = 0;
        transitAttempts = 0;
        recoveryAttempts = 0;
        recentBreaks.clear();
        shinyCountCooldowns.clear();
        targetCooldowns.clear();
        targetMisses.clear();
        ignoredTargets.clear();
        pendingTargets.clear();
        trace.clear();
        response.reset();
        response.speed = 0.8;
        response.inertia = 0.70;
        missedPasses = targetAttempts = localRecoveries = localAttempts = riskSkips = 0;
        rejectedPoint = null;
        sessionStartedNanos = System.nanoTime();
        sessionStoppedNanos = 0L;
    }

    private void holdMiningInputs(Minecraft minecraft) {
        // Do not release on a stale render hit after a block breaks. The attack
        // callback checks the actual block against the mine boundary and stairs.
        boolean attack = isMining() && minecraft.gui.screen() == null;
        if (attack) {
            minecraft.options.keyAttack.setDown(true);
            modHoldingAttack = true;
        } else {
            releaseAttack(minecraft);
        }
        minecraft.options.keyUp.setDown(true);
        minecraft.options.keySprint.setDown(true);
        minecraft.player.setSprinting(true);
        modHoldingForward = true;
        modHoldingSprint = true;
    }

    public boolean mayAttack(Minecraft minecraft, BlockPos block) {
        if (!isRunning()) {
            return true;
        }
        return state == State.MINING && minecraft.level != null
            && activeWorldKey != null && activeWorldKey.equals(FieldProfileStore.worldKey(minecraft))
            && MineLayout.interior(block.getX(), block.getY(), block.getZ())
            && minecraft.level.hasChunkAt(block) && !minecraft.level.getBlockState(block).isAir()
            && !CoverageMemory.excluded(config.exclusions, activeWorldKey, id(), block.getX() + 0.5, block.getZ() + 0.5, 0)
            && !minecraft.level.getBlockState(block).is(BlockTags.STAIRS);
    }

    private void setStrafe(Minecraft minecraft, boolean left, boolean right) {
        minecraft.options.keyLeft.setDown(left);
        minecraft.options.keyRight.setDown(right);
        modHoldingLeft = left;
        modHoldingRight = right;
    }

    private void releaseAttack(Minecraft minecraft) {
        if (modHoldingAttack) {
            minecraft.options.keyAttack.setDown(false);
            modHoldingAttack = false;
            if (minecraft.gameMode != null) {
                minecraft.gameMode.stopDestroyBlock();
            }
        }
    }

    private void releaseVertical(Minecraft minecraft) {
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyShift.setDown(false);
        modHoldingSneak = false;
    }

    private void releaseMovement(Minecraft minecraft) {
        if (modHoldingForward) {
            minecraft.options.keyUp.setDown(false);
            modHoldingForward = false;
        }
        if (modHoldingSprint) {
            minecraft.options.keySprint.setDown(false);
            modHoldingSprint = false;
        }
        if (modHoldingLeft || modHoldingRight) {
            minecraft.options.keyLeft.setDown(false);
            minecraft.options.keyRight.setDown(false);
            modHoldingLeft = false;
            modHoldingRight = false;
        }
        if (minecraft.player != null) {
            minecraft.player.setSprinting(false);
        }
    }

    private void releaseAll(Minecraft minecraft) {
        releaseAttack(minecraft);
        releaseMovement(minecraft);
        releaseVertical(minecraft);
    }

    void stop(Minecraft minecraft, String reason) {
        lastDecision = reason;
        trace.event(ticks, reason);
        stopSilently(minecraft);
        status = reason;
        exportDiagnostics(minecraft, false);
        message(minecraft, reason);
    }

    private void stopSilently(Minecraft minecraft) {
        if (isActive() && sessionStoppedNanos == 0L) {
            sessionStoppedNanos = System.nanoTime();
        }
        releaseAll(minecraft);
        restoreFocusPause(minecraft);
        camera.restore(minecraft);
        manualPause = false;
        state = State.OFF;
        target = null;
        activeWorldKey = null;
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

    private void pruneBreaks() {
        long cutoff = System.nanoTime() - 1_000_000_000L;
        while (!recentBreaks.isEmpty() && recentBreaks.peekFirst() < cutoff) {
            recentBreaks.removeFirst();
        }
    }

    public double blocksPerSecond() {
        pruneBreaks();
        return recentBreaks.size();
    }

    public double blocksPerHour() {
        if (sessionStartedNanos == 0L || blocksMined == 0L) {
            return 0.0;
        }
        long now = sessionStoppedNanos == 0L ? System.nanoTime() : sessionStoppedNanos;
        double seconds = Math.max(1.0, (now - sessionStartedNanos) / 1_000_000_000.0);
        return blocksMined * 3600.0 / seconds;
    }

    public long totalBlocksMined() { return blocksMined; }
    public long shinyHarvests() { return fossilsMined; }
    public double shiniesPerHour() {
        return blocksMined == 0 ? 0.0 : blocksPerHour() * fossilsMined / blocksMined;
    }

    public void previewMine(Minecraft minecraft) {
        if (isActive()) {
            lastDecision = "Live route shown; preview does not change active inputs";
            return;
        }
        previewOnly = true;
        predictedPath.clear();
        fossilTargets.clear();
        iceTargets.clear();
        iceTargetCount = 0;
        target = null;
        joinPoint = null;
        routeArea = null;
        activeWorldKey = null;
        boolean verified = minecraft.player != null && minecraft.level != null && scanMine(minecraft);
        String world = FieldProfileStore.worldKey(minecraft);
        if (world == null || !world.equals(coverageWorldKey)) coverage.clear();
        coverageWorldKey = world;
        if (verified) {
            activeWorldKey = FieldProfileStore.worldKey(minecraft);
            lastDecision = "Preview: loaded geometry verified; no movement or attack";
        } else {
            surface.clear();
            safeSurface.clear();
            blockedCells.clear();
            unknownCells.clear();
            route = MotionMath.roundedRectangle(MineLayout.MIN_X + 7.5, MineLayout.MAX_X - 6.5,
                MineLayout.MIN_Z + 7.5, MineLayout.MAX_Z - 6.5, 22, 12);
            lastDecision = "Saved-layout preview only; live chunks are not fully verified";
        }
        perimeter = route.clone();
        var choice = config.mineRouteMode.pattern == null ? MinePattern.PERIMETER : config.mineRouteMode.pattern;
        var candidate = MineLayout.variedPath(choice, perimeter, random, config.mineRouteVariation / 100.0);
        if (!verified) {
            route = candidate;
        } else if (routeClear(minecraft, candidate)) {
            route = candidate;
        } else {
            lastDecision = "Preview: selected pattern is unsafe; showing the safe perimeter";
        }
    }

    public void exportDiagnostics(Minecraft minecraft, boolean notify) {
        var path = FabricLoader.getInstance().getConfigDir().resolve("cropium/mine-diagnostics-last.csv");
        try {
            trace.write(path);
            if (notify) message(minecraft, "Mine diagnostics saved to " + path);
        } catch (java.io.IOException exception) {
            if (notify) message(minecraft, "Could not save mine diagnostics: " + exception.getMessage());
        }
    }

    public String decisionStatus() { return isRunning() && state != State.MINING ? status : lastDecision; }
    public List<String> recoveryHistory() { return trace.events(); }
    public String outcomeStatus() {
        return targetAttempts + " passes • " + missedPasses + " misses • " + ignoredTargets.size()
            + " ignored • " + riskSkips + " risky skips";
    }
    public String responseStatus() {
        return String.format(Locale.ROOT, "Model: %.2f b/t • inertia %.2f • %d local rejoins",
            response.speed, response.inertia, localRecoveries);
    }
    public List<MotionMath.Vec2> routePreview() { return List.of(route); }
    public List<MotionMath.Vec2> coverageBounds() {
        return List.of(new MotionMath.Vec2(MineLayout.MIN_X - 1, MineLayout.MIN_Z - 1),
            new MotionMath.Vec2(MineLayout.MAX_X + 1, MineLayout.MAX_Z + 1));
    }
    public List<MotionMath.Vec2> coverageRoute() { return routePreview(); }

    public String routeMapKey() {
        return MineLayout.MIN_X + ":" + MineLayout.MAX_X + ":" + MineLayout.MIN_Z + ":" + MineLayout.MAX_Z + ":" + MineLayout.FLOOR_Y;
    }

    /** Static editor check. Runtime still checks chunks, collision boxes, momentum and the join. */
    public String tracedRouteProblem(Minecraft minecraft, List<MotionMath.Vec2> path) {
        if (minecraft.level == null) return "Join the saved world first";
        var bad = TracedRoute.firstUnsafe(path, p ->
            p.x() >= MineLayout.MIN_X + EDGE_CLEARANCE + 1 && p.x() < MineLayout.MAX_X - EDGE_CLEARANCE
            && p.z() >= MineLayout.MIN_Z + EDGE_CLEARANCE + 1 && p.z() < MineLayout.MAX_Z - EDGE_CLEARANCE
            && !CoverageMemory.excluded(config.exclusions, FieldProfileStore.worldKey(minecraft), id(), p.x(), p.z(), 1.4)
            && !blockedCells.contains(key(Mth.floor(p.x()), Mth.floor(p.z()))));
        return bad == null ? null : "Outside safe core / obstacle near " + (int)bad.x() + ", " + (int)bad.z();
    }
    public int coverageColor(int x, int z) {
        var block = new BlockPos(x, MineLayout.FLOOR_Y, z);
        if (coverage.recent(x, z, ticks)) return CoverageMemory.RECENT;
        if (ignoredTargets.contains(block)) return CoverageMemory.IGNORED;
        if (targetMisses.containsKey(block)) return CoverageMemory.MISSED;
        if (fossilTargets.containsKey(block)) return CoverageMemory.GLOW;
        if (iceCells.contains(key(x, z))) return CoverageMemory.ICE;
        return mapColor(x, z);
    }

    public boolean validateExclusion(Minecraft minecraft, ExclusionZone zone) {
        if (isRunning() || minecraft.player == null || minecraft.level == null || zone == null || !zone.valid()) return false;
        String world = FieldProfileStore.worldKey(minecraft);
        if (!zone.worldKey().equals(world) || !zone.moduleId().equals(id())) return false;
        double padding = minecraft.player.getBbWidth() / 2.0 + 1.0;
        List<ExclusionZone> zones = new ArrayList<>(config.exclusions == null ? List.of() : config.exclusions);
        zones.add(zone);
        if (CoverageMemory.excluded(zones, world, id(), minecraft.player.getX(), minecraft.player.getZ(), padding)) return false;
        var cursor = new MotionMath.Vec2(431.271, 1527.239);
        for (var entry : MineLayout.ENTRY) {
            if (CoverageMemory.segmentExcluded(zones, world, id(), cursor, entry, padding)) return false;
            cursor = entry;
        }
        var saved = perimeter.length >= 3 ? perimeter : MotionMath.roundedRectangle(
            MineLayout.MIN_X + 7.5, MineLayout.MAX_X - 6.5, MineLayout.MIN_Z + 7.5, MineLayout.MAX_Z - 6.5, 22, 12);
        for (var candidate : new MotionMath.Vec2[][]{route, saved}) {
            if (candidate.length < 3) continue;
            boolean clear = true, entryClear = false, playerClear = false;
            for (int i = 0; i < candidate.length; i++) {
                if (CoverageMemory.segmentExcluded(zones, world, id(), candidate[i], candidate[(i + 1) % candidate.length], padding)) clear = false;
                if (!CoverageMemory.segmentExcluded(zones, world, id(), cursor, candidate[i], padding)) entryClear = true;
                if (!CoverageMemory.segmentExcluded(zones, world, id(), point(minecraft.player), candidate[i], padding)) playerClear = true;
            }
            if (clear && entryClear && playerClear) return true;
        }
        return false;
    }
    public List<MotionMath.Vec2> forecastPreview() { return List.copyOf(predictedPath); }
    public MotionMath.Vec2 rejectedPoint() { return rejectedPoint; }
    public List<BlockPos> fossilPreview() { return List.copyOf(fossilTargets.keySet()); }
    public boolean ignored(BlockPos block) { return ignoredTargets.contains(block); }
    public int mapColor(int x, int z) {
        if (MineLayout.rim(x, z)) return 0xFF497E71;
        if (unknownCells.contains(key(x, z)) || !surface.contains(key(x, z))) return 0xFF424252;
        if (blockedCells.contains(key(x, z))) return 0xFFFF667A;
        return safeSurface.contains(key(x, z)) ? 0xFF253C42 : 0xFF66512F;
    }

    private int nextIndex(int index, int offset) {
        return Math.floorMod(index + offset, route.length);
    }

    private static boolean isIce(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE
            || block == Blocks.FROSTED_ICE;
    }

    private static MotionMath.Vec2 point(LocalPlayer player) {
        return new MotionMath.Vec2(player.getX(), player.getZ());
    }

    private static MotionMath.Vec2 yawHeading(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new MotionMath.Vec2(-Math.sin(yaw), Math.cos(yaw)).normalized();
    }

    private static long key(int x, int z) {
        return (long)x << 32 ^ z & 0xFFFFFFFFL;
    }

    private static int cellX(long key) {
        return (int)(key >> 32);
    }

    private static int cellZ(long key) {
        return (int)key;
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

    private static String shortPos(BlockPos position) {
        return position.getX() + ", " + position.getY() + ", " + position.getZ();
    }

    private void setState(State next) {
        state = next;
        stateSince = ticks;
    }

    private long stateAge() {
        return ticks - stateSince;
    }

    private enum State {
        OFF("idle"),
        TELEPORTING("teleporting"),
        ENTERING("entering"),
        STARTING_FLIGHT("starting flight"),
        WAITING_FOR_FLIGHT("checking flight"),
        ADJUSTING_HEIGHT("settling flight"),
        CALIBRATING("scanning"),
        ALIGNING("aligning"),
        MINING("mining"),
        LOCAL_RECOVERY("local recovery"),
        PAUSED("paused");

        private final String label;

        State(String label) {
            this.label = label;
        }
    }

    private enum TargetKind {
        FOSSIL("Fossil"),
        ICE("Ice");

        private final String label;

        TargetKind(String label) {
            this.label = label;
        }
    }

    private static final class TargetIntercept {
        private final BlockPos block;
        private final TargetKind kind;
        private final MotionMath.Vec2 point;
        private final MotionMath.Vec2 pass;
        private final int rejoinIndex;
        private final MotionMath.Vec2 approach;
        private MotionMath.Vec2 start;
        private final int startedTick;
        private double closestDistance;
        private boolean harvested;

        private TargetIntercept(BlockPos block, TargetKind kind, MotionMath.Vec2 point,
                                MotionMath.Vec2 pass, int rejoinIndex, MotionMath.Vec2 approach,
                                int startedTick, double closestDistance) {
            this.block = block;
            this.kind = kind;
            this.point = point;
            this.pass = pass;
            this.rejoinIndex = rejoinIndex;
            this.approach = approach;
            this.startedTick = startedTick;
            this.closestDistance = closestDistance;
        }
    }

    private record PendingTarget(TargetKind kind, int dueTick) { }

    private record TargetChoice(BlockPos block, TargetKind kind, MotionMath.Vec2 point,
                                MotionMath.Vec2 pass, int rejoinIndex, MotionMath.Vec2 approach,
                                double distance, double score) {
    }

    private record GridRect(int minX, int maxX, int minZ, int maxZ) {
        private int width() {
            return maxX - minX + 1;
        }

        private int depth() {
            return maxZ - minZ + 1;
        }

        private GridRect inset(int amount) {
            return new GridRect(minX + amount, maxX - amount, minZ + amount, maxZ - amount);
        }
    }
}
