package com.salesfarm.croppilot;

/** Never compete with an inventory transaction or an NPC currently being placed. */
final class HotbarGuard {
    static final int FIRST_SLOT = 0;

    // Server tools can be custom items with no vanilla TOOL component. The server
    // decides what they mine; only an empty slot or an actually broken item blocks us.
    static boolean usableItem(boolean empty, boolean damageable, int damage, int maxDamage) {
        return !empty && (!damageable || maxDamage <= 0 || damage < maxDamage);
    }

    static boolean shouldRestore(boolean running, boolean merchant, boolean screenOpen,
                                 boolean cursorEmpty, boolean playerInventory, int selected) {
        return running && !merchant && !screenOpen && cursorEmpty && playerInventory && selected != FIRST_SLOT;
    }

    static void selfTest() {
        assert usableItem(false, false, 0, 0) : "Custom server item without vanilla tool/durability data";
        assert usableItem(false, true, 99, 100) : "Last durability point still works";
        assert !usableItem(false, true, 100, 100);
        assert !usableItem(true, false, 0, 0);
        assert shouldRestore(true, false, false, true, true, 5);
        assert !shouldRestore(true, true, false, true, true, 5);
        assert !shouldRestore(true, false, true, true, true, 5);
        assert !shouldRestore(true, false, false, false, true, 5);
        assert !shouldRestore(true, false, false, true, false, 5);
        assert !shouldRestore(false, false, false, true, true, 5);
        assert !shouldRestore(true, false, false, true, true, FIRST_SLOT);
    }
}
