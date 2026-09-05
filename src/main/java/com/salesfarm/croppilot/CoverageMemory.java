package com.salesfarm.croppilot;

import java.util.LinkedHashMap;
import java.util.List;

/** Exact break events only. A bounded overlay, never a replacement for the saved map. */
public final class CoverageMemory {
    public static final int RECENT = 0xFF6EE7A8, MISSED = 0xFFFF9F43,
        IGNORED = 0xFF777784, GLOW = 0xFFFF5EDB, ICE = 0xFF65D9F3;
    private static final int CAPACITY = 4096;
    private static final long MAX_AGE = 1_200;
    private final LinkedHashMap<Long, Long> breaks = new LinkedHashMap<>();

    public void clear() { breaks.clear(); }
    public void record(int x, int z, long tick) {
        long key = key(x, z);
        breaks.remove(key);
        breaks.put(key, tick);
        while (breaks.size() > CAPACITY) breaks.pollFirstEntry();
    }
    public boolean recent(int x, int z, long tick) {
        Long at = breaks.get(key(x, z));
        return at != null && tick >= at && tick - at < MAX_AGE;
    }
    public double recentFraction(List<MotionMath.Vec2> route, long tick) {
        int samples = 0, hits = 0;
        for (int i = 1; i < route.size(); i++) {
            var start = route.get(i - 1);
            var delta = route.get(i).subtract(start);
            int steps = Math.max(1, (int)Math.ceil(delta.length() / 2));
            for (int step = 0; step < steps; step++) {
                var point = start.add(delta.scale((double)step / steps));
                if (recent((int)Math.floor(point.x()), (int)Math.floor(point.z()), tick)) hits++;
                samples++;
            }
        }
        return samples == 0 ? 0 : (double)hits / samples;
    }
    private static long key(int x, int z) { return (long)x << 32 ^ z & 0xFFFFFFFFL; }

    public static boolean excluded(List<ExclusionZone> zones, String world, String module,
                                   double x, double z, double padding) {
        if (zones == null) return false;
        for (var zone : zones) {
            if (zone != null && zone.intersects(world, module, x, z, padding)) return true;
        }
        return false;
    }

    public static boolean segmentExcluded(List<ExclusionZone> zones, String world, String module,
                                          MotionMath.Vec2 start, MotionMath.Vec2 end, double padding) {
        if (zones == null) return false;
        for (var zone : zones) {
            if (segmentExcluded(zone, world, module, start, end, padding)) return true;
        }
        return false;
    }

    /** Exact swept rectangle intersection: short diagonal corner clips cannot fall between samples. */
    public static boolean segmentExcluded(ExclusionZone zone, String world, String module,
                                          MotionMath.Vec2 start, MotionMath.Vec2 end, double padding) {
        if (zone == null || !zone.valid() || !zone.worldKey().equals(world) || !zone.moduleId().equals(module)) return false;
        if (!Double.isFinite(start.x()) || !Double.isFinite(start.z())
            || !Double.isFinite(end.x()) || !Double.isFinite(end.z()) || !Double.isFinite(padding)) return true;
        padding = Math.max(0, padding);
        double near = 0, far = 1;
        double[] origins = {start.x(), start.z()}, deltas = {end.x() - start.x(), end.z() - start.z()};
        double[] minimum = {zone.minX() - padding, zone.minZ() - padding};
        double[] maximum = {zone.maxX() + 1.0 + padding, zone.maxZ() + 1.0 + padding};
        for (int axis = 0; axis < 2; axis++) {
            if (Math.abs(deltas[axis]) < 1.0E-12) {
                if (origins[axis] < minimum[axis] || origins[axis] > maximum[axis]) return false;
            } else {
                double a = (minimum[axis] - origins[axis]) / deltas[axis];
                double b = (maximum[axis] - origins[axis]) / deltas[axis];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return false;
            }
        }
        return true;
    }

    public static void selfTest() {
        var memory = new CoverageMemory();
        memory.record(-1, 4, 10);
        assert memory.recent(-1, 4, 10) && !memory.recent(4, -1, 10);
        assert !memory.recent(-1, 4, 9) && !memory.recent(-1, 4, 1_210);
        for (int i = 0; i <= CAPACITY; i++) memory.record(i, 0, 20);
        assert memory.breaks.size() == CAPACITY && !memory.recent(0, 0, 20);
        assert memory.recentFraction(List.of(new MotionMath.Vec2(2, 0), new MotionMath.Vec2(8, 0)), 20) == 1;
        memory.clear();
        assert !memory.recent(8, 0, 20);
        var zone = new ExclusionZone("world", "mine", 10, 10, 10, 10);
        var start = new MotionMath.Vec2(0, 10.5);
        var end = new MotionMath.Vec2(20, 10.5);
        assert segmentExcluded(zone, "world", "mine", start, end, 1.3);
        assert !segmentExcluded(zone, "other", "mine", start, end, 1.3);
        assert !segmentExcluded(zone, "world", "harvest", start, end, 1.3);
        assert segmentExcluded(zone, "world", "mine", new MotionMath.Vec2(8.9, 11.8),
            new MotionMath.Vec2(9.2, 12.1), 1.0) : "Corner clip between sampled points";
        assert segmentExcluded(zone, "world", "mine", new MotionMath.Vec2(8.8, 10.5),
            new MotionMath.Vec2(8.8, 10.5), 1.3) : "Body padding at zero length";
        assert !segmentExcluded(zone, "world", "mine", new MotionMath.Vec2(0, 5),
            new MotionMath.Vec2(20, 5), 1.3);
    }
}
