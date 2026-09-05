package com.salesfarm.croppilot.mixin;

import com.salesfarm.croppilot.CropPilotClient;
import com.salesfarm.croppilot.FreeLookCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void move(float distance, float vertical, float horizontal);

    @Shadow
    private float getMaxZoom(float desiredDistance) {
        throw new AssertionError();
    }

    @Inject(
        method = "update(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;calculateFov(F)F")
    )
    private void cropPilot$applyFreeLook(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        FreeLookCamera camera = CropPilotClient.freeLookCamera();
        LocalPlayer player = Minecraft.getInstance().player;
        if (camera == null || player == null) {
            return;
        }

        float partialTick = ((Camera)(Object)this).getCameraEntityPartialTicks(deltaTracker);
        setRotation(camera.yaw(), camera.pitch());
        setPosition(
            Mth.lerp(partialTick, player.xo, player.getX()),
            Mth.lerp(partialTick, player.yo, player.getY()) + player.getEyeHeight(),
            Mth.lerp(partialTick, player.zo, player.getZ())
        );
        move(-getMaxZoom(5.5F), 0.0F, 0.0F);
    }
}
