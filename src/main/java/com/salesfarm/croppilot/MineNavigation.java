package com.salesfarm.croppilot;

import com.salesfarm.croppilot.MotionMath.Vec2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** One eased-input model for live steering, joins, target passes and offline checks. */
final class MineNavigation {
    record Pose(Vec2 position, Vec2 velocity, Vec2 heading, float yaw) { }
    record Input(Vec2 heading, float yaw, int strafe) { }
    record Guide(Vec2 aim, int segment, double crossTrack) { }
    record Forecast(boolean clear, Input input, List<Vec2> points, Vec2 obstruction,
                    double closestTarget, double error) { }

    static Vec2 yawHeading(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vec2(-Math.sin(radians), Math.cos(radians));
    }

    static double lookAhead(double speed, double inertia) {
        return Math.clamp(speed * (7.0 + 1.8 * inertia / (1.0 - inertia)), 4.0, 20.0);
    }

    static Vec2 forwardJoin(Vec2 position, Vec2 start, Vec2 end, double speed) {
        Vec2 tangent = end.subtract(start);
        double t = MotionMath.routeProgress(position, start, end, 0).progress();
        return start.add(tangent.scale(Math.clamp(t + Math.max(8, speed * 14) / Math.max(0.01, tangent.length()), 0, 1)));
    }

    static Input input(Pose pose, Vec2 aim, boolean precise, Integer forcedStrafe) {
        Vec2 desired = aim.subtract(pose.position).normalized();
        if (desired.length() < 0.001) desired = pose.heading;
        Vec2 heading = FlightMotion.followHeading(pose.heading, desired, precise ? 9.0 : 6.0);
        float yaw = FlightMotion.easeYaw(pose.yaw, heading, precise ? 0.70F : 0.50F);
        double error = MotionMath.signedAngle(yawHeading(yaw), desired);
        int strafe = forcedStrafe != null ? forcedStrafe
            : Math.abs(error) > Math.toRadians(24) ? (int)Math.signum(error) : 0;
        return new Input(heading, yaw, strafe);
    }

    static Pose step(Pose pose, Input input, double speed, double inertia) {
        Vec2 direction = yawHeading(input.yaw).rotate(input.strafe * Math.PI / 4);
        Vec2 velocity = pose.velocity.scale(inertia).add(direction.scale(speed * (1 - inertia)));
        return new Pose(pose.position.add(velocity), velocity, input.heading, input.yaw);
    }

    static Guide guide(List<Vec2> path, Vec2 position, int segment, double lookAhead) {
        segment = Math.clamp(segment, 1, path.size() - 1);
        while (segment < path.size() - 1 && (position.subtract(path.get(segment)).length() < 0.6
            || MotionMath.shouldAdvanceSegment(position, path.get(segment - 1), path.get(segment), path.get(segment + 1)))) {
            segment++;
        }
        var progress = MotionMath.routeProgress(position, path.get(segment - 1), path.get(segment), 0);
        Vec2 aim = MotionMath.polylineLookPoint(position, path.get(segment - 1), path.get(segment),
            path.subList(segment + 1, path.size()), lookAhead);
        // Forecasts must continue through the end, never turn back to a passed point.
        if (segment == path.size() - 1 && progress.progress() >= 1.0) {
            aim = position.add(path.get(segment).subtract(path.get(segment - 1)).normalized().scale(lookAhead));
        }
        return new Guide(aim, segment, progress.crossTrack());
    }

