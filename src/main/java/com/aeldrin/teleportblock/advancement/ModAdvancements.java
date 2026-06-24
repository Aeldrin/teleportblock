package com.aeldrin.teleportblock.advancement;

import com.aeldrin.teleportblock.TeleportBlockMod;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModAdvancements {
    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, TeleportBlockMod.MODID);

    public static final DeferredHolder<CriterionTrigger<?>, TeleportTrigger> TELEPORT_TRIGGER =
            TRIGGERS.register("teleport", () -> TeleportTrigger.INSTANCE);

    public static final DeferredHolder<CriterionTrigger<?>, LinkTrigger> LINK_TRIGGER =
            TRIGGERS.register("link", () -> LinkTrigger.INSTANCE);

    public static void register(IEventBus modEventBus) {
        TRIGGERS.register(modEventBus);
    }
}