package com.salesfarm.croppilot;

import com.salesfarm.croppilot.MotionMath.Vec2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;

/** Bounded movement-only diagnostics. No chat, account data, or inventory contents. */
final class MineTrace {
    private final ArrayDeque<String> samples = new ArrayDeque<>();
    private final ArrayDeque<String> events = new ArrayDeque<>();

    void sample(long tick, MineNavigation.Pose pose, MineNavigation.Input input, double speed, double inertia) {
        var predicted = MineNavigation.step(pose, input, speed, inertia);
        samples.add(String.join(",", "" + tick, "" + pose.position().x(), "" + pose.position().z(),
            "" + pose.velocity().x(), "" + pose.velocity().z(), "" + input.heading().x(), "" + input.heading().z(),
            "" + input.yaw(), "" + input.strafe(), "" + speed, "" + inertia,
            "" + predicted.position().x(), "" + predicted.position().z()));
        while (samples.size() > 2400) samples.removeFirst();
    }

    void event(long tick, String text) {
        events.addFirst(tick + "t | " + text.replace('\n', ' ').replace('\r', ' '));
        while (events.size() > 20) events.removeLast();
    }

    List<String> events() { return List.copyOf(events); }
    void clear() { samples.clear(); events.clear(); }

    void write(Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        StringBuilder csv = new StringBuilder("# Cropium mine trace v1; predicted steps are approximate, not server ground truth\n");
        for (String event : events) csv.append("# ").append(event).append('\n');
        csv.append("tick,x,z,vx,vz,hx,hz,yaw,strafe,speed,inertia,predictedX,predictedZ\n");
        for (String sample : samples) csv.append(sample).append('\n');
        Files.writeString(destination, csv);
    }

    /** Recheck exported input-model decisions after a math change. */
    static int replay(Path source) throws IOException {
        int checked = 0;
        for (String line : Files.readAllLines(source)) {
            if (line.startsWith("#") || line.startsWith("tick,") || line.isBlank()) continue;
            String[] cells = line.split(",");
            if (cells.length != 13) throw new IOException("Invalid mine trace row");
            double[] n = java.util.Arrays.stream(cells).mapToDouble(Double::parseDouble).toArray();
            if (java.util.Arrays.stream(n).anyMatch(v -> !Double.isFinite(v))) throw new IOException("Non-finite trace value");
            var pose = new MineNavigation.Pose(new Vec2(n[1], n[2]), new Vec2(n[3], n[4]), new Vec2(n[5], n[6]), (float)n[7]);
            var input = new MineNavigation.Input(pose.heading(), pose.yaw(), (int)n[8]);
            Vec2 predicted = MineNavigation.step(pose, input, n[9], n[10]).position();
            if (n[8] < -1 || n[8] > 1 || n[9] <= 0 || n[9] > 3 || n[10] < 0 || n[10] >= 1
                || predicted.x() < MineLayout.MIN_X + 5 || predicted.x() >= MineLayout.MAX_X - 4
                || predicted.z() < MineLayout.MIN_Z + 5 || predicted.z() >= MineLayout.MAX_Z - 4) {
                throw new IOException("Unsafe input or edge crossing at tick " + cells[0]);
            }
            if (predicted.subtract(new Vec2(n[11], n[12])).length() > 0.0001) {
                throw new IOException("Prediction changed at tick " + cells[0]);
            }
            checked++;
        }
        return checked;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Pass the exported mine CSV path");
        System.out.println("Replayed " + replay(Path.of(args[0])) + " mine input samples");
    }
}
