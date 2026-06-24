package com.aeldrin.teleportblock;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMapDecorations {
    public static final DeferredRegister<MapDecorationType> MAP_DECORATION_TYPES =
            DeferredRegister.create(Registries.MAP_DECORATION_TYPE, TeleportBlockMod.MODID);

    public static final DeferredHolder<MapDecorationType, MapDecorationType> TELEPORT_BLOCK =
            MAP_DECORATION_TYPES.register("teleport_block", () -> new MapDecorationType(
                    ResourceLocation.fromNamespaceAndPath(TeleportBlockMod.MODID, "teleport_block"),
                    true,   // showOnItemFrame
                    0x7B41E0, // mapColor (purple tint for the pixel on zoomed-out maps)
                    false,  // explorationMapElement
                    false   // trackCount
            ));
}
