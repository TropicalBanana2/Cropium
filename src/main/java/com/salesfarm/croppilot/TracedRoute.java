package com.salesfarm.croppilot;

import com.salesfarm.croppilot.MotionMath.Vec2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/** Saved freehand strokes. Compiled geometry is derived, never trusted from disk. */
public record TracedRoute(String id, String name, String world, String module, String map,
                          List<Vec2> stroke) {
    public static final int MAX_ROUTES = 32, MAX_POINTS = 256;
    public record Shape(List<Vec2> points, String problem) {
        public boolean valid() { return problem == null; }
    }

    public boolean valid() {
        return id != null && !id.isBlank() && id.length() <= 64 && name != null && !name.isBlank()
            && name.length() <= 40 && world != null && !world.isBlank() && world.length() <= 512
            && ("mine".equals(module) || "harvest".equals(module)) && map != null && map.length() <= 128
            && stroke != null && stroke.size() >= 2 && stroke.size() <= MAX_POINTS
            && stroke.stream().allMatch(p -> p != null && Double.isFinite(p.x()) && Double.isFinite(p.z())
                && Math.abs(p.x()) <= 30_000_000 && Math.abs(p.z()) <= 30_000_000);
    }

    public boolean matches(String world, String module, String map) {
        return valid() && this.world.equals(world) && this.module.equals(module) && this.map.equals(map);
    }

    public Shape compile() { return valid() ? smooth(stroke) : new Shape(List.of(), "Invalid saved drawing"); }

    public static Shape smooth(List<Vec2> stroke) {
        if (stroke == null || stroke.size() < 2 || stroke.size() > MAX_POINTS
            || stroke.stream().anyMatch(p -> p == null || !Double.isFinite(p.x()) || !Double.isFinite(p.z())))
            return new Shape(List.of(), "Draw a longer line first");
        double length = 0;
        for (int i = 1; i < stroke.size(); i++) length += stroke.get(i).subtract(stroke.get(i - 1)).length();
        length += stroke.getLast().subtract(stroke.getFirst()).length();
        if (length < 30 || length > 1200) return new Shape(List.of(), "Use a loop between 30 and 1200 blocks long, including its closing line");
        // Smooth only the drawing, preserving its start and release point. All loop
        // consumers connect the last vertex directly to the first, with no return lane.
        List<Vec2> line = chaikin(chaikin(resample(stroke, 4)));
        List<Vec2> path = resample(line, 2);
        if (path.size() > 1 && path.getLast().equals(path.getFirst())) path.removeLast();
        double area = 0;
        for (int i = 0; i < path.size(); i++) {
            var a = path.get(i).subtract(path.getFirst());
            var b = path.get((i + 1) % path.size()).subtract(path.getFirst());
            area += a.x() * b.z() - b.x() * a.z();
        }
        if (path.size() < 8 || path.size() > 640 || Math.abs(area) < 40)
            return new Shape(path, "This loop is too small or folded back on itself");
        return new Shape(List.copyOf(path), null);
    }

    private static List<Vec2> chaikin(List<Vec2> source) {
        List<Vec2> result = new ArrayList<>();
        result.add(source.getFirst());
        for (int i = 0; i < source.size() - 1; i++) {
            var a = source.get(i); var b = source.get(i + 1);
            result.add(a.scale(.75).add(b.scale(.25)));
            result.add(a.scale(.25).add(b.scale(.75)));
        }
        result.add(source.getLast());
        return result;
    }

    private static List<Vec2> resample(List<Vec2> source, double spacing) {
        List<Vec2> result = new ArrayList<>();
        result.add(source.getFirst());
        double remaining = spacing;
        for (int i = 0; i < source.size() - 1; i++) {
            Vec2 a = source.get(i), b = source.get(i + 1);
            double distance = b.subtract(a).length();
            while (distance >= remaining && distance > .0001) {
                a = a.add(b.subtract(a).scale(remaining / distance));
                result.add(a); distance = b.subtract(a).length(); remaining = spacing;
            }
            remaining -= distance;
        }
        if (result.getLast().subtract(source.getLast()).length() < .001) result.set(result.size() - 1, source.getLast());
        else result.add(source.getLast());
        return result;
    }

    /** Includes segment interiors, smoothing excursions and the entire straight closing line. */
    public static Vec2 firstUnsafe(List<Vec2> path, Predicate<Vec2> safe) {
        for (int i = 0; i < path.size(); i++) {
            var a = path.get(i); var b = path.get((i + 1) % path.size());
            int steps = Math.max(1, (int)Math.ceil(b.subtract(a).length() / .5));
            for (int j = 0; j <= steps; j++) {
                var p = a.add(b.subtract(a).scale(j / (double)steps));
                if (!safe.test(p)) return p;
            }
        }
        return null;
    }

    public static List<TracedRoute> choices(List<TracedRoute> routes, String world, String module,
                                            String map, String previous, Random random) {
        List<TracedRoute> result = new ArrayList<>(routes.stream().filter(r -> r.matches(world, module, map)).toList());
        if (result.size() > 1) result.removeIf(r -> r.id.equals(previous));
        Collections.shuffle(result, random);
        return result;
    }

    /** One aspect-preserving transform for both drawing and rendering (no half-block offset). */
    public record View(double minX, double minZ, double spanX, double spanZ,
                       double left, double top, double width, double height) {
        public double scale() { return Math.min(width / spanX, height / spanZ); }
        public double originX() { return left + (width - spanX * scale()) / 2; }
        public double originZ() { return top + (height - spanZ * scale()) / 2; }
        public Vec2 screen(Vec2 p) { return new Vec2(originX() + (p.x() - minX) * scale(), originZ() + (p.z() - minZ) * scale()); }
        public Vec2 world(double x, double y) { return new Vec2(minX + (x - originX()) / scale(), minZ + (y - originZ()) / scale()); }
        public boolean contains(double x, double y) {
            var p = world(x, y);
            return p.x() >= minX && p.z() >= minZ && p.x() < minX + spanX && p.z() < minZ + spanZ;
        }
    }

    static void selfTest() {
        var square = List.of(new Vec2(335, 1542), new Vec2(386, 1542), new Vec2(386, 1594),
            new Vec2(335, 1594), new Vec2(335, 1542));
        var loop = smooth(square);
        assert loop.valid() && loop.points.size() > 8;
        assert firstUnsafe(loop.points, p -> p.x() >= 335 && p.x() <= 386 && p.z() >= 1542 && p.z() <= 1594) == null;
        assert !smooth(List.of(new Vec2(0, 0), new Vec2(2, 0))).valid();
        assert !smooth(List.of(new Vec2(0, 0), new Vec2(60, 0))).valid() : "A straight stroke alone has no loop area";
        var unfinished = square.subList(0, 4);
        var autoClosed = smooth(unfinished);
        assert autoClosed.valid() : "Release more than 12 blocks from the start is allowed";
        assert autoClosed.points.getFirst().equals(unfinished.getFirst());
        assert autoClosed.points.getLast().equals(unfinished.getLast()) : "Closing line starts at the release point";
        assert firstUnsafe(autoClosed.points, p -> !(p.x() < 336 && p.z() > 1560 && p.z() < 1565)) != null
            : "Obstacle on the new closing line must reject the whole loop";
        assert firstUnsafe(autoClosed.points, p -> p.x() >= 335 && p.x() <= 386 && p.z() >= 1542 && p.z() <= 1594) == null
            : "No offset return lane or extra turnaround outside the drawing";
        var diagonal = smooth(square.subList(0, 3));
        assert diagonal.valid() && diagonal.points.getFirst().equals(square.getFirst())
            && diagonal.points.getLast().equals(square.get(2)) : "Diagonal closing chord preserves both endpoints";
        assert firstUnsafe(List.of(new Vec2(0, 0), new Vec2(10, 0)), p -> p.x() < 4 || p.x() > 6) != null;
        for (double width : new double[]{180, 550}) for (double height : new double[]{130, 240}) {
            var view = new View(320, 1527, 83, 83, 113, 71, width, height);
            var p = new Vec2(351.275, 1560.875); var pixel = view.screen(p);
            assert view.world(pixel.x(), pixel.z()).subtract(p).length() < 1e-8;
            assert !view.contains(view.originX() - 1, view.originZ());
        }
        var a = new TracedRoute("a", "One", "world", "mine", "layout", square);
        var b = new TracedRoute("b", "Two", "world", "mine", "layout", square);
        assert choices(List.of(a, b), "world", "mine", "layout", "a", new Random(1)).equals(List.of(b));
        assert choices(List.of(a), "world", "mine", "layout", "a", new Random(1)).equals(List.of(a));
        assert choices(List.of(a, b), "other", "mine", "layout", null, new Random(1)).isEmpty();
        assert choices(List.of(a), "world", "harvest", "layout", null, new Random(1)).isEmpty();
        assert choices(List.of(a), "world", "mine", "different bounds", null, new Random(1)).isEmpty();
        var roundTrip = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(a), TracedRoute.class);
        assert roundTrip.equals(a) && roundTrip.compile().valid() : "Saved names, points and world round-trip";
        var legacyJson = new com.google.gson.Gson().toJsonTree(a).getAsJsonObject();
        legacyJson.addProperty("mode", "RETURN_RIGHT");
        assert new com.google.gson.Gson().fromJson(legacyJson, TracedRoute.class).equals(a)
            : "Old return-mode saves retain their strokes and become closed loops";
        // Exercise actual following through the straight closure in both directions.
        for (Shape shape : List.of(loop, autoClosed, diagonal)) {
            for (int direction : new int[]{1, -1}) for (double speed : new double[]{.4, .8, 1.0}) {
                List<Vec2> course = new ArrayList<>();
                for (int i = 0; i < shape.points.size() * 8; i++) course.add(shape.points.get(Math.floorMod(i * direction, shape.points.size())));
                Vec2 h = course.get(1).subtract(course.getFirst()).normalized();
                var pose = new MineNavigation.Pose(course.getFirst(), h.scale(speed), h,
                    (float)Math.toDegrees(Math.atan2(-h.x(), h.z())));
                var forecast = MineNavigation.forecast(pose, course, speed, .7, null, null, 800,
                    (from, to) -> to.x() >= 327 && to.x() < 396 && to.z() >= 1534 && to.z() < 1603);
                assert forecast.clear() : "Smoothed path must fly safely: " + direction + " / " + speed;
            }
        }
    }
}
