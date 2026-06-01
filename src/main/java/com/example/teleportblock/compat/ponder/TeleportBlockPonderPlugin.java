package com.example.teleportblock.compat.ponder;

import com.example.teleportblock.TeleportBlockMod;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public class TeleportBlockPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return TeleportBlockMod.MODID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(ResourceLocation.fromNamespaceAndPath("teleportblock", "teleport_block"))
                .addStoryBoard("teleport_block/linking", TeleportBlockPonderScenes::linking)
                .addStoryBoard("teleport_block/teleporting", TeleportBlockPonderScenes::teleporting);
    }
}