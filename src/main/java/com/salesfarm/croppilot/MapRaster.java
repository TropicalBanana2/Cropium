package com.salesfarm.croppilot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

/** UI-only snapshot: refresh colors four times/second, draw equal-color row spans. */
final class MapRaster {
    record Run(int x, int z, int length, int color) { }
    private List<Run> runs = List.of();
    private int minX, maxX, minZ, maxZ;
    private long refreshAt = Long.MIN_VALUE;

    List<Run> update(int left, int right, int top, int bottom, long now, IntBinaryOperator color) {
        if (left > right || top > bottom || (long)right - left >= 512 || (long)bottom - top >= 512) return List.of();
        if (now < refreshAt && minX == left && maxX == right && minZ == top && maxZ == bottom) return runs;
        minX = left; maxX = right; minZ = top; maxZ = bottom;
        refreshAt = now + 250_000_000L;
        var result = new ArrayList<Run>();
        for (int z = top; z <= bottom; z++) {
            int start = left, previous = color.applyAsInt(left, z);
            for (int x = left + 1; x <= right; x++) {
                int next = color.applyAsInt(x, z);
                if (next != previous) {
                    result.add(new Run(start, z, x - start, previous));
                    start = x; previous = next;
                }
            }
            result.add(new Run(start, z, right + 1 - start, previous));
        }
        runs = List.copyOf(result);
        return runs;
    }

    static void selfTest() {
        var raster = new MapRaster();
        int[] reads = {0};
        IntBinaryOperator color = (x, z) -> { reads[0]++; return x < 3 ? 1 : 2; };
        var runs = raster.update(0, 5, 0, 1, 0, color);
        assert runs.size() == 4 && reads[0] == 12;
        assert runs.getFirst().equals(new Run(0, 0, 3, 1));
        assert runs.getLast().equals(new Run(3, 1, 3, 2));
        for (int frame = 1; frame < 15; frame++) assert raster.update(0, 5, 0, 1, frame * 16_000_000L, color) == runs;
        assert reads[0] == 12 : "No block lookups or geometry rebuild on intervening frames";
        raster.update(0, 5, 0, 1, 250_000_000L, color);
        assert reads[0] == 24;
        assert raster.update(-2, -1, 0, 0, 250_000_001L, color).getFirst().equals(new Run(-2, 0, 2, 1));
        assert raster.update(0, 512, 0, 0, 0, color).isEmpty();
        var uniform = new MapRaster().update(0, 200, 0, 200, 0, (x,z) -> 1);
        assert uniform.size() == 201 : "40,401 uniform cells become 201 draw spans";
    }
}
