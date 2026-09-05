package com.salesfarm.croppilot;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/** Shared detached view; never changes the player's actual mining aim. */
public final class FreeLookCamera {
    private CameraType previous;
    private float yaw;
    private float pitch;

    void enable(Minecraft minecraft, boolean enabled) {
        if (!enabled) {
            restore(minecraft);
            return;
        }
        if (previous == null) {
            previous = minecraft.options.getCameraType();
            yaw = minecraft.player == null ? 0 : minecraft.player.getYRot();
            pitch = 35;
        }
        minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    boolean enabled() { return previous != null; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    void capture(double yawDelta, double pitchDelta) {
        yaw = Mth.wrapDegrees(yaw + (float)yawDelta * 0.15F);
        pitch = Mth.clamp(pitch + (float)pitchDelta * 0.15F, -80, 80);
    }

    void restore(Minecraft minecraft) {
        if (previous != null) {
            minecraft.options.setCameraType(previous);
            previous = null;
        }
    }
}
