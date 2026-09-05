package com.salesfarm.croppilot;

/** Optional variation only; clearance, turn limits and the movement predictor stay unchanged. */
public enum MovementPreset {
    CAUTIOUS("Cautious", 0.18, 0.8F, 0.45, 0.0),
    BALANCED("Balanced", 0.36, 1.6F, 1.0, 0.0),
    COVERAGE("Coverage", 0.26, 1.1F, 0.75, 1.0);

    public final String label;
    private final double drift, variation, coverage;
    private final float pitch;

    MovementPreset(String label, double drift, float pitch, double variation, double coverage) {
        this.label = label;
        this.drift = drift;
        this.pitch = pitch;
        this.variation = variation;
        this.coverage = coverage;
    }

    public String label() { return label; }
    public MovementPreset offset(int amount) {
        return values()[Math.floorMod(ordinal() + Math.floorMod(amount, values().length), values().length)];
    }
    public double driftLimit() { return drift; }
    public float pitchLimit() { return pitch; }
    public double variationScale() { return variation; }
    public double coverageWeight() { return coverage; }

    public static void selfTest() {
        for (MovementPreset preset : values()) {
            assert preset.offset(1).offset(-1) == preset;
            assert preset.offset(Integer.MAX_VALUE) != null && preset.offset(Integer.MIN_VALUE) != null;
            var motion = new FlightMotion(new java.util.Random(27));
            double previous = 0;
            float previousPitch = 0;
            for (int tick = 0; tick < 4_000; tick++) {
                double drift = motion.wander(tick, 15, preset, 1);
                float pitch = motion.pitchOffset(tick, true, preset, 1);
                assert Math.abs(drift) <= preset.driftLimit();
                assert Math.abs(drift - previous) <= 0.025 : "Drift must ease coherently";
                assert Math.abs(pitch) <= preset.pitchLimit();
                assert Math.abs(pitch - previousPitch) <= 0.15 : "Pitch must ease coherently";
                previous = drift;
                previousPitch = pitch;
            }
            assert motion.wander(4_001, 15, preset, 0) == 0 : "No drift near hazards/targets";
            assert motion.pitchOffset(4_001, true, preset, 0) == 0;
            assert motion.wander(4_002, 0, preset, 1) == 0 : "Natural movement off";
        }
        assert CAUTIOUS.driftLimit() < BALANCED.driftLimit();
        assert COVERAGE.coverageWeight() > BALANCED.coverageWeight();
        assert Float.isNaN(FlightMotion.boundedMiningPitch(-45, 8)) : "Never snap from looking upward";
        assert FlightMotion.boundedMiningPitch(60, 100) == 68;
        assert FlightMotion.boundedMiningPitch(60, -100) == 52;
    }
}
