package com.salesfarm.croppilot;

import java.util.List;

final class PlotScannerLogic {
    private static final double SLOT_HALF_WIDTH = 0.45;
    private static final double ENTITY_PADDING = 0.10;

    private PlotScannerLogic() {
    }

    static boolean occupied(double slotX, double slotY, double slotZ, Iterable<Box> entities) {
        double minX = slotX - SLOT_HALF_WIDTH;
        double maxX = slotX + SLOT_HALF_WIDTH;
        double minY = slotY + 0.10;
        double maxY = slotY + 5.0;
        double minZ = slotZ - SLOT_HALF_WIDTH;
        double maxZ = slotZ + SLOT_HALF_WIDTH;
        for (Box entity : entities) {
            if (entity.maxX + ENTITY_PADDING >= minX && entity.minX - ENTITY_PADDING <= maxX
                && entity.maxY >= minY && entity.minY <= maxY
                && entity.maxZ + ENTITY_PADDING >= minZ && entity.minZ - ENTITY_PADDING <= maxZ) {
                return true;
            }
        }
        return false;
    }

    static boolean countsAsFigure(boolean playerEntity, boolean localPlayer,
                                  boolean listedOnline, String teamName) {
        if (localPlayer) {
            return false;
        }
        boolean npcTeam = teamName != null && teamName.equalsIgnoreCase("npc");
        return !playerEntity || npcTeam || !listedOnline;
    }

    static int selectPlacementSlot(List<GridSlot> slots, double playerX, double playerZ) {
        boolean foundClearRoute = false;
        int best = -1;
        double bestIsolation = -1.0;
        double bestTravel = Double.POSITIVE_INFINITY;
        for (int index = 0; index < slots.size(); index++) {
            GridSlot candidate = slots.get(index);
            if (candidate.occupied) {
                continue;
            }
            boolean clearRoute = routeIsClear(playerX, playerZ, candidate.x, candidate.z, slots);
            double travel = squaredDistance(playerX, playerZ, candidate.x, candidate.z);
            double isolation = minimumOccupiedDistance(candidate, slots);
            if ((clearRoute && !foundClearRoute)
                || (clearRoute == foundClearRoute && (isolation > bestIsolation + 1.0E-6
                    || (Math.abs(isolation - bestIsolation) <= 1.0E-6 && travel < bestTravel)))) {
                foundClearRoute = clearRoute;
                best = index;
                bestIsolation = isolation;
                bestTravel = travel;
            }
        }
        return best;
    }

    private static boolean routeIsClear(double startX, double startZ, double endX, double endZ,
                                        List<GridSlot> slots) {
        for (GridSlot slot : slots) {
            if (slot.occupied && distanceToSegmentSquared(slot.x, slot.z,
                startX, startZ, endX, endZ) < 0.90 * 0.90) {
                double progress = segmentProgress(slot.x, slot.z, startX, startZ, endX, endZ);
                if (progress > 0.06 && progress < 0.96) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double minimumOccupiedDistance(GridSlot candidate, List<GridSlot> slots) {
        double minimum = Double.POSITIVE_INFINITY;
        for (GridSlot slot : slots) {
            if (slot.occupied) {
                minimum = Math.min(minimum, squaredDistance(candidate.x, candidate.z, slot.x, slot.z));
            }
        }
        return Double.isInfinite(minimum) ? 0.0 : minimum;
    }

    private static double distanceToSegmentSquared(double x, double z, double startX, double startZ,
                                                   double endX, double endZ) {
        double progress = Math.clamp(segmentProgress(x, z, startX, startZ, endX, endZ), 0.0, 1.0);
        double closestX = startX + (endX - startX) * progress;
        double closestZ = startZ + (endZ - startZ) * progress;
        return squaredDistance(x, z, closestX, closestZ);
    }

    private static double segmentProgress(double x, double z, double startX, double startZ,
                                          double endX, double endZ) {
        double dx = endX - startX;
        double dz = endZ - startZ;
        double lengthSquared = dx * dx + dz * dz;
        return lengthSquared < 1.0E-9 ? 0.0 : ((x - startX) * dx + (z - startZ) * dz) / lengthSquared;
    }

    private static double squaredDistance(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    static void selfTest() {
        Box npc = new Box(10.2, 116.0, 20.2, 10.8, 117.8, 20.8);
        assert occupied(10.5, 115.0, 20.5, List.of(npc));
        assert !occupied(11.5, 115.0, 20.5, List.of(npc));
        assert !occupied(13.0, 115.0, 20.5, List.of(npc));
        assert !occupied(10.5, 120.0, 20.5, List.of(npc));
        Box zeroWidthDisplay = new Box(30.5, 116.0, 40.5, 30.5, 116.0, 40.5);
        assert occupied(30.5, 115.0, 40.5, List.of(zeroWidthDisplay));
        assert !countsAsFigure(true, true, true, null);
        assert !countsAsFigure(true, false, true, null);
        assert countsAsFigure(true, false, true, "npc");
        assert countsAsFigure(true, false, false, null);
        assert countsAsFigure(false, false, false, null);

        List<GridSlot> placement = List.of(
            new GridSlot(0.5, 0.5, false),
            new GridSlot(5.5, 0.5, true),
            new GridSlot(10.5, 0.5, false),
            new GridSlot(10.5, 5.5, false));
        assert selectPlacementSlot(placement, 0.5, 5.5) == 3;
        assert selectPlacementSlot(List.of(
            new GridSlot(2.5, 2.5, false), new GridSlot(8.5, 8.5, false)), 1.5, 1.5) == 0;
    }

    record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    record GridSlot(double x, double z, boolean occupied) {
    }
}
