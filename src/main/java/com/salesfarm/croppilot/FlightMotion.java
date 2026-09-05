package com.salesfarm.croppilot;

import java.util.Random;

/** The farm's movement easing, also used by the mine. No simulated input packets. */
final class FlightMotion {
    private final Random random;
    private int nextWanderTick;
    private int nextPitchChangeTick;
    private float pitchOffset;
    private float targetPitchOffset;
    double routeOffset;
    double targetRouteOffset;

    FlightMotion(Random random) {
        this.random = random;
    }

    void scheduleWander(int ticks) {
        nextWanderTick = ticks + 100 + random.nextInt(161);
    }

    void schedulePitchChange(int ticks) {
        nextPitchChangeTick = ticks + 70 + random.nextInt(111);
    }

    double wander(int ticks, double jitterDegrees) {
        return wander(ticks, jitterDegrees, MovementPreset.BALANCED, 1.0);
    }

    double wander(int ticks, double jitterDegrees, MovementPreset preset, double room) {
        double maximum = Math.min(preset.driftLimit(), Math.max(0, jitterDegrees) * 0.04)
            * Math.clamp(room, 0, 1);
        if (ticks >= nextWanderTick) {
            targetRouteOffset = maximum == 0.0 ? 0.0
                : (random.nextBoolean() ? 1.0 : -1.0) * random.nextDouble() * maximum;
            scheduleWander(ticks);
        }
        targetRouteOffset = Math.clamp(targetRouteOffset, -maximum, maximum);
        routeOffset += (targetRouteOffset - routeOffset) * 0.02;
        routeOffset = Math.clamp(routeOffset, -maximum, maximum);
        return routeOffset;
    }

    float pitchOffset(int ticks, boolean natural) {
        return pitchOffset(ticks, natural, MovementPreset.BALANCED, 1.0);
    }

    float pitchOffset(int ticks, boolean natural, MovementPreset preset, double room) {
        float maximum = natural ? preset.pitchLimit() * (float)Math.clamp(room, 0, 1) : 0;
        if (natural && ticks >= nextPitchChangeTick) {
            targetPitchOffset = (float)(random.nextDouble() * 2.0 - 1.0) * maximum;
            schedulePitchChange(ticks);
        }
        targetPitchOffset = Math.clamp(targetPitchOffset, -maximum, maximum);
        pitchOffset += (targetPitchOffset - pitchOffset) * 0.035F;
        pitchOffset = Math.clamp(pitchOffset, -maximum, maximum);
        return pitchOffset;
    }

    static float pitchJitter(int ticks, boolean natural) {
        return natural ? (float)Math.sin(ticks * 0.09) * 0.35F : 0.0F;
    }

    static float easePitch(float current, float target) {
        return current + (target - current) * 0.35F;
    }

    static float boundedMiningPitch(float current, float change) {
        float target = current + Math.clamp(change, -8.0F, 8.0F);
        return target >= 35 && target <= 89 ? target : Float.NaN;
    }

    static float easeYaw(float current, MotionMath.Vec2 heading, float response) {
        float target = (float)Math.toDegrees(Math.atan2(-heading.x(), heading.z()));
        double delta = ((target - current) % 360.0 + 540.0) % 360.0 - 180.0;
        return current + (float)delta * response;
    }

    static MotionMath.Vec2 followHeading(MotionMath.Vec2 heading, MotionMath.Vec2 desired, double maximumDegrees) {
        double error = MotionMath.signedAngle(heading, desired);
        double maximum = Math.toRadians(maximumDegrees);
        return heading.rotate(Math.clamp(error * 0.20, -maximum, maximum)).normalized();
    }

    static double lookAhead(double speed) {
        return Math.clamp(speed * 12.0, 3.0, 6.0);
    }

    static int nextPatternTick(int ticks, Random random) {
        return ticks + 1_200 + random.nextInt(1_201);
    }
}
