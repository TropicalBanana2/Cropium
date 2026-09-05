package com.salesfarm.croppilot;

/** Inclusive block rectangle. A block occupies [x,x+1), not just its integer origin. */
public record ExclusionZone(String worldKey, String moduleId, int minX, int maxX, int minZ, int maxZ) {
    public boolean valid() {
        return worldKey != null && !worldKey.isBlank() && worldKey.length() <= 512
            && ("mine".equals(moduleId) || "harvest".equals(moduleId))
            && minX >= -30_000_000 && maxX <= 30_000_000 && minZ >= -30_000_000 && maxZ <= 30_000_000
            && minX <= maxX && minZ <= maxZ && (long)maxX - minX < 512 && (long)maxZ - minZ < 512;
    }

    public boolean intersects(String world, String module, double x, double z, double padding) {
        return valid() && worldKey.equals(world) && moduleId.equals(module)
            && Double.isFinite(x) && Double.isFinite(z) && Double.isFinite(padding) && padding >= 0
            && x >= minX - padding && x <= maxX + 1.0 + padding
            && z >= minZ - padding && z <= maxZ + 1.0 + padding;
    }

    static void selfTest() {
        var zone = new ExclusionZone("world", "mine", -3, -2, 4, 5);
        assert zone.valid();
        assert zone.intersects("world", "mine", -1.1, 5.9, 0);
        assert !zone.intersects("world", "mine", -0.9, 5.9, 0);
        assert zone.intersects("world", "mine", -0.9, 5.9, .2);
        assert !zone.intersects("other", "mine", -2, 5, 0);
        assert !zone.intersects("world", "harvest", -2, 5, 0);
        assert !new ExclusionZone("world", "mine", Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0).valid();
        assert !new ExclusionZone("world", "egg", 0, 0, 0, 0).valid();
    }
}