    static Forecast forecast(Pose initial, List<Vec2> path, double speed, double inertia,
                             Integer firstStrafe, Vec2 target, int ticks, BiPredicate<Vec2, Vec2> clear) {
        Pose pose = initial;
        int segment = 1;
        Input first = null;
        List<Vec2> points = new ArrayList<>();
        points.add(pose.position);
        double closest = Double.POSITIVE_INFINITY, error = 0;
        Vec2 approach = target == null ? null : target.subtract(path.getFirst()).normalized();
        for (int tick = 0; tick < ticks; tick++) {
            Guide guide = guide(path, pose.position, segment, lookAhead(speed, inertia));
            segment = guide.segment;
            boolean approaching = target != null && !MotionMath.passedPoint(pose.position, target, approach, 0.3);
            Vec2 aim = approaching ? target.add(approach.scale(0.8)) : guide.aim;
            Input input = input(pose, aim, approaching, tick == 0 ? firstStrafe : null);
            if (first == null) first = input;
            Pose next = step(pose, input, speed, inertia);
            points.add(next.position);
            if (target != null) closest = Math.min(closest,
                MotionMath.routeProgress(target, pose.position, next.position, 0).crossTrack());
            if (!clear.test(pose.position, next.position)) {
                return new Forecast(false, first, points, next.position, closest, error);
            }
            error += Math.min(20, guide.crossTrack);
            pose = next;
        }
        return new Forecast(true, first, points, null, closest, error / Math.max(1, ticks));
    }

    /** Passive estimate from observed movement; teleport/correction samples are excluded. */
    static final class Response {
        double speed = 0.8, inertia = 0.70;
        private Vec2 previousVelocity, previousInput;

        void observe(Vec2 velocity) {
            if (velocity.length() > 3.0) { reset(); return; }
            if (previousVelocity != null && previousInput != null && velocity.length() > 0.15) {
                Vec2 side = new Vec2(-previousInput.z(), previousInput.x());
                double lateral = previousVelocity.dot(side);
                if (Math.abs(lateral) > 0.08) {
                    double measured = velocity.dot(side) / lateral;
                    if (measured >= 0.35 && measured <= 0.95) inertia += (measured - inertia) * 0.06;
                }
                double cruise = velocity.subtract(previousVelocity.scale(inertia)).dot(previousInput) / (1 - inertia);
                if (cruise >= 0.2 && cruise <= 2.5) speed += (cruise - speed) * 0.08;
            }
            previousVelocity = velocity;
        }

        void applied(Input input) { previousInput = yawHeading(input.yaw).rotate(input.strafe * Math.PI / 4); }
        void reset() { previousVelocity = previousInput = null; }
        double planningSpeed(Vec2 velocity) { return Math.clamp(Math.max(speed, velocity.length()) * 1.06, 0.4, 2.65); }
    }

    static boolean loadingAllowed(boolean teleporting, boolean loadingScreen, long age, int timeout) {
        return teleporting && loadingScreen && age <= timeout;
    }

    static boolean preferIce(double iceScore, double fossilScore) {
        return !Double.isFinite(fossilScore) && Double.isFinite(iceScore);
    }

    static double iceCoverage(Vec2[] path, List<Vec2> ice) {
        if (ice.isEmpty() || path.length < 2) return 0;
        int hits = 0;
        for (Vec2 block : ice) {
            for (int i = 0; i < path.length; i++) {
                if (MotionMath.routeProgress(block, path[i], path[(i + 1) % path.length], 0).crossTrack() <= 1.25) {
                    hits++;
                    break; // A cell counts once, not once per nearby route vertex.
                }
            }
        }
        double distance = 0;
        for (int i = 0; i < path.length; i++) distance += path[i].subtract(path[(i + 1) % path.length]).length();
        return hits * 100.0 / Math.max(1, distance);
    }

