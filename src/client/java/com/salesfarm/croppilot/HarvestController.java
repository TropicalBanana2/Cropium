package com.salesfarm.croppilot;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class HarvestController implements CropiumModule {
    private static final Gson GSON = new Gson();
    private static final String IMPORTED_MAP_RESOURCE = "/assets/crop-pilot/maps/sales_minehut.json";
    private static final String IMPORTED_OBSTACLE_RESOURCE = "/assets/crop-pilot/maps/sales_minehut_obstacles.json";
    private static final String IMPORTED_ROUTE_RESOURCE = "/assets/crop-pilot/maps/sales_minehut_safe_routes.json";
    private static final String AUTOMATIC_WORLD_MARKER = "sales.minehut.gg";
    private static final int MAX_BOUND_SIZE = 256;
    private static final int MIN_TURN_TICKS = 14;
    private static final int MAX_ROUTE_TURN_TICKS = 18;
    private static final int MAX_BOUNDARY_TURN_TICKS = 22;
    private static final int BOUNCE_ESCAPE_GRACE_TICKS = 6;
    private static final int BOUNCE_COLLISION_TIMEOUT_TICKS = 10;
    private static final int BOUNCE_TIMEOUT_TICKS = 40;
    private static final int FARM_TELEPORT_TIMEOUT_TICKS = 120;
    private static final int FARM_ENTRY_TIMEOUT_TICKS = 120;
    private static final int MAX_FARM_RECOVERY_ATTEMPTS = 2;
    private static final double FARM_TELEPORT_STEP_DISTANCE = 1.25;
    private static final double FARM_TELEPORT_TOTAL_DISTANCE = 8.0;
    private static final double FARM_ENTRY_DISTANCE = 5.0;
    private static final double SAFE_JOIN_RUNWAY = 7.0;
    private static final double ROUTE_BOUND_TOLERANCE = 0.0;
    private static final double EMERGENCY_BOUND_TOLERANCE = 0.35;
    private static final double TURN_SAFETY_MARGIN = 1.75;
    private static final double TURN_STRAFE_WEIGHT = 1.0;
    private static final int GLOW_SCAN_INTERVAL_TICKS = 5;
    private static final int GLOW_STALE_TICKS = 30;
    private static final int GLOW_TARGET_COOLDOWN_TICKS = 400;
    private static final int GLOW_FAILED_COOLDOWN_TICKS = 300;
    private static final int GLOW_MISSES_BEFORE_IGNORE = 2;
    private static final int GLOW_ATTEMPT_TIMEOUT_TICKS = 200;
    private static final int GLOW_POST_PASS_OBSERVE_TICKS = 35;
    private static final int GLOW_REJOIN_TIMEOUT_TICKS = 200;
    private static final double GLOW_SCAN_RADIUS = 256.0;
    private static final double GLOW_MAX_CROSS_TRACK = 96.0;
    private static final double GLOW_MIN_FORWARD = 4.0;
    private static final double GLOW_MAX_FORWARD = 64.0;
    private static final double GLOW_REJOIN_RUNWAY = 8.0;
    private static final double GLOW_DIRECT_ALIGNMENT = Math.toRadians(10.0);
    private static final double GLOW_DIRECT_PASS_MARGIN = 1.75;
    private static final int SCAN_CELLS_PER_TICK = 2_048;
    private static final int SCAN_RETRY_TICKS = 20;
    private static final byte FIELD_UNKNOWN = 0;
    private static final byte FIELD_CROP = 1;
    private static final byte FIELD_CLEAR = 2;
    private static final byte FIELD_OBSTACLE = 3;

    private final CropPilotConfig config;
    private final FieldProfileStore profiles;
    private final Random random = new Random();
    private final FlightMotion motion = new FlightMotion(random);
    private final CoverageMemory coverage = new CoverageMemory();
    private List<MotionMath.Vec2> coveragePreview = List.of();
    private List<BlockPos> fieldOverlayTiles = List.of();
    private byte[] overlayFieldCells;
    private long nextOverlayRefresh;
    private String fieldWorldKey;
    private boolean preparingStart, modHoldingJump, modHoldingSneak;
    private int preparedFlightAttempts, recoveryAimUntil;
    private float recoveryPitch;
    private MotionMath.Vec2 lastFarmEntryStart, lastFarmEntryEnd;
    private final Set<BlockPos> breakPreview = new HashSet<>();
    private final Map<BlockPos, GlowingPlant> glowingPlants = new HashMap<>();
    private final Map<BlockPos, Integer> glowTargetCooldowns = new HashMap<>();
    private final Map<BlockPos, Integer> glowFailureCounts = new HashMap<>();
    private final Map<BlockPos, Integer> shinyCountCooldowns = new HashMap<>();
    private final Set<BlockPos> ignoredGlowTargets = new HashSet<>();
    private final Deque<Long> recentBreaks = new ArrayDeque<>();
    private final Deque<RememberedObstacle> obstacleMemory = new ArrayDeque<>();
    private final Deque<MotionMath.Vec2> specialRouteWaypoints = new ArrayDeque<>();
    private MotionMath.Vec2[] specialRouteTemplate;
    private MotionMath.Vec2[] pendingTurnRoute;
    private SpecialRoute pendingTurnRouteType;
    private Mode mode = Mode.OFF;
    private Pattern pattern = Pattern.STRAIGHT;
    private SpecialRoute specialRoute = SpecialRoute.NONE;
    private TracedRoute activeTrace;
    private RecoveryPhase recoveryPhase = RecoveryPhase.NONE;
    private BlockPos firstCorner;
    private Bounds bounds;
    private MotionMath.Vec2 heading = new MotionMath.Vec2(0.0, 1.0);
    private MotionMath.Vec2 legStart;
    private MotionMath.Vec2 routeTarget;
    private MotionMath.Vec2 resumeTarget;
    private MotionMath.Vec2 detourRejoinTarget;
    private Turn turn;
    private boolean detouring;
    private boolean longAxisX;
    private int routeDirection = 1;
    private int laneSweepDirection = 1;
    private int detourStage;
    private int completedPasses;
    private int ticks;
    private int nextObstacleAvoidanceTick;
    private int nextSpecialRouteTick;
    private int specialRouteEndTick;
    private int lastBoundaryTurnSign;
    private int repeatedBoundaryTurnSigns;
    private int recoveryStartedTick;
    private int farmRecoveryAttempts;
    private final FreeLookCamera camera = new FreeLookCamera();
    private Boolean previousPauseOnLostFocus;
    private boolean modHoldingAttack;
    private boolean modHoldingForward;
    private boolean modHoldingSprint;
    private boolean modHoldingLeft;
    private boolean modHoldingRight;
    private double calibratedSpeedPerTick = 0.45;
    private double calibratedTurnRadius;
    private double routeCrossTrack;
    private double previousDetourDistance = Double.POSITIVE_INFINITY;
    private AABB detectedObstacleBox;
    private Vec3 recoveryOrigin;
    private Vec3 recoveryLastPosition;
    private MotionMath.Vec2 farmEntryStart;
    private int detectedObstacleTick = -1_000_000;
    private int detourCount;
    private long blocksMined;
    private long sessionStartedNanos;
    private long sessionStoppedNanos;
    private long bpsWindowStartedNanos;
    private int bpsWindowBlocks;
    private int consecutiveLowBpsWindows;
    private String activeWorldKey;
    private String scanWorldKey;
    private byte[] fieldCells;
    private AABB scanBody;
    private boolean scanningField;
    private boolean fieldScanComplete;
    private int scanCursor;
    private int scanKnownCells;
    private int scanCropCells;
    private int scanObstacleCells;
    private int scanPassStartKnown;
    private int nextScanTick;
    private List<MotionMath.Vec2[]> importedPerimeterRoutes = List.of();
    private String importedMapName;
    private String preparedWorldKey;
    private GlowObservation glowObservation;
    private GlowIntercept glowIntercept;
    private String glowRoutingStatus = "Waiting for a loaded glowing plant";
    private int nextGlowScanTick;
    private long glowingPlantsHarvested;

    public HarvestController(CropPilotConfig config, FieldProfileStore profiles) {
        this.config = config;
        this.profiles = profiles;
    }

    @Override
    public String id() {
        return "harvest";
    }

    @Override
    public Component name() {
        return Component.literal("Crop Harvester");
    }

    @Override
    public Component description() {
        return Component.literal("Full-speed perimeter farming with safe glow interception");
    }

    @Override
    public boolean isActive() {
        return mode == Mode.RUNNING || mode == Mode.PAUSED;
    }

    public boolean isRunning() {
        return mode == Mode.RUNNING;
    }

    public boolean isHarvesting() {
        return mode == Mode.RUNNING && recoveryPhase == RecoveryPhase.NONE;
    }

    public boolean isPaused() {
        return mode == Mode.PAUSED;
    }

    private MovementPreset movementPreset() {
        return config.movementPreset == null ? MovementPreset.BALANCED : config.movementPreset;
    }

    public String preflightStatus(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return "Join the server first";
        if (isPaused()) return "Paused; resume or stop the current workflow";
        if (minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof CropiumScreen)) return "Close the open container or screen";
        if (!savedFieldMatches(minecraft)) return "Load a complete saved farm map for this world";
        if (!minecraft.player.getAbilities().mayfly && importedFieldContains(minecraft.player.getX(), minecraft.player.getZ(), .3))
            return "Enable server flight at the farm first";
        return "Ready";
    }

    private boolean savedFieldMatches(Minecraft minecraft) {
        return bounds != null && fieldScanComplete && fieldCells != null && scanCropCells >= 3
            && fieldCells.length == bounds.width() * bounds.depth() && fieldWorldKey != null
            && fieldWorldKey.equals(FieldProfileStore.worldKey(minecraft));
    }

    public boolean preparedStart(Minecraft minecraft) {
        String status = preflightStatus(minecraft);
        if (!status.equals("Ready")) { message(minecraft, status); return false; }
        if (isRunning()) return true;
        if (minecraft.gui.screen() instanceof CropiumScreen) minecraft.gui.setScreen(null);
        var player = minecraft.player;
        boolean inside = importedFieldContains(player.getX(), player.getZ(), player.getBbWidth() / 2.0);
        if (inside && player.getAbilities().flying && preparedFlightReady(player, 8)) return start(minecraft);
        resetRecoveryState();
        resetCounters();
        activeWorldKey = fieldWorldKey;
        mode = Mode.RUNNING;
        preparingStart = true;
        keepRunningWhenUnfocused(minecraft);
        if (inside) beginPreparedFlight(minecraft);
        else beginFarmRecovery(minecraft, "preparing saved farm start");
        return isRunning();
    }

    public boolean recoveryReady() { return isHarvesting() && turn == null && !detouring; }

    public void exclusionsChanged() {
        if (isRunning()) return;
        glowIntercept = null;
        glowTargetCooldowns.clear();
        turn = null;
        pendingTurnRoute = specialRouteTemplate = null;
        pendingTurnRouteType = null;
        specialRouteWaypoints.clear();
        specialRoute = SpecialRoute.NONE;
        legStart = routeTarget = resumeTarget = detourRejoinTarget = null;
        detouring = false;
        motion.routeOffset = motion.targetRouteOffset = 0;
        recoveryAimUntil = 0;
    }

    public String miningTargetStatus(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) return "World unavailable";
        if (isPaused()) return "Paused";
        if (minecraft.gui.screen() != null) return "GUI open";
        if (!isHarvesting()) return recoveryPhase == RecoveryPhase.NONE ? "Idle" : recoveryPhase.label;
        if (!savedFieldMatches(minecraft)) return "World or saved map changed";
        if (!minecraft.player.getAbilities().flying) return "Flight inactive";
        if (minecraft.player.horizontalCollision) return "Obstacle contact";
        if (excluded(minecraft.player.getX(), minecraft.player.getZ(), minecraft.player.getBbWidth() / 2.0 + config.obstacleClearance)) return "Inside exclusion padding";
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return "Aiming at air";
        if (minecraft.player.getEyePosition().distanceToSqr(hit.getLocation()) > Math.pow(minecraft.player.blockInteractionRange(), 2)) return "Crop out of reach";
        if (minecraft.level.getBlockState(hit.getBlockPos()).isAir()) return "Air; waiting for regrowth";
        return validMiningBlock(minecraft, hit.getBlockPos()) ? "Crop in reach" : "Aim misses mapped crop";
    }

    public boolean safeAttack(Minecraft minecraft) {
        return isHarvesting() && minecraft.player != null && minecraft.level != null
            && minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && validMiningBlock(minecraft, hit.getBlockPos());
    }

    public boolean mayAttack(Minecraft minecraft, BlockPos block) {
        if (!isRunning()) return true;
        return isHarvesting() && minecraft.level != null && validMiningBlock(minecraft, block);
    }

    private boolean validMiningBlock(Minecraft minecraft, BlockPos block) {
        if (bounds == null || fieldWorldKey == null || !fieldWorldKey.equals(FieldProfileStore.worldKey(minecraft))
            || block.getY() != bounds.cropY || fieldCell(block.getX(), block.getZ()) != FIELD_CROP
            || excluded(block.getX() + 0.5, block.getZ() + 0.5, 0)
            || !minecraft.level.hasChunkAt(block)) return false;
        // The saved crop footprint is authoritative: server crops can use non-vanilla
        // block types. Keep structures, exclusions and the ground below it protected.
        BlockState state = minecraft.level.getBlockState(block);
        return !state.isAir() && !state.is(BlockTags.STAIRS);
    }

    public void recoverMiningInput(Minecraft minecraft, boolean escalate) {
        if (!recoveryReady() || minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null
            || minecraft.gui.screen() != null || !savedFieldMatches(minecraft)) return;
        releaseAttack(minecraft);
        recoveryAimUntil = 0;
        if (escalate) { beginObstacleRecovery(minecraft, "mining input recovery requested"); return; }
        if (!minecraft.player.getAbilities().flying || minecraft.player.horizontalCollision) return;
        for (float change : new float[]{0, 2, -2, 4, -4, 6, -6, 8, -8}) {
            float pitch = FlightMotion.boundedMiningPitch(minecraft.player.getXRot(), change);
            if (!Float.isFinite(pitch)) continue;
            if (validRecoveryPitch(minecraft, pitch)) {
                recoveryPitch = pitch;
                recoveryAimUntil = ticks + 30;
                minecraft.player.setXRot(FlightMotion.easePitch(minecraft.player.getXRot(), pitch));
                return;
            }
        }
    }

    private boolean validRecoveryPitch(Minecraft minecraft, float pitch) {
        var eye = minecraft.player.getEyePosition();
        double yaw = Math.toRadians(minecraft.player.getYRot()), radians = Math.toRadians(pitch);
        double range = minecraft.player.blockInteractionRange();
        var end = eye.add(-Math.sin(yaw) * Math.cos(radians) * range, -Math.sin(radians) * range,
            Math.cos(yaw) * Math.cos(radians) * range);
        var hit = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.BLOCK && validMiningBlock(minecraft, hit.getBlockPos());
    }

    public List<MotionMath.Vec2> coverageBounds() {
        return bounds == null ? List.of() : List.of(new MotionMath.Vec2(bounds.minX, bounds.minZ),
            new MotionMath.Vec2(bounds.maxX, bounds.maxZ));
    }

    public String routeMapKey() { return bounds == null ? "" : boundsKey(); }

    public String tracedRouteProblem(Minecraft minecraft, List<MotionMath.Vec2> path) {
        if (bounds == null || !fieldScanComplete || minecraft.level == null
            || !java.util.Objects.equals(fieldWorldKey, FieldProfileStore.worldKey(minecraft))) return "Preview / scan this farm first";
        var bad = TracedRoute.firstUnsafe(path, p -> importedFieldContains(p.x(), p.z(), 3.0));
        return bad == null ? null : "Leave 3 blocks around farm edges / obstacles near " + (int)bad.x() + ", " + (int)bad.z();
    }

    public List<MotionMath.Vec2> coverageRoute() {
        if ((isRunning() || isPaused()) && routeTarget != null && legStart != null) {
            var route = new ArrayList<MotionMath.Vec2>();
            route.add(legStart);
            route.add(routeTarget);
            route.addAll(specialRouteWaypoints);
            return List.copyOf(route);
        }
        return coveragePreview;
    }

    public int coverageColor(int x, int z) {
        if (bounds == null) return 0xFF424252;
        var block = new BlockPos(x, bounds.cropY, z);
        if (coverage.recent(x, z, ticks)) return CoverageMemory.RECENT;
        if (ignoredGlowTargets.contains(block)) return CoverageMemory.IGNORED;
        if (glowFailureCounts.containsKey(block)) return CoverageMemory.MISSED;
        if (glowingPlants.containsKey(block)) return CoverageMemory.GLOW;
        return switch (fieldCell(x, z)) {
            case FIELD_CROP -> 0xFF253C42;
            case FIELD_CLEAR -> 0xFF66512F;
            case FIELD_OBSTACLE -> 0xFFFF667A;
            default -> 0xFF424252;
        };
    }

    private boolean excluded(double x, double z, double padding) {
        return CoverageMemory.excluded(config.exclusions, fieldWorldKey, id(), x, z, padding);
    }

    private boolean exclusionSegmentClear(Minecraft minecraft, MotionMath.Vec2 start, MotionMath.Vec2 end) {
        if (config.exclusions.isEmpty()) return true;
        return !CoverageMemory.segmentExcluded(config.exclusions, FieldProfileStore.worldKey(minecraft), id(),
            start, end, minecraft.player.getBbWidth() / 2.0 + config.obstacleClearance);
    }

    public boolean validateExclusion(Minecraft minecraft, ExclusionZone zone) {
        if (isRunning() || minecraft.player == null || minecraft.level == null || !savedFieldMatches(minecraft)
            || zone == null || !zone.valid() || !zone.worldKey().equals(fieldWorldKey) || !zone.moduleId().equals(id())) return false;
        var zones = new ArrayList<>(config.exclusions == null ? List.<ExclusionZone>of() : config.exclusions);
        zones.add(zone);
        var player = minecraft.player;
        double padding = player.getBbWidth() / 2.0 + config.obstacleClearance;
        var position = new MotionMath.Vec2(player.getX(), player.getZ());
        if (CoverageMemory.excluded(zones, fieldWorldKey, id(), position.x(), position.z(), padding)) return false;
        var entryStart = lastFarmEntryStart == null ? position : lastFarmEntryStart;
        var entryEnd = lastFarmEntryEnd == null ? position.add(new MotionMath.Vec2(
            -Math.sin(Math.toRadians(player.getYRot())), Math.cos(Math.toRadians(player.getYRot()))).scale(FARM_ENTRY_DISTANCE)) : lastFarmEntryEnd;
        if (CoverageMemory.segmentExcluded(zones, fieldWorldKey, id(), entryStart, entryEnd, padding)) return false;
        List<List<MotionMath.Vec2>> routes = new ArrayList<>();
        for (var route : importedPerimeterRoutes) routes.add(List.of(route));
        if (importedPerimeterRoutes.isEmpty() && !coveragePreview.isEmpty()) routes.add(coveragePreview);
        for (var route : routes) {
            if (route.size() < 3) continue;
            boolean clear = true, reachable = false, playerClear = false;
            for (int i = 0; i < route.size(); i++) {
                if (CoverageMemory.segmentExcluded(zones, fieldWorldKey, id(), route.get(i), route.get((i + 1) % route.size()), padding)) clear = false;
                if (!CoverageMemory.segmentExcluded(zones, fieldWorldKey, id(), entryEnd, route.get(i), padding)) reachable = true;
                if (!CoverageMemory.segmentExcluded(zones, fieldWorldKey, id(), position, route.get(i), padding)) playerClear = true;
            }
            if (clear && reachable && playerClear) return true;
        }
        return false;
    }

    @Override
    public String status() {
        String glow = glowIntercept == null ? "" : " • pursuing glow";
        String state = isRerouting() ? "Rerouting" : recoveryPhase == RecoveryPhase.NONE ? mode.name() : recoveryPhase.label;
        return state + " • " + String.format(Locale.ROOT, "%.1f BPS", blocksPerSecond()) + glow;
    }

    private boolean isRerouting() {
        return mode == Mode.RUNNING && recoveryPhase == RecoveryPhase.NONE
            && (detouring || ticks - detectedObstacleTick >= 0 && ticks - detectedObstacleTick < 50);
    }

    public void prepareWorld(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            preparedWorldKey = null;
            return;
        }
        String world = FieldProfileStore.worldKey(minecraft);
        if (world == null || world.equals(preparedWorldKey)) {
            return;
        }
        preparedWorldKey = world;
        String normalized = world.toLowerCase(Locale.ROOT);
        if (normalized.contains(AUTOMATIC_WORLD_MARKER)
            && normalized.endsWith("|minecraft:overworld")) {
            applyImportedField(minecraft, null, true);
        }
    }

    public void snapshotCrops(Minecraft minecraft) {
        breakPreview.clear();
        if (!isHarvesting() || minecraft.gui.screen() != null || minecraft.player == null || minecraft.level == null || bounds == null) {
            return;
        }

        int radius = 5;
        int centerX = Mth.floor(minecraft.player.getX());
        int centerZ = Mth.floor(minecraft.player.getZ());
        for (int x = Math.max(bounds.minX, centerX - radius); x <= Math.min(bounds.maxX, centerX + radius); x++) {
            for (int z = Math.max(bounds.minZ, centerZ - radius); z <= Math.min(bounds.maxZ, centerZ + radius); z++) {
                BlockPos pos = new BlockPos(x, bounds.cropY, z);
                if (isCrop(minecraft.level.getBlockState(pos))) {
                    if (isInBreakCorridor(minecraft.player, pos)) {
                        breakPreview.add(pos);
                    }
                }
            }
        }
    }

    private void scanGlowingPlants(Minecraft minecraft) {
        if (fieldWorldKey == null || !fieldWorldKey.equals(FieldProfileStore.worldKey(minecraft))) {
            glowingPlants.clear();
            return;
        }
        if (!isActive() && !config.glowInspector && !(minecraft.gui.screen() instanceof CropiumScreen)) return;
        if (bounds == null || !bounds.containsExpanded(minecraft.player.getX(), minecraft.player.getZ(), 64)) return;
        if (ticks < nextGlowScanTick) {
            return;
        }
        nextGlowScanTick = ticks + GLOW_SCAN_INTERVAL_TICKS;
        if (!config.targetGlowingPlants && !config.glowInspector) {
            glowingPlants.clear();
            glowFailureCounts.clear();
            ignoredGlowTargets.clear();
            glowObservation = null;
            glowIntercept = null;
            glowRoutingStatus = "Glow targeting is disabled";
            return;
        }

        Entity nearest = null;
        BlockPos nearestCrop = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        double maximumDistanceSquared = GLOW_SCAN_RADIUS * GLOW_SCAN_RADIUS;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == minecraft.player || entity instanceof Player || entity.isRemoved()
                || !entity.isCurrentlyGlowing()) {
                continue;
            }
            double distance = entity.distanceToSqr(minecraft.player);
            if (distance > maximumDistanceSquared) {
                continue;
            }
            BlockPos crop = nearestMappedCrop(minecraft, entity);
            if (distance < nearestDistance) {
                nearest = entity;
                nearestCrop = crop;
                nearestDistance = distance;
            }
            if (crop == null || Math.hypot(entity.getDeltaMovement().x, entity.getDeltaMovement().z) > 0.08) {
                continue;
            }
            GlowingPlant plant = glowingPlants.get(crop);
            if (plant == null) {
                plant = new GlowingPlant(crop, entity.getId(), entity.getType().toString(), ticks);
                glowingPlants.put(crop, plant);
            }
            plant.entityId = entity.getId();
            plant.entityType = entity.getType().toString();
            plant.lastSeenTick = ticks;
        }

        BlockPos observedBlock = nearestCrop != null ? nearestCrop
            : nearest == null ? null : BlockPos.containing(nearest.position());
        glowObservation = nearest == null ? null : new GlowObservation(
            nearest.getId(), nearest.getType().toString(), nearest.getClass().getSimpleName(),
            nearest.hasCustomName() ? nearest.getCustomName().getString() : "no custom name",
            nearest.getX(), nearest.getY(), nearest.getZ(), nearestCrop,
            minecraft.level.getBlockState(observedBlock).toString());
        glowingPlants.values().removeIf(plant -> ticks - plant.lastSeenTick > GLOW_STALE_TICKS);
        glowTargetCooldowns.entrySet().removeIf(entry -> entry.getValue() <= ticks);
        if (glowingPlants.isEmpty() && glowIntercept == null) {
            glowRoutingStatus = "Patrolling to load and discover glowing plants";
        }
    }

    private BlockPos nearestMappedCrop(Minecraft minecraft, Entity entity) {
        if (bounds == null || Math.abs(entity.getY() - (bounds.cropY + 0.5)) > 4.0) {
            return null;
        }
        int centerX = Mth.floor(entity.getX());
        int centerZ = Mth.floor(entity.getZ());
        BlockPos containing = new BlockPos(centerX, bounds.cropY, centerZ);
        if (fieldCell(centerX, centerZ) == FIELD_CROP
            || isCrop(minecraft.level.getBlockState(containing))) {
            return containing.immutable();
        }
        BlockPos nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                BlockPos candidate = new BlockPos(x, bounds.cropY, z);
                if (fieldCell(x, z) != FIELD_CROP && !isCrop(minecraft.level.getBlockState(candidate))) {
                    continue;
                }
                double distance = Mth.square(x + 0.5 - entity.getX()) + Mth.square(z + 0.5 - entity.getZ());
                if (distance < nearestDistance) {
                    nearest = candidate;
                    nearestDistance = distance;
                }
            }
        }
        return nearest == null ? null : nearest.immutable();
    }

    public int glowingPlantCount() {
        return (int)glowingPlants.keySet().stream().filter(crop -> !ignoredGlowTargets.contains(crop)).count();
    }

    public String glowInspectorSummary() {
        if (glowObservation == null) {
            return "No glowing entities in range; the effect may not use vanilla entity glow.";
        }
        String mapping = glowObservation.crop == null ? "unmapped" : "crop " + shortPos(glowObservation.crop);
        return simpleEntityType(glowObservation.entityType) + " / " + glowObservation.entityClass
            + " #" + glowObservation.entityId + " • " + mapping;
    }

    public String glowRoutingStatus() {
        return glowRoutingStatus;
    }

    public List<String> glowInspectorLines() {
        String state = glowIntercept == null ? "discovery patrol"
            : switch (glowIntercept.stage) {
                case ACQUIRE -> "turning toward " + shortPos(glowIntercept.crop);
                case DIRECT -> "direct intercept " + shortPos(glowIntercept.crop);
                case OBSERVE -> "checking passed crop " + shortPos(glowIntercept.crop);
                case REJOIN -> "rejoining route";
            };
        String observation = glowObservation == null
            ? "No vanilla glowing entity in " + (int)GLOW_SCAN_RADIUS + " blocks"
            : simpleEntityType(glowObservation.entityType) + " / " + glowObservation.entityClass
                + " #" + glowObservation.entityId
                + String.format(Locale.ROOT, " @ %.1f %.1f %.1f",
                    glowObservation.x, glowObservation.y, glowObservation.z);
        String mapping = glowObservation == null || glowObservation.crop == null
            ? "Mapping: none" : "Mapping: crop " + shortPos(glowObservation.crop);
        if (glowObservation != null) {
            mapping += " • " + glowObservation.customName + " • " + glowObservation.blockState;
        }
        return List.of("Glow Inspector • " + glowingPlants.size() + " tracked • "
            + ignoredGlowTargets.size() + " ignored • "
            + glowingPlantsHarvested + " harvested • " + state, observation, mapping);
    }

    private static String simpleEntityType(String type) {
        int separator = Math.max(type.lastIndexOf('.'), type.lastIndexOf(':'));
        return separator < 0 ? type : type.substring(separator + 1);
    }

    public void tick(Minecraft minecraft) {
        if (preparingStart && recoveryPhase == RecoveryPhase.WAITING_FOR_FARM
            && minecraft.gui.screen() instanceof LevelLoadingScreen
            && ticks - recoveryStartedTick < FARM_TELEPORT_TIMEOUT_TICKS) {
            ticks++;
            releaseAttack(minecraft);
            releaseMovement(minecraft);
            releasePreparedVertical(minecraft);
            return;
        }
        if (minecraft.player == null || minecraft.level == null) {
            if (isActive() && sessionStoppedNanos == 0L) {
                sessionStoppedNanos = System.nanoTime();
            }
            releaseAttack(minecraft);
            releaseMovement(minecraft);
            releasePreparedVertical(minecraft);
            restoreCamera(minecraft);
            restoreFocusPause(minecraft);
            activeWorldKey = null;
            scanningField = false;
            scanWorldKey = null;
            mode = Mode.OFF;
            glowingPlants.clear();
            glowTargetCooldowns.clear();
            glowFailureCounts.clear();
            ignoredGlowTargets.clear();
            glowObservation = null;
            glowIntercept = null;
            resetRecoveryState();
            return;
        }

        ticks++;
        scanFieldTick(minecraft);
        scanGlowingPlants(minecraft);
        if (!isActive()) {
            return;
        }
        String worldKey = FieldProfileStore.worldKey(minecraft);
        if (activeWorldKey != null && !activeWorldKey.equals(worldKey)) {
            stop(minecraft, "Stopped: world or dimension changed");
            return;
        }
        if (!isPaused() && minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof CropiumScreen)) {
            stop(minecraft, "Stopped: unexpected screen opened");
            return;
        }
        if (!isPaused() && preparingStart && minecraft.gui.screen() != null) {
            stop(minecraft, "Prepared farm start stopped while a GUI is open");
            return;
        }
        if (minecraft.options.keyShift.isDown() && !modHoldingSneak
            || InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            stop(minecraft, "Stopped by Shift");
            return;
        }
        if (mode == Mode.PAUSED) {
            releaseAttack(minecraft);
            releaseMovement(minecraft);
            releasePreparedVertical(minecraft);
            return;
        }
        if (recoveryPhase != RecoveryPhase.NONE) {
            enableChaseCamera(minecraft);
            keepRunningWhenUnfocused(minecraft);
            tickRecovery(minecraft);
            return;
        }
        if (checkLowBps(minecraft)) {
            return;
        }

        holdAttack(minecraft);
        holdMovement(minecraft);
        enableChaseCamera(minecraft);
        keepRunningWhenUnfocused(minecraft);
        flyPattern(minecraft);
    }

    public void selectNextCorner(Minecraft minecraft) {
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || minecraft.level == null) {
            message(minecraft, "Look at a crop or farmland block first");
            return;
        }

        BlockPos selected = normalizeCropLayer(minecraft, hit.getBlockPos());
        if (firstCorner == null || bounds != null) {
            stopSilently(minecraft);
            firstCorner = selected;
            bounds = null;
            resetFieldScan();
            mode = Mode.OFF;
            message(minecraft, "Corner A: " + shortPos(selected) + " — press B on the opposite corner");
            return;
        }

        Bounds candidate = Bounds.between(firstCorner, selected);
        if (candidate.width() > MAX_BOUND_SIZE || candidate.depth() > MAX_BOUND_SIZE) {
            message(minecraft, "Bounds must be at most " + MAX_BOUND_SIZE + " × " + MAX_BOUND_SIZE);
            return;
        }
        if (candidate.width() < 2 || candidate.depth() < 2) {
            message(minecraft, "Bounds must be at least 2 × 2");
            return;
        }

        bounds = candidate;
        resetRouteMemory();
        loadObstacleMemory(minecraft);
        loadFieldScan(minecraft);
        mode = Mode.READY;
        String scanStatus = fieldScanComplete ? " — saved scan loaded" : " — press J to scan it";
        message(minecraft, "Bounds set: " + bounds.width() + " × " + bounds.depth()
            + " at crop Y " + bounds.cropY + scanStatus);
    }

    public boolean scanField(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            message(minecraft, "Join a world before scanning");
            return false;
        }
        if (bounds == null) {
            message(minecraft, "Set two corners with B before scanning");
            return false;
        }
        if (!minecraft.player.getAbilities().mayfly || !minecraft.player.getAbilities().flying) {
            message(minecraft, "Double-tap Space and hover at farming height before scanning");
            return false;
        }

        stopSilently(minecraft);
        List<MotionMath.Vec2[]> calibratedRoutes = importedPerimeterRoutes;
        String calibratedMapName = importedMapName;
        resetFieldScan();
        importedPerimeterRoutes = calibratedRoutes;
        importedMapName = calibratedMapName;
        fieldCells = new byte[bounds.width() * bounds.depth()];
        scanBody = minecraft.player.getBoundingBox();
        fieldScanComplete = false;
        scanningField = true;
        scanCursor = 0;
        scanKnownCells = 0;
        scanCropCells = 0;
        scanObstacleCells = 0;
        scanPassStartKnown = 0;
        nextScanTick = ticks;
        scanWorldKey = FieldProfileStore.worldKey(minecraft);
        fieldWorldKey = scanWorldKey;
        message(minecraft, "Scanning " + bounds.width() + " × " + bounds.depth()
            + " at your current flight height — fly around until the map reaches 100%");
        return true;
    }

    public boolean anchorImportedField(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            message(minecraft, "Join the server before calibrating the imported map");
            return false;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
            || hit.getType() != HitResult.Type.BLOCK) {
            message(minecraft, "Look directly at the recorded anchor block first");
            return false;
        }

        return applyImportedField(minecraft, hit.getBlockPos(), false);
    }

    private boolean applyImportedField(Minecraft minecraft, BlockPos selectedAnchor, boolean automatic) {
        ImportedFieldMap map;
        ImportedObstacleMap obstacleMap;
        ImportedRouteMap routeMap;
        try (InputStream mapStream = HarvestController.class.getResourceAsStream(IMPORTED_MAP_RESOURCE);
             InputStream obstacleStream = HarvestController.class.getResourceAsStream(IMPORTED_OBSTACLE_RESOURCE);
             InputStream routeStream = HarvestController.class.getResourceAsStream(IMPORTED_ROUTE_RESOURCE)) {
            if (mapStream == null || obstacleStream == null || routeStream == null) {
                throw new IllegalStateException("embedded map data is missing");
            }
            map = GSON.fromJson(new InputStreamReader(mapStream, StandardCharsets.UTF_8), ImportedFieldMap.class);
            obstacleMap = GSON.fromJson(
                new InputStreamReader(obstacleStream, StandardCharsets.UTF_8), ImportedObstacleMap.class);
            routeMap = GSON.fromJson(
                new InputStreamReader(routeStream, StandardCharsets.UTF_8), ImportedRouteMap.class);
        } catch (Exception exception) {
            message(minecraft, "Could not load the embedded farm map: " + exception.getMessage());
            return false;
        }
        if (map == null || obstacleMap == null || obstacleMap.cells == null || routeMap == null
            || routeMap.routes == null || routeMap.routes.length == 0
            || obstacleMap.sourceAnchorX != map.sourceAnchorX
            || obstacleMap.sourceAnchorZ != map.sourceAnchorZ
            || routeMap.sourceAnchorX != map.sourceAnchorX
            || routeMap.sourceAnchorZ != map.sourceAnchorZ) {
            message(minecraft, "The embedded farm map is empty");
            return false;
        }

        int width = map.maxX - map.minX + 1;
        int depth = map.maxZ - map.minZ + 1;
        byte[] cells;
        try {
            cells = Base64.getDecoder().decode(map.cells);
        } catch (IllegalArgumentException exception) {
            message(minecraft, "The embedded farm map is invalid");
            return false;
        }
        if (cells.length != width * depth
            || width < 2 || depth < 2 || width > MAX_BOUND_SIZE || depth > MAX_BOUND_SIZE) {
            message(minecraft, "The embedded farm map has invalid dimensions or routes");
            return false;
        }
        for (int[] obstacle : obstacleMap.cells) {
            if (obstacle == null || obstacle.length != 2) {
                message(minecraft, "The embedded obstacle map is invalid");
                return false;
            }
            int sourceX = map.sourceAnchorX + obstacle[0];
            int sourceZ = map.sourceAnchorZ + obstacle[1];
            if (sourceX < map.minX || sourceX > map.maxX || sourceZ < map.minZ || sourceZ > map.maxZ) {
                message(minecraft, "The embedded obstacle map is outside the farm bounds");
                return false;
            }
            cells[(sourceZ - map.minZ) * width + sourceX - map.minX] = FIELD_OBSTACLE;
        }

        BlockPos anchor = selectedAnchor == null
            ? new BlockPos(map.sourceAnchorX, map.sourceAnchorY, map.sourceAnchorZ)
            : selectedAnchor;
        int offsetX = anchor.getX() - map.sourceAnchorX;
        int offsetY = anchor.getY() - map.sourceAnchorY;
        int offsetZ = anchor.getZ() - map.sourceAnchorZ;
        Bounds importedBounds = new Bounds(map.minX + offsetX, map.maxX + offsetX,
            map.minZ + offsetZ, map.maxZ + offsetZ, map.cropY + offsetY);
        List<MotionMath.Vec2[]> routes = new ArrayList<>();
        for (double[][] storedRoute : routeMap.routes) {
            if (storedRoute == null || storedRoute.length < 3) {
                message(minecraft, "The embedded farm map contains an invalid perimeter");
                return false;
            }
            MotionMath.Vec2[] route = new MotionMath.Vec2[storedRoute.length];
            for (int index = 0; index < storedRoute.length; index++) {
                if (storedRoute[index] == null || storedRoute[index].length != 2
                    || !Double.isFinite(storedRoute[index][0]) || !Double.isFinite(storedRoute[index][1])) {
                    message(minecraft, "The embedded farm map contains an invalid waypoint");
                    return false;
                }
                route[index] = new MotionMath.Vec2(anchor.getX() + storedRoute[index][0],
                    anchor.getZ() + storedRoute[index][1]);
            }
            if (!MotionMath.pathInside(route, importedBounds.minCenterX(), importedBounds.maxCenterX(),
                importedBounds.minCenterZ(), importedBounds.maxCenterZ())) {
                message(minecraft, "The calibrated perimeter does not fit inside its map");
                return false;
            }
            routes.add(route);
        }

        stopSilently(minecraft);
        firstCorner = null;
        bounds = importedBounds;
        resetRouteMemory();
        resetFieldScan();
        fieldCells = cells;
        fieldWorldKey = FieldProfileStore.worldKey(minecraft);
        for (byte state : cells) {
            if (state < FIELD_CROP || state > FIELD_OBSTACLE) {
                resetFieldScan();
                message(minecraft, "The embedded farm map contains an invalid cell");
                return false;
            }
            scanKnownCells++;
            if (state == FIELD_CROP) {
                scanCropCells++;
            } else if (state == FIELD_OBSTACLE) {
                scanObstacleCells++;
            }
        }
        fieldScanComplete = true;
        importedPerimeterRoutes = List.copyOf(routes);
        importedMapName = map.name;
        coveragePreview = List.of(importedPerimeterRoutes.getLast());
        loadObstacleMemory(minecraft);
        mode = Mode.READY;
        boolean saved = automatic || profiles.putFieldScan(FieldProfileStore.worldKey(minecraft), boundsKey(),
            bounds.width(), bounds.depth(), fieldCells);
        message(minecraft, (automatic ? "Loaded " : "Anchored ") + importedMapName
            + (automatic ? " automatically: " : ": ") + scanCropCells + " navigable crop cells, "
            + scanObstacleCells + " mapped obstacles, " + routes.size() + " high-clearance routes"
            + (saved ? "" : " (scan persistence failed)"));
        return true;
    }

    private void scanFieldTick(Minecraft minecraft) {
        if (!scanningField || ticks < nextScanTick || bounds == null || fieldCells == null) {
            return;
        }
        String world = FieldProfileStore.worldKey(minecraft);
        if (scanWorldKey == null || !scanWorldKey.equals(world)) {
            resetFieldScan();
            message(minecraft, "Field scan cancelled because the world or dimension changed");
            return;
        }

        int total = fieldCells.length;
        AABB body = scanBody;
        int processed = 0;
        while (scanCursor < total && processed++ < SCAN_CELLS_PER_TICK) {
            int index = scanCursor++;
            if (fieldCells[index] != FIELD_UNKNOWN) {
                continue;
            }
            int x = bounds.minX + index % bounds.width();
            int z = bounds.minZ + index / bounds.width();
            BlockPos position = new BlockPos(x, bounds.cropY, z);
            if (!minecraft.level.hasChunkAt(position)) {
                continue;
            }

            byte state = classifyFieldCell(minecraft, body, position);
            fieldCells[index] = state;
            scanKnownCells++;
            if (state == FIELD_CROP) {
                scanCropCells++;
            } else if (state == FIELD_OBSTACLE) {
                scanObstacleCells++;
            }
        }
        if (scanCursor < total) {
            return;
        }
        if (scanKnownCells == total) {
            scanningField = false;
            fieldScanComplete = true;
            scanWorldKey = null;
            coveragePreview = List.of(perimeterRoutePoints(false));
            boolean saved = profiles.putFieldScan(world, boundsKey(), bounds.width(), bounds.depth(), fieldCells);
            message(minecraft, "Scan complete: " + scanCropCells + " crop cells and "
                + scanObstacleCells + " obstacle cells" + (saved ? " saved" : " mapped; save failed"));
            return;
        }

        boolean madeProgress = scanKnownCells > scanPassStartKnown;
        scanPassStartKnown = scanKnownCells;
        scanCursor = 0;
        nextScanTick = madeProgress ? ticks + 1 : ticks + SCAN_RETRY_TICKS;
    }

    private byte classifyFieldCell(Minecraft minecraft, AABB body, BlockPos position) {
        double targetX = position.getX() + 0.5;
        double targetZ = position.getZ() + 0.5;
        double bodyCenterX = (body.minX + body.maxX) * 0.5;
        double bodyCenterZ = (body.minZ + body.maxZ) * 0.5;
        AABB probe = body.move(targetX - bodyCenterX, 0.0, targetZ - bodyCenterZ);
        if (findBlockingBlock(minecraft, probe, false) != null) {
            return FIELD_OBSTACLE;
        }

        BlockState state = minecraft.level.getBlockState(position);
        BlockState below = minecraft.level.getBlockState(position.below());
        if (isCrop(state) || below.getBlock() instanceof FarmlandBlock || !state.isAir()) {
            return FIELD_CROP;
        }
        return FIELD_CLEAR;
    }

    private void loadFieldScan(Minecraft minecraft) {
        resetFieldScan();
        if (bounds == null) {
            return;
        }
        String world = FieldProfileStore.worldKey(minecraft);
        byte[] saved = profiles.fieldScan(world, boundsKey(), bounds.width(), bounds.depth());
        if (saved == null) {
            return;
        }
        for (byte state : saved) {
            if (state < FIELD_CROP || state > FIELD_OBSTACLE) {
                resetFieldScan();
                return;
            }
            scanKnownCells++;
            if (state == FIELD_CROP) {
                scanCropCells++;
            } else if (state == FIELD_OBSTACLE) {
                scanObstacleCells++;
            }
        }
        fieldCells = saved;
        fieldWorldKey = world;
        fieldScanComplete = true;
        restoreImportedPerimeters();
        coveragePreview = List.of(perimeterRoutePoints(false));
    }

    private void restoreImportedPerimeters() {
        try (InputStream mapStream = HarvestController.class.getResourceAsStream(IMPORTED_MAP_RESOURCE);
             InputStream obstacleStream = HarvestController.class.getResourceAsStream(IMPORTED_OBSTACLE_RESOURCE);
             InputStream routeStream = HarvestController.class.getResourceAsStream(IMPORTED_ROUTE_RESOURCE)) {
            if (mapStream == null || obstacleStream == null || routeStream == null) {
                return;
            }
            ImportedFieldMap map = GSON.fromJson(
                new InputStreamReader(mapStream, StandardCharsets.UTF_8), ImportedFieldMap.class);
            ImportedObstacleMap obstacleMap = GSON.fromJson(
                new InputStreamReader(obstacleStream, StandardCharsets.UTF_8), ImportedObstacleMap.class);
            ImportedRouteMap routeMap = GSON.fromJson(
                new InputStreamReader(routeStream, StandardCharsets.UTF_8), ImportedRouteMap.class);
            byte[] expected = Base64.getDecoder().decode(map.cells);
            int expectedWidth = map.maxX - map.minX + 1;
            for (int[] obstacle : obstacleMap.cells) {
                int sourceX = map.sourceAnchorX + obstacle[0];
                int sourceZ = map.sourceAnchorZ + obstacle[1];
                expected[(sourceZ - map.minZ) * expectedWidth + sourceX - map.minX] = FIELD_OBSTACLE;
            }
            if (bounds.width() != map.maxX - map.minX + 1
                || bounds.depth() != map.maxZ - map.minZ + 1 || !Arrays.equals(fieldCells, expected)
                || routeMap.sourceAnchorX != map.sourceAnchorX || routeMap.sourceAnchorZ != map.sourceAnchorZ) {
                return;
            }
            int anchorX = map.sourceAnchorX + bounds.minX - map.minX;
            int anchorZ = map.sourceAnchorZ + bounds.minZ - map.minZ;
            List<MotionMath.Vec2[]> routes = new ArrayList<>();
            for (double[][] storedRoute : routeMap.routes) {
                MotionMath.Vec2[] route = new MotionMath.Vec2[storedRoute.length];
                for (int index = 0; index < storedRoute.length; index++) {
                    route[index] = new MotionMath.Vec2(anchorX + storedRoute[index][0],
                        anchorZ + storedRoute[index][1]);
                }
                routes.add(route);
            }
            importedPerimeterRoutes = List.copyOf(routes);
            importedMapName = map.name;
        } catch (Exception ignored) {
            importedPerimeterRoutes = List.of();
            importedMapName = null;
        }
    }

    private void resetFieldScan() {
        coverage.clear();
        coveragePreview = List.of();
        fieldWorldKey = null;
        lastFarmEntryStart = lastFarmEntryEnd = null;
        scanningField = false;
        fieldScanComplete = false;
        fieldCells = null;
        importedPerimeterRoutes = List.of();
        importedMapName = null;
        scanBody = null;
        scanWorldKey = null;
        scanCursor = 0;
        scanKnownCells = 0;
        scanCropCells = 0;
        scanObstacleCells = 0;
        scanPassStartKnown = 0;
        nextScanTick = 0;
    }

    @Override
    public boolean start(Minecraft minecraft) {
        if (isActive()) {
            return true;
        }
        if (minecraft.player == null || minecraft.level == null) return false;
        if (bounds == null) {
            message(minecraft, "Set two corners with B first");
            return false;
        }
        if (!fieldScanComplete || fieldCells == null || scanCropCells < 3) {
            message(minecraft, "Scan this field first with J or /cropium scan");
            return false;
        }
        if (!savedFieldMatches(minecraft)) {
            message(minecraft, "Load the saved farm map for this world first");
            return false;
        }
        if (excluded(minecraft.player.getX(), minecraft.player.getZ(),
            minecraft.player.getBbWidth() / 2.0 + config.obstacleClearance)) {
            message(minecraft, "Move clear of the exclusion and its body padding first");
            return false;
        }
        if (!minecraft.player.getAbilities().mayfly || !minecraft.player.getAbilities().flying) {
            message(minecraft, "Double-tap Space to start normal flight, then press H");
            return false;
        }
        if (!bounds.containsExpanded(minecraft.player.getX(), minecraft.player.getZ(), 1.5)) {
            message(minecraft, "Move inside or immediately beside the selected bounds first");
            return false;
        }
        if (importedMapName != null && !importedFieldContains(
            minecraft.player.getX(), minecraft.player.getZ(), minecraft.player.getBbWidth() * 0.5)) {
            message(minecraft, "Move over the green imported crop area before starting");
            return false;
        }

        activeWorldKey = FieldProfileStore.worldKey(minecraft);
        resetRecoveryState();
        loadObstacleMemory(minecraft);
        pattern = Pattern.STRAIGHT;
        turn = null;
        mode = Mode.RUNNING;
        initializeRoute(minecraft);
        if (specialRoute != SpecialRoute.PERIMETER) {
            stopSilently(minecraft);
            message(minecraft, importedMapName == null
                ? "The scanned crop outline is too small for a safe full-speed perimeter"
                : "No collision-free crop path reaches the perimeter from here");
            return false;
        }
        scheduleWander();
        schedulePitchChange();
        scheduleSpecialRoute();
        resetCounters();
        holdMovement(minecraft);
        enableChaseCamera(minecraft);
        keepRunningWhenUnfocused(minecraft);
        message(minecraft, "Started — Forward, Sprint, and Attack are held");
        return true;
    }

    @Override
    public void stop(Minecraft minecraft) {
        stop(minecraft, "Stopped");
    }

    public void pause(Minecraft minecraft) {
        if (mode == Mode.RUNNING) {
            suspendForUtility(minecraft);
            message(minecraft, "Paused");
        } else if (mode == Mode.PAUSED) {
            resumeAfterUtility(minecraft);
            message(minecraft, "Resumed");
        }
    }

    public boolean suspendForUtility(Minecraft minecraft) {
        if (mode != Mode.RUNNING) {
            return false;
        }
        mode = Mode.PAUSED;
        releaseAttack(minecraft);
        releaseMovement(minecraft);
        releasePreparedVertical(minecraft);
        restoreCamera(minecraft);
        restoreFocusPause(minecraft);
        return true;
    }

    public boolean resumeAfterUtility(Minecraft minecraft) {
        if (mode != Mode.PAUSED) {
            return false;
        }
        String worldKey = FieldProfileStore.worldKey(minecraft);
        if (activeWorldKey != null && !activeWorldKey.equals(worldKey)) {
            stopSilently(minecraft);
            return false;
        }
        if (minecraft.player == null || bounds == null
            || !minecraft.player.getAbilities().mayfly || !minecraft.player.getAbilities().flying
            || !bounds.containsExpanded(minecraft.player.getX(), minecraft.player.getZ(), 1.5)
            || importedMapName != null && !importedFieldContains(
                minecraft.player.getX(), minecraft.player.getZ(), minecraft.player.getBbWidth() * 0.5)) {
            return false;
        }
        resetRecoveryState();
        pattern = Pattern.STRAIGHT;
        turn = null;
        initializeRoute(minecraft);
        if (specialRoute != SpecialRoute.PERIMETER) {
            return false;
        }
        mode = Mode.RUNNING;
        scheduleWander();
        schedulePitchChange();
        scheduleSpecialRoute();
        resetBpsWindow();
        holdAttack(minecraft);
        holdMovement(minecraft);
        enableChaseCamera(minecraft);
        keepRunningWhenUnfocused(minecraft);
        return true;
    }

    public void stopForScreen(Minecraft minecraft) {
        if (isActive()) {
            stop(minecraft, "Stopped");
        }
    }

    public void clear(Minecraft minecraft) {
        stopSilently(minecraft);
        firstCorner = null;
        bounds = null;
        resetRouteMemory();
        resetFieldScan();
        mode = Mode.OFF;
        message(minecraft, "Bounds cleared");
    }

    public void onChatMessage(Minecraft minecraft, Component chatMessage) {
        if (!isActive() || !config.stopOnNameMention || minecraft.player == null || chatMessage == null) {
            return;
        }
        String username = minecraft.player.getGameProfile().name();
        if (MotionMath.containsNameToken(chatMessage.getString(), username)) {
            stop(minecraft, "Stopped: your name was mentioned in chat");
        }
    }

    public void onBlockBroken(BlockPos position) {
        if (!isHarvesting()) {
            return;
        }
        long now = System.nanoTime();
        coverage.record(position.getX(), position.getZ(), ticks);
        blocksMined++;
        bpsWindowBlocks++;
        recentBreaks.addLast(now);
        pruneRecentBreaks(now);
        shinyCountCooldowns.entrySet().removeIf(entry -> entry.getValue() <= ticks);
        boolean knownShiny = glowingPlants.containsKey(position)
            || glowIntercept != null && !glowIntercept.harvested && position.equals(glowIntercept.crop);
        if (knownShiny && !shinyCountCooldowns.containsKey(position)) {
            glowingPlantsHarvested++;
            shinyCountCooldowns.put(position.immutable(), ticks + 40);
        }
        if (glowIntercept != null && glowIntercept.stage != GlowStage.REJOIN
            && !glowIntercept.harvested && position.equals(glowIntercept.crop)) {
            glowingPlants.remove(glowIntercept.crop);
            glowFailureCounts.remove(glowIntercept.crop);
            ignoredGlowTargets.remove(glowIntercept.crop);
            glowIntercept.harvested = true;
            if (glowIntercept.stage == GlowStage.DIRECT) {
                glowRoutingStatus = "Glow harvested — finishing the straight exit across the route";
            } else {
                startGlowRejoin("Glow harvested; rejoining the active route");
            }
        }
    }

    FieldProfileStore.Profile currentProfile() {
        if (bounds == null) {
            return null;
        }
        FieldProfileStore.Profile profile = new FieldProfileStore.Profile();
        profile.routeStyle = config.routeStyle.name();
        profile.minX = bounds.minX;
        profile.maxX = bounds.maxX;
        profile.minZ = bounds.minZ;
        profile.maxZ = bounds.maxZ;
        profile.cropY = bounds.cropY;
        profile.lookDownPitch = config.lookDownPitch;
        profile.headingJitterDegrees = config.headingJitterDegrees;
        profile.turnDurationTicks = config.turnDurationTicks;
        profile.naturalMovement = config.naturalMovement;
        profile.obstacleAvoidance = config.obstacleAvoidance;
        profile.obstacleLookAhead = config.obstacleLookAhead;
        profile.obstacleClearance = config.obstacleClearance;
        profile.stopOnNameMention = config.stopOnNameMention;
        profile.lowBpsFailsafe = config.lowBpsFailsafe;
        profile.minimumBps = config.minimumBps;
        profile.lowBpsWindowSeconds = config.lowBpsWindowSeconds;
        profile.showHud = config.showHud;
        profile.showWorldOverlay = config.showWorldOverlay;
        profile.thirdPersonCamera = config.thirdPersonCamera;
        return profile;
    }

    boolean applyProfile(Minecraft minecraft, FieldProfileStore.Profile profile) {
        Bounds candidate = new Bounds(profile.minX, profile.maxX, profile.minZ, profile.maxZ, profile.cropY);
        if (candidate.width() < 2 || candidate.depth() < 2
            || candidate.width() > MAX_BOUND_SIZE || candidate.depth() > MAX_BOUND_SIZE) {
            return false;
        }
        stopSilently(minecraft);
        firstCorner = null;
        bounds = candidate;
        resetRouteMemory();
        loadObstacleMemory(minecraft);
        loadFieldScan(minecraft);
        try {
            config.routeStyle = CropPilotConfig.RouteStyle.valueOf(profile.routeStyle);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            config.routeStyle = CropPilotConfig.RouteStyle.RECTANGULAR;
        }
        config.lookDownPitch = profile.lookDownPitch;
        config.headingJitterDegrees = profile.headingJitterDegrees;
        config.turnDurationTicks = profile.turnDurationTicks;
        config.naturalMovement = profile.naturalMovement;
        config.obstacleAvoidance = profile.obstacleAvoidance;
        config.obstacleLookAhead = profile.obstacleLookAhead;
        config.obstacleClearance = profile.obstacleClearance;
        config.stopOnNameMention = profile.stopOnNameMention;
        config.lowBpsFailsafe = profile.lowBpsFailsafe;
        config.minimumBps = profile.minimumBps;
        config.lowBpsWindowSeconds = profile.lowBpsWindowSeconds;
        config.showHud = profile.showHud;
        config.showWorldOverlay = profile.showWorldOverlay;
        config.thirdPersonCamera = profile.thirdPersonCamera;
        config.save();
        mode = Mode.READY;
        return true;
    }

    public String hudLineOne() {
        if (scanningField) {
            return String.format(Locale.ROOT, "Cropium: SCANNING • %.1f%% mapped", scanPercent());
        }
        String route = specialRoute == SpecialRoute.NONE ? "" : " • " + (activeTrace == null ? specialRoute.label : activeTrace.name());
        String state = recoveryPhase == RecoveryPhase.NONE ? mode.name() : recoveryPhase.label;
        return "Cropium: " + state + " • " + pattern.label + route;
    }

    public String hudLineTwo() {
        if (bounds == null) {
            return firstCorner == null ? "B: select corner A" : "B: select corner B from " + shortPos(firstCorner);
        }
        if (scanningField) {
            return String.format(Locale.ROOT, "%dx%d • %,d/%,d cells • fly around to load the rest",
                bounds.width(), bounds.depth(), scanKnownCells, fieldCells.length);
        }
        String route = importedMapName != null ? importedMapName + " exact map"
            : fieldScanComplete ? "scanned perimeter" : "J scans field";
        return String.format(Locale.ROOT, "%dx%d • %s • crop Y %d • H starts/stops",
            bounds.width(), bounds.depth(), route, bounds.cropY);
    }

    public String hudLineThree() {
        pruneRecentBreaks(System.nanoTime());
        return String.format(Locale.ROOT, "Mined %,d • %.1f BPS • %,.0f blocks/h • %.1f shinies/h",
            blocksMined, blocksPerSecond(), blocksPerHour(), shiniesPerHour());
    }

    public String hudLineFour() {
        if (scanningField) {
            return String.format(Locale.ROOT, "MAP  %,d crops • %,d obstacles • unloaded cells are unsafe",
                scanCropCells, scanObstacleCells);
        }
        if (importedMapName != null && mode != Mode.RUNNING) {
            return "MAP  exact irregular footprint • /cropium profile save <name> keeps the bounds";
        }
        if (mode == Mode.RUNNING && recoveryPhase != RecoveryPhase.NONE) {
            return switch (recoveryPhase) {
                case BOUNCING -> "NAV  BOUNCE • full-speed escape • mapped rejoin armed";
                case WAITING_FOR_FARM -> "NAV  /FARM • waiting for server teleport";
                case ENTERING_FARM -> "NAV  RETURN • moving five blocks into the farm";
                case STARTING_FLIGHT, WAITING_FOR_FLIGHT, SETTLING_FLIGHT -> "NAV  PREPARING • verifying safe low flight";
                case NONE -> throw new IllegalStateException("unreachable recovery state");
            };
        }
        if (isRerouting()) {
            return "NAV  REROUTING • obstacle avoidance • safe rejoin";
        }
        if (mode == Mode.RUNNING && glowIntercept != null) {
            String pursuit = switch (glowIntercept.stage) {
                case ACQUIRE -> "ALIGN";
                case DIRECT -> "DIRECT";
                case OBSERVE -> "VERIFY";
                case REJOIN -> "REJOIN";
            };
            return "GLOW  " + pursuit
                + " • " + glowingPlants.size() + " tracked • " + glowingPlantsHarvested + " harvested";
        }
        if (mode == Mode.RUNNING && specialRoute != SpecialRoute.NONE) {
            if (activeTrace != null) return "NAV  DRAWN: " + activeTrace.name() + " • " + completedPasses + " laps";
            if (specialRoute == SpecialRoute.LARGE_FIGURE_EIGHT
                || specialRoute == SpecialRoute.BACK_AND_FORTH
                || specialRoute == SpecialRoute.RANDOM_WEAVE) {
                return "NAV  " + specialRoute.label.toUpperCase(Locale.ROOT) + " • "
                    + Math.max(0, (specialRouteEndTick - ticks + 19) / 20) + "s remaining";
            }
            return "NAV  " + specialRoute.label.toUpperCase(Locale.ROOT)
                + " • " + (specialRouteWaypoints.size() + 1) + " points remaining";
        }
        String obstacle = config.obstacleAvoidance ? "obstacle scan on" : "obstacle scan off";
        String mention = config.stopOnNameMention ? "name guard on" : "name guard off";
        if (mode == Mode.RUNNING) {
            return String.format(Locale.ROOT, "NAV  PASS %d • %.2f b/t • %.1f turn • %.2f error • %d learned",
                completedPasses + 1, calibratedSpeedPerTick, calibratedTurnRadius, routeCrossTrack, obstacleMemory.size());
        }
        return "NAV  " + obstacle + " • " + mention;
    }

    public int hudAccentColor() {
        if (scanningField) {
            return 0xFF4CD97B;
        }
        if (mode == Mode.PAUSED) {
            return 0xFFFFD166;
        }
        return recoveryPhase != RecoveryPhase.NONE || detouring
            ? 0xFFFF9F43 : mode == Mode.RUNNING ? 0xFF35D9FF : 0xFF7F8C8D;
    }

    private double scanPercent() {
        return fieldCells == null || fieldCells.length == 0
            ? 0.0 : scanKnownCells * 100.0 / fieldCells.length;
    }

    private void flyPattern(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!player.getAbilities().mayfly || !player.getAbilities().flying) {
            stop(minecraft, "Flight ended");
            return;
        }
        calibrateMovement(player);
        var position = new MotionMath.Vec2(player.getX(), player.getZ());
        var forward = position.add(movementHeading(player).scale(Math.max(4.0, calibratedSpeedPerTick * 12)));
        if (!exclusionSegmentClear(minecraft, position, forward)) {
            releaseAttack(minecraft);
            releaseMovement(minecraft);
            beginObstacleRecovery(minecraft, "user exclusion ahead");
            return;
        }
        if (importedMapName != null
            && !importedFieldContains(player.getX(), player.getZ(), player.getBbWidth() * 0.5)) {
            beginObstacleRecovery(minecraft, "left the mapped crop footprint");
            return;
        }
        if (player.horizontalCollision && turn != null) {
            beginObstacleRecovery(minecraft, "a turn contacted an unexpected obstacle");
            return;
        }
        if (!bounds.containsExpanded(player.getX(), player.getZ(), EMERGENCY_BOUND_TOLERANCE)) {
            beginObstacleRecovery(minecraft, "left the selected field");
            return;
        }
        if (turn == null && !bounds.containsExpanded(player.getX(), player.getZ(), ROUTE_BOUND_TOLERANCE)) {
            if (!startRecoveryTurn(minecraft)) {
                beginObstacleRecovery(minecraft, "the boundary recovery curve did not fit");
                return;
            }
        } else if (turn == null) {
            if (player.horizontalCollision && !config.obstacleAvoidance) {
                stop(minecraft, "Stopped at an obstacle");
                return;
            }
            avoidObstacles(minecraft, player.horizontalCollision);
            if (mode != Mode.RUNNING) {
                return;
            }
        }

        switch (pattern) {
            case STRAIGHT -> flyStraight(minecraft);
            case TURNING -> flyTurn(minecraft);
        }
        lookDownAlongHeading(player);
    }

    private void flyStraight(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        releaseStrafe(minecraft);
        if (routeTarget == null) {
            initializeRoute(minecraft);
        }
        if (updateDetour(minecraft)) {
            if (turn != null) {
                flyTurn(minecraft);
            }
            return;
        }
        tightenRouteEndpoint();
        MotionMath.Vec2 position = new MotionMath.Vec2(player.getX(), player.getZ());
        updateGlowIntercept(minecraft, position);
        if (glowIntercept == null) {
            beginGlowIntercept(minecraft, position);
        }
        double lookAhead = FlightMotion.lookAhead(calibratedSpeedPerTick);
        MotionMath.RouteProgress progress = MotionMath.routeProgress(position, legStart, routeTarget, lookAhead);
        routeCrossTrack = progress.crossTrack();
        for (int advances = 0; glowIntercept == null && advances < 8; advances++) {
            if (!advanceSpecialRoute(minecraft, progress)) break;
            if (turn != null) {
                flyTurn(minecraft);
                return;
            }
            if (activeTrace == null || mode != Mode.RUNNING || recoveryPhase != RecoveryPhase.NONE || detouring) return;
            // Dense freehand curves must advance without skipping the steering tick.
            progress = MotionMath.routeProgress(position, legStart, routeTarget, lookAhead);
            routeCrossTrack = progress.crossTrack();
        }
        boolean passedEndpoint = routeDirection > 0
            ? longCoordinate(player) >= longCoordinate(routeTarget)
            : longCoordinate(player) <= longCoordinate(routeTarget);
        double arrivalDistance = Math.max(0.45, calibratedSpeedPerTick * 1.5);
        double boundaryDistance = routeDirection > 0
            ? longMaximum() - longCoordinate(player)
            : longCoordinate(player) - longMinimum();
        if (specialRoute == SpecialRoute.NONE && !detouring && (boundaryDistance <= turnStartDistance()
            || passedEndpoint || progress.remaining() <= arrivalDistance)) {
            if (!startBoundaryTurn(minecraft)) {
                beginObstacleRecovery(minecraft, "the route turn did not fit inside the field");
                return;
            }
            flyTurn(minecraft);
            return;
        }

        double room = detouring || glowIntercept != null || ticks < recoveryAimUntil
            || ticks - detectedObstacleTick < 40 ? 0 : Math.clamp(1.0 - routeCrossTrack / 1.5, 0.0, 1.0);
        if (activeTrace != null) room = 0;
        double routeOffset = motion.wander(ticks, config.naturalMovement ? config.headingJitterDegrees : 0.0,
            movementPreset(), room);
        double offsetScale = detouring || glowIntercept != null ? 0.0
            : Math.clamp(1.0 - routeCrossTrack / 1.5, 0.0, 1.0);
        MotionMath.Vec2 lookPoint = specialRoute == SpecialRoute.NONE || detouring
            ? progress.lookPoint()
            : MotionMath.polylineLookPoint(position, legStart, routeTarget,
                specialRouteWaypoints, lookAhead);
        MotionMath.Vec2 segment = routeTarget.subtract(legStart).normalized();
        MotionMath.Vec2 desiredPoint = glowIntercept == null
            ? lookPoint.add(new MotionMath.Vec2(-segment.z(), segment.x()).scale(routeOffset * offsetScale))
            : switch (glowIntercept.stage) {
                case ACQUIRE -> glowIntercept.aimPoint;
                case DIRECT -> MotionMath.glowGuidePoint(position, glowIntercept.target,
                    glowIntercept.aimPoint, glowIntercept.directHeading);
                case OBSERVE, REJOIN -> glowIntercept.rejoin;
            };
        if (glowIntercept == null && Math.abs(routeOffset) > 0.01 && !segmentClear(minecraft, position, desiredPoint)) {
            motion.routeOffset = motion.targetRouteOffset = 0;
            desiredPoint = lookPoint;
        }
        if (!exclusionSegmentClear(minecraft, position, desiredPoint)) {
            beginObstacleRecovery(minecraft, "the active pass meets an exclusion");
            return;
        }
        MotionMath.Vec2 desired = directionTo(player, desiredPoint);
        double headingError = MotionMath.signedAngle(heading, desired);
        if (glowIntercept != null && glowIntercept.stage == GlowStage.ACQUIRE) {
            double movementError = MotionMath.signedAngle(movementHeading(player), desired);
            double distance = position.subtract(glowIntercept.target).length();
            double mouseDegrees = Math.clamp(1.5 + Math.toDegrees(Math.abs(movementError)) * 0.05
                + Math.max(0.0, 8.0 - distance) * 0.20, 2.0, 7.0);
            double correction = Math.clamp(headingError * 0.18,
                -Math.toRadians(mouseDegrees), Math.toRadians(mouseDegrees));
            heading = heading.rotate(correction).normalized();
            if (Math.abs(movementError) >= Math.toRadians(6.0)) {
                holdTurnStrafe(minecraft, (int)Math.signum(movementError));
            } else {
                releaseStrafe(minecraft);
            }
            if (Math.abs(movementError) <= GLOW_DIRECT_ALIGNMENT
                && Math.abs(headingError) <= GLOW_DIRECT_ALIGNMENT) {
                lockDirectGlowLine(minecraft, position);
            }
        } else if (glowIntercept != null && glowIntercept.stage == GlowStage.DIRECT) {
            double movementError = MotionMath.signedAngle(movementHeading(player), desired);
            heading = desired;
            if (position.subtract(glowIntercept.target).length() > 3.0
                && Math.abs(movementError) >= Math.toRadians(6.0)) {
                holdTurnStrafe(minecraft, (int)Math.signum(movementError));
            } else {
                releaseStrafe(minecraft);
            }
        } else {
            heading = FlightMotion.followHeading(heading, desired,
                specialRoute == SpecialRoute.NONE && glowIntercept == null ? 2.5 : 5.0);
            if ((specialRoute != SpecialRoute.NONE || glowIntercept != null) && !detouring
                && Math.abs(headingError) >= Math.toRadians(18.0)) {
                holdTurnStrafe(minecraft, (int)Math.signum(headingError));
            }
        }
    }

    private void beginGlowIntercept(Minecraft minecraft, MotionMath.Vec2 position) {
        if (!config.targetGlowingPlants || glowingPlants.isEmpty() || detouring || turn != null
            || legStart == null || routeTarget == null) {
            return;
        }
        List<MotionMath.Vec2> upcomingRoute = new ArrayList<>(specialRouteWaypoints.size() + 1);
        upcomingRoute.add(routeTarget);
        upcomingRoute.addAll(specialRouteWaypoints);
        GlowingPlant selected = null;
        MotionMath.GlowInterceptPlan selectedPlan = null;
        double selectedScore = Double.POSITIVE_INFINITY;
        int approaching = 0;

        for (GlowingPlant plant : glowingPlants.values()) {
            if (ignoredGlowTargets.contains(plant.crop)
                || glowTargetCooldowns.getOrDefault(plant.crop, 0) > ticks) {
                continue;
            }
            MotionMath.Vec2 target = new MotionMath.Vec2(plant.crop.getX() + 0.5, plant.crop.getZ() + 0.5);
            MotionMath.GlowInterceptPlan plan = MotionMath.glowInterceptPlan(position, upcomingRoute,
                target, GLOW_MIN_FORWARD, GLOW_MAX_FORWARD, GLOW_MAX_CROSS_TRACK, GLOW_REJOIN_RUNWAY);
            if (plan == null) {
                continue;
            }
            double crossTrack = Math.max(0.0, (plan.cost() - plan.targetProgress()) / 2.5);
            MotionMath.Vec2 targetDirection = target.subtract(position).normalized();
            double turnAngle = Math.abs(MotionMath.signedAngle(movementHeading(minecraft.player), targetDirection));
            double activationDistance = MotionMath.glowActivationDistance(calibratedTurnRadius,
                calibratedSpeedPerTick, crossTrack, turnAngle, 22.0, GLOW_MAX_FORWARD);
            if (plan.targetProgress() > activationDistance) {
                continue;
            }
            approaching++;
            if (!glowSegmentClear(minecraft, position, target)) {
                ignoreRiskyGlow(plant.crop, "the direct flight corridor is blocked");
                continue;
            }
            if (!segmentClear(minecraft, target, plan.rejoin())) {
                plan = MotionMath.glowInterceptPlan(position, upcomingRoute, target,
                    GLOW_MIN_FORWARD, GLOW_MAX_FORWARD, GLOW_MAX_CROSS_TRACK, 0.0);
                if (plan == null || !segmentClear(minecraft, target, plan.rejoin())) {
                    ignoreRiskyGlow(plant.crop, "there is no safe far-side exit");
                    continue;
                }
            }
            if (plan.targetProgress() < selectedScore) {
                selected = plant;
                selectedPlan = plan;
                selectedScore = plan.targetProgress();
            }
        }

        if (selected != null && selectedPlan != null) {
            MotionMath.Vec2 aimPoint = shinyAimPoint(minecraft, position, selectedPlan.target());
            if (aimPoint == null) {
                ignoreRiskyGlow(selected.crop, "the through-line is too risky");
                return;
            }
            MotionMath.Vec2 rejoin = oppositeSideGlowRejoin(minecraft, position,
                selectedPlan.target(), selectedPlan.rejoin(), upcomingRoute);
            glowIntercept = new GlowIntercept(selected.crop, selectedPlan.target(), rejoin, aimPoint);
            glowIntercept.directHeading = selectedPlan.target().subtract(position).normalized();
            glowIntercept.attemptDeadlineTick = ticks + GLOW_ATTEMPT_TIMEOUT_TICKS;
            glowRoutingStatus = "Leaving the route at its closest safe approach to " + shortPos(selected.crop);
            motion.targetRouteOffset = 0.0;
        } else {
            glowRoutingStatus = approaching == 0
                ? glowingPlantCount() + " available • "
                    + ignoredGlowTargets.size() + " ignored • waiting for a route approach"
                : approaching + " nearby shiny detour" + (approaching == 1 ? " is" : "s are")
                    + " blocked; waiting for a safer route point";
        }
    }

    private void updateGlowIntercept(Minecraft minecraft, MotionMath.Vec2 position) {
        if (glowIntercept == null) {
            return;
        }
        GlowingPlant plant = glowingPlants.get(glowIntercept.crop);
        double distance = position.subtract(glowIntercept.target).length();
        if (glowIntercept.stage == GlowStage.ACQUIRE) {
            if (plant == null && distance < 5.0) {
                markGlowCleared();
            } else if (glowIntercept.directHeading != null && MotionMath.passedPoint(position,
                glowIntercept.target, glowIntercept.directHeading, GLOW_DIRECT_PASS_MARGIN)) {
                deferGlowToNextPass("Passed the approach before alignment; continuing to the far-side rejoin");
            } else if (ticks >= glowIntercept.attemptDeadlineTick) {
                deferGlowToNextPass("Could not acquire a safe direct line; retrying next pass");
            }
        } else if (glowIntercept.stage == GlowStage.DIRECT) {
            if (!glowIntercept.harvested && plant == null && distance < 5.0) {
                markGlowCleared();
            }
            if (MotionMath.passedPoint(position, glowIntercept.target,
                glowIntercept.directHeading, GLOW_DIRECT_PASS_MARGIN)) {
                if (glowIntercept.harvested) {
                    startGlowRejoin("Shiny harvested — crossing to the far-side route rejoin");
                } else {
                    glowIntercept.stage = GlowStage.OBSERVE;
                    glowIntercept.observeDeadlineTick = ticks + GLOW_POST_PASS_OBSERVE_TICKS;
                    glowRoutingStatus = "Passed shiny — checking for 1.75s while crossing the route";
                }
            } else if (ticks >= glowIntercept.attemptDeadlineTick) {
                deferGlowToNextPass("Direct pass timed out; retrying on the next lap");
            }
        } else if (glowIntercept.stage == GlowStage.OBSERVE) {
            if (plant == null) {
                markGlowCleared();
            } else if (ticks >= glowIntercept.observeDeadlineTick) {
                deferGlowToNextPass("Shiny still rendered after the pass; leaving it for the next lap");
            }
        }
        if (glowIntercept.stage == GlowStage.REJOIN
            && (position.subtract(glowIntercept.rejoin).length() <= 1.75
                || MotionMath.passedPoint(position, glowIntercept.rejoin,
                    glowIntercept.rejoin.subtract(glowIntercept.target), 0.35)
                || ticks >= glowIntercept.rejoinDeadlineTick)) {
            glowTargetCooldowns.put(glowIntercept.crop, ticks
                + (glowIntercept.harvested ? GLOW_TARGET_COOLDOWN_TICKS : GLOW_FAILED_COOLDOWN_TICKS));
            glowRoutingStatus = ticks >= glowIntercept.rejoinDeadlineTick
                ? "Rejoin timed out; route follower resumed automatically"
                : glowIntercept.harvested ? "Glow harvested; resumed discovery patrol"
                : "Shiny missed; continuing the route until its next closest approach";
            glowIntercept = null;
        }
    }

    private void markGlowCleared() {
        if (!glowIntercept.harvested) {
            glowIntercept.harvested = true;
        }
        glowingPlants.remove(glowIntercept.crop);
        glowFailureCounts.remove(glowIntercept.crop);
        ignoredGlowTargets.remove(glowIntercept.crop);
        if (glowIntercept.stage == GlowStage.DIRECT) {
            glowRoutingStatus = "Shiny cleared — holding the straight line through the far side";
        } else {
            startGlowRejoin("Shiny cleared; crossing to the far-side route rejoin");
        }
    }

    private void deferGlowToNextPass(String status) {
        glowIntercept.harvested = false;
        int failures = glowFailureCounts.merge(glowIntercept.crop, 1, Integer::sum);
        if (failures > GLOW_MISSES_BEFORE_IGNORE) {
            ignoredGlowTargets.add(glowIntercept.crop);
            startGlowRejoin("Ignoring shiny after " + failures + " missed passes; continuing the route");
        } else {
            startGlowRejoin(status + " • miss " + failures + "/" + (GLOW_MISSES_BEFORE_IGNORE + 1));
        }
    }

    private void ignoreRiskyGlow(BlockPos crop, String reason) {
        ignoredGlowTargets.add(crop.immutable());
        glowFailureCounts.remove(crop);
        glowTargetCooldowns.remove(crop);
        glowRoutingStatus = "Ignoring risky shiny at " + shortPos(crop) + ": " + reason;
    }

    private void startGlowRejoin(String status) {
        glowIntercept.stage = GlowStage.REJOIN;
        glowIntercept.rejoinDeadlineTick = ticks + GLOW_REJOIN_TIMEOUT_TICKS;
        glowRoutingStatus = status;
    }

    private MotionMath.Vec2 shinyAimPoint(Minecraft minecraft, MotionMath.Vec2 position,
                                           MotionMath.Vec2 target) {
        double passDistance = glowPassDistance();
        for (double distance : new double[]{passDistance, 1.0, 0.0}) {
            MotionMath.Vec2 candidate = MotionMath.directAimPoint(position, target, distance);
            if (glowSegmentClear(minecraft, position, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private MotionMath.Vec2 oppositeSideGlowRejoin(Minecraft minecraft, MotionMath.Vec2 position,
                                                    MotionMath.Vec2 target, MotionMath.Vec2 fallback,
                                                    List<MotionMath.Vec2> upcomingRoute) {
        MotionMath.Vec2 direction = target.subtract(position).normalized();
        if (direction.length() == 0.0) {
            return fallback;
        }
        MotionMath.Vec2 desiredExit = target.add(direction.scale(8.0));
        List<MotionMath.Vec2> route = new ArrayList<>(upcomingRoute.size() + 1);
        route.add(position);
        route.addAll(upcomingRoute);
        MotionMath.Vec2 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int index = 1; index < route.size(); index++) {
            MotionMath.Vec2 start = route.get(index - 1);
            MotionMath.Vec2 segment = route.get(index).subtract(start);
            double lengthSquared = segment.dot(segment);
            if (lengthSquared < 1.0E-6) {
                continue;
            }
            double projection = Math.clamp(desiredExit.subtract(start).dot(segment) / lengthSquared, 0.0, 1.0);
            MotionMath.Vec2 candidate = start.add(segment.scale(projection));
            double forward = candidate.subtract(target).dot(direction);
            if (forward < 3.0 || forward > 24.0 || !glowSegmentClear(minecraft, target, candidate)) {
                continue;
            }
            double score = candidate.subtract(desiredExit).length() + Math.abs(forward - 8.0) * 0.15;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best != null) {
            return best;
        }
        for (double distance : new double[]{8.0, 6.0, 4.0, 2.0}) {
            MotionMath.Vec2 candidate = target.add(direction.scale(distance));
            if (glowSegmentClear(minecraft, target, candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    private void lockDirectGlowLine(Minecraft minecraft, MotionMath.Vec2 position) {
        MotionMath.Vec2 direct = glowIntercept.target.subtract(position).normalized();
        if (direct.length() == 0.0) {
            return;
        }
        MotionMath.Vec2 aimPoint = shinyAimPoint(minecraft, position, glowIntercept.target);
        if (aimPoint == null) {
            return;
        }
        glowIntercept.stage = GlowStage.DIRECT;
        glowIntercept.directHeading = direct;
        glowIntercept.aimPoint = aimPoint;
        heading = direct;
        releaseStrafe(minecraft);
        glowRoutingStatus = "Direction acquired — flying a direct line through " + shortPos(glowIntercept.crop);
    }

    private double glowPassDistance() {
        return Math.clamp(calibratedSpeedPerTick * 10.0, 3.0, 6.0);
    }

    private void flyTurn(Minecraft minecraft) {
        if (turn == null) {
            releaseStrafe(minecraft);
            pattern = Pattern.STRAIGHT;
            return;
        }
        holdTurnStrafe(minecraft, turn.strafeSign());
        turn.tick++;
        heading = turn.heading();

        if (turn.finished()) {
            Turn completed = turn;
            heading = completed.target;
            turn = null;
            releaseStrafe(minecraft);
            pattern = Pattern.STRAIGHT;
            if (pendingTurnRoute != null) {
                MotionMath.Vec2[] route = pendingTurnRoute;
                SpecialRoute routeType = pendingTurnRouteType;
                pendingTurnRoute = null;
                pendingTurnRouteType = null;
                if (!activateClosedRoute(minecraft, routeType, route, false)
                    && !beginPerimeterRoute(minecraft, false)) {
                    beginObstacleRecovery(minecraft, "the turnaround could not rejoin a safe route");
                }
                scheduleWander();
                motion.targetRouteOffset = 0.0;
                return;
            }
            if (specialRoute == SpecialRoute.NONE || detouring || completed.boundary || completed.recovery) {
                legStart = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
            }
            if (completed.boundary) {
                completedPasses++;
                if (ticks >= nextSpecialRouteTick) {
                    beginSpecialRoute(minecraft);
                }
            } else if (completed.recovery) {
                initializeRoute(minecraft);
            }
            scheduleWander();
            motion.targetRouteOffset = 0.0;
        }
    }

    private boolean startBoundaryTurn(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        MotionMath.Vec2 targetHeading = axisHeading(-routeDirection);
        int preferredSign = lastBoundaryTurnSign == 0
            ? (random.nextBoolean() ? 1 : -1)
            : repeatedBoundaryTurnSigns >= 2 || random.nextDouble() < 0.70
                ? -lastBoundaryTurnSign : lastBoundaryTurnSign;
        boolean preferStrafe = random.nextDouble() < 0.78;
        Turn selected = null;
        int maximumDuration = boundaryTurnDuration();
        search:
        for (int turnSign : new int[]{preferredSign, -preferredSign}) {
            for (boolean strafeAssisted : new boolean[]{preferStrafe, !preferStrafe}) {
                for (int duration = maximumDuration; duration >= MIN_TURN_TICKS; duration -= 2) {
                    Turn candidate = createTurn(player, targetHeading, duration, false, true,
                        turnSign, strafeAssisted);
                    if (validatedTurn(minecraft, candidate, ROUTE_BOUND_TOLERANCE)) {
                        selected = candidate;
                        break search;
                    }
                }
            }
        }
        if (selected == null) {
            return false;
        }

        int selectedSign = selected.turnDirection();
        repeatedBoundaryTurnSigns = selectedSign == lastBoundaryTurnSign
            ? repeatedBoundaryTurnSigns + 1 : 1;
        lastBoundaryTurnSign = selectedSign;
        double crossShift = crossCoordinate(selected.plannedEnd()) - crossCoordinate(player);
        if (Math.abs(crossShift) > 0.25) {
            laneSweepDirection = crossShift > 0.0 ? 1 : -1;
        }
        routeDirection = -routeDirection;
        double nextCross = nextLaneCross(crossCoordinate(selected.plannedEnd()));
        legStart = selected.plannedEnd();
        routeTarget = laneEndpoint(routeDirection, nextCross);
        turn = selected;
        pattern = Pattern.TURNING;
        return true;
    }

    private boolean startTurn(Minecraft minecraft, MotionMath.Vec2 target, int maximumDuration,
                              boolean recovery, int preferredTurnSign) {
        LocalPlayer player = minecraft.player;
        int longest = Math.max(MIN_TURN_TICKS, Math.min(config.turnDurationTicks, maximumDuration));
        boolean preferStrafe = recovery || random.nextDouble() < 0.70;
        for (boolean strafeAssisted : new boolean[]{preferStrafe, !preferStrafe}) {
            for (int duration = longest; duration >= MIN_TURN_TICKS; duration -= 2) {
                int[] signs = preferredTurnSign == 0
                    ? new int[]{1, -1}
                    : new int[]{preferredTurnSign, -preferredTurnSign};
                for (int sign : signs) {
                    Turn candidate = createTurn(player, target, duration, recovery, false,
                        sign, strafeAssisted);
                    double tolerance = recovery ? EMERGENCY_BOUND_TOLERANCE : ROUTE_BOUND_TOLERANCE;
                    if (validatedTurn(minecraft, candidate, tolerance)) {
                        turn = candidate;
                        pattern = Pattern.TURNING;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Turn createTurn(LocalPlayer player, MotionMath.Vec2 target, int duration,
                            boolean recovery, boolean boundary, int turnSign, boolean strafeAssisted) {
        double yaw = Math.toRadians(player.getYRot());
        MotionMath.Vec2 actualLookHeading = new MotionMath.Vec2(-Math.sin(yaw), Math.cos(yaw));
        MotionMath.Vec2 actualMovementHeading = movementHeading(player);
        double planningSpeed = Math.max(calibratedSpeedPerTick, movementStep(player)) * 1.15;
        float pitchLift = (float)(2.0 + random.nextDouble() * 2.0);
        return new Turn(heading, target, actualLookHeading, actualMovementHeading, player.getX(), player.getZ(),
            planningSpeed, duration, pitchLift, recovery, boundary, turnSign,
            strafeAssisted ? TURN_STRAFE_WEIGHT : 0.0);
    }

    private boolean validatedTurn(Minecraft minecraft, Turn candidate, double tolerance) {
        if (!exclusionPathClear(minecraft, candidate.plannedPoints)) return false;
        if (!candidate.staysInside(bounds, tolerance)) {
            return false;
        }
        AABB body = minecraft.player.getBoundingBox().inflate(
            config.obstacleClearance, 0.0, config.obstacleClearance);
        MotionMath.Vec2 origin = candidate.plannedPoints[0];
        for (int index = 2; index < candidate.plannedPoints.length; index += 2) {
            MotionMath.Vec2 point = candidate.plannedPoints[index];
            if (importedMapName != null && !importedFieldContains(
                point.x(), point.z(), minecraft.player.getBbWidth() * 0.5 + config.obstacleClearance)) {
                return false;
            }
            AABB probe = body.move(point.x() - origin.x(), 0.0, point.z() - origin.z());
            if (findBlockingBlock(minecraft, probe) != null || intersectsRememberedObstacle(probe)) {
                return false;
            }
        }
        return true;
    }

    private boolean startRecoveryTurn(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        detouring = false;
        resumeTarget = null;
        detourRejoinTarget = null;
        MotionMath.Vec2 inward = new MotionMath.Vec2(bounds.centerX() - player.getX(), bounds.centerZ() - player.getZ()).normalized();
        legStart = new MotionMath.Vec2(player.getX(), player.getZ());
        routeTarget = new MotionMath.Vec2(bounds.centerX(), bounds.centerZ());
        return startTurn(minecraft, inward, 24, true, 0);
    }

    private void beginObstacleRecovery(Minecraft minecraft, String reason) {
        if (recoveryPhase != RecoveryPhase.NONE) {
            return;
        }
        farmRecoveryAttempts = 0;
        clearRecoveryNavigation(minecraft);
        if (startObstacleBounce(minecraft)) {
            recoveryPhase = RecoveryPhase.BOUNCING;
            recoveryStartedTick = ticks;
            recoveryOrigin = minecraft.player.position();
            resetBpsWindow();
            message(minecraft, "Obstacle recovery — bouncing "
                + (turn.turnDirection() > 0 ? "left" : "right") + " at full speed (" + reason + ")");
            return;
        }
        beginFarmRecovery(minecraft, "no safe bounce fit after " + reason);
    }

    private boolean startObstacleBounce(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        MotionMath.Vec2 position = new MotionMath.Vec2(player.getX(), player.getZ());
        MotionMath.Vec2 travel = movementHeading(player).normalized();
        MotionMath.Vec2 inward = new MotionMath.Vec2(
            bounds.centerX() - player.getX(), bounds.centerZ() - player.getZ()).normalized();
        ObstacleHit impact = scanCollision(minecraft, travel, 2.5);
        int preferredSign = (int)Math.signum(MotionMath.signedAngle(travel, inward));
        if (impact != null) {
            detectedObstacleBox = impact.box;
            detectedObstacleTick = ticks;
            rememberObstacle(impact, preferredSign == 0 ? 1 : preferredSign);
            Vec3 center = impact.box.getCenter();
            MotionMath.Vec2 towardObstacle = new MotionMath.Vec2(
                center.x - player.getX(), center.z - player.getZ()).normalized();
            int obstacleSide = (int)Math.signum(MotionMath.signedAngle(travel, towardObstacle));
            if (obstacleSide != 0) {
                preferredSign = -obstacleSide;
            }
        }
        if (preferredSign == 0) {
            preferredSign = random.nextBoolean() ? 1 : -1;
        }

        Turn selected = null;
        double selectedScore = Double.POSITIVE_INFINITY;
        MotionMath.Vec2 center = new MotionMath.Vec2(bounds.centerX(), bounds.centerZ());
        for (int sign : new int[]{preferredSign, -preferredSign}) {
            for (int angle : new int[]{110, 135, 165}) {
                MotionMath.Vec2 target = travel.rotate(Math.toRadians(sign * angle)).normalized();
                for (int duration = MIN_TURN_TICKS; duration <= MAX_ROUTE_TURN_TICKS; duration += 2) {
                    Turn candidate = createTurn(player, target, duration, true, false, sign, true);
                    if (!validatedBounceTurn(minecraft, candidate)) {
                        continue;
                    }
                    double score = candidate.plannedEnd().subtract(center).length()
                        + Math.abs(angle - 135) * 0.015
                        + (sign == preferredSign ? 0.0 : 0.35)
                        + duration * 0.02;
                    if (score < selectedScore) {
                        selected = candidate;
                        selectedScore = score;
                    }
                }
            }
        }
        if (selected == null) {
            return false;
        }
        legStart = position;
        routeTarget = center;
        turn = selected;
        pattern = Pattern.TURNING;
        return true;
    }

    private boolean validatedBounceTurn(Minecraft minecraft, Turn candidate) {
        // Exclusions have no collision-escape grace period.
        if (!exclusionPathClear(minecraft, candidate.plannedPoints)) return false;
        AABB body = minecraft.player.getBoundingBox().inflate(
            config.obstacleClearance, 0.0, config.obstacleClearance);
        MotionMath.Vec2 origin = candidate.plannedPoints[0];
        for (int index = BOUNCE_ESCAPE_GRACE_TICKS + 1;
             index < candidate.plannedPoints.length; index++) {
            MotionMath.Vec2 point = candidate.plannedPoints[index];
            if (!bounds.containsExpanded(point.x(), point.z(), EMERGENCY_BOUND_TOLERANCE)
                || importedMapName != null && !importedFieldContains(point.x(), point.z(),
                    minecraft.player.getBbWidth() * 0.5 + config.obstacleClearance)) {
                return false;
            }
            AABB probe = body.move(point.x() - origin.x(), 0.0, point.z() - origin.z());
            if (findBlockingBlock(minecraft, probe) != null || intersectsRememberedObstacle(probe)) {
                return false;
            }
        }
        return true;
    }

    private void tickRecovery(Minecraft minecraft) {
        switch (recoveryPhase) {
            case BOUNCING -> tickObstacleBounce(minecraft);
            case WAITING_FOR_FARM -> tickFarmTeleport(minecraft);
            case ENTERING_FARM -> tickFarmEntry(minecraft);
            case STARTING_FLIGHT, WAITING_FOR_FLIGHT, SETTLING_FLIGHT -> tickPreparedFlight(minecraft);
            case NONE -> {
            }
        }
    }

    private void tickObstacleBounce(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!player.getAbilities().mayfly || !player.getAbilities().flying) {
            stop(minecraft, "Flight ended during obstacle recovery");
            return;
        }
        int age = ticks - recoveryStartedTick;
        if (age >= BOUNCE_TIMEOUT_TICKS
            || player.horizontalCollision && age >= BOUNCE_COLLISION_TIMEOUT_TICKS) {
            beginFarmRecovery(minecraft, "the bounce remained blocked");
            return;
        }
        holdAttack(minecraft);
        holdMovement(minecraft);
        flyTurn(minecraft);
        lookDownAlongHeading(player);
        if (turn != null || recoveryPhase != RecoveryPhase.BOUNCING) {
            return;
        }
        if (specialRoute == SpecialRoute.PERIMETER) {
            recoveryPhase = RecoveryPhase.NONE;
            recoveryOrigin = null;
            recoveryLastPosition = null;
            farmRecoveryAttempts = 0;
            nextObstacleAvoidanceTick = ticks + 40;
            resetBpsWindow();
            message(minecraft, "Bounce clear — rejoined the mapped perimeter");
        } else {
            beginFarmRecovery(minecraft, "the bounce could not rejoin the mapped perimeter");
        }
    }

    private void beginFarmRecovery(Minecraft minecraft, String reason) {
        releasePreparedVertical(minecraft);
        if (!savedFieldMatches(minecraft) || minecraft.player == null || minecraft.gui.screen() != null) {
            stop(minecraft, "Farm return requires the saved map in its original world and a closed GUI");
            return;
        }
        if (++farmRecoveryAttempts > MAX_FARM_RECOVERY_ATTEMPTS) {
            stop(minecraft, "Recovery stopped after /farm could not restore a safe route");
            return;
        }
        clearRecoveryNavigation(minecraft);
        releaseAttack(minecraft);
        releaseMovement(minecraft);
        recoveryPhase = RecoveryPhase.WAITING_FOR_FARM;
        recoveryStartedTick = ticks;
        recoveryOrigin = minecraft.player.position();
        recoveryLastPosition = recoveryOrigin;
        farmEntryStart = null;
        if (minecraft.getConnection() == null) {
            stop(minecraft, "Recovery stopped: no server connection for /farm");
            return;
        }
        minecraft.getConnection().sendCommand("farm");
        message(minecraft, "Recovery fallback " + farmRecoveryAttempts + "/"
            + MAX_FARM_RECOVERY_ATTEMPTS + " — running /farm (" + reason + ")");
    }

    private void tickFarmTeleport(Minecraft minecraft) {
        releaseAttack(minecraft);
        releaseMovement(minecraft);
        int age = ticks - recoveryStartedTick;
        Vec3 position = minecraft.player.position();
        double stepDistance = recoveryLastPosition == null ? 0.0
            : Math.sqrt(position.distanceToSqr(recoveryLastPosition));
        double totalDistance = recoveryOrigin == null ? 0.0
            : Math.sqrt(position.distanceToSqr(recoveryOrigin));
        recoveryLastPosition = position;
        if (age >= 2 && (stepDistance >= FARM_TELEPORT_STEP_DISTANCE
            || totalDistance >= FARM_TELEPORT_TOTAL_DISTANCE)) {
            double yaw = Math.toRadians(minecraft.player.getYRot());
            heading = new MotionMath.Vec2(-Math.sin(yaw), Math.cos(yaw)).normalized();
            farmEntryStart = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
            lastFarmEntryStart = farmEntryStart;
            lastFarmEntryEnd = farmEntryStart.add(heading.scale(FARM_ENTRY_DISTANCE));
            if (!exclusionSegmentClear(minecraft, farmEntryStart, lastFarmEntryEnd)
                || !importedFieldContains(lastFarmEntryEnd.x(), lastFarmEntryEnd.z(), minecraft.player.getBbWidth() / 2.0)) {
                stop(minecraft, "/farm entry does not reach a safe mapped crop footprint");
                return;
            }
            recoveryPhase = RecoveryPhase.ENTERING_FARM;
            recoveryStartedTick = ticks;
            recoveryLastPosition = null;
            message(minecraft, "/farm arrived — moving five blocks inward");
            return;
        }
        if (age >= FARM_TELEPORT_TIMEOUT_TICKS) {
            beginFarmRecovery(minecraft, "the server did not confirm the teleport");
        }
    }

    private void tickFarmEntry(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (!player.getAbilities().mayfly || !preparingStart && !player.getAbilities().flying) {
            stop(minecraft, "/farm disabled flight — double-tap Space before restarting Cropium");
            return;
        }
        var position = new MotionMath.Vec2(player.getX(), player.getZ());
        if (!exclusionSegmentClear(minecraft, position, position.add(heading.scale(1)))
            || !farmEntryStepClear(minecraft)) {
            stop(minecraft, "Farm entry corridor is blocked or excluded");
            return;
        }
        int age = ticks - recoveryStartedTick;
        if (player.horizontalCollision && age >= 4) {
            beginFarmRecovery(minecraft, "the farm entry path was blocked");
            return;
        }
        releaseAttack(minecraft);
        holdMovement(minecraft);
        if (preparingStart) {
            minecraft.options.keySprint.setDown(false);
            player.setSprinting(false);
            modHoldingSprint = false;
        }
        releaseStrafe(minecraft);
        lookDownAlongHeading(player);
        if (farmEntryStart != null && new MotionMath.Vec2(player.getX(), player.getZ())
            .subtract(farmEntryStart).length() >= FARM_ENTRY_DISTANCE) {
            releaseMovement(minecraft);
            if (preparingStart) { beginPreparedFlight(minecraft); return; }
            initializeRoute(minecraft);
            if (specialRoute != SpecialRoute.PERIMETER) {
                beginFarmRecovery(minecraft, "the five-block entry could not reach a safe route");
                return;
            }
            recoveryPhase = RecoveryPhase.NONE;
            recoveryOrigin = null;
            recoveryLastPosition = null;
            farmEntryStart = null;
            farmRecoveryAttempts = 0;
            nextObstacleAvoidanceTick = ticks + 40;
            resetBpsWindow();
            holdAttack(minecraft);
            holdMovement(minecraft);
            message(minecraft, "Recovered through /farm — mapped perimeter resumed");
            return;
        }
        if (age >= FARM_ENTRY_TIMEOUT_TICKS) {
            beginFarmRecovery(minecraft, "the five-block farm entry timed out");
        }
    }

    private void clearRecoveryNavigation(Minecraft minecraft) {
        if (glowIntercept != null && !glowIntercept.harvested) {
            ignoreRiskyGlow(glowIntercept.crop, "an obstacle interrupted its approach");
        }
        glowIntercept = null;
        turn = null;
        pendingTurnRoute = null;
        pendingTurnRouteType = null;
        specialRoute = SpecialRoute.NONE;
        specialRouteWaypoints.clear();
        specialRouteTemplate = null;
        resumeTarget = null;
        detourRejoinTarget = null;
        detouring = false;
        detourStage = 0;
        releaseStrafe(minecraft);
        pattern = Pattern.STRAIGHT;
    }

    private boolean farmEntryStepClear(Minecraft minecraft) {
        var player = minecraft.player;
        var probe = player.getBoundingBox().move(heading.x() * 0.4, 0, heading.z() * 0.4);
        if (findBlockingBlock(minecraft, probe) != null) return false;
        if (player.getAbilities().flying) return true;
        for (int x = Mth.floor(probe.minX); x <= Mth.floor(probe.maxX); x++) {
            for (int z = Mth.floor(probe.minZ); z <= Mth.floor(probe.maxZ); z++) {
                boolean supported = false;
                for (int y = Mth.floor(player.getY()); y >= Mth.floor(player.getY()) - 2; y--) {
                    var block = new BlockPos(x, y, z);
                    if (!minecraft.level.hasChunkAt(block)) return false;
                    var shape = minecraft.level.getBlockState(block).getCollisionShape(minecraft.level, block);
                    if (shape.isEmpty()) continue;
                    double top = y + shape.bounds().maxY;
                    supported = top >= player.getY() - 0.61 && top <= player.getY() + 0.61;
                    break;
                }
                if (!supported) return false;
            }
        }
        return true;
    }

    private void beginPreparedFlight(Minecraft minecraft) {
        releaseAttack(minecraft);
        releaseMovement(minecraft);
        releasePreparedVertical(minecraft);
        var player = minecraft.player;
        if (!savedFieldMatches(minecraft) || !player.getAbilities().mayfly
            || !importedFieldContains(player.getX(), player.getZ(), player.getBbWidth() / 2.0)
            || findBlockingBlock(minecraft, player.getBoundingBox().expandTowards(0, 1.5, 0)) != null) {
            stop(minecraft, "Prepared start needs loaded crop space and clear flight headroom");
            return;
        }
        recoveryStartedTick = ticks;
        if (player.getAbilities().flying) recoveryPhase = RecoveryPhase.SETTLING_FLIGHT;
        else if (++preparedFlightAttempts <= 3) recoveryPhase = RecoveryPhase.STARTING_FLIGHT;
        else stop(minecraft, "Prepared start stopped: vanilla flight was not accepted");
    }

    private boolean preparedFlightReady(LocalPlayer player, int age) {
        return MineLayout.readyToMine(age, player.getY() - bounds.cropY, player.getDeltaMovement().y);
    }

    private void tickPreparedFlight(Minecraft minecraft) {
        releaseAttack(minecraft);
        releaseMovement(minecraft);
        releasePreparedVertical(minecraft);
        var player = minecraft.player;
        if (!preparingStart || !savedFieldMatches(minecraft) || !player.getAbilities().mayfly
            || minecraft.gui.screen() != null
            || !importedFieldContains(player.getX(), player.getZ(), player.getBbWidth() / 2.0)
            || findBlockingBlock(minecraft, player.getBoundingBox()) != null) {
            stop(minecraft, "Prepared farm start lost its safe flight space");
            return;
        }
        int age = ticks - recoveryStartedTick;
        if (recoveryPhase == RecoveryPhase.STARTING_FLIGHT) {
            if (findBlockingBlock(minecraft, player.getBoundingBox().expandTowards(0, 1.5, 0)) != null) {
                stop(minecraft, "Prepared flight headroom is blocked");
                return;
            }
            minecraft.options.keyJump.setDown(MerchantRestockLogic.flightTapDown(age));
            modHoldingJump = true;
            if (age >= 5) {
                releasePreparedVertical(minecraft);
                recoveryPhase = RecoveryPhase.WAITING_FOR_FLIGHT;
                recoveryStartedTick = ticks;
            }
        } else if (recoveryPhase == RecoveryPhase.WAITING_FOR_FLIGHT) {
            if (player.getAbilities().flying) {
                recoveryPhase = RecoveryPhase.SETTLING_FLIGHT;
                recoveryStartedTick = ticks;
            } else if (age >= 35) beginPreparedFlight(minecraft);
        } else {
            if (!player.getAbilities().flying) { beginPreparedFlight(minecraft); return; }
            if (preparedFlightReady(player, age)) {
                // Run the original route entry validation and preserve manual H-start behavior.
                mode = Mode.READY;
                resetRecoveryState();
                if (!start(minecraft)) stop(minecraft, "Prepared flight could not join a safe farm perimeter");
                return;
            }
            double clearance = player.getY() - bounds.cropY;
            if (MineLayout.descentTapForClearance(age, clearance, player.getDeltaMovement().y,
                player.getAbilities().getFlyingSpeed())) {
                minecraft.options.keyShift.setDown(true);
                modHoldingSneak = true;
            } else if (age >= 4 && age % 6 == 4 && clearance < 0.35
                && Math.abs(player.getDeltaMovement().y) < 0.035
                && findBlockingBlock(minecraft, player.getBoundingBox().expandTowards(0, 0.75, 0)) == null) {
                minecraft.options.keyJump.setDown(true);
                modHoldingJump = true;
            }
            if (age >= MineLayout.FLIGHT_SETTLE_TICKS) stop(minecraft, "Prepared start stopped: low flight could not settle safely");
        }
    }

    private void releasePreparedVertical(Minecraft minecraft) {
        if (modHoldingJump) minecraft.options.keyJump.setDown(false);
        if (modHoldingSneak) minecraft.options.keyShift.setDown(false);
        modHoldingJump = modHoldingSneak = false;
    }

    private boolean exclusionPathClear(Minecraft minecraft, MotionMath.Vec2[] points) {
        for (int i = 1; i < points.length; i++) {
            if (!exclusionSegmentClear(minecraft, points[i - 1], points[i])) return false;
        }
        return true;
    }

    private void resetRecoveryState() {
        preparingStart = false;
        preparedFlightAttempts = 0;
        recoveryAimUntil = 0;
        recoveryPhase = RecoveryPhase.NONE;
        recoveryStartedTick = 0;
        farmRecoveryAttempts = 0;
        recoveryOrigin = null;
        recoveryLastPosition = null;
        farmEntryStart = null;
    }

    private void lookDownAlongHeading(LocalPlayer player) {
        double room = glowIntercept != null || detouring || turn != null || ticks < recoveryAimUntil
            || recoveryPhase != RecoveryPhase.NONE || ticks - detectedObstacleTick < 40 ? 0 : 1;
        float pitchOffset = motion.pitchOffset(ticks, config.naturalMovement, movementPreset(), room);
        float maneuverPitch = switch (pattern) {
            case TURNING -> turn == null ? 0.0F : -turn.pitchArc();
            case STRAIGHT -> 0.0F;
        };
        float targetPitch = config.lookDownPitch + pitchOffset + maneuverPitch;
        float yawResponse = 0.50F;
        if (glowIntercept != null && (glowIntercept.stage == GlowStage.ACQUIRE
            || glowIntercept.stage == GlowStage.DIRECT) && bounds != null) {
            double dx = glowIntercept.target.x() - player.getX();
            double dz = glowIntercept.target.z() - player.getZ();
            double horizontal = Math.hypot(dx, dz);
            if (horizontal <= 6.0) {
                double blend = Math.clamp((6.0 - horizontal) / 3.0, 0.0, 1.0);
                float cropPitch = (float)Math.toDegrees(Math.atan2(
                    player.getEyeY() - (bounds.cropY + 0.5), Math.max(0.20, horizontal)));
                targetPitch += (cropPitch - targetPitch) * (float)blend;
            }
            yawResponse = glowIntercept.stage == GlowStage.DIRECT ? 0.70F : 0.45F;
        }
        targetPitch += FlightMotion.pitchJitter(ticks, config.naturalMovement) * (float)room * movementPreset().pitchLimit() / 2.5F;
        Minecraft minecraft = Minecraft.getInstance();
        if (ticks < recoveryAimUntil && minecraft.player == player && validRecoveryPitch(minecraft, recoveryPitch)) targetPitch = recoveryPitch;
        player.setYRot(FlightMotion.easeYaw(player.getYRot(), heading, yawResponse));
        player.setXRot(FlightMotion.easePitch(player.getXRot(), targetPitch));
    }

    private static double movementStep(LocalPlayer player) {
        double horizontalSpeed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
        return Math.clamp(horizontalSpeed, 0.05, 2.50);
    }

    private void avoidObstacles(Minecraft minecraft, boolean emergency) {
        if (!config.obstacleAvoidance || ticks < nextObstacleAvoidanceTick) {
            return;
        }
        MotionMath.Vec2 travel = movementHeading(minecraft.player);
        double lookAhead = Math.max(config.obstacleLookAhead,
            calibratedTurnRadius + 5.0);
        if (detouring) {
            if (scanCollision(minecraft, travel, Math.min(lookAhead, 6.0)) != null || emergency) {
                beginObstacleRecovery(minecraft, "the obstacle bypass became blocked");
            }
            return;
        }
        ObstacleHit hit = scanCollision(minecraft, travel, lookAhead);
        ObstacleHit remembered = scanRememberedObstacle(minecraft, travel, lookAhead + 8.0);
        if (hit == null || (remembered != null && remembered.distance < hit.distance)) {
            hit = remembered;
        }
        if (hit == null && emergency) {
            beginObstacleRecovery(minecraft, "contacted an unmapped obstacle");
            return;
        }
        if (hit == null) {
            return;
        }
        if (deferObstacleUntilGlowPass(minecraft, hit)) {
            nextObstacleAvoidanceTick = ticks + 2;
            return;
        }

        detectedObstacleBox = hit.box;
        detectedObstacleTick = ticks;
        beginDetour(minecraft, hit, travel);
    }

    private boolean deferObstacleUntilGlowPass(Minecraft minecraft, ObstacleHit hit) {
        if (glowIntercept == null
            || (glowIntercept.stage != GlowStage.ACQUIRE && glowIntercept.stage != GlowStage.DIRECT)) {
            return false;
        }
        MotionMath.Vec2 position = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
        MotionMath.Vec2 guide = glowIntercept.stage == GlowStage.DIRECT
            ? MotionMath.glowGuidePoint(position, glowIntercept.target,
                glowIntercept.aimPoint, glowIntercept.directHeading)
            : glowIntercept.aimPoint;
        double immediateCollisionDistance = Math.max(1.25, calibratedSpeedPerTick * 3.0);
        return hit.distance > immediateCollisionDistance && glowSegmentClear(minecraft, position, guide);
    }

    private void beginDetour(Minecraft minecraft, ObstacleHit obstacle, MotionMath.Vec2 travel) {
        LocalPlayer player = minecraft.player;
        if (glowIntercept != null && !glowIntercept.harvested) {
            ignoreRiskyGlow(glowIntercept.crop, "an obstacle interrupted its approach");
        }
        glowIntercept = null;
        RememberedObstacle remembered = findRememberedObstacle(obstacle.key);
        int preferredSide = remembered == null ? (random.nextBoolean() ? 1 : -1) : remembered.side;
        if (importedMapName != null) {
            rememberObstacle(obstacle, preferredSide);
            detourCount++;
            nextObstacleAvoidanceTick = ticks + MAX_ROUTE_TURN_TICKS;
            if (beginPerimeterRoute(minecraft, false)) {
                resetBpsWindow();
            } else {
                beginObstacleRecovery(minecraft, "no high-clearance mapped route fit");
            }
            return;
        }
        DetourChoice choice = null;
        for (int side : new int[]{preferredSide, -preferredSide}) {
            for (int angle = 30; angle <= 70; angle += 10) {
                DetourChoice candidate = evaluateDetour(minecraft, obstacle, travel, side, angle);
                if (candidate.clearEnough
                    && (choice == null || candidate.distance < choice.distance - (side == preferredSide ? 0.35 : 0.0))) {
                    choice = candidate;
                }
            }
        }

        if (choice == null) {
            rememberObstacle(obstacle, preferredSide);
            beginObstacleRecovery(minecraft, "no in-bounds full-speed bypass fit");
            return;
        }

        resumeTarget = routeTarget;
        detourRejoinTarget = choice.rejoin;
        detourStage = 0;
        routeTarget = choice.target;
        legStart = new MotionMath.Vec2(player.getX(), player.getZ());
        detouring = true;
        previousDetourDistance = distanceTo(player, routeTarget);
        detourCount++;
        rememberObstacle(obstacle, choice.side);
        resetBpsWindow();

        MotionMath.Vec2 targetHeading = directionTo(player, routeTarget);
        int maximumDuration = Math.min(MAX_BOUNDARY_TURN_TICKS, config.turnDurationTicks);
        if (!startTurn(minecraft, targetHeading, maximumDuration, false, choice.side)) {
            detouring = false;
            routeTarget = resumeTarget;
            resumeTarget = null;
            detourRejoinTarget = null;
            beginObstacleRecovery(minecraft, "the obstacle bypass turn did not fit");
            return;
        }
        nextObstacleAvoidanceTick = ticks + Math.max(18, maximumDuration);
    }

    private DetourChoice evaluateDetour(Minecraft minecraft, ObstacleHit obstacle, MotionMath.Vec2 travel,
                                        int side, int angleDegrees) {
        LocalPlayer player = minecraft.player;
        MotionMath.Vec2 candidateHeading = travel.rotate(Math.toRadians(side * angleDegrees)).normalized();
        double travelDistance = Math.max(7.0,
            Math.min(config.obstacleLookAhead + 4.0, obstacle.distance + config.obstacleClearance + 5.0));
        MotionMath.Vec2 target = new MotionMath.Vec2(
            player.getX() + candidateHeading.x() * travelDistance,
            player.getZ() + candidateHeading.z() * travelDistance);
        MotionMath.Vec2 rejoin = rejoinPoint(player, obstacle.distance + 8.0);
        boolean inside = bounds.containsExpanded(target.x(), target.z(), ROUTE_BOUND_TOLERANCE)
            && bounds.containsExpanded(rejoin.x(), rejoin.z(), ROUTE_BOUND_TOLERANCE);
        boolean clear = inside
            && segmentClear(minecraft, new MotionMath.Vec2(player.getX(), player.getZ()), target)
            && segmentClear(minecraft, target, rejoin);
        double distance = distanceTo(player, target) + target.subtract(rejoin).length();
        return new DetourChoice(target, rejoin, side, distance, clear);
    }

    private boolean updateDetour(Minecraft minecraft) {
        if (!detouring || routeTarget == null) {
            return false;
        }
        LocalPlayer player = minecraft.player;
        double distance = distanceTo(player, routeTarget);
        boolean passedWaypoint = Double.isFinite(previousDetourDistance)
            && distance > previousDetourDistance + 0.35;
        previousDetourDistance = Math.min(previousDetourDistance, distance);
        if (distance > Math.max(1.25, calibratedSpeedPerTick * 3.0) && !passedWaypoint) {
            return false;
        }

        MotionMath.Vec2 nextTarget;
        if (detourStage == 0) {
            detourStage = 1;
            nextTarget = detourRejoinTarget;
        } else {
            detouring = false;
            detourStage = 0;
            detourRejoinTarget = null;
            nextTarget = resumeTarget;
            resumeTarget = null;
        }
        previousDetourDistance = Double.POSITIVE_INFINITY;
        if (nextTarget == null) {
            return false;
        }
        routeTarget = nextTarget;
        legStart = new MotionMath.Vec2(player.getX(), player.getZ());
        previousDetourDistance = distanceTo(player, routeTarget);
        resetBpsWindow();
        MotionMath.Vec2 targetHeading = directionTo(player, routeTarget);
        if (!startTurn(minecraft, targetHeading,
            Math.min(MAX_BOUNDARY_TURN_TICKS, config.turnDurationTicks), false, 0)) {
            beginObstacleRecovery(minecraft, "the obstacle bypass could not rejoin the lane");
        }
        return true;
    }

    private MotionMath.Vec2 rejoinPoint(LocalPlayer player, double forwardDistance) {
        MotionMath.Vec2 position = new MotionMath.Vec2(player.getX(), player.getZ());
        MotionMath.RouteProgress progress = MotionMath.routeProgress(position, legStart, routeTarget, 0.0);
        double length = Math.max(0.01, routeTarget.subtract(legStart).length());
        double rejoinProgress = Math.clamp(progress.progress() + forwardDistance / length, 0.0, 1.0);
        return legStart.add(routeTarget.subtract(legStart).scale(rejoinProgress));
    }

    private boolean segmentClear(Minecraft minecraft, MotionMath.Vec2 start, MotionMath.Vec2 end) {
        return segmentClear(minecraft, start, end, false);
    }

    private boolean segmentClear(Minecraft minecraft, MotionMath.Vec2 start, MotionMath.Vec2 end,
                                 boolean leavingEdge) {
        return segmentClear(minecraft, start, end, leavingEdge, config.obstacleClearance);
    }

    private boolean glowSegmentClear(Minecraft minecraft, MotionMath.Vec2 start, MotionMath.Vec2 end) {
        return segmentClear(minecraft, start, end, false, Math.min(0.12, config.obstacleClearance));
    }

    private boolean segmentClear(Minecraft minecraft, MotionMath.Vec2 start, MotionMath.Vec2 end,
                                 boolean leavingEdge, double clearance) {
        // Glow passes and edge joins retain the normal exclusion padding.
        if (!exclusionSegmentClear(minecraft, start, end)) return false;
        MotionMath.Vec2 segment = end.subtract(start);
        double distance = segment.length();
        if (distance < 0.01) {
            return true;
        }
        MotionMath.Vec2 direction = segment.scale(1.0 / distance);
        AABB body = minecraft.player.getBoundingBox().inflate(clearance, 0.0, clearance);
        for (double step = 0.5; step < distance + 0.5; step += 0.5) {
            double sampledDistance = Math.min(step, distance);
            MotionMath.Vec2 point = start.add(direction.scale(sampledDistance));
            double mapClearance = minecraft.player.getBbWidth() * 0.5 + clearance
                * (leavingEdge
                    ? Math.clamp(sampledDistance / Math.max(1.0, distance - 2.5), 0.0, 1.0)
                    : 1.0);
            if (importedMapName != null
                && !importedFieldContains(point.x(), point.z(), mapClearance)) {
                return false;
            }
            AABB probe = body.move(point.x() - minecraft.player.getX(), 0.0, point.z() - minecraft.player.getZ());
            if (findBlockingBlock(minecraft, probe) != null || intersectsRememberedObstacle(probe)) {
                return false;
            }
        }
        return true;
    }

    private MotionMath.Vec2 movementHeading(LocalPlayer player) {
        double speed = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
        if (speed < 0.03) {
            return heading;
        }
        return new MotionMath.Vec2(
            player.getDeltaMovement().x / speed,
            player.getDeltaMovement().z / speed);
    }

    private ObstacleHit scanCollision(Minecraft minecraft, MotionMath.Vec2 direction, double maximumDistance) {
        AABB body = minecraft.player.getBoundingBox();
        double clearance = config.obstacleClearance;
        AABB probe = new AABB(
            body.minX - clearance, body.minY + 0.05, body.minZ - clearance,
            body.maxX + clearance, body.maxY - 0.05, body.maxZ + clearance);
        for (double distance = 0.5; distance <= maximumDistance; distance += 0.5) {
            AABB moved = probe.move(direction.x() * distance, 0.0, direction.z() * distance);
            BlockingBlock blocking = findBlockingBlock(minecraft, moved);
            if (blocking != null) {
                return new ObstacleHit(blocking.position, blocking.box, distance);
            }
        }
        return null;
    }

    private ObstacleHit scanRememberedObstacle(Minecraft minecraft, MotionMath.Vec2 direction, double maximumDistance) {
        LocalPlayer player = minecraft.player;
        ObstacleHit closest = null;
        for (RememberedObstacle obstacle : obstacleMemory) {
            BlockState state = minecraft.level.getBlockState(obstacle.key);
            var shape = state.getCollisionShape(minecraft.level, obstacle.key);
            AABB box = obstacle.box;
            if (!shape.isEmpty()) {
                box = shape.bounds().move(obstacle.key);
                obstacle.box = box;
            }
            Vec3 center = box.getCenter();
            double dx = center.x - player.getX();
            double dz = center.z - player.getZ();
            double forward = dx * direction.x() + dz * direction.z();
            double sideways = Math.abs(dx * direction.z() - dz * direction.x());
            double corridor = Math.max(box.getXsize(), box.getZsize()) * 0.5
                + player.getBbWidth() * 0.5 + config.obstacleClearance;
            if (forward >= 0.5 && forward <= maximumDistance && sideways <= corridor
                && (closest == null || forward < closest.distance)) {
                closest = new ObstacleHit(obstacle.key, box, forward);
            }
        }
        return closest;
    }

    private boolean intersectsRememberedObstacle(AABB probe) {
        for (RememberedObstacle obstacle : obstacleMemory) {
            if (obstacle.box.intersects(probe)) {
                return true;
            }
        }
        return false;
    }

    private BlockingBlock findBlockingBlock(Minecraft minecraft, AABB probe) {
        return findBlockingBlock(minecraft, probe, true);
    }

    private BlockingBlock findBlockingBlock(Minecraft minecraft, AABB probe, boolean exclusions) {
        if (exclusions && config.exclusions != null) {
            double x = (probe.minX + probe.maxX) / 2, z = (probe.minZ + probe.maxZ) / 2;
            double padding = Math.max(Math.max(probe.getXsize(), probe.getZsize()) / 2,
                minecraft.player.getBbWidth() / 2.0 + config.obstacleClearance);
            for (var zone : config.exclusions) {
                if (zone != null && zone.intersects(FieldProfileStore.worldKey(minecraft), id(), x, z, padding)) {
                    return new BlockingBlock(BlockPos.containing(x, probe.minY, z),
                        new AABB(zone.minX(), probe.minY, zone.minZ(), zone.maxX() + 1.0, probe.maxY, zone.maxZ() + 1.0));
                }
            }
        }
        int minX = Mth.floor(probe.minX + 1.0E-6);
        int maxX = Mth.floor(probe.maxX - 1.0E-6);
        int minY = Mth.floor(probe.minY + 1.0E-6);
        int maxY = Mth.floor(probe.maxY - 1.0E-6);
        int minZ = Mth.floor(probe.minZ + 1.0E-6);
        int maxZ = Mth.floor(probe.maxZ - 1.0E-6);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (!minecraft.level.hasChunkAt(position)) return new BlockingBlock(position, new AABB(position));
                    BlockState state = minecraft.level.getBlockState(position);
                    if (isCrop(state)) {
                        continue;
                    }
                    var shape = state.getCollisionShape(minecraft.level, position);
                    if (!shape.isEmpty()) {
                        AABB box = shape.bounds().move(x, y, z);
                        if (box.intersects(probe)) {
                            return new BlockingBlock(position, box);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static double distanceTo(LocalPlayer player, MotionMath.Vec2 point) {
        return Math.hypot(point.x() - player.getX(), point.z() - player.getZ());
    }

    private RememberedObstacle findRememberedObstacle(BlockPos key) {
        for (RememberedObstacle obstacle : obstacleMemory) {
            if (obstacle.key.equals(key)) {
                return obstacle;
            }
        }
        return null;
    }

    private void rememberObstacle(ObstacleHit hit, int side) {
        // User rectangles are an overlay, never learned into the saved obstacle map.
        var level = Minecraft.getInstance().level;
        if (level == null || !level.hasChunkAt(hit.key) || excluded(hit.box.getCenter().x, hit.box.getCenter().z, 0)) return;
        RememberedObstacle remembered = findRememberedObstacle(hit.key);
        if (remembered == null) {
            if (obstacleMemory.size() >= 256) {
                obstacleMemory.removeFirst();
            }
            obstacleMemory.addLast(new RememberedObstacle(hit.key, hit.box, side));
        } else {
            remembered.box = hit.box;
            remembered.side = side;
            remembered.hits++;
        }
        profiles.rememberObstacle(activeWorldKey, boundsKey(), hit.key, side);
    }

    private void initializeRoute(Minecraft minecraft) {
        activeTrace = null;
        LocalPlayer player = minecraft.player;
        glowIntercept = null;
        specialRoute = SpecialRoute.NONE;
        specialRouteWaypoints.clear();
        specialRouteTemplate = null;
        pendingTurnRoute = null;
        pendingTurnRouteType = null;
        specialRouteEndTick = 0;
        resumeTarget = null;
        detourRejoinTarget = null;
        detouring = false;
        detourStage = 0;
        previousDetourDistance = Double.POSITIVE_INFINITY;
        detectedObstacleBox = null;
        detectedObstacleTick = -1_000_000;
        detourCount = 0;
        double yaw = Math.toRadians(player.getYRot());
        MotionMath.Vec2 actual = new MotionMath.Vec2(-Math.sin(yaw), Math.cos(yaw));
        heading = actual.normalized();
        boolean similarSides = Math.max(bounds.spanX(), bounds.spanZ())
            / Math.max(1.0, Math.min(bounds.spanX(), bounds.spanZ())) < 1.25;
        longAxisX = config.routeStyle == CropPilotConfig.RouteStyle.SQUARE && similarSides
            ? Math.abs(actual.x()) >= Math.abs(actual.z())
            : bounds.spanX() >= bounds.spanZ();
        double along = longAxisX ? actual.x() : actual.z();
        if (Math.abs(along) < 0.20) {
            along = longCoordinate(player) <= longCenter() ? 1.0 : -1.0;
        }
        routeDirection = along >= 0.0 ? 1 : -1;
        laneSweepDirection = crossCoordinate(player) <= (crossMinimum() + crossMaximum()) * 0.5 ? 1 : -1;
        calibratedSpeedPerTick = Math.max(0.40, movementStep(player));
        updateTurnCalibration();
        completedPasses = 0;
        routeCrossTrack = 0.0;
        motion.routeOffset = 0.0;
        motion.targetRouteOffset = 0.0;
        if (fieldScanComplete && beginPerimeterRoute(minecraft, false)) {
            return;
        }
        legStart = new MotionMath.Vec2(player.getX(), player.getZ());
        routeTarget = laneEndpoint(routeDirection, nextLaneCross(crossCoordinate(player)));
        heading = directionTo(player, routeTarget);
    }

    private void calibrateMovement(LocalPlayer player) {
        double measured = Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z);
        if (measured >= 0.03 && measured <= 2.50) {
            double response = measured > calibratedSpeedPerTick ? 0.65 : 0.03;
            calibratedSpeedPerTick += (measured - calibratedSpeedPerTick) * response;
            calibratedSpeedPerTick = Math.clamp(calibratedSpeedPerTick, 0.05, 2.50);
            updateTurnCalibration();
        }
    }

    private void updateTurnCalibration() {
        calibratedTurnRadius = MotionMath.calibrateTurn(
            calibratedSpeedPerTick * 1.15, boundaryTurnDuration(), TURN_STRAFE_WEIGHT).forwardExcursion();
    }

    private int boundaryTurnDuration() {
        return Math.clamp(config.turnDurationTicks, MIN_TURN_TICKS, MAX_BOUNDARY_TURN_TICKS);
    }

    private double turnStartDistance() {
        return calibratedTurnRadius + TURN_SAFETY_MARGIN + calibratedSpeedPerTick * 4.0;
    }

    private void tightenRouteEndpoint() {
        if (specialRoute != SpecialRoute.NONE || detouring || routeTarget == null) {
            return;
        }
        MotionMath.Vec2 safe = laneEndpoint(routeDirection, crossCoordinate(routeTarget));
        if ((routeDirection > 0 && longCoordinate(safe) < longCoordinate(routeTarget))
            || (routeDirection < 0 && longCoordinate(safe) > longCoordinate(routeTarget))) {
            routeTarget = safe;
        }
    }

    private MotionMath.Vec2 laneEndpoint(int direction, double cross) {
        double maximumInset = Math.max(0.5, (longMaximum() - longMinimum()) * 0.45);
        double inset = Math.min(turnStartDistance(), maximumInset);
        double along = direction > 0 ? longMaximum() - inset : longMinimum() + inset;
        return longAxisX
            ? new MotionMath.Vec2(along, clampCross(cross))
            : new MotionMath.Vec2(clampCross(cross), along);
    }

    private double nextLaneCross(double fromCross) {
        double usableLong = Math.max(10.0, longMaximum() - longMinimum() - turnStartDistance() * 2.0);
        double angleDegrees = config.naturalMovement ? 3.5 + random.nextDouble() * 3.5 : 5.0;
        double maximumShift = Math.max(2.5, (crossMaximum() - crossMinimum()) * 0.28);
        double shift = Math.clamp(Math.tan(Math.toRadians(angleDegrees)) * usableLong, 2.5, maximumShift);
        double margin = Math.max(1.5, calibratedSpeedPerTick * 2.0);
        double minimum = crossMinimum() + margin;
        double maximum = crossMaximum() - margin;
        if (minimum >= maximum) {
            return clampCross(fromCross);
        }
        double candidate = fromCross + laneSweepDirection * shift;
        if (candidate < minimum || candidate > maximum) {
            laneSweepDirection = -laneSweepDirection;
            candidate = fromCross + laneSweepDirection * shift;
        }
        return Math.clamp(candidate, minimum, maximum);
    }

    private boolean beginPerimeterRoute(Minecraft minecraft, boolean announce) {
        if (config.customFarmRoutes) {
            if (activeTrace != null && activateTrace(minecraft, activeTrace)) return true;
            if (activeTrace == null && beginTracedPhase(minecraft)) return true;
        }
        activeTrace = null;
        MotionMath.Vec2[] points = perimeterRoutePoints();
        if (points.length < 3) {
            return false;
        }
        if (!exclusionPathClear(minecraft, points)
            || !exclusionSegmentClear(minecraft, points[points.length - 1], points[0])) {
            points = new MotionMath.Vec2[0];
            for (var alternative : importedPerimeterRoutes) {
                if (exclusionPathClear(minecraft, alternative)
                    && exclusionSegmentClear(minecraft, alternative[alternative.length - 1], alternative[0])) {
                    points = alternative.clone();
                    break;
                }
            }
            if (points.length < 3) return false;
        }
        MotionMath.Vec2 position = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
        orientRouteForHeading(position, points);
        MotionMath.Vec2[] ordered = startPerimeterNear(minecraft, position, points);
        if (ordered.length == 0) {
            return false;
        }
        if (!MotionMath.pathInside(ordered, bounds.minCenterX(), bounds.maxCenterX(),
            bounds.minCenterZ(), bounds.maxCenterZ())) {
            return false;
        }
        specialRoute = SpecialRoute.PERIMETER;
        specialRouteTemplate = null;
        specialRouteEndTick = 0;
        specialRouteWaypoints.clear();
        for (MotionMath.Vec2 point : ordered) {
            specialRouteWaypoints.addLast(point);
        }
        legStart = position;
        routeTarget = specialRouteWaypoints.removeFirst();
        if (mode != Mode.RUNNING) {
            heading = directionTo(minecraft.player, routeTarget);
        }
        motion.targetRouteOffset = 0.0;
        if (announce) {
            message(minecraft, "Tracing " + (importedMapName == null ? "the scanned crop perimeter" : importedMapName));
        }
        return true;
    }

    private void orientRouteForHeading(MotionMath.Vec2 position, MotionMath.Vec2[] points) {
        int nearest = 0;
        for (int index = 1; index < points.length; index++) {
            if (points[index].subtract(position).length() < points[nearest].subtract(position).length()) {
                nearest = index;
            }
        }
        MotionMath.Vec2 forward = points[(nearest + 1) % points.length].subtract(points[nearest]).normalized();
        MotionMath.Vec2 reverse = points[(nearest + points.length - 1) % points.length]
            .subtract(points[nearest]).normalized();
        if (heading.dot(reverse) > heading.dot(forward)) {
            for (int left = 0, right = points.length - 1; left < right; left++, right--) {
                MotionMath.Vec2 swap = points[left];
                points[left] = points[right];
                points[right] = swap;
            }
        }
    }

    private MotionMath.Vec2[] startPerimeterNear(Minecraft minecraft, MotionMath.Vec2 position,
                                                  MotionMath.Vec2[] points) {
        if (importedMapName == null) {
            var ordered = MotionMath.startClosedPathNear(position, points);
            return exclusionSegmentClear(minecraft, position, ordered[0]) && exclusionPathClear(minecraft, ordered)
                ? ordered : new MotionMath.Vec2[0];
        }
        int nearest = -1;
        double nearestDistance = Double.POSITIVE_INFINITY;
        double nearestScore = Double.POSITIVE_INFINITY;
        for (int index = 0; index < points.length; index++) {
            MotionMath.Vec2 toPoint = points[index].subtract(position);
            double distance = toPoint.length();
            double behindPenalty = distance == 0.0 ? 0.0
                : Math.max(0.0, -heading.dot(toPoint.scale(1.0 / distance))) * 24.0;
            double score = distance + behindPenalty;
            if (score < nearestScore && segmentClear(minecraft, position, points[index])) {
                nearest = index;
                nearestDistance = distance;
                nearestScore = score;
            }
        }
        if (nearest < 0) {
            return pathToPerimeter(minecraft, position, points);
        }
        List<MotionMath.Vec2> join = new ArrayList<>(1);
        if (nearestDistance >= 1.0) {
            join.add(points[nearest]);
        }
        return joinedPerimeter(join, points, nearest);
    }

    private MotionMath.Vec2[] pathToPerimeter(Minecraft minecraft, MotionMath.Vec2 position,
                                               MotionMath.Vec2[] points) {
        int startX = Mth.floor(position.x());
        int startZ = Mth.floor(position.z());
        if (startX < bounds.minX || startX > bounds.maxX || startZ < bounds.minZ || startZ > bounds.maxZ) {
            return new MotionMath.Vec2[0];
        }

        int width = bounds.width();
        boolean[] traversable = new boolean[fieldCells.length];
        AABB body = minecraft.player.getBoundingBox().inflate(
            config.obstacleClearance, 0.0, config.obstacleClearance);
        for (int index = 0; index < fieldCells.length; index++) {
            if (fieldCells[index] != FIELD_CROP) {
                continue;
            }
            double x = bounds.minX + index % width + 0.5;
            double z = bounds.minZ + index / width + 0.5;
            AABB probe = body.move(x - position.x(), 0.0, z - position.z());
            traversable[index] = importedFieldContains(x, z,
                minecraft.player.getBbWidth() * 0.5 + config.obstacleClearance)
                && findBlockingBlock(minecraft, probe) == null
                && !intersectsRememberedObstacle(probe);
        }

        int start = (startZ - bounds.minZ) * width + startX - bounds.minX;
        MotionMath.Vec2 searchOrigin = position;
        List<MotionMath.Vec2> join = new ArrayList<>();
        if (!traversable[start]) {
            int safeStart = -1;
            double safeStartDistance = Double.POSITIVE_INFINITY;
            for (int index = 0; index < traversable.length; index++) {
                if (!traversable[index]) {
                    continue;
                }
                MotionMath.Vec2 candidate = new MotionMath.Vec2(
                    bounds.minX + index % width + 0.5,
                    bounds.minZ + index / width + 0.5);
                double distance = candidate.subtract(position).length();
                if (distance >= SAFE_JOIN_RUNWAY && distance <= 18.0 && distance < safeStartDistance
                    && segmentClear(minecraft, position, candidate, true)) {
                    safeStart = index;
                    safeStartDistance = distance;
                    searchOrigin = candidate;
                }
            }
            if (safeStart < 0) {
                return new MotionMath.Vec2[0];
            }
            start = safeStart;
            join.add(searchOrigin);
        }
        boolean[] goals = new boolean[fieldCells.length];
        int[] routeAtCell = new int[fieldCells.length];
        Arrays.fill(routeAtCell, -1);
        for (int index = 0; index < points.length; index++) {
            int x = Mth.floor(points[index].x());
            int z = Mth.floor(points[index].z());
            if (x < bounds.minX || x > bounds.maxX || z < bounds.minZ || z > bounds.maxZ) {
                continue;
            }
            int cell = (z - bounds.minZ) * width + x - bounds.minX;
            if (traversable[cell]) {
                goals[cell] = true;
                routeAtCell[cell] = index;
            }
        }

        int[] path = MotionMath.shortestGridPath(traversable, width, start, goals);
        if (path.length == 0) {
            return new MotionMath.Vec2[0];
        }
        int routeIndex = routeAtCell[path[path.length - 1]];
        List<MotionMath.Vec2> candidates = new ArrayList<>(path.length);
        for (int index = 1; index < path.length - 1; index++) {
            int cell = path[index];
            candidates.add(new MotionMath.Vec2(
                bounds.minX + cell % width + 0.5,
                bounds.minZ + cell / width + 0.5));
        }
        candidates.add(points[routeIndex]);

        MotionMath.Vec2 cursor = searchOrigin;
        for (int next = 0; next < candidates.size();) {
            int farthest = -1;
            for (int candidate = candidates.size() - 1; candidate >= next; candidate--) {
                if (segmentClear(minecraft, cursor, candidates.get(candidate))) {
                    farthest = candidate;
                    break;
                }
            }
            if (farthest < 0) {
                return new MotionMath.Vec2[0];
            }
            cursor = candidates.get(farthest);
            join.add(cursor);
            next = farthest + 1;
        }
        MotionMath.Vec2 beforeGoal = join.size() > 1 ? join.get(join.size() - 2) : position;
        if (beforeGoal.subtract(join.getLast()).length() < 1.0) {
            join.removeLast();
        }
        return joinedPerimeter(join, points, routeIndex);
    }

    private static MotionMath.Vec2[] joinedPerimeter(List<MotionMath.Vec2> join,
                                                       MotionMath.Vec2[] points, int routeIndex) {
        MotionMath.Vec2[] ordered = new MotionMath.Vec2[join.size() + points.length];
        int target = 0;
        for (MotionMath.Vec2 waypoint : join) {
            ordered[target++] = waypoint;
        }
        for (int index = 1; index <= points.length; index++) {
            ordered[target++] = points[(routeIndex + index) % points.length];
        }
        return ordered;
    }

    private MotionMath.Vec2[] perimeterRoutePoints() {
        return perimeterRoutePoints(true);
    }

    private MotionMath.Vec2[] perimeterRoutePoints(boolean vary) {
        if (!fieldScanComplete || fieldCells == null || scanCropCells < 3) {
            return new MotionMath.Vec2[0];
        }
        if (!importedPerimeterRoutes.isEmpty()) {
            // The generated file stores its verified center shortcut last. Outer outlines remain
            // available to the timed random-route feature, but recovery must return here reliably.
            return importedPerimeterRoutes.getLast().clone();
        }
        MotionMath.Vec2[] crops = new MotionMath.Vec2[scanCropCells];
        int cropIndex = 0;
        for (int index = 0; index < fieldCells.length; index++) {
            if (fieldCells[index] != FIELD_CROP) {
                continue;
            }
            int x = bounds.minX + index % bounds.width();
            int z = bounds.minZ + index / bounds.width();
            crops[cropIndex++] = new MotionMath.Vec2(x + 0.5, z + 0.5);
        }
        MotionMath.Vec2[] hull = MotionMath.convexHull(crops);
        if (hull.length < 3) {
            return new MotionMath.Vec2[0];
        }

        double centerX = 0.0;
        double centerZ = 0.0;
        for (MotionMath.Vec2 point : hull) {
            centerX += point.x();
            centerZ += point.z();
        }
        MotionMath.Vec2 center = new MotionMath.Vec2(centerX / hull.length, centerZ / hull.length);
        double maximumInset = Math.max(0.0, Math.min(bounds.spanX(), bounds.spanZ()) * 0.42);
        double inset = Math.min(turnStartDistance() + (vary ? random.nextDouble() * 3.0 : 1.5), maximumInset);
        double marginX = Math.min(inset, Math.max(0.0, bounds.spanX() * 0.5 - 2.0));
        double marginZ = Math.min(inset, Math.max(0.0, bounds.spanZ() * 0.5 - 2.0));
        double minimumX = bounds.minCenterX() + marginX;
        double maximumX = bounds.maxCenterX() - marginX;
        double minimumZ = bounds.minCenterZ() + marginZ;
        double maximumZ = bounds.maxCenterZ() - marginZ;
        if (maximumX - minimumX < 3.0 || maximumZ - minimumZ < 3.0) {
            return new MotionMath.Vec2[0];
        }

        List<MotionMath.Vec2> route = new ArrayList<>();
        for (MotionMath.Vec2 point : hull) {
            MotionMath.Vec2 inward = center.subtract(point).normalized();
            double variedInset = Math.max(0.5, inset + (vary ? random.nextDouble() * 1.5 - 0.75 : 0));
            MotionMath.Vec2 moved = point.add(inward.scale(variedInset));
            MotionMath.Vec2 safe = new MotionMath.Vec2(
                Math.clamp(moved.x(), minimumX, maximumX),
                Math.clamp(moved.z(), minimumZ, maximumZ));
            if (route.isEmpty() || route.getLast().subtract(safe).length() >= 1.5) {
                route.add(safe);
            }
        }
        if (route.size() < 3) {
            return new MotionMath.Vec2[0];
        }
        if (vary && random.nextBoolean()) {
            Collections.reverse(route);
        }
        return route.toArray(MotionMath.Vec2[]::new);
    }

    private boolean beginSpecialRoute(Minecraft minecraft) {
        if (config.customFarmRoutes) return beginTracedPhase(minecraft);
        activeTrace = null;
        List<SpecialRoute> attempts = new ArrayList<>(List.of(
            SpecialRoute.BACK_AND_FORTH, SpecialRoute.RANDOM_WEAVE, SpecialRoute.CROSS_CUT,
            SpecialRoute.REVERSAL, SpecialRoute.LARGE_FIGURE_EIGHT));
        Collections.shuffle(attempts, random);
        for (SpecialRoute attempt : attempts) {
            boolean started = switch (attempt) {
                case BACK_AND_FORTH -> beginBackAndForth(minecraft);
                case RANDOM_WEAVE -> beginRandomWeave(minecraft);
                case CROSS_CUT -> beginCrossCut(minecraft);
                case REVERSAL -> beginRouteReversal(minecraft);
                case LARGE_FIGURE_EIGHT -> beginLargeFigureEight(minecraft);
                default -> false;
            };
            if (started) {
                return true;
            }
        }
        scheduleSpecialRoute();
        return false;
    }

    private boolean activateTrace(Minecraft minecraft, TracedRoute candidate) {
        if (!candidate.matches(FieldProfileStore.worldKey(minecraft), "harvest", routeMapKey())) return false;
        var shape = candidate.compile();
        var path = shape.points().toArray(MotionMath.Vec2[]::new);
        if (!shape.valid() || tracedRouteProblem(minecraft, shape.points()) != null
            || !routeSegmentsClear(minecraft, path)
            || !activateClosedRoute(minecraft, SpecialRoute.PERIMETER, path, false)) return false;
        activeTrace = candidate;
        motion.routeOffset = motion.targetRouteOffset = 0;
        return true;
    }

    private boolean beginTracedPhase(Minecraft minecraft) {
        var choices = TracedRoute.choices(config.tracedRoutes, FieldProfileStore.worldKey(minecraft), "harvest",
            routeMapKey(), activeTrace == null ? null : activeTrace.id(), random);
        if (!choices.isEmpty() && activateTrace(minecraft, choices.getFirst())) {
            scheduleSpecialRoute();
            message(minecraft, "Drawn phase: " + activeTrace.name());
            return true;
        }
        nextSpecialRouteTick = ticks + 100;
        return false;
    }

    private boolean advanceSpecialRoute(Minecraft minecraft, MotionMath.RouteProgress progress) {
        if (specialRoute == SpecialRoute.NONE || detouring) {
            return false;
        }
        MotionMath.Vec2 position = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
        MotionMath.Vec2 next = specialRouteWaypoints.peekFirst();
        boolean finalSegmentReached = next == null
            && progress.remaining() <= Math.max(1.0, calibratedSpeedPerTick * 2.0);
        if (!finalSegmentReached && !MotionMath.shouldAdvanceSegment(
            position, legStart, routeTarget, next)) {
            return false;
        }
        if (specialRouteWaypoints.isEmpty()) {
            SpecialRoute completedRoute = specialRoute;
            if (completedRoute == SpecialRoute.PERIMETER) {
                completedPasses++;
                if (ticks >= nextSpecialRouteTick && beginSpecialRoute(minecraft)) {
                    return true;
                }
                if (beginPerimeterRoute(minecraft, false)) {
                    return true;
                }
                beginObstacleRecovery(minecraft, "the scanned perimeter could not be rebuilt");
                return true;
            }
            if (ticks < specialRouteEndTick) {
                if (completedRoute == SpecialRoute.BACK_AND_FORTH && specialRouteTemplate != null) {
                    setOrderedRoute(position, SpecialRoute.BACK_AND_FORTH, specialRouteTemplate.clone());
                    return true;
                }
                if (completedRoute == SpecialRoute.RANDOM_WEAVE && activateRandomWeave(minecraft)) {
                    return true;
                }
                if (completedRoute == SpecialRoute.LARGE_FIGURE_EIGHT && specialRouteTemplate != null
                    && activateClosedRoute(minecraft, SpecialRoute.LARGE_FIGURE_EIGHT,
                        specialRouteTemplate, false)) {
                    return true;
                }
            }
            specialRoute = SpecialRoute.NONE;
            specialRouteTemplate = null;
            specialRouteEndTick = 0;
            if (!beginPerimeterRoute(minecraft, false)) {
                beginObstacleRecovery(minecraft, "the random route could not rejoin the safe perimeter");
                return true;
            }
            scheduleSpecialRoute();
            message(minecraft, completedRoute.label + " complete — resuming the scanned perimeter");
            return true;
        }

        legStart = routeTarget;
        routeTarget = specialRouteWaypoints.removeFirst();
        return true;
    }

    private boolean beginCrossCut(Minecraft minecraft) {
        MotionMath.Vec2[] points = perimeterRoutePoints();
        if (points.length < 12) {
            return false;
        }
        MotionMath.Vec2 position = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
        orientRouteForHeading(position, points);
        int nearest = 0;
        for (int index = 1; index < points.length; index++) {
            if (points[index].subtract(position).length() < points[nearest].subtract(position).length()) {
                nearest = index;
            }
        }
        List<Integer> candidates = new ArrayList<>();
        for (int offset = Math.max(4, points.length / 4); offset <= points.length * 3 / 4; offset++) {
            candidates.add((nearest + offset) % points.length);
        }
        Collections.shuffle(candidates, random);
        MotionMath.Vec2 center = new MotionMath.Vec2(bounds.centerX(), bounds.centerZ());
        double centerLimit = Math.max(bounds.spanX(), bounds.spanZ()) * 0.36;
        for (int index : candidates) {
            MotionMath.Vec2 target = points[index];
            MotionMath.Vec2 direct = target.subtract(position);
            double distance = direct.length();
            if (distance < 28.0 || distance > 120.0
                || heading.dot(direct.normalized()) < Math.cos(Math.toRadians(68.0))
                || position.add(target).scale(0.5).subtract(center).length() > centerLimit
                || !segmentClear(minecraft, position, target)) {
                continue;
            }
            MotionMath.Vec2 outgoing = points[(index + 1) % points.length].subtract(target).normalized();
            if (Math.abs(MotionMath.signedAngle(direct.normalized(), outgoing)) > Math.toRadians(82.0)) {
                continue;
            }
            MotionMath.Vec2[] ordered = joinedPerimeter(List.of(target), points, index);
            if (!exclusionPathClear(minecraft, ordered)) continue;
            setOrderedRoute(position, SpecialRoute.CROSS_CUT, ordered);
            message(minecraft, "Taking a safe random cut across the farm");
            return true;
        }
        return false;
    }

    private boolean beginBackAndForth(Minecraft minecraft) {
        MotionMath.Vec2[] points = perimeterRoutePoints();
        if (points.length < 8) {
            return false;
        }
        MotionMath.Vec2 position = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
        List<Integer> starts = new ArrayList<>();
        int step = Math.max(1, points.length / 24);
        for (int index = 0; index < points.length; index += step) {
            starts.add(index);
        }
        Collections.shuffle(starts, random);
        for (int firstIndex : starts) {
            for (int offset = points.length / 3; offset <= points.length * 2 / 3; offset += step) {
                MotionMath.Vec2 first = points[firstIndex];
                MotionMath.Vec2 second = points[(firstIndex + offset) % points.length];
                double span = first.subtract(second).length();
                if (span < 40.0 || span > 140.0 || !segmentClear(minecraft, first, second)) {
                    continue;
                }
                if (position.subtract(second).length() < position.subtract(first).length()) {
                    MotionMath.Vec2 swap = first;
                    first = second;
                    second = swap;
                }
                if (!segmentClear(minecraft, position, first)) {
                    continue;
                }
                specialRouteTemplate = new MotionMath.Vec2[]{first, second};
                specialRouteEndTick = randomPatternEndTick();
                setOrderedRoute(position, SpecialRoute.BACK_AND_FORTH, specialRouteTemplate.clone());
                message(minecraft, "Switching to a preset back-and-forth route");
                return true;
            }
        }
        return false;
    }

    private boolean beginRandomWeave(Minecraft minecraft) {
        if (!activateRandomWeave(minecraft)) {
            return false;
        }
        specialRouteTemplate = null;
        specialRouteEndTick = randomPatternEndTick();
        message(minecraft, "Switching to a randomized preset route");
        return true;
    }

    private boolean activateRandomWeave(Minecraft minecraft) {
        if (importedPerimeterRoutes.isEmpty()) {
            return false;
        }
        List<MotionMath.Vec2[]> routes = new ArrayList<>(importedPerimeterRoutes);
        Collections.shuffle(routes, random);
        if (movementPreset().coverageWeight() > 0) {
            routes.sort(java.util.Comparator.comparingDouble(route -> coverage.recentFraction(List.of(route), ticks)));
        }
        for (MotionMath.Vec2[] route : routes) {
            if (activateClosedRoute(minecraft, SpecialRoute.RANDOM_WEAVE, route, false)) {
                return true;
            }
        }
        return false;
    }

    private boolean beginRouteReversal(Minecraft minecraft) {
        MotionMath.Vec2[] points = perimeterRoutePoints();
        if (points.length < 3 || !startTurn(minecraft, heading.scale(-1.0),
            Math.min(MAX_BOUNDARY_TURN_TICKS, config.turnDurationTicks), false,
            random.nextBoolean() ? 1 : -1)) {
            return false;
        }
        pendingTurnRoute = points;
        pendingTurnRouteType = SpecialRoute.REVERSAL;
        specialRoute = SpecialRoute.REVERSAL;
        specialRouteWaypoints.clear();
        specialRouteTemplate = null;
        specialRouteEndTick = 0;
        motion.targetRouteOffset = 0.0;
        message(minecraft, "Making a validated full-speed turnaround");
        return true;
    }

    private boolean beginLargeFigureEight(Minecraft minecraft) {
        MotionMath.Vec2[] points = largeFigureEightPoints(minecraft);
        if (points.length == 0
            || !activateClosedRoute(minecraft, SpecialRoute.LARGE_FIGURE_EIGHT, points, false)) {
            return false;
        }
        specialRouteTemplate = points.clone();
        specialRouteEndTick = randomPatternEndTick();
        message(minecraft, "Starting a large figure eight for one to two minutes");
        return true;
    }

    private int randomPatternEndTick() {
        return FlightMotion.nextPatternTick(ticks, random);
    }

    private MotionMath.Vec2[] largeFigureEightPoints(Minecraft minecraft) {
        double minX = bounds.minCenterX();
        double maxX = bounds.maxCenterX();
        double minZ = bounds.minCenterZ();
        double maxZ = bounds.maxCenterZ();
        if (!importedPerimeterRoutes.isEmpty()) {
            MotionMath.Vec2[] outline = importedPerimeterRoutes.get(
                Math.min(2, importedPerimeterRoutes.size() - 1));
            minX = Arrays.stream(outline).mapToDouble(MotionMath.Vec2::x).min().orElse(minX);
            maxX = Arrays.stream(outline).mapToDouble(MotionMath.Vec2::x).max().orElse(maxX);
            minZ = Arrays.stream(outline).mapToDouble(MotionMath.Vec2::z).min().orElse(minZ);
            maxZ = Arrays.stream(outline).mapToDouble(MotionMath.Vec2::z).max().orElse(maxZ);
        }
        double spanX = maxX - minX;
        double spanZ = maxZ - minZ;
        double margin = Math.min(10.0, Math.min(spanX, spanZ) * 0.12);
        double usableX = spanX - margin * 2.0;
        double usableZ = spanZ - margin * 2.0;
        if (usableX < 18.0 || usableZ < 18.0) {
            return new MotionMath.Vec2[0];
        }
        boolean majorAxisX = usableX >= usableZ;
        double[] centerFractions = importedMapName == null
            ? new double[]{0.50, 0.42, 0.58, 0.35, 0.65}
            : new double[]{0.35, 0.40, 0.30, 0.45, 0.50, 0.25, 0.60, 0.75};
        double[] scales = {0.98, 0.90, 0.82, 0.74, 0.66};
        for (double centerFraction : centerFractions) {
            for (double scale : scales) {
                double centerX = majorAxisX ? (minX + maxX) * 0.5 : minX + spanX * centerFraction;
                double centerZ = majorAxisX ? minZ + spanZ * centerFraction : (minZ + maxZ) * 0.5;
                double majorRadius = (majorAxisX ? usableX : usableZ) * 0.46 * scale;
                double minorRadius = (majorAxisX ? usableZ : usableX) * 0.38 * scale;
                MotionMath.Vec2[] candidate = MotionMath.figureEight(
                    new MotionMath.Vec2(centerX, centerZ), majorRadius, minorRadius,
                    majorAxisX, 64, 0.0);
                if (routeSegmentsClear(minecraft, candidate)) {
                    return candidate;
                }
            }
        }
        return new MotionMath.Vec2[0];
    }

    private boolean routeSegmentsClear(Minecraft minecraft, MotionMath.Vec2[] points) {
        if (points.length < 3 || !MotionMath.pathInside(points,
            bounds.minCenterX(), bounds.maxCenterX(), bounds.minCenterZ(), bounds.maxCenterZ())) {
            return false;
        }
        for (int index = 0; index < points.length; index++) {
            if (!segmentClear(minecraft, points[index], points[(index + 1) % points.length])) {
                return false;
            }
        }
        return true;
    }

    private boolean activateClosedRoute(Minecraft minecraft, SpecialRoute routeType,
                                        MotionMath.Vec2[] source, boolean announce) {
        MotionMath.Vec2[] points = source.clone();
        if (points.length < 3 || !exclusionPathClear(minecraft, points)
            || !exclusionSegmentClear(minecraft, points[points.length - 1], points[0])) return false;
        MotionMath.Vec2 position = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
        orientRouteForHeading(position, points);
        MotionMath.Vec2[] ordered = startPerimeterNear(minecraft, position, points);
        if (ordered.length == 0 || !MotionMath.pathInside(ordered,
            bounds.minCenterX(), bounds.maxCenterX(), bounds.minCenterZ(), bounds.maxCenterZ())) {
            return false;
        }
        setOrderedRoute(position, routeType, ordered);
        if (announce) {
            message(minecraft, "Switching to the " + routeType.label + " route");
        }
        return true;
    }

    private void setOrderedRoute(MotionMath.Vec2 position, SpecialRoute routeType,
                                 MotionMath.Vec2[] ordered) {
        specialRoute = routeType;
        specialRouteWaypoints.clear();
        Collections.addAll(specialRouteWaypoints, ordered);
        legStart = position;
        routeTarget = specialRouteWaypoints.removeFirst();
        motion.targetRouteOffset = 0.0;
    }

    private MotionMath.Vec2 axisHeading(int direction) {
        return longAxisX
            ? new MotionMath.Vec2(direction, 0.0)
            : new MotionMath.Vec2(0.0, direction);
    }

    private double clampCross(double cross) {
        return Math.clamp(cross, crossMinimum() + 0.5, crossMaximum() - 0.5);
    }

    private MotionMath.Vec2 directionTo(LocalPlayer player, MotionMath.Vec2 target) {
        MotionMath.Vec2 direction = new MotionMath.Vec2(target.x() - player.getX(), target.z() - player.getZ());
        return direction.length() < 0.01 ? heading : direction.normalized();
    }

    private double longCoordinate(LocalPlayer player) {
        return longAxisX ? player.getX() : player.getZ();
    }

    private double longCoordinate(MotionMath.Vec2 point) {
        return longAxisX ? point.x() : point.z();
    }

    private double crossCoordinate(LocalPlayer player) {
        return longAxisX ? player.getZ() : player.getX();
    }

    private double crossCoordinate(MotionMath.Vec2 point) {
        return longAxisX ? point.z() : point.x();
    }

    private double longMinimum() {
        return longAxisX ? bounds.minCenterX() : bounds.minCenterZ();
    }

    private double longMaximum() {
        return longAxisX ? bounds.maxCenterX() : bounds.maxCenterZ();
    }

    private double longCenter() {
        return (longMinimum() + longMaximum()) * 0.5;
    }

    private double crossMinimum() {
        return longAxisX ? bounds.minCenterZ() : bounds.minCenterX();
    }

    private double crossMaximum() {
        return longAxisX ? bounds.maxCenterZ() : bounds.maxCenterX();
    }

    private void resetRouteMemory() {
        obstacleMemory.clear();
        specialRouteWaypoints.clear();
        specialRoute = SpecialRoute.NONE;
        specialRouteTemplate = null;
        pendingTurnRoute = null;
        pendingTurnRouteType = null;
        specialRouteEndTick = 0;
        legStart = null;
        routeTarget = null;
        resumeTarget = null;
        detourRejoinTarget = null;
        turn = null;
        detouring = false;
        detourStage = 0;
        previousDetourDistance = Double.POSITIVE_INFINITY;
        detectedObstacleBox = null;
        detectedObstacleTick = -1_000_000;
        detourCount = 0;
        completedPasses = 0;
        lastBoundaryTurnSign = 0;
        repeatedBoundaryTurnSigns = 0;
        routeCrossTrack = 0.0;
        motion.routeOffset = 0.0;
        motion.targetRouteOffset = 0.0;
        glowIntercept = null;
        glowRoutingStatus = "Waiting for a loaded glowing plant";
        glowTargetCooldowns.clear();
        glowFailureCounts.clear();
        ignoredGlowTargets.clear();
    }

    private void loadObstacleMemory(Minecraft minecraft) {
        obstacleMemory.clear();
        if (bounds == null || minecraft.level == null) {
            return;
        }
        String world = FieldProfileStore.worldKey(minecraft);
        for (FieldProfileStore.SavedObstacle saved : profiles.obstacles(world, boundsKey())) {
            BlockPos position = new BlockPos(saved.x, saved.y, saved.z);
            BlockState state = minecraft.level.getBlockState(position);
            if (isCrop(state)) {
                continue;
            }
            var shape = state.getCollisionShape(minecraft.level, position);
            if (shape.isEmpty()) {
                continue;
            }
            AABB box = shape.bounds().move(saved.x, saved.y, saved.z);
            obstacleMemory.addLast(new RememberedObstacle(position, box, saved.side));
        }
    }

    private String boundsKey() {
        return bounds.minX + ":" + bounds.maxX + ":" + bounds.minZ + ":" + bounds.maxZ + ":" + bounds.cropY;
    }

    private void scheduleWander() {
        motion.scheduleWander(ticks);
    }

    private void scheduleSpecialRoute() {
        nextSpecialRouteTick = config.customFarmRoutes ? MineLayout.nextChangeTick(ticks, config.customRouteSeconds, random)
            : FlightMotion.nextPatternTick(ticks, random);
    }

    private boolean isInBreakCorridor(LocalPlayer player, BlockPos pos) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        double forward = dx * heading.x() + dz * heading.z();
        double sideways = Math.abs(dx * heading.z() - dz * heading.x());
        return forward >= -1.0 && forward <= 5.0 && sideways <= 1.0;
    }

    public void collectGizmos(Minecraft minecraft) {
        if (!config.showWorldOverlay || bounds == null || minecraft.player == null
            || !bounds.containsExpanded(minecraft.player.getX(), minecraft.player.getZ(), 64)
            || !java.util.Objects.equals(fieldWorldKey, FieldProfileStore.worldKey(minecraft))) {
            return;
        }

        AABB box = new AABB(bounds.minX, bounds.cropY - 0.05, bounds.minZ,
            bounds.maxX + 1.0, bounds.cropY + 2.5, bounds.maxZ + 1.0);
        Gizmos.cuboid(box, GizmoStyle.stroke(0xFF35D9FF, 3.0F)).setAlwaysOnTop();
        collectFieldScanGizmos(minecraft.player);
        collectGlowGizmos(minecraft.player);
        if (mode != Mode.RUNNING) {
            collectImportedRouteGizmos();
            return;
        }

        for (BlockPos pos : breakPreview) {
            Gizmos.cuboid(pos, 0.025F, GizmoStyle.strokeAndFill(0xFFFF5C5C, 2.0F, 0x28FF5C5C)).setAlwaysOnTop();
        }
        for (RememberedObstacle obstacle : obstacleMemory) {
            Gizmos.cuboid(obstacle.box.inflate(0.04), GizmoStyle.strokeAndFill(0xFFB56EFF, 1.5F, 0x20B56EFF)).setAlwaysOnTop();
        }
        if (detectedObstacleBox != null && ticks - detectedObstacleTick < 50) {
            Gizmos.cuboid(detectedObstacleBox.inflate(0.08),
                GizmoStyle.strokeAndFill(0xFFFF7043, 3.0F, 0x45FF7043)).setAlwaysOnTop();
        }
        collectPathGizmos(minecraft.player);
    }

    private void collectGlowGizmos(LocalPlayer player) {
        for (GlowingPlant plant : glowingPlants.values()) {
            int color = ignoredGlowTargets.contains(plant.crop) ? 0xFF777784
                : glowIntercept != null && glowIntercept.crop.equals(plant.crop)
                    ? 0xFFFF5EDB : 0xFF78E6A8;
            Gizmos.cuboid(plant.crop, 0.08F,
                GizmoStyle.strokeAndFill(color, 2.5F, color & 0x30FFFFFF)).setAlwaysOnTop();
        }
        if (glowIntercept != null) {
            Vec3 start = new Vec3(player.getX(), player.getY() + 0.2, player.getZ());
            Vec3 target = new Vec3(glowIntercept.target.x(), player.getY() + 0.2, glowIntercept.target.z());
            Vec3 rejoin = new Vec3(glowIntercept.rejoin.x(), player.getY() + 0.2, glowIntercept.rejoin.z());
            Gizmos.line(start, target, 0xFFFF5EDB, 3.0F).setAlwaysOnTop();
            Gizmos.line(target, rejoin, 0xFF78E6A8, 2.0F).setAlwaysOnTop();
        }
    }

    private void collectImportedRouteGizmos() {
        int[] colors = {0xFF35D9FF, 0xFFFFD166, 0xFFB56EFF, 0xFFFF7043};
        double y = bounds.cropY + 1.15;
        for (int routeIndex = 0; routeIndex < importedPerimeterRoutes.size(); routeIndex++) {
            MotionMath.Vec2[] route = importedPerimeterRoutes.get(routeIndex);
            for (int index = 0; index < route.length; index++) {
                MotionMath.Vec2 start = route[index];
                MotionMath.Vec2 end = route[(index + 1) % route.length];
                Gizmos.line(new Vec3(start.x(), y, start.z()), new Vec3(end.x(), y, end.z()),
                    colors[routeIndex % colors.length], 1.5F).setAlwaysOnTop();
            }
        }
    }

    private void collectFieldScanGizmos(LocalPlayer player) {
        if (fieldCells == null) {
            return;
        }
        int radius = 30;
        int centerX = Mth.floor(player.getX());
        int centerZ = Mth.floor(player.getZ());
        int minimumX = Math.max(bounds.minX, centerX - radius);
        int maximumX = Math.min(bounds.maxX, centerX + radius);
        int minimumZ = Math.max(bounds.minZ, centerZ - radius);
        int maximumZ = Math.min(bounds.maxZ, centerZ + radius);
        long now = System.nanoTime();
        if (overlayFieldCells != fieldCells || now >= nextOverlayRefresh) {
            overlayFieldCells = fieldCells;
            nextOverlayRefresh = now + 250_000_000L;
            var tiles = new ArrayList<BlockPos>();
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    byte state = fieldCell(x, z);
                    if (state == FIELD_CROP && isCropBoundaryCell(x, z) || state == FIELD_OBSTACLE
                        || state == FIELD_UNKNOWN && scanningField) tiles.add(new BlockPos(x, bounds.cropY, z));
                }
            }
            fieldOverlayTiles = tiles.stream().sorted(java.util.Comparator.comparingDouble(pos ->
                Mth.square(pos.getX() + .5 - player.getX()) + Mth.square(pos.getZ() + .5 - player.getZ())))
                .limit(192).toList();
        }
        for (BlockPos position : fieldOverlayTiles) {
                int x = position.getX(), z = position.getZ();
                byte state = fieldCell(x, z);
                if (state == FIELD_CROP) {
                    AABB tile = new AABB(x, bounds.cropY + 0.02, z,
                        x + 1.0, bounds.cropY + 0.08, z + 1.0);
                    Gizmos.cuboid(tile, GizmoStyle.strokeAndFill(
                        0xFF4CD97B, 1.0F, 0x204CD97B)).setAlwaysOnTop();
                } else if (state == FIELD_OBSTACLE) {
                    AABB obstacle = new AABB(x, bounds.cropY, z,
                        x + 1.0, bounds.cropY + 2.5, z + 1.0);
                    Gizmos.cuboid(obstacle, GizmoStyle.strokeAndFill(
                        0xFFFF7043, 1.5F, 0x28FF7043)).setAlwaysOnTop();
                } else if (state == FIELD_UNKNOWN && scanningField) {
                    AABB tile = new AABB(x, bounds.cropY + 0.01, z,
                        x + 1.0, bounds.cropY + 0.04, z + 1.0);
                    Gizmos.cuboid(tile, GizmoStyle.strokeAndFill(
                        0xFF7F8C8D, 0.5F, 0x107F8C8D)).setAlwaysOnTop();
                }
        }
    }

    private boolean isCropBoundaryCell(int x, int z) {
        return fieldCell(x - 1, z) != FIELD_CROP || fieldCell(x + 1, z) != FIELD_CROP
            || fieldCell(x, z - 1) != FIELD_CROP || fieldCell(x, z + 1) != FIELD_CROP;
    }

    private byte fieldCell(int x, int z) {
        if (fieldCells == null || x < bounds.minX || x > bounds.maxX
            || z < bounds.minZ || z > bounds.maxZ) {
            return FIELD_UNKNOWN;
        }
        return fieldCells[(z - bounds.minZ) * bounds.width() + x - bounds.minX];
    }

    private boolean importedFieldContains(double x, double z, double clearance) {
        if (excluded(x, z, Math.max(clearance, 0.3 + config.obstacleClearance))) return false;
        int minimumX = Mth.floor(x - clearance);
        int maximumX = Mth.floor(x + clearance);
        int minimumZ = Mth.floor(z - clearance);
        int maximumZ = Mth.floor(z + clearance);
        for (int blockX = minimumX; blockX <= maximumX; blockX++) {
            for (int blockZ = minimumZ; blockZ <= maximumZ; blockZ++) {
                if (fieldCell(blockX, blockZ) != FIELD_CROP) {
                    return false;
                }
            }
        }
        return true;
    }

    private void collectPathGizmos(LocalPlayer player) {
        Vec3 cursor = new Vec3(player.getX(), player.getY() + 0.15, player.getZ());
        int routeColor = detouring ? 0xFFFF9F43
            : specialRoute != SpecialRoute.NONE ? 0xFFB56EFF : 0xFFFFD166;

        if (turn != null) {
            double yaw = Math.toRadians(player.getYRot());
            MotionMath.Vec2 actualLookHeading = new MotionMath.Vec2(-Math.sin(yaw), Math.cos(yaw));
            MotionMath.Vec2[] forecast = turn.forecast(player.getX(), player.getZ(), actualLookHeading,
                movementHeading(player),
                Math.max(calibratedSpeedPerTick, movementStep(player)) * 1.15, turn.tick);
            for (int index = 2; index < forecast.length; index += 2) {
                MotionMath.Vec2 planned = forecast[index];
                Vec3 next = new Vec3(planned.x(), cursor.y, planned.z());
                Gizmos.line(cursor, next, routeColor, 3.0F).setAlwaysOnTop();
                cursor = next;
            }
        }

        if (routeTarget != null) {
            Vec3 end = new Vec3(routeTarget.x(), cursor.y, routeTarget.z());
            Gizmos.arrow(cursor, end, routeColor, 3.0F).setAlwaysOnTop();
            if (specialRoute != SpecialRoute.NONE && !detouring) {
                Vec3 continuation = end;
                for (MotionMath.Vec2 waypoint : specialRouteWaypoints) {
                    Vec3 next = new Vec3(waypoint.x(), cursor.y, waypoint.z());
                    Gizmos.line(continuation, next, routeColor, 2.0F).setAlwaysOnTop();
                    continuation = next;
                }
            }
            if (detouring && resumeTarget != null) {
                Vec3 continuation = end;
                if (detourStage == 0 && detourRejoinTarget != null) {
                    continuation = new Vec3(detourRejoinTarget.x(), cursor.y, detourRejoinTarget.z());
                    Gizmos.line(end, continuation, 0xFFFF9F43, 2.0F).setAlwaysOnTop();
                }
                Vec3 resume = new Vec3(resumeTarget.x(), cursor.y, resumeTarget.z());
                Gizmos.line(continuation, resume, 0xFF35D9FF, 2.0F).setAlwaysOnTop();
            }
        }
    }

    private void enableChaseCamera(Minecraft minecraft) {
        camera.enable(minecraft, config.thirdPersonCamera);
    }

    FreeLookCamera freeLookCamera() {
        return mode == Mode.RUNNING && config.thirdPersonCamera && camera.enabled() ? camera : null;
    }

    private void restoreCamera(Minecraft minecraft) {
        camera.restore(minecraft);
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

    private void schedulePitchChange() {
        motion.schedulePitchChange(ticks);
    }

    private void resetCounters() {
        coverage.clear();
        blocksMined = 0;
        glowingPlantsHarvested = 0;
        shinyCountCooldowns.clear();
        glowTargetCooldowns.clear();
        glowFailureCounts.clear();
        ignoredGlowTargets.clear();
        recentBreaks.clear();
        sessionStartedNanos = System.nanoTime();
        sessionStoppedNanos = 0L;
        consecutiveLowBpsWindows = 0;
        resetBpsWindow();
    }

    private void resetBpsWindow() {
        bpsWindowStartedNanos = System.nanoTime();
        bpsWindowBlocks = 0;
    }

    private boolean checkLowBps(Minecraft minecraft) {
        if (!config.lowBpsFailsafe || mode != Mode.RUNNING) {
            consecutiveLowBpsWindows = 0;
            resetBpsWindow();
            return false;
        }
        long now = System.nanoTime();
        double elapsedSeconds = (now - bpsWindowStartedNanos) / 1_000_000_000.0;
        if (elapsedSeconds < config.lowBpsWindowSeconds) {
            return false;
        }
        double sampledBps = bpsWindowBlocks / Math.max(1.0, elapsedSeconds);
        resetBpsWindow();
        if (detouring) {
            return false;
        }
        if (sampledBps >= config.minimumBps) {
            consecutiveLowBpsWindows = 0;
            return false;
        }

        consecutiveLowBpsWindows++;
        if (consecutiveLowBpsWindows >= 2) {
            stop(minecraft, String.format(Locale.ROOT,
                "Low-BPS failsafe: %.2f blocks/s", sampledBps));
            return true;
        }
        if (specialRoute != SpecialRoute.NONE) {
            initializeRoute(minecraft);
            scheduleSpecialRoute();
        } else if (routeTarget == null) {
            initializeRoute(minecraft);
        } else {
            legStart = new MotionMath.Vec2(minecraft.player.getX(), minecraft.player.getZ());
            routeTarget = laneEndpoint(routeDirection, clampCross(crossCoordinate(minecraft.player)));
            motion.targetRouteOffset = 0.0;
        }
        message(minecraft, String.format(Locale.ROOT,
            "Low BPS (%.2f) — rebuilding the scanned route", sampledBps));
        return false;
    }

    private void pruneRecentBreaks(long now) {
        long cutoff = now - 1_000_000_000L;
        while (!recentBreaks.isEmpty() && recentBreaks.peekFirst() < cutoff) {
            recentBreaks.removeFirst();
        }
    }

    public double blocksPerSecond() {
        pruneRecentBreaks(System.nanoTime());
        return recentBreaks.size();
    }

    public double blocksPerHour() {
        if (sessionStartedNanos == 0L || blocksMined == 0L) {
            return 0.0;
        }
        long now = sessionStoppedNanos == 0L ? System.nanoTime() : sessionStoppedNanos;
        double elapsedSeconds = Math.max(1.0, (now - sessionStartedNanos) / 1_000_000_000.0);
        return blocksMined * 3600.0 / elapsedSeconds;
    }

    public long totalBlocksMined() { return blocksMined; }
    public long shinyHarvests() { return glowingPlantsHarvested; }
    public double shiniesPerHour() {
        return blocksMined == 0 ? 0.0 : blocksPerHour() * glowingPlantsHarvested / blocksMined;
    }

    private void holdAttack(Minecraft minecraft) {
        if (!isHarvesting() || minecraft.gui.screen() != null) { releaseAttack(minecraft); return; }
        // A just-broken block can still be the render hit at END_CLIENT_TICK.
        // Keep holding through regrowth; mayAttack checks the next actual block.
        minecraft.options.keyAttack.setDown(true);
        modHoldingAttack = true;
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

    private void holdMovement(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(true);
        minecraft.options.keySprint.setDown(true);
        modHoldingForward = true;
        modHoldingSprint = true;
    }

    private void holdTurnStrafe(Minecraft minecraft, int turnSign) {
        boolean left = turnSign < 0;
        boolean right = turnSign > 0;
        minecraft.options.keyLeft.setDown(left);
        minecraft.options.keyRight.setDown(right);
        modHoldingLeft = left;
        modHoldingRight = right;
    }

    private void releaseStrafe(Minecraft minecraft) {
        if (modHoldingLeft) {
            minecraft.options.keyLeft.setDown(false);
            modHoldingLeft = false;
        }
        if (modHoldingRight) {
            minecraft.options.keyRight.setDown(false);
            modHoldingRight = false;
        }
    }

    private void releaseForward(Minecraft minecraft) {
        if (modHoldingForward) {
            minecraft.options.keyUp.setDown(false);
            modHoldingForward = false;
        }
    }

    private void releaseMovement(Minecraft minecraft) {
        releaseForward(minecraft);
        releaseStrafe(minecraft);
        if (modHoldingSprint) {
            minecraft.options.keySprint.setDown(false);
            modHoldingSprint = false;
        }
    }

    void stop(Minecraft minecraft, String reason) {
        stopSilently(minecraft);
        message(minecraft, reason);
    }

    private void stopSilently(Minecraft minecraft) {
        if (isActive() && sessionStoppedNanos == 0L) {
            sessionStoppedNanos = System.nanoTime();
        }
        releaseAttack(minecraft);
        releaseMovement(minecraft);
        releasePreparedVertical(minecraft);
        restoreCamera(minecraft);
        restoreFocusPause(minecraft);
        breakPreview.clear();
        turn = null;
        resumeTarget = null;
        detourRejoinTarget = null;
        detouring = false;
        glowIntercept = null;
        specialRouteTemplate = null;
        pendingTurnRoute = null;
        pendingTurnRouteType = null;
        specialRouteEndTick = 0;
        activeWorldKey = null;
        pattern = Pattern.STRAIGHT;
        resetRecoveryState();
        mode = bounds == null ? Mode.OFF : Mode.READY;
    }

    private static BlockPos normalizeCropLayer(Minecraft minecraft, BlockPos selected) {
        BlockState selectedState = minecraft.level.getBlockState(selected);
        if (isCrop(selectedState)) {
            return selected.immutable();
        }
        BlockPos above = selected.above();
        if (selectedState.getBlock() instanceof FarmlandBlock || isCrop(minecraft.level.getBlockState(above))) {
            return above.immutable();
        }
        return selected.immutable();
    }

    private static boolean isCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock
            || state.getBlock() instanceof NetherWartBlock
            || state.getBlock() instanceof CocoaBlock;
    }

    private static void message(Minecraft minecraft, String text) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("[Cropium] " + text));
        }
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private enum Mode {
        OFF,
        READY,
        RUNNING,
        PAUSED
    }

    private enum Pattern {
        STRAIGHT("route tracking"),
        TURNING("validated turn");

        private final String label;

        Pattern(String label) {
            this.label = label;
        }
    }

    private enum RecoveryPhase {
        NONE("RUNNING"),
        BOUNCING("RECOVERING"),
        WAITING_FOR_FARM("WAITING /FARM"),
        ENTERING_FARM("RETURNING"),
        STARTING_FLIGHT("STARTING FLIGHT"),
        WAITING_FOR_FLIGHT("VERIFYING FLIGHT"),
        SETTLING_FLIGHT("SETTLING FLIGHT");

        private final String label;

        RecoveryPhase(String label) {
            this.label = label;
        }
    }

    private enum SpecialRoute {
        NONE("lanes"),
        PERIMETER("crop perimeter"),
        BACK_AND_FORTH("back-and-forth route"),
        RANDOM_WEAVE("random preset weave"),
        CROSS_CUT("cross-farm cut"),
        REVERSAL("reversed perimeter"),
        LARGE_FIGURE_EIGHT("large figure eight");

        private final String label;

        SpecialRoute(String label) {
            this.label = label;
        }
    }

    private static final class ImportedFieldMap {
        private String name;
        private String cells;
        private int sourceAnchorX;
        private int sourceAnchorY;
        private int sourceAnchorZ;
        private int cropY;
        private int minX;
        private int maxX;
        private int minZ;
        private int maxZ;
        private double[][][] routes;
    }

    private static final class ImportedObstacleMap {
        private int sourceAnchorX;
        private int sourceAnchorZ;
        private int[][] cells;
    }

    private static final class ImportedRouteMap {
        private int sourceAnchorX;
        private int sourceAnchorZ;
        private double[][][] routes;
    }

    private record BlockingBlock(BlockPos position, AABB box) {
    }

    private record ObstacleHit(BlockPos key, AABB box, double distance) {
    }

    private record DetourChoice(MotionMath.Vec2 target, MotionMath.Vec2 rejoin,
                                int side, double distance, boolean clearEnough) {
    }

    private enum GlowStage {
        ACQUIRE,
        DIRECT,
        OBSERVE,
        REJOIN
    }

    private record GlowObservation(int entityId, String entityType, String entityClass, String customName,
                                   double x, double y, double z, BlockPos crop, String blockState) {
    }

    private static final class GlowingPlant {
        private final BlockPos crop;
        private final int firstSeenTick;
        private int entityId;
        private String entityType;
        private int lastSeenTick;

        private GlowingPlant(BlockPos crop, int entityId, String entityType, int tick) {
            this.crop = crop.immutable();
            this.entityId = entityId;
            this.entityType = entityType;
            this.firstSeenTick = tick;
            this.lastSeenTick = tick;
        }
    }

    private static final class GlowIntercept {
        private final BlockPos crop;
        private final MotionMath.Vec2 target;
        private final MotionMath.Vec2 rejoin;
        private MotionMath.Vec2 aimPoint;
        private MotionMath.Vec2 directHeading;
        private GlowStage stage = GlowStage.ACQUIRE;
        private boolean harvested;
        private int attemptDeadlineTick;
        private int observeDeadlineTick;
        private int rejoinDeadlineTick;

        private GlowIntercept(BlockPos crop, MotionMath.Vec2 target, MotionMath.Vec2 rejoin,
                              MotionMath.Vec2 aimPoint) {
            this.crop = crop.immutable();
            this.target = target;
            this.rejoin = rejoin;
            this.aimPoint = aimPoint;
        }
    }

    private static final class RememberedObstacle {
        private final BlockPos key;
        private AABB box;
        private int side;
        private int hits = 1;

        private RememberedObstacle(BlockPos key, AABB box, int side) {
            this.key = key.immutable();
            this.box = box;
            this.side = side;
        }
    }

    private static final class Turn {
        private final MotionMath.Vec2 target;
        private final MotionMath.Vec2 start;
        private final double turnRadians;
        private final int durationTicks;
        private final float pitchLift;
        private final boolean recovery;
        private final boolean boundary;
        private final double strafeWeight;
        private final MotionMath.Vec2[] plannedPoints;
        private int tick;

        private Turn(MotionMath.Vec2 start, MotionMath.Vec2 target, MotionMath.Vec2 actualLook,
                     MotionMath.Vec2 actualMovement, double startX, double startZ,
                     double planningSpeed, int durationTicks,
                     float pitchLift, boolean recovery, boolean boundary, int preferredTurnSign,
                     double strafeWeight) {
            this.start = start.normalized();
            this.target = target;
            this.durationTicks = durationTicks;
            this.pitchLift = pitchLift;
            this.recovery = recovery;
            this.boundary = boundary;
            this.turnRadians = MotionMath.directedTurnAngle(this.start, target, preferredTurnSign);
            this.strafeWeight = Math.abs(turnRadians) >= Math.toRadians(55.0) ? strafeWeight : 0.0;
            this.plannedPoints = forecast(startX, startZ, actualLook, actualMovement, planningSpeed, 0);
        }

        private boolean staysInside(Bounds bounds, double expansion) {
            return MotionMath.pathInside(plannedPoints,
                bounds.minCenterX() - expansion, bounds.maxCenterX() + expansion,
                bounds.minCenterZ() - expansion, bounds.maxCenterZ() + expansion);
        }

        private MotionMath.Vec2 heading() {
            return headingAt(tick);
        }

        private int strafeSign() {
            return strafeWeight == 0.0 ? 0 : turnDirection();
        }

        private int turnDirection() {
            return (int)Math.signum(turnRadians);
        }

        private MotionMath.Vec2 plannedEnd() {
            return plannedPoints[plannedPoints.length - 1];
        }

        private MotionMath.Vec2 headingAt(int atTick) {
            double progress = Math.clamp(atTick / (double)durationTicks, 0.0, 1.0);
            return start.rotate(turnRadians * MotionMath.smootherstep(progress)).normalized();
        }

        private MotionMath.Vec2[] forecast(double startX, double startZ, MotionMath.Vec2 actualLook,
                                           MotionMath.Vec2 actualMovement, double planningSpeed,
                                           int fromTick) {
            return MotionMath.forecastTurn(new MotionMath.Vec2(startX, startZ), start, actualLook,
                actualMovement, turnRadians, planningSpeed, durationTicks, fromTick, strafeWeight);
        }

        private float pitchArc() {
            double progress = Math.clamp(tick / (double)durationTicks, 0.0, 1.0);
            return pitchLift * (float)Math.sin(Math.PI * progress);
        }

        private boolean finished() {
            return tick >= durationTicks;
        }
    }

    private record Bounds(int minX, int maxX, int minZ, int maxZ, int cropY) {
        static Bounds between(BlockPos a, BlockPos b) {
            return new Bounds(
                Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX()),
                Math.min(a.getZ(), b.getZ()), Math.max(a.getZ(), b.getZ()),
                Math.max(a.getY(), b.getY())
            );
        }

        int width() {
            return maxX - minX + 1;
        }

        int depth() {
            return maxZ - minZ + 1;
        }

        double minCenterX() {
            return minX + 0.5;
        }

        double maxCenterX() {
            return maxX + 0.5;
        }

        double minCenterZ() {
            return minZ + 0.5;
        }

        double maxCenterZ() {
            return maxZ + 0.5;
        }

        double spanX() {
            return maxCenterX() - minCenterX();
        }

        double spanZ() {
            return maxCenterZ() - minCenterZ();
        }

        double centerX() {
            return (minCenterX() + maxCenterX()) * 0.5;
        }

        double centerZ() {
            return (minCenterZ() + maxCenterZ()) * 0.5;
        }

        boolean contains(double x, double z) {
            return containsExpanded(x, z, 0.0);
        }

        boolean containsExpanded(double x, double z, double expansion) {
            return x >= minCenterX() - expansion && x <= maxCenterX() + expansion
                && z >= minCenterZ() - expansion && z <= maxCenterZ() + expansion;
        }
    }
}
