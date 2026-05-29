package com.example.teleportblock.compat.jade;

import com.example.teleportblock.block.TeleportBlock;
import com.example.teleportblock.block.entity.TeleportBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public class TeleportBlockJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration reg) {
        reg.registerBlockDataProvider(TeleportBlockJadeProvider.INSTANCE, TeleportBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration reg) {
        reg.registerBlockComponent(TeleportBlockJadeProvider.INSTANCE, TeleportBlock.class);
    }
}