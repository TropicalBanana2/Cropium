package com.salesfarm.croppilot;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public interface CropiumModule {
    String id();

    Component name();

    Component description();

    boolean isActive();

    String status();

    boolean start(Minecraft minecraft);

    void stop(Minecraft minecraft);

    default void toggle(Minecraft minecraft) {
        if (isActive()) {
            stop(minecraft);
        } else {
            start(minecraft);
        }
    }
}
