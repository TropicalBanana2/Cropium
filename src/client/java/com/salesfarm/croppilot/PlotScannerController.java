package com.salesfarm.croppilot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PlotScannerController {
    private static final int MAX_HORIZONTAL_SIZE = 256;
    private static final int MAX_VERTICAL_SIZE = 16;
    private static final int AUTO_SCAN_INTERVAL_TICKS = 40;

    private final CropPilotConfig config;
    private BlockPos firstCorner;
    private PlotBounds bounds;
    private List<PlotSlot> slots = List.of();
    private String activeWorldKey;
    private String status = "Set two corners around the NPC plot";
    private int ticks;
    private int nextScanTick;
    private int loadedColumns;
    private int totalColumns;
    private int visibleEntities;
    private String entitySummary = "No figure entities detected in the loaded plot";

    public PlotScannerController(CropPilotConfig config) {
        this.config = config;
    }

    public void tick(Minecraft minecraft) {
        ticks++;
        prepareWorld(minecraft);
        if (bounds != null && ticks >= nextScanTick) {
            if (minecraft.player == null || minecraft.player.getX() < bounds.minX - 64
                || minecraft.player.getX() > bounds.maxX + 64 || minecraft.player.getZ() < bounds.minZ - 64
                || minecraft.player.getZ() > bounds.maxZ + 64) {
                nextScanTick = ticks + AUTO_SCAN_INTERVAL_TICKS;
                status = "Away from plot; automatic scan deferred";
                return;
            }
            scan(minecraft, false);
        }
    }

    public boolean openPlot(Minecraft minecraft) {
        if (minecraft.getConnection() == null) {
            status = "Join the server before opening the plot";
            return false;
        }
        minecraft.getConnection().sendCommand("plot");
        status = "Opening /plot; scan refreshes after teleporting";
        nextScanTick = ticks + 30;
        return true;
    }

    public boolean selectNextCorner(Minecraft minecraft) {
        prepareWorld(minecraft);
        if (!(minecraft.hitResult instanceof BlockHitResult hit)
            || hit.getType() != HitResult.Type.BLOCK || minecraft.level == null) {
            status = "Look directly at a plot corner block first";
            message(minecraft, status);
            return false;
        }

        BlockPos selected = hit.getBlockPos().immutable();
        if (firstCorner == null) {
            firstCorner = selected;
            status = "Corner A set at " + shortPos(selected) + "; select Corner B";
            message(minecraft, status);
            return true;
        }

        PlotBounds candidate = PlotBounds.between(firstCorner, selected);
        if (candidate.width() > MAX_HORIZONTAL_SIZE || candidate.depth() > MAX_HORIZONTAL_SIZE
            || candidate.height() > MAX_VERTICAL_SIZE) {
            status = "Plot bounds must fit within 256 × 16 × 256 blocks";
            message(minecraft, status);
            return false;
        }
        bounds = candidate;
        firstCorner = null;
        saveBounds();
        scan(minecraft, false);
        status = "Plot bounds saved; " + summary();
        message(minecraft, status);
        return true;
    }

    public boolean scan(Minecraft minecraft) {
        prepareWorld(minecraft);
        return scan(minecraft, true);
    }

    private boolean scan(Minecraft minecraft, boolean announce) {
        nextScanTick = ticks + AUTO_SCAN_INTERVAL_TICKS;
        if (bounds == null || minecraft.level == null || minecraft.player == null) {
            status = "Set two plot corners before scanning";
            if (announce) {
                message(minecraft, status);
            }
            return false;
        }

        List<PlotScannerLogic.Box> figures = new ArrayList<>();
        Map<String, Integer> entityTypes = new HashMap<>();
        Set<UUID> listedPlayers = minecraft.getConnection() == null ? Set.of()
            : minecraft.getConnection().getListedOnlinePlayers().stream()
                .map(info -> info.getProfile().id())
                .collect(Collectors.toSet());
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            String teamName = entity.getTeam() == null ? null : entity.getTeam().getName();
            if (entity.isRemoved() || !PlotScannerLogic.countsAsFigure(entity instanceof Player,
                entity == minecraft.player, listedPlayers.contains(entity.getUUID()), teamName)) {
                continue;
            }
            var box = entity.getBoundingBox();
            if (box.maxX < bounds.minX || box.minX > bounds.maxX + 1.0
                || box.maxY < bounds.minY + 0.10 || box.minY > bounds.maxY + 5.0
                || box.maxZ < bounds.minZ || box.minZ > bounds.maxZ + 1.0) {
                continue;
            }
            figures.add(new PlotScannerLogic.Box(
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
            String type = simpleEntityType(entity.getType().toString())
                + "/" + entity.getClass().getSimpleName();
            entityTypes.merge(type, 1, Integer::sum);
        }
        visibleEntities = figures.size();
        entitySummary = entityTypes.isEmpty() ? "No figure entities detected in the loaded plot"
            : entityTypes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(entry -> entry.getKey() + " ×" + entry.getValue())
                .reduce((left, right) -> left + " • " + right)
                .orElse("No figure entities detected in the loaded plot");

        List<PlotSlot> found = new ArrayList<>();
        loadedColumns = 0;
        totalColumns = bounds.width() * bounds.depth();
        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                BlockPos column = new BlockPos(x, bounds.minY, z);
                if (!minecraft.level.hasChunkAt(column)) {
                    continue;
                }
                loadedColumns++;
                for (int y = bounds.maxY; y >= bounds.minY; y--) {
                    BlockPos position = new BlockPos(x, y, z);
                    var state = minecraft.level.getBlockState(position);
                    if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DYED_TERRACOTTA.green())) {
                        continue;
                    }
                    boolean occupied = PlotScannerLogic.occupied(
                        x + 0.5, y, z + 0.5, figures);
                    found.add(new PlotSlot(position.immutable(), occupied));
                    break;
                }
            }
        }
        found.sort(Comparator.comparingInt((PlotSlot slot) -> slot.position.getZ())
            .thenComparingInt(slot -> slot.position.getX())
            .thenComparingInt(slot -> slot.position.getY()));
        slots = List.copyOf(found);
        status = summary();
        if (announce) {
            message(minecraft, status);
            message(minecraft, "Entity inspector: " + entitySummary);
        }
        return true;
    }

    public void clear(Minecraft minecraft) {
        firstCorner = null;
        bounds = null;
        slots = List.of();
        loadedColumns = 0;
        totalColumns = 0;
        visibleEntities = 0;
        entitySummary = "No figure entities detected in the loaded plot";
        config.plotBoundsSet = false;
        config.plotWorldKey = null;
        config.save();
        status = "Plot bounds cleared";
        message(minecraft, status);
    }

    public String nextCornerLabel() {
        return firstCorner == null ? "Set A" : "Set B";
    }

    public String boundsLabel() {
        if (firstCorner != null) {
            return "Corner A  " + shortPos(firstCorner) + "  •  waiting for Corner B";
        }
        if (bounds == null) {
            return "No plot preset saved for this world";
        }
        return bounds.width() + " × " + bounds.depth() + "  •  Y "
            + bounds.minY + (bounds.minY == bounds.maxY ? "" : "–" + bounds.maxY);
    }

    public String status() {
        return status;
    }

    public int openSlots() {
        return (int)slots.stream().filter(slot -> !slot.occupied).count();
    }

    public int occupiedSlots() {
        return (int)slots.stream().filter(PlotSlot::occupied).count();
    }

    public int loadedColumns() {
        return loadedColumns;
    }

    public int totalColumns() {
        return totalColumns;
    }

    public int visibleEntities() {
        return visibleEntities;
    }

    public String entitySummary() {
        return entitySummary;
    }

    public PlotBounds bounds() {
        return bounds;
    }

    public List<PlotSlot> slots() {
        return slots;
    }

    public PlotSlot bestPlacementSlot(Minecraft minecraft) {
        return bestPlacementSlot(minecraft, Set.of());
    }

    public PlotSlot bestPlacementSlot(Minecraft minecraft, Set<BlockPos> excluded) {
        prepareWorld(minecraft);
        if (!scan(minecraft, false) || minecraft.player == null) {
            return null;
        }
        List<PlotSlot> available = slots.stream()
            .filter(slot -> !excluded.contains(slot.position))
            .toList();
        List<PlotScannerLogic.GridSlot> candidates = available.stream()
            .map(slot -> new PlotScannerLogic.GridSlot(slot.position.getX() + 0.5,
                slot.position.getZ() + 0.5, slot.occupied))
            .toList();
        int selected = PlotScannerLogic.selectPlacementSlot(candidates,
            minecraft.player.getX(), minecraft.player.getZ());
        return selected < 0 ? null : available.get(selected);
    }

    public boolean refresh(Minecraft minecraft) {
        prepareWorld(minecraft);
        return scan(minecraft, false);
    }

    public boolean isOccupied(BlockPos position) {
        return slots.stream().anyMatch(slot -> slot.position.equals(position) && slot.occupied);
    }

    private void prepareWorld(Minecraft minecraft) {
        String current = FieldProfileStore.worldKey(minecraft);
        if (Objects.equals(current, activeWorldKey)) {
            return;
        }
        activeWorldKey = current;
        firstCorner = null;
        slots = List.of();
        loadedColumns = 0;
        totalColumns = 0;
        visibleEntities = 0;
        entitySummary = "No figure entities detected in the loaded plot";
        bounds = config.plotBoundsSet && Objects.equals(config.plotWorldKey, current)
            ? new PlotBounds(config.plotMinX, config.plotMaxX, config.plotMinY,
                config.plotMaxY, config.plotMinZ, config.plotMaxZ)
            : null;
        status = bounds == null ? "Set two corners around the NPC plot" : "Loaded the saved plot bounds";
        nextScanTick = ticks;
    }

    private void saveBounds() {
        config.plotBoundsSet = true;
        config.plotWorldKey = activeWorldKey;
        config.plotMinX = bounds.minX;
        config.plotMaxX = bounds.maxX;
        config.plotMinY = bounds.minY;
        config.plotMaxY = bounds.maxY;
        config.plotMinZ = bounds.minZ;
        config.plotMaxZ = bounds.maxZ;
        config.save();
    }

    private String summary() {
        if (slots.isEmpty()) {
            return loadedColumns < totalColumns
                ? "No surface blocks found yet; walk through the unloaded plot area"
                : "No grass or green-terracotta surface blocks found inside the bounds";
        }
        String loaded = loadedColumns < totalColumns
            ? " • " + loadedColumns + "/" + totalColumns + " columns loaded"
            : " • full bounds loaded";
        return openSlots() + " open • " + occupiedSlots() + " occupied • "
            + visibleEntities + " visible entities" + loaded;
    }

    private static String simpleEntityType(String type) {
        int separator = Math.max(type.lastIndexOf('.'), type.lastIndexOf(':'));
        return separator < 0 ? type : type.substring(separator + 1);
    }

    private static String shortPos(BlockPos position) {
        return position.getX() + ", " + position.getY() + ", " + position.getZ();
    }

    private static void message(Minecraft minecraft, String text) {
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.literal("[Cropium] " + text));
        }
    }

    public record PlotSlot(BlockPos position, boolean occupied) {
    }

    public record PlotBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        static PlotBounds between(BlockPos first, BlockPos second) {
            return new PlotBounds(Math.min(first.getX(), second.getX()), Math.max(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()), Math.max(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()), Math.max(first.getZ(), second.getZ()));
        }

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }

        int depth() {
            return maxZ - minZ + 1;
        }
    }
}
