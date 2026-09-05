package com.salesfarm.croppilot;

public final class MotionMath {
    private MotionMath() {
    }

    public static double smootherstep(double value) {
        double t = Math.clamp(value, 0.0, 1.0);
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    public static double signedAngle(Vec2 from, Vec2 to) {
        return Math.atan2(from.x * to.z - from.z * to.x, from.x * to.x + from.z * to.z);
    }

    public static double directedTurnAngle(Vec2 from, Vec2 to, int preferredSign) {
        double signed = signedAngle(from, to);
        if (Math.abs(signed) > Math.toRadians(120.0)
            && preferredSign != 0 && Math.signum(signed) != preferredSign) {
            signed += Math.copySign(Math.PI * 2.0, preferredSign);
        }
        return signed;
    }

    public static RouteProgress routeProgress(Vec2 position, Vec2 start, Vec2 end, double lookAhead) {
        Vec2 segment = end.subtract(start);
        double lengthSquared = segment.x * segment.x + segment.z * segment.z;
        if (lengthSquared < 1.0E-9) {
            return new RouteProgress(1.0, position.subtract(end).length(), end, 0.0);
        }
        double length = Math.sqrt(lengthSquared);
        double projection = position.subtract(start).dot(segment) / lengthSquared;
        double progress = Math.clamp(projection, 0.0, 1.0);
        Vec2 closest = start.add(segment.scale(progress));
        double lookProgress = Math.clamp(progress + Math.max(0.0, lookAhead) / length, 0.0, 1.0);
        Vec2 lookPoint = start.add(segment.scale(lookProgress));
        return new RouteProgress(progress, position.subtract(closest).length(), lookPoint, length * (1.0 - progress));
    }

    public static Vec2 polylineLookPoint(Vec2 position, Vec2 start, Vec2 end,
                                         Vec2 next, double lookAhead) {
        return polylineLookPoint(position, start, end,
            next == null ? java.util.List.of() : java.util.List.of(next), lookAhead);
    }

    public static Vec2 polylineLookPoint(Vec2 position, Vec2 start, Vec2 end,
                                         Iterable<Vec2> following, double lookAhead) {
        RouteProgress progress = routeProgress(position, start, end, lookAhead);
        double remaining = Math.max(0.0, lookAhead);
        if (progress.remaining >= remaining) {
            return progress.lookPoint;
        }
        remaining -= progress.remaining;
        Vec2 cursor = end;
        for (Vec2 next : following) {
            Vec2 segment = next.subtract(cursor);
            double length = segment.length();
            if (length >= remaining) {
                return length == 0.0 ? cursor : cursor.add(segment.scale(remaining / length));
            }
            remaining -= length;
            cursor = next;
        }
        return cursor;
    }

    public static boolean shouldAdvanceSegment(Vec2 position, Vec2 start, Vec2 end, Vec2 next) {
        Vec2 incoming = end.subtract(start);
        if (incoming.length() == 0.0 || position.subtract(end).dot(incoming) >= 0.0) {
            return true;
        }
        Vec2 outgoing = next == null ? new Vec2(0.0, 0.0) : next.subtract(end);
        if (outgoing.length() == 0.0 || position.subtract(end).dot(outgoing) <= 0.0) {
            return false;
        }
        double incomingDistance = routeProgress(position, start, end, 0.0).crossTrack;
        double outgoingDistance = routeProgress(position, end, next, 0.0).crossTrack;
        return outgoingDistance <= incomingDistance + 0.05;
    }

    public static GlowInterceptPlan glowInterceptPlan(Vec2 position, Vec2 start, Vec2 end, Vec2 target,
                                                       double minimumForward, double maximumForward,
                                                       double maximumCrossTrack, double rejoinRunway) {
        Vec2 segment = end.subtract(start);
        double length = segment.length();
        if (length < minimumForward + rejoinRunway + 2.0) {
            return null;
        }
        double lengthSquared = length * length;
        double playerProgress = position.subtract(start).dot(segment) / lengthSquared;
        double targetProgress = target.subtract(start).dot(segment) / lengthSquared;
        double forward = (targetProgress - playerProgress) * length;
        double crossTrack = routeProgress(target, start, end, 0.0).crossTrack;
        double rejoinProgress = targetProgress + rejoinRunway / length;
        if (forward < minimumForward || forward > maximumForward
            || crossTrack > maximumCrossTrack || rejoinProgress > 0.92) {
            return null;
        }
        return new GlowInterceptPlan(target, start.add(segment.scale(rejoinProgress)),
            targetProgress, rejoinProgress, forward + crossTrack * 2.5);
    }

    public static GlowInterceptPlan glowInterceptPlan(Vec2 position, Iterable<Vec2> upcomingRoute,
                                                       Vec2 target, double minimumForward,
                                                       double maximumForward, double maximumCrossTrack,
                                                       double rejoinRunway) {
        java.util.ArrayList<Vec2> points = new java.util.ArrayList<>();
        points.add(position);
        upcomingRoute.forEach(points::add);
        double closest = Double.POSITIVE_INFINITY;
        double closestAlong = 0.0;
        double routeLength = 0.0;
        for (int index = 1; index < points.size(); index++) {
            Vec2 start = points.get(index - 1);
            Vec2 segment = points.get(index).subtract(start);
            double length = segment.length();
            if (length == 0.0) {
                continue;
            }
            double progress = Math.clamp(target.subtract(start).dot(segment) / (length * length), 0.0, 1.0);
            double distance = target.subtract(start.add(segment.scale(progress))).length();
            if (distance < closest) {
                closest = distance;
                closestAlong = routeLength + progress * length;
            }
            routeLength += length;
        }
        double rejoinAlong = closestAlong + rejoinRunway;
        if (closestAlong < minimumForward || closestAlong > maximumForward
            || closest > maximumCrossTrack || rejoinAlong > routeLength) {
            return null;
        }
        double traversed = 0.0;
        Vec2 rejoin = null;
        for (int index = 1; index < points.size(); index++) {
            Vec2 start = points.get(index - 1);
            Vec2 segment = points.get(index).subtract(start);
            double length = segment.length();
            if (traversed + length >= rejoinAlong) {
                rejoin = length == 0.0 ? start
                    : start.add(segment.scale((rejoinAlong - traversed) / length));
                break;
            }
            traversed += length;
        }
        return rejoin == null ? null : new GlowInterceptPlan(
            target, rejoin, closestAlong, rejoinAlong, closestAlong + closest * 2.5);
    }

    public static double glowActivationDistance(double turnRadius, double movementPerTick,
                                                double crossTrack, double turnRadians,
                                                double minimum, double maximum) {
        return Math.clamp(turnRadius + movementPerTick * 12.0 + crossTrack * 0.65
            + Math.toDegrees(Math.abs(turnRadians)) / 6.0, minimum, maximum);
    }

    public static Vec2 directAimPoint(Vec2 position, Vec2 target, double passDistance) {
        return target.add(target.subtract(position).normalized().scale(Math.max(0.0, passDistance)));
    }

    public static Vec2 glowGuidePoint(Vec2 position, Vec2 target, Vec2 exit, Vec2 travel) {
        Vec2 axis = travel.normalized();
        return axis.length() == 0.0 || position.subtract(target).dot(axis) < 0.0 ? target : exit;
    }

    public static boolean passedPoint(Vec2 position, Vec2 target, Vec2 travel, double margin) {
        return position.subtract(target).dot(travel.normalized()) > margin;
    }

    public static Vec2[] figureEight(Vec2 center, double majorRadius, double minorRadius,
                                     boolean majorAxisX, int pointCount, double phase) {
        if (pointCount < 8 || majorRadius <= 0.0 || minorRadius <= 0.0) {
            return new Vec2[0];
        }
        Vec2[] points = new Vec2[pointCount];
        for (int index = 0; index < pointCount; index++) {
            double angle = phase + Math.PI * 2.0 * index / pointCount;
            double major = Math.sin(angle) * majorRadius;
            double minor = Math.sin(angle * 2.0) * minorRadius;
            points[index] = majorAxisX
                ? new Vec2(center.x + major, center.z + minor)
                : new Vec2(center.x + minor, center.z + major);
        }
        return points;
    }

    public static Vec2[] roundedRectangle(double minX, double maxX, double minZ, double maxZ,
                                           double radius, int cornerSamples) {
        if (maxX <= minX || maxZ <= minZ || cornerSamples < 1) {
            return new Vec2[0];
        }
        double safeRadius = Math.clamp(radius, 0.5, Math.min(maxX - minX, maxZ - minZ) * 0.5);
        Vec2[] points = new Vec2[4 * (cornerSamples + 1)];
        double[] centersX = {maxX - safeRadius, maxX - safeRadius,
            minX + safeRadius, minX + safeRadius};
        double[] centersZ = {minZ + safeRadius, maxZ - safeRadius,
            maxZ - safeRadius, minZ + safeRadius};
        int target = 0;
        for (int corner = 0; corner < 4; corner++) {
            double startAngle = -Math.PI / 2.0 + corner * Math.PI / 2.0;
            for (int sample = 0; sample <= cornerSamples; sample++) {
                double angle = startAngle + sample * Math.PI / 2.0 / cornerSamples;
                points[target++] = new Vec2(centersX[corner] + Math.cos(angle) * safeRadius,
                    centersZ[corner] + Math.sin(angle) * safeRadius);
            }
        }
        return points;
    }

    public static TurnCalibration calibrateTurn(double movementPerTick, int durationTicks) {
        return calibrateTurn(movementPerTick, durationTicks, 0.0);
    }

    public static TurnCalibration calibrateTurn(double movementPerTick, int durationTicks, double strafeWeight) {
        Vec2[] points = forecastTurn(new Vec2(0.0, 0.0), new Vec2(1.0, 0.0),
            new Vec2(1.0, 0.0), new Vec2(1.0, 0.0), Math.PI,
            movementPerTick, durationTicks, 0, strafeWeight);
        double forwardExcursion = 0.0;
        for (Vec2 point : points) {
            forwardExcursion = Math.max(forwardExcursion, point.x);
        }
        Vec2 position = points[points.length - 1];
        return new TurnCalibration(forwardExcursion, Math.abs(position.z));
    }

    public static Vec2[] forecastTurn(Vec2 origin, Vec2 startHeading, Vec2 actualLookHeading,
                                      Vec2 actualMovementHeading, double turnRadians,
                                      double movementPerTick, int durationTicks, int fromTick,
                                      double strafeWeight) {
        int duration = Math.max(1, durationTicks);
        int firstTick = Math.clamp(fromTick, 0, duration);
        Vec2[] points = new Vec2[duration - firstTick + 1];
        points[0] = origin;
        Vec2 start = startHeading.normalized();
        Vec2 look = actualLookHeading.normalized();
        if (look.length() == 0.0) {
            look = start;
        }
        Vec2 movement = actualMovementHeading.normalized();
        if (movement.length() == 0.0) {
            movement = start;
        }
        double step = Math.max(0.0, movementPerTick);
        double strafe = Math.max(0.0, strafeWeight);
        double strafeRadians = Math.signum(turnRadians) * Math.PI / 2.0;
        for (int tick = firstTick + 1; tick <= duration; tick++) {
            Vec2 targetLook = start.rotate(turnRadians * smootherstep(tick / (double)duration)).normalized();
            look = look.rotate(signedAngle(look, targetLook) * 0.50).normalized();
            Vec2 desired = strafeRadians == 0.0
                ? look
                : look.add(look.rotate(strafeRadians).scale(strafe)).normalized();
            movement = movement.rotate(signedAngle(movement, desired) * 0.50).normalized();
            int index = tick - firstTick;
            points[index] = points[index - 1].add(movement.scale(step));
        }
        return points;
    }

    public static boolean pathInside(Vec2[] points, double minX, double maxX, double minZ, double maxZ) {
        for (Vec2 point : points) {
            if (point.x < minX || point.x > maxX || point.z < minZ || point.z > maxZ) {
                return false;
            }
        }
        return true;
    }

    public static Vec2[] convexHull(Vec2[] points) {
        if (points == null || points.length == 0) {
            return new Vec2[0];
        }
        Vec2[] sorted = points.clone();
        java.util.Arrays.sort(sorted, java.util.Comparator.comparingDouble(Vec2::x)
            .thenComparingDouble(Vec2::z));
        java.util.List<Vec2> unique = new java.util.ArrayList<>(sorted.length);
        for (Vec2 point : sorted) {
            if (unique.isEmpty() || !unique.getLast().equals(point)) {
                unique.add(point);
            }
        }
        if (unique.size() <= 2) {
            return unique.toArray(Vec2[]::new);
        }

        java.util.List<Vec2> lower = new java.util.ArrayList<>();
        for (Vec2 point : unique) {
            while (lower.size() >= 2
                && cross(lower.get(lower.size() - 2), lower.getLast(), point) <= 0.0) {
                lower.removeLast();
            }
            lower.add(point);
        }
        java.util.List<Vec2> upper = new java.util.ArrayList<>();
        for (int index = unique.size() - 1; index >= 0; index--) {
            Vec2 point = unique.get(index);
            while (upper.size() >= 2
                && cross(upper.get(upper.size() - 2), upper.getLast(), point) <= 0.0) {
                upper.removeLast();
            }
            upper.add(point);
        }
        lower.removeLast();
        upper.removeLast();
        lower.addAll(upper);
        return lower.toArray(Vec2[]::new);
    }

    private static double cross(Vec2 origin, Vec2 a, Vec2 b) {
        return (a.x - origin.x) * (b.z - origin.z) - (a.z - origin.z) * (b.x - origin.x);
    }

    public static Vec2[] startClosedPathNear(Vec2 position, Vec2[] points) {
        if (points.length == 0) {
            return new Vec2[0];
        }
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < points.length; index++) {
            Vec2 offset = points[index].subtract(position);
            double distance = offset.dot(offset);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = index;
            }
        }
        Vec2[] ordered = new Vec2[points.length + 1];
        for (int index = 0; index <= points.length; index++) {
            ordered[index] = points[(nearest + index) % points.length];
        }
        return ordered;
    }

    public static int[] shortestGridPath(boolean[] traversable, int width, int start, boolean[] goals) {
        if (traversable == null || goals == null || width <= 0 || traversable.length == 0
            || traversable.length != goals.length || traversable.length % width != 0
            || start < 0 || start >= traversable.length || !traversable[start]) {
            return new int[0];
        }

        int height = traversable.length / width;
        int[] previous = new int[traversable.length];
        java.util.Arrays.fill(previous, -2);
        int[] queue = new int[traversable.length];
        int head = 0;
        int tail = 0;
        int reached = -1;
        previous[start] = -1;
        queue[tail++] = start;
        int[] dx = {-1, 1, 0, 0};
        int[] dz = {0, 0, -1, 1};
        while (head < tail) {
            int cell = queue[head++];
            if (goals[cell]) {
                reached = cell;
                break;
            }
            int x = cell % width;
            int z = cell / width;
            for (int direction = 0; direction < dx.length; direction++) {
                int nextX = x + dx[direction];
                int nextZ = z + dz[direction];
                if (nextX < 0 || nextX >= width || nextZ < 0 || nextZ >= height) {
                    continue;
                }
                int next = nextZ * width + nextX;
                if (traversable[next] && previous[next] == -2) {
                    previous[next] = cell;
                    queue[tail++] = next;
                }
            }
        }
        if (reached < 0) {
            return new int[0];
        }

        int length = 1;
        for (int cell = reached; previous[cell] >= 0; cell = previous[cell]) {
            length++;
        }
        int[] path = new int[length];
        int cell = reached;
        for (int index = length - 1; index >= 0; index--) {
            path[index] = cell;
            cell = previous[cell];
        }
        return path;
    }

    public static boolean containsNameToken(String text, String name) {
        if (text == null || name == null || name.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase(java.util.Locale.ROOT);
        String needle = name.toLowerCase(java.util.Locale.ROOT);
        for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + 1)) {
            int end = index + needle.length();
            boolean leftBoundary = index == 0 || !isUsernameCharacter(haystack.charAt(index - 1));
            boolean rightBoundary = end == haystack.length() || !isUsernameCharacter(haystack.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUsernameCharacter(char character) {
        return character == '_' || Character.isLetterOrDigit(character);
    }

    public static void main(String[] args) {
        MineNavigation.selfTest();
        AttackResumeGate watchdog = new AttackResumeGate();
        assert !watchdog.watchdog(2, true, true, 0, 0);
        for (int tick = 1; tick < 100; tick++) {
            assert !watchdog.watchdog(2, true, true, 0, tick * 50_000_000L);
        }
        assert watchdog.watchdog(2, true, true, 0, 5_000_000_000L) : "Rearm stalled breaking at five seconds";
        assert watchdog.afterTick(false, true);
        assert !watchdog.afterTick(false, true) : "Release must last only one game tick";
        assert !watchdog.watchdog(2, true, true, 100, 10_000_000_000L) : "Do not interrupt healthy mining";
        assert watchdog.watchdog(2, true, false, 200, 15_000_000_000L) : "Repair a missing held input";
        assert watchdog.afterTick(false, true);
        assert !watchdog.watchdog(2, false, false, 200, 20_000_000_000L) : "Never force protected-block attacks";
        assert !watchdog.watchdog(0, true, false, 200, 25_000_000_000L) : "No watchdog during menus/utility/recovery";
        assert !watchdog.watchdog(1, true, false, 0, 30_000_000_000L) : "New owner gets a fresh observation window";
        AttackResumeGate attackGate = new AttackResumeGate();
        boolean attackDown = false;
        int missTime = 0;
        // A lagging merchant screen stays open, then closes during END_CLIENT_TICK.
        for (int tick = 0; tick < 12; tick++) {
            boolean screenAtStart = tick < 10;
            attackGate.beforeTick(screenAtStart);
            if (screenAtStart) {
                missTime = 10000;
            } else if (!attackDown) {
                missTime = 0; // Vanilla continueAttack(false).
            }
            boolean resumed = tick >= 9;
            attackDown = resumed; // The macro reasserts its held input at END.
            if (attackGate.afterTick(tick < 9, resumed)) {
                attackDown = false;
            }
            if (tick == 9) assert !attackDown : "Resume must leave attack released for a tick";
            if (tick >= 10) assert attackDown && missTime == 0 : "Breaking must resume after GUI close";
        }
        attackGate.beforeTick(true);
        assert !attackGate.afterTick(false, false) : "Do not change manual or egg-hatcher input";
        attackGate.beforeTick(false);
        assert !attackGate.afterTick(false, true) : "No stale rearm after a stopped workflow";
        assert AttackResumeGate.allowUncapturedAttack(true, false, false, true, true, true);
        assert !AttackResumeGate.allowUncapturedAttack(false, false, false, true, true, true) : "Idle/paused/recovery";
        assert !AttackResumeGate.allowUncapturedAttack(true, true, false, true, true, true) : "GUI or overlay";
        assert !AttackResumeGate.allowUncapturedAttack(true, false, true, true, true, true) : "Merchant/egg input";
        assert !AttackResumeGate.allowUncapturedAttack(true, false, false, false, true, true) : "Unfinished inventory";
        assert !AttackResumeGate.allowUncapturedAttack(true, false, false, true, false, true) : "Released attack";
        assert !AttackResumeGate.allowUncapturedAttack(true, false, false, true, true, false) : "Protected target or wrong tool";
        // Reproduce a merchant menu closing while unfocused: vanilla leaves the
        // mouse uncaptured, so keyAttack alone never reaches continueDestroyBlock.
        AttackResumeGate backgroundGate = new AttackResumeGate();
        boolean captured = false;
        attackDown = false;
        missTime = 0;
        int backgroundBreaks = 0;
        for (int tick = 0; tick < 100; tick++) {
            boolean screenAtStart = tick < 10;
            backgroundGate.beforeTick(screenAtStart);
            if (tick == 60) {
                captured = true;
                missTime = 10000; // Vanilla grabMouse on returning to the game.
                backgroundGate.rearm();
            }
            if (screenAtStart) missTime = 10000;
            else {
                boolean continued = attackDown && (captured || AttackResumeGate.allowUncapturedAttack(
                    true, false, false, true, attackDown, true));
                if (!continued) missTime = 0;
                else if (missTime == 0) backgroundBreaks++;
            }
            boolean resumed = tick >= 9;
            attackDown = resumed;
            if (backgroundGate.afterTick(tick < 9, resumed)) attackDown = false;
            if (tick == 59) assert backgroundBreaks == 49 : "Mining must continue without mouse capture after Merchant";
            if (tick == 61) assert missTime == 0 && attackDown : "Recapturing the mouse must rearm once";
        }
        assert backgroundBreaks == 87 : "Resume safely through both background menu close and refocus";
        PetOpenerLogic.selfTest();
        MerchantRestockLogic.selfTest();
        HotbarTransfer.selfTest();
        ExclusionZone.selfTest();
        TracedRoute.selfTest();
        MiningHealth.selfTest();
        MovementPreset.selfTest();
        CoverageMemory.selfTest();
        HotbarGuard.selfTest();
        MapRaster.selfTest();
        PlotScannerLogic.selfTest();
        // Regression: brief mine descent taps must leave clearance after all
        // remaining vertical momentum, including faster server flight speeds.
        for (double flyingSpeed : new double[] {0.05, 0.1, 0.2}) {
            for (double height : new double[] {0.35, 0.8, 1.35, 1.8, 3.5}) {
                double y = MineLayout.FLOOR_Y + 1 + height;
                double velocity = -0.02;
                int taps = 0;
                boolean previousTap = false;
                for (int age = 1; age <= MineLayout.FLIGHT_SETTLE_TICKS + 20; age++) {
                    if (MineLayout.readyToMine(age, y - (MineLayout.FLOOR_Y + 1), velocity)) {
                        break;
                    }
                    boolean tap = MineLayout.descentTap(age, y, velocity, flyingSpeed);
                    assert !tap || !previousTap : "Mine descent must not hold Shift";
                    if (tap) {
                        taps++;
                        velocity -= flyingSpeed * 3.0;
                    }
                    y += velocity;
                    velocity *= 0.6;
                    assert y >= MineLayout.FLOOR_Y + 1.29 : "Mine descent grounded the player";
                    previousTap = tap;
                }
                assert taps <= 10;
                assert MineLayout.lowFlightHeight(y);
            }
        }
        assert !MineLayout.lowFlightHeight(MineLayout.FLOOR_Y + 1);
        assert !MineLayout.lowFlightHeight(MineLayout.FLOOR_Y + 3);
        assert !MineLayout.descentTapForClearance(4, Double.NaN, 0, 0.05);
        assert !MineLayout.descentTapForClearance(4, 0.5, -0.1, 0.2);
        assert Math.abs(FlightMotion.easeYaw(179, new Vec2(Math.sin(Math.toRadians(179)),
            Math.cos(Math.toRadians(179))), 0.5F) - 180) < 0.001;
        FlightMotion sharedMotion = new FlightMotion(new java.util.Random(27));
        double lastOffset = 0;
        for (int tick = 0; tick < 2000; tick++) {
            double offset = sharedMotion.wander(tick, 25);
            assert Math.abs(offset) <= 0.60 && Math.abs(offset - lastOffset) <= 0.0241;
            assert Math.abs(sharedMotion.pitchOffset(tick, true)) <= 2.5;
            lastOffset = offset;
        }
        for (int tick = 2000; tick < 2400; tick++) {
            sharedMotion.wander(tick, 0);
            sharedMotion.pitchOffset(tick, false);
        }
        assert Math.abs(sharedMotion.routeOffset) < 0.001 : "Disabling variation must settle the route offset";
        assert Math.abs(sharedMotion.pitchOffset(2400, false)) < 0.001;
        Vec2[] mineOutline = roundedRectangle(328.5, 394.5, 1535.5, 1601.5, 12, 6);
        for (MineLayout.MinePattern pattern : MineLayout.MinePattern.values()) {
            for (int seed = 0; seed < 20; seed++) {
                Vec2[] path = MineLayout.patternPath(pattern, mineOutline, new java.util.Random(seed));
                assert path.length >= 8;
                assert pathInside(path, 328.5, 394.5, 1535.5, 1601.5) : pattern;
                if (seed == 0) {
                    for (int direction : new int[] {-1, 1}) {
                        for (double speed : new double[] {0.4, 0.7, 1.0}) {
                            checkMineLoop(path, direction, speed, pattern);
                        }
                    }
                }
            }
        }
        assert smootherstep(-1.0) == 0.0;
        assert Math.abs(smootherstep(0.5) - 0.5) < 1.0E-9;
        assert smootherstep(2.0) == 1.0;
        assert Math.abs(signedAngle(new Vec2(1.0, 0.0), new Vec2(0.0, 1.0)) - Math.PI / 2.0) < 1.0E-9;
        assert Math.abs(signedAngle(new Vec2(1.0, 0.0), new Vec2(0.0, -1.0)) + Math.PI / 2.0) < 1.0E-9;
        RouteProgress route = routeProgress(new Vec2(4.0, 2.0), new Vec2(0.0, 0.0), new Vec2(10.0, 0.0), 3.0);
        assert Math.abs(route.progress - 0.4) < 1.0E-9;
        assert Math.abs(route.crossTrack - 2.0) < 1.0E-9;
        assert Math.abs(route.lookPoint.x - 7.0) < 1.0E-9;
        assert Math.abs(route.remaining - 6.0) < 1.0E-9;
        Vec2 cornerLook = polylineLookPoint(new Vec2(8.0, 0.0), new Vec2(0.0, 0.0),
            new Vec2(10.0, 0.0), new Vec2(10.0, 10.0), 4.0);
        assert Math.abs(cornerLook.x - 10.0) < 1.0E-9 && Math.abs(cornerLook.z - 2.0) < 1.0E-9;
        Vec2 multiCornerLook = polylineLookPoint(new Vec2(8.0, 0.0), new Vec2(0.0, 0.0),
            new Vec2(10.0, 0.0), java.util.List.of(new Vec2(10.0, 2.0), new Vec2(10.0, 10.0)), 6.0);
        assert Math.abs(multiCornerLook.x - 10.0) < 1.0E-9
            && Math.abs(multiCornerLook.z - 4.0) < 1.0E-9;
        assert !shouldAdvanceSegment(new Vec2(8.0, 1.0), new Vec2(0.0, 0.0),
            new Vec2(10.0, 0.0), new Vec2(10.0, 10.0));
        assert shouldAdvanceSegment(new Vec2(9.0, 1.0), new Vec2(0.0, 0.0),
            new Vec2(10.0, 0.0), new Vec2(10.0, 10.0));
        assert shouldAdvanceSegment(new Vec2(11.0, -2.0), new Vec2(0.0, 0.0),
            new Vec2(10.0, 0.0), new Vec2(10.0, 10.0));
        TurnCalibration turn = calibrateTurn(0.45, 42);
        assert turn.forwardExcursion > 7.0 && turn.forwardExcursion < 8.5;
        assert turn.lateralDisplacement > 7.0 && turn.lateralDisplacement < 8.5;
        assert calibrateTurn(0.30, 42).forwardExcursion < turn.forwardExcursion;
        TurnCalibration strafedTurn = calibrateTurn(0.45, 30, 1.0);
        assert strafedTurn.forwardExcursion < calibrateTurn(0.45, 30, 0.0).forwardExcursion;
        Vec2[] boundedTurn = forecastTurn(new Vec2(20.0 - strafedTurn.forwardExcursion, 10.0),
            new Vec2(1.0, 0.0), new Vec2(1.0, 0.0), new Vec2(1.0, 0.0),
            Math.PI, 0.45, 30, 0, 1.0);
        assert pathInside(boundedTurn, 0.0, 20.000_001, 0.0, 20.0);
        Vec2[] tooLateTurn = forecastTurn(new Vec2(20.1 - strafedTurn.forwardExcursion, 10.0),
            new Vec2(1.0, 0.0), new Vec2(1.0, 0.0), new Vec2(1.0, 0.0),
            Math.PI, 0.45, 30, 0, 1.0);
        assert !pathInside(tooLateTurn, 0.0, 20.0, 0.0, 20.0);
        Vec2[] oppositeTurn = forecastTurn(new Vec2(20.0 - strafedTurn.forwardExcursion, 10.0),
            new Vec2(1.0, 0.0), new Vec2(1.0, 0.0), new Vec2(1.0, 0.0),
            -Math.PI, 0.45, 30, 0, 1.0);
        assert pathInside(oppositeTurn, 0.0, 20.000_001, 0.0, 20.0);
        assert Math.abs(boundedTurn[boundedTurn.length - 1].z()
            + oppositeTurn[oppositeTurn.length - 1].z() - 20.0) < 1.0E-9;
        Vec2[] obstacleBounce = forecastTurn(new Vec2(0.0, 0.0),
            new Vec2(1.0, 0.0), new Vec2(1.0, 0.0), new Vec2(1.0, 0.0),
            Math.toRadians(135.0), 0.45, 14, 0, 1.0);
        assert obstacleBounce[obstacleBounce.length - 1].x() < 0.0
            && obstacleBounce[obstacleBounce.length - 1].z() > 4.0;
        Vec2 almostReverse = new Vec2(-1.0, 0.1).normalized();
        assert directedTurnAngle(new Vec2(1.0, 0.0), almostReverse, 1) > 0.0;
        assert directedTurnAngle(new Vec2(1.0, 0.0), almostReverse, -1) < 0.0;
        assert pathInside(new Vec2[]{new Vec2(1.0, 1.0), new Vec2(2.0, 2.0)}, 0.0, 3.0, 0.0, 3.0);
        assert !pathInside(new Vec2[]{new Vec2(1.0, 1.0), new Vec2(4.0, 2.0)}, 0.0, 3.0, 0.0, 3.0);
        Vec2[] closed = startClosedPathNear(new Vec2(9.0, 9.0), new Vec2[]{
            new Vec2(0.0, 0.0), new Vec2(10.0, 0.0), new Vec2(10.0, 10.0), new Vec2(0.0, 10.0)});
        assert closed.length == 5;
        assert closed[0].equals(new Vec2(10.0, 10.0));
        assert closed[0].equals(closed[closed.length - 1]);
        assert pathInside(closed, 0.0, 10.0, 0.0, 10.0);
        boolean[] grid = {
            true, true, false, true, true,
            false, true, false, true, false,
            true, true, true, true, true
        };
        boolean[] goals = new boolean[grid.length];
        goals[4] = true;
        int[] gridPath = shortestGridPath(grid, 5, 0, goals);
        assert gridPath.length == 9;
        assert gridPath[0] == 0 && gridPath[gridPath.length - 1] == 4;
        for (int index = 1; index < gridPath.length; index++) {
            assert grid[gridPath[index]];
            int difference = Math.abs(gridPath[index] - gridPath[index - 1]);
            assert difference == 1 || difference == 5;
        }
        assert shortestGridPath(new boolean[]{true, false, true}, 3, 0,
            new boolean[]{false, false, true}).length == 0;
        Vec2[] hull = convexHull(new Vec2[]{
            new Vec2(0.0, 0.0), new Vec2(10.0, 0.0), new Vec2(10.0, 10.0),
            new Vec2(0.0, 10.0), new Vec2(5.0, 5.0), new Vec2(0.0, 0.0)});
        assert hull.length == 4;
        assert java.util.Arrays.asList(hull).contains(new Vec2(10.0, 10.0));
        assert pathInside(hull, 0.0, 10.0, 0.0, 10.0);
        assert containsNameToken("Hey, CropPilot_7!", "croppilot_7");
        assert !containsNameToken("CropPilot_70 is different", "CropPilot_7");
        assert !containsNameToken("ordinary chat", "CropPilot_7");
        GlowInterceptPlan glow = glowInterceptPlan(new Vec2(5.0, 0.0), new Vec2(0.0, 0.0),
            new Vec2(40.0, 0.0), new Vec2(15.0, 4.0), 5.0, 24.0, 5.5, 8.0);
        assert glow != null;
        assert Math.abs(glow.rejoin.x - 23.0) < 1.0E-9 && glow.rejoin.z == 0.0;
        assert glowInterceptPlan(new Vec2(20.0, 0.0), new Vec2(0.0, 0.0),
            new Vec2(40.0, 0.0), new Vec2(15.0, 1.0), 5.0, 24.0, 5.5, 8.0) == null;
        assert glowInterceptPlan(new Vec2(5.0, 0.0), new Vec2(0.0, 0.0),
            new Vec2(40.0, 0.0), new Vec2(15.0, 6.0), 5.0, 24.0, 5.5, 8.0) == null;
        GlowInterceptPlan polylineGlow = glowInterceptPlan(new Vec2(0.0, 0.0),
            java.util.List.of(new Vec2(5.0, 0.0), new Vec2(5.0, 20.0), new Vec2(20.0, 20.0)),
            new Vec2(8.0, 10.0), 5.0, 24.0, 5.5, 8.0);
        assert polylineGlow != null;
        assert Math.abs(polylineGlow.targetProgress - 15.0) < 1.0E-9;
        assert polylineGlow.rejoin.equals(new Vec2(5.0, 18.0));
        double nearGlowLead = glowActivationDistance(4.0, 0.5, 5.0,
            Math.toRadians(20.0), 18.0, 64.0);
        double farGlowLead = glowActivationDistance(4.0, 0.5, 45.0,
            Math.toRadians(70.0), 18.0, 64.0);
        assert farGlowLead > nearGlowLead && farGlowLead <= 64.0;
        Vec2 directAim = directAimPoint(new Vec2(0.0, 0.0), new Vec2(3.0, 4.0), 2.0);
        assert Math.abs(directAim.x - 4.2) < 1.0E-9;
        assert Math.abs(directAim.z - 5.6) < 1.0E-9;
        Vec2 guideTarget = new Vec2(10.0, 0.0);
        Vec2 guideExit = new Vec2(14.0, 0.0);
        assert glowGuidePoint(new Vec2(8.0, 1.0), guideTarget, guideExit,
            new Vec2(1.0, 0.0)).equals(guideTarget);
        assert glowGuidePoint(new Vec2(11.0, 1.0), guideTarget, guideExit,
            new Vec2(1.0, 0.0)).equals(guideExit);
        assert !passedPoint(new Vec2(2.0, 0.0), new Vec2(3.0, 0.0), new Vec2(1.0, 0.0), 0.5);
        assert passedPoint(new Vec2(4.0, 0.0), new Vec2(3.0, 0.0), new Vec2(1.0, 0.0), 0.5);
        Vec2[] eight = figureEight(new Vec2(10.0, 20.0), 8.0, 4.0, true, 64, 0.0);
        assert eight.length == 64;
        assert pathInside(eight, 2.0, 18.0, 16.0, 24.0);
        assert eight[16].x > 17.9 && Math.abs(eight[16].z - 20.0) < 1.0E-9;
        assert eight[48].x < 2.1 && Math.abs(eight[48].z - 20.0) < 1.0E-9;
        Vec2[] mineLoop = roundedRectangle(2.0, 28.0, 4.0, 24.0, 6.0, 4);
        assert mineLoop.length == 20;
        assert pathInside(mineLoop, 2.0, 28.0, 4.0, 24.0);
        assert mineLoop[0].equals(new Vec2(22.0, 4.0));
    }

    /** Offline eased-input smoke check, not an emulator of server flight physics. */
    private static void checkMineLoop(Vec2[] path, int direction, double speed, MineLayout.MinePattern pattern) {
        Vec2 position = path[0], start = position;
        int index = Math.floorMod(direction, path.length);
        Vec2 heading = path[index].subtract(start).normalized();
        Vec2 velocity = heading.scale(speed);
        float yaw = (float)Math.toDegrees(Math.atan2(-heading.x, heading.z));
        for (int tick = 0; tick < 1200; tick++) {
            for (int advance = 0; advance < 3; advance++) {
                int next = Math.floorMod(index + direction, path.length);
                if (position.subtract(path[index]).length() > 0.85
                    && !shouldAdvanceSegment(position, start, path[index], path[next])) break;
                start = path[index];
                index = next;
            }
            Vec2 look = polylineLookPoint(position, start, path[index], java.util.List.of(
                path[Math.floorMod(index + direction, path.length)],
                path[Math.floorMod(index + direction * 2, path.length)],
                path[Math.floorMod(index + direction * 3, path.length)]), FlightMotion.lookAhead(speed));
            Vec2 desired = look.subtract(position).normalized();
            double error = signedAngle(heading, desired);
            int strafe = Math.abs(error) >= Math.toRadians(18) ? (int)Math.signum(error) : 0;
            heading = FlightMotion.followHeading(heading, desired, 5);
            yaw = FlightMotion.easeYaw(yaw, heading, 0.5F);
            Vec2 input = new Vec2(-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)))
                .rotate(strafe * Math.PI / 4);
            velocity = velocity.scale(0.5).add(input.scale(speed * 0.5));
            position = position.add(velocity);
            assert position.x >= 326 && position.x < 397 && position.z >= 1533 && position.z < 1604
                : "Mine pattern left the safe core: " + pattern + " speed=" + speed;
        }
    }

    public record Vec2(double x, double z) {
        public double length() {
            return Math.hypot(x, z);
        }

        public Vec2 normalized() {
            double length = length();
            return length == 0.0 ? new Vec2(0.0, 0.0) : new Vec2(x / length, z / length);
        }

        public Vec2 rotate(double radians) {
            double cosine = Math.cos(radians);
            double sine = Math.sin(radians);
            return new Vec2(x * cosine - z * sine, x * sine + z * cosine);
        }

        public Vec2 scale(double amount) {
            return new Vec2(x * amount, z * amount);
        }

        public Vec2 add(Vec2 other) {
            return new Vec2(x + other.x, z + other.z);
        }

        public Vec2 subtract(Vec2 other) {
            return new Vec2(x - other.x, z - other.z);
        }

        public double dot(Vec2 other) {
            return x * other.x + z * other.z;
        }
    }

    public record RouteProgress(double progress, double crossTrack, Vec2 lookPoint, double remaining) {
    }

    public record GlowInterceptPlan(Vec2 target, Vec2 rejoin, double targetProgress,
                                    double rejoinProgress, double cost) {
    }

    public record TurnCalibration(double forwardExcursion, double lateralDisplacement) {
    }
}
