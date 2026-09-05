import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Client-only source contracts; supplement the behavior tests and live-server smoke tests. */
public final class ClientSourceContracts {
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Path clientRoot = root.resolve("src/client/java/com/salesfarm/croppilot");
        String farm = Files.readString(clientRoot.resolve("HarvestController.java"));
        String mine = Files.readString(clientRoot.resolve("MineController.java"));
        String client = Files.readString(clientRoot.resolve("CropPilotClient.java"));

        for (String body : List.of(methodBody(farm, "holdAttack"), methodBody(mine, "holdMiningInputs"))) {
            check(!Pattern.compile("safeAttack\\(|hitResult|validMiningBlock\\(").matcher(body).find(),
                "Stale/air render hits must not release held attack");
            require(body, "Active harvesting must hold attack every tick", "keyAttack.setDown(true)");
            require(body, "Never hold attack through a GUI", "minecraft.gui.screen()");
        }

        var callbackMatch = Pattern.compile("(?s)AttackBlockCallback.EVENT.register\\((.*?)ClientPlayerBlockBreakEvents")
            .matcher(client);
        check(callbackMatch.find(), "Missing block-attack callback");
        String callback = callbackMatch.group();
        for (String owner : List.of("mine", "controller")) {
            require(callback, "Validate the actual callback block, not the render hit",
                "!" + owner + ".mayAttack(Minecraft.getInstance(), position)");
        }
        check(!callback.contains("safeAttack("), "The attack callback must not recheck the previous render target");
        require(callback, "Unsafe block attacks must still be cancelled", "InteractionResult.FAIL");

        String target = methodBody(farm, "validMiningBlock");
        check(!target.contains("isCrop("), "Custom server crops must not need a vanilla CropBlock type");
        require(target, "Missing farm protection", "fieldWorldKey.equals", "block.getY() != bounds.cropY",
            "!= FIELD_CROP", "excluded(", "hasChunkAt(block)", "!state.isAir()", "!state.is(BlockTags.STAIRS)");
        require(methodBody(mine, "mayAttack"), "Missing mine protection", "activeWorldKey.equals",
            "MineLayout.interior(", "CoverageMemory.excluded(", "BlockTags.STAIRS");

        String detour = methodBody(farm, "beginDetour");
        check(!detour.contains("message(minecraft,"), "Routine reroutes belong in status, not repeated chat messages");
        require(detour, "Rerouting must retain its safety behavior", "beginPerimeterRoute(minecraft, false)",
            "candidate.clearEnough", "startTurn(", "beginObstacleRecovery(");
        require(methodBody(farm, "isRerouting"), "Rerouting status must not hide a stop/recovery",
            "mode == Mode.RUNNING", "recoveryPhase == RecoveryPhase.NONE", "detouring", "ticks - detectedObstacleTick < 50");
        for (String display : List.of("status", "hudLineFour")) {
            require(methodBody(farm, display), "Menu and HUD must both display rerouting", "isRerouting()");
        }
        System.out.println("Client source contracts passed: continuous attack, protected boundaries, quiet rerouting status with safety intact.");
    }

    private static String methodBody(String source, String name) {
        var match = Pattern.compile("(?ms)^    (?:public|private) (?:boolean|void|String) "
            + Pattern.quote(name) + "\\([^)]*\\) \\{(.*?)^    \\}").matcher(source);
        check(match.find(), "Missing method: " + name);
        return match.group(1);
    }

    private static void require(String source, String message, String... fragments) {
        for (String fragment : fragments) {
            check(source.contains(fragment), message + ": " + fragment);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
