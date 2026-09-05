package com.salesfarm.croppilot;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Only the four legal states of a normal pickup/place hotbar transfer. */
final class HotbarTransfer<T> {
    enum Step { PICK_SOURCE, PLACE_HOTBAR, RETURN_DISPLACED, COMPLETE, WAIT, ABORT }

    private final T npc;
    private final T displaced;
    private final BiPredicate<T, T> same;
    private final Predicate<T> empty;
    private Step observed, sent;
    private long started = -1, stableSince, clickedAt;
    private int retries;

    HotbarTransfer(T npc, T displaced, BiPredicate<T, T> same, Predicate<T> empty) {
        this.npc = npc;
        this.displaced = displaced;
        this.same = same;
        this.empty = empty;
    }

    Step step(T source, T hotbar, T cursor) {
        if (same.test(source, displaced) && same.test(hotbar, npc) && empty.test(cursor)) return Step.COMPLETE;
        if (same.test(source, npc) && same.test(hotbar, displaced) && empty.test(cursor)) return Step.PICK_SOURCE;
        if (empty.test(source) && same.test(hotbar, displaced) && same.test(cursor, npc)) return Step.PLACE_HOTBAR;
        if (empty.test(source) && same.test(hotbar, npc) && !empty.test(displaced)
            && same.test(cursor, displaced)) return Step.RETURN_DISPLACED;
        return Step.WAIT; // Lag/rollback or user edits: do not guess what to click.
    }

    Step nextStep(long tick, T source, T hotbar, T cursor) {
        if (started < 0) started = clickedAt = tick;
        Step step = step(source, hotbar, cursor);
        if (step != observed) {
            observed = step;
            stableSince = tick;
        }
        if (tick - started > 300 || step == Step.WAIT && tick - clickedAt > 80) return Step.ABORT;
        if (step == Step.WAIT || tick - stableSince < 12) return Step.WAIT;
        if (step == Step.COMPLETE) return step;
        if (step == sent) {
            if (tick - clickedAt < 80) return Step.WAIT;
            if (++retries > 2) return Step.ABORT;
        } else retries = 0;
        sent = step;
        clickedAt = tick;
        return step;
    }

    static void selfTest() {
        var emptyBar = new HotbarTransfer<>("NPC x4", "", String::equals, String::isEmpty);
        assert emptyBar.step("NPC x4", "", "") == Step.PICK_SOURCE;
        assert emptyBar.step("", "", "NPC x4") == Step.PLACE_HOTBAR;
        assert emptyBar.step("", "NPC x4", "") == Step.COMPLETE;
        assert emptyBar.step("", "NPC x3", "") == Step.WAIT : "Partial transfers aren't complete";
        var fullBar = new HotbarTransfer<>("NPC x2", "pickaxe", String::equals, String::isEmpty);
        assert fullBar.step("NPC x2", "pickaxe", "") == Step.PICK_SOURCE;
        assert fullBar.step("", "pickaxe", "NPC x2") == Step.PLACE_HOTBAR;
        assert fullBar.step("", "NPC x2", "pickaxe") == Step.RETURN_DISPLACED;
        assert fullBar.step("pickaxe", "NPC x2", "") == Step.COMPLETE;
        assert fullBar.step("", "NPC x2", "") == Step.WAIT : "Do not lose the displaced tool";
        assert fullBar.step("NPC x2", "pickaxe", "NPC x2") == Step.WAIT : "Partial server update";
        assert fullBar.step("other", "pickaxe", "") == Step.WAIT;
        assert fullBar.step("NPC x2", "pickaxe", "") == Step.PICK_SOURCE : "Recognize rejected pickup";
        var second = new HotbarTransfer<>("NPC B", "NPC A", String::equals, String::isEmpty);
        assert second.step("NPC A", "NPC B", "") == Step.COMPLETE : "Multiple queued NPC types";
        var paced = new HotbarTransfer<>("NPC", "tool", String::equals, String::isEmpty);
        assert paced.nextStep(0, "NPC", "tool", "") == Step.WAIT;
        assert paced.nextStep(12, "NPC", "tool", "") == Step.PICK_SOURCE;
        assert paced.nextStep(30, "NPC", "tool", "") == Step.WAIT : "Don't double-click a delayed pickup";
        assert paced.nextStep(31, "", "tool", "NPC") == Step.WAIT;
        assert paced.nextStep(43, "", "tool", "NPC") == Step.PLACE_HOTBAR;
        assert paced.nextStep(44, "", "NPC", "tool") == Step.WAIT;
        assert paced.nextStep(56, "", "NPC", "tool") == Step.RETURN_DISPLACED;
        assert paced.nextStep(57, "tool", "NPC", "") == Step.WAIT;
        assert paced.nextStep(69, "tool", "NPC", "") == Step.COMPLETE;
        var rejected = new HotbarTransfer<>("NPC", "", String::equals, String::isEmpty);
        rejected.nextStep(0, "NPC", "", "");
        assert rejected.nextStep(12, "NPC", "", "") == Step.PICK_SOURCE;
        assert rejected.nextStep(92, "NPC", "", "") == Step.PICK_SOURCE;
        assert rejected.nextStep(172, "NPC", "", "") == Step.PICK_SOURCE;
        assert rejected.nextStep(252, "NPC", "", "") == Step.ABORT : "Bounded rejection retries";
        var changed = new HotbarTransfer<>("NPC", "", String::equals, String::isEmpty);
        assert changed.nextStep(0, "NPC", "", "unrelated cursor") == Step.WAIT;
        assert changed.nextStep(81, "NPC", "", "unrelated cursor") == Step.ABORT;
    }
}
