package com.salesfarm.croppilot;

import java.util.Locale;

public final class PetOpenerLogic {
    public static final int EGGS_PER_PAGE = 7;

    private PetOpenerLogic() {
    }

    public static int eggPage(int eggIndex) {
        return eggIndex / EGGS_PER_PAGE;
    }

    public static int eggSlot(int eggIndex) {
        return 1 + eggIndex % EGGS_PER_PAGE;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replace('_', ' ')
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    }

    public static boolean containsWords(String text, String words) {
        String normalized = " " + normalize(text) + " ";
        for (String word : normalize(words).split(" ")) {
            if (!normalized.contains(" " + word + " ")) {
                return false;
            }
        }
        return true;
    }

    public static boolean needsAutoDelete(String tooltip) {
        String text = normalize(tooltip);
        if (!containsWords(text, "click to auto delete") || containsWords(text, "locked pet")) {
            return false;
        }
        return containsWords(text, "common") || containsWords(text, "uncommon")
            || containsWords(text, "rare") || containsWords(text, "epic");
    }

    public static boolean storageFull(String message) {
        return containsWords(message, "pet storage limit");
    }

    public static boolean eggsOpened(String message, int amount) {
        String raw = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return (containsWords(message, "opened") || raw.contains("ᴏᴘᴇɴᴇᴅ"))
            && containsWords(message, "x" + amount);
    }

    public static void selfTest() {
        assert eggPage(0) == 0 && eggSlot(0) == 1;
        assert eggPage(6) == 0 && eggSlot(6) == 7;
        assert eggPage(7) == 1 && eggSlot(7) == 1;
        assert eggPage(17) == 2 && eggSlot(17) == 4;
        assert containsWords("Brain_Rot Egg", "brain rot");
        assert !containsWords("Brain Rot Egg", "AI");
        assert needsAutoDelete("Snake\nRare\nClick to Auto-Delete");
        assert !needsAutoDelete("Dragon\nLegendary\nClick to Auto-Delete");
        assert !needsAutoDelete("Snake\nRare\nAUTO-DELETED");
        assert storageFull("ERROR You have reached the pet storage limit!");
        assert eggsOpened("HEAVENLY [ᴏᴘᴇɴᴇᴅ] x9", 9);
        assert eggsOpened("Default [OPENED] x3", 3);
        assert !eggsOpened("HEAVENLY [ᴏᴘᴇɴᴇᴅ] x9", 3);
        assert containsWords("Open Egg(s) (x3)\n(Right-Click)", "open");
        assert containsWords("Open Egg(s) (x3)\n(Right-Click)", "x3");
    }
}
