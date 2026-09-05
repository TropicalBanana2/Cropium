package com.salesfarm.croppilot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CropPilotConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("crop-pilot/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("crop-pilot.json");

    public int configVersion = 20;
    public MovementPreset movementPreset = MovementPreset.BALANCED;
    public List<ExclusionZone> exclusions = new ArrayList<>();
    public List<TracedRoute> tracedRoutes = new ArrayList<>();
    public boolean customMineRoutes;
    public boolean customFarmRoutes;
    public int customRouteSeconds = 60;
    public boolean advancedSettings;
    public boolean showMiningHealth = true;
    public boolean mouseMenuShortcut = true;
    public int mouseMenuButton = 2;
    public RouteStyle routeStyle = RouteStyle.RECTANGULAR;
    public float lookDownPitch = 74.0F;
    public float headingJitterDegrees = 9.0F;
    public int turnDurationTicks = 26;
    public boolean naturalMovement = true;
    public boolean obstacleAvoidance = true;
    public float obstacleLookAhead = 14.0F;
    public float obstacleClearance = 1.0F;
    public boolean stopOnNameMention = true;
    public boolean lowBpsFailsafe = true;
    public float minimumBps = 0.25F;
    public int lowBpsWindowSeconds = 20;
    public boolean showHud = true;
    public boolean showWorldOverlay = true;
    public boolean thirdPersonCamera = true;
    public boolean targetGlowingPlants = true;
    public boolean glowInspector = false;
    public boolean merchantRestockHarvester = true;
    public boolean merchantRestockEggHatcher = true;
    public boolean merchantRestockMine = true;
    public boolean mineTargetFossils = true;
    public boolean mineTargetIce = true;
    public MineLayout.RouteMode mineRouteMode = MineLayout.RouteMode.AUTO;
    public int mineRouteSeconds = 60;
    public int mineRouteVariation = 75;
    public float mineEntryDistance = 28.0F;
    public boolean merchantAutoPlace = true;
    public String merchantNpcThreshold = "1M";
    public boolean merchantBuyRares = true;
    public boolean merchantAlwaysPlaceRares = true;
    public PetEgg petEgg = PetEgg.DEFAULT;
    public int petOpenAmount = 9;
    public ForgeTier petMaxForgeTier = ForgeTier.COMBUSTED;
    public boolean plotBoundsSet;
    public String plotWorldKey;
    public int plotMinX;
    public int plotMaxX;
    public int plotMinY;
    public int plotMaxY;
    public int plotMinZ;
    public int plotMaxZ;

    public static CropPilotConfig load() {
        try {
            if (Files.exists(PATH)) {
                String json = Files.readString(PATH);
                var saved = JsonParser.parseString(json).getAsJsonObject();
                boolean legacy = !saved.has("configVersion");
                if (saved.has("mineRouteMode") && saved.get("mineRouteMode").isJsonPrimitive()) {
                    saved.addProperty("mineRouteMode", MineLayout.upgradeRouteMode(saved.get("mineRouteMode").getAsString()));
                }
                CropPilotConfig config = GSON.fromJson(saved, CropPilotConfig.class);
                if (config != null) {
                    if (legacy || config.configVersion < 6) {
                        config.turnDurationTicks = 42;
                        config.showWorldOverlay = true;
                        config.thirdPersonCamera = true;
                    }
                    if (legacy || config.configVersion < 7) {
                        config.routeStyle = RouteStyle.RECTANGULAR;
                    }
                    if (legacy || config.configVersion < 8) {
                        config.configVersion = 8;
                        config.obstacleAvoidance = true;
                        config.obstacleLookAhead = 10.0F;
                        config.obstacleClearance = 0.45F;
                        config.stopOnNameMention = true;
                        config.lowBpsFailsafe = true;
                        config.minimumBps = 0.25F;
                        config.lowBpsWindowSeconds = 20;
                        config.save();
                    }
                    if (config.configVersion < 9) {
                        config.configVersion = 9;
                        config.save();
                    }
                    if (config.configVersion < 10) {
                        config.configVersion = 10;
                        config.turnDurationTicks = Math.min(config.turnDurationTicks, 26);
                        config.obstacleLookAhead = Math.max(config.obstacleLookAhead, 14.0F);
                        config.obstacleClearance = Math.max(config.obstacleClearance, 1.0F);
                        config.save();
                    }
                    if (config.configVersion < 11) {
                        config.configVersion = 11;
                        config.targetGlowingPlants = true;
                        config.glowInspector = false;
                        config.save();
                    }
                    if (config.configVersion < 12) {
                        config.configVersion = 12;
                        config.petEgg = PetEgg.DEFAULT;
                        config.petOpenAmount = 9;
                        config.petMaxForgeTier = ForgeTier.COMBUSTED;
                        config.save();
                    }
                    if (config.configVersion < 13) {
                        config.configVersion = 13;
                        config.merchantRestockHarvester = true;
                        config.merchantRestockEggHatcher = true;
                        config.save();
                    }
                    if (config.configVersion < 14) {
                        config.configVersion = 14;
                        config.save();
                    }
                    if (config.configVersion < 15) {
                        config.configVersion = 15;
                        config.merchantAutoPlace = true;
                        config.merchantNpcThreshold = "1M";
                        config.save();
                    }
                    if (config.configVersion < 16) {
                        config.configVersion = 16;
                        config.merchantRestockMine = true;
                        config.mineTargetFossils = true;
                        config.mineTargetIce = true;
                        config.mineEntryDistance = 28.0F;
                        config.save();
                    }
                    config.validate();
                    if (config.configVersion < 20) {
                        config.configVersion = 20;
                        config.save();
                    }
                    return config;
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not read {}; defaults will be used", PATH, exception);
        }
        return new CropPilotConfig();
    }

    public boolean save() {
        validate();
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this));
            return true;
        } catch (IOException exception) {
            LOGGER.error("Could not save {}", PATH, exception);
            return false;
        }
    }

    void validate() {
        tracedRoutes = tracedRoutes == null ? new ArrayList<>() : new ArrayList<>(tracedRoutes.stream()
            .filter(route -> route != null && route.valid()).distinct().limit(TracedRoute.MAX_ROUTES).toList());
        customRouteSeconds = Math.clamp(customRouteSeconds, 30, 180);
        if (movementPreset == null) movementPreset = MovementPreset.BALANCED;
        if (mouseMenuButton != 2 && mouseMenuButton != 3 && mouseMenuButton != 4) mouseMenuButton = 2;
        exclusions = exclusions == null ? new ArrayList<>() : new ArrayList<>(exclusions.stream()
            .filter(zone -> zone != null && zone.valid()).distinct().limit(64).toList());
        if (routeStyle == null) {
            routeStyle = RouteStyle.RECTANGULAR;
        }
        if (petEgg == null) {
            petEgg = PetEgg.DEFAULT;
        }
        if (petMaxForgeTier == null) {
            petMaxForgeTier = ForgeTier.COMBUSTED;
        }
        if (MerchantRestockLogic.parseAmount(merchantNpcThreshold) == null) {
            merchantNpcThreshold = "1M";
        }
        petOpenAmount = petOpenAmount == 3 ? 3 : 9;
        lookDownPitch = Math.clamp(lookDownPitch, 55.0F, 88.0F);
        headingJitterDegrees = Math.clamp(headingJitterDegrees, 0.0F, 25.0F);
        turnDurationTicks = Math.clamp(turnDurationTicks, 16, 48);
        obstacleLookAhead = Math.clamp(obstacleLookAhead, 4.0F, 18.0F);
        obstacleClearance = Math.clamp(obstacleClearance, 0.10F, 1.50F);
        minimumBps = Math.clamp(minimumBps, 0.05F, 20.0F);
        lowBpsWindowSeconds = Math.clamp(lowBpsWindowSeconds, 10, 60);
        mineEntryDistance = Math.clamp(mineEntryDistance, 20.0F, 36.0F);
        if (mineRouteMode == null) mineRouteMode = MineLayout.RouteMode.AUTO;
        mineRouteSeconds = Math.clamp(mineRouteSeconds, 30, 180);
        mineRouteVariation = Math.clamp(mineRouteVariation, 0, 100);
        if (plotBoundsSet && (plotWorldKey == null || plotWorldKey.isBlank()
            || plotMinX > plotMaxX || plotMinY > plotMaxY || plotMinZ > plotMaxZ
            || (long)plotMaxX - plotMinX >= 256 || plotMaxY - plotMinY >= 16
            || (long)plotMaxZ - plotMinZ >= 256)) {
            plotBoundsSet = false;
            plotWorldKey = null;
        }
    }

    public enum RouteStyle {
        RECTANGULAR("Rectangular — lengthwise"),
        SQUARE("Square — heading-axis lanes");

        private final String label;

        RouteStyle(String label) {
            this.label = label;
        }
    }

    public enum PetEgg {
        DEFAULT("Default"),
        DESERT("Desert"),
        CACTUS("Cactus"),
        ICE("Ice"),
        HELL("Hell"),
        HEAVENLY("Heavenly"),
        BRAIN_ROT("Brain Rot"),
        DINO("Dino"),
        PUMPKIN("Pumpkin"),
        WITCH("Witch"),
        ROBOT("Robot"),
        VOID("Void"),
        CORRUPT("Corrupt"),
        DRAGON("Dragon"),
        ALIEN("Alien"),
        AI("AI"),
        ANCIENT("Ancient"),
        MEDIEVAL("Medieval");

        private final String label;

        PetEgg(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public PetEgg offset(int amount) {
            PetEgg[] values = values();
            return values[Math.floorMod(ordinal() + amount, values.length)];
        }
    }

    public enum ForgeTier {
        GOLDEN("Golden"),
        RAINBOW("Rainbow"),
        COMBUSTED("Combusted"),
        VOID("Void"),
        GLAZE("Glaze"),
        SHADOW("Shadow"),
        ATOMIC("Atomic");

        private final String label;

        ForgeTier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public ForgeTier offset(int amount) {
            ForgeTier[] values = values();
            return values[Math.floorMod(ordinal() + amount, values.length)];
        }
    }
}
