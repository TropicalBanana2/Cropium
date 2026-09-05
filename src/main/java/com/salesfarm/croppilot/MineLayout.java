package com.salesfarm.croppilot;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Sales mine, verified against the downloaded world and the player's corner coordinates. */
final class MineLayout {
    static final int MIN_X = 321;
    static final int MAX_X = 401;
    static final int MIN_Z = 1528;
    static final int MAX_Z = 1608;
    // The screenshots show feet at 120; the actual mineable blocks are one block lower.
    static final int FLOOR_Y = 119;
    static final int FLIGHT_SETTLE_TICKS = 60;
    static final List<MotionMath.Vec2> ENTRY = List.of(
        new MotionMath.Vec2(406.5, 1529.5), // gently angled west, up the slab ramp
        new MotionMath.Vec2(383.5, 1536.5)); // shallow diagonal into the north mining lane

    private MineLayout() {}

    static boolean interior(int x, int y, int z) {
        return y == FLOOR_Y && x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z;
    }

    static boolean rim(int x, int z) {
        return x >= MIN_X - 1 && x <= MAX_X + 1 && z >= MIN_Z - 1 && z <= MAX_Z + 1
            && (x == MIN_X - 1 || x == MAX_X + 1 || z == MIN_Z - 1 || z == MAX_Z + 1);
    }

    static boolean nearArrival(double x, double y, double z) {
        return Math.hypot(x - 431.271, z - 1527.239) <= 3.0 && Math.abs(y - 115.0) <= 1.5;
    }

    static boolean lowFlightHeight(double feetY) {
        double height = feetY - (FLOOR_Y + 1);
        return height >= 0.2 && height <= 1.8;
    }

    static boolean descentTap(int age, double feetY, double verticalSpeed, double flyingSpeed) {
        return descentTapForClearance(age, feetY - (FLOOR_Y + 1), verticalSpeed, flyingSpeed);
    }

    static boolean descentTapForClearance(int age, double clearance, double verticalSpeed, double flyingSpeed) {
        if (age < 4 || age > FLIGHT_SETTLE_TICKS || age % 6 != 4 || !Double.isFinite(clearance)) {
            return false;
        }
        // Vanilla adds 3 * flyingSpeed per pressed tick and damps Y velocity by
        // 0.6. Include the remaining coast; never spend the last 0.3-block gap.
        double settled = clearance + Math.min(0.0, verticalSpeed) / (1.0 - 0.6);
        double tapDrop = Math.abs(flyingSpeed) * 3.0 / (1.0 - 0.6);
        return settled > 1.0 && settled - tapDrop >= 0.35;
    }

    static boolean readyToMine(int age, double clearance, double verticalSpeed) {
        return age >= 8 && clearance >= 0.2 && clearance <= 1.8 && Math.abs(verticalSpeed) < 0.035;
    }

    enum MinePattern {
        PERIMETER("Perimeter"), CROSS_CUT("Cross-cut"), REVERSAL("Reverse perimeter"),
        ZAMBONI("Zamboni passes"), BACK_AND_FORTH("Back-and-forth"), LONG_OVAL("Long oval");

        final String label;
        MinePattern(String label) { this.label = label; }
    }

    enum RouteMode {
        AUTO("Automatic mix", null), PERIMETER("Perimeter", MinePattern.PERIMETER),
        CROSS_CUT("Cross-cuts", MinePattern.CROSS_CUT), REVERSAL("Reversals", MinePattern.REVERSAL),
        ZAMBONI("Zamboni passes", MinePattern.ZAMBONI),
        BACK_AND_FORTH("Back-and-forth", MinePattern.BACK_AND_FORTH), LONG_OVAL("Long oval", MinePattern.LONG_OVAL);

        final String label;
        final MinePattern pattern;
        RouteMode(String label, MinePattern pattern) { this.label = label; this.pattern = pattern; }
        RouteMode offset(int step) { return values()[Math.floorMod(ordinal() + step, values().length)]; }
    }

    static int nextChangeTick(int ticks, int seconds, Random random) {
        int base = Math.clamp(seconds, 30, 180) * 20;
        return ticks + base * 3 / 4 + random.nextInt(base / 2 + 1);
    }

    static List<MinePattern> patternChoices(RouteMode mode, MinePattern previous, Random random) {
        if (mode.pattern != null) return List.of(mode.pattern);
        var choices = new java.util.ArrayList<>(List.of(MinePattern.values()));
        choices.remove(previous);
        java.util.Collections.shuffle(choices, random);
        // Prefer end-to-end coverage. Perimeter/cuts/reversals are safe fallbacks.
        choices.sort(java.util.Comparator.comparingInt(pattern -> longPass(pattern) ? 0 : 1));
        return choices;
    }

    static boolean longPass(MinePattern pattern) {
        return pattern == MinePattern.ZAMBONI || pattern == MinePattern.LONG_OVAL || pattern == MinePattern.BACK_AND_FORTH;
    }

