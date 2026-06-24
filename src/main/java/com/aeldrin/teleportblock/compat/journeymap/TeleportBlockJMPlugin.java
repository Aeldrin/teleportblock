package com.aeldrin.teleportblock.compat.journeymap;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;

@JourneyMapPlugin(apiVersion = "2.0.0")
public class TeleportBlockJMPlugin implements IClientPlugin {

    @Override
    public void initialize(IClientAPI api) {
        JourneyMapCompat.setApi(api);
    }

    @Override
    public String getModId() {
        return "teleportblock";
    }
}