package com.salesfarm.croppilot;

/** Five-second progress watchdog, separate from the one-frame vanilla attack rearm. */
final class MiningHealth {
    enum Action { NONE, INPUT, AIM, RECOVER, STOP }
    private int owner;
    private int stalledWindows;
    private long nextCheck;
    private long baseline;
    private long lastBreak;
    private String diagnosis = "Idle";

    void reset() {
        owner = 0;
        stalledWindows = 0;
        lastBreak = 0;
        diagnosis = "Waiting for prepared start";
    }

    Action update(int activeOwner, boolean held, boolean target, boolean tool, long breaks, long now) {
        if (activeOwner == 0) {
            nextCheck = now + 5_000_000_000L;
            return Action.NONE; // Suspend the clock, not the bounded recovery budget.
        }
        if (owner != activeOwner) {
            owner = activeOwner;
            stalledWindows = 0;
            nextCheck = now + 5_000_000_000L;
            baseline = breaks;
            lastBreak = 0;
            diagnosis = "Waiting for first block break";
            return Action.NONE;
        }
        boolean progress = breaks > baseline;
        if (progress) {
            lastBreak = now;
            baseline = breaks;
            stalledWindows = 0;
            diagnosis = "Mining: block-break progress observed";
        }
        if (now < nextCheck) return Action.NONE;
        nextCheck = now + 5_000_000_000L;
        if (lastBreak != 0 && now - lastBreak < 5_000_000_000L && tool && held) return Action.NONE;
        stalledWindows++;
        if (stalledWindows >= 4) {
            diagnosis = "Stopped: recovery could not restore block breaking";
            return Action.STOP;
        }
        if (stalledWindows >= 2) {
            diagnosis = "Recovering a safe route after stalled mining";
            return Action.RECOVER;
        }
        if (!tool || !held) {
            diagnosis = !tool ? "Restoring slot-1 tool and attack input" : "Rearming released attack input";
            return Action.INPUT;
        }
        diagnosis = target ? "No breaks: resetting attack and aim" : "No reachable target: correcting aim";
        return Action.AIM;
    }

    String status() { return diagnosis; }
    String breakAge(long now) {
        return lastBreak == 0 ? "No break observed yet" : String.format(java.util.Locale.ROOT,
            "Last observed break %.1fs ago", Math.max(0, now - lastBreak) / 1e9);
    }

    static void selfTest() {
        MiningHealth health = new MiningHealth();
        assert health.update(1, true, true, true, 0, 1) == Action.NONE;
        assert health.update(1, false, true, true, 0, 5_000_000_001L) == Action.INPUT;
        assert health.update(1, true, true, true, 1, 6_000_000_001L) == Action.NONE;
        assert health.update(1, true, true, true, 1, 10_000_000_001L) == Action.NONE;
        assert health.update(1, true, false, true, 1, 15_000_000_001L) == Action.AIM;
        assert health.update(1, true, false, true, 1, 20_000_000_001L) == Action.RECOVER;
        assert health.update(1, true, false, true, 1, 25_000_000_001L) == Action.RECOVER;
        assert health.update(1, true, false, true, 1, 30_000_000_001L) == Action.STOP;
        assert health.update(0, false, false, false, 1, 31_000_000_001L) == Action.NONE;
        assert health.update(1, true, true, true, 1, 36_000_000_001L) == Action.STOP : "Recovery/GUI pauses must not erase repeated failures";
        assert health.update(2, true, true, true, 1, 90_000_000_001L) == Action.NONE;
        health.reset();
        assert health.update(2, true, true, true, 1, 100_000_000_001L) == Action.NONE;
    }
}