    static void selfTest() {
        assert !preferIce(20, 20) && !preferIce(1, 1000) : "Any safe fossil outranks ice, regardless of distance score";
        assert !preferIce(40, 5) : "A nearby fossil still beats a distant ice detour";
        assert !preferIce(Double.POSITIVE_INFINITY, 10) && preferIce(10, Double.POSITIVE_INFINITY);
        assert !preferIce(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY) : "No reachable targets means stay on route";
        assert MineLayout.upgradeRouteMode("FIGURE_EIGHT").equals("ZAMBONI");
        assert MineLayout.upgradeRouteMode("WEAVE").equals("LONG_OVAL");
        var iceLine = new Vec2[]{new Vec2(0, 0), new Vec2(20, 0), new Vec2(20, 10), new Vec2(0, 10)};
        assert iceCoverage(iceLine, List.of(new Vec2(10, 0))) > iceCoverage(iceLine, List.of(new Vec2(10, 5)));
        assert iceCoverage(iceLine, List.of()) == 0;
        assert loadingAllowed(true, true, 40, 120);
        assert !loadingAllowed(false, true, 40, 120);
        assert !loadingAllowed(true, false, 40, 120);
        assert !loadingAllowed(true, true, 121, 120);
        Vec2 entry = new Vec2(383.5, 1536.5);
        Vec2 join = forwardJoin(entry, new Vec2(382.5, 1535.5), new Vec2(340.5, 1535.5), 0.8);
        assert join.x() < entry.x() - 8 && join.z() == 1535.5 : "Entry must join ahead along the lane";
        var outline = MotionMath.roundedRectangle(328.5, 394.5, 1535.5, 1601.5, 22, 12);
        BiPredicate<Vec2, Vec2> core = (a, b) -> b.x() >= 326 && b.x() < 397 && b.z() >= 1533 && b.z() < 1604;
        for (var mode : MineLayout.RouteMode.values()) {
            assert mode.offset(1).offset(-1) == mode;
            for (var previous : MineLayout.MinePattern.values()) {
                var choices = MineLayout.patternChoices(mode, previous, new java.util.Random(1));
                assert mode.pattern == null ? choices.size() == 5 && !choices.contains(previous)
                    : choices.equals(List.of(mode.pattern));
                if (mode.pattern == null) assert MineLayout.longPass(choices.getFirst());
            }
        }
        for (int seed = 0; seed < 12; seed++) {
            var random = new java.util.Random(seed);
            int deadline = MineLayout.nextChangeTick(100, 60, random);
            assert deadline >= 100 + 900 && deadline <= 100 + 1500 : "Bounded 45–75s default cadence";
            for (var pattern : MineLayout.MinePattern.values()) {
                var points = MineLayout.variedPath(pattern, outline, random, 1);
                var repeat = MineLayout.variedPath(pattern, outline, new java.util.Random(seed + 100), 1);
                assert !java.util.Arrays.equals(points, repeat) : "Repeated style must vary across seeds";
                var unvaried = MineLayout.variedPath(pattern, outline, new java.util.Random(seed), 0);
                assert java.util.Arrays.equals(unvaried,
                    MineLayout.patternPath(pattern, outline, new java.util.Random(seed))) : "Zero removes shape jitter";
                for (int i = 0; i < points.length; i++) {
                    Vec2 p = points[i];
                    assert p.x() >= 328.5 && p.x() <= 394.5 && p.z() >= 1535.5 && p.z() <= 1601.5
                        : "Variation must never expand the validated outline";
                    assert p.subtract(points[(i + 1) % points.length]).length() > 0.05 : "No duplicate/stop vertices";
                }
                if (MineLayout.longPass(pattern)) {
                    double winding = 0;
                    for (int i = 0; i < points.length; i++) {
                        var entering = points[i].subtract(points[Math.floorMod(i - 1, points.length)]);
                        var leaving = points[(i + 1) % points.length].subtract(points[i]);
                        double turn = MotionMath.signedAngle(entering, leaving);
                        assert turn >= -1e-8 : "Long ovals must be convex, never figure eights";
                        winding += turn;
                    }
                    assert Math.abs(winding - 2 * Math.PI) < 0.001 : "One non-crossing oval lap";
                    double width = java.util.Arrays.stream(points).mapToDouble(Vec2::x).max().orElseThrow()
                        - java.util.Arrays.stream(points).mapToDouble(Vec2::x).min().orElseThrow();
                    double depth = java.util.Arrays.stream(points).mapToDouble(Vec2::z).max().orElseThrow()
                        - java.util.Arrays.stream(points).mapToDouble(Vec2::z).min().orElseThrow();
                    assert Math.max(width, depth) > 60 && Math.max(width, depth) / Math.min(width, depth) > 1.3
                        : "Long passes must span the area, not turn into little circles";
                }
                for (int direction : new int[]{-1, 1}) {
                    List<Vec2> course = new ArrayList<>();
                    for (int n = 0; n < points.length * 8; n++) course.add(points[Math.floorMod(n * direction, points.length)]);
                    Vec2 h = course.get(1).subtract(course.getFirst()).normalized();
                    var pose = new Pose(course.getFirst(), h, h, (float)Math.toDegrees(Math.atan2(-h.x(), h.z())));
                    var result = forecast(pose, course, 1, 0.85, null, null, 800, core);
                    assert result.clear : "Varied loop left safe core: " + pattern + " seed=" + seed + " dir=" + direction;
                }
            }
        }
        for (var pattern : MineLayout.MinePattern.values()) {
            var points = MineLayout.patternPath(pattern, outline, new java.util.Random(17));
            for (int direction : new int[]{1, -1}) {
                List<Vec2> course = new ArrayList<>();
                for (int n = 0; n < points.length * 8; n++) course.add(points[Math.floorMod(n * direction, points.length)]);
                for (double inertia : new double[]{0.5, 0.7, 0.85}) {
                    for (double speed : new double[]{0.4, 0.7, 1.0}) {
                        Vec2 h = course.get(1).subtract(course.getFirst()).normalized();
                        Pose initial = new Pose(course.getFirst(), h.scale(speed), h,
                            (float)Math.toDegrees(Math.atan2(-h.x(), h.z())));
                        var result = forecast(initial, course, speed, inertia, null, null, 800, core);
                        assert result.clear : "Unsafe loop " + pattern + " speed=" + speed + " inertia=" + inertia
                            + " direction=" + direction + " at " + result.obstruction + " after " + result.points.size();
                    }
                }
            }
        }
        Vec2 east = new Vec2(1, 0);
        Pose initial = new Pose(new Vec2(350, 1550), east.scale(0.8), east, -90);
        List<Vec2> pass = List.of(initial.position, new Vec2(370, 1550), new Vec2(380, 1550), new Vec2(390, 1560), new Vec2(390, 1590));
        var target = forecast(initial, pass, 0.8, 0.7, null, pass.get(1), 50, core);
        assert target.clear && target.closestTarget < 0.1 : "Direct fossil pass must cross the crop";
        var obstruction = forecast(initial, pass, 0.8, 0.7, null, pass.get(1), 50,
            (a, b) -> core.test(a, b) && !(b.x() >= 364 && b.x() <= 367 && b.z() < 1552));
        assert !obstruction.clear && obstruction.obstruction != null;
        var afterEnd = guide(List.of(new Vec2(0, 0), new Vec2(10, 0)), new Vec2(11, 0), 1, 5);
        assert afterEnd.aim.x() > 11 : "No circles back to passed endpoints";
        var response = new Response();
        for (int n = 0; n < 60; n++) {
            response.observe(east.scale(0.9));
            response.applied(new Input(east, -90, 0));
        }
        assert response.speed > 0.85 && response.speed < 1;
        double before = response.speed;
        response.observe(east.scale(40));
        assert response.speed == before : "Teleport is not a speed measurement";
        try {
            var trace = new MineTrace();
            trace.event(1, "Local rejoin test");
            trace.sample(1, initial, new Input(east, -90, 0), 0.8, 0.7);
            var directory = java.nio.file.Files.createTempDirectory("cropium-trace-test-");
            var file = directory.resolve("sample.csv");
            trace.write(file);
            assert MineTrace.replay(file) == 1;
            java.nio.file.Files.delete(file);
            java.nio.file.Files.delete(directory);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
