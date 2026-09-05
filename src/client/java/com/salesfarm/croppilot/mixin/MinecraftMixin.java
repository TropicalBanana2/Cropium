package com.salesfarm.croppilot.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.salesfarm.croppilot.CropPilotClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyExpressionValue(
        method = "handleKeybinds",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"),
        require = 1, allow = 1
    )
    private boolean cropium$continueOwnedMining(boolean captured) {
        // Menus closed while unfocused do not recapture the mouse. Keep the
        // existing vanilla attack path and block safety callbacks, not extra clicks.
        return captured || CropPilotClient.allowUncapturedMiningAttack();
    }
}