    static String upgradeRouteMode(String saved) {
        return switch (saved) {
            case "FIGURE_EIGHT" -> "ZAMBONI";
            case "WEAVE" -> "LONG_OVAL";
            default -> saved;
        };
    }

    static MotionMath.Vec2[] variedPath(MinePattern pattern, MotionMath.Vec2[] perimeter,
                                      Random random, double variation) {
        MotionMath.Vec2[] path = patternPath(pattern, perimeter, random);
        double amount = Double.isFinite(variation) ? Math.clamp(variation, 0, 1) : 0;
        if (amount == 0) return path;
        double minX = Arrays.stream(perimeter).mapToDouble(MotionMath.Vec2::x).min().orElseThrow();
        double maxX = Arrays.stream(perimeter).mapToDouble(MotionMath.Vec2::x).max().orElseThrow();
        double minZ = Arrays.stream(perimeter).mapToDouble(MotionMath.Vec2::z).min().orElseThrow();
        double maxZ = Arrays.stream(perimeter).mapToDouble(MotionMath.Vec2::z).max().orElseThrow();
        double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        // Transform the whole curve, not individual waypoints: keep the broad turns
        // smooth and stay inside the validated outline, even at maximum variation.
        double sx = 1 - random.nextDouble() * 0.08 * amount;
        double sz = 1 - random.nextDouble() * 0.08 * amount;
        double dx = (random.nextDouble() * 2 - 1) * (maxX - minX) / 2 * (1 - sx) * 0.8;
        double dz = (random.nextDouble() * 2 - 1) * (maxZ - minZ) / 2 * (1 - sz) * 0.8;
        MotionMath.Vec2[] varied = new MotionMath.Vec2[path.length];
        int phase = random.nextInt(path.length);
        for (int i = 0; i < path.length; i++) {
            var p = path[(i + phase) % path.length];
            varied[i] = new MotionMath.Vec2(cx + (p.x() - cx) * sx + dx, cz + (p.z() - cz) * sz + dz);
        }
        return varied;
    }

    static MotionMath.Vec2[] patternPath(MinePattern pattern, MotionMath.Vec2[] perimeter, Random random) {
        if (pattern == MinePattern.PERIMETER || pattern == MinePattern.CROSS_CUT || pattern == MinePattern.REVERSAL) {
            return perimeter.clone();
        }
        double minX = Arrays.stream(perimeter).mapToDouble(MotionMath.Vec2::x).min().orElseThrow();
        double maxX = Arrays.stream(perimeter).mapToDouble(MotionMath.Vec2::x).max().orElseThrow();
        double minZ = Arrays.stream(perimeter).mapToDouble(MotionMath.Vec2::z).min().orElseThrow();
        double maxZ = Arrays.stream(perimeter).mapToDouble(MotionMath.Vec2::z).max().orElseThrow();
        MotionMath.Vec2 center = new MotionMath.Vec2((minX + maxX) / 2, (minZ + maxZ) / 2);
        double rx = (maxX - minX) / 2, rz = (maxZ - minZ) / 2;
        boolean alongX = random.nextBoolean();
        // Shift the broad return lanes across the floor; never make tight hairpins.
        double cross = Math.min(rx, rz) * (0.52 + random.nextDouble() * 0.16);
        double lateralRoom = (alongX ? rz : rx) - cross;
        double lateralShift = (random.nextDouble() * 2 - 1) * lateralRoom * 0.8;
        center = center.add(alongX ? new MotionMath.Vec2(0, lateralShift) : new MotionMath.Vec2(lateralShift, 0));
        if (pattern == MinePattern.ZAMBONI || pattern == MinePattern.BACK_AND_FORTH) {
            var loop = MotionMath.roundedRectangle(alongX ? minX : center.x() - cross,
                alongX ? maxX : center.x() + cross, alongX ? center.z() - cross : minZ,
                alongX ? center.z() + cross : maxZ, cross, 12);
            // A stadium has touching quarter-arcs; omit their duplicate endpoints.
            var unique = new java.util.ArrayList<MotionMath.Vec2>();
            for (var p : loop) {
                if (unique.isEmpty() || p.subtract(unique.getLast()).length() > 0.01) unique.add(p);
            }
            if (unique.getFirst().subtract(unique.getLast()).length() < 0.01) unique.removeLast();
            return unique.toArray(MotionMath.Vec2[]::new);
        }
        MotionMath.Vec2[] points = new MotionMath.Vec2[96];
        for (int i = 0; i < points.length; i++) {
            double angle = i * Math.PI * 2 / points.length;
            points[i] = center.add(new MotionMath.Vec2(Math.cos(angle) * (alongX ? rx : cross),
                Math.sin(angle) * (alongX ? cross : rz)));
        }
        return points;
    }
}
