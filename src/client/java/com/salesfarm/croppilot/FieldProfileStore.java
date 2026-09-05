package com.salesfarm.croppilot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FieldProfileStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("crop-pilot/profiles");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("crop-pilot-fields.json");

    private Data data = new Data();

    static FieldProfileStore load() {
        FieldProfileStore store = new FieldProfileStore();
        try {
            if (Files.exists(PATH)) {
                Data loaded = GSON.fromJson(Files.readString(PATH), Data.class);
                if (loaded != null && loaded.worlds != null) {
                    if (loaded.obstacles == null) {
                        loaded.obstacles = new HashMap<>();
                    }
                    if (loaded.scans == null) {
                        loaded.scans = new HashMap<>();
                    }
                    store.data = loaded;
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not read {}; starting with no saved fields", PATH, exception);
        }
        return store;
    }

    static String worldKey(Minecraft minecraft) {
        if (minecraft.level == null) {
            return null;
        }
        String place;
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            place = "singleplayer:" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
        } else if (minecraft.getCurrentServer() != null) {
            place = "server:" + minecraft.getCurrentServer().ip;
        } else {
            return null;
        }
        return place + "|" + minecraft.level.dimension().identifier();
    }

    void put(String world, String name, Profile profile) throws IOException {
        validateName(name);
        profile.name = name.trim();
        data.worlds.computeIfAbsent(world, ignored -> new HashMap<>()).put(key(name), profile);
        save();
    }

    Profile get(String world, String name) {
        Map<String, Profile> profiles = data.worlds.get(world);
        return profiles == null ? null : profiles.get(key(name));
    }

    boolean delete(String world, String name) throws IOException {
        Map<String, Profile> profiles = data.worlds.get(world);
        if (profiles == null || profiles.remove(key(name)) == null) {
            return false;
        }
        if (profiles.isEmpty()) {
            data.worlds.remove(world);
        }
        save();
        return true;
    }

    List<String> names(String world) {
        Map<String, Profile> profiles = data.worlds.get(world);
        if (profiles == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Profile profile : profiles.values()) {
            names.add(profile.name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    List<SavedObstacle> obstacles(String world, String field) {
        Map<String, List<SavedObstacle>> fields = data.obstacles.get(world);
        List<SavedObstacle> obstacles = fields == null ? null : fields.get(field);
        return obstacles == null ? List.of() : List.copyOf(obstacles);
    }

    void rememberObstacle(String world, String field, BlockPos position, int side) {
        if (world == null || field == null || position == null) {
            return;
        }
        List<SavedObstacle> obstacles = data.obstacles
            .computeIfAbsent(world, ignored -> new HashMap<>())
            .computeIfAbsent(field, ignored -> new ArrayList<>());
        for (SavedObstacle obstacle : obstacles) {
            if (obstacle.x == position.getX() && obstacle.y == position.getY() && obstacle.z == position.getZ()) {
                if (obstacle.side != side) {
                    obstacle.side = side;
                    saveObstacleMap();
                }
                return;
            }
        }
        if (obstacles.size() >= 256) {
            obstacles.removeFirst();
        }
        obstacles.add(new SavedObstacle(position.getX(), position.getY(), position.getZ(), side));
        saveObstacleMap();
    }

    byte[] fieldScan(String world, String field, int width, int depth) {
        if (world == null || field == null || width < 1 || depth < 1) {
            return null;
        }
        Map<String, SavedFieldScan> fields = data.scans.get(world);
        SavedFieldScan scan = fields == null ? null : fields.get(field);
        if (scan == null || scan.width != width || scan.depth != depth || scan.cells == null) {
            return null;
        }
        try {
            byte[] cells = Base64.getDecoder().decode(scan.cells);
            return cells.length == width * depth ? cells : null;
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Ignoring an invalid saved Cropium field scan for {}", field);
            return null;
        }
    }

    boolean putFieldScan(String world, String field, int width, int depth, byte[] cells) {
        if (world == null || field == null || cells == null || cells.length != width * depth) {
            return false;
        }
        data.scans.computeIfAbsent(world, ignored -> new HashMap<>()).put(field,
            new SavedFieldScan(width, depth, Base64.getEncoder().encodeToString(cells)));
        try {
            save();
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Could not save the Cropium field scan", exception);
            return false;
        }
    }

    private void saveObstacleMap() {
        try {
            save();
        } catch (IOException exception) {
            LOGGER.warn("Could not save learned Cropium obstacles", exception);
        }
    }

    private void save() throws IOException {
        Files.createDirectories(PATH.getParent());
        Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(data));
        try {
            Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateName(String name) {
        if (name == null || !name.trim().matches("[A-Za-z0-9 _-]{1,32}")) {
            throw new IllegalArgumentException("Use 1-32 letters, numbers, spaces, underscores, or hyphens");
        }
    }

    private static final class Data {
        private Map<String, Map<String, Profile>> worlds = new HashMap<>();
        private Map<String, Map<String, List<SavedObstacle>>> obstacles = new HashMap<>();
        private Map<String, Map<String, SavedFieldScan>> scans = new HashMap<>();
    }

    private static final class SavedFieldScan {
        private int width;
        private int depth;
        private String cells;

        private SavedFieldScan(int width, int depth, String cells) {
            this.width = width;
            this.depth = depth;
            this.cells = cells;
        }
    }

    static final class SavedObstacle {
        int x;
        int y;
        int z;
        int side;

        private SavedObstacle(int x, int y, int z, int side) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
        }
    }

    static final class Profile {
        String name;
        String routeStyle = "RECTANGULAR";
        int minX;
        int maxX;
        int minZ;
        int maxZ;
        int cropY;
        float lookDownPitch;
        float headingJitterDegrees;
        int turnDurationTicks;
        boolean naturalMovement;
        boolean obstacleAvoidance = true;
        float obstacleLookAhead = 10.0F;
        float obstacleClearance = 0.45F;
        boolean stopOnNameMention = true;
        boolean lowBpsFailsafe = true;
        float minimumBps = 0.25F;
        int lowBpsWindowSeconds = 20;
        boolean showHud;
        boolean showWorldOverlay;
        boolean thirdPersonCamera;
    }
}
