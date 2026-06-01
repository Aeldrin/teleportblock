package com.example.teleportblock.compat.ponder;

import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public class TeleportBlockPonderScenes {

    public static void linking(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("linking", "Linking Two Teleport Blocks");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos first = new BlockPos(1, 1, 2);
        BlockPos second = new BlockPos(3, 1, 2);

        scene.world().showSection(util.select().position(first), Direction.DOWN);
        scene.idle(15);
        scene.addKeyframe();

        scene.world().showSection(util.select().position(second), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(60)
                .text("shift_click_first")
                .pointAt(Vec3.atCenterOf(first))
                .attachKeyFrame();
        scene.idle(70);

        scene.overlay().showText(60)
                .text("shift_click_second")
                .pointAt(Vec3.atCenterOf(second))
                .attachKeyFrame();
        scene.idle(70);

        scene.overlay().showText(60)
                .text("linked")
                .pointAt(Vec3.atCenterOf(first))
                .attachKeyFrame();
        scene.idle(70);

        scene.markAsFinished();
    }

    public static void teleporting(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("teleporting", "Teleporting");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        BlockPos first = new BlockPos(1, 1, 2);
        BlockPos second = new BlockPos(3, 1, 2);

        scene.world().showSection(util.select().position(first), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(second), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(60)
                .text("click_to_teleport")
                .pointAt(Vec3.atCenterOf(first))
                .attachKeyFrame();
        scene.idle(70);

        scene.overlay().showText(60)
                .text("range")
                .pointAt(Vec3.atCenterOf(second))
                .attachKeyFrame();
        scene.idle(70);

        scene.markAsFinished();
    }
}