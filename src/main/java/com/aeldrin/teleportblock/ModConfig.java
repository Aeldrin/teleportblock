package com.aeldrin.teleportblock;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ModConfig {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MAX_LINK_DISTANCE;
    public static final ModConfigSpec.IntValue COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue PEARL_COOLDOWN_SECONDS;

    static {
        BUILDER.push("teleportblock");

        MAX_LINK_DISTANCE = BUILDER
                .comment("Maximum distance between two linked blocks (in blocks)")
                .defineInRange("max_link_distance", 1024, 1, 100000);

        COOLDOWN_SECONDS = BUILDER
                .comment("Cooldown in seconds between teleportations (per link pair)")
                .defineInRange("cooldown_seconds", 2, 0, 3600);

        PEARL_COOLDOWN_SECONDS = BUILDER
                .comment("Cooldown in seconds for ender pearl teleportation through a linked block")
                .defineInRange("pearl_cooldown_seconds", 1, 0, 3600);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
