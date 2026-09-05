package com.salesfarm.croppilot;

/** Gives vanilla one screen-free tick with attack released after a menu closes. */
final class AttackResumeGate {
    private boolean releasePending;
    private int watchdogOwner;
    private long nextCheckNanos;
    private long baselineBreaks;

    void rearm() { releasePending = true; }

    static boolean allowUncapturedAttack(boolean active, boolean uiOpen, boolean utility,
                                        boolean inventoryReady, boolean held, boolean safeTarget) {
        return active && !uiOpen && !utility && inventoryReady && held && safeTarget;
    }

    /** Check every five seconds; only rearm when a safe mining input needs it. */
    boolean watchdog(int owner, boolean attackPermitted, boolean attackHeld, long breaks, long nowNanos) {
        if (owner == 0 || owner != watchdogOwner) {
            watchdogOwner = owner;
            baselineBreaks = breaks;
            nextCheckNanos = nowNanos + 5_000_000_000L;
            return false;
        }
        if (nowNanos < nextCheckNanos) return false;
        boolean stalled = breaks <= baselineBreaks;
        baselineBreaks = breaks;
        nextCheckNanos = nowNanos + 5_000_000_000L;
        if (attackPermitted && (!attackHeld || stalled)) {
            releasePending = true;
            return true;
        }
        return false;
    }

    void beforeTick(boolean screenOpen) {
        releasePending |= screenOpen;
    }

    boolean afterTick(boolean screenOpen, boolean breakingMacroRunning) {
        releasePending |= screenOpen;
        boolean release = releasePending && breakingMacroRunning;
        if (!screenOpen) {
            releasePending = false;
        }
        return release;
    }
}
