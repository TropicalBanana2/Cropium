package com.salesfarm.croppilot.mixin;

import com.salesfarm.croppilot.CropPilotClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "grabMouse", at = @At("TAIL"))
    private void cropium$rearmAfterMouseGrab(CallbackInfo ci) {
        CropPilotClient.onMouseGrabbed();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void cropium$menuMouse(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (CropPilotClient.handleMenuMouse(button.button(), action)) ci.cancel();
    }
    @Redirect(
        method = "turnPlayer(D)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V")
    )
    private void cropPilot$captureFreeLook(LocalPlayer player, double yawDelta, double pitchDelta) {
        if (!CropPilotClient.captureFreeLook(yawDelta, pitchDelta)) {
            player.turn(yawDelta, pitchDelta);
        }
    }
}
