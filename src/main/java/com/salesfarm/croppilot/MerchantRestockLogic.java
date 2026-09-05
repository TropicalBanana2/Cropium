package com.salesfarm.croppilot;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MerchantRestockLogic {
    // Server sheet (prestige tab, p0–p300): each following unit is 1,000 times the last.
    // https://docs.google.com/spreadsheets/d/1IZOq_b3Kec-gA_5P1ZKS8bPAr-Qv2y03UnkJ6cSuY5g/edit?gid=0
    static final java.util.List<String> MONEY_UNITS = java.util.List.of("", "K", "M", "B", "T", "Q", "Qi",
        "Sx", "Sp", "Oc", "No", "Dc", "UD", "Dd", "Td", "Qad", "Qid", "Sxd", "Spd", "Ocd", "Nod",
        "V", "UV", "DV", "TV", "QdV", "QiV", "SxV", "SpV", "OcV", "NoV");
    private static final String NUMBER = "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?";
    private static final Pattern AMOUNT = Pattern.compile(
        "^\\s*\\$?(" + NUMBER + ")[ \\t]*([A-Za-z]*)\\s*$");
    private static final Pattern GENERATED = Pattern.compile(
        "(?i)generates\\h*:\\h*\\$?(" + NUMBER + ")\\h*([A-Za-z]*)");
    private static final Pattern EXACT_GENERATED = Pattern.compile(
        "(?im)^\\h*generates\\h*:\\h*\\$?(" + NUMBER + ")\\h*([A-Za-z]*)"
            + "\\h*(?:(?:/|per\\h+)(?:s|sec|second|seconds))?\\h*$");
    private static final Pattern PRICE_LABEL = Pattern.compile(
        "(?i)^(?:cost|price|purchase price)(?: per (?:purchase|npc)| each)?\\h*:\\h*(.+)$");
    private static final Pattern PRICE = Pattern.compile(
        "(?i)^(\\$?)(" + NUMBER + ")([A-Za-z]*)(?:\\h+(coins|cash))?"
            + "(?:\\h+(?:each|per purchase|per npc))?$");
    private static final Pattern PURCHASE = Pattern.compile(
        "(?i)^(?:left[ -]?)?click to (?:buy|purchase)(?: (?:1|one)(?: npc)?)?[.!]?$"
            + "|^left[ -]?click\\h*[:➜→-]\\h*(?:buy|purchase)(?: (?:1|one)(?: npc)?)?[.!]?$");

    private MerchantRestockLogic() {
    }

    public static boolean isRestockMessage(String message) {
        String raw = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return PetOpenerLogic.containsWords(message, "merchant restock")
            || (PetOpenerLogic.containsWords(message, "back in stock")
                && PetOpenerLogic.containsWords(message, "merchant"))
            || (PetOpenerLogic.containsWords(message, "merchant") && raw.contains("ʀᴇꜱᴛᴏᴄᴋ"));
    }

    public static int purchasedCount(int before, int after) {
        return Math.max(0, after - before);
    }

    public static boolean flightTapDown(int phaseAge) {
        return phaseAge == 1 || phaseAge == 4;
    }

    public static boolean inventoryReady(boolean screenClosed, boolean inventoryMenuActive) {
        return screenClosed && inventoryMenuActive;
    }

    enum MenuCursorAction { CONTINUE, WAIT, STOP }

    static MenuCursorAction menuCursorAction(boolean cursorEmpty, boolean predictedMenuIcon, long clickAge) {
        if (cursorEmpty) return MenuCursorAction.CONTINUE;
        // Native GUI clicks predict picking up the button before the server restores
        // it. Only our own pending icon gets a bounded grace period; never click again.
        return predictedMenuIcon && clickAge >= 0 && clickAge < 60
            ? MenuCursorAction.WAIT : MenuCursorAction.STOP;
    }

    public static BigDecimal parseAmount(String text) {
        if (text == null || text.length() > 128) {
            return null;
        }
        Matcher matcher = AMOUNT.matcher(text);
        return matcher.matches() ? scaled(matcher.group(1), matcher.group(2)) : null;
    }

    public static BigDecimal generatedAmount(String tooltip) {
        if (tooltip == null) return null;
        Matcher matcher = GENERATED.matcher(cleanText(tooltip));
        return matcher.find() ? scaled(matcher.group(1), matcher.group(2)) : null;
    }

    private static BigDecimal npcGeneratedAmount(String tooltip) {
        if (tooltip == null) {
            return null;
        }
        Matcher matcher = EXACT_GENERATED.matcher(cleanText(tooltip));
        if (!matcher.find()) return null;
        BigDecimal amount = scaled(matcher.group(1), matcher.group(2));
        return matcher.find() ? null : amount;
    }

    public static boolean meetsThreshold(String tooltip, String threshold) {
        BigDecimal generated = generatedAmount(tooltip);
        BigDecimal minimum = parseAmount(threshold);
        return generated != null && minimum != null && generated.compareTo(minimum) >= 0;
    }

    public static String cleanText(String text) {
        return text == null ? "" : text.replaceAll("(?i)§[0-9a-fk-orx]", "").strip();
    }

    public static boolean isNpcTooltip(String tooltip) {
        String text = cleanText(tooltip);
        BigDecimal generated = npcGeneratedAmount(text);
        return PetOpenerLogic.containsWords(text, "npc plot")
            && generated != null && generated.signum() > 0;
    }

    public static boolean inspectableRareSlot(int slot, String itemId) {
        return slot >= 0 && slot < 9 && itemId != null && !itemId.isBlank()
            && !itemId.equals("air") && !itemId.endsWith("_pane")
            && !itemId.equals("nether_star") && !itemId.equals("cookie");
    }

    /** Fail closed until a real tooltip establishes additional supported offer formats. */
    public static String rareOfferProblem(String tooltip) {
        String text = cleanText(tooltip);
        String words = " " + PetOpenerLogic.normalize(text) + " ";
        for (String control : new String[]{"buy all", "minion forge", "salvage", "next page",
            "previous page", "back", "return", "close", "navigation", "coming soon", "placeholder",
            "sold out", "out of stock", "unavailable", "locked", "requires", "right click",
            "shift click", "bundle", "bulk", "preview", "confirm", "random", "chance",
            "fee", "fees", "tax", "surcharge"}) {
            if (words.contains(" " + control + " ")) return "control, unavailable or unsupported offer";
        }
        if (!isNpcTooltip(text)) return "missing NPC/plot/generation lore";
        if (text.lines().map(String::strip).filter(line -> PURCHASE.matcher(line).matches()).count() != 1) {
            return "missing single left-click purchase instruction";
        }
        for (String line : text.split("\\R")) {
            String normalized = PetOpenerLogic.normalize(line);
            if (normalized.matches("(?:amount|quantity|you receive) .*")
                && !normalized.matches("(?:amount|quantity|you receive) (?:1|one)(?: npc)?")) {
                return "unsupported purchase quantity";
            }
            if (normalized.matches(".*\\bx\\s*(?:[2-9]|[1-9][0-9]+)\\b.*")
                || normalized.contains("click") && !PURCHASE.matcher(line.strip()).matches()) {
                return "unsupported purchase action";
            }
        }
        BigDecimal cost = purchaseCost(text);
        // This server's NPC claims are free, including offers with no price line.
        // Still refuse an explicit charge or an ambiguous price; no paid purchases are authorized.
        if (cost != null) return cost.signum() == 0 ? null : "offer is not free";
        return Pattern.compile("(?i)\\b(?:cost|price)\\b").matcher(text).find()
            ? "unknown or ambiguous purchase cost" : null;
    }

    /** One explicit cash price for one click; income, balances, ranges and multiple prices are not costs. */
    public static BigDecimal purchaseCost(String tooltip) {
        BigDecimal cost = null;
        for (String line : cleanText(tooltip).split("\\R")) {
            Matcher label = PRICE_LABEL.matcher(line.strip());
            if (!label.matches()) {
                if (line.toLowerCase(Locale.ROOT).matches(".*\\b(?:cost|price)\\b.*")) return null;
                continue;
            }
            if (cost != null) return null;
            if (label.group(1).strip().equalsIgnoreCase("free")) {
                cost = BigDecimal.ZERO;
                continue;
            }
            Matcher price = PRICE.matcher(label.group(1).strip());
            if (!price.matches() || price.group(1).isEmpty() && price.group(4) == null) return null;
            cost = scaled(price.group(2), price.group(3));
            if (cost == null || cost.signum() < 0) return null;
        }
        return cost;
    }

    public static boolean sameNpcOffer(String offerName, String offerLore, String itemName, String itemLore) {
        BigDecimal offered = npcGeneratedAmount(offerLore);
        BigDecimal received = npcGeneratedAmount(itemLore);
        return !cleanText(offerName).isEmpty() && cleanText(offerName).equalsIgnoreCase(cleanText(itemName))
            && isNpcTooltip(offerLore) && isNpcTooltip(itemLore)
            && offered != null && received != null && offered.compareTo(received) == 0;
    }

    public static boolean shouldPlace(boolean rare, boolean alwaysPlaceRares, boolean autoPlace,
                                      String tooltip, String threshold) {
        return rare && alwaysPlaceRares || autoPlace && meetsThreshold(tooltip, threshold);
    }

    public static boolean maySalvage(boolean rare, boolean placeAtPlot) {
        return !rare && !placeAtPlot;
    }

    public static boolean confirmedSinglePurchase(int added, boolean sameNpc, long stableTicks) {
        return added == 1 && sameNpc && stableTicks >= 20;
    }

    private static BigDecimal scaled(String number, String suffix) {
        String unit = switch (suffix.toUpperCase(Locale.ROOT)) {
            // Keep historical config aliases without displaying the old guessed spellings.
            case "QD" -> "Q";
            case "QN" -> "Qi";
            case "D" -> "Dc";
            case "AD" -> "Qad";
            case "ID" -> "Qid";
            case "VG" -> "V";
            default -> suffix;
        };
        int exponent = -1;
        for (int i = 0; i < MONEY_UNITS.size(); i++) {
            if (MONEY_UNITS.get(i).equalsIgnoreCase(unit)) { exponent = i * 3; break; }
        }
        if (exponent < 0) {
            return null;
        }
        try {
            return new BigDecimal(number.replace(",", "")).scaleByPowerOfTen(exponent);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public static void selfTest() {
        assert isRestockMessage("MERCHANT [RESTOCK]\nNPCs Back in Stock! (/merchant)");
        assert isRestockMessage("MERCHANT [RESTOCK]");
        assert isRestockMessage("MERCHANT [ʀᴇꜱᴛᴏᴄᴋ]");
        assert isRestockMessage("NPC's Back in Stock! (/merchant)");
        assert !isRestockMessage("The wandering merchant left");
        assert purchasedCount(3, 8) == 5;
        assert purchasedCount(8, 3) == 0;
        assert flightTapDown(1) && flightTapDown(4);
        assert !flightTapDown(0) && !flightTapDown(2) && !flightTapDown(5);
        assert inventoryReady(true, true);
        assert !inventoryReady(false, true) && !inventoryReady(true, false);
        assert menuCursorAction(true, false, 0) == MenuCursorAction.CONTINUE;
        for (int tick = 0; tick < 60; tick++) {
            assert menuCursorAction(false, true, tick) == MenuCursorAction.WAIT
                : "Keep the merchant in control while its clicked icon is awaiting the server";
        }
        assert menuCursorAction(true, true, 30) == MenuCursorAction.CONTINUE : "Server cleared the predicted cursor";
        assert menuCursorAction(false, true, 60) == MenuCursorAction.STOP : "Never wait indefinitely or retry a purchase";
        assert menuCursorAction(false, false, 1) == MenuCursorAction.STOP : "An unrelated cursor/menu change is unsafe";
        assert menuCursorAction(false, true, -1) == MenuCursorAction.STOP;
        assert new BigDecimal("1200000").compareTo(parseAmount("$1.2M")) == 0;
        assert new BigDecimal("1000000000000000").compareTo(parseAmount("1QD")) == 0;
        assert new BigDecimal("1E+51").compareTo(parseAmount("1SxD")) == 0;
        assert generatedAmount("[Legendary] Billionaire\nGenerates: $1.2M\nType: NORMAL")
            .compareTo(new BigDecimal("1200000")) == 0;
        assert meetsThreshold("Generates: $1.2M", "1M");
        assert !meetsThreshold("Generates: $900K", "1M");
        assert parseAmount("12 mystery") == null;
        assert parseAmount("$1,234.50").compareTo(new BigDecimal("1234.50")) == 0;
        assert parseAmount("1,2M") == null && parseAmount("1,,000") == null;
        assert parseAmount("-1") == null && parseAmount("1e6") == null;
        for (int i = 0; i < MONEY_UNITS.size(); i++) {
            String unit = MONEY_UNITS.get(i);
            assert parseAmount("1" + unit.toLowerCase(Locale.ROOT)).compareTo(BigDecimal.ONE.scaleByPowerOfTen(i * 3)) == 0 : unit;
            if (i > 0) assert parseAmount("1000" + MONEY_UNITS.get(i - 1)).compareTo(parseAmount("1" + unit)) == 0;
        }
        assert parseAmount("1Q").compareTo(parseAmount("1QD")) == 0;
        assert parseAmount("1NoV").compareTo(new BigDecimal("1E90")) == 0;
        assert meetsThreshold("Generates: $1.114Q", "597.731T");
        assert !meetsThreshold("Generates: $938.308TV", "1.835QdV");
        assert meetsThreshold("Generates: $1.572NoV", "798.77OcV");
        String lore = "Rare NPC\nPlace this NPC on your plot\nGenerates: $1.2M\nCost: FREE\nLeft-Click to Purchase";
        assert inspectableRareSlot(0, "player_head") && inspectableRareSlot(8, "player_head");
        assert !inspectableRareSlot(-1, "player_head") && !inspectableRareSlot(9, "player_head");
        for (String item : new String[]{"glass_pane", "red_stained_glass_pane", "nether_star", "cookie", "air"}) {
            assert !inspectableRareSlot(4, item) : item;
        }
        assert rareOfferProblem(lore) == null;
        assert purchaseCost(lore).signum() == 0;
        assert rareOfferProblem(lore.replace("FREE", "$0")) == null;
        assert rareOfferProblem(lore.replace("Cost: FREE\n", "")) == null;
        assert rareOfferProblem(lore.replace("FREE", "$250K")) != null;
        assert rareOfferProblem(lore.replace("FREE", "unknown")) != null;
        assert purchaseCost("Generates: $9M\nBalance: $1B") == null;
        assert purchaseCost("Price per NPC: 1,234.50 coins").compareTo(new BigDecimal("1234.50")) == 0;
        assert purchaseCost("Cost: $1QD per purchase").compareTo(parseAmount("1QD")) == 0;
        for (String invalid : new String[]{"$1M-$2M", "$1M+", "$1M / second", "$1M tokens",
            "~$1M", "$1 mystery", "$1,2M", "250", "$5 for 2", "$5 (x2)"}) {
            assert purchaseCost("Cost: " + invalid) == null : invalid;
        }
        assert purchaseCost("Cost: $5\nPrice: $10") == null;
        assert purchaseCost("Cost: $5\nCost: $5") == null;
        assert purchaseCost("Cost: $5\nTotal cost: $10") == null;
        assert purchaseCost("Cost: $5\nPrice $10") == null;
        assert rareOfferProblem(lore + "\nPrice: $10") != null;
        assert rareOfferProblem(lore.replace("Left-Click to Purchase", "Right-Click to Purchase")) != null;
        assert rareOfferProblem(lore.replace("Left-Click to Purchase", "Click to Buy 2")) != null;
        assert rareOfferProblem(lore + "\nQuantity: 2") != null;
        assert rareOfferProblem(lore + "\nAdditional fee: $1M") != null;
        assert rareOfferProblem(lore + "\nx10 NPCs") != null;
        assert rareOfferProblem(lore + "\nShiftClick for more") != null;
        assert rareOfferProblem(lore + "\nClick to Buy") != null;
        assert rareOfferProblem(lore + "\nQuantity: 1") == null;
        assert rareOfferProblem(lore.replace("plot", "inventory")) != null;
        assert rareOfferProblem(lore.replace("Generates: $1.2M", "Generates: unknown")) != null;
        for (String control : new String[]{"Coming Soon", "Sold Out", "Buy All", "Next Page", "Cookie Preview"}) {
            assert rareOfferProblem(lore + "\n" + control) != null : control;
        }
        assert rareOfferProblem("§a" + lore.replace("Cost:", "§eCost:")) == null;
        assert npcGeneratedAmount("Generates: $1.2M tokens") == null;
        assert npcGeneratedAmount("Generates: $1M\nGenerates: $2M") == null;
        assert meetsThreshold("Generates: $1.2M (per second)", "1M") : "Preserve normal placement parsing";
        assert sameNpcOffer("§dRare NPC", lore, "Rare NPC", lore.replace("Cost: FREE\nLeft-Click to Purchase", ""));
        assert !sameNpcOffer("Rare NPC", lore, "Other NPC", lore);
        assert !sameNpcOffer("Rare NPC", lore, "Rare NPC", lore.replace("1.2M", "2M"));
        assert confirmedSinglePurchase(1, true, 20);
        assert !confirmedSinglePurchase(0, true, 100) && !confirmedSinglePurchase(2, true, 100);
        assert !confirmedSinglePurchase(1, false, 100) && !confirmedSinglePurchase(1, true, 19);
        assert shouldPlace(true, true, false, lore, "invalid");
        assert !shouldPlace(true, false, true, lore, "2M");
        assert shouldPlace(false, false, true, lore, "1M");
        assert !maySalvage(true, false) && !maySalvage(true, true);
        assert maySalvage(false, false) && !maySalvage(false, true);
    }
}
