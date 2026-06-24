package com.aeldrin.teleportblock;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModStats {
    public static final ResourceLocation TELEPORTATIONS = ResourceLocation.fromNamespaceAndPath(
            TeleportBlockMod.MODID, "teleportations");

    public static final DeferredRegister<ResourceLocation> CUSTOM_STATS =
            DeferredRegister.create(Registries.CUSTOM_STAT, TeleportBlockMod.MODID);

    static {
        CUSTOM_STATS.register("teleportations", () -> TELEPORTATIONS);
    }

    // Вызвать из FMLCommonSetupEvent
    public static void init() {
        Stats.CUSTOM.get(TELEPORTATIONS, StatFormatter.DEFAULT);
    }
}